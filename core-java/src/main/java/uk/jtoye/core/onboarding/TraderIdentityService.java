package uk.jtoye.core.onboarding;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.common.CurrentTenant;
import uk.jtoye.core.onboarding.dto.TraderIdentityDto;
import uk.jtoye.core.onboarding.dto.UpdateTraderIdentityRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped read and upsert of the tenant's legal entity (#789, D-10/D-11).
 *
 * <p>Invariants:
 * <ul>
 *   <li>the tenant is ALWAYS resolved server-side ({@link CurrentTenant#require()}), never read
 *       from the request (the {@link VendorOnboardingService} rule);</li>
 *   <li>only a tenant-wide GROUP_ADMIN may change the published identity
 *       ({@link ShopAccessService#requireGroupAdmin()}, the same service-boundary gate as staff
 *       and webhook management); any tenant user may read it;</li>
 *   <li>the write is an UPSERT keyed by tenant, so PUT is idempotent by construction: the same
 *       body twice leaves one row with the same values (its version and {@code updated_at}
 *       advance, which is the audit trail recording a second save, not a second identity);</li>
 *   <li>the company number is read from {@link VendorOnboarding}, never copied.</li>
 * </ul>
 */
@Service
@Transactional
public class TraderIdentityService {

    /** The 404 detail when no identity is on file. Names the missing record, not a missing route. */
    public static final String NO_IDENTITY_DETAIL = "No trader identity is on file for this tenant";

    private static final Logger log = LoggerFactory.getLogger(TraderIdentityService.class);

    private final TraderIdentityRepository repository;
    private final VendorOnboardingRepository onboardingRepository;
    private final ShopAccessService shopAccessService;
    private final EntityManager entityManager;

    /**
     * The public seller read's own transaction (31.1-24): REQUIRES_NEW and read-only. A new
     * transaction, never a joined one, because the tenant GUC it pins is transaction-local
     * ({@code set_config(..., true)}): joined to the caller's public read, the pin would outlive
     * this method and the rest of that request would run as the shop's tenant.
     */
    private final TransactionTemplate publicSellerTx;

    public TraderIdentityService(TraderIdentityRepository repository,
                                 VendorOnboardingRepository onboardingRepository,
                                 ShopAccessService shopAccessService,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.onboardingRepository = onboardingRepository;
        this.shopAccessService = shopAccessService;
        this.entityManager = entityManager;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        this.publicSellerTx = tx;
    }

    /** The caller tenant's identity, or empty when none is on file. */
    @Transactional(readOnly = true)
    public Optional<TraderIdentityDto> get() {
        UUID tenantId = CurrentTenant.require();
        return repository.findByTenantId(tenantId).map(identity -> toDto(identity, tenantId));
    }

    /**
     * Create or replace the caller tenant's identity. GROUP_ADMIN only. Fields arrive validated
     * and stripped ({@link UpdateTraderIdentityRequest}); the postcode and VAT number are put in
     * canonical form here, so what is stored is what customers see.
     *
     * <p>A first save racing a second first save for the same tenant hits
     * {@code UNIQUE(tenant_id)}; {@code saveAndFlush} surfaces it inside the request as the
     * existing 409, and a retry then takes the update branch.
     */
    public TraderIdentityDto upsert(UpdateTraderIdentityRequest request) {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = CurrentTenant.require();

        TraderIdentity identity = repository.findByTenantId(tenantId).orElseGet(() -> {
            TraderIdentity created = new TraderIdentity();
            created.setTenantId(tenantId);
            return created;
        });
        boolean creating = identity.getId() == null;

        identity.setLegalName(TraderIdentityFields.strip(request.getLegalName()));
        identity.setEntityType(request.getEntityType());
        identity.setAddressLine1(TraderIdentityFields.strip(request.getAddressLine1()));
        identity.setAddressLine2(TraderIdentityFields.stripToNull(request.getAddressLine2()));
        identity.setAddressCity(TraderIdentityFields.strip(request.getAddressCity()));
        identity.setAddressPostcode(TraderIdentityFields.normalisePostcode(request.getAddressPostcode()));
        identity.setVatNumber(TraderIdentityFields.normaliseVatNumber(request.getVatNumber()));
        // Always advanced, so every save is a revision even when the values are unchanged: the
        // audit trail then records that an admin re-affirmed the published identity.
        identity.setUpdatedAt(OffsetDateTime.now());

        identity = repository.saveAndFlush(identity);
        log.info("{} trader identity {} for tenant {}", creating ? "Created" : "Updated", identity.getId(), tenantId);
        return toDto(identity, tenantId);
    }

    /**
     * The identity of an EXPLICIT tenant, for callers outside a request (the customer-facing
     * seller block, 31.1-24, and order emails, 31.1-25). Under FORCE RLS a lookup only sees the
     * tenant whose GUC is pinned, so the caller must have pinned {@code tenantId} in
     * {@link TenantContext} first. A missing or different pin is refused loudly rather than
     * answered with a silent empty result, which would read as "this trader has no identity".
     */
    @Transactional(readOnly = true)
    public Optional<TraderIdentityDto> findForTenant(UUID tenantId) {
        UUID pinned = TenantContext.get().orElse(null);
        if (!tenantId.equals(pinned)) {
            throw new IllegalStateException(
                    "findForTenant requires the tenant to be pinned in TenantContext first (row-level "
                            + "security would otherwise hide the row)");
        }
        return repository.findByTenantId(tenantId).map(identity -> toDto(identity, tenantId));
    }

    /**
     * The seller block for a PUBLISHED shop, read on an anonymous public request (#789, 31.1-24,
     * D-10/D-11/D-20): the tenant's legal entity, the company number when it is a company, and the
     * shop's own email and phone. {@code null} when the tenant has no identity on file — never an
     * invented or partly blank seller.
     *
     * <p><b>How a request with no tenant reads one tenant's row, and nothing more
     * (T-31.1-81/-82).</b> {@code trader_identity} and {@code vendor_onboarding} are FORCE RLS
     * tenant-scoped, and a public request has no tenant. So:
     * <ol>
     *   <li>the read runs in its OWN transaction ({@link #publicSellerTx}, REQUIRES_NEW), and the
     *       SHOP's tenant is pinned on that transaction's connection with
     *       {@code set_config('app.current_tenant_id', ?, true)} — the {@code DsarFanoutWorker}
     *       idiom. The pin dies with that transaction;</li>
     *   <li>{@link TenantContext} is NEVER set: this runs on the request thread, and a context left
     *       behind there would turn the rest of the request into a tenant request;</li>
     *   <li>every query also carries an explicit {@code tenantId} predicate, so a wrong pin returns
     *       nothing rather than another tenant's row;</li>
     *   <li>the queries go through the {@link EntityManager}, not a Spring Data repository:
     *       {@code TenantSetLocalAspect} advises every repository call and, with an empty
     *       {@link TenantContext}, RESETS the GUC first — it would silently undo the pin and the read
     *       would return no seller.</li>
     * </ol>
     *
     * <p>The method itself is {@code NOT_SUPPORTED} so the class-level {@code @Transactional}
     * does not open a second, useless transaction around the dedicated one.
     *
     * @param tenantId the shop's tenant; must equal {@code shop.getTenantId()}
     * @param shop     the published shop whose contact details complete the block
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SellerIdentityDto findPublicSeller(UUID tenantId, Shop shop) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(shop, "shop");
        if (!tenantId.equals(shop.getTenantId())) {
            throw new IllegalArgumentException("findPublicSeller: the tenant must be the shop's own tenant");
        }
        return publicSellerTx.execute(status -> {
            pinTenantGuc(tenantId);
            List<TraderIdentity> found = entityManager.createQuery(
                            "SELECT t FROM TraderIdentity t WHERE t.tenantId = :tenantId", TraderIdentity.class)
                    .setParameter("tenantId", tenantId)
                    .setMaxResults(1)
                    .getResultList();
            if (found.isEmpty()) {
                return null;
            }
            TraderIdentity identity = found.get(0);
            String companyNumber = null;
            if (identity.getEntityType() == TraderEntityType.COMPANY) {
                companyNumber = entityManager.createQuery(
                                "SELECT o.companyNumber FROM VendorOnboarding o WHERE o.tenantId = :tenantId",
                                String.class)
                        .setParameter("tenantId", tenantId)
                        .setMaxResults(1)
                        .getResultList()
                        .stream().findFirst()
                        .map(TraderIdentityFields::stripToNull)
                        .orElse(null);
            }
            List<String> addressLines = new ArrayList<>(4);
            addIfPresent(addressLines, identity.getAddressLine1());
            addIfPresent(addressLines, identity.getAddressLine2());
            addIfPresent(addressLines, identity.getAddressCity());
            addIfPresent(addressLines, identity.getAddressPostcode());
            return new SellerIdentityDto(
                    identity.getLegalName(),
                    identity.getEntityType(),
                    companyNumber,
                    TraderIdentityFields.stripToNull(identity.getVatNumber()),
                    addressLines,
                    TraderIdentityFields.stripToNull(shop.getEmail()),
                    TraderIdentityFields.stripToNull(shop.getPhone()));
        });
    }

    private static void addIfPresent(List<String> lines, String value) {
        String stripped = TraderIdentityFields.stripToNull(value);
        if (stripped != null) {
            lines.add(stripped);
        }
    }

    /** Transaction-local tenant pin on the CURRENT transaction's connection (DsarFanoutWorker idiom). */
    private void pinTenantGuc(UUID tenantId) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }

    private TraderIdentityDto toDto(TraderIdentity identity, UUID tenantId) {
        String companyNumber = onboardingRepository.findByTenantId(tenantId)
                .map(VendorOnboarding::getCompanyNumber)
                .orElse(null);
        return new TraderIdentityDto(
                identity.getId(),
                identity.getLegalName(),
                identity.getEntityType(),
                identity.getAddressLine1(),
                identity.getAddressLine2(),
                identity.getAddressCity(),
                identity.getAddressPostcode(),
                identity.getVatNumber(),
                companyNumber,
                identity.getVersion(),
                identity.getUpdatedAt());
    }
}

package uk.jtoye.core.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.common.CurrentTenant;
import uk.jtoye.core.onboarding.dto.TraderIdentityDto;
import uk.jtoye.core.onboarding.dto.UpdateTraderIdentityRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;

import java.time.OffsetDateTime;
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

    public TraderIdentityService(TraderIdentityRepository repository,
                                 VendorOnboardingRepository onboardingRepository,
                                 ShopAccessService shopAccessService) {
        this.repository = repository;
        this.onboardingRepository = onboardingRepository;
        this.shopAccessService = shopAccessService;
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

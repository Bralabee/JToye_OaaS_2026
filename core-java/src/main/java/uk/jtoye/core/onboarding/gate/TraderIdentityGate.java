package uk.jtoye.core.onboarding.gate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.OnboardingGate;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.TraderEntityType;
import uk.jtoye.core.onboarding.TraderIdentityService;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.onboarding.dto.TraderIdentityDto;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code TRADER_IDENTITY} gate (#789, D-10/D-12/D-20): a shop may not go live until its
 * customers can see who they are buying from and how to email them — Consumer Contracts
 * Regulations 2013 Sch 2 and Electronic Commerce Regulations 2002 reg 6. Automatic and
 * mandatory for every model; registering it as a {@code @Component} plugs it into
 * {@code GateChainRunner} (materialised at submit). The GO_LIVE guard additionally requires
 * this row to be PASSED, so a human WAIVE never substitutes for the statutory details, and
 * {@code VendorOnboardingService} materialises-or-refreshes the row at GO_LIVE/REINSTATE so
 * an onboarding submitted before this gate existed is never passed on the row's absence.
 *
 * <p>Passes only when:
 * <ul>
 *   <li>the tenant's {@code trader_identity} exists with a legal name and a geographic address
 *       (line 1, town or city, postcode) — D-10, tenant level per D-11;</li>
 *   <li>for {@link TraderEntityType#COMPANY}, the onboarding carries a company number (it is
 *       read from {@code vendor_onboarding}, the single copy);</li>
 *   <li>the onboarding's shop has an email address. A phone is optional (D-20 amends D-10's
 *       "phone or email": reg 6(1)(c) requires an email address).</li>
 * </ul>
 *
 * <p>The evidence records WHICH details were present, never their values: the gate row is a
 * compliance record, not a second copy of the legal entity or of a contact address.
 */
@Component
public class TraderIdentityGate implements OnboardingGate {

    static final String NO_SHOP_REASON =
            "No shop is attached to this onboarding, so there is no shop to show seller details on.";
    static final String NO_IDENTITY_REASON =
            "Add your business details (legal name and address) — customers must see who they are buying from.";
    static final String INCOMPLETE_IDENTITY_REASON =
            "Complete your business details: a legal name and a full address (first line, town or city, "
                    + "postcode) — customers must see who they are buying from.";
    static final String NO_COMPANY_NUMBER_REASON =
            "Add your company number — a company must show it to customers before they order.";
    static final String NO_SHOP_EMAIL_REASON =
            "Add an email address to your shop — customers must be able to contact you.";

    private final TraderIdentityService traderIdentityService;
    private final ShopRepository shopRepository;

    public TraderIdentityGate(TraderIdentityService traderIdentityService, ShopRepository shopRepository) {
        this.traderIdentityService = traderIdentityService;
        this.shopRepository = shopRepository;
    }

    @Override
    public GateType type() {
        return GateType.TRADER_IDENTITY;
    }

    @Override
    public boolean isAutomatic() {
        return true;
    }

    @Override
    public boolean mandatory(OnboardingModel model) {
        // Statutory for every commercial model: a WHITE_LABEL storefront sells to consumers too.
        return true;
    }

    /**
     * Callers pin the onboarding's tenant first (the async gate chain and the go-live request
     * both do); {@link TraderIdentityService#findForTenant} refuses an unpinned tenant rather
     * than return an RLS-empty result that would read as "no identity".
     */
    @Override
    @Transactional(readOnly = true)
    public GateResult evaluate(VendorOnboarding onboarding) {
        UUID tenantId = onboarding.getTenantId();
        UUID shopId = onboarding.getShopId();
        if (shopId == null) {
            return GateResult.failed(NO_SHOP_REASON);
        }

        Optional<TraderIdentityDto> found = traderIdentityService.findForTenant(tenantId);
        if (found.isEmpty()) {
            return GateResult.failed(NO_IDENTITY_REASON);
        }
        TraderIdentityDto identity = found.get();
        if (isBlank(identity.legalName()) || !hasGeographicAddress(identity)) {
            return GateResult.failed(INCOMPLETE_IDENTITY_REASON);
        }

        boolean company = identity.entityType() == TraderEntityType.COMPANY;
        boolean hasCompanyNumber = !isBlank(onboarding.getCompanyNumber());
        if (company && !hasCompanyNumber) {
            return GateResult.failed(NO_COMPANY_NUMBER_REASON);
        }

        // Tenant-scoped finder (the CR-02 one), never the RLS-only findById, whose
        // shops_public_read policy could read another tenant's published shop.
        Optional<Shop> shop = shopRepository.findByIdAndTenantId(shopId, tenantId);
        if (shop.isEmpty()) {
            return GateResult.failed(NO_SHOP_REASON);
        }
        if (isBlank(shop.get().getEmail())) {
            return GateResult.failed(NO_SHOP_EMAIL_REASON);
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("legal_name", true);
        evidence.put("geographic_address", true);
        evidence.put("entity_type", identity.entityType().name());
        evidence.put("company_number", hasCompanyNumber ? "present" : "not_required");
        evidence.put("vat_number", !isBlank(identity.vatNumber()));
        evidence.put("shop_email", true);
        evidence.put("shop_phone", !isBlank(shop.get().getPhone()));
        return GateResult.passed(evidence, null);
    }

    private static boolean hasGeographicAddress(TraderIdentityDto identity) {
        return !isBlank(identity.addressLine1())
                && !isBlank(identity.addressCity())
                && !isBlank(identity.addressPostcode());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

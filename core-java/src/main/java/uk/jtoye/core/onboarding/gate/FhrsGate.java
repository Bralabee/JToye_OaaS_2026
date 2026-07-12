package uk.jtoye.core.onboarding.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.OnboardingGate;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.OnboardingProperties;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.onboarding.client.FhrsClient;
import uk.jtoye.core.onboarding.client.FhrsEstablishment;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code FOOD_HYGIENE_RATING} gate — the industry's single hard vetting rule
 * (VENDOR_ONBOARDING_STATE_MODEL.md §3.1, §5.4). Looks the shop up on the FSA FHRS
 * API via {@link FhrsClient} and maps the result against the config-driven
 * {@code onboarding.fhrs.min-rating} (default 2, Deliveroo/Uber parity):
 *
 * <ul>
 *   <li>exactly one FHRS match, numeric rating &gt;= min-rating &rarr; PASSED;</li>
 *   <li>exactly one FHRS match, numeric rating &lt; min-rating &rarr; FAILED;</li>
 *   <li>exactly one Scotland FHIS match with {@code RatingValue == "Pass"} &rarr; PASSED;</li>
 *   <li>zero / ambiguous multi-match / unparseable rating / a non-pass FHIS word /
 *       a client failure (5xx, open circuit, timeout) &rarr; MANUAL_REVIEW.</li>
 * </ul>
 *
 * <p><strong>Never hard-fails on ambiguity or an outage</strong> — a fuzzy name /
 * address match or an FSA blip degrades to a human decision, so a legitimate
 * vendor is never auto-rejected by a lookup miss (design §5.4). Registering this
 * as a {@link Component} auto-plugs it into the {@code GateChainRunner}
 * {@code List<OnboardingGate>} registry with no runner edit.
 */
@Component
public class FhrsGate implements OnboardingGate {

    private static final Logger log = LoggerFactory.getLogger(FhrsGate.class);
    private static final String SCHEME_FHIS = "FHIS";
    private static final String FHIS_PASS = "Pass";

    private final FhrsClient fhrsClient;
    private final OnboardingProperties properties;
    private final ShopRepository shopRepository;

    public FhrsGate(FhrsClient fhrsClient, OnboardingProperties properties, ShopRepository shopRepository) {
        this.fhrsClient = fhrsClient;
        this.properties = properties;
        this.shopRepository = shopRepository;
    }

    @Override
    public GateType type() {
        return GateType.FOOD_HYGIENE_RATING;
    }

    @Override
    public boolean isAutomatic() {
        return true;
    }

    @Override
    public boolean mandatory(OnboardingModel model) {
        // Mandatory for BOTH commercial models this slice (state model §3.1); the
        // model parameter exists so slice-2 model-specific gates fit (IN-09).
        return true;
    }

    @Override
    public GateResult evaluate(VendorOnboarding onboarding) {
        UUID shopId = onboarding.getShopId();
        if (shopId == null) {
            return GateResult.manualReview("No shop linked to onboarding; cannot look up hygiene rating");
        }
        // CR-02 (defence-in-depth): resolve the shop with an EXPLICIT tenant filter,
        // not the RLS-only findById. The shops_public_read policy (V16) OR-permits
        // published=true rows cross-tenant, so a plain findById could otherwise read a
        // foreign published shop and store hygiene evidence against someone else's FSA
        // establishment. findByIdAndTenantId scopes the read to the onboarding's own
        // tenant (async worker re-establishes TenantContext -> app.current_tenant_id GUC).
        Shop shop = shopRepository.findByIdAndTenantId(shopId, onboarding.getTenantId()).orElse(null);
        if (shop == null) {
            return GateResult.manualReview("Shop " + shopId + " not found for hygiene lookup");
        }

        List<FhrsEstablishment> matches;
        try {
            matches = fhrsClient.lookup(shop.getName(), shop.getAddress());
        } catch (RuntimeException e) {
            // 5xx / open circuit / timeout — degrade to a human decision, never a silent pass.
            log.warn("FHRS lookup failed for shop {} — degrading to MANUAL_REVIEW: {}", shopId, e.getMessage());
            // IN-05: fixed, human-readable reason — raw exception text (upstream URL,
            // status, breaker name) stays in the WARN log above, never on the
            // vendor-visible gate row.
            return GateResult.manualReview(
                    "Food hygiene service temporarily unavailable — a reviewer will check this manually");
        }

        if (matches == null || matches.isEmpty()) {
            return GateResult.manualReview("No FSA establishment matched the shop name/address");
        }
        if (matches.size() > 1) {
            return GateResult.manualReview(
                    "Ambiguous FSA match (" + matches.size() + " establishments); needs a human decision");
        }

        FhrsEstablishment est = matches.get(0);

        // Scotland FHIS is a Pass/Improvement scheme, not numeric.
        if (SCHEME_FHIS.equalsIgnoreCase(est.schemeType())) {
            if (FHIS_PASS.equalsIgnoreCase(est.ratingValue())) {
                return GateResult.passed(evidence(est.ratingValue(), est), est.establishmentId());
            }
            return GateResult.manualReview(
                    "FHIS rating '" + est.ratingValue() + "' is not a Pass; needs a human decision");
        }

        // FHRS (England / Wales / Northern Ireland) — numeric 0..5.
        Integer rating = parseRating(est.ratingValue());
        if (rating == null) {
            return GateResult.manualReview("Unparseable FHRS rating '" + est.ratingValue() + "'");
        }
        int minRating = properties.getFhrs().getMinRating();
        if (rating >= minRating) {
            return GateResult.passed(evidence(rating, est), est.establishmentId());
        }
        return GateResult.failed(
                "FHRS rating " + rating + " is below the required minimum of " + minRating);
    }

    private Map<String, Object> evidence(Object ratingValue, FhrsEstablishment est) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("fhrs_rating", ratingValue);
        evidence.put("establishment_id", est.establishmentId());
        evidence.put("scheme", est.schemeType());
        return evidence;
    }

    private Integer parseRating(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

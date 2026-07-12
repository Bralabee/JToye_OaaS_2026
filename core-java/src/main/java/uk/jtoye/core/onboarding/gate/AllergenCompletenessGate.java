package uk.jtoye.core.onboarding.gate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.OnboardingGate;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code ALLERGEN_DATA_COMPLETE} gate — the legal "before publish" allergen check
 * (Natasha's Law / Food Information Regulations 2014). A distance-selling
 * storefront must not go live until every product it sells carries the data a
 * compliant PPDS label requires; this automatic, mandatory gate blocks GO_LIVE
 * until that holds. Registering it as a {@code @Component} auto-plugs it into the
 * 18-02 {@code GateChainRunner} registry — no runner edit needed.
 *
 * <p><strong>Alignment (no duplicate/contradictory rule):</strong> the completeness
 * predicate mirrors {@code ProductLabelService.validatePpdsData} — the authoritative
 * label code. That method returns HTTP 422 for a product missing {@code shelfLifeDays}
 * or {@code durabilityType}, and the renderer derives the emphasised allergen runs
 * by RE-PARSING {@code ingredientsText} at print time (it deliberately does NOT trust
 * the stored {@code allergen_spans} cache, and an allergen-free product with an empty
 * span set is legitimately compliant). This gate therefore reads the SAME V41 product
 * fields — {@code durabilityType}, {@code shelfLifeDays}, and a present
 * {@code ingredientsText} (from which the spans are derivable) — and does not invent a
 * "must declare at least one allergen" rule the label code never enforces.
 */
@Component
public class AllergenCompletenessGate implements OnboardingGate {

    /** Cap the offenders named in a FAILED reason so the message stays bounded. */
    private static final int MAX_NAMED_OFFENDERS = 10;

    private final ProductRepository productRepository;

    public AllergenCompletenessGate(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public GateType type() {
        return GateType.ALLERGEN_DATA_COMPLETE;
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
    @Transactional(readOnly = true)
    public GateResult evaluate(VendorOnboarding onboarding) {
        UUID shopId = onboarding.getShopId();
        if (shopId == null) {
            return GateResult.failed(
                    "No shop is attached to this onboarding, so no products can be checked for allergen completeness.");
        }

        List<Product> products = productRepository.findByShopId(shopId);
        if (products.isEmpty()) {
            return GateResult.failed(
                    "The shop has no products; add at least one fully-labelled product before going live.");
        }

        List<String> offenders = products.stream()
                .filter(product -> !isAllergenComplete(product))
                .map(this::identify)
                .toList();

        if (offenders.isEmpty()) {
            return GateResult.passed(Map.<String, Object>of("products_checked", products.size()), null);
        }

        return GateResult.failed(buildReason(offenders));
    }

    /**
     * A product is allergen-complete when it carries the product-level data a
     * compliant PPDS label requires — mirrors {@code ProductLabelService.validatePpdsData}:
     * a durability type, a shelf life, and a present ingredients list (from which
     * the label re-parses the emphasised allergen runs at print time). An empty
     * allergen set is compliant for allergen-free food, exactly as the label
     * renderer allows — so this gate does not require a non-empty span set.
     */
    private boolean isAllergenComplete(Product product) {
        return product.getDurabilityType() != null && !product.getDurabilityType().isBlank()
                && product.getShelfLifeDays() != null
                && product.getIngredientsText() != null && !product.getIngredientsText().isBlank();
    }

    /** Prefer the SKU (vendor-facing) to name an offender; fall back to the id. */
    private String identify(Product product) {
        String sku = product.getSku();
        if (sku != null && !sku.isBlank()) {
            return sku;
        }
        return String.valueOf(product.getId());
    }

    private String buildReason(List<String> offenders) {
        int total = offenders.size();
        List<String> named = total > MAX_NAMED_OFFENDERS ? offenders.subList(0, MAX_NAMED_OFFENDERS) : offenders;
        String overflow = total > named.size() ? " (+" + (total - named.size()) + " more)" : "";
        return total + " product(s) are missing required allergen/durability data: "
                + String.join(", ", named) + overflow
                + ". Each product needs a durability type, a shelf life, and an ingredients list.";
    }
}

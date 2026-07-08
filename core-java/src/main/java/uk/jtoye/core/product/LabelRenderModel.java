package uk.jtoye.core.product;

import java.util.List;

/**
 * Pure, Jackson-serializable render data for a PPDS (Natasha's Law) allergen
 * label. Produced by {@code ProductLabelService.buildRenderModel} from a
 * {@link Product} + owning {@code Shop} + an injectable generation date, and
 * consumed by the thin OpenPDF renderer. Being a pure record with no I/O, it is
 * both unit-testable and golden-serializable (AC3).
 *
 * @param productName     the food name (FSA: name of the food)
 * @param sku             the product SKU
 * @param pricePennies    price in pennies, or {@code null} if unpriced
 * @param ingredientRuns  ordered runs covering the whole plain ingredients text;
 *                        emphasised runs are the marked allergens rendered INLINE
 *                        in bold (FSA: allergens emphasised within the list) — there
 *                        is NO standalone allergen-summary block
 * @param durabilityLine  the computed durability line, e.g. "Use by: 8 Jul 2026"
 *                        or "Best before: 8 Jul 2026" (FSA: durability date)
 * @param businessName    the food business name (FSA: name + address of the FBO)
 * @param businessAddress the food business address
 */
public record LabelRenderModel(
        String productName,
        String sku,
        Long pricePennies,
        List<IngredientRun> ingredientRuns,
        String durabilityLine,
        String businessName,
        String businessAddress) {

    /**
     * One run of the ingredients text. {@code emphasised == true} marks an allergen
     * to be rendered in bold inline within the flowing ingredients paragraph.
     */
    public record IngredientRun(String text, boolean emphasised) {
    }
}

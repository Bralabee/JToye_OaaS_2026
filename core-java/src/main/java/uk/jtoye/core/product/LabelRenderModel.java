package uk.jtoye.core.product;

import java.time.LocalDate;
import java.util.List;

/**
 * Pure, Jackson-serializable render data for a PPDS (Natasha's Law) allergen
 * label. Produced by {@code ProductLabelService.buildRenderModel} from a
 * {@link Product} + owning {@code Shop} + an injectable production date, and
 * consumed by the thin OpenPDF renderer. Being a pure record with no I/O, it is
 * both unit-testable and golden-serializable (AC3).
 *
 * <p>Every date on the label is rendered en-GB with the full month name, for
 * example "5 October 2026" (#861, D-17).
 *
 * @param productName     the food name (FSA: name of the food)
 * @param sku             the product SKU
 * @param pricePennies    price in pennies, or {@code null} if unpriced
 * @param ingredientRuns  ordered runs covering the whole plain ingredients text;
 *                        emphasised runs are the marked allergens rendered INLINE
 *                        in bold (FSA: allergens emphasised within the list) — there
 *                        is NO standalone allergen-summary block
 * @param mayContainNames #861 (D-16): the "may contain" (cross-contact) allergens that are
 *                        NOT already declared, in {@code AllergenCatalog} bit order; empty
 *                        (never null) when none remain. Never merged into the declared set
 * @param mayContainLine  the printed "May contain: …" line, or {@code null} when
 *                        {@code mayContainNames} is empty (no line is printed)
 * @param productionDate  #861 (D-17): the date the food was produced, as given at download,
 *                        or today in Europe/London when none was given
 * @param productionLine  the printed production line, e.g. "Produced: 5 July 2026"
 * @param durabilityLine  the computed durability line, productionDate + shelf life, e.g.
 *                        "Use by: 8 July 2026" or "Best before: 8 July 2026" (FSA: durability date)
 * @param businessName    the food business name (FSA: name + address of the FBO)
 * @param businessAddress the food business address
 */
public record LabelRenderModel(
        String productName,
        String sku,
        Long pricePennies,
        List<IngredientRun> ingredientRuns,
        List<String> mayContainNames,
        String mayContainLine,
        LocalDate productionDate,
        String productionLine,
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

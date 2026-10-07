package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A save-time and read-time warning on a product: its ingredients text emphasises an
 * allergen that its declared allergen mask does not include (#787, D-09).
 *
 * <p>Advisory, never authoritative. The product save that produced it SUCCEEDED, and the
 * vendor's declared {@code allergenMask} is stored exactly as sent: the platform never ticks
 * an allergen on the vendor's behalf, because a text heuristic must not author a legally
 * operative allergen statement. The warning is derived from the product's current title,
 * mask and ingredients on every read and is never stored, so it disappears the moment the
 * vendor ticks the allergen or corrects the text.
 *
 * @param code        always {@link #UNDECLARED_INGREDIENT_ALLERGEN} for this warning
 * @param allergenBit the UK FSA allergen bit (0..13, {@code AllergenCatalog} order)
 * @param allergen    the allergen's name as shown to a human, e.g. {@code Milk}
 * @param message     a sentence for the vendor naming the allergen
 */
@Schema(description = "Advisory warning: the ingredients text emphasises (in CAPITALS or **bold** markup) an "
        + "allergen that the declared allergenMask does not include. The save still succeeds and the "
        + "declared mask is stored as sent; the warning is recomputed on every read.")
public record ProductAllergenWarning(
        @Schema(description = "Stable machine-readable warning code",
                example = UNDECLARED_INGREDIENT_ALLERGEN,
                allowableValues = {UNDECLARED_INGREDIENT_ALLERGEN})
        String code,
        @Schema(description = "UK FSA allergen bit, 0..13", example = "6")
        int allergenBit,
        @Schema(description = "Allergen name", example = "Milk")
        String allergen,
        @Schema(description = "Human-readable explanation naming the allergen",
                example = "The ingredients name Milk, but it is not ticked as an allergen. "
                        + "Tick it, or check the ingredients.")
        String message) {

    /** The ingredients emphasise an allergen the declared mask omits. */
    public static final String UNDECLARED_INGREDIENT_ALLERGEN = "UNDECLARED_INGREDIENT_ALLERGEN";

    /** The {@link #UNDECLARED_INGREDIENT_ALLERGEN} warning for one allergen. */
    public static ProductAllergenWarning undeclaredIngredientAllergen(int allergenBit, String allergen) {
        return new ProductAllergenWarning(
                UNDECLARED_INGREDIENT_ALLERGEN,
                allergenBit,
                allergen,
                "The ingredients name " + allergen + ", but it is not ticked as an allergen. "
                        + "Tick it, or check the ingredients.");
    }
}

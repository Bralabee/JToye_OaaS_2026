package uk.jtoye.core.product;

import java.util.List;

/**
 * #861 (D-16): the one rule for what a "May contain" line says, shared by the PPDS label and the
 * public storefront DTO so the two can never disagree.
 *
 * <p>A "may contain" (cross-contact) statement names the allergens in the product's
 * {@code may_contain_mask} that are NOT already in its declared {@code allergen_mask}: an allergen
 * the food is declared to contain is shown only under the declaration, never repeated as a
 * precaution. Names come back in {@link AllergenCatalog} bit order, whatever order the mask was
 * built in.
 *
 * <p>This is a view, not a write: neither mask is changed, and the stored may-contain mask keeps a
 * bit that is also declared. Nothing here is ever OR-ed into the declared mask.
 */
public final class MayContainAllergens {

    private MayContainAllergens() {
    }

    /**
     * The may-contain allergen names to print or show, in catalogue bit order. Empty (never null)
     * when nothing is recorded ({@code null}), when the vendor recorded none ({@code 0}), or when
     * every may-contain bit is already declared.
     *
     * @param mayContainMask the stored may-contain mask, or {@code null} when not recorded
     * @param declaredMask   the declared allergen mask, or {@code null} (read as none)
     */
    public static List<String> undeclaredNames(Integer mayContainMask, Integer declaredMask) {
        if (mayContainMask == null || mayContainMask == 0) {
            return List.of();
        }
        int declared = declaredMask == null ? 0 : declaredMask;
        return AllergenCatalog.namesFor(mayContainMask & ~declared);
    }
}

package uk.jtoye.core.product;

/**
 * An emphasised (allergen) run within the parsed ingredients plainText.
 *
 * <p>Offsets index into {@code IngredientMarkupParser.ParsedIngredients.plainText}:
 * {@code start} inclusive, {@code end} exclusive, so
 * {@code plainText.substring(start, end)} is the allergen word to embolden.
 *
 * <p>This is the JSON shape persisted in {@code products.allergen_spans} (a
 * render-time cache written on save). The label renderer re-parses
 * {@code ingredients_text} fresh rather than trusting stored offsets, so a later
 * edit to the ingredients text can never leave stale offsets pointing at the
 * wrong characters.
 */
public record AllergenSpan(int start, int end) {
}

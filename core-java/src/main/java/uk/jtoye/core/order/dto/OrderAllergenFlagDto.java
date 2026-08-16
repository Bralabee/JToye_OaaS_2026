package uk.jtoye.core.order.dto;

/**
 * One ADVISORY reconciliation line: {@code productName}'s ingredients text emphasises
 * {@code allergenName}, which its declared allergen mask omits (LGL-03, D-03).
 *
 * <h2>Never authoritative</h2>
 *
 * <p>This is the output of a text heuristic over the vendor's own {@code **markup**}, not an
 * allergen test and not a laboratory result. It is carried BESIDE the declared set and is never
 * merged into it — rendering a flag as if it were a declared allergen would defeat the separation
 * the whole design rests on and would make the platform the author of an allergen statement it
 * cannot stand behind.
 *
 * <p>Both surfaces name both halves, which is why both are on the wire: the checkout panel renders
 * {@code "Check — {productName}: the ingredients list mentions {allergenName} ..."} (UI-SPEC S3)
 * and the kitchen banner renders a {@code "CHECK:"} line naming the item and the allergen
 * (UI-SPEC S4). {@code allergenBit} is the stable identity — the 0..13 UK FSA bit from
 * {@code AllergenCatalog} — so a client can key or filter on it without parsing prose;
 * {@code allergenName} is that bit's label, resolved server-side so the two surfaces cannot
 * disagree about wording.
 */
public record OrderAllergenFlagDto(String productName, int allergenBit, String allergenName) {
}

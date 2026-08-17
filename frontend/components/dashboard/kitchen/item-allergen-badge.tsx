"use client"

import { AlertTriangle } from "lucide-react"

/** How many allergen names fit on an item row before the row stops being glanceable. */
const VISIBLE = 3

/**
 * The per-item allergen badge on a kitchen ticket card (31-15, LGL-03 / D-04,
 * UI-SPEC S4).
 *
 * WHY A PER-ITEM BADGE EXISTS AT ALL. An order-level aggregate tells kitchen staff
 * nothing actionable: knowing the order contains sesame does not tell them which of the
 * four dishes to be careful with. The banner answers "what is in this order", this
 * answers "which dish", and D-04 requires both.
 *
 * THE TRUNCATION IS SAFE ONLY BECAUSE THE BANNER ABOVE IS NOT TRUNCATED. Showing three
 * names then "+N" here is acceptable precisely because `OrderAllergenBanner` on the same
 * card carries the COMPLETE set with no cut-off. That dependency is load-bearing and
 * runs in exactly one direction — a future reader tidying the card must not invert it by
 * truncating the banner "for consistency". If the banner ever truncates, this must stop
 * truncating first.
 *
 * NEVER COLOUR-ONLY: the amber carries nothing on its own, so the badge shows an icon
 * and the allergen names as words.
 *
 * A NOTE ON `null` vs `[]`, which are different statements everywhere else in this
 * chain: both render nothing HERE, and that is not a collapse. The item row is not
 * where the "not recorded" statement is made — the card-level strip says it once, for
 * the whole ticket, and repeating it on every line would bury the lines that DO carry
 * data. Same dependency direction as the truncation above: the badge is an amplifier of
 * the card-level statement and never its sole carrier.
 */
export function ItemAllergenBadge({
  allergenNames,
}: {
  /** This line's write-time allergen snapshot. `null`/absent = not recorded. */
  allergenNames?: string[] | null
}) {
  if (allergenNames == null || allergenNames.length === 0) return null

  const shown = allergenNames.slice(0, VISIBLE)
  const hidden = allergenNames.length - shown.length

  return (
    <span
      data-testid="kds-item-allergen-badge"
      className="inline-flex items-center gap-1 rounded-full border border-amber-700 bg-amber-50 px-2 py-0.5 text-sm font-semibold text-amber-800"
    >
      <AlertTriangle aria-hidden="true" className="h-4 w-4 flex-shrink-0" />
      {shown.join(", ")}
      {hidden > 0 ? ` +${hidden}` : ""}
    </span>
  )
}

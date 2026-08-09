import Link from "next/link"
import { SafeImage } from "@/components/ui/safe-image"
import { formatMiles } from "@/lib/distance"
import type { PublicShop } from "@/types/storefront"

/**
 * One real published shop, rendered as a card in the landing kitchen row (issue 544).
 *
 * NOTE: written "issue 544", not with a leading hash. `__tests__/palette-discipline`
 * greps components/marketing for /#[0-9a-fA-F]{3,8}/ to keep raw hex colours out,
 * and an issue reference matches that pattern — the recorded "a rule that must
 * match a token fires on prose that is not one" shape. It is why dish-scroller.tsx
 * next door says "PR 221" rather than using a hash. The gate is right about hex and
 * is left alone.
 *
 * WHAT THIS REPLACES, AND WHY IT PRINTS LESS
 *
 * The row it replaces rendered five INVENTED vendors — Mama's Kitchen, Spice
 * Route, Olive & Vine, Crumb & Co, Hanoi House — with invented ratings ("⭐ 4.8"),
 * an invented "FHRS 5" badge and invented dish prices. None of the five exists
 * anywhere in the backend; measured 0 hits each across all of `core-java/src/main`
 * (control: the real "Mama Ade" returns 3 files). Meanwhile the seeder creates
 * three REAL published shops the row never showed, one of them *Mama Ade's
 * Kitchen* — of which the fictional *Mama's Kitchen* is a near-duplicate.
 *
 * So this card prints only fields that exist on `PublicShop`:
 *
 *   - rating  REMOVED. There is no rating field. Carrying "⭐ 4.8" onto a real
 *             shop would replace one fiction with a worse one, because it would
 *             now be attributed to a named business that could be harmed by it.
 *   - FHRS    REMOVED. Same reason, and a food-hygiene score is a regulated
 *             claim, not decoration.
 *   - price   REMOVED. It was a DISH price on a SHOP card. The delivery and
 *             minimum-order figures below are real and shop-level.
 */

const LOGO_WIDTH = 220
const LOGO_HEIGHT = 132

function pounds(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/**
 * Distance is printed in MILES, converted from the `distanceKm` the database
 * computed (33-06) by `lib/distance` and never recomputed here — so the figure on
 * the card remains a unit conversion of the exact number the ordering used, not a
 * second opinion about it.
 *
 * Miles because the customer is in the UK, where road distance is signed in
 * miles and every delivery app they already use prints them. Required at 33-07's
 * human-verification gate; the wire format is untouched and still metric. The
 * rounding rule and its ~100 m postcode-centroid justification live with the
 * conversion in `lib/distance.ts`.
 *
 * @see lib/distance.ts
 */

/**
 * `tags` arrives as a single free-text field, not an array. Split, trim and cap
 * so one vendor with a long tag list cannot set the row's card height for
 * everyone — an uneven row is the CLS risk this card exists to avoid.
 */
function firstTags(tags: string | null, limit = 3): string[] {
  if (!tags) return []
  return tags
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, limit)
}

export function ShopCard({ shop }: { shop: PublicShop }) {
  const tags = firstTags(shop.tags)

  return (
    <Link
      href={`/shop/${shop.slug}`}
      // `grow basis-[220px]` with a matching `min-w` is what lets THREE cards
      // fill the container instead of huddling left as if two were missing,
      // while a longer list still overflows and scrolls. Do not swap this for a
      // fixed width to "fix" the three-card case — that is what makes it look
      // broken.
      //
      // Hover is gated on a fine pointer in the written-out form. Tailwind's
      // `future.hoverOnlyWhenSupported` is unset, so a bare `hover:` latches on
      // tap and leaves the card stuck lifted after a finger lifts.
      className="group grow basis-[220px] min-w-[220px] overflow-hidden rounded-xl border border-cream-100 bg-white shadow-sm transition-[box-shadow,transform] duration-200 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.98] [@media(hover:hover)_and_(pointer:fine)]:hover:shadow-md"
    >
      {/* Explicit width + height so the browser reserves the box before the
          logo arrives. Phase 24's D-07 precedent, and the landing route's CLS
          budget in e2e/landing-webperf.spec.ts depends on it — the emoji-to-
          real-image swap is exactly where layout shift enters. */}
      <div className="relative h-28 w-full overflow-hidden bg-cream">
        <SafeImage
          src={shop.logoUrl}
          alt={`${shop.name} logo`}
          width={LOGO_WIDTH}
          height={LOGO_HEIGHT}
          className="h-28 w-full object-cover"
        />
        {/* The distance pill is ABSOLUTELY POSITIONED, and that is a CLS
            decision rather than a styling one. 33-07 swaps this row's contents
            after a permission grant; a distance rendered in the flow would add
            a line to every card and push everything below the row down. Out of
            flow, the located and unlocated cards are byte-identical in height,
            so the upgrade cannot shift the page at all. Switching the unit to
            miles changed the STRING only — the element, its positioning and its
            reserved space are untouched, so the post-grant vertical-shift budget
            in e2e/landing-webperf.spec.ts still holds for the same reason. */}
        {shop.distanceKm != null && (
          <span className="absolute right-2 top-2 rounded-full bg-white/95 px-2 py-0.5 text-xs font-bold text-oxblood shadow-sm ring-1 ring-cream-100">
            {formatMiles(shop.distanceKm)}
            {/* Spelled out rather than abbreviated: a screen reader pronounces a
                two-letter unit as a word, and "point two my away" is not a
                distance. The pill has room for it at 375px. */}
            <span className="sr-only"> away</span>
          </span>
        )}
      </div>

      <div className="p-3.5">
        <div className="font-bold text-slate-900">{shop.name}</div>

        {tags.length > 0 && (
          <div className="mt-0.5 truncate text-xs text-slate-600">{tags.join(" · ")}</div>
        )}

        <div className="mt-2 text-sm font-semibold text-oxblood">
          {shop.deliveryFeePennies === 0 ? "Free delivery" : `${pounds(shop.deliveryFeePennies)} delivery`}
          <span className="font-normal text-slate-600"> · min {pounds(shop.minimumOrderPennies)}</span>
        </div>
      </div>
    </Link>
  )
}

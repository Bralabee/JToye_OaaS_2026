import Link from "next/link"
import { SafeImage } from "@/components/ui/safe-image"
import type { PublicShop } from "@/types/storefront"

/**
 * One real published shop, rendered as a card in the landing kitchen row (#544).
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
      <div className="h-28 w-full overflow-hidden bg-cream">
        <SafeImage
          src={shop.logoUrl}
          alt={`${shop.name} logo`}
          width={LOGO_WIDTH}
          height={LOGO_HEIGHT}
          className="h-28 w-full object-cover"
        />
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

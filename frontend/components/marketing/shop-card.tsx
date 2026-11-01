import { useId } from "react"
import Image from "next/image"
import Link from "next/link"
import { SafeImage } from "@/components/ui/safe-image"
import { assignExamplePhotos, type ExamplePhoto } from "@/components/marketing/landing-photos"
import { formatMiles } from "@/lib/distance"
import { cardBadgeLabel, readShopAvailability } from "@/lib/shop-availability"
import { ONLY_CHIP_COPY, onlyOfferedType } from "@/lib/fulfilment-offered"
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

// The kitchen's own banner, when it has one (D13). Explicit width and height so
// the browser reserves the box before the picture arrives (CLS).
const BANNER_WIDTH = 640
const BANNER_HEIGHT = 352

// next/image `sizes`: 80% of a phone (the card peek, D14), a 260-320px card above.
// At a 390px phone this resolves to the 640w candidate, under 750px.
const CARD_IMAGE_SIZES = "(min-width: 640px) 320px, 80vw"

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

export function ShopCard({
  shop,
  examplePhoto,
}: {
  shop: PublicShop
  /**
   * The credited cuisine picture this card shows when the shop has no banner
   * (D13). `NearYouRow` assigns these across the whole row so two kitchens of
   * one cuisine get two dishes; a card rendered alone works it out for itself.
   * `null` means "no example photo" (no banner and no recognised cuisine).
   */
  examplePhoto?: ExamplePhoto | null
}) {
  const example =
    examplePhoto === undefined ? (assignExamplePhotos([shop]).get(shop.slug) ?? null) : examplePhoto
  const tags = firstTags(shop.tags)
  // 37-22 (UI-SPEC K3, theme 2): an open-state badge is printed ONLY when the server sent its
  // answer (openNow and the pause). This card used to make no openness claim at all, because the
  // only source was the client parser, which said "open" for every shop with no hours. The
  // server's field is backed by the same decision checkout enforces; its absence still says nothing.
  const availability = readShopAvailability(shop)
  const badge = cardBadgeLabel(availability)
  // 37-G (UXT-023): a collection-only shop quotes no delivery fee here. The flags are read through
  // lib/fulfilment-offered.ts like the /shop directory card and every other customer surface.
  const collectionOnly = onlyOfferedType(shop) === "COLLECTION"
  const leadShown = collectionOnly || shop.deliveryFeePennies != null
  // 37-52 (UI-SPEC F2, UXT-082): the name is the shop; the open state and the distance are the
  // description. Ids per render (useId).
  //
  // Issue 1027 (owner ruling 2026-10-22, option 1): the link wraps ONLY the shop name and is named
  // by that content, not by aria-labelledby, so its accessible name contains its visible text
  // (WCAG 2.5.3, axe-core 4.14 label-content-name-mismatch). The whole card stays clickable through
  // the link's `after:` overlay, positioned against the `relative` <article>. `outline-hidden`, not
  // `outline-none`, so forced-colors mode (which drops the overlay's box-shadow ring) still paints one.
  const badgeId = useId()
  const distanceId = useId()
  const describedBy = [badge ? badgeId : null, shop.distanceKm != null ? distanceId : null]
    .filter(Boolean)
    .join(" ")

  return (
    <article
      data-shop-name={shop.name}
      // CARD PEEK (D14, owner ruling 2026-11-01): on a phone each card is 80% of
      // the row, so the next card shows at the edge — that peek is the scroll
      // affordance now, in place of the gradient edge fade. From 640px the old
      // rule returns: `grow` with a matching basis and min-w lets THREE cards
      // fill the container instead of huddling left as if two were missing,
      // while a longer list still overflows and scrolls. Do not swap that for a
      // fixed width — that is what makes it look broken.
      //
      // Flat: a tone step (warm-100 card on the white section), no border and no
      // resting shadow. Hover is gated on a fine pointer in the written-out form,
      // which is narrower than `future.hoverOnlyWhenSupported`.
      className="group relative shrink-0 basis-[80%] overflow-hidden rounded-xl bg-warm-100 transition-[background-color,transform] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.98] sm:shrink sm:grow sm:basis-[260px] sm:min-w-[260px] [@media(hover:hover)_and_(pointer:fine)]:hover:bg-warm-200"
    >
      {/* The picture box is a fixed h-44 whichever picture fills it, so a card
          with a banner, with an example photo and with neither are the same
          height (CLS, and an even row). */}
      <figure className="m-0">
        <div className="relative h-44 w-full overflow-hidden bg-warm-200">
          {shop.bannerUrl ? (
            <SafeImage
              src={shop.bannerUrl}
              alt={`${shop.name} banner`}
              width={BANNER_WIDTH}
              height={BANNER_HEIGHT}
              className="h-44 w-full object-cover"
            />
          ) : example ? (
            <Image
              src={example.photo.src}
              alt={`${example.photo.alt} (example photo)`}
              width={example.photo.width}
              height={example.photo.height}
              sizes={CARD_IMAGE_SIZES}
              className="h-44 w-full object-cover"
            />
          ) : null}
          {/* The badge and the distance pill are ABSOLUTELY POSITIONED, and that
              is a CLS decision rather than a styling one. 33-07 swaps this row's
              contents after a permission grant; a distance rendered in the flow
              would add a line to every card and push everything below the row
              down. Out of flow, the located and unlocated cards are
              byte-identical in height, so the upgrade cannot shift the page. */}
          {badge && (
            <span
              id={badgeId}
              data-testid="shop-availability-badge"
              className={`absolute left-2.5 top-2.5 rounded-full px-2.5 py-0.5 text-sm font-bold ${
                availability.kind === "open"
                  ? "bg-emerald-700 text-white"
                  : availability.kind === "paused"
                    ? "bg-gold-200 text-brand-900"
                    : "bg-warm-700 text-white"
              }`}
            >
              {badge}
            </span>
          )}
          {shop.distanceKm != null && (
            <span id={distanceId} className="absolute right-2.5 top-2.5 rounded-full bg-white px-2.5 py-0.5 text-sm font-bold text-brand-900">
              {formatMiles(shop.distanceKm)}
              {/* Spelled out rather than abbreviated: a screen reader pronounces a
                  two-letter unit as a word, and "point two my away" is not a
                  distance. The pill has room for it at 375px. */}
              <span className="sr-only"> away</span>
            </span>
          )}
        </div>
        {!shop.bannerUrl && example && (
          <figcaption className="px-4 pt-2 text-sm text-warm-700">
            {example.cuisine} — example photo
          </figcaption>
        )}
      </figure>

      <div className="px-4 pb-4 pt-1.5">
        <div className="font-bold text-warm-900">
          <Link
            href={`/shop/${shop.slug}`}
            aria-describedby={describedBy || undefined}
            className="focus-visible:outline-hidden after:absolute after:inset-0 after:rounded-xl after:content-[''] focus-visible:after:ring-2 focus-visible:after:ring-inset focus-visible:after:ring-brand-700"
          >
            {shop.name}
          </Link>
        </div>

        {tags.length > 0 && (
          <div className="mt-0.5 truncate text-sm text-warm-700">{tags.join(" · ")}</div>
        )}

        {/* Review WR-04: both figures are nullable ON THE WIRE (nullable Longs,
            and CreateShopRequest has no delivery-fee field, so an API-created
            shop sends null). null means UNKNOWN, and an unknown fee renders
            NOTHING — not "£0.00 delivery" (null / 100 coerces to 0) and not
            "Free delivery" (a claim that needs a wire 0, not a null). A zero
            minimum is a KNOWN "no minimum" and also renders nothing, matching
            the discovery listing. The separator belongs to the pair, so it
            only prints when the fee line before it exists. */}
        <div className="mt-2 font-semibold text-brand-900">
          {/* 37-G (UXT-023): a shop that does not deliver says "Collection only" (the shop header's
              own words) in place of the fee, with no fall-through to "Free delivery". A shop taking
              both, or a flag-less payload, renders this row exactly as before. */}
          {collectionOnly ? (
            <>{ONLY_CHIP_COPY.COLLECTION}</>
          ) : (
            shop.deliveryFeePennies != null && (
              <>{shop.deliveryFeePennies === 0 ? "Free delivery" : `${pounds(shop.deliveryFeePennies)} delivery`}</>
            )
          )}
          {shop.minimumOrderPennies != null && shop.minimumOrderPennies > 0 && (
            <span className="font-normal text-warm-700">
              {leadShown && " · "}min {pounds(shop.minimumOrderPennies)}
            </span>
          )}
        </div>
      </div>
    </article>
  )
}

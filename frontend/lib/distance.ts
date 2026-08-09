/**
 * Distance, converted from the API's kilometres to the miles a UK customer reads.
 *
 * ── WHY A CONVERSION AND NOT A UNIT CHANGE ───────────────────────────────────
 *
 * The API contract is METRIC and deliberately stays that way: `33-06` ships
 * `radiusKm` as a query parameter, `distanceKm` as a response field, and
 * `jtoye.geo.default-radius-km` / `max-radius-km` as config keys, and the
 * committed OpenAPI snapshot pins all of it. Changing any of those to satisfy a
 * presentation preference would put a unit choice into a machine contract that
 * three other callers read, for the benefit of one surface.
 *
 * So the wire stays kilometres and the CUSTOMER reads miles. The UK is the only
 * place this product ships, road distances here are signed in miles, and every
 * delivery app a J'Toye customer already uses prints miles — "3.0 km" is the kind
 * of small wrongness that makes a page feel like it was built somewhere else.
 * Corrected at the human-verification gate on 2026-08-09: *"Walkthrough was a
 * success"*, with miles required for both the distance and the radius.
 *
 * ── ONE CONSTANT, ONE PLACE, SO A BREAK ARM IS DECISIVE ──────────────────────
 *
 * The conversion lives here and only here, imported by the card that prints a
 * distance and by the row that quotes the radius. Two copies of a magic number
 * are two things to get wrong and only one of them fails a test. Breaking this
 * single constant reds `lib/__tests__/distance.test.ts` AND the two rendered
 * assertions in `near-you-row.test.tsx` — measured, both directions, in 33-07.
 *
 * ── THE NUMBER SHOWN IS STILL THE NUMBER THE ORDERING USED ───────────────────
 *
 * `33-06` computes the distance in SQL and the row renders whatever came back —
 * nothing is recomputed in the browser, before or after this change. What the
 * card prints is now a unit conversion of the ordering key rather than the
 * ordering key itself, which is a strictly weaker claim than "identical" and is
 * stated that way deliberately: a converted number that disagreed with the order
 * would be a defect, and monotonic conversion is what rules that out.
 */

/**
 * Miles per kilometre, to six decimal places (exactly 1 / 1.609344).
 *
 * Six decimals is ~1.6 mm over a kilometre, which is roughly five orders of
 * magnitude finer than the ~100 m postcode-centroid error already in the input.
 * It is written out rather than derived from 1.609344 so there is one literal to
 * read and one to break.
 */
export const MILES_PER_KM = 0.621371

/** Kilometres to miles. No rounding — formatting is the caller's decision. */
export function kmToMiles(km: number): number {
  return km * MILES_PER_KM
}

/**
 * A distance in kilometres, rendered for a customer in miles.
 *
 * One decimal below ten miles, whole miles above. Deliberately NOT yards or
 * feet: shop coordinates are POSTCODE CENTROIDS accurate to about 100 m (`33-02`
 * D-1), so "290 yards" would advertise a precision the data does not have — the
 * same class of invented certainty as the invented star ratings `ShopCard`
 * removed. One decimal of a mile is 161 m, which is COARSER than the underlying
 * error rather than finer, so the figure cannot imply more than it knows.
 */
export function formatMiles(km: number): string {
  const miles = kmToMiles(km)
  return miles < 10 ? `${miles.toFixed(1)} miles` : `${Math.round(miles)} miles`
}

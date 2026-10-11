/**
 * Opening-hours DISPLAY helpers: day order, day names, schema.org day URLs.
 *
 * This module decides nothing (37-22; D-15, D-25, theme 2). It used to carry `isOpenNow`, which
 * re-parsed the vendor's free-text hours on every customer surface, and `parseHoursRange`, which
 * fed the same text into JSON-LD. The server's `ShopAvailabilityPolicy` (37-20) now makes the one
 * open/paused/closed decision in UK time, and every customer surface reads it from the shop payload
 * (`lib/shop-availability.ts`); JSON-LD reads the structured `openingSchedule`
 * (`lib/structured-data.ts`). Two deciders disagree: on the 37-20 server a paused shop with open hours
 * read "Open now" while checkout refused the order, and an unreadable NEEDS_REVIEW day read
 * "Closed" while checkout took it. Do not add a decider back here; `__tests__/opening-hours.test.ts`
 * fails if `isOpenNow` or `parseHoursRange` is exported again.
 */

export const DAY_ORDER = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const

export const DAY_LABELS: Record<string, string> = {
  mon: "Monday",
  tue: "Tuesday",
  wed: "Wednesday",
  thu: "Thursday",
  fri: "Friday",
  sat: "Saturday",
  sun: "Sunday",
}

/** schema.org day URLs, keyed by the API's three-letter key. */
export const SCHEMA_DAYS: Record<string, string> = {
  mon: "https://schema.org/Monday",
  tue: "https://schema.org/Tuesday",
  wed: "https://schema.org/Wednesday",
  thu: "https://schema.org/Thursday",
  fri: "https://schema.org/Friday",
  sat: "https://schema.org/Saturday",
  sun: "https://schema.org/Sunday",
}

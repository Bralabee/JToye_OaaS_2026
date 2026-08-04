/**
 * "Is this shop open right now?" — one implementation, used by the storefront
 * index and by an individual storefront.
 *
 * WHY IT IS SHARED NOW. There were two copies. `app/shop/[slug]/page.tsx`
 * carried the corrected one (WR-05 + WR-06); `app/shop/page.tsx` still carried
 * the original, and the original is wrong in two ways:
 *
 *   1. It round-tripped through a locale string —
 *      `new Date(new Date().toLocaleString("en-GB", …))` — which re-parses a
 *      dd/mm/yyyy string with the mm/dd-first JS `Date` parser. For days 1-12
 *      the day and month silently swap (wrong weekday row); for days 13-31 it
 *      is `Invalid Date`. So the "Closed" pill showed for open shops on most
 *      days of the month.
 *   2. It could not express an overnight window ("18:00 - 02:00"), where the
 *      close time is numerically less than the open time. The backend's
 *      `PublicStorefrontService.validateShopIsOpen` wraps past midnight; this
 *      did not, so a late-night kitchen read "Closed" for its entire service.
 *
 * Both were client-only defects while `/shop` rendered on the client. Making
 * that page server-rendered puts the answer into the crawled HTML and into the
 * `openingHoursSpecification` of its JSON-LD, so shipping the broken copy would
 * have promoted a rendering bug into published structured data.
 *
 * `Intl.DateTimeFormat(...).formatToParts` gives the UK-local weekday and time
 * directly, with no string round-trip, and behaves identically on the server and
 * in the browser — which is what keeps SSR and hydration agreeing.
 */

const DAY_KEYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"] as const

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

const RANGE = /(\d{2}):(\d{2})\s*-\s*(\d{2}):(\d{2})/

/** `"09:00 - 17:30"` -> `{ opens: "09:00", closes: "17:30" }`, or null. */
export function parseHoursRange(
  value: string | undefined | null
): { opens: string; closes: string } | null {
  if (!value || value.toLowerCase().trim() === "closed") return null
  const m = value.match(RANGE)
  if (!m) return null
  return { opens: `${m[1]}:${m[2]}`, closes: `${m[3]}:${m[4]}` }
}

/**
 * Whether the shop is serving at this instant, in Europe/London.
 *
 * No hours at all means always open — deliberately matching the backend, which
 * treats an unset schedule as "no restriction" rather than "never open".
 */
export function isOpenNow(hours: Record<string, string> | null | undefined): boolean {
  if (!hours || Object.keys(hours).length === 0) return true

  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/London",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date())
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? ""

  const dayKey = part("weekday").toLowerCase().slice(0, 3)
  const range = parseHoursRange(hours[dayKey])
  if (!range) return false

  // Some engines render midnight as "24" under hour12:false — normalise.
  const nowMinutes = (parseInt(part("hour"), 10) % 24) * 60 + parseInt(part("minute"), 10)
  const [oh, om] = range.opens.split(":").map(Number)
  const [ch, cm] = range.closes.split(":").map(Number)
  const openMinutes = oh * 60 + om
  const closeMinutes = ch * 60 + cm

  // An overnight window ("18:00 - 02:00", close < open) wraps past midnight.
  if (closeMinutes < openMinutes) {
    return nowMinutes >= openMinutes || nowMinutes < closeMinutes
  }
  return nowMinutes >= openMinutes && nowMinutes < closeMinutes
}

/** Exported so tests can assert the weekday-key mapping without mocking Intl. */
export const __testables = { DAY_KEYS }

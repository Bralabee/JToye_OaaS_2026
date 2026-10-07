/**
 * The one en-GB, Europe/London formatter for order records (31.1-22; #861, D-17; Pitfall 17;
 * goods P2-CHA-18).
 *
 * WHY THIS EXISTS. The vendor order detail printed "Oct 3, 2026, 11:10:25 PM": date-fns'
 * `format(…, "PPpp")` is en-US unless given a locale, and like every un-zoned formatter it renders
 * in the DEVICE's zone. A UK food business's records are read in UK time, so the zone is always
 * explicit here, never the browser's: a kitchen tablet left on another zone, or a vendor
 * travelling, must read the same time the customer was told.
 *
 * ONE CONVENTION. The customer surfaces (31.1-18) already format with `Intl.DateTimeFormat`
 * ("en-GB", { timeZone: "Europe/London" }); this module is that convention, not a second one.
 * `formatUkClockTime` IS the rule the tracking page uses for "Read by the shop at 18:05" —
 * `formatAllergyNoteReadAt` in recorded-allergen-set.tsx delegates to it.
 *
 * Every function returns "" for an unparseable value rather than "Invalid Date".
 */

const LONDON = "Europe/London"

const LONDON_DATE = new Intl.DateTimeFormat("en-GB", {
  timeZone: LONDON,
  day: "numeric",
  month: "long",
  year: "numeric",
})

// hourCycle h23, not hour12:false: some ICU builds render midnight as "24:10" under hour12:false.
const LONDON_TIME = new Intl.DateTimeFormat("en-GB", {
  timeZone: LONDON,
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
})

// en-CA gives YYYY-MM-DD: a stable key for "is this the same London day".
const LONDON_DAY_KEY = new Intl.DateTimeFormat("en-CA", {
  timeZone: LONDON,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
})

const LONDON_SHORT_DATE = new Intl.DateTimeFormat("en-GB", {
  timeZone: LONDON,
  day: "numeric",
  month: "short",
})

function toDate(at: string | number | Date): Date | null {
  if (at === "" || at === null || at === undefined) return null
  const d = at instanceof Date ? at : new Date(at)
  return Number.isNaN(d.getTime()) ? null : d
}

/** "4 October 2026, 00:10" — an order record's date and time, in UK time. */
export function formatUkDateTime(at: string | number | Date): string {
  const d = toDate(at)
  // Built from two formatters rather than one with both date and time options: en-GB joins
  // those with " at " in current ICU, and the record form is "<date>, <time>".
  return d ? `${LONDON_DATE.format(d)}, ${LONDON_TIME.format(d)}` : ""
}

/** "4 October 2026" — the date alone, in UK time. */
export function formatUkDate(at: string | number | Date): string {
  const d = toDate(at)
  return d ? LONDON_DATE.format(d) : ""
}

/**
 * "18:05", or "18:05 on 4 Oct" when that is not today IN LONDON — a time read at a glance (the
 * kitchen board, the tracking page) where the date is noise on the day and essential after it.
 */
export function formatUkClockTime(at: string | number | Date, now: Date = new Date()): string {
  const d = toDate(at)
  if (!d) return ""
  const time = LONDON_TIME.format(d)
  return LONDON_DAY_KEY.format(d) === LONDON_DAY_KEY.format(now)
    ? time
    : `${time} on ${LONDON_SHORT_DATE.format(d)}`
}

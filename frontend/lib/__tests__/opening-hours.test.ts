/**
 * `isOpenNow` — the copy that `/shop` did NOT have (#507).
 *
 * There were two implementations. `/shop/[slug]` carried the corrected one;
 * `/shop` carried the original, which round-tripped through a locale string and
 * could not express an overnight window. Making `/shop` server-rendered puts its
 * answer into the crawled HTML and into the shop's `openingHoursSpecification`,
 * so the broken copy could no longer be tolerated as a client-only cosmetic bug.
 *
 * The clock is frozen per block, in Europe/London, so these assert the real
 * calendar arithmetic rather than whatever today happens to be. The old
 * implementation FAILS the first two blocks: on 2026-08-04 (a Tuesday, day 4)
 * `new Date(new Date().toLocaleString("en-GB", …))` reparsed "04/08/2026" as
 * 8 April — a Wednesday — and read the wrong row.
 */

import { isOpenNow, parseHoursRange } from "@/lib/opening-hours"

/** Freeze the wall clock at a given UTC instant. */
function at(iso: string, fn: () => void) {
  jest.useFakeTimers().setSystemTime(new Date(iso))
  try {
    fn()
  } finally {
    jest.useRealTimers()
  }
}

const WEEK = {
  mon: "09:00 - 17:00",
  tue: "09:00 - 17:00",
  wed: "09:00 - 17:00",
  thu: "09:00 - 17:00",
  fri: "09:00 - 17:00",
  sat: "closed",
  sun: "closed",
}

describe("isOpenNow", () => {
  it("reads the row for TODAY, not for a day/month swap", () => {
    // 2026-08-04 is a TUESDAY. Under the old locale round-trip this parsed as
    // 8 April (a Wednesday). Both days are open here, so the block below is the
    // one that actually distinguishes them.
    at("2026-08-04T12:00:00Z", () => {
      expect(isOpenNow(WEEK)).toBe(true)
    })
  })

  it("distinguishes a real Saturday from a swapped weekday", () => {
    // 2026-08-01 is a SATURDAY -> closed. The old parser read "01/08/2026" as
    // 8 January 2026, a THURSDAY, and reported the shop OPEN on a day it is
    // shut. That is the customer-visible half of the bug.
    at("2026-08-01T12:00:00Z", () => {
      expect(isOpenNow(WEEK)).toBe(false)
    })
  })

  it("handles an overnight window that wraps past midnight", () => {
    const late = { ...WEEK, fri: "18:00 - 02:00", sat: "18:00 - 02:00" }
    // Saturday 00:30 UK — inside Friday-night service.
    at("2026-08-01T00:30:00+01:00", () => {
      expect(isOpenNow(late)).toBe(true)
    })
    // Saturday 15:00 UK — after close, before the evening opening.
    at("2026-08-01T15:00:00+01:00", () => {
      expect(isOpenNow(late)).toBe(false)
    })
  })

  it("is closed before opening and at/after closing", () => {
    at("2026-08-04T07:00:00+01:00", () => expect(isOpenNow(WEEK)).toBe(false))
    at("2026-08-04T17:00:00+01:00", () => expect(isOpenNow(WEEK)).toBe(false))
    at("2026-08-04T16:59:00+01:00", () => expect(isOpenNow(WEEK)).toBe(true))
  })

  it("treats no schedule as always open, matching the backend", () => {
    expect(isOpenNow(null)).toBe(true)
    expect(isOpenNow({})).toBe(true)
    expect(isOpenNow(undefined)).toBe(true)
  })

  it("is closed for a day with no row or an unparseable one", () => {
    at("2026-08-04T12:00:00+01:00", () => {
      expect(isOpenNow({ mon: "09:00 - 17:00" })).toBe(false) // no tue row
      expect(isOpenNow({ ...WEEK, tue: "all day" })).toBe(false)
    })
  })
})

describe("parseHoursRange", () => {
  it("splits a range into schema.org opens/closes", () => {
    expect(parseHoursRange("09:00 - 17:30")).toEqual({ opens: "09:00", closes: "17:30" })
    expect(parseHoursRange("09:00-17:30")).toEqual({ opens: "09:00", closes: "17:30" })
  })

  it("returns null for closed, blank and unparseable rows", () => {
    expect(parseHoursRange("Closed")).toBeNull()
    expect(parseHoursRange("")).toBeNull()
    expect(parseHoursRange(null)).toBeNull()
    expect(parseHoursRange("by appointment")).toBeNull()
  })
})

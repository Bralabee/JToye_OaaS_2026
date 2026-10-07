/**
 * 31.1-22 (#861, D-17; Pitfall 17; goods P2-CHA-18): the one en-GB, Europe/London formatter for
 * order records on vendor surfaces.
 *
 * The persona read "Oct 3, 2026, 11:10:25 PM" on the vendor order detail: date-fns' "PPpp"
 * is en-US by default and renders in the DEVICE's zone. These pin the UK form and the UK zone.
 *
 * THE BST/GMT PAIR IS THE ZONE PROOF. 23:10Z on 3 October is 00:10 on 4 October in London
 * (BST, UTC+1); 23:10Z on 3 December is 23:10 the same day (GMT). A UTC formatter gets the
 * first wrong and the second right, a device-zone formatter on a London machine gets both right
 * by accident — which is why this suite is also run under TZ=America/New_York (31.1-18's
 * finding: changing process.env.TZ inside jest has no effect, so the whole run is re-zoned).
 */
import { readFileSync } from "fs"
import path from "path"
import { formatUkClockTime, formatUkDate, formatUkDateTime } from "@/lib/uk-datetime"
import { formatAllergyNoteReadAt } from "@/components/storefront/recorded-allergen-set"

describe("formatUkDateTime — an order record's date and time, in UK time", () => {
  it("BST: 23:10Z on 3 Oct 2026 is '4 October 2026, 00:10'", () => {
    expect(formatUkDateTime("2026-10-03T23:10:25Z")).toBe("4 October 2026, 00:10")
  })

  it("GMT: 23:10Z on 3 Dec 2026 is '3 December 2026, 23:10'", () => {
    expect(formatUkDateTime("2026-12-03T23:10:25Z")).toBe("3 December 2026, 23:10")
  })

  it("has no AM/PM and no US month-first form", () => {
    const out = formatUkDateTime("2026-10-03T13:10:25Z")
    expect(out).toBe("3 October 2026, 14:10")
    expect(out).not.toMatch(/AM|PM|Oct 3/)
  })

  it("accepts a Date and an epoch number as well as an ISO string", () => {
    const at = Date.parse("2026-10-03T23:10:25Z")
    expect(formatUkDateTime(at)).toBe("4 October 2026, 00:10")
    expect(formatUkDateTime(new Date(at))).toBe("4 October 2026, 00:10")
  })

  it("an unparseable value renders nothing rather than 'Invalid Date'", () => {
    expect(formatUkDateTime("not a date")).toBe("")
  })
})

describe("formatUkDate — the date alone, in UK time", () => {
  it("BST rolls the date over; GMT does not", () => {
    expect(formatUkDate("2026-10-03T23:10:25Z")).toBe("4 October 2026")
    expect(formatUkDate("2026-12-03T23:10:25Z")).toBe("3 December 2026")
  })

  it("an unparseable value renders nothing", () => {
    expect(formatUkDate("")).toBe("")
  })
})

describe("formatUkClockTime — '18:05', or '18:05 on 4 Oct' when not today in London", () => {
  it("today in London: the time alone", () => {
    expect(formatUkClockTime("2026-10-03T17:05:00Z", new Date("2026-10-03T20:00:00Z"))).toBe("18:05")
  })

  it("another London day: the time and the day", () => {
    expect(formatUkClockTime("2026-10-03T17:05:00Z", new Date("2026-10-05T09:00:00Z"))).toBe("18:05 on 3 Oct")
  })

  it("'today' is London's today: 23:30Z on 3 Oct is already 4 Oct", () => {
    expect(formatUkClockTime("2026-10-03T23:30:00Z", new Date("2026-10-04T08:00:00Z"))).toBe("00:30")
  })

  it("is the SAME rule the customer's tracking page uses (one convention, 31.1-18)", () => {
    const now = new Date("2026-10-05T09:00:00Z")
    for (const iso of ["2026-10-03T17:05:00Z", "2026-10-05T07:59:00Z", "2026-12-03T23:10:25Z"]) {
      expect(formatAllergyNoteReadAt(iso, now)).toBe(formatUkClockTime(iso, now))
    }
  })
})

describe("the vendor order record surfaces no longer use date-fns' en-US 'PPpp'", () => {
  const read = (rel: string) => readFileSync(path.join(__dirname, "..", "..", rel), "utf8")

  it("control: the sources were read", () => {
    expect(read("components/dashboard/orders/OrderDetailPanel.tsx")).toContain("OrderDetailPanel")
    expect(read("app/dashboard/orders/page.tsx")).toContain("OrdersPage")
  })

  it.each([
    "components/dashboard/orders/OrderDetailPanel.tsx",
    "app/dashboard/orders/page.tsx",
  ])("%s has no \"PPpp\" or \"PPp\" format", (rel) => {
    expect(read(rel)).not.toMatch(/"PPp{1,2}"/)
  })
})

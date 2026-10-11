/**
 * `lib/opening-hours.ts` after 37-22: DISPLAY helpers only.
 *
 * Until 37-22 this module also DECIDED whether a shop was open (`isOpenNow`, re-parsing the
 * vendor's free-text hours) and fed JSON-LD from the same text (`parseHoursRange`). The server now
 * decides once (37-20 `ShopAvailabilityPolicy`, exposed as `PublicShop.openNow`), every customer
 * surface reads that (`lib/shop-availability.ts`), and JSON-LD reads the structured schedule. A
 * second, client-side decider is exactly the disagreement 37-22 removes (D-15, theme 2), so the
 * module must not export one: re-adding `isOpenNow` reds the first block.
 *
 * The isOpenNow calendar cases that lived here (day/month swap, overnight wrap, boundaries) moved
 * with the decision to the server: `ShopAvailabilityPolicyTest` (core-java) holds them in UK time,
 * DST included.
 */

import * as openingHours from "@/lib/opening-hours"
import { DAY_LABELS, DAY_ORDER, SCHEMA_DAYS } from "@/lib/opening-hours"

describe("lib/opening-hours decides nothing (37-22)", () => {
  it("exports no open/closed decider and no free-text parser", () => {
    const exported = Object.keys(openingHours)
    expect(exported).not.toContain("isOpenNow")
    expect(exported).not.toContain("parseHoursRange")
    // CONTROL: the namespace import can see this module's exports at all.
    expect(exported).toEqual(expect.arrayContaining(["DAY_ORDER", "DAY_LABELS", "SCHEMA_DAYS"]))
  })
})

describe("display helpers", () => {
  it("orders the week Monday first, in the API's three-letter keys", () => {
    expect([...DAY_ORDER]).toEqual(["mon", "tue", "wed", "thu", "fri", "sat", "sun"])
  })

  it("labels every key with its full English day name", () => {
    expect(DAY_ORDER.map((d) => DAY_LABELS[d])).toEqual([
      "Monday",
      "Tuesday",
      "Wednesday",
      "Thursday",
      "Friday",
      "Saturday",
      "Sunday",
    ])
  })

  it("maps every key to its schema.org day URL", () => {
    expect(DAY_ORDER.map((d) => SCHEMA_DAYS[d])).toEqual(
      ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"].map(
        (d) => `https://schema.org/${d}`
      )
    )
  })
})

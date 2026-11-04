import { readyByIsRelevant, readyByLabel } from "@/lib/ready-by"

// #825: the ESTIMATED ready-by label. Nothing is ever guessed: no field, no label.
describe("readyByLabel", () => {
  const NOW = new Date("2026-11-04T12:00:00Z")

  it("formats the time in UK time (GMT in November)", () => {
    expect(readyByLabel("2026-11-04T14:35:00Z", NOW)).toBe("14:35")
  })

  it("formats in British Summer Time, not the device zone", () => {
    expect(readyByLabel("2026-07-04T14:35:00Z", new Date("2026-07-04T12:00:00Z"))).toBe("15:35")
  })

  it("adds the day when it is not today in London", () => {
    expect(readyByLabel("2026-11-05T09:10:00Z", NOW)).toBe("09:10 on 5 Nov")
  })

  it.each([[undefined], [null], [""], ["not-a-date"]])("%j shows nothing", (value) => {
    expect(readyByLabel(value as string | null | undefined, NOW)).toBeNull()
  })
})

describe("readyByIsRelevant", () => {
  it.each([["PENDING"], ["CONFIRMED"], ["PREPARING"]])("%s: still waiting to be made", (status) => {
    expect(readyByIsRelevant(status)).toBe(true)
  })

  it.each([["READY"], ["COMPLETED"], ["CANCELLED"], ["REFUNDED"], ["DRAFT"], [null], [undefined]])(
    "%j: the estimate no longer means anything",
    (status) => {
      expect(readyByIsRelevant(status as string | null | undefined)).toBe(false)
    }
  )
})

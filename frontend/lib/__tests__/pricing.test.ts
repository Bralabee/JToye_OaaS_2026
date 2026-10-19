import { PRICING, formatRate } from "@/lib/pricing"

describe("formatRate", () => {
  it.each([
    [0.005, "0.5%"],
    [0.06, "6%"],
    [0, "0%"],
    [0.025, "2.5%"],
    [0.07, "7%"], // 0.07 * 100 = 7.000000000000001 in floating point
  ])("formats %p as %s", (rate, label) => {
    expect(formatRate(rate)).toBe(label)
  })
})

describe("PRICING (billing code: StripeProperties.Billing / V88)", () => {
  it("matches the billed tiers", () => {
    expect(PRICING.coreMonthly).toBe(39)
    expect(PRICING.usageLabel).toBe("0.5%")
    expect(PRICING.flatLow).toBe(79)
    expect(PRICING.flatHigh).toBe(149)
    expect(PRICING.paygLabel).toBe("6%")
    expect(PRICING.setupFrom).toBe(99)
  })

  it("never carries the internal WALK_SETUP_STEPDOWN tier", () => {
    expect(JSON.stringify(PRICING)).not.toMatch(/195|walk/i)
  })
})

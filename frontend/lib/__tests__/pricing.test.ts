import { PRICING, STRIPE_UK_PRICING_LINK_TEXT, STRIPE_UK_PRICING_URL, formatRate, planSentence, pricingPlans } from "@/lib/pricing"

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

// SW-CLR-06 (swarm epic #964): the card-fee line points at Stripe's own UK price
// list instead of quoting a rate. Owner ruling 2026-11-01: never hardcode the rate,
// so the URL is the one thing pinned here, and both render sites read this constant.
describe("STRIPE_UK_PRICING_URL (the card-fee line's link)", () => {
  it("is Stripe's UK pricing page over https", () => {
    expect(STRIPE_UK_PRICING_URL).toBe("https://stripe.com/gb/pricing")
  })

  it("names the page it links, and quotes no rate", () => {
    expect(STRIPE_UK_PRICING_LINK_TEXT).toMatch(/stripe.*uk.*pricing/i)
    expect(STRIPE_UK_PRICING_LINK_TEXT).not.toMatch(/\d/)
  })
})

// Round-1 review of PR 1116 (finding 3): the sentence form of each plan, from the shared rows.
describe("planSentence (the merchant terms plan list)", () => {
  it("renders each plan from pricingPlans()", () => {
    expect(pricingPlans(PRICING).map(planSentence)).toEqual([
      "Standard: \u00a339 per location per month, plus 0.5% of direct sales.",
      "Flat: \u00a379\u2013\u00a3149 per location per month, 0% of sales.",
      "Batch pay-as-you-go: \u00a30 per month, plus 6% of direct sales.",
    ])
  })
})

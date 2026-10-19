import { render, screen } from "@testing-library/react"

/**
 * The page must READ its prices from lib/pricing.ts (PR #966), not carry its
 * own copy. Proof by substitution: the module is mocked with figures no billing
 * code has, and every place the page prints a price must show the substitute.
 * A page with a local const would still print £39 / 0.5% / £99 and fail here.
 */
jest.mock("@/lib/pricing", () => {
  const actual = jest.requireActual("@/lib/pricing")
  return {
    ...actual,
    PRICING: {
      ...actual.PRICING,
      coreMonthly: 41,
      usageRate: 0.007,
      usageLabel: "0.7%",
      flatLow: 81,
      flatHigh: 151,
      paygRate: 0.07,
      paygLabel: "7%",
      setupFrom: 105,
    },
  }
})

import { OperatorPitch } from "@/components/marketing/operator-pitch"

describe("OperatorPitch reads the shared pricing module", () => {
  it("prints the substituted figures in the docket, the table, the setup line and the margin sentence", () => {
    const { container } = render(<OperatorPitch />)

    const docket = container.querySelector("[data-op-figure]") as HTMLElement
    expect(docket).toHaveTextContent("0.7%")
    expect(docket).toHaveTextContent("plus £41 a month per location")

    const rows = Array.from(container.querySelectorAll("tbody tr"))
    expect(rows[0]).toHaveTextContent(/Standard.*£41 per location.*0\.7% of direct sales/)
    expect(rows[1]).toHaveTextContent(/Flat.*£81–£151 per location.*0% of sales/)
    expect(rows[2]).toHaveTextContent(/Batch pay-as-you-go.*£0.*7% of direct sales/)

    expect(screen.getByText(/assisted setup from £105, scoped to the work agreed/i)).toBeInTheDocument()
    expect(screen.getByText(/direct storefront cost £41 \+ 0\.7%, plus Stripe.s card fees/i)).toBeInTheDocument()

    // None of the real figures survives anywhere on the page.
    const text = container.textContent ?? ""
    for (const real of ["£39", "0.5%", "£79", "£149", "£99", "6% of direct"]) expect(text).not.toContain(real)
  })
})

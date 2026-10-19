import fs from "fs"
import path from "path"
import { fireEvent, render, screen } from "@testing-library/react"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { OperatorPitch } from "@/components/marketing/operator-pitch"
import ForOperatorsPage from "@/app/for-operators/page"

/**
 * The stock scale token these bands used to carry, named ONCE per suite.
 *
 * The assertion it backs is that the swap is COMPLETE per site (T-35-24): a band
 * left carrying both the old token and the tier class renders identically today
 * and diverges silently the moment the tier's value moves, which is the whole
 * failure mode a declared contract exists to prevent.
 *
 * NOTE FOR PLAN 35-10: a static gate over this token must exclude `__tests__/`,
 * exactly as plan 35-02 recorded for the shed dashboard width class. The literal
 * below is the assertion, not a surviving usage.
 */
const STOCK_BAND_TOKEN = "max-w-7xl"

describe("OperatorPitch design tokens (Surface C re-skin)", () => {
  const src = fs.readFileSync(
    path.join(process.cwd(), "components/marketing/operator-pitch.tsx"),
    "utf8"
  )

  it("uses only design tokens — zero hardcoded hex", () => {
    // The re-skin migrates the bespoke navy/orange/yellow palette onto tokens.
    expect(src).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it("renders on-token classes within the landing brand family (oxblood/cream/gold/amber)", () => {
    // The surface used to run its own navy/emerald/mono skin, which read as a
    // different product to anyone arriving from `/`. It now shares the landing
    // brand thread (jtoyedigital.co.uk oxblood + Work Sans + amber appetite).
    expect(src).toMatch(/bg-oxblood/)
    expect(src).toMatch(/text-cream|bg-cream/)
    expect(src).toMatch(/text-gold/)
    expect(src).toMatch(/bg-amber-500/)
  })

  it("does not regress to the old off-brand navy/emerald/mono skin", () => {
    expect(src).not.toMatch(/bg-slate-900|bg-slate-800|bg-emerald-50|border-emerald-200/)
    expect(src).not.toMatch(/font-mono/)
  })

  it("obeys the public display cap — no font-black / text-7xl / serif", () => {
    expect(src).not.toMatch(/font-black|text-7xl|text-8xl|font-serif/)
  })

  it("wraps the operator pitch in the shared PublicShell (connected surface)", () => {
    render(<ForOperatorsPage />)
    // The shared public header exposes a "For operators" nav link (header-only copy),
    // proving PublicShell rendered around the pitch.
    const headerLink = screen.getByRole("link", { name: /^for operators$/i })
    expect(headerLink.getAttribute("href")).toBe("/for-operators")
  })
})

describe("OperatorPitch", () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = jest.fn()
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
  })

  it("speaks directly to the established London-cluster operator audience", () => {
    render(<OperatorPitch />)

    expect(screen.getByRole("heading", { name: /keep the order.*keep the customer.*keep the kitchen moving/i })).toBeInTheDocument()
    expect(screen.getByText(/established Nigerian and West African takeaway and catering operators/i)).toBeInTheDocument()
    expect(screen.getByText(/one London cluster/i)).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /one change.*three channels.*two versions/i })).toBeInTheDocument()
    expect(screen.getByText(/fewer places to check/i)).toBeInTheDocument()
    // Both rhythms are named in the hero, from one kitchen.
    expect(screen.getByText(/daily counter takeaway and the weekend batch and catering run from one kitchen/i)).toBeInTheDocument()
    expect(screen.getByText(/updated 19 October 2026/i)).toBeInTheDocument()
  })

  it("no longer carries the July pack, its download links, or its stale framing", () => {
    const { container } = render(<OperatorPitch />)
    fireEvent.click(screen.getByRole("button", { name: /check your pilot fit/i }))

    // Non-vacuity control: the instrument finds a link that IS there.
    expect(container.querySelectorAll('a[href="/dashboard/onboarding"]').length).toBeGreaterThan(0)
    expect(container.querySelector("a[href*='pilot-pack']")).toBeNull()
    expect(container.querySelector("a[download]")).toBeNull()
    expect(screen.queryByText(/download vendor pack|download the pack/i)).toBeNull()
    expect(screen.queryByText(/return this pack/i)).toBeNull()
    expect(screen.queryByText(/edition 10 July 2026|UK pilot hypothesis/i)).toBeNull()
    expect(screen.queryByText(/validation tracks?/i)).toBeNull()
    expect(screen.queryByText(/should not be sold as the same job/i)).toBeNull()
    expect(screen.queryByText(/not production connected-account settlement/i)).toBeNull()
    expect(screen.queryByText(/£119|£199|pricing test|high volume/i)).toBeNull()
  })

  it("quantifies the aggregator leak with the guide's about-£2,800 figure", () => {
    render(<OperatorPitch />)
    expect(screen.getByText(/28% aggregator commission on £10k a month of orders hands over £2,800 a month/i)).toBeInTheDocument()
    expect(screen.getByText(/direct storefront cost £39 \+ 0\.5%/i)).toBeInTheDocument()
  })

  it("offers a 'Start your application' CTA linking to onboarding", () => {
    render(<OperatorPitch />)

    // The hero CTA and the closing fit-check route are both "Start your application".
    const ctas = screen.getAllByRole("link", { name: /start your application/i })
    expect(ctas.length).toBeGreaterThanOrEqual(2)
    for (const cta of ctas) expect(cta).toHaveAttribute("href", "/dashboard/onboarding")
    expect(screen.getByText(/a person reviews every application, and nothing is charged until a plan is agreed/i)).toBeInTheDocument()
  })

  it("gives the hero two distinct CTAs: amber primary, outlined secondary", () => {
    render(<OperatorPitch />)
    const primary = screen.getAllByRole("link", { name: /start your application/i })[0]
    const secondary = screen.getByRole("button", { name: /check your pilot fit/i })
    expect(primary.classList.contains("bg-amber-500")).toBe(true)
    expect(secondary.classList.contains("bg-amber-500")).toBe(false)
    expect(secondary.classList.contains("border-2")).toBe(true)
    expect(secondary.classList.contains("border-gold/50")).toBe(true)
  })

  it("keeps three service-rail items covering storefront, kitchen display and the allergen engine", () => {
    const { container } = render(<OperatorPitch />)
    const items = container.querySelectorAll("[data-rail-item]")
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent(/storefront and QR ordering/i)
    expect(items[1]).toHaveTextContent(/kitchen display with cook sheet/i)
    expect(items[2]).toHaveTextContent(/allergen and label engine/i)
  })

  it("keeps four pilot steps and step 03 names the kitchen display and cook sheet", () => {
    const { container } = render(<OperatorPitch />)
    const steps = container.querySelectorAll("[data-pilot-step]")
    expect(steps).toHaveLength(4)
    expect(steps[2]).toHaveTextContent(/kitchen display and the cook sheet/i)
    expect(screen.queryByText(/while you remain responsible for fulfilment/i)).toBeNull()
  })

  it("prints exactly the billed price tiers, each as a countable tile", () => {
    const { container } = render(<OperatorPitch />)
    const terms = container.querySelector("[data-op-terms]") as HTMLElement
    expect(terms).not.toBeNull()
    // Band is lg:grid-cols-4: four tiles, no more, no fewer.
    const grid = terms.querySelector(".lg\\:grid-cols-4") as HTMLElement
    expect(grid.children).toHaveLength(4)
    expect(terms).toHaveTextContent("£39 per location per month + 0.5% of direct sales")
    expect(terms).toHaveTextContent("£79–£149 per location per month, 0% platform fee")
    expect(terms).toHaveTextContent("£0 per month + 6% of direct sales")
    expect(terms).toHaveTextContent("From £99, scoped to the work agreed")
    // renderCountable keeps the real number as text and hooks the first integer.
    expect(Array.from(terms.querySelectorAll("[data-count-to]")).map((el) => el.getAttribute("data-count-to"))).toEqual(["39", "79", "0", "99"])
    expect(terms).toHaveTextContent(/charged by Stripe on your own Stripe account/i)
    expect(terms).toHaveTextContent(/live card settlement switches on with the first production operator/i)
  })

  it("keeps the takeaway and catering cohorts distinct", () => {
    render(<OperatorPitch />)

    expect(screen.getByRole("heading", { name: /takeaway: protect the regular order/i })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /catering: plan the weekend batch/i })).toBeInTheDocument()
    // The old "validation tracks" callout is replaced by one honest line.
    expect(screen.getByText(/scheduled orders, deposits and cook sheets are built and tested; card settlement runs in Stripe test mode until the first production operator goes live/i)).toBeInTheDocument()
    expect(screen.getByText(/scheduled pre-order slots with cut-offs and capacity/i)).toBeInTheDocument()
    expect(screen.getByText(/deposit and balance payment links/i)).toBeInTheDocument()
    expect(screen.getByText(/consolidated cook sheet/i)).toBeInTheDocument()
    expect(screen.getByText(/^PPDS labels$/)).toBeInTheDocument()
    expect(screen.getByText(/structured opening hours and a one-tap kitchen pause/i)).toBeInTheDocument()
    expect(screen.getByText(/cash-order abuse caps/i)).toBeInTheDocument()
  })

  it("states the product and responsibility caveats plainly", () => {
    render(<OperatorPitch />)

    expect(screen.getByText(/direct storefront does not generate demand/i)).toBeInTheDocument()
    expect(screen.getByText(/not an offline KDS/i)).toBeInTheDocument()
    expect(screen.getByText(/you remain the food business operator/i)).toBeInTheDocument()
    expect(screen.getByText(/whatsapp is a generated pre-order link/i)).toBeInTheDocument()
    expect(screen.getByText(/live card settlement waits for the production cutover/i)).toBeInTheDocument()
    // One list, no duplicates: each caveat appears once.
    expect(screen.getAllByText(/does not generate demand/i)).toHaveLength(1)
    expect(screen.getAllByText(/not an offline KDS/i)).toHaveLength(1)
    expect(screen.getByRole("heading", { name: /what is built in/i })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /what stays with you/i })).toBeInTheDocument()
    expect(screen.getByText(/the allergen engine: 14-allergen acknowledgement at checkout/i)).toBeInTheDocument()
  })

  /**
   * PHASE 35 / UIX-07 — the DECLARED Marketing width tier.
   *
   * This surface already rendered at the Marketing width before the phase, but
   * only because a stock scale token happens to equal that number. A coincidence
   * nothing asserts is the same class of problem as the inherited dashboard width
   * this phase exists to remove, so the tier is now DECLARED and the declaration
   * is what these cases read.
   *
   * The count is asserted per component rather than "at least one", because a
   * band left behind is invisible at today's value.
   */
  const MARKETING_BANDS = 3

  function marketingBands(): Element[] {
    const { container } = render(<OperatorPitch />)
    return Array.from(container.querySelectorAll('[data-width-tier="marketing"]'))
  }

  it("declares the Marketing tier on every one of its band elements", () => {
    expect(marketingBands()).toHaveLength(MARKETING_BANDS)
  })

  it("carries the marketing tier class on every declared band", () => {
    const bands = marketingBands()
    // Non-vacuity: a per-band loop over an empty list passes trivially.
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      expect(band.classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
    }
  })

  it("carries the tier class INSTEAD of the stock token, never beside it", () => {
    const bands = marketingBands()
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      // CONTROL, run first and on this very element: classList.contains
      // demonstrably finds a token that IS present here, so the absence below is
      // a statement about the token rather than about the instrument.
      expect(band.classList.contains("mx-auto")).toBe(true)
      expect(band.classList.contains(STOCK_BAND_TOKEN)).toBe(false)
    }
  })

  it("leaves the typographic measure clamps inside the band alone", () => {
    render(<OperatorPitch />)

    // A headline measure is not a page band: it is orthogonal to the tier and
    // must survive the swap untouched.
    const headline = screen.getByRole("heading", {
      name: /keep the order.*keep the customer.*keep the kitchen moving/i,
    })
    expect(headline.classList.contains("max-w-4xl")).toBe(true)
    expect(headline.hasAttribute("data-width-tier")).toBe(false)
  })

  it("reveals the fit check and copies its locally generated summary", async () => {
    render(<OperatorPitch />)

    fireEvent.click(screen.getByRole("button", { name: /check your pilot fit/i }))
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText("Your main service"), { target: { value: "Catering" } })
    fireEvent.change(screen.getByLabelText("What you want to steady first"), { target: { value: "Make catering handover clearer" } })
    fireEvent.click(screen.getByRole("button", { name: /copy fit summary/i }))

    expect(await screen.findByRole("status")).toHaveTextContent("Fit summary copied")
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining("Business: Catering"))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(expect.stringContaining("Priority: Make catering handover clearer"))
  })
})
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
    expect(screen.getAllByRole("link", { name: /download vendor pack|download the pack/i })[0]).toHaveAttribute("href", "/jtoye-operator-pilot-pack.pdf")
    expect(screen.getAllByRole("link", { name: /download vendor pack|download the pack/i })[0]).toHaveAttribute("download")
    expect(screen.getByText(/return this pack to the person who shared it/i)).toBeInTheDocument()
    expect(screen.getByText(/edition 10 July 2026/i)).toBeInTheDocument()
  })

  it("offers a 'Start your application' CTA linking to onboarding", () => {
    render(<OperatorPitch />)

    const cta = screen.getByRole("link", { name: /start your application/i })
    expect(cta).toHaveAttribute("href", "/dashboard/onboarding")
  })

  it("keeps the takeaway and catering cohorts distinct", () => {
    render(<OperatorPitch />)

    expect(screen.getByRole("heading", { name: /takeaway: protect the regular order/i })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /catering: make the handover legible/i })).toBeInTheDocument()
    expect(screen.getAllByText(/catering and WhatsApp are validation tracks/i)).not.toHaveLength(0)
  })

  it("states the product and responsibility caveats plainly", () => {
    render(<OperatorPitch />)

    expect(screen.getByText(/direct storefront does not generate demand/i)).toBeInTheDocument()
    expect(screen.getByText(/not an offline KDS/i)).toBeInTheDocument()
    expect(screen.getByText(/allergen information is vendor-entered assistance/i)).toBeInTheDocument()
    expect(screen.getAllByText(/not production connected-account settlement/i)).toHaveLength(2)
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
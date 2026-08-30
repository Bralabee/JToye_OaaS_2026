import fs from "fs"
import path from "path"
import { fireEvent, render, screen } from "@testing-library/react"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { BusinessModelGuide } from "@/components/marketing/business-model-guide"
import BusinessModelGuidePage from "@/app/business-model-guide/page"

/**
 * The stock scale token these bands used to carry, named ONCE per suite.
 * See the note in operator-pitch.test.tsx: the assertion it backs is that the
 * swap is COMPLETE per site (T-35-24), and a static gate over this token in plan
 * 35-10 must exclude `__tests__/` or it will count this assertion as a usage.
 */
const STOCK_BAND_TOKEN = "max-w-7xl"

describe("BusinessModelGuide design tokens (Surface C re-skin)", () => {
  const src = fs.readFileSync(
    path.join(process.cwd(), "components/marketing/business-model-guide.tsx"),
    "utf8"
  )

  it("uses only design tokens — zero hardcoded hex", () => {
    // The re-skin converges the bespoke teal/rust/olive palette onto the same
    // dark surface (bg-slate-900) as the operator pitch.
    expect(src).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it("renders on-token classes within the landing brand family (oxblood/cream/amber)", () => {
    // Chrome + accents now share the landing brand thread; emerald/orange stay
    // as SEMANTIC hues (evidence confidence, "we can support" vs "to validate")
    // and are deliberately not rebranded.
    expect(src).toMatch(/bg-oxblood/)
    expect(src).toMatch(/bg-cream/)
    expect(src).toMatch(/amber-500|amber-600/)
    expect(src).toMatch(/bg-emerald-50/)
  })

  it("obeys the public display cap — no font-black / text-7xl / serif", () => {
    expect(src).not.toMatch(/font-black|text-7xl|text-8xl|font-serif/)
  })

  it("wraps the guide in the shared PublicShell (connected surface)", () => {
    render(<BusinessModelGuidePage />)
    // The shared public footer carries the allergen note — proves PublicShell wrapped it.
    expect(
      screen.getByText(/allergen info available on all products/i)
    ).toBeTruthy()
  })
})

describe("BusinessModelGuide", () => {
  beforeEach(() => {
    Object.assign(navigator, {
      clipboard: { writeText: jest.fn().mockResolvedValue(undefined) },
    })
  })

  it("updates the transparent unit economics when GTV changes", () => {
    render(<BusinessModelGuide />)

    expect(screen.getByText("£89")).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Monthly GTV"), { target: { value: "20000" } })

    expect(screen.getByText("£139")).toBeInTheDocument()
    expect(screen.getByText("£29")).toBeInTheDocument()
    expect(screen.getByText("£110")).toBeInTheDocument()
  })

  it("filters the evidence ledger by confidence", () => {
    render(<BusinessModelGuide />)

    fireEvent.click(screen.getByRole("button", { name: "To validate" }))

    expect(screen.getByText("Assisted onboarding may beat a self-serve funnel")).toBeInTheDocument()
    expect(screen.queryByText("The first cluster should be narrow, not pan-European")).not.toBeInTheDocument()
  })

  /**
   * PHASE 35 / UIX-07 — the DECLARED Marketing width tier.
   *
   * Four bands: the header rail, the sticky topic rail, the body and the footer
   * rail. All four already rendered at the Marketing width via a stock scale
   * token; the tier is now declared so the width is a contract rather than a
   * coincidence. The count is asserted because a band left behind is invisible
   * at today's value and only diverges when the tier moves.
   */
  const MARKETING_BANDS = 4

  function marketingBands(): Element[] {
    const { container } = render(<BusinessModelGuide />)
    return Array.from(container.querySelectorAll('[data-width-tier="marketing"]'))
  }

  it("declares the Marketing tier on every one of its band elements", () => {
    expect(marketingBands()).toHaveLength(MARKETING_BANDS)
  })

  it("carries the marketing tier class on every declared band", () => {
    const bands = marketingBands()
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      expect(band.classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
    }
  })

  it("carries the tier class INSTEAD of the stock token, never beside it", () => {
    const bands = marketingBands()
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      // CONTROL on this very element, so the absence below is about the token.
      expect(band.classList.contains("mx-auto")).toBe(true)
      expect(band.classList.contains(STOCK_BAND_TOKEN)).toBe(false)
    }
  })

  it("keeps the topic rail's own horizontal scroll behaviour on the band element", () => {
    // The sticky topic rail IS one of the four bands, and it carries an overflow
    // class on the same element. A displaced good: the swap must not shed it.
    const bands = marketingBands()
    const scrollRail = bands.filter((b) => b.classList.contains("overflow-x-auto"))
    expect(scrollRail).toHaveLength(1)
  })

  it("leaves the typographic measure clamps inside the band alone", () => {
    render(<BusinessModelGuide />)

    const headline = screen.getByRole("heading", { level: 1 })
    expect(headline.classList.contains("max-w-4xl")).toBe(true)
    expect(headline.hasAttribute("data-width-tier")).toBe(false)
  })

  it("copies the link and provides a printable PDF", async () => {
    render(<BusinessModelGuide />)

    fireEvent.click(screen.getAllByRole("button", { name: "Copy link" })[0])
    expect(await screen.findByText("Link copied")).toBeInTheDocument()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(window.location.href)

    const printLinks = screen.getAllByRole("link", { name: /print \/ save pdf/i })
    expect(printLinks[0]).toHaveAttribute("href", "/business-model-guide.pdf")
    expect(printLinks[0]).toHaveAttribute("target", "_blank")
  })
})
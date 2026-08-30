import { fireEvent, render, screen } from "@testing-library/react"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { CompetitiveTeardown } from "@/components/marketing/competitive-teardown"
import CompetitivePage from "@/app/competitive/page"

/**
 * The stock scale token these bands used to carry, named ONCE per suite.
 * See the note in operator-pitch.test.tsx: the assertion it backs is that the
 * swap is COMPLETE per site (T-35-24), and a static gate over this token in plan
 * 35-10 must exclude `__tests__/` or it will count this assertion as a usage.
 */
const STOCK_BAND_TOKEN = "max-w-7xl"

// Recharts renders SVG through a ResponsiveContainer that needs real layout /
// ResizeObserver — unavailable under jsdom. Mock it so the test targets the
// interactive feature matrix (the behaviour under test), not the chart.
jest.mock("recharts", () => {
  const Passthrough = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  const Empty = () => null
  return {
    ResponsiveContainer: Passthrough,
    RadarChart: Passthrough,
    Radar: Empty,
    PolarGrid: Empty,
    PolarAngleAxis: Empty,
    Legend: Empty,
    Tooltip: Empty,
  }
})

describe("CompetitiveTeardown", () => {
  it("renders the h1 headline and a known feature name", () => {
    render(<CompetitiveTeardown />)

    const h1 = screen.getByRole("heading", { level: 1 })
    expect(h1).toBeInTheDocument()
    expect(h1).toHaveTextContent(/Flipdish/i)

    // A known feature row from the verified dataset.
    expect(screen.getByText(/PPDS \/ Natasha's-Law allergen PDF labels/i)).toBeInTheDocument()
  })

  /**
   * FE-1: /competitive's body overflowed 56px at 390px. jsdom cannot measure
   * real layout (no ResizeObserver, no computed scrollWidth), so the actual
   * "does the document overflow" proof lives in the Playwright mobile-project
   * run of `e2e/public-layout.spec.ts`'s existing PUBLIC_ROUTES loop, which
   * already includes `/competitive` and already asserts
   * `horizontalOverflow <= 1`. This test pins the STRUCTURAL fix instead: the
   * radar chart's `<figure>` is a CSS-grid item, whose default `min-width:
   * auto` lets a wide grid-item child (Recharts' pixel-measured axis/legend
   * layout) force the grid track — and the document — wider than the
   * viewport. `min-w-0` removes that floor at the source, and the
   * `overflow-x-auto` wrapper is the safety net (same pattern as
   * components/legal/retention-table.tsx) so nothing is ever shrunk
   * off-screen.
   */
  it("contains the radar chart in a min-w-0 grid item with an overflow-x-auto scroll fallback (FE-1)", () => {
    const { container } = render(<CompetitiveTeardown />)

    const figure = container.querySelector("figure")
    // radar chart <figure> not found would mean the section did not render.
    expect(figure).toBeInTheDocument()
    expect(figure).toHaveClass("min-w-0")

    const chartRegion = screen.getByRole("img", {
      name: /Radar chart comparing Flipdish and J'Toye/i,
    })
    // The chart's role=img container must have an overflow-x-auto parent.
    const scrollWrapper = chartRegion.parentElement
    expect(scrollWrapper).toHaveClass("overflow-x-auto")
  })

  it("renders all five verdict filter chips as buttons", () => {
    render(<CompetitiveTeardown />)

    expect(screen.getByRole("button", { name: "All" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "J'Toye leads" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Flipdish leads" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Hard gap" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Parity" })).toBeInTheDocument()
  })

  it("filtering by 'J'Toye leads' hides a Flipdish-only row and keeps a J'Toye-leads row", () => {
    render(<CompetitiveTeardown />)

    // Both present under the default "All" filter (full matrix-row name has the
    // parenthetical, so it targets the matrix, not the 'Hard gaps' card deck).
    expect(screen.getByText(/Native mobile apps \(iOS\/Android\)/i)).toBeInTheDocument()
    expect(screen.getByText(/PPDS \/ Natasha's-Law allergen PDF labels/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "J'Toye leads" }))

    // Native mobile apps is a 'Hard gap' → removed from the matrix.
    expect(screen.queryByText(/Native mobile apps \(iOS\/Android\)/i)).not.toBeInTheDocument()
    // PPDS labels is a 'J'Toye leads' row → still present.
    expect(screen.getByText(/PPDS \/ Natasha's-Law allergen PDF labels/i)).toBeInTheDocument()

    // The active chip reports its pressed state for assistive tech.
    expect(screen.getByRole("button", { name: "J'Toye leads" })).toHaveAttribute("aria-pressed", "true")
  })

  it("the search input filters the matrix by text", () => {
    render(<CompetitiveTeardown />)

    // Card payments is present before searching.
    expect(screen.getByText(/Card payments/i)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/search features/i), { target: { value: "PPDS" } })

    // Only PPDS-matching rows survive; Card payments is filtered out.
    expect(screen.getByText(/PPDS \/ Natasha's-Law allergen PDF labels/i)).toBeInTheDocument()
    expect(screen.queryByText(/Card payments/i)).not.toBeInTheDocument()
  })

  it("shows an empty state when nothing matches", () => {
    render(<CompetitiveTeardown />)

    fireEvent.change(screen.getByLabelText(/search features/i), {
      target: { value: "zzz-no-such-feature" },
    })

    expect(screen.getByText(/no features match/i)).toBeInTheDocument()
  })

  /**
   * PHASE 35 / UIX-07 — the DECLARED Marketing width tier.
   *
   * Four bands: the hero rail, the sticky jump rail, the body and the footer
   * rail. All four already rendered at the Marketing width via a stock scale
   * token; the tier is now declared so the width is a contract rather than a
   * coincidence.
   *
   * The radar chart's `min-w-0` figure and its `overflow-x-auto` wrapper (FE-1,
   * above) sit INSIDE the body band and are untouched by the swap — the case
   * below re-reads them through the band query so a regression there cannot hide.
   */
  const MARKETING_BANDS = 4

  function marketingBands(): Element[] {
    const { container } = render(<CompetitiveTeardown />)
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

  it("keeps the radar chart's FE-1 overflow wrapper inside a declared band", () => {
    const { container } = render(<CompetitiveTeardown />)

    const chartRegion = screen.getByRole("img", {
      name: /Radar chart comparing Flipdish and J'Toye/i,
    })
    expect(chartRegion.parentElement).toHaveClass("overflow-x-auto")
    // And that wrapper is still a DESCENDANT of a declared band, so the swap
    // moved a class rather than restructuring the tree around the chart.
    const bodyBand = container.querySelector('[data-width-tier="marketing"] .overflow-x-auto')
    expect(bodyBand).not.toBeNull()
  })

  it("leaves the typographic measure clamps inside the band alone", () => {
    render(<CompetitiveTeardown />)

    const headline = screen.getByRole("heading", { level: 1 })
    expect(headline.classList.contains("max-w-4xl")).toBe(true)
    expect(headline.hasAttribute("data-width-tier")).toBe(false)
  })

  it("wraps the page in the shared PublicShell (connected public surface)", () => {
    render(<CompetitivePage />)
    // The shared public footer carries the allergen note — proves PublicShell wrapped it.
    expect(screen.getByText(/allergen info available on all products/i)).toBeInTheDocument()
  })
})

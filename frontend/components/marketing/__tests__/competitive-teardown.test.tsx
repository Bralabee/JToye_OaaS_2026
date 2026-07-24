import { fireEvent, render, screen } from "@testing-library/react"
import { CompetitiveTeardown } from "@/components/marketing/competitive-teardown"
import CompetitivePage from "@/app/competitive/page"

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

  it("wraps the page in the shared PublicShell (connected public surface)", () => {
    render(<CompetitivePage />)
    // The shared public footer carries the allergen note — proves PublicShell wrapped it.
    expect(screen.getByText(/allergen info available on all products/i)).toBeInTheDocument()
  })
})

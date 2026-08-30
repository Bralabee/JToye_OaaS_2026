/**
 * Dashboard orders table — QA-council FIX-5 (L1, run disc-20260712-010550).
 *
 * Customers quote the ORD-… number from their receipt/confirmation email,
 * but the vendor table's ID column rendered a truncated internal UUID
 * (order.id.substring(0, 8)), so a vendor could not match a phone enquiry
 * to a row. The cell must render the customer-facing orderNumber, falling
 * back to the truncated UUID only for legacy orders without one.
 */
import { render, screen, within } from "@testing-library/react"
import OrdersPage from "../page"
import apiClient from "@/lib/api-client"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// SSE hook (#92) — no real EventSource in jsdom.
jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

function orderRow(id: string, orderNumber?: string) {
  return {
    id,
    tenantId: "tenant-1",
    shopId: "shop-1",
    ...(orderNumber ? { orderNumber } : {}),
    status: "PENDING",
    customerName: "Jane Doe",
    customerEmail: "jane@example.com",
    totalAmountPennies: 2149,
    itemCount: 2,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
}

const WITH_NUMBER_ID = "11111111-2222-3333-4444-555555555555"
const LEGACY_ID = "99999999-8888-7777-6666-555555555555"
const ORDER_NUMBER = "ORD-00000000-20260712-F7C16B7F"

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/orders")) {
      return Promise.resolve({
        data: {
          content: [orderRow(WITH_NUMBER_ID, ORDER_NUMBER), orderRow(LEGACY_ID)],
          totalPages: 1,
          totalElements: 2,
        },
      })
    }
    // shops + products lookups
    return Promise.resolve({ data: { content: [] } })
  })
})

describe("Dashboard orders table ID column (QA-council FIX-5)", () => {
  it("shows the customer-facing order number when the order has one", async () => {
    render(<OrdersPage />)
    expect(await screen.findByText(ORDER_NUMBER)).toBeInTheDocument()
    // The raw truncated UUID must not replace the customer-facing number.
    expect(screen.queryByText("11111111...")).not.toBeInTheDocument()
  })

  it("falls back to the truncated UUID for legacy orders without a number", async () => {
    render(<OrdersPage />)
    expect(await screen.findByText("99999999...")).toBeInTheDocument()
  })
})

// QA-council A11Y-6: <h1>Orders</h1> is followed immediately by CardTitle's
// hard-coded <h3> ("Order Status Flow") — no <h2> in between (axe heading-order).
describe("Orders page — heading hierarchy (QA-council A11Y-6)", () => {
  function headingLevels(): number[] {
    return screen.getAllByRole("heading").map((el) => Number(el.tagName.slice(1)))
  }

  it("never steps DOWN more than one level at a time (no H1 -> H3 skip)", async () => {
    render(<OrdersPage />)
    await screen.findByRole("table")

    const levels = headingLevels()
    // POSITIVE CONTROL: more than one level really is present to check.
    expect(levels.length).toBeGreaterThan(1)
    expect(levels[0]).toBe(1)
    for (let i = 1; i < levels.length; i++) {
      if (levels[i] > levels[i - 1]) {
        expect(levels[i] - levels[i - 1]).toBe(1)
      }
    }
  })
})

describe("Orders table PENDING badge contrast (QA-council F3 / A11Y-1)", () => {
  it("renders the PENDING badge as bg-yellow-700, not the failing bg-yellow-500", async () => {
    render(<OrdersPage />)
    // Scoped to the table: the "Order Status Flow" strip above it also prints
    // the word "Pending" (in a plain white pill, not the Badge component) and
    // is unrelated to this contrast fix.
    const table = await screen.findByRole("table")
    const badges = within(table).getAllByText("Pending")
    expect(badges.length).toBeGreaterThan(0)
    for (const badge of badges) {
      expect(badge).toHaveClass("bg-yellow-700")
      expect(badge).not.toHaveClass("bg-yellow-500")
    }
  })
})

/**
 * Phase 35 — the Index width tier (ORCH-03, orchestrator decision 2026-08-29).
 *
 * This page is the surface the phase exists for. CONTEXT.md section 1 measured
 * the six-column table confined to 1400px at a 2560 viewport with roughly 900px
 * of empty gutter beside it. Plan 35-02 freed the WIDTH at the dashboard band;
 * what these cases assert is the CONTRACT, which is a separate claim.
 *
 * PATTERNS.md F-3 is why the contract needs asserting at all: a spec measuring
 * this page at the band width cannot tell a deliberate resource-index tier from
 * a forgotten narrow cap, because the two read identically. Declaring the tier
 * in the DOM turns "this page is deliberately uncapped" into something a test
 * can find and falsify, rather than an absence nobody can distinguish from a bug.
 */
describe("Orders page — the Index width tier (phase 35)", () => {
  // A Tailwind max-width utility as a whole class TOKEN — never a substring of
  // some longer name, which is the shape that made a naive `container` search
  // match 57 files in this tree (PATTERNS.md F-1).
  const WIDTH_CAP = /(?:^|\s)max-w-/

  async function loadedRoot() {
    const view = render(<OrdersPage />)
    // Wait for the LOADED branch. While the fetch is in flight the component
    // returns its spinner guard, which carries no tier by design — see the note
    // at the declaration site in page.tsx.
    await screen.findByRole("table")
    const declared = view.container.querySelectorAll<HTMLElement>("[data-width-tier]")
    return { view, declared }
  }

  it("declares the index tier once, on the loaded root element itself", async () => {
    const { view, declared } = await loadedRoot()
    // Exactly one: a second, nested declaration would be a cap inside a cap.
    expect(declared).toHaveLength(1)
    expect(declared[0]).toBe(view.container.firstElementChild)
    expect(declared[0]).toHaveAttribute("data-width-tier", "index")
  })

  it("adds no width cap of its own — Index is fluid to the shell band", async () => {
    const { declared } = await loadedRoot()

    // NON-VACUITY CONTROL. The detector is shown firing on a real tier cap
    // first, so the absence below is a statement about this element rather than
    // about a pattern incapable of matching anything.
    const probe = document.createElement("div")
    probe.className = `space-y-6 ${WIDTH_TIER_CLASS.detail}`
    expect(probe.className).toMatch(WIDTH_CAP)

    expect(declared[0].className).not.toMatch(WIDTH_CAP)
  })

  it("keeps the vertical rhythm class the declaration was added beside", async () => {
    const { declared } = await loadedRoot()
    expect(declared[0]).toHaveClass("space-y-6")
  })
})

/**
 * QA-council F4 (dashboard half): FEB-2 Medium + A11Y-3 Medium.
 *
 * FEB-2: the products header is a no-wrap `flex justify-between` — two
 * min-width buttons plus a `text-4xl` h1 exceed a 390px viewport, clipping
 * "Add Product". A structural class assertion is the correct proof HERE
 * (Jest/jsdom does not lay out or measure pixels); the visual "does it
 * actually wrap at 390px" claim is verified separately by Playwright against
 * the rebuilt containers, per this project's web-perf/mobile-first standard.
 *
 * A11Y-3: the `div.overflow-x-auto` table-scroll region is not keyboard
 * reachable (axe `scrollable-region-focusable`, serious) — a keyboard user
 * cannot pan a horizontally-overflowing table with no visible scrollbar
 * affordance and no focus target.
 */
import { render, screen, waitFor } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

describe("Products page header — mobile wrap (F4 / FEB-2)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue({
      data: { content: [], totalElements: 0, totalPages: 0 },
    })
  })

  it("the header row can wrap, with a gap between the wrapped lines", async () => {
    render(<ProductsPage />)
    await waitFor(() => expect(screen.getByText("Products")).toBeInTheDocument())

    const heading = screen.getByRole("heading", { name: "Products", level: 1 })
    // The header row is the heading's grandparent: h1 -> title/subtitle div -> row.
    const headerRow = heading.parentElement?.parentElement
    expect(headerRow).toHaveClass("flex-wrap")
    expect(headerRow).toHaveClass("gap-3")
  })
})

describe("Products page table scroll region — keyboard reachability (F4 / A11Y-3)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [
          {
            id: "prod-1",
            tenantId: "tenant-1",
            sku: "PROD-001",
            title: "Test Product",
            ingredientsText: "Test ingredients",
            allergenMask: 0,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        ],
        totalElements: 1,
        totalPages: 1,
      },
    })
  })

  it("the horizontally-scrolling table region is a keyboard-focusable, named landmark", async () => {
    render(<ProductsPage />)
    const table = await screen.findByRole("table")
    // QA council 20260902-134741 A11Y-5: the region is now the Table
    // primitive's OWN overflow-auto div — the node that actually scrolls —
    // and this page's hand-rolled outer wrapper is gone. Before, the outer
    // wrapper was the region and the primitive's div sat INSIDE it, so the
    // scroller itself was still unfocusable and the route had two nested
    // horizontal scroll containers.
    const scrollRegion = screen.getByRole("region", { name: /products table/i })
    expect(scrollRegion).toHaveAttribute("tabIndex", "0")
    expect(scrollRegion).toBe(table.parentElement)
  })

  it("renders exactly one scroll container around the table — no double nesting", async () => {
    render(<ProductsPage />)
    const table = await screen.findByRole("table")
    const scrollAncestors: Element[] = []
    for (let el = table.parentElement; el; el = el.parentElement) {
      if (/\boverflow-(x-)?auto\b/.test(el.className)) scrollAncestors.push(el)
    }
    expect(scrollAncestors).toHaveLength(1)
    // And that one container is the named region, not an anonymous wrapper.
    expect(scrollAncestors[0]).toHaveAttribute("role", "region")
  })
})

/**
 * #875 / UXT-101: on a phone the first screen of Orders was the "Order Status Flow" explainer, and on a tablet
 * the table was clipped so Total and Actions needed a sideways scroll.
 *
 * jsdom has no layout, so this pins the DECLARED responsive contract (the classes that make the browser do it),
 * and the rendered pixels are the Playwright arm's job:
 *   - below `lg` the explainer sits AFTER the orders (`order-last`), at `lg` and up it is back where it was
 *     (`lg:order-none`), so nothing is removed from any screen;
 *   - the Created column (relative time, the least needed to act on an order) drops out below `xl`, in the
 *     header and in every row together, so Total and Actions keep the room.
 */
import { render, screen, within } from "@testing-library/react"
import OrdersPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))
jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/orders")) {
      return Promise.resolve({
        data: {
          content: [
            {
              id: "11111111-2222-3333-4444-555555555555",
              tenantId: "tenant-1",
              shopId: "shop-1",
              orderNumber: "ORD-1",
              status: "PENDING",
              customerName: "Jane Doe",
              customerEmail: "jane@example.com",
              totalAmountPennies: 2149,
              itemCount: 2,
              createdAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
            },
          ],
          totalPages: 1,
          totalElements: 1,
        },
      })
    }
    return Promise.resolve({ data: { content: [] } })
  })
})

describe("Orders on small screens (UXT-101)", () => {
  it("puts the status-flow explainer after the orders below lg and restores it at lg", async () => {
    render(<OrdersPage />)
    await screen.findByRole("table")

    const flow = screen.getByTestId("order-status-flow")
    expect(flow).toHaveClass("order-last")
    expect(flow).toHaveClass("lg:order-none")
    expect(within(flow).getByText("Order Status Flow")).toBeInTheDocument()
    // It is reordered, not removed: still in the document for every width.
    expect(flow).toBeVisible()
  })

  it("drops only the Created column below xl, header and cells together; Total and Actions never hide", async () => {
    render(<OrdersPage />)
    const table = await screen.findByRole("table")

    const created = within(table).getByRole("columnheader", { name: "Created" })
    expect(created).toHaveClass("hidden")
    expect(created).toHaveClass("xl:table-cell")
    const createdCells = table.querySelectorAll("td[data-column='created']")
    expect(createdCells.length).toBeGreaterThan(0)
    for (const cell of Array.from(createdCells)) {
      expect(cell).toHaveClass("hidden")
      expect(cell).toHaveClass("xl:table-cell")
    }

    for (const name of ["Order ID", "Customer", "Status", "Total", "Actions"]) {
      expect(within(table).getByRole("columnheader", { name })).not.toHaveClass("hidden")
    }
  })
})

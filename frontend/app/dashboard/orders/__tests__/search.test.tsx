/**
 * SW-ADE-10 (swarm epic #964): the Orders list had no search, only a status filter, so a vendor
 * taking a phone enquiry had to scan the table for the caller's order. The search filters the
 * orders ALREADY loaded on the page (no new API) by order number, customer name or email, and
 * works together with the status filter.
 */
import { fireEvent, render, screen, within } from "@testing-library/react"
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

function orderRow(id: string, orderNumber: string, customerName: string, customerEmail: string) {
  return {
    id,
    tenantId: "tenant-1",
    shopId: "shop-1",
    orderNumber,
    status: "PENDING",
    customerName,
    customerEmail,
    totalAmountPennies: 2149,
    itemCount: 2,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
}

const ROWS = [
  orderRow("11111111-0000-0000-0000-000000000001", "ORD-AAAA-0001", "Jane Doe", "jane@example.com"),
  orderRow("22222222-0000-0000-0000-000000000002", "ORD-BBBB-0002", "Kemi Ade", "kemi@bakery.test"),
  orderRow("33333333-0000-0000-0000-000000000003", "ORD-CCCC-0003", "Tunde Bello", "tb@example.org"),
]

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/orders")) {
      return Promise.resolve({ data: { content: ROWS, totalPages: 1, totalElements: 3 } })
    }
    return Promise.resolve({ data: { content: [] } })
  })
})

async function loaded() {
  render(<OrdersPage />)
  const table = await screen.findByRole("table")
  const search = screen.getByRole("searchbox", { name: /search orders/i })
  return { table, search }
}

const rowNumbers = () =>
  ["ORD-AAAA-0001", "ORD-BBBB-0002", "ORD-CCCC-0003"].filter((n) => screen.queryByText(n))

describe("Orders search (SW-ADE-10)", () => {
  it("filters the loaded orders by customer name, case-insensitively", async () => {
    const { search } = await loaded()
    expect(rowNumbers()).toHaveLength(3)
    fireEvent.change(search, { target: { value: "kemi" } })
    expect(rowNumbers()).toEqual(["ORD-BBBB-0002"])
  })

  it("filters by order number and by email", async () => {
    const { search } = await loaded()
    fireEvent.change(search, { target: { value: "cccc" } })
    expect(rowNumbers()).toEqual(["ORD-CCCC-0003"])
    fireEvent.change(search, { target: { value: "@example" } })
    expect(rowNumbers()).toEqual(["ORD-AAAA-0001", "ORD-CCCC-0003"])
  })

  it("says nothing matched, not 'No orders yet', and asks for no new request", async () => {
    const { search } = await loaded()
    const callsBefore = mockedApiClient.get.mock.calls.length
    fireEvent.change(search, { target: { value: "nobody-here" } })
    expect(screen.queryByRole("table")).toBeNull()
    expect(screen.getByText(/no orders match/i)).toBeInTheDocument()
    expect(screen.queryByText("No orders yet")).toBeNull()
    expect(mockedApiClient.get.mock.calls.length).toBe(callsBefore)
  })

  it("keeps the status filter beside the search", async () => {
    const { table } = await loaded()
    expect(within(table).getAllByText("Pending").length).toBe(3)
    expect(screen.getByRole("combobox", { name: /filter orders by status/i })).toBeInTheDocument()
  })
})

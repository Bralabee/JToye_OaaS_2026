/**
 * Dashboard orders table — QA-council FIX-5 (L1, run disc-20260712-010550).
 *
 * Customers quote the ORD-… number from their receipt/confirmation email,
 * but the vendor table's ID column rendered a truncated internal UUID
 * (order.id.substring(0, 8)), so a vendor could not match a phone enquiry
 * to a row. The cell must render the customer-facing orderNumber, falling
 * back to the truncated UUID only for legacy orders without one.
 */
import { render, screen } from "@testing-library/react"
import OrdersPage from "../page"
import apiClient from "@/lib/api-client"

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

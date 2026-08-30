/**
 * QA-council F2 (A11Y-2): the orders list must discriminate a load FAILURE
 * from a genuine empty order book. Before this fix, `fetchData`'s catch block
 * only toasted a raw axios string and left `orders` at its initial `[]`, so a
 * 429 (or any other fetch failure) rendered the identical "No orders yet /
 * Create Order" empty state a vendor with zero orders sees.
 */
import { render, screen, waitFor } from "@testing-library/react"
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

function nonOrderEndpoints(url: string) {
  if (url.startsWith("/api/v1/orders")) return undefined // caller overrides
  return Promise.resolve({ data: { content: [], groupAdmin: true, userId: "u1" } })
}

describe("Orders page — load-failure vs genuine-empty (F2 / A11Y-2)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("shows the error panel, not the empty state, when the initial load 429s", async () => {
    mockedApiClient.get.mockImplementation((url: string) => {
      const fallback = nonOrderEndpoints(url)
      if (fallback) return fallback
      return Promise.reject({ response: { status: 429, headers: {}, data: {} } })
    })

    render(<OrdersPage />)

    expect(await screen.findByTestId("load-error-panel")).toBeInTheDocument()
    expect(screen.queryByText("No orders yet")).not.toBeInTheDocument()
  })

  it("CONTROL: a genuine 200 with zero rows still shows the real empty state", async () => {
    mockedApiClient.get.mockResolvedValue({
      data: { content: [], totalElements: 0, totalPages: 0 },
    })

    render(<OrdersPage />)

    await waitFor(() => {
      expect(screen.getByText("No orders yet")).toBeInTheDocument()
    })
    expect(screen.queryByTestId("load-error-panel")).not.toBeInTheDocument()
  })
})

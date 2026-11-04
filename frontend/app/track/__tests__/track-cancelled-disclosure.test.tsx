/**
 * #820 (owner ruling 2026-11-04): the /track page, like the order page, says why a shop cancelled
 * (the server's plain-English category, never a code) and gives the shop's phone to call.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import TrackOrderPage from "@/app/track/page"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args), post: (...args: unknown[]) => mockGet(...args) },
}))

const mockGetSession = jest.fn()
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => mockGetSession(),
  customerLogin: jest.fn(),
}))

const cancelled = {
  orderNumber: "ORD-CANCEL-0001",
  status: "CANCELLED",
  shopName: "Ada's Kitchen",
  totalAmountPennies: 1850,
  itemCount: 2,
  createdAt: "2026-11-04T10:00:00Z",
  updatedAt: "2026-11-04T10:00:00Z",
  cancellationReason: "This order was cancelled at your request.",
  shopPhone: "07700 900123",
}

async function track(order: Record<string, unknown>) {
  mockGet.mockResolvedValue({ data: order })
  render(<TrackOrderPage />)
  fireEvent.change(await screen.findByLabelText(/order number/i), { target: { value: String(order.orderNumber) } })
  fireEvent.change(screen.getByLabelText(/email/i), { target: { value: "guest@example.com" } })
  fireEvent.click(screen.getByRole("button", { name: /track order/i }))
  await waitFor(() => expect(mockGet).toHaveBeenCalled())
  await screen.findByText("Ada's Kitchen")
}

describe("/track cancelled order (issue #820)", () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockGetSession.mockReset()
    mockGetSession.mockResolvedValue(null)
  })

  it("shows the reason category and a call link", async () => {
    await track(cancelled)

    const notice = await screen.findByTestId("cancelled-notice")
    expect(notice).toHaveTextContent("This order was cancelled at your request.")
    expect(screen.getByRole("link", { name: /call ada's kitchen on 07700 900123/i })).toHaveAttribute("href", "tel:07700900123")
  })

  it("a live order has no cancelled notice", async () => {
    await track({ ...cancelled, status: "CONFIRMED", cancellationReason: undefined, shopPhone: undefined })
    expect(screen.queryByTestId("cancelled-notice")).toBeNull()
  })
})

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

/**
 * PR #1124 review round 1: the person who has JUST pressed Cancel must see what the server recorded.
 * The cancel response carries only status and payment status, so the page re-reads the order; without the
 * re-read they were shown the no-reason, no-phone fallback ("If this was unexpected, please contact the shop").
 */
describe("/track right after the customer cancels (PR #1124 review)", () => {
  const pending = {
    orderNumber: "ORD-CANCEL-0002",
    status: "PENDING",
    paymentStatus: "NONE",
    shopName: "Ada's Kitchen",
    totalAmountPennies: 1850,
    itemCount: 2,
    createdAt: "2026-11-04T10:00:00Z",
    updatedAt: "2026-11-04T10:00:00Z",
  }

  beforeEach(() => {
    mockGet.mockReset()
    mockGetSession.mockReset()
    mockGetSession.mockResolvedValue(null)
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ orderNumber: pending.orderNumber, status: "CANCELLED", paymentStatus: "NONE" }),
    }) as unknown as typeof fetch
  })

  it("shows 'cancelled at your request' and the shop's phone after the cancel, not the fallback", async () => {
    // The first lookup is the PENDING order; every read after the cancel is the server's cancelled record.
    mockGet
      .mockResolvedValueOnce({ data: pending })
      .mockResolvedValue({
        data: {
          ...pending,
          status: "CANCELLED",
          cancellationReason: "This order was cancelled at your request.",
          shopPhone: "07700 900123",
        },
      })
    render(<TrackOrderPage />)
    fireEvent.change(await screen.findByLabelText(/order number/i), { target: { value: pending.orderNumber } })
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: "guest@example.com" } })
    fireEvent.click(screen.getByRole("button", { name: /track order/i }))
    await screen.findByRole("button", { name: /cancel this order/i })

    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))

    const notice = await screen.findByTestId("cancelled-notice")
    await waitFor(() => expect(notice).toHaveTextContent("This order was cancelled at your request."))
    expect(notice).not.toHaveTextContent("please contact the shop")
    expect(screen.getByRole("link", { name: /call ada's kitchen on 07700 900123/i })).toHaveAttribute("href", "tel:07700900123")
    // The re-read is the lookup with the email that proves ownership.
    expect(mockGet).toHaveBeenCalledTimes(2)
  })
})

/**
 * SW-ADE-12 (swarm epic #964): the /track status chip names a status in the customer's words.
 *
 * A card order is a DRAFT until its payment completes (#957). DRAFT is not a tracker step, so the
 * chip fell back to the raw enum and the customer read "DRAFT". The words come from the one shared
 * copy (lib/order-status-announcement.ts), which already says "Awaiting payment".
 */

import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import TrackOrderPage from "@/app/track/page"
import { ORDER_STATUS_COPY } from "@/lib/order-status-announcement"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

const mockGetSession = jest.fn()
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => mockGetSession(),
  customerLogin: jest.fn(),
}))

const draftOrder = {
  orderNumber: "ORD-DRAFT-0001",
  status: "DRAFT",
  shopName: "Ada's Kitchen",
  totalAmountPennies: 1850,
  itemCount: 2,
  createdAt: "2026-10-19T10:00:00Z",
  updatedAt: "2026-10-19T10:00:00Z",
}

async function track(order: Record<string, unknown>) {
  mockGet.mockResolvedValue({ data: order })
  render(<TrackOrderPage />)
  fireEvent.change(await screen.findByLabelText(/order number/i), {
    target: { value: String(order.orderNumber) },
  })
  fireEvent.change(screen.getByLabelText(/email/i), { target: { value: "guest@example.com" } })
  fireEvent.click(screen.getByRole("button", { name: /track order/i }))
  await waitFor(() => expect(mockGet).toHaveBeenCalled())
  await screen.findByText("Ada's Kitchen")
}

describe("/track status chip (SW-ADE-12)", () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockGetSession.mockReset()
    mockGetSession.mockResolvedValue(null)
  })

  it("an unpaid card order reads 'Awaiting payment', never the raw DRAFT enum", async () => {
    await track(draftOrder)

    expect(await screen.findByText(ORDER_STATUS_COPY.DRAFT.label)).toBeInTheDocument()
    expect(screen.queryByText("DRAFT")).not.toBeInTheDocument()
  })
})

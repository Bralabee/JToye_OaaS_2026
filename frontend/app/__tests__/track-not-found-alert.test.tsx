/**
 * #850 remainder (MAR-13): on /track, "Order not found" appeared in a plain div. A screen-reader
 * customer pressed Track order and heard nothing, then had to hunt the page for why nothing came up.
 *
 * It is now a role=alert. A successful lookup stays silent on purpose (37-51: "a lookup is not a
 * change", track-live-region.test.tsx), so only the failure is announced, and the status region stays
 * the page's ONE role=status region.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { useSearchParams } from "next/navigation"
import TrackOrderPage from "@/app/track/page"

const mockLookup = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockLookup(...args),
    post: (...args: unknown[]) => mockLookup(...args),
  },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve(null),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const ORDER_NUMBER = "ORD-NOTFOUND-20261104-00000001"
const EMAIL = "guest@example.com"

beforeEach(() => {
  mockLookup.mockReset()
  ;(useSearchParams as jest.Mock).mockReturnValue({ get: () => null })
  sessionStorage.clear()
  global.fetch = jest.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({}) }) as unknown as typeof fetch
})

async function lookUp() {
  render(<TrackOrderPage />)
  fireEvent.change(await screen.findByLabelText(/order number/i), { target: { value: ORDER_NUMBER } })
  fireEvent.change(screen.getByLabelText(/email/i), { target: { value: EMAIL } })
  fireEvent.click(screen.getByRole("button", { name: /track order/i }))
}

describe("/track announces a failed lookup", () => {
  it("'Order not found' is a role=alert, so it is read out when it appears", async () => {
    mockLookup.mockRejectedValue(Object.assign(new Error("404"), { response: { status: 404 } }))
    await lookUp()
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("Order not found. Check your order number and email address.")
  })

  it("CONTROL: before any lookup there is no alert (an always-mounted alert is announced on load)", async () => {
    render(<TrackOrderPage />)
    await screen.findByLabelText(/order number/i)
    expect(screen.queryByRole("alert")).toBeNull()
  })

  it("the not-found message does not become a second status region", async () => {
    mockLookup.mockRejectedValue(new Error("404"))
    await lookUp()
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument())
    expect(screen.getAllByRole("status")).toHaveLength(1)
  })
})

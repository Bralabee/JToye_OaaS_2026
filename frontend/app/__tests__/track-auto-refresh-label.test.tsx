/**
 * #877 UXT-111 (persona close-out 2026-11-04): /track said "Auto-refreshing" on a COMPLETED order. It
 * does not refresh a finished order (the poll stops on COMPLETED, CANCELLED and REFUNDED; only the label
 * ignored that), so the page described something that was not happening.
 *
 * (The React #418 hydration error the same finding names was not reproduced here and has no test: it was
 * reported SUSPECTED on /shop/orders and not located. Recorded in the quick task's SUMMARY.)
 */
import { fireEvent, render, screen } from "@testing-library/react"
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

const ORDER_NUMBER = "ORD-AUTOREFRESH-20261104-0001"
const BASE = {
  orderNumber: ORDER_NUMBER,
  shopName: "Ada's Kitchen",
  totalAmountPennies: 1850,
  itemCount: 2,
  unitCount: 2,
  createdAt: "2026-11-04T10:00:00Z",
  updatedAt: "2026-11-04T10:00:05Z",
}

beforeEach(() => {
  mockLookup.mockReset()
  ;(useSearchParams as jest.Mock).mockReturnValue({ get: () => null })
  sessionStorage.clear()
  global.fetch = jest.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({}) }) as unknown as typeof fetch
})

async function lookUp(status: string) {
  mockLookup.mockResolvedValue({ data: { ...BASE, status } })
  render(<TrackOrderPage />)
  fireEvent.change(await screen.findByLabelText(/order number/i), { target: { value: ORDER_NUMBER } })
  fireEvent.change(screen.getByLabelText(/email/i), { target: { value: "guest@example.com" } })
  fireEvent.click(screen.getByRole("button", { name: /track order/i }))
  await screen.findByText("Ada's Kitchen")
}

describe("the 'Auto-refreshing' label says what the page is doing", () => {
  it.each(["PENDING", "CONFIRMED", "PREPARING", "READY"])("an in-flight %s order says it is auto-refreshing", async (status) => {
    await lookUp(status)
    expect(screen.getByText("Auto-refreshing")).toBeInTheDocument()
  })

  it("a COMPLETED order does not claim to refresh", async () => {
    await lookUp("COMPLETED")
    // Non-vacuity: the result rendered (the stepper is there), so the absence is about the label.
    expect(screen.getByRole("list", { name: /order progress/i })).toBeInTheDocument()
    expect(screen.queryByText("Auto-refreshing")).toBeNull()
  })
})

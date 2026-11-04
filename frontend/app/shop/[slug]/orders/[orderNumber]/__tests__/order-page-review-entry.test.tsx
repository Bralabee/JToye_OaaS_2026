/**
 * #846: the order's own page offers "Rate your order" to a SIGNED-IN customer once the order is COMPLETED,
 * the second place the entry point lives (My Orders is the first).
 *
 * A guest (no session) is not offered it: the review is posted with the customer's token, which a guest
 * does not have, so a form they could fill in and then be refused at would be worse than none. An order that
 * is not COMPLETED is not offered it either: the server refuses it, and the button should not promise it.
 */
import { Suspense } from "react"
import { render, screen } from "@testing-library/react"
import OrderTrackingPage from "@/app/shop/[slug]/orders/[orderNumber]/page"
import { getCustomerSession } from "@/lib/customer-auth"

const mockLookup = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockLookup(...args), post: (...args: unknown[]) => mockLookup(...args) },
}))
jest.mock("@/lib/customer-auth", () => ({ getCustomerSession: jest.fn() }))

const SLUG = "mama-ade"
const ORDER_NUMBER = "ORD-REVIEW-20261104-00000001"

const order = (status: string) => ({
  orderNumber: ORDER_NUMBER,
  status,
  paymentStatus: "NONE",
  shopName: "Mama Ade's Kitchen",
  fulfilmentType: "COLLECTION",
  totalAmountPennies: 1200,
  itemCount: 1,
  unitCount: 1,
  createdAt: "2026-11-04T10:00:00Z",
  updatedAt: "2026-11-04T10:00:05Z",
})

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function renderPage(status: string, signedIn: boolean) {
  mockLookup.mockImplementation((url: string) =>
    url === `/public/orders/${ORDER_NUMBER}/lookup` ? Promise.resolve({ data: order(status) }) : Promise.reject(new Error(`unexpected ${url}`))
  )
  ;(getCustomerSession as jest.Mock).mockResolvedValue(
    signedIn ? { profile: { sub: "u1", email: "ade@example.com", name: "Ade" }, expiresAt: 9999999999 } : null
  )
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem(`jtoye-checkout-email-${SLUG}`, "ade@example.com")
  return render(
    <Suspense fallback={<div>loading</div>}>
      <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
    </Suspense>
  )
}

describe("Rate your order on the order page", () => {
  it("signed in + COMPLETED: offered", async () => {
    renderPage("COMPLETED", true)
    expect(await screen.findByRole("button", { name: /rate your order/i })).toBeInTheDocument()
  })

  it("CONTROL: the same page for a guest has no review entry", async () => {
    renderPage("COMPLETED", false)
    await screen.findByText("Mama Ade's Kitchen")
    expect(screen.queryByRole("button", { name: /rate your order/i })).toBeNull()
  })

  it.each(["PENDING", "PREPARING", "READY", "CANCELLED"])("signed in but %s: not offered", async (status) => {
    renderPage(status, true)
    await screen.findByText("Mama Ade's Kitchen")
    expect(screen.queryByRole("button", { name: /rate your order/i })).toBeNull()
  })
})

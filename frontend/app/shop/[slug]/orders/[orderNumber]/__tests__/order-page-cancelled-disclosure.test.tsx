/**
 * #820 (owner ruling 2026-11-04): when a shop cancels, the order page says WHY in the shop's category
 * words (the server maps the cancel reason code to plain English; the page prints that text and never a
 * code) and gives the shop's public phone as a tap-to-call link. Nothing is invented: an order with no
 * recorded reason says no reason, and a shop with no phone gets no call line.
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
const ORDER_NUMBER = "ORD-CANCEL-20261104-00000001"

const order = (extra: Record<string, unknown>) => ({
  orderNumber: ORDER_NUMBER,
  status: "CANCELLED",
  paymentStatus: "NONE",
  shopName: "Mama Ade's Kitchen",
  fulfilmentType: "COLLECTION",
  totalAmountPennies: 1200,
  itemCount: 1,
  unitCount: 1,
  createdAt: "2026-11-04T10:00:00Z",
  updatedAt: "2026-11-04T10:00:05Z",
  ...extra,
})

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function renderPage(data: Record<string, unknown>) {
  mockLookup.mockImplementation((url: string) =>
    url === `/public/orders/${ORDER_NUMBER}/lookup` ? Promise.resolve({ data }) : Promise.reject(new Error(`unexpected ${url}`))
  )
  ;(getCustomerSession as jest.Mock).mockResolvedValue(null)
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem(`jtoye-checkout-email-${SLUG}`, "ade@example.com")
  return render(
    <Suspense fallback={<div>loading</div>}>
      <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
    </Suspense>
  )
}

describe("Cancelled order page: reason category and the shop's phone (issue #820)", () => {
  it("prints the server's plain-English reason and a call link with the shop's phone", async () => {
    renderPage(order({ cancellationReason: "The shop could not make or deliver this order.", shopPhone: "0121 496 0000" }))

    const notice = await screen.findByTestId("cancelled-notice")
    expect(notice).toHaveTextContent("This order was cancelled.")
    expect(notice).toHaveTextContent("The shop could not make or deliver this order.")
    const call = screen.getByRole("link", { name: /call mama ade's kitchen on 0121 496 0000/i })
    expect(call).toHaveAttribute("href", "tel:01214960000")
  })

  it("no recorded reason: no reason sentence, but the phone is still offered", async () => {
    renderPage(order({ shopPhone: "0121 496 0000" }))

    const notice = await screen.findByTestId("cancelled-notice")
    expect(notice).toHaveTextContent("This order was cancelled.")
    expect(notice).not.toHaveTextContent(/could not|at your request|another reason/)
    expect(screen.getByRole("link", { name: /0121 496 0000/ })).toBeInTheDocument()
  })

  it("no phone: the old 'contact the shop' sentence, no call link, no 'null'", async () => {
    renderPage(order({ cancellationReason: "The shop cancelled this order for another reason." }))

    const notice = await screen.findByTestId("cancelled-notice")
    expect(notice).toHaveTextContent("The shop cancelled this order for another reason.")
    expect(notice).toHaveTextContent("please contact the shop")
    expect(screen.queryByRole("link", { name: /call/i })).toBeNull()
    expect(notice).not.toHaveTextContent("null")
  })
})

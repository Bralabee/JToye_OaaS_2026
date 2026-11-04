/**
 * "Order again" (#845): /shop/{slug}/reorder/{orderNumber}, reached from a past order on My Orders.
 *
 * It reads the order's lines through the signed-in proxy (no email anywhere), reads the shop's live menu,
 * puts what is still available into THIS shop's basket through the basket provider's own write path, and
 * says what changed: what could not be added, what costs a different price now, what was capped. It never
 * checks out for the customer.
 *
 * IDENTITY. The basket is user-scoped client-persisted state with an owner stamp (R-16). This page adds a
 * second WAY to put items in the basket, so the stamp is asserted by content in localStorage after an
 * order-again by a signed-in customer: it must be that customer, and the page must not have written the
 * basket any other way.
 */
import { Suspense } from "react"
import { act, render, screen, waitFor, within } from "@testing-library/react"

import ReorderPage from "@/app/shop/[slug]/reorder/[orderNumber]/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import { CUSTOMER_ID_KEY, CUSTOMER_MARKER_KEY, CUSTOMER_EXPIRES_KEY } from "@/lib/cart-identity"
import publicApiClient from "@/lib/public-api-client"

jest.mock("@/lib/public-api-client", () => ({ __esModule: true, default: { get: jest.fn() } }))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedGet = publicApiClient.get as jest.Mock
const fetchMock = jest.fn()

const SLUG = "mama-ades-kitchen"
const ORDER = "ORD-20261104-ABC123"

const MENU = {
  Mains: [
    { id: "p1", title: "Jollof Rice", pricePennies: 1000, inStock: true, category: "Mains", imageUrl: null, vatRate: "ZERO" },
    { id: "p2", title: "Moin Moin", pricePennies: 800, inStock: true, category: "Mains", imageUrl: null },
    { id: "p3", title: "Suya", pricePennies: 1200, inStock: false, category: "Mains", imageUrl: null },
  ],
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function respond(status: number, body: unknown) {
  fetchMock.mockResolvedValue({ ok: status >= 200 && status < 300, status, json: async () => body })
}

const LINES = {
  orderNumber: ORDER,
  shopSlug: SLUG,
  lines: [
    { productId: "p1", productName: "Jollof Rice", quantity: 2, unitPricePennies: 1000 },
    { productId: "p2", productName: "Moin Moin", quantity: 1, unitPricePennies: 700 },
    { productId: "p3", productName: "Suya", quantity: 1, unitPricePennies: 1200 },
    { productId: "gone", productName: "Egusi", quantity: 1, unitPricePennies: 900 },
  ],
}

function renderPage() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <ReorderPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER })} />
      </CartProvider>
    </Suspense>
  )
}

const storedCart = () => JSON.parse(localStorage.getItem(`jtoye-cart-${SLUG}`) ?? "null")

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  mockedGet.mockReset()
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: url.endsWith("/products") ? MENU : { slug: SLUG, name: "Mama Ade's Kitchen", maxLineQuantity: 20 } })
  )
  fetchMock.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
})

describe("a signed-in customer orders again", () => {
  it("fills the basket from the live menu, once, and says what changed", async () => {
    localStorage.setItem(CUSTOMER_ID_KEY, "user-A")
    localStorage.setItem(CUSTOMER_MARKER_KEY, "true")
    localStorage.setItem(CUSTOMER_EXPIRES_KEY, String(Math.floor(Date.now() / 1000) + 300))
    respond(200, LINES)
    renderPage()

    const result = await screen.findByTestId("reorder-result")
    // The proxy was asked for this shop's order, with no email, and nothing else was fetched with identity.
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toBe(`/api/customer-orders/reorder?shop=${SLUG}&order=${ORDER}`)
    expect(url).not.toMatch(/@|email/i)

    // The basket: Jollof x2 and Moin Moin x1 at the MENU's prices; Suya (sold out) and Egusi (gone) not added.
    await waitFor(() => expect(storedCart()?.items).toHaveLength(2))
    const items = storedCart().items as Array<{ productId: string; quantity: number; pricePennies: number }>
    expect(items.find((i) => i.productId === "p1")).toMatchObject({ quantity: 2, pricePennies: 1000 })
    expect(items.find((i) => i.productId === "p2")).toMatchObject({ quantity: 1, pricePennies: 800 })
    expect(items.map((i) => i.productId)).not.toContain("p3")
    // IDENTITY: stamped by the provider's own write, as THIS customer.
    expect(storedCart().owner).toBe("user-A")

    // What changed, in words.
    expect(result).toHaveTextContent("Added 3 items to your basket")
    expect(within(result).getByTestId("reorder-unavailable")).toHaveTextContent("Suya")
    expect(within(result).getByTestId("reorder-unavailable")).toHaveTextContent("Egusi")
    expect(within(result).getByTestId("reorder-price-changed")).toHaveTextContent("Moin Moin")
    expect(within(result).getByTestId("reorder-price-changed")).toHaveTextContent("£7.00")
    expect(within(result).getByTestId("reorder-price-changed")).toHaveTextContent("£8.00")
    // The next step is the customer's: links, never an automatic checkout.
    expect(within(result).getByRole("link", { name: /go to basket/i })).toHaveAttribute("href", `/shop/${SLUG}/cart`)
    expect(within(result).getByRole("link", { name: /go to checkout|check out/i })).toHaveAttribute("href", `/shop/${SLUG}/checkout`)
    // Announced.
    expect(screen.getByRole("status")).toBeInTheDocument()
  })

  it("adds to what is already in the basket and says so (it does not silently replace it)", async () => {
    localStorage.setItem(
      `jtoye-cart-${SLUG}`,
      JSON.stringify({ shopSlug: SLUG, items: [{ productId: "p2", title: "Moin Moin", pricePennies: 800, quantity: 1, imageUrl: null, category: "Mains" }] })
    )
    respond(200, LINES)
    renderPage()
    const result = await screen.findByTestId("reorder-result")
    expect(result).toHaveTextContent(/already in your basket/i)
    await waitFor(() => expect(storedCart().items.find((i: { productId: string }) => i.productId === "p2").quantity).toBe(2))
  })

  it("does not add the lines twice when the effect replays (StrictMode)", async () => {
    respond(200, LINES)
    render(
      <Suspense fallback={<div>loading</div>}>
        <CartProvider shopSlug={SLUG}>
          <ReorderPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER })} />
        </CartProvider>
      </Suspense>,
      { reactStrictMode: true } as never
    )
    await screen.findByTestId("reorder-result")
    await waitFor(() => expect(storedCart()?.items).toHaveLength(2))
    expect(storedCart().items.find((i: { productId: string }) => i.productId === "p1").quantity).toBe(2)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("when nothing on the order is available any more it adds nothing and says so", async () => {
    respond(200, { ...LINES, lines: [LINES.lines[2], LINES.lines[3]] })
    renderPage()
    const result = await screen.findByTestId("reorder-result")
    expect(result).toHaveTextContent(/nothing from this order is available/i)
    expect(storedCart()?.items ?? []).toHaveLength(0)
    expect(within(result).queryByRole("link", { name: /go to checkout|check out/i })).toBeNull()
    expect(within(result).getByRole("link", { name: /see the menu/i })).toHaveAttribute("href", `/shop/${SLUG}`)
  })
})

describe("it never leaves the customer on a blank page", () => {
  it("a lapsed session (401) asks them to sign in again, with the way back", async () => {
    respond(401, { error: "not_authenticated" })
    renderPage()
    expect(await screen.findByRole("heading", { name: /sign in to continue/i })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /^sign in$/i })).toHaveAttribute(
      "href",
      `/shop/signin?next=${encodeURIComponent(`/shop/${SLUG}/reorder/${ORDER}`)}`
    )
    expect(screen.queryByTestId("reorder-result")).toBeNull()
    expect(storedCart()?.items ?? []).toHaveLength(0)
  })

  it("an order that is not theirs, or does not exist (404), says it could not find it", async () => {
    respond(404, { detail: "Order not found" })
    renderPage()
    expect(await screen.findByRole("alert")).toHaveTextContent(/couldn.t find that order/i)
    expect(storedCart()?.items ?? []).toHaveLength(0)
  })

  it("a server fault says so and offers a retry that asks again", async () => {
    respond(502, { error: "upstream_unavailable" })
    renderPage()
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/couldn.t load that order/i)
    respond(200, LINES)
    await act(async () => {
      within(alert).getByRole("button", { name: /try again/i }).click()
    })
    await screen.findByTestId("reorder-result")
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

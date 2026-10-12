/**
 * Phase 37-30 (UI-SPEC § D1 "Cash cap 429" / "Order limit 422"; D-18, D-20; RWO-041, RWO-042).
 *
 * The server now refuses two things a checkout can run into on a cash order, each with its own
 * RFC 7807 type, and the customer must read an honest sentence for each — never the rate-limiter's
 * "wait N seconds", never a retry loop, never a literal held by the client:
 *
 *   429 https://jtoye.uk/errors/order-cap-exceeded  {axis: EMAIL | PHONE | IP, cap}   (37-27, 37-28)
 *       This customer (or this connection) already has the most open cash orders the shop allows.
 *       No Retry-After, by design: a slot frees when an order completes, not when time passes, so
 *       the client must NOT auto-retry it (Pitfall 8) and the basket is kept.
 *   422 https://jtoye.uk/errors/order-limit-exceeded {field, value, limit, productName}  (37-26)
 *       The order is bigger than the shop accepts online. The copy interpolates the limit (and the
 *       item) FROM THE BODY — the client holds no limits as literals, so the arm that changes the
 *       body's limit must see the copy change.
 *
 * Also here: the signed-in customer's order goes through the same-origin proxy that can add the
 * X-Customer-Token from the HttpOnly cookie (the /orders/mine shape), so 37-27 can record a verified
 * contact; a guest's goes straight to core. Driven through the REAL checkout page and CartProvider.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
}))

jest.mock("@/lib/order-history", () => ({ saveLocalOrder: jest.fn() }))

jest.mock("@stripe/stripe-js/pure", () => ({ loadStripe: jest.fn(() => Promise.resolve(null)) }))
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

const mockedGet = publicApiClient.get as jest.Mock
const mockedPost = publicApiClient.post as jest.Mock
const mockedSession = getCustomerSession as jest.Mock

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

const CATALOGUE = { Mains: [{ id: "p1", allergenMask: 0 }] }

const SHOP = {
  slug: SLUG,
  name: "Jollof Express",
  description: null,
  address: null,
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 0,
  deliveryFeePennies: 300,
  freeDeliveryThresholdPennies: 3000,
  tags: null,
}

function mockShop(shop: unknown) {
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? CATALOGUE : shop })
  )
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function seedCart() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [{ productId: "p1", title: "Suya", pricePennies: 1000, quantity: 1, imageUrl: null, category: "Mains" }],
    })
  )
}

function renderCheckout() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CheckoutPage params={resolvedThenable({ slug: SLUG })} />
      </CartProvider>
    </Suspense>
  )
}

async function armCheckout() {
  await screen.findByRole("button", { name: /place order/i })
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900000" } })
  fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))
}

function placeOrder() {
  fireEvent.click(screen.getByRole("button", { name: /place order/i }))
}

/** A typed refusal as axios surfaces it. No Retry-After on the cap, by the server's design. */
function problem(status: number, data: Record<string, unknown>, headers: Record<string, string> = {}) {
  return { response: { status, data, headers } }
}

const capProblem = (axis: "EMAIL" | "PHONE" | "IP") =>
  problem(429, {
    type: "https://jtoye.uk/errors/order-cap-exceeded",
    title: "Too many open cash orders",
    status: 429,
    detail: `This customer has reached the limit of open cash orders (${axis}). Try again once one is completed.`,
    code: "ORDER_CAP_EXCEEDED",
    axis,
    cap: 3,
  })

const limitProblem = (field: string, value: number, limit: number, productName: string | null) =>
  problem(422, {
    type: "https://jtoye.uk/errors/order-limit-exceeded",
    title: "Order too large",
    status: 422,
    detail: `${field} is ${value}, above the limit of ${limit}.`,
    code: "ORDER_LIMIT_EXCEEDED",
    field,
    value,
    limit,
    productName,
  })

/** Lets any stray retry timer fire; a second POST would show up here. */
async function settle() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 30))
  })
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
  seedCart()
})

afterEach(() => {
  localStorage.clear()
})

describe("Checkout: the cash-order cap 429 (UI-SPEC § D1, § Copywriting C1-C4)", () => {
  it("axis EMAIL with card payments offered: the customer copy ending ', or pay by card.'; exactly ONE POST; the basket is kept", async () => {
    mockShop({ ...SHOP, acceptsCardPayments: true })
    mockedPost.mockRejectedValue(capProblem("EMAIL"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(
      "You already have the most open cash orders this shop allows for one customer. You can order again once one is completed, or pay by card."
    )
    // Never the rate-limiter's copy: there is nothing to wait for.
    expect(alert).not.toHaveTextContent(/wait|seconds/i)

    await settle()
    expect(mockedPost).toHaveBeenCalledTimes(1)
    // The basket is kept: the form is still here, and the stored cart still holds the line.
    expect(screen.getByRole("button", { name: /place order/i })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) as string).items).toHaveLength(1)
  })

  it("axis PHONE without card payments: the same copy with no ', or pay by card'", async () => {
    mockShop({ ...SHOP, acceptsCardPayments: false })
    mockedPost.mockRejectedValue(capProblem("PHONE"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    const alert = await screen.findByRole("alert")
    expect(alert.textContent?.trim()).toBe(
      "You already have the most open cash orders this shop allows for one customer. You can order again once one is completed."
    )
  })

  it("axis IP: the connection copy, with the card suffix only when cards are accepted", async () => {
    mockShop({ ...SHOP, acceptsCardPayments: true })
    mockedPost.mockRejectedValue(capProblem("IP"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    const alert = await screen.findByRole("alert")
    expect(alert.textContent?.trim()).toBe(
      "Too many cash orders have been placed from this connection. Try again later, or pay by card."
    )
    await settle()
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })
})

describe("Checkout: the order-limit 422 reads its numbers from the body (UI-SPEC § D1)", () => {
  it("field quantity: 'You can order up to {limit} of one item. Reduce {item} to {limit} or fewer.'", async () => {
    mockShop(SHOP)
    mockedPost.mockRejectedValue(limitProblem("quantity", 15, 12, "Suya"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "You can order up to 12 of one item. Reduce Suya to 12 or fewer."
    )
  })

  it("LITERAL-FREE ARM: a different limit in the body changes the copy", async () => {
    mockShop(SHOP)
    mockedPost.mockRejectedValue(limitProblem("quantity", 15, 7, "Suya"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("You can order up to 7 of one item. Reduce Suya to 7 or fewer.")
    expect(alert).not.toHaveTextContent("12")
  })

  it("field total: the £ copy from the body's limit in pennies", async () => {
    mockShop(SHOP)
    mockedPost.mockRejectedValue(limitProblem("total", 30000, 25000, null))
    renderCheckout()
    await armCheckout()

    placeOrder()
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This order is over this shop's online limit of £250.00. Contact the shop for large orders."
    )
  })

  it("fields units and lines: 'bigger than this shop accepts online (up to {limit} items/lines)'", async () => {
    mockShop(SHOP)
    mockedPost.mockRejectedValueOnce(limitProblem("units", 45, 40, null))
    const view = renderCheckout()
    await armCheckout()

    placeOrder()
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This order is bigger than this shop accepts online (up to 40 items). Contact the shop for large orders."
    )
    view.unmount()

    seedCart()
    mockedPost.mockRejectedValueOnce(limitProblem("lines", 25, 20, null))
    renderCheckout()
    await armCheckout()
    placeOrder()
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This order is bigger than this shop accepts online (up to 20 lines). Contact the shop for large orders."
    )
  })
})

describe("Checkout: the signed-in customer's order carries the customer token (37-27, T-37-76)", () => {
  it("signed in: the POST goes to the same-origin proxy that adds X-Customer-Token from the HttpOnly cookie", async () => {
    mockShop(SHOP)
    mockedSession.mockResolvedValue({
      profile: { sub: "cust-1", email: "ade@example.com", name: "Ade Johnson", emailVerified: true },
      expiresAt: Date.now() + 60_000,
    })
    mockedPost.mockRejectedValue(new Error("stop here"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    const [url, , config] = mockedPost.mock.calls[0] as [string, unknown, { headers?: Record<string, unknown> }]
    expect(url).toBe(`${window.location.origin}/api/customer-orders?shop=${SLUG}`)
    // The browser never holds the token (HttpOnly): it is NOT on this request. The proxy adds it.
    expect(config?.headers?.["X-Customer-Token"]).toBeUndefined()
    expect(typeof config?.headers?.["Idempotency-Key"]).toBe("string")
  })

  it("guest: the POST goes straight to core, with no customer header anywhere", async () => {
    mockShop(SHOP)
    mockedPost.mockRejectedValue(new Error("stop here"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    const [url, , config] = mockedPost.mock.calls[0] as [string, unknown, { headers?: Record<string, unknown> }]
    expect(url).toBe(`/public/shops/${SLUG}/orders`)
    expect(config?.headers?.["X-Customer-Token"]).toBeUndefined()
  })
})

/**
 * UI-SPEC § UI Considerations "long-text" (D1, backstop): the limit refusal naming a 60-character
 * item must wrap inside the checkout error slot at 375px. jsdom lays nothing out, so this is the
 * held-out render the spec asks for: the copy is present IN FULL and the slot carries no class
 * that would clip or refuse to wrap it (truncate / whitespace-nowrap / overflow-hidden). The
 * visual check at 375px is 37-31's Playwright spec.
 */
describe("Checkout: a long item name in the limit refusal (UI-SPEC D1 long-text backstop)", () => {
  it("renders the 60-character item in full inside an error slot that can wrap", async () => {
    const longName = "Jollof rice with grilled plantain, suya and extra pepper sauce" // 60+ chars
    expect(longName.length).toBeGreaterThanOrEqual(60)
    mockShop(SHOP)
    mockedPost.mockRejectedValue(limitProblem("quantity", 15, 12, longName))
    renderCheckout()
    await armCheckout()

    placeOrder()
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(`Reduce ${longName} to 12 or fewer.`)
    for (const cls of ["truncate", "whitespace-nowrap", "overflow-hidden", "text-ellipsis"]) {
      expect(alert).not.toHaveClass(cls)
    }
  })
})

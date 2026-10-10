/**
 * 37-22 Task 3 (UI-SPEC K3 "Checkout" and "Checkout race"; D-15; UXT-022 customer half): a customer
 * cannot try to order from a shop that is not taking orders by accident, and a shop that stops
 * while they are on the page costs them nothing.
 *
 *  - At page load the shop's server answer (`openNow` false, or a reported pause) puts the K3 notice
 *    at the top of step 1 and disables Place order, with the notice as the button's permanent
 *    explanation (`aria-describedby`): the `belowMinimum` precedent, disabled only where the reason
 *    sits beside it.
 *  - During checkout 37-20's typed refusal (409 `https://jtoye.uk/errors/shop-closed`, with
 *    `reason` and `reopensAt`) renders the race copy in the page's existing `role="alert"` slot,
 *    and the stored basket is unchanged BY CONTENT (the client-persisted identity contract: the
 *    stored value, ownership stamp included, not the rendered list).
 *
 * The 409 body below is exactly what GlobalExceptionHandler.handleShopClosed publishes.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"

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

const SLUG = "rosies-kitchen"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

/** Tuesday 13 October 2026, 18:20 in the UK (BST). */
const TUESDAY_EVENING = new Date("2026-10-13T17:20:00Z")

/** Fake ONLY the clock: React, RTL and promises keep real timers. */
function freezeClockAt(now: Date) {
  jest.useFakeTimers({
    now,
    doNotFake: [
      "setTimeout",
      "clearTimeout",
      "setInterval",
      "clearInterval",
      "setImmediate",
      "clearImmediate",
      "nextTick",
      "queueMicrotask",
      "requestAnimationFrame",
      "cancelAnimationFrame",
      "requestIdleCallback",
      "cancelIdleCallback",
      "hrtime",
      "performance",
    ],
  })
}

const BASE_SHOP = {
  slug: SLUG,
  name: "Rosie's Kitchen",
  description: null,
  address: null,
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  // "always open" to the old client parser: only the server's fields can close this shop.
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 0,
  deliveryFeePennies: 0,
  freeDeliveryThresholdPennies: 0,
  tags: null,
  acceptsCardPayments: false,
}

const OPEN = { ...BASE_SHOP, openNow: true, nextOpenAt: null, pausedUntil: null, pausedIndefinitely: false }
const CLOSED = {
  ...BASE_SHOP,
  openNow: false,
  nextOpenAt: "2026-10-14T08:00:00Z",
  pausedUntil: null,
  pausedIndefinitely: false,
}
const PAUSED = {
  ...BASE_SHOP,
  openNow: false,
  nextOpenAt: "2026-10-13T17:40:00Z",
  pausedUntil: "2026-10-13T17:40:00Z",
  pausedIndefinitely: false,
}

const CATALOGUE = { Mains: [{ id: "p1", title: "Jollof Rice", allergenMask: 0, pricePennies: 1000 }] }

function serve(shop: unknown) {
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? CATALOGUE : shop })
  )
}

function shopClosed409(reason: string, reopensAt: string | null) {
  return {
    response: {
      status: 409,
      data: {
        type: "https://jtoye.uk/errors/shop-closed",
        title: "Shop not taking orders",
        status: 409,
        detail: `Rosie's Kitchen isn't taking orders (paused). ${reopensAt ? "Reopens at 18:40." : "No reopening time is set."}`,
        code: "SHOP_CLOSED",
        reason,
        reopensAt,
      },
    },
  }
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
      items: [{ productId: "p1", title: "Jollof Rice", pricePennies: 1000, quantity: 2, imageUrl: null, category: "Mains" }],
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

const placeOrder = () => screen.getByRole("button", { name: /place order/i })
const notice = () => screen.queryByTestId("shop-availability-notice")

/** Collection (no address), contact details, and the allergen acknowledgement. */
async function fillAndAcknowledge() {
  await screen.findByRole("button", { name: /place order/i })
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ravi Patel" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ravi@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900123" } })
  fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  seedCart()
})

afterEach(() => {
  localStorage.clear()
  jest.useRealTimers()
})

describe("checkout when the server says the shop is not taking orders at page load (K3)", () => {
  it("closed: Place order is disabled and described by the notice at the top of step 1", async () => {
    serve(CLOSED)
    renderCheckout()
    await waitFor(() => expect(placeOrder()).toBeDisabled())

    const describedBy = placeOrder().getAttribute("aria-describedby") ?? ""
    expect(describedBy.split(/\s+/)).toContain("shop-availability-explainer")
    const explainer = document.getElementById("shop-availability-explainer")
    expect(explainer).not.toBeNull()
    expect(explainer).toBe(notice())
    expect(explainer).toHaveTextContent("Closed now")
    expect(explainer).toHaveTextContent(/Opens Wednesday at 09:00\./)
    // Top of step 1: before the first step-1 control (the fulfilment toggle).
    const firstControl = screen.getByRole("button", { name: /delivery/i })
    expect(explainer!.compareDocumentPosition(firstControl) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("paused: Place order is disabled with '{Shop} isn't taking orders right now'", async () => {
    freezeClockAt(TUESDAY_EVENING)
    serve(PAUSED)
    renderCheckout()
    await waitFor(() => expect(placeOrder()).toBeDisabled())
    expect(notice()).toHaveTextContent("Rosie's Kitchen isn't taking orders right now")
    expect(notice()).toHaveTextContent("Back at 18:40.")
    expect(placeOrder().getAttribute("aria-describedby")).toContain("shop-availability-explainer")
  })

  it("a disabled Place order sends nothing, even through the form's submit", async () => {
    serve(CLOSED)
    renderCheckout()
    await fillAndAcknowledge()
    await waitFor(() => expect(placeOrder()).toBeDisabled())
    fireEvent.submit(placeOrder().closest("form")!)
    await new Promise((r) => setTimeout(r, 0))
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("open: Place order is enabled and no explanation is pointed at", async () => {
    serve(OPEN)
    renderCheckout()
    await screen.findByRole("button", { name: /place order/i })
    await waitFor(() => expect(mockedGet).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 0))
    expect(placeOrder()).not.toBeDisabled()
    expect(notice()).toBeNull()
    expect(placeOrder().getAttribute("aria-describedby") ?? "").not.toContain("shop-availability-explainer")
  })

  it("openNow absent (an older backend): enabled, no notice — the server still decides at submit", async () => {
    serve(BASE_SHOP)
    renderCheckout()
    await screen.findByRole("button", { name: /place order/i })
    await new Promise((r) => setTimeout(r, 0))
    expect(placeOrder()).not.toBeDisabled()
    expect(notice()).toBeNull()
  })
})

describe("the checkout race: the shop stops while the customer is on the page (typed shop-closed)", () => {
  it("renders the race copy in the role=alert slot and keeps the stored basket, by content", async () => {
    freezeClockAt(TUESDAY_EVENING)
    serve(OPEN)
    mockedPost.mockRejectedValueOnce(shopClosed409("PAUSED", "2026-10-13T17:40:00Z"))
    renderCheckout()
    await fillAndAcknowledge()
    await waitFor(() => expect(placeOrder()).not.toBeDisabled())

    const storedBefore = localStorage.getItem(STORAGE_KEY)
    expect(storedBefore).not.toBeNull()
    expect(JSON.parse(storedBefore!).items).toHaveLength(1)

    fireEvent.click(placeOrder())
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(
      "Rosie's Kitchen stopped taking orders while you were checking out. Your basket is saved. Back at 18:40."
    )
    // The basket on disk is byte-for-byte what it was: nothing cleared it, nothing re-stamped it.
    expect(localStorage.getItem(STORAGE_KEY)).toBe(storedBefore)
    expect(screen.queryByText(/order confirmed/i)).not.toBeInTheDocument()
  })

  it("a closed shop with no reopening time ends the race copy with 'Check back later.'", async () => {
    serve(OPEN)
    mockedPost.mockRejectedValueOnce(shopClosed409("NO_HOURS", null))
    renderCheckout()
    await fillAndAcknowledge()
    await waitFor(() => expect(placeOrder()).not.toBeDisabled())
    fireEvent.click(placeOrder())
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(
      "Rosie's Kitchen stopped taking orders while you were checking out. Your basket is saved. Check back later."
    )
  })

  it("a closed shop that reopens another day names the day", async () => {
    freezeClockAt(TUESDAY_EVENING)
    serve(OPEN)
    mockedPost.mockRejectedValueOnce(shopClosed409("CLOSED_NOW", "2026-10-14T08:00:00Z"))
    renderCheckout()
    await fillAndAcknowledge()
    await waitFor(() => expect(placeOrder()).not.toBeDisabled())
    fireEvent.click(placeOrder())
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("Your basket is saved. Opens Wednesday at 09:00.")
  })
})

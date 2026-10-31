/**
 * #957 (Phase 30-02, UI-SPEC § S1) — the card checkout never fails silently.
 *
 * The defect: the staging image carried no Stripe publishable key, so the page's Stripe promise was
 * null, and the step-2 render guard `if (paymentState && stripePromise)` fell through to the step-1
 * form AFTER the server had already created an unpaid DRAFT. The customer pressed Place order, the
 * page blinked, and the same form came back with nothing said. Stripe.js failing to load (network,
 * CSP, an ad blocker) recreates the same silence.
 *
 * Post-POST (task 1): when a confirmation carries a clientSecret, step 2 renders in EVERY case, and
 * its payment-details card shows one of three things:
 *   - Loading: a reserved-height skeleton and "Loading secure card form…" in role=status;
 *   - Ready:   the Payment Element (Elements with STRIPE_APPEARANCE);
 *   - Failed:  an amber role=alert "Card payment couldn't load" naming the unpaid order, with
 *              "Try again" only when a key exists (a retry without a key cannot succeed).
 *
 * Harness: the stripe-lazy-load (#793) harness — mocked public API, the throwing DEFAULT Stripe
 * entry (the page must only ever reach `/pure`), a `/pure` loadStripe whose result each test
 * controls, and Elements / PaymentElement stand-ins. The PaymentElement stand-in renders a button
 * that fires the `onLoadError` prop it was given, so "the Payment Element failed to load" is a click.
 */

import { Suspense, type ReactNode } from "react"
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react"
import { loadStripe } from "@stripe/stripe-js/pure"
import { useRouter } from "next/navigation"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import { saveLocalOrder } from "@/lib/order-history"
import { resetStripeLoaderForTests } from "@/lib/stripe-loader"

if (!globalThis.crypto || typeof globalThis.crypto.randomUUID !== "function") {
  Object.defineProperty(globalThis, "crypto", {
    value: { ...(globalThis.crypto || {}), randomUUID: () => "test-uuid-0000" },
    configurable: true,
  })
}

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
}))

jest.mock("@/lib/order-history", () => ({ saveLocalOrder: jest.fn() }))

// The DEFAULT entry must never be evaluated (#793): its import injects js.stripe.com.
jest.mock("@stripe/stripe-js", () => {
  throw new Error("default Stripe entry imported")
})

// Each test decides what a load does; the default is a SUCCESSFUL load (a stand-in instance).
jest.mock("@stripe/stripe-js/pure", () => ({
  loadStripe: jest.fn(() => Promise.resolve({ __fakeStripe: true })),
}))

// What useStripe() hands the card form. null = Stripe not ready (the Pay button stays disabled).
let mockStripeInstance: unknown = null
const elementsOptions: unknown[] = []
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ options, children }: { options: unknown; children: ReactNode }) => {
    elementsOptions.push(options)
    return <div data-testid="stripe-elements">{children}</div>
  },
  PaymentElement: ({ onLoadError }: { onLoadError?: (event: unknown) => void }) => (
    <button
      type="button"
      onClick={() => onLoadError?.({ elementType: "payment", error: { type: "api_error" } })}
    >
      simulate payment element load error
    </button>
  ),
  useStripe: () => mockStripeInstance,
  useElements: () => (mockStripeInstance ? { __fakeElements: true } : null),
}))

const mockedLoadStripe = loadStripe as unknown as jest.Mock
const mockedGet = publicApiClient.get as jest.Mock
const mockedPost = publicApiClient.post as jest.Mock
const mockedSaveLocalOrder = saveLocalOrder as unknown as jest.Mock
const mockedUseRouter = useRouter as unknown as jest.Mock

const CATALOGUE = { Mains: Array.from({ length: 10 }, (_, i) => ({ id: `p${i}`, allergenMask: 0 })) }
function mockShop(shop: unknown) {
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? CATALOGUE : shop })
  )
}

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`
const PUBLISHABLE_KEY = "pk_test_card_unavailable_957"

// No acceptsCardPayments field: an older backend, or a shop object that failed to say. The pre-POST
// refusal keys on `acceptsCardPayments === true`, so with this shop the order IS posted and only the
// post-POST defence stands between the customer and #957.
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

const ORDER_NUMBER = "ORD-957-CARD"
const CARD_CONFIRMATION = {
  status: "DRAFT",
  orderNumber: ORDER_NUMBER,
  clientSecret: "pi_957_secret_x",
  subtotalPennies: 1000,
  deliveryFeePennies: 0,
  vatRate: "STANDARD",
  vatAmountPennies: 167,
  totalAmountPennies: 1000,
  shopName: "Jollof Express",
  itemCount: 1,
  allergenWarnings: [],
}

const FAILED_HEADLINE = "Card payment couldn't load"
const FAILED_DETAIL = `Order ${ORDER_NUMBER} hasn't been paid, so the shop won't prepare it. Nothing has been charged.`
const LOADING_COPY = "Loading secure card form…"

async function loadCheckoutPage() {
  return (await import("@/app/shop/[slug]/checkout/page")).default
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
      items: [
        { productId: "p1", title: "Jollof Rice", pricePennies: 1000, quantity: 1, imageUrl: null, category: "Mains" },
      ],
    })
  )
}

async function renderCheckout() {
  const CheckoutPage = await loadCheckoutPage()
  const params = resolvedThenable({ slug: SLUG })
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CheckoutPage params={params} />
      </CartProvider>
    </Suspense>
  )
}

/** Fill the form for a COLLECTION order and acknowledge the allergen panel. */
async function armCheckout() {
  await screen.findByRole("button", { name: /place order/i })
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900000" } })
  fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))
}

async function placeCardOrder() {
  await renderCheckout()
  await armCheckout()
  fireEvent.click(screen.getByRole("button", { name: /place order/i }))
  await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
}

/** The failure block: found by its headline, asserted to be the role=alert that carries it. */
async function findFailedAlert() {
  const headline = await screen.findByText(FAILED_HEADLINE)
  const alert = headline.closest('[role="alert"]')
  expect(alert).not.toBeNull()
  expect(alert).toHaveTextContent(FAILED_DETAIL)
  return alert as HTMLElement
}

/** #957 was a fall-through to the step-1 form: step 2's h1 is up, step 1's is gone. */
function expectOnPaymentStep() {
  expect(screen.getByRole("heading", { name: "Payment", level: 1 })).toBeInTheDocument()
  expect(screen.queryByRole("heading", { name: "Checkout", level: 1 })).toBeNull()
}

const ORIGINAL_KEY = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedLoadStripe.mockReset()
  mockedLoadStripe.mockImplementation(() => Promise.resolve({ __fakeStripe: true }))
  mockedSaveLocalOrder.mockClear()
  resetStripeLoaderForTests()
  mockStripeInstance = null
  elementsOptions.length = 0
  mockShop(SHOP)
  mockedPost.mockResolvedValue({ data: CARD_CONFIRMATION })
  seedCart()
})

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  if (ORIGINAL_KEY === undefined) delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  else process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ORIGINAL_KEY
})

describe("#957 post-POST: a clientSecret confirmation always reaches step 2", () => {
  it("with NO publishable key, step 2 shows the Failed alert (no Try again), never the step-1 form, and loads nothing", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    await placeCardOrder()

    const alert = await findFailedAlert()
    expectOnPaymentStep()
    // A retry cannot succeed without a key, so none is offered.
    expect(within(alert).queryByRole("button", { name: /try again/i })).toBeNull()
    expect(screen.queryByRole("button", { name: /try again/i })).toBeNull()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
    // "Back to details" stays.
    expect(screen.getByRole("button", { name: /back to details/i })).toBeInTheDocument()
  })

  it("when Stripe.js resolves null, the Failed alert offers Try again, and Try again loads again", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockedLoadStripe.mockImplementationOnce(() => Promise.resolve(null))
    await placeCardOrder()

    const alert = await findFailedAlert()
    expectOnPaymentStep()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)

    const retry = within(alert).getByRole("button", { name: "Try again" })
    expect(retry).toHaveClass("h-11")
    fireEvent.click(retry)

    // The loader forgot the null load, so the retry is a NEW load — which succeeds this time.
    await screen.findByTestId("stripe-elements")
    expect(mockedLoadStripe).toHaveBeenCalledTimes(2)
    expect(screen.queryByText(FAILED_HEADLINE)).toBeNull()
  })

  it("when Stripe.js REJECTS (network, CSP), step 2 shows the Failed alert with Try again", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockedLoadStripe.mockImplementationOnce(() => Promise.reject(new Error("Failed to load Stripe.js")))
    await placeCardOrder()

    const alert = await findFailedAlert()
    expectOnPaymentStep()
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeInTheDocument()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
  })

  it("while Stripe.js is loading, the card shows a reserved-height skeleton and 'Loading secure card form…' in role=status", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockedLoadStripe.mockImplementation(() => new Promise(() => {}))
    await placeCardOrder()

    const status = await screen.findByRole("status")
    expect(status).toHaveTextContent(LOADING_COPY)
    expect(screen.getByTestId("card-form-skeleton")).toHaveClass("min-h-60")
    expectOnPaymentStep()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
    expect(screen.queryByText(FAILED_HEADLINE)).toBeNull()
  })

  it("when the Payment Element fires onLoadError, the form is replaced by the Failed alert", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    await placeCardOrder()

    await screen.findByTestId("stripe-elements")
    fireEvent.click(screen.getByRole("button", { name: /simulate payment element load error/i }))

    const alert = await findFailedAlert()
    expectOnPaymentStep()
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------------------------
// Pre-POST (task 2): a shop that takes cards, in a build with no publishable key, cannot take a
// card payment, so the order is never sent — no DRAFT is created that the customer can never pay.
// The reason is on screen BEFORE they try (the K3 / belowMinimum rule: a button may be disabled only
// when its reason is beside it), and the submit handler refuses too (a disabled button is not the
// only way a form is submitted).
// ---------------------------------------------------------------------------------------------

const CARD_NOTICE_HEADLINE = "Card payments are unavailable"
const CARD_NOTICE_DETAIL = "Card payments are unavailable right now, so this order can't be placed. Nothing has been charged."
const CARD_LINE_UNAVAILABLE = "Pay by card — unavailable right now."
const placeOrderButton = () => screen.getByRole("button", { name: /place order/i })

describe("#957 pre-POST: a card shop with no publishable key refuses to place the order, and says why", () => {
  it("shows the notice at the top of step 1, disables Place order described by it, and sends NO order however it is submitted", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    mockShop({ ...SHOP, acceptsCardPayments: true })
    await renderCheckout()
    await armCheckout()

    const notice = await screen.findByText(CARD_NOTICE_HEADLINE)
    const region = notice.closest('[role="alert"]') as HTMLElement
    expect(region).not.toBeNull()
    expect(region.id).toBe("card-payments-unavailable")
    expect(region).toHaveTextContent(CARD_NOTICE_DETAIL)
    // Top of step 1: before the first step-1 control (the fulfilment toggle).
    const firstControl = screen.getByRole("button", { name: /delivery/i })
    expect(region.compareDocumentPosition(firstControl) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    expect(placeOrderButton()).toBeDisabled()
    expect(placeOrderButton().getAttribute("aria-describedby")).toBe("card-payments-unavailable")
    // The How you'll pay line says the same thing, not "Pay securely by card".
    expect(screen.getByText(CARD_LINE_UNAVAILABLE)).toBeInTheDocument()
    expect(screen.queryByText(/pay securely by card/i)).toBeNull()

    // A click on the disabled button sends nothing...
    fireEvent.click(placeOrderButton())
    // ...and neither does a forced submit of the form itself (the handler refuses too).
    const form = placeOrderButton().closest("form") as HTMLFormElement
    fireEvent.submit(form)
    await new Promise((r) => setTimeout(r, 50))
    expect(mockedPost).toHaveBeenCalledTimes(0)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
  })

  it("when the shop is ALSO not taking orders: availability first, the card notice second, and Place order names both", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    mockShop({
      ...SHOP,
      acceptsCardPayments: true,
      openNow: false,
      nextOpenAt: null,
      pausedUntil: null,
      pausedIndefinitely: true,
    })
    await renderCheckout()
    await screen.findByText(CARD_NOTICE_HEADLINE)

    const availability = screen.getByTestId("shop-availability-notice")
    const card = document.getElementById("card-payments-unavailable") as HTMLElement
    expect(card).not.toBeNull()
    expect(availability.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await waitFor(() => expect(placeOrderButton()).toBeDisabled())
    expect(placeOrderButton().getAttribute("aria-describedby")).toBe(
      "shop-availability-explainer card-payments-unavailable"
    )
  })

  it("a card shop WITH a key shows no notice and places the order (regression)", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockShop({ ...SHOP, acceptsCardPayments: true })
    await placeCardOrder()

    expect(screen.queryByText(CARD_NOTICE_HEADLINE)).toBeNull()
    expect(document.getElementById("card-payments-unavailable")).toBeNull()
    await screen.findByTestId("stripe-elements")
  })

  it("a cash shop with no key shows no notice, places the order, and loads no Stripe", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    mockShop({ ...SHOP, acceptsCardPayments: false })
    mockedPost.mockResolvedValue({ data: { ...CARD_CONFIRMATION, orderNumber: "ORD-957-CASH", clientSecret: null } })
    await renderCheckout()
    await armCheckout()

    expect(screen.queryByText(CARD_NOTICE_HEADLINE)).toBeNull()
    expect(placeOrderButton()).not.toBeDisabled()
    expect(placeOrderButton().getAttribute("aria-describedby")).toBeNull()
    fireEvent.click(placeOrderButton())
    await waitFor(() => expect(sessionStorage.getItem("jtoye-just-placed:ORD-957-CASH")).toBe("ORD-957-CASH"))
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
  })
})

describe("#957 post-POST: the Ready state keeps every existing good", () => {
  it("mounts Elements with STRIPE_APPEARANCE (colorPrimary #c2410c, not #f97316) and the order's clientSecret", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    await placeCardOrder()

    await screen.findByTestId("stripe-elements")
    expectOnPaymentStep()
    const options = elementsOptions[elementsOptions.length - 1] as {
      clientSecret: string
      appearance: { theme: string; variables: { colorPrimary: string } }
    }
    expect(options.clientSecret).toBe("pi_957_secret_x")
    expect(options.appearance.theme).toBe("stripe")
    expect(options.appearance.variables.colorPrimary).toBe("#c2410c")
    // The card form itself: one h2, the Pay CTA with the total, the Stripe line.
    expect(screen.getByRole("heading", { name: "Payment details", level: 2 })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Pay £10.00" })).toBeInTheDocument()
    expect(screen.getByText(/Secured by Stripe/)).toBeInTheDocument()
  })

  it("a successful payment saves the order locally, clears the basket and routes to the order page, as before", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    const push = jest.fn()
    mockedUseRouter.mockReturnValue({ push, replace: jest.fn(), back: jest.fn(), forward: jest.fn(), refresh: jest.fn(), prefetch: jest.fn() })
    const confirmPayment = jest.fn(() => Promise.resolve({}))
    mockStripeInstance = { confirmPayment }
    await placeCardOrder()

    await screen.findByTestId("stripe-elements")
    fireEvent.click(screen.getByRole("button", { name: "Pay £10.00" }))

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/shop/${SLUG}/orders/${ORDER_NUMBER}`))
    expect(confirmPayment).toHaveBeenCalledTimes(1)
    expect(confirmPayment).toHaveBeenCalledWith(
      expect.objectContaining({
        confirmParams: {
          return_url: `${window.location.origin}/shop/${SLUG}/orders/${ORDER_NUMBER}`,
          receipt_email: "ade@example.com",
        },
        redirect: "if_required",
      })
    )
    expect(mockedSaveLocalOrder).toHaveBeenCalledWith(
      expect.objectContaining({ orderNumber: ORDER_NUMBER, email: "ade@example.com", shopSlug: SLUG })
    )
    await waitFor(() => expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{"items":[]}').items).toHaveLength(0))
  })
})

// ---------------------------------------------------------------------------------------------
// Plan 30-18 Task 3 (UI-SPEC § S1 "Connected-account initialisation"): a direct-charge order's
// PaymentIntent lives on the vendor's connected account, so the confirmation's stripeAccountId
// (plan 30-17) must reach loadStripe as `{ stripeAccount }` — or confirmPayment cannot find the
// intent. With no account the call stays loadStripe(pk), ONE argument (#793 property 3).
// ---------------------------------------------------------------------------------------------

describe("30-18: the checkout initialises Stripe on the account that owns the intent", () => {
  it("a direct-charge confirmation (stripeAccountId) loads Stripe with { stripeAccount }, and Try again keeps the account", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockedPost.mockResolvedValue({ data: { ...CARD_CONFIRMATION, stripeAccountId: "acct_1" } })
    mockedLoadStripe.mockImplementationOnce(() => Promise.resolve(null))
    await placeCardOrder()

    const alert = await findFailedAlert()
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe.mock.calls[0]).toEqual([PUBLISHABLE_KEY, { stripeAccount: "acct_1" }])

    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }))
    await screen.findByTestId("stripe-elements")
    expect(mockedLoadStripe).toHaveBeenCalledTimes(2)
    expect(mockedLoadStripe.mock.calls[1]).toEqual([PUBLISHABLE_KEY, { stripeAccount: "acct_1" }])
  })

  it.each([
    ["absent (an older server)", {}],
    ["null (a destination or platform charge)", { stripeAccountId: null }],
  ])("with stripeAccountId %s, loadStripe is called with the key alone", async (_label, extra) => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockedPost.mockResolvedValue({ data: { ...CARD_CONFIRMATION, ...extra } })
    await placeCardOrder()

    await screen.findByTestId("stripe-elements")
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe.mock.calls[0]).toEqual([PUBLISHABLE_KEY])
  })
})

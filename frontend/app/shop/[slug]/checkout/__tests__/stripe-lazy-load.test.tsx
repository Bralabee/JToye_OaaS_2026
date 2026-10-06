/**
 * #793 (phase 31.1, PGC-793) — a checkout that takes no payment loads no Stripe code.
 *
 * `import { loadStripe } from "@stripe/stripe-js"` (the package's DEFAULT entry) injects
 * js.stripe.com as a MODULE SIDE EFFECT: `dist/index.mjs` schedules `getStripePromise()` on a
 * resolved promise at module scope, so merely importing the checkout page put Stripe's script
 * on the page. That script sets `__stripe_mid` (1 year) and `__stripe_sid` and calls
 * m.stripe.network — on a cash-only checkout whose own copy says no payment is taken online.
 *
 * The `@stripe/stripe-js/pure` entry injects nothing until `loadStripe()` is CALLED. These tests
 * pin the four properties the fix relies on:
 *
 *  1. the page never imports the default entry at all (its factory below THROWS, so an import
 *     from anywhere in the page's module graph fails the load);
 *  2. a cash (no clientSecret) confirmation calls the /pure loadStripe ZERO times;
 *  3. a card confirmation (clientSecret present, publishable key set) calls it EXACTLY ONCE, and
 *     the promise it returned is the one handed to <Elements>; viewing the card form beforehand,
 *     re-rendering, and going back and resubmitting call it no further times;
 *  4. with no publishable key configured, it is never called on any path.
 *
 * The page module is loaded LAZILY (dynamic import inside each test) rather than by a static
 * import: a static import of a page that pulls in the throwing default entry would crash the
 * whole suite at load time, which is not evidence about any one behaviour. Loading it inside a
 * test turns "the default entry was imported" into an assertion failure of a named test.
 */

import { Suspense, type ReactNode } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import { loadStripe } from "@stripe/stripe-js/pure"
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

// The DEFAULT entry must never be evaluated: its import is the side effect #793 is about.
jest.mock("@stripe/stripe-js", () => {
  throw new Error("default Stripe entry imported")
})

// The side-effect-free entry. The spy returns a fresh, identifiable promise per call so the
// test can prove WHICH promise <Elements> received.
jest.mock("@stripe/stripe-js/pure", () => ({
  loadStripe: jest.fn(() => Promise.resolve(null)),
}))

// Every `stripe` prop <Elements> was rendered with, in render order.
const elementsStripeProps: unknown[] = []
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ stripe, children }: { stripe: unknown; children: ReactNode }) => {
    elementsStripeProps.push(stripe)
    return <div data-testid="stripe-elements">{children}</div>
  },
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

const mockedLoadStripe = loadStripe as unknown as jest.Mock
const mockedGet = publicApiClient.get as jest.Mock
const mockedPost = publicApiClient.post as jest.Mock

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`
const PUBLISHABLE_KEY = "pk_test_lazy_load_793"

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

const CONFIRMATION_BASE = {
  status: "PENDING",
  subtotalPennies: 1000,
  deliveryFeePennies: 0,
  vatRate: "STANDARD",
  vatAmountPennies: 167,
  totalAmountPennies: 1000,
  shopName: "Jollof Express",
  itemCount: 1,
  allergenWarnings: [],
}

const COD_CONFIRMATION = { ...CONFIRMATION_BASE, orderNumber: "ORD-793-CASH", clientSecret: null }
const CARD_CONFIRMATION = { ...CONFIRMATION_BASE, orderNumber: "ORD-793-CARD", clientSecret: "pi_x_secret_y" }

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
        {
          productId: "p1",
          title: "Jollof Rice",
          pricePennies: 1000,
          quantity: 1,
          imageUrl: null,
          category: "Mains",
        },
      ],
    })
  )
}

async function renderCheckout() {
  const CheckoutPage = await loadCheckoutPage()
  const params = resolvedThenable({ slug: SLUG })
  const tree = () => (
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CheckoutPage params={params} />
      </CartProvider>
    </Suspense>
  )
  const utils = render(tree())
  return { ...utils, rerenderSame: () => utils.rerender(tree()) }
}

/** Fill the form for a COLLECTION order (no address) and acknowledge the allergen panel. */
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

const ORIGINAL_KEY = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedLoadStripe.mockClear()
  elementsStripeProps.length = 0
  mockedGet.mockResolvedValue({ data: { ...SHOP, acceptsCardPayments: true } })
  seedCart()
})

afterEach(() => {
  localStorage.clear()
  if (ORIGINAL_KEY === undefined) {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  } else {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ORIGINAL_KEY
  }
})

describe("Stripe lazy load (#793)", () => {
  it("never imports the default @stripe/stripe-js entry (its import alone injects js.stripe.com)", async () => {
    // The default-entry factory above throws "default Stripe entry imported". If the page, or
    // anything it imports, pulls that entry in, this load rejects with that message.
    await expect(loadCheckoutPage()).resolves.toEqual(expect.any(Function))
  })

  it("a cash (no clientSecret) order calls loadStripe ZERO times, even with a publishable key set", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockedGet.mockResolvedValue({ data: { ...SHOP, acceptsCardPayments: false } })
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    await renderCheckout()
    await armCheckout()

    placeOrder()
    await screen.findByText(/Order confirmed!/i)

    // Non-vacuity: the order really was placed through the mocked API, so the zero below is
    // about a completed cash checkout, not about a render that never reached submission.
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
  })

  it("a card order calls loadStripe EXACTLY ONCE after the clientSecret arrives, and Elements gets that promise", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = PUBLISHABLE_KEY
    mockedPost.mockResolvedValue({ data: CARD_CONFIRMATION })
    const { rerenderSame } = await renderCheckout()
    await armCheckout()

    // Adjacency: a card-accepting shop's checkout FORM alone loads nothing.
    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)

    placeOrder()
    await screen.findByTestId("stripe-elements")

    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe).toHaveBeenCalledWith(PUBLISHABLE_KEY)
    const loaded = mockedLoadStripe.mock.results[0].value
    expect(elementsStripeProps.length).toBeGreaterThan(0)
    expect(elementsStripeProps.every((p) => p === loaded)).toBe(true)

    // A re-render does not load it again.
    rerenderSame()
    await screen.findByTestId("stripe-elements")
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)

    // Back to details and a resubmission still reuses the one promise (at most once per page).
    fireEvent.click(screen.getByRole("button", { name: /back to details/i }))
    fireEvent.click(await screen.findByRole("checkbox", { name: /I have read the allergen information/i }))
    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))
    await screen.findByTestId("stripe-elements")
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
    expect(elementsStripeProps.every((p) => p === loaded)).toBe(true)
  })

  it("with NO publishable key configured, a card order never calls loadStripe", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    mockedPost.mockResolvedValue({ data: CARD_CONFIRMATION })
    await renderCheckout()
    await armCheckout()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    // Let the post-submit state settle before asserting the absence.
    await waitFor(() => expect(screen.getByRole("button", { name: /place order/i })).not.toBeDisabled())

    expect(mockedLoadStripe).toHaveBeenCalledTimes(0)
    expect(screen.queryByTestId("stripe-elements")).toBeNull()
  })
})

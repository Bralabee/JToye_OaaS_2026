/**
 * Persona close-out 2026-11-04 (lane `storefront`): the checkout's share of
 *   #829  the payment method stated at the TOP of checkout, and a Place order icon that matches it
 *   #876  UXT-103 an offline failure says so and warns the order may already be placed,
 *         UXT-104 the address, phone and notes survive Back then Forward,
 *         UXT-105 one validation pass with an error summary
 *   SW-PRY-07 the allergen panel's "tell the kitchen" gives a way to do so
 *
 * Harness copied from checkout-form-a11y.test.tsx: the same mocks and the same seeded basket.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"

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
jest.mock("@stripe/stripe-js/pure", () => ({ loadStripe: jest.fn(() => Promise.resolve(null)) }))
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

const mockedGet = publicApiClient.get as jest.Mock
const mockedPost = publicApiClient.post as jest.Mock

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

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
  deliveryFeePennies: 0,
  freeDeliveryThresholdPennies: 0,
  tags: null,
  acceptsCardPayments: false,
}

const PRODUCTS = {
  Mains: [
    {
      id: "p1",
      title: "Jollof Rice",
      description: null,
      imageUrl: null,
      imageUrls: [],
      ingredientsText: "rice, tomato",
      allergenMask: 0,
      pricePennies: 1000,
      category: "Mains",
      dietaryTags: null,
      preparationTimeMinutes: null,
      featured: false,
      inStock: true,
    },
  ],
}

function mockShop(shop: Record<string, unknown>) {
  mockedGet.mockImplementation((url: string) => {
    if (typeof url === "string" && url.endsWith("/products")) return Promise.resolve({ data: PRODUCTS })
    return Promise.resolve({ data: shop })
  })
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

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
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

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockShop(SHOP)
})

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  jest.clearAllMocks()
})

// ---- #829 -------------------------------------------------------------------------------------

describe("#829 the payment method is stated at the top of checkout, and the button matches it", () => {
  it("a cash shop: 'Cash on delivery' is stated BEFORE the first field, and Place order wears the banknote, not a card", async () => {
    seedCart()
    renderCheckout()
    const note = await screen.findByTestId("payment-method-note")
    expect(note).toHaveTextContent("Cash on delivery")
    expect(note).toHaveTextContent(/nothing is charged online/i)

    // BEFORE the form: the note precedes the fulfilment control and every input in document order.
    const form = document.querySelector("form") as HTMLFormElement
    expect(note.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(note.contains(form)).toBe(false)

    const place = screen.getByRole("button", { name: /place order/i })
    expect(place.querySelector("svg.lucide-banknote")).not.toBeNull()
    expect(place.querySelector("svg.lucide-credit-card")).toBeNull()
  })

  it("the note follows the customer's choice: Collection reads 'Cash on collection'", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("payment-method-note")
    fireEvent.click(screen.getByRole("button", { name: /^collection$/i }))
    expect(screen.getByTestId("payment-method-note")).toHaveTextContent("Cash on collection")
    expect(screen.getByTestId("payment-method-note")).not.toHaveTextContent(/delivery/i)
  })

  it("a card shop: 'Card payment' at the top and the card icon on the button", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = "pk_test_closeout_829"
    mockShop({ ...SHOP, acceptsCardPayments: true })
    seedCart()
    renderCheckout()
    expect(await screen.findByTestId("payment-method-note")).toHaveTextContent("Card payment")
    const place = screen.getByRole("button", { name: /place order/i })
    expect(place.querySelector("svg.lucide-credit-card")).not.toBeNull()
    expect(place.querySelector("svg.lucide-banknote")).toBeNull()
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  })

  it("CONTROL: a shop that did not say gets no note and no payment icon at all (absent is not cash, not card)", async () => {
    const { acceptsCardPayments: _omit, ...unsaid } = SHOP
    void _omit
    mockShop(unsaid)
    seedCart()
    renderCheckout()
    const place = await screen.findByRole("button", { name: /place order/i })
    await screen.findByTestId("order-allergen-panel")
    expect(screen.queryByTestId("payment-method-note")).toBeNull()
    expect(place.querySelector("svg.lucide-credit-card")).toBeNull()
    expect(place.querySelector("svg.lucide-banknote")).toBeNull()
  })
})

// keep the shared helpers referenced by the describes appended below
void waitFor
void within

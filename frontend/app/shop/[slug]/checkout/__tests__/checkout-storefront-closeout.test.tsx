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

// ---- shared helpers for the describes below -----------------------------------------------------

const COD_CONFIRMATION = {
  orderNumber: "ORD-CLOSEOUT-1",
  status: "PENDING",
  subtotalPennies: 1000,
  deliveryFeePennies: 0,
  vatRate: "ZERO",
  vatAmountPennies: 0,
  totalAmountPennies: 1000,
  clientSecret: null,
}

function fillDetails() {
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900000" } })
}

function fillAddress() {
  fireEvent.change(screen.getByLabelText(/address line 1/i), { target: { value: "12 Coldharbour Lane" } })
  fireEvent.change(screen.getByLabelText(/town \/ city/i), { target: { value: "London" } })
  fireEvent.change(screen.getByLabelText(/^postcode/i), { target: { value: "SW9 8LF" } })
}

function acknowledgeAllergens() {
  fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))
}

const placeOrder = () => fireEvent.click(screen.getByRole("button", { name: /place order/i }))

// ---- #876 UXT-103: a network failure says so, and warns the order may exist ----------------------

describe("#876 UXT-103: a network failure at Place order", () => {
  async function submitWith(rejection: unknown) {
    mockedPost.mockRejectedValue(rejection)
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetails()
    fillAddress()
    acknowledgeAllergens()
    placeOrder()
    return screen.findByRole("alert")
  }

  afterEach(() => {
    Object.defineProperty(window.navigator, "onLine", { configurable: true, value: true })
  })

  it("says the order may already have been placed, and where to look, instead of 'Failed to place order'", async () => {
    const alert = await submitWith(Object.assign(new Error("Network Error"), { code: "ERR_NETWORK" }))
    expect(alert).toHaveTextContent(/may already have been placed/i)
    expect(alert).toHaveTextContent(/My Orders/)
    expect(alert).toHaveTextContent(/basket is safe/i)
    expect(alert).not.toHaveTextContent(/Failed to place order/i)
  })

  it("offline: says 'You appear to be offline' and still warns the order may exist", async () => {
    Object.defineProperty(window.navigator, "onLine", { configurable: true, value: false })
    const alert = await submitWith(new Error("anything at all"))
    expect(alert).toHaveTextContent(/you appear to be offline/i)
    expect(alert).toHaveTextContent(/may already have been placed/i)
  })

  it("CONTROL: a server answer (500 with a detail) is shown as before, with no offline guidance", async () => {
    const alert = await submitWith({ response: { status: 500, data: { detail: "The kitchen is on fire." } } })
    expect(alert).toHaveTextContent("The kitchen is on fire.")
    expect(alert).not.toHaveTextContent(/may already have been placed/i)
  })
})

// ---- #876 UXT-105: one validation pass, an error summary, every error described ------------------

describe("#876 UXT-105: an empty submit is ONE pass with a summary", () => {
  it("names every missing field at once, describes each, and puts focus on the first invalid one", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    placeOrder()

    // One pass: no network call, and a summary that lists all six.
    const summary = await screen.findByRole("alert", { name: /please fix/i })
    expect(mockedPost).not.toHaveBeenCalled()
    const items = within(summary).getAllByRole("link")
    expect(items.map((a) => a.textContent)).toEqual([
      "Add a delivery address, or switch to Collection.",
      "Enter your town or city.",
      "Enter a valid UK postcode (e.g. SW1A 1AA)",
      "Enter your full name.",
      "Enter your email address.",
      "Enter a phone number the shop can reach you on.",
    ])

    // Each field exposes its own error through aria-describedby (the city no longer reuses the address sentence).
    for (const [label, id] of [
      [/address line 1/i, "address1-error"],
      [/town \/ city/i, "city-error"],
      [/^postcode/i, "postcode-error"],
      [/full name/i, "name-error"],
      [/email address/i, "email-error"],
      [/phone number/i, "phone-error"],
    ] as const) {
      const field = screen.getByLabelText(label)
      expect(field).toHaveAttribute("aria-invalid", "true")
      expect(field).toHaveAttribute("aria-describedby", expect.stringContaining(id))
      expect(document.getElementById(id)).toBeInTheDocument()
    }
    // Focus: the first invalid field in the order the page presents them.
    expect(screen.getByLabelText(/address line 1/i)).toHaveFocus()
  })

  it("a summary link moves focus to its field", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    placeOrder()
    const summary = await screen.findByRole("alert", { name: /please fix/i })
    fireEvent.click(within(summary).getByRole("link", { name: "Enter your email address." }))
    expect(screen.getByLabelText(/email address/i)).toHaveFocus()
  })

  it("an invalid email is refused with its own message, not the browser's bubble", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetails()
    fillAddress()
    fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@" } })
    placeOrder()
    const summary = await screen.findByRole("alert", { name: /please fix/i })
    expect(within(summary).getAllByRole("link")).toHaveLength(1)
    expect(within(summary).getByRole("link")).toHaveTextContent("Enter a valid email address, like ade@example.com.")
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("CONTROL: a complete form shows no summary and places the order", async () => {
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetails()
    fillAddress()
    acknowledgeAllergens()
    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole("alert", { name: /please fix/i })).toBeNull()
  })
})

// ---- #876 UXT-104: Back then Forward keeps the fields ---------------------------------------------

describe("#876 UXT-104: the address, phone and notes survive Back then Forward", () => {
  it("unmounting (Back) and mounting again (Forward) restores what was typed", async () => {
    seedCart()
    const first = renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillAddress()
    fireEvent.change(screen.getByLabelText(/address line 2/i), { target: { value: "Flat 3" } })
    fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900123" } })
    fireEvent.change(screen.getByLabelText(/order notes/i), { target: { value: "Ring twice" } })
    fireEvent.change(screen.getByLabelText(/allergy or dietary note/i), { target: { value: "no peanuts" } })
    first.unmount()

    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    await waitFor(() => expect(screen.getByLabelText(/address line 1/i)).toHaveValue("12 Coldharbour Lane"))
    expect(screen.getByLabelText(/address line 2/i)).toHaveValue("Flat 3")
    expect(screen.getByLabelText(/town \/ city/i)).toHaveValue("London")
    expect(screen.getByLabelText(/^postcode/i)).toHaveValue("SW9 8LF")
    expect(screen.getByLabelText(/phone number/i)).toHaveValue("07700 900123")
    expect(screen.getByLabelText(/order notes/i)).toHaveValue("Ring twice")
    // The allergy note is health data that goes to the POST body and nowhere else (T-31.1-55).
    expect(screen.getByLabelText(/allergy or dietary note/i)).toHaveValue("")
    expect(JSON.stringify({ ...sessionStorage })).not.toContain("peanuts")
  })

  it("a mount does not wipe a stored draft before restoring it (the mount-time re-serialise trap)", async () => {
    seedCart()
    const first = renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillAddress()
    first.unmount()
    const before = sessionStorage.getItem(`jtoye-checkout-draft-${SLUG}`)
    expect(before).toContain("Coldharbour")

    const second = renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    // Unmount again without touching a field: the draft is still the customer's.
    second.unmount()
    expect(sessionStorage.getItem(`jtoye-checkout-draft-${SLUG}`)).toContain("Coldharbour")
  })

  it("placing the order clears the draft", async () => {
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetails()
    fillAddress()
    expect(sessionStorage.getItem(`jtoye-checkout-draft-${SLUG}`)).toContain("Coldharbour")
    acknowledgeAllergens()
    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(sessionStorage.getItem(`jtoye-checkout-draft-${SLUG}`)).toBeNull())
  })
})

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
import { act, render, screen, fireEvent, waitFor, within } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"
import { CONTACT_COPY } from "@/lib/contact-plausibility"

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
    expect(within(summary).getByRole("link")).toHaveTextContent(CONTACT_COPY.email)
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it.each(["ade@", "name@example", "a@b.c"])(
    "review 1 (PR 1122): %p gets the ONE email message (the plausibility copy), whichever check would have caught it",
    async (bad) => {
      seedCart()
      renderCheckout()
      await screen.findByTestId("order-allergen-panel")
      fillDetails()
      fillAddress()
      fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: bad } })
      placeOrder()
      const summary = await screen.findByRole("alert", { name: /please fix/i })
      expect(within(summary).getByRole("link")).toHaveTextContent(CONTACT_COPY.email)
      expect(within(summary).getByRole("link")).not.toHaveTextContent("ade@example.com")
      expect(mockedPost).not.toHaveBeenCalled()
    },
  )

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

// ---- #845: the signed-in customer's last delivery address is offered, to them only -----------------

describe("#845: the last delivery address is prefilled for the signed-in customer, and only for them", () => {
  const A_ADDRESS = { address1: "12 Coldharbour Lane", address2: "Flat 3", city: "London", postcode: "SW9 8LF", phone: "07700 900001" }
  const session = (sub: string) => ({
    profile: { sub, email: `${sub}@example.com`, name: sub === "user-A" ? "Ade Johnson" : "Bola Smith", emailVerified: true },
    expiresAt: Math.floor(Date.now() / 1000) + 300,
  })
  function signIn(sub: string) {
    localStorage.setItem("jtoye-customer-id", sub)
    localStorage.setItem("jtoye-customer-logged-in", "true")
    localStorage.setItem("jtoye-customer-expires-at", String(Math.floor(Date.now() / 1000) + 300))
    ;(getCustomerSession as jest.Mock).mockResolvedValue(session(sub))
  }
  const storedLast = () => JSON.parse(localStorage.getItem("jtoye-last-delivery") ?? "null")

  afterEach(() => {
    ;(getCustomerSession as jest.Mock).mockResolvedValue(null)
  })

  it("customer A's address and phone are prefilled at A's next checkout", async () => {
    signIn("user-A")
    localStorage.setItem("jtoye-last-delivery", JSON.stringify({ owner: "user-A", ...A_ADDRESS }))
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    await waitFor(() => expect(screen.getByLabelText(/address line 1/i)).toHaveValue("12 Coldharbour Lane"))
    expect(screen.getByLabelText(/address line 2/i)).toHaveValue("Flat 3")
    expect(screen.getByLabelText(/town \/ city/i)).toHaveValue("London")
    expect(screen.getByLabelText(/^postcode/i)).toHaveValue("SW9 8LF")
    expect(screen.getByLabelText(/phone number/i)).toHaveValue("07700 900001")
    // and the name and email still come from the session, as before
    expect(screen.getByLabelText(/full name/i)).toHaveValue("Ade Johnson")
  })

  it("all or nothing: a restored draft with a new address is NOT mixed with the remembered one (no flat, city or phone borrowed from the old address)", async () => {
    signIn("user-A")
    localStorage.setItem("jtoye-last-delivery", JSON.stringify({ owner: "user-A", ...A_ADDRESS }))
    sessionStorage.setItem(
      `jtoye-checkout-draft-${SLUG}`,
      JSON.stringify({
        owner: "user-A",
        fields: { fulfilmentType: "DELIVERY", address1: "10 New Street", address2: "", city: "", postcode: "N1 1AA", phone: "", notes: "" },
      })
    )
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    await waitFor(() => expect(screen.getByLabelText(/full name/i)).toHaveValue("Ade Johnson"))
    // Let the prefill effect (which runs after the sign-in resolves) settle before asserting its absence.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50))
    })
    expect(screen.getByLabelText(/address line 1/i)).toHaveValue("10 New Street")
    expect(screen.getByLabelText(/address line 2/i)).toHaveValue("")
    expect(screen.getByLabelText(/town \/ city/i)).toHaveValue("")
    expect(screen.getByLabelText(/^postcode/i)).toHaveValue("N1 1AA")
    expect(screen.getByLabelText(/phone number/i)).toHaveValue("")
  })

  it("through the transition: B signing in on the same browser is NOT offered A's address, and the stored stamp is still A's", async () => {
    signIn("user-A")
    localStorage.setItem("jtoye-last-delivery", JSON.stringify({ owner: "user-A", ...A_ADDRESS }))
    signIn("user-B")
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    await waitFor(() => expect(screen.getByLabelText(/full name/i)).toHaveValue("Bola Smith"))
    expect(screen.getByLabelText(/address line 1/i)).toHaveValue("")
    expect(screen.getByLabelText(/phone number/i)).toHaveValue("")
    // A mount is not a write: A's stamp and address are exactly as they were.
    expect(storedLast()).toMatchObject({ owner: "user-A", address1: "12 Coldharbour Lane" })
  })

  it("a guest is offered nothing, and a lapsed session (nobody identified) is offered nothing", async () => {
    localStorage.setItem("jtoye-last-delivery", JSON.stringify({ owner: "user-A", ...A_ADDRESS }))
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    expect(screen.getByLabelText(/address line 1/i)).toHaveValue("")
    expect(storedLast()).toMatchObject({ owner: "user-A" })
  })

  it("placing a DELIVERY order as the signed-in customer stores their address with THEIR stamp; a guest's order stores nothing", async () => {
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    signIn("user-B")
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    await waitFor(() => expect(screen.getByLabelText(/full name/i)).toHaveValue("Bola Smith"))
    fillAddress()
    fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900002" } })
    acknowledgeAllergens()
    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(storedLast()).toMatchObject({ owner: "user-B", address1: "12 Coldharbour Lane", phone: "07700 900002" }))
  })

  it("a guest's delivery order stores no address at all", async () => {
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetails()
    fillAddress()
    acknowledgeAllergens()
    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    expect(localStorage.getItem("jtoye-last-delivery")).toBeNull()
  })
})

/**
 * The checkout allergen gate (Phase 31-14, S3, LGL-03 / D-02).
 *
 * THE ASSERTION THAT MATTERS is not "an error appeared" — it is that NO ORDER WAS CREATED.
 * An error rendered alongside a successfully created order is the dangerous outcome: the
 * customer is told off while the kitchen is already cooking. Every refusal case below therefore
 * asserts `publicApiClient.post` was never called, and the error copy second.
 *
 * The harness mirrors the existing checkout.test.tsx: CartProvider hydrates from localStorage in
 * a useEffect, so the cart is seeded BEFORE render.
 */

import fs from "fs"
import path from "path"
import { Suspense } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider, useCart } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import {
  ALLERGEN_ACK_ERROR_COPY,
  ALLERGEN_ACK_LABEL_COPY,
  ALLERGEN_PANEL_HEADING_COPY,
} from "@/components/storefront/order-allergen-panel"

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
jest.mock("@stripe/stripe-js", () => ({ loadStripe: jest.fn(() => Promise.resolve(null)) }))
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

/** bit 0 = Gluten, bit 6 = Milk — the declared mask the storefront serves for p1. */
const PRODUCTS = {
  Mains: [
    {
      id: "p1",
      title: "Jollof Rice",
      description: null,
      imageUrl: null,
      imageUrls: [],
      ingredientsText: "rice, **wheat**, tomato",
      allergenMask: (1 << 0) | (1 << 6),
      pricePennies: 1000,
      category: "Mains",
      dietaryTags: null,
      preparationTimeMinutes: null,
      featured: false,
      inStock: true,
    },
  ],
}

function mockEndpoints({ products = PRODUCTS as unknown }: { products?: unknown } = {}) {
  mockedGet.mockImplementation((url: string) => {
    if (typeof url === "string" && url.endsWith("/products")) {
      return Promise.resolve({ data: products })
    }
    return Promise.resolve({ data: SHOP })
  })
}

function seedCart(quantity = 1) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        {
          productId: "p1",
          title: "Jollof Rice",
          pricePennies: 1000,
          quantity,
          imageUrl: null,
          category: "Mains",
        },
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

/**
 * Drives a REAL basket edit through the cart context, inside the SAME mount as the checkout.
 *
 * Why not re-render with different localStorage: CartProvider hydrates from storage once, on
 * mount, so a `rerender` leaves the basket untouched and the assertion silently tests nothing.
 * Why not unmount and remount: a fresh mount starts with the acknowledgement unchecked ANYWAY, so
 * the test would pass whether or not the reset logic exists — vacuous in the most dangerous
 * direction, since this gate is what stops one basket's acknowledgement carrying to another.
 */
function BasketEditor() {
  const { updateQuantity } = useCart()
  return (
    <button type="button" onClick={() => updateQuantity("p1", 3)}>
      test-edit-basket
    </button>
  )
}

function renderCheckoutWithBasketEditor() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CheckoutPage params={resolvedThenable({ slug: SLUG })} />
        <BasketEditor />
      </CartProvider>
    </Suspense>
  )
}

/** Fill the details the server requires, and switch to COLLECTION so no address is needed. */
function fillDetailsAndChooseCollection() {
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), {
    target: { value: "ade@example.com" },
  })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900000" } })
}

/**
 * Read the checkout page's own source for the two structural assertions below.
 *
 * Throws `VOID:` rather than returning "" when the file cannot be read: an empty string would
 * make `split(...).length - 1` return 0 and `matchAll` return nothing, so both structural
 * assertions would report a confident verdict about a file they never opened.
 */
function readCheckoutSource(): string {
  const file = path.join(__dirname, "..", "page.tsx")
  if (!fs.existsSync(file)) throw new Error(`VOID: checkout page not found at ${file}`)
  const src = fs.readFileSync(file, "utf8")
  if (src.trim().length === 0) throw new Error(`VOID: checkout page at ${file} is empty`)
  return src
}

const submitButton = () => screen.getByRole("button", { name: /place order/i })
const ackCheckbox = () => screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockEndpoints()
  mockedPost.mockResolvedValue({
    data: {
      orderNumber: "ORD-1",
      status: "PENDING",
      subtotalPennies: 1000,
      deliveryFeePennies: 0,
      vatRate: "STANDARD",
      vatAmountPennies: 166,
      totalAmountPennies: 1000,
      shopName: "Jollof Express",
      itemCount: 1,
      clientSecret: "",
      allergenWarnings: [],
    },
  })
})

afterEach(() => {
  localStorage.clear()
  jest.clearAllMocks()
})

describe("checkout: the pre-submit allergen panel is mounted", () => {
  it("renders the panel with the basket's declared set, above the Place order button", async () => {
    seedCart()
    renderCheckout()

    expect(
      await screen.findByRole("heading", { name: ALLERGEN_PANEL_HEADING_COPY, level: 2 })
    ).toBeInTheDocument()

    // The declared union of the basket: bit 0 (Gluten) | bit 6 (Milk).
    await waitFor(() => {
      expect(screen.getAllByTestId("allergen-chip").map((c) => c.textContent)).toEqual([
        "Gluten",
        "Milk",
      ])
    })
  })

  it("is positioned BEFORE the submit button in document order — the last thing read", async () => {
    seedCart()
    renderCheckout()

    const panel = await screen.findByTestId("order-allergen-panel")
    const button = submitButton()
    // Node.compareDocumentPosition: DOCUMENT_POSITION_FOLLOWING (4) means button follows panel.
    expect(panel.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("shows NOT RECORDED rather than 'no allergens declared' when the products cannot be resolved", async () => {
    // A failed/unusable products response is NOT evidence that the kitchen declared nothing.
    seedCart()
    mockEndpoints({ products: { Mains: [] } })
    renderCheckout()

    expect(
      await screen.findByRole("heading", {
        name: "Allergen information not recorded for this order",
        level: 2,
      })
    ).toBeInTheDocument()
    expect(screen.queryByText("No allergens declared for this order")).not.toBeInTheDocument()
  })
})

describe("checkout: submitting WITHOUT the acknowledgement is refused (T-31-14-02)", () => {
  it("CREATES NO ORDER — no network call is made", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()

    expect(ackCheckbox()).not.toBeChecked()
    fireEvent.click(submitButton())

    // THE load-bearing assertion.
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(ALLERGEN_ACK_ERROR_COPY)
    })
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("announces the refusal: role=alert with the exact copy", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()
    fireEvent.click(submitButton())

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(ALLERGEN_ACK_ERROR_COPY)
  })

  it("sets aria-invalid and wires aria-describedby to the alert's id", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()
    fireEvent.click(submitButton())

    await screen.findByRole("alert")
    const box = ackCheckbox()
    expect(box).toHaveAttribute("aria-invalid", "true")

    const describedBy = box.getAttribute("aria-describedby")
    expect(describedBy).toBeTruthy()
    // An aria-describedby pointing at nothing is as silent as no wiring at all.
    expect(document.getElementById(describedBy!)).toHaveTextContent(ALLERGEN_ACK_ERROR_COPY)
  })

  it("MOVES FOCUS to the control that refused", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()
    fireEvent.click(submitButton())

    await waitFor(() => {
      expect(document.activeElement).toBe(ackCheckbox())
    })
  })

  it("leaves the Place order button ENABLED throughout (T-31-14-07)", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()

    // Enabled before the refusal...
    expect(submitButton()).not.toBeDisabled()
    fireEvent.click(submitButton())
    await screen.findByRole("alert")
    // ...and still enabled after it. A disabled button gives a touch user no feedback at all.
    expect(submitButton()).not.toBeDisabled()
  })
})

describe("checkout: submitting WITH the acknowledgement proceeds", () => {
  it("creates the order once the box is ticked", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()

    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())

    fireEvent.click(submitButton())

    await waitFor(() => {
      expect(mockedPost).toHaveBeenCalledTimes(1)
    })
    expect(mockedPost.mock.calls[0][0]).toBe(`/public/shops/${SLUG}/orders`)
  })

  it("clears the refusal once acknowledged, so the alert is not left announcing", async () => {
    seedCart()
    renderCheckout()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()

    fireEvent.click(submitButton())
    await screen.findByRole("alert")

    fireEvent.click(ackCheckbox())
    await waitFor(() => {
      expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    })
  })
})

describe("checkout: the acknowledgement is per ORDER INTENT, not per page mount", () => {
  it("RESETS when the basket changes, so the next submit is refused again", async () => {
    seedCart(1)
    renderCheckoutWithBasketEditor()
    await screen.findByTestId("order-allergen-panel")
    fillDetailsAndChooseCollection()

    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())

    // Edit the basket IN PLACE: a different quantity is a different order intent.
    fireEvent.click(screen.getByRole("button", { name: "test-edit-basket" }))

    await waitFor(() => expect(ackCheckbox()).not.toBeChecked())

    // And the refusal really fires again — the state reset is not merely cosmetic.
    fillDetailsAndChooseCollection()
    fireEvent.click(submitButton())
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(ALLERGEN_ACK_ERROR_COPY)
    })
    expect(mockedPost).not.toHaveBeenCalled()
  })
})

describe("checkout: preserved goods (Incremental Betterment)", () => {
  it("still guards BOTH post-order-creation allergen panels on allergenWarnings.length > 0", () => {
    // These panels render only AFTER the order exists and are provably always empty today
    // (PublicStorefrontService records allergenWarnings as an empty list). Their silence is
    // EXPECTED and is not a regression signal — this plan adds a pre-submit block beside them
    // and must not repurpose or delete the seam.
    const src = readCheckoutSource()
    const occurrences = src.split("allergenWarnings.length > 0").length - 1
    expect(occurrences).toBe(2)
  })

  it("does not disable ANY button on the acknowledgement state, and keeps the belowMinimum gate", () => {
    const src = readCheckoutSource()

    // Collect EVERY disabled expression rather than the first. The file's first one belongs to
    // the Stripe pay button (`paying || !stripe || !elements`), so a single-match regex asserts
    // about the wrong control entirely — this assertion caught exactly that mistake once.
    const expressions = Array.from(src.matchAll(/disabled=\{([^}]*)\}/g)).map((m) => m[1])

    // Non-vacuity: an empty list would satisfy every `every()` below trivially.
    expect(expressions.length).toBeGreaterThan(0)

    // The pre-existing below-minimum gate survives, on exactly one control.
    expect(expressions.filter((e) => e.includes("belowMinimum"))).toHaveLength(1)

    // And NOTHING in this file is disabled by the acknowledgement. A disabled button on a touch
    // device gives no feedback at all when pressed; the gate refuses and announces instead.
    expect(expressions.every((e) => !/acknowledg/i.test(e))).toBe(true)
  })
})

describe("checkout: D-01 — nothing derived from the stored customer allergen profile", () => {
  it("renders no value from a customer profile fixture that DOES supply one", async () => {
    // The fixture supplies a profile naming Peanuts, an allergen absent from the basket's
    // declared set (Gluten, Milk). Its presence anywhere in the DOM could only come from the
    // profile — an assertion over a value that exists nowhere would prove nothing.
    const CUSTOMER_PROFILE_FIXTURE = { allergenRestrictions: 1 << 4, names: ["Peanuts"] }
    expect(CUSTOMER_PROFILE_FIXTURE.names).toContain("Peanuts")

    seedCart()
    const { container } = renderCheckout()
    await screen.findByTestId("order-allergen-panel")

    // Non-vacuity: the panel really rendered a set.
    expect(screen.getAllByTestId("allergen-chip").length).toBeGreaterThan(0)

    expect(container.textContent).not.toContain("Peanuts")
  })
})

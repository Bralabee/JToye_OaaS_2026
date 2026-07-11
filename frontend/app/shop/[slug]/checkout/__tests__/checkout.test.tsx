/**
 * Tests for the storefront checkout page (UIX-04 / Surface E).
 *
 * Covers:
 *  - Pure fee-preview parity with the server waiver (delivery below/above the
 *    free-delivery threshold, and collection) — the client value must equal the
 *    value PublicStorefrontService recomputes server-side (T-19-06-01).
 *  - UK postcode validation (valid + invalid, case-insensitive).
 *  - Fulfilment toggle switching the conditional UK address block's visibility.
 *  - The definite fee breakdown (Subtotal + Delivery/Free + VAT + Total)
 *    rendering BEFORE payment.
 *  - An invalid postcode blocking submit with the exact inline error.
 *
 * CartProvider hydrates from localStorage in a useEffect, so tests seed
 * localStorage BEFORE rendering (mirrors __tests__/shop/cart.test.tsx).
 */

import { Suspense } from "react"
import { render, screen, fireEvent } from "@testing-library/react"
import CheckoutPage, {
  previewDeliveryFeePennies,
  isValidUkPostcode,
} from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"

// crypto.randomUUID is used in a useRef initialiser; jsdom may not expose it.
if (!globalThis.crypto || typeof globalThis.crypto.randomUUID !== "function") {
  Object.defineProperty(globalThis, "crypto", {
    value: { ...(globalThis.crypto || {}), randomUUID: () => "test-uuid-0000" },
    configurable: true,
  })
}

// Public API client — get() returns the shop (fee source), post() never fires
// in these Step-1-only tests.
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))

// No customer session — keeps the pre-fill effect quiet.
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
}))

jest.mock("@/lib/order-history", () => ({ saveLocalOrder: jest.fn() }))

// Stripe is never reached (no publishable key in test → stripePromise = null),
// but the imports must resolve without hitting the network.
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

// A shop whose delivery fee is £3.00, waived once the subtotal clears £30.00.
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

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function seedCart(subtotalPennies: number) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        {
          productId: "p1",
          title: "Jollof Rice",
          pricePennies: subtotalPennies,
          quantity: 1,
          imageUrl: null,
          category: "Mains",
        },
      ],
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

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedGet.mockResolvedValue({ data: SHOP })
})

afterEach(() => {
  localStorage.clear()
})

// ---------------------------------------------------------------------------
// Pure fee-preview parity (mirrors PublicStorefrontService.calculateDeliveryFee)
// ---------------------------------------------------------------------------

describe("previewDeliveryFeePennies — server waiver parity", () => {
  const fee = SHOP.deliveryFeePennies
  const threshold = SHOP.freeDeliveryThresholdPennies

  it("delivery below the free-delivery threshold charges the shop fee", () => {
    const subtotal = 1000 // £10.00 < £30.00 threshold
    const preview = previewDeliveryFeePennies(subtotal, "DELIVERY", fee, threshold)
    // Server: deliveryFee = shop.deliveryFeePennies (no waiver) => 300
    expect(preview).toBe(300)
    expect(subtotal + preview).toBe(1300) // client total == server total
  })

  it("delivery at/above the free-delivery threshold is waived to £0", () => {
    const subtotal = 5000 // £50.00 >= £30.00 threshold
    const preview = previewDeliveryFeePennies(subtotal, "DELIVERY", fee, threshold)
    // Server: subtotal >= freeDeliveryThreshold => 0
    expect(preview).toBe(0)
    expect(subtotal + preview).toBe(5000)
  })

  it("collection is always £0 regardless of subtotal", () => {
    const subtotal = 1000 // below threshold, but collection => free
    const preview = previewDeliveryFeePennies(subtotal, "COLLECTION", fee, threshold)
    // Server: fulfilmentType == COLLECTION => 0
    expect(preview).toBe(0)
    expect(subtotal + preview).toBe(1000)
  })
})

describe("isValidUkPostcode", () => {
  it("accepts valid UK postcodes (case-insensitive)", () => {
    expect(isValidUkPostcode("SW1A 1AA")).toBe(true)
    expect(isValidUkPostcode("sw9 8lf")).toBe(true)
    expect(isValidUkPostcode("M1 1AE")).toBe(true)
    expect(isValidUkPostcode("EC1A1BB")).toBe(true)
  })

  it("rejects malformed postcodes", () => {
    expect(isValidUkPostcode("")).toBe(false)
    expect(isValidUkPostcode("INVALID")).toBe(false)
    expect(isValidUkPostcode("12345")).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// Rendered checkout — fulfilment toggle, fee-before-payment, validation
// ---------------------------------------------------------------------------

describe("Checkout page (/shop/[slug]/checkout)", () => {
  it("defaults to Delivery, shows the address block, and hides it for Collection", async () => {
    seedCart(1000)
    renderCheckout()

    // Address block visible by default (DELIVERY)
    expect(await screen.findByText("Delivery address")).toBeTruthy()
    expect(screen.getByLabelText(/address line 1/i)).toBeTruthy()

    // Toggle to Collection → address block disappears
    fireEvent.click(screen.getByRole("button", { name: /collection/i }))
    expect(screen.queryByText("Delivery address")).toBeNull()

    // Toggle back to Delivery → address block returns
    fireEvent.click(screen.getByRole("button", { name: /^delivery$/i }))
    expect(screen.getByText("Delivery address")).toBeTruthy()
  })

  it("shows a definite fee breakdown BEFORE payment (delivery below threshold)", async () => {
    seedCart(1000) // £10.00 subtotal, below £30 threshold → £3.00 delivery
    renderCheckout()

    // Breakdown labels rendered (unique labels; "Delivery" also names the toggle
    // button, so we assert the delivery row via its unique fee value below).
    expect(await screen.findByText("Subtotal")).toBeTruthy()
    expect(screen.getByText(/VAT \(incl\. 20%\)/)).toBeTruthy()
    expect(screen.getByText("Total")).toBeTruthy()

    // Delivery fee (£3.00) and total (£13.00) appear once the shop fetch resolves
    expect(await screen.findByText("£3.00")).toBeTruthy()
    expect(await screen.findByText("£13.00")).toBeTruthy()

    // The deferred footnote is gone
    expect(screen.queryByText(/Final total confirmed/i)).toBeNull()
  })

  it("shows Free delivery for Collection (delivery fee waived)", async () => {
    seedCart(1000)
    renderCheckout()

    // Wait for the shop fetch so DELIVERY first shows the £3.00 fee
    expect(await screen.findByText("£3.00")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: /collection/i }))

    // Delivery is now Free and the £3.00 fee is no longer rendered
    expect(await screen.findByText("Free")).toBeTruthy()
    expect(screen.queryByText("£3.00")).toBeNull()
  })

  it("blocks submit on an invalid postcode with the exact inline error", async () => {
    seedCart(1000)
    renderCheckout()

    await screen.findByText("Delivery address")

    fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
    fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
    fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900000" } })
    fireEvent.change(screen.getByLabelText(/address line 1/i), { target: { value: "12 Coldharbour Lane" } })
    fireEvent.change(screen.getByLabelText(/town/i), { target: { value: "London" } })
    fireEvent.change(screen.getByLabelText(/postcode/i), { target: { value: "NOPE" } })

    fireEvent.click(screen.getByRole("button", { name: /place order/i }))

    expect(
      await screen.findByText(/Enter a valid UK postcode \(e\.g\. SW1A 1AA\)/i)
    ).toBeTruthy()
    expect(mockedPost).not.toHaveBeenCalled()
  })
})

/**
 * QA council 20260902-134741, Cluster E (client half of API-3 / API-4).
 *
 * The server now enforces the platform's V50 Idempotency-Key contract on the guest checkout:
 * the same key with a DIFFERENT body is refused 422 `errors/idempotency-payload-mismatch`.
 * The client's key used to be bound to the page MOUNT, so any basket change between two
 * submits (an out-of-stock line removed, an edit through the cart drawer) would have turned a
 * correct server fix into a hard 422 with no recovery path. These tests pin the two client
 * properties the server contract relies on:
 *
 *  1. every submit carries the `Idempotency-Key` HEADER, equal to the body `idempotencyKey`
 *     (both are sent: the header is the platform contract, the body field is the working
 *     legacy convention and stays authoritative server-side);
 *  2. the key is bound to the ORDER INTENT — an unchanged basket resubmits the SAME key (so a
 *     retry replays rather than duplicates), and a changed basket mints a NEW key (so it can
 *     never trip the payload-mismatch refusal).
 *
 * The basket is mutated through the real CartProvider API rather than by poking state, so the
 * test exercises the same `basketSignature` path a shopper's edit would.
 */

import { Suspense, useEffect } from "react"
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider, useCart } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"

// Deterministic, DISTINCT UUIDs: the assertions below compare keys across submits, so the
// generator must be able to produce a different value each call (a constant stub would make
// the "new key" assertion vacuous and the "same key" assertion pass by construction).
let uuidCounter = 0
const nextUuid = () => `00000000-0000-4000-8000-${String(++uuidCounter).padStart(12, "0")}` as const

beforeAll(() => {
  const existing = globalThis.crypto as Crypto | undefined
  if (existing && typeof existing.randomUUID === "function") {
    jest.spyOn(existing, "randomUUID").mockImplementation(nextUuid)
  } else {
    Object.defineProperty(globalThis, "crypto", {
      value: { ...(existing ?? {}), randomUUID: nextUuid },
      configurable: true,
    })
  }
})

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
  deliveryFeePennies: 300,
  freeDeliveryThresholdPennies: 3000,
  tags: null,
}

const COD_CONFIRMATION = {
  orderNumber: "ORD-TEST-IDEM-1",
  status: "PENDING",
  subtotalPennies: 1000,
  deliveryFeePennies: 0,
  vatRate: "STANDARD",
  vatAmountPennies: 167,
  totalAmountPennies: 1000,
  shopName: "Jollof Express",
  itemCount: 1,
  clientSecret: null,
  allergenWarnings: [],
}

type CartApi = ReturnType<typeof useCart>

/** Exposes the real cart API to the test so the basket can be edited the way a shopper would. */
function CartTap({ onCart }: { onCart: (cart: CartApi) => void }) {
  const cart = useCart()
  useEffect(() => {
    onCart(cart)
  }, [cart, onCart])
  return null
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

function renderCheckout(onCart: (cart: CartApi) => void = () => {}) {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CartTap onCart={onCart} />
        <CheckoutPage params={resolvedThenable({ slug: SLUG })} />
      </CartProvider>
    </Suspense>
  )
}

/** Fill the form for a COLLECTION order (no address) and acknowledge the allergen panel. */
async function armCheckout() {
  await screen.findByRole("button", { name: /place order/i })
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900000" } })
  acknowledgeAllergens()
}

function acknowledgeAllergens() {
  fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))
}

function placeOrder() {
  fireEvent.click(screen.getByRole("button", { name: /place order/i }))
}

/** The (payload, config) pair of the n-th POST, as the server would see it. */
function submittedKeys(callIndex: number): { bodyKey: unknown; headerKey: unknown } {
  const call = mockedPost.mock.calls[callIndex]
  expect(call).toBeDefined()
  const [, payload, config] = call as [string, { idempotencyKey?: unknown }, { headers?: Record<string, unknown> } | undefined]
  return { bodyKey: payload?.idempotencyKey, headerKey: config?.headers?.["Idempotency-Key"] }
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedGet.mockResolvedValue({ data: SHOP })
  seedCart()
})

afterEach(() => {
  localStorage.clear()
})

describe("Checkout idempotency contract (Cluster E client half)", () => {
  it("sends the Idempotency-Key HEADER equal to the body idempotencyKey on every submit (API-3)", async () => {
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    renderCheckout()
    await armCheckout()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))

    const { bodyKey, headerKey } = submittedKeys(0)
    expect(typeof bodyKey).toBe("string")
    expect(bodyKey).toBeTruthy()
    expect(headerKey).toBe(bodyKey)
  })

  it("CONTROL: an UNCHANGED basket resubmits the SAME key, so a retry replays instead of duplicating", async () => {
    // A failed first attempt leaves the form in place; the shopper simply tries again.
    mockedPost.mockRejectedValue(new Error("network down"))
    renderCheckout()
    await armCheckout()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    await screen.findByText(/Failed to place order/i)

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))

    const first = submittedKeys(0)
    const second = submittedKeys(1)
    expect(second.bodyKey).toBe(first.bodyKey)
    expect(second.headerKey).toBe(first.headerKey)
  })

  it("a CHANGED basket mints a NEW key before the next submit, so it can never trip the 422 payload-mismatch (API-4)", async () => {
    mockedPost.mockRejectedValue(new Error("network down"))
    let cart: CartApi | null = null
    renderCheckout((c) => {
      cart = c
    })
    await armCheckout()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    await screen.findByText(/Failed to place order/i)

    // The shopper edits the basket through the real cart API (what the cart drawer does).
    expect(cart).not.toBeNull()
    act(() => {
      cart!.updateQuantity("p1", 2)
    })
    // D-02: a basket change resets the acknowledgement — re-tick it, as a shopper would have to.
    await waitFor(() => {
      const box = screen.getByRole("checkbox", { name: /I have read the allergen information/i })
      expect(box.getAttribute("aria-checked") ?? (box as HTMLInputElement).checked.toString()).not.toBe("true")
    })
    acknowledgeAllergens()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))

    const first = submittedKeys(0)
    const second = submittedKeys(1)
    expect(second.bodyKey).toBeTruthy()
    expect(second.bodyKey).not.toBe(first.bodyKey)
    // And the header still travels with the body on the rotated key.
    expect(second.headerKey).toBe(second.bodyKey)
    // The edited quantity is what the second submit carries — it IS a different body.
    const secondPayload = mockedPost.mock.calls[1][1] as { items: Array<{ productId: string; quantity: number }> }
    expect(secondPayload.items).toEqual([{ productId: "p1", quantity: 2 }])
  })
})

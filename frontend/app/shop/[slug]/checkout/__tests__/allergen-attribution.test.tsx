/**
 * #860 at the checkout (31.1-23, D-18): the combined allergen set says which dish brings which
 * allergen, so a customer deciding what to drop does not have to reopen every dish.
 *
 * What must NOT change, and is asserted here as well:
 *   - the combined DECLARED set (the chips) is exactly what it was — attribution is said beneath it,
 *     never instead of it;
 *   - the acknowledged mask the checkout submits is the declared union, and the whole submit body is
 *     byte-identical for the same basket (pinned below; the same literal passes on the tree before
 *     this plan, recorded in the SUMMARY);
 *   - NOT RECORDED stays NOT RECORDED: one unresolved line and there is no set and no attribution.
 *
 * Harness as allergen-acknowledgement.test.tsx: the cart is seeded before render.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import {
  ALLERGEN_ACK_LABEL_COPY,
  ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY,
  ALLERGEN_PANEL_SUBLINE_COPY,
  allergenFlagCopy,
} from "@/components/storefront/order-allergen-panel"
import { ALLERGEN_ATTRIBUTION_INTRO_COPY } from "@/lib/allergen-copy"

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

const GLUTEN = 1 << 0
const EGGS = 1 << 2
const MILK = 1 << 6

function product(id: string, title: string, allergenMask: number, extra: Record<string, unknown> = {}) {
  return {
    id,
    title,
    description: null,
    imageUrl: null,
    imageUrls: [],
    ingredientsText: "",
    allergenMask,
    pricePennies: 500,
    category: "Mains",
    dietaryTags: null,
    preparationTimeMinutes: null,
    featured: false,
    inStock: true,
    ...extra,
  }
}

const PRODUCTS = {
  Mains: [product("p-jollof", "Jollof Rice", MILK), product("p-puff", "Puff Puff", MILK | EGGS)],
  Sides: [
    product("p-egusi", "Egusi", GLUTEN, { undeclaredIngredientAllergens: ["Milk"] }),
    product("p-plantain", "Plantain", 0),
  ],
}

function mockEndpoints(products: unknown = PRODUCTS) {
  mockedGet.mockImplementation((url: string) => {
    if (typeof url === "string" && url.endsWith("/products")) return Promise.resolve({ data: products })
    return Promise.resolve({ data: SHOP })
  })
}

function seedCart(lines: { productId: string; title: string; quantity?: number }[]) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: lines.map((l) => ({
        productId: l.productId,
        title: l.title,
        pricePennies: 500,
        quantity: l.quantity ?? 1,
        imageUrl: null,
        category: "Mains",
      })),
    })
  )
}

const JOLLOF_LINE = { productId: "p-jollof", title: "Jollof Rice" }
const PUFF_LINE = { productId: "p-puff", title: "Puff Puff", quantity: 2 }

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

function fillDetailsAndChooseCollection() {
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900000" } })
}

const panel = () => screen.getByTestId("order-allergen-panel")
const chips = () => screen.queryAllByTestId("allergen-chip").map((c) => c.textContent)
const attributionLines = () =>
  screen.queryAllByTestId("allergen-attribution-line").map((li) => li.textContent)
const ackCheckbox = () => screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })
const submitButton = () => screen.getByRole("button", { name: /place order/i })

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockEndpoints()
  mockedPost.mockResolvedValue({
    data: {
      orderNumber: "ORD-860",
      status: "PENDING",
      subtotalPennies: 1500,
      deliveryFeePennies: 0,
      vatRate: "STANDARD",
      vatAmountPennies: 250,
      totalAmountPennies: 1500,
      shopName: "Jollof Express",
      itemCount: 2,
      clientSecret: "",
      allergenWarnings: [],
    },
  })
})

afterEach(() => {
  localStorage.clear()
  jest.clearAllMocks()
})

describe("#860: the checkout attributes each declared allergen to its dish", () => {
  it("says 'Milk — Jollof Rice, Puff Puff' under the combined set, allergens in catalogue order", async () => {
    seedCart([JOLLOF_LINE, PUFF_LINE])
    renderCheckout()

    await waitFor(() => expect(attributionLines()).toEqual(["Eggs — Puff Puff", "Milk — Jollof Rice, Puff Puff"]))
    expect(within(panel()).getByText(ALLERGEN_ATTRIBUTION_INTRO_COPY)).toBeInTheDocument()
  })

  it("leaves the combined declared set itself unchanged (the chips)", async () => {
    seedCart([JOLLOF_LINE, PUFF_LINE])
    renderCheckout()
    await waitFor(() => expect(attributionLines()).toHaveLength(2))
    expect(chips()).toEqual(["Eggs", "Milk"])
  })

  it("is said beneath the chips and before the acknowledgement, inside the panel", async () => {
    seedCart([JOLLOF_LINE, PUFF_LINE])
    renderCheckout()
    await waitFor(() => expect(attributionLines()).toHaveLength(2))

    const list = screen.getByTestId("allergen-attribution")
    expect(panel()).toContainElement(list)
    const lastChip = screen.getAllByTestId("allergen-chip").at(-1)!
    expect(lastChip.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(list.compareDocumentPosition(ackCheckbox()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // goods P2-MAR-P2: the D-01/D-02 line stays.
    expect(within(panel()).getByText(ALLERGEN_PANEL_SUBLINE_COPY)).toBeInTheDocument()
  })

  it("a flagged line's D-09 name is its own advisory Check line, never a declared chip", async () => {
    seedCart([JOLLOF_LINE, { productId: "p-egusi", title: "Egusi" }])
    renderCheckout()

    await waitFor(() => expect(attributionLines()).toEqual(["Gluten — Egusi", "Milk — Jollof Rice"]))
    expect(chips()).toEqual(["Gluten", "Milk"])
    const flags = screen.getAllByTestId("allergen-flag").map((f) => f.textContent)
    expect(flags).toEqual([allergenFlagCopy({ productName: "Egusi", allergenName: "Milk", allergenBit: 6 })])
  })

  it("a dish that declares none is simply not named against any allergen", async () => {
    seedCart([JOLLOF_LINE, { productId: "p-plantain", title: "Plantain" }])
    renderCheckout()
    await waitFor(() => expect(attributionLines()).toEqual(["Milk — Jollof Rice"]))
  })

  it("NOT RECORDED: a line missing from the catalogue leaves no set and no attribution", async () => {
    seedCart([JOLLOF_LINE, { productId: "p-withdrawn", title: "Withdrawn dish" }])
    renderCheckout()

    expect(
      await screen.findByRole("heading", { name: ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY, level: 2 })
    ).toBeInTheDocument()
    expect(chips()).toEqual([])
    expect(screen.queryByTestId("allergen-attribution")).toBeNull()
  })
})

describe("#860: attribution changes nothing the checkout SUBMITS", () => {
  /**
   * The exact body for this basket and form, minus the per-attempt idempotency key. Pinned as a
   * string so a reordered, added or renamed field reds it; the acknowledged mask is MILK|EGGS = 68,
   * the declared union, and never OR-s in the Egusi-style advisory flags.
   */
  const EXPECTED_BODY =
    '{"customerName":"Ade Johnson","customerEmail":"ade@example.com","customerPhone":"07700900000","fulfilmentType":"COLLECTION","items":[{"productId":"p-jollof","quantity":1},{"productId":"p-puff","quantity":2}],"acknowledgedAllergenMask":68}'

  it("posts a body byte-identical to the pre-attribution checkout for the same basket", async () => {
    seedCart([JOLLOF_LINE, PUFF_LINE])
    renderCheckout()
    await waitFor(() => expect(chips()).toEqual(["Eggs", "Milk"]))
    fillDetailsAndChooseCollection()
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())
    fireEvent.click(submitButton())

    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    const body = { ...(mockedPost.mock.calls[0][1] as Record<string, unknown>) }
    expect(typeof body.idempotencyKey).toBe("string")
    delete body.idempotencyKey
    expect(body.acknowledgedAllergenMask).toBe(MILK | EGGS)
    expect(JSON.stringify(body)).toBe(EXPECTED_BODY)
  })
})

describe("#860: attribution never contradicts the set the panel states", () => {
  it("after a stale 409 shows the server's newer set, the client's attribution is withdrawn", async () => {
    seedCart([JOLLOF_LINE, PUFF_LINE])
    renderCheckout()
    await waitFor(() => expect(attributionLines()).toHaveLength(2))
    fillDetailsAndChooseCollection()
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())

    mockedPost.mockRejectedValueOnce({
      response: {
        status: 409,
        data: {
          type: "https://jtoye.uk/errors/allergen-acknowledgement-stale",
          status: 409,
          code: "ALLERGEN_ACKNOWLEDGEMENT_STALE",
          currentAllergenMask: MILK | EGGS | (1 << 10),
          currentAllergens: ["Eggs", "Milk", "Sesame"],
          acknowledgedAllergenMask: MILK | EGGS,
        },
      },
    })
    fireEvent.click(submitButton())

    await waitFor(() => expect(chips()).toEqual(["Eggs", "Milk", "Sesame"]))
    // The re-fetched catalogue still says Eggs|Milk, so it disagrees and the server's set stays.
    await waitFor(() => expect(mockedGet.mock.calls.filter(([u]) => String(u).endsWith("/products")).length).toBe(2))
    expect(screen.queryByTestId("allergen-attribution")).toBeNull()
  })
})

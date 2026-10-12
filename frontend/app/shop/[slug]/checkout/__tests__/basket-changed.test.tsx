/**
 * UXT-007 at the checkout (Phase 37-34, UI-SPEC § C1; D-16; RWO-007).
 *
 * 37-32/37-33 made the server compare the basket the customer SAW with the one it reads at submit:
 * Place order carries each line's displayed unit price and the displayed total, and any difference is
 * refused with ONE typed 409 `basket-changed` listing every change. Without this client half every
 * Place order would be refused 422 (no expected prices), and a vendor reprice mid-checkout would
 * reach the customer as a bare error or, on the old runtime, as a charge they never saw.
 *
 * The load-bearing assertions are the 31.1-15 ones: on every refusal path NO ORDER is created and
 * exactly the one refused POST was made; the customer is moved — focus, alert — to the one control
 * that lets them continue; and the resubmit after "Accept changes" reuses the SAME Idempotency-Key
 * (goods P2-CHA-12/13, P2-RAV-20: one order from any sequence of taps).
 *
 * The 409 bodies below are the shape GlobalExceptionHandler.handleBasketChanged publishes (37-33):
 * type, code BASKET_CHANGED, changes[] with only each kind's fields, and the four 31.1 allergen
 * properties only when the acknowledged set moved too.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import {
  ALLERGENS_ALSO_CHANGED_COPY,
  BASKET_CHANGED_ACCEPT_LABEL,
  BASKET_CHANGED_HEADING_COPY,
  BASKET_CHANGED_REFUSAL_COPY,
  BASKET_CONFIRMATION_REQUIRED_COPY,
  BASKET_UPDATED_NOTE_COPY,
} from "@/components/storefront/basket-changed-panel"
import { ALLERGEN_ACK_STALE_COPY } from "@/components/storefront/order-allergen-panel"
import { CUSTOMER_ID_KEY } from "@/lib/cart-identity"
import type { BasketChange } from "@/types/storefront"

// DISTINCT keys per call: the same-key arm asserts the SAME key across two submits, which a constant
// stub would make impossible to fail (the allergen-ack-stale precedent, with the opposite assertion).
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

const SHOP = {
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
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 0,
  deliveryFeePennies: 300,
  freeDeliveryThresholdPennies: 3000,
  tags: null,
  acceptsCardPayments: false,
}

function product(id: string, title: string, pricePennies: number, allergenMask = 0) {
  return {
    id,
    title,
    description: null,
    imageUrl: null,
    imageUrls: [],
    ingredientsText: "",
    allergenMask,
    pricePennies,
    category: "Mains",
    dietaryTags: null,
    preparationTimeMinutes: null,
    featured: false,
    inStock: true,
  }
}

/** The catalogue the customer read: Suya £9.00, Jollof Rice £5.00, no allergens declared. */
const CATALOGUE = { Mains: [product("p1", "Suya", 900), product("p2", "Jollof Rice", 500)] }

const COD_CONFIRMATION = {
  orderNumber: "ORD-BASKET-1",
  status: "PENDING",
  subtotalPennies: 2400,
  deliveryFeePennies: 0,
  vatRate: "ZERO",
  vatAmountPennies: 0,
  totalAmountPennies: 2400,
  shopName: "Rosie's Kitchen",
  itemCount: 2,
  clientSecret: null,
  allergenWarnings: [],
  acknowledgedAllergenMask: 0,
  acknowledgedAllergens: [],
  recordedAllergens: [],
  recordedAllergenFlags: [],
}

/** Exactly what 37-33's handler sends: the type, the code, and `changes` carrying each kind's fields. */
function basketChanged409(changes: BasketChange[] | undefined, extra: Record<string, unknown> = {}) {
  return {
    response: {
      status: 409,
      data: {
        type: "https://jtoye.uk/errors/basket-changed",
        title: "Your basket has changed",
        status: 409,
        detail: "The basket changed since it was priced. Show the customer each change in `changes`, then resubmit.",
        code: "BASKET_CHANGED",
        ...(changes === undefined ? {} : { changes }),
        ...extra,
      },
    },
  }
}

/** The UXT-007 repro: Suya repriced £9.00 -> £14.00 while the page was open; the total follows. */
const SUYA_REPRICED: BasketChange[] = [
  { kind: "PRICE_CHANGED", productId: "p1", name: "Suya", expected: 900, current: 1400 },
  { kind: "TOTAL_CHANGED", expected: 1900, current: 2400 },
]

const CONFIRMATION_REQUIRED_422 = {
  response: {
    status: 422,
    data: {
      type: "https://jtoye.uk/errors/basket-confirmation-required",
      title: "Expected prices are missing",
      status: 422,
      detail: "Expected prices are missing. Send expectedUnitPricePennies on each item and expectedTotalPennies.",
      code: "BASKET_CONFIRMATION_REQUIRED",
    },
  },
}

function mockEndpoints(catalogue: unknown = CATALOGUE, shop: unknown = SHOP) {
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? catalogue : shop })
  )
}

/** The basket as the customer saw it: Suya x1 at £9.00, Jollof Rice x2 at £5.00 (subtotal £19.00). */
function seedCart() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        { productId: "p1", title: "Suya", pricePennies: 900, quantity: 1, imageUrl: null, category: "Mains" },
        { productId: "p2", title: "Jollof Rice", pricePennies: 500, quantity: 2, imageUrl: null, category: "Mains" },
      ],
    })
  )
}

const storedItems = () =>
  (JSON.parse(localStorage.getItem(STORAGE_KEY) as string) as { items: Array<Record<string, unknown>> }).items

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

function fillDetails() {
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ravi Patel" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ravi@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900123" } })
}

const submitButton = () => screen.getByRole("button", { name: /place order|creating order/i })
const ackCheckbox = () => screen.getByRole("checkbox", { name: /I have read the allergen information/i })
const placeOrder = () => fireEvent.click(submitButton())

type PostedItem = { productId: string; quantity: number; expectedUnitPricePennies?: number }
type Posted = [
  string,
  { items: PostedItem[]; expectedTotalPennies?: number; idempotencyKey?: unknown; acknowledgedAllergenMask?: unknown },
  { headers?: Record<string, unknown> },
]
const posted = (i: number) => mockedPost.mock.calls[i] as Posted

/** Render, wait for the catalogue (the allergen panel's "declared none" heading), fill, choose, tick. */
async function armCheckout(fulfilment: "COLLECTION" | "DELIVERY" = "COLLECTION") {
  seedCart()
  renderCheckout()
  await screen.findByRole("heading", { name: "No allergens declared for this order" })
  if (fulfilment === "COLLECTION") {
    fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  } else {
    fireEvent.change(screen.getByLabelText(/address line 1/i), { target: { value: "12 Coldharbour Lane" } })
    fireEvent.change(screen.getByLabelText(/town \/ city/i), { target: { value: "London" } })
    fireEvent.change(screen.getByLabelText(/postcode/i), { target: { value: "SW9 8LF" } })
    // The fee preview reads the SHOP; wait for it so the displayed total is the one asserted.
    await screen.findByText("£3.00")
  }
  fillDetails()
  fireEvent.click(ackCheckbox())
  await waitFor(() => expect(ackCheckbox()).toBeChecked())
}

/** Arm, submit into the repriced 409, and wait for the refusal to land. */
async function submitIntoRepriced409(changes: BasketChange[] = SUYA_REPRICED) {
  mockEndpoints()
  mockedPost.mockRejectedValueOnce(basketChanged409(changes)).mockResolvedValue({ data: COD_CONFIRMATION })
  await armCheckout()
  placeOrder()
  await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
}

beforeEach(() => {
  uuidCounter = 0
  mockedGet.mockReset()
  mockedPost.mockReset()
})

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

describe("Place order carries what the customer saw (D-16, 37-32 contract)", () => {
  it("COLLECTION: each line's displayed unit price as expectedUnitPricePennies and the displayed total as expectedTotalPennies", async () => {
    mockEndpoints()
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    await armCheckout("COLLECTION")

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    const [, body] = posted(0)
    expect(body.items).toEqual([
      { productId: "p1", quantity: 1, expectedUnitPricePennies: 900 },
      { productId: "p2", quantity: 2, expectedUnitPricePennies: 500 },
    ])
    // £19.00: Suya £9.00 + Jollof Rice 2 x £5.00, collection carries no fee.
    expect(body.expectedTotalPennies).toBe(1900)
  })

  it("DELIVERY: the total includes the delivery fee the page showed (the server's FulfilmentPolicy rule, 37-32)", async () => {
    mockEndpoints()
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })
    await armCheckout("DELIVERY")
    // The button shows the total the customer is asked to commit to.
    expect(submitButton()).toHaveTextContent("£22.00")

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    // £19.00 + the £3.00 fee (subtotal below the £30.00 free-delivery threshold).
    expect(posted(0)[1].expectedTotalPennies).toBe(2200)
  })
})

describe("F14: a basket-changed 409 is shown and must be accepted (UI-SPEC § C1)", () => {
  it("shows the panel with the row 'Suya · £9.00 → £14.00' in a role=alert container, moves focus to its heading, and creates no order", async () => {
    await submitIntoRepriced409()

    const panel = await screen.findByTestId("basket-changed-panel")
    expect(panel).toHaveAttribute("role", "alert")
    const heading = screen.getByRole("heading", { level: 2, name: BASKET_CHANGED_HEADING_COPY })
    expect(screen.getAllByTestId("basket-change-row")[0]).toHaveTextContent("Suya · £9.00 → £14.00")
    expect(screen.getAllByTestId("basket-change-row")[1]).toHaveTextContent("Total was £19.00, now £24.00")
    await waitFor(() => expect(document.activeElement).toBe(heading))
    // 37-37 (C3): a placed cash order leaves the jtoye-just-placed marker and routes away; none was left.
    expect(Object.keys(sessionStorage).filter((k) => k.startsWith("jtoye-just-placed:"))).toEqual([])
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })

  it("sits directly above the allergen panel, inside the form", async () => {
    await submitIntoRepriced409()
    const panel = await screen.findByTestId("basket-changed-panel")
    const allergenPanel = screen.getByTestId("order-allergen-panel")
    expect(panel.closest("form")).toBe(allergenPanel.closest("form"))
    expect(panel.compareDocumentPosition(allergenPanel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // Directly above: the panel is the allergen panel's previous element sibling.
    expect(allergenPanel.previousElementSibling).toBe(panel)
  })

  it("Place order before 'Accept changes' is refused with a role=alert line, focus moves to Accept changes, and there is still exactly one POST", async () => {
    await submitIntoRepriced409()
    await screen.findByTestId("basket-changed-panel")
    await waitFor(() => expect(submitButton()).not.toBeDisabled())

    placeOrder()
    const refusal = await screen.findByText(BASKET_CHANGED_REFUSAL_COPY)
    expect(refusal).toHaveAttribute("role", "alert")
    const accept = screen.getByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL })
    await waitFor(() => expect(document.activeElement).toBe(accept))
    // Refuse-not-disable (the 31.1 pattern): the button stays enabled and gives feedback.
    expect(submitButton()).not.toBeDisabled()
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })

  it("after 'Accept changes' the panel collapses to the note and the resubmit reuses the SAME Idempotency-Key (one order from any tap sequence)", async () => {
    await submitIntoRepriced409()
    await screen.findByTestId("basket-changed-panel")
    await waitFor(() => expect(submitButton()).not.toBeDisabled())

    fireEvent.click(screen.getByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL }))
    expect(screen.getByText(BASKET_UPDATED_NOTE_COPY)).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL })).not.toBeInTheDocument()
    expect(screen.queryByText(BASKET_CHANGED_REFUSAL_COPY)).not.toBeInTheDocument()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))
    expect(posted(1)[1].idempotencyKey).toBe(posted(0)[1].idempotencyKey)
    expect(posted(1)[2].headers?.["Idempotency-Key"]).toBe(posted(0)[2].headers?.["Idempotency-Key"])
    // 37-37 (C3): the cash order was placed — the checkout left the order route's one-shot marker.
    await waitFor(() => expect(sessionStorage.getItem("jtoye-just-placed:ORD-BASKET-1")).toBe("ORD-BASKET-1"))
  })
})

describe("UI-SPEC § C1 loading: the pending state is the existing 'Creating order...' with the button disabled", () => {
  it("a second tap while the POST is in flight sends nothing, and a tap landing as the 409 returns hits the refusal, not a submit at unseen prices", async () => {
    mockEndpoints()
    let reject: (reason: unknown) => void = () => {}
    mockedPost
      .mockImplementationOnce(
        () =>
          new Promise((_resolve, rej) => {
            reject = rej
          })
      )
      .mockResolvedValue({ data: COD_CONFIRMATION })
    await armCheckout()

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    expect(submitButton()).toHaveTextContent("Creating order...")
    expect(submitButton()).toBeDisabled()

    // The second tap, while the first is in flight.
    placeOrder()
    expect(mockedPost).toHaveBeenCalledTimes(1)

    // The 409 lands.
    await act(async () => {
      reject(basketChanged409(SUYA_REPRICED))
    })
    await screen.findByTestId("basket-changed-panel")
    await waitFor(() => expect(submitButton()).not.toBeDisabled())

    // The tap that lands as the 409 returns is refused; nothing is submitted at prices not yet seen.
    placeOrder()
    await screen.findByText(BASKET_CHANGED_REFUSAL_COPY)
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })
})

describe("UI-SPEC § C1 empty and error: a malformed 409 and the 422 show the generic copy and leave the basket alone", () => {
  it.each<[string, unknown]>([
    ["changes[] empty", basketChanged409([])],
    ["changes missing", basketChanged409(undefined)],
    ["an entry without a readable kind", basketChanged409([{ kind: "SOMETHING_ELSE" } as unknown as BasketChange])],
  ])("a basket-changed 409 with %s: no panel, the refresh copy in the error slot, the stored basket untouched", async (_label, problem) => {
    mockEndpoints()
    mockedPost.mockRejectedValue(problem)
    await armCheckout()
    const before = storedItems()

    placeOrder()
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(BASKET_CONFIRMATION_REQUIRED_COPY)
    expect(screen.queryByTestId("basket-changed-panel")).not.toBeInTheDocument()
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(storedItems()).toEqual(before)
    // Place order is still here, still enabled: nothing was taken away.
    expect(submitButton()).not.toBeDisabled()
  })

  it("a 422 basket-confirmation-required (a cached pre-37-C page): the refresh copy in the error slot, no panel, one POST", async () => {
    mockEndpoints()
    mockedPost.mockRejectedValue(CONFIRMATION_REQUIRED_422)
    await armCheckout()
    const before = storedItems()

    placeOrder()
    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(BASKET_CONFIRMATION_REQUIRED_COPY)
    expect(screen.queryByTestId("basket-changed-panel")).not.toBeInTheDocument()
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(storedItems()).toEqual(before)
  })
})

/**
 * Task 2 (F15; UI-SPEC § C1 "What the page does with the change"): the page UPDATES the stored
 * basket to the server's values when the panel renders — new prices; unavailable and removed lines
 * dropped; sold-out quantities set to what is available or dropped at 0 — WITHOUT touching the
 * basket's ownership stamp. So the resubmit after "Accept changes" sends the new prices and the
 * new total under the same key, and the Place order label shows the new total.
 */
describe("the basket is rewritten to the server's values and still belongs to the right person (37-34 Task 2, F15)", () => {
  const MILK = 1 << 6
  const storedPayload = () => JSON.parse(localStorage.getItem(STORAGE_KEY) as string) as { owner?: string | null; items: Array<{ productId: string; pricePennies: number; quantity: number }> }
  const storedLines = () => storedPayload().items.map((i) => `${i.productId}:${i.pricePennies}x${i.quantity}`)

  it("a reprice: the stored Suya line is £14.00, the Place order label shows the new total, and the resubmit carries the new price and total under the SAME key", async () => {
    await submitIntoRepriced409()
    await screen.findByTestId("basket-changed-panel")
    // Stored content, not the rendered view: Suya at 1400, Jollof Rice as it was.
    await waitFor(() => expect(storedLines()).toEqual(["p1:1400x1", "p2:500x2"]))

    fireEvent.click(screen.getByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL }))
    // £14.00 + 2 x £5.00, collection: the label is the new total the customer now commits to.
    expect(submitButton()).toHaveTextContent("Place order · £24.00")

    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))
    expect(posted(1)[1].items).toEqual([
      { productId: "p1", quantity: 1, expectedUnitPricePennies: 1400 },
      { productId: "p2", quantity: 2, expectedUnitPricePennies: 500 },
    ])
    expect(posted(1)[1].expectedTotalPennies).toBe(2400)
    expect(posted(1)[1].idempotencyKey).toBe(posted(0)[1].idempotencyKey)
    // 37-37 (C3): the cash order was placed — the checkout left the order route's one-shot marker.
    await waitFor(() => expect(sessionStorage.getItem("jtoye-just-placed:ORD-BASKET-1")).toBe("ORD-BASKET-1"))
  })

  it("sold out, unavailable and removed: the stored lines follow the diff, a REMOVED line is still named from the basket's copy, and one Place order after Accept submits once without a re-tick", async () => {
    await submitIntoRepriced409([
      { kind: "REMOVED", productId: "p1" },
      { kind: "SOLD_OUT", productId: "p2", name: "Jollof Rice", requested: 2, available: 1 },
      { kind: "TOTAL_CHANGED", expected: 1900, current: 500 },
    ])
    await screen.findByTestId("basket-changed-panel")
    await waitFor(() => expect(storedLines()).toEqual(["p2:500x1"]))
    const rows = screen.getAllByTestId("basket-change-row")
    expect(rows[0]).toHaveTextContent("Suya · no longer on this shop's menu, removed from your basket")
    expect(rows[1]).toHaveTextContent("Jollof Rice · only 1 left, we've changed your quantity from 2 to 1")
    // The server sent no allergen properties: the set the customer acknowledged is unchanged, and
    // the box stays ticked — one Place order after Accept changes submits.
    expect(ackCheckbox()).toBeChecked()

    fireEvent.click(screen.getByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL }))
    expect(submitButton()).toHaveTextContent("Place order · £5.00")
    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))
    expect(posted(1)[1].items).toEqual([{ productId: "p2", quantity: 1, expectedUnitPricePennies: 500 }])
    expect(posted(1)[1].expectedTotalPennies).toBe(500)
    expect(posted(1)[1].acknowledgedAllergenMask).toBe(0)
    expect(posted(1)[1].idempotencyKey).toBe(posted(0)[1].idempotencyKey)
  })

  it("F15 at the page: the ownership stamp is byte-identical through the rewrite, with the session lapsed between the 409 and the update", async () => {
    // A signed in when the basket was stamped; the access cookie lapsed by checkout (the id is
    // gone, nobody is signed in now). The rewrite must preserve A — never downgrade to null.
    mockEndpoints()
    mockedPost.mockRejectedValueOnce(basketChanged409(SUYA_REPRICED)).mockResolvedValue({ data: COD_CONFIRMATION })
    seedCart()
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...storedPayload(), owner: "sub-customer-a" }))
    localStorage.removeItem(CUSTOMER_ID_KEY)
    renderCheckout()
    await screen.findByRole("heading", { name: "No allergens declared for this order" })
    fireEvent.click(screen.getByRole("button", { name: /collection/i }))
    fillDetails()
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())
    const ownerBefore = JSON.stringify(storedPayload().owner)
    expect(ownerBefore).toBe('"sub-customer-a"')

    placeOrder()
    await screen.findByTestId("basket-changed-panel")
    await waitFor(() => expect(storedLines()).toEqual(["p1:1400x1", "p2:500x2"]))
    expect(JSON.stringify(storedPayload().owner)).toBe(ownerBefore)
  })

  it("the same 409 carrying the allergen properties: the allergen panel re-renders the new set unticked, the panel says so, and one Place order after re-tick + Accept submits once with the new mask", async () => {
    const CATALOGUE_AFTER_EDIT = { Mains: [product("p1", "Suya", 1400, MILK), product("p2", "Jollof Rice", 500)] }
    mockedGet.mockImplementation((url: string) => {
      if (typeof url === "string" && url.endsWith("/products")) {
        // Mount reads the catalogue as the customer read it; the re-fetch after the 409 reads the edited one.
        const calls = mockedGet.mock.calls.filter(([u]) => typeof u === "string" && u.endsWith("/products")).length
        return Promise.resolve({ data: calls <= 1 ? CATALOGUE : CATALOGUE_AFTER_EDIT })
      }
      return Promise.resolve({ data: SHOP })
    })
    mockedPost
      .mockRejectedValueOnce(
        basketChanged409(SUYA_REPRICED, {
          currentAllergenMask: MILK,
          currentAllergens: ["Milk"],
          acknowledgedAllergenMask: 0,
          lines: [
            { productId: "p1", productName: "Suya", allergenMask: MILK, allergens: ["Milk"] },
            { productId: "p2", productName: "Jollof Rice", allergenMask: 0, allergens: [] },
          ],
        })
      )
      .mockResolvedValue({ data: { ...COD_CONFIRMATION, acknowledgedAllergenMask: MILK, acknowledgedAllergens: ["Milk"], recordedAllergens: ["Milk"] } })
    seedCart()
    renderCheckout()
    await screen.findByRole("heading", { name: "No allergens declared for this order" })
    fireEvent.click(screen.getByRole("button", { name: /collection/i }))
    fillDetails()
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())

    placeOrder()
    await screen.findByTestId("basket-changed-panel")
    expect(screen.getByText(ALLERGENS_ALSO_CHANGED_COPY)).toBeInTheDocument()
    // The allergen panel now states the server's set, unticked.
    await waitFor(() => expect(screen.getAllByTestId("allergen-chip").map((c) => c.textContent)).toEqual(["Milk"]))
    await waitFor(() => expect(ackCheckbox()).not.toBeChecked())
    await waitFor(() => expect(storedLines()).toEqual(["p1:1400x1", "p2:500x2"]))
    expect(mockedPost).toHaveBeenCalledTimes(1)

    // Re-tick, accept, one Place order.
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())
    fireEvent.click(screen.getByRole("button", { name: BASKET_CHANGED_ACCEPT_LABEL }))
    placeOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))
    expect(posted(1)[1].acknowledgedAllergenMask).toBe(MILK)
    expect(posted(1)[1].items[0]).toEqual({ productId: "p1", quantity: 1, expectedUnitPricePennies: 1400 })
    expect(posted(1)[1].idempotencyKey).toBe(posted(0)[1].idempotencyKey)
    // 37-37 (C3): the cash order was placed — the checkout left the order route's one-shot marker.
    await waitFor(() => expect(sessionStorage.getItem("jtoye-just-placed:ORD-BASKET-1")).toBe("ORD-BASKET-1"))
  })

  it("an allergen-only 409 keeps the unchanged 31.1 path: no basket panel, the stale copy in the allergen panel, the stored prices untouched", async () => {
    mockEndpoints()
    mockedPost.mockRejectedValueOnce({
      response: {
        status: 409,
        data: {
          type: "https://jtoye.uk/errors/allergen-acknowledgement-stale",
          title: "Allergen information changed",
          status: 409,
          code: "ALLERGEN_ACKNOWLEDGEMENT_STALE",
          currentAllergenMask: MILK,
          currentAllergens: ["Milk"],
          acknowledgedAllergenMask: 0,
          lines: [{ productId: "p1", productName: "Suya", allergenMask: MILK, allergens: ["Milk"] }],
        },
      },
    })
    await armCheckout()
    const before = storedLines()

    placeOrder()
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(ALLERGEN_ACK_STALE_COPY))
    expect(screen.queryByTestId("basket-changed-panel")).not.toBeInTheDocument()
    expect(storedLines()).toEqual(before)
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })
})

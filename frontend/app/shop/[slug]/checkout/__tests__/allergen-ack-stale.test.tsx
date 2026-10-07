/**
 * #784 / #785 at the checkout (Phase 31.1-15, D-05): the submit carries the allergen set the
 * panel SHOWED, and a stale acknowledgement is recovered from honestly.
 *
 * 31.1-03 made the server refuse a storefront order with no `acknowledgedAllergenMask` (422) and
 * one whose mask differs from the basket's declared union at submit (409
 * `allergen-acknowledgement-stale`, carrying the CURRENT set). Without this client half every
 * customer's Place order would be refused, and a vendor edit landing mid-checkout would leave the
 * customer with a generic error and no way back.
 *
 * The load-bearing assertions are the same as the 31-14 gate's: on every refusal path NO ORDER is
 * created (no confirmation, and on the client-side refusals no POST at all), and the customer is
 * moved — focus, alert, unticked box — to the one control that lets them continue.
 *
 * The 409 body below is the shape GlobalExceptionHandler.handleAllergenAcknowledgementStale
 * publishes (31.1-03): type, code, currentAllergenMask, currentAllergens (bit order),
 * acknowledgedAllergenMask, lines (basket order).
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import {
  ALLERGEN_ACK_LABEL_COPY,
  ALLERGEN_ACK_STALE_COPY,
  ALLERGEN_ACK_UNAVAILABLE_COPY,
  ALLERGEN_PANEL_SUBLINE_COPY,
} from "@/components/storefront/order-allergen-panel"

// DISTINCT keys per call: the re-acknowledge arm asserts a NEW key, which a constant stub would
// make impossible to fail, and the lost-response arm asserts the SAME key, which a constant stub
// would make impossible to fail in the other direction.
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
  deliveryFeePennies: 0,
  freeDeliveryThresholdPennies: 0,
  tags: null,
  acceptsCardPayments: false,
}

const GLUTEN = 1 << 0
const FISH = 1 << 3
const PEANUTS = 1 << 4
const MILK = 1 << 6

function product(id: string, title: string, allergenMask: number) {
  return {
    id,
    title,
    description: null,
    imageUrl: null,
    imageUrls: [],
    ingredientsText: "",
    allergenMask,
    pricePennies: 900,
    category: "Mains",
    dietaryTags: null,
    preparationTimeMinutes: null,
    featured: false,
    inStock: true,
  }
}

/** The catalogue the customer read: Satay -> Peanuts, Fish Pie -> Gluten + Fish. Union 25. */
const PRODUCTS_AS_READ = {
  Mains: [product("p1", "Satay", PEANUTS), product("p2", "Fish Pie", GLUTEN | FISH)],
}
/** The catalogue after the vendor's edit: Fish Pie now also declares Milk. Union 89. */
const PRODUCTS_AFTER_EDIT = {
  Mains: [product("p1", "Satay", PEANUTS), product("p2", "Fish Pie", GLUTEN | FISH | MILK)],
}

const READ_MASK = GLUTEN | FISH | PEANUTS
const CURRENT_MASK = READ_MASK | MILK

/** Exactly what 31.1-03's handler sends for the persona race. */
const STALE_409 = {
  response: {
    status: 409,
    data: {
      type: "https://jtoye.uk/errors/allergen-acknowledgement-stale",
      title: "Allergen information changed",
      status: 409,
      detail:
        "The allergen information for your basket changed after you read it. Read it again and confirm before placing the order.",
      code: "ALLERGEN_ACKNOWLEDGEMENT_STALE",
      currentAllergenMask: CURRENT_MASK,
      currentAllergens: ["Gluten", "Fish", "Peanuts", "Milk"],
      acknowledgedAllergenMask: READ_MASK,
      lines: [
        { productId: "p1", productName: "Satay", allergenMask: PEANUTS, allergens: ["Peanuts"] },
        {
          productId: "p2",
          productName: "Fish Pie",
          allergenMask: GLUTEN | FISH | MILK,
          allergens: ["Gluten", "Fish", "Milk"],
        },
      ],
    },
  },
}

const COD_CONFIRMATION = {
  orderNumber: "ORD-RACE-1",
  status: "PENDING",
  subtotalPennies: 1800,
  deliveryFeePennies: 0,
  vatRate: "ZERO",
  vatAmountPennies: 0,
  totalAmountPennies: 1800,
  shopName: "Rosie's Kitchen",
  itemCount: 2,
  clientSecret: null,
  allergenWarnings: [],
  acknowledgedAllergenMask: CURRENT_MASK,
  acknowledgedAllergens: ["Gluten", "Fish", "Peanuts", "Milk"],
  recordedAllergens: ["Gluten", "Fish", "Peanuts", "Milk"],
  recordedAllergenFlags: [],
}

/** Route GETs: the products endpoint answers from `productResponses` in order, the shop always. */
function mockEndpoints(productResponses: Array<unknown | Error>) {
  let call = 0
  mockedGet.mockImplementation((url: string) => {
    if (typeof url === "string" && url.endsWith("/products")) {
      const next = productResponses[Math.min(call, productResponses.length - 1)]
      call += 1
      return next instanceof Error ? Promise.reject(next) : Promise.resolve({ data: next })
    }
    return Promise.resolve({ data: SHOP })
  })
}

const productGets = () =>
  mockedGet.mock.calls.filter(([url]) => typeof url === "string" && url.endsWith("/products")).length

function seedCart() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        { productId: "p1", title: "Satay", pricePennies: 900, quantity: 1, imageUrl: null, category: "Mains" },
        { productId: "p2", title: "Fish Pie", pricePennies: 900, quantity: 1, imageUrl: null, category: "Mains" },
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

function fillDetailsAndChooseCollection() {
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ravi Patel" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ravi@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900123" } })
}

const submitButton = () => screen.getByRole("button", { name: /place order/i })
const ackCheckbox = () => screen.getByRole("checkbox", { name: ALLERGEN_ACK_LABEL_COPY })
const chips = () => screen.queryAllByTestId("allergen-chip").map((c) => c.textContent)

type Posted = [string, { acknowledgedAllergenMask?: unknown; idempotencyKey?: unknown }, { headers?: Record<string, unknown> }]
const posted = (i: number) => mockedPost.mock.calls[i] as Posted

/** Render, wait for the declared set to resolve, fill the form, tick, and submit. */
async function readTickAndSubmit() {
  seedCart()
  renderCheckout()
  await waitFor(() => expect(chips()).toEqual(["Gluten", "Fish", "Peanuts"]))
  fillDetailsAndChooseCollection()
  fireEvent.click(ackCheckbox())
  await waitFor(() => expect(ackCheckbox()).toBeChecked())
  fireEvent.click(submitButton())
}

beforeEach(() => {
  uuidCounter = 0
  mockedGet.mockReset()
  mockedPost.mockReset()
})

afterEach(() => {
  localStorage.clear()
})

describe("the submit carries the set the panel showed (#784, D-05)", () => {
  it("posts acknowledgedAllergenMask = the OR of the basket products' declared masks", async () => {
    mockEndpoints([PRODUCTS_AS_READ])
    mockedPost.mockResolvedValue({ data: COD_CONFIRMATION })

    await readTickAndSubmit()

    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    // 25, not 0 and not absent: the panel listed Gluten, Fish and Peanuts.
    expect(posted(0)[1].acknowledgedAllergenMask).toBe(READ_MASK)
  })
})

describe("a stale acknowledgement (409 allergen-acknowledgement-stale) is recovered from (#785)", () => {
  async function submitIntoA409() {
    // Mount reads the old catalogue; the re-fetch after the 409 reads the edited one.
    mockEndpoints([PRODUCTS_AS_READ, PRODUCTS_AFTER_EDIT])
    mockedPost.mockRejectedValueOnce(STALE_409).mockResolvedValue({ data: COD_CONFIRMATION })
    await readTickAndSubmit()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
  }

  it("re-renders the panel from the server's set: Milk is now listed", async () => {
    await submitIntoA409()
    await waitFor(() => expect(chips()).toEqual(["Gluten", "Fish", "Peanuts", "Milk"]))
  })

  it("unticks the box, announces the change in the panel's alert region, and creates no order", async () => {
    await submitIntoA409()

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(ALLERGEN_ACK_STALE_COPY))
    expect(ackCheckbox()).not.toBeChecked()
    // The alert is the PANEL's region (the one the checkbox is described by), not the page's
    // generic error box — so the announcement sits next to the control that resolves it.
    expect(screen.getByTestId("order-allergen-panel")).toContainElement(screen.getByRole("alert"))
    expect(ackCheckbox()).toHaveAttribute("aria-invalid", "true")
    expect(screen.queryByText(/order confirmed/i)).not.toBeInTheDocument()
    // Exactly the one refused POST — nothing was re-sent behind the customer's back.
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })

  it("moves focus to the acknowledgement checkbox and keeps Place order enabled", async () => {
    await submitIntoA409()

    await waitFor(() => expect(document.activeElement).toBe(ackCheckbox()))
    await waitFor(() => expect(submitButton()).not.toHaveAttribute("disabled"))
  })

  it("refuses a resubmit until the customer ticks again — still no second POST", async () => {
    await submitIntoA409()
    await waitFor(() => expect(ackCheckbox()).not.toBeChecked())
    await waitFor(() => expect(submitButton()).not.toBeDisabled())

    fireEvent.click(submitButton())
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })

  it("re-ticked, the resubmit carries the NEW mask under a NEW Idempotency-Key", async () => {
    await submitIntoA409()
    await waitFor(() => expect(chips()).toEqual(["Gluten", "Fish", "Peanuts", "Milk"]))
    await waitFor(() => expect(submitButton()).not.toBeDisabled())

    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())
    fireEvent.click(submitButton())
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))

    expect(posted(1)[1].acknowledgedAllergenMask).toBe(CURRENT_MASK)
    expect(posted(1)[1].idempotencyKey).not.toBe(posted(0)[1].idempotencyKey)
    expect(posted(1)[2].headers?.["Idempotency-Key"]).toBe(posted(1)[1].idempotencyKey)
    await screen.findByText(/order confirmed/i)
  })

  it("holds the server's set even when the re-fetch fails, so the resubmit still sends it", async () => {
    // The 409 itself is the authority on what the server will accept for this basket. A failed
    // re-fetch must not drop the panel back to the set the customer can no longer acknowledge.
    mockEndpoints([PRODUCTS_AS_READ, new Error("offline")])
    mockedPost.mockRejectedValueOnce(STALE_409).mockResolvedValue({ data: COD_CONFIRMATION })
    await readTickAndSubmit()

    await waitFor(() => expect(chips()).toEqual(["Gluten", "Fish", "Peanuts", "Milk"]))
    await waitFor(() => expect(submitButton()).not.toBeDisabled())
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())
    fireEvent.click(submitButton())
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))
    expect(posted(1)[1].acknowledgedAllergenMask).toBe(CURRENT_MASK)
  })

  it("leaves the legally-operative subline copy in place through the stale state", async () => {
    await submitIntoA409()
    await screen.findByText(ALLERGEN_ACK_STALE_COPY)
    expect(screen.getByText(ALLERGEN_PANEL_SUBLINE_COPY)).toBeInTheDocument()
  })
})

describe("an unchanged intent keeps its key (existing behaviour, P2-CHA-13)", () => {
  it("a lost response followed by a retry resends the SAME key and the same mask", async () => {
    mockEndpoints([PRODUCTS_AS_READ])
    mockedPost
      .mockRejectedValueOnce({ message: "Network Error" }) // no response: the reply was lost
      .mockResolvedValue({ data: COD_CONFIRMATION })

    await readTickAndSubmit()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(submitButton()).not.toBeDisabled())

    // A lost reply is NOT a stale acknowledgement: the box stays ticked.
    expect(ackCheckbox()).toBeChecked()
    fireEvent.click(submitButton())
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(2))

    expect(posted(1)[1].idempotencyKey).toBe(posted(0)[1].idempotencyKey)
    expect(posted(1)[1].acknowledgedAllergenMask).toBe(READ_MASK)
  })
})

describe("the catalogue could not be loaded: refuse, never guess (T-31.1-53)", () => {
  it("refuses with an announced message and makes NO POST — never a zero or guessed mask", async () => {
    mockEndpoints([new Error("offline")])

    seedCart()
    renderCheckout()
    await screen.findByRole("heading", { name: "Allergen information not recorded for this order" })
    fillDetailsAndChooseCollection()
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())
    fireEvent.click(submitButton())

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(ALLERGEN_ACK_UNAVAILABLE_COPY))
    expect(mockedPost).not.toHaveBeenCalled()
    // It tried once more before refusing: the mount fetch plus exactly one re-fetch.
    expect(productGets()).toBe(2)
    expect(submitButton()).not.toBeDisabled()
  })

  it("when the re-fetch succeeds it shows the set and asks for a fresh tick instead of sending it", async () => {
    // The customer ticked a panel that said NOT RECORDED. Sending the set that has just arrived
    // would record an acknowledgement of something they were never shown — D-05's exact defect.
    mockEndpoints([new Error("offline"), PRODUCTS_AS_READ])

    seedCart()
    renderCheckout()
    await screen.findByRole("heading", { name: "Allergen information not recorded for this order" })
    fillDetailsAndChooseCollection()
    fireEvent.click(ackCheckbox())
    await waitFor(() => expect(ackCheckbox()).toBeChecked())
    fireEvent.click(submitButton())

    await waitFor(() => expect(chips()).toEqual(["Gluten", "Fish", "Peanuts"]))
    expect(ackCheckbox()).not.toBeChecked()
    expect(screen.getByRole("alert")).toHaveTextContent(ALLERGEN_ACK_STALE_COPY)
    expect(mockedPost).not.toHaveBeenCalled()
  })
})

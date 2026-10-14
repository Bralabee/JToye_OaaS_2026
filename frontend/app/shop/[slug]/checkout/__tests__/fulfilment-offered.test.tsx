/**
 * 37-49 Task 1 (UI-SPEC G1, UXT-023, RWO-023): checkout offers only what the shop offers.
 *
 * 37-48 gave the server the two flags (`PublicShopDto.offersDelivery` / `offersCollection`, always
 * booleans on the wire) and a typed refusal for a type the shop does not take (422
 * `fulfilment-not-offered` with `fulfilmentType` and `offered[]`). Before this plan the checkout
 * rendered Delivery and Collection for every shop and defaulted to Delivery, so a collection-only
 * shop showed a delivery address form and a delivery fee for an order the server would refuse.
 *
 * Driven through the REAL checkout page and CartProvider; every claim about what is SENT is read from
 * the POST body.
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
const CATALOGUE = { Mains: [{ id: "p1", allergenMask: 0 }] }

// A £2.99 fee, waived at £30.00 and above (the threshold is inclusive, as the server's FulfilmentPolicy).
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
  deliveryFeePennies: 299,
  freeDeliveryThresholdPennies: 3000,
  tags: null,
  offersDelivery: true,
  offersCollection: true,
}

const COLLECTION_ONLY = { ...SHOP, offersDelivery: false, offersCollection: true }
const DELIVERY_ONLY = { ...SHOP, offersDelivery: true, offersCollection: false }

function mockShop(shop: unknown) {
  mockedGet.mockImplementation((url: string) =>
    Promise.resolve({ data: typeof url === "string" && url.endsWith("/products") ? CATALOGUE : shop })
  )
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function seedCart(pricePennies: number) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [{ productId: "p1", title: "Suya", pricePennies, quantity: 1, imageUrl: null, category: "Mains" }],
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

/** The order-summary row whose label is `label` (the toggle buttons also say "Delivery"). */
function summaryRow(label: string): HTMLElement {
  const summary = screen.getByText("Subtotal").closest("div")?.parentElement as HTMLElement
  const cell = within(summary).getByText(label)
  return cell.parentElement as HTMLElement
}

function fillContact() {
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700 900000" } })
}

function fillAddress() {
  fireEvent.change(screen.getByLabelText(/address line 1/i), { target: { value: "12 Coldharbour Lane" } })
  fireEvent.change(screen.getByLabelText(/town/i), { target: { value: "London" } })
  fireEvent.change(screen.getByLabelText(/postcode/i), { target: { value: "SW9 8LF" } })
}

function acknowledgeAndPlace() {
  fireEvent.click(screen.getByRole("checkbox", { name: /I have read the allergen information/i }))
  fireEvent.click(screen.getByRole("button", { name: /place order/i }))
}

const CONFIRMATION = {
  orderNumber: "ORD-G1-1",
  status: "PENDING",
  subtotalPennies: 2000,
  deliveryFeePennies: 0,
  vatRate: "STANDARD",
  vatAmountPennies: 333,
  totalAmountPennies: 2000,
  shopName: "Jollof Express",
  itemCount: 1,
  clientSecret: null,
  allergenWarnings: [],
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
})

afterEach(() => {
  localStorage.clear()
})

describe("checkout renders only the fulfilment types the shop offers (UI-SPEC G1)", () => {
  it("a collection-only shop: no Delivery option, the static row 'Collection only. This shop doesn't deliver.', no address form, and the order is sent as COLLECTION", async () => {
    mockShop(COLLECTION_ONLY)
    mockedPost.mockResolvedValue({ data: CONFIRMATION })
    seedCart(2000)
    renderCheckout()

    expect(await screen.findByText("Collection only. This shop doesn't deliver.")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /^delivery$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /^collection$/i })).not.toBeInTheDocument()
    expect(screen.queryByText("Delivery address")).not.toBeInTheDocument()
    // Collection pays no delivery fee, so the preview cannot show the £2.99 a delivery would.
    expect(summaryRow("Delivery")).toHaveTextContent("Free")

    fillContact()
    acknowledgeAndPlace()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    expect(mockedPost.mock.calls[0][1]).toMatchObject({ fulfilmentType: "COLLECTION" })
  })

  it("a delivery-only shop: no Collection option and the static row 'Delivery only. This shop doesn't offer collection.'", async () => {
    mockShop(DELIVERY_ONLY)
    seedCart(2000)
    renderCheckout()

    expect(await screen.findByText("Delivery only. This shop doesn't offer collection.")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /^collection$/i })).not.toBeInTheDocument()
    expect(screen.getByText("Delivery address")).toBeInTheDocument()
  })

  it("a shop that offers both keeps the two-option segmented control (zero-one-many: two)", async () => {
    mockShop(SHOP)
    seedCart(2000)
    renderCheckout()

    expect(await screen.findByRole("button", { name: /^delivery$/i })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /^collection$/i })).toBeInTheDocument()
    expect(screen.queryByText(/This shop doesn't deliver/)).not.toBeInTheDocument()
    expect(screen.queryByText(/This shop doesn't offer collection/)).not.toBeInTheDocument()
  })

  it("an older payload with no flags is read as both offered (the control is unchanged)", async () => {
    const { offersDelivery: _d, offersCollection: _c, ...legacy } = SHOP
    void _d
    void _c
    mockShop(legacy)
    seedCart(2000)
    renderCheckout()

    expect(await screen.findByRole("button", { name: /^delivery$/i })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /^collection$/i })).toBeInTheDocument()
  })
})

describe("the delivery fee preview follows the declared fee and threshold (UI-SPEC G1, goods P2-REG-17)", () => {
  it("£20.00 of food, below the £30.00 threshold: Delivery £2.99, Total £22.99", async () => {
    mockShop(SHOP)
    seedCart(2000)
    renderCheckout()

    await screen.findByRole("button", { name: /^delivery$/i })
    await waitFor(() => expect(summaryRow("Delivery")).toHaveTextContent("£2.99"))
    expect(summaryRow("Total")).toHaveTextContent("£22.99")
  })

  it("£30.00 of food, exactly the threshold: Delivery Free, Total £30.00", async () => {
    mockShop(SHOP)
    seedCart(3000)
    renderCheckout()

    await screen.findByRole("button", { name: /^delivery$/i })
    await waitFor(() => expect(summaryRow("Total")).toHaveTextContent("£30.00"))
    expect(summaryRow("Delivery")).toHaveTextContent("Free")
  })
})

describe("the server's fulfilment refusal (422 fulfilment-not-offered)", () => {
  it("renders 'This shop doesn't offer delivery. Choose collection to continue.' in the error slot and switches to the offered type", async () => {
    // The shop stopped delivering after the page loaded: the page still shows both options.
    mockShop(SHOP)
    mockedPost.mockRejectedValueOnce({
      response: {
        status: 422,
        headers: {},
        data: {
          type: "https://jtoye.uk/errors/fulfilment-not-offered",
          title: "Fulfilment type not offered",
          status: 422,
          detail: "This shop doesn't offer delivery. Choose collection to continue.",
          code: "FULFILMENT_NOT_OFFERED",
          fulfilmentType: "DELIVERY",
          offered: ["COLLECTION"],
        },
      },
    })
    seedCart(2000)
    renderCheckout()

    await screen.findByRole("button", { name: /^delivery$/i })
    fillContact()
    fillAddress()
    acknowledgeAndPlace()

    const alert = await screen.findByText("This shop doesn't offer delivery. Choose collection to continue.")
    expect(alert.closest("[role='alert']")).not.toBeNull()
    // The server named what it takes: the control now offers only that, so the next tap continues.
    expect(await screen.findByText("Collection only. This shop doesn't deliver.")).toBeInTheDocument()
    expect(screen.queryByText("Delivery address")).not.toBeInTheDocument()
    expect(mockedPost).toHaveBeenCalledTimes(1)
  })

  it("the copy is built from the typed members, not the detail: a refused COLLECTION reads the collection sentence", async () => {
    mockShop(SHOP)
    mockedPost.mockRejectedValueOnce({
      response: {
        status: 422,
        headers: {},
        data: {
          type: "https://jtoye.uk/errors/fulfilment-not-offered",
          status: 422,
          detail: "something else entirely",
          fulfilmentType: "COLLECTION",
          offered: ["DELIVERY"],
        },
      },
    })
    seedCart(2000)
    renderCheckout()

    await screen.findByRole("button", { name: /^collection$/i })
    fireEvent.click(screen.getByRole("button", { name: /^collection$/i }))
    fillContact()
    acknowledgeAndPlace()

    expect(
      await screen.findByText("This shop doesn't offer collection. Choose delivery to continue.")
    ).toBeInTheDocument()
    expect(await screen.findByText("Delivery only. This shop doesn't offer collection.")).toBeInTheDocument()
  })
})

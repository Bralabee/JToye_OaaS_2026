/**
 * UXT-047 (#816), UI-SPEC § B7 — VAT is shown only for a seller who declared a VAT registration.
 *
 * Until 37-14 the checkout printed "VAT (incl. 20%)" and a VAT figure for EVERY vendor, a sole
 * trader under the registration threshold included. A seller with no VAT number charges no VAT, so
 * that line was a false statement about the price the customer was paying (T-37-38).
 *
 * The rule: a VAT row renders only when `shop.seller.vatNumber` is present (31.1 V71, null = no
 * registration declared; the public seller object omits the field then). Otherwise the row is
 * omitted and ONE 14px slate-600 line under Total reads the B7 copy. It applies to checkout step 1,
 * the card payment step and the cash confirmation. The order page has never printed VAT and must
 * not start to: the tracking response carries vatAmountPennies, and none of it may surface.
 *
 * A shop that failed to load is not "the seller has not declared": no VAT row (nothing proves the
 * registration) and no note (nothing proves its absence) — the SellerBlock rule (31.1-24).
 *
 * The ledger is not touched (CONTEXT Q2, pending a legal ruling); this file asserts display only.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import OrderTrackingPage from "@/app/shop/[slug]/orders/[orderNumber]/page"
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
  Elements: ({ children }: { children: React.ReactNode }) => <div data-testid="stripe-elements">{children}</div>,
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

const mockedGet = publicApiClient.get as jest.Mock
const mockedPost = publicApiClient.post as jest.Mock

/** The UI-SPEC § Copywriting B7 line, verbatim. */
const NO_VAT_NOTE = "This seller hasn't declared a VAT registration, so no VAT is shown."
/** Any VAT row label: "VAT (incl. 20%)", "VAT (zero-rated)", "VAT (exempt)", "VAT (incl. 5%)". */
const VAT_ROW = /VAT \(/

const SLUG = "mama-ade"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

const SOLE_TRADER = {
  legalName: "Ade Johnson",
  entityType: "SOLE_TRADER",
  addressLines: ["12 Market Street", "Birmingham", "B1 1AA"],
  email: "orders@mama-ade.example.com",
}
const VAT_REGISTERED = {
  legalName: "Mama Ade Foods Ltd",
  entityType: "COMPANY",
  companyNumber: "01234567",
  vatNumber: "GB123456789",
  addressLines: ["12 Market Street", "Birmingham", "B1 1AA"],
  email: "orders@mama-ade.example.com",
}

const SHOP_BASE = {
  slug: SLUG,
  name: "Mama Ade's Kitchen",
  description: null,
  address: null,
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: "orders@mama-ade.example.com",
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

// 1200p STANDARD: the preview VAT is 1200 * 20 / 120 = 200p, i.e. "£2.00" — a figure that appears
// nowhere else on the page, so its absence is evidence that no VAT figure was printed.
const CATALOGUE = { Mains: [{ id: "p0", allergenMask: 0 }] }

const CONFIRMATION = {
  orderNumber: "ORD-UXT-047",
  status: "PENDING",
  subtotalPennies: 1200,
  deliveryFeePennies: 0,
  vatRate: "STANDARD",
  vatAmountPennies: 200,
  totalAmountPennies: 1200,
  shopName: "Mama Ade's Kitchen",
  itemCount: 1,
  clientSecret: "",
  allergenWarnings: [],
}

function mockEndpoints(shop: Record<string, unknown> | "fail") {
  mockedGet.mockImplementation((url: string) => {
    if (typeof url === "string" && url.endsWith("/products")) return Promise.resolve({ data: CATALOGUE })
    if (shop === "fail") return Promise.reject(new Error("network"))
    return Promise.resolve({ data: shop })
  })
}

function seedCart() {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        { productId: "p0", title: "Jollof Rice", pricePennies: 1200, quantity: 1, imageUrl: null, category: "Mains", vatRate: "STANDARD" },
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

const ackCheckbox = () => screen.getByRole("checkbox", { name: /I have read the allergen information/i })

async function placeCollectionOrder() {
  await screen.findByRole("button", { name: /place order/i })
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900000" } })
  fireEvent.click(ackCheckbox())
  await waitFor(() => expect(ackCheckbox()).toBeChecked())
  fireEvent.click(screen.getByRole("button", { name: /place order/i }))
}

/** The shop has loaded once its name is in the seller block or the header. */
async function shopLoaded() {
  await waitFor(() => expect(mockedGet).toHaveBeenCalled())
  await screen.findByRole("button", { name: /place order/i })
  // The shop fetch resolves in a microtask after the first render.
  await waitFor(() => expect(screen.getAllByText(/Total/).length).toBeGreaterThan(0))
}

/** The note must sit UNDER Total: after the Total row in document order. */
function expectNoteUnderTotal() {
  const note = screen.getByText(NO_VAT_NOTE)
  expect(note).toHaveClass("text-sm")
  expect(note).toHaveClass("text-slate-600")
  const totals = screen.getAllByText("Total")
  const lastTotal = totals[totals.length - 1]
  expect(lastTotal.compareDocumentPosition(note) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
}

const ORIGINAL_KEY = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedPost.mockResolvedValue({ data: CONFIRMATION })
  seedCart()
})

afterEach(() => {
  localStorage.clear()
  jest.clearAllMocks()
  if (ORIGINAL_KEY === undefined) delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  else process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ORIGINAL_KEY
})

describe("UXT-047 checkout step 1: VAT only for a VAT-registered seller (UI-SPEC § B7)", () => {
  it("a seller with no VAT number: no VAT row, no VAT figure, and the B7 note under Total", async () => {
    mockEndpoints({ ...SHOP_BASE, seller: SOLE_TRADER })
    renderCheckout()
    await shopLoaded()
    await screen.findByText(NO_VAT_NOTE)

    expect(document.body.textContent).not.toMatch(VAT_ROW)
    expect(screen.queryByText("VAT (incl. 20%)")).not.toBeInTheDocument()
    expect(screen.queryByText("£2.00")).not.toBeInTheDocument()
    expectNoteUnderTotal()
  })

  it("a shop whose seller has given no details at all is treated the same: no VAT row, the note", async () => {
    mockEndpoints({ ...SHOP_BASE })
    renderCheckout()
    await shopLoaded()
    await screen.findByText(NO_VAT_NOTE)

    expect(document.body.textContent).not.toMatch(VAT_ROW)
    expect(screen.queryByText("£2.00")).not.toBeInTheDocument()
  })

  it("a VAT-registered seller keeps the VAT row with vatRateLabel and the figure, and no note", async () => {
    mockEndpoints({ ...SHOP_BASE, seller: VAT_REGISTERED })
    renderCheckout()

    const label = await screen.findByText("VAT (incl. 20%)")
    expect(label.parentElement).toHaveTextContent("£2.00")
    expect(screen.queryByText(NO_VAT_NOTE)).not.toBeInTheDocument()
  })

  it("a shop that could not be loaded shows neither a VAT row nor the note — a failed fetch proves nothing", async () => {
    mockEndpoints("fail")
    renderCheckout()
    await screen.findByRole("button", { name: /place order/i })
    await waitFor(() => expect(mockedGet).toHaveBeenCalled())

    expect(document.body.textContent).not.toMatch(VAT_ROW)
    expect(screen.queryByText(NO_VAT_NOTE)).not.toBeInTheDocument()
  })
})

describe("UXT-047 after the order: the cash confirmation and the card payment step", () => {
  it("the cash confirmation for a seller with no VAT number prints no VAT row though the server sent a VAT amount", async () => {
    mockEndpoints({ ...SHOP_BASE, seller: SOLE_TRADER })
    renderCheckout()
    await placeCollectionOrder()
    await screen.findByText(/Order confirmed!/i)

    expect(document.body.textContent).not.toMatch(VAT_ROW)
    expect(screen.queryByText("£2.00")).not.toBeInTheDocument()
    expectNoteUnderTotal()
  })

  it("the cash confirmation for a VAT-registered seller keeps 'VAT (incl. 20%)' with the server's figure", async () => {
    mockEndpoints({ ...SHOP_BASE, seller: VAT_REGISTERED })
    renderCheckout()
    await placeCollectionOrder()
    await screen.findByText(/Order confirmed!/i)

    const label = screen.getByText("VAT (incl. 20%)")
    expect(label.parentElement).toHaveTextContent("£2.00")
    expect(screen.queryByText(NO_VAT_NOTE)).not.toBeInTheDocument()
  })

  it("the card payment step for a seller with no VAT number prints no VAT row, and the note under Total", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = "pk_test_uxt047"
    mockEndpoints({ ...SHOP_BASE, acceptsCardPayments: true, seller: SOLE_TRADER })
    mockedPost.mockResolvedValue({ data: { ...CONFIRMATION, clientSecret: "pi_047_secret_x" } })
    renderCheckout()
    await placeCollectionOrder()
    await screen.findByTestId("stripe-elements")

    expect(document.body.textContent).not.toMatch(VAT_ROW)
    expect(screen.queryByText("£2.00")).not.toBeInTheDocument()
    expectNoteUnderTotal()
  })

  it("the card payment step for a VAT-registered seller keeps the VAT row", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = "pk_test_uxt047"
    mockEndpoints({ ...SHOP_BASE, acceptsCardPayments: true, seller: VAT_REGISTERED })
    mockedPost.mockResolvedValue({ data: { ...CONFIRMATION, clientSecret: "pi_047_secret_x" } })
    renderCheckout()
    await placeCollectionOrder()
    await screen.findByTestId("stripe-elements")

    expect(screen.getByText("VAT (incl. 20%)").parentElement).toHaveTextContent("£2.00")
    expect(screen.queryByText(NO_VAT_NOTE)).not.toBeInTheDocument()
  })
})

describe("UXT-047 on the order page: no VAT statement is made", () => {
  it("prints no VAT row or figure even though the tracking response carries a VAT amount", async () => {
    const ORDER_NUMBER = "ORD-UXT-047-TRACK"
    localStorage.setItem(`jtoye-checkout-email-${SLUG}`, "ade@example.com")
    mockedGet.mockResolvedValue({
      data: {
        orderNumber: ORDER_NUMBER,
        status: "PREPARING",
        shopName: "Mama Ade's Kitchen",
        subtotalPennies: 1200,
        vatRate: "STANDARD",
        vatAmountPennies: 200,
        totalAmountPennies: 1200,
        itemCount: 1,
        unitCount: 1,
        createdAt: "2026-10-09T10:00:00Z",
        updatedAt: "2026-10-09T10:05:00Z",
      },
    })
    render(
      <Suspense fallback={<div>loading</div>}>
        <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
      </Suspense>
    )
    await screen.findByText("Order in Progress")

    expect(document.body.textContent).not.toMatch(/VAT/)
    expect(screen.queryByText("£2.00")).not.toBeInTheDocument()
  })
})

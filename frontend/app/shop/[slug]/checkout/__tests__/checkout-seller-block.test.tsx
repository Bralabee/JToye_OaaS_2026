/**
 * #789 at the checkout (31.1-24, D-13): the customer sees who they are buying from, that J'Toye is
 * the platform and not the seller, and that freshly prepared food carries no 14-day right to cancel
 * — BEFORE they commit (CCR 2013 Sch 2(o) is pre-contract information), and again on what they are
 * left with afterwards: the cash confirmation and the card payment step.
 *
 * What must NOT change, asserted here too:
 *   - the allergen panel stays the LAST thing read before Place order (31-14 D-02): the seller block
 *     sits above the panel, the panel above the button;
 *   - the submit body is unchanged — the block is display only, it sends nothing.
 *
 * Harness as allergen-attribution.test.tsx: the cart is seeded before render.
 */

import { Suspense } from "react"
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react"
import CheckoutPage from "@/app/shop/[slug]/checkout/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import {
  CANCELLATION_STATEMENT_COPY,
  PLATFORM_NOT_SELLER_COPY,
  SELLER_DETAILS_MISSING_COPY,
} from "@/components/storefront/seller-block"

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

const SLUG = "mama-ade"
const STORAGE_KEY = `jtoye-cart-${SLUG}`
const REGION = { name: "Who you are buying from" }

const SELLER = {
  legalName: "Mama Ade Foods Ltd",
  entityType: "COMPANY",
  companyNumber: "01234567",
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

const PRODUCTS = {
  Mains: [
    {
      id: "p-jollof",
      title: "Jollof Rice",
      description: null,
      imageUrl: null,
      imageUrls: [],
      ingredientsText: "",
      allergenMask: 1 << 6,
      pricePennies: 500,
      category: "Mains",
      dietaryTags: null,
      preparationTimeMinutes: null,
      featured: false,
      inStock: true,
    },
  ],
}

const CONFIRMATION = {
  orderNumber: "ORD-789",
  status: "PENDING",
  subtotalPennies: 500,
  deliveryFeePennies: 0,
  vatRate: "STANDARD",
  vatAmountPennies: 83,
  totalAmountPennies: 500,
  shopName: "Mama Ade's Kitchen",
  itemCount: 1,
  clientSecret: "",
  allergenWarnings: [],
}

function mockEndpoints(shop: Record<string, unknown> | "fail") {
  mockedGet.mockImplementation((url: string) => {
    if (typeof url === "string" && url.endsWith("/products")) return Promise.resolve({ data: PRODUCTS })
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
        { productId: "p-jollof", title: "Jollof Rice", pricePennies: 500, quantity: 1, imageUrl: null, category: "Mains" },
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

const submitButton = () => screen.getByRole("button", { name: /place order/i })
const ackCheckbox = () => screen.getByRole("checkbox", { name: /I have read the allergen information/i })

async function placeCollectionOrder() {
  await screen.findByRole("button", { name: /place order/i })
  fireEvent.click(screen.getByRole("button", { name: /collection/i }))
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: "Ade Johnson" } })
  fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ade@example.com" } })
  fireEvent.change(screen.getByLabelText(/phone number/i), { target: { value: "07700900000" } })
  await waitFor(() => expect(screen.getAllByTestId("allergen-chip").length).toBeGreaterThan(0))
  fireEvent.click(ackCheckbox())
  await waitFor(() => expect(ackCheckbox()).toBeChecked())
  fireEvent.click(submitButton())
}

const ORIGINAL_KEY = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockEndpoints({ ...SHOP_BASE, seller: SELLER })
  mockedPost.mockResolvedValue({ data: CONFIRMATION })
  seedCart()
})

afterEach(() => {
  localStorage.clear()
  jest.clearAllMocks()
  if (ORIGINAL_KEY === undefined) delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  else process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ORIGINAL_KEY
})

describe("#789: the seller block is in front of the customer BEFORE they place the order", () => {
  it("names the seller with the platform and cancellation statements, inside the checkout form", async () => {
    renderCheckout()
    const region = await screen.findByRole("region", REGION)
    expect(within(region).getByText("Mama Ade Foods Ltd")).toBeInTheDocument()
    expect(within(region).getByText("01234567")).toBeInTheDocument()
    expect(within(region).getByRole("link", { name: "orders@mama-ade.example.com" })).toHaveAttribute(
      "href",
      "mailto:orders@mama-ade.example.com"
    )
    expect(within(region).getByText(PLATFORM_NOT_SELLER_COPY)).toBeInTheDocument()
    expect(within(region).getByText(CANCELLATION_STATEMENT_COPY)).toBeInTheDocument()
    // inside the form's reading order, not a sidebar after it
    expect(submitButton().closest("form")).toContainElement(region)
  })

  it("precedes the Place order button, and the allergen panel stays the last thing read before it", async () => {
    renderCheckout()
    const region = await screen.findByRole("region", REGION)
    const panel = screen.getByTestId("order-allergen-panel")
    expect(region.compareDocumentPosition(submitButton()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(region.compareDocumentPosition(panel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(panel.compareDocumentPosition(submitButton()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("a shop whose trader has given no details says so, before purchase", async () => {
    mockEndpoints({ ...SHOP_BASE })
    renderCheckout()
    const region = await screen.findByRole("region", REGION)
    expect(within(region).getByText(SELLER_DETAILS_MISSING_COPY)).toBeInTheDocument()
    expect(within(region).getByText(CANCELLATION_STATEMENT_COPY)).toBeInTheDocument()
  })

  it("a shop that could not be loaded shows no seller block — a failed fetch is not 'not provided'", async () => {
    mockEndpoints("fail")
    renderCheckout()
    await screen.findByRole("button", { name: /place order/i })
    await waitFor(() => expect(mockedGet).toHaveBeenCalled())
    expect(screen.queryByRole("region", REGION)).not.toBeInTheDocument()
    expect(screen.queryByText(SELLER_DETAILS_MISSING_COPY)).not.toBeInTheDocument()
  })

  it("sends nothing: the submit body carries no seller field and none of the seller's details", async () => {
    renderCheckout()
    await screen.findByRole("region", REGION)
    await placeCollectionOrder()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    const body = JSON.stringify(mockedPost.mock.calls[0][1])
    expect(body).not.toMatch(/seller|legalName|Mama Ade Foods Ltd|01234567/)
    // the keys that reach the wire (JSON drops the undefined allergyNote/notes of an empty form)
    expect(Object.keys(JSON.parse(body)).sort()).toEqual(
      ["acknowledgedAllergenMask", "customerEmail", "customerName", "customerPhone", "fulfilmentType", "idempotencyKey", "items"].sort()
    )
  })
})

describe("#789: the seller block is on what the customer is left with afterwards", () => {
  it("the cash confirmation names the seller and carries the cancellation statement", async () => {
    renderCheckout()
    await placeCollectionOrder()
    await screen.findByText(/Order confirmed!/i)
    const region = screen.getByRole("region", REGION)
    expect(within(region).getByText("Mama Ade Foods Ltd")).toBeInTheDocument()
    expect(within(region).getByText(PLATFORM_NOT_SELLER_COPY)).toBeInTheDocument()
    expect(within(region).getByText(CANCELLATION_STATEMENT_COPY)).toBeInTheDocument()
    // before the customer navigates away from it
    const track = screen.getByRole("link", { name: /track your order/i })
    expect(region.compareDocumentPosition(track) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("the card payment step names the seller before the card form", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = "pk_test_789"
    mockEndpoints({ ...SHOP_BASE, acceptsCardPayments: true, seller: SELLER })
    mockedPost.mockResolvedValue({ data: { ...CONFIRMATION, clientSecret: "pi_789_secret_x" } })
    renderCheckout()
    await placeCollectionOrder()
    const elements = await screen.findByTestId("stripe-elements")
    const region = screen.getByRole("region", REGION)
    expect(within(region).getByText("Mama Ade Foods Ltd")).toBeInTheDocument()
    expect(within(region).getByText(CANCELLATION_STATEMENT_COPY)).toBeInTheDocument()
    expect(region.compareDocumentPosition(elements) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

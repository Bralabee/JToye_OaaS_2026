/**
 * The cash-order confirmation on its own route (37-37, UI-SPEC § C3, RWO-029 / UXT-029 #802).
 *
 * Before 37-37 a cash order was confirmed IN PLACE at /shop/{slug}/checkout: nothing announced it,
 * focus stayed wherever the Place order button had been, and a refresh lost the confirmation (the
 * checkout re-rendered as "Nothing to checkout"). The cash path now routes here, like the card path
 * always did, and leaves a one-shot marker `jtoye-just-placed:{orderNumber}` in sessionStorage so this
 * page can tell the FIRST view of a just-placed order from every later one.
 *
 * What this suite holds the page to:
 *   - first view (marker present): h1 "Order confirmed" takes focus, ONE role=status region says
 *     "Order confirmed, {number}", the title is "Order {number} confirmed · {Shop} — J'Toye", and the
 *     marker is gone from sessionStorage afterwards;
 *   - any later view (refresh, a second visit): h1 "Order {number}", title "Order {number} · {Shop} — J'Toye";
 *   - F16 / T-37-88: NO POST on load or refresh, in either view — the page only reads tracking data,
 *     bound to order number + email (goods F3, P2-KYL-P5);
 *   - everything the in-place confirmation showed is carried over (goods P2-REG-17, P2-GRA-21): the
 *     payment line, the full breakdown with the 37-14 VAT rule, the recorded allergen set, the seller
 *     block and "Back to shop";
 *   - C3 error (planner assumption): a failed tracking read on the FIRST view still says the order was
 *     placed, announced and focused, with a Retry; without the marker the existing error branch stays.
 */
import { Suspense } from "react"
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import OrderTrackingPage from "@/app/shop/[slug]/orders/[orderNumber]/page"
import {
  CANCELLATION_STATEMENT_COPY,
  PLATFORM_NOT_SELLER_COPY,
} from "@/components/storefront/seller-block"

const mockGet = jest.fn()
const mockPost = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
  },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve(null),
}))

const SLUG = "mama-ade"
const ORDER_NUMBER = "ORD-37C3-20261012-CASH0001"
const EMAIL = "ade@example.com"
const MARKER = `jtoye-just-placed:${ORDER_NUMBER}`
const NO_VAT_NOTE = "This seller hasn't declared a VAT registration, so no VAT is shown."

const VAT_REGISTERED = {
  legalName: "Mama Ade Foods Ltd",
  entityType: "COMPANY",
  companyNumber: "01234567",
  vatNumber: "GB123456789",
  addressLines: ["12 Market Street", "Birmingham", "B1 1AA"],
  email: "orders@mama-ade.example.com",
}
const SOLE_TRADER = {
  legalName: "Ade Johnson",
  entityType: "SOLE_TRADER",
  addressLines: ["12 Market Street", "Birmingham", "B1 1AA"],
  email: "orders@mama-ade.example.com",
}
const SHOP = {
  slug: SLUG,
  name: "Mama Ade's Kitchen",
  acceptsCardPayments: false,
  seller: VAT_REGISTERED,
}

/** A cash DELIVERY order as the tracking endpoint returns it. 2000 + 300 = 2300; VAT inside the 2000. */
const ORDER = {
  orderNumber: ORDER_NUMBER,
  status: "PENDING",
  paymentStatus: "NONE",
  shopName: "Mama Ade's Kitchen",
  fulfilmentType: "DELIVERY",
  subtotalPennies: 2000,
  deliveryFeePennies: 300,
  vatRate: "STANDARD",
  vatAmountPennies: 333,
  totalAmountPennies: 2300,
  itemCount: 1,
  unitCount: 2,
  createdAt: "2026-10-12T10:00:00Z",
  updatedAt: "2026-10-12T10:00:05Z",
  placedVia: "STOREFRONT",
  acknowledgedAllergenMask: 64,
  acknowledgedAllergens: ["Milk"],
  recordedAllergens: ["Milk"],
  recordedAllergenFlags: [],
  allergyNoteProvided: false,
  allergyNoteAcknowledgedAt: null,
}

type Endpoint = Record<string, unknown> | "fail"

function mockEndpoints({ order = ORDER, shop = SHOP }: { order?: Endpoint; shop?: Endpoint } = {}) {
  mockGet.mockImplementation((url: string) => {
    if (url === `/public/orders/${ORDER_NUMBER}`) {
      return order === "fail" ? Promise.reject(new Error("network")) : Promise.resolve({ data: order })
    }
    if (url === `/public/shops/${SLUG}`) {
      return shop === "fail" ? Promise.reject(new Error("network")) : Promise.resolve({ data: shop })
    }
    return Promise.reject(new Error(`unexpected GET ${url}`))
  })
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function renderOrderPage() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
    </Suspense>
  )
}

/** The page's one role=status region (C3: one region, announced once). */
function statusRegion() {
  const regions = screen.getAllByRole("status")
  expect(regions).toHaveLength(1)
  return regions[0]
}

const fetchSpy = jest.fn<Promise<never>, [RequestInfo | URL, RequestInit?]>(() =>
  Promise.reject(new Error("no network in tests"))
)
const ORIGINAL_FETCH = globalThis.fetch

/** F16: no request with method POST reached the network, through the API client or bare fetch. */
function expectNoPost() {
  expect(mockPost).not.toHaveBeenCalled()
  const posts = fetchSpy.mock.calls.filter(([, init]) => {
    const method = (init as RequestInit | undefined)?.method
    return typeof method === "string" && method.toUpperCase() === "POST"
  })
  expect(posts).toHaveLength(0)
}

beforeEach(() => {
  mockGet.mockReset()
  mockPost.mockReset()
  fetchSpy.mockClear()
  globalThis.fetch = fetchSpy as unknown as typeof fetch
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem(`jtoye-checkout-email-${SLUG}`, EMAIL)
  document.title = "Checkout · Mama Ade's Kitchen — J'Toye"
})

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH
})

describe("C3 first view: the just-placed marker is consumed, announced and focused", () => {
  it("h1 'Order confirmed' takes focus, the status region says 'Order confirmed, {number}', the title is the confirmed variant", async () => {
    sessionStorage.setItem(MARKER, ORDER_NUMBER)
    mockEndpoints()
    renderOrderPage()

    const heading = await screen.findByRole("heading", { level: 1, name: "Order confirmed" })
    expect(heading).toHaveAttribute("tabindex", "-1")
    await waitFor(() => expect(document.activeElement).toBe(heading))
    await waitFor(() => expect(statusRegion()).toHaveTextContent(`Order confirmed, ${ORDER_NUMBER}`))
    await waitFor(() =>
      expect(document.title).toBe(`Order ${ORDER_NUMBER} confirmed · Mama Ade's Kitchen — J'Toye`)
    )
  })

  it("the marker is removed on the first view, so it cannot announce twice", async () => {
    sessionStorage.setItem(MARKER, ORDER_NUMBER)
    mockEndpoints()
    renderOrderPage()

    await screen.findByRole("heading", { level: 1, name: "Order confirmed" })
    expect(sessionStorage.getItem(MARKER)).toBeNull()
  })

  it("a marker for a DIFFERENT order is not consumed and does not confirm this one", async () => {
    const other = "jtoye-just-placed:ORD-SOMEONE-ELSE"
    sessionStorage.setItem(other, "ORD-SOMEONE-ELSE")
    mockEndpoints()
    renderOrderPage()

    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    expect(screen.queryByRole("heading", { name: "Order confirmed" })).not.toBeInTheDocument()
    expect(sessionStorage.getItem(other)).toBe("ORD-SOMEONE-ELSE")
  })
})

describe("C3 later views: a refresh shows the plain heading and title, and nothing re-posts", () => {
  it("the second render (marker consumed by the first) reads 'Order {number}' with the plain title", async () => {
    sessionStorage.setItem(MARKER, ORDER_NUMBER)
    mockEndpoints()
    const first = renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: "Order confirmed" })
    first.unmount()

    // The refresh: a fresh mount of the same route.
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    expect(screen.queryByRole("heading", { name: "Order confirmed" })).not.toBeInTheDocument()
    await waitFor(() => expect(document.title).toBe(`Order ${ORDER_NUMBER} · Mama Ade's Kitchen — J'Toye`))
    expect(statusRegion()).not.toHaveTextContent("Order confirmed")
  })

  it("F16: no POST on the first view or on the refresh — the page only reads tracking data bound to number + email", async () => {
    sessionStorage.setItem(MARKER, ORDER_NUMBER)
    mockEndpoints()
    const first = renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: "Order confirmed" })
    first.unmount()
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })

    // Non-vacuity: the tracking read DID happen in both views, bound to the email (F3).
    const trackingReads = mockGet.mock.calls.filter(([url]) => url === `/public/orders/${ORDER_NUMBER}`)
    expect(trackingReads.length).toBeGreaterThanOrEqual(2)
    for (const [, config] of trackingReads) expect(config).toEqual({ params: { email: EMAIL } })
    expectNoPost()
  })

  it("the status line still says where the order is (the old heading text is not dropped)", async () => {
    mockEndpoints({ order: { ...ORDER, status: "PREPARING" } })
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    expect(screen.getByText("Order in Progress")).toBeInTheDocument()
  })
})

describe("C3 content carried over from the in-place confirmation (P2-REG-17, P2-GRA-21)", () => {
  it("a cash DELIVERY order: 'Pay on delivery', subtotal, delivery fee, VAT (incl. 20%), total", async () => {
    mockEndpoints()
    renderOrderPage()
    const breakdown = await screen.findByTestId("order-breakdown")

    expect(screen.getByText("Pay on delivery")).toBeInTheDocument()
    expect(screen.queryByText(/Pay on collection/)).not.toBeInTheDocument()
    expect(within(breakdown).getByText("Subtotal").parentElement).toHaveTextContent("£20.00")
    expect(within(breakdown).getByText("Delivery").parentElement).toHaveTextContent("£3.00")
    const vat = await within(breakdown).findByText("VAT (incl. 20%)")
    expect(vat.parentElement).toHaveTextContent("£3.33")
    expect(within(breakdown).getByText("Total").parentElement).toHaveTextContent("£23.00")
    expect(within(breakdown).queryByText(NO_VAT_NOTE)).not.toBeInTheDocument()
  })

  it("a cash COLLECTION order with no fee: 'Pay on collection' and Delivery 'Free'", async () => {
    mockEndpoints({
      order: { ...ORDER, fulfilmentType: "COLLECTION", deliveryFeePennies: 0, totalAmountPennies: 2000 },
    })
    renderOrderPage()
    const breakdown = await screen.findByTestId("order-breakdown")

    expect(screen.getByText("Pay on collection")).toBeInTheDocument()
    expect(screen.queryByText(/Pay on delivery/)).not.toBeInTheDocument()
    expect(within(breakdown).getByText("Delivery").parentElement).toHaveTextContent("Free")
    expect(within(breakdown).getByText("Total").parentElement).toHaveTextContent("£20.00")
  })

  it("a card order carries no 'Pay on …' instruction (it is not a cash order)", async () => {
    mockEndpoints({ order: { ...ORDER, paymentStatus: "PAID" } })
    renderOrderPage()
    await screen.findByTestId("order-breakdown")
    expect(screen.queryByText(/Pay on (delivery|collection)/)).not.toBeInTheDocument()
  })

  it("UXT-047: a seller with no VAT number gets no VAT row or figure, and the B7 note under Total", async () => {
    mockEndpoints({ shop: { ...SHOP, seller: SOLE_TRADER } })
    renderOrderPage()
    const breakdown = await screen.findByTestId("order-breakdown")
    const note = await within(breakdown).findByText(NO_VAT_NOTE)

    expect(breakdown.textContent).not.toMatch(/VAT \(/)
    expect(screen.queryByText("£3.33")).not.toBeInTheDocument()
    const total = within(breakdown).getByText("Total")
    expect(total.compareDocumentPosition(note) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("an EXEMPT order reads 'VAT (exempt)', never 'VAT (0%)' (vatRateLabel, PR #726 low c)", async () => {
    mockEndpoints({ order: { ...ORDER, vatRate: "EXEMPT", vatAmountPennies: 1 } })
    renderOrderPage()
    const breakdown = await screen.findByTestId("order-breakdown")
    expect(await within(breakdown).findByText("VAT (exempt)")).toBeInTheDocument()
    expect(screen.queryByText(/VAT \(0%\)/)).not.toBeInTheDocument()
  })

  it("a shop that could not be loaded proves nothing: no VAT row, no note, no seller block", async () => {
    mockEndpoints({ shop: "fail" })
    renderOrderPage()
    const breakdown = await screen.findByTestId("order-breakdown")
    await waitFor(() => expect(mockGet).toHaveBeenCalledWith(`/public/shops/${SLUG}`))

    expect(breakdown.textContent).not.toMatch(/VAT/)
    expect(screen.queryByRole("region", { name: /who you are buying from/i })).not.toBeInTheDocument()
  })

  it("the seller block names the seller and carries the cancellation statement (#789)", async () => {
    mockEndpoints()
    renderOrderPage()
    const region = await screen.findByRole("region", { name: /who you are buying from/i })
    expect(within(region).getByText("Mama Ade Foods Ltd")).toBeInTheDocument()
    expect(within(region).getByText(PLATFORM_NOT_SELLER_COPY)).toBeInTheDocument()
    expect(within(region).getByText(CANCELLATION_STATEMENT_COPY)).toBeInTheDocument()
  })

  it("the recorded allergen set and 'Back to shop' are on the page", async () => {
    mockEndpoints()
    renderOrderPage()
    const block = await screen.findByTestId("recorded-allergen-set")
    expect(within(block).getByTestId("recorded-set")).toHaveTextContent("Recorded on your order: Milk")
    expect(screen.getByRole("link", { name: /back to shop/i })).toHaveAttribute("href", `/shop/${SLUG}`)
  })
})

describe("C3 loading and error states", () => {
  it("loading: the existing spinner shows while the tracking data loads", async () => {
    sessionStorage.setItem(MARKER, ORDER_NUMBER)
    let release: (v: unknown) => void = () => {}
    mockGet.mockImplementation((url: string) =>
      url === `/public/orders/${ORDER_NUMBER}`
        ? new Promise((resolve) => { release = resolve })
        : Promise.resolve({ data: SHOP })
    )
    renderOrderPage()
    expect(await screen.findByText("Loading order status...")).toBeInTheDocument()

    await act(async () => release({ data: ORDER }))
    await screen.findByRole("heading", { level: 1, name: "Order confirmed" })
  })

  it("first view, tracking read fails: 'Order {number} placed' is focused and announced, with the retry copy", async () => {
    sessionStorage.setItem(MARKER, ORDER_NUMBER)
    mockEndpoints({ order: "fail" })
    renderOrderPage()

    const heading = await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER} placed` })
    await waitFor(() => expect(document.activeElement).toBe(heading))
    await waitFor(() => expect(statusRegion()).toHaveTextContent(`Order placed, ${ORDER_NUMBER}`))
    expect(screen.getByText("We couldn't load the latest details. Your order was placed.")).toBeInTheDocument()
    expect(screen.queryByText("Could not load order status.")).not.toBeInTheDocument()

    // Retry reads the tracking data again (a GET, never a POST) and lands on the confirmed view.
    mockEndpoints()
    fireEvent.click(screen.getByRole("button", { name: "Retry" }))
    await screen.findByRole("heading", { level: 1, name: "Order confirmed" })
    expectNoPost()
  })

  it("a later view whose tracking read fails keeps the existing error branch", async () => {
    mockEndpoints({ order: "fail" })
    renderOrderPage()

    expect(await screen.findByText("Could not load order status.")).toBeInTheDocument()
    expect(screen.queryByText(/Your order was placed/)).not.toBeInTheDocument()
    expect(screen.queryByRole("heading", { name: /placed$/ })).not.toBeInTheDocument()
  })
})

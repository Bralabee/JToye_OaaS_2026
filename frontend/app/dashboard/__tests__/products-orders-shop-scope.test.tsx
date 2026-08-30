/**
 * VSA-03 (23-07) — Products & Orders operate on the selected shop context.
 *
 * NOTE ON WHAT THIS PROVES: the client-side narrow is a UX layer, NOT the
 * security boundary. Reads are already grant-scoped server-side by 23-03 and
 * tenant-scoped by RLS; these cases prove the switcher selection is *visible*
 * on the consuming screens and that the All-shops context is a zero-regression
 * fall-through to today's behaviour.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import ProductsPage from "../products/page"
import OrdersPage from "../orders/page"
import apiClient from "@/lib/api-client"
import { getShopContext } from "@/lib/shop-context"
import {
  manyProducts,
  manyShops,
  pagedResponse,
  param,
} from "@/test-utils/spring-page"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))
const mockedGetShopContext = getShopContext as jest.MockedFunction<typeof getShopContext>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// The orders page's SSE stream is irrelevant to shop scoping and would open a
// real fetch loop in jsdom.
jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-2222-2222-2222-222222222222"

const shops = [
  { id: SHOP_A, name: "Peckham Kitchen", published: true },
  { id: SHOP_B, name: "Brixton Bakery", published: true },
]

const product = (id: string, sku: string, title: string, shopId: string) => ({
  id,
  tenantId: "t-1",
  sku,
  title,
  ingredientsText: "flour, water",
  allergenMask: 0,
  pricePennies: 500,
  description: null,
  imageUrl: null,
  additionalImageUrls: [],
  category: "Mains",
  displayOrder: 0,
  available: true,
  featured: false,
  preparationTimeMinutes: null,
  dietaryTags: null,
  shopId,
  quantityInStock: null,
  createdAt: "2026-07-01T10:00:00Z",
  updatedAt: "2026-07-01T10:00:00Z",
})

const PRODUCTS = [
  product("p-a", "SKU-A", "Jollof Rice", SHOP_A),
  product("p-b", "SKU-B", "Sourdough Loaf", SHOP_B),
]

const defaultMock = (url: string) => {
  if (url.startsWith("/api/v1/products")) {
    // WR-04 (#280): the narrow is SERVER-side now, so the fake server has to behave
    // like one — it honours ?shopId= and returns a total describing that shop. A mock
    // that ignored the param would return every shop's rows and the screen would look
    // broken even though the code is right, which is what made the old client-side
    // assertion here misleading in the first place.
    const shopId = new URLSearchParams(url.split("?")[1] ?? "").get("shopId")
    const rows = shopId ? PRODUCTS.filter((p) => p.shopId === shopId) : PRODUCTS
    return Promise.resolve({
      data: { content: rows, totalPages: 1, totalElements: rows.length },
    })
  }
  if (url.startsWith("/api/v1/shops")) {
    return Promise.resolve({ data: { content: shops, totalPages: 1, totalElements: shops.length } })
  }
  if (url.startsWith("/api/v1/orders")) {
    return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
  }
  return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
}

/** All URLs the page requested against the orders collection endpoint. */
const ordersCalls = () =>
  mockedApiClient.get.mock.calls
    .map(([url]) => url as string)
    .filter((url) => url.startsWith("/api/v1/orders?"))

/** All URLs the page requested against the products collection endpoint. */
const productsCalls = () =>
  mockedApiClient.get.mock.calls
    .map(([url]) => url as string)
    .filter((url) => url.startsWith("/api/v1/products?"))

describe("VSA-03 — shop-context scoping on Products & Orders", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(defaultMock as jest.Mock)
    mockedGetShopContext.mockReturnValue("all")
  })

  describe("a specific shop is selected in the switcher", () => {
    beforeEach(() => {
      mockedGetShopContext.mockReturnValue(SHOP_A)
    })

    it("narrows the products list server-side via ?shopId=", async () => {
      // WR-04 (#280). This used to assert only the RENDERED rows, which passed just as
      // happily when the narrow was a client-side .filter() over one already-paginated
      // page — the arrangement that produced wrong counts, a false empty state when a
      // shop's rows began on page 2, and unreachable rows past page 1. Asserting the
      // REQUEST is what distinguishes the two implementations.
      render(<ProductsPage />)

      await waitFor(() => expect(productsCalls().length).toBeGreaterThan(0))
      expect(productsCalls().some((url) => url.includes(`shopId=${SHOP_A}`))).toBe(true)

      await waitFor(() => expect(screen.getByText("Jollof Rice")).toBeInTheDocument())
      expect(screen.queryByText("Sourdough Loaf")).not.toBeInTheDocument()
    })

    it("reports the SHOP's total, not a count of what fitted on this page", async () => {
      // The defect's most visible symptom: the header count was filtered.length, i.e.
      // "matches on this page", so a shop with more rows than one page under-reported.
      mockedApiClient.get.mockImplementation(((url: string) => {
        if (url.startsWith("/api/v1/products")) {
          return Promise.resolve({
            data: {
              content: [PRODUCTS[0]],
              totalPages: 3,
              totalElements: 41,
            },
          })
        }
        return defaultMock(url)
      }) as jest.Mock)

      render(<ProductsPage />)

      await waitFor(() => expect(screen.getByText(/41 products/i)).toBeInTheDocument())
    })

    it("defaults AND constrains the product create-form shop to the selected shop (D-08)", async () => {
      render(<ProductsPage />)
      await waitFor(() => expect(screen.getByText("Jollof Rice")).toBeInTheDocument())

      fireEvent.click(screen.getByRole("button", { name: /add product/i }))

      const shopSelect = await screen.findByLabelText(/shop assignment/i)
      expect(shopSelect).toHaveValue(SHOP_A)
      // Single-shop context ⇒ single-shop writes only: not switchable to
      // another shop or back to "All Shops".
      expect(shopSelect).toBeDisabled()
      expect(screen.queryByRole("option", { name: /all shops/i })).not.toBeInTheDocument()
    })

    it("narrows the orders list server-side via ?shopId=", async () => {
      render(<OrdersPage />)

      await waitFor(() => expect(ordersCalls().length).toBeGreaterThan(0))
      expect(ordersCalls().some((url) => url.includes(`shopId=${SHOP_A}`))).toBe(true)
    })
  })

  describe('the "All shops" context (GROUP_ADMIN) — zero day-one regression', () => {
    beforeEach(() => {
      mockedGetShopContext.mockReturnValue("all")
    })

    it("keeps every fetched product visible across shops", async () => {
      render(<ProductsPage />)

      await waitFor(() => expect(screen.getByText("Jollof Rice")).toBeInTheDocument())
      expect(screen.getByText("Sourdough Loaf")).toBeInTheDocument()
    })

    it("requests products without a shopId param (today's cross-shop behaviour)", async () => {
      // A7: the All-shops default must be untouched by WR-04. Sending shopId= here
      // would silently narrow GROUP_ADMINs to one shop — a capability regression.
      render(<ProductsPage />)

      await waitFor(() => expect(productsCalls().length).toBeGreaterThan(0))
      expect(productsCalls().every((url) => !url.includes("shopId="))).toBe(true)
    })

    it("keeps the full shop dropdown on the product create-form", async () => {
      render(<ProductsPage />)
      await waitFor(() => expect(screen.getByText("Jollof Rice")).toBeInTheDocument())

      fireEvent.click(screen.getByRole("button", { name: /add product/i }))

      const shopSelect = await screen.findByLabelText(/shop assignment/i)
      expect(shopSelect).not.toBeDisabled()
      expect(screen.getByRole("option", { name: /all shops/i })).toBeInTheDocument()
    })

    it("requests orders without a shopId param (today's cross-shop behaviour)", async () => {
      render(<OrdersPage />)

      await waitFor(() => expect(ordersCalls().length).toBeGreaterThan(0))
      expect(ordersCalls().every((url) => !url.includes("shopId="))).toBe(true)
    })
  })
})

/**
 * #485 — the pickers on these two screens are followed to their end.
 *
 * Every fixture here is longer than the server's clamped 100-row page and every
 * assertion is on the LAST entry, because a fixture of three rows cannot tell paged
 * code from unpaged code. The fake endpoints honour `?page=` and `?size=` and apply
 * the clamp; one that ignored them would return everything on page 0 and each case
 * would pass against the bug it exists to catch.
 */
describe("#485 — pickers are followed to the end of the list", () => {
  const SHOPS = manyShops(150)
  const TAIL_SHOP = SHOPS[SHOPS.length - 1]
  const CATALOGUE = manyProducts(150, SHOPS[0].id)
  const TAIL_PRODUCT = CATALOGUE[CATALOGUE.length - 1]

  /** Requests issued against an endpoint, in order, as `?page=` values. */
  const pagesRequested = (prefix: string) =>
    mockedApiClient.get.mock.calls
      .map(([url]) => url as string)
      .filter((url) => url.startsWith(prefix))
      .map((url) => param(url, "page"))

  const pagedMock = (url: string) => {
    if (url.startsWith("/api/v1/shops")) return Promise.resolve(pagedResponse(url, SHOPS))
    if (url.startsWith("/api/v1/products"))
      return Promise.resolve(pagedResponse(url, CATALOGUE))
    // The orders list reports three pages so the pager's Next control is ENABLED —
    // the refetch the hoist case needs to trigger. Its rows are irrelevant here.
    return Promise.resolve({ data: { content: [], totalPages: 3, totalElements: 60 } })
  }

  beforeEach(() => {
    jest.clearAllMocks()
    mockedGetShopContext.mockReturnValue("all")
    mockedApiClient.get.mockImplementation(pagedMock as jest.Mock)
  })

  describe("products page (call site :158) — the create-product shop select", () => {
    it("offers a shop that lives past the first page", async () => {
      render(<ProductsPage />)
      await waitFor(() => expect(screen.getByText("Product 1")).toBeInTheDocument())

      fireEvent.click(screen.getByRole("button", { name: /add product/i }))
      const shopSelect = await screen.findByLabelText(/shop assignment/i)

      // Shop 150 could not be assigned a product at all while the list stopped at 100.
      expect(
        within(shopSelect).getByRole("option", { name: TAIL_SHOP.name })
      ).toBeInTheDocument()
    })

    it("walks page 0 then page 1 rather than issuing one size=100 request", async () => {
      render(<ProductsPage />)

      await waitFor(() => expect(pagesRequested("/api/v1/shops")).toEqual(["0", "1"]))
    })
  })

  describe("orders page (call sites :297 shops, :298 products)", () => {
    it("walks BOTH pickers to the end, not just the first page of each", async () => {
      render(<OrdersPage />)

      await waitFor(() => expect(pagesRequested("/api/v1/shops")).toEqual(["0", "1"]))
      await waitFor(() =>
        expect(pagesRequested("/api/v1/products?")).toEqual(["0", "1"])
      )
    })

    it("holds the tail shop AND the tail product in the create-order dialog", async () => {
      // Radix renders `SelectItem`s only while the listbox is OPEN, so asserting on
      // the open listbox is what makes this a claim about what a vendor can actually
      // pick rather than about component state. It is opened by keyboard: Radix's
      // pointer path needs `setPointerCapture`, which jsdom does not implement well
      // enough to drive the open (the stubs in jest.setup.js keep it from throwing,
      // but ArrowDown is the path that actually works here).
      render(<OrdersPage />)
      await waitFor(() => expect(pagesRequested("/api/v1/shops")).toEqual(["0", "1"]))

      // Several controls read "Create Order" (header CTA, empty-state CTA, the
      // dialog's own submit). The header CTA is the one that opens the dialog.
      fireEvent.click(screen.getAllByRole("button", { name: /^create order$/i })[0])

      const shopTrigger = await screen.findByRole("combobox", {
        name: /shop for this order/i,
      })
      fireEvent.keyDown(shopTrigger, { key: "ArrowDown" })
      expect(
        await screen.findByRole("option", { name: TAIL_SHOP.name })
      ).toBeInTheDocument()
      fireEvent.keyDown(shopTrigger, { key: "Escape" })

      fireEvent.click(screen.getByRole("button", { name: /add item/i }))
      const productTrigger = await screen.findByRole("combobox", {
        name: /product for order item 1/i,
      })
      fireEvent.keyDown(productTrigger, { key: "ArrowDown" })
      // Product 150 could not be added as a line item while the list stopped at 100.
      expect(
        await screen.findByRole("option", { name: new RegExp(`^${TAIL_PRODUCT.title} `) })
      ).toBeInTheDocument()
      // Explicit generous timeout: this test does ~4.2s of work; under the
      // parallel CPU load added by the new dashboard-a11y-axe scan it can
      // brush the default 5s ceiling and flake. Raise the ceiling rather
      // than shrink the work (QA-council integration follow-up).
    }, 15000)

    it("does NOT re-page the pickers when the orders list refetches", async () => {
      // The pickers used to ride inside `fetchData`, which reruns on every pager
      // click, switcher change and SSE order event. Following the list there would
      // have re-paged the whole catalogue on every ticket that moved, so this guards
      // the hoist that made the fix affordable — without it the fix is a perf defect.
      render(<OrdersPage />)
      await waitFor(() => expect(pagesRequested("/api/v1/products?")).toEqual(["0", "1"]))

      const before = pagesRequested("/api/v1/products?").length
      fireEvent.click(screen.getByRole("button", { name: /go to next page/i }))

      await waitFor(() => expect(ordersCalls().length).toBeGreaterThan(1))
      expect(pagesRequested("/api/v1/products?")).toHaveLength(before)
      expect(pagesRequested("/api/v1/shops")).toHaveLength(2)
    })
  })
})

/**
 * VSA-03 (23-07) — Products & Orders operate on the selected shop context.
 *
 * NOTE ON WHAT THIS PROVES: the client-side narrow is a UX layer, NOT the
 * security boundary. Reads are already grant-scoped server-side by 23-03 and
 * tenant-scoped by RLS; these cases prove the switcher selection is *visible*
 * on the consuming screens and that the All-shops context is a zero-regression
 * fall-through to today's behaviour.
 */
import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import ProductsPage from "../products/page"
import OrdersPage from "../orders/page"
import apiClient from "@/lib/api-client"
import { getShopContext } from "@/lib/shop-context"

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

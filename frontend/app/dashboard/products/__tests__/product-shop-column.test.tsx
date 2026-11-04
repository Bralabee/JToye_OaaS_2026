/**
 * #875 / UXT-117: with All shops selected the products table did not say which shop a product belongs to, so
 * two dishes with the same name in two kitchens were indistinguishable. A Shop column appears under All shops
 * and is absent when one shop is selected (the page heading already names it).
 */
import { render, screen, waitFor, within } from "@testing-library/react"
import ProductsPage from "../page"
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

const PECKHAM = { id: "5a5a5a5a-1111-4111-8111-111111111111", name: "Peckham Kitchen", published: true }
const BRIXTON = { id: "6b6b6b6b-2222-4222-8222-222222222222", name: "Brixton Bakery", published: true }

function product(id: string, shopId: string | null) {
  return {
    id,
    tenantId: "tenant-1",
    sku: `SKU-${id}`,
    title: "Jollof Rice",
    ingredientsText: "rice",
    allergenMask: 0,
    pricePennies: 800,
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
    allergenWarnings: [],
    createdAt: "2026-10-19T12:00:00Z",
    updatedAt: "2026-10-19T12:00:00Z",
  }
}

function serve(rows: unknown[]) {
  mockedApiClient.get.mockImplementation((url: string) => {
    const content = String(url).startsWith("/api/v1/shops") ? [PECKHAM, BRIXTON] : rows
    return Promise.resolve({ data: { content, totalElements: content.length, totalPages: 1 } })
  })
}

describe("Products table: Shop column (UXT-117)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedGetShopContext.mockReturnValue("all")
  })

  it("under All shops, each product row names its shop, so same-named dishes can be told apart", async () => {
    serve([product("a", PECKHAM.id), product("b", BRIXTON.id)])
    render(<ProductsPage />)

    const table = await screen.findByRole("table")
    expect(within(table).getByRole("columnheader", { name: "Shop" })).toBeInTheDocument()
    await waitFor(() => {
      expect(within(table).getByText("Peckham Kitchen")).toBeInTheDocument()
      expect(within(table).getByText("Brixton Bakery")).toBeInTheDocument()
    })
  })

  it("a shop-less product says so in that column rather than leaving it blank", async () => {
    serve([product("c", null)])
    render(<ProductsPage />)

    const table = await screen.findByRole("table")
    expect(within(table).getByRole("columnheader", { name: "Shop" })).toBeInTheDocument()
    expect(within(table).getAllByText("No shop").length).toBeGreaterThan(0)
  })

  it("with one shop selected there is no Shop column (the heading already names it)", async () => {
    mockedGetShopContext.mockReturnValue(PECKHAM.id)
    serve([product("a", PECKHAM.id)])
    render(<ProductsPage />)

    const table = await screen.findByRole("table")
    expect(within(table).queryByRole("columnheader", { name: "Shop" })).toBeNull()
  })
})

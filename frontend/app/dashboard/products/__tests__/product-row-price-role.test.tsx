/**
 * PR #1124 review round 1: the inline price edit PATCHes, and the PATCH needs SHOP_MANAGER on the product's
 * shop (ProductService.patchProduct). It was offered to every dashboard user, so a STAFF member opened the
 * editor, typed a price and only then got a 403. The control is now offered on the same signal the Shops page
 * uses for its manager-only controls (shopAdminCapabilities.canEditShop); the server stays the boundary.
 *
 * Through the real page inside the real ShopSwitcherProvider (only the network and the access answer are faked).
 */
import { render, screen, waitFor } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { fetchAllMyShops, fetchMyShops, type MyAccess } from "@/lib/shops-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
  fetchAllMyShops: jest.fn(),
  fetchMyAccess: jest.fn(),
}))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>
const mockedFetchAllMyShops = fetchAllMyShops as jest.MockedFunction<typeof fetchAllMyShops>

const SHOP_A = "shop-a"
const SHOP_B = "shop-b"
const SHOPS = [
  { id: SHOP_A, name: "Mama Ade's Kitchen", slug: "mama-ades-kitchen" },
  { id: SHOP_B, name: "Suya Stop", slug: "suya-stop" },
]

function access(over: Partial<MyAccess>): MyAccess {
  return {
    userId: "user-1",
    groupAdmin: false,
    grantedShopIds: [SHOP_A, SHOP_B],
    tenantWideRole: null,
    businessName: "Ade Foods Ltd",
    ...over,
  } as MyAccess
}

function product(id: string, title: string, pricePennies: number, shopId: string) {
  return {
    id, tenantId: "t", sku: `SKU-${id}`, title, ingredientsText: "rice, water", allergenMask: 0, pricePennies,
    vatRate: "STANDARD", description: null, imageUrl: null, additionalImageUrls: [], category: null,
    displayOrder: 0, available: true, featured: false, preparationTimeMinutes: null, dietaryTags: null,
    shopId, quantityInStock: null, allergenWarnings: [], createdAt: "2026-10-12T12:00:00Z", updatedAt: "2026-10-12T12:00:00Z",
  }
}

async function renderAs(a: MyAccess) {
  mockedFetchAllMyShops.mockResolvedValue(SHOPS as never)
  mockedFetchMyShops.mockResolvedValue({
    shops: SHOPS as never,
    isGroupAdmin: a.groupAdmin,
    userId: a.userId,
    access: a,
  })
  mockedApiClient.get.mockResolvedValue({
    data: {
      content: [product("p1", "Jollof Rice", 900, SHOP_A), product("p2", "Suya", 1250, SHOP_B)],
      totalElements: 2,
      totalPages: 1,
    },
  })
  render(
    <ShopSwitcherProvider>
      <ProductsPage />
    </ShopSwitcherProvider>
  )
  await screen.findByText("Jollof Rice")
}

beforeEach(() => {
  jest.clearAllMocks()
  window.localStorage.clear()
})

describe("Products list: the inline price edit is offered only to a shop manager of that shop (PR #1124 review)", () => {
  it("STAFF on both shops: the price is plain text, no editor control", async () => {
    await renderAs(access({ shopRoles: { [SHOP_A]: "STAFF", [SHOP_B]: "STAFF" } }))

    await waitFor(() => expect(screen.getAllByTestId("price-readonly")).toHaveLength(2))
    expect(screen.queryByRole("button", { name: /change price of/i })).not.toBeInTheDocument()
    expect(screen.getAllByTestId("price-readonly")[0]).toHaveTextContent("£9.00")
  })

  it("manager of shop A only: the control is on A's product and absent from B's", async () => {
    await renderAs(access({ shopRoles: { [SHOP_A]: "SHOP_MANAGER", [SHOP_B]: "STAFF" } }))

    expect(await screen.findByRole("button", { name: "Change price of Jollof Rice, now £9.00" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /change price of suya/i })).not.toBeInTheDocument()
    expect(screen.getByTestId("price-readonly")).toHaveTextContent("£12.50")
  })

  it("a Group admin is offered it on every product", async () => {
    await renderAs(access({ groupAdmin: true, grantedShopIds: null, shopRoles: null }))

    expect(await screen.findByRole("button", { name: "Change price of Jollof Rice, now £9.00" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Change price of Suya, now £12.50" })).toBeInTheDocument()
  })
})

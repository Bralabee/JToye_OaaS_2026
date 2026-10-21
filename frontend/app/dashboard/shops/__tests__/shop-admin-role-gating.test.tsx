/**
 * Issue #961 (SW-TND-06): a STAFF member was offered Add Shop, Edit shop and Delete shop on the Shops page,
 * and the API refuses all three (Group admin for create and delete, SHOP_MANAGER on the shop for edit). The
 * page now offers only what the server's own staff/me answer says the caller can use.
 *
 * Every arm renders the REAL page with the switcher context stubbed to a staff/me answer, and a CONTROL arm
 * (a Group admin) shows the same fixture does offer the controls, so a missing button is the role gate and
 * not a render that never reached the table.
 */
import { render, screen } from "@testing-library/react"
import ShopsPage from "../page"
import apiClient from "@/lib/api-client"
import type { MyAccess } from "@/lib/shops-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

let mockSwitcher: { loading: boolean; access: MyAccess | null } | null = null
jest.mock("@/components/dashboard/shop-switcher-provider", () => ({
  useOptionalShopSwitcherData: () => mockSwitcher,
}))

const SHOP_A = {
  id: "shop-a",
  tenantId: "t1",
  name: "Kitchen A",
  address: "1 High St",
  slug: "kitchen-a",
  published: true,
  minimumOrderPennies: 0,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
  hoursSource: "STRUCTURED",
}

function access(over: Partial<MyAccess>): MyAccess {
  return {
    userId: "u1",
    groupAdmin: false,
    grantedShopIds: ["shop-a"],
    tenantWideRole: null,
    businessName: "Biz",
    shopRoles: { "shop-a": "STAFF" },
    ...over,
  }
}

async function renderPage(switcher: { loading: boolean; access: MyAccess | null } | null) {
  mockSwitcher = switcher
  mockedApiClient.get.mockResolvedValue({
    data: { content: [SHOP_A], totalElements: 1, totalPages: 1, last: true, number: 0, size: 20 },
  })
  render(<ShopsPage />)
  await screen.findByText("Kitchen A")
}

describe("Shops page: shop-admin controls follow the caller's role (#961)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockSwitcher = null
  })

  it("CONTROL: a Group admin is offered Add Shop, Edit shop and Delete shop", async () => {
    await renderPage({ loading: false, access: access({ groupAdmin: true, grantedShopIds: null, shopRoles: null }) })

    expect(screen.getByRole("button", { name: /add shop/i })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Edit shop Kitchen A" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Delete shop Kitchen A" })).toBeInTheDocument()
  })

  it("a STAFF member is offered none of Add Shop, Edit shop or Delete shop, but still reaches Scheduled slots", async () => {
    await renderPage({ loading: false, access: access({}) })

    expect(screen.queryByRole("button", { name: /add shop/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /edit shop/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /delete shop/i })).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: /scheduled slots and whatsapp link for kitchen a/i })).toBeInTheDocument()
  })

  it("a tenant-wide STAFF grant is gated the same way", async () => {
    await renderPage({
      loading: false,
      access: access({ tenantWideRole: "STAFF", shopRoles: { "shop-a": "STAFF" } }),
    })

    expect(screen.queryByRole("button", { name: /add shop/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /edit shop/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /delete shop/i })).not.toBeInTheDocument()
  })

  it("a SHOP_MANAGER may edit their shop but is not offered Add Shop or Delete shop (the API refuses both)", async () => {
    await renderPage({ loading: false, access: access({ shopRoles: { "shop-a": "SHOP_MANAGER" } }) })

    expect(screen.getByRole("button", { name: "Edit shop Kitchen A" })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /add shop/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /delete shop/i })).not.toBeInTheDocument()
  })

  it("offers nothing destructive while the caller's access is still loading", async () => {
    await renderPage({ loading: true, access: null })

    expect(screen.queryByRole("button", { name: /add shop/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /edit shop/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /delete shop/i })).not.toBeInTheDocument()
  })

  it("decides nothing when staff/me could not be read: the server stays the boundary", async () => {
    await renderPage({ loading: false, access: null })

    expect(screen.getByRole("button", { name: /add shop/i })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Edit shop Kitchen A" })).toBeInTheDocument()
  })

  it("a STAFF member sees an 'ask your administrator' empty state instead of an Add Shop button", async () => {
    mockSwitcher = { loading: false, access: access({}) }
    mockedApiClient.get.mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 0 } })
    render(<ShopsPage />)

    expect(await screen.findByText("No shops yet")).toBeInTheDocument()
    expect(screen.getByText("Ask your administrator to add a shop.")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /add shop/i })).not.toBeInTheDocument()
  })
})

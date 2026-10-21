import { shopAdminCapabilities } from "@/lib/shop-admin-capabilities"
import type { MyAccess } from "@/lib/shops-api"

function access(over: Partial<MyAccess>): MyAccess {
  return {
    userId: "u1",
    groupAdmin: false,
    grantedShopIds: ["a", "b"],
    tenantWideRole: null,
    businessName: null,
    shopRoles: { a: "STAFF", b: "SHOP_MANAGER" },
    ...over,
  }
}

describe("shopAdminCapabilities (#961)", () => {
  it("offers everything with no switcher provider (a bare mount decides nothing)", () => {
    const c = shopAdminCapabilities(null)
    expect(c.pending).toBe(false)
    expect(c.canCreateShop && c.canDeleteShop && c.canEditShop("a") && c.canManageSlots("a")).toBe(true)
  })

  it("offers nothing while loading", () => {
    const c = shopAdminCapabilities({ loading: true, access: null })
    expect(c.pending).toBe(true)
    expect(c.canCreateShop || c.canDeleteShop || c.canEditShop("a") || c.canManageSlots("a")).toBe(false)
  })

  it("offers everything when staff/me could not be read (the server stays the boundary)", () => {
    const c = shopAdminCapabilities({ loading: false, access: null })
    expect(c.canCreateShop && c.canDeleteShop && c.canEditShop("a")).toBe(true)
  })

  it("offers everything to a Group admin", () => {
    const c = shopAdminCapabilities({
      loading: false,
      access: access({ groupAdmin: true, grantedShopIds: null, shopRoles: null }),
    })
    expect(c.canCreateShop && c.canDeleteShop && c.canEditShop("zzz") && c.canManageSlots("zzz")).toBe(true)
  })

  it("STAFF on a shop: no create, delete, edit or slot management there; a SHOP_MANAGER shop of the same user is editable", () => {
    const c = shopAdminCapabilities({ loading: false, access: access({}) })
    expect(c.canCreateShop).toBe(false)
    expect(c.canDeleteShop).toBe(false)
    expect(c.canEditShop("a")).toBe(false)
    expect(c.canManageSlots("a")).toBe(false)
    expect(c.canEditShop("b")).toBe(true)
    expect(c.canManageSlots("b")).toBe(true)
  })

  it("a tenant-wide STAFF grant is STAFF on every shop", () => {
    const c = shopAdminCapabilities({
      loading: false,
      access: access({ tenantWideRole: "STAFF", shopRoles: null }),
    })
    expect(c.canEditShop("a")).toBe(false)
    expect(c.canEditShop("anything")).toBe(false)
    expect(c.canCreateShop).toBe(false)
  })

  it("a role the answer does not name (a server older than #961) decides nothing for edit, but never offers create or delete", () => {
    const c = shopAdminCapabilities({ loading: false, access: access({ shopRoles: undefined }) })
    expect(c.canEditShop("a")).toBe(true)
    expect(c.canCreateShop).toBe(false)
    expect(c.canDeleteShop).toBe(false)
  })
})

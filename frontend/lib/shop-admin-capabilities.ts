import type { MyAccess } from "@/lib/shops-api"
import type { ShopRole } from "@/lib/staff-api"

/**
 * What the Shops page offers the caller (#961). The server refuses a STAFF member every shop-admin
 * mutation (create and delete are Group admin; edit, media and scheduled slots need SHOP_MANAGER on the
 * shop), and the page used to offer all of them anyway: a tap on a destructive-looking control that could
 * only fail. This decides what to OFFER, from the server's own answer (GET /api/v1/staff/me, the same
 * source the sidebar's Developers item and the finance page use). It is never a trust boundary: the API
 * checks every request.
 *
 * Fails toward offering, because the server stays the boundary and a false hide would lock an owner out of
 * their own controls on a transient read failure:
 * - no switcher provider (a bare mount), or an answer that could not be read: decides nothing, offers all;
 * - still loading: offers nothing, so a STAFF member never sees the controls flash and vanish;
 * - a Group admin: offers all;
 * - anyone else: no create, no delete; edit / slots management only where their role on that shop is
 *   SHOP_MANAGER or higher. A role the answer does not name (a server older than #961) decides nothing.
 */
export interface ShopAdminCapabilities {
  /** True while the caller's access is still loading: every `can*` is false until it settles. */
  pending: boolean
  canCreateShop: boolean
  canDeleteShop: boolean
  canEditShop: (shopId: string) => boolean
  canManageSlots: (shopId: string) => boolean
}

const RANK: Record<ShopRole, number> = { STAFF: 0, SHOP_MANAGER: 1, GROUP_ADMIN: 2 }

function roleOn(access: MyAccess, shopId: string): ShopRole | null {
  return access.shopRoles?.[shopId] ?? access.tenantWideRole ?? null
}

export function shopAdminCapabilities(
  switcher: { loading: boolean; access: MyAccess | null } | null
): ShopAdminCapabilities {
  const offerAll: ShopAdminCapabilities = {
    pending: false,
    canCreateShop: true,
    canDeleteShop: true,
    canEditShop: () => true,
    canManageSlots: () => true,
  }
  if (!switcher) return offerAll
  if (switcher.loading) {
    return {
      pending: true,
      canCreateShop: false,
      canDeleteShop: false,
      canEditShop: () => false,
      canManageSlots: () => false,
    }
  }
  const access = switcher.access
  if (!access || access.groupAdmin) return offerAll
  const atLeastManager = (shopId: string): boolean => {
    const role = roleOn(access, shopId)
    return role === null ? true : RANK[role] >= RANK.SHOP_MANAGER
  }
  return {
    pending: false,
    canCreateShop: false,
    canDeleteShop: false,
    canEditShop: atLeastManager,
    canManageSlots: atLeastManager,
  }
}

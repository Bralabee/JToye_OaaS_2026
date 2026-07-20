import apiClient from "@/lib/api-client"
import type { PageResponse, Shop } from "@/types/api"

/**
 * The caller's server-authoritative effective access (GET /api/v1/staff/me,
 * delivered by plan 23-12). This is the single source of truth for GROUP_ADMIN
 * status — a browser-side JWT parse is the wrong shape even for a UI hint (CR-08),
 * because a GROUP_ADMIN is anyone holding a NULL-shop GROUP_ADMIN row, which under
 * the default strict-scoping = false is every JIT-provisioned user; realm `admin`
 * is only the bridge (D-03), not the definition.
 */
export interface MyAccess {
  /** The caller's Keycloak `sub` — the authoritative identity for self-checks (WR-12). */
  userId: string
  /** True when the caller may act group-wide (server-decided, not JWT-parsed). */
  groupAdmin: boolean
  /**
   * Resolved at the DTO boundary by 23-12: `null` for an unrestricted GROUP_ADMIN
   * (all shops — NOT "no shops"); an exact, possibly-empty set for a scoped user
   * (empty ⇒ no access). So an empty array only ever means "no access".
   */
  grantedShopIds: string[] | null
}

/**
 * The caller's shops + their server-authoritative access, feeding the shop-context
 * switcher (VSA-03) and the staff screen's self-identification (VSA-04).
 */
export interface MyShops {
  /** Read-scoped to the caller's grants server-side (23-03) — a non-GA cannot
   *  structurally see ungranted shops (T-23-05-03). */
  shops: Shop[]
  /** GROUP_ADMIN status from GET /api/v1/staff/me — server-authoritative (CR-08). */
  isGroupAdmin: boolean
  /** The caller's Keycloak `sub`, for self-identification without an email round-trip. */
  userId: string
}

/**
 * Fetch the caller's server-authoritative effective access. The server
 * (`ShopAccessService`) is the single decision point; the client asks, it does
 * not re-derive.
 */
export async function fetchMyAccess(): Promise<MyAccess> {
  const res = await apiClient.get<MyAccess>("/api/v1/staff/me")
  return {
    userId: res.data?.userId ?? "",
    groupAdmin: res.data?.groupAdmin ?? false,
    grantedShopIds: res.data?.grantedShopIds ?? null,
  }
}

/**
 * Fetch the caller's read-scoped shop list (GET /api/v1/shops, already narrowed
 * server-side by 23-03) plus their server-authoritative access (GET /api/v1/staff/me).
 * Requests a large page so a multi-shop group's full grant set fits in one call.
 *
 * IN-01 (deferred): `size=200` is a hard cap — a tenant with >200 shops gets a
 * truncated list. The real fix is a dedicated unpaginated `/api/v1/shops/mine`
 * endpoint (new API surface, out of scope for this gap-closure); >200 shops per
 * tenant is not a current scenario.
 */
export async function fetchMyShops(): Promise<MyShops> {
  const [shopsRes, access] = await Promise.all([
    apiClient.get<PageResponse<Shop>>("/api/v1/shops?page=0&size=200"),
    fetchMyAccess(),
  ])
  const shops = shopsRes.data?.content ?? []
  return { shops, isGroupAdmin: access.groupAdmin, userId: access.userId }
}

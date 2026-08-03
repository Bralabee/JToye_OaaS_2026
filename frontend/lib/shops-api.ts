import apiClient from "@/lib/api-client"
import { resolveShopsPageSize } from "@/lib/env-validation"
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
 * A defensive bound on the paging loop in {@link fetchAllMyShops}.
 *
 * Not environment config — a code-level circuit breaker. Every normal exit is a
 * signal FROM the server (`last`, `totalPages`, a short page, an empty page); this
 * only fires if the API keeps claiming there is another full page forever, which
 * would otherwise hang the browser tab rather than fail. At the default page size
 * that is 10,000 shops; hitting it logs a warning and returns what was collected.
 */
const MAX_SHOP_PAGES = 50

/**
 * Page GET /api/v1/shops until the API says there is nothing after this page (#282).
 *
 * The previous single `?page=0&size=200` request silently truncated: a tenant with
 * more shops than one page lost the tail entirely — those shops could not be picked
 * in the switcher and did not appear in the staff screen's shop list. Raising the
 * literal only moves the cliff, so this follows the list instead.
 *
 * Stops on the FIRST of: an empty page, `last: true`, the last of `totalPages`, a
 * short page (fewer items than requested), or {@link MAX_SHOP_PAGES}. The short-page
 * and empty-page exits mean a response with no paging metadata at all still
 * terminates after one request.
 */
async function fetchAllMyShops(): Promise<Shop[]> {
  const size = resolveShopsPageSize(process.env.NEXT_PUBLIC_SHOPS_PAGE_SIZE)
  const shops: Shop[] = []

  for (let page = 0; page < MAX_SHOP_PAGES; page++) {
    const res = await apiClient.get<PageResponse<Shop>>(
      `/api/v1/shops?page=${page}&size=${size}`
    )
    const body = res.data
    const content = body?.content ?? []
    shops.push(...content)

    if (content.length === 0) return shops
    if (body?.last === true) return shops
    if (typeof body?.totalPages === "number" && page + 1 >= body.totalPages) {
      return shops
    }
    if (content.length < size) return shops
  }

  console.warn(
    `[shops-api] stopped paging /api/v1/shops at the ${MAX_SHOP_PAGES}-page bound ` +
      `(${shops.length} shops); the API never reported a final page.`
  )
  return shops
}

/**
 * Fetch the caller's read-scoped shop list (GET /api/v1/shops, already narrowed
 * server-side by 23-03) plus their server-authoritative access (GET /api/v1/staff/me).
 * The shop list is paged in full (#282), so the caller sees every shop they hold —
 * not the first page's worth.
 */
export async function fetchMyShops(): Promise<MyShops> {
  const [shops, access] = await Promise.all([fetchAllMyShops(), fetchMyAccess()])
  return { shops, isGroupAdmin: access.groupAdmin, userId: access.userId }
}

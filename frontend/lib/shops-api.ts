import { getSession } from "next-auth/react"
import apiClient from "@/lib/api-client"
import type { PageResponse, Shop } from "@/types/api"

/**
 * The caller's shops + their GROUP_ADMIN status, feeding the shop-context
 * switcher (VSA-03).
 */
export interface MyShops {
  /** Read-scoped to the caller's grants server-side (23-03) — a non-GA cannot
   *  structurally see ungranted shops (T-23-05-03). */
  shops: Shop[]
  /** True for a GROUP_ADMIN. Realm `admin` ⇒ implicit GROUP_ADMIN (D-03). */
  isGroupAdmin: boolean
}

/** base64url-decode a JWT payload segment; returns `{}` on any malformed input. */
function decodeJwtPayload(token: string): Record<string, unknown> {
  try {
    const segment = token.split(".")[1]
    if (!segment) return {}
    const base64 = segment.replace(/-/g, "+").replace(/_/g, "/")
    const json =
      typeof atob === "function"
        ? atob(base64)
        : Buffer.from(base64, "base64").toString("binary")
    return JSON.parse(json) as Record<string, unknown>
  } catch {
    return {}
  }
}

/**
 * GROUP_ADMIN status derived from the session access-token realm roles — a realm
 * `admin` is an implicit GROUP_ADMIN (D-03). This gates a UI affordance only; the
 * server independently re-gates every group-wide write to GROUP_ADMIN (T-23-05-02),
 * so this is not a trust boundary.
 */
export async function isGroupAdminFromSession(): Promise<boolean> {
  const session = await getSession()
  const token = session?.accessToken
  if (!token) return false
  const realmAccess = decodeJwtPayload(token)["realm_access"] as
    | { roles?: string[] }
    | undefined
  return Array.isArray(realmAccess?.roles) && realmAccess.roles.includes("admin")
}

/**
 * Fetch the caller's read-scoped shop list (GET /api/v1/shops, already narrowed
 * server-side by 23-03) plus their GROUP_ADMIN status. Requests a large page so a
 * multi-shop group's full grant set fits in one call.
 */
export async function fetchMyShops(): Promise<MyShops> {
  const res = await apiClient.get<PageResponse<Shop>>("/api/v1/shops?page=0&size=200")
  const shops = res.data?.content ?? []
  const isGroupAdmin = await isGroupAdminFromSession()
  return { shops, isGroupAdmin }
}

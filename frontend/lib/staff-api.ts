import apiClient from "@/lib/api-client"

/**
 * Client for the GROUP_ADMIN-gated staff-management API (23-04, VSA-04).
 *
 * Every call is authorized SERVER-side: `requireGroupAdmin()` guards each
 * endpoint, so a non-GROUP_ADMIN receives a typed `/shop-access-denied` 403 and
 * this module never sees directory data (T-23-06-02). Nothing here is a trust
 * boundary — it is transport plus typing over `apiClient` (which already attaches
 * the Bearer token + X-Tenant-Id and retries 5xx).
 */

/** In-tenant shop-role tier (mirrors the Java `ShopRole` enum, D-03). */
export type ShopRole = "STAFF" | "SHOP_MANAGER" | "GROUP_ADMIN"

/**
 * A grant's provenance (mirrors the Java `GrantSource` enum, V57). `JIT` = auto-granted
 * on the user's first sign-in (D-04); `OPERATOR` = deliberately granted by a group admin.
 * A group admin can see this before flipping strict-scoping, which de-honours JIT
 * tenant-wide grants (CR-07).
 */
export type GrantSource = "JIT" | "OPERATOR"

/** Human-readable role labels — GROUP_ADMIN is inherently tenant-wide. */
export const ROLE_LABELS: Record<ShopRole, string> = {
  STAFF: "Staff",
  SHOP_MANAGER: "Shop manager",
  GROUP_ADMIN: "Group admin",
}

/** A login-populated directory entry — the grant-target picker source (D-09). */
export interface DirectoryEntry {
  userId: string
  email: string
  displayName: string | null
  lastSeen: string | null
}

/** A current `(user, shop|null, role)` grant. A null `shopId` is tenant-wide. */
export interface StaffMember {
  id: string
  userId: string
  shopId: string | null
  role: ShopRole
  /** Provenance (V57) — `JIT` rows are auto-granted on first sign-in (CR-07). */
  grantSource: GrantSource
  createdAt: string | null
  createdBy: string | null
}

export interface StaffList {
  directory: DirectoryEntry[]
  grants: StaffMember[]
}

export interface GrantStaffInput {
  userId: string
  /** null ⇒ tenant-wide (required for a GROUP_ADMIN grant; 23-04 rejects a
   *  shop-scoped GROUP_ADMIN with a 400 so the last-admin count stays exact). */
  shopId: string | null
  role: ShopRole
}

/** GET /api/v1/staff — the directory + current grants. */
export async function fetchStaff(): Promise<StaffList> {
  const res = await apiClient.get<StaffList>("/api/v1/staff")
  return {
    directory: res.data?.directory ?? [],
    grants: res.data?.grants ?? [],
  }
}

/**
 * POST /api/v1/staff/grant — idempotent: a duplicate grant replays the existing
 * row as a 200 with the same id (23-04) rather than erroring, so a double-submit
 * is safe.
 */
export async function grantStaff(input: GrantStaffInput): Promise<StaffMember> {
  const res = await apiClient.post<StaffMember>("/api/v1/staff/grant", input)
  return res.data
}

/**
 * DELETE /api/v1/staff/{id} — 204 on success; a 409 (`/last-group-admin`) when
 * the grant is the tenant's final GROUP_ADMIN (D-11 lockout guard).
 */
export async function revokeStaff(id: string): Promise<void> {
  await apiClient.delete(`/api/v1/staff/${id}`)
}

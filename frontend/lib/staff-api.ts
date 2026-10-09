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
 * A grant's provenance (mirrors the Java `GrantSource` enum, V57). `JIT` = written
 * automatically at a user's first sign-in while strict scoping was off (D-04);
 * `OPERATOR` = deliberately granted by a group admin. Strict scoping is ON by default
 * since D-06 (37-04), and under it a JIT tenant-wide GROUP_ADMIN row is honoured only
 * for the tenant's bootstrap admin. The Staff page therefore never shows provenance as
 * access: it shows {@link EffectiveAccess}, which the server computes (D-09).
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
  /** Provenance (V57, see {@link GrantSource}). Never rendered as access (D-09). */
  grantSource: GrantSource
  createdAt: string | null
  createdBy: string | null
}

/**
 * The strongest thing a person may do in the tenant (mirrors the Java
 * `EffectiveAccess.Level`, 37-05). REALM_ADMIN is a platform admin account, seen
 * signing in with the realm admin role (V76); the database cannot see that role
 * live, so it is an observation, never "No access".
 */
export type AccessLevel = "GROUP_ADMIN" | "REALM_ADMIN" | "SHOP_ROLES" | "NONE"

/**
 * One person's effective access, as `ShopAccessService.effectiveAccessFor` decides
 * it — the same decision path enforcement uses (D-09, 37-05). The browser renders
 * this value and derives none of its own (T-37-12).
 */
export interface EffectiveAccess {
  userId: string
  level: AccessLevel
  /** GROUP_ADMIN only because this is the tenant's oldest automatic admin and the
   *  tenant has no operator-granted admin (kept so it cannot lock itself out). */
  bootstrapAdmin: boolean
  /** The access covers every shop of the tenant, present and future. */
  allShops: boolean
  /** Role a tenant-wide STAFF / SHOP_MANAGER grant confers on every shop. */
  tenantWideRole: ShopRole | null
  /** Role per specifically granted shop id. */
  perShopRole: Record<string, ShopRole>
  /** First sign-in seen with the realm admin role; null when never observed. */
  realmAdminSeenAt: string | null
}

/**
 * A person on the Staff page (`StaffPersonDto`, 37-05): everyone who has signed in
 * to the tenant plus every grant holder with no directory row (an integration
 * account), whose email, name and last-seen are then null.
 */
export interface StaffPerson {
  userId: string
  /** Masked like the directory (WR-10); null when the person never signed in here. */
  maskedEmail: string | null
  displayName: string | null
  lastSeen: string | null
  /** Absent only from a server older than 37-05; the page then says "Not recorded". */
  effectiveAccess?: EffectiveAccess | null
}

export interface StaffList {
  directory: DirectoryEntry[]
  grants: StaffMember[]
  /** null when the server sent no `people` (older than 37-05). */
  people: StaffPerson[] | null
}

export interface GrantStaffInput {
  userId: string
  /** null ⇒ tenant-wide (required for a GROUP_ADMIN grant; 23-04 rejects a
   *  shop-scoped GROUP_ADMIN with a 400 so the last-admin count stays exact). */
  shopId: string | null
  role: ShopRole
}

/** GET /api/v1/staff — the directory, current grants and the people list. */
export async function fetchStaff(): Promise<StaffList> {
  const res = await apiClient.get<StaffList>("/api/v1/staff")
  return {
    directory: res.data?.directory ?? [],
    grants: res.data?.grants ?? [],
    people: Array.isArray(res.data?.people) ? res.data.people : null,
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

/**
 * Each role's plain-English scope, shown beside its name wherever a role is chosen
 * (the grant form and the invite form, UI-SPEC § B1). One list, so the two forms
 * cannot describe the same role differently.
 */
export const ROLE_HINTS: Record<ShopRole, string> = {
  STAFF: "order ops on one shop",
  SHOP_MANAGER: "full CRUD on one shop",
  GROUP_ADMIN: "all shops + staff management",
}

/** The roles in the order every picker lists them. */
export const ROLE_ORDER: ShopRole[] = ["STAFF", "SHOP_MANAGER", "GROUP_ADMIN"]

/**
 * An invitation's state, computed by the server at read time (mirrors the Java
 * `StaffInvite.Status`, 37-07). OPEN = the link works; EXPIRED = its time ran out;
 * ACCEPTED = it became a grant; CANCELLED = cancelled, or replaced by a resend.
 */
export type StaffInviteStatus = "OPEN" | "EXPIRED" | "ACCEPTED" | "CANCELLED"

/**
 * One invitation as a Group admin sees it (`StaffInviteDto`, 37-07). `shopId` null
 * means every shop. The link token is never part of this body: it exists only in the
 * invitee's email.
 */
export interface StaffInvite {
  id: string
  /** The address the link went to, in full (the Group admin typed it). */
  email: string
  role: ShopRole
  shopId: string | null
  status: StaffInviteStatus
  expiresAt: string
  createdAt: string | null
  createdBy: string | null
  acceptedAt: string | null
  revokedAt: string | null
}

export interface CreateStaffInviteInput {
  email: string
  role: ShopRole
  /** null ⇒ every shop (always null for GROUP_ADMIN; the server refuses otherwise). */
  shopId: string | null
}

/**
 * The outcome of {@link createStaffInvite}. `created` is false on the server's
 * idempotent replay (200): an invitation for the same email, shop and role was
 * already open, and NO second email was sent — so the page must not say "sent".
 */
export interface CreateStaffInviteResult {
  invite: StaffInvite
  created: boolean
}

/** The problem `type` suffix of the "address belongs to another business" refusal. */
export const INVITE_OTHER_BUSINESS_PROBLEM = "staff-invite-email-in-other-business"

/** GET /api/v1/staff/invites — every invitation of the business, newest first. */
export async function fetchStaffInvites(): Promise<StaffInvite[]> {
  const res = await apiClient.get<StaffInvite[]>("/api/v1/staff/invites")
  return Array.isArray(res.data) ? res.data : []
}

/**
 * POST /api/v1/staff/invites — 201 when a new invitation was created and emailed,
 * 200 when the same (email, shop, role) invitation was already open (no email).
 */
export async function createStaffInvite(
  input: CreateStaffInviteInput
): Promise<CreateStaffInviteResult> {
  const res = await apiClient.post<StaffInvite>("/api/v1/staff/invites", input)
  return { invite: res.data, created: res.status !== 200 }
}

/**
 * POST /api/v1/staff/invites/{id}/resend — revokes the old link and emails a new
 * one for the same email, shop and role. Deliberately not idempotent.
 */
export async function resendStaffInvite(id: string): Promise<StaffInvite> {
  const res = await apiClient.post<StaffInvite>(`/api/v1/staff/invites/${id}/resend`)
  return res.data
}

/** DELETE /api/v1/staff/invites/{id} — the link stops working (204, repeatable). */
export async function cancelStaffInvite(id: string): Promise<void> {
  await apiClient.delete(`/api/v1/staff/invites/${id}`)
}

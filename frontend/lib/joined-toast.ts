import type { MyAccess } from "@/lib/shops-api"

/**
 * The one-time welcome after an invitation is accepted (D-26, 37-09; UI-SPEC § B2
 * Success): "You've joined {business}. You can work on {shop / all shops}."
 *
 * Built ONLY from what the server says now — `GET /api/v1/staff/me` (the access it
 * enforces, and the business name) and the caller's grant-scoped shop list. The
 * accept page's own answer is not carried across the Keycloak sign-in: a URL
 * parameter would let any link make the dashboard claim a business or a shop.
 */

/** "A", "A and B", "A, B and C". */
function listNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? ""
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`
}

/**
 * Where the person can work, in words: "all shops" for a Group admin or a tenant-wide
 * role, else the names of their granted shops. Null when the server says they have
 * no access at all — then there is nothing to welcome them to.
 */
export function joinedScope(
  access: MyAccess,
  shops: { id: string; name: string }[]
): string | null {
  if (access.groupAdmin || access.tenantWideRole) return "all shops"
  const names = (access.grantedShopIds ?? [])
    .map((id) => shops.find((s) => s.id === id)?.name)
    .filter((n): n is string => !!n)
  return names.length > 0 ? listNames(names) : null
}

/** The toast title, or null when there is no access to describe. */
export function joinedToastTitle(
  access: MyAccess,
  shops: { id: string; name: string }[]
): string | null {
  const scope = joinedScope(access, shops)
  if (!scope) return null
  const business = access.businessName ?? "your new business"
  return `You've joined ${business}. You can work on ${scope}.`
}

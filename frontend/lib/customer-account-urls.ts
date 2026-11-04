/**
 * Where a signed-in customer changes their password (SW-JRD-07 / SW-SAM-11, owner ruling 2026-11-04):
 * the sign-in provider's own account page for the customer realm. The platform stores no password and
 * builds no password form; Keycloak's account console does it, behind the same sign-in.
 *
 * Same base as `lib/customer-auth.ts` (`NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`, the realm URL), same
 * dev default, and never the staff realm.
 */
export function customerPasswordChangeUrl(): string {
  const base = (
    process.env.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL || "http://localhost:8085/realms/jtoye-customers"
  ).replace(/\/+$/, "")
  return `${base}/account/#/security/signingin`
}

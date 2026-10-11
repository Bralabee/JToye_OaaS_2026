/**
 * D-08 (37-06) — the one signal a refused dashboard read sends to the dashboard shell.
 *
 * Every shop- or group-level refusal from core-java is the same typed 403,
 * `https://jtoye.uk/errors/shop-access-denied` (`GlobalExceptionHandler`). The
 * api-client interceptor dispatches {@link SHOP_ACCESS_DENIED_EVENT} on `window`
 * when it sees one; the shell's access provider then re-reads `GET /api/v1/staff/me`
 * and shows the no-access page only if that fresh answer says nothing is left. A
 * single refusal therefore never decides anything by itself: a manager refused one
 * shop keeps the dashboard.
 *
 * Its own module (not api-client.ts) so a test that auto-mocks the api-client still
 * sees the real name.
 */
export const SHOP_ACCESS_DENIED_EVENT = "jtoye:shop-access-denied"

/** The RFC 7807 `type` ends with this; matched as a suffix, like the staff page does. */
const SHOP_ACCESS_DENIED_TYPE_SUFFIX = "/shop-access-denied"

/** True for the typed shop-access-denied 403 and for nothing else. */
export function isShopAccessDenied(status: number | undefined, problemType: unknown): boolean {
  return (
    status === 403 &&
    typeof problemType === "string" &&
    problemType.endsWith(SHOP_ACCESS_DENIED_TYPE_SUFFIX)
  )
}

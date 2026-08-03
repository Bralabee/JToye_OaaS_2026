/**
 * Cookie contract for the customer session — the single place the three cookie
 * names and their options are declared.
 *
 * These constants were previously redeclared in login/, logout/ and session/
 * route handlers. A name that drifts in one of the three does not fail to
 * compile and does not fail a unit test that mocks the same wrong string; it
 * simply logs the customer out. Now that a fourth consumer (the refresh path)
 * reads and re-issues them, one source is load-bearing rather than tidy.
 *
 * SERVER ONLY. These cookies are HttpOnly by design so the OAuth tokens are
 * never readable from JavaScript (XSS exfiltration mitigation) — see
 * lib/customer-auth.ts for the browser half of the model.
 */

export const ACCESS_COOKIE = "jtoye-customer-access"
export const REFRESH_COOKIE = "jtoye-customer-refresh"
export const ID_COOKIE = "jtoye-customer-id"

export const CUSTOMER_COOKIES = [ACCESS_COOKIE, REFRESH_COOKIE, ID_COOKIE] as const

/**
 * Refresh/ID cookies outlive the access token deliberately: the access token is
 * short (the realm sets accessTokenLifespan=300s) and the refresh token is what
 * carries the session across that boundary. Capped at 30 days; the effective
 * limit is the IdP's own ssoSessionIdleTimeout / ssoSessionMaxLifespan, which is
 * where a session lifetime decision belongs.
 */
export const REFRESH_MAX_AGE = 60 * 60 * 24 * 30

export function cookieBaseOptions() {
  return {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
    path: "/",
  }
}

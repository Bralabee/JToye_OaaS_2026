/**
 * buildSession copies the access + id tokens from the server-side NextAuth JWT
 * onto the client-readable session, but DELIBERATELY omits the refresh token
 * (issue #87 P1-5, threat T-bl2-05).
 *
 * The refresh token stays on the server-side JWT (see callbacks.jwt and
 * refreshAccessToken in auth.ts) so silent token refresh keeps working. Leaking
 * it into the browser-visible session — as the vendor flow previously did —
 * would let any XSS mint fresh access tokens indefinitely, and is inconsistent
 * with the customer flow. Kept as a pure function so it is unit-testable without
 * a NextAuth runtime.
 */

import type { JWT } from "next-auth/jwt"

export function buildSession<T>(session: T, token: JWT): T {
  const s = session as Record<string, unknown>
  s.accessToken = token.accessToken
  s.idToken = token.idToken
  // 37-18 (D-14): when the access token expires (epoch seconds), so the kitchen board can ask
  // for a fresh one before it does. Not a credential: the same `exp` is inside the access token
  // the session already carries.
  s.expiresAt = token.expiresAt
  // Defense in depth: guarantee no refresh token can ride along, even if a
  // caller handed us a session object that already carried one.
  delete s.refreshToken
  return session
}

/**
 * 37-18 (D-14): refresh inside the access token's last minute, not only once it has expired.
 *
 * The NextAuth `jwt` callback used to keep a token until `Date.now() >= expiresAt * 1000`, so a
 * session read a minute before expiry returned the same token, and a request sent in its last
 * second could land expired. With this skew the kitchen board's read at `expiresAt - 60 s`
 * (use-session-keep-alive.ts) comes back with a new token. An unknown expiry is refreshed.
 */
export const ACCESS_TOKEN_REFRESH_SKEW_S = 60

export function accessTokenNeedsRefresh(expiresAt: number | undefined, nowMs: number): boolean {
  if (!expiresAt) return true
  return nowMs >= (expiresAt - ACCESS_TOKEN_REFRESH_SKEW_S) * 1000
}

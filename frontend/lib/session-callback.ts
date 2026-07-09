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
  // Defense in depth: guarantee no refresh token can ride along, even if a
  // caller handed us a session object that already carried one.
  delete s.refreshToken
  return session
}

import { timingSafeEqual } from "node:crypto"
import type { ResponseCookie } from "next/dist/compiled/@edge-runtime/cookies"

/**
 * OIDC RP-initiated-logout `state` for the vendor sign-out — PR #726 review, M4.
 *
 * THE GAP. `app/api/vendor-auth/logout-complete/route.ts` ends the Auth.js
 * session on a GET, because Keycloak's `post_logout_redirect_uri` return leg
 * can only ever be a GET. A GET with no binding is cross-site reachable:
 * `<img src="https://<vendor-origin>/api/vendor-auth/logout-complete">` on any
 * page force-signs-out any vendor who loads it. Denial of session rather than
 * escalation, but it was documented as an accepted residual and it need not be.
 *
 * THE BINDING. OpenID Connect RP-Initiated Logout 1.0 §2 defines `state`: the
 * RP puts an opaque value on the end-session request and the OP MUST echo it
 * back as a query parameter on `post_logout_redirect_uri`. So:
 *
 *   1. `logout-url` mints a fresh random value, sets it in a SHORT-LIVED,
 *      httpOnly, SameSite=Lax cookie scoped to `/api/vendor-auth`, and appends
 *      `state=<value>` to the Keycloak URL. The REGISTERED redirect URI itself
 *      stays fixed and query-less (plan R2) — the echo is Keycloak's doing.
 *   2. `logout-complete` requires `?state=` to be present and equal to the cookie
 *      (constant-time), clears the session ONLY then, and expires the cookie so
 *      the value is one-shot.
 *
 * A cross-site attacker cannot read the cookie (httpOnly, different site) and so
 * cannot supply a matching `state`; a stale or forged hit lands on `/auth/signin`
 * with the session intact — today's pre-FE-1 behaviour, never worse (E-5 shape).
 *
 * SameSite=Lax is the correct strictness: the return leg is a cross-site
 * TOP-LEVEL navigation from Keycloak, which Lax permits, while a cross-site
 * `<img>`/`fetch` subresource request never carries the cookie at all. Strict
 * would drop it on the very navigation that needs it.
 *
 * `Secure` follows the resolved PUBLIC origin's scheme rather than being forced:
 * a Secure cookie set over plain http (the compose stack, `http://app.jtoye.local`)
 * is discarded by the browser, and every legitimate sign-out would then fail the
 * binding — a fail-CLOSED that would strand the P0 fix. HTTPS deployments get it.
 *
 * WHY `node:crypto` AND NOT A `===`. String equality short-circuits at the first
 * differing byte; `timingSafeEqual` does not. The value is a 122-bit UUID, so the
 * leak is academic, but the API is one line and the route is on a security path.
 * Length is compared first because `timingSafeEqual` THROWS on unequal lengths —
 * and unequal length is simply "not a match".
 */

/** The one-shot cookie carrying the minted state between the two legs. */
export const VENDOR_LOGOUT_STATE_COOKIE = "jtoye-vendor-logout-state"

/** Only the two vendor-auth routes ever need to see it. */
export const VENDOR_LOGOUT_STATE_COOKIE_PATH = "/api/vendor-auth"

/**
 * Long enough for a vendor to read Keycloak's confirmation page on a slow
 * connection; short enough that a captured value is useless by the time it could
 * be used. The review ceiling was 5 minutes; this is at it, not under it, because
 * the compose realm's `logout` endpoint can show a confirmation prompt.
 */
export const VENDOR_LOGOUT_STATE_TTL_SECONDS = 300

/** A fresh, unguessable state. `crypto.randomUUID()` is the Web Crypto CSPRNG (Node >= 19 global). */
export function mintVendorLogoutState(): string {
  return crypto.randomUUID()
}

/** Is the resolved public origin https, so the cookie may (and must) be `Secure`? */
export function isHttpsOrigin(origin: string | null | undefined): boolean {
  return typeof origin === "string" && origin.startsWith("https:")
}

/** Cookie attributes for SETTING the state on the `logout-url` response. */
export function vendorLogoutStateCookieOptions(secure: boolean): Partial<ResponseCookie> {
  return {
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: VENDOR_LOGOUT_STATE_COOKIE_PATH,
    maxAge: VENDOR_LOGOUT_STATE_TTL_SECONDS,
  }
}

/**
 * Cookie attributes for EXPIRING the state once it has been consumed. Same
 * scope as the set, or the browser treats it as a different cookie and the
 * original survives.
 */
export function vendorLogoutStateClearingOptions(secure: boolean): Partial<ResponseCookie> {
  return { ...vendorLogoutStateCookieOptions(secure), maxAge: 0 }
}

/**
 * Does the `state` Keycloak echoed back match the one this browser was issued?
 *
 * `false` for a missing, empty or differently-sized value on either side; a
 * byte-for-byte constant-time comparison otherwise. Never throws.
 */
export function vendorLogoutStateMatches(
  presented: string | null | undefined,
  expected: string | null | undefined
): boolean {
  if (typeof presented !== "string" || typeof expected !== "string") return false
  if (presented.length === 0 || expected.length === 0) return false
  const a = Buffer.from(presented, "utf8")
  const b = Buffer.from(expected, "utf8")
  if (a.length !== b.length) return false
  return timingSafeEqual(a, b)
}

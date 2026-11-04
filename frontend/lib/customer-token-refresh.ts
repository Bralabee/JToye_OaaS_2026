/**
 * Server-side refresh of the customer session (issue #465).
 *
 * The customer session used to end at exactly `accessTokenLifespan` (300s on the
 * jtoye-customers realm) no matter what the customer was doing: the refresh token
 * was stored HttpOnly for 30 days and nothing ever redeemed it. Measured — six
 * minutes of continuous navigation across /shop did not move the expiry, and the
 * customer was signed out on schedule mid-session, while Keycloak's own SSO
 * session (30 min idle / 2 h max) was still perfectly alive.
 *
 * SERVER ONLY. The refresh token must never reach the browser: it arrives as an
 * HttpOnly cookie, is redeemed here, and the rotated one goes straight back into
 * another HttpOnly cookie.
 */

import { createHash } from "crypto"

interface RefreshedTokens {
  accessToken: string
  refreshToken: string
  idToken: string
  expiresAt: number
}

interface TokenResponse {
  access_token?: string
  refresh_token?: string
  id_token?: string
  expires_in?: number
}

/**
 * Refresh runs inside the frontend container, so it must use the INTERNAL
 * Keycloak URL. The public issuer (localhost:8085) is not routable from here and
 * hangs on a connect timeout before failing — the same trap documented in
 * auth.ts for the operator realm, and in the port-3100 finding before that.
 *
 * Falls back to the public issuer only so a non-containerised `next dev` still
 * works; in the container the INTERNAL value is always set.
 */
function tokenEndpoint(): string | null {
  const base =
    process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL ||
    process.env.CUSTOMER_KEYCLOAK_ISSUER
  if (!base) return null
  return `${base}/protocol/openid-connect/token`
}

function clientId(): string {
  return process.env.CUSTOMER_KEYCLOAK_CLIENT_ID || "storefront-client"
}

/**
 * Single-flight guard, keyed by the refresh token being redeemed.
 *
 * This is not defensive padding — it is required by the realm configuration.
 * `revokeRefreshToken=true` with `refreshTokenMaxReuse=0` means a refresh token
 * is single-use: the second redemption of the same token is rejected AND can
 * revoke the session. StorefrontNav probes the session on mount, focus,
 * visibilitychange, storage events and on a 1s interval for the first 5s after
 * mount, so several probes crossing the expiry boundary together is the normal
 * case, not a rare race. Without this, the fix for #465 would itself log
 * customers out.
 *
 * Scope is one server instance. Across replicas the IdP remains the arbiter —
 * which is why a failed refresh clears the session rather than retrying.
 */
const inFlight = new Map<string, Promise<RefreshedTokens | null>>()

async function redeem(refreshToken: string): Promise<RefreshedTokens | null> {
  const url = tokenEndpoint()
  if (!url) return null

  let res: Response
  try {
    res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: clientId(),
        refresh_token: refreshToken,
      }),
      cache: "no-store",
    })
  } catch {
    // Network/DNS failure to the IdP. Treated as "cannot refresh", never as
    // "session is valid" — failing closed is the only safe direction here.
    return null
  }

  if (!res.ok) return null

  let data: TokenResponse
  try {
    data = (await res.json()) as TokenResponse
  } catch {
    return null
  }

  if (!data.access_token || !data.id_token) return null

  return {
    accessToken: data.access_token,
    // Persist the ROTATED refresh token. Falling back to the incoming one is
    // correct only when the IdP omitted a new one (rotation disabled); on this
    // realm it always sends one, and keeping the old value would guarantee the
    // next refresh is rejected.
    refreshToken: data.refresh_token || refreshToken,
    idToken: data.id_token,
    expiresAt: Math.floor(Date.now() / 1000) + (Number(data.expires_in) || 300),
  }
}

/**
 * Grace memo of SUCCESSFUL redemptions, keyed by the digest of the refresh token that was spent
 * (issue #843, a regression of #465 on a slow network).
 *
 * The in-flight guard above only collapses probes that overlap IN TIME. Under latency the
 * storefront's probes all leave the browser carrying the same cookie but reach the server at
 * different moments: the first redeems and settles, and a probe arriving after that would
 * redeem the already-rotated token, be refused (`invalid_grant`), and make the route clear all
 * three cookies over the ones the first probe had just issued. A late arrival is not a replay
 * attack, it is the same browser one round trip behind, so for a bounded window the answer to
 * "this token was just spent" is the renewed session it was spent for.
 *
 * Bounds, deliberate:
 *   - SUCCESS ONLY. A refusal or a network failure is never remembered, so a transient IdP
 *     fault cannot pin a customer signed out and a genuinely dead token is still reported dead.
 *   - GRACE_MS. A replay of a spent token after the window goes to the IdP and is refused, as
 *     before. The window only has to cover one slow round trip, not a session.
 *   - Keyed by SHA-256 of the spent token, so the map holds no usable credential as a key, and
 *     one customer's renewed tokens can only be returned to a request that presented the exact
 *     token that produced them (the same proof the IdP would have asked for).
 *   - Capped (MAX_ENTRIES, oldest first) so a flood of distinct tokens cannot grow it.
 *
 * Scope is one server instance, exactly like the in-flight guard. Across frontend replicas the
 * IdP stays the arbiter; the client-side single flight in getCustomerSession() is what keeps one
 * browser from sending overlapping probes in the first place.
 */
const GRACE_MS = 60_000
const MAX_ENTRIES = 1000

interface Remembered {
  tokens: RefreshedTokens
  until: number
}

const recentlySpent = new Map<string, Remembered>()

function digest(token: string): string {
  return createHash("sha256").update(token).digest("hex")
}

function sweep(now: number): void {
  for (const [key, entry] of recentlySpent) {
    if (entry.until <= now) recentlySpent.delete(key)
  }
  // Map iterates in insertion order, so the first keys are the oldest.
  while (recentlySpent.size >= MAX_ENTRIES) {
    const oldest = recentlySpent.keys().next().value
    if (oldest === undefined) break
    recentlySpent.delete(oldest)
  }
}

export async function refreshCustomerTokens(
  refreshToken: string
): Promise<RefreshedTokens | null> {
  const key = digest(refreshToken)

  const remembered = recentlySpent.get(key)
  if (remembered && remembered.until > Date.now()) return remembered.tokens

  const existing = inFlight.get(refreshToken)
  if (existing) return existing

  const attempt = redeem(refreshToken)
    .then((tokens) => {
      if (tokens) {
        const now = Date.now()
        sweep(now)
        recentlySpent.set(key, { tokens, until: now + GRACE_MS })
      }
      return tokens
    })
    .finally(() => {
      inFlight.delete(refreshToken)
    })
  inFlight.set(refreshToken, attempt)
  return attempt
}

/** TEST ISOLATION ONLY: drop the grace memo (module state outlives an individual `it`). */
export function __resetRefreshMemoForTests(): void {
  recentlySpent.clear()
  inFlight.clear()
}

export type { RefreshedTokens }

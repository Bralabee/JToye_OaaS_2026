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
 * What a redemption came to. Three outcomes, because the route must treat them differently:
 *
 *   - `renewed`   the IdP issued new tokens;
 *   - `refused`   the IdP answered with a client error (invalid_grant: the token is spent,
 *                 expired or its session was revoked). The session is, as far as the IdP is
 *                 concerned, not renewable with THIS token;
 *   - `transient` the IdP could not be asked or did not answer usefully: a network failure, a
 *                 5xx or 429, an unreadable body. Says nothing about the session.
 *
 * Collapsing the last two (as this module first did) made one IdP blip clear the cookies and sign
 * the customer out for good.
 */
type RefreshOutcome =
  | { kind: "renewed"; tokens: RefreshedTokens }
  | { kind: "refused" }
  | { kind: "transient" }

/**
 * Single-flight guard, keyed by the SHA-256 of the refresh token being redeemed (never the raw
 * credential), and holding a promise only WHILE the redemption is in flight: nothing is retained
 * once it settles, so there is no server-side copy of any token to outlive a sign-out.
 *
 * It is required by the realm configuration. `revokeRefreshToken=true` with
 * `refreshTokenMaxReuse=0` means a refresh token is single-use, and MEASURED on Keycloak 26.8.1
 * (evidence/reuse-detection-measured.txt) the second redemption is refused AND revokes the whole
 * session, so even the successor token dies. Probes that overlap in one process must therefore
 * share one redemption.
 *
 * What this does NOT cover, on purpose: a probe that reaches the server AFTER the first settled,
 * or one that lands on another pod (staging runs up to 10). Both would redeem a spent token. They
 * are prevented where they start, in the browser: `getCustomerSession()` serialises probes across
 * tabs, so a probe never leaves carrying a cookie that an earlier probe is about to replace.
 */
const inFlight = new Map<string, Promise<RefreshOutcome>>()

function digest(token: string): string {
  return createHash("sha256").update(token).digest("hex")
}

async function redeem(refreshToken: string): Promise<RefreshOutcome> {
  const url = tokenEndpoint()
  if (!url) return { kind: "transient" }

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
    // Network/DNS failure to the IdP. Never "session is valid" and never "session is dead".
    return { kind: "transient" }
  }

  if (!res.ok) {
    // A client error (400 invalid_grant and kin) is the IdP saying no to THIS token. Timeouts and
    // rate limits are 4xx too but say nothing about the token, so they are transient.
    const refused = res.status >= 400 && res.status < 500 && res.status !== 408 && res.status !== 429
    return refused ? { kind: "refused" } : { kind: "transient" }
  }

  let data: TokenResponse
  try {
    data = (await res.json()) as TokenResponse
  } catch {
    return { kind: "transient" }
  }

  if (!data.access_token || !data.id_token) return { kind: "transient" }

  return {
    kind: "renewed",
    tokens: {
      accessToken: data.access_token,
      // Persist the ROTATED refresh token. Falling back to the incoming one is correct only when
      // the IdP omitted a new one (rotation disabled); on this realm it always sends one.
      refreshToken: data.refresh_token || refreshToken,
      idToken: data.id_token,
      expiresAt: Math.floor(Date.now() / 1000) + (Number(data.expires_in) || 300),
    },
  }
}

export async function refreshCustomerTokens(refreshToken: string): Promise<RefreshOutcome> {
  const key = digest(refreshToken)
  const existing = inFlight.get(key)
  if (existing) return existing

  const attempt = redeem(refreshToken).finally(() => {
    inFlight.delete(key)
  })
  inFlight.set(key, attempt)
  return attempt
}

export type { RefreshedTokens, RefreshOutcome }

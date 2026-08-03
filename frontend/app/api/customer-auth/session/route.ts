import { NextRequest, NextResponse } from "next/server"
import {
  ACCESS_COOKIE,
  CUSTOMER_COOKIES,
  ID_COOKIE,
  REFRESH_COOKIE,
  REFRESH_MAX_AGE,
  cookieBaseOptions,
} from "@/lib/customer-auth-cookies"
import { refreshCustomerTokens } from "@/lib/customer-token-refresh"

/**
 * GET /api/customer-auth/session
 *
 * Returns the customer session state based on HttpOnly cookies. Never returns
 * the tokens themselves — only profile claims decoded from the ID token and
 * the access-token expiry.
 *
 * The no-session / expired case returns HTTP 200 with `{ authenticated: false }`
 * (NOT 401). This is the expected steady state for every anonymous visitor, and
 * a public component (storefront nav) probes this endpoint on mount for each
 * public page load — a 401 there makes the browser log a failed request to the
 * console on every anonymous page view (backlog #13). A 200 body carries no
 * session data, so consumers read the `authenticated` flag, not the status. The
 * authoritative auth gates remain server-side (dashboard auth() + core-java RLS);
 * this probe is a client-side convenience read only.
 *
 * Issue #465: this endpoint is also where the session is RENEWED. The access
 * cookie's maxAge is the access-token lifetime (300s on this realm), so it simply
 * disappears from the browser at expiry while the refresh and ID cookies remain.
 * Previously that made this handler answer `authenticated: false` and the customer
 * was signed out mid-order, with an unused 30-day refresh token sitting in a
 * cookie. Now the expired case redeems that refresh token and re-issues all three
 * cookies, so the session is bounded by the IdP's own SSO settings rather than by
 * the access-token lifespan.
 */

interface IdTokenClaims {
  sub?: string
  email?: string
  name?: string
  preferred_username?: string
  email_verified?: boolean
  exp?: number
}

function decodeJwtPayload(jwt: string): IdTokenClaims | null {
  try {
    const parts = jwt.split(".")
    if (parts.length !== 3) return null
    // base64url -> base64
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/")
    const pad = b64.length % 4 === 0 ? b64 : b64 + "=".repeat(4 - (b64.length % 4))
    const json = Buffer.from(pad, "base64").toString("utf8")
    return JSON.parse(json) as IdTokenClaims
  } catch {
    return null
  }
}

function profileFrom(claims: IdTokenClaims) {
  return {
    sub: claims.sub ?? "",
    email: claims.email ?? "",
    name: claims.name ?? claims.preferred_username ?? "",
    emailVerified: claims.email_verified ?? false,
  }
}

/** The anonymous / unrecoverable answer. Never 401 — see the note above. */
function unauthenticated() {
  return NextResponse.json({ authenticated: false })
}

/**
 * Clear all three cookies alongside the negative answer. Used when a refresh was
 * attempted and refused: leaving a dead refresh cookie in place would make every
 * subsequent page load retry a redemption the IdP has already rejected.
 */
function unauthenticatedAndCleared() {
  const res = unauthenticated()
  for (const name of CUSTOMER_COOKIES) {
    res.cookies.set(name, "", { ...cookieBaseOptions(), maxAge: 0 })
  }
  return res
}

export async function GET(_req: NextRequest) {
  const access = _req.cookies.get(ACCESS_COOKIE)?.value
  const id = _req.cookies.get(ID_COOKIE)?.value
  const refresh = _req.cookies.get(REFRESH_COOKIE)?.value

  const nowSec = Math.floor(Date.now() / 1000)
  const claims = id ? decodeJwtPayload(id) : null
  const live = Boolean(access && claims && (!claims.exp || claims.exp > nowSec))

  if (live && claims) {
    return NextResponse.json({
      authenticated: true,
      expiresAt: claims.exp ?? null,
      profile: profileFrom(claims),
    })
  }

  // Nothing to renew from: a genuinely anonymous visitor, or a session whose
  // refresh cookie has itself aged out. Answer exactly as before.
  if (!refresh) return unauthenticated()

  const renewed = await refreshCustomerTokens(refresh)
  if (!renewed) return unauthenticatedAndCleared()

  const newClaims = decodeJwtPayload(renewed.idToken)
  if (!newClaims) return unauthenticatedAndCleared()

  const res = NextResponse.json({
    authenticated: true,
    expiresAt: newClaims.exp ?? renewed.expiresAt,
    profile: profileFrom(newClaims),
  })

  const base = cookieBaseOptions()
  res.cookies.set(ACCESS_COOKIE, renewed.accessToken, {
    ...base,
    maxAge: Math.max(0, renewed.expiresAt - Math.floor(Date.now() / 1000)),
  })
  // The rotated refresh token — see customer-token-refresh.ts. Writing the old
  // value back here would make the NEXT refresh fail on this realm.
  res.cookies.set(REFRESH_COOKIE, renewed.refreshToken, {
    ...base,
    maxAge: REFRESH_MAX_AGE,
  })
  res.cookies.set(ID_COOKIE, renewed.idToken, {
    ...base,
    maxAge: REFRESH_MAX_AGE,
  })
  return res
}

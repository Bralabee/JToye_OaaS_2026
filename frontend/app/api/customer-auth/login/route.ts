import { NextRequest, NextResponse } from "next/server"
import {
  ACCESS_COOKIE,
  ID_COOKIE,
  REFRESH_COOKIE,
  REFRESH_MAX_AGE,
  cookieBaseOptions,
} from "@/lib/customer-auth-cookies"

/**
 * POST /api/customer-auth/login
 *
 * Accepts customer OAuth tokens produced by the storefront PKCE flow and stores
 * them in HttpOnly, Secure, SameSite=Lax cookies so that JavaScript cannot read
 * them (XSS mitigation). The browser keeps only a non-sensitive marker in
 * localStorage that indicates "I am logged in" + the expiry timestamp.
 *
 * Request body: { tokens: { accessToken, refreshToken, idToken, expiresAt } }
 * Response:     { ok: true, expiresAt }
 */

interface LoginBody {
  tokens?: {
    accessToken?: string
    refreshToken?: string
    idToken?: string
    expiresAt?: number // unix seconds
  }
}

export async function POST(req: NextRequest) {
  let body: LoginBody
  try {
    body = (await req.json()) as LoginBody
  } catch {
    return NextResponse.json({ error: "invalid_json" }, { status: 400 })
  }

  const tokens = body?.tokens
  if (
    !tokens ||
    !tokens.accessToken ||
    !tokens.refreshToken ||
    !tokens.idToken ||
    !tokens.expiresAt
  ) {
    return NextResponse.json({ error: "missing_tokens" }, { status: 400 })
  }

  const nowSec = Math.floor(Date.now() / 1000)
  const accessMaxAge = Math.max(0, tokens.expiresAt - nowSec)
  // Refresh/ID cookies outlive the short access token on purpose — the refresh
  // token is what carries the session past accessTokenLifespan (#465).
  const refreshMaxAge = REFRESH_MAX_AGE

  const baseOpts = cookieBaseOptions()

  const res = NextResponse.json({ ok: true, expiresAt: tokens.expiresAt })
  res.cookies.set(ACCESS_COOKIE, tokens.accessToken, {
    ...baseOpts,
    maxAge: accessMaxAge,
  })
  res.cookies.set(REFRESH_COOKIE, tokens.refreshToken, {
    ...baseOpts,
    maxAge: refreshMaxAge,
  })
  res.cookies.set(ID_COOKIE, tokens.idToken, {
    ...baseOpts,
    maxAge: refreshMaxAge,
  })
  return res
}

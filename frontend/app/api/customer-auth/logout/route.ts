import { NextRequest, NextResponse } from "next/server"
import {
  CUSTOMER_COOKIES,
  REFRESH_COOKIE,
  cookieBaseOptions,
} from "@/lib/customer-auth-cookies"
import { endCustomerIdpSession } from "@/lib/customer-idp-logout"

/**
 * POST /api/customer-auth/logout
 *
 * Ends the customer's session in BOTH places it exists: at the IdP (back-channel
 * revocation of the refresh token, which terminates the Keycloak SSO session)
 * and in this app (the three HttpOnly cookies set by /api/customer-auth/login).
 *
 * Issue #504 changed this. It previously did the app half only, and said so:
 * "This does NOT perform Keycloak logout — that is initiated client-side with
 * the id_token hint". That left the entire security half of sign-out riding on a
 * front-channel redirect whose `post_logout_redirect_uri` was inferred from the
 * container's bind address. Keycloak refused the URI, errored out BEFORE
 * terminating anything, and the shopper was left signed out of the app while
 * still signed in at the IdP — so the next person to press Sign in on that
 * device was silently signed in as them. Measured: same `sub`, no prompt.
 *
 * The front-channel logout still runs afterwards (lib/customer-auth.ts follows
 * the URL from /api/customer-auth/logout-url) and is still what returns the
 * shopper to the storefront. This call is what makes the SESSION dying no longer
 * depend on that redirect being right.
 *
 * Best-effort and never IdP-blocking: a shopper who pressed Sign out must end up
 * signed out of the app even when Keycloak is unreachable, or an outage recreates
 * the shared-device defect. 200 either way; `idp` reports which happened, as
 * evidence rather than as control flow.
 */

export async function POST(req?: NextRequest) {
  const idp = await endCustomerIdpSession(req?.cookies.get(REFRESH_COOKIE)?.value)

  const res = NextResponse.json({ ok: true, idp })
  for (const name of CUSTOMER_COOKIES) {
    res.cookies.set(name, "", { ...cookieBaseOptions(), maxAge: 0 })
  }
  return res
}

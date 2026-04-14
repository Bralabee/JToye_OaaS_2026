import { NextRequest, NextResponse } from "next/server"

/**
 * GET /api/customer-auth/logout-url?redirect=/shop
 *
 * Builds the Keycloak end-session URL server-side using the id_token cookie
 * so the raw id token never reaches the browser. The caller follows the
 * returned URL to complete the Keycloak logout.
 */

const ID_COOKIE = "jtoye-customer-id"

const KC_BASE =
  process.env.NEXT_PUBLIC_KEYCLOAK_URL || "http://localhost:8085/realms/jtoye-dev"

export async function GET(req: NextRequest) {
  const id = req.cookies.get(ID_COOKIE)?.value
  const redirect = req.nextUrl.searchParams.get("redirect") || "/shop"
  const origin = req.nextUrl.origin
  const postLogoutRedirectUri = `${origin}${redirect.startsWith("/") ? redirect : `/${redirect}`}`

  if (!id) {
    // No session — just bounce back to the redirect target
    return NextResponse.json({ url: postLogoutRedirectUri })
  }

  const params = new URLSearchParams({
    id_token_hint: id,
    post_logout_redirect_uri: postLogoutRedirectUri,
  })
  return NextResponse.json({
    url: `${KC_BASE}/protocol/openid-connect/logout?${params.toString()}`,
  })
}

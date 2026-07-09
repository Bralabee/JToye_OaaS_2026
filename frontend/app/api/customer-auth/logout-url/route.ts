import { NextRequest, NextResponse } from "next/server"

/**
 * GET /api/customer-auth/logout-url?redirect=/shop
 *
 * Builds the Keycloak end-session URL server-side using the id_token cookie
 * so the raw id token never reaches the browser. The caller follows the
 * returned URL to complete the Keycloak logout.
 */

const ID_COOKIE = "jtoye-customer-id"

// Phase 18: customer logout targets the jtoye-customers realm end-session endpoint.
// Use the dedicated customer base URL, falling back ONLY to the jtoye-customers dev
// default — never to NEXT_PUBLIC_KEYCLOAK_URL (the staff/vendor realm), which would
// route customer logout into the wrong identity pool. Admin logout (NextAuth) unaffected.
const KC_BASE =
  process.env.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL ||
  "http://localhost:8085/realms/jtoye-customers" // never fall back to jtoye-dev (staff realm)

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

import { NextRequest, NextResponse } from "next/server"
import { resolvePublicOrigin } from "@/lib/public-origin"

/**
 * GET /api/customer-auth/logout-url?redirect=/shop
 *
 * Builds the Keycloak end-session URL server-side using the id_token cookie
 * so the raw id token never reaches the browser. The caller follows the
 * returned URL to complete the Keycloak logout.
 */

const ID_COOKIE = "jtoye-customer-id"

/**
 * WR-04 — this body embeds the caller's raw id token, so nothing may store it.
 *
 * The handler previously set no cache headers at all. Correctness rested on a
 * framework default plus every intermediary inferring "do not share this" from
 * a URL with no user-varying component; a shared cache keyed on path alone
 * would serve one customer's id_token to the next. `Vary: Cookie` states the
 * real dependency. Applied to BOTH exit branches so they cannot drift, and
 * mirrored verbatim in the vendor sibling — the two routes share this gap and
 * fixing one alone is how the pair starts to diverge.
 */
const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  Vary: "Cookie",
} as const

// Phase 18: customer logout targets the jtoye-customers realm end-session endpoint.
// Use the dedicated customer base URL, falling back ONLY to the jtoye-customers dev
// default — never to NEXT_PUBLIC_KEYCLOAK_URL (the staff/vendor realm), which would
// route customer logout into the wrong identity pool. Admin logout (NextAuth) unaffected.
//
// Issue #467 audit: this `localhost` is CORRECT and must stay public, unlike the
// one that broke /api/customer-orders. This handler never fetches the URL — it
// returns it in JSON for the BROWSER to navigate to, and the browser is outside
// the container where localhost:8085 is exactly right. Swapping in an internal
// host (keycloak:8080, as customer-token-refresh.ts correctly uses for its
// server-side token call) would produce a URL no browser can resolve. The
// NEXT_PUBLIC_ prefix marks the same distinction: this is the public view.
const KC_BASE =
  process.env.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL ||
  "http://localhost:8085/realms/jtoye-customers" // never fall back to jtoye-dev (staff realm)

/**
 * Restrict the post-logout redirect to a same-origin storefront path. The value
 * is user-controlled; only accept a relative path beginning with a single "/"
 * (reject protocol-relative "//host", backslash tricks "/\\host", and absolute
 * URLs) so the returned URL can never escape this origin. Falls back to "/shop".
 */
function sanitizeRedirect(raw: string | null): string {
  const fallback = "/shop"
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) {
    return fallback
  }
  return raw
}

export async function GET(req: NextRequest) {
  const id = req.cookies.get(ID_COOKIE)?.value
  const redirect = sanitizeRedirect(req.nextUrl.searchParams.get("redirect"))

  // Issue #504: the origin is INJECTED, not read off the request. `nextUrl.origin`
  // is the server's BIND address inside a container — measured
  // `http://0.0.0.0:3000`, unmoved by the Host header — and Keycloak refused the
  // `post_logout_redirect_uri` built from it. `resolvePublicOrigin` returns null
  // rather than guessing; see the degraded branch below.
  const origin = resolvePublicOrigin(req)
  const postLogoutRedirectUri = origin ? `${origin}${redirect}` : null

  if (!id) {
    // No session — just bounce back to the redirect target. With no trustworthy
    // origin the RELATIVE path is strictly safer and equally correct: the
    // browser resolves it against the page it is already on, which is this app.
    return NextResponse.json(
      { url: postLogoutRedirectUri ?? redirect },
      { headers: NO_STORE_HEADERS }
    )
  }

  const params = new URLSearchParams({ id_token_hint: id })
  if (postLogoutRedirectUri) {
    params.set("post_logout_redirect_uri", postLogoutRedirectUri)
  }
  // No origin => NO post_logout_redirect_uri, deliberately. Measured against the
  // live realm: `logout?id_token_hint=…` with no redirect uri TERMINATES the
  // session and renders Keycloak's own "You are logged out" page, whereas an
  // unregistered redirect uri errors WITHOUT terminating anything. Losing the
  // return journey is a cosmetic degradation; losing the sign-out is the
  // security defect. Never trade the second away to keep the first.
  return NextResponse.json(
    { url: `${KC_BASE}/protocol/openid-connect/logout?${params.toString()}` },
    { headers: NO_STORE_HEADERS }
  )
}

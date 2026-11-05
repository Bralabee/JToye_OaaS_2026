import { NextResponse } from "next/server"
import { auth } from "@/auth"
import { buildCsp } from "@/lib/security-headers"
import {
  SHOP_SLUG_CHECKED_PARAM,
  checkShopSlug,
  fetchKitchenNotFoundPage,
  isDocumentRequest,
  shopSlugOf,
} from "@/lib/shop-slug-gate"

// Per-request Content-Security-Policy with a nonce (issue #89 P1-7 / SEC-02).
//
// CSP moved here from next.config.mjs `headers()` because a nonce must be fresh
// per response — a static header cannot drop `script-src 'unsafe-inline'` without
// breaking Next's inline bootstrap scripts. Following Next's canonical CSP recipe:
// we set the nonce on the REQUEST headers (both `x-nonce` and the CSP header) so
// Next stamps it onto its own <script>/preload tags, and set the CSP on the
// RESPONSE so the browser enforces it.
//
// Wrapped in NextAuth's `auth` so the existing session resolution still runs
// (the /dashboard route is additionally gated server-side in its layout — there
// is no `authorized` callback, so the broadened matcher below cannot gate public
// routes). Enforcing by default; set CSP_REPORT_ONLY=true to observe first.
export default auth(async (req) => {
  const nonce = btoa(crypto.randomUUID())

  const csp = buildCsp({
    nonce,
    isDev: process.env.NODE_ENV !== "production",
    keycloakOrigin: process.env.NEXT_PUBLIC_KEYCLOAK_URL || "",
    // The CUSTOMER realm is a different identity pool (#382) and a different CSP
    // source. Without it the customer token exchange is blocked and sign-in dies
    // as "Authentication failed" AFTER the Keycloak user has been created.
    customerKeycloakOrigin: process.env.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL || "",
    apiOrigin: process.env.NEXT_PUBLIC_API_URL || "",
    upgradeInsecure: process.env.CSP_UPGRADE_INSECURE_REQUESTS === "true",
  })

  const reportOnly = process.env.CSP_REPORT_ONLY === "true"

  // Forward the nonce + CSP on the request so Next nonces its framework scripts.
  const requestHeaders = new Headers(req.headers)
  requestHeaders.set("x-nonce", nonce)
  requestHeaders.set("Content-Security-Policy", csp)

  // SW-CLR-17: an unknown kitchen gets a real 404 and a retired slug a real 301. Decided here
  // because a streamed page has already sent 200 by the time it can call notFound() (see
  // lib/shop-slug-gate.ts). Anything but core's own answer passes through unchanged.
  // Document requests only (review R1): never a prefetch or an RSC navigation.
  const slug =
    isDocumentRequest(req.headers, req.nextUrl.searchParams) &&
    !req.nextUrl.searchParams.has(SHOP_SLUG_CHECKED_PARAM)
      ? shopSlugOf(req.nextUrl.pathname)
      : null
  const verdict = slug ? await checkShopSlug(slug, req.headers) : { kind: "pass" as const }
  if (verdict.kind === "redirect") {
    const target = new URL(`/shop/${encodeURIComponent(verdict.canonicalSlug)}`, req.nextUrl)
    target.search = req.nextUrl.search
    return NextResponse.redirect(target, 301)
  }

  // An unknown kitchen is answered here with the page body and status 404 (see fetchKitchenNotFoundPage
  // for why this is not a rewrite). The second request is its own pass through this proxy, so the CSP
  // it carries is the one its HTML was rendered with; null means "could not get it", and the request
  // passes to the page unchanged.
  if (verdict.kind === "notfound") {
    const missing = await fetchKitchenNotFoundPage(req.nextUrl, req.headers)
    if (missing) return missing
  }

  const response = NextResponse.next({ request: { headers: requestHeaders } })
  response.headers.set(
    reportOnly ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy",
    csp,
  )
  return response
})

export const config = {
  // All routes except API handlers, Next internals, the favicon, and static
  // assets (which need no CSP and would only add per-file middleware overhead).
  matcher: [
    "/((?!api|_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp|ico|css|js|woff2?|ttf|map)$).*)",
  ],
}

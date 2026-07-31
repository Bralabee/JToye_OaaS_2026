import { NextResponse } from "next/server"
import { auth } from "@/auth"
import { buildCsp } from "@/lib/security-headers"

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
export default auth((req) => {
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

import { NextRequest, NextResponse } from "next/server"

/**
 * GET /api/customer-orders?page=&size=
 *
 * Server-side proxy for the logged-in customer's order history (issue #179
 * defect 1). The "My Orders" page holds only the customer session's email, so
 * it cannot satisfy the AUDIT-W0-02 `verify` proof (a recent order number)
 * that GET /public/orders?email=&verify= demands. Instead, this route reads
 * the customer's Keycloak access token from its HttpOnly cookie (set by
 * /api/customer-auth/login — never readable by browser JS) and forwards it to
 * the core API's session-authenticated variant, GET /public/orders/mine, on
 * the X-Customer-Token header. Core verifies the token cryptographically
 * (signature/issuer/expiry/email_verified) and lists orders for EXACTLY the
 * token's email claim.
 *
 * Security invariants:
 *  - No email parameter exists on this surface or the upstream one — the
 *    caller can never choose whose orders to list, so the enumeration/IDOR
 *    protection on the public endpoint is not weakened.
 *  - The access token never reaches browser JS: HttpOnly cookie in, custom
 *    header out, both server-side only.
 *  - Only pagination params are forwarded, and only when strictly numeric.
 */

const ACCESS_COOKIE = "jtoye-customer-access"

// Server-side base URL for the core API. In the compose stack the frontend
// container maps localhost to the host gateway (extra_hosts), so the browser
// bake NEXT_PUBLIC_API_URL (http://localhost:9090) also resolves in-container.
// Deployments without that mapping can point CORE_API_INTERNAL_URL at the
// in-network core host instead.
function coreBaseUrl(): string {
  return (
    process.env.CORE_API_INTERNAL_URL ||
    process.env.NEXT_PUBLIC_API_URL ||
    "http://localhost:9090"
  )
}

export async function GET(req: NextRequest) {
  const access = req.cookies.get(ACCESS_COOKIE)?.value
  if (!access) {
    return NextResponse.json({ error: "not_authenticated" }, { status: 401 })
  }

  // Forward pagination only — strictly numeric values, nothing else.
  const params = new URLSearchParams()
  for (const key of ["page", "size"] as const) {
    const value = req.nextUrl.searchParams.get(key)
    if (value && /^\d+$/.test(value)) params.set(key, value)
  }
  const qs = params.toString()

  try {
    const upstream = await fetch(
      `${coreBaseUrl()}/public/orders/mine${qs ? `?${qs}` : ""}`,
      {
        headers: { "X-Customer-Token": access },
        cache: "no-store",
      }
    )
    const body = await upstream.json().catch(() => ({}))
    return NextResponse.json(body, { status: upstream.status })
  } catch {
    return NextResponse.json({ error: "upstream_unavailable" }, { status: 502 })
  }
}

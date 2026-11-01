import { NextRequest, NextResponse } from "next/server"
import { coreBaseUrl } from "@/lib/customer-orders-server"

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

// Issue #467: `coreBaseUrl` now lives in lib/customer-orders-server.ts and is
// shared with the server component that renders /shop/orders, so there is ONE
// definition of how this container reaches core rather than one per caller.
// The comment that used to sit here was wrong in a way that cost this route
// every request it ever made: it claimed the compose `extra_hosts` mapping made
// `localhost` resolve in-container. It does not — the container's own
// /etc/hosts maps `127.0.0.1 localhost` first and wins — so this route 502'd on
// the compose stack from the day it shipped. See the shared function for the
// measurements.

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
    // SW-PRY-06 (T-31.1-46): the history carries the customer's own allergy note, so neither the
    // browser nor any proxy may keep a copy beyond the request.
    return NextResponse.json(body, {
      status: upstream.status,
      headers: { "Cache-Control": "no-store" },
    })
  } catch {
    return NextResponse.json({ error: "upstream_unavailable" }, { status: 502 })
  }
}

/**
 * POST /api/customer-orders?shop={slug}
 *
 * 37-30 (37-27 D-18, T-37-76): places a signed-in customer's storefront order through the same
 * server-side seam as the GET above, so core receives the customer's access token on
 * X-Customer-Token (from the HttpOnly cookie, never from browser JS) and records the contact as
 * VERIFIED when the token's proven email matches the order's. Without the cookie the order is
 * still placed — as a guest, no token — so a session that lapsed between page load and submit
 * degrades to exactly what a guest order is, never to a dead checkout.
 *
 * Forwarded, and nothing else: the JSON body as received, the Idempotency-Key header (the
 * platform contract the checkout speaks), and the chain in X-Forwarded-For if one reached this
 * server — core reads it only from a peer declared in jtoye.security.trusted-proxies
 * (TRUSTED_PROXY_CIDRS), otherwise the socket peer (this container) is the address its IP axis
 * digests. Status, body and Retry-After pass through unchanged so the checkout's typed-error
 * handling sees exactly what core said.
 */
const SHOP_SLUG = /^[a-z0-9](?:[a-z0-9-]{0,118}[a-z0-9])?$/

export async function POST(req: NextRequest) {
  const shop = req.nextUrl.searchParams.get("shop") ?? ""
  if (!SHOP_SLUG.test(shop)) {
    return NextResponse.json({ error: "invalid_shop" }, { status: 400 })
  }
  let body: unknown
  try {
    body = await req.json()
  } catch {
    return NextResponse.json({ error: "invalid_body" }, { status: 400 })
  }

  const headers: Record<string, string> = { "Content-Type": "application/json" }
  const access = req.cookies.get(ACCESS_COOKIE)?.value
  if (access) headers["X-Customer-Token"] = access
  const idempotencyKey = req.headers.get("idempotency-key")
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey
  const forwardedFor = req.headers.get("x-forwarded-for")
  if (forwardedFor) headers["X-Forwarded-For"] = forwardedFor

  try {
    const upstream = await fetch(`${coreBaseUrl()}/public/shops/${shop}/orders`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    })
    const data = await upstream.json().catch(() => ({}))
    const res = NextResponse.json(data, { status: upstream.status })
    const retryAfter = upstream.headers?.get?.("Retry-After")
    if (retryAfter) res.headers.set("Retry-After", retryAfter)
    return res
  } catch {
    return NextResponse.json({ error: "upstream_unavailable" }, { status: 502 })
  }
}

import { NextRequest, NextResponse } from "next/server"
import { coreBaseUrl } from "@/lib/customer-orders-server"
import { forwardedForHeader } from "@/lib/forwarded-for"

/**
 * POST /api/customer-orders/review?shop={slug}&order={orderNumber}
 *
 * The server-side proxy behind "Rate your order" (#846, #813). The signed-in customer's access token is
 * an HttpOnly cookie that browser JS cannot read; this route reads it and forwards it to core as
 * `X-Customer-Token`, exactly as /api/customer-orders does for the order history. Core then learns WHO is
 * reviewing from the verified token alone (`CustomerJwtVerifier`: signature, issuer, expiry,
 * email_verified), so no email travels anywhere: not in this URL, not in the upstream URL (which has no
 * query string), not in the body.
 *
 * Security invariants (the contract test is route.test.ts beside this file):
 *  - no cookie -> 401 and core is never called;
 *  - `shop` and `order` come from the query string and are matched against a strict shape BEFORE they are
 *    placed in the upstream PATH, so neither can add a segment or a query to core's routing;
 *  - only `foodRating`, `deliveryRating` and `comment` are taken from the body; an `email`, an `orderId` or
 *    anything else the client sends is dropped;
 *  - status and body pass through unchanged, so the form sees core's typed refusal (400 already reviewed,
 *    404 not your order, 401 session lapsed) as core said it; the visitor's X-Forwarded-For chain is
 *    forwarded verbatim (quick 261102-vwa) so core's limiter buckets per visitor;
 *  - a failure to reach core is a 502, never an unhandled rejection.
 *
 * No Idempotency-Key: a review is one per order (UNIQUE(order_id), V27), so a replay is refused, not
 * duplicated; the endpoint is idempotent in effect.
 */
const ACCESS_COOKIE = "jtoye-customer-access"
const SHOP_SLUG = /^[a-z0-9](?:[a-z0-9-]{0,118}[a-z0-9])?$/
const ORDER_NUMBER = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/

export async function POST(req: NextRequest) {
  const access = req.cookies.get(ACCESS_COOKIE)?.value
  if (!access) {
    return NextResponse.json({ error: "not_authenticated" }, { status: 401 })
  }
  const shop = req.nextUrl.searchParams.get("shop") ?? ""
  const order = req.nextUrl.searchParams.get("order") ?? ""
  if (!SHOP_SLUG.test(shop) || !ORDER_NUMBER.test(order)) {
    return NextResponse.json({ error: "invalid_order" }, { status: 400 })
  }
  let body: Record<string, unknown>
  try {
    const parsed: unknown = await req.json()
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object")
    body = parsed as Record<string, unknown>
  } catch {
    return NextResponse.json({ error: "invalid_body" }, { status: 400 })
  }

  const forwarded: Record<string, unknown> = {}
  for (const key of ["foodRating", "deliveryRating", "comment"] as const) {
    if (body[key] !== undefined) forwarded[key] = body[key]
  }

  try {
    const upstream = await fetch(`${coreBaseUrl()}/public/shops/${shop}/orders/${order}/review`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Customer-Token": access, ...forwardedForHeader(req.headers) },
      body: JSON.stringify(forwarded),
      cache: "no-store",
    })
    const data = await upstream.json().catch(() => ({}))
    return NextResponse.json(data, { status: upstream.status, headers: { "Cache-Control": "no-store" } })
  } catch {
    return NextResponse.json({ error: "upstream_unavailable" }, { status: 502 })
  }
}

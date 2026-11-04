import { NextRequest, NextResponse } from "next/server"
import { coreBaseUrl } from "@/lib/customer-orders-server"
import { forwardedForHeader } from "@/lib/forwarded-for"

/**
 * GET /api/customer-orders/reorder?shop={slug}&order={orderNumber}
 *
 * The server-side proxy behind "Order again" (#845): the lines of one of the signed-in customer's own past
 * orders, so the basket can be refilled. Same shape and the same invariants as the review proxy beside it
 * (route.test.ts): the HttpOnly access cookie is forwarded as `X-Customer-Token` and core decides whose
 * order it is from the verified token; there is no email on this surface or the upstream one; `shop` and
 * `order` are matched against a strict shape before they enter the upstream path; no cookie is a 401 with
 * no upstream call; core's status passes through (another customer's order is core's 404); a failure to
 * reach core is a 502. The answer carries what the customer bought (product names and quantities), so it is
 * `no-store`.
 */
const ACCESS_COOKIE = "jtoye-customer-access"
const SHOP_SLUG = /^[a-z0-9](?:[a-z0-9-]{0,118}[a-z0-9])?$/
const ORDER_NUMBER = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/

export async function GET(req: NextRequest) {
  const access = req.cookies.get(ACCESS_COOKIE)?.value
  if (!access) {
    return NextResponse.json({ error: "not_authenticated" }, { status: 401 })
  }
  const shop = req.nextUrl.searchParams.get("shop") ?? ""
  const order = req.nextUrl.searchParams.get("order") ?? ""
  if (!SHOP_SLUG.test(shop) || !ORDER_NUMBER.test(order)) {
    return NextResponse.json({ error: "invalid_order" }, { status: 400 })
  }
  try {
    const upstream = await fetch(`${coreBaseUrl()}/public/shops/${shop}/orders/${order}/reorder`, {
      headers: { "X-Customer-Token": access, ...forwardedForHeader(req.headers) },
      cache: "no-store",
    })
    const data = await upstream.json().catch(() => ({}))
    return NextResponse.json(data, { status: upstream.status, headers: { "Cache-Control": "no-store" } })
  } catch {
    return NextResponse.json({ error: "upstream_unavailable" }, { status: 502 })
  }
}

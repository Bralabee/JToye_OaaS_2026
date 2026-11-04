import { NextRequest, NextResponse } from "next/server"
import { coreBaseUrl } from "@/lib/customer-orders-server"
import { forwardedForHeader } from "@/lib/forwarded-for"

/**
 * POST /api/customer-orders/{orderNumber}/cancel
 *
 * #868 (owner ruling 2026-11-04, persona close-out): a customer cancels their own PENDING order. The
 * same server-side seam as ../route.ts: a signed-in customer's access token lives in an HttpOnly
 * cookie browser JS cannot read, so it is read here and sent to core on X-Customer-Token, and core
 * takes the order's owner from the VERIFIED token and nothing else. A guest sends the email the
 * order was placed with in the body (the tracking proof) and no cookie.
 *
 * Forwarded, and nothing else: the JSON body, the Idempotency-Key header (so a retried tap never
 * refunds twice), and the visitor's X-Forwarded-For chain through the shared `lib/forwarded-for.ts`.
 * Status and body pass through unchanged, so the page sees core's typed errors (409
 * order-not-cancellable-by-customer, 502 refund-failed) exactly as core sent them.
 *
 * One deliberate degradation, mirroring the order POST in ../route.ts: a session that lapsed between
 * page load and tap makes core answer 401 for the stale token. If the caller also sent the guest
 * proof (an email), the request is retried ONCE as a guest, without the token; the email is itself the
 * proof of ownership, so this widens nothing. With no email the 401 stands.
 *
 * Not an MCP tool: it is the customer's own action, and the MCP server acts for a vendor.
 */
const ACCESS_COOKIE = "jtoye-customer-access"
const ORDER_NUMBER = /^[A-Za-z0-9-]{3,64}$/

export async function POST(
  req: NextRequest,
  ctx: { params: Promise<{ orderNumber: string }> }
) {
  const { orderNumber } = await ctx.params
  if (!ORDER_NUMBER.test(orderNumber)) {
    return NextResponse.json({ error: "invalid_order_number" }, { status: 400 })
  }
  let body: { email?: unknown } = {}
  try {
    const parsed = await req.json()
    if (parsed && typeof parsed === "object") body = parsed as { email?: unknown }
  } catch {
    body = {}
  }
  const email = typeof body.email === "string" && body.email.trim() ? body.email.trim() : undefined

  const base: Record<string, string> = { "Content-Type": "application/json" }
  const idempotencyKey = req.headers.get("idempotency-key")
  if (idempotencyKey) base["Idempotency-Key"] = idempotencyKey
  Object.assign(base, forwardedForHeader(req.headers))

  const url = `${coreBaseUrl()}/public/orders/${encodeURIComponent(orderNumber)}/cancel`
  const send = (token: string | undefined) =>
    fetch(url, {
      method: "POST",
      headers: token ? { ...base, "X-Customer-Token": token } : base,
      body: JSON.stringify(email ? { email } : {}),
      cache: "no-store",
    })

  try {
    const access = req.cookies.get(ACCESS_COOKIE)?.value
    let upstream = await send(access)
    if (upstream.status === 401 && access && email) {
      upstream = await send(undefined)
    }
    const data = await upstream.json().catch(() => ({}))
    return NextResponse.json(data, {
      status: upstream.status,
      headers: { "Cache-Control": "no-store" },
    })
  } catch {
    return NextResponse.json({ error: "upstream_unavailable" }, { status: 502 })
  }
}

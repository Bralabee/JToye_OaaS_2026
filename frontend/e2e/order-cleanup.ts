/**
 * Order hygiene for specs that place REAL cash orders against the running stack (37-31, the
 * sub-theme 37-D gate).
 *
 * WHY THIS EXISTS. 37-27 and 37-28 bound open cash orders per shop: 3 per email, 3 per phone and 5
 * per connection (the client's IP digest). Every Playwright spec runs from ONE address (the Docker
 * gateway) and, until the 37-D gate, typed the SAME contact details on every run and left every
 * order it placed PENDING for ever. Measured at the gate on the rebuilt stack: `cartflow@test.com`
 * had 18 open cash orders at Mama Ade's, `race-785@test.com` and `cash-793@test.com` 8 each — so
 * the first order of each of those specs was refused 429 order-cap-exceeded (axis EMAIL), and the
 * second project's runs tripped the IP axis on the orders the first project had left behind. The
 * caps were doing exactly what they are for; the specs were behaving like the abuse they bound.
 *
 * Two rules, both here so every ordering spec shares them:
 *   1. `uniqueContact()` — a per-run email AND phone (the phone axis is a cap of its own), so a
 *      spec's identity never accumulates across runs. Never raise a cap default to make a spec pass
 *      (RESEARCH Pitfall 9; 37-27's own ITs were fixed the same way).
 *   2. `cancelOrdersByNumber()` — a spec cancels what it placed, through the vendor's own cancel,
 *      so its orders stop counting (a CANCELLED order holds no slot and its digest is nulled). The
 *      public confirmation carries only an orderNumber (no id) and the public shop DTO carries no
 *      id either, so both are resolved through the vendor API: the shop by slug from
 *      GET /api/v1/shops, the order by number from GET /api/v1/orders/shop/{shopId}.
 *
 * Without a vendor password (E2E_VENDOR_PASSWORD or KC_SEED_USER_PASSWORD) and the core-api client
 * secret (KEYCLOAK_CLIENT_SECRET) the cleanup is a recorded no-op: it logs once and returns, and the
 * orders stay for the next run to trip over — which is why CI sources the stack's .env.
 */
import type { APIRequestContext } from "@playwright/test"
import { VENDOR_PASSWORD, VENDOR_USERNAME } from "./vendor-credentials"

const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const REALM = process.env.E2E_VENDOR_REALM || "jtoye-dev"
const CLIENT_ID = process.env.E2E_VENDOR_CLIENT_ID || "core-api"
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET ?? ""

let warnedOnce = false

/** A per-run email and UK mobile number, unique across runs AND within one (the counter). */
let contactSeq = 0
export function uniqueContact(tag: string): { email: string; phone: string } {
  contactSeq += 1
  const stamp = `${Date.now()}${String(contactSeq).padStart(2, "0")}`
  // "07700 9xxxxx" is Ofcom's drama range; the last six digits come from the stamp.
  return { email: `${tag}-${stamp}@e2e.jtoye.test`, phone: `07700 9${stamp.slice(-5)}` }
}

/** A vendor JWT via the password grant — only ever used to CANCEL what a spec placed. */
export async function vendorToken(request: APIRequestContext): Promise<string | null> {
  if (!VENDOR_PASSWORD || !CLIENT_SECRET) {
    if (!warnedOnce) {
      warnedOnce = true
      console.warn(
        "[order-cleanup] no vendor password / client secret — placed orders are NOT cancelled " +
          "(set E2E_VENDOR_PASSWORD or source the stack's .env)",
      )
    }
    return null
  }
  const res = await request.post(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`, {
    form: {
      grant_type: "password",
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      username: VENDOR_USERNAME,
      password: VENDOR_PASSWORD,
    },
  })
  if (!res.ok()) return null
  return (await res.json()).access_token as string
}

/** The vendor's shop id for a public slug (the public shop DTO deliberately carries no id). */
export async function vendorShopId(
  request: APIRequestContext,
  token: string,
  slug: string,
): Promise<string | null> {
  const res = await request.get(`${API}/api/v1/shops?size=100`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok()) return null
  const body = (await res.json()) as { content?: Array<{ id: string; slug: string }> } | Array<{ id: string; slug: string }>
  const list = Array.isArray(body) ? body : (body.content ?? [])
  return list.find((s) => s.slug === slug)?.id ?? null
}

/**
 * Cancel, as the vendor, every order in `orderNumbers` placed at `slug`. Each id goes through
 * POST /api/v1/orders/{id}/cancel, which cancels a PENDING order and an accepted one alike; a
 * terminal order answers 400 and is left as it is. Returns the number of 200s.
 */
export async function cancelOrdersByNumber(
  request: APIRequestContext,
  slug: string,
  orderNumbers: string[],
): Promise<number> {
  const numbers = orderNumbers.filter((n) => typeof n === "string" && n.startsWith("ORD-"))
  if (numbers.length === 0) return 0
  const token = await vendorToken(request)
  if (!token) return 0
  const shopId = await vendorShopId(request, token, slug)
  if (!shopId) return 0
  const listRes = await request.get(
    `${API}/api/v1/orders/shop/${shopId}?size=100&sort=createdAt,desc`,
    { headers: { Authorization: `Bearer ${token}` } },
  )
  if (!listRes.ok()) return 0
  const page = (await listRes.json()) as { content?: Array<{ id: string; orderNumber: string }> }
  const wanted = new Set(numbers)
  let cancelled = 0
  for (const o of page.content ?? []) {
    if (!wanted.has(o.orderNumber)) continue
    const res = await request
      .post(`${API}/api/v1/orders/${o.id}/cancel`, { headers: { Authorization: `Bearer ${token}` } })
      .catch(() => null)
    if (res && res.ok()) cancelled += 1
  }
  return cancelled
}

/** The "ORD-…" number a confirmation page shows ("Order ORD-00000000-20261012-ABCDEF01"). */
export function orderNumberIn(text: string | null | undefined): string | null {
  const m = (text ?? "").match(/ORD-[0-9A-Z-]+/)
  return m ? m[0] : null
}

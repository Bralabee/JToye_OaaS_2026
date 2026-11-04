import { coreBaseUrl } from "@/lib/customer-orders-server"
import { forwardedForHeader, type HeaderSource } from "@/lib/forwarded-for"

/**
 * SW-CLR-17 (swarm epic #964, reconfirms UXT-116): an unknown kitchen answers a real HTTP 404.
 *
 * WHY THIS RUNS IN THE PROXY (middleware.ts) AND NOT IN THE PAGE. `/shop/[slug]` already calls
 * `notFound()`, but every page is streamed (`app/layout.tsx` is force-dynamic for the CSP nonce) and
 * sits under three `loading.tsx` Suspense boundaries (`app/`, `app/shop/`, `app/shop/[slug]/`). The
 * fallback is flushed first, which commits the status line to 200 before the page body runs.
 * Measured on next dev 2026-11-03: 200 with all three boundaries present, 200 with any one of them
 * present, 404 only with all three removed. Removing them would take the loading skeleton away from
 * every route. Next's own docs (`loading.md`, "Status codes") give the supported answer: check the
 * slug in Proxy, before the body streams.
 *
 * ONE READ, THE SAME ONE THE PAGE MAKES: `GET /public/shops/{slug}`, with the visitor's
 * X-Forwarded-For (quick 261102-vwa), so core's public limiter still buckets per visitor.
 *
 * FAILS OPEN. Only core's own 404 is a verdict. A timeout, a 5xx, a 429, a network error or a 301
 * without a target all return `pass`, and the page renders exactly as it did before this change,
 * which already shows "Kitchen not found" with noindex. A slow or broken core must never make a
 * live kitchen answer 404.
 *
 * A retired slug (V86, 301 from core) is redirected here too, as a real 301: under streaming the
 * page's `permanentRedirect` can only become a client-side redirect.
 */

/** First path segments under /shop/ that are routes of their own, not kitchens. */
export const RESERVED_SHOP_SEGMENTS: ReadonlySet<string> = new Set([
  "account",
  "auth",
  "orders",
  "signin",
])

/**
 * PR #1111 review round 1: only a full DOCUMENT request is checked. Next sends `RSC: 1` on a client
 * navigation and `Next-Router-Prefetch: 1` on a <Link> prefetch (measured: viewing /shop alone sent
 * 9 prefetches of /shop/{slug} through the proxy). Checking those spent the visitor's public rate
 * limit (30/min + 10) on kitchens they never opened, and a status code on an RSC payload helps no
 * one: the HTTP status matters to a crawler or a direct visit, which is a document request. A client
 * navigation to a missing kitchen still renders "Kitchen not found" from the page's own notFound().
 */
export function isDocumentRequest(
  requestHeaders: HeaderSource,
  searchParams: { has(name: string): boolean }
): boolean {
  return (
    !requestHeaders.get("rsc") &&
    !requestHeaders.get("next-router-prefetch") &&
    !searchParams.has("_rsc")
  )
}

/** How long the proxy waits for core before it lets the page decide. */
export const SHOP_SLUG_GATE_TIMEOUT_MS = 1500

export type ShopSlugVerdict =
  | { kind: "pass" }
  | { kind: "notfound" }
  | { kind: "redirect"; canonicalSlug: string }

/**
 * The kitchen slug of a `/shop/{slug}` page request (the shop page itself, not its basket,
 * checkout or order pages), or null for anything else.
 */
export function shopSlugOf(pathname: string): string | null {
  const m = /^\/shop\/([^/]+)\/?$/.exec(pathname)
  if (!m) return null
  let slug: string
  try {
    slug = decodeURIComponent(m[1])
  } catch {
    return null
  }
  if (!slug || RESERVED_SHOP_SEGMENTS.has(slug)) return null
  return slug
}

export async function checkShopSlug(
  slug: string,
  requestHeaders: HeaderSource,
  fetchImpl: typeof fetch = fetch,
  timeoutMs: number = SHOP_SLUG_GATE_TIMEOUT_MS
): Promise<ShopSlugVerdict> {
  try {
    const res = await fetchImpl(
      `${coreBaseUrl()}/public/shops/${encodeURIComponent(slug)}`,
      {
        cache: "no-store",
        redirect: "manual",
        headers: forwardedForHeader(requestHeaders),
        signal: AbortSignal.timeout(timeoutMs),
      }
    )
    if (res.status === 404) return { kind: "notfound" }
    if (res.status === 301) {
      const canonical =
        res.headers.get("x-shop-canonical-slug")?.trim() ||
        res.headers.get("location")?.split("/").filter(Boolean).pop()?.trim()
      if (canonical) {
        const decoded = decodeURIComponent(canonical)
        if (decoded && decoded !== slug) return { kind: "redirect", canonicalSlug: decoded }
      }
    }
    return { kind: "pass" }
  } catch {
    return { kind: "pass" }
  }
}

/**
 * Where the proxy rewrites an unknown kitchen to, so Next serves the page's own not-found screen
 * with status 404 instead of proxying the request to itself.
 *
 * WHY NOT `req.nextUrl`. Measured 2026-11-04 on the delivered stack (frontend image, Next 16.4.3,
 * `HOSTNAME=0.0.0.0` as the Dockerfile and compose set it): `GET /shop/<unknown>` hung for 30 s and
 * answered 500, "Failed to proxy http://localhost:3000/shop/... socket hang up". Next builds the
 * request URL a proxy sees from `localhost:PORT`, but decides whether a rewrite is INTERNAL by
 * comparing its origin with `{protocol}://{HOSTNAME}:{PORT}` (resolve-routes.js `initUrl`). The two
 * differ whenever the server is bound to 0.0.0.0, so the rewrite was treated as an EXTERNAL one and
 * proxied over HTTP to the same server, which ran this proxy again and rewrote again, until the
 * socket was dropped. `next dev` on its default host matches, which is why #1111 measured 404 there.
 * Every deployed frontend is bound that way (frontend/Dockerfile sets HOSTNAME), so the unknown-kitchen
 * 404 never worked outside `next dev`.
 *
 * So the target carries the origin Next compares against: HOSTNAME and PORT when the server was bound with
 * them (standalone, the compose and k8s images), else the request's own host (`next dev`, `next start` on the
 * default host); and the protocol Next derives from X-Forwarded-Proto. Behind a TLS-terminating ingress Next
 * compares https://HOSTNAME:PORT while the URL a proxy sees stays http, so an http target there hung exactly
 * like the unfixed one (measured with X-Forwarded-Proto: https).
 */
export function notFoundRewriteTarget(
  requestUrl: URL,
  requestHeaders: HeaderSource = new Headers(),
  env: Record<string, string | undefined> = process.env
): URL {
  const protocol = requestHeaders.get("x-forwarded-proto")?.includes("https") ? "https" : "http"
  const host = env.HOSTNAME?.trim()
  let authority = requestUrl.host
  if (host) {
    const bare = host.includes(":") && !host.startsWith("[") ? `[${host}]` : host
    authority = `${bare}:${Number.parseInt(env.PORT ?? "", 10) || 3000}`
  }
  const target = new URL(`${protocol}://${authority}`)
  target.pathname = requestUrl.pathname
  target.search = requestUrl.search
  return target
}

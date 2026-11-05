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

 * PR #1126: those three signals are NOT visible to the proxy in a built server. Next removes the flight headers
 * and `_rsc` from the request it hands middleware (`next/dist/server/web/adapter.js`, "Headers should only be
 * stripped for middleware"), so in production every prefetch looked like a document and was checked (measured:
 * an RSC prefetch of an unknown kitchen answered 404). They are kept for dev and for any server that does not
 * strip them, and a browser's own fetch metadata is added: a prefetch or client navigation is a `fetch()`, which
 * carries `Sec-Fetch-Dest: empty`, whereas a page load says `document` (or `iframe`) and a crawler or a plain
 * HTTP client says nothing, which still counts as a document.
 */
export function isDocumentRequest(
  requestHeaders: HeaderSource,
  searchParams: { has(name: string): boolean }
): boolean {
  const dest = requestHeaders.get("sec-fetch-dest")
  if (dest && dest !== "document" && dest !== "iframe" && dest !== "frame") return false
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
 * The query parameter the proxy adds to its own second request for the not-found screen. A request that
 * carries it is never gated again (that is what stops the proxy asking itself in a loop); it is otherwise
 * ignored, and a visitor who adds it by hand only gets the page's own noindex "Kitchen not found" with
 * status 200, which is exactly what a failed check gives.
 */
export const SHOP_SLUG_CHECKED_PARAM = "__kitchen_checked"

/** How long the proxy waits for its own page render before it lets the request pass (fail open). */
export const KITCHEN_NOT_FOUND_PAGE_TIMEOUT_MS = 8000

const HOP_BY_HOP = new Set(["content-length", "content-encoding", "transfer-encoding", "connection", "keep-alive"])

/**
 * The page for an unknown kitchen, served with status 404.
 *
 * WHY THE PROXY RENDERS IT ITSELF, AND DOES NOT REWRITE. The first version answered
 * `NextResponse.rewrite(req.nextUrl, { status: 404 })`. Measured 2026-11-04 on the delivered stack
 * (Next 16.4.3, `HOSTNAME=0.0.0.0`, which is how the Dockerfile, compose and k8s run it): HTTP 500
 * after 30 s, "Failed to proxy http://localhost:3000/shop/... socket hang up". Next decides whether a
 * rewrite is internal by comparing its origin with `{protocol}://{opts.hostname}:{port}` built in
 * `resolve-routes.js`, but the URL a proxy is handed is built from `localhost:PORT` (and, behind Auth.js, from
 * NEXTAUTH_URL), in which 0.0.0.0 has become `localhost` (`start-server.js`). They differ, so Next proxied the rewrite over HTTP
 * to the same server, which ran this proxy again and rewrote again until the socket dropped. A
 * rewrite to any other path avoids the loop but then answers with the status of that page, and a page
 * that calls `notFound()` under the app's Suspense boundaries answers 200 (see the note in the page).
 *
 * Re-deriving the origin Next compares against from `HOSTNAME`, `PORT` and `X-Forwarded-Proto` was tried and
 * rejected in review: each of those is read differently by `next start`, `next dev -H` and the standalone
 * `server.js`, and nothing would notice when a Next upgrade changed it. This asks the server that is running
 * the proxy, at the origin Next itself recorded for it, for the same page with `SHOP_SLUG_CHECKED_PARAM` set,
 * and answers with that page's body and headers (its CSP carries the nonce its HTML was rendered with) and
 * status 404. Nothing here depends on how Next compares origins, and scripts/check-unknown-kitchen-404.sh
 * runs it against the built server in each launch mode.
 *
 * Returns null when the page cannot be had (a network error, a timeout, a 5xx): the caller then lets the
 * request pass and the page renders exactly as it did before the status check existed.
 */
export async function fetchKitchenNotFoundPage(
  requestUrl: URL,
  requestHeaders: HeaderSource,
  fetchImpl: typeof fetch = fetch,
  timeoutMs: number = KITCHEN_NOT_FOUND_PAGE_TIMEOUT_MS,
  selfOrigin: string | undefined = process.env.__NEXT_PRIVATE_ORIGIN
): Promise<Response | null> {
  try {
    // WHERE THIS SERVER CAN BE REACHED BY ITSELF. Not `requestUrl`: the Auth.js wrapper around the proxy
    // rewrites `req.nextUrl` to the origin of NEXTAUTH_URL (measured: with NEXTAUTH_URL=http://localhost the
    // request URL read http://localhost:80 and the fetch was refused), which in a deployment is the PUBLIC
    // origin, and the Host header is the visitor's. `__NEXT_PRIVATE_ORIGIN` is what Next's start-server sets
    // to the origin it just bound ({protocol}://{host}:{port}, 0.0.0.0 shown as localhost) and uses for its
    // own requests to itself. Absent (a Next that no longer sets it), fall back to the request URL.
    const target = new URL(requestUrl.pathname + requestUrl.search, selfOrigin || requestUrl.origin)
    target.searchParams.set(SHOP_SLUG_CHECKED_PARAM, "1")
    const upstream = await fetchImpl(target, {
      cache: "no-store",
      redirect: "manual",
      headers: forwardedForHeader(requestHeaders),
      signal: AbortSignal.timeout(timeoutMs),
    })
    if (upstream.status !== 200 && upstream.status !== 404) return null
    const headers = new Headers()
    upstream.headers.forEach((value, key) => {
      if (!HOP_BY_HOP.has(key.toLowerCase())) headers.append(key, value)
    })
    return new Response(upstream.body, { status: 404, headers })
  } catch {
    return null
  }
}

import { headers } from "next/headers"

/**
 * SERVER ONLY (quick 261102-vwa). The one place the frontend reads and names X-Forwarded-For.
 *
 * "Server only" is the same convention as in `customer-orders-server.ts`, not a compile-time
 * guarantee: the `server-only` package is not a dependency. Here it is backed by `next/headers`,
 * which `next build` refuses to bundle into a `"use client"` file.
 *
 * WHY IT EXISTS. Core's public rate limiter keys its bucket on the client address
 * (`rl:public:{ip}`, `rate-limiting.public.*`, 30/min + burst 10 by default). For a server-side
 * read the socket peer core sees is the FRONTEND POD, so before this module every visitor's SSR
 * reads shared one bucket per pod. The #1104 and #1105 code reviews (round 1) recorded the
 * effect: the landing makes two public reads per render, so about 15 landing views a minute
 * across ALL visitors emptied the bucket, the listing got a 429 and the kitchen row vanished.
 *
 * WHAT IT FORWARDS: exactly ONE header, X-Forwarded-For, copied byte-for-byte. Nothing here
 * trims, splits, validates or chooses a hop. Deciding whom to believe stays with core's
 * `ClientIpResolver`: it reads the chain only when the socket peer is inside
 * `jtoye.security.trusted-proxies`, and walks it right to left to the first untrusted hop, so a
 * client-written left part is passed through untouched and never read (#782 is open on exactly
 * the class of bug a frontend-side choice would create).
 *
 * TWO ENTRY POINTS, ONE RULE.
 *  - `forwardedForHeader(source)` is pure, for callers that hold the request (route handlers pass
 *    `req.headers`). Their Jest suite invokes them outside Next's request scope, where
 *    `headers()` throws, so they must not depend on it.
 *  - `forwardedClientHeaders()` reads `next/headers`, for loaders and server components, which
 *    hold no request object. Outside a request scope (Jest, any non-request caller) `headers()`
 *    throws; this sends no header and NEVER throws. The catch does not log: an IP address is
 *    personal data, and the out-of-scope throw is expected.
 *
 * NEXT'S OWN FILL. When a request arrives without X-Forwarded-For, Next's base server fills it
 * with its own socket peer (`next/dist/server/base-server.js`, `??=` over
 * `socket.remoteAddress`). That value is Next's, not ours, and it is harmless: core reads XFF
 * only from a trusted peer.
 *
 * WHERE IT TAKES EFFECT. Staging trusts the cluster pod range, which covers the frontend pods,
 * so it applies there at once. Compose and production trust no peer, so XFF is inert there. In
 * production it takes effect only once `security.trusted-proxies` covers the frontend pods (the
 * peer of these server-side reads) as well as the ingress controller.
 */

/** Anything with `get(name)`: a Headers, Next's ReadonlyHeaders, `req.headers`. */
export interface HeaderSource {
  get(name: string): string | null
}

/** `{ "X-Forwarded-For": <the received value, unchanged> }` when present and non-empty, else `{}`. */
export function forwardedForHeader(
  source: HeaderSource | null | undefined
): Record<string, string> {
  const chain = source?.get("x-forwarded-for")
  return chain ? { "X-Forwarded-For": chain } : {}
}

/** The same header from the current request via `next/headers`; `{}` outside a request scope. */
export async function forwardedClientHeaders(): Promise<Record<string, string>> {
  try {
    return forwardedForHeader(await headers())
  } catch {
    return {}
  }
}

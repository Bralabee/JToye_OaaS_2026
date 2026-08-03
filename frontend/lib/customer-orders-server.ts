import { ORDERS_FETCH_SIZE, toOrdersLoad, type OrdersLoad } from "@/lib/customer-orders"

/**
 * SERVER ONLY. Reads the customer's order history straight from the core API so
 * the page can be rendered as HTML instead of after hydration.
 *
 * "Server only" is a convention here rather than a compile-time guarantee: the
 * `server-only` package is not a dependency of this project and adding one for a
 * single module is not worth the lockfile churn. It matters because this module
 * resolves the INTERNAL core host, which is meaningless in a browser and is a
 * small piece of infrastructure disclosure. Two things keep it honest: nothing
 * here is exported to a `"use client"` file (the client island talks to
 * /api/customer-orders instead), and `Buffer` below would fail in a browser
 * bundle. Import it only from server components and route handlers.
 */

/**
 * Server-side base URL for the core API — issue #467.
 *
 * The order of these fallbacks matters, and the reason the original ordering was
 * wrong is worth keeping:
 *
 *  - `CORE_API_INTERNAL_URL` is a plain runtime variable, so a deployment can set
 *    it to whatever hostname resolves on ITS network. In compose that is
 *    `http://core-java:9090`.
 *  - `NEXT_PUBLIC_API_URL` is NOT a runtime variable here. Next inlines every
 *    `process.env.NEXT_PUBLIC_*` reference at BUILD time, into the server bundle
 *    as well as the client one — verified in the running image:
 *      grep -rlF 'http://localhost:9090' /app/.next/server  -> matches
 *    So on a container built for browser use it is frozen to the BROWSER's view
 *    of the API and cannot be corrected by an `environment:` entry.
 *  - `http://localhost:9090` is only ever right for a developer running
 *    `next dev` on the host. Inside a container it is that container's OWN
 *    loopback. The compose `extra_hosts: localhost:host-gateway` mapping does
 *    NOT rescue it: the container's /etc/hosts already carries
 *    `127.0.0.1 localhost` first and wins. Measured in jtoye-frontend:
 *      wget http://localhost:9090/actuator/health -> Connection refused
 *      wget http://core-java:9090/actuator/health -> {"status":"UP",...}
 *
 * That is why /api/customer-orders returned 502 on every request on the compose
 * stack, and why the page had never listed a single order.
 */
export function coreBaseUrl(): string {
  return (
    process.env.CORE_API_INTERNAL_URL ||
    process.env.NEXT_PUBLIC_API_URL ||
    "http://localhost:9090"
  )
}

/**
 * Fetch the signed-in customer's orders using their Keycloak access token.
 *
 * The token is the proof of ownership (core verifies signature/issuer/expiry and
 * derives the email from the verified claim), so there is no email parameter on
 * this path and a caller can never choose whose orders to read.
 *
 * Never throws: every failure becomes `{ state: "error" }`, because a thrown
 * error in a server component renders the route's error boundary — which is a
 * worse answer than an in-page error with a retry, and loses the page chrome.
 */
export async function loadCustomerOrders(
  accessToken: string,
  size: number = ORDERS_FETCH_SIZE
): Promise<OrdersLoad> {
  try {
    const res = await fetch(`${coreBaseUrl()}/public/orders/mine?size=${size}`, {
      headers: { "X-Customer-Token": accessToken },
      cache: "no-store",
    })
    const body = await res.json().catch(() => null)
    return toOrdersLoad(res.status, body)
  } catch {
    // DNS/connect/timeout — the class of failure that produced the 502.
    return { state: "error", reason: "upstream" }
  }
}

/**
 * Read the `email` claim out of the customer's ID token.
 *
 * Rendered server-side purely so the "N orders · email" subtitle is present in
 * the first paint; a client-side fill-in would shift the layout under the
 * customer's thumb, which is the CLS problem this change is also trying to
 * reduce. No verification is done or needed here — this value is DISPLAY ONLY
 * and never selects whose orders are fetched (the core API does that from the
 * cryptographically verified access token).
 */
export function displayEmailFromIdToken(idToken: string | undefined): string | null {
  if (!idToken) return null
  try {
    const parts = idToken.split(".")
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/")
    const pad = b64.length % 4 === 0 ? b64 : b64 + "=".repeat(4 - (b64.length % 4))
    const claims = JSON.parse(Buffer.from(pad, "base64").toString("utf8")) as {
      email?: string
    }
    return claims.email ?? null
  } catch {
    return null
  }
}

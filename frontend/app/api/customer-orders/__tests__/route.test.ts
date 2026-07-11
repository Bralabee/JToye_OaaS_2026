/**
 * @jest-environment node
 *
 * Contract tests for /api/customer-orders (issue #179 defect 1) — the
 * server-side proxy behind the customer "My Orders" page. Security contract:
 *
 *  - no HttpOnly access-token cookie → 401 and the core API is NEVER called
 *    (an unauthenticated caller cannot even trigger an upstream request)
 *  - with the cookie, the token is forwarded on X-Customer-Token to
 *    /public/orders/mine — and ONLY pagination params go with it; a
 *    client-supplied email param must never reach the upstream URL
 *  - upstream status codes pass through (a core-side 401 stays a 401)
 *  - upstream network failure → 502, not an unhandled rejection
 */

import { GET } from "../route"
import { NextRequest } from "next/server"

const ACCESS_COOKIE = "jtoye-customer-access"

function makeRequest(url: string, cookie?: string): NextRequest {
  return new NextRequest(url, {
    headers: cookie ? { cookie: `${ACCESS_COOKIE}=${cookie}` } : {},
  })
}

describe("/api/customer-orders", () => {
  const realFetch = global.fetch

  afterEach(() => {
    global.fetch = realFetch
    jest.restoreAllMocks()
  })

  it("returns 401 without calling core when the access cookie is absent", async () => {
    const fetchSpy = jest.fn()
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await GET(makeRequest("http://localhost/api/customer-orders?size=100"))

    expect(res.status).toBe(401)
    expect(fetchSpy).not.toHaveBeenCalled()
    const body = await res.json()
    expect(body).toEqual({ error: "not_authenticated" })
  })

  it("forwards the cookie token on X-Customer-Token to /public/orders/mine", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({
      status: 200,
      json: async () => ({ content: [{ orderNumber: "ORD-1" }] }),
    })
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await GET(
      makeRequest("http://localhost/api/customer-orders?size=100&page=2", "tok-abc")
    )

    expect(res.status).toBe(200)
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    const [url, init] = fetchSpy.mock.calls[0]
    expect(String(url)).toContain("/public/orders/mine?")
    expect(String(url)).toContain("size=100")
    expect(String(url)).toContain("page=2")
    expect(init.headers["X-Customer-Token"]).toBe("tok-abc")

    const body = await res.json()
    expect(body.content[0].orderNumber).toBe("ORD-1")
  })

  it("never forwards a client-supplied email param (identity comes from the token only)", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({
      status: 200,
      json: async () => ({ content: [] }),
    })
    global.fetch = fetchSpy as unknown as typeof fetch

    await GET(
      makeRequest(
        "http://localhost/api/customer-orders?email=victim%40example.com&size=abc;DROP",
        "tok-abc"
      )
    )

    const [url] = fetchSpy.mock.calls[0]
    expect(String(url)).not.toContain("email")
    expect(String(url)).not.toContain("victim")
    // non-numeric size is dropped too
    expect(String(url)).not.toContain("size")
  })

  it("passes an upstream 401 (rejected/expired token) through to the caller", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({
      status: 401,
      json: async () => ({ detail: "Customer authentication required" }),
    })
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await GET(makeRequest("http://localhost/api/customer-orders", "stale-tok"))
    expect(res.status).toBe(401)
  })

  it("returns 502 when core is unreachable", async () => {
    const fetchSpy = jest.fn().mockRejectedValue(new Error("ECONNREFUSED"))
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await GET(makeRequest("http://localhost/api/customer-orders", "tok-abc"))
    expect(res.status).toBe(502)
    const body = await res.json()
    expect(body).toEqual({ error: "upstream_unavailable" })
  })
})

/**
 * @jest-environment node
 *
 * #868 (owner ruling 2026-11-04): the server-side seam for a customer cancelling their own order.
 * The HttpOnly access-token cookie never reaches browser JS, so a signed-in customer's cancel goes
 * through here and core receives the token on X-Customer-Token. A guest sends the email in the body.
 *
 *  - the Idempotency-Key and the visitor's X-Forwarded-For chain are forwarded, nothing else
 *  - with the cookie, the token is forwarded; status and body pass through unchanged
 *  - a lapsed token (core 401) with a guest email in the body degrades to the guest proof, once
 *  - a malformed order number never reaches core
 *  - upstream network failure -> 502, not an unhandled rejection
 */
import { NextRequest } from "next/server"
import { POST } from "../route"

const ACCESS_COOKIE = "jtoye-customer-access"

function makeRequest(
  orderNumber: string,
  body: unknown,
  opts: { cookie?: string; key?: string } = {}
): [NextRequest, { params: Promise<{ orderNumber: string }> }] {
  const headers: Record<string, string> = { "content-type": "application/json" }
  if (opts.cookie) headers.cookie = `${ACCESS_COOKIE}=${opts.cookie}`
  if (opts.key) headers["idempotency-key"] = opts.key
  headers["x-forwarded-for"] = "203.0.113.9"
  const req = new NextRequest(`http://localhost/api/customer-orders/${orderNumber}/cancel`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  })
  return [req, { params: Promise.resolve({ orderNumber }) }]
}

describe("POST /api/customer-orders/[orderNumber]/cancel", () => {
  const realFetch = global.fetch
  afterEach(() => {
    global.fetch = realFetch
  })

  it("forwards a guest cancel to core with the email, the Idempotency-Key and the visitor chain", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({
      status: 200,
      json: async () => ({ status: "CANCELLED", refunded: false }),
    })
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await POST(...makeRequest("ORD-1", { email: "a@b.co" }, { key: "k-1" }))

    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ status: "CANCELLED", refunded: false })
    const [url, init] = fetchSpy.mock.calls[0]
    expect(String(url)).toContain("/public/orders/ORD-1/cancel")
    expect(init.method).toBe("POST")
    expect(init.headers["Idempotency-Key"]).toBe("k-1")
    expect(init.headers["X-Forwarded-For"]).toBe("203.0.113.9")
    expect(init.headers["X-Customer-Token"]).toBeUndefined()
    expect(JSON.parse(init.body)).toEqual({ email: "a@b.co" })
  })

  it("with the access cookie, sends the token on X-Customer-Token (and still no way to choose an order's owner)", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({ status: 200, json: async () => ({ status: "CANCELLED" }) })
    global.fetch = fetchSpy as unknown as typeof fetch

    await POST(...makeRequest("ORD-2", {}, { cookie: "tok-abc" }))

    expect(fetchSpy.mock.calls[0][1].headers["X-Customer-Token"]).toBe("tok-abc")
  })

  it("passes a core 409 through unchanged so the page can show the contact-the-shop copy", async () => {
    const problem = { type: "https://jtoye.uk/errors/order-not-cancellable-by-customer", contactShop: true }
    global.fetch = jest.fn().mockResolvedValue({ status: 409, json: async () => problem }) as unknown as typeof fetch

    const res = await POST(...makeRequest("ORD-3", { email: "a@b.co" }))

    expect(res.status).toBe(409)
    expect(await res.json()).toEqual(problem)
  })

  it("a lapsed token (core 401) with a guest email retries ONCE as a guest, without the token", async () => {
    const fetchSpy = jest
      .fn()
      .mockResolvedValueOnce({ status: 401, json: async () => ({}) })
      .mockResolvedValueOnce({ status: 200, json: async () => ({ status: "CANCELLED" }) })
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await POST(...makeRequest("ORD-4", { email: "a@b.co" }, { cookie: "stale" }))

    expect(res.status).toBe(200)
    expect(fetchSpy).toHaveBeenCalledTimes(2)
    expect(fetchSpy.mock.calls[0][1].headers["X-Customer-Token"]).toBe("stale")
    expect(fetchSpy.mock.calls[1][1].headers["X-Customer-Token"]).toBeUndefined()
  })

  it("a lapsed token with NO email stays a 401 and is not retried", async () => {
    const fetchSpy = jest.fn().mockResolvedValue({ status: 401, json: async () => ({}) })
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await POST(...makeRequest("ORD-5", {}, { cookie: "stale" }))

    expect(res.status).toBe(401)
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it("refuses a malformed order number without calling core", async () => {
    const fetchSpy = jest.fn()
    global.fetch = fetchSpy as unknown as typeof fetch

    const res = await POST(...makeRequest("..%2F..%2Fadmin", { email: "a@b.co" }))

    expect(res.status).toBe(400)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it("an unreachable core is a 502", async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error("down")) as unknown as typeof fetch

    const res = await POST(...makeRequest("ORD-6", { email: "a@b.co" }))

    expect(res.status).toBe(502)
  })
})

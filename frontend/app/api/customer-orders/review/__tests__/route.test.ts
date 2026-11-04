/**
 * @jest-environment node
 *
 * Contract tests for POST /api/customer-orders/review (#846): the server-side proxy behind "Rate your
 * order". It exists so the customer's identity travels as the HttpOnly access-token cookie, forwarded
 * as X-Customer-Token, and never as an email anywhere.
 *
 *  - no cookie -> 401 and core is NEVER called;
 *  - the shop and order come from the query string and are validated to a safe shape before they are
 *    placed in the upstream PATH (a `../` or a slash must not reach core's routing);
 *  - the upstream path is /public/shops/{shop}/orders/{order}/review and the URL carries no email, no
 *    query string at all; a client-sent `email` in the query or the body never reaches upstream;
 *  - only the rating fields are forwarded from the body;
 *  - status and body pass through (a 400 stays a 400, a 404 a 404); a network failure is a 502.
 */
import { NextRequest } from "next/server"
import { POST } from "../route"

const ACCESS_COOKIE = "jtoye-customer-access"
const URL_OK = "http://localhost/api/customer-orders/review?shop=mama-ades-kitchen&order=ORD-20261104-ABC123"

function makeRequest(url: string, body: unknown, cookie?: string): NextRequest {
  return new NextRequest(url, {
    method: "POST",
    headers: { "content-type": "application/json", ...(cookie ? { cookie: `${ACCESS_COOKIE}=${cookie}` } : {}) },
    body: typeof body === "string" ? body : JSON.stringify(body),
  })
}

describe("/api/customer-orders/review", () => {
  const realFetch = global.fetch
  afterEach(() => {
    global.fetch = realFetch
    jest.restoreAllMocks()
  })

  it("401 and no upstream call without the access cookie", async () => {
    const spy = jest.fn()
    global.fetch = spy as unknown as typeof fetch
    const res = await POST(makeRequest(URL_OK, { foodRating: 5 }))
    expect(res.status).toBe(401)
    expect(spy).not.toHaveBeenCalled()
  })

  it("forwards the cookie token on X-Customer-Token to the shop+order review path, no query string, no email", async () => {
    const spy = jest.fn().mockResolvedValue({ status: 201, json: async () => ({ id: "r1", customerName: "Grace P." }) })
    global.fetch = spy as unknown as typeof fetch

    const res = await POST(makeRequest(`${URL_OK}&email=victim@example.com`, { foodRating: 5, deliveryRating: 4, comment: "Lovely", email: "victim@example.com", orderId: "x" }, "tok-abc"))

    expect(res.status).toBe(201)
    expect(spy).toHaveBeenCalledTimes(1)
    const [url, init] = spy.mock.calls[0]
    expect(String(url)).toMatch(/\/public\/shops\/mama-ades-kitchen\/orders\/ORD-20261104-ABC123\/review$/)
    expect(String(url)).not.toMatch(/\?|@|email/)
    expect(init.method).toBe("POST")
    expect(init.headers["X-Customer-Token"]).toBe("tok-abc")
    // Only the rating fields: an `email` or `orderId` sent by the client is dropped.
    expect(JSON.parse(init.body)).toEqual({ foodRating: 5, deliveryRating: 4, comment: "Lovely" })
    expect(await res.json()).toEqual({ id: "r1", customerName: "Grace P." })
  })

  it.each([
    ["shop with a slash", "shop=a/b&order=ORD-1"],
    ["shop with dot-dot", "shop=..&order=ORD-1"],
    ["shop uppercase", "shop=Shop&order=ORD-1"],
    ["order with a slash", "shop=a&order=ORD/1"],
    ["order with a query", "shop=a&order=ORD-1%3Fx%3D1"],
    ["missing order", "shop=a"],
    ["missing shop", "order=ORD-1"],
  ])("400 and no upstream call for %s", async (_name, query) => {
    const spy = jest.fn()
    global.fetch = spy as unknown as typeof fetch
    const res = await POST(makeRequest(`http://localhost/api/customer-orders/review?${query}`, { foodRating: 5 }, "tok-abc"))
    expect(res.status).toBe(400)
    expect(spy).not.toHaveBeenCalled()
  })

  it("400 for a body that is not JSON, without calling core", async () => {
    const spy = jest.fn()
    global.fetch = spy as unknown as typeof fetch
    const res = await POST(makeRequest(URL_OK, "{not json", "tok-abc"))
    expect(res.status).toBe(400)
    expect(spy).not.toHaveBeenCalled()
  })

  it("passes a core refusal through unchanged (400 stays 400, with its problem body)", async () => {
    global.fetch = jest.fn().mockResolvedValue({
      status: 400,
      json: async () => ({ type: "https://jtoye.uk/errors/invalid-argument", detail: "You have already reviewed this order" }),
    }) as unknown as typeof fetch
    const res = await POST(makeRequest(URL_OK, { foodRating: 5 }, "tok-abc"))
    expect(res.status).toBe(400)
    expect((await res.json()).detail).toBe("You have already reviewed this order")
  })

  it("an upstream network failure is a 502, never an unhandled rejection", async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error("ECONNREFUSED")) as unknown as typeof fetch
    const res = await POST(makeRequest(URL_OK, { foodRating: 5 }, "tok-abc"))
    expect(res.status).toBe(502)
  })
})

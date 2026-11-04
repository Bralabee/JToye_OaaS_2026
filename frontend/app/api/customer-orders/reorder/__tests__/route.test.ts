/**
 * @jest-environment node
 *
 * Contract tests for GET /api/customer-orders/reorder (#845): the server-side proxy behind "Order again".
 * Same security shape as the review proxy: the customer's HttpOnly access cookie is forwarded as
 * X-Customer-Token, the shop and order come from the query string and are validated before they enter the
 * upstream path, no email exists on this surface, and the answer is never cached.
 */
import { NextRequest } from "next/server"
import { GET } from "../route"

const ACCESS_COOKIE = "jtoye-customer-access"
const URL_OK = "http://localhost/api/customer-orders/reorder?shop=mama-ades-kitchen&order=ORD-20261104-ABC123"

function makeRequest(url: string, cookie?: string): NextRequest {
  return new NextRequest(url, { headers: cookie ? { cookie: `${ACCESS_COOKIE}=${cookie}` } : {} })
}

describe("/api/customer-orders/reorder", () => {
  const realFetch = global.fetch
  afterEach(() => {
    global.fetch = realFetch
    jest.restoreAllMocks()
  })

  it("401 and no upstream call without the access cookie", async () => {
    const spy = jest.fn()
    global.fetch = spy as unknown as typeof fetch
    const res = await GET(makeRequest(URL_OK))
    expect(res.status).toBe(401)
    expect(spy).not.toHaveBeenCalled()
  })

  it("forwards the cookie token to /public/shops/{shop}/orders/{order}/reorder and returns it no-store", async () => {
    const lines = { orderNumber: "ORD-20261104-ABC123", shopSlug: "mama-ades-kitchen", lines: [{ productId: "p1", productName: "Jollof", quantity: 2, unitPricePennies: 1000 }] }
    const spy = jest.fn().mockResolvedValue({ status: 200, json: async () => lines })
    global.fetch = spy as unknown as typeof fetch

    const res = await GET(makeRequest(`${URL_OK}&email=victim@example.com`, "tok-abc"))

    expect(res.status).toBe(200)
    const [url, init] = spy.mock.calls[0]
    expect(String(url)).toMatch(/\/public\/shops\/mama-ades-kitchen\/orders\/ORD-20261104-ABC123\/reorder$/)
    expect(String(url)).not.toMatch(/\?|@|email/)
    expect(init.headers["X-Customer-Token"]).toBe("tok-abc")
    expect(res.headers.get("Cache-Control")).toBe("no-store")
    expect(await res.json()).toEqual(lines)
  })

  it.each([
    ["shop with a slash", "shop=a/b&order=ORD-1"],
    ["shop uppercase", "shop=Shop&order=ORD-1"],
    ["order with a slash", "shop=a&order=ORD/1"],
    ["missing order", "shop=a"],
  ])("400 and no upstream call for %s", async (_name, query) => {
    const spy = jest.fn()
    global.fetch = spy as unknown as typeof fetch
    const res = await GET(makeRequest(`http://localhost/api/customer-orders/reorder?${query}`, "tok-abc"))
    expect(res.status).toBe(400)
    expect(spy).not.toHaveBeenCalled()
  })

  it("passes a core 404 through, and an upstream network failure is a 502", async () => {
    global.fetch = jest.fn().mockResolvedValue({ status: 404, json: async () => ({ detail: "Order not found" }) }) as unknown as typeof fetch
    expect((await GET(makeRequest(URL_OK, "tok-abc"))).status).toBe(404)
    global.fetch = jest.fn().mockRejectedValue(new Error("ECONNREFUSED")) as unknown as typeof fetch
    expect((await GET(makeRequest(URL_OK, "tok-abc"))).status).toBe(502)
  })
})

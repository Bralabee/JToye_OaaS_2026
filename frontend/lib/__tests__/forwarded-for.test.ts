/**
 * @jest-environment node
 *
 * Quick 261102-vwa: EVERY server-side core read the frontend makes carries the visitor's
 * X-Forwarded-For chain verbatim, beside the headers it already sent and nothing else.
 *
 * Core's public rate limiter buckets per client address (`rl:public:{ip}`). The socket peer of a
 * server-side read is the frontend pod, so a read that drops the chain puts every visitor in one
 * bucket (#1104 r1, #1105 r1). One assertion over all eight callers, so a caller that forgets the
 * header is named in the diff.
 *
 * This file deliberately does NOT import `@/lib/forwarded-for`: on a tree where a caller lacks
 * the helper it must fail on its assertions, not on a missing module.
 */

import { headers } from "next/headers"
import { NextRequest } from "next/server"
import {
  loadAllShopSlugs,
  loadFeaturedShops,
  loadPublicShop,
  loadShopDetail,
  loadShopList,
} from "@/lib/storefront-server"
import { loadCustomerOrders } from "@/lib/customer-orders-server"
import { GET, POST } from "@/app/api/customer-orders/route"

// Delegates to the REAL next/headers by default (it throws outside a request scope). Reset in
// beforeEach explicitly: the jest config sets no resetMocks.
jest.mock("next/headers", () => {
  const actual = jest.requireActual("next/headers")
  return { ...actual, headers: jest.fn(() => actual.headers()) }
})
const headersMock = headers as unknown as jest.Mock

const CHAIN = "not-an-ip, 192.0.2.66, 198.51.100.7,203.0.113.9"
const SHOP = { slug: "probe-kitchen", name: "Probe Kitchen" }
const PAGE = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 0 }

function respond(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, headers: new Headers(), json: async () => body }
}

/** Routes by method and path; anything unlisted is a 404 so it cannot pass silently. */
function routedFetch() {
  return jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input))
    if (url.origin !== "http://core.test") return respond(599, { title: "not core" })
    const { pathname } = url
    const method = init?.method ?? "GET"
    if (method === "POST" && pathname === "/public/shops/probe-kitchen/orders") return respond(201, {})
    if (pathname === "/public/featured-shops") return respond(200, [SHOP])
    // ONE page: loadAllShopSlugs reads page 0 only, so the sitemap's slugs are one fetch.
    if (pathname === "/public/shops")
      return respond(200, { content: [SHOP], totalElements: 1, totalPages: 1, number: 0, size: 1 })
    if (pathname === "/public/shops/probe-kitchen") return respond(200, SHOP)
    if (pathname === "/public/shops/probe-kitchen/products") return respond(200, {})
    if (pathname === "/public/shops/probe-kitchen/reviews") return respond(200, { content: [], totalElements: 0 })
    if (pathname === "/public/shops/probe-kitchen/announcements") return respond(200, [])
    if (pathname === "/public/orders/mine") return respond(200, PAGE)
    return respond(404, { title: "Not Found" })
  })
}

interface Seen {
  caller: string
  method: string
  path: string
  xff: string | null
  names: string[]
}

const realFetch = global.fetch
// Pin the core base URL so paths below are exactly core's (jest.setup gives NEXT_PUBLIC_API_URL a
// path prefix). coreBaseUrl() reads it at call time.
const realCoreUrl = process.env.CORE_API_INTERNAL_URL
beforeAll(() => {
  process.env.CORE_API_INTERNAL_URL = "http://core.test"
})
afterAll(() => {
  if (realCoreUrl === undefined) delete process.env.CORE_API_INTERNAL_URL
  else process.env.CORE_API_INTERNAL_URL = realCoreUrl
})

beforeEach(() => {
  headersMock.mockReset()
  headersMock.mockImplementation(() => jest.requireActual("next/headers").headers())
})

afterEach(() => {
  global.fetch = realFetch
})

describe("every server-side core read forwards the visitor's X-Forwarded-For (quick 261102-vwa)", () => {
  it("E1 all 11 reads across the eight callers carry the chain verbatim and keep only their own headers", async () => {
    headersMock.mockResolvedValue(new Headers({ "x-forwarded-for": CHAIN, cookie: "a=b", "x-nonce": "n" }))
    const fetchMock = routedFetch()
    global.fetch = fetchMock as unknown as typeof fetch

    const seen: Seen[] = []
    const record = async (caller: string, run: () => Promise<unknown>) => {
      const from = fetchMock.mock.calls.length
      await run()
      for (const [input, init] of fetchMock.mock.calls.slice(from)) {
        const u = new URL(String(input))
        expect(u.origin).toBe("http://core.test")
        const h = new Headers((init as RequestInit | undefined)?.headers)
        seen.push({
          caller,
          method: (init as RequestInit | undefined)?.method ?? "GET",
          path: u.pathname + u.search,
          xff: h.get("x-forwarded-for"),
          names: [...h.keys()].sort(),
        })
      }
    }

    await record("loadShopList", () => loadShopList())
    await record("loadFeaturedShops", () => loadFeaturedShops())
    await record("loadShopDetail", () => loadShopDetail("probe-kitchen"))
    await record("loadPublicShop", () => loadPublicShop("probe-kitchen"))
    await record("loadAllShopSlugs", () => loadAllShopSlugs())
    await record("loadCustomerOrders", () => loadCustomerOrders("tok"))
    await record("GET /api/customer-orders", () =>
      GET(
        new NextRequest("http://localhost/api/customer-orders?size=5", {
          headers: { cookie: "jtoye-customer-access=tok", "x-forwarded-for": CHAIN, "x-real-ip": "203.0.113.50" },
        })
      )
    )
    await record("POST /api/customer-orders", () =>
      POST(
        new NextRequest("http://localhost/api/customer-orders?shop=probe-kitchen", {
          method: "POST",
          headers: {
            "content-type": "application/json",
            cookie: "jtoye-customer-access=tok",
            "idempotency-key": "k-1",
            "x-forwarded-for": CHAIN,
            "x-real-ip": "203.0.113.50",
          },
          body: "{}",
        })
      )
    )

    expect(fetchMock).toHaveBeenCalledTimes(11)
    const order = (a: Seen) => `${a.caller} ${a.method} ${a.path}`
    const sorted = [...seen].sort((a, b) => order(a).localeCompare(order(b)))

    const XFF = ["x-forwarded-for"]
    const TOKEN = ["x-customer-token", "x-forwarded-for"]
    const expected: Seen[] = [
      { caller: "GET /api/customer-orders", method: "GET", path: "/public/orders/mine?size=5", xff: CHAIN, names: TOKEN },
      { caller: "POST /api/customer-orders", method: "POST", path: "/public/shops/probe-kitchen/orders", xff: CHAIN,
        names: ["content-type", "idempotency-key", "x-customer-token", "x-forwarded-for"] },
      { caller: "loadAllShopSlugs", method: "GET", path: "/public/shops?page=0&size=500", xff: CHAIN, names: XFF },
      { caller: "loadCustomerOrders", method: "GET", path: "/public/orders/mine?size=100", xff: CHAIN, names: TOKEN },
      { caller: "loadFeaturedShops", method: "GET", path: "/public/featured-shops", xff: CHAIN, names: XFF },
      { caller: "loadPublicShop", method: "GET", path: "/public/shops/probe-kitchen", xff: CHAIN, names: XFF },
      { caller: "loadShopDetail", method: "GET", path: "/public/shops/probe-kitchen", xff: CHAIN, names: XFF },
      { caller: "loadShopDetail", method: "GET", path: "/public/shops/probe-kitchen/announcements", xff: CHAIN, names: XFF },
      { caller: "loadShopDetail", method: "GET", path: "/public/shops/probe-kitchen/products", xff: CHAIN, names: XFF },
      { caller: "loadShopDetail", method: "GET", path: "/public/shops/probe-kitchen/reviews?size=5", xff: CHAIN, names: XFF },
      { caller: "loadShopList", method: "GET", path: "/public/shops?page=0&size=12", xff: CHAIN, names: XFF },
    ].sort((a, b) => order(a).localeCompare(order(b)))
    expect(sorted).toEqual(expected)
  })

  it("E2 loadCustomerOrders outside a request scope resolves, sends its token uncached, and no chain", async () => {
    const fetchMock = routedFetch()
    global.fetch = fetchMock as unknown as typeof fetch

    await expect(loadCustomerOrders("tok")).resolves.toBeDefined()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    const h = new Headers(init.headers)
    expect(h.get("x-customer-token")).toBe("tok")
    expect(h.has("x-forwarded-for")).toBe(false)
    expect(init.cache).toBe("no-store")
  })
})

/**
 * Server-side storefront loaders (#507, #447).
 *
 * The whole point of moving `/shop` and `/shop/[slug]` to the server is that the
 * HTML arrives populated. That makes the loader the new single point of failure
 * for the storefront, so the three-valued result is asserted directly — including
 * the distinction the client UI depends on: a 429 is NOT a missing shop.
 *
 * Every block below is written to fail on a plausible wrong implementation, not
 * merely to exercise the happy path:
 *  - "429 -> notfound" would render "Shop not found" for a rate-limited shop,
 *    which is the exact defect F-RATE (#88) exists to prevent.
 *  - "unreadable menu -> empty menu" would publish "No items yet" — a claim
 *    about the vendor — because of a network fault.
 *  - "one optional sub-resource fails -> whole page fails" would take the menu
 *    down because nobody had reviewed the shop.
 */

import {
  loadShopDetail,
  loadShopList,
  loadAllShopSlugs,
  loadFeaturedShops,
  allProducts,
} from "@/lib/storefront-server"
import type { PublicProduct, PublicShop } from "@/types/storefront"
import { headers } from "next/headers"

/**
 * next/headers (quick 261102-vwa). The mock DELEGATES to the real module by default, so every
 * block in this file runs against the real out-of-scope behaviour ("`headers` was called outside
 * a request scope") unless it says otherwise. That is the degrade path the sitemap and any
 * non-request caller take, so the pre-existing blocks below double as its regression suite.
 * The implementation is reset in beforeEach explicitly: the jest config sets no resetMocks, and
 * Jest 30's restoreAllMocks does not touch a jest.fn made in a mock factory.
 */
jest.mock("next/headers", () => {
  const actual = jest.requireActual("next/headers")
  return { ...actual, headers: jest.fn(() => actual.headers()) }
})
const headersMock = headers as unknown as jest.Mock
beforeEach(() => {
  headersMock.mockReset()
  headersMock.mockImplementation(() => jest.requireActual("next/headers").headers())
})

const shop: PublicShop = {
  slug: "brixton-village-grill",
  name: "Brixton Village Grill",
  description: "Flame-grilled peri peri chicken.",
  address: "Unit 74, Brixton Village Market, London SW9 8PS",
  logoUrl: "/brand/logo-brixton-grill.png",
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: { mon: "09:00 - 17:00" },
  deliveryInfo: null,
  minimumOrderPennies: 1000,
  deliveryFeePennies: 399,
  freeDeliveryThresholdPennies: 2000,
  tags: "Grill, Peri Peri, Halal",
}

const product: PublicProduct = {
  id: "p1",
  title: "Peri Peri Chicken",
  description: "Half a flame-grilled bird.",
  imageUrl: null,
  imageUrls: [],
  ingredientsText: "chicken, peri peri",
  allergenMask: 0,
  pricePennies: 850,
  category: "Mains",
  dietaryTags: "Halal",
  preparationTimeMinutes: 15,
  featured: true,
  inStock: true,
}

/**
 * Route the mocked `fetch` by URL substring, so a block only has to describe the
 * calls it cares about. Anything unlisted answers 200 with `fallback` — an
 * UNLISTED call must never be the reason a block passes or fails.
 */
function mockFetch(
  routes: Array<[RegExp, number, unknown, Record<string, string>?]>,
  fallback: unknown = {}
) {
  return jest.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    for (const [pattern, status, body, hdrs] of routes) {
      if (pattern.test(url)) {
        return {
          ok: status >= 200 && status < 300,
          status,
          headers: new Headers(hdrs ?? {}),
          json: async () => body,
        } as Response
      }
    }
    return { ok: true, status: 200, headers: new Headers(), json: async () => fallback } as Response
  })
}

const realFetch = global.fetch

afterEach(() => {
  global.fetch = realFetch
  jest.restoreAllMocks()
})

describe("loadShopDetail", () => {
  it("returns ok with the shop and its menu", async () => {
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 200, shop],
      [/\/products$/, 200, { Mains: [product] }],
      [/\/reviews/, 200, { content: [], totalElements: 0 }],
    ]) as unknown as typeof fetch

    const r = await loadShopDetail("brixton-village-grill")
    expect(r.state).toBe("ok")
    if (r.state !== "ok") return
    expect(r.data.shop.name).toBe("Brixton Village Grill")
    expect(r.data.products.Mains).toHaveLength(1)
  })

  it("carries the server's availability answer through untouched and computes none of its own (37-22, D-15)", async () => {
    // `{}` hours are "always open" to the old client parser; the server says paused. The page
    // must be handed the server's answer, and no second, client-computed open flag beside it.
    const paused = {
      ...shop,
      openingHours: {},
      openNow: false,
      pausedUntil: "2026-10-13T17:40:00Z",
      nextOpenAt: "2026-10-13T17:40:00Z",
      pausedIndefinitely: false,
    }
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 200, paused],
      [/\/products$/, 200, { Mains: [product] }],
    ]) as unknown as typeof fetch

    const r = await loadShopDetail("brixton-village-grill")
    if (r.state !== "ok") throw new Error("expected ok")
    expect(r.data.shop.openNow).toBe(false)
    expect(r.data.shop.pausedUntil).toBe("2026-10-13T17:40:00Z")
    expect(r.data).not.toHaveProperty("isOpen")
  })

  it("fetches every storefront call with cache: no-store, so a cached page cannot say 'Open now' for a paused shop (UI-SPEC open item 5)", async () => {
    const fetchMock = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 200, shop],
      [/\/products$/, 200, { Mains: [product] }],
    ]) as unknown as jest.Mock
    global.fetch = fetchMock as unknown as typeof fetch

    await loadShopDetail("brixton-village-grill")
    await loadShopList()

    // shop, products, reviews, announcements, and the directory. No promotions call
    // (37-36, D-17: the storefront renders none, so it does not ask for any).
    expect(fetchMock.mock.calls).toHaveLength(5)
    for (const [url, init] of fetchMock.mock.calls as Array<[string, RequestInit & { next?: unknown }]>) {
      expect({ url, cache: init?.cache }).toEqual({ url, cache: "no-store" })
      // A revalidate window beside no-store would be a second, contradicting caching rule.
      expect(init?.next).toBeUndefined()
    }
  })

  it("returns notfound ONLY for a real 404 on the shop itself", async () => {
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 404, null],
    ]) as unknown as typeof fetch

    await expect(loadShopDetail("gone")).resolves.toEqual({ state: "notfound" })
  })

  it("returns defer — NOT notfound — on a 429 (F-RATE #88)", async () => {
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 429, { detail: "rate limited" }],
    ]) as unknown as typeof fetch

    const r = await loadShopDetail("brixton-village-grill")
    // The failure this asserts against is real: "any non-200 is notfound" would
    // render the authoritative "Shop not found" for a shop that exists and is
    // merely busy, and would answer HTTP 404 for it.
    expect(r).toEqual({ state: "defer" })
    expect(r.state).not.toBe("notfound")
  })

  it("returns defer on a 5xx and on a transport failure", async () => {
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 503, null],
    ]) as unknown as typeof fetch
    await expect(loadShopDetail("x")).resolves.toEqual({ state: "defer" })

    global.fetch = jest.fn(async () => {
      throw new Error("ECONNREFUSED")
    }) as unknown as typeof fetch
    await expect(loadShopDetail("x")).resolves.toEqual({ state: "defer" })
  })

  it("defers rather than publishing an empty menu when the menu call fails", async () => {
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 200, shop],
      [/\/products$/, 500, null],
    ]) as unknown as typeof fetch

    // "No items yet" is a claim about the vendor. A 500 is a claim about the
    // network, and the two must not be confused on a public page.
    await expect(loadShopDetail("x")).resolves.toEqual({ state: "defer" })
  })

  it("still renders when the OPTIONAL sub-resources fail", async () => {
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 200, shop],
      [/\/products$/, 200, { Mains: [product] }],
      [/\/reviews/, 500, null],
      [/\/announcements$/, 500, null],
    ]) as unknown as typeof fetch

    const r = await loadShopDetail("x")
    expect(r.state).toBe("ok")
    if (r.state !== "ok") return
    expect(r.data.reviews).toEqual([])
    expect(r.data.reviewCount).toBe(0)
    expect(r.data.announcements).toEqual([])
  })

  it("averages the review SAMPLE it fetched, to one decimal place", async () => {
    global.fetch = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 200, shop],
      [/\/products$/, 200, {}],
      [
        /\/reviews/,
        200,
        {
          content: [
            { id: "r1", foodRating: 5 },
            { id: "r2", foodRating: 4 },
            { id: "r3", foodRating: 4 },
          ],
          totalElements: 41,
        },
      ],
    ]) as unknown as typeof fetch

    const r = await loadShopDetail("x")
    if (r.state !== "ok") throw new Error("expected ok")
    expect(r.data.avgRating).toBe(4.3)
    // The displayed total stays the real total; only the JSON-LD count is
    // narrowed to the sample (see structured-data).
    expect(r.data.reviewCount).toBe(41)
    expect(r.data.reviews).toHaveLength(3)
  })

  it("percent-encodes the slug so a crafted path cannot escape the endpoint", async () => {
    const fetchMock = mockFetch([
      [/\/public\/shops\/[^/?]+$/, 404, null],
    ]) as unknown as jest.Mock
    global.fetch = fetchMock as unknown as typeof fetch

    await loadShopDetail("../../actuator/env")

    for (const call of fetchMock.mock.calls) {
      expect(String(call[0])).not.toContain("../")
      expect(String(call[0])).toContain("%2F")
    }
  })

  it("returns redirect with canonicalSlug when the shop endpoint answers 301 with X-Shop-Canonical-Slug, even though /products is 404 on the old slug (39-09, RWO-028)", async () => {
    global.fetch = mockFetch([
      [
        /\/public\/shops\/[^/?]+$/,
        301,
        null,
        {
          location: "/api/v1/public/shops/brixton-village-grill-new",
          "x-shop-canonical-slug": "brixton-village-grill-new",
        },
      ],
      [/\/products$/, 404, null],
    ]) as unknown as typeof fetch

    await expect(loadShopDetail("brixton-village-grill-old")).resolves.toEqual({
      state: "redirect",
      canonicalSlug: "brixton-village-grill-new",
    })
  })

  it("falls back to the Location header's trailing segment when X-Shop-Canonical-Slug is absent on a 301 (39-09, RWO-028)", async () => {
    global.fetch = mockFetch([
      [
        /\/public\/shops\/[^/?]+$/,
        301,
        null,
        {
          location: "/public/shops/peckham-kitchen-2026",
        },
      ],
      [/\/products$/, 404, null],
    ]) as unknown as typeof fetch

    await expect(loadShopDetail("peckham-kitchen-old")).resolves.toEqual({
      state: "redirect",
      canonicalSlug: "peckham-kitchen-2026",
    })
  })
})

describe("loadShopList", () => {
  it("passes page, size and a trimmed q", async () => {
    const fetchMock = mockFetch([
      [/\/public\/shops\?/, 200, { content: [shop], totalPages: 1, totalElements: 1 }],
    ]) as unknown as jest.Mock
    global.fetch = fetchMock as unknown as typeof fetch

    const r = await loadShopList({ page: 2, size: 12, q: "  jollof  " })
    expect(r.state).toBe("ok")
    const url = String(fetchMock.mock.calls[0][0])
    expect(url).toContain("page=2")
    expect(url).toContain("size=12")
    expect(url).toContain("q=jollof")
  })

  it("omits q entirely when it is blank", async () => {
    const fetchMock = mockFetch([
      [/\/public\/shops\?/, 200, { content: [], totalPages: 0, totalElements: 0 }],
    ]) as unknown as jest.Mock
    global.fetch = fetchMock as unknown as typeof fetch

    await loadShopList({ q: "   " })
    expect(String(fetchMock.mock.calls[0][0])).not.toContain("q=")
  })

  it("maps a 404 on the directory to defer — the list always exists", async () => {
    global.fetch = mockFetch([[/\/public\/shops\?/, 404, null]]) as unknown as typeof fetch
    await expect(loadShopList()).resolves.toEqual({ state: "defer" })
  })
})

describe("loadAllShopSlugs", () => {
  it("returns every slug for the sitemap", async () => {
    global.fetch = mockFetch([
      [
        /\/public\/shops\?/,
        200,
        { content: [shop, { ...shop, slug: "mama-ades-kitchen" }], totalPages: 1, totalElements: 2 },
      ],
    ]) as unknown as typeof fetch

    await expect(loadAllShopSlugs()).resolves.toEqual([
      "brixton-village-grill",
      "mama-ades-kitchen",
    ])
  })

  it("degrades to [] rather than throwing, so /sitemap.xml cannot 500", async () => {
    global.fetch = jest.fn(async () => {
      throw new Error("upstream down")
    }) as unknown as typeof fetch

    await expect(loadAllShopSlugs()).resolves.toEqual([])
  })
})

describe("allProducts", () => {
  it("flattens the category map", () => {
    expect(
      allProducts({ Mains: [product], Sides: [{ ...product, id: "p2" }] })
    ).toHaveLength(2)
  })
})

/**
 * loadFeaturedShops (quick 261102-r6u, D-02/D-03). Featured kitchens are
 * decoration on top of the real listing: any failure degrades to [] (today's
 * row) and never throws or defers the landing page.
 */
describe("loadFeaturedShops", () => {
  it("requests /public/featured-shops uncached and returns the array on 200", async () => {
    const fetchMock = mockFetch([[/\/public\/featured-shops$/, 200, [shop]]])
    global.fetch = fetchMock as unknown as typeof fetch
    const featured = await loadFeaturedShops()
    expect(featured.map((s) => s.slug)).toEqual(["brixton-village-grill"])
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toMatch(/\/public\/featured-shops$/)
    expect(init.cache).toBe("no-store")
  })

  it("returns [] on a 500 and on a 429, never throwing", async () => {
    for (const status of [500, 429]) {
      global.fetch = mockFetch([[/\/public\/featured-shops$/, status, { title: "x" }]]) as unknown as typeof fetch
      await expect(loadFeaturedShops()).resolves.toEqual([])
    }
  })

  it("returns [] on a network failure, never throwing", async () => {
    global.fetch = jest.fn(async () => {
      throw new TypeError("fetch failed")
    }) as unknown as typeof fetch
    await expect(loadFeaturedShops()).resolves.toEqual([])
  })
})

/**
 * Quick 261102-vwa. The public rate limiter buckets per client address (`rl:public:{ip}`), and the
 * socket peer of a server-side read is the frontend pod, so without the visitor's chain every
 * SSR read of every visitor shares one bucket (#1104 r1, #1105 r1). The loaders forward exactly
 * one header, X-Forwarded-For, byte-for-byte; core's ClientIpResolver alone decides whom to trust.
 */
describe("forwards the visitor's X-Forwarded-For chain verbatim (quick 261102-vwa)", () => {
  const CHAIN = "not-an-ip, 192.0.2.66, 198.51.100.7,203.0.113.9"
  const listing = () =>
    mockFetch([[/\/public\/shops\?/, 200, { content: [shop], totalPages: 1, totalElements: 1 }]])
  const initOf = (fetchMock: jest.Mock, i = 0) => fetchMock.mock.calls[i][1] as RequestInit

  it("T1 sends the chain exactly as received, beside cache no-store and redirect manual", async () => {
    headersMock.mockResolvedValue(new Headers({ "x-forwarded-for": CHAIN }))
    const fetchMock = listing()
    global.fetch = fetchMock as unknown as typeof fetch

    await expect(loadShopList()).resolves.toMatchObject({ state: "ok" })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const init = initOf(fetchMock)
    expect(new Headers(init.headers).get("x-forwarded-for")).toBe(CHAIN)
    expect(init.cache).toBe("no-store")
    expect(init.redirect).toBe("manual")
  })

  it("T2 sends X-Forwarded-For and no other request header", async () => {
    headersMock.mockResolvedValue(
      new Headers({
        "x-forwarded-for": CHAIN,
        cookie: "jtoye-customer-access=secret",
        authorization: "Bearer secret",
        "x-real-ip": "203.0.113.50",
        forwarded: "for=203.0.113.51",
        "x-nonce": "nonce-1",
        host: "frontend.internal",
      })
    )
    const fetchMock = listing()
    global.fetch = fetchMock as unknown as typeof fetch

    await loadShopList()
    expect([...new Headers(initOf(fetchMock).headers).keys()]).toEqual(["x-forwarded-for"])
  })

  it("T3 sends no X-Forwarded-For when the request carried none", async () => {
    headersMock.mockResolvedValue(new Headers({ cookie: "a=b", "x-nonce": "nonce-1" }))
    const fetchMock = listing()
    global.fetch = fetchMock as unknown as typeof fetch

    await expect(loadShopList()).resolves.toMatchObject({ state: "ok" })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(new Headers(initOf(fetchMock).headers).has("x-forwarded-for")).toBe(false)
  })

  it("T4 outside a request scope sends no header and never throws", async () => {
    // Positive control: the REAL next/headers rejects here, so the default mock is not a no-op.
    const actual = jest.requireActual("next/headers")
    await expect(Promise.resolve().then(() => actual.headers())).rejects.toThrow(/outside a request scope/)

    let fetchMock = listing()
    global.fetch = fetchMock as unknown as typeof fetch
    await expect(loadShopList()).resolves.toMatchObject({ state: "ok" })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(new Headers(initOf(fetchMock).headers).has("x-forwarded-for")).toBe(false)

    headersMock.mockImplementation(() => Promise.reject(new Error("no request store")))
    fetchMock = listing()
    global.fetch = fetchMock as unknown as typeof fetch
    await expect(loadShopList()).resolves.toMatchObject({ state: "ok" })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(new Headers(initOf(fetchMock).headers).has("x-forwarded-for")).toBe(false)
  })
})

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
  allProducts,
} from "@/lib/storefront-server"
import type { PublicProduct, PublicShop } from "@/types/storefront"

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
function mockFetch(routes: Array<[RegExp, number, unknown]>, fallback: unknown = {}) {
  return jest.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    for (const [pattern, status, body] of routes) {
      if (pattern.test(url)) {
        return {
          ok: status >= 200 && status < 300,
          status,
          json: async () => body,
        } as Response
      }
    }
    return { ok: true, status: 200, json: async () => fallback } as Response
  })
}

const realFetch = global.fetch

afterEach(() => {
  global.fetch = realFetch
  jest.restoreAllMocks()
})

describe("loadShopDetail", () => {
  it("returns ok with the shop, its menu and a server-computed open flag", async () => {
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
    // Present at all — the pill has to be in the served HTML, and it is a
    // boolean the client must not have to recompute.
    expect(typeof r.data.isOpen).toBe("boolean")
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
      [/\/promotions$/, 500, null],
      [/\/announcements$/, 500, null],
    ]) as unknown as typeof fetch

    const r = await loadShopDetail("x")
    expect(r.state).toBe("ok")
    if (r.state !== "ok") return
    expect(r.data.reviews).toEqual([])
    expect(r.data.reviewCount).toBe(0)
    expect(r.data.promotions).toEqual([])
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

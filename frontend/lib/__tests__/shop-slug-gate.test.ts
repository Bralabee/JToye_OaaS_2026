/**
 * @jest-environment node
 */
/**
 * SW-CLR-17: the proxy's kitchen check (lib/shop-slug-gate.ts). Only core's own 404 is a verdict;
 * every other answer, and every failure, passes the request to the page unchanged (fail open).
 */
import { checkShopSlug, shopSlugOf } from "@/lib/shop-slug-gate"

function res(status: number, headers: Record<string, string> = {}): Response {
  return new Response(status === 204 || status === 301 ? null : "{}", { status, headers })
}

const noHeaders = new Headers()

describe("shopSlugOf", () => {
  it("returns the slug of a kitchen page only", () => {
    expect(shopSlugOf("/shop/mama-ades-kitchen")).toBe("mama-ades-kitchen")
    expect(shopSlugOf("/shop/mama-ades-kitchen/")).toBe("mama-ades-kitchen")
  })

  it("ignores the basket, checkout and order pages of a kitchen", () => {
    expect(shopSlugOf("/shop/mama-ades-kitchen/cart")).toBeNull()
    expect(shopSlugOf("/shop/mama-ades-kitchen/checkout")).toBeNull()
    expect(shopSlugOf("/shop/mama-ades-kitchen/orders/JT-1")).toBeNull()
  })

  it("ignores /shop itself and the routes that live under /shop/", () => {
    expect(shopSlugOf("/shop")).toBeNull()
    for (const route of ["account", "auth", "orders", "signin"]) {
      expect(shopSlugOf(`/shop/${route}`)).toBeNull()
    }
    expect(shopSlugOf("/track")).toBeNull()
  })

  it("returns null for a slug that cannot be decoded", () => {
    expect(shopSlugOf("/shop/%E0%A4%A")).toBeNull()
  })
})

describe("checkShopSlug", () => {
  it("is notfound only when core answers 404", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(res(404))
    await expect(checkShopSlug("nope", noHeaders, fetchImpl)).resolves.toEqual({ kind: "notfound" })
  })

  it("passes a live kitchen", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(res(200))
    await expect(checkShopSlug("mama", noHeaders, fetchImpl)).resolves.toEqual({ kind: "pass" })
  })

  it.each([429, 500, 502, 503])("fails open on %i", async (status) => {
    const fetchImpl = jest.fn().mockResolvedValue(res(status))
    await expect(checkShopSlug("mama", noHeaders, fetchImpl)).resolves.toEqual({ kind: "pass" })
  })

  it("fails open when core cannot be reached or times out", async () => {
    const fetchImpl = jest.fn().mockRejectedValue(new DOMException("timed out", "TimeoutError"))
    await expect(checkShopSlug("mama", noHeaders, fetchImpl)).resolves.toEqual({ kind: "pass" })
  })

  it("redirects a retired slug to its canonical slug", async () => {
    const fetchImpl = jest
      .fn()
      .mockResolvedValue(res(301, { "X-Shop-Canonical-Slug": "new-name", Location: "/api/v1/public/shops/new-name" }))
    await expect(checkShopSlug("old-name", noHeaders, fetchImpl)).resolves.toEqual({
      kind: "redirect",
      canonicalSlug: "new-name",
    })
  })

  it("falls back to the Location header, and passes a 301 with no target", async () => {
    const viaLocation = jest.fn().mockResolvedValue(res(301, { Location: "/api/v1/public/shops/new-name" }))
    await expect(checkShopSlug("old", noHeaders, viaLocation)).resolves.toEqual({
      kind: "redirect",
      canonicalSlug: "new-name",
    })
    const noTarget = jest.fn().mockResolvedValue(res(301))
    await expect(checkShopSlug("old", noHeaders, noTarget)).resolves.toEqual({ kind: "pass" })
  })

  it("reads the one shop record, unredirected, carrying the visitor's X-Forwarded-For only", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(res(200))
    const headers = new Headers({ "x-forwarded-for": "203.0.113.7", cookie: "secret=1" })
    await checkShopSlug("a b", headers, fetchImpl)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toMatch(/\/public\/shops\/a%20b$/)
    expect(init.redirect).toBe("manual")
    expect(init.headers).toEqual({ "X-Forwarded-For": "203.0.113.7" })
    expect(init.signal).toBeDefined()
  })
})

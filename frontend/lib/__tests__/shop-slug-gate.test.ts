/**
 * @jest-environment node
 */
/**
 * SW-CLR-17: the proxy's kitchen check (lib/shop-slug-gate.ts). Only core's own 404 is a verdict;
 * every other answer, and every failure, passes the request to the page unchanged (fail open).
 */
import {
  checkShopSlug,
  isDocumentRequest,
  notFoundRewriteTarget,
  shopSlugOf,
} from "@/lib/shop-slug-gate"

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

describe("isDocumentRequest (PR #1111 review R1)", () => {
  const none = new URLSearchParams()
  it("is true for a plain page load", () => {
    expect(isDocumentRequest(new Headers({ accept: "text/html" }), none)).toBe(true)
  })
  it("is false for a <Link> prefetch, an RSC navigation, or an _rsc request", () => {
    expect(isDocumentRequest(new Headers({ rsc: "1", "next-router-prefetch": "1" }), none)).toBe(false)
    expect(isDocumentRequest(new Headers({ rsc: "1" }), none)).toBe(false)
    expect(isDocumentRequest(new Headers({ "next-router-prefetch": "1" }), none)).toBe(false)
    expect(isDocumentRequest(new Headers(), new URLSearchParams("_rsc=abc"))).toBe(false)
  })
})

describe("notFoundRewriteTarget (runtime defect after the persona close-out merge)", () => {
  // Next treats a rewrite as INTERNAL only when its origin is {protocol}://{HOSTNAME}:{PORT}, with the
  // protocol read from X-Forwarded-Proto; the URL a proxy sees is built from http://localhost:PORT. A
  // rewrite to the request URL on a server bound to 0.0.0.0 was proxied to itself and hung for 30 s
  // (HTTP 500 instead of 404).
  const seen = new URL("http://localhost:3000/shop/no-such-kitchen?utm=a")
  const none = new Headers()
  const tls = new Headers({ "x-forwarded-proto": "https" })

  it("carries the origin Next compares against when the server is bound with HOSTNAME", () => {
    const target = notFoundRewriteTarget(seen, none, { HOSTNAME: "0.0.0.0", PORT: "3000" })
    expect(target.origin).toBe("http://0.0.0.0:3000")
    expect(target.pathname + target.search).toBe("/shop/no-such-kitchen?utm=a")
  })

  it("follows PORT, and defaults to 3000 when PORT is absent or not a number", () => {
    expect(notFoundRewriteTarget(seen, none, { HOSTNAME: "0.0.0.0", PORT: "8081" }).origin).toBe(
      "http://0.0.0.0:8081"
    )
    expect(notFoundRewriteTarget(seen, none, { HOSTNAME: "0.0.0.0" }).origin).toBe("http://0.0.0.0:3000")
    expect(notFoundRewriteTarget(seen, none, { HOSTNAME: "0.0.0.0", PORT: "x" }).origin).toBe(
      "http://0.0.0.0:3000"
    )
  })

  it("keeps the request's own host when HOSTNAME is unset (next dev, next start on the default host)", () => {
    expect(notFoundRewriteTarget(seen, none, {}).href).toBe(seen.href)
    expect(notFoundRewriteTarget(seen, none, { HOSTNAME: "  " }).href).toBe(seen.href)
  })

  it("uses https when X-Forwarded-Proto says so (a TLS-terminating ingress), with or without HOSTNAME", () => {
    expect(notFoundRewriteTarget(seen, tls, { HOSTNAME: "0.0.0.0", PORT: "3000" }).origin).toBe(
      "https://0.0.0.0:3000"
    )
    expect(notFoundRewriteTarget(seen, tls, {}).origin).toBe("https://localhost:3000")
    expect(
      notFoundRewriteTarget(seen, new Headers({ "x-forwarded-proto": "http" }), { HOSTNAME: "0.0.0.0" }).origin
    ).toBe("http://0.0.0.0:3000")
  })

  it("brackets an IPv6 host and never carries the public host of the request", () => {
    const pub = new URL("https://olajay.co.uk/shop/x")
    expect(notFoundRewriteTarget(pub, none, { HOSTNAME: "::", PORT: "3000" }).origin).toBe("http://[::]:3000")
    expect(notFoundRewriteTarget(pub, none, { HOSTNAME: "0.0.0.0" }).host).not.toContain("olajay")
  })
})

/**
 * @jest-environment node
 */
/**
 * SW-CLR-17: the proxy's kitchen check (lib/shop-slug-gate.ts). Only core's own 404 is a verdict;
 * every other answer, and every failure, passes the request to the page unchanged (fail open).
 */
import {
  KITCHEN_NOT_FOUND_PAGE_TIMEOUT_MS,
  SHOP_SLUG_CHECKED_PARAM,
  checkShopSlug,
  fetchKitchenNotFoundPage,
  isDocumentRequest,
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
  it("reads the browser's fetch metadata, which survives Next stripping the flight headers (PR #1126)", () => {
    expect(isDocumentRequest(new Headers({ "sec-fetch-dest": "empty", "sec-fetch-mode": "cors" }), none)).toBe(false)
    expect(isDocumentRequest(new Headers({ "sec-fetch-dest": "document", "sec-fetch-mode": "navigate" }), none)).toBe(true)
    expect(isDocumentRequest(new Headers({ "sec-fetch-dest": "iframe" }), none)).toBe(true)
    // A crawler or a plain HTTP client sends none of it and is a document.
    expect(isDocumentRequest(new Headers({ "user-agent": "Googlebot" }), none)).toBe(true)
  })
})

describe("fetchKitchenNotFoundPage (runtime defect after the persona close-out merge)", () => {
  // The proxy used to rewrite an unknown kitchen to its own URL; on a server bound to 0.0.0.0 Next proxied
  // that to itself in a loop (HTTP 500). It now renders the page itself, with the checked marker so that
  // second request is not gated again, and answers with that page's body and headers at status 404.
  const url = new URL("http://localhost:3000/shop/no-such-kitchen?utm=a")

  const page = (status = 200, headers: Record<string, string> = {}) =>
    new Response("<html>Kitchen not found</html>", {
      status,
      headers: { "content-type": "text/html", "content-security-policy": "script-src 'nonce-abc'", ...headers },
    })

  it("asks the same URL once more with the checked marker, forwarding only the visitor's X-Forwarded-For", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(page())
    const res = await fetchKitchenNotFoundPage(
      url,
      new Headers({ "x-forwarded-for": "203.0.113.9", cookie: "session=secret" }),
      fetchImpl
    )
    expect(res).not.toBeNull()
    const [target, init] = fetchImpl.mock.calls[0]
    expect((target as URL).pathname).toBe("/shop/no-such-kitchen")
    expect((target as URL).searchParams.get("utm")).toBe("a")
    expect((target as URL).searchParams.get(SHOP_SLUG_CHECKED_PARAM)).toBe("1")
    expect(init.headers).toEqual({ "X-Forwarded-For": "203.0.113.9" })
    expect(init.redirect).toBe("manual")
    expect(init.cache).toBe("no-store")
  })

  it("answers 404 with the page's body and its CSP (the nonce it was rendered with), minus hop-by-hop headers", async () => {
    const res = await fetchKitchenNotFoundPage(
      url,
      noHeaders,
      jest.fn().mockResolvedValue(page(200, { "content-length": "999", "transfer-encoding": "chunked" }))
    )
    expect(res!.status).toBe(404)
    expect(await res!.text()).toContain("Kitchen not found")
    expect(res!.headers.get("content-security-policy")).toBe("script-src 'nonce-abc'")
    expect(res!.headers.get("content-type")).toBe("text/html")
    expect(res!.headers.get("content-length")).toBeNull()
    expect(res!.headers.get("transfer-encoding")).toBeNull()
  })

  it("is null (the request passes to the page) on a 5xx, a redirect, a network error or a timeout", async () => {
    expect(await fetchKitchenNotFoundPage(url, noHeaders, jest.fn().mockResolvedValue(page(500)))).toBeNull()
    expect(await fetchKitchenNotFoundPage(url, noHeaders, jest.fn().mockResolvedValue(page(302)))).toBeNull()
    expect(await fetchKitchenNotFoundPage(url, noHeaders, jest.fn().mockRejectedValue(new Error("ECONNREFUSED")))).toBeNull()
    expect(KITCHEN_NOT_FOUND_PAGE_TIMEOUT_MS).toBeLessThanOrEqual(10_000)
  })

  it("asks the origin Next recorded for this server, not the request URL (Auth.js rewrites that to NEXTAUTH_URL)", async () => {
    const publicUrl = new URL("https://app.example.test/shop/no-such-kitchen")
    const fetchImpl = jest.fn().mockResolvedValue(page())
    await fetchKitchenNotFoundPage(publicUrl, noHeaders, fetchImpl, 1000, "http://localhost:3000")
    const target = fetchImpl.mock.calls[0][0] as URL
    expect(target.origin).toBe("http://localhost:3000")
    expect(target.pathname).toBe("/shop/no-such-kitchen")
    // Without it (a Next that no longer sets the variable) the request URL is the fallback.
    const fallback = jest.fn().mockResolvedValue(page())
    await fetchKitchenNotFoundPage(publicUrl, noHeaders, fallback, 1000, undefined)
    expect((fallback.mock.calls[0][0] as URL).origin).toBe("https://app.example.test")
  })

  it("does not mutate the request URL", async () => {
    await fetchKitchenNotFoundPage(url, noHeaders, jest.fn().mockResolvedValue(page()))
    expect(url.searchParams.has(SHOP_SLUG_CHECKED_PARAM)).toBe(false)
  })
})

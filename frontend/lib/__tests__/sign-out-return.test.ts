/**
 * SW-JRD-08 — where a customer sign-out lands, and that `customerLogout` carries it.
 *
 * A sign-out returns to the page it was made on when that page is PUBLIC, and to /shop otherwise:
 *  - a page `sitemap.xml` lists (lib/public-routes.ts, the same list) returns to itself;
 *  - anything under a shop's own path returns to that shop's page (/shop/<slug>): its basket and
 *    checkout included, so the customer stays at that kitchen (#1109 review round 1);
 *  - a shop's order page and every customer-private route (account, my orders, sign-in, the auth
 *    callback, /track) falls back to /shop.
 */

import fs from "fs"
import path from "path"
import { RESERVED_SHOP_SEGMENTS, signOutReturnPath } from "@/lib/sign-out-return"
import { PUBLIC_STATIC_ROUTES } from "@/lib/public-routes"
import { SHOP_SLUG_PATTERN } from "@/lib/shop-slug"
import { customerLogout } from "@/lib/customer-auth"

describe("signOutReturnPath", () => {
  it.each([
    ["/shop", "/shop"],
    ["/shop/", "/shop"],
    ["/shop/rosies-kitchen", "/shop/rosies-kitchen"],
    ["/shop/peckham-jollof-co", "/shop/peckham-jollof-co"],
    ["/shop/rosies-kitchen/cart", "/shop/rosies-kitchen"],
    ["/shop/rosies-kitchen/checkout", "/shop/rosies-kitchen"],
    ["/", "/"],
    ["/for-operators", "/for-operators"],
    ["/legal/cookies", "/legal/cookies"],
    ["/legal/privacy/", "/legal/privacy"],
  ])("returns to the public page %s -> %s", (input, expected) => {
    expect(signOutReturnPath(input)).toBe(expected)
  })

  it.each([
    "/shop/account",
    "/shop/orders",
    "/shop/signin",
    "/shop/auth/callback",
    "/shop/rosies-kitchen/orders/ORD-1",
    "/shop/Rosies-Kitchen",
    "/track",
    "/dashboard",
    "/unsubscribe",
    "//evil.example",
    "/shop/%2F%2Fevil.example",
    "",
  ])("falls back to /shop from %s", (input) => {
    expect(signOutReturnPath(input)).toBe("/shop")
  })

  it("falls back to /shop with no pathname at all", () => {
    expect(signOutReturnPath(null)).toBe("/shop")
    expect(signOutReturnPath(undefined)).toBe("/shop")
  })

  it("every public static route returns to itself (control: the list is not empty)", () => {
    expect(PUBLIC_STATIC_ROUTES.length).toBeGreaterThan(3)
    for (const { path: p } of PUBLIC_STATIC_ROUTES) expect(signOutReturnPath(p)).toBe(p)
  })
})

describe("the copies that could drift", () => {
  it("RESERVED_SHOP_SEGMENTS is exactly the static route directories under app/shop", () => {
    const shopDir = path.join(__dirname, "..", "..", "app", "shop")
    const routeDirs = fs
      .readdirSync(shopDir, { withFileTypes: true })
      .filter((d) => d.isDirectory() && !d.name.startsWith("[") && !d.name.startsWith("_"))
      .map((d) => d.name)
      .sort()
    // Floor: the four that exist today, so an unreadable directory cannot pass as "no routes".
    expect(routeDirs.length).toBeGreaterThanOrEqual(4)
    expect([...RESERVED_SHOP_SEGMENTS].sort()).toEqual(routeDirs)
  })

  it("the shop slug pattern is lowercase only, as CreateShopRequest's @Pattern is", () => {
    expect(SHOP_SLUG_PATTERN.test("rosies-kitchen")).toBe(true)
    expect(SHOP_SLUG_PATTERN.flags).not.toContain("i")
    expect(SHOP_SLUG_PATTERN.test("Rosies-Kitchen")).toBe(false)
    expect(SHOP_SLUG_PATTERN.test("a--b")).toBe(false)
    expect(SHOP_SLUG_PATTERN.test("-a")).toBe(false)
  })
})

// customerLogout() ends by assigning window.location.href, which jsdom reports (not throws) as
// unimplemented navigation; silenced as in customer-auth-signout-clears-carts.test.ts.
const realConsoleError = console.error
beforeAll(() => {
  console.error = (...args: unknown[]) => {
    if (String(args[0]).includes("Not implemented: navigation")) return
    realConsoleError(...args)
  }
})
afterAll(() => {
  console.error = realConsoleError
})

describe("customerLogout(returnTo)", () => {
  function stubFetch(logoutUrlBody: { url?: string } | null) {
    const calls: string[] = []
    global.fetch = jest.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url.includes("logout-url")) {
        return logoutUrlBody
          ? ({ ok: true, json: async () => logoutUrlBody } as Response)
          : ({ ok: false, json: async () => ({}) } as Response)
      }
      return { ok: true, json: async () => ({}) } as Response
    }) as unknown as typeof fetch
    return calls
  }

  it("asks the server for an end-session URL that returns to the given page", async () => {
    const calls = stubFetch({ url: "http://kc.example/logout" })
    await customerLogout("/shop/rosies-kitchen")
    expect(calls[0]).toBe("/api/customer-auth/logout-url?redirect=%2Fshop%2Frosies-kitchen")
  })

  it("keeps /shop as the default destination (no argument)", async () => {
    const calls = stubFetch(null)
    await customerLogout()
    expect(calls[0]).toBe("/api/customer-auth/logout-url?redirect=%2Fshop")
  })

  it("never takes an off-site destination", async () => {
    const calls = stubFetch(null)
    await customerLogout("//evil.example/x")
    expect(calls[0]).toBe("/api/customer-auth/logout-url?redirect=%2Fshop")
  })
})

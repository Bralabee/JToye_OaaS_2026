/**
 * SW-JRD-08 — where a storefront sign-out lands, and that `customerLogout` carries it.
 *
 * Allow-list, not deny-list: only /shop and a shop's own page (/shop/<slug>) are returned to. Every
 * other path — account, my orders, sign-in, the auth callback, a basket, a checkout, an order page —
 * falls back to /shop, because each is personal or mid-journey and the sign-out has just removed
 * the session and the baskets it depended on.
 */

import { signOutReturnPath } from "@/lib/sign-out-return"
import { customerLogout } from "@/lib/customer-auth"

describe("signOutReturnPath", () => {
  it.each([
    ["/shop", "/shop"],
    ["/shop/", "/shop"],
    ["/shop/rosies-kitchen", "/shop/rosies-kitchen"],
    ["/shop/peckham-jollof-co", "/shop/peckham-jollof-co"],
  ])("returns to the public page %s -> %s", (input, expected) => {
    expect(signOutReturnPath(input)).toBe(expected)
  })

  it.each([
    "/shop/account",
    "/shop/orders",
    "/shop/signin",
    "/shop/auth/callback",
    "/shop/rosies-kitchen/cart",
    "/shop/rosies-kitchen/checkout",
    "/shop/rosies-kitchen/orders/ORD-1",
    "/dashboard",
    "/",
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

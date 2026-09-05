/**
 * `lib/safe-return-to.ts` — the ONE same-origin redirect sanitiser (PR #726 low a).
 *
 * The behavioural matrix (what is rejected and why) lives in
 * `customer-auth-return-to.test.ts`, which exercises the storefront re-export.
 * This file pins the two things that changed when the function moved: the
 * fallback is now a parameter (the vendor route lands on `/auth/signin`, not
 * `/shop`), and the storefront's `safeReturnTo` is the SAME function, not a
 * second copy that could drift into the weaker one the vendor route had.
 */
import { safeReturnTo } from "@/lib/safe-return-to"
import { safeReturnTo as storefrontSafeReturnTo } from "@/lib/customer-auth"

describe("safeReturnTo — one function, two realms", () => {
  it("is the same function object the storefront module re-exports (one sanitiser, not two)", () => {
    expect(storefrontSafeReturnTo).toBe(safeReturnTo)
  })

  it("defaults the fallback to /shop so every existing storefront caller is unchanged", () => {
    expect(safeReturnTo("//evil.example")).toBe("/shop")
    expect(safeReturnTo(null)).toBe("/shop")
  })

  it.each([
    ["protocol-relative", "//evil.com"],
    ["backslash trick", "/\\evil.com"],
    ["interior backslash", "/dashboard\\@evil.com"],
    ["javascript:", "javascript:alert(1)"],
    ["absolute https", "https://evil.com"],
    ["padded absolute", "  https://evil.com"],
  ])("%s -> the CALLER's fallback (/auth/signin), never a hard-coded /shop", (_label, raw) => {
    expect(safeReturnTo(raw, "/auth/signin")).toBe("/auth/signin")
  })

  it("honours a legitimate relative path regardless of which fallback was supplied", () => {
    expect(safeReturnTo("/dashboard/orders", "/auth/signin")).toBe("/dashboard/orders")
    expect(safeReturnTo(" /dashboard/orders ", "/auth/signin")).toBe("/dashboard/orders")
  })
})

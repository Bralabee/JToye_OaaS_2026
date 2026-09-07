/**
 * @jest-environment node
 *
 * `lib/vendor-logout-state.ts` — the OIDC RP-initiated-logout `state` binding for
 * the vendor sign-out (PR #726 review, M4).
 *
 * The routes' own suites prove the END-TO-END shape (cookie set on `logout-url`,
 * clear gated on `logout-complete`). This file pins the primitives they lean on:
 * the matcher's refusal cases (the ones a `===` would get right by accident and
 * a `timingSafeEqual` call would THROW on), the cookie scope, and the ceiling on
 * the TTL the review set.
 */
import {
  VENDOR_LOGOUT_STATE_COOKIE,
  VENDOR_LOGOUT_STATE_COOKIE_PATH,
  VENDOR_LOGOUT_STATE_TTL_SECONDS,
  isHttpsOrigin,
  mintVendorLogoutState,
  vendorLogoutStateClearingOptions,
  vendorLogoutStateCookieOptions,
  vendorLogoutStateMatches,
} from "@/lib/vendor-logout-state"

describe("vendorLogoutStateMatches", () => {
  const STATE = "3f9c1a2e-7b44-4c0d-9e1a-0f6d2b8c4a11"

  it("matches an identical value", () => {
    expect(vendorLogoutStateMatches(STATE, STATE)).toBe(true)
  })

  it.each([
    ["presented missing", null, STATE],
    ["presented undefined", undefined, STATE],
    ["expected missing (no cookie)", STATE, undefined],
    ["both missing", null, null],
    ["presented empty", "", STATE],
    ["expected empty", STATE, ""],
    ["both empty", "", ""],
  ])("refuses when %s — an absent side is never a match", (_label, presented, expected) => {
    expect(vendorLogoutStateMatches(presented, expected)).toBe(false)
  })

  it("refuses a different value of the SAME length (the constant-time path is exercised)", () => {
    expect(vendorLogoutStateMatches("00000000-0000-4000-8000-000000000000", STATE)).toBe(false)
  })

  it("refuses a prefix / a longer value WITHOUT throwing (timingSafeEqual would throw on unequal lengths)", () => {
    expect(() => vendorLogoutStateMatches(STATE.slice(0, -1), STATE)).not.toThrow()
    expect(vendorLogoutStateMatches(STATE.slice(0, -1), STATE)).toBe(false)
    expect(vendorLogoutStateMatches(`${STATE}0`, STATE)).toBe(false)
  })

  it("compares bytes, not code points: a multi-byte lookalike of equal string length is refused", () => {
    // Same JS string length as "aa" but different UTF-8 byte length — must be refused, not thrown.
    expect(vendorLogoutStateMatches("aa", "é")).toBe(false)
    expect(vendorLogoutStateMatches("é", "aa")).toBe(false)
  })
})

describe("mintVendorLogoutState", () => {
  it("mints UUID-shaped, distinct values", () => {
    const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    const a = mintVendorLogoutState()
    const b = mintVendorLogoutState()
    expect(a).toMatch(UUID)
    expect(b).toMatch(UUID)
    expect(a).not.toBe(b)
  })
})

describe("the cookie contract", () => {
  it("is scoped to the vendor-auth routes, httpOnly, Lax, and lives no longer than the 5-minute review ceiling", () => {
    expect(VENDOR_LOGOUT_STATE_COOKIE).toBe("jtoye-vendor-logout-state")
    expect(VENDOR_LOGOUT_STATE_COOKIE_PATH).toBe("/api/vendor-auth")
    expect(VENDOR_LOGOUT_STATE_TTL_SECONDS).toBeGreaterThan(0)
    expect(VENDOR_LOGOUT_STATE_TTL_SECONDS).toBeLessThanOrEqual(300)

    const set = vendorLogoutStateCookieOptions(true)
    expect(set).toEqual({
      httpOnly: true,
      sameSite: "lax",
      secure: true,
      path: "/api/vendor-auth",
      maxAge: VENDOR_LOGOUT_STATE_TTL_SECONDS,
    })
  })

  it("Secure follows the public origin's scheme, so an http compose stack does not fail closed", () => {
    expect(isHttpsOrigin("https://app.olajay.co.uk")).toBe(true)
    expect(isHttpsOrigin("http://app.jtoye.local")).toBe(false)
    expect(isHttpsOrigin(null)).toBe(false)
    expect(vendorLogoutStateCookieOptions(false).secure).toBe(false)
  })

  it("the clearing options are the set options with maxAge 0 — same scope, or the browser keeps the original", () => {
    const set = vendorLogoutStateCookieOptions(true)
    const clear = vendorLogoutStateClearingOptions(true)
    expect(clear).toEqual({ ...set, maxAge: 0 })
  })
})

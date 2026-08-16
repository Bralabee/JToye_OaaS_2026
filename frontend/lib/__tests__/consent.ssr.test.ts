/**
 * @jest-environment node
 */

/**
 * The consent store under a REAL server render (LGL-01, plan 31-16).
 *
 * WHY THIS IS A SEPARATE FILE RATHER THAN A CASE IN `consent.test.ts`:
 * the common idiom for faking SSR — `delete global.window` — is a NO-OP in this
 * project's jsdom. Probed directly: the `window` property is defined
 * `configurable: false`, `delete` returns `false`, and `typeof window` remains
 * `"object"` afterwards.
 *
 * What makes that worth a file of its own is that the fake SSR test does not
 * announce its own failure. Three of its four assertions are true with `window`
 * present as well, so it reads green while proving nothing whatsoever about the
 * server path. That is the "vacuous check" shape this project keeps paying for:
 * an assertion observed only passing may be incapable of failing.
 *
 * Under `@jest-environment node` there is genuinely no `window`, no `document`
 * and no `localStorage`, so the SSR guard is exercised rather than simulated.
 * The non-vacuity control below asserts that absence FIRST — otherwise this file
 * would silently degrade into a duplicate of the jsdom suite if the docblock
 * above were ever dropped.
 */
import {
  COOKIE_POLICY_VERSION,
  SHIPPED_CATEGORIES,
  accept,
  acknowledgeCookieNotice,
  isAllowed,
  loadWhenAllowed,
  onChange,
  readNoticeAck,
  register,
  shouldShowCookieNotice,
} from "@/lib/consent"

describe("consent store during SSR (no window)", () => {
  it("NON-VACUITY CONTROL: this file really is running without a window", () => {
    // If the docblock is ever lost, this fires and every assertion below is
    // revealed as a jsdom duplicate rather than an SSR proof.
    expect(typeof window).toBe("undefined")
  })

  it("imports without touching browser globals at module scope", () => {
    // A module that read `localStorage` at import time would throw on the server
    // and break every route. Reaching this line at all is the assertion; the
    // shipped config is checked so the import is not tree-shaken away.
    expect(SHIPPED_CATEGORIES.length).toBeGreaterThan(0)
  })

  it("never paints the notice on the server", () => {
    // The server cannot know whether THIS visitor already dismissed it, so
    // rendering it would flash on every page for everyone who already had. The
    // notice is `position: fixed` and out of flow, so appearing a frame later
    // on the client costs no layout shift — this is a free correctness win.
    expect(shouldShowCookieNotice()).toBe(false)
  })

  it("returns safe defaults for every read", () => {
    expect(readNoticeAck()).toBeNull()
    expect(isAllowed("strictly-necessary")).toBe(true) // essential, no storage needed
    expect(isAllowed("never-registered-anywhere")).toBe(false) // fails closed
  })

  it("attempts no write and throws nothing", () => {
    expect(() => acknowledgeCookieNotice()).not.toThrow()
    expect(() => accept("anything")).not.toThrow()
    // Still null: the write was skipped, not swallowed after landing somewhere.
    expect(readNoticeAck()).toBeNull()
    expect(COOKIE_POLICY_VERSION).toBeTruthy()
  })

  it("gates a non-essential category CLOSED on the server", () => {
    const unregister = register({
      id: "fixture-analytics",
      essential: false,
      label: "Fixture analytics",
      purpose: "Exists only so the gate can be proven.",
    })
    const load = jest.fn()

    expect(isAllowed("fixture-analytics")).toBe(false)
    expect(loadWhenAllowed("fixture-analytics", load)).toBe(false)
    expect(load).not.toHaveBeenCalled()

    unregister()
  })

  it("returns a no-op unsubscribe from onChange rather than throwing", () => {
    const unsubscribe = onChange(() => {})
    expect(typeof unsubscribe).toBe("function")
    expect(() => unsubscribe()).not.toThrow()
  })
})

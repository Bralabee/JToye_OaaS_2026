/**
 * The consent gate (LGL-01, plan 31-16) — and the reason this file exists at all.
 *
 * D-05's own warning: **a consent gate over zero categories cannot fail as
 * shipped.** The shipped configuration registers no non-essential category, so
 * every "nothing was blocked" assertion would pass identically against a working
 * gate and against a gate that does nothing whatsoever. A green suite over an
 * inert gate is precisely the failure mode this phase was told to avoid.
 *
 * So the gate is proven with a FIXTURE category that exists only here, and the
 * proof is two-armed in ONE test:
 *
 *   BLOCK ARM   before any choice, `isAllowed` is false AND the gated loader is
 *               never invoked. The second half is the load-bearing one — a false
 *               boolean says what the gate believes; an uncalled `jest.fn()` says
 *               what the gate DID.
 *   PERMIT ARM  after a recorded accept, both flip.
 *
 * And, separately, that the shipped configuration registers zero non-essential
 * categories. The two claims are deliberately NOT merged: "the gate works" and
 * "today it gates nothing" are different facts, and either one standing in for
 * the other is how an inert gate ships looking green.
 */
import {
  COOKIE_NOTICE_ACK_KEY,
  COOKIE_POLICY_VERSION,
  SHIPPED_CATEGORIES,
  acknowledgeCookieNotice,
  isAllowed,
  loadWhenAllowed,
  onChange,
  readNoticeAck,
  reject,
  accept,
  register,
  registeredCategories,
  shouldShowCookieNotice,
} from "@/lib/consent"

/** The fixture category. It exists ONLY in this file — nothing ships it. */
const FIXTURE = {
  id: "fixture-analytics",
  essential: false,
  label: "Fixture analytics",
  purpose: "Exists only so the gate can be proven to block and to permit.",
} as const

beforeEach(() => {
  window.localStorage.clear()
})

describe("the gate blocks, then permits — the arms that make a zero-category gate meaningful", () => {
  it("BLOCKS a gated script before a choice and PERMITS it after (both arms, one test)", () => {
    const unregister = register(FIXTURE)
    const load = jest.fn()

    // ---- BLOCK ARM -------------------------------------------------------
    // Not merely "isAllowed is false": the gated loader must not have RUN.
    // A gate that returns false and loads anyway passes the boolean assertion.
    expect(isAllowed(FIXTURE.id)).toBe(false)
    expect(loadWhenAllowed(FIXTURE.id, load)).toBe(false)
    expect(load).not.toHaveBeenCalled()

    // ---- the choice ------------------------------------------------------
    accept(FIXTURE.id)

    // ---- PERMIT ARM ------------------------------------------------------
    expect(isAllowed(FIXTURE.id)).toBe(true)
    expect(loadWhenAllowed(FIXTURE.id, load)).toBe(true)
    expect(load).toHaveBeenCalledTimes(1)

    unregister()
  })

  it("stops permitting after a reject — a previously loaded script is not loaded again", () => {
    const unregister = register(FIXTURE)
    const load = jest.fn()

    accept(FIXTURE.id)
    expect(loadWhenAllowed(FIXTURE.id, load)).toBe(true)
    expect(load).toHaveBeenCalledTimes(1)

    reject(FIXTURE.id)

    expect(isAllowed(FIXTURE.id)).toBe(false)
    expect(loadWhenAllowed(FIXTURE.id, load)).toBe(false)
    // Still 1 — the reject did not permit a second load.
    expect(load).toHaveBeenCalledTimes(1)

    unregister()
  })

  it("always allows an essential category, with no choice recorded", () => {
    const essential = SHIPPED_CATEGORIES.find((c) => c.essential)
    expect(essential).toBeDefined()
    expect(isAllowed(essential!.id)).toBe(true)

    const load = jest.fn()
    expect(loadWhenAllowed(essential!.id, load)).toBe(true)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it("FAILS CLOSED for an unregistered category — unknown is never allowed", () => {
    // An unknown id must not inherit "no choice recorded => allowed". Fail open
    // here would let a category load simply by being spelled wrong.
    expect(isAllowed("never-registered-anywhere")).toBe(false)

    const load = jest.fn()
    expect(loadWhenAllowed("never-registered-anywhere", load)).toBe(false)
    expect(load).not.toHaveBeenCalled()
  })

  it("keeps a stored choice for an id that is not currently registered failing closed", () => {
    // Accepting then unregistering must not leave a permitted orphan.
    const unregister = register(FIXTURE)
    accept(FIXTURE.id)
    expect(isAllowed(FIXTURE.id)).toBe(true)
    unregister()
    expect(isAllowed(FIXTURE.id)).toBe(false)
  })
})

describe("the shipped configuration", () => {
  it("registers ZERO non-essential categories, so no consent choice is presented today", () => {
    // Asserted SEPARATELY from the fixture arms above, on purpose. Together the
    // two say "the gate works" AND "today it gates nothing". Either alone is
    // misleading: this test on its own is satisfied by a gate that does nothing.
    expect(SHIPPED_CATEGORIES.filter((c) => !c.essential)).toEqual([])
  })

  it("does ship at least one essential category, so the config is not simply empty", () => {
    // Non-vacuity control for the assertion above: an empty SHIPPED_CATEGORIES
    // would satisfy "zero non-essential" while meaning the module is unwired.
    expect(SHIPPED_CATEGORIES.filter((c) => c.essential).length).toBeGreaterThan(0)
  })

  it("registers the shipped categories at module load", () => {
    const ids = registeredCategories().map((c) => c.id)
    for (const c of SHIPPED_CATEGORIES) expect(ids).toContain(c.id)
  })
})

describe("the versioned dismissal key", () => {
  it("stores the CURRENT policy version under the contracted key and reads it back", () => {
    acknowledgeCookieNotice()
    expect(window.localStorage.getItem(COOKIE_NOTICE_ACK_KEY)).toBe(COOKIE_POLICY_VERSION)
    expect(readNoticeAck()).toBe(COOKIE_POLICY_VERSION)
  })

  it("shows the notice when nothing is stored", () => {
    expect(shouldShowCookieNotice()).toBe(true)
  })

  it("does NOT show the notice once the current version is acknowledged", () => {
    acknowledgeCookieNotice()
    expect(shouldShowCookieNotice()).toBe(false)
  })

  it("shows the notice again when the stored version differs from the current policy version", () => {
    window.localStorage.setItem(COOKIE_NOTICE_ACK_KEY, "some-older-version")
    expect(shouldShowCookieNotice()).toBe(true)
  })
})

describe("resilience — the failure modes that never appear in a happy-path test", () => {
  it("survives localStorage throwing (Safari private mode) on read AND write", () => {
    const original = Object.getOwnPropertyDescriptor(window, "localStorage")
    const throwing = {
      getItem: () => {
        throw new DOMException("denied", "SecurityError")
      },
      setItem: () => {
        throw new DOMException("denied", "SecurityError")
      },
      removeItem: () => {
        throw new DOMException("denied", "SecurityError")
      },
    }
    Object.defineProperty(window, "localStorage", { value: throwing, configurable: true })

    try {
      // Reads return a safe default rather than propagating.
      expect(() => readNoticeAck()).not.toThrow()
      expect(readNoticeAck()).toBeNull()
      expect(() => shouldShowCookieNotice()).not.toThrow()
      expect(shouldShowCookieNotice()).toBe(true)
      // Writes swallow the exception.
      expect(() => acknowledgeCookieNotice()).not.toThrow()
      const unregister = register(FIXTURE)
      expect(() => accept(FIXTURE.id)).not.toThrow()
      // Storage is unavailable, so no choice can have been recorded: fail closed.
      expect(isAllowed(FIXTURE.id)).toBe(false)
      unregister()
    } finally {
      if (original) Object.defineProperty(window, "localStorage", original)
    }
  })

  it("returns safe defaults during SSR and attempts no write", () => {
    const originalWindow = global.window
    // @ts-expect-error — deliberately simulating a server render
    delete global.window
    try {
      expect(readNoticeAck()).toBeNull()
      // A server render must NOT paint the notice: it has no way to know whether
      // this visitor already dismissed it, and painting then removing is a flash.
      expect(shouldShowCookieNotice()).toBe(false)
      expect(() => acknowledgeCookieNotice()).not.toThrow()
      expect(isAllowed("never-registered-anywhere")).toBe(false)
    } finally {
      global.window = originalWindow
    }
    // The write attempted during SSR must not have landed.
    expect(window.localStorage.getItem(COOKIE_NOTICE_ACK_KEY)).toBeNull()
  })
})

describe("change notification", () => {
  it("notifies a subscriber in the SAME tab — the `storage` event only fires in OTHERS", () => {
    // The assertion that is easy to omit: manual testing in a single tab looks
    // fine whether or not the same-tab CustomEvent exists, because the tab that
    // wrote the value is also the tab that re-reads it on the next render.
    const cb = jest.fn()
    const unsubscribe = onChange(cb)

    acknowledgeCookieNotice()

    expect(cb).toHaveBeenCalled()
    unsubscribe()
  })

  it("notifies on a cross-tab `storage` event too", () => {
    const cb = jest.fn()
    const unsubscribe = onChange(cb)

    window.dispatchEvent(new StorageEvent("storage", { key: COOKIE_NOTICE_ACK_KEY }))

    expect(cb).toHaveBeenCalled()
    unsubscribe()
  })

  it("stops notifying after unsubscribe", () => {
    const cb = jest.fn()
    const unsubscribe = onChange(cb)
    unsubscribe()

    acknowledgeCookieNotice()

    expect(cb).not.toHaveBeenCalled()
  })

  it("notifies when a consent choice is recorded", () => {
    const unregister = register(FIXTURE)
    const cb = jest.fn()
    const unsubscribe = onChange(cb)

    accept(FIXTURE.id)

    expect(cb).toHaveBeenCalled()
    unsubscribe()
    unregister()
  })
})

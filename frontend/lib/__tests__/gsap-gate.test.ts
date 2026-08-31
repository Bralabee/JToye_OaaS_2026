import {
  canEnhance,
  DESKTOP_MOTION_QUERY,
  ENTRANCE_BUDGET_MS,
  entranceIsSafe,
  entranceIsSafeForMount,
  prefersDesktopMotion,
  splitWords,
  __resetEntranceMountGateForTests,
} from "@/lib/gsap-gate"

const HEADLINE = "Order from local kitchens. Or run yours."

describe("gsap-gate — prefersDesktopMotion", () => {
  it("enhances a wide desktop viewport with motion allowed", () => {
    expect(prefersDesktopMotion({ width: 1440, reducedMotion: false })).toBe(true)
  })

  it("does not enhance when reduced motion is requested", () => {
    expect(prefersDesktopMotion({ width: 1440, reducedMotion: true })).toBe(false)
  })

  it("does not enhance below the 768px breakpoint", () => {
    expect(prefersDesktopMotion({ width: 767, reducedMotion: false })).toBe(false)
  })

  it("enhances exactly at the 768px (Tailwind md) breakpoint", () => {
    expect(prefersDesktopMotion({ width: 768, reducedMotion: false })).toBe(true)
  })
})

describe("gsap-gate — DESKTOP_MOTION_QUERY", () => {
  it("targets md-and-up with a no-preference reduced-motion clause", () => {
    expect(DESKTOP_MOTION_QUERY).toContain("min-width: 768px")
    expect(DESKTOP_MOTION_QUERY).toContain("prefers-reduced-motion: no-preference")
  })
})

describe("gsap-gate — splitWords", () => {
  it("splits a headline into multiple .gsap-word spans without dropping text", () => {
    const h1 = document.createElement("h1")
    h1.textContent = HEADLINE
    const words = splitWords(h1)

    expect(words.length).toBeGreaterThan(1)
    for (const word of words) expect(word).toHaveClass("gsap-word")
    // Nothing dropped: concatenated textContent equals the original headline.
    expect(h1.textContent).toBe(HEADLINE)
  })

  it("is idempotent — calling twice does not double-wrap", () => {
    const h1 = document.createElement("h1")
    h1.textContent = HEADLINE

    const first = splitWords(h1)
    const second = splitWords(h1)

    expect(second.length).toBe(first.length)
    expect(h1.querySelectorAll(".gsap-word").length).toBe(first.length)
    expect(h1.textContent).toBe(HEADLINE)
  })

  it("preserves a nested child element's text inside word spans", () => {
    const h1 = document.createElement("h1")
    h1.appendChild(document.createTextNode("Order from local kitchens. "))
    const accent = document.createElement("span")
    accent.className = "accent"
    accent.textContent = "Or run yours."
    h1.appendChild(accent)

    splitWords(h1)

    const survivingAccent = h1.querySelector(".accent")
    expect(survivingAccent).not.toBeNull()
    // the accent element survives and its inner text is preserved…
    expect(survivingAccent!.textContent).toBe("Or run yours.")
    // …now wrapped in word spans inside the accent element
    expect(survivingAccent!.querySelectorAll(".gsap-word").length).toBeGreaterThan(1)
    // full round-trip: nothing dropped across the text node + child element
    expect(h1.textContent).toBe(HEADLINE)
  })
})

describe("gsap-gate — canEnhance", () => {
  const original = Object.getOwnPropertyDescriptor(window, "matchMedia")

  afterEach(() => {
    if (original) Object.defineProperty(window, "matchMedia", original)
    else delete (window as unknown as { matchMedia?: unknown }).matchMedia
  })

  it("is true when window.matchMedia is a function", () => {
    ;(window as unknown as { matchMedia: unknown }).matchMedia = jest.fn()
    expect(canEnhance()).toBe(true)
  })

  it("is false when window.matchMedia is absent (jsdom / SSR)", () => {
    delete (window as unknown as { matchMedia?: unknown }).matchMedia
    expect(canEnhance()).toBe(false)
  })
})

/**
 * R-03 (2026-08-31 customer-surface audit) — the late-hydration predicate.
 *
 * WHAT THESE ARMS PROVE, AND WHAT THEY DO NOT. They prove the PREDICATE: its
 * boundary, and that it can answer both ways. They say NOTHING about rendering.
 * The claim the fix actually makes — "a GSAP bundle that hydrates late never
 * hides already-painted landing content" — is a browser-level truth measured on
 * a throttled profile, and it belongs to the orchestrator's pass. A screenshot
 * cannot verify motion either; the timeline is the instrument. Do not let a
 * green run here be read as the rendering claim.
 *
 * Both sides of the boundary are asserted, so an inverted comparison or an
 * off-by-one reds rather than sliding through on one lucky side.
 */
describe("gsap-gate — entranceIsSafe (R-03)", () => {
  it("plays the entrance at first paint", () => {
    expect(entranceIsSafe(0)).toBe(true)
  })

  it("plays the entrance EXACTLY on the budget (inclusive)", () => {
    expect(entranceIsSafe(ENTRANCE_BUDGET_MS)).toBe(true)
  })

  it("REFUSES the entrance one millisecond past the budget", () => {
    expect(entranceIsSafe(ENTRANCE_BUDGET_MS + 1)).toBe(false)
  })

  it("refuses a genuinely late hydration by a wide margin", () => {
    // The measured case: a throttled load hydrates ~2.5s after first paint, and
    // the entrance's `autoAlpha: 0` used to RETROACTIVELY BLANK an h1 and the
    // persona CTAs the visitor had already been reading (~800ms of blank).
    expect(entranceIsSafe(2500)).toBe(false)
  })

  it("treats a negative elapsed reading as safe rather than as a refusal", () => {
    // `performance.now()` cannot go backwards, but a caller passing 0 for an
    // absent `performance` must never be pushed onto the refusing side.
    expect(entranceIsSafe(-1)).toBe(true)
  })

  it("the budget is a positive finite number, so the arms above mean something", () => {
    // Non-vacuity: with the budget at 0 or NaN the boundary arms would still
    // pass while asserting nothing about a real timing decision.
    expect(Number.isFinite(ENTRANCE_BUDGET_MS)).toBe(true)
    expect(ENTRANCE_BUDGET_MS).toBeGreaterThan(0)
  })
})

/**
 * WR-01 (code review, 2026-08-31) — the budget was measured against the wrong
 * clock, and the landing entrance was dead on every soft navigation.
 *
 * `performance.now()` counts from the document's TIME ORIGIN, set once at page
 * load and NOT reset by client-side routing. `/` is reachable by `next/link`
 * from every public surface (the wordmark "ALWAYS goes to /"), so a visitor who
 * browsed `/shop` for 30 s and clicked the wordmark mounted the hero at
 * `performance.now() ≈ 30000` and the entrance was refused — permanently, for
 * the rest of the session, however fast the bundle had arrived. And
 * `data-entrance="skipped"` reported it as a correct decision, so a
 * throttled-profile observation pass read green either way.
 *
 * The budget answers ONE question: "was something painted before this code ran,
 * such that hiding it now blanks what the visitor is reading?" That only has a
 * yes on the FIRST mount after a document load.
 */
describe("gsap-gate — entranceIsSafeForMount (WR-01)", () => {
  beforeEach(() => {
    __resetEntranceMountGateForTests()
  })

  it("applies the budget on the FIRST mount — a slow hydration still skips", () => {
    // The R-03 case, and the whole reason the budget exists. This must keep
    // working: it is the defect the previous commit was written to close.
    expect(entranceIsSafeForMount(2500)).toBe(false)
  })

  it("plays the entrance on the first mount when hydration was fast", () => {
    expect(entranceIsSafeForMount(300)).toBe(true)
  })

  it("SOFT NAV: a later mount plays the entrance even far past the budget", () => {
    // THE LOAD-BEARING ARM. First mount consumes the latch (fast, so `true`);
    // the second is a client-side route change 30 s into the session, where
    // nothing was painted before this scene existed.
    expect(entranceIsSafeForMount(300)).toBe(true)
    expect(entranceIsSafeForMount(30_000)).toBe(true)
  })

  it("SOFT NAV after a SKIPPED first mount also plays", () => {
    // The combination the session actually produces: a throttled first load
    // (entrance correctly skipped), then a soft nav back to `/`. Without this
    // the fix could be latching the VERDICT rather than the first-mount fact.
    expect(entranceIsSafeForMount(2500)).toBe(false)
    expect(entranceIsSafeForMount(2500)).toBe(true)
  })

  it("every mount from the third onwards is a soft nav too", () => {
    entranceIsSafeForMount(2500)
    expect(entranceIsSafeForMount(99_999)).toBe(true)
    expect(entranceIsSafeForMount(99_999)).toBe(true)
  })

  it("CONTROL: the reset really does re-arm the latch", () => {
    // Without this, every arm above could be passing because the latch was
    // already consumed by an earlier file-level import, and "first mount"
    // would never actually be under test.
    expect(entranceIsSafeForMount(2500)).toBe(false)
    expect(entranceIsSafeForMount(2500)).toBe(true)
    __resetEntranceMountGateForTests()
    expect(entranceIsSafeForMount(2500)).toBe(false)
  })

  it("the pure predicate is UNCHANGED and still available on its own", () => {
    // `entranceIsSafe` stays exported and stateless; the latch wraps it rather
    // than replacing it, so the boundary arms above still describe live code.
    expect(entranceIsSafe(ENTRANCE_BUDGET_MS)).toBe(true)
    expect(entranceIsSafe(ENTRANCE_BUDGET_MS + 1)).toBe(false)
  })
})

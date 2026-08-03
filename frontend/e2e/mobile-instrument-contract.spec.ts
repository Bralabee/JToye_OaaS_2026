/**
 * The instrument's own contract (#503).
 *
 * Every other spec in this directory tests the PRODUCT. This one tests the
 * TEST RIG, because the rig was wrong and nothing noticed.
 *
 * `playwright.config.ts` declared the `mobile` project with `isMobile: true` and
 * no `hasTouch`. Chromium then reports `pointer: fine` and `maxTouchPoints: 0`,
 * so `(pointer: coarse)` never matches and the mobile suite is blind BY
 * CONSTRUCTION to every defect whose symptom is "behaves like a mouse on a touch
 * device". A suite that cannot observe a failure reports green over it.
 *
 * So these blocks ASSERT the emulation state rather than trusting the config to
 * have taken effect — the distinction that makes this a check and not a comment.
 *
 * They also assert the defect that blindness was hiding: Tailwind's `hover:`
 * utilities must be gated behind `@media (hover: hover)`, or a tap latches the
 * hover state on a real phone and the button stays highlighted after being
 * pressed.
 *
 * Run:
 *   npx playwright test e2e/mobile-instrument-contract.spec.ts
 *
 * NOTE: the hover-gating blocks read the SERVED stylesheet, so they describe the
 * running build, not the source tree. After changing tailwind.config.ts you must
 * rebuild the frontend image before they mean anything — `docker compose ... build
 * frontend` then recreate. That is the "artifact vs running thing" rule; a source
 * edit alone will leave these red.
 */

import { test, expect } from "@playwright/test"

/** Read the emulated pointer/touch capabilities out of the live browser. */
async function pointerState(page: import("@playwright/test").Page) {
  return page.evaluate(() => ({
    coarse: window.matchMedia("(pointer: coarse)").matches,
    fine: window.matchMedia("(pointer: fine)").matches,
    canHover: window.matchMedia("(hover: hover)").matches,
    maxTouchPoints: navigator.maxTouchPoints,
    ontouchstart: "ontouchstart" in window,
  }))
}

/**
 * Walk every same-origin stylesheet and classify Tailwind hover-variant
 * utilities as gated (inside an `@media` whose condition mentions `hover:hover`)
 * or ungated.
 *
 * Selectors are matched on the ESCAPED class prefix `.hover\:`, which is what
 * Tailwind emits for the `hover:` variant. That is deliberately narrower than
 * "any rule containing :hover" — preflight, Radix and tailwindcss-animate all
 * emit bare `:hover` rules legitimately, so a blanket assertion would be red on
 * a correct tree.
 */
async function hoverRuleAudit(page: import("@playwright/test").Page) {
  return page.evaluate(() => {
    const ungated: string[] = []
    let gated = 0
    let unreadableSheets = 0

    const walk = (rules: CSSRuleList, insideHoverMedia: boolean) => {
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule) {
          const cond = (rule.conditionText || rule.media.mediaText || "").replace(/\s+/g, "")
          walk(rule.cssRules, insideHoverMedia || cond.includes("hover:hover"))
        } else if (rule instanceof CSSSupportsRule) {
          walk(rule.cssRules, insideHoverMedia)
        } else if (rule instanceof CSSStyleRule && rule.selectorText.includes("hover\\:")) {
          if (insideHoverMedia) gated++
          else ungated.push(rule.selectorText)
        }
      }
    }

    for (const sheet of Array.from(document.styleSheets)) {
      try {
        walk(sheet.cssRules, false)
      } catch {
        // Cross-origin sheets throw on .cssRules. Counted, never ignored — if
        // every sheet were unreadable this audit would find nothing and read as
        // a pass.
        unreadableSheets++
      }
    }
    return { gated, ungatedCount: ungated.length, ungatedSample: ungated.slice(0, 8), unreadableSheets }
  })
}

test.describe("Playwright mobile project — emulation contract", () => {
  test("mobile project reports a COARSE pointer and real touch points", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "mobile", "asserts the mobile project's own emulation")

    await page.goto("/")
    const state = await pointerState(page)

    // These four failed on the tree that motivated #503: coarse=false,
    // canHover=true, maxTouchPoints=0, ontouchstart=false.
    expect(state.coarse, "(pointer: coarse) must match under the mobile project").toBe(true)
    expect(state.fine, "(pointer: fine) must NOT match under the mobile project").toBe(false)
    expect(state.canHover, "(hover: hover) must NOT match a touch device").toBe(false)
    expect(state.maxTouchPoints, "navigator.maxTouchPoints must be >= 1").toBeGreaterThanOrEqual(1)
    expect(state.ontouchstart, "touch events must be present").toBe(true)
  })

  test("desktop project still reports a FINE pointer that can hover", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "desktop", "asserts the desktop project's own emulation")

    await page.goto("/")
    const state = await pointerState(page)

    // The non-regression half. A change that made everything coarse would
    // satisfy the block above and break hover for every desktop user.
    expect(state.fine, "(pointer: fine) must match under the desktop project").toBe(true)
    expect(state.coarse, "(pointer: coarse) must NOT match under the desktop project").toBe(false)
    expect(state.canHover, "(hover: hover) must match a mouse device").toBe(true)
  })
})

test.describe("Tailwind hover gating — the defect the blind instrument was hiding", () => {
  test("every hover: utility is gated behind @media (hover: hover)", async ({ page }) => {
    await page.goto("/")
    await page.waitForLoadState("domcontentloaded")

    const audit = await hoverRuleAudit(page)

    // VACUITY GUARD, and the reason this block is trustworthy. If the stylesheet
    // failed to load, or every sheet were cross-origin, the audit would return
    // 0/0 and the assertion below would pass over a completely unverified page.
    // The landing page carried 65 hover-variant utilities when #503 was filed.
    expect(
      audit.gated + audit.ungatedCount,
      `found no hover: utilities at all (unreadable sheets: ${audit.unreadableSheets}) — ` +
        "the stylesheet did not load, so this audit proved nothing"
    ).toBeGreaterThan(20)

    expect(
      audit.ungatedCount,
      `ungated hover: utilities latch on tap. Sample: ${audit.ungatedSample.join(", ")}`
    ).toBe(0)
  })
})

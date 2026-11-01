/**
 * Marketing GSAP scene + reveal-floor proof — LOCAL/STAGING, PROD BUILD ONLY.
 *
 * Like csp-no-violations.spec.ts this spec is intentionally NOT wired into CI;
 * the docs-freshness gate still counts its Playwright test blocks. It MUST run
 * against a PRODUCTION build (RESEARCH Pitfall 5: dev CSP allows 'unsafe-eval'
 * and dev/prod bundling differs). Run against the local Docker/prod stack:
 *
 *   npx playwright test e2e/marketing-motion.spec.ts   # uses the config baseURL (:3000)
 *
 *
 * Deterministic signals (frontmatter interface contract):
 *   - `.gsap-word`               — hand-split headline word spans (desktop only)
 *   - `[data-motion-active]`     — set by each enhancer INSIDE the matchMedia
 *                                  desktop branch; absent on mobile/reduced-motion
 *   - `[data-motion-decided]`    — "scene" | "static", stamped by each enhancer
 *                                  once it has RUN and decided either way; the
 *                                  deterministic anchor for absence assertions
 *                                  (the network-idle heuristic was structurally
 *                                  unreliable on these pages — RSC prefetch
 *                                  churn + session polling reset the idle
 *                                  window indefinitely; #687)
 *   - `.pin-spacer`              — inserted by ScrollTrigger.pin. `/` still uses
 *                                  none; `/for-operators` must now have NONE
 *                                  either (its pins were removed so the page
 *                                  arrives populated instead of filling on scroll)
 *   - `[data-op-media]`          — the `/for-operators` hero image frame (one element; fades and
 *                                  rises on load) and `[data-op-figure]`, the price docket
 *                                  pinned over it (moves with it)
 *   - `[data-entrance]`          — "played" | "skipped": the late-arrival verdict (R-03)
 *   - `[data-pilot-step]`        — the four pilot steps (must stay visible on the floor)
 */
import { test, expect } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"

// Tagged @desktop-only so the MOBILE project never ENUMERATES this block
// (playwright.config.ts sets grepInvert on that project). #420: these two tests are
// desktop-by-design and the desktop project always runs them, so skipping them at
// runtime under mobile added 2 permanent entries to the suite's skip count — surface
// that is not unverified at all. A skip should mean "nobody checked this", and it
// cannot carry that meaning while it also means "not applicable here".
test.describe("desktop GSAP scenes (>=768px + motion)", { tag: "@desktop-only" }, () => {
  // Belt-and-braces with the project filter: the tag governs enumeration, this governs
  // execution. They protect different failure modes — a config edit that drops the
  // grepInvert would otherwise silently run GSAP scene assertions at 390px.
  test.skip(({ viewport }) => (viewport?.width ?? 0) < 768, "desktop-only scenes")

  test("/ splits the hero headline, marks the scope active, has no heat-wash, scrubs the rail", async ({
    page,
  }) => {
    await page.goto(`${BASE}/`)

    // Deterministic anchor: the attribute only exists once the enhancer built
    // the scene. Generous timeout is slack, not the predicate.
    await expect(page.locator("[data-motion-active='desktop']").first()).toBeAttached({
      timeout: 15000,
    })
    expect(
      await page.locator("h1[data-hero-headline] .gsap-word").count(),
    ).toBeGreaterThanOrEqual(2)

    // The two persona-door CTAs MUST end fully visible after the deal-in
    // settles — a `gsap.from` here was re-hidden by ScrollTrigger.refresh(),
    // shipping them invisible on desktop while every other check stayed green.
    await page.waitForTimeout(1400) // past the 0.45s delay + 0.6s tween
    const doors = page.locator("[data-hero-door]")
    expect(await doors.count()).toBeGreaterThanOrEqual(2)
    for (let i = 0; i < (await doors.count()); i++) {
      await expect(doors.nth(i)).toBeVisible()
      const opacity = await doors.nth(i).evaluate((el) => getComputedStyle(el).opacity)
      expect(Number(opacity)).toBeGreaterThan(0.9)
    }

    // D14 (design/decisions.md, 2026-11-01): the radial heat-wash and its
    // parallax are gone with the gradient ban; the hero is a flat field. This
    // used to assert the wash moved on scroll. It now asserts the wash is
    // absent, and — so the absence is not a statement about a dead page — that
    // the rest of the scrubbed scene still runs: the How-it-works rail fill
    // still scales as the page scrolls.
    expect(await page.locator("[data-hero-heatwash]").count(), "the heat-wash is gone (D14)").toBe(0)
    const rail = page.locator("[data-hero-railfill]").first()
    const before = await rail.evaluate((el) => getComputedStyle(el).transform)
    await page.locator("[data-hero-steps]").first().scrollIntoViewIfNeeded()
    await page.mouse.wheel(0, 300)
    await page.waitForTimeout(800)
    const after = await rail.evaluate((el) => getComputedStyle(el).transform)
    expect(after, "the step rail still scrubs on scroll").not.toBe(before)
  })

  test("/for-operators arrives POPULATED — headline + hero image + docket visible without scrolling, no pin", async ({
    page,
  }) => {
    await page.goto(`${BASE}/for-operators`)

    // Deterministic anchor: the operator hook stamps the same attribute, and
    // the .gsap-word spans are created synchronously in the same callback, so
    // the count assertion below is anchored to a settled scene.
    await expect(page.locator("[data-motion-active='desktop']").first()).toBeAttached({
      timeout: 15000,
    })

    expect(
      await page.locator("[data-op-headline] .gsap-word").count(),
    ).toBeGreaterThanOrEqual(2)

    // The entrance plays on LOAD. Past the longest delay+duration (0.25 + 0.6)
    // everything above the fold must be readable with the page never scrolled —
    // the hero used to be pinned and the Service rail scrubbed in, so both
    // landed EMPTY until the user scrolled. Whether the entrance PLAYED or was
    // SKIPPED as late (R-03), the content must end up visible: the verdict is
    // reported but not asserted, because it depends on how fast this machine
    // hydrated, while visibility must not.
    await expect(page.locator("[data-entrance]").first()).toBeAttached()
    const verdict = await page.locator("[data-entrance]").first().getAttribute("data-entrance")
    expect(["played", "skipped"]).toContain(verdict)
    await page.waitForTimeout(1400)
    expect(await page.evaluate(() => window.scrollY)).toBe(0)

    const headline = page.locator("[data-op-headline]")
    await expect(headline).toBeVisible()
    const headlineOpacity = await headline.evaluate(
      (el) => getComputedStyle(el).opacity,
    )
    expect(Number(headlineOpacity)).toBeGreaterThan(0.9)

    // The hero image frame is ONE element now (the three-item rail it replaced
    // is gone), and the price docket rides inside it.
    const media = page.locator("[data-op-media]")
    expect(await media.count()).toBe(1)
    await expect(media).toBeVisible()
    const mediaOpacity = await media.evaluate((el) => getComputedStyle(el).opacity)
    expect(Number(mediaOpacity)).toBeGreaterThan(0.9)
    const figure = page.locator("[data-op-figure]")
    await expect(figure).toBeVisible()
    await expect(figure).toContainText("%")

    // No scroll hijack anywhere on the page: nothing is pinned any more, and
    // the four pilot steps are a plain grid rather than a horizontal track.
    expect(await page.locator(".pin-spacer").count()).toBe(0)
    const track = page.locator("[data-pilot-track]").first()
    const start = await track.evaluate((el) => getComputedStyle(el).transform)
    for (let i = 0; i < 10; i++) {
      await page.mouse.wheel(0, 500)
      await page.waitForTimeout(120)
    }
    const end = await track.evaluate((el) => getComputedStyle(el).transform)
    expect(end).toBe(start)
    expect(await page.locator(".pin-spacer").count()).toBe(0)
  })
})

test.describe("mobile floor (375px) — no heavy scenes", () => {
  test.use({ viewport: { width: 375, height: 812 } })

  for (const path of ["/", "/for-operators"]) {
    test(`${path} degrades to fully-visible static content`, async ({ page }) => {
      await page.goto(`${BASE}${path}`)
      // Load-bearing anchor: the attribute only exists AFTER the enhancer ran
      // and chose the static branch, so the absence assertions below cannot be
      // vacuous-by-timing. Asserting the VALUE 'static' (not mere presence)
      // makes a wrongly-built scene fail the wait itself.
      await expect(page.locator("[data-motion-decided='static']").first()).toBeAttached({
        timeout: 15000,
      })
      await page.waitForTimeout(500)

      expect(await page.locator(".gsap-word").count()).toBe(0)
      expect(await page.locator("[data-motion-active='desktop']").count()).toBe(0)
      expect(await page.locator(".pin-spacer").count()).toBe(0)

      const heading = page.locator("h1").first()
      await expect(heading).toBeVisible()
      const box = await heading.boundingBox()
      expect(box?.width ?? 0).toBeGreaterThan(0)

      if (path === "/for-operators") {
        const steps = page.locator("[data-pilot-step]")
        const count = await steps.count()
        expect(count).toBeGreaterThanOrEqual(4)
        for (let i = 0; i < count; i++) {
          await expect(steps.nth(i)).toBeVisible()
          const b = await steps.nth(i).boundingBox()
          expect((b?.width ?? 0) * (b?.height ?? 0)).toBeGreaterThan(0)
        }
      }
    })
  }
})

test.describe("reduced motion — no heavy scenes", () => {
  for (const path of ["/", "/for-operators"]) {
    test(`${path} builds no GSAP scene under prefers-reduced-motion: reduce`, async ({
      page,
    }) => {
      // Emulate reduced-motion explicitly BEFORE navigation. (Describe-level
      // `test.use({ reducedMotion })` did not propagate to the page fixture in
      // this Playwright/project setup, so the DESKTOP_MOTION_QUERY still matched;
      // page.emulateMedia is the reliable path and matches the phase plan.)
      await page.emulateMedia({ reducedMotion: "reduce" })
      await page.goto(`${BASE}${path}`)
      // Load-bearing anchor (see the mobile block): only stamped once the
      // enhancer ran and DECLINED to build a scene under reduced motion.
      await expect(page.locator("[data-motion-decided='static']").first()).toBeAttached({
        timeout: 15000,
      })
      await page.waitForTimeout(500)

      expect(await page.locator(".gsap-word").count()).toBe(0)
      expect(await page.locator(".pin-spacer").count()).toBe(0)
      expect(await page.locator("[data-motion-active='desktop']").count()).toBe(0)
      await expect(page.locator("h1").first()).toBeVisible()
    })
  }
})

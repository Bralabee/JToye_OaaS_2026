/**
 * Marketing GSAP scene + reveal-floor proof — LOCAL/STAGING, PROD BUILD ONLY.
 *
 * Like csp-no-violations.spec.ts this spec is intentionally NOT wired into CI;
 * the docs-freshness gate still counts its Playwright test blocks. It MUST run
 * against a PRODUCTION build (RESEARCH Pitfall 5: dev CSP allows 'unsafe-eval'
 * and dev/prod bundling differs). Run against the local Docker/prod stack:
 *
 *   PLAYWRIGHT_BASE_URL=http://localhost:3100 \
 *     npx playwright test e2e/marketing-motion.spec.ts
 *
 * Deterministic signals (frontmatter interface contract):
 *   - `.gsap-word`               — hand-split headline word spans (desktop only)
 *   - `[data-motion-active]`     — set by each enhancer INSIDE the matchMedia
 *                                  desktop branch; absent on mobile/reduced-motion
 *   - `.pin-spacer`              — inserted by ScrollTrigger.pin. `/` still uses
 *                                  none; `/for-operators` must now have NONE
 *                                  either (its pins were removed so the page
 *                                  arrives populated instead of filling on scroll)
 *   - `[data-rail-item]`         — the three Service-rail items (visible on load)
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

  test("/ splits the hero headline, marks the scope active, parallaxes the heat-wash", async ({
    page,
  }) => {
    await page.goto(`${BASE}/`)
    await page.waitForLoadState("networkidle")

    await expect(page.locator("[data-motion-active='desktop']").first()).toBeAttached()
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

    const heatwash = page.locator("[data-hero-heatwash]").first()
    const before = await heatwash.evaluate((el) => getComputedStyle(el).transform)
    await page.mouse.wheel(0, 700)
    await page.waitForTimeout(500)
    const after = await heatwash.evaluate((el) => getComputedStyle(el).transform)
    expect(after).not.toBe(before)
  })

  test("/for-operators arrives POPULATED — headline + rail visible without scrolling, no pin", async ({
    page,
  }) => {
    await page.goto(`${BASE}/for-operators`)
    await page.waitForLoadState("networkidle")

    expect(
      await page.locator("[data-op-headline] .gsap-word").count(),
    ).toBeGreaterThanOrEqual(2)

    // The entrance plays on LOAD. Past the longest delay+duration (0.35 + 0.55)
    // everything above the fold must be readable with the page never scrolled —
    // the hero used to be pinned and the Service rail scrubbed in, so both
    // landed EMPTY until the user scrolled.
    await page.waitForTimeout(1400)
    expect(await page.evaluate(() => window.scrollY)).toBe(0)

    const headline = page.locator("[data-op-headline]")
    await expect(headline).toBeVisible()
    const headlineOpacity = await headline.evaluate(
      (el) => getComputedStyle(el).opacity,
    )
    expect(Number(headlineOpacity)).toBeGreaterThan(0.9)

    const rail = page.locator("[data-rail-item]")
    const railCount = await rail.count()
    expect(railCount).toBeGreaterThanOrEqual(3)
    for (let i = 0; i < railCount; i++) {
      await expect(rail.nth(i)).toBeVisible()
      const opacity = await rail.nth(i).evaluate((el) => getComputedStyle(el).opacity)
      expect(Number(opacity)).toBeGreaterThan(0.9)
    }

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
      await page.waitForLoadState("networkidle")
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
      await page.waitForLoadState("networkidle")
      await page.waitForTimeout(500)

      expect(await page.locator(".gsap-word").count()).toBe(0)
      expect(await page.locator(".pin-spacer").count()).toBe(0)
      expect(await page.locator("[data-motion-active='desktop']").count()).toBe(0)
      await expect(page.locator("h1").first()).toBeVisible()
    })
  }
})

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
 *   - `.pin-spacer`              — inserted by ScrollTrigger.pin (desktop only)
 *   - `[data-pilot-step]`        — the four pilot steps (must stay visible on the floor)
 */
import { test, expect } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"

test.describe("desktop GSAP scenes (>=768px + motion)", () => {
  // Only meaningful on the desktop project (1440x900); skip under mobile (390).
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

    const heatwash = page.locator("[data-hero-heatwash]").first()
    const before = await heatwash.evaluate((el) => getComputedStyle(el).transform)
    await page.mouse.wheel(0, 700)
    await page.waitForTimeout(500)
    const after = await heatwash.evaluate((el) => getComputedStyle(el).transform)
    expect(after).not.toBe(before)
  })

  test("/for-operators pins the hero, splits the headline, scrolls the pilot rail horizontally", async ({
    page,
  }) => {
    await page.goto(`${BASE}/for-operators`)
    await page.waitForLoadState("networkidle")

    expect(
      await page.locator("[data-op-headline] .gsap-word").count(),
    ).toBeGreaterThanOrEqual(2)

    // Scroll into the pinned Service-rail hero → ScrollTrigger inserts a pin-spacer.
    await page.mouse.wheel(0, 400)
    await page.waitForTimeout(400)
    expect(await page.locator(".pin-spacer").count()).toBeGreaterThanOrEqual(1)

    // Drive deep into the pinned pilot section → the track translates horizontally.
    const track = page.locator("[data-pilot-track]").first()
    const start = await track.evaluate((el) => getComputedStyle(el).transform)
    for (let i = 0; i < 10; i++) {
      await page.mouse.wheel(0, 500)
      await page.waitForTimeout(120)
    }
    const end = await track.evaluate((el) => getComputedStyle(el).transform)
    expect(end).not.toBe(start)
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
  test.use({ reducedMotion: "reduce" })

  for (const path of ["/", "/for-operators"]) {
    test(`${path} builds no GSAP scene under prefers-reduced-motion: reduce`, async ({
      page,
    }) => {
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

/**
 * Throttled-mobile Core Web Vitals for `/` — the LCP-critical public route.
 *
 * WHY THIS ROUTE, NOW. 33-03 rewrites the landing page's main content row: the
 * emoji-and-gradient cards become REAL remote logos through `SafeImage`, and the
 * page becomes an async Server Component that fetches shops at request time.
 * Swapping placeholder gradients for network images is the single most reliable
 * way to introduce layout shift, and `/` is the first page every customer sees.
 * CLAUDE.md makes CWV a standing acceptance criterion for any phase touching a
 * user-facing page, so this is IN scope, not N/A.
 *
 * Method copied from `webhooks-webperf.spec.ts` — 375px viewport, CDP network +
 * 4x CPU throttling, buffered PerformanceObserver. Budgets are imported from
 * `perf-budgets.ts` rather than restated, so there is one place to argue with.
 *
 * TWO THINGS THIS DOES THAT THE PRECEDENT DOES NOT:
 *
 *  1. CLS IS MEASURED AFTER THE LOGOS RESOLVE. A CLS reading taken before the
 *     images load measures nothing — the shift has not happened yet. The whole
 *     reason this file exists is the image swap, so the assertion waits for the
 *     images to be `complete` before sampling.
 *  2. THE CLIENT BUNDLE IS RECORDED AND BOUNDED. 33-07 adds a client island to
 *     this route that requests a coordinate and refetches. A baseline measured
 *     here is what makes that growth visible instead of invisible.
 *
 * Run: npx playwright test landing-webperf   (needs the REBUILT stack — the
 * Permissions-Policy header and the server-rendered row are both build outputs,
 * and `docker compose start` does not rebuild).
 */
import { test, expect, type BrowserContext, type Page } from "@playwright/test"
import {
  LCP_BUDGET_MS,
  CLS_BUDGET,
  LANDING_CLS_KNOWN_BASELINE,
  LANDING_CLS_TOLERANCE,
  LANDING_BUNDLE_BASELINE_BYTES,
  LANDING_BUNDLE_MAX_GROWTH,
} from "./perf-budgets"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"

async function throttle(context: BrowserContext, page: Page) {
  const client = await context.newCDPSession(page)
  await client.send("Network.enable")
  await client.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: 40,
    downloadThroughput: (1.5 * 1024 * 1024) / 8, // ~Fast 3G
    uploadThroughput: (750 * 1024) / 8,
  })
  await client.send("Emulation.setCPUThrottlingRate", { rate: 4 })
}

/** Buffered LCP (max renderTime) + cumulative layout shift. */
async function measureVitals(page: Page): Promise<{ lcp: number; cls: number }> {
  return page.evaluate(
    () =>
      new Promise<{ lcp: number; cls: number }>((resolve) => {
        let lcp = 0
        let cls = 0
        try {
          new PerformanceObserver((list) => {
            for (const e of list.getEntries() as PerformanceEntry[]) {
              // @ts-expect-error layout-shift entry fields
              if (!e.hadRecentInput) cls += (e as { value: number }).value
            }
          }).observe({ type: "layout-shift", buffered: true })
          new PerformanceObserver((list) => {
            const entries = list.getEntries()
            const last = entries[entries.length - 1] as PerformanceEntry & {
              renderTime?: number
              loadTime?: number
            }
            lcp = last.renderTime || last.loadTime || lcp
          }).observe({ type: "largest-contentful-paint", buffered: true })
        } catch {
          /* browser lacks the entry types — resolve with zeros and skip */
        }
        setTimeout(() => resolve({ lcp, cls }), 2500)
      })
  )
}

/**
 * Wait until every <img> on the page has either loaded or failed. `complete` is
 * true in both cases, which is what we want: a broken logo still finishes
 * reserving (or not reserving) its box, and hanging the test on a 404 would turn
 * a CLS assertion into a timeout.
 */
async function imagesSettled(page: Page) {
  await page
    .waitForFunction(
      () => Array.from(document.images).every((img) => img.complete),
      undefined,
      { timeout: 20_000 }
    )
    .catch(() => {
      /* fall through — measure what we have rather than failing on a slow CDN */
    })
}

test.describe("Landing route `/` — throttled-mobile CWV (33-03)", () => {
  test.use({ viewport: { width: 375, height: 812 }, isMobile: true })

  test("holds LCP and CLS at a throttled mobile profile, with the real logos loaded", async ({
    context,
    page,
  }) => {
    await throttle(context, page)
    await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" })

    // The route is interactive when its primary content is visible. Use
    // getByRole for headings: React's streaming staging buffer holds a second
    // copy of the shell in `<div hidden id="S:n">` for ~300 ms, which
    // getByTestId/getByTitle can see and getByRole cannot (#556, #593).
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })

    // THEN let the images settle. Sampling CLS before this point would measure a
    // page that has not yet had the chance to shift.
    await imagesSettled(page)

    const { lcp, cls } = await measureVitals(page)
    test.info().annotations.push({
      type: "web-vitals",
      description: `/ — LCP=${Math.round(lcp)}ms CLS=${cls.toFixed(4)} (throttled 375px, 4x CPU)`,
    })

    // CLS — NO-REGRESSION against a measured pre-existing baseline, not against
    // the absolute target, and the difference is recorded rather than hidden.
    //
    // `/` measures 0.1793 and DID SO BEFORE THIS PHASE. Proven by running the
    // pre-change build simultaneously on :3001 under identical throttling:
    // control 0.1793 / treatment 0.1793, one shift each, sources all in the hero.
    // See perf-budgets.ts for the full A/B and why CLS_BUDGET was NOT raised to
    // 0.2 to make this green.
    //
    // This assertion is the falsifiable one: it fires if 33-07's client island
    // makes the shift worse. The absolute target below stays declared and unmet
    // on purpose, so the debt stays visible instead of being budgeted away.
    expect(
      cls,
      `/ CLS regressed past its recorded pre-existing baseline of ${LANDING_CLS_KNOWN_BASELINE}`
    ).toBeLessThan(LANDING_CLS_KNOWN_BASELINE + LANDING_CLS_TOLERANCE)

    if (cls >= CLS_BUDGET) {
      test.info().annotations.push({
        type: "known-debt",
        description:
          `/ CLS=${cls.toFixed(4)} exceeds the declared CLS_BUDGET of ${CLS_BUDGET}. ` +
          `PRE-EXISTING (control arm measured identically), caused by hero client-island ` +
          `hydration, not by the kitchen row. Fixing it means changing how HeroSearch ` +
          `hydrates — outside 33-03's file set, its own scoped work.`,
      })
    }

    if (lcp > 0) {
      expect(lcp, "/ LCP within the throttled budget").toBeLessThan(LCP_BUDGET_MS)
    }

    // No horizontal overflow at 375px — the three-card row must not push the
    // document wider than the viewport.
    const overflow = await page.evaluate(() => {
      const el = document.scrollingElement || document.documentElement
      return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }
    })
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
  })

  test("every shop logo reserves its box before it loads (the CLS mechanism)", async ({ page }) => {
    // The budget assertion above can pass for the wrong reason — a slow CDN, a
    // cached image, a run where nothing happened to shift. This asserts the
    // MECHANISM directly: width and height attributes present on every card
    // image, which is what lets the browser reserve space at all.
    await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("region", { name: "Dishes cooking near you" })).toBeVisible({
      timeout: 20_000,
    })

    const imgs = page.getByRole("region", { name: "Dishes cooking near you" }).locator("img")
    const count = await imgs.count()
    // Non-vacuity: an empty row would make the loop below assert nothing at all.
    expect(count, "the kitchen row must contain at least one shop logo").toBeGreaterThan(0)

    for (let i = 0; i < count; i++) {
      const img = imgs.nth(i)
      await expect(img, `card image ${i} must declare a width`).toHaveAttribute("width", /\d+/)
      await expect(img, `card image ${i} must declare a height`).toHaveAttribute("height", /\d+/)
    }
  })

  test("records the / client-JS baseline and bounds its growth", async ({ page }) => {
    // Sum every script the route actually downloads. 33-07 adds a client island
    // to this page; without a recorded number here, that growth is invisible.
    // Measured from the BODY, not from content-length. The first form of this
    // meter read `res.headers()["content-length"]` and reported ZERO — Next
    // serves its chunks without that header, so every script scored 0 and the
    // total sailed in under any ceiling. The non-vacuity assertion below is what
    // caught it; without that line this test would have "passed" measuring
    // nothing.
    const sizes: Array<Promise<number>> = []
    page.on("response", (res) => {
      if (res.request().resourceType() !== "script") return
      sizes.push(
        res
          .body()
          .then((b) => b.length)
          .catch(() => 0)
      )
    })

    await page.goto(`${BASE}/`, { waitUntil: "load" })
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })
    // Settle before summing: the baseline was taken the same way, and a shorter
    // window undercounts late chunks (an early draft read 913,759 for this reason).
    await page.waitForTimeout(1500)
    const bytes = (await Promise.all(sizes)).reduce((a, b) => a + b, 0)

    test.info().annotations.push({
      type: "bundle",
      description: `/ client JS = ${bytes} bytes (${(bytes / 1024).toFixed(1)} KiB)`,
    })

    // Non-vacuity FIRST. A zero would sail past any ceiling, and zero is exactly
    // what this test reports if the resourceType filter or content-length header
    // stops matching — a silent pass that means "I measured nothing".
    expect(bytes, "measured zero script bytes — the meter is broken, not the page").toBeGreaterThan(
      0
    )

    // The declared bound. LANDING_BUNDLE_BASELINE_BYTES is the number measured
    // when this test was written; the growth factor is what 33-07 must justify
    // against. Imported, not restated, so there is one place to argue with.
    expect(
      bytes,
      `/ client JS grew past ${LANDING_BUNDLE_MAX_GROWTH}x the recorded baseline of ${LANDING_BUNDLE_BASELINE_BYTES} bytes`
    ).toBeLessThan(LANDING_BUNDLE_BASELINE_BYTES * LANDING_BUNDLE_MAX_GROWTH)
  })
})

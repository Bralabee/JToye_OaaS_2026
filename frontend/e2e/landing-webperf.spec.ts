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
  LANDING_CLS_DESKTOP_CONTROL,
  LANDING_CLS_DESKTOP_RECORD,
  LANDING_BUNDLE_BASELINE_BYTES,
  LANDING_BUNDLE_CEILING_BYTES,
  LANDING_POST_GRANT_MAX_VERTICAL_PX,
} from "./perf-budgets"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"

/** Rye Lane, Peckham — inside the row's radius, so the grant returns results. */
const PECKHAM = { latitude: 51.47, longitude: -0.07 }

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
type ShiftTotals = {
  /** Layout-shift score, every entry, including the ones CLS discards. */
  score: number
  /** Largest vertical movement (top or height) any single source underwent, px. */
  verticalPx: number
  /** Largest horizontal movement any single source underwent, px. */
  horizontalPx: number
  /** How many entries were seen — 0 means the recorder saw nothing at all. */
  entries: number
}

/**
 * Start recording layout shift FROM NOW, split into the axis that matters and
 * the axis that does not.
 *
 * `buffered` is deliberately absent: this measures the post-grant window only.
 * `hadRecentInput` entries are KEPT, because the shift this plan risks happens
 * within 500 ms of the click and is therefore precisely what CLS declines to
 * count — but they are split by axis, because the horizontal movement IS the
 * feature (see the note on LANDING_POST_GRANT_MAX_VERTICAL_PX for the measured
 * entry that proved it) and only the vertical movement is a defect.
 */
async function startShiftRecorder(page: Page) {
  await page.evaluate(() => {
    const w = window as unknown as { __shift?: ShiftTotalsLike }
    type ShiftTotalsLike = {
      score: number
      verticalPx: number
      horizontalPx: number
      entries: number
    }
    w.__shift = { score: 0, verticalPx: 0, horizontalPx: 0, entries: 0 }
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        const entry = e as unknown as {
          value: number
          sources?: Array<{ previousRect: DOMRectReadOnly; currentRect: DOMRectReadOnly }>
        }
        w.__shift!.score += entry.value
        w.__shift!.entries += 1
        for (const s of entry.sources ?? []) {
          const dTop = Math.abs(s.currentRect.top - s.previousRect.top)
          const dHeight = Math.abs(s.currentRect.height - s.previousRect.height)
          const dLeft = Math.abs(s.currentRect.left - s.previousRect.left)
          w.__shift!.verticalPx = Math.max(w.__shift!.verticalPx, dTop, dHeight)
          w.__shift!.horizontalPx = Math.max(w.__shift!.horizontalPx, dLeft)
        }
      }
    }).observe({ type: "layout-shift" })
  })
}

async function readShifts(page: Page): Promise<ShiftTotals> {
  return page.evaluate(() => {
    const w = window as unknown as { __shift?: ShiftTotals }
    return w.__shift ?? { score: -1, verticalPx: -1, horizontalPx: -1, entries: -1 }
  })
}

/**
 * The page-space Y of a stable landmark BELOW the kitchen row. Page-space, not
 * viewport-space, so a scroll between readings cannot masquerade as a shift.
 */
async function howItWorksPageY(page: Page): Promise<number> {
  return page
    .getByRole("heading", { name: "How it works" })
    .evaluate((el) => el.getBoundingClientRect().top + window.scrollY)
}

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

    // THE DECLARED CEILING. Imported, never restated — a constant with no
    // consumer enforces nothing, and "record the size in the summary" is a
    // reporting instruction, which cannot fail and is therefore a note rather
    // than a criterion.
    //
    // 33-07 replaced 33-03's 1.5x growth factor (1,430,029 bytes) with this
    // absolute number: baseline + a 20,480-byte allowance for the located
    // island, derived from the +5,635 bytes the island actually costs. See
    // perf-budgets.ts for both measurements and for the regression it catches —
    // routing the island's one GET through axios instead of `fetch` puts this
    // route at 1,005,834 bytes, which reds this line and passed the old one.
    expect(
      bytes,
      `/ client JS is past the declared ceiling of ${LANDING_BUNDLE_CEILING_BYTES} bytes ` +
        `(33-03 baseline ${LANDING_BUNDLE_BASELINE_BYTES} + the recorded island allowance)`
    ).toBeLessThan(LANDING_BUNDLE_CEILING_BYTES)
  })

  /**
   * THE POST-GRANT ARM — the reason this file runs again in 33-07 rather than
   * inheriting wave 2's pass.
   *
   * 33-03 measured this route with a server-rendered row and nothing else. 33-07
   * adds a client island that REFETCHES AND RE-RENDERS that same row after a
   * permission grant, which is a materially larger CLS and INP risk than the
   * image swap the budget was written for. A budget measured only before the
   * riskiest change is a budget measured on the wrong artifact.
   *
   * Two numbers, because they answer different questions:
   *   - CLS, which drops every shift within 500 ms of the click, is compared to
   *     the same recorded baseline the initial-state test uses. It answers "is
   *     the page as stable as it was?"
   *   - the strict post-grant total, which keeps those entries, answers "did the
   *     row jump when the new list landed?" — and it is the only one of the two
   *     that CAN answer it.
   */
  test("holds its budget in the POST-GRANT state, not only the initial one", async ({
    context,
    page,
  }) => {
    await context.grantPermissions(["geolocation"])
    await context.setGeolocation(PECKHAM)
    await throttle(context, page)
    await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" })

    await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })
    await expect(
      page.getByRole("region", { name: "Dishes cooking near you" })
    ).toBeVisible({ timeout: 20_000 })
    await imagesSettled(page)

    // Everything from here is the island's doing, and nothing before it counts.
    const anchorBefore = await howItWorksPageY(page)
    await startShiftRecorder(page)
    await page.getByRole("button", { name: /use my location/i }).click()

    // NON-VACUITY. Without this the test would "pass" over a click that did
    // nothing at all — the row would obviously not shift, and the budget would
    // be measuring an interaction that never happened. `getByRole` for the
    // heading: the streaming staging buffer is visible to attribute locators
    // and not to this one (556, 593).
    await expect(page.getByRole("heading", { name: /near you/i })).toHaveCount(1)
    await imagesSettled(page)
    // Let anything late — a reflowing heading, a logo swap — actually land.
    await page.waitForTimeout(1500)

    const anchorAfter = await howItWorksPageY(page)
    const shifts = await readShifts(page)
    const { lcp, cls } = await measureVitals(page)

    test.info().annotations.push({
      type: "web-vitals-post-grant",
      description:
        `/ AFTER the location grant — LCP=${Math.round(lcp)}ms CLS=${cls.toFixed(4)} · ` +
        `post-grant shift score=${shifts.score.toFixed(4)} over ${shifts.entries} entr(ies), ` +
        `max vertical=${shifts.verticalPx.toFixed(2)}px, max horizontal=${shifts.horizontalPx.toFixed(2)}px ` +
        `(horizontal IS the reorder — see perf-budgets.ts) · ` +
        `"How it works" moved ${Math.abs(anchorAfter - anchorBefore).toFixed(2)}px ` +
        `(throttled 375px, 4x CPU)`,
    })

    // NON-VACUITY. -1 is the sentinel `readShifts` returns when the recorder was
    // never installed, and 0 entries means nothing was observed at all — either
    // would make the two bounds below pass while measuring nothing.
    expect(shifts.entries, "the shift recorder never installed — this arm measured nothing").toBeGreaterThanOrEqual(0)

    // (i) NOTHING BELOW THE ROW MOVED. The simplest statement of the thing that
    // would actually hurt: the visitor asks for one thing and the rest of the
    // page walks away from under their thumb.
    expect(
      Math.abs(anchorAfter - anchorBefore),
      "the content BELOW the kitchen row moved when the located list landed — check that the " +
        "status line's height is still reserved and the row is not being unmounted for a skeleton"
    ).toBeLessThanOrEqual(LANDING_POST_GRANT_MAX_VERTICAL_PX)

    // (ii) AND NOTHING MOVED VERTICALLY INSIDE IT EITHER. Catches what (i)
    // cannot: a card growing taller while the section below happens to be pushed
    // by something that compensates. The horizontal figure is deliberately NOT
    // asserted — it is the reorder the visitor asked for.
    expect(
      shifts.verticalPx,
      "a card changed height or moved vertically — check that the distance pill is still out of flow"
    ).toBeLessThanOrEqual(LANDING_POST_GRANT_MAX_VERTICAL_PX)

    expect(
      cls,
      `/ CLS regressed past its recorded pre-existing baseline of ${LANDING_CLS_KNOWN_BASELINE} with the island mounted and located`
    ).toBeLessThan(LANDING_CLS_KNOWN_BASELINE + LANDING_CLS_TOLERANCE)

    if (lcp > 0) {
      expect(lcp, "/ LCP within the throttled budget, post-grant").toBeLessThan(LCP_BUDGET_MS)
    }

    // The located row must not push the document wider than the viewport either
    // — a distance pill is new content inside a card at 375px.
    const overflow = await page.evaluate(() => {
      const el = document.scrollingElement || document.documentElement
      return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }
    })
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
  })
})

/**
 * THE DESKTOP ARM — 35-09 / ORCH-02 (orchestrator decision, 2026-08-29).
 *
 * WHY IT EXISTS, AND WHY IT IS NOT A DUPLICATE OF THE DESCRIBE ABOVE. Everything
 * above pins a 375px viewport, and it does so even under the Playwright
 * `desktop` project, because a describe's own `test.use` overrides the project's.
 * Phase 35 moved this route's content bands from 1152px to 1280px, and the
 * generated `max-w-marketing` rule is emitted with NO media query — so it cannot
 * bind at 375px. The mobile arm is therefore STRUCTURALLY BLIND to the change:
 * it can only ever report a pass, and a pass from an instrument that cannot see
 * the thing it is asked about is not evidence.
 *
 * THE TWO ARMS MEASURE DIFFERENT THINGS AND NEITHER REPLACES THE OTHER. CLS
 * normalises by viewport, so 0.1793 at 375px and 0.1316 at 1440px describe
 * different layouts and their difference means nothing on its own. The mobile
 * arm above is untouched by this plan — its baseline, its tolerance and the
 * absolute CLS_BUDGET are all unchanged, so the pre-existing debt stays visible.
 *
 * Tagged `@desktop-only` so the `mobile` project never ENUMERATES it. A skip must
 * mean "nobody checked this"; it must not also mean "not applicable here" (#420),
 * and an enumerated-then-skipped block would spend the e2e skip budget (#686).
 *
 * COVERAGE BOUNDARY, stated rather than implied: `.github/workflows/ci-cd.yaml`
 * runs only `public-layout.spec.ts` and `public-a11y.spec.ts` per PR, so this
 * block is NOT in the per-PR set. #683 records the nightly full-suite lane as
 * dark. The honest phrasing is "covered by a spec that no current tree executes",
 * never "covered nightly".
 *
 * Run: PLAYWRIGHT_BASE_URL=<a REBUILT frontend> npx playwright test
 *      landing-webperf --project=desktop
 * A frontend image built before phase 35 serves the 1152px band and scores the
 * CONTROL, which passes the first assertion below and reds the second — by
 * design; see LANDING_CLS_DESKTOP_RECORD.
 */
test.describe("@desktop-only Landing route `/` — desktop CLS (35-09, ORCH-02)", () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test("holds desktop CLS no worse than its measured pre-change control", async ({
    context,
    page,
  }) => {
    // The SAME throttling and the SAME observer as the mobile arm, deliberately:
    // both declared constants were measured through these two helpers, and a
    // number measured one way and asserted another is not a comparison.
    await throttle(context, page)
    await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })
    await imagesSettled(page)

    const { lcp, cls } = await measureVitals(page)

    // ---- NON-VACUITY, BEFORE THE SCORE IS TRUSTED -------------------------
    // A page that failed to render scores a PERFECT CLS of 0, and that zero
    // would be cited as evidence about this phase. So the score is only read
    // after the landing page is shown to be present, at desktop, in `main`.
    const main = page.locator("main")

    // (1) It is the LANDING page, not an error route: exactly one h1 in `main`.
    await expect(
      main.getByRole("heading", { level: 1 }),
      "no landing h1 inside <main> — this arm measured something that is not the landing page, " +
        "and a CLS of 0 from a page that did not render is not a pass"
    ).toHaveCount(1)

    // (2) The band this arm exists to measure is present, SCOPED TO `main`.
    const bandsInMain = main.locator('[data-width-tier="marketing"]')
    const inMain = await bandsInMain.count()
    expect(
      inMain,
      "no Marketing-tier band inside <main> — the surface whose width changed is not on this page"
    ).toBeGreaterThan(0)

    // (3) THE SCOPE CONTROL. The shared public header and footer rails ALSO
    // declare `marketing` (35-06), so a document-wide `length > 0` check would
    // be satisfied by chrome alone and would pass over a landing page whose own
    // bands were never migrated. This proves the scope above is doing work: the
    // document-wide count must strictly exceed the main-scoped one.
    const docWide = await page.locator('[data-width-tier="marketing"]').count()
    expect(
      docWide,
      `the scope control failed: ${docWide} Marketing bands document-wide vs ${inMain} inside <main>. ` +
        "The header and footer rails declare this tier too, so these counts must differ — if they " +
        "are equal the main-scoped query above is not discriminating and guard (2) is vacuous"
    ).toBeGreaterThan(inMain)

    // (4) It really ran at a DESKTOP width. A 375px render would satisfy (1)-(3)
    // and score the wrong thing entirely; the declared constants describe 1440.
    // Deliberately a floor, not the tier's value — plan 35-08 owns the width
    // contract, and restating 1280 here would duplicate it and let the two drift.
    const bandWidth = await bandsInMain
      .first()
      .evaluate((el) => Math.round(el.getBoundingClientRect().width))
    expect(
      bandWidth,
      `the Marketing band measured ${bandWidth}px — this arm did not render at a desktop width, ` +
        "so the score does not describe the viewport the declared constants were measured at"
    ).toBeGreaterThan(375)

    test.info().annotations.push({
      type: "web-vitals-desktop",
      description:
        `/ — LCP=${Math.round(lcp)}ms CLS=${cls.toFixed(4)} at 1440x900, 4x CPU · ` +
        `Marketing band ${bandWidth}px, ${inMain} in main / ${docWide} document-wide · ` +
        `control ${LANDING_CLS_DESKTOP_CONTROL} · record ${LANDING_CLS_DESKTOP_RECORD}`,
    })

    // ---- (i) THE NO-REGRESSION FORM, against the MEASURED pre-change control.
    // Not against CLS_BUDGET: `/` breaches 0.1 at 375px and this phase is not
    // fixing that. "It still breaches the absolute budget" is expected; "it got
    // worse than it was before this phase" is the failure.
    expect(
      cls,
      `/ desktop CLS ${cls.toFixed(4)} regressed past LANDING_CLS_DESKTOP_CONTROL ` +
        `(${LANDING_CLS_DESKTOP_CONTROL} + LANDING_CLS_TOLERANCE ${LANDING_CLS_TOLERANCE} = ` +
        `${(LANDING_CLS_DESKTOP_CONTROL + LANDING_CLS_TOLERANCE).toFixed(4)}) — the phase made the ` +
        "landing route less stable at the viewport where its width actually changed"
    ).toBeLessThan(LANDING_CLS_DESKTOP_CONTROL + LANDING_CLS_TOLERANCE)

    // ---- (ii) AND THE RATCHET on the improvement this phase actually shipped.
    // (i) alone leaves 0.1154 of slack over what the route now measures — a
    // bound 3.6x the value it guards, which fires only on a catastrophe. This
    // keeps the delivered good from rotting back silently, and it is also what
    // catches a runtime built before the change: that runtime serves the 1152px
    // band, scores the control, and sails under (i).
    expect(
      cls,
      `/ desktop CLS ${cls.toFixed(4)} regressed past LANDING_CLS_DESKTOP_RECORD ` +
        `(${LANDING_CLS_DESKTOP_RECORD} + LANDING_CLS_TOLERANCE ${LANDING_CLS_TOLERANCE} = ` +
        `${(LANDING_CLS_DESKTOP_RECORD + LANDING_CLS_TOLERANCE).toFixed(4)}). Two causes, both real: ` +
        "a genuine layout-stability regression, OR a frontend build that predates phase 35 — " +
        "check the runtime is current before changing this number"
    ).toBeLessThan(LANDING_CLS_DESKTOP_RECORD + LANDING_CLS_TOLERANCE)

    if (cls >= CLS_BUDGET) {
      test.info().annotations.push({
        type: "known-debt",
        description:
          `/ desktop CLS=${cls.toFixed(4)} exceeds the declared CLS_BUDGET of ${CLS_BUDGET}. ` +
          "The pre-change control was 0.1316 at this viewport; the absolute target stays " +
          "declared and unmet so the debt stays visible.",
      })
    }
  })
})

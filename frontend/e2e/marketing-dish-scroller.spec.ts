import { test, expect, type Page } from "@playwright/test"

/**
 * The landing kitchen row scrolls horizontally. Before this spec nothing on
 * screen said so: the last card was hard-clipped at the container edge (measured
 * 2026-08-02 — "Lamb Biryani" cut mid-word at 390px, "Pho Bo" at 1440px) and the
 * only affordance was a scrollbar that does not exist on touch until you are
 * already scrolling.
 *
 * What is asserted here is the AFFORDANCE, not the scrolling. `overflow-x-auto`
 * has always scrolled; a test that only proved the row moves would have passed
 * on the broken version too and told us nothing.
 *
 * The affordance must also be HONEST, which is what the at-rest / at-end pair of
 * assertions is for: a fade or arrow that is always on is decoration, and
 * decoration cannot tell you there is more to see.
 *
 * ── RE-STATED FOR 33-03, NOT WEAKENED ────────────────────────────────────────
 *
 * The row used to hold FIVE invented vendors and therefore overflowed at every
 * viewport, so the spec could assert `canRight === "true"` at rest as a constant.
 * It now holds the REAL published shops — currently three — and measured on the
 * rebuilt stack:
 *
 *   390px   canLeft=false  canRight=true    (still overflows)
 *   1440px  canLeft=false  canRight=false   (three cards fill the row)
 *
 * At 1440px there is genuinely nothing to scroll, so the old assertion would now
 * fail while the component is behaving exactly as its docblock promises: *"a fade
 * that is always on reads as decoration and stops carrying information"*.
 *
 * Deleting or loosening the assertion would remove the only guard on the
 * affordance this phase promised to preserve. So it is re-stated to assert the
 * INVARIANT that was always the real intent — **the disclosure state matches
 * whether the row actually overflows** — which is strictly stronger, because it
 * now also catches a fade shown over a row with nothing behind it. Overflow is
 * MEASURED per run rather than assumed from the viewport, so the spec stays
 * correct whether the seed has three shops or thirty.
 */

const SCROLLER_LABEL = "Dishes cooking near you"

/**
 * Located by ROLE + accessible name, never by the `[aria-label="…"]` attribute
 * selector the previous version of this spec used.
 *
 * React streams this page with a SECOND copy of the whole shell parked in
 * `<div hidden id="S:n">` for roughly 300 ms. An attribute selector matches both
 * copies and Playwright fails on a strict-mode violation — measured here, 2
 * elements. `getByRole` matches only the live one, because the staging copy is
 * `hidden` and hidden content has no accessible role.
 *
 * The old spec never hit this ONLY because it waited on `networkidle`, which
 * happened to outlast the staging buffer. Removing that wait — required, because
 * 33-07 adds a client island that holds a request open and the page would then
 * never reach network idle — exposed the race immediately. So the wait was not
 * merely redundant; it was masking a fragile locator. The same race was filed as
 * a product bug twice, #556 and #593, before being recognised as a locator choice.
 *
 * The aria-label itself is unchanged and byte-identical; only how this spec
 * reaches it has changed.
 */
const scroller = (page: Page) => page.getByRole("region", { name: SCROLLER_LABEL })

async function gotoRow(page: Page) {
  // `domcontentloaded`, not `networkidle` — see above.
  await page.goto("/", { waitUntil: "domcontentloaded" })
  await expect(scroller(page)).toBeVisible({ timeout: 20_000 })
  await scroller(page).scrollIntoViewIfNeeded()
  // Let the ResizeObserver land its first measurement.
  await page.waitForTimeout(250)
}

/** Read the wrapper's disclosure state — the single source both fades and arrows use. */
async function edges(page: Page) {
  const wrapper = scroller(page).locator("xpath=..")
  return {
    canLeft: await wrapper.getAttribute("data-can-left"),
    canRight: await wrapper.getAttribute("data-can-right"),
  }
}

/**
 * Arrows, scoped to the LIVE region rather than the document.
 *
 * `getByTestId` has exactly the same exposure to the streaming staging buffer as
 * the attribute selector above — it would match the hidden copy too. Resolving
 * them through the live scroller.s wrapper keeps the strict-mode guarantee.
 */
const arrow = (page: Page, side: "left" | "right") =>
  scroller(page).locator("xpath=..").getByTestId(`dish-scroll-`)

/** Does the row have anywhere to scroll at this viewport? Measured, not assumed. */
async function overflows(page: Page): Promise<boolean> {
  return scroller(page).evaluate((el) => el.scrollWidth - el.clientWidth > 2)
}

test.describe("marketing dish row — scroll affordance", () => {
  test("discloses more-to-the-right only when there IS more, and stops claiming it at the end", async ({
    page,
  }) => {
    await gotoRow(page)

    // Non-vacuity: an empty row would make every assertion below trivially true.
    const cards = await scroller(page).locator("a").count()
    expect(cards, "the kitchen row served no cards at all").toBeGreaterThan(0)

    const canScroll = await overflows(page)
    const atRest = await edges(page)

    if (!canScroll) {
      // The row fits. The affordance must be SILENT — this is the honesty half of
      // the contract, and it is a real assertion, not an escape hatch: a fade or
      // arrow shown here would be advertising content that does not exist.
      expect(atRest.canRight, "row fits, so it must not claim more to the right").toBe("false")
      expect(atRest.canLeft, "row fits, so it must not claim more to the left").toBe("false")
      await expect(arrow(page, "right"), "no arrow over a row that fits").toBeHidden()
      await expect(arrow(page, "left"), "no arrow over a row that fits").toBeHidden()
      return
    }

    // The row overflows: at rest, nothing to the left, more to the right.
    expect(atRest.canRight, "row overflows and must disclose more to the right at rest").toBe("true")
    expect(atRest.canLeft, "nothing is off-screen to the left at rest").toBe("false")

    // Drive it to the far end and re-read.
    await scroller(page).evaluate((el) => {
      el.scrollLeft = el.scrollWidth - el.clientWidth
    })
    await page.waitForTimeout(250)

    const atEnd = await edges(page)
    expect(atEnd.canLeft, "content is now off-screen to the left").toBe("true")
    expect(atEnd.canRight, "nothing further right — the affordance must stop claiming there is").toBe("false")
  })

  test("the row is reachable as a labelled, focusable region", async ({ page }) => {
    await gotoRow(page)
    const region = scroller(page)
    await expect(region).toHaveAttribute("role", "region")
    await expect(region).toHaveAttribute("tabindex", "0")
  })

  test("@desktop-only arrows appear for a fine pointer and hide at the edge they cannot serve", async ({
    page,
  }) => {
    await gotoRow(page)

    const left = arrow(page, "left")
    const right = arrow(page, "right")

    if (!(await overflows(page))) {
      // Same honesty contract as above. A real desktop Chromium reports
      // (hover: hover) and (pointer: fine), so the pointer half of the gate is
      // satisfied — which makes this a genuine assertion that the OTHER half of
      // the gate (there is somewhere to go) is doing its job.
      await expect(right, "no right arrow when the row fits").toBeHidden()
      await expect(left, "no left arrow when the row fits").toBeHidden()
      return
    }

    await expect(right, "right arrow should be offered when there is more to the right").toBeVisible()
    await expect(left, "left arrow is meaningless at the start of the row").toBeHidden()

    await right.click()
    await page.waitForTimeout(600) // smooth scroll settle

    await expect(left, "after moving right, back is now a real option").toBeVisible()
  })
})

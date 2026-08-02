import { test, expect, type Page } from "@playwright/test"

/**
 * The "Cooking near you right now" row scrolls horizontally. Before this spec
 * nothing on screen said so: the last card was hard-clipped at the container
 * edge (measured 2026-08-02 — "Lamb Biryani" cut mid-word at 390px, "Pho Bo"
 * at 1440px) and the only affordance was a scrollbar that does not exist on
 * touch until you are already scrolling.
 *
 * What is asserted here is the AFFORDANCE, not the scrolling. `overflow-x-auto`
 * has always scrolled; a test that only proved the row moves would have passed
 * on the broken version too and told us nothing.
 *
 * The affordance must also be HONEST, which is what the at-rest / at-end pair
 * of assertions is for: a fade or arrow that is always on is decoration, and
 * decoration cannot tell you there is more to see.
 */

const SCROLLER = '[aria-label="Dishes cooking near you"]'

async function gotoRow(page: Page) {
  await page.goto("/", { waitUntil: "networkidle" })
  await page.locator(SCROLLER).scrollIntoViewIfNeeded()
  // Let the ResizeObserver land its first measurement.
  await expect(page.locator(SCROLLER)).toBeVisible()
  await page.waitForTimeout(250)
}

/** Read the wrapper's disclosure state — the single source both fades and arrows use. */
async function edges(page: Page) {
  const wrapper = page.locator(SCROLLER).locator("xpath=..")
  return {
    canLeft: await wrapper.getAttribute("data-can-left"),
    canRight: await wrapper.getAttribute("data-can-right"),
  }
}

test.describe("marketing dish row — scroll affordance", () => {
  test("discloses more-to-the-right at rest, and stops claiming it at the end", async ({ page }) => {
    await gotoRow(page)

    // At rest: nothing to the left, more to the right. If the row did not
    // overflow at this viewport both would be false and the affordance would
    // correctly be absent — so assert canRight explicitly rather than
    // inferring overflow.
    const atRest = await edges(page)
    expect(atRest.canRight, "row should disclose more content to the right at rest").toBe("true")
    expect(atRest.canLeft, "nothing is off-screen to the left at rest").toBe("false")

    // Drive it to the far end and re-read.
    await page.locator(SCROLLER).evaluate((el) => {
      el.scrollLeft = el.scrollWidth - el.clientWidth
    })
    await page.waitForTimeout(250)

    const atEnd = await edges(page)
    expect(atEnd.canLeft, "content is now off-screen to the left").toBe("true")
    expect(atEnd.canRight, "nothing further right — the affordance must stop claiming there is").toBe("false")
  })

  test("the row is reachable as a labelled, focusable region", async ({ page }) => {
    await gotoRow(page)
    const region = page.locator(SCROLLER)
    await expect(region).toHaveAttribute("role", "region")
    await expect(region).toHaveAttribute("tabindex", "0")
  })

  test("@desktop-only arrows appear for a fine pointer and hide at the edge they cannot serve", async ({ page }) => {
    await gotoRow(page)

    const left = page.getByTestId("dish-scroll-left")
    const right = page.getByTestId("dish-scroll-right")

    // A real desktop Chromium reports (hover: hover) and (pointer: fine).
    await expect(right, "right arrow should be offered when there is more to the right").toBeVisible()
    await expect(left, "left arrow is meaningless at the start of the row").toBeHidden()

    await right.click()
    await page.waitForTimeout(600) // smooth scroll settle

    await expect(left, "after moving right, back is now a real option").toBeVisible()
  })
})

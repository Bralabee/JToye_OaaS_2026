/**
 * The cookie notice on a phone: compact, first in focus order, never over the focused element, and
 * absent from /invite (#854; swarm SW-CLR-12 and SW-TND-03).
 *
 * WHAT WAS MEASURED. On an iPhone 13 the notice took about 170-182 px of an 844 px viewport (20%),
 * sat over the seller panel and hero on first paint, and sat over the invite form's Last name and
 * Password before the first tap. The brief for this change is "no more than ~10% of a 390x664
 * viewport": 66.4 px.
 *
 * STACK-FREE, like public-layout.spec.ts: the public API is answered by stubPublicApi, and the
 * invitation preview by a route below, so a built frontend is all it needs and it can run on every
 * pull request. It is tagged @mobile-only because the geometry is a phone's (the desktop project
 * excludes it); the viewport is fixed here at 390x664 rather than inherited from the project.
 *
 * EVERY ASSERTION HERE HAS A BREAK ARM. The height is read by `cardHeight`, and the INSTRUMENT test
 * forces the card to 200 px and requires the same function to report it; the overlap probe is the one
 * a11y-storefront.spec.ts uses and is broken the same way (the card moved over the focused element).
 */
import { test, expect, type Page } from "@playwright/test"
import { stubPublicApi, resolveStorefrontPath, openStorefront } from "./helpers/public-surface"

const VIEWPORT = { width: 390, height: 664 }
/** "No more than ~10% of the viewport". */
const MAX_FRACTION = 0.1
const NOTICE = 'section[aria-label="Cookie notice"]'

test.use({ viewport: VIEWPORT, isMobile: true, hasTouch: true })

/** The DRAWN card, not the full-bleed, pointer-transparent positioning wrapper. */
async function cardHeight(page: Page): Promise<number> {
  return page.evaluate((selector) => {
    const notice = document.querySelector(selector) as HTMLElement | null
    if (!notice) return -1
    const card = (notice.querySelector(".pointer-events-auto") as HTMLElement | null) ?? notice
    return card.getBoundingClientRect().height
  }, NOTICE)
}

/** The band of the viewport the notice covers: from the card's top edge to the bottom of the screen. */
async function coveredBand(page: Page): Promise<number> {
  return page.evaluate((selector) => {
    const notice = document.querySelector(selector) as HTMLElement | null
    if (!notice) return -1
    const card = (notice.querySelector(".pointer-events-auto") as HTMLElement | null) ?? notice
    return window.innerHeight - card.getBoundingClientRect().top
  }, NOTICE)
}

interface TabStop {
  index: number
  label: string
  inNotice: boolean
  obscured: boolean
  scrollY: number
}

async function tabStopNow(page: Page, index: number): Promise<TabStop> {
  return page.evaluate(
    ({ index, selector }) => {
      const el = document.activeElement as HTMLElement | null
      const notice = document.querySelector(selector) as HTMLElement | null
      const label =
        !el || el === document.body
          ? "BODY"
          : el.getAttribute("aria-label") || (el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 60)
      const inNotice = !!(el && notice && notice.contains(el))
      let obscured = false
      if (el && el !== document.body && notice && !inNotice) {
        const a = el.getBoundingClientRect()
        const card = (notice.querySelector(".pointer-events-auto") as HTMLElement | null) ?? notice
        const b = card.getBoundingClientRect()
        // 1px of slack on the vertical edge only: scroll-padding is the band rounded UP to a whole
        // pixel and a scrolled element lands on a fractional one, so an element that sits exactly on
        // the card's top edge (the browser did its job) can read as overlapping by a hair. The
        // INSTRUMENT arm below moves the card 8px over the element and is still caught.
        obscured = a.width > 0 && a.height > 0 && a.top < b.bottom && a.bottom > b.top + 1 && a.left < b.right && a.right > b.left
      }
      return { index, label, inNotice, obscured, scrollY: window.scrollY }
    },
    { index, selector: NOTICE },
  )
}

/**
 * Resolve once the page has stopped scrolling (scrollY unchanged for 4 frames, 1.5 s at most). A focus
 * change scrolls the element into view, and the root has `scroll-behavior: smooth` for some users, so
 * a rect read straight after Tab is a rect from the middle of the scroll, not the one a person sees.
 */
async function settled(page: Page): Promise<void> {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        let last = window.scrollY
        let still = 0
        const started = performance.now()
        const tick = () => {
          if (window.scrollY === last) still += 1
          else {
            still = 0
            last = window.scrollY
          }
          if (still >= 4 || performance.now() - started > 1500) resolve()
          else requestAnimationFrame(tick)
        }
        requestAnimationFrame(tick)
      }),
  )
}

async function walkTabStops(page: Page, stops: number): Promise<TabStop[]> {
  const out: TabStop[] = []
  for (let i = 1; i <= stops; i++) {
    await page.keyboard.press("Tab")
    await settled(page)
    out.push(await tabStopNow(page, i))
  }
  return out
}

test.describe("the cookie notice on a 390x664 phone @mobile-only", () => {
  test.beforeEach(async ({ context }) => {
    await stubPublicApi(context)
  })

  test("takes no more than 10% of the viewport on the landing page and on the shop list", async ({ page }) => {
    const limit = VIEWPORT.height * MAX_FRACTION
    for (const path of ["/", "/shop"]) {
      await page.goto(path)
      await expect(page.locator(NOTICE), `the notice must show at ${path} for this to measure anything`).toBeVisible({ timeout: 15_000 })
      // The 200 ms entrance (a transform) must have settled before anything is measured.
      await page.waitForTimeout(500)
      const height = await cardHeight(page)
      const band = await coveredBand(page)
      expect(height, `${path}: the card is ${height}px of ${VIEWPORT.height}px (limit ${limit}px)`).toBeGreaterThan(0)
      expect(height, `${path}: the card is ${height}px, over 10% of the viewport`).toBeLessThanOrEqual(limit + 1)
      expect(band, `${path}: the notice covers a ${band}px band, over 10% of the viewport`).toBeLessThanOrEqual(limit + 1)
    }
  })

  test("INSTRUMENT: a 200px card is reported as too tall by the same measure", async ({ page }) => {
    await page.goto("/")
    await expect(page.locator(NOTICE)).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(500)
    await page.evaluate((selector) => {
      const card = document.querySelector(selector + " .pointer-events-auto") as HTMLElement
      card.style.height = "200px"
    }, NOTICE)
    const height = await cardHeight(page)
    expect(height, "the height probe did not see a card forced to 200px").toBeGreaterThanOrEqual(199)
    expect(height).toBeGreaterThan(VIEWPORT.height * MAX_FRACTION + 1)
  })

  test("is the first stop in focus order and never covers the focused element across the shop page", async ({ page }) => {
    const path = await resolveStorefrontPath(page)
    await openStorefront(page, path)
    await expect(page.locator(NOTICE), "the notice must show for this walk to mean anything").toBeVisible()

    const stops = await walkTabStops(page, 30)
    const gotIt = stops.find((s) => s.label === "Got it")
    expect(gotIt?.index ?? Infinity, `"Got it" among the first stops; walked: ${JSON.stringify(stops.map((s) => s.label))}`).toBeLessThanOrEqual(3)

    const obscured = stops.filter((s) => s.obscured)
    expect(obscured, `focused element under the notice: ${JSON.stringify(obscured)}`).toEqual([])

    // Non-vacuity: most stops were page content, and the walk scrolled below the fold.
    expect(stops.filter((s) => !s.inNotice && s.label !== "BODY").length).toBeGreaterThanOrEqual(15)
    expect(Math.max(...stops.map((s) => s.scrollY)), "the walk never scrolled, so it never met the notice's band").toBeGreaterThan(0)

    // INSTRUMENT: focus a content link (the last tab stop may be the browser's own chrome or a dev
    // overlay, which has no box), put the card over it; the probe must report it.
    await page.locator("main a[href]:visible").first().focus()
    await page.evaluate((selector) => {
      const el = document.activeElement as HTMLElement
      const notice = document.querySelector(selector) as HTMLElement
      const r = el.getBoundingClientRect()
      notice.style.top = `${Math.max(0, r.top - 8)}px`
      notice.style.bottom = "auto"
    }, NOTICE)
    const forced = await tabStopNow(page, 31)
    expect(forced.inNotice).toBe(false)
    expect(forced.obscured, "the overlap probe missed a notice drawn over the focused element").toBe(true)
  })
})

test.describe("the invitation page /invite @mobile-only", () => {
  test.beforeEach(async ({ context }) => {
    await context.route("**/staff-invites/preview", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          businessName: "Test Kitchen Ltd",
          role: "STAFF",
          shopName: "Test Kitchen",
          inviterName: "Ade",
          email: "tunde@example.com",
          accountState: "NEW",
        }),
      }),
    )
  })

  test("shows no cookie notice, so Last name and Password are not covered on first paint", async ({ page }) => {
    await page.goto("/invite#token=00000000-0000-0000-0000-000000000001.abcdef")
    const last = page.getByLabel("Last name")
    const password = page.getByLabel("Password", { exact: true })
    await expect(last, "the invitation form did not render, so nothing below was checked").toBeVisible({ timeout: 15_000 })
    await password.scrollIntoViewIfNeeded()
    await page.waitForTimeout(800)

    expect(await page.locator(NOTICE).count(), "the cookie notice is mounted on /invite").toBe(0)
    // And the geometry the swarm reported: no fixed element is drawn over either field.
    for (const field of [last, password]) {
      const covered = await field.evaluate((el) => {
        const r = el.getBoundingClientRect()
        const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
        return hit !== el && !el.contains(hit)
      })
      expect(covered, "a field on /invite is covered by another element").toBe(false)
    }
  })

  test("CONTROL: the same visit to a route that is not carved out still shows the notice", async ({ page }) => {
    await page.goto("/legal/cookies")
    await expect(page.locator(NOTICE)).toBeVisible({ timeout: 15_000 })
  })
})

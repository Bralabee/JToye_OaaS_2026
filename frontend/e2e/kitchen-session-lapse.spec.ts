/**
 * 37-18 (D-14, D-08 on the kitchen; UXT-021, UI-SPEC K2): a lapsed kitchen board stops LOUDLY.
 *
 * The persona run found the kitchen board quietly turning into a sign-in page when its session
 * lapsed, so a wall-mounted screen stopped showing orders with nothing to say so. This spec drives
 * the REAL stack (real SSO, real core-java, no route stubs) through the transition:
 *
 *   1. sign in as the vendor, open the kitchen, turn sound on;
 *   2. end the session the way a lapse does from the browser's side: the NextAuth session cookie
 *      goes, so the next read carries no token, core-java answers 401, the api-client's one
 *      refresh comes back empty, and the cancellable `jtoye:session-lapsed` event is dispatched;
 *   3. trigger a read at once (the board re-reads on the browser's `online` event) rather than
 *      waiting up to 60 s for the poll;
 *   4. assert the overlay, the UNCHANGED URL, the "Signed out" pill and the STOPPED title; then
 *      "Sign in again" returns the tablet to /dashboard/kitchen (the Keycloak SSO session is still
 *      alive, so it comes straight back).
 *
 * Plus the goods it must not break: a deliberate Sign out shows no overlay (P2-TUN-14).
 *
 * And an INSTRUMENT arm (F22): the same lapse on a page where nobody can claim the event (an init
 * script swallows it before the kitchen's listener runs) must navigate to /auth/signin and show NO
 * overlay. If that arm ever sees the overlay, the overlay assertion above proves nothing.
 *
 * SSE/STOMP never reaches an idle network, so nothing here waits for `networkidle` except the one
 * hydration settle on the sign-in page that kitchen-flow.spec.ts uses too (with a catch).
 *
 * Sound itself cannot be heard by Playwright: the alarm is proven in jest
 * (components/dashboard/kitchen/__tests__/kds-board-stopped.test.tsx) and by ear at the 37-23 UAT.
 *
 * Run: npx playwright test e2e/kitchen-session-lapse.spec.ts
 */

import { test, expect, type BrowserContext, type Page } from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const KITCHEN = `${BASE}/dashboard/kitchen`
const LAPSE_EVENT = "jtoye:session-lapsed"
const HEADLINE = "Signed out: new orders are NOT showing"
/**
 * How long dropAppSession waits after the session endpoint has read `null` twice.
 *
 * frontend/lib/api-client.ts holds a freshly read session in memory for SESSION_BURST_TTL_MS
 * (2 s, #963, shipped in #1045), so for that long after the cookie is gone a request still goes
 * out with the old Bearer. Measured on nightly 40258034071: the kitchen read came 1545 ms after
 * the last api-client session read (741740 -> 743285 ms), still carried the old Bearer, got 200,
 * so nothing lapsed, and the board's next poll was 60 s away. The wait must stay ABOVE the TTL;
 * frontend/lib/__tests__/api-client-session-lapse.test.ts fails if it does not.
 */
const SESSION_BURST_WAIT_MS = 2_500

async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  await expect(ssoButton, "no 'Sign in with Keycloak' button on /auth/signin").toHaveCount(1)
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
  // Let React hydrate before clicking (kitchen-flow.spec.ts: an early click can no-op).
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()
  try {
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 25_000 })
  } catch {
    if (page.url().includes("/auth/signin")) {
      await ssoButton.click({ force: true }).catch(() => {})
    }
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 20_000 })
  }
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
}

/**
 * Query root for the LIVE document (#593, kitchen-flow.spec.ts): while Next streams this route,
 * React parks a second copy of the whole board in a `<div hidden>`, so a raw test-id locator
 * resolves to 2 elements. Measured here on the first live run: `getByTestId('kds-sound-control')`
 * strict-mode failed with exactly that. Role locators are immune; test ids are scoped.
 */
const live = (page: Page) => page.locator("body > div:not([hidden])")

/** Open the kitchen and wait until it has read its board (the pill has left "Connecting…"). */
async function openKitchen(page: Page) {
  await page.goto(KITCHEN, { waitUntil: "domcontentloaded" })
  await expect(page.getByRole("heading", { level: 1, name: "Kitchen Display" })).toBeVisible({
    timeout: 20_000,
  })
  await expect(live(page).getByTestId("kds-feed-pill")).toBeVisible({ timeout: 20_000 })
}

async function turnOnSound(page: Page) {
  await live(page).getByTestId("kds-sound-control").getByText("Turn on sound").click()
  await expect(live(page).getByTestId("kds-sound-control")).toContainText("Mute alerts")
}

const readSession = (page: Page) =>
  page.evaluate(async () => (await fetch("/api/auth/session", { cache: "no-store" })).text())

/**
 * End the app session from the browser's side: drop every NextAuth session-token cookie, until the
 * session endpoint really reads `null`.
 *
 * WHY A LOOP, measured on the first live run: NextAuth re-issues the session cookie on every
 * session read, so a `/api/auth/session` request already in flight when the cookie is cleared
 * answers with a fresh `Set-Cookie` and the session comes straight back; the next board read then
 * got 200 and nothing lapsed. So: clear, read the session, and require `null` twice, a beat apart,
 * so a late in-flight response cannot resurrect it after the check.
 *
 * "Dropped" also covers the api-client's in-memory session: the helper then waits out its burst
 * cache (SESSION_BURST_WAIT_MS), so the next read goes out with no Bearer at all.
 */
async function dropAppSession(page: Page, context: BrowserContext) {
  const initial = (await context.cookies()).filter((c) => c.name.includes("authjs.session-token"))
  expect(initial.length, "no NextAuth session cookie to drop: the login did not land").toBeGreaterThan(0)
  const clearAndRead = async () => {
    for (const c of (await context.cookies()).filter((k) => k.name.includes("authjs.session-token"))) {
      await context.clearCookies({ name: c.name })
    }
    return readSession(page)
  }
  await expect.poll(clearAndRead, { timeout: 15_000, intervals: [500] }).toBe("null")
  await page.waitForTimeout(1_500)
  await expect.poll(clearAndRead, { timeout: 10_000, intervals: [500] }).toBe("null")
  // Outlast the api-client's session burst cache, or the next read still carries the old Bearer.
  await page.waitForTimeout(SESSION_BURST_WAIT_MS)
}

/** Make the board read now: it re-reads on the browser's `online` event (#106). */
async function triggerRead(page: Page) {
  await page.evaluate(() => window.dispatchEvent(new Event("online")))
}

test.describe("Kitchen: a lapsed session stops the board loudly (37-18, K2)", () => {
  test.setTimeout(120_000)

  test("lapse: overlay, unchanged URL, 'Signed out' pill, STOPPED title; Sign in again returns to the kitchen", async ({
    page,
    context,
  }) => {
    await vendorLogin(page)
    await openKitchen(page)
    await turnOnSound(page)

    await dropAppSession(page, context)
    await triggerRead(page)

    const overlay = page.getByRole("alertdialog", { name: HEADLINE })
    await expect(overlay).toBeVisible({ timeout: 20_000 })
    expect(new URL(page.url()).pathname).toBe("/dashboard/kitchen")
    await expect(page).toHaveTitle("STOPPED · Kitchen Display — J'Toye")
    // Sound was turned on, so the overlay must not claim it cannot ring.
    await expect(overlay).not.toContainText("sound was never turned on")
    // Behind the overlay the page is inert, so the pill is read from the DOM, not the a11y tree.
    await expect(live(page).getByTestId("kds-feed-pill")).toContainText("Signed out")
    await expect(live(page).getByTestId("kds-feed-pill")).not.toContainText("Live")

    const signInAgain = overlay.getByRole("link", { name: "Sign in again" })
    await expect(signInAgain).toBeFocused()
    await expect(signInAgain).toHaveAttribute("href", "/auth/signin?callbackUrl=%2Fdashboard%2Fkitchen")
    await signInAgain.click()
    await page.waitForURL(/\/auth\/signin\?callbackUrl=/, { timeout: 20_000 })

    // The Keycloak SSO session outlived the app cookie, so signing in again comes straight back.
    await vendorLoginFromHere(page)
    await page.waitForURL(/\/dashboard\/kitchen/, { timeout: 30_000 })
    await expect(page.getByRole("heading", { level: 1, name: "Kitchen Display" })).toBeVisible()
    await expect(page.getByRole("alertdialog")).toHaveCount(0)
  })

  test("a deliberate Sign out shows no overlay (P2-TUN-14)", async ({ page }) => {
    await vendorLogin(page)
    await openKitchen(page)
    await turnOnSound(page)

    // Watch for the overlay for the whole of the sign-out, not just at the end.
    await page.evaluate(() => {
      const w = window as unknown as { __sawBoardStopped: boolean }
      w.__sawBoardStopped = false
      new MutationObserver(() => {
        if (document.querySelector('[role="alertdialog"][data-testid="kds-board-stopped"]')) {
          w.__sawBoardStopped = true
        }
      }).observe(document.body, { childList: true, subtree: true })
    })

    const sidebarSignOut = page.getByRole("button", { name: /^sign out$/i })
    if (await sidebarSignOut.isVisible().catch(() => false)) {
      await sidebarSignOut.click()
    } else {
      // Below md the sidebar is hidden; Sign out lives in the mobile "More" sheet.
      await page.getByRole("button", { name: /more/i }).click()
      await page.getByRole("button", { name: /sign out/i }).click()
    }
    // Before the browser leaves, read the observer. It may already have left; then the overlay
    // could not have been seen in this document either way, and the URL check below decides.
    const saw = await page
      .evaluate(() => (window as unknown as { __sawBoardStopped?: boolean }).__sawBoardStopped)
      .catch(() => false)
    expect(saw ?? false).toBe(false)
    await page.waitForURL((url) => !url.pathname.startsWith("/dashboard/kitchen"), { timeout: 30_000 })
  })

  test("INSTRUMENT: on a page that cannot claim the lapse, the overlay is absent and the browser goes to sign-in", async ({
    page,
    context,
  }) => {
    // Swallow the lapse before any page listener sees it: this is "a page without the claim".
    await context.addInitScript((eventName: string) => {
      window.addEventListener(eventName, (e) => e.stopImmediatePropagation(), { capture: true })
    }, LAPSE_EVENT)
    await vendorLogin(page)
    await openKitchen(page)

    await dropAppSession(page, context)
    await triggerRead(page)

    await page.waitForURL(/\/auth\/signin/, { timeout: 20_000 })
    const sawOverlay = await page
      .getByRole("alertdialog", { name: HEADLINE })
      .waitFor({ state: "visible", timeout: 3_000 })
      .then(() => true, () => false)
    expect(sawOverlay, "INSTRUMENT: the overlay showed on a page that could not claim the lapse").toBe(false)
  })
})

/**
 * UI-SPEC K2 backstop (long-text) and the plan's recorded overflow assumption: at 375x667 with the
 * root at 130% text, the headline and body wrap inside `max-w-xl px-6` with nothing clipped, and
 * both 56 px buttons stay reachable (by scrolling the overlay) and focusable, for BOTH trigger
 * variants. Each test ends with an INSTRUMENT step: forcing the headline onto one line must make
 * the same measurement report overflow, or the "no clipping" reading above proves nothing.
 */
/**
 * WHAT IS MEASURED, and why not the obvious thing. The first version compared each text element's
 * own scrollWidth with its clientWidth, and its INSTRUMENT step proved that vacuous on the live
 * stack: the headline is a centred flex item that grows to fit its text, so a one-line headline
 * read 930/930 (and 1200/1200 for the access-ended copy), "not clipped", while running off both
 * edges of a 375 px screen. The honest question is whether each text box stays inside the
 * viewport horizontally, and whether the overlay itself has sideways overflow.
 */
async function measureOverlay(page: Page) {
  return page.getByRole("alertdialog").evaluate((overlay) => {
    const vw = document.documentElement.clientWidth
    const box = (el: Element | null) => {
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { left: r.left, right: r.right }
    }
    const headline = box(overlay.querySelector("h2"))
    const body = box(overlay.querySelector("p"))
    const sidewaysOverflow = (overlay as HTMLElement).scrollWidth > (overlay as HTMLElement).clientWidth
    const outside = (b: { left: number; right: number } | null) => !b || b.left < 0 || b.right > vw
    return { vw, headline, body, sidewaysOverflow, clipped: sidewaysOverflow || outside(headline) || outside(body) }
  })
}

async function assertK2Layout(page: Page, primaryName: string) {
  const overlay = page.getByRole("alertdialog")
  await expect(overlay).toBeVisible({ timeout: 20_000 })
  const m = await measureOverlay(page)
  expect(m.headline, "the K2 headline is missing").not.toBeNull()
  expect(m.body, "the K2 body is missing").not.toBeNull()
  expect(m.vw).toBeLessThanOrEqual(375)
  expect(m, "the K2 headline or body leaves a 375 px screen, or the overlay scrolls sideways").toMatchObject({
    clipped: false,
  })

  // Both buttons reachable: scrolled into view inside the overlay, fully on screen, 56 px tall.
  for (const control of [
    overlay.getByRole(primaryName === "Sign in again" ? "link" : "button", { name: primaryName }),
    overlay.getByRole("button", { name: "Silence alarm" }),
  ]) {
    await control.scrollIntoViewIfNeeded()
    const box = await control.boundingBox()
    expect(box, `${primaryName}: a K2 button has no box`).not.toBeNull()
    expect(box!.height).toBeGreaterThanOrEqual(56)
    expect(box!.y).toBeGreaterThanOrEqual(0)
    expect(box!.y + box!.height).toBeLessThanOrEqual(667)
  }
  // ...and focusable: focus starts on the primary, Tab reaches the secondary.
  await overlay.getByRole(primaryName === "Sign in again" ? "link" : "button", { name: primaryName }).focus()
  await page.keyboard.press("Tab")
  await expect(overlay.getByRole("button", { name: "Silence alarm" })).toBeFocused()

  // INSTRUMENT: the same measurement must be able to see a clipped headline.
  await page.addStyleTag({ content: "#kds-board-stopped-headline{white-space:nowrap}" })
  const broken = await measureOverlay(page)
  expect(broken.clipped, "INSTRUMENT: a one-line headline did not read as clipped, so the check above cannot fail").toBe(
    true
  )
}

test.describe("Kitchen: the board-stopped overlay fits a small screen at 130% text (K2 backstop)", () => {
  test.setTimeout(120_000)

  test("signed-out variant at 375x667, root 130%: nothing clipped, both buttons reachable", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 })
    await vendorLogin(page)
    await openKitchen(page)
    await page.addStyleTag({ content: "html{font-size:130%}" })

    // Layout only: the real lapse path is the first describe's. The kitchen claims this event the
    // same way it claims the api-client's.
    await page.evaluate((name: string) => {
      window.dispatchEvent(new CustomEvent(name, { cancelable: true }))
    }, LAPSE_EVENT)

    await expect(page.getByRole("alertdialog", { name: HEADLINE })).toBeVisible({ timeout: 10_000 })
    await assertK2Layout(page, "Sign in again")
  })

  test("access-ended variant at 375x667, root 130%: nothing clipped, both buttons reachable", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 })
    await vendorLogin(page)
    await openKitchen(page)
    await page.addStyleTag({ content: "html{font-size:130%}" })

    // The server's typed refusal, through the real api-client (a live revoke is the 37-23 UAT's).
    await page.route("**/api/v1/orders/kitchen**", (route) =>
      route.fulfill({
        status: 403,
        contentType: "application/problem+json",
        headers: { "access-control-allow-origin": BASE, "access-control-allow-credentials": "true" },
        body: JSON.stringify({ type: "https://jtoye.uk/errors/shop-access-denied", status: 403 }),
      })
    )
    await triggerRead(page)

    await expect(
      page.getByRole("alertdialog", { name: "Your access has ended: new orders are NOT showing" })
    ).toBeVisible({ timeout: 20_000 })
    await expect(live(page).getByTestId("kds-feed-pill")).toContainText("No access")
    await assertK2Layout(page, "Sign out")
  })
})

/** On /auth/signin?callbackUrl=…: press the SSO button and let the live SSO session return us. */
async function vendorLoginFromHere(page: Page) {
  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 25_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
}

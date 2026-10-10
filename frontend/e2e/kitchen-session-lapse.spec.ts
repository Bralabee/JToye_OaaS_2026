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

/** Open the kitchen and wait until it has read its board (the pill has left "Connecting…"). */
async function openKitchen(page: Page) {
  await page.goto(KITCHEN, { waitUntil: "domcontentloaded" })
  await expect(page.getByRole("heading", { level: 1, name: "Kitchen Display" })).toBeVisible({
    timeout: 20_000,
  })
  await expect(page.getByTestId("kds-feed-pill")).toBeVisible({ timeout: 20_000 })
}

async function turnOnSound(page: Page) {
  await page.getByTestId("kds-sound-control").getByText("Turn on sound").click()
  await expect(page.getByTestId("kds-sound-control")).toContainText("Mute alerts")
}

/** End the app session from the browser's side: drop every NextAuth session-token cookie. */
async function dropAppSession(context: BrowserContext) {
  const cookies = await context.cookies()
  const sessionCookies = cookies.filter((c) => c.name.includes("authjs.session-token"))
  expect(sessionCookies.length, "no NextAuth session cookie to drop: the login did not land").toBeGreaterThan(0)
  for (const c of sessionCookies) {
    await context.clearCookies({ name: c.name })
  }
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

    await dropAppSession(context)
    await triggerRead(page)

    const overlay = page.getByRole("alertdialog", { name: HEADLINE })
    await expect(overlay).toBeVisible({ timeout: 20_000 })
    expect(new URL(page.url()).pathname).toBe("/dashboard/kitchen")
    await expect(page).toHaveTitle("STOPPED · Kitchen Display — J'Toye")
    // Sound was turned on, so the overlay must not claim it cannot ring.
    await expect(overlay).not.toContainText("sound was never turned on")
    // Behind the overlay the page is inert, so the pill is read from the DOM, not the a11y tree.
    await expect(page.getByTestId("kds-feed-pill")).toContainText("Signed out")
    await expect(page.getByTestId("kds-feed-pill")).not.toContainText("Live")

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

    await dropAppSession(context)
    await triggerRead(page)

    await page.waitForURL(/\/auth\/signin/, { timeout: 20_000 })
    const sawOverlay = await page
      .getByRole("alertdialog", { name: HEADLINE })
      .waitFor({ state: "visible", timeout: 3_000 })
      .then(() => true, () => false)
    expect(sawOverlay, "INSTRUMENT: the overlay showed on a page that could not claim the lapse").toBe(false)
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

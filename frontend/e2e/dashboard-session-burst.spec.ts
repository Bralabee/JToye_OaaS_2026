/**
 * Issue #963: a /dashboard reload must coalesce GET /api/auth/session instead of
 * firing a 20+ request burst across concurrent apiClient calls.
 *
 * Root cause (#963, fixed in PR #1045):
 *   1. `frontend/lib/api-client.ts` previously called `getSession()` uncoalesced on
 *      every outgoing request interceptor invocation. Because `/dashboard` fires
 *      7+ concurrent `/api/v1/*` requests on mount (shops, products, orders x3,
 *      customers, onboarding/me, financial-transactions/summary, staff/me), every
 *      page load or reload stampeded `/api/auth/session`.
 *   2. `frontend/components/providers.tsx` mounted `<SessionProvider>` without
 *      `refetchOnWindowFocus={false}`, adding focus-triggered `/api/auth/session`
 *      refetches on top of the burst.
 *
 * This spec drives the real stack through vendor login, a full `/dashboard` reload,
 * and a window-focus event, asserting:
 *   - Non-vacuity: the reload fires at least 6 `/api/v1/` requests AND at least 1
 *     `GET /api/auth/session` request (proving the listener is not deaf and the
 *     dashboard actually re-fetched its backend data).
 *   - Coalescing (#963): `GET /api/auth/session` fires at most `MAX_SESSION_READS_PER_RELOAD`
 *     (3) times across the entire reload + focus settle window — strictly fewer
 *     than the `/api/v1/` request burst it authenticates, with zero HTTP 429s.
 *   - INSTRUMENT arm: firing uncoalesced `/api/auth/session` fetches through the
 *     same listener crosses `MAX_SESSION_READS_PER_RELOAD`, proving the counter
 *     and threshold would fail if the burst returned.
 */

import { test, expect, type Page } from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

const MAX_SESSION_READS_PER_RELOAD = 3
const MIN_API_V1_CALLS_PER_RELOAD = 6

async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto("/auth/signin", { waitUntil: "domcontentloaded" })

  const emailInput = page.locator('input[name="email"], input[type="email"]').first()
  if ((await emailInput.count()) > 0) {
    await emailInput.fill(VENDOR_USERNAME)
    await page.locator('input[name="password"], input[type="password"]').first().fill(VENDOR_PASSWORD)
    await page.locator('button[type="submit"]').first().click()
    await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
    return
  }

  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  await expect(ssoButton, "no 'Sign in with Keycloak' button on /auth/signin").toHaveCount(1)
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
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

async function waitForDashboardSettled(page: Page) {
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible({
    timeout: 20_000,
  })
  await expect(page.getByRole("heading", { name: /^Shops$/ })).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole("heading", { name: /^Products$/ })).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole("heading", { name: /^Orders$/ })).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole("heading", { name: /^Customers$/ })).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole("heading", { name: /^Recent Orders/ })).toBeVisible({
    timeout: 15_000,
  })
}

test.describe("Dashboard: /api/auth/session request burst stays coalesced on reload (#963)", () => {
  test.setTimeout(90_000)

  test("reloading /dashboard coalesces /api/auth/session across the /api/v1/* fan-out and ignores window focus", async ({
    page,
  }) => {
    await vendorLogin(page)
    await page.goto("/dashboard", { waitUntil: "domcontentloaded" })
    await waitForDashboardSettled(page)

    const sessionReads: string[] = []
    const apiV1Requests: string[] = []
    const rateLimitedResponses: Array<{ url: string; status: number }> = []

    const onRequest = (req: import("@playwright/test").Request) => {
      const url = req.url()
      if (req.method() === "GET" && url.includes("/api/auth/session")) {
        sessionReads.push(url)
      }
      if (url.includes("/api/v1/")) {
        apiV1Requests.push(`${req.method()} ${new URL(url).pathname}`)
      }
    }
    const onResponse = (res: import("@playwright/test").Response) => {
      const url = res.url()
      if (
        res.status() === 429 &&
        (url.includes("/api/auth/session") || url.includes("/api/v1/"))
      ) {
        rateLimitedResponses.push({ url, status: res.status() })
      }
    }

    page.on("request", onRequest)
    page.on("response", onResponse)

    try {
      await page.reload({ waitUntil: "domcontentloaded" })
      await waitForDashboardSettled(page)

      // Also dispatch window focus + visibilitychange after the reload settles to
      // verify SessionProvider's refetchOnWindowFocus={false} (#963) holds in a real browser.
      await page.evaluate(() => {
        window.dispatchEvent(new Event("focus"))
        document.dispatchEvent(new Event("visibilitychange"))
      })
      await page.waitForTimeout(500)

      // Non-vacuity control 1: the dashboard reload actually fanned out to core-java.
      expect(
        apiV1Requests.length,
        `expected at least ${MIN_API_V1_CALLS_PER_RELOAD} /api/v1/* requests on /dashboard reload, got: ${apiV1Requests.join(", ")}`
      ).toBeGreaterThanOrEqual(MIN_API_V1_CALLS_PER_RELOAD)

      // Non-vacuity control 2: the listener observed the reload's session read.
      expect(
        sessionReads.length,
        "expected at least 1 GET /api/auth/session on a full document reload of /dashboard"
      ).toBeGreaterThanOrEqual(1)

      // Core #963 assertion: session reads stay bounded by a small constant (<= 3)
      // and strictly below the /api/v1/* fan-out count, with zero 429s.
      expect(
        sessionReads.length,
        `GET /api/auth/session burst regression (#963): saw ${sessionReads.length} session reads across ${apiV1Requests.length} /api/v1/* calls`
      ).toBeLessThanOrEqual(MAX_SESSION_READS_PER_RELOAD)
      expect(sessionReads.length).toBeLessThan(apiV1Requests.length)
      expect(rateLimitedResponses, "saw HTTP 429 during /dashboard reload").toEqual([])

      // INSTRUMENT: prove the same listener and threshold fail when uncoalesced
      // /api/auth/session requests fire.
      await page.evaluate(async (burstCount: number) => {
        await Promise.all(
          Array.from({ length: burstCount }, (_, i) =>
            fetch(`/api/auth/session?instrument=${i}`, { cache: "no-store" })
          )
        )
      }, MAX_SESSION_READS_PER_RELOAD + 1)

      expect(
        sessionReads.length,
        "INSTRUMENT: uncoalesced /api/auth/session fetches did not push the counter past MAX_SESSION_READS_PER_RELOAD"
      ).toBeGreaterThan(MAX_SESSION_READS_PER_RELOAD)
    } finally {
      page.off("request", onRequest)
      page.off("response", onResponse)
    }
  })
})

/**
 * COMMS-03 E2E — public one-click unsubscribe flow (Surface C).
 *
 * Proves the unsubscribe link resolves and confirms opt-out end-to-end from the
 * browser: the public page reads `?tenant=&email=&category=&token=`, POSTs the
 * token to the no-auth backend, and renders the resulting state. No auth, no
 * dashboard chrome, no sign-in prompt.
 *
 * The backend is STUBBED so the two outcomes are deterministic: a genuine
 * end-to-end token can only be minted server-side from the configured HMAC
 * signing secret (inert/empty by default in dev — that is the security
 * property), so the browser can never compute a valid token itself. The stub
 * mirrors the real contract: a "valid" token → { status: "unsubscribed" }, a
 * tampered token → { status: "invalid" } (exactly what PublicUnsubscribeController
 * returns after its constant-time verify).
 *
 * Run: npx playwright test unsubscribe-flow  (needs the rebuilt frontend :3000)
 */
import { test, expect, type BrowserContext } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

const TENANT = "00000000-0000-0000-0000-000000000001"
const EMAIL = "recipient@example.com"

async function stubUnsubscribe(context: BrowserContext) {
  // The page POSTs to {/public,/api/v1/public}/unsubscribe with the token as a
  // query param. Return the state the backend would return for that token:
  // a VALID-* token verifies (unsubscribed), anything else fails (invalid).
  await context.route("**/public/unsubscribe**", (route) => {
    const token = new URL(route.request().url()).searchParams.get("token") || ""
    const valid = token.startsWith("VALID")
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ status: valid ? "unsubscribed" : "invalid" }),
    })
  })
}

test.describe("Public unsubscribe flow (COMMS-03)", () => {
  test.beforeEach(async ({ context }) => {
    await stubUnsubscribe(context)
  })

  test("a valid link resolves and confirms opt-out", async ({ page }) => {
    await page.goto(
      `${BASE}/unsubscribe?tenant=${TENANT}&email=${encodeURIComponent(EMAIL)}&category=ORDERS&token=VALID-e2e-token`,
      { waitUntil: "domcontentloaded" }
    )
    await expect(
      page.getByRole("heading", { name: /you're unsubscribed/i })
    ).toBeVisible({ timeout: 10_000 })

    // No dashboard chrome and no sign-in prompt on this public surface.
    await expect(page.getByTestId("mobile-tab-bar")).toHaveCount(0)
    await expect(page.getByRole("button", { name: /sign in/i })).toHaveCount(0)

    // The token/email must never be printed into the visible body (PII-safe).
    const body = (await page.locator("body").textContent()) || ""
    expect(body).not.toContain("VALID-e2e-token")
    expect(body).not.toContain(EMAIL)
  })

  test("a tampered token shows the invalid state", async ({ page }) => {
    await page.goto(
      `${BASE}/unsubscribe?tenant=${TENANT}&email=${encodeURIComponent(EMAIL)}&category=ORDERS&token=TAMPERED-xyz`,
      { waitUntil: "domcontentloaded" }
    )
    await expect(
      page.getByRole("heading", { name: /this link isn't valid/i })
    ).toBeVisible({ timeout: 10_000 })
  })

  test("no horizontal overflow at 375px", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 })
    await page.goto(
      `${BASE}/unsubscribe?tenant=${TENANT}&email=${encodeURIComponent(EMAIL)}&category=MARKETING&token=VALID-e2e-token`,
      { waitUntil: "domcontentloaded" }
    )
    await expect(
      page.getByRole("heading", { name: /you're unsubscribed/i })
    ).toBeVisible({ timeout: 10_000 })
    const overflow = await page.evaluate(() => {
      const el = document.scrollingElement || document.documentElement
      return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }
    })
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
  })
})

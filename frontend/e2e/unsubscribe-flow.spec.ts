/**
 * COMMS-03 E2E — public one-click unsubscribe flow (Surface C).
 *
 * Proves the unsubscribe link resolves and confirms opt-out end-to-end from the
 * browser: the public page reads `?tenant=&email=&category=&token=` from ITS OWN
 * url, POSTs them to the no-auth backend in a JSON body (#278 — never as query
 * params, which access logs capture verbatim), and renders the resulting state.
 * No auth, no dashboard chrome, no sign-in prompt.
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
  // The page POSTs to {/public,/api/v1/public}/unsubscribe. Return the state the
  // backend would return for that token: a VALID-* token verifies
  // (unsubscribed), anything else fails (invalid).
  //
  // The token is read from the JSON BODY (issue #278) — it is deliberately no
  // longer in the query string, because a query string is copied verbatim into
  // every access log on the path. The query-param read is kept as a fallback so
  // this stub mirrors the backend, which still accepts that shape for the RFC
  // 8058 one-click links already sitting in customers' inboxes.
  await context.route("**/public/unsubscribe**", (route) => {
    const request = route.request()

    let token = ""
    try {
      const body = request.postDataJSON() as { token?: string } | null
      token = body?.token ?? ""
    } catch {
      // Not JSON (e.g. an RFC 8058 form-encoded one-click POST) — fall through.
    }
    if (!token) {
      token = new URL(request.url()).searchParams.get("token") || ""
    }

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

    // The token/email must never be printed into the VISIBLE body (PII-safe).
    // Use innerText (rendered visible text) — NOT textContent, which also
    // captures the App Router RSC hydration <script> that mirrors the URL for
    // router state (the token is in the URL the recipient clicked either way;
    // the contract is that we never RENDER it into the page or its meta).
    const visible = (await page.locator("main").innerText()) || ""
    expect(visible).not.toContain("VALID-e2e-token")
    expect(visible).not.toContain(EMAIL)

    // And the token/email must not be in any meta tag (title/description/robots).
    const metas = await page.locator("head meta").allTextContents()
    const headHtml = (await page.locator("head").innerHTML()) + metas.join(" ")
    expect(headHtml).not.toContain("VALID-e2e-token")
    expect(headHtml).not.toContain(EMAIL)
    // Confirm the noindex directive is present (SEO/privacy).
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute(
      "content",
      /noindex/i
    )
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

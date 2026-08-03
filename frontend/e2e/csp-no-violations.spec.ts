/**
 * CSP violation smoke test — LOCAL/STAGING ONLY.
 *
 * This spec is intentionally NOT wired into ci-cd.yaml (per RESEARCH.md §6,
 * Q4 default): the CI gate for SEC-02 criterion 5 is the Jest unit +
 * snapshot tests (csp-headers.test.ts + header-snapshot.test.ts) wired by
 * Task 12-02-06. Run this spec locally before merging a CSP change:
 *
 *   npx playwright test e2e/csp-no-violations.spec.ts   # uses the config baseURL (:3000)
 *
 *
 * Or in staging (Task 12-02-07 manual gate, step 2):
 *
 *   PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk \
 *     npx playwright test e2e/csp-no-violations.spec.ts
 *
 * The spec requires the Next.js app to be running and a live storefront
 * slug (SHOP_SLUG below — override via PLAYWRIGHT_SHOP_SLUG) to be seeded.
 * The /dashboard sub-test tolerates a 401/redirect when no vendor session
 * is available and still asserts the CSP header is present + no violations
 * fire on the served response.
 */

import { test, expect, type Page, type ConsoleMessage } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const SHOP_SLUG = process.env.PLAYWRIGHT_SHOP_SLUG || "jollof-express-brixton-900b57a8"

/**
 * Attach console + pageerror listeners to capture CSP violation reports.
 * Browsers emit a native SecurityPolicyViolationEvent on the document; in
 * Playwright both Chromium console errors AND pageerror paths can surface
 * the violation message "Refused to ... because it violates the following
 * Content Security Policy directive: ..." — listen to both.
 */
function collectCspViolations(page: Page): string[] {
  const violations: string[] = []
  page.on("console", (msg: ConsoleMessage) => {
    const text = msg.text()
    if (msg.type() === "error" && text.includes("Content Security Policy")) {
      violations.push(text)
    }
  })
  page.on("pageerror", (err) => {
    if (err.message.includes("Content Security Policy")) {
      violations.push(err.message)
    }
  })
  return violations
}

test("homepage emits CSP header and triggers no violations", async ({ page }) => {
  const violations = collectCspViolations(page)
  const response = await page.goto(`${BASE}/`)
  expect(response).not.toBeNull()
  expect(response!.ok()).toBe(true)

  const headers = response!.headers()
  const csp = headers["content-security-policy"] || headers["content-security-policy-report-only"]
  expect(csp).toBeDefined()
  expect(csp).toContain("default-src 'self'")
  expect(csp).toContain("frame-ancestors 'none'")
  expect(csp).toContain("https://js.stripe.com")

  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(2000)
  expect(violations, `CSP violations: ${violations.join("\n")}`).toEqual([])
})

test("for-operators route emits CSP header and triggers no violations (GSAP bundled)", async ({ page }) => {
  // Proves the bundled GSAP marketing scenes (motion-D) fire zero CSP
  // violations against a prod build — no CDN reference, no 'unsafe-eval' /
  // 'unsafe-inline' added to script-src under #89 strict-dynamic.
  const violations = collectCspViolations(page)
  const response = await page.goto(`${BASE}/for-operators`)
  expect(response).not.toBeNull()
  expect(response!.ok()).toBe(true)

  const headers = response!.headers()
  const csp = headers["content-security-policy"] || headers["content-security-policy-report-only"]
  expect(csp).toBeDefined()
  expect(csp).toContain("default-src 'self'")
  expect(csp).toContain("frame-ancestors 'none'")

  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(2000)
  expect(violations, `CSP violations: ${violations.join("\n")}`).toEqual([])
})

test("storefront /shop/[slug] triggers no CSP violations", async ({ page }) => {
  const violations = collectCspViolations(page)
  const response = await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
  expect(response).not.toBeNull()
  expect(response!.ok()).toBe(true)

  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(3000)
  expect(violations, `CSP violations: ${violations.join("\n")}`).toEqual([])
})

test("dashboard route either 401s (expected without session) or emits CSP with no violations", async ({ page }) => {
  const violations = collectCspViolations(page)
  const response = await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" })
  // /dashboard may 200 (if a session cookie is picked up) or redirect to
  // /auth/signin; either way the CSP header must be present and no
  // violations should fire on the served response.
  const headers = response?.headers() ?? {}
  const csp = headers["content-security-policy"] || headers["content-security-policy-report-only"]
  expect(csp, "dashboard route must emit CSP header").toBeDefined()
  await page.waitForTimeout(1500)
  expect(violations, `CSP violations: ${violations.join("\n")}`).toEqual([])
})

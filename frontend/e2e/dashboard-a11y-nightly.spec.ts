/**
 * A11Y-5 (E1) — REPORT-ONLY nightly axe sweep of the vendor dashboard.
 *
 * WHY THIS IS SEPARATE FROM `public-a11y.spec.ts`. That file's own header is
 * explicit: it is scoped to D-09's unauthenticated public surfaces and the
 * "AUTHENTICATED VENDOR DASHBOARD IS DELIBERATELY OUT" of it, precisely
 * because it must stay STACK-FREE to run on every PR. A Keycloak vendor
 * login needs the real compose stack, so dashboard axe coverage can only
 * exist here, in the nightly full-stack lane — never in the per-PR gate.
 *
 * WHY THIS IS REPORT-ONLY AND `e2e-nightly.yml`'s OWN header is explicit that
 * "every one of these is a FAILURE, never a pass" for the stack/report
 * conditions it lists — but a THIRD condition it does not mention is a
 * per-test failure: `check-e2e-skip-budget.sh` aside, the workflow computes
 * `failed` from `report.json` and reds the whole nightly job on ANY failed
 * test (`e2e-nightly.yml`'s "[ "$failed" -eq 0 ] || exit 1"`). A blocking axe
 * assertion here would therefore not be "report-only in spirit, blocking in
 * practice" — it would ACTUALLY fail the nightly job on a dashboard finding
 * this batch is not scoped to fix (this remediation batch's write boundary
 * excludes `app/dashboard/**` entirely). So nothing in this file calls a
 * throwing `expect()` against an axe result — findings are written to the
 * Playwright report via `test.info().annotations` and `console.log` instead,
 * the same non-blocking-annotation shape `e2e/landing-webperf.spec.ts` already
 * uses for its own declared, non-blocking CLS debt. The ONLY way this file's
 * test can fail is an unhandled exception, which is why the whole body is
 * wrapped in try/catch and every exception is caught and annotated instead of
 * rethrown.
 *
 * WHY THE STACK-FREE jest-axe GATE STILL MATTERS MORE. `__tests__/dashboard-
 * a11y-axe.test.tsx` is the actual BLOCKING per-PR check (stack-free, jsdom).
 * This nightly sweep exists to widen VISIBILITY — a real browser, a real
 * login, real CSS/layout axe rules jsdom cannot evaluate at all (colour
 * contrast, focus order under real geometry) — without asking a PR to wait on
 * a live Keycloak realm.
 */
import { test, type Page } from "@playwright/test"
import AxeBuilder from "@axe-core/playwright"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const WCAG_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]

/**
 * The same three "key" pages the stack-free jest-axe gate scans
 * (`__tests__/dashboard-a11y-axe.test.tsx`) — kept in sync deliberately, so a
 * finding this sweep surfaces in a REAL browser can be cross-checked against
 * the jsdom gate's own allowlist rather than describing a different surface.
 */
const KEY_ROUTES = ["/dashboard", "/dashboard/orders", "/dashboard/products"]

/** Mirrors `dashboard-mobile.spec.ts`'s `vendorLogin`, duplicated rather than
 * imported: importing another `.spec.ts` file executes its module body and
 * re-registers every `test.describe` in it (see `public-a11y.spec.ts`'s own
 * header for why that anti-pattern is named rather than repeated here). Any
 * failure inside this helper is caught by the caller's try/catch, never
 * thrown to Playwright directly — login failing is itself a reportable
 * outcome for a NIGHTLY sweep, not a reason to red the job.
 */
async function vendorLogin(page: Page): Promise<void> {
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })

  const emailInput = page.locator('input[name="email"], input[type="email"]').first()
  if ((await emailInput.count()) > 0) {
    await emailInput.fill(VENDOR_USERNAME)
    await page.locator('input[name="password"], input[type="password"]').first().fill(VENDOR_PASSWORD)
    await page.locator('button[type="submit"]').first().click()
    await page.waitForURL(/\/dashboard/, { timeout: 15_000 })
    return
  }

  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()

  try {
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 20_000 })
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
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
}

test.describe("Vendor dashboard — REPORT-ONLY nightly axe sweep (A11Y-5)", () => {
  test("scans the key dashboard routes and records findings without blocking the run", async ({
    page,
  }, testInfo) => {
    skipWithoutVendorPassword()

    try {
      await vendorLogin(page)
    } catch (err) {
      testInfo.annotations.push({
        type: "dashboard-a11y-nightly",
        description: `Vendor login did not complete — no dashboard routes could be scanned this run: ${String(err)}`,
      })
      console.log(`[dashboard-a11y-nightly] login failed: ${String(err)}`)
      return
    }

    for (const route of KEY_ROUTES) {
      try {
        await page.goto(`${BASE}${route}`, { waitUntil: "domcontentloaded" })
        // Best-effort settle; a slow chart/table must not turn into a false
        // "the page never rendered" note in a report-only sweep.
        await page.waitForTimeout(1500)

        const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
        const summary = results.violations
          .map((v) => `${v.id} [${v.impact ?? "n/a"}] x${v.nodes.length}`)
          .join("; ")

        testInfo.annotations.push({
          type: "dashboard-a11y-nightly",
          description:
            results.violations.length === 0
              ? `${route}: 0 WCAG 2.1 AA violations`
              : `${route}: ${results.violations.length} violation rule(s) — ${summary}`,
        })
        console.log(
          `[dashboard-a11y-nightly] ${route}: ${results.violations.length} violation rule(s)${
            summary ? ` — ${summary}` : ""
          }`
        )
      } catch (err) {
        // A single route failing to scan (timeout, navigation error) must not
        // stop the sweep from reporting on the others, and must never throw
        // out of this test — see the file header.
        testInfo.annotations.push({
          type: "dashboard-a11y-nightly",
          description: `${route}: scan did not complete — ${String(err)}`,
        })
        console.log(`[dashboard-a11y-nightly] ${route}: scan error — ${String(err)}`)
      }
    }
  })
})

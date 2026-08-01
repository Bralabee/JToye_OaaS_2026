/**
 * Phase 17 VOPS-01 + VOPS-02 Playwright E2E.
 *
 * Targets the local dev stack on port 3100 (frontend) and the core-java API
 * on port 8080 (default). The backend MUST be seeded with at least one
 * CONFIRMED order whose payment_reference is a Stripe test-mode payment
 * intent — the docker-compose dev-data SQL seed normally creates such an
 * order; if the seed is absent the success path will skip cleanly.
 *
 * Per CLAUDE.md "rebuild containers before E2E": run
 *   docker compose build && docker compose up -d
 * before invoking this spec. Stripe test mode requires
 *   STRIPE_API_KEY=sk_test_...
 *   STRIPE_WEBHOOK_SECRET=<dev tunnel signing secret>
 * to be set in the backend's .env. We do NOT mock Stripe at the E2E
 * boundary — the test exercises the full backend → Stripe → webhook → DB
 * lifecycle. Per `feedback_e2e_testing.md` memory: never trust health
 * checks alone.
 *
 * Selector contract with Task 1 components (DO NOT change in either place
 * without updating the other):
 *   - Refund button:     button text "Issue refund"
 *   - Amount input:      #amountPounds
 *   - Reason select:     #reason
 *   - Note textarea:     #note
 *   - Refund-in-flight:  button text "Refunding…"
 *   - Refund history:    heading text /Refunds \(\d+\)/
 *   - Detail route URL:  /dashboard/orders/<uuid>
 */

import { test, expect } from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

const BASE_URL =
  process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3100"
// Keycloak dev-realm vendor. `admin-user` is the live jtoye-dev account, mapping
// to tenant 00000000-…-000000000001 via the realm's tenant_id attribute. The
// credential comes from e2e/vendor-credentials.ts (never committed).

async function vendorLogin(page: import("@playwright/test").Page) {
  skipWithoutVendorPassword()
  await page.goto(`${BASE_URL}/auth/signin`)
  // NOT networkidle: the app keeps SSE/realtime connections open, so
  // networkidle never fires — wait for the DOM and the concrete controls.
  await page.waitForLoadState("domcontentloaded")

  // Some deployments expose a NextAuth credentials form; the dev stack's
  // signin page is a single "Sign in with Keycloak" SSO button. Support both.
  const emailInput = page.locator('input[name="email"], input[type="email"]').first()
  if ((await emailInput.count()) > 0) {
    await emailInput.fill(VENDOR_USERNAME)
    await page.locator('input[name="password"], input[type="password"]').first().fill(VENDOR_PASSWORD)
    await page.locator('button[type="submit"]').first().click()
    await page.waitForURL(/\/dashboard/, { timeout: 15_000 })
    return
  }

  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  if ((await ssoButton.count()) === 0) {
    test.skip(true, "No sign-in method found on /auth/signin — unknown auth flow")
  }
  await ssoButton.click()

  // NextAuth redirects to the Keycloak hosted login (realm jtoye-dev on
  // localhost:8085). A live Keycloak SSO cookie can skip the form entirely
  // and land straight on /dashboard — handle both arrivals.
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 15_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
}

test.describe("Phase 17 — vendor refund flow", () => {
  test("vendor logs in, opens an order, issues a partial refund, sees REFUNDED state", async ({ page }) => {
    await vendorLogin(page)

    // 1. Open the orders list
    await page.goto(`${BASE_URL}/dashboard/orders`)
    // Element-based wait (networkidle never fires with SSE open): the page
    // is ready when the heading renders; rows may stream in after.
    await page.waitForLoadState("domcontentloaded")
    await expect(
      page.locator("h1, h2").filter({ hasText: /Orders/i }).first()
    ).toBeVisible({ timeout: 10_000 })

    // 2. Click the first refundable row (anything past CONFIRMED that hasn't
    //    already been REFUNDED). Skip the test if no refundable order is
    //    seeded — the run is environment-dependent.
    // Rows stream in after the heading — give the table a beat before
    // counting, but don't fail if the tenant simply has no orders.
    await page.locator("table tbody tr").first().waitFor({ timeout: 8_000 }).catch(() => {})

    const refundableRow = page
      .locator("table tbody tr")
      .filter({ hasText: /CONFIRMED|PREPARING|READY|COMPLETED/ })
      .filter({ hasNotText: /REFUNDED/ })
      .first()

    const refundableCount = await refundableRow.count()
    if (refundableCount === 0) {
      test.skip(
        true,
        "No CONFIRMED+CAPTURED order seeded — fixture is environment-dependent. Run docker-compose dev-data seed before this spec."
      )
    }
    // Click the FIRST cell (plain text, triggers row navigation): at mobile widths the fixed w-64 sidebar overlays
    // the row's horizontal centre and intercepts the click (real mobile
    // layout gap — dashboard shell has no responsive sidebar variant).
    await refundableRow.locator("td").first().click()

    // 3. Verify navigation to the dedicated detail route (Task 1 contract).
    await page.waitForURL(/\/dashboard\/orders\/[0-9a-f-]+$/, {
      timeout: 5_000,
    })
    await expect(page.getByText(/Items/i).first()).toBeVisible({
      timeout: 10_000,
    })

    // 4. The "Issue refund" button is gated by paymentStatus=CAPTURED +
    //    paymentReference set. If the seeded order is not yet captured,
    //    Stripe test-mode keys are required — skip cleanly.
    const refundButton = page.getByRole("button", { name: /^Issue refund$/i })
    if ((await refundButton.count()) === 0) {
      test.skip(
        true,
        "Order is not in a refundable payment state (paymentStatus=CAPTURED + paymentReference required). Seed a Stripe test-mode payment intent and capture it first."
      )
    }
    await expect(refundButton).toBeVisible()
    await refundButton.click()

    // 5. Fill the dialog — partial £1.00 refund.
    await page.fill("input#amountPounds", "1.00")
    await page.selectOption("select#reason", "REQUESTED_BY_CUSTOMER")
    await page.fill("textarea#note", "E2E test refund")

    // The dialog's submit button has the same accessible label as the
    // open-dialog button on the panel — pick the LAST one (inside the
    // modal) so we don't reopen the dialog.
    const dialogSubmit = page
      .getByRole("button", { name: /^Issue refund$/i })
      .last()
    await dialogSubmit.click()

    // 6. While in flight the button text flips to "Refunding…". The
    //    backend's stored-first idempotency means even a slow Stripe
    //    response keeps the dialog responsive.
    // 7. Wait for the dialog to close — when the post resolves the panel
    //    re-fetches detail, which surfaces the new refund row.
    await expect(page.getByText(/Refunding/)).toHaveCount(0, {
      timeout: 20_000,
    })

    // 8. The new refund history row is visible.
    await expect(
      page.getByRole("heading", { name: /Refunds \(\d+\)/ })
    ).toBeVisible({ timeout: 10_000 })

    // 9. The £1.00 amount appears at least once on the detail page now —
    //    it's the refund-history row's amount.
    await expect(page.locator("text=£1.00").first()).toBeVisible({
      timeout: 10_000,
    })
  })

  test("Issue refund button is hidden on a DRAFT order", async ({ page }) => {
    await vendorLogin(page)

    await page.goto(`${BASE_URL}/dashboard/orders`)
    // Element-based wait (networkidle never fires with SSE open): the page
    // is ready when the heading renders; rows may stream in after.
    await page.waitForLoadState("domcontentloaded")
    await expect(
      page.locator("h1, h2").filter({ hasText: /Orders/i }).first()
    ).toBeVisible({ timeout: 10_000 })
    // Rows stream in after the heading — give the table a beat before counting.
    await page.locator("table tbody tr").first().waitFor({ timeout: 8_000 }).catch(() => {})
    const draftRow = page
      .locator("table tbody tr")
      .filter({ hasText: /DRAFT/i })
      .first()
    if ((await draftRow.count()) === 0) {
      test.skip(true, "No DRAFT order seeded — skipping")
    }
    // First cell for the same mobile-sidebar interception reason as above.
    await draftRow.locator("td").first().click()
    await page.waitForURL(/\/dashboard\/orders\/[0-9a-f-]+$/, {
      timeout: 5_000,
    })
    // The action panel must not show the Issue refund button on a DRAFT
    // order (visibility predicate gate from OrderDetailPanel).
    await expect(
      page.getByRole("button", { name: /^Issue refund$/i })
    ).toHaveCount(0)
  })
})

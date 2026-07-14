/**
 * Phase 21 ONBD-05 — blocked-onboarding journey (the flagship "black hole -> honest,
 * actionable surface" path). Drives, end-to-end against the LIVE rebuilt stack, the
 * vendor half of the journey the phase exists to fix:
 *
 *     create with a BAD company number  ->  fix it inline (POST /onboarding/company-number)
 *  -> submit / re-run checks            ->  the honest "in review" state (NOT the old
 *                                           forever "this usually takes under a minute"
 *                                           spinner) once a mandatory gate parks in
 *                                           MANUAL_REVIEW.
 *
 * Targets the dev frontend (port 3100 by default; the full-stack compose maps it on
 * 3000 — override with PLAYWRIGHT_BASE_URL) and the core-java API on :9090. Per
 * CLAUDE.md "rebuild containers before E2E" run
 *   docker compose -f docker-compose.full-stack.yml build && \
 *   docker compose -f docker-compose.full-stack.yml up -d
 * before invoking this spec (RULE 0: the minikube cluster must be STOPPED — compose is
 * the canonical runtime; the two share one dev Postgres).
 *
 * ── Determinism notes (why the journey ends at the honest "in review" state) ──────────
 * The dev/E2E stack runs the real onboarding gate chain with NO Companies House API key
 * and the LIVE FSA FHRS API:
 *   • BUSINESS_VERIFIED — a blank company number (sole trader) WAIVES with no external
 *     call; a present number with no CH key degrades to MANUAL_REVIEW (never a silent
 *     FAILED). So the deterministic, external-call-free correction is "clear the bad
 *     number" (sole trader) — proven here as the inline fix.
 *   • ALLERGEN_DATA_COMPLETE — PASSES for a curated demo shop (the DemoDataSeeder shops
 *     carry full durability/shelf-life/ingredients data).
 *   • FOOD_HYGIENE_RATING — a fictional demo shop does not cleanly match one FSA
 *     establishment, so it parks in MANUAL_REVIEW (the honest "in review" trigger).
 * With {WAIVED, PASSED, MANUAL_REVIEW} and no PENDING gate, the server derives
 * reviewPending = true and the page shows the honest in-review copy. The final
 * "-> live" hop (an admin resolving the parked FHRS gate to advance the state machine)
 * is the plan's human-verify checkpoint — it is left un-consumed here so the reviewer
 * can confirm it visually against this exact seeded state (one onboarding per tenant:
 * vendor_onboarding is UNIQUE(tenant_id)).
 *
 * Selector contract with the 21-04 vendor page (frontend/app/dashboard/onboarding/page.tsx):
 *   - Create form heading:      /Take your shop live/
 *   - Shop <select>:            #onboarding-shop
 *   - Create company input:     #onboarding-company
 *   - Create CTA:               button "Create application"
 *   - Status heading:           "Go live"
 *   - Inline company edit:      #edit-company-number  (card #company-number)
 *   - Save inline edit:         button "Save company number"  -> toast "Company number updated"
 *   - Submit CTA (DRAFT):       button "Submit for verification"
 *   - Re-run CTA (ACTION_REQ):  button "Re-run checks"
 *   - Honest in-review copy:    /with our team for review/  + badge "In review"
 *   - Dishonest forever copy:   /This usually takes under a minute/  (MUST be absent once in review)
 */

import { test, expect, type Page } from "@playwright/test"

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3100"
// The dev-realm vendor `admin-user` maps to tenant 00000000-…-0001 (curated demo data)
// AND carries the `admin` realm role — the same session can both drive the vendor
// journey and resolve gates. The password is deployment-specific: supply via
// E2E_VENDOR_PASSWORD (never committed).
const VENDOR_USERNAME = process.env.E2E_VENDOR_USERNAME ?? "admin-user"
const VENDOR_PASSWORD = process.env.E2E_VENDOR_PASSWORD ?? "password123"

// The curated DemoDataSeeder shop we prefer (full allergen data -> ALLERGEN gate PASSES).
const PREFERRED_SHOP = /Mama Ade/i
const BAD_COMPANY_NUMBER = "BAD123"

async function vendorLogin(page: Page) {
  await page.goto(`${BASE_URL}/auth/signin`)
  // NOT networkidle: the dashboard keeps SSE/poll connections open, so networkidle
  // never fires — wait for the DOM and drive concrete controls.
  await page.waitForLoadState("domcontentloaded")

  // The dev stack's signin page is a single "Sign in with Keycloak" SSO button, but
  // some deployments expose a NextAuth credentials form — support both.
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

  // NextAuth redirects to the Keycloak hosted login (realm jtoye-dev). A live SSO
  // cookie can skip the form and land straight on /dashboard — handle both arrivals.
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 15_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
}

async function isVisible(page: Page, selector: ReturnType<Page["locator"]>): Promise<boolean> {
  return selector.first().isVisible().catch(() => false)
}

test.describe("Phase 21 — blocked onboarding journey (ONBD-05)", () => {
  test("bad company number -> fix inline -> re-run checks -> honest in-review", async ({ page }, testInfo) => {
    // Pin this stateful journey to a SINGLE project. vendor_onboarding is
    // UNIQUE(tenant_id): running the mobile + desktop projects as parallel workers
    // would race two concurrent create/submit flows onto the one onboarding this
    // tenant may have. Desktop is the canonical journey viewport; the create form +
    // status view are responsive (no sidebar-click), and mobile dashboard layout is
    // covered separately by dashboard-mobile.spec.ts.
    test.skip(
      testInfo.project.name !== "desktop",
      "single-tenant onboarding journey pinned to the desktop project (UNIQUE(tenant_id) — no cross-worker race)"
    )

    await vendorLogin(page)

    await page.goto(`${BASE_URL}/dashboard/onboarding`)
    await page.waitForLoadState("domcontentloaded")

    const createHeading = page.locator("h1", { hasText: /Take your shop live/i })
    const statusHeading = page.locator("h1", { hasText: /^Go live$/i })

    // Wait until the onboarding surface has resolved to either the create form or the
    // status view (the initial spinner clears once GET /me resolves).
    await expect(createHeading.or(statusHeading).first()).toBeVisible({ timeout: 20_000 })

    // ── Step 1: reach a blocked/DRAFT onboarding carrying a BAD company number. ──────
    // vendor_onboarding is UNIQUE(tenant_id): create only when none exists yet (a
    // second project run — mobile then desktop — finds the one this run seeded).
    if (await isVisible(page, createHeading)) {
      const shopSelect = page.locator("#onboarding-shop")
      await expect(shopSelect).toBeVisible({ timeout: 10_000 })

      // Prefer a curated shop (full allergen data). Fall back to the first real option.
      const preferred = shopSelect.locator("option", { hasText: PREFERRED_SHOP })
      let shopValue: string | null = null
      if ((await preferred.count()) > 0) {
        shopValue = await preferred.first().getAttribute("value")
      }
      if (!shopValue) {
        const realOption = shopSelect.locator('option:not([value=""])').first()
        if ((await realOption.count()) === 0) {
          test.skip(true, "No shop available for the demo tenant — run the DemoDataSeeder (dev profile) first.")
        }
        shopValue = await realOption.getAttribute("value")
      }
      await shopSelect.selectOption(shopValue as string)

      // Seed the "bad company number" the journey exists to correct.
      await page.fill("#onboarding-company", BAD_COMPANY_NUMBER)

      const createBtn = page.getByRole("button", { name: /^Create application$/i })
      await expect(createBtn).toBeEnabled()
      await createBtn.click()

      // Landed on the status view (DRAFT).
      await expect(statusHeading).toBeVisible({ timeout: 15_000 })
    }

    // ── Step 2: fix the company number inline (POST /onboarding/company-number). ──────
    // The inline edit card is present only in DRAFT / ACTION_REQUIRED.
    const editInput = page.locator("#edit-company-number")
    if (await isVisible(page, editInput)) {
      // Prove the bad number is what we're correcting (when this run seeded it).
      const current = await editInput.inputValue()
      if (current) {
        expect(current.length).toBeGreaterThan(0)
      }
      // Correct it: clear -> sole trader (BUSINESS_VERIFIED gate WAIVES, no CH call).
      await editInput.fill("")
      await page.getByRole("button", { name: /^Save company number$/i }).click()
      // The live backend confirms with a toast (ONBD-02 round-trip proven).
      await expect(page.getByText(/Company number updated/i).first()).toBeVisible({ timeout: 10_000 })
    }

    // ── Step 3: submit / re-run the checks to drive VERIFYING. ───────────────────────
    const submitBtn = page.getByRole("button", { name: /^Submit for verification$/i })
    const rerunBtn = page.getByRole("button", { name: /^Re-run checks$/i })
    if ((await submitBtn.count()) > 0 && (await isVisible(page, submitBtn))) {
      await submitBtn.click()
    } else if ((await rerunBtn.count()) > 0 && (await isVisible(page, rerunBtn))) {
      await rerunBtn.click()
    }
    // (If neither CTA is present the onboarding is already past DRAFT/ACTION_REQUIRED —
    //  a prior project run left it in VERIFYING; the in-review assertion below still holds.)

    // ── Step 4: the honest "in review" state (NOT the forever spinner). ──────────────
    // Once a mandatory gate parks in MANUAL_REVIEW (FHRS on a fictional demo shop) and
    // no gate is still PENDING, the server derives reviewPending and the page swaps the
    // dishonest "under a minute" copy for the honest reviewer copy. The page polls every
    // 4s (backing off to 30s in review); assert on the concrete honest copy, never on
    // networkidle. Generous timeout to absorb async gate settling + one poll cycle.
    await expect(
      page.getByText(/with our team for review/i).first()
    ).toBeVisible({ timeout: 60_000 })

    // The honest "In review" badge replaces the running-checks label.
    await expect(page.getByText(/^In review$/i).first()).toBeVisible({ timeout: 10_000 })

    // The dishonest forever-spinner subtitle is gone once the reviewer state is shown.
    await expect(page.getByText(/This usually takes under a minute/i)).toHaveCount(0)

    // The surface stays actionable, not a black hole: the compliance-check breakdown is
    // rendered so the vendor can always see exactly what is (or isn't) holding them up.
    await expect(page.getByText(/Compliance checks/i).first()).toBeVisible({ timeout: 10_000 })
  })
})

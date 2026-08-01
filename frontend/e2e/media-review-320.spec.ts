/**
 * AC-5.5 (27-01 Task 5) — the media review queue at a 320 px viewport, on the RUNNING stack.
 *
 * 320 px is the narrowest viewport the dashboard supports, and it is where a
 * two-action control row breaks: shrink-to-fit clips the second button, so both
 * rows in this feature stack below `sm` instead. This spec proves that on the
 * real page rather than in jsdom, which has no layout engine and therefore cannot
 * observe an overflow at all.
 *
 * <b>Real data, not a stub.</b> Three `media_asset` rows are seeded in the dev
 * database before this runs (object_key `…/ac55-fixture-*`), so the assertions
 * exercise the whole path — the rebuilt core-java deriving `redrivable`/`delayed`,
 * the widened review-queue query, and the rebuilt frontend rendering all three.
 * Stubbing `/media/review-queue` here would have proven only that the component
 * lays out correctly given a shape the backend might not actually send.
 *
 * <b>Seed with `bash scripts/seed-media-review-fixtures.sh` — it is not optional,
 * and it is not one-off.</b> The fixtures were originally hand-inserted with
 * ABSOLUTE timestamps and decayed four days later: once `quarantine_expires_at`
 * passed, the quarantine sweep reclaimed the bytes exactly as designed, and
 * `redrivable` went false. The spec then failed on its own VOID guard — correctly,
 * and that guard is the only reason this did not pass silently over an empty queue.
 * The script writes every instant RELATIVE TO NOW so the same bomb cannot re-arm.
 *
 * Fixtures, and what each one proves:
 *   ac55-fixture-redrivable  FAILED, bytes retained   -> Re-upload AND Re-process
 *   ac55-fixture-vetoed      FAILED, bytes reclaimed  -> Re-upload ONLY
 *   ac55-fixture-delayed     PENDING, 30 min old      -> the "Taking longer" section
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:3100 E2E_VENDOR_PASSWORD=… \
 *     npx playwright test --project=mobile media-review-320.spec
 *
 * Waits are `domcontentloaded` + explicit element waits, never networkidle: the
 * dashboard holds SSE/STOMP connections open so the network never goes idle
 * (19-RESEARCH Pitfall 5).
 */

import { test, expect, type Page } from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
/** Real vendor sign-in through Keycloak. Mirrors e2e/dashboard-mobile.spec.ts. */
async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
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
  if ((await ssoButton.count()) === 0) {
    test.skip(true, "No sign-in method found on /auth/signin — unknown auth flow")
  }
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
  // Let React hydrate — a click on `domcontentloaded` can land before the onClick
  // handler is attached and silently no-op.
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

test.describe("AC-5.5 — media review queue at 320px", () => {
  test.use({ viewport: { width: 320, height: 720 } })

  test("all three states render, both FAILED actions are visible, and nothing overflows", async ({ page }) => {
    await vendorLogin(page)
    await page.goto(`${BASE}/dashboard/media/review`, { waitUntil: "domcontentloaded" })

    // --- VOID guard -------------------------------------------------------
    // If the seeded fixtures are not on the page, every assertion below would
    // pass vacuously against an empty queue. "Nothing overflowed" is trivially
    // true when nothing rendered.
    const redrivableRow = page.getByText(/processing stalled before it finished/i)
    await expect(redrivableRow, "VOID: the seeded redrivable fixture is not on the page").toBeVisible({
      timeout: 20_000,
    })
    await expect(
      page.getByText(/that file is not a supported image/i),
      "VOID: the seeded non-redrivable fixture is not on the page"
    ).toBeVisible()
    await expect(
      page.getByRole("heading", { name: /taking longer than usual/i }),
      "VOID: the seeded delayed fixture is not on the page (D-10 widening not reaching the wire)"
    ).toBeVisible()

    // --- Counts are DERIVED from the page, never hardcoded ----------------
    // The dev database carries its own history — the first run of this spec
    // expected 2 Re-uploads and found 3, because a real pre-Phase-27 failure
    // (a .gif, bytes long gone) already sat in this tenant's queue. Hardcoding
    // a count asserts the fixture state of one machine at one moment; deriving
    // it asserts the actual invariant, and survives whatever else is in the DB.
    const rejectedRows = page.getByText(/upload rejected/i)
    const reuploads = page.getByRole("button", { name: /re-upload/i })
    const reprocess = page.getByRole("button", { name: /re-process/i })
    const retainedNotes = page.getByText(/your original upload is still saved/i)

    const rejectedCount = await rejectedRows.count()
    expect(rejectedCount, "VOID: no rejected rows on the page at all").toBeGreaterThanOrEqual(2)

    // EVERY rejected row keeps its Re-upload — the live Incremental Betterment
    // receipt. Re-process was added beside it, never in place of it.
    await expect(reuploads).toHaveCount(rejectedCount)

    // Re-process appears on exactly the rows whose bytes are retained, and on no
    // others. Offering it elsewhere is a control that can only ever 409.
    const retainedCount = await retainedNotes.count()
    expect(
      retainedCount,
      "VOID: no redrivable row rendered — the criterion would pass on zero. " +
        "Run `bash scripts/seed-media-review-fixtures.sh`. The fixtures decay by " +
        "design: the quarantine sweep reclaims any non-ACTIVE asset once " +
        "quarantine_expires_at passes, and MediaAssetDto derives redrivable as " +
        "(expires_at != null && reclaimed_at == null), so a reclaimed fixture " +
        "stops offering Re-process. This is the guard working, not a flake."
    ).toBeGreaterThanOrEqual(1)
    await expect(reprocess).toHaveCount(retainedCount)
    expect(retainedCount, "Re-process must NOT appear on every rejected row").toBeLessThan(rejectedCount)

    const checkAgain = page.getByRole("button", { name: /check again/i })
    await expect(checkAgain).toBeVisible()

    // --- Visible, not merely present --------------------------------------
    // toBeVisible, not toBeAttached: a clipped-off-canvas button is in the DOM.
    for (let i = 0; i < rejectedCount; i++) await expect(reuploads.nth(i)).toBeVisible()
    for (let i = 0; i < retainedCount; i++) await expect(reprocess.nth(i)).toBeVisible()

    // --- No horizontal overflow at 320px ----------------------------------
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth, `page overflows horizontally at 320px (scrollWidth=${scrollWidth})`).toBeLessThanOrEqual(320)

    // Each action must also sit inside the viewport — a page can stay within
    // scrollWidth while an individual control is pushed past the right edge.
    const controls = [checkAgain]
    for (let i = 0; i < rejectedCount; i++) controls.push(reuploads.nth(i))
    for (let i = 0; i < retainedCount; i++) controls.push(reprocess.nth(i))
    for (const control of controls) {
      const box = await control.boundingBox()
      expect(box, "control has no layout box").not.toBeNull()
      expect(box!.x + box!.width, "control is clipped past the 320px right edge").toBeLessThanOrEqual(320)
    }

    await page.screenshot({ path: "e2e-artifacts/ac55-media-review-320.png", fullPage: true })
    // The two-action row is the actual subject of this criterion, and it sits
    // below the fold at 320px — screenshot it directly so the stacked layout is
    // reviewable by eye, not just by a scrollWidth number.
    await reprocess.first().scrollIntoViewIfNeeded()
    await page.screenshot({ path: "e2e-artifacts/ac55-failed-actions-320.png" })
  })
})

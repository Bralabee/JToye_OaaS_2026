/**
 * COMMS-06 E2E — vendor webhook dashboard journey (Surface A + B).
 *
 * Proves the full click-through the user requires (memory
 * feedback_e2e_click_through — CLICK the buttons and verify the OUTCOME):
 *   create an endpoint → the once-only secret dialog reveals a secret → the row
 *   appears in the list → open the detail → the delivery log renders → filter by
 *   status narrows the visible rows → replay a delivery → a success toast + a new
 *   "Replay"-tagged attempt appears → no horizontal overflow at 375px.
 *
 * Pattern (mirrors e2e/dashboard-mobile.spec.ts): authentication is REAL (the
 * dashboard layout gates server-side via auth() and redirects a fake cookie to
 * /auth/signin, so we perform the genuine Keycloak vendor login), while the
 * backend DATA is STUBBED via Playwright `route()` so the journey is
 * deterministic and independent of DB seed state / a live delivery worker.
 *
 * Run: npx playwright test --project=desktop webhooks-flow
 * (needs the rebuilt full stack — frontend :3000 + a reachable Keycloak.)
 */
import { test, expect, type BrowserContext, type Page } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

const VENDOR_USERNAME = process.env.E2E_VENDOR_USERNAME ?? "admin-user"
const VENDOR_PASSWORD = process.env.E2E_VENDOR_PASSWORD ?? "password123"

// --- Real vendor sign-in (verbatim shape from dashboard-mobile.spec.ts) ------
async function vendorLogin(page: Page) {
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

// --- Deterministic webhook API fixtures --------------------------------------
const now = new Date().toISOString()
const SUB_ID = "e2e-sub-1"
const SECRET = "whsec_e2e_test_only_secret_value_0123456789"

const subscription = {
  id: SUB_ID,
  targetUrl: "https://e2e.example.com/hooks/jtoye",
  eventTypes: ["ORDER_STATE_CHANGED"],
  status: "ACTIVE",
  consecutiveFailures: 0,
  createdAt: now,
  updatedAt: now,
}

const delivered = {
  id: "e2e-d1", subscriptionId: SUB_ID, eventId: "e1",
  eventType: "order.state.CONFIRMED", status: "DELIVERED", attemptCount: 1,
  lastHttpStatus: 200, lastError: null, replay: false, replayOf: null,
  nextAttemptAt: now, createdAt: now, updatedAt: now,
}
const failed = {
  id: "e2e-d2", subscriptionId: SUB_ID, eventId: "e2",
  eventType: "order.state.CANCELLED", status: "FAILED", attemptCount: 5,
  lastHttpStatus: 500, lastError: "Connection refused", replay: false, replayOf: null,
  nextAttemptAt: now, createdAt: now, updatedAt: now,
}
const replayRow = {
  id: "e2e-d3", subscriptionId: SUB_ID, eventId: "e2",
  eventType: "order.state.CANCELLED", status: "PENDING", attemptCount: 0,
  lastHttpStatus: null, lastError: null, replay: true, replayOf: "e2e-d2",
  nextAttemptAt: now, createdAt: now, updatedAt: now,
}

function pageOf(rows: unknown[]) {
  return { content: rows, totalPages: 1, totalElements: rows.length, number: 0, size: 20 }
}

// Mutable journey state the stubs advance across the click-through.
let created = false
let replayed = false

async function setupWebhookStubs(context: BrowserContext) {
  // Broad catch-all FIRST (Playwright matches the LAST-registered route first),
  // so the specific webhook handlers below win.
  await context.route(`${API}/api/v1/**`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(pageOf([])) })
  )
  await context.route(`${API}/api/v1/orders/stream**`, (route) => route.abort())
  await context.route("**/ws**", (route) => route.abort())

  // GET list + POST create (same URL, dispatch by method).
  await context.route(`${API}/api/v1/webhooks`, (route) => {
    if (route.request().method() === "POST") {
      created = true
      return route.fulfill({
        status: 201, contentType: "application/json",
        body: JSON.stringify({ subscription, signingSecret: SECRET }),
      })
    }
    return route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify(created ? [subscription] : []),
    })
  })

  // GET detail.
  await context.route(`${API}/api/v1/webhooks/${SUB_ID}`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(subscription) })
  )

  // GET delivery log (status filter narrows server-side).
  await context.route(`${API}/api/v1/webhooks/${SUB_ID}/deliveries**`, (route) => {
    const status = new URL(route.request().url()).searchParams.get("status")
    let rows: unknown[] = replayed ? [replayRow, delivered, failed] : [delivered, failed]
    if (status) rows = (rows as Array<{ status: string }>).filter((r) => r.status === status)
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(pageOf(rows)) })
  })

  // POST replay → a new PENDING attempt tagged Replay appears on the next fetch.
  await context.route(`${API}/api/v1/webhooks/*/deliveries/*/replay`, (route) => {
    replayed = true
    return route.fulfill({ status: 202, contentType: "application/json", body: JSON.stringify(replayRow) })
  })
}

test.describe("Webhook dashboard journey (COMMS-06)", () => {
  // Desktop viewport for the table-driven journey; the 375px no-overflow
  // contract is asserted by resizing at the end of the same flow.
  test.use({ viewport: { width: 1440, height: 900 } })

  test.beforeEach(async ({ context, page }) => {
    created = false
    replayed = false
    await setupWebhookStubs(context)
    await vendorLogin(page)
  })

  test("create → secret reveal → list → detail → filter → replay → 375px", async ({ page }) => {
    // --- Surface A: empty list, then create -----------------------------------
    await page.goto(`${BASE}/dashboard/webhooks`, { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("heading", { name: "Webhooks" })).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText("No webhook endpoints yet")).toBeVisible()

    await page.getByRole("button", { name: /add endpoint/i }).first().click()
    const dialog = page.getByRole("dialog")
    await expect(dialog.getByText("Add webhook endpoint")).toBeVisible()
    await dialog.getByLabel("Endpoint URL").fill("https://e2e.example.com/hooks/jtoye")
    // Pick the first event family (Orders).
    await dialog.getByRole("checkbox").first().check()
    await dialog.getByRole("button", { name: /add endpoint/i }).click()

    // --- Once-only secret reveal ---------------------------------------------
    // Scope to the reveal dialog's textbox: getByLabel("Signing secret") is
    // page-wide and strict-mode-collides with the "Copy signing secret" button
    // (substring match). The textbox role excludes the button unambiguously.
    await expect(page.getByText("Copy your signing secret")).toBeVisible()
    const secretDialog = page.getByRole("dialog")
    await expect(
      secretDialog.getByRole("textbox", { name: "Signing secret" })
    ).toHaveValue(SECRET)
    await page.getByRole("button", { name: /i've saved it/i }).click()

    // --- Row now in the list --------------------------------------------------
    await expect(
      page.getByText("https://e2e.example.com/hooks/jtoye").first()
    ).toBeVisible({ timeout: 10_000 })

    // --- Surface B: open detail ----------------------------------------------
    await page.getByRole("link", { name: /view/i }).first().click()
    await page.waitForURL(new RegExp(`/dashboard/webhooks/${SUB_ID}`), { timeout: 10_000 })
    const table = page.getByTestId("deliveries-table")
    await expect(table.getByText("Delivered")).toBeVisible({ timeout: 10_000 })
    await expect(table.getByText("Failed")).toBeVisible()

    // --- Filter by status = Failed narrows the visible rows -------------------
    await page.getByLabel("Filter by status").click()
    await page.getByRole("option", { name: "Failed" }).click()
    await expect(table.getByText("Delivered")).toHaveCount(0, { timeout: 10_000 })
    await expect(table.getByText("Failed")).toBeVisible()

    // Back to All so the replayed (PENDING) attempt is visible after replay.
    await page.getByLabel("Filter by status").click()
    await page.getByRole("option", { name: "All statuses" }).click()
    await expect(table.getByText("Delivered")).toBeVisible({ timeout: 10_000 })

    // --- Replay a delivery → toast + a new Replay-tagged attempt --------------
    await table.getByRole("button", { name: /^replay$/i }).first().click()
    await page.getByRole("button", { name: /replay delivery/i }).click()
    // Exact match targets the toast TITLE only; /replay queued/i also matches the
    // toast description ("Replay queued — the new attempt…") → strict-mode collision.
    await expect(page.getByText("Replay queued", { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(table.getByText("Replay").first()).toBeVisible({ timeout: 10_000 })

    // --- 375px: no horizontal overflow ---------------------------------------
    await page.setViewportSize({ width: 375, height: 800 })
    await page.waitForTimeout(300)
    const overflow = await page.evaluate(() => {
      const el = document.scrollingElement || document.documentElement
      return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }
    })
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
  })
})

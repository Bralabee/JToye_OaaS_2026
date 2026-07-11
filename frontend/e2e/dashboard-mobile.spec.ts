/**
 * Surface D (UIX-02) — Dashboard mobile responsive shell E2E.
 *
 * Proves all 11 dashboard routes are usable at a 390px phone viewport:
 *   - the 256px desktop sidebar (`hidden md:flex`) is NOT visible
 *   - the fixed bottom tab bar (`md:hidden`) IS visible
 *   - the page title is not squeezed into a one-word-per-line column and does
 *     not overflow horizontally (h1 scrollWidth <= clientWidth + given room)
 *   - /dashboard/onboarding (the Phase 18 quality reference) is not regressed
 *
 * Backend data + realtime traffic is stubbed via Playwright `route()` so the
 * per-route assertions render deterministically without a seeded DB or a live
 * broker. Authentication, however, is REAL: the dashboard layout gates
 * server-side via `auth()` (frontend/app/dashboard/layout.tsx), which rejects a
 * fake session cookie and redirects to /auth/signin — so we perform the genuine
 * Keycloak vendor login used by e2e/vendor-refund-flow.spec.ts. Run with:
 *   npx playwright test --project=mobile dashboard-mobile.spec
 *
 * IMPORTANT — waits: the dashboard keeps SSE/STOMP connections open, so the
 * network never goes idle. We use `domcontentloaded` + explicit element waits
 * everywhere (19-RESEARCH Pitfall 5) — deliberately NOT the idle-network wait.
 * The definitive human-visual pass is deferred to the whole-app UAT in plan
 * 19-09 against the rebuilt stack.
 */

import { test, expect, type BrowserContext, type Page } from "@playwright/test"

// Honour PLAYWRIGHT_BASE_URL (dev stack may run on :3100). Mirrors playwright.config.ts.
const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

// Keycloak dev-realm vendor (docs/setup/SETUP.md); tenant-a-user maps to
// tenant 00000000-…-000000000001. Overridable for other environments.
const VENDOR_USERNAME = process.env.E2E_VENDOR_USERNAME ?? "tenant-a-user"
const VENDOR_PASSWORD = process.env.E2E_VENDOR_PASSWORD ?? "password123"

/**
 * Perform the real vendor sign-in (SSO button → Keycloak, or a credentials
 * form if the deployment exposes one). Mirrors vendor-refund-flow.spec.ts.
 * Skips cleanly if no known sign-in affordance is present.
 */
async function vendorLogin(page: Page) {
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })

  // Some deployments expose a NextAuth credentials form; the dev stack's signin
  // page is a single "Sign in with Keycloak" SSO button. Support both.
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

  // A live Keycloak SSO cookie can skip the hosted form and land straight on
  // /dashboard — handle both arrivals.
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 15_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
}

// A concrete id for the /dashboard/orders/[id] detail route.
const ORDER_ID = "order-1"

const shopsResponse = {
  content: [
    {
      id: "shop-1",
      tenantId: "tenant-1",
      name: "Test Shop",
      address: "1 Main St",
      slug: "test",
      description: null,
      logoUrl: null,
      bannerUrl: null,
      phone: null,
      email: null,
      latitude: null,
      longitude: null,
      openingHours: null,
      deliveryInfo: null,
      minimumOrderPennies: 0,
      published: true,
      tags: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
}

const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }

const orderSummaryResponse = {
  content: [
    {
      id: ORDER_ID,
      tenantId: "tenant-1",
      shopId: "shop-1",
      status: "CONFIRMED",
      customerName: "Alice",
      totalAmountPennies: 1000,
      itemCount: 2,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
}

const orderDetailResponse = {
  id: ORDER_ID,
  tenantId: "tenant-1",
  shopId: "shop-1",
  orderNumber: "ORD-TEST-0001",
  status: "CONFIRMED",
  customerName: "Alice",
  customerEmail: "alice@example.com",
  totalAmountPennies: 1000,
  paymentReference: null,
  paymentStatus: "PAID",
  fulfilmentType: "COLLECTION",
  addressLine1: null,
  addressLine2: null,
  city: null,
  postcode: null,
  items: [
    {
      id: "item-1",
      productId: "p-1",
      productName: "Burger",
      quantity: 2,
      unitPricePennies: 500,
      totalPricePennies: 1000,
      createdAt: new Date().toISOString(),
    },
  ],
  refunds: [],
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
}

/** All 11 dashboard routes. `titleHasH1` = the route renders a page <h1>
 *  (the order-detail route uses a card <h2>, so we only assert the chrome). */
const ROUTES: Array<{ path: string; name: string; titleHasH1: boolean }> = [
  { path: "/dashboard", name: "Dashboard", titleHasH1: true },
  { path: "/dashboard/shops", name: "Shops", titleHasH1: true },
  { path: "/dashboard/products", name: "Products", titleHasH1: true },
  { path: "/dashboard/products/import", name: "Import Products", titleHasH1: true },
  { path: "/dashboard/orders", name: "Orders", titleHasH1: true },
  { path: `/dashboard/orders/${ORDER_ID}`, name: "Order detail", titleHasH1: false },
  { path: "/dashboard/customers", name: "Customers", titleHasH1: true },
  { path: "/dashboard/finance", name: "Finance", titleHasH1: true },
  { path: "/dashboard/marketing", name: "Marketing", titleHasH1: true },
  { path: "/dashboard/kitchen", name: "Kitchen", titleHasH1: true },
  { path: "/dashboard/onboarding", name: "Onboarding", titleHasH1: true },
]

async function setupStubs(context: BrowserContext) {
  // Order matters: Playwright matches the LAST-registered handler first, so we
  // register the broad catch-all FIRST and specific handlers AFTER so they win.

  // Catch-all: any other /api/v1 GET resolves fast with an empty page so
  // client-side loading spinners settle and the page <h1> renders.
  await context.route(`${API}/api/v1/**`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(emptyPage),
    })
  )

  await context.route(`${API}/api/v1/shops**`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(shopsResponse) })
  )

  await context.route(`${API}/api/v1/orders?**`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(orderSummaryResponse) })
  )

  await context.route(`${API}/api/v1/orders/*/detail`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(orderDetailResponse) })
  )

  // Onboarding: 404 => "no onboarding yet" => the create form ("Take your shop
  // live") renders — the primary, un-regressed onboarding surface.
  await context.route(`${API}/api/v1/onboarding/**`, (route) =>
    route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) })
  )

  // Realtime: SSE (/orders/stream) and STOMP (/ws) never idle — abort so the
  // client fails fast and still renders its HTTP-fetched state.
  await context.route(`${API}/api/v1/orders/stream**`, (route) => route.abort())
  await context.route("**/ws**", (route) => route.abort())
}

test.describe("Dashboard mobile shell (390px)", () => {
  test.beforeEach(async ({ context, page }) => {
    // Stub the API data first (no effect on the Keycloak login origin), then
    // perform the real vendor sign-in so the server-side dashboard auth gate
    // lets us through.
    await setupStubs(context)
    await vendorLogin(page)
  })

  for (const route of ROUTES) {
    test(`${route.name} (${route.path}) is usable at 390px`, async ({ page }) => {
      await page.goto(`${BASE}${route.path}`, { waitUntil: "domcontentloaded" })

      // The fixed bottom tab bar is present and visible on mobile.
      const tabBar = page.getByTestId("mobile-tab-bar")
      await expect(tabBar).toBeVisible()

      // The 256px desktop sidebar is hidden below md — its unique "OaaS
      // Platform" wordmark subtitle must not be visible.
      await expect(page.getByText("OaaS Platform")).toBeHidden()

      // Title readability: the page <h1> is given room (not squeezed into a
      // one-word-per-line column) and does not overflow horizontally.
      if (route.titleHasH1) {
        const h1 = page.locator("main h1").first()
        await expect(h1).toBeVisible({ timeout: 10_000 })
        const metrics = await h1.evaluate((el) => ({
          scrollWidth: el.scrollWidth,
          clientWidth: el.clientWidth,
        }))
        // No forced horizontal overflow (+1px tolerance for sub-pixel rounding).
        expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
        // The title column is wide, proving the sidebar is not stealing ~66%
        // of the 390px viewport any more (old squeezed column was ~130px).
        const box = await h1.boundingBox()
        expect(box?.width ?? 0).toBeGreaterThanOrEqual(260)
      }
    })
  }

  test("/dashboard/onboarding is not regressed (known heading renders)", async ({ page }) => {
    await page.goto(`${BASE}/dashboard/onboarding`, { waitUntil: "domcontentloaded" })
    await expect(page.getByTestId("mobile-tab-bar")).toBeVisible()
    // The onboarding page shows one of its known headings ("Take your shop
    // live" for the create form, or "Go live" for an in-progress record).
    await expect(
      page.getByRole("heading", { name: /take your shop live|go live/i }).first()
    ).toBeVisible({ timeout: 10_000 })
  })
})

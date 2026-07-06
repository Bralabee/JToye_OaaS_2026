/**
 * E2E test for the Kitchen Display flagship feature.
 *
 * All backend/WebSocket traffic is stubbed via Playwright's `route()` API so
 * the test can run in isolation without a live Core API or RabbitMQ. This
 * spec asserts:
 *   - the page renders with a fake authenticated session cookie
 *   - a mock order is visible in the grid
 *   - the mute toggle can be clicked
 *   - the shop selector is present
 *
 * Run: npx playwright test e2e/kitchen-flow.spec.ts
 */

import { test, expect } from "@playwright/test"

// Honour PLAYWRIGHT_BASE_URL (dev stack runs on :3100). Mirrors playwright.config.ts.
const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

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
}

const orderSummaryResponse = {
  content: [
    {
      id: "order-1",
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
}

const orderDetailResponse = {
  id: "order-1",
  tenantId: "tenant-1",
  shopId: "shop-1",
  orderNumber: "ORD-TEST-0001",
  status: "CONFIRMED",
  customerName: "Alice",
  totalAmountPennies: 1000,
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
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
}

test.describe("Kitchen display", () => {
  test.beforeEach(async ({ page, context }) => {
    // Stub the STOMP websocket endpoint so a real broker is not needed — the
    // client can fail fast and the page still renders its HTTP-fetched state.
    await context.route("**/ws**", (route) => route.abort())

    // Stub REST calls
    await context.route(`${API}/api/v1/shops**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(shopsResponse),
      })
    )
    await context.route(`${API}/api/v1/orders?**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(orderSummaryResponse),
      })
    )
    await context.route(`${API}/api/v1/orders/*/detail`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(orderDetailResponse),
      })
    )

    // Fake a NextAuth session cookie so the dashboard layout lets us through.
    // (The real value is opaque to tests — server-side auth is exercised in
    // its own unit test; here we just need the client to render.)
    await context.addCookies([
      {
        name: "authjs.session-token",
        value: "e2e-stub",
        domain: "localhost",
        path: "/",
        httpOnly: true,
        sameSite: "Lax",
      },
    ])
  })

  test("renders header, shop selector, mute toggle and a mock order card", async ({ page }) => {
    await page.goto(`${BASE}/dashboard/kitchen`)

    // Header
    await expect(page.getByRole("heading", { name: /Kitchen Display/i })).toBeVisible()

    // Shop selector trigger is rendered
    await expect(page.getByText(/Select shop|Test Shop/i).first()).toBeVisible()

    // Mute toggle button is present
    await expect(page.getByTitle(/Mute alerts|Unmute alerts/)).toBeVisible()

    // The mocked order card shows customer name and order number
    await expect(page.getByText("Alice")).toBeVisible()
    await expect(page.getByText(/ORD-TEST-0001/)).toBeVisible()

    // Status filter buttons — the bump action reflects the current status
    await expect(page.getByRole("button", { name: /Start Preparing/i })).toBeVisible()

    // Mute toggle click works
    await page.getByTitle(/Mute alerts|Unmute alerts/).click()
  })
})

/**
 * E2E test for the Kitchen Display flagship feature + order-detail product names.
 *
 * All backend/WebSocket traffic is stubbed via Playwright's `route()` API so
 * the test can run in isolation without a live Core API or RabbitMQ. This
 * spec asserts (Surface F — backlog #2, #8, #12):
 *   - the page renders with a fake authenticated session cookie
 *   - a mock order is visible in the grid with its REAL product name
 *   - "Unknown Product" never renders on the kitchen display or order-detail
 *     for a line item that references a real product (#2)
 *   - a long order number truncates cleanly and the status badge does not clip
 *     over it (#8)
 *   - elapsed time is capped/formatted ("1d ago"), never raw uncapped minutes
 *     like "2245m ago" (#12)
 *   - the order-detail page renders the real product names + delivery address
 *
 * SSE/STOMP never reaches an idle-network state, so every navigation uses
 * `domcontentloaded` plus explicit element waits — see 19-RESEARCH.md § Pitfall 5.
 *
 * Run: npx playwright test e2e/kitchen-flow.spec.ts
 */

import { test, expect } from "@playwright/test"

// Honour PLAYWRIGHT_BASE_URL (dev stack runs on :3100). Mirrors playwright.config.ts.
const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

// Pin the order age so the elapsed-time cap is deterministic: 2245 minutes is
// the exact raw value the audit flagged (#12). Capped, it renders "1d ago".
const CREATED_AT = new Date(Date.now() - 2245 * 60 * 1000).toISOString()

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
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT,
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
      // Real snapshotted product name (19-01) — NOT "Unknown Product".
      productName: "Jollof Rice",
      quantity: 2,
      unitPricePennies: 500,
      totalPricePennies: 1000,
      createdAt: CREATED_AT,
    },
  ],
  // Payment + fulfilment fields exposed by 19-01's OrderDetailDto.
  paymentStatus: "CAPTURED",
  paymentReference: "pi_test_123",
  paymentMethod: "card",
  refunds: [],
  fulfilmentType: "DELIVERY",
  addressLine1: "12 Rye Lane",
  addressCity: "London",
  addressPostcode: "SE15 5BS",
  createdAt: CREATED_AT,
  updatedAt: CREATED_AT,
}

test.describe("Kitchen display + order detail — product names & fixes (Surface F)", () => {
  test.beforeEach(async ({ context }) => {
    // Stub the STOMP websocket endpoint so a real broker is not needed — the
    // client can fail fast and the page still renders its HTTP-fetched state.
    await context.route("**/ws**", (route) => route.abort())

    // Stub the order-detail SSE stream so it fails fast (it never idles).
    await context.route("**/api/v1/orders/stream", (route) => route.abort())

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

  test("kitchen display shows real product names, a clean badge, and capped elapsed time", async ({
    page,
  }) => {
    // SSE/STOMP never idles the network — wait on the DOM, then on elements.
    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })

    // Header
    await expect(page.getByRole("heading", { name: /Kitchen Display/i })).toBeVisible()

    // Shop selector trigger is rendered
    await expect(page.getByText(/Select shop|Test Shop/i).first()).toBeVisible()

    // Mute toggle button is present
    await expect(page.getByTitle(/Mute alerts|Unmute alerts/)).toBeVisible()

    // The mocked order card shows customer name and order number
    await expect(page.getByText("Alice")).toBeVisible()

    // #8 — the order number truncates (does not wrap under the badge) and the
    // status badge stays visible beside it.
    const orderNumber = page.getByText("ORD-TEST-0001")
    await expect(orderNumber).toBeVisible()
    await expect(orderNumber).toHaveClass(/truncate/)
    await expect(page.getByText("Confirmed")).toBeVisible()

    // #2 — the REAL product name renders on the kitchen card, and the
    // "Unknown Product" fallback never appears for a real product.
    await expect(page.getByText(/Jollof Rice/).first()).toBeVisible()
    await expect(page.getByText("Unknown Product")).toHaveCount(0)

    // #12 — elapsed time is capped/formatted; raw uncapped minutes never render.
    await expect(page.getByText("1d ago")).toBeVisible()
    await expect(page.getByText(/2245m/)).toHaveCount(0)

    // Status filter buttons — the bump action reflects the current status
    await expect(page.getByRole("button", { name: /Start Preparing/i })).toBeVisible()

    // Mute toggle click works
    await page.getByTitle(/Mute alerts|Unmute alerts/).click()
  })

  test("order-detail page shows real product names and the delivery address, no 'Unknown Product'", async ({
    page,
  }) => {
    await page.goto(`${BASE}/dashboard/orders/order-1`, {
      waitUntil: "domcontentloaded",
    })

    // Order number header
    await expect(page.getByText("ORD-TEST-0001")).toBeVisible()

    // #2 — real product name renders in the items table; "Unknown Product" never.
    await expect(page.getByText("Jollof Rice")).toBeVisible()
    await expect(page.getByText("Unknown Product")).toHaveCount(0)

    // Delivery-address block renders for a DELIVERY order (19-01 fulfilment DTO).
    await expect(page.getByTestId("delivery-address")).toBeVisible()
    await expect(page.getByText("SE15 5BS")).toBeVisible()
  })
})

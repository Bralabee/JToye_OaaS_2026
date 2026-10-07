/**
 * 31.1-22 (#812, D-15; D-07; #861 D-17) — the allergy note on the kitchen board and the vendor
 * order detail, in a real browser.
 *
 * WRITTEN AND TYPE-CHECKED IN 31.1-22, RUN IN 31.1-30. The shared compose stack is rebuilt by
 * 31.1-30, which owns the live click-through; jest proves the components, this proves the
 * browser: the block is visible without scrolling the card, the button is really >= 44px tall
 * at a phone width, the press posts once and the read line appears from the server's answer,
 * and the order detail reads UK dates.
 *
 * API traffic is stubbed with route() exactly as kitchen-flow.spec.ts does (the vendor SSO login
 * is genuine, because the dashboard's server-side auth gate needs it). Stubbing is the point
 * here: the acknowledgement's server rules are proven by AllergyNoteAckIntegrationTest (31.1-13);
 * this spec is about what the browser shows around them.
 *
 * Run: npx playwright test e2e/kitchen-allergy-note.spec.ts
 */

import { test, expect, type Page } from "@playwright/test"
import { VENDOR_USERNAME, VENDOR_PASSWORD, skipWithoutVendorPassword } from "./vendor-credentials"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

const NOTE = "My son has a peanut allergy. Please no satay sauce."
// 23:10Z on 3 Oct 2026 is 00:10 on 4 Oct in London (BST); 17:05Z is 18:05.
const CREATED_AT = "2026-10-03T23:10:25Z"
const ACK_AT = "2026-10-03T17:05:00Z"

async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  await expect(ssoButton, "no 'Sign in with Keycloak' button on /auth/signin").toHaveCount(1)
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 25_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
}

const shop = {
  id: "shop-1", tenantId: "tenant-1", name: "Test Shop", address: "1 Main St", slug: "test",
  description: null, logoUrl: null, bannerUrl: null, phone: null, email: null,
  latitude: null, longitude: null, openingHours: null, deliveryInfo: null,
  minimumOrderPennies: 0, published: true, tags: null,
  createdAt: CREATED_AT, updatedAt: CREATED_AT,
}

const order = {
  id: "order-1", tenantId: "tenant-1", shopId: "shop-1", orderNumber: "ORD-TEST-0001",
  status: "CONFIRMED", customerName: "Alice", totalAmountPennies: 1000,
  notes: "Ring the bell",
  items: [
    { id: "item-1", productId: "p-1", productName: "Jollof Rice", quantity: 2, unitPricePennies: 500, totalPricePennies: 1000, createdAt: CREATED_AT, allergenNames: ["Peanuts"] },
  ],
  allergenMask: 4, allergenNames: ["Peanuts"], allergenFlags: [],
  placedVia: "STOREFRONT", allergenAckMask: 4, allergenAckAt: "2026-10-03T17:02:00Z",
  acknowledgedAllergenNames: ["Peanuts"],
  allergyNote: NOTE, allergyNoteAcknowledgedAt: null, allergyNoteAcknowledgedBy: null,
  paymentStatus: "CAPTURED", paymentReference: "pi_test_123", paymentMethod: "card", refunds: [],
  fulfilmentType: "COLLECTION",
  createdAt: CREATED_AT, updatedAt: CREATED_AT,
}

const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) })

test.describe("31.1-22 — the allergy note on the kitchen board and the order detail", () => {
  let ackCalls: string[]

  test.beforeEach(async ({ context, page }) => {
    ackCalls = []
    await context.route("**/ws**", (route) => route.abort())
    await context.route("**/api/v1/orders/stream", (route) => route.abort())
    await context.route(`${API}/api/v1/shops**`, (route) => route.fulfill(json({ content: [shop] })))
    await context.route(`${API}/api/v1/orders/kitchen**`, (route) =>
      route.fulfill(json({ content: [order], totalElements: 1, totalPages: 1, size: 100, number: 0, first: true, last: true }))
    )
    await context.route(`${API}/api/v1/orders/*/detail`, (route) => route.fulfill(json(order)))
    await context.route(`${API}/api/v1/orders/*/allergy-note/acknowledgement`, (route) => {
      ackCalls.push(route.request().url())
      return route.fulfill(json({ ...order, allergyNoteAcknowledgedAt: ACK_AT, allergyNoteAcknowledgedBy: "kim" }))
    })
    await vendorLogin(page)
  })

  test("the board shows the note in the card header and marks it read from the server's answer", async ({ page }) => {
    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })

    const note = page.getByRole("group", { name: "ALLERGY NOTE" })
    await expect(note).toBeVisible()
    await expect(note).toContainText(NOTE)

    const button = note.getByRole("button", { name: /^Mark allergy note as read/ })
    await expect(button).toBeVisible()
    const box = await button.boundingBox()
    expect(box, "the button has no layout box").not.toBeNull()
    expect((box as { height: number }).height).toBeGreaterThanOrEqual(44)

    await button.click()
    await expect(note.getByText(/Read by kim at 18:05/)).toBeVisible()
    await expect(button).toHaveCount(0)
    expect(ackCalls).toHaveLength(1)

    // The goods already on the card stay: the big bump button.
    await expect(page.getByRole("button", { name: /Start Preparing/i })).toBeVisible()
  })

  test("the order detail states the channel, shows the note, and reads UK dates", async ({ page }) => {
    await page.goto(`${BASE}/dashboard/orders/order-1`, { waitUntil: "domcontentloaded" })

    await expect(page.getByText("Created 4 October 2026, 00:10")).toBeVisible()
    await expect(
      page.getByText("Customer confirmed allergens: Peanuts — 3 October 2026, 18:02")
    ).toBeVisible()
    await expect(page.getByRole("group", { name: "ALLERGY NOTE" })).toContainText(NOTE)
    await expect(page.getByText(/\b(AM|PM)\b/)).toHaveCount(0)
  })
})

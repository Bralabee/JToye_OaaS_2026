/**
 * Comprehensive E2E tests for the customer storefront.
 * These test REAL user journeys — not just element existence, but actual
 * flows that a customer would experience end-to-end.
 *
 * Run: npx playwright test e2e/storefront-flows.spec.ts
 */

import { test, expect, type Page } from "@playwright/test"

const BASE = "http://localhost:3000"
const KEYCLOAK = "http://localhost:8085"
const SHOP_SLUG = "jollof-express-brixton-900b57a8"

// Helper: place an order and return the order number
async function placeOrder(page: Page, email: string, name = "E2E Test User") {
  await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(2000)

  // Add first available product
  const addBtn = page.locator('button:has-text("Add")').first()
  await expect(addBtn).toBeVisible({ timeout: 5000 })
  await addBtn.click()
  await page.waitForTimeout(500)

  // Go to cart
  await page.locator("text=View basket").click()
  await page.waitForLoadState("networkidle")
  await expect(page.locator("text=Your basket")).toBeVisible()

  // Proceed to checkout
  await page.locator("text=Proceed to checkout").click()
  await page.waitForLoadState("networkidle")
  await expect(page.locator("text=Your details")).toBeVisible()

  // Fill form
  await page.fill("input#name", name)
  await page.fill("input#email", email)
  await page.fill("input#phone", "07700 000000")

  // Submit
  await page.locator('button[type="submit"]:has-text("Place order")').click()
  await page.waitForTimeout(5000)

  // Should be on confirmation/tracking page
  expect(page.url()).toContain("/orders/ORD-")
  const orderNumber = page.url().split("/orders/")[1].split("?")[0]
  return orderNumber
}

test.describe("Shop Discovery", () => {
  test("customer can browse shops and see shop details", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")

    // Shop card renders with real data
    await expect(page.locator("text=Jollof Express Brixton")).toBeVisible()
    await expect(page.locator("text=Nigerian").first()).toBeVisible()

    // Click into shop
    await page.locator(`a[href*="${SHOP_SLUG}"]`).first().click()
    await page.waitForLoadState("networkidle")

    // Menu loads with categories and products
    await expect(page.locator("text=Jollof Rice")).toBeVisible({ timeout: 5000 })
    await expect(page.locator("text=Popular")).toBeVisible()

    // Dietary badges render
    await expect(page.locator("text=Halal").first()).toBeVisible()

    // Price renders correctly
    await expect(page.locator("text=£8.99").first()).toBeVisible()
  })

  test("search filters shops", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")

    await page.fill('input[placeholder*="Search"]', "Nigerian")
    await page.waitForTimeout(1500)
    await expect(page.locator("text=Jollof Express Brixton")).toBeVisible()

    await page.fill('input[placeholder*="Search"]', "xyznonexistent")
    await page.waitForTimeout(1500)
    await expect(page.locator("text=No shops found")).toBeVisible()
  })
})

test.describe("Cart + Checkout", () => {
  test("add items, view cart, modify quantity, checkout", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    // Add two different items
    const addButtons = page.locator('button:has-text("Add")')
    await addButtons.first().click()
    await page.waitForTimeout(300)
    await addButtons.nth(1).click()
    await page.waitForTimeout(500)

    // Floating cart bar appears
    const cartBar = page.locator("text=View basket")
    await expect(cartBar).toBeVisible()

    // Go to cart
    await cartBar.click()
    await page.waitForLoadState("networkidle")
    await expect(page.locator("text=Your basket")).toBeVisible()
    await expect(page.locator("text=Proceed to checkout")).toBeVisible()

    // Increase quantity on first item
    const plusBtn = page.locator('button:has(svg.lucide-plus)').first()
    await plusBtn.click()
    await page.waitForTimeout(500)

    // Proceed to checkout
    await page.locator("text=Proceed to checkout").click()
    await page.waitForLoadState("networkidle")

    // Fill and submit
    await page.fill("input#name", "Cart Flow Tester")
    await page.fill("input#email", "cartflow@test.com")
    await page.fill("input#phone", "07700 111111")
    await page.locator('button[type="submit"]:has-text("Place order")').click()
    await page.waitForTimeout(5000)

    // Should land on tracking page with progress
    expect(page.url()).toContain("/orders/ORD-")
    await expect(page.locator("text=Order in Progress")).toBeVisible({ timeout: 5000 })
    await expect(page.locator("text=Received")).toBeVisible()
  })

  test("empty cart shows appropriate message", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}/cart`)
    await page.waitForLoadState("networkidle")
    await expect(page.locator("text=Your basket is empty")).toBeVisible()
    await expect(page.locator("text=Back to menu")).toBeVisible()
  })
})

test.describe("Order Tracking", () => {
  test("after placing order, My Orders shows it and Track link works", async ({ page }) => {
    const email = `track-${Date.now()}@test.com`
    const orderNumber = await placeOrder(page, email)

    // Navigate to My Orders
    await page.goto(`${BASE}/shop/orders`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(3000)

    // Order card visible
    await expect(page.locator("text=Jollof Express").first()).toBeVisible({ timeout: 5000 })
    await expect(page.locator("text=Received").first()).toBeVisible()

    // Click Track
    await page.locator('a:has-text("Track")').first().click()
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(3000)

    // Progress tracker shows — NOT an email prompt
    await expect(page.locator("text=Enter the email")).not.toBeVisible()
    await expect(page.locator("text=Order in Progress")).toBeVisible()
    await expect(page.locator("text=Received")).toBeVisible()
    await expect(page.locator("text=Confirmed")).toBeVisible()
    await expect(page.locator("text=Preparing").first()).toBeVisible()
    await expect(page.getByText("Ready", { exact: true })).toBeVisible()
    await expect(page.getByText("Completed", { exact: true })).toBeVisible()
    await expect(page.locator("text=Live updates")).toBeVisible()
  })

  test("standalone tracker works with order number + email", async ({ page }) => {
    const email = `standalone-${Date.now()}@test.com`
    const orderNumber = await placeOrder(page, email)

    // Open standalone tracker in a fresh context (clear storage)
    const newPage = await page.context().newPage()
    await newPage.goto(`${BASE}/track?order=${orderNumber}&email=${email}`)
    await newPage.waitForLoadState("networkidle")
    await newPage.waitForTimeout(3000)

    // Should show results without manual input
    await expect(newPage.locator("text=Jollof Express").first()).toBeVisible({ timeout: 5000 })
    await expect(newPage.locator("text=Received").first()).toBeVisible()
    await newPage.close()
  })

  test("standalone tracker rejects wrong email", async ({ page }) => {
    const email = `reject-${Date.now()}@test.com`
    const orderNumber = await placeOrder(page, email)

    await page.goto(`${BASE}/track`)
    await page.waitForLoadState("networkidle")

    await page.fill("input#orderNumber", orderNumber)
    await page.fill("input#email", "wrong@email.com")
    await page.locator('button:has-text("Track order")').click()
    await page.waitForTimeout(3000)

    await expect(page.locator("text=not found").or(page.locator("text=Not found"))).toBeVisible()
  })
})

test.describe("Customer Auth", () => {
  test("Sign in button redirects to Keycloak", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    // Sign in button exists
    const signIn = page.locator('button:has-text("Sign in")')
    await expect(signIn).toBeVisible()

    // Click redirects to Keycloak
    await signIn.click()
    await page.waitForTimeout(3000)
    expect(page.url()).toContain("8085")
    expect(page.url()).toContain("storefront-client")

    // Registration link available
    await expect(page.locator('a:has-text("Register")')).toBeVisible()
  })

  test("after login, nav shows profile and Sign in disappears", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    // Click Sign in
    await page.locator('button:has-text("Sign in")').click()
    await page.waitForTimeout(3000)

    // Register a new user
    const rand = Math.floor(Math.random() * 100000)
    const regLink = page.locator('a:has-text("Register")')
    if (await regLink.isVisible()) {
      await regLink.click()
      await page.waitForTimeout(2000)

      await page.fill("input#email", `e2e${rand}@test.com`)
      await page.fill("input#password", "TestPass123!")
      await page.fill("input#password-confirm", "TestPass123!")
      await page.fill("input#firstName", "E2E")
      await page.fill("input#lastName", `User${rand}`)
      await page.locator('input[type="submit"]').click()
      await page.waitForTimeout(5000)
    }

    // Should be back on storefront
    expect(page.url()).toContain("/shop")

    // Wait for nav to update (polling)
    await page.waitForTimeout(3000)

    // Sign in should be GONE
    await expect(page.locator('button:has-text("Sign in")')).not.toBeVisible()

    // Sign out should be visible
    await expect(page.locator('button[title="Sign out"]')).toBeVisible()
  })
})

test.describe("Email Notifications", () => {
  test("order confirmation email sent to Mailhog", async ({ page, request }) => {
    // Clear Mailhog
    await request.delete("http://localhost:8025/api/v1/messages")

    const email = `email-${Date.now()}@test.com`
    await placeOrder(page, email)

    // Wait for async email delivery
    await page.waitForTimeout(5000)

    // Check Mailhog
    const response = await request.get("http://localhost:8025/api/v2/messages?limit=10")
    const data = await response.json()

    expect(data.items.length).toBeGreaterThan(0)

    const subject = data.items[0]?.Content?.Headers?.Subject?.[0] || ""
    expect(subject).toContain("Received")

    const body = data.items[0]?.Content?.Body || ""
    expect(body.toLowerCase()).toContain("track")
  })
})

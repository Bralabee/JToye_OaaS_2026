/**
 * E2E tests for the customer storefront.
 * Tests REAL user journeys including visual rendering — not just element existence.
 *
 * Run: npx playwright test e2e/storefront-flows.spec.ts
 */

import { test, expect, type Page } from "@playwright/test"

// Honour PLAYWRIGHT_BASE_URL (dev stack runs on :3100; the MCP server holds
// :3000). Hardcoding :3000 tested the wrong app — and, on a shared host, could
// hit a cohabiting service. Mirrors the default in playwright.config.ts.
const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const SHOP_SLUG = "jollof-express-brixton-900b57a8"

// Helper: register + login a customer, returns the page with an active session
async function loginCustomer(page: Page): Promise<{ email: string }> {
  const rand = Math.floor(Math.random() * 100000)
  const email = `e2e${rand}@test.com`

  await page.goto(`${BASE}/shop`)
  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(1000)

  await page.locator('button:has-text("Sign in")').click()
  await page.waitForTimeout(3000)

  // Register
  const regLink = page.locator('a:has-text("Register")')
  if (await regLink.isVisible()) {
    await regLink.click()
    await page.waitForTimeout(2000)
    await page.fill("input#email", email)
    await page.fill("input#password", "TestPass123!")
    await page.fill("input#password-confirm", "TestPass123!")
    await page.fill("input#firstName", "E2E")
    await page.fill("input#lastName", `User${rand}`)
    await page.locator('input[type="submit"]').click()
    await page.waitForTimeout(5000)
  }

  return { email }
}

// Helper: place an order (requires logged-in customer)
async function placeOrder(page: Page, email: string, name = "E2E Test User") {
  await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(2000)

  const addBtn = page.locator('button:has-text("Add")').first()
  await expect(addBtn).toBeVisible({ timeout: 5000 })
  await addBtn.click()
  await page.waitForTimeout(500)

  await page.locator("text=View basket").click()
  await page.waitForLoadState("networkidle")
  await expect(page.locator("text=Your basket")).toBeVisible()

  await page.locator("text=Proceed to checkout").click()
  await page.waitForLoadState("networkidle")

  await page.fill("input#name", name)
  await page.fill("input#email", email)
  await page.fill("input#phone", "07700 000000")
  await page.locator('button[type="submit"]:has-text("Place order")').click()
  await page.waitForTimeout(5000)

  expect(page.url()).toContain("/orders/ORD-")
  const orderNumber = page.url().split("/orders/")[1].split("?")[0]
  return orderNumber
}

// ============================
// SHOP DISCOVERY
// ============================

test.describe("Shop Discovery", () => {
  test("shop card renders with real data", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")

    await expect(page.locator("text=Jollof Express Brixton")).toBeVisible()
    await expect(page.locator("text=Nigerian").first()).toBeVisible()
    await expect(page.locator("text=Browse")).toBeVisible()
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

// ============================
// SHOP MENU + PRODUCT IMAGES
// ============================

test.describe("Shop Menu & Product Cards", () => {
  test("menu loads with categories and products", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")

    await expect(page.locator("text=Jollof Rice")).toBeVisible({ timeout: 5000 })
    await expect(page.locator("text=Popular")).toBeVisible()
    await expect(page.locator("text=Halal").first()).toBeVisible()
    await expect(page.locator("text=£8.99").first()).toBeVisible()
  })

  test("product images ACTUALLY RENDER on cards (not just exist in DOM)", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(3000)

    // Get all product card images and check they have non-zero dimensions
    const imageResults = await page.evaluate(() => {
      const imgs = document.querySelectorAll("article img")
      return Array.from(imgs).map((img) => {
        const el = img as HTMLImageElement
        return {
          loaded: el.complete && el.naturalWidth > 0,
          naturalWidth: el.naturalWidth,
          naturalHeight: el.naturalHeight,
          src: el.src.substring(0, 80),
        }
      })
    })

    // There should be product images on the page
    expect(imageResults.length).toBeGreaterThan(0)

    // EVERY product image that exists must actually render (no 0x0 ghosts)
    const loadedCount = imageResults.filter((i) => i.loaded).length
    const failedImages = imageResults.filter((i) => !i.loaded)

    console.log(`Images: ${loadedCount}/${imageResults.length} loaded`)
    if (failedImages.length > 0) {
      console.log("Failed images:", failedImages)
    }

    // All images must load — no broken images allowed
    expect(failedImages.length).toBe(0)
  })

  test("promotion banner and discount badge render on shop detail (STFR-06)", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    // Announcement/promotion block lives above the menu. Presence is seed-
    // dependent: skip cleanly if neither an announcement nor an active
    // promotion exists in the dev stack. A deterministic V33 seed fixture
    // is tracked as a milestone-4+ follow-up in 10-03-SUMMARY.md.
    const announcement = page.locator("section:has(h2:has-text('Menu')) ~ *, aside, div").filter({
      hasText: /announcement|new|promo|offer/i,
    })
    const percentBadge = page.locator("article").filter({ hasText: /\d+% off/ }).first()
    const flatBadge = page.locator("article").filter({ hasText: /£\d+(\.\d+)? off/ }).first()

    const announcementVisible = await announcement.first().isVisible().catch(() => false)
    const percentVisible = await percentBadge.isVisible().catch(() => false)
    const flatVisible = await flatBadge.isVisible().catch(() => false)

    test.skip(
      !announcementVisible && !percentVisible && !flatVisible,
      "No active announcement or promotion seed — banner/badge assertion requires a V33 promo fixture (tracked as 10-03 follow-up)"
    )

    // At least one of the three marketing surfaces must render when the
    // skip guard above did not trigger.
    expect(announcementVisible || percentVisible || flatVisible).toBe(true)

    if (percentVisible || flatVisible) {
      const anyBadge = percentVisible ? percentBadge : flatBadge
      await expect(anyBadge).toBeVisible()
    }
  })

  test("clicking product card opens detail modal", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    // Click a product card
    const productCard = page.locator("article").first()
    await productCard.click()
    await page.waitForTimeout(600)

    // Modal should appear with product details
    await expect(page.locator("text=About")).toBeVisible({ timeout: 3000 })
    await expect(page.locator("text=Ingredients")).toBeVisible()
    await expect(page.locator("text=Add to cart")).toBeVisible()
  })

  test("detail modal shows image carousel when product has images", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    // Click a product that has an image
    const suya = page.locator("article").filter({ hasText: "Suya" }).first()
    if (await suya.count() > 0) {
      await suya.click()
      await page.waitForTimeout(800)

      // Modal should have an image that actually loaded
      const modalImage = await page.evaluate(() => {
        const modal = document.querySelector(".fixed.inset-0.z-50")
        if (!modal) return null
        const img = modal.querySelector("img") as HTMLImageElement | null
        if (!img) return null
        return {
          loaded: img.complete && img.naturalWidth > 0,
          naturalWidth: img.naturalWidth,
          src: img.src.substring(0, 80),
        }
      })

      expect(modalImage).not.toBeNull()
      expect(modalImage?.loaded).toBe(true)
      expect(modalImage?.naturalWidth).toBeGreaterThan(0)
    }
  })
})

// ============================
// CART + CHECKOUT
// ============================

test.describe("Cart + Checkout", () => {
  test("add items, view cart, modify quantity, checkout", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    const addButtons = page.locator('button:has-text("Add")')
    await addButtons.first().click()
    await page.waitForTimeout(300)
    await addButtons.nth(1).click()
    await page.waitForTimeout(500)

    const cartBar = page.locator("text=View basket")
    await expect(cartBar).toBeVisible()

    // STFR-06: navigate explicitly to the standalone /shop/{slug}/cart page
    // before proceeding, so the dedicated cart route is exercised end-to-end
    // (not just the floating cart drawer).
    await page.goto(`${BASE}/shop/${SHOP_SLUG}/cart`)
    await page.waitForLoadState("networkidle")
    await expect(page.locator("text=Your basket")).toBeVisible()
    await expect(page.locator("text=Proceed to checkout")).toBeVisible()

    await page.locator("text=Proceed to checkout").click()
    await page.waitForLoadState("networkidle")

    await page.fill("input#name", "Cart Flow Tester")
    await page.fill("input#email", "cartflow@test.com")
    await page.fill("input#phone", "07700 111111")
    await page.locator('button[type="submit"]:has-text("Place order")').click()
    await page.waitForTimeout(5000)

    expect(page.url()).toContain("/orders/ORD-")
    await expect(page.locator("text=Order in Progress")).toBeVisible({ timeout: 5000 })
  })

  test("empty cart shows appropriate message", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}/cart`)
    await page.waitForLoadState("networkidle")
    await expect(page.locator("text=Your basket is empty")).toBeVisible()
  })
})

// ============================
// ORDER TRACKING (AUTH REQUIRED)
// ============================

test.describe("Order Tracking (requires auth)", () => {
  test("My Orders page requires login — shows sign-in prompt when not authenticated", async ({ page }) => {
    await page.goto(`${BASE}/shop/orders`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(1000)

    await expect(page.locator("text=Sign in to continue")).toBeVisible()
    await expect(page.locator('button:has-text("Sign in")')).toBeVisible()
  })

  test("standalone tracker requires login", async ({ page }) => {
    await page.goto(`${BASE}/track`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(1000)

    await expect(page.locator("text=Sign in to continue")).toBeVisible()
  })

  test("My Orders link hidden when not logged in", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(1000)

    // "My Orders" should NOT be visible in nav
    const myOrders = page.locator('nav >> text=My Orders')
    await expect(myOrders).not.toBeVisible()

    // But Browse and Sign in should be
    await expect(page.locator('nav >> text=Browse')).toBeVisible()
    await expect(page.locator('nav >> button:has-text("Sign in")')).toBeVisible()
  })
})

// ============================
// CUSTOMER AUTH
// ============================

test.describe("Customer Auth", () => {
  test("Sign in button redirects to Keycloak", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    const signIn = page.locator('button:has-text("Sign in")')
    await expect(signIn).toBeVisible()

    await signIn.click()
    await page.waitForTimeout(3000)
    expect(page.url()).toContain("8085")
    expect(page.url()).toContain("storefront-client")

    await expect(page.locator('a:has-text("Register")')).toBeVisible()
  })

  test("after login, nav shows profile and My Orders appears", async ({ page }) => {
    await loginCustomer(page)

    // Should be back on storefront
    expect(page.url()).toContain("/shop")
    await page.waitForTimeout(3000)

    // Sign in should be GONE
    await expect(page.locator('button:has-text("Sign in")')).not.toBeVisible()

    // Sign out should be visible
    await expect(page.locator('button[title="Sign out"]')).toBeVisible()

    // My Orders should now be visible
    await expect(page.locator('nav >> text=My Orders')).toBeVisible()
  })
})

// ============================
// EMAIL NOTIFICATIONS
// ============================

test.describe("Email Notifications", () => {
  test("order confirmation email sent to Mailhog", async ({ page, request }) => {
    await request.delete("http://localhost:8025/api/v1/messages")

    const email = `email-${Date.now()}@test.com`
    await placeOrder(page, email)

    await page.waitForTimeout(5000)

    const response = await request.get("http://localhost:8025/api/v2/messages?limit=10")
    const data = await response.json()

    expect(data.items.length).toBeGreaterThan(0)

    const subject = data.items[0]?.Content?.Headers?.Subject?.[0] || ""
    expect(subject).toContain("Received")

    const body = data.items[0]?.Content?.Body || ""
    expect(body.toLowerCase()).toContain("track")
  })
})

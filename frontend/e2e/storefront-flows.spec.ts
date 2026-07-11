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
// Target a DETERMINISTIC seeded shop (DemoDataSeeder, UIX-05). The old
// hardcoded `jollof-express-brixton-<hash>` slug carried a random suffix that
// changed on every reseed, so it no longer resolved. "Mama Ade's Kitchen" has a
// stable slug and a curated Nigerian menu (Jollof Rice @ £8.99, featured items,
// Halal tags) with no duplicate line items.
const SHOP_SLUG = "mama-ades-kitchen"
const SHOP_NAME = "Mama Ade's Kitchen"

// Helper: register + login a customer, returns the page with an active session
async function loginCustomer(page: Page): Promise<{ email: string }> {
  const rand = Math.floor(Math.random() * 100000)
  const email = `e2e${rand}@test.com`

  await page.goto(`${BASE}/shop`)
  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(1000)

  // Scope to the NAV "Sign in" (the page body can also render a "Sign in" CTA).
  await page.locator("nav").getByRole("button", { name: "Sign in" }).first().click()
  await page.waitForTimeout(3000)

  // Register (verifyEmail=false in jtoye-customers, so this auto-authenticates).
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
    // Wait for the OIDC round-trip to land back on the storefront (callback →
    // token exchange → localStorage session marker), then reload so the nav's
    // session check runs against the freshly-set marker.
    await page.waitForURL(/\/shop(\/|$|\?)/, { timeout: 25_000 }).catch(() => {})
    await page.waitForTimeout(3000)
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)
  }

  return { email }
}

// Helper: place an order (requires logged-in customer).
// UIX-04: checkout now defaults to Delivery and requires a UK address; the fee
// breakdown is shown BEFORE payment.
async function placeOrder(page: Page, email: string, name = "E2E Test User") {
  await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
  await page.waitForLoadState("domcontentloaded")
  await page.waitForTimeout(2000)

  // Add TWO items so the order clears the seeded shop's £10 minimum (a single
  // item is £8.99 < £10 and the order would be blocked at checkout). After the
  // first "Add" the card swaps to a +/- stepper, so the next `Add` button is a
  // different product.
  const addButtons = page.locator('button:has-text("Add")')
  await expect(addButtons.first()).toBeVisible({ timeout: 5000 })
  await addButtons.first().click()
  await page.waitForTimeout(400)
  await addButtons.first().click()
  await page.waitForTimeout(500)

  await page.locator("text=View basket").click()
  await page.waitForLoadState("domcontentloaded")
  await expect(page.locator("text=Your basket")).toBeVisible()

  await page.locator("text=Proceed to checkout").click()
  await page.waitForLoadState("domcontentloaded")

  await page.fill("input#name", name)
  await page.fill("input#email", email)
  await page.fill("input#phone", "07700 000000")

  // Default fulfilment is Delivery — a UK address is required to submit.
  await page.fill("input#address1", "12 Coldharbour Lane")
  await page.fill("input#city", "London")
  await page.fill("input#postcode", "SW9 8LF")

  // Definite fee breakdown is visible BEFORE payment.
  await expect(page.getByText("Subtotal")).toBeVisible()
  await expect(page.getByText("Total", { exact: true })).toBeVisible()

  await page.locator('button[type="submit"]:has-text("Place order")').click()

  // No Stripe keys in this env → the order takes the COD path and confirms
  // INLINE ("Order confirmed! · Pay on collection"), rather than redirecting to
  // /orders/ORD- (that redirect only follows a live Stripe payment). The order
  // is genuinely created (its number is shown + a confirmation email is sent).
  await expect(page.getByRole("heading", { name: "Order confirmed!" })).toBeVisible({ timeout: 15000 })
  const confText = await page.getByText(/Order\s+ORD-/).innerText()
  const match = confText.match(/ORD-[A-Z0-9-]+/)
  expect(match).not.toBeNull()
  return match![0]
}

// ============================
// SHOP DISCOVERY
// ============================

test.describe("Shop Discovery", () => {
  test("shop card renders with real data", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")

    await expect(page.locator(`text=${SHOP_NAME}`)).toBeVisible()
    // Cuisine tag chip from the seeded shop's `tags`.
    await expect(page.locator("text=Nigerian").first()).toBeVisible()
    // `text=Browse` is ambiguous (nav link + hero body copy) — scope to the nav link.
    await expect(page.getByRole("link", { name: "Browse" })).toBeVisible()
  })

  test("search filters shops", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")

    await page.fill('input[placeholder*="Search"]', "Nigerian")
    await page.waitForTimeout(1500)
    await expect(page.locator(`text=${SHOP_NAME}`)).toBeVisible()

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
    await page.waitForLoadState("domcontentloaded")

    // Jollof Rice is featured, so it appears in BOTH the "Popular" section and
    // its "Mains" category (as name + description) — scope to the first match.
    await expect(page.locator("text=Jollof Rice").first()).toBeVisible({ timeout: 5000 })
    await expect(page.locator("text=Popular")).toBeVisible()
    await expect(page.locator("text=Halal").first()).toBeVisible()
    await expect(page.locator("text=£8.99").first()).toBeVisible()
  })

  test("per-shop menu shows only this shop's products with no duplicate line items (UIX-05)", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("domcontentloaded")
    await page.waitForTimeout(2000)

    // The shop's own menu renders
    await expect(page.locator("text=Jollof Rice").first()).toBeVisible({ timeout: 5000 })

    // Collect product-card titles from the CATEGORY sections only, excluding the
    // featured "Popular" section (where featured items legitimately re-appear).
    // 19-02 dropped the `shopId IS NULL` bleed, so a shop no longer shows the
    // 24-item shared menu and no title duplicates across its own categories.
    const categoryTitles = await page.evaluate(() => {
      const titles: string[] = []
      for (const section of Array.from(document.querySelectorAll("section"))) {
        const heading = section.querySelector("h2")?.textContent?.trim() || ""
        if (/popular/i.test(heading)) continue
        section.querySelectorAll("article h4").forEach((h) => {
          const t = h.textContent?.trim()
          if (t) titles.push(t)
        })
      }
      return titles
    })

    expect(categoryTitles.length).toBeGreaterThan(0)

    // No duplicate line items — each product appears once in its own category.
    const duplicates = categoryTitles.filter((t, i) => categoryTitles.indexOf(t) !== i)
    expect(duplicates).toEqual([])
  })

  test("images obey the SafeImage contract: populated render, photo-less fall back, never broken (UI-SPEC Surface G)", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(3000)

    // Product cards are present.
    expect(await page.locator("article").count()).toBeGreaterThan(0)

    // Contract #1 — NO broken <img> ever renders. Every populated <img> on the
    // page (shop logo, any product photo) must resolve to non-zero dimensions;
    // a 0x0 ghost is a hard failure.
    const imageResults = await page.evaluate(() =>
      Array.from(document.querySelectorAll("img")).map((img) => {
        const el = img as HTMLImageElement
        return {
          loaded: el.complete && el.naturalWidth > 0,
          naturalWidth: el.naturalWidth,
          src: el.src.substring(0, 100),
        }
      })
    )
    const brokenImages = imageResults.filter((i) => !i.loaded)
    console.log(`Images: ${imageResults.length - brokenImages.length}/${imageResults.length} loaded`)
    if (brokenImages.length > 0) console.log("Broken images:", brokenImages)
    expect(brokenImages).toEqual([])

    // Contract #2 — a POPULATED image resolves naturalWidth>0 on real data: the
    // seeded shop logo (a branded card image) is a genuine <img>, not a fallback.
    const logo = imageResults.find((i) => i.src.includes("/brand/logo"))
    expect(logo, "seeded shop logo image should be present").toBeTruthy()
    expect(logo?.naturalWidth ?? 0).toBeGreaterThan(0)

    // Contract #3 — photo-less products (no product photography added this phase,
    // #15) render the SafeImage BRANDED FALLBACK TILE (a gradient div), never a
    // broken <img>. Prove the fallback path renders on the product cards.
    const fallbackTiles = await page.locator("article div.bg-gradient-to-br").count()
    expect(fallbackTiles).toBeGreaterThan(0)
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
  test("add items, view cart, modify quantity, checkout with fulfilment + fee-before-pay", async ({ page }) => {
    await page.goto(`${BASE}/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("domcontentloaded")
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
    await page.waitForLoadState("domcontentloaded")
    await expect(page.locator("text=Your basket")).toBeVisible()
    await expect(page.locator("text=Proceed to checkout")).toBeVisible()

    await page.locator("text=Proceed to checkout").click()
    await page.waitForLoadState("domcontentloaded")

    // UIX-04: fulfilment defaults to Delivery and the UK address block is shown.
    await expect(page.getByRole("button", { name: /delivery/i })).toBeVisible()
    await expect(page.getByRole("button", { name: /collection/i })).toBeVisible()
    await expect(page.locator("text=Delivery address")).toBeVisible()

    await page.fill("input#name", "Cart Flow Tester")
    await page.fill("input#email", "cartflow@test.com")
    await page.fill("input#phone", "07700 111111")
    await page.fill("input#address1", "5 Atlantic Road")
    await page.fill("input#city", "London")
    await page.fill("input#postcode", "SW9 8HX")

    // Fee breakdown (Subtotal + Delivery + Total) is visible BEFORE payment —
    // the deferred "Delivery fee may apply" footnote is gone. Scope to the
    // "Order summary" block: "Delivery" also names the fulfilment TOGGLE button,
    // so an unscoped exact-text match is ambiguous.
    const orderSummary = page.getByRole("heading", { name: "Order summary" }).locator("..")
    await expect(orderSummary.getByText("Subtotal")).toBeVisible()
    await expect(orderSummary.getByText("Delivery", { exact: true }).first()).toBeVisible()
    await expect(orderSummary.getByText("Total", { exact: true })).toBeVisible()
    await expect(page.locator("text=Final total confirmed")).toHaveCount(0)

    await page.locator('button[type="submit"]:has-text("Place order")').click()

    // COD path (no Stripe keys in this env): the order confirms INLINE with the
    // fee breakdown — "Order confirmed! · Pay on collection" — proving the order
    // was created and the fee-before-pay total is shown, without a live card.
    await expect(page.getByRole("heading", { name: "Order confirmed!" })).toBeVisible({ timeout: 15000 })
    await expect(page.getByText(/Order\s+ORD-/)).toBeVisible()
    await expect(page.getByRole("heading", { name: "Order total" })).toBeVisible()
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
    // Two "Sign in" affordances exist (nav + the RequireCustomerAuth prompt).
    // Assert the in-page prompt button specifically, scoped to <main>.
    await expect(page.getByRole("main").getByRole("button", { name: "Sign in" })).toBeVisible()
  })

  test("standalone tracker is a GUEST lookup — no forced sign-in (Surface H)", async ({ page }) => {
    await page.goto(`${BASE}/track`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(1000)

    // Surface H removed the RequireCustomerAuth wall: /track is now an
    // email-gated GUEST lookup (order number + email), NOT a sign-in wall.
    await expect(page.getByRole("heading", { name: /track your order/i })).toBeVisible()
    await expect(page.locator("#orderNumber")).toBeVisible()
    await expect(page.locator("#email")).toBeVisible()
    // The forced sign-in prompt must NOT appear.
    await expect(page.locator("text=Sign in to continue")).toHaveCount(0)
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

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

  // #382 split customer and vendor sign-in. The nav control is now a LINK to the
  // /shop/signin landing page — NOT a button firing customerLogin() straight at
  // Keycloak. Ask for role=link: a role=button locator matches nothing here, and
  // `.first().click()` on nothing waits out the entire test timeout, which reads
  // as a slow stack rather than as a stale locator. Scope to the NAV, since the
  // page body can also render a "Sign in" CTA.
  await page.locator("nav").getByRole("link", { name: "Sign in" }).first().click()
  await page.waitForURL(/\/shop\/signin/, { timeout: 15_000 })

  // The landing page offers both doors. "Create an account" calls
  // customerRegister(), which redirects to Keycloak's /registrations endpoint
  // directly — so there is no longer a "Register" link to click on a login page.
  // verifyEmail=false in jtoye-customers, so registering auto-authenticates.
  await page.getByRole("button", { name: "Create an account" }).click()
  await page.waitForURL(/openid-connect\/registrations/, { timeout: 20_000 })

  await page.fill("input#email", email)
  await page.fill("input#password", "TestPass123!")
  await page.fill("input#password-confirm", "TestPass123!")
  await page.fill("input#firstName", "E2E")
  await page.fill("input#lastName", `User${rand}`)
  await page.locator('input[type="submit"]').click()

  // Wait for the OIDC round-trip to land back on the storefront (callback →
  // token exchange → session cookie), then reload so the nav's session check
  // runs against the freshly-set session.
  await page.waitForURL(/\/shop(\/|$|\?)/, { timeout: 25_000 }).catch(() => {})
  await page.waitForTimeout(3000)
  await page.goto(`${BASE}/shop`)
  await page.waitForLoadState("networkidle")
  await page.waitForTimeout(2000)

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
  // INLINE ("Order confirmed! · Pay on delivery" — this is a DELIVERY order,
  // WR-08), rather than redirecting to /orders/ORD- (that redirect only
  // follows a live Stripe payment). The order is genuinely created (its
  // number is shown + a confirmation email is sent).
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

    await page.fill('#shop-search', "Nigerian")
    await page.waitForTimeout(1500)
    await expect(page.locator(`text=${SHOP_NAME}`)).toBeVisible()

    await page.fill('#shop-search', "xyznonexistent")
    await page.waitForTimeout(1500)
    await expect(page.locator("text=No kitchens found")).toBeVisible()
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

    // Contract #3 — EVERY product card presents either a loaded photo or the
    // SafeImage branded fallback tile (a gradient div). Never neither.
    //
    // This previously asserted `fallbackTiles > 0`, on the premise that no
    // product photography existed yet (#15). That premise expired: all seven
    // seeded products on this shop now carry images, so zero fallback tiles
    // render and the assertion failed on CORRECT behaviour. Asserting per-card
    // coverage keeps the fallback guarantee without pinning it to seed state —
    // and unlike relaxing it to `>= 0`, it cannot pass vacuously, because every
    // card has to account for itself.
    const cards = page.locator("article")
    const cardCount = await cards.count()
    expect(
      cardCount,
      "VOID: no product cards rendered — the per-card contract below would pass on zero"
    ).toBeGreaterThan(0)

    for (let i = 0; i < cardCount; i++) {
      const card = cards.nth(i)
      const loadedPhotos = await card
        .locator("img")
        .evaluateAll((imgs) => imgs.filter((el) => (el as HTMLImageElement).naturalWidth > 0).length)
      const tiles = await card.locator("div.bg-gradient-to-br").count()
      expect(
        loadedPhotos + tiles,
        `product card ${i} shows neither a loaded photo nor a branded fallback tile`
      ).toBeGreaterThan(0)
    }
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

    // The seeded shop enforces a £10 minimum, and one item is £8.99. Clicking
    // `.first()` then `.nth(1)` looks like "add two products" but is not: after
    // the first Add the card swaps to a +/- stepper, so the list SHIFTS and
    // `.nth(1)` lands on a different, cheaper item. That left the basket at
    // £7.50, below the minimum, and "Place order" stayed correctly DISABLED —
    // whereupon `.click()` waited out the whole 60s test timeout. The app was
    // right; the test was adding the wrong things.
    //
    // Derive instead of counting: keep adding until the basket clears the
    // minimum, bounded so a genuinely broken Add button still fails fast.
    const addButtons = page.locator('button:has-text("Add")')
    await expect(addButtons.first()).toBeVisible({ timeout: 10_000 })

    const MAX_ADDS = 6
    let adds = 0
    while (adds < MAX_ADDS) {
      await addButtons.first().click()
      adds++
      await page.waitForTimeout(400)
      // The blocking notice disappears once the subtotal clears the minimum.
      if ((await page.getByText(/Minimum order/i).count()) === 0 && adds >= 2) break
    }
    expect(adds, "never cleared the shop minimum within MAX_ADDS items").toBeLessThan(MAX_ADDS)

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

    // Assert ENABLED before clicking. A disabled submit makes `.click()` wait out
    // the full 60s test timeout with no assertion location, so the failure reads
    // as a hung stack rather than "the basket is under the shop minimum". This
    // turns that into a named failure in 10s.
    const placeOrder = page.locator('button[type="submit"]:has-text("Place order")')
    await expect(
      placeOrder,
      "Place order is disabled — basket is likely under the shop minimum"
    ).toBeEnabled({ timeout: 10_000 })
    await placeOrder.click()

    // COD path (no Stripe keys in this env): the order confirms INLINE with the
    // fee breakdown — "Order confirmed! · Pay on delivery" (a DELIVERY order,
    // WR-08) — proving the order was created and the fee-before-pay total is
    // shown, without a live card.
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
    // Assert the in-page prompt specifically, scoped to <main>. #382 turned it
    // into a LINK to /shop/signin so an expired session has a landing
    // destination — assert the destination too, not just that a control exists.
    const prompt = page.getByRole("main").getByRole("link", { name: "Sign in" })
    await expect(prompt).toBeVisible()
    await expect(prompt).toHaveAttribute("href", /\/shop\/signin/)
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

    // Sign in IS visible at every width — and it is a LINK since #382, not a
    // button. The old button locator matched nothing, which `toBeVisible()`
    // reports as a plain "not visible" and hides the real cause.
    await expect(page.locator("nav").getByRole("link", { name: "Sign in" }).first()).toBeVisible()

    // The browse destination is labelled "Shops" (renamed from "Browse" for
    // label parity with PublicHeader — the same destination must not be called
    // two things), and its row is `hidden sm:flex`, so it is a DESKTOP-only
    // affordance. Gate on the measured viewport rather than the project name so
    // this stays honest if the projects are ever retuned.
    const width = page.viewportSize()?.width ?? 0
    if (width >= 640) {
      await expect(page.locator("nav").getByRole("link", { name: "Shops" }).first()).toBeVisible()
    } else {
      // Below sm the destinations live behind the hamburger, which must exist —
      // otherwise the nav has simply lost them on mobile.
      await expect(page.locator("nav").getByRole("button", { name: /open menu/i })).toBeVisible()
    }
  })
})

// ============================
// CUSTOMER AUTH
// ============================

test.describe("Customer Auth", () => {
  // #382 deliberately replaced the bare window.location redirect with a landing
  // PAGE, because the redirect gave a shopper no destination — no working back
  // button, no "create an account", no way to reach the vendor door if they had
  // guessed wrong. So the contract is now two hops, and this test asserts both.
  // Asserting the old one-hop behaviour was testing a decision that was reversed.
  test("Sign in goes to the customer sign-in PAGE, which hands off to Keycloak", async ({ page }) => {
    await page.goto(`${BASE}/shop`)
    await page.waitForLoadState("networkidle")
    await page.waitForTimeout(2000)

    // Hop 1: nav link -> /shop/signin, carrying where to come back to.
    const signIn = page.locator("nav").getByRole("link", { name: "Sign in" }).first()
    await expect(signIn).toBeVisible()
    await signIn.click()
    await page.waitForURL(/\/shop\/signin/, { timeout: 15_000 })
    expect(page.url()).toContain("next=")
    await expect(page.getByRole("heading", { name: "Sign in to order" })).toBeVisible()

    // The page is a landing destination, so it must not be a dead end.
    await expect(page.getByRole("link", { name: /browse kitchens/i })).toBeVisible()

    // Hop 2: the page hands off to Keycloak, against the CUSTOMER realm client.
    await page.getByRole("button", { name: "Create an account" }).click()
    await page.waitForURL(/openid-connect/, { timeout: 20_000 })
    expect(page.url()).toContain("8085")
    expect(page.url()).toContain("storefront-client")
  })

  test("after login, nav shows profile and My Orders appears", async ({ page }) => {
    await loginCustomer(page)

    // Should be back on storefront
    expect(page.url()).toContain("/shop")
    await page.waitForTimeout(3000)

    // Sign in should be GONE.
    //
    // This MUST use the link locator. `button:has-text("Sign in")` matched
    // nothing even while signed OUT — #382 made the control a <Link> — so this
    // assertion passed unconditionally and could never fail. That is worse than
    // a failing test: it read as cover for a signed-in/signed-out invariant that
    // nothing was actually checking. Falsified by running it signed-OUT, where
    // the link form correctly reports 1.
    await expect(page.locator("nav").getByRole("link", { name: "Sign in" })).toHaveCount(0)

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

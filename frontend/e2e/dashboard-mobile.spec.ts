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
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

// Honour PLAYWRIGHT_BASE_URL (set it only for a non-default host). Mirrors playwright.config.ts.
const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

// Keycloak dev-realm vendor. `admin-user` is the live jtoye-dev realm account and
// maps to tenant 00000000-…-000000000001 (the DemoDataSeeder demo tenant). The
// credential comes from e2e/vendor-credentials.ts — see that file for why the
// old `?? "password123"` default made every test here fail as a ~21s timeout
// rather than skip with a reason.

/**
 * The mobile tab bar in the LIVE document.
 *
 * `getByTestId("mobile-tab-bar")` is NOT safe here. While React is still
 * streaming, a suspended segment's HTML is parked in a `<div hidden id="S:n">`
 * staging buffer appended to `<body>` — and that buffer holds a SECOND copy of
 * the entire dashboard shell, tab bar included. A raw test-id query matches
 * both, and Playwright strict mode then fails with "resolved to 2 elements".
 *
 * Measured 2026-07-31 against the live stack: **8 such failures in one
 * whole-file run, and 0 when the same tests run in isolation** — under light
 * load the stream finishes before the assertion, so the staged copy is already
 * gone. That is the entire reason these tests looked like a duplicate-nav
 * product bug; there is no duplicate nav.
 *
 * At every one of those failures the counts were `byTestId=2` but **`byRole=1`**:
 * the staged copy is inside `[hidden]`, so it is not in the accessibility tree.
 * Querying by ROLE is therefore both immune to the staging buffer *by
 * construction* and the semantically honest assertion — what these tests mean is
 * "the Primary navigation is visible at this width", not "an element carrying
 * this test id exists somewhere in the document, including React's scratch space".
 *
 * Deliberately NOT fixed with `.first()`: that would silence the strict-mode
 * error while still allowing the assertion to bind to the hidden staged copy,
 * which is exactly the class of change that keeps passing through a real
 * regression.
 */
function tabBarOf(page: Page) {
  return page.getByRole("navigation", { name: "Primary" })
}

/**
 * Query root for the LIVE document, excluding React's streaming staging buffers.
 *
 * The buffer duplicates the **entire dashboard shell**, not just the tab bar —
 * so the sidebar wordmark, `<main>`, the shop switcher and everything else in
 * the shell are doubled too. Fixing only the tab-bar locator moved the failure
 * rather than removing it: a first pass here cut the strict-mode violations from
 * 8 to 7, and all 7 survivors were `getByText('OaaS Platform')`, the sidebar
 * subtitle. Every shell-scoped query therefore goes through this root.
 *
 * `body > div:not([hidden])` is the right shape because React appends its
 * staging buffers as DIRECT children of `<body>` carrying the `hidden`
 * attribute (`<div hidden id="S:0">`), while the real shell is a direct child
 * without it. This keeps `toBeHidden()` assertions meaningful: an element that
 * is in the live tree but CSS-hidden still matches, so the sidebar-is-hidden
 * check can still fail if the sidebar ever becomes visible at 390px.
 */
function live(page: Page) {
  return page.locator("body > div:not([hidden])")
}

/**
 * Perform the real vendor sign-in (SSO button → Keycloak, or a credentials
 * form if the deployment exposes one). Mirrors vendor-refund-flow.spec.ts.
 * Skips cleanly if no known sign-in affordance is present.
 */
async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
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
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
  // Let React hydrate before clicking — a click on `domcontentloaded` can land
  // before the onClick handler is attached and silently no-op (login hangs).
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()

  // A live Keycloak SSO cookie can skip the hosted form and land straight on
  // /dashboard — handle both arrivals. Retry once if the first click raced
  // hydration (we are still sitting on /auth/signin).
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

// The finance summary is an OBJECT, not a Page. The dashboard reads
// `financialSummary.vatBreakdown.map(...)` and the finance page reads the
// *Pennies fields, so the empty-Page catch-all shape makes them throw during
// render → the route error boundary ("Dashboard error"). Stub the real shape.
const financialSummaryResponse = {
  totalRevenuePennies: 0,
  totalExpensesPennies: 0,
  netAmountPennies: 0,
  totalVatPennies: 0,
  transactionCount: 0,
  vatBreakdown: [],
}

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

  // Finance summary (object shape) — must win over the empty-Page catch-all.
  await context.route(`${API}/api/v1/financial-transactions/summary`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(financialSummaryResponse) })
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
  // This is inherently a 390px phone-shell contract (bottom tab bar visible,
  // 256px sidebar hidden). Pin the viewport so it is exercised correctly under
  // BOTH the `mobile` and `desktop` Playwright projects — at a 1440px desktop
  // viewport the tab bar hides and the sidebar shows, which would be a false red.
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true })

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
      const tabBar = tabBarOf(page)
      await expect(tabBar).toBeVisible()

      // The 256px desktop sidebar is hidden below md — its unique "OaaS
      // Platform" wordmark subtitle must not be visible.
      await expect(live(page).getByText("OaaS Platform")).toBeHidden()

      // Title readability: the page <h1> is given room (not squeezed into a
      // one-word-per-line column) and does not overflow horizontally.
      if (route.titleHasH1) {
        const h1 = live(page).locator("main h1").first()
        await expect(h1).toBeVisible({ timeout: 10_000 })
        const metrics = await h1.evaluate((el) => {
          const cs = getComputedStyle(el)
          const lineHeight = parseFloat(cs.lineHeight) || el.clientHeight || 1
          const main = el.closest("main") as HTMLElement | null
          return {
            scrollWidth: el.scrollWidth,
            clientWidth: el.clientWidth,
            // How many text lines the title wraps to.
            lines: Math.max(1, Math.round(el.scrollHeight / lineHeight)),
            // Width of the content column the title lives in.
            mainWidth: main?.clientWidth ?? 0,
          }
        })
        // No forced horizontal overflow (+1px tolerance for sub-pixel rounding).
        expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
        // The title is NOT squeezed into a one-word-per-line column (the old bug
        // wrapped titles vertically inside a ~130px column stolen by the sidebar).
        expect(metrics.lines).toBeLessThanOrEqual(1)
        // The content column is wide — the sidebar is not stealing ~66% of the
        // 390px viewport any more. (h1 text width varies by title length, so we
        // measure the column, not the shrink-wrapped h1 in its flex header.)
        expect(metrics.mainWidth).toBeGreaterThanOrEqual(300)
      }
    })
  }

  test("/dashboard/onboarding is not regressed (known heading renders)", async ({ page }) => {
    await page.goto(`${BASE}/dashboard/onboarding`, { waitUntil: "domcontentloaded" })
    await expect(tabBarOf(page)).toBeVisible()
    // The onboarding page shows one of its known headings ("Take your shop
    // live" for the create form, or "Go live" for an in-progress record).
    await expect(
      page.getByRole("heading", { name: /take your shop live|go live/i }).first()
    ).toBeVisible({ timeout: 10_000 })
  })
})

/**
 * MOBL-01 375px regression — the requirement literally names a 375px viewport
 * spec. RESEARCH §7's live browser probe proved there is NO 375px occlusion
 * (docScrollWidth == 375, horizontalOverflow false, sidebar hidden, tab bar
 * visible); this locks that in AND proves the newly-mounted VSA-03 shop-context
 * switcher (top bar) reintroduces no horizontal overflow. NOT a new drawer.
 */
/**
 * The bar's own geometry, plus every descendant that escapes it.
 *
 * The bar is a fixed `h-14` (56px) `items-center` flex row, so a note laid out in
 * the switcher column does not merely spill past the border — it RE-CENTRES the
 * column and lifts the `<select>` off the top of the screen. Both directions
 * therefore matter, which is why this returns `top` as well as `bottom`.
 */
async function topbarGeometry(page: Page) {
  return page.evaluate(() => {
    const bar = document.querySelector(
      'body > div:not([hidden]) [data-testid="mobile-topbar"]'
    ) as HTMLElement | null
    if (!bar) return null
    const br = bar.getBoundingClientRect()
    const rel = (el: Element) => {
      const r = el.getBoundingClientRect()
      return {
        testid: el.getAttribute("data-testid") || el.id || el.tagName.toLowerCase(),
        top: +(r.top - br.top).toFixed(2),
        bottom: +(r.bottom - br.top).toFixed(2),
        h: +r.height.toFixed(2),
      }
    }
    return {
      barHeight: +br.height.toFixed(2),
      select: document.querySelector(
        'body > div:not([hidden]) [data-testid="mobile-topbar"] #shop-context-select'
      )
        ? rel(
            document.querySelector(
              'body > div:not([hidden]) [data-testid="mobile-topbar"] #shop-context-select'
            )!
          )
        : null,
      // Only elements that take part in layout — an `sr-only` note is clipped to
      // 1x1 and absolutely positioned, so it legitimately reports inside the bar.
      escaping: [...bar.querySelectorAll("*")]
        .map(rel)
        .filter((c) => c.bottom > br.height + 1 || c.top < -1),
    }
  })
}

test.describe("Dashboard mobile shell (375px) — MOBL-01 + switcher regression", () => {
  test.use({ viewport: { width: 375, height: 812 }, isMobile: true })

  test.beforeEach(async ({ context, page }) => {
    await setupStubs(context)
    // The catch-all above answers GET /api/v1/staff/me with an empty Page, so
    // `groupAdmin` reads false and the D-08 "Apply to all shops" badge — the
    // #495 defect — never renders. The real `admin-user` IS a group admin, so
    // this stub is the faithful shape, and it is registered LAST so it wins.
    await context.route(`${API}/api/v1/staff/me`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          userId: "00000000-0000-0000-0000-0000000000aa",
          groupAdmin: true,
          grantedShopIds: null,
        }),
      })
    )
    await vendorLogin(page)
  })

  test("no horizontal overflow; sidebar hidden, tab bar + switcher visible at 375px", async ({ page }) => {
    await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" })

    // The fixed bottom tab bar is the 375px nav.
    await expect(tabBarOf(page)).toBeVisible()
    // The 256px desktop sidebar is hidden below md (its "OaaS Platform" subtitle).
    await expect(live(page).getByText("OaaS Platform")).toBeHidden()
    // The shop-context switcher is reachable in the md:hidden mobile top bar
    // (the sidebar's copy is display:none below md, so scope to <main>).
    await expect(
      live(page).locator("main").getByTestId("shop-switcher").first()
    ).toBeVisible({ timeout: 10_000 })

    // No horizontal overflow — the whole document fits the 375px viewport
    // (RESEARCH §7: docScrollWidth == viewportWidth, +1px sub-pixel tolerance).
    const geom = await page.evaluate(() => ({
      docScrollWidth: document.documentElement.scrollWidth,
      viewportWidth: window.innerWidth,
      mainWidth: (document.querySelector("body > div:not([hidden]) main") as HTMLElement | null)?.clientWidth ?? 0,
    }))
    expect(geom.docScrollWidth).toBeLessThanOrEqual(geom.viewportWidth + 1)
    // Content spans (near) full width — the sidebar steals nothing at 375px.
    expect(geom.mainWidth).toBeGreaterThanOrEqual(300)

    // ---- #495 / #490: nothing in the fixed 56px bar escapes it ----------------
    // jsdom has no layout, so the Jest cases can only assert the mechanism
    // (`sr-only` in the topbar variant). THIS is the assertion about the actual
    // geometry, and it is the one that was failing: measured on main at 375px,
    // the "Apply to all shops" badge sat at bottom 59 against a 56px bar.
    await expect(live(page).locator("main #shop-context-select")).toBeVisible({
      timeout: 10_000,
    })
    const bar = await topbarGeometry(page)
    expect(bar).not.toBeNull()
    expect(bar!.barHeight).toBe(56)
    // The D-08 badge must be present, or this proves nothing about #495.
    await expect(live(page).locator("main [data-testid='apply-to-all']")).toHaveCount(1)
    expect(bar!.escaping).toEqual([])
    // …and the control itself is still inside the bar, not lifted above it.
    expect(bar!.select!.top).toBeGreaterThanOrEqual(0)
    expect(bar!.select!.bottom).toBeLessThanOrEqual(56)

    // ---- the same, in the D-13 stale state (#490) -----------------------------
    // Driven through the REAL seam: `shopContext` holding a shop the caller is no
    // longer granted is exactly what localStorage contains after a revocation.
    // Measured on main this put the notice's bottom at 95 and the <select> at
    // top -40 / bottom -2 — the control was entirely above the viewport.
    await page.evaluate(() =>
      localStorage.setItem("shopContext", "00000000-0000-0000-0000-0000deadbeef")
    )
    await page.reload({ waitUntil: "domcontentloaded" })
    await expect(live(page).locator("main #shop-context-select")).toBeVisible({
      timeout: 10_000,
    })
    await expect(
      live(page).locator("main [data-testid='shop-switcher-stale']")
    ).toHaveCount(1)
    const stale = await topbarGeometry(page)
    expect(stale!.escaping).toEqual([])
    expect(stale!.select!.top).toBeGreaterThanOrEqual(0)
    expect(stale!.select!.bottom).toBeLessThanOrEqual(56)
  })
})

/**
 * #286's 375px half, for ALL ELEVEN routes — additive to the 390px sweep above.
 *
 * The block before this one is the only 375px coverage that existed, and it
 * drives ONE route (`/dashboard`). The eleven-route sweep ran only at 390px. So
 * the issue's "375px" ask was satisfied for 1/11 of the surface it names.
 *
 * WHY A NEW BLOCK RATHER THAN MOVING THE PROJECT VIEWPORT. Changing
 * `playwright.config.ts`'s `mobile` project from 390x844 to 375 would satisfy
 * #286 in one line — and silently move every other mobile spec, the
 * `mobile-instrument-contract.spec.ts` assertions, and every mobile perf
 * baseline that was measured at 390. That trades a working, documented good for
 * a new one, which the Incremental Betterment Doctrine forbids. The viewport is
 * therefore pinned HERE, per-describe, exactly as the two blocks above do —
 * which is also what makes the block correct under BOTH Playwright projects: at
 * the desktop project's 1440px the tab bar hides and the sidebar shows, so an
 * unpinned viewport would be a false red there. `playwright.config.ts` is
 * deliberately NOT touched by this block; `check-playwright-mobile-contract.sh`
 * still guards its shape.
 *
 * WHY THE PRESENCE CONTROLS COME FIRST. A no-overflow assertion is satisfied
 * *trivially* by an empty page, a redirect to /auth/signin, and a 404 — nothing
 * wide cannot overflow. That is the exact vacuous shape this file's sibling
 * already documents (`dashboard-interface-corrections.spec.ts:15-20`: with the
 * API refusing, the switcher falls back to a zero-grant state and the count
 * reads 0 — "a pass on a page that is not working"). So each iteration asserts
 * the shell is really rendered — the Primary nav, and the page `<h1>` where the
 * route has one — BEFORE it measures geometry. Proven: pointing an iteration at
 * a route that does not exist turns the PRESENCE assertion red rather than
 * letting the geometry one pass over a page that is not the dashboard.
 *
 * WHY THIS COMPARES AGAINST `page.viewportSize()`, NOT `window.innerWidth` —
 * MEASURED, and it is the reason this block does not simply copy the assertion
 * from the block above. Under `isMobile: true` Chromium emulates a phone's
 * LAYOUT VIEWPORT: when content is wider than the device width the page zooms
 * out to fit, and `window.innerWidth` GROWS to match the content. So
 * `docScrollWidth <= window.innerWidth + 1` compares a number against itself and
 * **cannot fail**. Measured 2026-08-28 on /dashboard/kitchen by appending a
 * deliberate 1200px-wide div before the read:
 *
 *   {"docScrollWidth":1200,"bodyScrollWidth":1200,"innerWidth":1200,
 *    "htmlOverflowX":"visible","bodyOverflowX":"visible","injectedWidth":1200}
 *
 * — a 1200px overflow on a 375px phone, and the assertion PASSED (1 passed,
 * rc=0). `page.viewportSize()!.width` is the width Playwright was configured
 * with (375) and does not move, so the same injection now reads
 * `expect(received).toBeLessThanOrEqual(expected)  expected: 376  received: 1200`.
 * The widening of the layout viewport is itself the user-visible symptom (the
 * page shrinks to fit), so it is asserted directly as well.
 *
 * NOTE for whoever touches the single-route 375px block above: its overflow
 * line (`geom.docScrollWidth <= geom.viewportWidth + 1`) has this same vacuous
 * shape and is left alone here only because it is out of this plan's scope. Its
 * OTHER assertions — the 56px top-bar geometry and the escaping-element list —
 * are genuinely falsifiable and were measured red on main.
 *
 * No `@desktop-only` / `@mobile-only` tag, deliberately: the 390px and 375px
 * blocks above are enumerated by both projects by design, and consistency with
 * them is worth more than halving the run.
 */
test.describe("Dashboard mobile shell (375px) — the eleven-route sweep has no horizontal overflow", () => {
  test.use({ viewport: { width: 375, height: 812 }, isMobile: true })

  test.beforeEach(async ({ context, page }) => {
    // Same order as the 390px sweep: stub the API data first (no effect on the
    // Keycloak login origin), then perform the real vendor sign-in so the
    // server-side dashboard auth gate lets us through.
    await setupStubs(context)
    await vendorLogin(page)
  })

  for (const route of ROUTES) {
    test(`${route.name} (${route.path}) has no horizontal overflow at 375px`, async ({ page }) => {
      await page.goto(`${BASE}${route.path}`, { waitUntil: "domcontentloaded" })

      // ---- PRESENCE CONTROL: the page is actually rendered ---------------------
      // Without this, every assertion below passes on a blank page.
      await expect(tabBarOf(page)).toBeVisible({ timeout: 10_000 })
      if (route.titleHasH1) {
        await expect(live(page).locator("main h1").first()).toBeVisible({
          timeout: 10_000,
        })
      }

      // ---- the measurement -----------------------------------------------------
      // The width Playwright was CONFIGURED with — a fixed 375 that no amount of
      // page content can move. See the docblock: window.innerWidth can, which is
      // what makes the innerWidth form of this assertion unfalsifiable.
      const configuredWidth = page.viewportSize()!.width
      const geom = await page.evaluate(() => ({
        docScrollWidth: document.documentElement.scrollWidth,
        layoutViewportWidth: window.innerWidth,
        mainWidth:
          (document.querySelector("body > div:not([hidden]) main") as HTMLElement | null)
            ?.clientWidth ?? 0,
      }))
      // No horizontal overflow (+1px tolerance for sub-pixel rounding).
      expect(geom.docScrollWidth).toBeLessThanOrEqual(configuredWidth + 1)
      // The layout viewport did not widen — i.e. the phone did not zoom out to
      // fit oversized content, which is what the user actually sees.
      expect(geom.layoutViewportWidth).toBeLessThanOrEqual(configuredWidth + 1)
      // …and the content column still spans (near) the full width — the sidebar
      // steals nothing at 375px. A collapsed column would satisfy the overflow
      // assertion while being unusable.
      expect(geom.mainWidth).toBeGreaterThanOrEqual(300)
    })
  }
})

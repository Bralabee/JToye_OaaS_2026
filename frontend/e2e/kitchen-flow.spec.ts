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

import { test, expect, type Page } from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

// Honour PLAYWRIGHT_BASE_URL (set it only for a non-default host). Mirrors playwright.config.ts.
const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

// Keycloak dev-realm vendor. `admin-user` is the live jtoye-dev account; the
// credential comes from e2e/vendor-credentials.ts (never committed). A fake session
// cookie no longer passes the server-side dashboard auth gate (NextAuth middleware,
// #89), so this spec performs the genuine SSO login like dashboard-mobile.spec.ts.

async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  // #106: this used to `test.skip(true, "No sign-in method found …")` when the button
  // was absent, which turns a genuine sign-in regression — the page failing to render
  // its only auth control — into a silent green skip. A missing sign-in button IS the
  // failure, so it fails, and it names what to look at.
  //
  // The skip that remains is `skipWithoutVendorPassword()` above, which fires only on
  // a MISSING CREDENTIAL (an environment fact, not a product fact) and is the one the
  // skip-budget gate declares.
  await expect(
    ssoButton,
    "no 'Sign in with Keycloak' button on /auth/signin — the sign-in page is broken, " +
      "or the auth flow changed and this spec needs updating"
  ).toHaveCount(1)
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
  // Let React hydrate before clicking — a click on `domcontentloaded` can land
  // before the onClick handler is attached and silently no-op (login hangs).
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()
  // A live SSO cookie may skip the hosted form and land straight on /dashboard.
  // Retry once if the first click raced hydration (still on /auth/signin).
  try {
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 25_000 })
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
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
}

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

/**
 * A paging-honest fake page (#485). The live API clamps `size` at 100 — measured
 * 2026-08-04: `?size=500` against a 125-order shop returned 100 rows with
 * `size: 100`. Reproducing the clamp here means a "fix" that only asks for a bigger
 * page cannot pass this spec.
 */
const SERVER_MAX_PAGE_SIZE = 100
function pageOf<T>(rows: T[], url: string) {
  const q = new URL(url).searchParams
  const page = Number(q.get("page") ?? 0)
  const size = Math.min(Number(q.get("size") ?? 20), SERVER_MAX_PAGE_SIZE)
  const start = page * size
  const content = rows.slice(start, start + size)
  const totalPages = Math.max(1, Math.ceil(rows.length / size))
  return {
    content,
    totalElements: rows.length,
    totalPages,
    size,
    number: page,
    first: page === 0,
    last: page + 1 >= totalPages,
  }
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
  test.beforeEach(async ({ context, page }) => {
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
    // #485: the board pages now, so the fake HONOURS ?page= and ?size=. A stub that
    // ignored them would return everything on page 0 and pass against the bug.
    await context.route(`${API}/api/v1/orders?**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(orderSummaryResponse.content, route.request().url())),
      })
    )
    await context.route(`${API}/api/v1/orders/*/start-preparation`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ ...orderDetailResponse, status: "PREPARING" }),
      })
    )
    await context.route(`${API}/api/v1/orders/*/detail`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(orderDetailResponse),
      })
    )

    // Perform the genuine vendor SSO login so the server-side dashboard auth
    // gate (NextAuth middleware) lets us through. The /api/v1 route stubs above
    // are client-side and never touch the Keycloak login origin, so the mocked
    // order data still drives the rendered kitchen/order-detail views.
    await vendorLogin(page)
  })

  test("kitchen display shows real product names, a clean badge, and capped elapsed time", async ({
    page,
  }) => {
    // SSE/STOMP never idles the network — wait on the DOM, then on elements.
    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })

    // Header
    await expect(page.getByRole("heading", { name: /Kitchen Display/i })).toBeVisible()

    // Shop selector trigger is rendered.
    //
    // Anchored to the trigger's ROLE, not to its text (#404). Radix `Select`
    // renders a visually-hidden native <select> for a11y alongside the visible
    // trigger, so `getByText(/Select shop|Test Shop/i).first()` resolved to
    // `<option value="shop-1">Test Shop</option>` — an element that is hidden BY
    // DESIGN and can therefore never satisfy toBeVisible(). The failure read
    // `Received: hidden` and looked like a rendering defect; the page was fine.
    //
    // Strictly stronger than the original: this asserts the trigger is visible AND
    // carries the expected label, which is what the text matcher was reaching for
    // but could not express. There is exactly one Select on this page (page.tsx:419).
    const shopSelector = page.getByRole("combobox").first()
    await expect(shopSelector).toBeVisible()
    await expect(shopSelector).toHaveText(/Select shop|Test Shop/i)

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

  // ---------------------------------------------------------------------------
  // #106 — the KDS e2e now BUMPS, and exercises a real disconnect and recovery.
  //
  // The issue's own words: "The KDS e2e never clicks a bump button and the reconnect
  // spec is opt-in-skipped." Everything below clicks.
  // ---------------------------------------------------------------------------

  test("clicking bump advances the ticket and posts the transition", async ({ page }) => {
    const bumpCalls: string[] = []
    await page.route(`${API}/api/v1/orders/*/start-preparation`, (route) => {
      bumpCalls.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ ...orderDetailResponse, status: "PREPARING" }),
      })
    })

    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })
    const bump = page.getByRole("button", { name: /Start Preparing/i })
    await expect(bump).toBeVisible()

    await bump.click()

    // The optimistic update moves the card to the next stage, and the POST really fired.
    await expect(page.getByRole("button", { name: /Mark Ready/i })).toBeVisible()
    await expect(page.getByText("Preparing")).toBeVisible()
    // POLLED, not read once: the card advances OPTIMISTICALLY, i.e. before the POST is
    // even issued, so a bare `expect(bumpCalls.length)` here is a race. Measured — it
    // won on desktop and lost on mobile in the same run.
    await expect.poll(() => bumpCalls.length, { timeout: 10_000 }).toBeGreaterThan(0)
    expect(bumpCalls[0]).toContain("/order-1/start-preparation")
  })

  test("going offline raises a stale banner with a last-updated stamp, and coming back clears it", async ({
    page,
    context,
  }) => {
    // THIS test alone runs against the live stack, with every stub lifted.
    //
    // A recovery assertion needs a socket that was genuinely up first, and the STOMP
    // topic is derived from the data: with the fixture shop the page subscribes to
    // `/topic/kitchen.tenant-1.shop-1`, a tenant that does not exist, and the relay
    // drops the connection — the pill sat on "Reconnecting" forever (measured). Real
    // shop, real tenant, real topic, real socket.
    //
    // The spec already requires the live stack (it performs a real SSO login), so this
    // adds no new dependency, and if the relay is down the test goes RED — the correct
    // outcome for a test of the live order feed.
    await context.unroute("**/ws**")
    await context.unroute(`${API}/api/v1/shops**`)
    await context.unroute(`${API}/api/v1/orders?**`)
    await context.unroute(`${API}/api/v1/orders/*/detail`)

    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })

    // ONLINE: a pill reading Live, with a real wall clock, and no banner.
    const pill = page.getByTestId("kds-feed-pill")
    await expect(pill).toBeVisible()
    await expect(pill).toContainText(/\d{2}:\d{2}:\d{2}/)
    await expect(pill).toContainText("Live", { timeout: 20_000 })
    await expect(page.getByTestId("kds-feed-banner")).toHaveCount(0)

    // OFFLINE — induced for real. `context.route()` cannot do this: it does not
    // intercept WebSocket handshakes, so aborting a ws glob leaves the STOMP client
    // connected and the page still reading "Connected" (measured).
    await context.setOffline(true)

    const banner = page.getByTestId("kds-feed-banner")
    await expect(banner).toBeVisible({ timeout: 15_000 })
    await expect(banner).toHaveAttribute("role", "alert")
    await expect(banner).toContainText(/Offline|Not updating|out of date/i)
    // The stamp the board had none of before #106.
    await expect(banner).toContainText(/Last updated \d{2}:\d{2}:\d{2}/)
    await expect(page.getByTestId("kds-stale-age")).toBeVisible()
    await expect(page.getByRole("button", { name: /refresh now/i })).toBeVisible()

    // RECOVERY: the banner must go away on its own. A warning that outlives its cause
    // is how a kitchen learns to ignore warnings.
    await context.setOffline(false)
    await expect(banner).toHaveCount(0, { timeout: 20_000 })
    await expect(pill).toContainText("Live")
  })

  // ---------------------------------------------------------------------------
  // #105 — a kitchen ticket can be printed, and it is the PRINT stylesheet that
  // makes it a ticket. Asserted under `emulateMedia({ media: "print" })`, because a
  // screen screenshot says nothing about `@media print`.
  // ---------------------------------------------------------------------------

  test("a ticket can be printed, and the print stylesheet hides the dashboard chrome", async ({
    page,
  }) => {
    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })
    await expect(page.getByText("Alice")).toBeVisible()

    // With no ticket queued, printing must still yield the BOARD rather than a blank
    // page — the `body:has(#kds-print-root)` guard in globals.css. Falsifiable: drop
    // the guard and the app root goes `display: none` here.
    await page.emulateMedia({ media: "print" })
    expect(await page.locator("#kds-print-root").count()).toBe(0)
    expect(
      await page.evaluate(() =>
        [...document.body.children]
          .filter((el) => el.tagName === "DIV")
          .every((el) => getComputedStyle(el).display === "none")
      )
    ).toBe(false)
    await page.emulateMedia({ media: "screen" })

    // Print one ticket.
    await page.getByRole("button", { name: /^Print ticket/i }).first().click()
    const sheet = page.locator("#kds-print-root")
    await expect(sheet).toHaveCount(1)
    // Invisible on screen — the sheet must never leak into the board.
    expect(await sheet.evaluate((el) => getComputedStyle(el).display)).toBe("none")

    await page.emulateMedia({ media: "print" })
    const printed = await page.evaluate(() => {
      const root = document.getElementById("kds-print-root")!
      const ref = document.querySelector(".kds-ticket__ref")!
      return {
        rootDisplay: getComputedStyle(root).display,
        rootWidth: getComputedStyle(root).width,
        refText: ref.textContent,
        refFontSize: getComputedStyle(ref).fontSize,
        // Every ordinary <body> child must be gone; only the sheet survives.
        appChromeHidden: [...document.body.children]
          .filter((el) => el.id !== "kds-print-root" && el.tagName === "DIV")
          .every((el) => getComputedStyle(el).display === "none"),
      }
    })
    expect(printed.rootDisplay).toBe("block")
    expect(printed.appChromeHidden).toBe(true)
    expect(printed.refText).toBe("ORD-TEST-0001")
    // 72mm at 96dpi = 272.126px — the printable width of an 80mm thermal roll.
    expect(parseFloat(printed.rootWidth)).toBeGreaterThan(270)
    expect(parseFloat(printed.rootWidth)).toBeLessThan(275)
    // 22pt = 29.33px. The order reference is the thing read from a rail.
    expect(parseFloat(printed.refFontSize)).toBeGreaterThan(28)

    await expect(page.getByTestId("kitchen-ticket")).toHaveCount(1)
    await expect(page.locator(".kds-ticket__qty").first()).toHaveText("2×")
    await page.emulateMedia({ media: "screen" })
  })

  // ---------------------------------------------------------------------------
  // #450 sub-item 5d — the board says whose tickets it is showing.
  // ---------------------------------------------------------------------------

  test("the board names its shop, and explains itself in the All-shops context", async ({
    page,
  }) => {
    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })
    await expect(page.getByTestId("kds-board-shop")).toContainText(
      "Showing tickets for Test Shop"
    )

    // The fixture tenant has exactly one shop, so there is no mismatch to explain and
    // the notice must NOT appear. Asserting the quiet case as well as the loud one is
    // what stops the notice becoming permanent furniture.
    await page.evaluate(() => window.localStorage.setItem("shopContext", "all"))
    await page.reload({ waitUntil: "domcontentloaded" })
    await expect(page.getByTestId("kds-board-shop")).toBeVisible()
    const notice = page.getByTestId("kds-all-shops-notice")
    await expect(notice).toContainText("one shop at a time")
    await expect(notice).not.toContainText("not on this screen")
  })

  // ---------------------------------------------------------------------------
  // #485 (kitchen/page.tsx:229) — a live ticket past the first page still boards.
  // ---------------------------------------------------------------------------

  test("a kitchen ticket that exists only on page 1 still reaches the board", async ({
    page,
  }) => {
    // 125 orders; the only CONFIRMED one is at index 110, i.e. page 1 at size 100.
    // Before #485 the board issued one `?size=100` and this ticket never rendered.
    const deep = Array.from({ length: 125 }, (_, i) => ({
      ...orderSummaryResponse.content[0],
      id: i === 110 ? "order-1" : `bulk-${i}`,
      status: i === 110 ? "CONFIRMED" : "COMPLETED",
    }))
    const requested: string[] = []
    await page.route(`${API}/api/v1/orders?**`, (route) => {
      requested.push(route.request().url())
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(deep, route.request().url())),
      })
    })

    await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })

    await expect(page.getByText("ORD-TEST-0001")).toBeVisible({ timeout: 20_000 })
    expect(requested.some((u) => /[?&]page=1\b/.test(u))).toBe(true)
    // And it stops when the server says so — a fix that pages forever is another bug.
    expect(requested.some((u) => /[?&]page=2\b/.test(u))).toBe(false)
  })
})

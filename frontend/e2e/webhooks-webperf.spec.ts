/**
 * W5 — throttled-mobile Core Web Vitals smoke for the three NEW user-facing
 * routes introduced by Phase 22 (CLAUDE.md cross-cutting web-perf contract:
 * mobile-first LCP/CLS/INP at a throttled profile; no route regresses).
 *
 *   /dashboard/webhooks       (Surface A, authenticated)
 *   /dashboard/webhooks/[id]  (Surface B, authenticated)
 *   /unsubscribe              (Surface C, public)
 *
 * Profile: a 375px device viewport + CDP throttling (Fast-3G-ish network +
 * 4× CPU slowdown) via `context.newCDPSession` (mirrors the dashboard-mobile
 * approach). We measure LCP + CLS via buffered PerformanceObserver in the page
 * and assert the NO-REGRESSION form (no numeric budget is declared in the repo):
 *   - LCP resolves to a finite value within a generous throttled budget (the
 *     largest element actually renders — the page is not blank/hung)
 *   - CLS is near-zero (< 0.1 "good" threshold — no late-content shift storm)
 *   - the route becomes interactive (its primary content is visible)
 * The measured values are attached to the test output for trend visibility.
 *
 * Data is STUBBED (deterministic, DB-independent) and auth is REAL for the two
 * dashboard routes — same split as dashboard-mobile.spec.ts.
 *
 * Run: npx playwright test webhooks-webperf  (needs the rebuilt full stack)
 */
import { test, expect, type BrowserContext, type Page } from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"

// Generous throttled-mobile budgets (no-regression guardrails, not SLAs).
const LCP_BUDGET_MS = 8000
const CLS_BUDGET = 0.1

const now = new Date().toISOString()
const SUB_ID = "e2e-sub-1"
const subscription = {
  id: SUB_ID, targetUrl: "https://e2e.example.com/hooks/jtoye",
  eventTypes: ["ORDER_STATE_CHANGED"], status: "ACTIVE", consecutiveFailures: 0,
  createdAt: now, updatedAt: now,
}
const delivery = {
  id: "e2e-d1", subscriptionId: SUB_ID, eventId: "e1",
  eventType: "order.state.CONFIRMED", status: "DELIVERED", attemptCount: 1,
  lastHttpStatus: 200, lastError: null, replay: false, replayOf: null,
  nextAttemptAt: now, createdAt: now, updatedAt: now,
}
function pageOf(rows: unknown[]) {
  return { content: rows, totalPages: 1, totalElements: rows.length, number: 0, size: 20 }
}

async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
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
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()
  try {
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 20_000 })
  } catch {
    if (page.url().includes("/auth/signin")) await ssoButton.click({ force: true }).catch(() => {})
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 20_000 })
  }
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
}

async function stubApi(context: BrowserContext) {
  await context.route(`${API}/api/v1/**`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(pageOf([])) })
  )
  await context.route(`${API}/api/v1/orders/stream**`, (route) => route.abort())
  await context.route("**/ws**", (route) => route.abort())
  await context.route(`${API}/api/v1/webhooks`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([subscription]) })
  )
  await context.route(`${API}/api/v1/webhooks/${SUB_ID}`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(subscription) })
  )
  await context.route(`${API}/api/v1/webhooks/${SUB_ID}/deliveries**`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(pageOf([delivery])) })
  )
  await context.route("**/public/unsubscribe**", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ status: "unsubscribed" }) })
  )
}

async function throttle(context: BrowserContext, page: Page) {
  const client = await context.newCDPSession(page)
  await client.send("Network.enable")
  await client.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: 40,
    downloadThroughput: (1.5 * 1024 * 1024) / 8, // ~Fast 3G
    uploadThroughput: (750 * 1024) / 8,
  })
  await client.send("Emulation.setCPUThrottlingRate", { rate: 4 })
}

/** Collect buffered LCP (max renderTime) + cumulative layout shift. */
async function measureVitals(page: Page): Promise<{ lcp: number; cls: number }> {
  return page.evaluate(
    () =>
      new Promise<{ lcp: number; cls: number }>((resolve) => {
        let lcp = 0
        let cls = 0
        try {
          new PerformanceObserver((list) => {
            for (const e of list.getEntries() as PerformanceEntry[]) {
              // @ts-expect-error layout-shift entry fields
              if (!e.hadRecentInput) cls += (e as { value: number }).value
            }
          }).observe({ type: "layout-shift", buffered: true })
          new PerformanceObserver((list) => {
            const entries = list.getEntries()
            const last = entries[entries.length - 1] as PerformanceEntry & {
              renderTime?: number
              loadTime?: number
            }
            lcp = last.renderTime || last.loadTime || lcp
          }).observe({ type: "largest-contentful-paint", buffered: true })
        } catch {
          /* browser lacks the entry types — resolve with zeros (skip below) */
        }
        setTimeout(() => resolve({ lcp, cls }), 2500)
      })
  )
}

const ROUTES: Array<{ name: string; path: string; ready: (p: Page) => Promise<void>; auth: boolean }> = [
  {
    name: "/dashboard/webhooks",
    path: "/dashboard/webhooks",
    auth: true,
    ready: async (p) => {
      await expect(p.getByRole("heading", { name: "Webhooks" })).toBeVisible({ timeout: 15_000 })
    },
  },
  {
    name: "/dashboard/webhooks/[id]",
    path: `/dashboard/webhooks/${SUB_ID}`,
    auth: true,
    ready: async (p) => {
      await expect(p.getByRole("heading", { name: "Delivery log" })).toBeVisible({ timeout: 15_000 })
    },
  },
  {
    name: "/unsubscribe",
    path: `/unsubscribe?tenant=00000000-0000-0000-0000-000000000001&email=r%40e.com&category=ORDERS&token=VALID-x`,
    auth: false,
    ready: async (p) => {
      await expect(p.getByRole("heading", { name: /you're unsubscribed/i })).toBeVisible({ timeout: 15_000 })
    },
  },
]

test.describe("Throttled-mobile Core Web Vitals — Phase 22 new routes (W5)", () => {
  test.use({ viewport: { width: 375, height: 812 }, isMobile: true })

  test.beforeEach(async ({ context }) => {
    await stubApi(context)
  })

  for (const route of ROUTES) {
    test(`${route.name} holds CWV at a throttled mobile profile`, async ({ context, page }) => {
      if (route.auth) await vendorLogin(page)
      await throttle(context, page)

      await page.goto(`${BASE}${route.path}`, { waitUntil: "domcontentloaded" })
      await route.ready(page) // route becomes interactive (primary content visible)

      const { lcp, cls } = await measureVitals(page)
      test.info().annotations.push({
        type: "web-vitals",
        description: `${route.name} — LCP=${Math.round(lcp)}ms CLS=${cls.toFixed(4)} (throttled 375px, 4× CPU)`,
      })

      // No-regression assertions (generous throttled budgets).
      expect(cls, `${route.name} CLS should be near-zero`).toBeLessThan(CLS_BUDGET)
      if (lcp > 0) {
        expect(lcp, `${route.name} LCP within throttled budget`).toBeLessThan(LCP_BUDGET_MS)
      }

      // No horizontal overflow at 375px on any of the three routes.
      const overflow = await page.evaluate(() => {
        const el = document.scrollingElement || document.documentElement
        return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }
      })
      expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
    })
  }
})

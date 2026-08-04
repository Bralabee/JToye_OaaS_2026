/**
 * #450 (items 1, 2, 5b, 5c) + #454 — the four dashboard interface corrections
 * where the system was already right and only the UI misrepresented it, plus the
 * CLS breach on /dashboard/staff.
 *
 * Every case here is written so it FAILS on the pre-fix tree, and each one was
 * run in that direction against the live app on 2026-08-04:
 *
 *   duplicate id   `#shop-context-select` -> 2 nodes on all 13 dashboard routes
 *   titles         all 13 -> "J'Toye OaaS - Multi-Tenant Order Management"
 *   onboarding     the switcher mounted, and a switch fired 0 API calls
 *   staff CLS      0.1805 / 0.1805 at 390px + Fast-3G + 4x CPU (budget 0.1)
 *
 * AUTH + DATA ARE REAL. The council's own numbers were reproduced only once the
 * API calls actually succeeded: with the vendor signed in but the API refusing
 * the request, the switcher falls back to its zero-grant state, `<select>` never
 * renders, and `#shop-context-select` reads 0 — a "pass" on the duplicate-id
 * assertion over a page that is not working. So this spec asserts the control is
 * PRESENT before counting it.
 *
 * Run: npx playwright test dashboard-interface-corrections
 */
import { test, expect, type Page } from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

/** The repo's declared throttled-mobile budget (webhooks-webperf.spec.ts:37). */
const CLS_BUDGET = 0.1

/** Shop-scoped surfaces — the switcher belongs on these. */
const SWITCHER_ROUTES = [
  { path: "/dashboard", title: "Dashboard — J'Toye OaaS" },
  { path: "/dashboard/products", title: "Products — J'Toye OaaS" },
  { path: "/dashboard/orders", title: "Orders — J'Toye OaaS" },
  { path: "/dashboard/staff", title: "Staff & access — J'Toye OaaS" },
  { path: "/dashboard/shops", title: "Shops — J'Toye OaaS" },
  { path: "/dashboard/finance", title: "Finance — J'Toye OaaS" },
]

/** Per-tenant surfaces — the switcher is deliberately absent (#450 item 1). */
const TENANT_ROUTES = [
  { path: "/dashboard/onboarding", title: "Go live — J'Toye OaaS" },
  { path: "/dashboard/onboarding/approvals", title: "Approvals — J'Toye OaaS" },
]

async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto("/auth/signin", { waitUntil: "domcontentloaded" })
  const sso = page.getByRole("button", { name: /sign in with keycloak/i })
  await sso.waitFor({ state: "visible", timeout: 15_000 })
  await sso.click()
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 30_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
}

/**
 * The LIVE app tree.
 *
 * Next keeps the outgoing shell in the document, marked `hidden`, while a
 * navigation settles — so `document` can briefly hold TWO complete dashboard
 * shells and four switcher mounts. Counting `#shop-context-select` across the
 * whole document therefore reports 2 on a correctly-fixed build, which would
 * make the duplicate-id assertion below fail for a reason that has nothing to do
 * with the defect. `e2e/dashboard-mobile.spec.ts` scopes to the same selector,
 * for the same reason.
 */
const LIVE = "body > div:not([hidden])"

/**
 * The dashboard chrome has settled: both switcher mounts have resolved past
 * their loading skeleton.
 *
 * Visibility is asserted on the TOP BAR mount specifically. The sidebar copy is
 * first in DOM order but `hidden md:flex`, so a `.first()` visibility wait is
 * unsatisfiable at a phone viewport — it fails on a perfectly healthy page.
 */
async function switcherSettled(page: Page) {
  await expect(
    page.locator(`${LIVE} [data-testid="mobile-topbar"] [data-testid="shop-switcher"]`)
  ).toBeVisible({ timeout: 20_000 })
  await expect(
    page.locator(`${LIVE} select[id^='shop-context-select']`)
  ).toHaveCount(2, { timeout: 20_000 })
}

/**
 * `@mobile-only` — the desktop project's `grepInvert` skips this file, and that
 * is deliberate rather than a coverage gap. Every block here pins its own 390px
 * `isMobile` viewport, so running them again under the desktop project measures
 * the same thing twice; what it does NOT duplicate is the load on a shared API
 * that rate-limits at 100 requests/minute per tenant. Measured 2026-08-04:
 * running both projects back to back exhausted the bucket, the switcher fell
 * back to its zero-grant state, and the duplicate-id block failed for a reason
 * that had nothing to do with the code under test.
 */
test.describe("Dashboard interface corrections (#450) @mobile-only", () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true })

  test.beforeEach(async ({ page }) => {
    await vendorLogin(page)
  })

  test("5b — #shop-context-select identifies exactly one node per route", async ({ page }) => {
    for (const { path } of SWITCHER_ROUTES) {
      await page.goto(path, { waitUntil: "domcontentloaded" })
      await switcherSettled(page)

      const state = await page.evaluate((live) => {
        const root = document.querySelector(live)
        if (!root) return null
        return {
          // The control has to be in its <select> state, or the count below is 0
          // for the wrong reason and the assertion passes over a dead page.
          selects: root.querySelectorAll("select[id^='shop-context-select']").length,
          duplicates: root.querySelectorAll("#shop-context-select").length,
          labels: [...root.querySelectorAll("label[for^='shop-context-select']")].map(
            (l) => l.getAttribute("for")
          ),
        }
      }, LIVE)

      expect(state, `${path}: the live app tree was found`).not.toBeNull()
      expect(state!.selects, `${path}: both switcher mounts render a <select>`).toBe(2)
      expect(state!.duplicates, `${path}: #shop-context-select is unique`).toBe(1)
      // Two labels, two distinct targets, and each target exists.
      expect(state!.labels).toHaveLength(2)
      expect(new Set(state!.labels).size, `${path}: labels target distinct ids`).toBe(2)
      for (const id of state!.labels) {
        await expect(page.locator(`${LIVE} select#${id}`)).toHaveCount(1)
      }
    }
  })

  test("5c — every dashboard route carries its own <title>", async ({ page }) => {
    const seen: string[] = []
    for (const { path, title } of [...SWITCHER_ROUTES, ...TENANT_ROUTES]) {
      await page.goto(path, { waitUntil: "domcontentloaded" })
      await expect(page).toHaveTitle(title, { timeout: 15_000 })
      seen.push(await page.title())
    }
    // The pre-fix defect was not "a wrong title" but "one title everywhere", so
    // the load-bearing assertion is that they are all different from each other.
    expect(new Set(seen).size).toBe(seen.length)
    // …and none of them is the root layout's fallback.
    for (const t of seen) expect(t).not.toBe("J'Toye OaaS - Multi-Tenant Order Management")
  })

  test("1 — the onboarding sub-tree does not mount the shop switcher", async ({ page }) => {
    for (const { path } of TENANT_ROUTES) {
      await page.goto(path, { waitUntil: "domcontentloaded" })
      // Wait for the page itself, so "not found" cannot mean "not rendered yet".
      await expect(page.locator(`${LIVE} main h1`).first()).toBeVisible({
        timeout: 20_000,
      })
      await expect(page.locator(`${LIVE} [data-testid="shop-switcher"]`)).toHaveCount(0)
      await expect(page.locator(`${LIVE} select[id^='shop-context-select']`)).toHaveCount(0)
    }

    // Control arm — without it this passes on a build that dropped the switcher
    // everywhere, which is a regression, not a fix.
    await page.goto("/dashboard/products", { waitUntil: "domcontentloaded" })
    await switcherSettled(page)
    await expect(page.locator(`${LIVE} [data-testid="shop-switcher"]`)).toHaveCount(2)
  })

  test("2 — the staff page does not promise an invite it cannot send", async ({ page }) => {
    await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("heading", { name: "Staff & access" })).toBeVisible({
      timeout: 20_000,
    })

    await expect(page.getByText(/invite them to log in/i)).toHaveCount(0)
    await expect(
      page.getByRole("button", { name: /invite/i }).or(page.getByRole("link", { name: /invite/i }))
    ).toHaveCount(0)
    await expect(page.getByText(/signed in once with their own/i)).toBeVisible()
    await expect(page.getByText(/cannot send them an invite/i)).toBeVisible()
  })
})

/**
 * #454 — CLS at the repository's OWN declared profile and budget. Deliberately
 * not relaxed: the acceptance criterion forbids moving the budget to meet the
 * measurement.
 */
test.describe("Throttled-mobile CLS — /dashboard/staff (#454) @mobile-only", () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true })

  test("holds CLS under the 0.1 budget at 390px / Fast-3G / 4x CPU", async ({
    context,
    page,
  }) => {
    await vendorLogin(page)

    const client = await context.newCDPSession(page)
    await client.send("Network.enable")
    await client.send("Network.emulateNetworkConditions", {
      offline: false,
      latency: 40,
      downloadThroughput: (1.5 * 1024 * 1024) / 8, // ~Fast 3G
      uploadThroughput: (750 * 1024) / 8,
    })
    await client.send("Emulation.setCPUThrottlingRate", { rate: 4 })

    await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })

    const { cls, shifts } = await page.evaluate(
      () =>
        new Promise<{ cls: number; shifts: number }>((resolve) => {
          let cls = 0
          let shifts = 0
          new PerformanceObserver((list) => {
            for (const e of list.getEntries()) {
              // @ts-expect-error layout-shift entry fields
              if (!e.hadRecentInput) {
                cls += (e as unknown as { value: number }).value
                shifts += 1
              }
            }
          }).observe({ type: "layout-shift", buffered: true })
          setTimeout(() => resolve({ cls, shifts }), 4000)
        })
    )

    test.info().annotations.push({
      type: "web-vitals",
      description: `/dashboard/staff — CLS=${cls.toFixed(4)} over ${shifts} shift(s) (390px, Fast-3G, 4x CPU)`,
    })

    // The page really did settle — a blank route also scores 0.
    await expect(page.getByRole("heading", { name: "Staff & access" })).toBeVisible({
      timeout: 20_000,
    })
    await expect(page.getByText("Team directory")).toBeVisible()

    expect(cls, "/dashboard/staff CLS is within the repo's declared budget").toBeLessThan(
      CLS_BUDGET
    )
  })
})

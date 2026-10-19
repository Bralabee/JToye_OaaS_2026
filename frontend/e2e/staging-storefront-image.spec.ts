/**
 * DPLY-01 / D-34(b) — a photo a vendor uploaded through the safe media pipeline
 * renders on the PUBLIC staging storefront (plan 29-29).
 *
 * WHAT IT PROVES, in order, as an anonymous visitor:
 *   1. the shop page answers 200 and lists the named dish (TITLE_VISIBLE);
 *   2. throttled Core Web Vitals for the page, measured BEFORE any scrolling so
 *      the pre-fix and post-fix runs record the same window (CWV project=...);
 *   3. the dish card renders a real <img> named by the dish title — not the
 *      SafeImage placeholder, which is what the storefront shows when the menu
 *      carries no image (NO_MEDIA_IMG);
 *   4. that image's src is the pipeline's WebP derivative of the named asset
 *      over https, never a quarantine object (MEDIA_SRC_MISMATCH);
 *   5. the browser actually decoded it: complete with naturalWidth > 0
 *      (naturalWidth=, naturalHeight=, src=);
 *   6. the page's Product JSON-LD for the dish carries the same image
 *      (JSONLD_PRODUCT_MISSING, JSONLD_IMAGE_MISMATCH, JSONLD_IMAGE).
 *
 * NEVER IN A DEFAULT RUN (D-27). playwright.config.ts ignores staging-*.spec.ts
 * unless STAGING_E2E is exactly "1", so local, CI and nightly runs never reach a
 * public host. Even when collected, the test SKIPS unless PLAYWRIGHT_BASE_URL is
 * exactly the staging app host: there is deliberately no fallback here (the
 * config is the only base-URL authority, scripts/check-e2e-baseurl-contract.sh).
 *
 * ANONYMOUS ON PURPOSE. The defect (29-14, WINDOWS #50) was that the vendor's
 * dashboard showed the photo while the public menu did not; a signed-in session
 * would prove the wrong thing. The storage state is empty and no credential is
 * read, so the failure output may print page text freely.
 *
 * INPUTS (all required; a missing or malformed one FAILS, because a skip would
 * read as "not applicable"): STAGING_SHOP_SLUG (^[a-z0-9-]+$),
 * STAGING_MEDIA_PRODUCT_TITLE (non-empty), STAGING_MEDIA_ASSET_ID (lower-case UUID).
 *
 * Both projects run it: the 390 px mobile project is the mobile-first proof, the
 * desktop one the 1440 px control.
 */

import { test, expect, type BrowserContext, type Page } from "@playwright/test"
import { LCP_BUDGET_MS, CLS_BUDGET } from "./perf-budgets"

const STAGING_APP = "https://app-staging.olajay.co.uk"

// No fallback literal: an unset or different base URL skips, below.
const BASE = process.env.PLAYWRIGHT_BASE_URL

test.use({ storageState: { cookies: [], origins: [] } })

function requiredInput(name: string, valid: (v: string) => boolean, rule: string): string {
  const value = process.env[name]
  if (value === undefined || value === "" || !valid(value)) {
    throw new Error(`${name} must be set and ${rule} (got ${value === undefined ? "nothing" : JSON.stringify(value)})`)
  }
  return value
}

// Copied from landing-webperf.spec.ts (each webperf spec carries its own copy).
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

/** Buffered LCP (max renderTime) + cumulative layout shift. */
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
          /* browser lacks the entry types — resolve with zeros */
        }
        setTimeout(() => resolve({ lcp, cls }), 2500)
      })
  )
}

/** Depth-first search of parsed JSON-LD for the Product node named `title`. */
function findProduct(node: unknown, title: string): Record<string, unknown> | null {
  if (Array.isArray(node)) {
    for (const item of node) {
      const hit = findProduct(item, title)
      if (hit) return hit
    }
    return null
  }
  if (node && typeof node === "object") {
    const obj = node as Record<string, unknown>
    if (obj["@type"] === "Product" && obj.name === title) return obj
    for (const value of Object.values(obj)) {
      const hit = findProduct(value, title)
      if (hit) return hit
    }
  }
  return null
}

test("staging storefront renders the vendor's pipeline photo for an anonymous visitor", async ({ page, context }, testInfo) => {
  test.skip(
    BASE !== STAGING_APP,
    `PLAYWRIGHT_BASE_URL must be exactly ${STAGING_APP} for the staging storefront check (set STAGING_E2E=1 too)`,
  )
  const slug = requiredInput("STAGING_SHOP_SLUG", (v) => /^[a-z0-9-]+$/.test(v), "match ^[a-z0-9-]+$")
  const title = requiredInput("STAGING_MEDIA_PRODUCT_TITLE", (v) => v.trim().length > 0, "be non-empty")
  const assetId = requiredInput(
    "STAGING_MEDIA_ASSET_ID",
    (v) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v),
    "be a lower-case UUID",
  )
  test.setTimeout(180_000)

  try {
    await throttle(context, page)
    const response = await page.goto(`/shop/${slug}`, { waitUntil: "domcontentloaded", timeout: 30_000 })
    expect(response?.status(), "the shop page status").toBe(200)

    await expect(page.getByText(title, { exact: true }).first()).toBeVisible({ timeout: 30_000 })
    console.log(`TITLE_VISIBLE ${title}`)

    const vitals = await measureVitals(page)
    console.log(
      `CWV project=${testInfo.project.name} lcp_ms=${Math.round(vitals.lcp)} cls=${vitals.cls.toFixed(4)} ` +
        `budget_lcp_ms=${LCP_BUDGET_MS} budget_cls=${CLS_BUDGET} (throttled ~Fast 3G, 4x CPU)`,
    )

    const img = page.getByRole("img", { name: title, exact: true }).first()
    expect(
      await page.getByRole("img", { name: title, exact: true }).count(),
      `NO_MEDIA_IMG: no <img> named "${title}" — the storefront fell back to its placeholder, so the menu carried no image`,
    ).toBeGreaterThan(0)

    const src = (await img.getAttribute("src")) ?? ""
    expect(
      src.startsWith("https://") && src.includes(`/media/${assetId}.webp`) && !src.includes("/quarantine/"),
      `MEDIA_SRC_MISMATCH: src ${JSON.stringify(src)} is not the https WebP derivative /media/${assetId}.webp`,
    ).toBe(true)

    await img.scrollIntoViewIfNeeded({ timeout: 15_000 })
    await expect
      .poll(
        async () =>
          img.evaluate((el) => {
            const i = el as HTMLImageElement
            return i.complete && i.naturalWidth > 0
          }),
        { timeout: 15_000, message: `the image ${src} never decoded (naturalWidth stayed 0)` },
      )
      .toBe(true)
    const natural = await img.evaluate((el) => {
      const i = el as HTMLImageElement
      return { w: i.naturalWidth, h: i.naturalHeight }
    })
    console.log(`naturalWidth=${natural.w} naturalHeight=${natural.h} src=${src}`)

    const blocks = await page.locator('script[type="application/ld+json"]').allTextContents()
    let product: Record<string, unknown> | null = null
    for (const block of blocks) {
      product = findProduct(JSON.parse(block), title)
      if (product) break
    }
    expect(product, `JSONLD_PRODUCT_MISSING: no Product named "${title}" in ${blocks.length} JSON-LD block(s)`).not.toBeNull()
    const jsonLdImage = product?.image
    expect(jsonLdImage, `JSONLD_IMAGE_MISMATCH: Product image ${JSON.stringify(jsonLdImage)} is not the card src ${src}`).toBe(src)
    console.log(`JSONLD_IMAGE ${String(jsonLdImage)}`)
  } catch (e) {
    const text = await page.locator("body").innerText({ timeout: 5_000 }).catch(() => "<no page text>")
    console.log(`PAGE_URL_ON_FAILURE ${page.url()}`)
    console.log(`PAGE_TEXT_ON_FAILURE ${text.replace(/\s+/g, " ").slice(0, 600)}`)
    throw e
  }
})

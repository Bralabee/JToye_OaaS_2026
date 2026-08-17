/**
 * Shared public-surface fixtures and navigation helpers for the stack-free
 * browser gates.
 *
 * WHY THIS FILE EXISTS. `public-layout.spec.ts` grew these helpers, and
 * `public-a11y.spec.ts` (plan 31-18) needs exactly the same ones — the API stub
 * that keeps both specs stack-free, and the two storefront helpers that refuse
 * to continue over a page that did not render. Copying them would have created
 * two definitions of "did this storefront actually load", and the whole reason
 * `openStorefront` exists is that the first copy of that judgement was wrong.
 *
 * WHY NOT `import { … } from "./public-layout.spec"`. Importing one spec file
 * from another EXECUTES its module body, which registers every `test.describe`
 * in the importing file's scope as well — the layout suite would then run twice
 * and its accounting would be wrong. A plain module is not collected by
 * Playwright (`testMatch` is `*.spec.ts`), so it is the only shape that shares
 * code without sharing tests.
 *
 * WHY NAVIGATION HERE IS RELATIVE. `playwright.config.ts` is the ONLY base-URL
 * authority (`scripts/check-e2e-baseurl-contract.sh`, #505). That gate scans
 * `*.spec.ts` files, so a `PLAYWRIGHT_BASE_URL` fallback declared HERE would sit
 * outside its scan and silently escape the check it exists to enforce. These
 * helpers therefore navigate with relative paths and let Playwright resolve them
 * against the configured `baseURL` — which is the fix that gate recommends in
 * its own failure message, not a workaround for it.
 */
import { expect, type Page, type BrowserContext } from "@playwright/test"

/** SVG data URIs give exact, readable intrinsic dimensions and need no assets. */
function svg(width: number, height: number, fill: string): string {
  const raw = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="${fill}"/></svg>`
  return `data:image/svg+xml;base64,${Buffer.from(raw).toString("base64")}`
}

export const PORTRAIT = svg(900, 1200, "%23c2410c") // the shape that broke the modal
export const LANDSCAPE = svg(858, 645, "%230f766e")
export const ULTRAWIDE = svg(1200, 400, "%237c2d12")

export const SHOP = {
  id: "shop-1",
  slug: "test-kitchen",
  name: "Test Kitchen",
  description: "Fixture kitchen",
  address: "1 Test Road, London SE15 1AA",
  tags: "Nigerian, Grill, Halal",
  logoUrl: LANDSCAPE,
  bannerUrl: ULTRAWIDE,
  openingHours: null,
  minimumOrderPennies: 1000,
  deliveryFeePennies: 299,
  freeDeliveryThresholdPennies: 3000,
  deliveryInfo: "30-40 min",
  published: true,
}

export const PRODUCTS = [
  { id: "p-1", title: "Portrait Dish", imageUrl: PORTRAIT },
  { id: "p-2", title: "Landscape Dish", imageUrl: LANDSCAPE },
  { id: "p-3", title: "Ultrawide Dish", imageUrl: ULTRAWIDE },
].map((p) => ({
  ...p,
  description: `${p.title} — fixture`,
  imageUrls: [p.imageUrl],
  ingredientsText: "fixture ingredients",
  allergenMask: 0,
  pricePennies: 950,
  category: "Mains",
  dietaryTags: null,
  preparationTimeMinutes: 10,
  featured: false,
  inStock: true,
}))

/** Serve the whole public API from fixtures — no backend, no DB, no Keycloak. */
export async function stubPublicApi(context: BrowserContext) {
  await context.route("**/public/**", async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    const json = (body: unknown) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(body),
      })

    if (p.endsWith("/public/shops")) {
      return json({
        content: [SHOP],
        totalElements: 1,
        totalPages: 1,
        size: 12,
        number: 0,
        first: true,
        last: true,
      })
    }
    // ProductsByCategory — a map keyed by category, NOT a flat array.
    if (p.endsWith("/products")) return json({ Mains: PRODUCTS })
    if (p.endsWith("/reviews")) {
      return json({
        content: [],
        totalElements: 0,
        totalPages: 0,
        size: 5,
        number: 0,
        first: true,
        last: true,
      })
    }
    if (p.endsWith("/promotions") || p.endsWith("/announcements")) return json([])
    if (p.endsWith("/config")) return json({})
    if (p.includes("/public/shops/")) return json(SHOP)
    return json({})
  })
}

/**
 * A storefront that EXISTS in whatever environment this spec is pointed at.
 *
 * Stack-free (the CI gate): the Next server's fetch to core is refused, so
 * `loadShopDetail` defers, the client island fetches, the browser stub answers,
 * and this resolves to the fixture `/shop/test-kitchen`.
 *
 * Against a live stack (nightly, local): the server answers authoritatively and
 * this resolves to a real seeded slug. Either way the page under test is one
 * that actually renders dish cards — which is the property these tests need, and
 * the property a hardcoded fixture slug silently lost.
 */
export async function resolveStorefrontPath(page: Page): Promise<string> {
  await page.goto("/shop")
  await page.waitForLoadState("domcontentloaded")

  // A shop CARD, not merely a link under /shop/. `/shop/` also hosts `signin`,
  // `auth` and `orders`, and the storefront nav's "Sign in" button is an
  // `a[href^="/shop/"]` sitting above the grid — picking it navigated to
  // `/shop/signin` and produced exactly the empty page this helper guards
  // against. `has: article` is the structural definition of a card
  // (`shop-discovery-client.tsx` wraps each `<article>` in its `<Link>`), so it
  // cannot drift into matching a nav control.
  const link = page
    .locator('a[href^="/shop/"]:visible')
    .filter({ has: page.locator("article") })
    .first()
  await expect(
    link,
    "the shop directory listed no storefront to open — neither the fixture stub " +
      "nor a live backend produced one"
  ).toBeVisible({ timeout: 15_000 })

  const href = await link.getAttribute("href")
  expect(href, "storefront link href").toBeTruthy()
  return href as string
}

/**
 * Open a storefront and REFUSE to continue silently if it has no dish cards.
 *
 * The regression this exists to make loud: when the fixture slug started
 * 404ing, `locator("article").click()` simply waited out the full 60s test
 * timeout with a call log that said nothing about why. An empty page also
 * satisfies every invariant below it (no fixed-ratio boxes, no images, no
 * overflow, an `<h1>` present), so the sibling layout test passed VACUOUSLY over
 * the same not-found page for as long as the modal test hung.
 */
export async function openStorefront(page: Page, path: string): Promise<void> {
  await page.goto(path)
  await page.waitForLoadState("domcontentloaded")
  // Also outlasts the React streaming buffer (`<div id="S:n" hidden>`), whose
  // duplicate copy of the server-rendered tree is briefly in the DOM.
  await page.waitForTimeout(1200)

  await expect(
    page.locator("article:visible").first(),
    `${path} rendered no dish cards — the storefront did not load, so anything ` +
      `asserted past this point would be asserted over an empty page`
  ).toBeVisible({ timeout: 15_000 })
}

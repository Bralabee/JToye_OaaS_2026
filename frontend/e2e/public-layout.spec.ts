/**
 * Public-surface layout conformance — THE CI BROWSER GATE.
 *
 * Every other spec in this directory needs the full docker stack (Postgres,
 * Keycloak, core-java, MinIO…), which is why none of them run in CI. The
 * consequence was that layout, image rendering and interaction — the dimension
 * users actually report defects in — had ZERO automated coverage, while text
 * had three static gates. Four user-reported defects in a row landed in that
 * blind spot.
 *
 * This spec closes it cheaply: it stubs the public API entirely (`**\/public/**`)
 * so it needs nothing but a built frontend, and asserts invariants that only a
 * real browser can check.
 *
 * Wired into CI by the "Frontend E2E (public surfaces)" job in ci-cd.yaml.
 * KEEP IT STACK-FREE — the moment this needs a backend it stops running, and
 * the blind spot comes back.
 *
 * The image fixtures deliberately span portrait, landscape and ultra-wide
 * intrinsic ratios, because uniform fixtures would have hidden the very bug
 * this spec exists to prevent (a 4:3 frame silently adopting its image's ratio).
 */
import { test, expect, type Page, type BrowserContext } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"

/** SVG data URIs give exact, readable intrinsic dimensions and need no assets. */
function svg(width: number, height: number, fill: string): string {
  const raw = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="${fill}"/></svg>`
  return `data:image/svg+xml;base64,${Buffer.from(raw).toString("base64")}`
}

const PORTRAIT = svg(900, 1200, "%23c2410c") // the shape that broke the modal
const LANDSCAPE = svg(858, 645, "%230f766e")
const ULTRAWIDE = svg(1200, 400, "%237c2d12")

const SHOP = {
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

const PRODUCTS = [
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
async function stubPublicApi(context: BrowserContext) {
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
 * GENERIC ASPECT CONFORMANCE.
 *
 * Any element whose computed `aspect-ratio` is not `auto` must actually MEASURE
 * that ratio. Deliberately expressed in terms of computed style rather than
 * Tailwind classes, so it catches every fixed-ratio box in the app — present
 * and future — without knowing anything about how the class was written.
 *
 * `aspect-ratio` is only a PREFERRED size: it yields to content. An in-flow
 * child with `h-full` makes the box adopt the CHILD's intrinsic ratio instead,
 * which is exactly how the product modal shipped a different shape per photo
 * while `getComputedStyle` still cheerfully reported `aspect-ratio: 4 / 3`.
 */
async function aspectViolations(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const bad: string[] = []
    for (const el of Array.from(document.querySelectorAll("*"))) {
      const cs = getComputedStyle(el)
      const declared = cs.aspectRatio
      if (!declared || declared === "auto") continue

      const [w, h] = declared.split("/").map((n) => parseFloat(n.trim()))
      if (!w || !h) continue

      const rect = el.getBoundingClientRect()
      if (rect.width === 0 || rect.height === 0) continue // not visible

      const want = w / h
      const got = rect.width / rect.height
      // 2% tolerance absorbs sub-pixel rounding, nothing more.
      if (Math.abs(got - want) / want > 0.02) {
        bad.push(
          `<${el.tagName.toLowerCase()} class="${el.className}"> declares ` +
            `aspect-ratio ${declared} but measures ${Math.round(rect.width)}x` +
            `${Math.round(rect.height)} (${got.toFixed(3)} vs ${want.toFixed(3)})`
        )
      }
    }
    return bad
  })
}

/** Every rendered image must have actually decoded — "builds clean" != "loads". */
async function brokenImages(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll("img"))
      .filter((img) => {
        const r = img.getBoundingClientRect()
        return r.width > 0 && r.height > 0 && img.naturalWidth === 0
      })
      .map((img) => `${img.getAttribute("alt") || "(no alt)"} -> ${img.src.slice(0, 80)}`)
  )
}

async function horizontalOverflow(page: Page): Promise<number> {
  return page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth
  )
}

const PUBLIC_ROUTES = [
  "/",
  "/shop",
  "/shop?q=grill",
  "/shop/test-kitchen",
  "/for-operators",
  "/track",
  "/legal",
  "/business-model-guide",
  "/competitive",
]

test.describe("public surfaces — layout conformance", () => {
  test.beforeEach(async ({ context }) => {
    await stubPublicApi(context)
  })

  for (const route of PUBLIC_ROUTES) {
    test(`${route} honours its fixed-ratio boxes, renders its images, and does not overflow`, async ({
      page,
    }) => {
      await page.goto(`${BASE}${route}`)
      await page.waitForLoadState("domcontentloaded")
      // Let entrance animations settle; they must not leave content hidden.
      await page.waitForTimeout(1500)

      expect(await aspectViolations(page), "fixed-ratio boxes").toEqual([])
      expect(await brokenImages(page), "images that failed to decode").toEqual([])
      expect(
        await horizontalOverflow(page),
        "horizontal overflow (px)"
      ).toBeLessThanOrEqual(1)

      // Nothing above the fold may be stranded invisible by an animation that
      // never ran — the /for-operators failure mode (content gated on scroll).
      const h1 = page.locator("h1").first()
      await expect(h1).toBeVisible()
      expect(
        Number(await h1.evaluate((el) => getComputedStyle(el).opacity))
      ).toBeGreaterThan(0.9)
    })
  }

  test("product modal opens the SAME shape for portrait, landscape and ultrawide sources", async ({
    page,
  }) => {
    await page.goto(`${BASE}/shop/test-kitchen`)
    await page.waitForLoadState("domcontentloaded")
    await page.waitForTimeout(1200)

    const shapes: { title: string; ratio: number }[] = []

    for (const title of ["Portrait Dish", "Landscape Dish", "Ultrawide Dish"]) {
      await page.locator("article", { hasText: title }).first().click()
      const frame = page.locator("[data-aspect-frame]").first()
      await expect(frame).toBeVisible()

      const box = await frame.boundingBox()
      shapes.push({ title, ratio: (box?.width ?? 0) / (box?.height ?? 1) })

      expect(await aspectViolations(page), `modal for ${title}`).toEqual([])
      await page.keyboard.press("Escape").catch(() => {})
      await page
        .locator(".max-w-lg button")
        .first()
        .click({ timeout: 2000 })
        .catch(() => {})
      await page.waitForTimeout(400)
    }

    // The whole point: the frame governs, the photo does not.
    for (const s of shapes) {
      expect(s.ratio, `${s.title} modal ratio`).toBeCloseTo(4 / 3, 1)
    }
  })
})

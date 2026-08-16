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
 * This spec closes it cheaply: it stubs the public API (`**\/public/**`) so it
 * needs nothing but a built frontend, and asserts invariants that only a real
 * browser can check.
 *
 * THE STUB IS NOT TOTAL, AND SAYING SO IS THE POINT. It intercepts requests made
 * by the BROWSER. Since #537, `/shop` and `/shop/[slug]` are server components
 * that fetch from the Next server, so their first render is not stubbed at all —
 * it is answered by whatever core API that server can reach, or deferred to the
 * client island (which the stub does answer) when it can reach none.
 *
 * That is why the storefront tests below resolve their shop at RUNTIME instead
 * of hardcoding the fixture slug. The nightly run of 2026-08-04 is what this
 * cost: `/shop/test-kitchen` 404s the moment a real backend is reachable, so the
 * modal test spent 60s waiting for a dish card on a "Shop not found" page, while
 * its sibling layout test passed vacuously over that same empty page. The gate
 * was green in CI for exactly one reason — CI has no backend for the server to
 * reach — which is the definition of green by construction.
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

/**
 * Routes whose data the browser stub above still fully describes.
 *
 * `/shop/test-kitchen` USED TO BE IN THIS LIST and no longer can be. #537 made
 * `/shop/[slug]` a SERVER component: it calls `loadShopDetail()` from the Next
 * server, so the request never passes through the browser and
 * `context.route("**\/public/**")` cannot see it. With a core API reachable, the
 * fixture slug gets an authoritative 404, the route renders `not-found.tsx`, and
 * no dish card exists. `/shop` is server-loaded for the same reason — it stays
 * here only because its assertions are shape invariants that hold over real
 * shops just as well as over the fixture.
 *
 * See `resolveStorefrontPath()` for how the two storefront-detail tests below
 * stopped depending on a slug that only exists when there is no backend.
 */
const PUBLIC_ROUTES = [
  "/",
  "/shop",
  "/shop?q=grill",
  "/for-operators",
  "/track",
  "/legal",
  "/legal/privacy",
  "/legal/cookies",
  "/legal/retention",
  "/legal/accessibility",
  "/business-model-guide",
  "/competitive",
]

/**
 * The published policy set (LGL-01), in the order a reader meets it.
 *
 * Every one is a static server page with no backend dependency, which is what
 * lets the assertions below stay inside this spec's stack-free contract.
 */
const LEGAL_ROUTES = [
  "/legal",
  "/legal/privacy",
  "/legal/cookies",
  "/legal/retention",
  "/legal/accessibility",
] as const

/**
 * The root layout's fallback title (app/layout.tsx:21).
 *
 * A page that forgot to export metadata inherits this and is INDISTINGUISHABLE
 * from one that set a title, until you compare against the default by name. Five
 * pages all inheriting it would also be perfectly "consistent" — which is why
 * distinctness alone is not enough and this constant exists.
 */
const ROOT_DEFAULT_TITLE = "J'Toye OaaS - Multi-Tenant Order Management"

/**
 * A storefront that EXISTS in whatever environment this spec is pointed at.
 *
 * Stack-free (the CI gate): the Next server's fetch to core is refused, so
 * `loadShopDetail` defers, the client island fetches, the browser stub answers,
 * and this resolves to the fixture `/shop/test-kitchen`.
 *
 * Against a live stack (nightly, local :3000): the server answers
 * authoritatively and this resolves to a real seeded slug. Either way the page
 * under test is one that actually renders dish cards — which is the property
 * these tests need, and the property a hardcoded fixture slug silently lost.
 */
async function resolveStorefrontPath(page: Page): Promise<string> {
  await page.goto(`${BASE}/shop`)
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
async function openStorefront(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE}${path}`)
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

/**
 * Force three DIFFERENT intrinsic image ratios onto the first three dish cards.
 *
 * The invariant under test (#265) is "a 4:3 frame keeps its shape whatever the
 * photo's intrinsic ratio", so feeding the photos IS the experiment. The stub
 * serves the three ratios as `data:` URIs, which no route can intercept and none
 * needs to — they are already the ladder. A live stack serves real object-store
 * URLs, so those are read off the rendered page and re-served as the ladder.
 * Reading the srcs rather than pattern-matching a bucket name keeps this working
 * wherever the images happen to be hosted.
 */
async function forceRatioLadder(
  page: Page,
  context: BrowserContext,
  path: string
): Promise<void> {
  const srcs = await page
    .locator("article:visible img")
    .evaluateAll((imgs) => imgs.slice(0, 3).map((i) => (i as HTMLImageElement).src))

  const ladder = [PORTRAIT, LANDSCAPE, ULTRAWIDE]
  let routed = 0

  for (let i = 0; i < srcs.length; i++) {
    const src = srcs[i]
    if (src.startsWith("data:")) continue // already a fixture of known ratio
    const body = Buffer.from(ladder[i].split(",")[1], "base64")
    // A predicate, not a glob: an arbitrary URL can contain `*` and `?`.
    await context.route(
      (url) => url.toString() === src,
      (route) => route.fulfill({ status: 200, contentType: "image/svg+xml", body })
    )
    routed++
  }

  if (routed > 0) await openStorefront(page, path)
}

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

  test("a storefront honours its fixed-ratio boxes, renders its images, and does not overflow", async ({
    page,
  }) => {
    const path = await resolveStorefrontPath(page)
    await openStorefront(page, path)

    expect(await aspectViolations(page), "fixed-ratio boxes").toEqual([])
    expect(await brokenImages(page), "images that failed to decode").toEqual([])
    expect(
      await horizontalOverflow(page),
      "horizontal overflow (px)"
    ).toBeLessThanOrEqual(1)

    const h1 = page.locator("h1").first()
    await expect(h1).toBeVisible()
    expect(
      Number(await h1.evaluate((el) => getComputedStyle(el).opacity))
    ).toBeGreaterThan(0.9)
  })

  test("product modal opens the SAME shape for portrait, landscape and ultrawide sources", async ({
    page,
    context,
  }) => {
    const path = await resolveStorefrontPath(page)
    await openStorefront(page, path)
    await forceRatioLadder(page, context, path)

    const cards = page.locator("article:visible")
    const count = Math.min(await cards.count(), 3)
    expect(count, "dish cards available to open").toBeGreaterThanOrEqual(1)

    const shapes: { card: number; ratio: number }[] = []

    for (let i = 0; i < count; i++) {
      await cards.nth(i).click()

      // Scoped to the DIALOG, not `[data-aspect-frame]` page-wide. #533 gave
      // this modal a real `role="dialog"`, so the frame being measured is
      // provably the modal's and cannot be a card's.
      const frame = page.getByRole("dialog").locator("[data-aspect-frame]").first()
      await expect(frame).toBeVisible()

      const box = await frame.boundingBox()
      shapes.push({ card: i, ratio: (box?.width ?? 0) / (box?.height ?? 1) })

      expect(await aspectViolations(page), `modal for card ${i}`).toEqual([])

      // Escape MUST close it, and that is now ASSERTED rather than attempted.
      // This block used to read `press("Escape").catch(() => {})` followed by a
      // `.max-w-lg button` click, also `.catch()`-swallowed — a shape that only
      // made sense while Escape did NOT close the modal, which is precisely the
      // defect #446/#533 fixed. Swallowing both meant the test could not tell a
      // working dismiss from a broken one.
      await page.keyboard.press("Escape")
      await expect(frame).toBeHidden()
    }

    // The whole point: the frame governs, the photo does not.
    for (const s of shapes) {
      expect(s.ratio, `card ${s.card} modal ratio`).toBeCloseTo(4 / 3, 1)
    }
  })
})

/**
 * The published retention schedule at the CONTRACTED width (UI-SPEC S2a, LGL-01).
 *
 * WHY THIS LIVES HERE AND NEEDS NO STUB. `/legal/retention` is a static server
 * page with no backend dependency at all — it does not even need the public-API
 * stub the block above installs. That keeps this spec's stack-free property
 * intact, which its header is emphatic about: the moment it needs a backend it
 * stops running in CI and the blind spot comes back.
 *
 * WHY THE VIEWPORT IS SET EXPLICITLY. The `mobile` project is 390x844 and the
 * contract names 375px. 390 passing is not evidence about 375 — the two differ
 * by 15px, which is roughly the margin a fourth column lives or dies on. Setting
 * it here also means both projects run this at the contracted width rather than
 * one of them testing 1440.
 *
 * WHY A NON-VACUITY CONTROL COMES FIRST. A missing table has a `scrollWidth` of
 * 0, and 0 <= clientWidth is trivially true, so the fit assertion PASSES over a
 * page that failed to render. That is the same artefact class as an axe scan
 * over an empty tree, and it is not hypothetical here: this file's own header
 * records a sibling test that "passed vacuously over that same empty page".
 */
test.describe("legal retention schedule — 375px conformance", () => {
  const RETENTION_PATH = "/legal/retention"
  const REGION = "Data retention schedule"

  async function openRetentionAt375(page: Page) {
    await page.setViewportSize({ width: 375, height: 667 })
    await page.goto(`${BASE}${RETENTION_PATH}`)
    await page.waitForLoadState("domcontentloaded")
    // Outlast React's streaming staging buffer (`<div id="S:n" hidden>`), which
    // briefly holds a second copy of the server-rendered tree.
    await page.waitForTimeout(1200)
  }

  test("the retention table fits 375px with no horizontal scrolling", async ({
    page,
  }) => {
    await openRetentionAt375(page)

    // ── NON-VACUITY CONTROL — asserted BEFORE the fit measurement ──
    // Role-based, deliberately: the streaming staging buffer is `hidden`, so
    // `getByRole` cannot see its duplicate copy while `getByTestId` can.
    const table = page.getByRole("table")
    await expect(
      table,
      "the retention table did not render — everything below would be measured " +
        "over an empty page, where scrollWidth is 0 and the fit passes trivially"
    ).toBeVisible()

    const rowCount = await page.getByRole("row").count()
    expect(rowCount, "table rows (header + body)").toBeGreaterThan(1)
    expect(
      await table.locator("caption").count(),
      "table caption"
    ).toBeGreaterThanOrEqual(1)

    // ── THE FIT ──
    const region = page.getByRole("region", { name: REGION })
    await expect(region).toBeVisible()

    const size = await region.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    // Guard the guard: a zero-width region would satisfy the comparison below
    // for the wrong reason.
    expect(size.clientWidth, "scroll region clientWidth").toBeGreaterThan(0)
    expect(
      size.scrollWidth,
      `retention table overflows its region at 375px ` +
        `(scrollWidth ${size.scrollWidth} > clientWidth ${size.clientWidth})`
    ).toBeLessThanOrEqual(size.clientWidth)

    // The document as a whole must not overflow either — a table that fits its
    // own region while pushing the page wide is still a horizontal scrollbar.
    expect(
      await horizontalOverflow(page),
      "document horizontal overflow (px) at 375px"
    ).toBeLessThanOrEqual(1)
  })

  test("the retention schedule states enforcement in words, not colour", async ({
    page,
  }) => {
    await openRetentionAt375(page)

    const table = page.getByRole("table")
    await expect(table).toBeVisible()

    // Control first, for the same reason as above: zero rows would make the
    // "every cell is one of these two words" assertion vacuously true.
    const bodyRows = table.locator("tbody tr")
    const count = await bodyRows.count()
    expect(count, "published retention rows").toBeGreaterThan(1)

    const words: string[] = []
    for (let i = 0; i < count; i++) {
      // The row header is a `th`, so the enforcement cell is the LAST `td`.
      const cell = bodyRows.nth(i).locator("td").last()
      words.push(((await cell.textContent()) ?? "").trim())
    }

    expect(words, "one enforcement word per published row").toHaveLength(count)
    for (const word of words) {
      expect(["Automated", "Operational"]).toContain(word)
    }
    // Both classes must actually appear: a page rendering only one of them would
    // satisfy the loop above while having lost the distinction entirely.
    expect(words).toContain("Automated")
    expect(words).toContain("Operational")
  })
})

/**
 * THE POLICY SET IS REACHABLE AND DISCOVERABLE (LGL-01, plan 31-17).
 *
 * Needs no stub, for the same reason as the retention block above: all five
 * routes are static server pages. The spec's stack-free contract is intact.
 *
 * WHAT THESE THREE TESTS ADD THAT NO PER-PAGE TEST CAN.
 * A unit test renders one page and can prove that page has a title. It cannot
 * prove the five titles DIFFER from each other, and duplicate titles across a
 * document set is the exact SEO defect #447/F-H9-SEOMETA found on /shop (4/4
 * cells agreeing on one title). Uniqueness is a property of the SET.
 */
test.describe("published policy set — metadata, reachability and contact", () => {
  interface PageMeta {
    route: string
    title: string
    description: string
    canonical: string
  }

  /**
   * Read an attribute, treating "the tag is not there" as the empty string.
   *
   * THE COUNT CHECK IS NOT DEFENSIVE PADDING — WITHOUT IT THIS TEST CANNOT
   * REPORT ITS OWN DEFECT. `locator.getAttribute()` WAITS for a match, so on a
   * page whose `<meta name="description">` is absent it blocks for the full
   * 60s test timeout and the run fails with "locator.getAttribute: Test timeout
   * exceeded" — which names neither the route nor the missing tag. Measured, by
   * blanking a page's description and watching exactly that happen: the test
   * did go red, but for a reason no maintainer could act on, and the explicit
   * non-emptiness assertion below never ran at all.
   *
   * Next omits the tag entirely for an empty description rather than emitting
   * an empty one, so "absent" is the shape this failure actually takes.
   */
  async function attr(page: Page, selector: string, name: string): Promise<string> {
    const loc = page.locator(selector)
    if ((await loc.count()) === 0) return ""
    return ((await loc.first().getAttribute(name)) ?? "").trim()
  }

  test("every legal route returns 200 with a unique, non-default title, a unique description and its own canonical", async ({
    page,
  }) => {
    const seen: PageMeta[] = []

    for (const route of LEGAL_ROUTES) {
      const response = await page.goto(`${BASE}${route}`)
      expect(response, `${route} produced no response`).not.toBeNull()
      expect(response?.status(), `${route} HTTP status`).toBe(200)
      await page.waitForLoadState("domcontentloaded")

      seen.push({
        route,
        title: (await page.title()).trim(),
        description: await attr(page, 'meta[name="description"]', "content"),
        canonical: await attr(page, 'link[rel="canonical"]', "href"),
      })
    }

    // ── NON-VACUITY CONTROL, before any distinctness claim ──
    // FIVE EMPTY STRINGS ARE EQUAL, so an unpopulated set fails distinctness
    // rather than passing it — but an unpopulated set is also not the failure
    // anyone would diagnose from "expected 5 unique, got 1". Naming emptiness
    // and the inherited default explicitly is what makes the diagnosis land on
    // the right defect.
    for (const s of seen) {
      expect(s.title, `${s.route} <title> is empty`).not.toBe("")
      expect(
        s.title,
        `${s.route} still carries the ROOT DEFAULT title — it exports no metadata of its own`
      ).not.toBe(ROOT_DEFAULT_TITLE)
      expect(s.description, `${s.route} meta description is empty`).not.toBe("")
      expect(s.canonical, `${s.route} has no canonical link`).not.toBe("")
    }

    // ── DISTINCTNESS ACROSS THE SET ──
    const titles = seen.map((s) => s.title)
    const descriptions = seen.map((s) => s.description)
    expect(
      new Set(titles).size,
      `two policy pages share a <title>: ${JSON.stringify(titles)}`
    ).toBe(LEGAL_ROUTES.length)
    expect(
      new Set(descriptions).size,
      `two policy pages share a meta description: ${JSON.stringify(descriptions)}`
    ).toBe(LEGAL_ROUTES.length)

    // ── EACH CANONICAL POINTS AT ITS OWN PATH ──
    // Resolved against BASE so this holds whether Next emitted an absolute URL
    // (metadataBase resolved) or a root-relative one (metadataBase undefined,
    // which is the deliberate outcome when no origin can be trusted).
    for (const s of seen) {
      const path = new URL(s.canonical, BASE).pathname.replace(/(.)\/$/, "$1")
      expect(path, `${s.route} canonical points elsewhere (${s.canonical})`).toBe(s.route)
    }
  })

  /**
   * F14 — the reachability claim, asserted on a STOREFRONT and not only on `/`.
   *
   * This is the test the deleted StorefrontLegalStrip was going to exist for.
   * The premise that `/shop/**` has no footer was measured FALSE:
   * app/shop/layout.tsx renders the same <PublicFooter/> over the whole subtree,
   * so the Legal column reaches a tenant storefront for free. That is a claim
   * about a real rendered page, so it is proven on one — a landing-page-only
   * assertion would pass identically whether the claim held or not.
   */
  async function footerLegalHrefs(page: Page): Promise<string[]> {
    // Outlast React's streaming staging buffer (`<div id="S:n" hidden>`), whose
    // duplicate copy of the tree would double every count below.
    await page.waitForTimeout(1200)

    const footer = page.getByRole("contentinfo")
    await expect(
      footer,
      "no contentinfo landmark — everything below would be asserted over a page with no footer"
    ).toBeVisible()

    // NON-VACUITY CONTROL: a known pre-existing footer link. An empty or
    // failed-to-render footer satisfies nothing here, so the absence of a legal
    // link cannot be confused with the absence of a footer.
    await expect(
      footer.locator('a[href="/shop"]'),
      "the footer rendered without its pre-existing 'Browse shops' link"
    ).toHaveCount(1)

    return footer.locator("a").evaluateAll((as) =>
      as.map((a) => a.getAttribute("href") ?? "").filter((h) => h.startsWith("/legal"))
    )
  }

  test("all five policy pages are reachable from the footer on the landing page AND on a tenant storefront", async ({
    page,
    context,
  }) => {
    await stubPublicApi(context)

    // ── the landing page ──
    await page.goto(`${BASE}/`)
    await page.waitForLoadState("domcontentloaded")
    const landing = await footerLegalHrefs(page)
    for (const route of LEGAL_ROUTES) {
      expect(landing, `/ footer does not link ${route}`).toContain(route)
    }

    // ── a tenant storefront, resolved rather than hardcoded ──
    // The fixture slug 404s the moment a real backend is reachable, and an
    // empty not-found page satisfies a footer assertion just as well as a real
    // storefront does. openStorefront() refuses to continue past that.
    const path = await resolveStorefrontPath(page)
    await openStorefront(page, path)
    const storefront = await footerLegalHrefs(page)
    for (const route of LEGAL_ROUTES) {
      expect(storefront, `${path} footer does not link ${route} — a tenant storefront cannot reach the policy`).toContain(
        route
      )
    }

    // ── and following each one actually arrives somewhere ──
    // A link is not reachability if its destination 404s.
    for (const route of LEGAL_ROUTES) {
      const response = await page.goto(`${BASE}${route}`)
      expect(response?.status(), `${route} followed from the storefront footer`).toBe(200)
      // Guard the guard: a 200 that renders an empty document is not a policy.
      await expect(page.locator("h1").first()).toBeVisible()
    }
  })

  /**
   * F15 — the accessibility statement publishes a contact, and it resolves.
   *
   * The statement degrades in two configurations and BOTH are legitimate, so
   * this test branches rather than assuming one. What it will not do is pass
   * over an absent contact: the fallback branch asserts the documented fallback
   * by following it, and 31-13's arm 2c proved an empty `mailto:` is the failure
   * mode that looks live and goes nowhere.
   */
  test("the accessibility statement's feedback contact resolves", async ({ page }) => {
    await page.goto(`${BASE}/legal/accessibility`)
    await page.waitForLoadState("domcontentloaded")
    await page.waitForTimeout(1200)

    // Scoped to the section by its accessible name (PolicySection renders
    // `<section aria-labelledby>`), so the EASS/EHRC links in the separate
    // "Enforcement procedure" section below cannot satisfy this by accident.
    const section = page.getByRole("region", { name: "Feedback and contact" })
    await expect(
      section,
      "the 'Feedback and contact' section did not render — a contact assertion over a missing section is vacuous"
    ).toBeVisible()

    const hrefs = await section
      .locator("a")
      .evaluateAll((as) => as.map((a) => a.getAttribute("href") ?? ""))

    // NON-VACUITY: the section must offer at least one route. A section of pure
    // prose with no way to reach anyone is the defect, not a passing state.
    expect(hrefs.length, "the feedback section offers no contact route at all").toBeGreaterThan(0)

    const mailtos = hrefs.filter((h) => h.startsWith("mailto:"))
    const internal = hrefs.filter((h) => h.startsWith("/"))

    if (mailtos.length > 0) {
      // CONFIGURED: a real published address.
      for (const href of mailtos) {
        const address = href.slice("mailto:".length).split("?")[0].trim()
        expect(address, `empty mailto: — a link that looks live and goes nowhere (${href})`).not.toBe(
          ""
        )
        expect(address, `published contact address is not an address (${href})`).toContain("@")
      }
    } else {
      // UNCONFIGURED: the documented fallback. 31-13 chose to name the routes
      // that DO exist rather than emit an empty link, and this is the branch CI
      // takes, since NEXT_PUBLIC_DATA_PROTECTION_EMAIL is a build arg that is
      // unset in a stack-free run. Recorded rather than silently passed over.
      expect(
        internal.length,
        "no contact address is configured AND the documented fallback routes are missing — the statement publishes no way to reach anyone"
      ).toBeGreaterThan(0)
      // An empty mailto must never be emitted in EITHER configuration.
      expect(hrefs.filter((h) => h === "mailto:" || h === "mailto:@")).toHaveLength(0)
    }

    // Every internal route the section offers must actually resolve, in both
    // configurations. This is the half that fires when a contact is pointed at
    // a path that does not exist.
    for (const href of internal) {
      const response = await page.request.get(`${BASE}${href}`)
      expect(
        response.status(),
        `the feedback section links ${href}, which does not resolve`
      ).toBe(200)
    }
  })
})

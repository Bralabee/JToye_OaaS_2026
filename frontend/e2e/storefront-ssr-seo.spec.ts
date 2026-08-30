/**
 * Storefront server-rendering + discoverability, measured in the SERVED HTML
 * (issues #507, #447).
 *
 * WHY THIS READS THE RAW RESPONSE AND NOT THE DOM
 *
 * The whole change is about what arrives BEFORE JavaScript runs. A `page.goto`
 * + `expect(locator)` would pass identically on the pre-fix tree, because the
 * client-side fetch fills the DOM in about two and a half seconds and Playwright
 * waits. Every block in the first describe therefore uses `request.get`, which
 * performs no navigation, runs no script, and hands back the bytes the crawler
 * and the first paint actually get.
 *
 * MEASURED ON THE PRE-FIX TREE — what each block below was written to fail
 * against (running stack, 2026-08-04):
 *
 *   /shop/brixton-village-grill .. 34,419 bytes, 1 spinner, 0 <h1>,
 *                                  0 occurrences of "Brixton Village Grill"
 *   /shop ........................ 0 occurrences of ANY shop name
 *   /robots.txt .................. 404   (/sitemap.xml 200 — the control arm)
 *   <title> ...................... "J'Toye — Discover Local Vendors" on /shop
 *                                  AND on all three /shop/[slug] pages, 4/4
 *   rel=canonical / og: / twitter: / application/ld+json ..... 0 / 0 / 0 / 0
 *
 * The uniqueness block is asserted ACROSS routes on purpose: a single-page check
 * cannot detect "all pages share one title", which is the actual defect.
 *
 * RE-MEASURED 2026-08-28 (phase 34, plan 34-01) — the fail direction of this
 * whole file, executed rather than assumed. Against a stack-free Next server
 * (`next start -p 3105` with CORE_API_INTERNAL_URL unreachable, which is the
 * per-PR CI condition), `PLAYWRIGHT_BASE_URL=http://localhost:3105 npx
 * playwright test e2e/storefront-ssr-seo.spec.ts --project=desktop` exits rc=1
 * with 13 of 17 red, opening on "the shop name must be an h1 in the served
 * HTML — Expected: > 0, Received: 0"; the same command against the stacked
 * :3000 exits rc=0, 17 passed. Served bytes on the two servers:
 *
 *   :3000 /shop 54,184 B, 5 occurrences of "Brixton Village Grill", 1 <h1
 *   :3000 /shop/brixton-village-grill 90,951 B, 33 occurrences, 1 <h1
 *   :3105 /shop 39,438 B, 0 occurrences, 1 <h1
 *   :3105 /shop/brixton-village-grill 39,299 B, 0 occurrences, 0 <h1
 *
 * So these blocks are falsifiable and this suite is NOT covered by the
 * stack-free CI job — it needs the live stack to mean anything. The raw-HTML
 * helpers moved to `e2e/helpers/served-html.ts`; `e2e/ssr-coverage.spec.ts`
 * carries the proof that a browser route stub cannot satisfy them.
 */

import { test, expect } from "@playwright/test"

// The raw-HTML instrument lives in ONE module — `e2e/ssr-coverage.spec.ts`
// asserts against the same functions, and two copies of "what did the server
// actually serve" is the one thing this instrument cannot afford.
import {
  servedHtml,
  countOf,
  titleOf,
  jsonLdNodes,
  typesOf,
} from "./helpers/served-html"

const SHOP_SLUGS = ["brixton-village-grill", "mama-ades-kitchen"]

// The served bytes do not vary with viewport, so this half runs once rather than
// being duplicated across both projects. `@desktop-only` EXCLUDES it from the
// mobile project's enumeration (playwright.config.ts grepInvert) rather than
// skipping it at runtime — a skip must mean "nobody checked this".
test.describe("Storefront served HTML — content before JavaScript @desktop-only", () => {
  test("a storefront serves its own name, an <h1> and its menu with no JS", async ({ request }) => {
    const html = await servedHtml(request, `/shop/${SHOP_SLUGS[0]}`)

    // 0 before this change, on 34,419 bytes of markup.
    expect(countOf(html, "<h1"), "the shop name must be an h1 in the served HTML").toBeGreaterThan(0)
    expect(
      countOf(html, "Brixton Village Grill"),
      "the shop's name appeared 0 times in the served HTML before #507"
    ).toBeGreaterThan(0)

    // The menu is the reason a customer is on this page, so it has to be here
    // too — a page that serves only the shop name would satisfy the two
    // assertions above and still be a spinner for the thing that matters.
    expect(html).toMatch(/£\d+\.\d{2}/)
  })

  test("the directory serves the actual shops, not a skeleton grid", async ({ request }) => {
    const html = await servedHtml(request, "/shop")
    const named = SHOP_SLUGS.filter((s) => html.includes(s))
    expect(
      named.length,
      "/shop served zero shop links before #507 — it was a skeleton grid"
    ).toBeGreaterThanOrEqual(2)

    // Crawlable <a href>, not a JS-only click handler.
    for (const slug of SHOP_SLUGS) {
      expect(html).toContain(`href="/shop/${slug}"`)
    }
  })

  /**
   * ── THE LANDING KITCHEN ROW (#544) ──────────────────────────────────────────
   *
   * THE ORIGINAL CRITERION WAS UNSATISFIABLE AND IS REPLACED, NOT WEAKENED.
   *
   * It read: *"the string 'near you' is absent from the landing DOM"*. Measured
   * on the tree, `/` renders that string at FOUR sites and only ONE of them is
   * the lie #544 names:
   *
   *   app/page.tsx:25   steps[0].body  "Find independent kitchens near you…"  (rendered :234)
   *   app/page.tsx:133  CTA span       "Order food near you"
   *                        <- asserted by app/__tests__/landing.test.tsx:30
   *                           AND components/marketing/__tests__/hero-scene.test.tsx:30
   *   app/page.tsx:180  row heading    "Cooking near you right now"   <- THE ONE THAT LIES
   *   app/page.tsx:191  DishScroller   label="Dishes cooking near you" -> aria-label
   *                        <- IS the selector at marketing-dish-scroller.spec.ts:19
   *
   * A document-wide absence assertion can therefore never pass, and quietly
   * narrowing it until it went green would be the silent weakening CONTEXT.md
   * forbids. So the scope decision is written down here rather than left implicit:
   *
   *   IN SCOPE      heading elements. The row heading was the only site asserting
   *                 that the platform knows where the visitor is ABOUT THE CONTENT
   *                 IT IS SHOWING. That is the claim #544 is about.
   *
   *   OUT OF SCOPE  :133 and :25, deliberately. Both make a locality claim, and the
   *                 judgement is recorded rather than hidden: they are aspirational
   *                 marketing copy about what the platform is FOR, not a claim about
   *                 the current result set — and :133 is the primary customer CTA,
   *                 protected by two existing tests. A phase that quietly rewrote the
   *                 main CTA's copy under a data-truthfulness criterion would be
   *                 exceeding its mandate. If those should change, that is a copy
   *                 decision, not this criterion. ESCALATED, NOT ABSORBED.
   *
   * The control that proves this is SCOPING and not NARROWING is the last assertion
   * in the heading test: :133 and :25 must still be PRESENT. If the criterion had
   * merely been narrowed until green, that control would be missing.
   */
  test("the landing row names REAL published shops, and none of the invented five", async ({
    request,
  }) => {
    const html = await servedHtml(request, "/")

    // Read the truth from the API at test time rather than hardcoding names, so
    // the assertion tracks the seed instead of drifting away from it.
    const res = await request.get("/api/v1/public/shops?page=0&size=8")
    let liveNames: string[] = []
    if (res.ok()) {
      const body = await res.json()
      liveNames = (body.content ?? []).map((s: { name: string }) => s.name)
    }
    if (liveNames.length === 0) {
      // Fall back to the seeded slugs this file already knows, but say so — a
      // silently-empty expectation set would make every assertion below vacuous.
      liveNames = ["Brixton Village Grill", "Mama Ade's Kitchen"]
    }
    expect(liveNames.length, "no shops to assert against — the check would be vacuous").toBeGreaterThan(0)

    // (a) at least one REAL shop name is in the bytes, before any JavaScript.
    const present = liveNames.filter((n) => html.includes(n))
    expect(
      present.length,
      `/ served none of the live shop names ${JSON.stringify(liveNames)} — the row is not server-rendered`
    ).toBeGreaterThan(0)

    // (b) each is a crawlable link to its own shop page, not a search.
    for (const slug of SHOP_SLUGS) {
      if (!html.includes(`/shop/${slug}`)) continue
      expect(html).toContain(`href="/shop/${slug}"`)
    }
    expect(
      countOf(html, /href="\/shop\/[a-z0-9-]+"/g),
      "the row must link into real shop pages"
    ).toBeGreaterThan(0)

    // (c) NONE of the five invented vendors survives anywhere in the bytes.
    // "Mama's Kitchen" is in this list on purpose: it is a near-duplicate of the
    // real "Mama Ade's Kitchen", so a careless substring check would pass on the
    // real name and hide a reintroduction.
    for (const invented of [
      "Mama&#x27;s Kitchen",
      "Mama's Kitchen",
      "Spice Route",
      "Olive &amp; Vine",
      "Olive & Vine",
      "Crumb &amp; Co",
      "Crumb & Co",
      "Hanoi House",
    ]) {
      expect(countOf(html, invented), `invented vendor "${invented}" is still served`).toBe(0)
    }

    // ...and the invented rating/FHRS decoration went with them.
    expect(countOf(html, "FHRS"), "the invented FHRS badge is still served").toBe(0)
  })

  test("no HEADING on / claims proximity while no coordinate is held (#544)", async ({
    request,
  }) => {
    const html = await servedHtml(request, "/")

    // SCOPED to headings — extract heading TEXT and test that, rather than
    // running the regex over the whole document. See the block comment above for
    // why the document-wide form is unsatisfiable.
    const headings = [...html.matchAll(/<h[1-6][^>]*>([\s\S]*?)<\/h[1-6]>/gi)].map((m) =>
      m[1].replace(/<[^>]+>/g, "").trim()
    )
    expect(headings.length, "no headings parsed — the instrument is broken, not the page").toBeGreaterThan(0)

    const offending = headings.filter((t) => /near you/i.test(t))
    expect(offending, `heading(s) claim proximity with no coordinate: ${JSON.stringify(offending)}`).toEqual([])

    // THE CONTROL that distinguishes scoping from narrowing. Both deliberately
    // out-of-scope sites must STILL be served. If either has gone, the criterion
    // was applied document-wide after all and something was quietly rewritten.
    expect(countOf(html, "Order food near you"), "the primary CTA was removed").toBeGreaterThan(0)
    expect(
      countOf(html, "Find independent kitchens near you"),
      "the Browse step copy was removed"
    ).toBeGreaterThan(0)
    // ...and the scroller's aria-label, which is a spec selector elsewhere.
    expect(countOf(html, "Dishes cooking near you"), "the scroller label changed").toBeGreaterThan(0)
  })

  test("/ emits shopListStructuredData JSON-LD naming the real shops", async ({ request }) => {
    // Asserted against the RAW BYTES, not the hydrated DOM: a client-side-only
    // node would satisfy a DOM query while being invisible to a crawler, which is
    // the entire point of the criterion.
    const html = await servedHtml(request, "/")

    const blocks = [
      ...html.matchAll(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g),
    ].map((m) => m[1])
    expect(blocks.length, "/ served no application/ld+json block").toBeGreaterThan(0)

    const parsed = blocks.flatMap((b) => {
      const j = JSON.parse(b)
      return Array.isArray(j) ? j : [j]
    })
    const list = parsed.find(
      (n: { "@type"?: string }) => n && n["@type"] === "ItemList"
    ) as { itemListElement?: Array<{ item?: { name?: string; url?: string } }> } | undefined
    expect(list, "no ItemList in the landing JSON-LD").toBeTruthy()

    // A well-formed but EMPTY ItemList must not pass — otherwise the criterion
    // only proves a tag exists, not that it carries the truth.
    const names = (list!.itemListElement ?? []).map((e) => e.item?.name).filter(Boolean)
    expect(names.length, "the ItemList is well-formed but empty").toBeGreaterThan(0)

    // Every named entity must also appear in the visible HTML — JSON-LD that
    // disagrees with the page is cloaking, and search engines treat it as such.
    for (const n of names) {
      expect(html, `JSON-LD names "${n}" but the page does not render it`).toContain(n!)
    }
  })

  test("the landing route PERMITS geolocation to its own origin, and still denies the rest", async ({
    request,
  }) => {
    // Task 1 of 33-03's runtime half. `next.config.mjs` headers are baked at
    // BUILD time, so a source grep proves nothing about what is served — this
    // reads the live response.
    //
    // Measured before the change (CA-2, 2026-08-08):
    //   Permissions-Policy: camera=(), microphone=(), geolocation=(), browsing-topics=()
    // `geolocation=()` is an EMPTY allowlist. It denies the API to the page's own
    // origin on every route, before any prompt, with no useful console error —
    // indistinguishable from a user declining.
    const res = await request.get("/")
    expect(res.status(), "/ should serve 200").toBe(200)
    const policy = res.headers()["permissions-policy"]
    expect(policy, "/ must send a Permissions-Policy header").toBeTruthy()

    // Assert the PERMISSIVE string is PRESENT, never that the restrictive one is
    // absent: an absence assertion would also pass if the whole header were
    // deleted, which would silently drop the three denials below.
    expect(policy, "geolocation must be permitted to same-origin").toContain("geolocation=(self)")

    // ...and the widening is exactly one capability wide.
    expect(policy, "camera must stay denied").toContain("camera=()")
    expect(policy, "microphone must stay denied").toContain("microphone=()")
    expect(policy, "browsing-topics must stay denied").toContain("browsing-topics=()")
  })

  test("every public storefront route has a DISTINCT title and description", async ({ request }) => {
    const routes = ["/shop", ...SHOP_SLUGS.map((s) => `/shop/${s}`)]
    const titles: string[] = []

    for (const route of routes) {
      const html = await servedHtml(request, route)
      const title = titleOf(html)
      expect(title, `${route} must have a <title>`).toBeTruthy()
      titles.push(title!)

      const desc = html.match(/<meta name="description" content="([^"]*)"/)
      expect(desc, `${route} must have a meta description`).toBeTruthy()
      expect(desc![1].length, `${route} description must not be empty`).toBeGreaterThan(20)
    }

    // THE block. Pre-fix all four routes returned the same string, and a
    // per-page check could not see it.
    expect(new Set(titles).size, `titles were not unique: ${JSON.stringify(titles)}`).toBe(
      titles.length
    )
    // ...and specifically not the one shared layout title they all used to be.
    for (const t of titles) {
      expect(t).not.toBe("J'Toye — Discover Local Vendors")
    }
  })

  test("each storefront route carries canonical, Open Graph and Twitter tags", async ({ request }) => {
    for (const route of ["/shop", ...SHOP_SLUGS.map((s) => `/shop/${s}`)]) {
      const html = await servedHtml(request, route)

      const canonical = html.match(/<link rel="canonical" href="([^"]*)"/)
      expect(canonical, `${route} must have a canonical`).toBeTruthy()
      // Absolute when an origin is configured, root-relative when it is not —
      // both correct, neither a guessed hostname.
      expect(canonical![1]).toMatch(new RegExp(`(^|//[^/]+)${route}/?$`))

      expect(countOf(html, 'property="og:'), `${route} Open Graph`).toBeGreaterThan(0)
      expect(html).toMatch(/<meta property="og:title" content="[^"]+"/)
      expect(countOf(html, 'name="twitter:'), `${route} Twitter card`).toBeGreaterThan(0)

      // A public surface must never be quietly de-indexed.
      expect(html).not.toMatch(/<meta name="robots" content="[^"]*noindex/)
    }
  })

  test("a storefront publishes valid Restaurant + Product/Offer JSON-LD", async ({ request }) => {
    const html = await servedHtml(request, `/shop/${SHOP_SLUGS[0]}`)
    const nodes = jsonLdNodes(html)
    expect(nodes.length, "zero JSON-LD across 28 priced dishes before #447").toBeGreaterThan(0)

    const types = typesOf(nodes)
    // Restaurant is a LocalBusiness subtype — the #447 requirement, in the form
    // that produces a food rich result.
    expect(types).toContain("Restaurant")
    expect(types).toContain("BreadcrumbList")

    const restaurant = nodes.find(
      (n) => (n as { "@type": string })["@type"] === "Restaurant"
    ) as { name: string; address?: unknown; hasMenu?: unknown }
    expect(restaurant.name).toBe("Brixton Village Grill")

    const list = nodes.find((n) => (n as { "@type": string })["@type"] === "ItemList") as {
      itemListElement: Array<{
        item: { "@type": string; name: string; offers?: { price: string; priceCurrency: string } }
      }>
    }
    expect(list, "the dishes must be an ItemList of Products").toBeTruthy()
    expect(list.itemListElement.length).toBeGreaterThan(0)
    for (const entry of list.itemListElement) {
      expect(entry.item["@type"]).toBe("Product")
      expect(entry.item.offers?.priceCurrency).toBe("GBP")
      // Pounds with two decimals — never the raw penny integer.
      expect(entry.item.offers?.price).toMatch(/^\d+\.\d{2}$/)
    }
  })

  test("the directory publishes an ItemList of Restaurants", async ({ request }) => {
    const nodes = jsonLdNodes(await servedHtml(request, "/shop"))
    expect(typesOf(nodes)).toContain("ItemList")
    const list = nodes.find((n) => (n as { "@type": string })["@type"] === "ItemList") as {
      itemListElement: Array<{ item: { "@type": string; url: string } }>
    }
    expect(list.itemListElement.length).toBeGreaterThan(0)
    for (const entry of list.itemListElement) {
      expect(entry.item["@type"]).toBe("Restaurant")
      expect(entry.item.url).toContain("/shop/")
    }
  })

  test("/robots.txt is served and points at the sitemap", async ({ request }) => {
    const res = await request.get("/robots.txt")
    expect(res.status(), "/robots.txt returned 404 before #447").toBe(200)
    const body = await res.text()

    expect(body).toMatch(/User-Agent:\s*\*/i)
    // An absolute Sitemap: URL — the format has no relative form.
    const sitemap = body.match(/Sitemap:\s*(\S+)/i)
    expect(sitemap, "robots.txt must reference the sitemap").toBeTruthy()
    expect(sitemap![1]).toMatch(/^https?:\/\/.+\/sitemap\.xml$/)

    // The storefront must stay crawlable; the authenticated surface must not.
    expect(body).not.toMatch(/Disallow:\s*\/shop\s*$/m)
    expect(body).toMatch(/Disallow:\s*\/dashboard/)
  })

  test("/sitemap.xml lists every shop page on a configured origin", async ({ request }) => {
    const res = await request.get("/sitemap.xml")
    expect(res.status()).toBe(200)
    const xml = await res.text()

    const locs = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1])
    expect(locs.length).toBeGreaterThan(5)

    // The coverage half: all three storefronts were omitted before #447.
    for (const slug of SHOP_SLUGS) {
      expect(locs.some((u) => u.endsWith(`/shop/${slug}`)), `sitemap omits ${slug}`).toBe(true)
    }
    // Every entry must be absolute and share one origin — a sitemap mixing
    // origins, or carrying a relative path, is not a valid sitemap.
    const origins = new Set(locs.map((u) => new URL(u).origin))
    expect(origins.size, `sitemap mixes origins: ${[...origins]}`).toBe(1)
  })

  test("a slug that does not exist renders the not-found page and is noindex", async ({ request }) => {
    // Fail-direction control for the blocks above: they all read a REAL shop, so
    // this proves the route can tell a live storefront from a dead one rather
    // than serving equivalent markup for anything.
    //
    // ON THE STATUS CODE. This deliberately does NOT assert 404. Measured on
    // this branch: the route answers **200** regardless of where `notFound()` is
    // raised, because `app/layout.tsx` sets `dynamic = "force-dynamic"` app-wide
    // for the CSP nonce and the status line is committed before the tree
    // finishes streaming. Asserting 404 here would be a criterion that cannot
    // pass; asserting `noindex` is the strictly weaker claim that is TRUE, and
    // it covers the harm a soft 404 actually does — a dead vendor page sitting
    // in the index competing with the live ones. See the note in page.tsx.
    const res = await request.get("/shop/definitely-not-a-real-shop-a2", {
      failOnStatusCode: false,
    })
    const html = await res.text()

    expect(html, "the not-found screen must still be what a customer sees").toContain(
      "Shop not found"
    )
    expect(html).toMatch(/<meta name="robots" content="[^"]*noindex/)
    // ...and it must NOT be a live shop's page under a different slug.
    expect(html).not.toContain("Brixton Village Grill")
  })

  test("heading order is hierarchical in the DOM: h1 -> h2 -> h3, no skipped level", async ({
    page,
  }) => {
    // READ FROM THE DOM, NOT THE BYTES. The first version of this block scanned
    // the raw HTML in source order and failed against a CORRECT tree: it found
    // two <h3>s before the <h1>. They are the PublicFooter's section headings —
    // under app-wide streaming, Next flushes the layout shell (header + footer)
    // first and inserts <main> later, so byte order is not document order. The
    // heading outline is a property of the accessibility tree, so the DOM is the
    // correct medium here, not a weaker one.
    //
    // Scripting is ON for the same reason: with it off, Next's streamed body is
    // never relocated into <main> and the selector below matches nothing (see
    // the JS-disabled block at the bottom of this file, which measures exactly
    // that). The headings still come from the SERVER — no client fetch runs on
    // this route, which the "no browser-side catalogue request" block proves
    // independently.
    await page.goto(`/shop/${SHOP_SLUGS[0]}`)
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible()

    // Scoped to <main>: the footer's own h3 section labels sit under no h2 and
    // belong to the shared marketing chrome (components/public/public-footer.tsx),
    // which is outside this change. Recorded rather than silently included.
    const levels = await page.$$eval("main h1, main h2, main h3, main h4, main h5, main h6", (els) =>
      els.map((el) => Number(el.tagName[1]))
    )

    expect(levels.length, "no headings at all is not a pass").toBeGreaterThan(2)
    expect(levels[0], "the first heading in <main> must be the h1").toBe(1)

    let deepest = 0
    for (const level of levels) {
      // A level may close back to anything shallower, but may only OPEN one
      // deeper than the deepest so far. The pre-fix H1 -> H2 -> H4 fails here.
      expect(level, `heading order jumped from h${deepest} to h${level}`).toBeLessThanOrEqual(
        deepest + 1
      )
      deepest = Math.max(deepest, level)
    }
  })
})

test.describe("Storefront in a real browser — content without a client fetch", () => {
  test("with JavaScript DISABLED the content is in the document, though Next does not paint it", async ({
    browser,
    baseURL,
  }) => {
    // THIS BLOCK RECORDS A LIMITATION RATHER THAN HIDING ONE.
    //
    // It was first written as "the menu is VISIBLE with JavaScript disabled" —
    // the strongest form of the #507 claim — and it failed against a tree that
    // is otherwise correct. The reason is worth keeping, because it bounds what
    // this change can honestly claim.
    //
    // `app/layout.tsx` sets `dynamic = "force-dynamic"` app-wide for the CSP
    // nonce, and `app/shop/loading.tsx` puts a Suspense boundary around the
    // segment. Next therefore STREAMS: it flushes the layout shell, then sends
    // the page body inside `<div hidden>` blocks that an inline bootstrap script
    // relocates into `<main>`. Measured on the served `/shop` document: 4
    // `<div hidden>` and 3 `<template>` wrappers. With scripting off the
    // relocation never runs, so the text is in the document but never painted —
    // the accessibility tree shows only the header and footer.
    //
    // What that means in practice:
    //  - a consumer that reads the RESPONSE BODY (the classic crawler, a
    //    previewer, a text extractor) gets the full menu — that is what every
    //    block in the describe above asserts, over `request.get`;
    //  - a consumer that renders WITHOUT scripting sees chrome only.
    // Before this change neither got anything: the body held a skeleton and the
    // data arrived from a `useEffect`. So this is a large improvement and not a
    // complete one, and the distinction is recorded here instead of being
    // rounded up.
    //
    // `baseURL` is passed through explicitly: a context created from `browser`
    // does NOT inherit the project's baseURL, so a relative `page.goto("/shop")`
    // would fail for the wrong reason.
    const context = await browser.newContext({ baseURL, javaScriptEnabled: false })
    const page = await context.newPage()
    await page.goto(`/shop/${SHOP_SLUGS[0]}`)

    const html = await page.content()
    // Present in the document with scripting off — 0 before this change.
    expect(html).toContain("Brixton Village Grill")
    expect(html).toMatch(/£\d+\.\d{2}/)
    // ...and the streaming wrappers that explain why it is not painted.
    expect(html).toContain("<div hidden")

    await context.close()
  })

  test("no browser-side catalogue request is made on load", async ({ page }) => {
    // The other half of the same property: not merely that the HTML is
    // populated, but that the island does not immediately refetch it — which
    // would restore the round-trip and the layout shift the change removes.
    const calls: string[] = []
    page.on("request", (r) => {
      if (/\/public\/shops/.test(r.url())) calls.push(r.url())
    })

    await page.goto(`/shop/${SHOP_SLUGS[0]}`)
    await expect(
      page.getByRole("heading", { level: 1, name: "Brixton Village Grill" })
    ).toBeVisible()
    // Give hydration room to make the call it must not make.
    await page.waitForTimeout(2500)

    expect(calls, `island refetched on mount: ${calls.join(", ")}`).toEqual([])
  })
})

test.describe("Landing headings in a real browser (#544)", () => {
  test("no rendered heading claims proximity, and the out-of-scope copy survives", async ({
    page,
  }) => {
    // The DOM-level companion to the served-HTML assertion above. Both are wanted:
    // the raw-bytes form proves a crawler sees the truth, this proves a human does
    // after hydration — a client component could reintroduce the claim on mount and
    // the bytes assertion would never see it.
    await page.goto("/", { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })

    // getByRole, NOT getByTestId/getByTitle. React's streaming staging buffer holds
    // a second copy of the whole shell in `<div hidden id="S:n">` for ~300 ms; those
    // locators see it and getByRole does not. It was filed as a product bug twice,
    // #556 and #593, when it was a race.
    await expect(
      page.getByRole("heading", { name: /near you/i }),
      "a heading claims proximity while no coordinate is held"
    ).toHaveCount(0)

    // The scoping control, same as the served-HTML test: both deliberately
    // out-of-scope sites must still be rendered. Their absence would mean the
    // criterion was applied document-wide and copy was quietly rewritten.
    //
    // BOTH of these first failed as instrument defects, and both are recorded
    // here rather than quietly fixed, because each is a trap this repo has hit
    // before and will hit again:
    //
    //  1. `getByText("Order food near you")` resolved to TWO elements. React's
    //     streaming staging buffer parks a second copy of the shell in
    //     `<div hidden id="S:n">`; getByText sees it, getByRole does not. So the
    //     CTA is located by ROLE — which is also the more meaningful assertion,
    //     since what must survive is a working customer door, not a string.
    //  2. `Find independent kitchens near you` was HIDDEN, not missing. It lives
    //     in a `Reveal` section below the fold, so asserting visibility without
    //     scrolling first is the recorded "scroll-reveal content reads as an
    //     empty band" mistake. Scrolled into view, then asserted.
    await expect(
      page.getByRole("link", { name: /order food near you/i }),
      "the primary customer CTA was removed"
    ).toBeVisible()

    const browseStep = page.getByText(/Find independent kitchens near you/i).first()
    await browseStep.scrollIntoViewIfNeeded()
    await expect(browseStep, "the Browse step copy was removed").toBeVisible()
  })
})

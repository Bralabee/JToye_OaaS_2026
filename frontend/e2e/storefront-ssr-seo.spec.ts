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
 */

import { test, expect, type APIRequestContext } from "@playwright/test"

const SHOP_SLUGS = ["brixton-village-grill", "mama-ades-kitchen"]

/** The raw response body — no browser, no hydration, no waiting. */
async function servedHtml(request: APIRequestContext, path: string): Promise<string> {
  const res = await request.get(path)
  expect(res.status(), `${path} should serve 200`).toBe(200)
  return res.text()
}

function countOf(html: string, needle: string | RegExp): number {
  const re =
    typeof needle === "string"
      ? new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "g")
      : new RegExp(needle.source, needle.flags.includes("g") ? needle.flags : needle.flags + "g")
  return (html.match(re) ?? []).length
}

function titleOf(html: string): string | null {
  const m = html.match(/<title[^>]*>([\s\S]*?)<\/title>/)
  // Next escapes the apostrophe in "J'Toye" as &#x27;. Normalise so a title is
  // compared as text rather than as an encoding.
  return m ? m[1].replace(/&#x27;/g, "'").replace(/&amp;/g, "&").trim() : null
}

/** Every `<script type="application/ld+json">` payload, parsed. */
function jsonLdNodes(html: string): unknown[] {
  const blocks = [
    ...html.matchAll(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g),
  ]
  const nodes: unknown[] = []
  for (const [, body] of blocks) {
    const parsed: unknown = JSON.parse(body) // throws -> the block fails, which is correct
    nodes.push(...(Array.isArray(parsed) ? parsed : [parsed]))
  }
  return nodes
}

function typesOf(nodes: unknown[]): string[] {
  return nodes.map((n) => (n as { "@type"?: string })["@type"] ?? "(none)")
}

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

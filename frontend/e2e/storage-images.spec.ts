/**
 * Storefront images load FROM THE OBJECT STORE: a real browser, real pixels.
 *
 * WHY THIS SPEC EXISTS (Phase 36, BLOB-05)
 *
 * Phase 36 moved every image onto Azure Blob (Azurite locally). "Proven working"
 * was defined in CONTEXT as a browser rendering storefront images with
 * naturalWidth > 0 from the new origin after the reseed, not as a status code.
 * No existing spec can say that:
 *
 *   - storefront-flows.spec.ts contract #3 is satisfied by EITHER a loaded photo
 *     OR SafeImage's branded fallback tile, and its contract #2 is the
 *     `/brand/logo` asset served by the frontend itself. A store that serves
 *     nothing at all leaves both green.
 *   - scripts/check-media-urls-resolve.sh HEADs every stored URL, but a HEAD from
 *     a shell says nothing about what the browser shows: CSP, CORS, the page
 *     rendering a different URL, or a fallback swap are all invisible to it.
 *
 * WHAT MAKES IT UNABLE TO PASS ON FALLBACKS
 *
 * 1. Images are filtered by their FULL src beginning with the storage origin. Any
 *    <img> the page renders from anywhere else (the /brand/ logos) does not count.
 *    The src is never truncated before the prefix test.
 * 2. Zero storage-origin images is a VOID and fails, naming the filter.
 * 3. SafeImage REMOVES an <img> whose load failed and renders a fallback <div>. So
 *    "every storage img in the DOM has naturalWidth > 0" still passes when one
 *    blob 404s: the failed img is gone and the rest are fine. The spec closes that
 *    by reading the storage-origin <img> srcs the SERVER rendered (raw HTML, no
 *    script, independent of the store) and requiring every one of them to be a
 *    loaded <img> in the browser.
 *
 * The shop is DISCOVERED from the served HTML, not hardcoded: the public listing
 * in order, and the first shop whose server-rendered page carries at least one
 * storage-origin <img>. Discovery reads HTML only, so a stopped store cannot
 * change which shop is chosen. It fails on the pixels, not on the discovery.
 *
 * SHOWN TO FAIL: with Azurite stopped (`docker compose -f
 * docker-compose.full-stack.yml stop azurite`) this spec goes red, and it goes
 * green again after `start azurite`. Recorded in
 * .planning/phases/36-azure-blob-storage-throughout/evidence/36-13-browser-proof.txt.
 *
 * WHERE IT RUNS: the nightly full-stack suite (e2e-nightly.yml runs every spec).
 * It deliberately carries no title tag and is not named in ci-cd.yaml's per-PR
 * browser gate: that job has no object store, so this spec could only fail there.
 *
 * NAVIGATION: relative paths only. playwright.config.ts is the base-URL
 * authority (#505, scripts/check-e2e-baseurl-contract.sh).
 *
 * NO networkidle: storefront pages hold long-lived connections, so networkidle is
 * not a reliable settle signal. The spec polls the images' own `complete` state
 * against a deadline instead.
 */

import { test, expect, type Page } from "@playwright/test"
import { servedHtml } from "./helpers/served-html"

// The BROWSER origin of the public image container. It defaults to the
// STORAGE_PUBLIC_URL that docker-compose.full-stack.yml gives core-java
// (Azurite's devstoreaccount1 path-style URL). Override it for any other store.
const STORAGE = (
  process.env.PLAYWRIGHT_STORAGE_PUBLIC_URL || "http://localhost:10000/devstoreaccount1/jtoye-images"
).replace(/\/+$/, "")
const STORAGE_PREFIX = `${STORAGE}/`

const LOAD_DEADLINE_MS = 20_000

/** The unique src of every <img> in raw HTML that starts with the storage origin. */
function storageImgSrcs(html: string): string[] {
  const srcs = new Set<string>()
  for (const [tag] of html.matchAll(/<img\b[^>]*>/g)) {
    const m = tag.match(/\ssrc="([^"]*)"/)
    if (!m) continue
    const src = m[1].replace(/&amp;/g, "&")
    if (src.startsWith(STORAGE_PREFIX)) srcs.add(src)
  }
  return [...srcs].sort()
}

/** First shop in the public listing whose served page renders a storage-origin <img>. */
async function discoverShop(page: Page): Promise<{ slug: string; expected: string[] }> {
  const listing = await servedHtml(page.request, "/shop")
  const slugs = [
    ...new Set(
      [...listing.matchAll(/href="\/shop\/([a-z0-9-]+)"/g)]
        .map((m) => m[1])
        .filter((s) => s !== "signin" && s !== "cart"),
    ),
  ]
  expect(slugs.length, "VOID: the public /shop listing links to no shop page").toBeGreaterThan(0)

  for (const slug of slugs) {
    const expected = storageImgSrcs(await servedHtml(page.request, `/shop/${slug}`))
    if (expected.length > 0) return { slug, expected }
  }
  throw new Error(
    `VOID: none of ${slugs.length} listed shop pages (${slugs.join(", ")}) server-renders an <img> ` +
      `whose src starts with ${STORAGE_PREFIX}. Either the data does not point at the object store ` +
      `or PLAYWRIGHT_STORAGE_PUBLIC_URL is not the origin the app uses.`,
  )
}

/** Walk to the bottom in steps and back, so lazy and scroll-reveal images are requested. */
async function scrollThrough(page: Page): Promise<void> {
  await page.evaluate(async () => {
    const step = Math.max(200, Math.floor(window.innerHeight / 2))
    for (let y = 0; y <= document.documentElement.scrollHeight; y += step) {
      window.scrollTo(0, y)
      await new Promise((r) => setTimeout(r, 120))
    }
    window.scrollTo(0, document.documentElement.scrollHeight)
    await new Promise((r) => setTimeout(r, 300))
    window.scrollTo(0, 0)
  })
}

interface ImgState {
  src: string
  complete: boolean
  naturalWidth: number
}

async function storageImgStates(page: Page): Promise<ImgState[]> {
  return page.evaluate(
    (prefix) =>
      Array.from(document.querySelectorAll("img"))
        .map((img) => ({ src: img.src, complete: img.complete, naturalWidth: img.naturalWidth }))
        // FULL src, prefix test first: never truncate before this comparison.
        .filter((i) => i.src.startsWith(prefix)),
    STORAGE_PREFIX,
  )
}

test.describe("Storefront images from the object store (Phase 36, BLOB-05)", () => {
  test("a seeded shop's images load from the storage origin with real pixels (naturalWidth > 0), never as fallbacks", async ({
    page,
  }) => {
    const { slug, expected } = await discoverShop(page)

    await page.goto(`/shop/${slug}`, { waitUntil: "domcontentloaded" })
    await scrollThrough(page)

    // Settle on the images themselves: every storage-origin <img> has finished,
    // successfully or not. An error is `complete` too, so a dead store does not
    // hang here. It fails below with the image named.
    const deadline = Date.now() + LOAD_DEADLINE_MS
    let states = await storageImgStates(page)
    while (Date.now() < deadline && states.some((s) => !s.complete)) {
      await page.waitForTimeout(250)
      states = await storageImgStates(page)
    }

    const loaded = states.filter((s) => s.complete && s.naturalWidth > 0)
    console.log(
      `storage-images: shop=${slug} server-rendered=${expected.length} in-dom=${states.length} ` +
        `loaded=${loaded.length} first=${states[0]?.src ?? "(none)"}`,
    )

    expect(
      states.length,
      `VOID: no <img> on /shop/${slug} has a src starting with ${STORAGE_PREFIX}. ` +
        `The page may be showing fallback tiles instead of store images.`,
    ).toBeGreaterThan(0)

    const broken = states.filter((s) => !(s.complete && s.naturalWidth > 0))
    expect(broken, "storage-origin <img> elements that did not decode (naturalWidth 0 or never completed)").toEqual([])

    // A failed <img> is swapped for a fallback <div> and leaves the DOM, so the
    // check above cannot see it. Every image the server rendered from the store
    // must be a loaded <img> in the browser.
    const loadedSrcs = new Set(loaded.map((s) => s.src))
    const missing = expected.filter((src) => !loadedSrcs.has(src))
    expect(
      missing,
      "server-rendered storage-origin images with no loaded <img> in the browser (swapped for a fallback?)",
    ).toEqual([])
  })
})

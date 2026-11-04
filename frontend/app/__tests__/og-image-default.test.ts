/**
 * A default og:image on every public page (SW-CLR-08, swarm epic #964: "no og:image on any page").
 *
 * THE TRAP THIS IS BUILT AROUND. Next resolves metadata one level at a time and a page that sets its own
 * `openGraph` REPLACES the parent's whole `openGraph`: it does not merge into it. So an image declared once
 * in the root layout reaches only the pages that declare no `openGraph` of their own, and the pages that
 * matter most for sharing (the landing page, /for-operators, /shop, /shop/signin) declare one. A test of the
 * root layout alone would pass over all of them. So there are three instruments:
 *
 *   1. the root layout carries the image and the Twitter large-image card, resolved to an ABSOLUTE url
 *      through Next's own resolver when an origin is configured (the metadata-base.test.ts instrument);
 *   2. each page that sets its own `openGraph` ends up with an image after Next's replace-not-merge
 *      semantics, evaluated here as `{ ...root, ...page }` per key, which is what Next does;
 *   3. a source scan: every app/**\/{page,layout}.tsx that writes `openGraph:` also names the helper or its
 *      own `images:`, so a NEW page cannot reintroduce the gap. Its control fixture proves the scan fails.
 *
 * The image is an existing brand asset (the owner's BeesLens photograph already on the landing page, D16),
 * served statically from /public. No new asset, no third-party host, nothing for the CSP to learn.
 */
import fs from "fs"
import path from "path"
import { resolveAbsoluteUrlWithPathname } from "next/dist/lib/metadata/resolvers/resolve-url"
import type { Metadata } from "next"

import { generateMetadata as rootMetadata } from "@/app/layout"
import { metadata as homeMetadata } from "@/app/page"
import { metadata as operatorsMetadata } from "@/app/for-operators/page"
import { metadata as signinMetadata } from "@/app/shop/signin/page"
import { generateMetadata as shopIndexMetadata } from "@/app/shop/page"
import { DEFAULT_OG_IMAGE, openGraphWithDefaultImage } from "@/lib/og-image"

jest.mock("@/lib/storefront-server", () => ({
  loadShopList: jest.fn(),
  loadShopDetail: jest.fn(),
  loadFeaturedShops: jest.fn(),
}))

const ORIGIN = "https://app-staging.example.test"
const VARS = ["APP_PUBLIC_ORIGIN", "NEXTAUTH_URL"] as const
const saved = Object.fromEntries(VARS.map((k) => [k, process.env[k]]))

afterEach(() => {
  for (const k of VARS) {
    if (saved[k] === undefined) delete process.env[k]
    else process.env[k] = saved[k]
  }
})

function absolute(url: string, base: URL | null | undefined): string {
  return resolveAbsoluteUrlWithPathname(url, base ?? null, "/", { trailingSlash: false, isStaticMetadataRouteFile: false })
}

type Images = Array<{ url: string | URL }> | undefined
const imagesOf = (m: Metadata): Images => (m.openGraph as { images?: Images } | null | undefined)?.images

/** Next's own merge for these two keys: a child's value replaces the parent's whole object. */
function effective(root: Metadata, page: Metadata): Metadata {
  return { ...root, ...page }
}

describe("the asset is real", () => {
  it("is a static file under /public that exists, with the dimensions it declares", () => {
    const file = path.join(__dirname, "..", "..", "public", DEFAULT_OG_IMAGE.url.replace(/^\//, ""))
    expect(fs.existsSync(file)).toBe(true)
    expect(DEFAULT_OG_IMAGE.url.startsWith("/")).toBe(true)
    expect(DEFAULT_OG_IMAGE.width).toBeGreaterThanOrEqual(600)
    expect(DEFAULT_OG_IMAGE.alt.length).toBeGreaterThan(10)
  })
})

describe("the root layout", () => {
  it("carries the default image and the Twitter large-image card, absolute on the configured origin", async () => {
    process.env.APP_PUBLIC_ORIGIN = ORIGIN
    const root = await rootMetadata()
    const [first] = imagesOf(root) ?? []
    // (jest's expect takes no message argument; the value itself names what is missing)
    expect({ rootImages: imagesOf(root) }).not.toEqual({ rootImages: undefined })
    expect(absolute(String(first.url), root.metadataBase)).toBe(`${ORIGIN}${DEFAULT_OG_IMAGE.url}`)
    expect((root.twitter as { card?: string }).card).toBe("summary_large_image")
    expect((root.twitter as { images?: unknown[] }).images).toBeDefined()
  })

  it("CONTROL: with no origin configured the url stays root-relative (no host is guessed), as the canonical does", async () => {
    delete process.env.APP_PUBLIC_ORIGIN
    delete process.env.NEXTAUTH_URL
    const root = await rootMetadata()
    expect(root.metadataBase).toBeUndefined()
    expect(absolute(String(imagesOf(root)![0].url), root.metadataBase)).toBe(DEFAULT_OG_IMAGE.url)
  })
})

describe("every page that sets its own openGraph still ends up with an image (replace, not merge)", () => {
  it("the landing page, /for-operators, /shop and /shop/signin", async () => {
    process.env.APP_PUBLIC_ORIGIN = ORIGIN
    const root = await rootMetadata()
    const pages: Array<[string, Metadata]> = [
      ["/", homeMetadata],
      ["/for-operators", operatorsMetadata],
      ["/shop/signin", signinMetadata],
      ["/shop", await shopIndexMetadata()],
    ]
    for (const [route, page] of pages) {
      // Non-vacuity: the page sets an openGraph of its own, which is what replaces the root's.
      expect({ route, setsOwnOpenGraph: Boolean(page.openGraph) }).toEqual({ route, setsOwnOpenGraph: true })
      // The result, per route: a non-empty image list after Next's replace-not-merge.
      expect({ route, images: (imagesOf(effective(root, page)) ?? []).length > 0 }).toEqual({ route, images: true })
    }
  })

  it("INSTRUMENT: without the helper, a page's own openGraph really does drop the root's image", async () => {
    const root = await rootMetadata()
    const bare: Metadata = { openGraph: { title: "x", url: "/x", type: "website" } }
    expect(imagesOf(effective(root, bare))).toBeUndefined()
    expect(imagesOf(effective(root, { openGraph: openGraphWithDefaultImage(bare.openGraph!) }))).toBeDefined()
  })

  it("a page that supplies its own image keeps it", () => {
    const own = openGraphWithDefaultImage({ images: [{ url: "https://cdn.example.test/shop.jpg" }] })
    expect((own as { images: Array<{ url: string }> }).images).toEqual([{ url: "https://cdn.example.test/shop.jpg" }])
  })
})

describe("the source scan: no page can set openGraph without an image again", () => {
  const APP = path.join(__dirname, "..")

  function pagesWithOpenGraph(root: string): string[] {
    const out: string[] = []
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === "__tests__" || entry.name === "node_modules") continue
        const full = path.join(dir, entry.name)
        if (entry.isDirectory()) walk(full)
        else if (/^(page|layout)\.tsx$/.test(entry.name) && /\bopenGraph\s*:/.test(fs.readFileSync(full, "utf8"))) out.push(full)
      }
    }
    walk(root)
    return out
  }

  /** The rule: the file names the helper, or declares an `images:` of its own. */
  const satisfies = (source: string) => /openGraphWithDefaultImage|DEFAULT_OG_IMAGE/.test(source) || /\bimages\s*:/.test(source)

  it("every page or layout that writes openGraph names the helper or its own images", () => {
    const files = pagesWithOpenGraph(APP)
    // Non-vacuity: the walk found the pages (a walk that finds none would pass the next line trivially).
    expect(files.length).toBeGreaterThanOrEqual(6)
    const offenders = files.filter((f) => !satisfies(fs.readFileSync(f, "utf8"))).map((f) => path.relative(APP, f))
    expect(offenders).toEqual([])
  })

  it("INSTRUMENT: a page that writes openGraph and names neither is caught", () => {
    expect(satisfies("export const metadata = { openGraph: { title: 'x' } }")).toBe(false)
    expect(satisfies("openGraph: openGraphWithDefaultImage({ title: 'x' })")).toBe(true)
    expect(satisfies("openGraph: { images: [] }")).toBe(true)
  })
})

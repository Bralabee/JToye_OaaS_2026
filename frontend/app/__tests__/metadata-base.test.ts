/**
 * Absolute canonical and og:url on the public pages (SW-CLR-08, swarm epic #964).
 *
 * THE FINDING. The landing page, /for-operators and /legal emitted a RELATIVE
 * canonical and og:url. Next resolves those strings against `metadataBase`, and
 * with no metadataBase it passes them through unchanged — a relative canonical
 * is ignored by most crawlers, and a relative og:url breaks the share card.
 * app/shop/layout.tsx already set metadataBase for /shop/**; nothing set it for
 * the rest of the app.
 *
 * THE INSTRUMENT IS NEXT'S OWN RESOLVER. Asserting "metadataBase is a URL" alone
 * would pass while the pages still emitted relative strings, so each page's
 * canonical and og:url are put through `resolveAbsoluteUrlWithPathname` — the
 * function Next's metadata pipeline calls for alternates and Open Graph — with
 * the root layout's metadataBase, and the RESULT must be absolute on the
 * configured origin. The control arm runs the same resolver with no base and
 * gets the relative string back, which is the defect reproduced.
 *
 * NO HOST IS GUESSED. The origin comes from resolvePublicOrigin() (APP_PUBLIC_
 * ORIGIN, then NEXTAUTH_URL — runtime envs every environment already sets), and
 * with neither set metadataBase is undefined rather than a made-up hostname.
 */
import { resolveAbsoluteUrlWithPathname } from "next/dist/lib/metadata/resolvers/resolve-url"
import type { Metadata } from "next"
import { generateMetadata as rootMetadata } from "@/app/layout"
import { metadata as homeMetadata } from "@/app/page"
import { metadata as operatorsMetadata } from "@/app/for-operators/page"
import { metadata as legalMetadata } from "@/app/legal/page"
import { metadata as termsMetadata } from "@/app/legal/terms/page"
import { metadata as contactMetadata } from "@/app/contact/page"

const ORIGIN = "https://app-staging.example.test"
const VARS = ["APP_PUBLIC_ORIGIN", "NEXTAUTH_URL"] as const
const saved = Object.fromEntries(VARS.map((k) => [k, process.env[k]]))

afterEach(() => {
  for (const k of VARS) {
    if (saved[k] === undefined) delete process.env[k]
    else process.env[k] = saved[k]
  }
})

function resolve(url: string, base: URL | null | undefined, pathname: string): string {
  return resolveAbsoluteUrlWithPathname(url, base ?? null, pathname, { trailingSlash: false, isStaticMetadataRouteFile: false })
}

function canonicalOf(m: Metadata): string {
  const c = (m.alternates as { canonical?: string } | undefined)?.canonical
  if (!c) throw new Error("VOID: page declares no canonical")
  return c
}

const PAGES: Array<[string, Metadata]> = [
  ["/", homeMetadata],
  ["/for-operators", operatorsMetadata],
  ["/legal", legalMetadata],
  ["/legal/terms", termsMetadata],
  ["/contact", contactMetadata],
]

/**
 * The pages that declare an og:url. A plain literal, not PAGES.filter(...):
 * scripts/count-test-blocks.mjs resolves an .each table only when it is an
 * array literal bound to a name, and fails closed otherwise. The completeness
 * test below keeps this list honest against PAGES.
 */
const OG_PAGES: Array<[string, Metadata]> = [
  ["/", homeMetadata],
  ["/for-operators", operatorsMetadata],
]

describe("root layout metadataBase (SW-CLR-08)", () => {
  it("is the configured public origin", async () => {
    delete process.env.APP_PUBLIC_ORIGIN
    process.env.NEXTAUTH_URL = `${ORIGIN}/`
    const meta = await rootMetadata()
    expect(meta.metadataBase).toBeInstanceOf(URL)
    expect((meta.metadataBase as URL).origin).toBe(ORIGIN)
    // The root title and description survive the move to generateMetadata.
    expect(String(meta.title).length).toBeGreaterThan(0)
  })

  it("is undefined, not a guessed host, when no origin is configured", async () => {
    for (const k of VARS) delete process.env[k]
    const meta = await rootMetadata()
    expect(meta.metadataBase).toBeUndefined()
  })
})

describe("public pages resolve to absolute URLs under the root metadataBase (SW-CLR-08)", () => {
  it("CONTROL: without a metadataBase Next passes the canonical through relative", () => {
    expect(resolve(canonicalOf(operatorsMetadata), null, "/for-operators")).toBe("/for-operators")
  })

  it.each(PAGES)("%s canonical resolves absolute on the configured origin", async (path, meta) => {
    process.env.NEXTAUTH_URL = ORIGIN
    const base = (await rootMetadata()).metadataBase as URL
    const abs = resolve(canonicalOf(meta), base, path)
    expect(abs.startsWith(ORIGIN)).toBe(true)
    expect(new URL(abs).pathname).toBe(path)
  })

  it("OG_PAGES lists exactly the pages in PAGES that declare an og:url", () => {
    const declared = PAGES.filter(([, m]) => m.openGraph?.url).map(([p]) => p)
    expect(OG_PAGES.map(([p]) => p)).toEqual(declared)
  })

  it.each(OG_PAGES)(
    "%s og:url resolves absolute on the configured origin",
    async (path, meta) => {
      process.env.NEXTAUTH_URL = ORIGIN
      const base = (await rootMetadata()).metadataBase as URL
      const abs = resolve(String(meta.openGraph?.url), base, path)
      expect(abs.startsWith(ORIGIN)).toBe(true)
    }
  )
})

/**
 * FE-5: `/`, `/for-operators` and `/business-model-guide` were missing
 * canonical + Open Graph metadata — every OTHER public marketing surface
 * (`/competitive`, the five `/legal/*` pages) already carries both. A page
 * with no canonical is vulnerable to duplicate-content signals from tracking
 * query params (`?utm_source=...`); a page with no Open Graph renders as a
 * bare link with no title/description card when shared on social/messaging
 * platforms — for the landing page specifically, the one page most likely to
 * be shared externally.
 *
 * These are plain object-export assertions (no render, no DOM) — the same
 * shape `app/unsubscribe/__tests__/unsubscribe-page.test.tsx` already uses
 * for its `robots` metadata check.
 */
import { metadata as homeMetadata } from "@/app/page"
import { metadata as operatorsMetadata } from "@/app/for-operators/page"
import { metadata as guideMetadata } from "@/app/business-model-guide/page"
import { metadata as trackMetadata } from "@/app/track/layout"
import type { Metadata } from "next"

function canonicalOf(m: Metadata): string | undefined {
  const alt = m.alternates as { canonical?: string } | undefined
  return alt?.canonical
}

describe("Public marketing pages carry canonical + Open Graph (FE-5)", () => {
  const pages: Array<[string, Metadata, string]> = [
    ["/", homeMetadata, "/"],
    ["/for-operators", operatorsMetadata, "/for-operators"],
    ["/business-model-guide", guideMetadata, "/business-model-guide"],
  ]

  it.each(pages)("%s declares a canonical matching its own route", (_label, meta, path) => {
    expect(canonicalOf(meta)).toBe(path)
  })

  it.each(pages)("%s declares Open Graph title/description/url", (_label, meta, path) => {
    const og = meta.openGraph as
      | { title?: unknown; description?: unknown; url?: string }
      | undefined
    expect(og).toBeDefined()
    expect(typeof og?.title).toBe("string")
    expect((og?.title as string).length).toBeGreaterThan(0)
    expect(typeof og?.description).toBe("string")
    expect((og?.description as string).length).toBeGreaterThan(0)
    expect(og?.url).toBe(path)
  })

  it.each(pages)("%s still has its own non-empty title and description (control)", (_label, meta) => {
    // Non-vacuity control: canonical/OG could be asserted correctly over a
    // page that has otherwise lost its title/description entirely.
    expect(typeof meta.title).toBe("string")
    expect((meta.title as string).length).toBeGreaterThan(0)
    expect(typeof meta.description).toBe("string")
    expect((meta.description as string).length).toBeGreaterThan(0)
  })
})

/**
 * FE-6: `/track` previously had NO page-level metadata (it inherited the root
 * layout's generic "J'Toye OaaS - Multi-Tenant Order Management" title) and
 * was simultaneously listed in `app/sitemap.ts` (indexed) and
 * `app/robots.ts`'s DISALLOW (not crawlable) — a direct contradiction.
 */
describe("/track has a real title and is consistently non-indexed (FE-6)", () => {
  it("carries its own descriptive title rather than the generic root fallback", () => {
    expect(trackMetadata.title).not.toBe("J'Toye OaaS - Multi-Tenant Order Management")
    expect(typeof trackMetadata.title).toBe("string")
    expect((trackMetadata.title as string).toLowerCase()).toContain("track")
  })

  it("declares robots.index=false, matching its robots.txt disallow", () => {
    const robots = trackMetadata.robots as { index?: boolean; follow?: boolean } | undefined
    expect(robots?.index).toBe(false)
    expect(robots?.follow).toBe(false)
  })
})

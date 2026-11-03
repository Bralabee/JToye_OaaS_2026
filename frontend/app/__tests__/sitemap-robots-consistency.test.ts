/**
 * FE-6: `sitemap.xml` and `robots.txt` must agree. `/track` used to be in
 * BOTH `app/sitemap.ts`'s STATIC_ROUTES (crawl + index this) and
 * `app/robots.ts`'s DISALLOW list (do not fetch this) — a direct
 * contradiction that a prior plan found and deliberately left
 * ("a pre-existing inconsistency ... it is not this plan's file to
 * reconcile").
 *
 * This is a GENERAL contract, not a one-off `/track` regex: any route
 * robots.ts disallows OUTRIGHT (no wildcard) must never appear as a sitemap
 * entry. A wildcard disallow (`/shop/*\/cart`) is deliberately excluded from
 * the general sweep — matching it against a concrete sitemap path needs glob
 * semantics this test does not attempt — but `/track` is asserted by exact
 * path as the concrete regression case FE-6 is about.
 */
import sitemap from "@/app/sitemap"
import robots from "@/app/robots"

jest.mock("@/lib/storefront-server", () => ({
  loadAllShopSlugs: jest.fn(() => Promise.resolve([])),
}))

function disallowList(): string[] {
  const { rules } = robots()
  const ruleList = Array.isArray(rules) ? rules : [rules]
  return ruleList.flatMap((r) => {
    const d = r?.disallow
    if (!d) return []
    return Array.isArray(d) ? d : [d]
  })
}

async function sitemapPaths(): Promise<string[]> {
  const entries = await sitemap()
  // NON-VACUITY CONTROL asserted by the caller: an empty sitemap (no trusted
  // origin under jest) would make every "not contained" assertion below pass
  // for the wrong reason.
  return entries.map((e) => new URL(e.url).pathname)
}

describe("sitemap.xml / robots.txt agree (FE-6)", () => {
  it("the instrument is not blind — robots disallows real paths and sitemap lists real routes", async () => {
    expect(disallowList().length).toBeGreaterThan(0)
    expect((await sitemapPaths()).length).toBeGreaterThan(0)
  })

  it("no EXACT (non-wildcard) robots disallow entry appears as a sitemap path", async () => {
    const exact = disallowList().filter((d) => !d.includes("*"))
    const paths = await sitemapPaths()
    const contradictions = exact.filter((d) => paths.includes(d))
    expect(contradictions).toEqual([])
  })

  it("/track specifically: disallowed in robots.txt, absent from the sitemap", async () => {
    expect(disallowList()).toContain("/track")
    expect(await sitemapPaths()).not.toContain("/track")
  })
})

/**
 * SW-CLR-05 (2026-10-19 staging persona swarm, epic issue 964): a page that tells crawlers
 * `noindex` must not also be offered to them in the sitemap. `/business-model-guide` and
 * `/competitive` went noindex and left the footer in PR 1080; the guide's sitemap entry was left
 * until after Phase 30 plan 30-19 and is now removed.
 */
describe("sitemap.xml lists no noindex page (SW-CLR-05)", () => {
  it.each([
    ["/business-model-guide", () => import("@/app/business-model-guide/page")],
    ["/competitive", () => import("@/app/competitive/page")],
  ])("%s is noindex and absent from the sitemap", async (path, load) => {
    const { metadata } = (await load()) as { metadata: { robots?: { index?: boolean } } }
    // CONTROL: the page really is noindex, so its absence is the agreement and not an accident.
    expect(metadata.robots?.index).toBe(false)
    const paths = await sitemapPaths()
    expect(paths.length).toBeGreaterThan(0)
    expect(paths).not.toContain(path)
  })
})

import type { MetadataRoute } from "next"
import { resolvePublicOrigin } from "@/lib/public-origin"
import { loadAllShopSlugs } from "@/lib/storefront-server"

// Machine-readable sitemap for the PUBLIC surface only (served at /sitemap.xml).
//
// SHOP PAGES ARE NOW INCLUDED. THE RECORDED REASON FOR EXCLUDING THEM NO LONGER
// HOLDS, AND THE REVERSAL IS DELIBERATE.
//
// The previous comment read: "per-shop dynamic storefronts are deliberately
// excluded: dashboards must not be indexed, and shop slugs are tenant data that
// would require a DB round-trip at build time."
//
//  - The dashboard half is still correct and is unchanged. Authenticated vendor
//    routes stay out, and `app/robots.ts` disallows them explicitly.
//  - The shop half rested on "at build time", and there is no build time here.
//    `app/layout.tsx` sets `dynamic = "force-dynamic"` app-wide for the CSP
//    nonce, this file now declares it too, and the slugs come from
//    `/public/shops` — a PUBLIC HTTP endpoint the storefront index already calls
//    on every request. No database is touched and nothing is read at build.
//  - "Shop slugs are tenant data" is true of the slug's origin, not of its
//    visibility: every one of these pages is already unauthenticated, already
//    linked from `/shop`, and its whole purpose is to be found. A sitemap that
//    omits them withholds the one thing the product promises a vendor.
//
// #447 measured the cost: the sitemap omitted all three shop pages while 28
// priced, allergen-tagged dishes sat behind them.
//
// THE BASE URL ALSO CHANGED, AND THAT MATTERS MORE THAN THE COVERAGE.
// It was `process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3100"`. Two
// defects in one line:
//
//  1. `NEXT_PUBLIC_*` is inlined by Next at BUILD time — into the server bundle
//     as well as the browser one (see lib/public-origin.ts, which documents the
//     measurement). A deployment cannot correct it with an `environment:` entry,
//     and `k8s/scripts/check-env-contract.sh:210` carries this exact caveat as a
//     known, reviewed omission: "sitemap.ts:11 falls back to
//     http://localhost:3100, so sitemap.xml advertises a loopback origin in
//     every k8s environment".
//  2. This route built to `○ (Static)`, so that fallback was frozen into the
//     image at build time regardless of what any runtime variable said.
//
// `resolvePublicOrigin()` is the project's answer to precisely this: a plain
// runtime env (`APP_PUBLIC_ORIGIN` -> `NEXTAUTH_URL`), already set correctly in
// compose and in every k8s overlay, already required by lib/env-validation.ts,
// and already rejecting bind addresses. `NEXT_PUBLIC_SITE_URL` is kept as a
// first-priority override so nothing that sets it today changes behaviour.
//
// NO HOSTNAME IS WRITTEN HERE. The production domain is unsettled — jtoye.co.uk
// serves a registrar placeholder with no TLS and olajay.co.uk does not resolve —
// so guessing one would publish a canonical set of URLs that 404.
//
// The human audience-classified inventory (all 22 page ROUTES, incl. dashboard)
// lives in docs/SITEMAP.md; adding shop INSTANCES here adds no new route, so the
// two remain in sync.
export const dynamic = "force-dynamic"

/**
 * Resolve the origin, or null.
 *
 * `NEXT_PUBLIC_SITE_URL` stays first for back-compatibility with any deployment
 * that already sets it; `resolvePublicOrigin()` is the fallback that actually
 * works at runtime. Returning null rather than a loopback guess is the point:
 * see below for what the sitemap degrades to.
 */
function siteOrigin(): string | null {
  const explicit = process.env.NEXT_PUBLIC_SITE_URL?.trim()
  if (explicit) {
    try {
      return new URL(explicit).origin
    } catch {
      /* malformed — fall through to the resolved origin */
    }
  }
  return resolvePublicOrigin()
}

const STATIC_ROUTES: Array<{
  path: string
  changeFrequency: MetadataRoute.Sitemap[number]["changeFrequency"]
  priority: number
}> = [
  { path: "/", changeFrequency: "weekly", priority: 1 },
  { path: "/shop", changeFrequency: "daily", priority: 0.9 },
  { path: "/for-operators", changeFrequency: "monthly", priority: 0.8 },
  { path: "/business-model-guide", changeFrequency: "monthly", priority: 0.5 },
  { path: "/track", changeFrequency: "monthly", priority: 0.4 },
]

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const now = new Date()
  const origin = siteOrigin()
  // A sitemap MUST carry absolute URLs (the protocol requires them, and a
  // crawler has no document to resolve a relative one against). With no
  // trustworthy origin the honest answer is an empty sitemap — still a valid
  // 200 document — rather than a list of URLs pointing at a loopback address or
  // a guessed domain, both of which actively mislead.
  if (!origin) return []

  // Never throws: `loadAllShopSlugs` swallows its own failures and returns [].
  // A shop-lookup outage must degrade to the static list, not 500 /sitemap.xml.
  const slugs = await loadAllShopSlugs()

  return [
    ...STATIC_ROUTES.map((r) => ({
      url: `${origin}${r.path}`,
      lastModified: now,
      changeFrequency: r.changeFrequency,
      priority: r.priority,
    })),
    ...slugs.map((slug) => ({
      url: `${origin}/shop/${slug}`,
      lastModified: now,
      // A storefront's menu, stock and promotions change on a daily rhythm —
      // the same cadence already claimed for /shop, which lists them.
      changeFrequency: "daily" as const,
      priority: 0.8,
    })),
  ]
}

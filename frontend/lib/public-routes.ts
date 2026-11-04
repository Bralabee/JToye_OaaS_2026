import type { MetadataRoute } from "next"

/**
 * The public, indexable pages that are not shop pages: what `app/sitemap.ts` lists ahead of the
 * per-shop entries, and the pages a customer sign-out may return to (`lib/sign-out-return.ts`). One
 * list, so "public enough to index" and "public enough to land on after signing out" cannot
 * disagree. Moved here unchanged from `app/sitemap.ts`, which imports a server-only loader; this
 * module has no such import, so client code can read it.
 */
export const PUBLIC_STATIC_ROUTES: ReadonlyArray<{
  path: string
  changeFrequency: MetadataRoute.Sitemap[number]["changeFrequency"]
  priority: number
}> = [
  { path: "/", changeFrequency: "weekly", priority: 1 },
  { path: "/shop", changeFrequency: "daily", priority: 0.9 },
  { path: "/for-operators", changeFrequency: "monthly", priority: 0.8 },
  // Persona close-out (issues 803, 804): the pages a prospective vendor needs. /pricing, /about
  // and the merchant terms did not exist (404); /contact and /legal/terms existed but were
  // unlisted. All five are in the footer, so a crawler reaches them by link as well.
  { path: "/pricing", changeFrequency: "monthly", priority: 0.7 },
  { path: "/about", changeFrequency: "monthly", priority: 0.5 },
  { path: "/contact", changeFrequency: "monthly", priority: 0.5 },
  // The two internal strategy pages were deleted (owner ruling 2026-11-04, #836), so there is
  // nothing of theirs to list.
  // FE-6 (RESOLVED, was: "a pre-existing inconsistency, left alone"): `/track`
  // used to be listed here AND in `app/robots.ts`'s DISALLOW list — telling
  // crawlers to index a page they were also told not to fetch. It is now
  // absent from this file entirely, resolved on the disallow side: a guest
  // order-lookup form is a state, not a destination, matching every other
  // mid-journey route already excluded (`/unsubscribe`, `/shop/*/cart`,
  // `/shop/*/checkout`). `app/track/layout.tsx` carries the matching
  // `robots.index=false` belt-and-braces meta.
  //
  // The published policy set (LGL-01). None of these was here, so a crawler
  // reaching the sitemap was told about five pages fewer than the site has.
  //
  // THEY ARE IN THE SITEMAP AND NOT DISALLOWED, WHICH WAS CHECKED RATHER THAN
  // ASSUMED: `app/robots.ts`'s DISALLOW list is /api/, /auth/, /dashboard,
  // /shop/orders, /shop/signin, /shop/auth/, /shop/*/cart, /shop/*/checkout,
  // /shop/*/orders/, /track and /unsubscribe. No /legal prefix appears in it,
  // so no robots change is needed.
  //
  // Priority sits at 0.3: these pages must be indexable and findable, but they
  // must not outrank a storefront, which is what the product actually sells.
  // `yearly` because a policy that changed weekly would be a different problem.
  { path: "/legal", changeFrequency: "yearly", priority: 0.3 },
  { path: "/legal/privacy", changeFrequency: "yearly", priority: 0.3 },
  { path: "/legal/cookies", changeFrequency: "yearly", priority: 0.3 },
  { path: "/legal/retention", changeFrequency: "yearly", priority: 0.3 },
  { path: "/legal/accessibility", changeFrequency: "yearly", priority: 0.3 },
  { path: "/legal/terms", changeFrequency: "yearly", priority: 0.3 },
  { path: "/legal/merchant-terms", changeFrequency: "yearly", priority: 0.3 },
]

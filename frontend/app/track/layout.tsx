import type { Metadata } from "next"

/**
 * FE-6: `/track` had no page-level metadata at all, so it inherited the ROOT
 * layout's generic title ("J'Toye OaaS - Multi-Tenant Order Management") — a
 * string that describes the platform, not what this page does. `page.tsx` is
 * a `"use client"` component and cannot export `metadata` (Next only reads
 * that export from a server module); a `layout.tsx` is always a server
 * component even when the page beneath it is a client one, so this is the
 * additive fix — a real title, with no change to the tested `TrackOrderPage`
 * client component at all.
 *
 * ROBOTS/SITEMAP CONSISTENCY (the other half of FE-6). `/track` was
 * simultaneously:
 *   - in `app/robots.ts`'s DISALLOW list (crawlers told not to fetch it), and
 *   - in `app/sitemap.ts`'s STATIC_ROUTES (crawlers told to index it) —
 *     a direct contradiction, previously recorded and deliberately left by a
 *     prior plan ("a pre-existing inconsistency, left alone: it is not this
 *     plan's file to reconcile").
 *
 * Resolved on the "do not index" side, matching every other utility /
 * mid-journey route already treated this way in this repo
 * (`/unsubscribe`, `/shop/*\/cart`, `/shop/*\/checkout`): a guest order-lookup
 * FORM has no fixed content of its own to rank, and indexing it would compete
 * with the storefront page that should rank instead — the exact reasoning
 * `app/robots.ts`'s own DISALLOW comment already gives. `robots.index=false`
 * here is the belt-and-braces sibling of the robots.txt disallow (the same
 * pattern `app/unsubscribe/page.tsx` already uses): a disallow only stops
 * crawling, it does not guarantee a URL discovered via an external link stays
 * out of results, so the page also declares it directly. `app/sitemap.ts`'s
 * entry for `/track` is removed in the same commit — a noindex page has no
 * business being advertised in a sitemap.
 */
export const metadata: Metadata = {
  title: "Track your order — J'Toye",
  description:
    "Look up the live status of your J'Toye order by order number and email.",
  robots: { index: false, follow: false },
}

export default function TrackLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return children
}

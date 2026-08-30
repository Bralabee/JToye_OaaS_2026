import type { Metadata } from "next"
import Link from "next/link"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { PublicFooter } from "@/components/public/public-footer"
import { resolvePublicOrigin } from "@/lib/public-origin"

/**
 * Storefront-wide metadata DEFAULTS.
 *
 * This used to be the only metadata anywhere under /shop, which is how `/shop`
 * and all three `/shop/[slug]` pages came to agree on one `<title>` across 4/4
 * cells (#447 / F-H9-SEOMETA). Both of those routes now export their own
 * `generateMetadata`, which overrides everything here; what remains below is the
 * fallback for the storefront's utility routes (cart, checkout, sign-in, OIDC
 * callback) — none of which is indexable anyway (`app/robots.ts`).
 *
 * `metadataBase` is set HERE rather than repeated per page so every relative
 * canonical and OG URL under /shop resolves against one injected origin. It is a
 * function, not a constant, so the value is read per request at RUNTIME:
 * `NEXTAUTH_URL` is a plain server env that each environment sets correctly,
 * unlike a `NEXT_PUBLIC_*` which Next inlines at build time. `undefined` is a
 * deliberate outcome, not a failure — Next then emits root-relative URLs, which
 * are correct on whatever host served the page, and no hostname is guessed.
 */
export async function generateMetadata(): Promise<Metadata> {
  const origin = resolvePublicOrigin()
  return {
    metadataBase: origin ? new URL(origin) : undefined,
    title: "J'Toye — Discover Local Vendors",
    description: "Browse and order from independent food vendors near you",
  }
}

/**
 * Storefront chrome. Deliberately mirrors the marketing shell
 * (components/public/public-shell.tsx) so a shopper crossing from the landing
 * page into /shop stays inside one brand: same 56px sticky bar, same oxblood
 * wordmark, same cream page ground, and the SAME PublicFooter — the storefront
 * used to end in a thin grey strip that read like a different product.
 *
 * It keeps its own <StorefrontNav> (cart count + customer session) rather than
 * the marketing nav, which is the one justified difference.
 *
 * Wordmark -> "/" (never /shop): the logo is constant across every surface.
 *
 * SKIP LINK (A11Y-4). Every other public route is served through
 * `components/public/public-shell.tsx`, which carries the skip-link + `id="main"`
 * pair documented at length there (WCAG 2.4.1 Bypass Blocks). This layout is a
 * SEPARATE component tree — the storefront keeps its own header for the cart
 * badge/session nav — so it does not inherit that fix for free, and every route
 * under /shop shipped without it. The markup here is copied verbatim from
 * `PublicShell` for the same reason that file gives: a fourth hand-rolled copy
 * of this pattern would be drift, not a fix.
 */
export default function StorefrontLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <div className="min-h-screen bg-cream flex flex-col">
      <a href="#main" className="sr-only z-50 rounded-full bg-oxblood px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to main content</a>
      <header className="sticky top-0 z-50 bg-white border-b border-cream-100 shadow-sm">
        {/* PHASE 35 / UIX-07 — the DECLARED Marketing width tier, applied IN PLACE.
            The rail's rendered width is unchanged. It is a deliberate verbatim
            mirror of PublicShell's header rail and must stay equal to it, so the
            storefront chrome and the content below it stay aligned; declaring the
            same tier on both is what makes that agreement checkable instead of a
            coincidence of two files having picked the same stock scale token. */}
        <div
          data-width-tier="marketing"
          className={`mx-auto ${WIDTH_TIER_CLASS.marketing} px-4 sm:px-6 lg:px-8`}
        >
          <div className="flex h-14 items-center justify-between">
            <Link
              href="/"
              aria-label="J'Toye home"
              className="flex items-center gap-2 text-lg font-semibold tracking-tight text-oxblood"
            >
              <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-oxblood text-sm font-bold text-white">
                J
              </span>
              <span>J&apos;Toye</span>
            </Link>
            <StorefrontNav />
          </div>
        </div>
      </header>

      <main id="main" className="flex-1">{children}</main>

      <PublicFooter />
    </div>
  )
}

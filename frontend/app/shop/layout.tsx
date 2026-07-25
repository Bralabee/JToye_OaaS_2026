import type { Metadata } from "next"
import Link from "next/link"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { PublicFooter } from "@/components/public/public-footer"

export const metadata: Metadata = {
  title: "J'Toye — Discover Local Vendors",
  description: "Browse and order from independent food vendors near you",
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
 */
export default function StorefrontLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <div className="min-h-screen bg-cream flex flex-col">
      <header className="sticky top-0 z-50 bg-white border-b border-cream-100 shadow-sm">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
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

      <main className="flex-1">{children}</main>

      <PublicFooter />
    </div>
  )
}

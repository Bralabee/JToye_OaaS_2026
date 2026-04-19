import type { Metadata } from "next"
import Link from "next/link"
import Image from "next/image"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { BRAND } from "@/lib/brand"

export const metadata: Metadata = {
  title: `${BRAND.name} — Discover Local Vendors`,
  description: "Browse and order from independent food vendors near you",
}

export default function StorefrontLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <div className="min-h-screen bg-surface-canvas text-ink-primary flex flex-col">
      {/* Compact editorial header */}
      <header className="sticky top-0 z-40 bg-surface-card/90 backdrop-blur-sm border-b border-subtle">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-14 items-center justify-between">
            <Link
              href="/shop"
              className="flex items-center gap-2"
              aria-label={`${BRAND.fullName} storefront home`}
            >
              <Image
                src={BRAND.marks.wordmark}
                alt={BRAND.fullName}
                width={96}
                height={24}
                priority
                className="h-6 w-auto"
              />
            </Link>
            <StorefrontNav />
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1">{children}</main>

      {/* Footer */}
      <footer className="border-t border-subtle bg-surface-card">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8">
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
            <p className="text-sm text-ink-tertiary">
              &copy; {new Date().getFullYear()} {BRAND.fullName}. All rights reserved.
            </p>
            <div className="flex gap-6 text-sm text-ink-tertiary">
              <span>Allergen info available on all products</span>
            </div>
          </div>
        </div>
      </footer>
    </div>
  )
}

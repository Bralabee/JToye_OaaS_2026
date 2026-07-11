import type { Metadata } from "next"
import Link from "next/link"
import { StorefrontNav } from "@/components/storefront/storefront-nav"

export const metadata: Metadata = {
  title: "J'Toye — Discover Local Vendors",
  description: "Browse and order from independent food vendors near you",
}

export default function StorefrontLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      {/* Compact, clean header */}
      <header className="sticky top-0 z-50 bg-white border-b border-slate-200 shadow-sm">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-14 items-center justify-between">
            <Link
              href="/shop"
              className="flex items-center gap-2 text-lg font-semibold tracking-tight text-slate-900"
            >
              <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-orange-500 text-sm font-bold text-white">
                J
              </span>
              <span>J&apos;Toye</span>
            </Link>
            <StorefrontNav />
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1">{children}</main>

      {/* Footer */}
      <footer className="border-t border-slate-200 bg-white">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8">
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
            <p className="text-sm text-slate-500">
              &copy; {new Date().getFullYear()} J&apos;Toye OaaS. All rights reserved.
            </p>
            <div className="flex items-center gap-6 text-sm text-slate-500">
              <Link
                href="/for-operators"
                className="transition-colors hover:text-slate-900"
              >
                For operators
              </Link>
              <span>Allergen info available on all products</span>
            </div>
          </div>
        </div>
      </footer>
    </div>
  )
}

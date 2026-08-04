import type { Metadata } from "next"
import Link from "next/link"
import { ArrowLeft, Store } from "lucide-react"

/**
 * "Shop not found" (issue #447).
 *
 * The copy, the icon and the one-tap route back to the directory are exactly
 * what `/shop/[slug]` rendered inline before — this is the same screen, moved,
 * so nothing a customer sees is lost.
 *
 * WHAT MOVING IT BUYS: `robots: noindex` and a distinct title, which the inline
 * version could not have (the page's own `generateMetadata` had already
 * published the shop's title by then). A dead storefront can now be served but
 * not INDEXED, which is the actual harm of a soft 404 — an indexed dead vendor
 * page competing with live ones. The HTTP status is still 200 and that is a
 * measured limitation, not an oversight: see the note in page.tsx.
 *
 * Scoped to this route segment, so nothing else inherits it.
 */

export const metadata: Metadata = {
  title: "Kitchen not found — J'Toye",
  description: "This kitchen is no longer available on J'Toye.",
  robots: { index: false, follow: true },
}

export default function ShopNotFound() {
  return (
    <div className="mx-auto max-w-4xl px-4 py-16 text-center">
      <Store className="mx-auto h-12 w-12 text-slate-300" />
      <h1 className="mt-4 text-lg font-semibold text-oxblood">Shop not found</h1>
      <p className="mt-1 text-sm text-slate-600">
        This shop may no longer be available.
      </p>
      <Link
        href="/shop"
        className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-amber-700 hover:text-amber-800"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to all shops
      </Link>
    </div>
  )
}

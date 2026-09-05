import type { Metadata } from "next"
import Link from "next/link"
import { ArrowLeft, Compass, ShoppingBag } from "lucide-react"
import { PublicShell } from "@/components/public/public-shell"

/**
 * The GLOBAL 404 (FE-2).
 *
 * There was no `app/not-found.tsx`, so an unknown URL got Next's built-in page:
 * measured in the browser at 1280 and 390 — 0 `<header>`, 0 `<footer>`, 0
 * `<nav>`, and the only anchor in the whole document was `/legal/cookies`, which
 * comes from the cookie notice in the root layout rather than from any
 * navigation. A visitor who mistyped a URL or followed a dead inbound link could
 * not reach `/`, `/shop` or anything else. That is a dead end, and it was a
 * strictly worse instance of the `/unsubscribe` case (FEB-6) already fixed for
 * this exact reason — that page at least had footer links.
 *
 * WHY THE ROOT AND NOT A ROUTE GROUP. `app/` has no route groups, so this file
 * renders inside `app/layout.tsx` AND NOTHING ELSE: Next does not run segment
 * layouts for an unmatched path, so `app/dashboard/layout.tsx` (which calls
 * `auth()` and redirects) never executes. The chrome therefore has to come from
 * this file, which is why it wraps `PublicShell` exactly as `app/page.tsx` does.
 *
 * KNOWN TRADE, RECORDED: a signed-in vendor who mistypes `/dashboard/xyz` now
 * gets PUBLIC chrome rather than dashboard chrome. Strictly better than a page
 * with no chrome at all; a `app/dashboard/not-found.tsx` is a separate additive
 * follow-up, not a silent drop.
 *
 * `PublicShell` already supplies the `id="main"` landmark and the skip link, so
 * this file adds no `<main>` of its own — a second one would be a landmark
 * violation.
 *
 * The HTTP status stays 404. Next serves this file WITH the 404 status for an
 * unmatched route; a 200 here would trade FE-2 for a soft-404 (FE-12), which is
 * why the proof asserts the status alongside the chrome.
 */
export const metadata: Metadata = {
  title: "Page not found — J'Toye",
  description: "That page does not exist. Browse local kitchens or track an order instead.",
  // Never indexed — but crawlers may follow the way out, which is the entire
  // point of the links below. Same posture as app/shop/[slug]/not-found.tsx.
  robots: { index: false, follow: true },
}

export default function NotFound() {
  return (
    <PublicShell>
      <div className="mx-auto max-w-2xl px-4 sm:px-6 py-16 sm:py-24 text-center">
        <Compass className="mx-auto h-12 w-12 text-oxblood-600" aria-hidden="true" />
        <p className="mt-4 text-sm font-semibold uppercase tracking-wider text-slate-600">404</p>
        <h1 className="mt-1 text-2xl font-bold text-slate-900 sm:text-3xl">
          We can&apos;t find that page
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          The link may be out of date, or the address may have a typo. Everything
          else is still here.
        </p>

        {/* Escape hatches — the same three this product already offers on its
            other landing destinations (components/storefront/customer-signin-card.tsx).
            A 404 is a landing destination like any other: it is reached from
            outside, and the only thing it owes the visitor is a way onward. */}
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Link
            href="/shop"
            className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood px-6 py-3 text-sm font-bold text-white transition-colors hover:bg-oxblood-700 sm:w-auto"
          >
            <ShoppingBag className="h-4 w-4" aria-hidden="true" />
            Browse kitchens
          </Link>
          <Link
            href="/track"
            className="inline-flex w-full items-center justify-center gap-2 rounded-2xl border border-cream-100 px-6 py-3 text-sm font-medium text-slate-600 transition-colors hover:bg-cream sm:w-auto"
          >
            Track an order
          </Link>
        </div>

        <Link
          href="/"
          className="mt-6 inline-flex items-center gap-1 text-sm font-medium text-amber-700 transition-colors hover:text-amber-800"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          Back to the home page
        </Link>
      </div>
    </PublicShell>
  )
}

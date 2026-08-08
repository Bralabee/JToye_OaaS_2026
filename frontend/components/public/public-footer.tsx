"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { useCustomerSession } from "@/hooks/use-customer-session"

/**
 * Shared public footer (Surface B). De-orphans /track and /business-model-guide
 * (backlog #5) and gives /for-operators a second inbound link.
 *
 * Oxblood brand anchor (sketch 004 winner D, matched to jtoyedigital.co.uk).
 *
 * PERSONA GATING (#458 items 1a, 2)
 * ---------------------------------
 * PR #508 gated both headers and deliberately left this component alone, on the
 * reasoning that the two destinations had to stay reachable somewhere. That
 * reasoning is kept below — but it left the reported defect live on the exact
 * page the report named: `app/shop/layout.tsx:73` mounts this footer under
 * /shop/orders, so a signed-in customer scrolled past a gated header straight
 * into a column literally headed "For operators".
 *
 * What changed, and what deliberately did not:
 *  - "For operators" is hidden from a signed-in CUSTOMER on CUSTOMER surfaces
 *    only. On the operator surfaces themselves (/for-operators and the two pages
 *    it leads to) it stays, because someone who navigated there did so on
 *    purpose, and removing "Vendor sign in" from the footer of the page whose
 *    whole job is recruiting vendors would be a worse bug than #458.
 *  - "Track order" — the GUEST lookup, order number + email — is replaced for a
 *    signed-in customer by "My orders", not deleted. Their tracking lives one tap
 *    behind an order card there, which is also the only place that can honour
 *    "only present when there's been an order": the card cannot exist without one.
 *  - Nothing is gated for an anonymous visitor. Both doors are exactly as they
 *    were, which is also what a crawler sees.
 *
 * WHY THIS IS NOW A CLIENT COMPONENT. It was a plain server component and the
 * comment here said so, citing the #89 force-dynamic/CSP-nonce contract. That
 * contract is unaffected: PublicHeader is `"use client"` and already sits inside
 * the same PublicShell, and the root layout sets `dynamic = "force-dynamic"`
 * app-wide, so neither the nonce nor the render mode changes. The session is an
 * HttpOnly cookie read through a route handler, which is asynchronous, so the
 * FIRST render — the one in the SSR HTML, and the one a crawler receives — is the
 * anonymous one and the link graph is intact. Pinned by a test, not assumed.
 *
 * The gate is shared with the two headers via useCustomerSession() rather than
 * re-derived here. #457 exists because two components disagreed about the session
 * once already; a third independent reader is how that comes back.
 */

/**
 * Surfaces where the operator column stays visible to everyone, signed in or not.
 * Prefix-matched, so /for-operators/anything is covered too.
 */
const OPERATOR_SURFACES = ["/for-operators", "/business-model-guide", "/competitive", "/auth"]

/**
 * Attribution year for the bundled OS Code-Point Open postcode dataset.
 *
 * THIS IS NOT THE COPYRIGHT YEAR AND MUST NOT BE DERIVED FROM `new Date()`.
 * The copyright line below moves every 1 January; this one is frozen at the
 * release the repo actually ships and only moves when the dataset is
 * regenerated. Writing `{year}` here would render an attribution for a dataset
 * release that does not exist.
 *
 * It must equal the year in `core-java/src/main/resources/geo/SOURCE.md`, which
 * `scripts/check-geo-attribution.sh` enforces in CI — the two cannot drift apart
 * silently.
 */
const GEO_ATTRIBUTION_YEAR = "2026"

export function PublicFooter() {
  const year = new Date().getFullYear()
  const { profile } = useCustomerSession()
  const pathname = usePathname()

  const onOperatorSurface = OPERATOR_SURFACES.some(
    (href) => pathname === href || pathname?.startsWith(`${href}/`)
  )
  const isCustomer = Boolean(profile)
  const showOperatorColumn = !isCustomer || onOperatorSurface

  return (
    <footer className="bg-oxblood text-cream">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-12">
        {/* Three tracks ALWAYS, even when the third is empty. Collapsing to
            sm:grid-cols-2 was tried first and looked tidier in a static
            screenshot, but the session resolves after first paint, so the
            operator column disappears live — and with two tracks the "For
            customers" column slides ~200px right as it goes. Keeping the track
            count fixed means the two surviving columns do not move at all. A
            little empty space on the right costs nothing; a footer that
            reflows under the reader's eyes on every load does. */}
        <div className="grid grid-cols-1 gap-8 sm:grid-cols-3">
          {/* Brand */}
          <div className="space-y-3">
            <Link
              href="/"
              className="flex items-center gap-2 text-lg font-semibold tracking-tight text-white"
            >
              <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-white text-sm font-bold text-oxblood">
                J
              </span>
              <span>J&apos;Toye</span>
            </Link>
            <p className="text-sm text-cream/75 max-w-xs">
              Order from independent local kitchens, or run your own food
              business — all on one platform.
            </p>
          </div>

          {/* For customers */}
          <div className="space-y-3">
            <h3 className="text-xs font-bold uppercase tracking-[0.14em] text-gold">
              For customers
            </h3>
            <ul className="space-y-2 text-sm">
              <li>
                <Link href="/shop" className="text-cream/85 transition-colors hover:text-white">
                  Browse shops
                </Link>
              </li>
              <li>
                {isCustomer ? (
                  /* Their orders, not a form asking for a number the system
                     already holds. Tracking is one tap on from here. */
                  <Link
                    href="/shop/orders"
                    className="text-cream/85 transition-colors hover:text-white"
                  >
                    My orders
                  </Link>
                ) : (
                  <Link href="/track" className="text-cream/85 transition-colors hover:text-white">
                    Track order
                  </Link>
                )}
              </li>
            </ul>
          </div>

          {/* For operators */}
          {showOperatorColumn && (
            <div className="space-y-3">
              <h3 className="text-xs font-bold uppercase tracking-[0.14em] text-gold">
                For operators
              </h3>
              <ul className="space-y-2 text-sm">
                <li>
                  <Link href="/for-operators" className="text-cream/85 transition-colors hover:text-white">
                    Become a vendor
                  </Link>
                </li>
                <li>
                  <Link href="/business-model-guide" className="text-cream/85 transition-colors hover:text-white">
                    Business model guide
                  </Link>
                </li>
                <li>
                  <Link href="/competitive" className="text-cream/85 transition-colors hover:text-white">
                    How we compare
                  </Link>
                </li>
                <li>
                  <Link href="/auth/signin" className="text-cream/85 transition-colors hover:text-white">
                    Vendor sign in
                  </Link>
                </li>
              </ul>
            </div>
          )}
        </div>

        {/* Bottom row */}
        <div className="mt-10 flex flex-col gap-2 border-t border-white/15 pt-6 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm text-cream/70">
            &copy; {year} J&apos;Toye Digital Ltd · Registered in England &amp; Wales · company no. 16471464
          </p>
          <p className="text-sm text-cream/70">
            Allergen info available on all products
          </p>
        </div>

        {/* Open Government Licence attribution for the bundled OS Code-Point Open
            postcode dataset that powers "shops near you".

            This is a LICENCE OBLIGATION, not a credit — OGL v3 permits commercial
            use inside a proprietary product and imposes no share-alike, and
            acknowledgement is the single thing it asks in return. All three lines
            are required; shipping only the Ordnance Survey line is a breach.
            scripts/check-geo-attribution.sh fails CI if any line goes missing or
            the year drifts from SOURCE.md. */}
        <div className="mt-6 border-t border-white/10 pt-4">
          <p className="text-xs leading-relaxed text-cream/55">
            Contains Ordnance Survey data &copy; Crown copyright and database right {GEO_ATTRIBUTION_YEAR}.
            <br />
            Contains Royal Mail data &copy; Royal Mail copyright and database right {GEO_ATTRIBUTION_YEAR}.
            <br />
            Contains National Statistics data &copy; Crown copyright and database right {GEO_ATTRIBUTION_YEAR}.
          </p>
        </div>
      </div>
    </footer>
  )
}

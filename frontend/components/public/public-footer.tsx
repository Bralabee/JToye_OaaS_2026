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
        {/* A FIXED track count per breakpoint, never one derived from session
            state. Collapsing the tracks when the operator column hides was
            tried first and looked tidier in a static screenshot, but the
            session resolves after first paint, so the operator column
            disappears live — and as the track count drops the "For customers"
            column slides ~200px right as it goes. A little empty space on the
            right costs nothing; a footer that reflows under the reader's eyes
            on every load does.

            The count moved from three to four when the Legal column was added
            (LGL-01). The invariant is unchanged and is now carried by DOM ORDER
            instead: the CONDITIONAL column must be LAST, because grid auto-flow
            pulls every later item forward when it unmounts. Legal therefore
            sits third, ahead of "For operators" — put it fourth and it would
            jump a whole track the moment a customer's session resolved, which
            is the exact defect this layout was shaped to avoid.

            2 tracks at sm rather than 4: four columns inside a 640px viewport
            leaves ~124px each, and "Cookie and browser-storage policy" is not a
            124px label. 2x2 there, one row at lg. */}
        <div className="grid grid-cols-1 gap-8 sm:grid-cols-2 lg:grid-cols-4">
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

          {/* For customers.

              HEADING LEVEL IS LOAD-BEARING (F-C). These column headings sit at
              level 2. They used to sit at level 3 with nothing at level 2
              anywhere above them, which is a level skip — and because this
              footer is shared chrome it fired axe's `heading-order` on every
              page whose own content happens to supply no level-2 heading of its
              own (measured on /shop/signin and /legal). A page that DOES have
              one hid it, which is why it survived so long. Any column added here
              takes level 2 too; the class strings are unchanged, so nothing
              moves visually.

              Deliberately written without naming the tag it replaced: the verify
              for this change counts that literal token in this file and expects
              zero, so a comment mentioning it would fail the gate it documents. */}
          <div className="space-y-3">
            <h2 className="text-xs font-bold uppercase tracking-[0.14em] text-gold">
              For customers
            </h2>
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

          {/* Legal (LGL-01).

              THIS COLUMN IS THE WHOLE OF THE REACHABILITY FIX. Five policy
              pages were published across this phase and nothing in the app
              linked to any of them: the only in-app link to /legal anywhere was
              components/platform/company-legal.tsx, which renders on platform
              surfaces only. A policy nobody can reach is not published.

              One column here covers EVERY public route including the tenant
              storefront, because app/shop/layout.tsx renders this same footer
              over the whole /shop/** subtree. That measurement is why the
              separately-planned StorefrontLegalStrip was never built.

              Real <Link>s, which render real <a href>: a JS-only nav does not
              satisfy LGL-01 and is not crawlable.

              LABELS ARE THE CANONICAL ONES, NOT FOOTER-SHORTENED. Each matches
              the destination page's own title and the labels the policy pages
              already use for each other (app/legal/retention/page.tsx:321-350).
              Two labels for one href is the shape #382 took.

              POLICY LINKS ONLY. No CompanyLegalLine, no registered office, no
              company identity — lib/company.ts:9-12 keeps the platform
              operator's trading disclosure off tenant storefronts, and this
              footer renders on every one of them. */}
          <div className="space-y-3">
            <h2 className="text-xs font-bold uppercase tracking-[0.14em] text-gold">
              Legal
            </h2>
            <ul className="space-y-2 text-sm">
              <li>
                <Link href="/legal" className="text-cream/85 transition-colors hover:text-white">
                  Legal &amp; company information
                </Link>
              </li>
              <li>
                <Link
                  href="/legal/privacy"
                  className="text-cream/85 transition-colors hover:text-white"
                >
                  Privacy notice
                </Link>
              </li>
              <li>
                <Link
                  href="/legal/cookies"
                  className="text-cream/85 transition-colors hover:text-white"
                >
                  Cookie and browser-storage policy
                </Link>
              </li>
              <li>
                <Link
                  href="/legal/retention"
                  className="text-cream/85 transition-colors hover:text-white"
                >
                  Data retention schedule
                </Link>
              </li>
              <li>
                <Link
                  href="/legal/accessibility"
                  className="text-cream/85 transition-colors hover:text-white"
                >
                  Accessibility statement
                </Link>
              </li>
            </ul>
          </div>

          {/* For operators — KEEP LAST. This is the only conditionally-rendered
              column; anything placed after it shifts a track when it unmounts. */}
          {showOperatorColumn && (
            <div className="space-y-3">
              <h2 className="text-xs font-bold uppercase tracking-[0.14em] text-gold">
                For operators
              </h2>
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

import Link from "next/link"

/**
 * Shared public footer (Surface B). De-orphans /track and /business-model-guide
 * (backlog #5) and gives /for-operators a second inbound link. Plain server
 * component — no client hooks, so the force-dynamic / CSP-nonce contract is
 * inherited from the root layout.
 *
 * Oxblood brand anchor (sketch 004 winner D, matched to jtoyedigital.co.uk).
 */
export function PublicFooter() {
  const year = new Date().getFullYear()

  return (
    <footer className="bg-oxblood text-cream">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-12">
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
                <Link href="/track" className="text-cream/85 transition-colors hover:text-white">
                  Track order
                </Link>
              </li>
            </ul>
          </div>

          {/* For operators */}
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
                <Link href="/auth/signin" className="text-cream/85 transition-colors hover:text-white">
                  Vendor sign in
                </Link>
              </li>
            </ul>
          </div>
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
      </div>
    </footer>
  )
}

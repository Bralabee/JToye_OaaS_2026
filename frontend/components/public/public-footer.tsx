import Link from "next/link"

/**
 * Shared public footer (Surface B). This is the mechanism that de-orphans
 * /track and /business-model-guide (backlog #5) and gives /for-operators a
 * second, non-orphan inbound link. Plain server component — no client hooks,
 * so the force-dynamic / CSP-nonce contract is inherited from the root layout.
 */
export function PublicFooter() {
  const year = new Date().getFullYear()

  return (
    <footer className="border-t border-slate-200 bg-white">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-12">
        <div className="grid grid-cols-1 gap-8 sm:grid-cols-3">
          {/* Brand */}
          <div className="space-y-3">
            <Link
              href="/"
              className="flex items-center gap-2 text-lg font-semibold tracking-tight text-slate-900"
            >
              <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-orange-500 text-sm font-bold text-white">
                J
              </span>
              <span>J&apos;Toye</span>
            </Link>
            <p className="text-sm text-slate-500 max-w-xs">
              Order from independent local kitchens, or run your own food
              business — all on one platform.
            </p>
          </div>

          {/* For customers */}
          <div className="space-y-3">
            <h3 className="text-sm font-semibold text-slate-900">
              For customers
            </h3>
            <ul className="space-y-2 text-sm">
              <li>
                <Link
                  href="/shop"
                  className="text-slate-600 transition-colors hover:text-slate-900"
                >
                  Browse shops
                </Link>
              </li>
              <li>
                <Link
                  href="/track"
                  className="text-slate-600 transition-colors hover:text-slate-900"
                >
                  Track order
                </Link>
              </li>
            </ul>
          </div>

          {/* For operators */}
          <div className="space-y-3">
            <h3 className="text-sm font-semibold text-slate-900">
              For operators
            </h3>
            <ul className="space-y-2 text-sm">
              <li>
                <Link
                  href="/for-operators"
                  className="text-slate-600 transition-colors hover:text-slate-900"
                >
                  Become a vendor
                </Link>
              </li>
              <li>
                <Link
                  href="/business-model-guide"
                  className="text-slate-600 transition-colors hover:text-slate-900"
                >
                  Business model guide
                </Link>
              </li>
              <li>
                <Link
                  href="/auth/signin"
                  className="text-slate-600 transition-colors hover:text-slate-900"
                >
                  Vendor sign in
                </Link>
              </li>
            </ul>
          </div>
        </div>

        {/* Bottom row */}
        <div className="mt-10 flex flex-col gap-2 border-t border-slate-100 pt-6 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm text-slate-500">
            &copy; {year} J&apos;Toye OaaS
          </p>
          <p className="text-sm text-slate-500">
            Allergen info available on all products
          </p>
        </div>
      </div>
    </footer>
  )
}

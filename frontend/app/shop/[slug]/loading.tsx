/**
 * /shop/[slug] loading boundary (debug: rsc-prefetch-double-abort).
 *
 * Shop-detail shaped skeleton (banner + menu rows) so tapping a shop card
 * commits the navigation instantly under the persistent StorefrontNav header
 * instead of freezing on the browse grid for a full server round-trip.
 */
export default function ShopDetailLoading() {
  return (
    <div className="animate-pulse">
      {/* Banner skeleton */}
      <div className="h-48 sm:h-64 bg-slate-200" />

      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        {/* Shop identity row */}
        <div className="-mt-8 flex items-end gap-4">
          <div className="h-20 w-20 rounded-2xl bg-slate-300 ring-4 ring-white" />
          <div className="pb-2 space-y-2">
            <div className="h-6 w-56 max-w-full rounded bg-slate-200" />
            <div className="h-4 w-40 rounded bg-slate-100" />
          </div>
        </div>

        {/* Menu rows skeleton */}
        <div className="mt-8 space-y-4 pb-12">
          <div className="h-5 w-32 rounded bg-slate-200" />
          {Array.from({ length: 4 }).map((_, i) => (
            <div
              key={i}
              className="flex items-center gap-4 rounded-2xl border border-slate-100 bg-white p-4"
            >
              <div className="h-20 w-20 flex-shrink-0 rounded-xl bg-slate-200" />
              <div className="flex-1 space-y-2">
                <div className="h-4 w-1/3 rounded bg-slate-200" />
                <div className="h-3 w-2/3 rounded bg-slate-100" />
                <div className="h-4 w-16 rounded bg-slate-200" />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

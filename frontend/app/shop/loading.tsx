/**
 * /shop loading boundary (debug: rsc-prefetch-double-abort).
 *
 * Mirrors the browse page's own client-side skeleton (app/shop/page.tsx
 * loading branch) so the click → RSC-stream → client-fetch sequence reads as
 * one continuous skeleton. The StorefrontNav header stays visible via
 * app/shop/layout.tsx, so navigation commits instantly with correct chrome
 * instead of freezing on the previous page for a full server round-trip.
 */
export default function ShopBrowseLoading() {
  return (
    <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-6 sm:py-10">
      {/* Hero skeleton */}
      <div className="mb-8 sm:mb-10 animate-pulse">
        <div className="h-8 w-72 max-w-full rounded bg-slate-200" />
        <div className="mt-3 h-4 w-96 max-w-full rounded bg-slate-100" />
      </div>

      {/* Search skeleton */}
      <div className="mb-6 sm:mb-8 max-w-md animate-pulse">
        <div className="h-10 w-full rounded-xl bg-slate-200" />
      </div>

      {/* Card grid skeleton — matches the page's own loading branch */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="bg-white rounded-2xl overflow-hidden animate-pulse">
            <div className="h-36 sm:h-44 bg-slate-200" />
            <div className="p-4 space-y-3">
              <div className="h-4 bg-slate-200 rounded w-2/3" />
              <div className="h-3 bg-slate-100 rounded w-full" />
              <div className="flex gap-2">
                <div className="h-5 bg-slate-100 rounded w-16" />
                <div className="h-5 bg-slate-100 rounded w-20" />
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

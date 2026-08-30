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
      <div className="h-48 sm:h-64 bg-cream-100" />

      {/* ORCH-05 (orchestrator decision, 2026-08-29, CONTEXT.md section 4b) — this
          band was 384px WIDER than the content it is replaced by. The skeleton
          rendered at 1280 while shop-detail-client.tsx renders at 896, so the page
          visibly NARROWED the moment real content arrived: a latent layout-shift
          contributor on a route that has no recorded CLS budget, and precisely the
          class of defect a declared width contract exists to prevent. Aligned DOWN
          to the content rather than the content up to the skeleton.

          NO TIER ATTRIBUTE HERE, deliberately. The /shop/[slug] surface has not
          been ASSIGNED a tier: A-11 — widening the menu itself to the Detail tier —
          is DEFERRED. A tier is a ceiling rather than a target, so a surface that
          sits below its tier for a stated reason keeps its measure, and 896 is
          within prose-measure territory for what is a scannable list of dish rows.
          Declaring a tier attribute here would claim an assignment that was never
          made.

          THIS WIDTH MOVES WITH TWO OTHER FILES, not one. The family is
          shop-detail-client.tsx (the content) AND not-found.tsx (the shop-not-found
          panel), both at the same value. All three must change together. That
          parity is asserted MECHANICALLY by the gate in plan 35-10, because a
          comment is not an assertion — this paragraph cannot fail a build. */}
      <div className="mx-auto max-w-4xl px-4 sm:px-6 lg:px-8">
        {/* Shop identity row */}
        <div className="-mt-8 flex items-end gap-4">
          <div className="h-20 w-20 rounded-2xl bg-slate-300 ring-4 ring-white" />
          <div className="pb-2 space-y-2">
            <div className="h-6 w-56 max-w-full rounded bg-cream-100" />
            <div className="h-4 w-40 rounded bg-cream" />
          </div>
        </div>

        {/* Menu rows skeleton */}
        <div className="mt-8 space-y-4 pb-12">
          <div className="h-5 w-32 rounded bg-cream-100" />
          {Array.from({ length: 4 }).map((_, i) => (
            <div
              key={i}
              className="flex items-center gap-4 rounded-2xl border border-cream-100 bg-white p-4"
            >
              <div className="h-20 w-20 flex-shrink-0 rounded-xl bg-cream-100" />
              <div className="flex-1 space-y-2">
                <div className="h-4 w-1/3 rounded bg-cream-100" />
                <div className="h-3 w-2/3 rounded bg-cream" />
                <div className="h-4 w-16 rounded bg-cream-100" />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

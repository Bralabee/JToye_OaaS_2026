/**
 * Root-level loading boundary (debug: rsc-prefetch-double-abort).
 *
 * The root layout forces `dynamic = "force-dynamic"` app-wide (CSP nonce,
 * issue #89), so without a loading.js boundary every client-side navigation
 * waited for the FULL RSC round-trip before anything changed on screen —
 * measured 0.5-1.5s of frozen UI on 4G-class latency, perceived as "clicks
 * just hang". This boundary lets the App Router commit the navigation
 * immediately and paint feedback from the prefetched shell while the dynamic
 * payload streams.
 *
 * Deliberately a plain server component with static markup only.
 */
export default function RootLoading() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-slate-50">
      <div className="flex items-center gap-3">
        <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-orange-500 text-lg font-bold text-white">
          J
        </span>
        <span className="text-xl font-semibold tracking-tight text-slate-900">
          J&apos;Toye
        </span>
      </div>
      <div className="mt-6 h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-orange-500" />
      <p className="mt-4 text-sm text-slate-400">Loading…</p>
    </div>
  )
}

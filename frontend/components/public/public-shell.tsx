import { PublicHeader } from "@/components/public/public-header"
import { PublicFooter } from "@/components/public/public-footer"

/**
 * Shared public shell (Surface B): header + footer wrapper for the public
 * marketing surfaces (/, /for-operators, /business-model-guide, /track, /shop).
 *
 * Deliberately a PLAIN server component — no client directive, no
 * route-segment config — so the root layout's `dynamic = "force-dynamic"` and
 * CSP nonce cascade through untouched (the #89 failure mode). It renders only
 * marketing chrome: no session/apiClient fetch, no auth-gated data
 * (threat T-19-03-01).
 */
export function PublicShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-white">
      <PublicHeader />
      <main className="flex-1">{children}</main>
      <PublicFooter />
    </div>
  )
}

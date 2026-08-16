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
 *
 * SKIP LINK (A11Y-06). Every public route served through this shell opens with a
 * sticky header carrying a wordmark and up to five nav links, so a keyboard or
 * switch user previously had to tab through the whole of it on every navigation
 * to reach the page they asked for — WCAG 2.4.1 Bypass Blocks. axe cannot see
 * this: a missing skip link produces no violation node, which is why it survived
 * every automated pass.
 *
 * The markup is copied VERBATIM from `components/marketing/operator-pitch.tsx:70`
 * — same classes, same `bg-oxblood`, same `focus:not-sr-only` reveal. Three
 * copies of this pattern already existed and two had already drifted apart; a
 * fourth variant would be the drift, not the fix.
 *
 * Two properties this depends on, both asserted in
 * `__tests__/public-shell-landmarks.test.tsx` rather than assumed:
 *  - it is the FIRST link in document order. A skip link rendered after the
 *    header is decoration — it is unreachable by the very keystroke it exists
 *    for, and a presence-only test cannot tell the two apart.
 *  - it targets `<main id="main">`. The id is the whole mechanism; without it the
 *    href resolves to nothing and the link silently does nothing.
 *
 * A plain `<a href>` needs no client boundary, so the server-component property
 * above is untouched.
 */
export function PublicShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-cream">
      <a href="#main" className="sr-only z-50 rounded-full bg-oxblood px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to main content</a>
      <PublicHeader />
      <main id="main" className="flex-1">{children}</main>
      <PublicFooter />
    </div>
  )
}

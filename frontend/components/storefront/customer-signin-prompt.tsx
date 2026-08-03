import { LogIn, ShoppingBag } from "lucide-react"
import Link from "next/link"

/**
 * The "you need to be signed in" wall for customer-gated storefront pages.
 *
 * Presentational and server-renderable ON PURPOSE (no "use client"): issue #463
 * is about content that waits for hydration, and this prompt is the one thing a
 * signed-out visitor is going to see. Rendering it from the server means a
 * signed-out customer gets an answer in the first paint instead of a spinner
 * that resolves into a wall.
 *
 * Extracted from require-customer-auth.tsx so the server component
 * (app/shop/orders/page.tsx, which can read the session cookies directly) and
 * the client island (which handles the token-refresh case, where only a route
 * handler can re-issue cookies) render the SAME markup. Two copies of this
 * would drift, and the storefront E2E asserts its specifics: the heading text
 * "Sign in to continue", and a role=link named "Sign in" inside <main> whose
 * href points at /shop/signin.
 */
export function CustomerSignInPrompt({
  message,
  nextPath = "/shop",
}: {
  message?: string
  /** Where to return after sign-in. Must be a same-origin relative path. */
  nextPath?: string
}) {
  return (
    <div className="mx-auto max-w-md px-4 py-20 text-center">
      <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-amber-100">
        <ShoppingBag className="h-8 w-8 text-amber-700" />
      </div>
      <h2 className="text-xl font-bold text-slate-900 mb-2">Sign in to continue</h2>
      <p className="text-sm text-slate-500 mb-6">
        {message || "You need to be signed in to view your orders and track deliveries."}
      </p>
      {/* Links to the sign-in PAGE rather than firing customerLogin() straight at
          Keycloak. The bare redirect gave the shopper no landing destination — no
          back button that worked, no "create an account" option, and no way to
          reach the vendor page if they had guessed wrong about which they needed. */}
      <Link
        href={`/shop/signin?next=${encodeURIComponent(nextPath)}`}
        className="inline-flex items-center gap-2 rounded-full bg-oxblood hover:bg-oxblood-700 text-white font-semibold px-6 py-3 transition-colors"
      >
        <LogIn className="h-5 w-5" />
        Sign in
      </Link>
      <p className="mt-4 text-xs text-slate-400">
        Don&apos;t have an account? You can create one on the next screen.
      </p>
    </div>
  )
}

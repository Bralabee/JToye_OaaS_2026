import { Suspense } from "react"
import type { Metadata } from "next"
import { Loader2 } from "lucide-react"
import { PublicShell } from "@/components/public/public-shell"
import { UnsubscribeContent } from "./unsubscribe-content"

/**
 * Public unsubscribe page (`/unsubscribe`, NO auth) — the ONE public/
 * unauthenticated Comms surface. Token-based one-click opt-out confirmation.
 *
 * SEO / privacy (T-22-07-02, UI-SPEC §SEO):
 *   - `robots: { index:false, follow:false }` — a transactional surface, never
 *     discovery content; must not be indexed.
 *   - EXCLUDED from `app/sitemap.ts` (the allowlist omits it) — see docs/SITEMAP.md.
 *   - no Open Graph / JSON-LD (nothing shareable/structured), and the client
 *     component never renders the email/token into meta or the visible body.
 *
 * The interactive part lives in a co-located "use client" component so this
 * server component can export `metadata` (a client module cannot).
 *
 * FEB-6: wrapped in `PublicShell` rather than a bare `<main>`. A malformed or
 * stripped link (any of tenant/email/category/token missing) renders the
 * "invalid" state, and with no nav/header of its own this page was a dead end
 * at mobile — a visitor arriving with a broken link had no way to reach the
 * rest of the site. `PublicShell` already supplies the `id="main"` landmark
 * (WCAG 2.4.1 skip link included), so this file's own bare `<main>` is
 * removed rather than nested — a second `<main>` would itself be a landmark
 * violation.
 */
export const metadata: Metadata = {
  title: "Unsubscribe — J'Toye",
  description: "Manage your email preferences.",
  robots: { index: false, follow: false },
}

export default function UnsubscribePage() {
  return (
    <PublicShell>
      <Suspense
        fallback={
          <div className="flex items-center justify-center py-24">
            <Loader2 className="h-8 w-8 animate-spin text-orange-500" />
          </div>
        }
      >
        <UnsubscribeContent />
      </Suspense>
    </PublicShell>
  )
}

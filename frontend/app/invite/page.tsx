import type { Metadata } from "next"
import { PublicShell } from "@/components/public/public-shell"
import { InviteClient } from "./invite-client"

/**
 * `/invite` — where a staff invitation email sends the person invited (D-07, D-26;
 * UI-SPEC § B2; 37-09). The link is `/invite#token={tenantId}.{token}`.
 *
 * The route is `/invite`, not `/invite/[token]` as first planned: 37-08 moved the
 * token into the URL FRAGMENT, which a browser never sends to a server, so it is in
 * no request line, no access log, no `Referer`, and no ProblemDetail `instance`
 * (WINDOWS.md entry 21, the V75 DSAR-link rule). The client island reads it, drops it
 * from the address bar and POSTs it in a JSON body.
 *
 * A server component only so it can export `metadata`; nothing is fetched here,
 * which is what `scripts/gates/ssr-routes.conf` declares (STATIC).
 *
 * Token hygiene, beyond the fragment (T-37-24):
 *   - `robots: noindex, nofollow`, no canonical, no Open Graph; `/invite` is in the
 *     robots.txt DISALLOW list and not in the sitemap;
 *   - `Referrer-Policy: no-referrer` on the route (next.config.mjs), so even the
 *     page's own address is not handed to anything it links to;
 *   - no third-party resource: the page loads only this origin, the API and, after
 *     accepting, the Keycloak sign-in;
 *   - nothing is written to web storage (local or session) by the island; the
 *     route's source does not name either API (37-09 acceptance, rg -uu).
 *
 * The title is generic here because the business is not known until the preview
 * answers; the island then sets "Join {business} on J'Toye".
 */
export const metadata: Metadata = {
  title: "Your invitation — J'Toye",
  description: "Accept an invitation to work on a business on J'Toye.",
  robots: { index: false, follow: false },
}

export default function InvitePage() {
  return (
    <PublicShell>
      <InviteClient />
    </PublicShell>
  )
}

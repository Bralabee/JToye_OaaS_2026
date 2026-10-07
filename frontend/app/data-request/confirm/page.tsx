import type { Metadata } from "next"
import { PublicShell } from "@/components/public/public-shell"
import { ConfirmClient } from "./confirm-client"

/**
 * `/data-request/confirm` — where the DSAR verification email sends the
 * requester (#839, D-04, 31.1-20). The link is
 * `/data-request/confirm#token=…`.
 *
 * Before this page existed the link pointed at the API: on compose that was
 * localhost:8080, where nothing listens, and where it did reach the API the
 * person saw raw JSON. So nobody who was not a developer could confirm a
 * request.
 *
 * A server component only so it can export `metadata` (a client module
 * cannot); everything the page does happens in the client island, because the
 * token lives in the URL FRAGMENT, which a browser never sends to a server.
 * Nothing is fetched here, which is what `scripts/gates/ssr-routes.conf`
 * declares (STATIC).
 *
 * Privacy:
 *   - `robots: noindex, nofollow` — a transactional page reached only from an
 *     emailed link, never discovery content; it is not in `app/sitemap.ts`.
 *   - no Open Graph, no JSON-LD, and the token is never rendered.
 *
 * Wrapped in `PublicShell` (the `/unsubscribe` and `/data-request/download`
 * precedent): a person whose link has expired still has the site's navigation
 * to go somewhere.
 */
export const metadata: Metadata = {
  title: "Confirm your data request — J'Toye",
  description: "Confirm the request you made about your personal data.",
  robots: { index: false, follow: false },
}

export default function ConfirmPage() {
  return (
    <PublicShell>
      <ConfirmClient />
    </PublicShell>
  )
}

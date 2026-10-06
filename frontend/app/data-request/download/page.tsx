import type { Metadata } from "next"
import { PublicShell } from "@/components/public/public-shell"
import { DownloadClient } from "./download-client"

/**
 * `/data-request/download` — where the Article 15 email sends the subject
 * (#778, D-01, 31.1-17). The link is `/data-request/download#token=…`.
 *
 * A server component only so it can export `metadata` (a client module
 * cannot); everything the page does happens in the client island, because the
 * token lives in the URL FRAGMENT, which a browser never sends to a server.
 * Nothing is fetched here, which is what `scripts/gates/ssr-routes.conf`
 * declares (STATIC).
 *
 * Privacy:
 *   - `robots: noindex, nofollow` — a transactional page holding a personal
 *     data document, never discovery content; it is not in `app/sitemap.ts`.
 *   - no Open Graph, no JSON-LD, and the token is never rendered.
 *
 * Wrapped in `PublicShell` (the `/unsubscribe` precedent, FEB-6): a person
 * whose link has expired still has the site's navigation to go somewhere.
 */
export const metadata: Metadata = {
  title: "Your data — J'Toye",
  description: "Collect the copy of your personal data you asked for.",
  robots: { index: false, follow: false },
}

export default function DownloadPage() {
  return (
    <PublicShell>
      <DownloadClient />
    </PublicShell>
  )
}

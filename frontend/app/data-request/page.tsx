import type { Metadata } from "next"
import { PublicShell } from "@/components/public/public-shell"
import { DataRequestClient } from "./data-request-client"

/**
 * `/data-request` — make a data request without an account (SW-SAM-03, swarm epic #964,
 * reconfirms UXT-069 / UXT-014).
 *
 * Until this page the privacy notice gave a guest no online route: the request forms were on
 * `/shop/account`, which needs a sign-in, and the only other routes were an email address that is
 * empty until the owner publishes one, and a postal letter. A guest is most of the customers: guest
 * checkout never creates an account.
 *
 * It lodges the same request the account page does, at the same public intake, with the address
 * the person types. Core's answer is constant whatever any shop holds (V62) and the page never
 * renders it, so the form cannot be used to learn whether an address is known. Nothing is actioned
 * until the person opens the emailed link and confirms (`/data-request/confirm`), so a request
 * lodged against someone else's address does nothing without access to that inbox.
 *
 * A server component only for `metadata`; nothing is fetched here (STATIC in
 * `scripts/gates/ssr-routes.conf`). Indexed, like the privacy notice that links to it: it is how a
 * person exercises a legal right, and should be findable.
 */
export const metadata: Metadata = {
  title: "Make a data request — J'Toye",
  description:
    "Ask for a copy of your personal data, or for it to be erased, from J'Toye and every shop you have ordered from. No account needed.",
  alternates: { canonical: "/data-request" },
}

export default function DataRequestPage() {
  return (
    <PublicShell>
      <DataRequestClient />
    </PublicShell>
  )
}

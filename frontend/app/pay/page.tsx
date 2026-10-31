import type { Metadata } from "next"
import { PublicShell } from "@/components/public/public-shell"
import { PayClient } from "./pay-client"

/**
 * `/pay` — where a shop's payment link sends the customer (PAY-04 / D-03; UI-SPEC S3; plan 30-18).
 * The link is `/pay#token={token}`.
 *
 * The route is `/pay`, not `/pay/[token]`: the owner chose the FRAGMENT for the token (plan 30-16
 * Task 1, 2026-10-31), which a browser never sends to a server, so it is in no request line, no
 * access log, no `Referer` and no ProblemDetail `instance` — the `/invite` (V77) and DSAR-link (V75)
 * rule. The client island reads it from `location.hash` and POSTs it in a JSON body.
 *
 * The same route is also:
 *   - Stripe's `return_url` for redirect-based payment methods (`?payment_intent_client_secret=…
 *     &redirect_status=…&acct=…`);
 *   - what a reload lands on after the link was opened: the island stripped the token with
 *     `history.replaceState(…, "/pay")` and resumes from `history.state.jtoyePayResume`.
 *
 * A server component only so it can export `metadata`; it fetches nothing and never renders the
 * token (the island reads it after mount). Token hygiene beyond the fragment: noindex,nofollow, no
 * canonical, no Open Graph, no JSON-LD; robots.txt, Referrer-Policy and the SSR-gate line are plan
 * 30-19's.
 */
export const metadata: Metadata = {
  title: "Pay for your order — J'Toye",
  description: "Pay for an order you placed with a shop on J'Toye.",
  robots: { index: false, follow: false },
}

export default function PayPage() {
  return (
    <PublicShell>
      <PayClient />
    </PublicShell>
  )
}

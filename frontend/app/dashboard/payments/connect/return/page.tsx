import type { Metadata } from "next"
import { ConnectOutcome } from "../connect-outcome"

/**
 * `stripe.connect.return-url` (#295).
 *
 * The path is fixed by the backend, not by this file: core-java's
 * `application.yml` resolves `stripe.connect.return-url` to
 * `${STRIPE_CONNECT_RETURN_URL:http://localhost:3000/dashboard/payments/connect/return}`,
 * and every k8s overlay overrides only the ORIGIN
 * (`https://app.olajay.co.uk`, `https://app-staging.olajay.co.uk`,
 * `http://app.jtoye.local`) — the path is byte-identical everywhere. Renaming
 * this route without changing all four config sites re-opens the 404.
 *
 * Stripe redirects here when the vendor LEAVES the Express onboarding flow —
 * whether they finished it or backed out. Stripe passes no state and no query
 * parameters, so this page reports what is actually true and nothing more; see
 * `../connect-outcome.tsx` for why there is no status poll.
 */
export const metadata: Metadata = {
  title: "Back from Stripe — J'Toye",
  description:
    "You've returned from Stripe Connect onboarding. Stripe confirms your payout status with J'Toye directly.",
  robots: { index: false, follow: false },
}

export default function StripeConnectReturnPage() {
  return <ConnectOutcome variant="return" />
}

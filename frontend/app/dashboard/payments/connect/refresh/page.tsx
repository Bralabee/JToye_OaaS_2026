import type { Metadata } from "next"
import { ConnectOutcome } from "../connect-outcome"

/**
 * `stripe.connect.refresh-url` (#295).
 *
 * Same path contract as the return route: core-java resolves
 * `${STRIPE_CONNECT_REFRESH_URL:http://localhost:3000/dashboard/payments/connect/refresh}`
 * and the k8s overlays override only the origin.
 *
 * Stripe redirects here BEFORE the flow starts, when the AccountLink is
 * expired, already-visited, or otherwise invalid. Stripe's own guidance is that
 * this URL should mint a fresh link and redirect — J'Toye deliberately does not,
 * because the only mint is `POST /api/v1/admin/tenants/{tenantId}/stripe/connect`
 * (`@PreAuthorize("hasRole('admin')")`) and the vendor session carries no tenant
 * id by design. A button that 403s or 404s is worse than an honest instruction,
 * so the page explains the state and routes the vendor to someone who can issue
 * a new link. When a tenant-scoped mint endpoint exists, this page gets the
 * self-service "Get a new link" button Stripe expects.
 */
export const metadata: Metadata = {
  title: "Stripe link expired — J'Toye",
  description:
    "The Stripe Connect onboarding link has expired or was already used. Here's how to get a new one.",
  robots: { index: false, follow: false },
}

export default function StripeConnectRefreshPage() {
  return <ConnectOutcome variant="refresh" />
}

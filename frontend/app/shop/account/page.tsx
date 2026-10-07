import type { Metadata } from "next"
import { cookies } from "next/headers"
import { ACCESS_COOKIE, ID_COOKIE, REFRESH_COOKIE } from "@/lib/customer-auth-cookies"
import { displayEmailFromIdToken } from "@/lib/customer-orders-server"
import { CustomerSignInPrompt } from "@/components/storefront/customer-signin-prompt"
import { AccountClient } from "./account-client"

/**
 * "My account" — the signed-in customer's front door to their data rights (#838, D-04).
 *
 * SERVER component, for the reason app/shop/orders/page.tsx is one (#463): the session cookies are
 * HttpOnly, so the server can decide signed-in vs signed-out and put the answer in the first paint
 * rather than behind a spinner.
 *
 * WHAT IT DELIBERATELY DOES NOT BUILD. Both actions go through the EXISTING public DSAR intake
 * (`POST /api/v1/public/gdpr/dsar`), which already verifies by email before anything is actioned.
 * There is no account-only endpoint: one path, not two, so a signed-in request is held to exactly the
 * same proof of address as an anonymous one, and nothing here needs the access token at all. That is
 * also why a session that lapses after this page has loaded cannot block a request — the intake is
 * public — and why it cannot skip the confirmation either.
 *
 * The address comes from the ID token cookie and is shown read-only. A token with no email claim gets
 * an explanation instead of the buttons: a request with a blank address would be refused by the
 * intake, and offering the press would only promise something that cannot happen.
 *
 * `/shop/account` sits beside `/shop/[slug]`, so `account` is a reserved shop slug
 * (jtoye.shop.reserved-slugs, ShopService) — no vendor shop can shadow this page.
 */

export const metadata: Metadata = {
  title: "My account — J'Toye",
  description: "Get a copy of your data or delete your account.",
  // A signed-in, per-customer surface: never indexed, no canonical version to point a crawler at.
  robots: { index: false, follow: false },
}

export default async function CustomerAccountPage() {
  const jar = await cookies()
  const access = jar.get(ACCESS_COOKIE)?.value
  const refresh = jar.get(REFRESH_COOKIE)?.value

  if (!access && !refresh) {
    return (
      <CustomerSignInPrompt
        message="Sign in to get a copy of your data or delete your account."
        nextPath="/shop/account"
      />
    )
  }

  // An expired access token with a live refresh token still identifies the customer for THIS page:
  // nothing here calls an authenticated API, so there is nothing to renew before rendering.
  const email = displayEmailFromIdToken(jar.get(ID_COOKIE)?.value)
  return <AccountClient email={email} />
}

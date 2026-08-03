import type { Metadata } from "next"
import { cookies } from "next/headers"
import { ACCESS_COOKIE, ID_COOKIE, REFRESH_COOKIE } from "@/lib/customer-auth-cookies"
import {
  displayEmailFromIdToken,
  loadCustomerOrders,
} from "@/lib/customer-orders-server"
import { CustomerSignInPrompt } from "@/components/storefront/customer-signin-prompt"
import { OrdersClient } from "./orders-client"

/**
 * "My Orders" — SERVER component (issues #463, #467).
 *
 * This page was `"use client"` on line 1, so nothing at all rendered from the
 * server: the customer waited through bundle -> hydrate -> effect -> fetch
 * before seeing anything but a spinner. Measured at the repo's throttled mobile
 * profile (390px / 4x CPU / ~Fast 3G), even the "My Orders" heading took ~2.5s
 * to appear, on an API that answers in 13-17ms warm. That is a rendering
 * strategy problem, not a data one, which is why the fix is here rather than in
 * a query.
 *
 * The session's access token is an HttpOnly cookie, which a server component can
 * read — so the orders can be fetched and rendered as HTML before the JS bundle
 * has even arrived. `orders-client.tsx` then hydrates over the top and takes
 * over filtering, pagination and live refresh.
 *
 * The root layout already sets `dynamic = "force-dynamic"` app-wide for the CSP
 * nonce, and reading cookies() is itself dynamic, so there is nothing to cache
 * and no per-customer data can leak into a shared render.
 */

export const metadata: Metadata = {
  title: "My Orders — J'Toye",
  description: "Track your orders and view your order history.",
  // A signed-in, per-customer surface. Nothing here should ever be indexed, and
  // there is no canonical version of it to point a crawler at.
  robots: { index: false, follow: false },
}

export default async function CustomerOrdersPage() {
  const jar = await cookies()
  const access = jar.get(ACCESS_COOKIE)?.value
  const refresh = jar.get(REFRESH_COOKIE)?.value
  const email = displayEmailFromIdToken(jar.get(ID_COOKIE)?.value)

  // No session material at all — an anonymous visitor or a fully aged-out
  // session. Answer from the server: the wall is in the first paint instead of
  // behind a spinner that resolves into it.
  if (!access && !refresh) {
    return (
      <CustomerSignInPrompt
        message="Sign in to view your order history and track deliveries."
        nextPath="/shop/orders"
      />
    )
  }

  // Access token expired, refresh token still alive (#465 — the access cookie's
  // maxAge is the 300s token lifetime, so it simply vanishes mid-session).
  // Renewing means SETTING cookies, which Next only permits in a route handler
  // or server action — not here. Hand to the island, which drives
  // /api/customer-auth/session and then fetches. `initial: null` is precisely
  // this case and nothing else.
  if (!access) {
    return <OrdersClient initial={null} email={email} />
  }

  const initial = await loadCustomerOrders(access)
  return <OrdersClient initial={initial} email={email} />
}

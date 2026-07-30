import type { Metadata } from "next"
import { Suspense } from "react"
import { CustomerSignInCard } from "@/components/storefront/customer-signin-card"

/**
 * CUSTOMER sign-in. The counterpart to /auth/signin, which is the OPERATOR page.
 *
 * These are two different identity pools, not two doors to one: customers live in
 * the `jtoye-customers` Keycloak realm (storefront-client, PKCE, HttpOnly cookies —
 * lib/customer-auth.ts) and staff live in `jtoye-dev` (NextAuth, core-api client —
 * frontend/auth.ts). Before this page existed the public header's "Sign in" CTA
 * pointed at /auth/signin, so a shopper clicking the primary call to action on the
 * landing page was sent to a realm their account does not exist in, with no route
 * back — while the footer's "Vendor sign in" went to the very same URL, so the two
 * personas were indistinguishable.
 *
 * This is a LANDING DESTINATION, not just transit (the /auth/signin precedent):
 * every expired customer session, every /shop/orders deep link and every bookmark
 * ends here, so it carries the brand and never dead-ends. Customer login was
 * previously a bare window.location redirect from a button inside StorefrontNav —
 * there was no page to land on at all.
 *
 * Rendered inside app/shop/layout.tsx, so it inherits StorefrontNav + PublicFooter
 * and a shopper never leaves the storefront chrome to sign in.
 */
export const metadata: Metadata = {
  title: "Sign in to order — J'Toye",
  description:
    "Sign in to J'Toye to place orders with independent UK food vendors, track deliveries and see your order history.",
  alternates: { canonical: "/shop/signin" },
  openGraph: {
    title: "Sign in to order — J'Toye",
    description:
      "Sign in to J'Toye to place orders with independent UK food vendors, track deliveries and see your order history.",
    url: "/shop/signin",
    type: "website",
  },
}

export default function CustomerSignInPage() {
  return (
    <div className="mx-auto flex w-full max-w-md flex-col items-center px-4 py-12 sm:py-20">
      {/* useSearchParams needs a Suspense boundary; the fallback matches the card's
          height so the page does not jump when the real card mounts (CLS). */}
      <Suspense
        fallback={
          <div className="h-[420px] w-full animate-pulse rounded-2xl border border-cream-100 bg-white/60" />
        }
      >
        <CustomerSignInCard />
      </Suspense>
    </div>
  )
}

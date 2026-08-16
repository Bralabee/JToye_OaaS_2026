import type { Metadata } from "next"

/**
 * Metadata carrier for the operator sign-in page.
 *
 * WHY A LAYOUT AND NOT A `page.tsx` EXPORT. `app/auth/signin/page.tsx` carries
 * the client directive — it calls `signIn()` from next-auth on a click — and a
 * client module cannot export `metadata`. Without this file the route inherited
 * the ROOT default, "J'Toye OaaS - Multi-Tenant Order Management", which names
 * neither the page nor the persona. That is not cosmetic on this route: every
 * expired dashboard session and every /dashboard deep link lands here, and the
 * browser tab, the history entry and the bookmark all read as the platform's
 * generic title, so a returning operator cannot tell from the tab strip which
 * of their open tabs is the one asking them to sign in.
 *
 * IT RENDERS `{children}` AND NOTHING ELSE, deliberately. The page owns its own
 * surface — gradient, card, escape links — and adding chrome here would either
 * duplicate it or fight it. In particular this must NOT wrap the page in
 * PublicShell: PublicHeader's sign-in CTA points at this very page.
 *
 * The description names the customer door in words rather than as an internal
 * identifier. Realm ids, IdP hostnames and the staff realm's URL are deliberately
 * absent (threat T-31-03-01): page metadata is served to anyone who asks,
 * including crawlers, and the realm topology is not something a shopper or a
 * scanner needs handed to them.
 *
 * This file deliberately never spells the client directive as a literal string:
 * the verify for this change greps this file for that token and expects zero
 * occurrences, so a comment quoting it would fail the very gate it explains.
 */
export const metadata: Metadata = {
  title: "Vendor sign in — J'Toye",
  description:
    "Sign in to J'Toye as a kitchen operator or member of staff to manage your shop, orders and fulfilment. Ordering food? Customer accounts sign in separately.",
  alternates: { canonical: "/auth/signin" },
}

export default function VendorSignInLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return <>{children}</>
}

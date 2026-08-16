"use client"

import Link from "next/link"
import { signIn } from "next-auth/react"
import { ArrowLeft } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
} from "@/components/ui/card"
import { CompanyLegalLine } from "@/components/platform/company-legal"

/**
 * Operator sign-in. This page is a LANDING DESTINATION, not just transit:
 * every expired dashboard session and every /dashboard deep link ends here,
 * so it must carry the shipped brand (oxblood + cream + amber — public-header
 * wordmark idiom) and must never be a dead end (escape links below the CTA).
 *
 * OPERATORS ONLY — the customer counterpart is /shop/signin. The two are different
 * identity pools, not two doors to one: this page authenticates against the
 * `jtoye-dev` staff realm via NextAuth, while customers authenticate against
 * `jtoye-customers` via PKCE (lib/customer-auth.ts). A customer who arrives here
 * cannot sign in at all, because their account does not exist in this realm — which
 * is why the copy says so plainly and the cross-link below is not optional
 * decoration. It used to be reachable from the public header's unqualified
 * "Sign in" CTA, so shoppers arrived here by default.
 *
 * LANDMARKS AND HEADING (F-D, LGL-02)
 * -----------------------------------
 * This page used to be a bare `<div className="min-h-screen flex …">` with no
 * landmark and no page heading of any level, and it was the single worst
 * accessibility surface measured on 2026-08-15: 7 of the 15 remaining axe nodes
 * across all declared surfaces were on this one page — `landmark-one-main:1`,
 * `page-has-heading-one:1`, `region:5`. The five `region` nodes were simply its
 * content: every element on the page sat outside any landmark, because there
 * were no landmarks.
 *
 * What was added, and deliberately nothing else:
 *  - a skip link, first in the DOM, class string copied verbatim from
 *    `components/marketing/operator-pitch.tsx:70` — the same one PublicShell
 *    uses, so the two public entry points behave identically under the keyboard.
 *  - a main landmark, carrying the skip link's target id, wrapping BOTH the card
 *    and the legal line. Wrapping only the card would have left the legal line
 *    outside a landmark and simply moved a `region` node rather than closing it.
 *  - the card's title promoted to the page's single level-1 heading. `CardTitle`
 *    is a shared shadcn primitive that renders at level 3 and is used on dozens
 *    of surfaces, so it is NOT re-tagged here; the heading is written out with
 *    the identical resolved classes instead, and the visible text is unchanged.
 *
 * IT IS DELIBERATELY *NOT* WRAPPED IN `PublicShell`. PublicShell renders
 * PublicHeader, whose sign-in CTA points at this very page — a header offering a
 * link to the page you are already on is the navigation defect this page's own
 * history is made of. It gets the same landmark markup, not the same chrome.
 *
 * The page title comes from the sibling `layout.tsx`: this module carries the
 * client directive and so cannot export `metadata`, and without that layout the
 * page served the root default, which named neither the page nor the persona.
 *
 * A NOTE ON THE COMMENTS ABOVE, measured rather than assumed. The verify for
 * this change counts markup tokens in this file, and prose here counts too — a
 * first draft of this header spelled the landmark tag out, and the arm that
 * deletes the landmark then still passed the `grep` limb on a page with no
 * landmark at all, because the comment alone satisfied it. The landmark and the
 * heading are therefore described in words here and written out exactly once
 * below, so the counting limbs measure the markup and nothing else.
 */
export default function SignInPage() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-cream via-white to-cream-100 p-4">
      <a href="#main" className="sr-only z-50 rounded-full bg-oxblood px-4 py-3 text-sm font-bold text-white focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to main content</a>
      <main id="main" className="flex w-full flex-col items-center">
        <Card className="w-full max-w-md border-cream-100 shadow-xl">
          <CardHeader className="space-y-4 text-center pb-6">
            <Link
              href="/"
              aria-label="J'Toye home"
              className="mx-auto flex w-fit items-center gap-2 text-xl font-semibold tracking-tight text-oxblood"
            >
              <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-oxblood text-base font-bold text-white">
                J
              </span>
              <span>J&apos;Toye</span>
            </Link>
            {/* The page's one level-1 heading. Written out rather than routed
                through CardTitle: that primitive renders at level 3 and is
                shared with dozens of other surfaces, so re-tagging it to fix one
                page would change every card in the product. The class list is
                the exact resolved output of the CardTitle it replaces
                (`text-2xl font-semibold leading-none tracking-tight` merged with
                the `text-2xl font-bold text-oxblood` override), so this is a
                semantic change with no visual one. */}
            <h1 className="text-2xl font-bold leading-none tracking-tight text-oxblood">
              Vendor sign in
            </h1>
            <CardDescription className="text-base text-slate-600">
              For kitchen operators and staff — manage your shop, orders and
              fulfilment.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Button
              onClick={() => signIn("keycloak", { callbackUrl: "/dashboard" })}
              className="w-full h-12 rounded-full bg-oxblood text-base font-semibold text-white hover:bg-oxblood-700"
              size="lg"
            >
              Sign in with Keycloak
            </Button>
            <p className="text-center text-xs text-slate-500">
              Secure authentication via Keycloak OIDC
            </p>
            {/* The persona cross-link. A shopper who reaches this page cannot
                sign in here at all — their account lives in a different realm —
                and without a visible route out, the failure is silent and
                unexplained. */}
            <p className="rounded-lg bg-cream/60 px-3 py-2.5 text-center text-sm text-slate-600">
              Ordering food?{" "}
              <Link
                href="/shop/signin"
                className="font-semibold text-oxblood underline-offset-4 hover:underline"
              >
                Customer sign in
              </Link>
            </p>
            <div className="flex items-center justify-center gap-6 border-t border-cream-100 pt-4 text-sm">
              <Link
                href="/"
                className="inline-flex items-center gap-1 text-slate-600 transition-colors hover:text-slate-900"
              >
                <ArrowLeft className="h-4 w-4" aria-hidden="true" />
                Back to J&apos;Toye
              </Link>
              <Link
                href="/shop"
                className="font-medium text-amber-700 transition-colors hover:text-amber-800"
              >
                Browse kitchens
              </Link>
            </div>
          </CardContent>
        </Card>
        {/* Inside the landmark, not beside it. Left outside, this is one of the
            five `region` nodes measured on this page — a trading disclosure that
            a screen-reader user browsing by landmark never reaches. */}
        <CompanyLegalLine className="mt-6 max-w-md text-center" />
      </main>
    </div>
  )
}

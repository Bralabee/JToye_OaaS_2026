import type { Metadata } from "next"
import Link from "next/link"
import { openGraphWithDefaultImage } from "@/lib/og-image"
import { PublicShell } from "@/components/public/public-shell"
import { LEGAL_LINK_CLASS, LEGAL_PROSE_LINK_CLASS } from "@/components/legal/policy-page"
import { getCompanyInfo } from "@/lib/company"
import { LAUNCH_AREA, POSITIONING_LINE } from "@/lib/positioning"

const DESCRIPTION = `About J'Toye: an ordering platform for independent food businesses, run by J'Toye Digital Ltd. ${POSITIONING_LINE}`

export const metadata: Metadata = {
  title: "About J'Toye",
  description: DESCRIPTION,
  alternates: { canonical: "/about" },
  // SW-CLR-08: a page's own openGraph replaces the root's, so the default share image is added here.
  openGraph: openGraphWithDefaultImage({
    title: "About J'Toye",
    description: DESCRIPTION,
    url: "/about",
    type: "website",
  }),
}

/**
 * /about (issue 804 / UXT-032, persona close-out; the page 404ed).
 *
 * Who J'Toye is, what it does and does not do, where it operates, and how to
 * reach a person. Three rules shape the copy:
 *
 *  - POSITIONING IS THE RULED LINE. OWNER RULING 2026-11-04: UK-wide, launching
 *    first in Birmingham and the West Midlands (lib/positioning.ts). No London
 *    pilot, no single-community pilot.
 *  - THE COMPANY FACTS ARE lib/company.ts's, never typed here, and the
 *    identity is the platform operator's (J'Toye Digital Ltd, by number), never
 *    a vendor's.
 *  - NOTHING ASPIRATIONAL IS STATED AS FACT. The same restraint as /for-operators:
 *    what the platform does is stated in the page's own words (J'Toye provides
 *    the platform; each kitchen is the seller of the food it lists), and no
 *    customer or vendor count, award or press claim is made. The checkout's
 *    platform-role sentence is a PER-ORDER one ("the seller named here") and
 *    is deliberately NOT reused on a company page (review finding, PR 1116).
 *
 * Layout is /contact's and /legal's (PublicShell and the legal link classes):
 * no new visual design.
 */
export default function AboutPage() {
  const c = getCompanyInfo()
  return (
    <PublicShell>
      <div className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
        <h1 className="text-[28px] font-semibold leading-tight tracking-tight text-oxblood">
          About J&apos;Toye
        </h1>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          J&apos;Toye is an ordering platform for independent food businesses.
          Customers find a kitchen, order and pay on the kitchen&apos;s own
          storefront; the kitchen sees the order on a live display and cooks.
        </p>

        <h2 className="mt-12 text-xl font-semibold tracking-tight text-oxblood">
          Where we operate
        </h2>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          {POSITIONING_LINE} If you run a food business anywhere in the UK you
          can apply; we are working first with kitchens in {LAUNCH_AREA}.
        </p>

        <h2 className="mt-12 text-xl font-semibold tracking-tight text-oxblood">
          What we do, and what we do not
        </h2>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          We provide the software: storefronts, ordering, a kitchen display,
          allergen information and labels, and card payments through Stripe.
          J&apos;Toye provides the platform, and each kitchen is the seller of
          the food it lists. The food, its description and its allergen
          information are the kitchen&apos;s responsibility.
        </p>

        <h2 className="mt-12 text-xl font-semibold tracking-tight text-oxblood">
          The company
        </h2>
        <dl className="mt-4 space-y-4 text-base leading-relaxed">
          <div>
            <dt className="font-semibold text-oxblood">Registered company name</dt>
            <dd className="text-slate-600">{c.legalName}</dd>
          </div>
          <div>
            <dt className="font-semibold text-oxblood">Company number</dt>
            <dd className="text-slate-600">{c.companyNumber}</dd>
          </div>
          <div>
            <dt className="font-semibold text-oxblood">Place of registration</dt>
            <dd className="text-slate-600">{c.registrationJurisdiction}</dd>
          </div>
        </dl>

        <h2 className="mt-12 text-xl font-semibold tracking-tight text-oxblood">
          Talk to us
        </h2>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          Our{" "}
          <Link href="/contact" className={LEGAL_PROSE_LINK_CLASS}>
            contact page
          </Link>{" "}
          has the ways to reach us. If you run a food business and want to join,{" "}
          <Link href="/contact#apply" className={LEGAL_PROSE_LINK_CLASS}>
            start your application
          </Link>
          , and see the{" "}
          <Link href="/pricing" className={LEGAL_PROSE_LINK_CLASS}>
            pricing
          </Link>{" "}
          and the{" "}
          <Link href="/legal" className={LEGAL_PROSE_LINK_CLASS}>
            legal and company information
          </Link>
          .
        </p>

        <p className="mt-14 text-base">
          <Link href="/" className={`inline-flex min-h-11 items-center ${LEGAL_LINK_CLASS}`}>
            ← Back to J&apos;Toye
          </Link>
        </p>
      </div>
    </PublicShell>
  )
}

import type { Metadata } from "next"
import Link from "next/link"
import { PublicShell } from "@/components/public/public-shell"
import { LEGAL_LINK_CLASS, LEGAL_PROSE_LINK_CLASS } from "@/components/legal/policy-page"
import { StripeUkPricingSentence } from "@/components/marketing/stripe-uk-pricing-sentence"
import { OPERATOR_APPLICATION_REVIEW_LINE } from "@/lib/operator-copy"
import {
  formatSetupLine,
  PRICING,
  PRICING_CARD_FEE_LINE,
  PRICING_VAT_BASIS_LINE,
  pricingPlans,
} from "@/lib/pricing"

const DESCRIPTION =
  "What J'Toye costs a food business: three plans per location, the one-off assisted setup, how VAT applies, and where to find Stripe's card fees. Merchant terms linked."

export const metadata: Metadata = {
  title: "Pricing for food businesses | J'Toye",
  description: DESCRIPTION,
  alternates: { canonical: "/pricing" },
  openGraph: {
    title: "Pricing for food businesses | J'Toye",
    description: DESCRIPTION,
    url: "/pricing",
    type: "website",
  },
}

/**
 * /pricing (issue 804 / UXT-032, persona close-out; the page 404ed).
 *
 * Every figure is lib/pricing.ts's, the module /for-operators, /legal/terms and
 * the merchant terms read, so no page can state a different price. This page
 * adds the two things the persona run found missing:
 *
 *  - the VAT BASIS, from PRICING_VAT_BASIS_LINE (OWNER RULING 2026-11-04: prices
 *    include VAT for VAT-registered businesses and carry no VAT for businesses
 *    that are not VAT-registered). No VAT number or registration status is
 *    stated for J'Toye; none was ruled.
 *  - the card-fee answer, which is a LINK to Stripe's UK pricing page and never
 *    a figure (owner ruling 2026-11-01): Stripe sets the rate on the vendor's own
 *    account and changes it on its own schedule.
 *
 * The internal WALK_SETUP_STEPDOWN tier is not part of lib/pricing.ts and so
 * cannot appear here. The layout is the /contact and /legal one (PublicShell,
 * the legal link classes): no new visual design.
 */
export default function PricingPage() {
  const plans = pricingPlans(PRICING)
  return (
    <PublicShell>
      <div className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
        <h1 className="text-[28px] font-semibold leading-tight tracking-tight text-oxblood">
          Pricing for food businesses
        </h1>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          J&apos;Toye charges per location. {OPERATOR_APPLICATION_REVIEW_LINE}
        </p>

        <table className="mt-8 w-full border-collapse text-left text-base">
          <caption className="sr-only">J&apos;Toye plans, per location</caption>
          <thead>
            <tr>
              <th scope="col" className="border-b border-oxblood/15 pb-3 pr-3 text-sm font-semibold text-slate-600">Plan</th>
              <th scope="col" className="border-b border-oxblood/15 pb-3 pr-3 text-sm font-semibold text-slate-600">Monthly</th>
              <th scope="col" className="border-b border-oxblood/15 pb-3 text-sm font-semibold text-slate-600">Platform fee</th>
            </tr>
          </thead>
          <tbody>
            {plans.map((plan) => (
              <tr key={plan.name}>
                <th scope="row" className="border-b border-oxblood/15 py-4 pr-3 align-top font-bold text-oxblood">
                  {plan.name}
                  <span className="mt-0.5 block text-sm font-normal text-slate-600">{plan.note}</span>
                </th>
                <td className="border-b border-oxblood/15 py-4 pr-3 align-top font-bold tabular-nums text-oxblood">
                  {plan.monthly[0]}
                  {plan.monthly[1] ? (
                    <span className="block text-sm font-normal text-slate-600">{plan.monthly[1]}</span>
                  ) : null}
                </td>
                <td className="border-b border-oxblood/15 py-4 align-top font-bold tabular-nums text-oxblood">
                  {plan.fee[0]}
                  <span className="block text-sm font-normal text-slate-600">{plan.fee[1]}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <dl className="mt-8 space-y-5 text-base leading-relaxed">
          <div>
            <dt className="font-semibold text-oxblood">One-off setup</dt>
            <dd className="text-slate-600">{formatSetupLine(PRICING)}</dd>
          </div>
          <div>
            <dt className="font-semibold text-oxblood">VAT</dt>
            <dd className="text-slate-600">{PRICING_VAT_BASIS_LINE}</dd>
          </div>
          <div>
            <dt className="font-semibold text-oxblood">Card fees</dt>
            <dd className="text-slate-600">
              {PRICING_CARD_FEE_LINE}{" "}
              <StripeUkPricingSentence linkClassName={LEGAL_PROSE_LINK_CLASS} />
            </dd>
          </div>
        </dl>

        <p className="mt-8 text-base leading-relaxed text-slate-600">
          The terms that go with these prices are the{" "}
          <Link href="/legal/merchant-terms" className={LEGAL_PROSE_LINK_CLASS}>
            merchant terms
          </Link>
          , currently a draft under legal review. To ask a question first, use
          the{" "}
          <Link href="/contact" className={LEGAL_PROSE_LINK_CLASS}>
            contact page
          </Link>
          .
        </p>

        <p className="mt-8">
          <Link
            href="/contact#apply"
            className="inline-flex min-h-11 items-center rounded-full bg-amber-500 px-6 py-3 text-base font-bold text-amber-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-500"
          >
            Start your application
          </Link>
        </p>

        <p className="mt-10 text-base">
          <Link
            href="/for-operators"
            className={`inline-flex min-h-11 items-center ${LEGAL_LINK_CLASS}`}
          >
            ← What the pilot includes
          </Link>
        </p>
      </div>
    </PublicShell>
  )
}

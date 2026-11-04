import type { Metadata } from "next"
import Link from "next/link"
import { openGraphWithDefaultImage } from "@/lib/og-image"

import {
  LEGAL_PROSE_LINK_CLASS,
  PolicyPage,
  PolicySection,
} from "@/components/legal/policy-page"
import { StripeUkPricingSentence } from "@/components/marketing/stripe-uk-pricing-sentence"
import { getCompanyInfo } from "@/lib/company"
import {
  OPERATOR_APPLICATION_REVIEW_LINE,
  OPERATOR_CONFIDENTIALITY_LINE,
  OPERATOR_FBO_LINE,
} from "@/lib/operator-copy"
import {
  formatSetupLine,
  planSentence,
  PRICING,
  PRICING_CARD_FEE_LINE,
  PRICING_VAT_BASIS_LINE,
  pricingPlans,
} from "@/lib/pricing"
import { POSITIONING_LINE } from "@/lib/positioning"

const DRAFT_MARKER =
  "Draft — to be reviewed by a solicitor before it applies to paying vendors"

const DESCRIPTION =
  "Draft merchant terms for food businesses on J'Toye: fees and billing, VAT, card payments and payouts through Stripe, refunds, food safety and allergens, data protection roles, suspension and liability. Not yet in force."

export const metadata: Metadata = {
  title: "Merchant terms (draft) — J'Toye",
  description: DESCRIPTION,
  alternates: { canonical: "/legal/merchant-terms" },
  // SW-CLR-08: a page's own openGraph replaces the root's, so the default share image is added here.
  openGraph: openGraphWithDefaultImage({
    title: "Merchant terms (draft) — J'Toye",
    description: DESCRIPTION,
    url: "/legal/merchant-terms",
    type: "website",
  }),
}

/**
 * Merchant terms for food businesses (issue 804 / UXT-032, persona close-out).
 *
 * ── A DRAFT, AND IT SAYS SO ──────────────────────────────────────────────────
 *
 * OWNER RULING 2026-11-04: the owner asked for these terms to be DRAFTED. They
 * are drafted by an engineer, from what the platform technically does, so that a
 * solicitor has something concrete to correct. The page is marked a draft in the
 * title, the standfirst, a visible status section and the version string, and it
 * says it does not yet apply. It must not be edited to read as in force until a
 * solicitor has reviewed it and the owner has said so; at that point the marker,
 * the version and the status section change together.
 *
 * Also from the 2026-11-04 rulings, applied below: the VAT basis is
 * PRICING_VAT_BASIS_LINE (prices include VAT for VAT-registered businesses and
 * carry none for businesses that are not VAT-registered; no VAT number is
 * stated for J'Toye) and the positioning is lib/positioning.ts's.
 *
 * ── WHERE EACH STATEMENT COMES FROM ──────────────────────────────────────────
 *
 *  - Parties, seller / merchant of record, payouts, deposits: ADR-0007
 *    (docs/architecture/decisions), default DIRECT_CHARGE_VENDOR_MOR: the charge
 *    is made on the vendor's own connected Stripe account, J'Toye holds no funds
 *    and takes an application fee. Other settlement modes exist in the code; the
 *    text says "unless we agree otherwise in writing for your shop".
 *  - Stripe fees and losses: the Phase 30 ruling (30-30, 2026-11-02, Managed
 *    Risk): Stripe, not J'Toye, collects losses on a connected account, and the
 *    vendor pays Stripe's processing fees. The fee itself is a LINK to Stripe's
 *    UK pricing page, never a figure (ruling 2026-11-01).
 *  - Fees: lib/pricing.ts, the module /for-operators, /pricing and /legal/terms
 *    read.
 *  - Food safety: the Food Business Operator line (lib/operator-copy.ts), the
 *    four onboarding gates (BUSINESS_VERIFIED, FOOD_HYGIENE_RATING,
 *    ALLERGEN_DATA_COMPLETE, TRADER_IDENTITY), the 14-allergen declaration and
 *    the separate "may contain" statement, PPDS labels, and the shop-side
 *    acknowledgement of a customer's allergy note.
 *  - Data roles: the privacy notice (app/legal/privacy/page.tsx) and
 *    docs/legal/article-26-arrangement.md. J'Toye and the vendor are JOINTLY
 *    responsible for consumer order data; the vendor is responsible and J'Toye
 *    its processor for the vendor's own customer records; the customer
 *    storefront account and staff sign-in accounts are J'Toye's alone. This page
 *    must not be edited into contradicting the notice.
 *  - Confidentiality: row-level security by tenant (OPERATOR_CONFIDENTIALITY_LINE).
 *  - Suspension and offboarding: the tenant lifecycle (status, suspension,
 *    offboarding, identity-provider deprovisioning) and the retention schedule.
 *
 * ── POINTS FOR THE SOLICITOR (not settled here) ──────────────────────────────
 *
 *  1. The liability cap (twelve months of fees) and the exclusions are a
 *     conventional starting position chosen by the drafter, not a ruling.
 *  2. The notice period is left to be agreed per plan; no number is invented.
 *  3. How billing periods, late payment and fee changes work is not specified in
 *     code and is stated only as far as the code supports.
 *  4. Whether the Article 26 arrangement and a processing agreement are
 *     incorporated by reference or signed separately.
 *  5. Whether VAT applies to J'Toye's fees depends on J'Toye Digital's own
 *     status; the ruled sentence is stated as ruled and no registration fact is
 *     asserted.
 */

const SECTIONS = [
  "Status of this draft",
  "Who the parties are",
  "What J'Toye provides",
  "Fees and billing",
  "VAT",
  "Card payments and payouts",
  "Refunds, disputes and chargebacks",
  "Food safety, allergens and labelling",
  "Your customers' personal data",
  "Keeping vendors' data apart",
  "Suspension, termination and offboarding",
  "Liability",
  "Governing law and changes",
  "Contact",
] as const

const LAST_UPDATED = "4 November 2026"
const VERSION = "0.1 (draft)"

export default function MerchantTermsPage() {
  const company = getCompanyInfo()

  return (
    <PolicyPage
      title="Merchant terms (draft)"
      lastUpdated={LAST_UPDATED}
      version={VERSION}
      sections={SECTIONS}
      intro={
        <>
          <strong>{DRAFT_MARKER}.</strong> These are the terms we propose for
          food businesses that sell through J&apos;Toye, written in plain
          English. They do not yet apply to anyone.
        </>
      }
    >
      <PolicySection heading={SECTIONS[0]}>
        <p className="border-l-[3px] border-amber-500 bg-amber-50 px-4 py-3 text-slate-800">
          <strong>{DRAFT_MARKER}.</strong> These terms are not yet in force.
          Joining the pilot does not make these terms binding, and nothing is
          charged until a plan is agreed with you. The version and date above
          change when the terms are reviewed and confirmed.
        </p>
        <p>
          Customers of a shop are covered separately by the{" "}
          <Link href="/legal/terms" className={LEGAL_PROSE_LINK_CLASS}>
            terms of use
          </Link>
          .
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[1]}>
        <p>
          <strong>The platform provider</strong> is {company.legalName},
          registered in {company.registrationJurisdiction} under company number{" "}
          {company.companyNumber} (&ldquo;J&apos;Toye&rdquo;, &ldquo;we&rdquo;).
        </p>
        <p>
          <strong>The vendor</strong> is the food business that opens a shop on
          J&apos;Toye (&ldquo;you&rdquo;), under its own legal identity, which
          you give us when you apply.
        </p>
        <p>
          You are the <strong>seller</strong> of the food on your shop and the{" "}
          <strong>merchant of record</strong> for the card payments for it.
          J&apos;Toye is the ordering platform, not the seller: the contract for
          an order is between the customer and you. This is the position unless
          we agree something different in writing for your shop.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[2]}>
        <p>
          We provide the software that lets you run a shop: a storefront,
          ordering and scheduled fulfilment, a kitchen display, allergen
          information and labels, customer tracking, and card payment through
          Stripe. {POSITIONING_LINE} We do not bring you customers by ourselves,
          and we do not promise any level of sales or uptime.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[3]}>
        <p>
          {OPERATOR_APPLICATION_REVIEW_LINE} The plans are billed per location:
        </p>
        {/* The same pricingPlans(PRICING) rows /pricing renders as its table,
            as sentences by planSentence(): one source, so the two cannot drift. */}
        <ul className="list-disc space-y-2 pl-6">
          {pricingPlans(PRICING).map((plan) => (
            <li key={plan.name}>{planSentence(plan)}</li>
          ))}
        </ul>
        <p>
          &ldquo;Direct sales&rdquo; are the card payments taken through your
          J&apos;Toye storefront. The platform fee is taken automatically from
          each of those payments as an application fee, and the monthly fee is
          billed through Stripe. {formatSetupLine(PRICING)} We will tell you in
          writing before a change to our fees takes effect. If a fee stays unpaid
          after we have asked for it, we may suspend your shop (see
          &ldquo;Suspension, termination and offboarding&rdquo;).
        </p>
        <p>
          The same plans and figures are shown as a table on the{" "}
          <Link href="/pricing" className={LEGAL_PROSE_LINK_CLASS}>
            pricing page
          </Link>
          .
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[4]}>
        <p>{PRICING_VAT_BASIS_LINE}</p>
        <p>
          Your own VAT position on the food you sell is yours. The order
          breakdown a customer sees shows a VAT line only for a seller who has
          declared a VAT registration.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[5]}>
        <p>
          Card payments are made through Stripe. You connect your own Stripe
          account to J&apos;Toye when you onboard (Stripe Connect), and you agree
          Stripe&apos;s terms for that account directly with Stripe.
        </p>
        <p>
          Unless we agree otherwise in writing for your shop, a customer&apos;s
          card payment is charged directly on your Stripe account. The money goes
          to your Stripe account and J&apos;Toye does not hold it. If your shop
          takes a deposit and a later balance payment, both are charged to your
          Stripe account in the same way. Stripe pays money out to your bank on
          the payout schedule of your Stripe account; we do not control or
          guarantee when a payout arrives.
        </p>
        <p>
          <strong>Stripe&apos;s processing fees are paid by you.</strong> Stripe
          takes them from your Stripe account; we do not set them and do not take
          a share of them. {PRICING_CARD_FEE_LINE}{" "}
          <StripeUkPricingSentence linkClassName={LEGAL_PROSE_LINK_CLASS} />
        </p>
        <p>
          Your Stripe account is set up so that Stripe, not J&apos;Toye, collects
          any loss on it, including a negative balance left by a refund or a
          dispute. Stripe may therefore look to you directly, and J&apos;Toye
          does not cover a negative balance on your account.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[6]}>
        <p>
          Because you are the seller, refunds, replacements and complaints about
          the food are yours to handle, under consumer law and the cancellation
          statement shown to the customer at checkout. Refunds go back to the
          customer&apos;s card through Stripe, and Stripe&apos;s rules decide what
          happens to its own fees on a refunded payment.
        </p>
        <p>
          A dispute (chargeback) is between you, the customer&apos;s bank and
          Stripe. Answer Stripe&apos;s requests for evidence before its deadline;
          we will give you the order records we hold that help you answer.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[7]}>
        <p>{OPERATOR_FBO_LINE}</p>
        <p>
          You must be registered with your local authority as a food business,
          keep that registration current, and tell us if your food hygiene rating
          changes. Before a shop can be published we check that the business is
          verified, that it has a food hygiene rating, that its allergen data is
          complete and that its trader identity is on file. These checks decide
          whether a shop can go live; they are not a certification of your food
          or your kitchen.
        </p>
        <p>
          You declare which of the 14 allergens each dish contains. What a
          customer is shown at checkout, and what is printed on a label, is what
          you declared: we do not check recipes or kitchens and we do not verify
          your declarations. A &ldquo;may contain&rdquo; statement is separate
          from a declaration and is never merged into it.
        </p>
        <p>
          For food packed on your premises for direct sale, Natasha&apos;s Law
          requires a label with the ingredients and the allergens emphasised. We
          can produce that label from your product data; it is only as accurate
          as that data, and printing it and putting it on the food is yours to
          do.
        </p>
        <p>
          When a customer writes an allergy request on an order, you must read it
          before you prepare the order and record that you have. J&apos;Toye
          prompts for this and keeps the record; it does not decide whether you
          can safely cook the order.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[8]}>
        <p>
          For the personal data created by a customer&apos;s order, J&apos;Toye
          and you are jointly responsible, as the{" "}
          <Link href="/legal/privacy" className={LEGAL_PROSE_LINK_CLASS}>
            privacy notice
          </Link>{" "}
          sets out. For the records you keep about your own customers on your
          dashboard, including any note of a customer&apos;s allergies, you are
          responsible and J&apos;Toye is your processor: we store them on your
          behalf and act on your instructions. A customer&apos;s J&apos;Toye
          storefront account, and the sign-in accounts of your staff, are
          J&apos;Toye&apos;s responsibility.
        </p>
        <p>
          Use customer data only to fulfil orders and look after customers, send
          marketing only where the customer has agreed, and help us answer a data
          request promptly. A customer can ask J&apos;Toye once for every shop
          that holds their details. The detailed written arrangement and
          processing terms are being finalised with these terms.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[9]}>
        <p>{OPERATOR_CONFIDENTIALITY_LINE}</p>
        <p>
          We use your business data to run the platform, and we do not give your
          sales or customer records to other food businesses.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[10]}>
        <p>
          We may stop your shop taking orders, or end the agreement, if you
          breach these terms; if your food business registration or your Stripe
          account stops being valid; if a fee stays unpaid after we have asked
          for it; or if the law or Stripe requires it. Where we reasonably can,
          we will tell you why first and give you the chance to put it right.
        </p>
        <p>
          You may stop at any time by telling us in writing. The notice period
          for your plan is agreed with you when the plan is agreed.
        </p>
        <p>
          When the agreement ends we take your shops off the public site and close
          your dashboard and your staff&apos;s sign-ins, and then keep or delete
          your data as the{" "}
          <Link href="/legal/retention" className={LEGAL_PROSE_LINK_CLASS}>
            data retention schedule
          </Link>{" "}
          says. Ask us for a copy of your order records before you leave. Your
          Stripe account is yours and stays with Stripe.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[11]}>
        <p>
          Nothing in these terms limits liability that the law does not allow to
          be limited, including liability for death or personal injury caused by
          negligence, or for fraud.
        </p>
        <p>
          Subject to that, we are not liable for loss of profit, revenue or
          goodwill, or for indirect loss; we are not responsible for the food you
          sell, its safety or description, or your compliance with food law; and
          our total liability to you for all claims connected with the platform is
          limited to the platform fees you paid us in the twelve months before the
          claim.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[12]}>
        <p>
          These terms, and any dispute about them, are governed by the law of
          England and Wales, and the courts of England and Wales have
          jurisdiction. We will tell you in writing about a change to these terms
          before it applies to you.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[13]}>
        <p>
          To ask a question about these terms, or to apply, see our{" "}
          <Link href="/contact" className={LEGAL_PROSE_LINK_CLASS}>
            contact page
          </Link>
          .
        </p>
      </PolicySection>
    </PolicyPage>
  )
}

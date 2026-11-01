import type { Metadata } from "next"
import Link from "next/link"

import {
  LEGAL_PROSE_LINK_CLASS,
  PolicyPage,
  PolicySection,
} from "@/components/legal/policy-page"
import {
  CANCELLATION_STATEMENT_COPY,
  PLATFORM_NOT_SELLER_COPY,
} from "@/components/storefront/seller-block"
import { NO_VAT_REGISTRATION_COPY } from "@/components/storefront/no-vat-registration-note"
import { getCompanyInfo } from "@/lib/company"
import { OPERATOR_APPLICATION_REVIEW_LINE, OPERATOR_FBO_LINE } from "@/lib/operator-copy"
import {
  formatRate,
  formatSetupLine,
  PRICING,
  PRICING_CARD_FEE_LINE,
  STRIPE_UK_PRICING_LINK_TEXT,
  STRIPE_UK_PRICING_URL,
} from "@/lib/pricing"

export const metadata: Metadata = {
  title: "Terms of use — J'Toye",
  description:
    "How ordering through J'Toye works: who runs the platform, who you are buying from, cancellation, VAT, and the terms for food businesses on the pilot.",
  alternates: { canonical: "/legal/terms" },
}

/**
 * Terms of use (SW-SAM-12, swarm epic #964).
 *
 * ── OWNER SIGN-OFF REQUIRED BEFORE MERGE ─────────────────────────────────────
 *
 * This page is a COMPILATION, not new legal drafting. Every statement below is
 * copy the site already publishes somewhere else, and each section names its
 * source in the comment above it. Nothing here was written as a fresh term:
 * there is no governing-law clause, no liability clause, no indemnity and no
 * fee that is not already printed on /for-operators, because none of those is
 * published anywhere on the site or in docs/, and inventing one on a page
 * titled "terms" would be the worst place to invent it. The co-located test
 * asserts their absence on the rendered text.
 *
 * The owner's wording sign-off is still required before this merges: putting
 * existing statements under a "Terms of use" heading changes what a reader may
 * take them to promise.
 *
 * ── QUOTED, NOT RETYPED ──────────────────────────────────────────────────────
 *
 * The seller, cancellation and VAT statements are imported from the modules the
 * checkout renders them from (seller-block.tsx records that its copy is legally
 * operative and must not be paraphrased in passing). The pricing figures come
 * from lib/pricing.ts, the same module /for-operators reads. A ruling on any of
 * them is therefore one edit, and this page cannot drift from the checkout.
 */

const SECTIONS = [
  "Who runs J'Toye",
  "Who you are buying from",
  "Cancelling an order",
  "Prices and VAT",
  "Food businesses on J'Toye",
  "Your personal data",
  "Contact",
] as const


const LAST_UPDATED = "1 November 2026"
const VERSION = "1.0"

export default function TermsPage() {
  const company = getCompanyInfo()

  return (
    <PolicyPage
      title="Terms of use"
      lastUpdated={LAST_UPDATED}
      version={VERSION}
      sections={SECTIONS}
      intro="This page brings together, in one place, what J'Toye already tells you at checkout and on its other pages about how ordering through the platform works."
    >
      {/* Source: app/legal/privacy/page.tsx "Who we are" (operator identity,
          rendered from lib/company.ts) and app/legal/page.tsx (the trading line). */}
      <PolicySection heading={SECTIONS[0]}>
        <p>
          The J&apos;Toye platform is operated by {company.legalName},
          registered in {company.registrationJurisdiction} under company number{" "}
          {company.companyNumber}.
        </p>
        <p>
          Individual vendor shops listed on the platform are run by their own
          businesses and remain responsible for their own trading disclosures.
        </p>
      </PolicySection>

      {/* Source: app/legal/privacy/page.tsx "Who is responsible for what", and
          PLATFORM_NOT_SELLER_COPY from components/storefront/seller-block.tsx
          (shown on the shop page, at checkout and on the confirmation). */}
      <PolicySection heading={SECTIONS[1]}>
        <p>
          The particular shop you are buying from is named to you at the point
          of order, on the shop page and again on your order confirmation, so
          you always know which business is the other party.
        </p>
        <p>{PLATFORM_NOT_SELLER_COPY}</p>
      </PolicySection>

      {/* Source: CANCELLATION_STATEMENT_COPY, components/storefront/seller-block.tsx. */}
      <PolicySection heading={SECTIONS[2]}>
        <p>{CANCELLATION_STATEMENT_COPY}</p>
      </PolicySection>

      {/* Source: NO_VAT_REGISTRATION_COPY and the rule it documents,
          components/storefront/no-vat-registration-note.tsx (checkout and the
          order page). */}
      <PolicySection heading={SECTIONS[3]}>
        <p>
          Your order breakdown shows a VAT line only for a seller who has
          declared a VAT registration. Where the seller has not, the breakdown
          says instead: &ldquo;{NO_VAT_REGISTRATION_COPY}&rdquo;
        </p>
      </PolicySection>

      {/* Source: components/marketing/operator-pitch.tsx (/for-operators): the
          plan table (figures from lib/pricing.ts), the two pricing footnotes,
          and the application and Food Business Operator lines, both imported
          from lib/operator-copy.ts. */}
      <PolicySection heading={SECTIONS[4]}>
        <p>
          J&apos;Toye runs an assisted pilot for food businesses.{" "}
          {OPERATOR_APPLICATION_REVIEW_LINE} The plans are:
        </p>
        <ul className="list-disc space-y-2 pl-6">
          <li>
            <strong>Standard:</strong> £{PRICING.coreMonthly} per location per
            month, plus {PRICING.usageLabel} of direct sales.
          </li>
          <li>
            <strong>Flat:</strong> £{PRICING.flatLow}–£{PRICING.flatHigh} per
            location per month, {formatRate(0)} of sales.
          </li>
          <li>
            <strong>Batch pay-as-you-go:</strong> £0 per month, plus{" "}
            {PRICING.paygLabel} of direct sales.
          </li>
        </ul>
        <p>
          {formatSetupLine(PRICING)} {PRICING_CARD_FEE_LINE} Current rates
          are on{" "}
          <a
            href={STRIPE_UK_PRICING_URL}
            className={LEGAL_PROSE_LINK_CLASS}
            rel="noopener noreferrer"
            target="_blank"
          >
            {STRIPE_UK_PRICING_LINK_TEXT}
            <span className="sr-only"> (opens in a new tab)</span>
          </a>
          .
        </p>
        <p>{OPERATOR_FBO_LINE}</p>
        <p>
          The full pilot description is on the{" "}
          <Link href="/for-operators" className={LEGAL_PROSE_LINK_CLASS}>
            operator page
          </Link>
          .
        </p>
      </PolicySection>

      {/* Source: the policy descriptions in app/legal/page.tsx. */}
      <PolicySection heading={SECTIONS[5]}>
        <p>
          How J&apos;Toye and the vendors on it use your personal data, who is
          responsible for what, and how to exercise your rights, is set out in
          the{" "}
          <Link href="/legal/privacy" className={LEGAL_PROSE_LINK_CLASS}>
            privacy notice
          </Link>
          . How long each category of data is kept is in the{" "}
          <Link href="/legal/retention" className={LEGAL_PROSE_LINK_CLASS}>
            data retention schedule
          </Link>
          , and every cookie and item of browser storage is listed in the{" "}
          <Link href="/legal/cookies" className={LEGAL_PROSE_LINK_CLASS}>
            cookie and browser-storage policy
          </Link>
          .
        </p>
      </PolicySection>

      {/* Source: app/contact/page.tsx and app/legal/page.tsx. */}
      <PolicySection heading={SECTIONS[6]}>
        <p>
          To contact J&apos;Toye, see our{" "}
          <Link href="/contact" className={LEGAL_PROSE_LINK_CLASS}>
            contact page
          </Link>
          . For a question about an order, contact the shop you ordered from:
          its details are shown on the shop page, at checkout and on your order
          confirmation.
        </p>
      </PolicySection>
    </PolicyPage>
  )
}

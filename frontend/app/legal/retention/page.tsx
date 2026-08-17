import type { Metadata } from "next"
import Link from "next/link"

import { PolicyPage, PolicySection } from "@/components/legal/policy-page"
import {
  RetentionTable,
  type RetentionRow,
} from "@/components/legal/retention-table"
import { resolveControllerContact } from "@/lib/company"

export const metadata: Metadata = {
  title: "Data retention schedule — J'Toye",
  description:
    "How long J'Toye keeps each category of data, the lawful basis, and whether the period is enforced automatically.",
  alternates: { canonical: "/legal/retention" },
}

/**
 * The published data-retention schedule (LGL-01 / D-07, UI-SPEC S2 + S2a).
 *
 * ── WHY THE ROWS BELOW ARE TRANSCRIBED RATHER THAN IMPORTED ──────────────────
 *
 * `docs/retention-manifest.json` is the source of truth for every period on this
 * page, and importing it here would make drift structurally impossible — one
 * copy, no gate needed. That was tried FIRST and it does not build. Measured on
 * this tree, not assumed:
 *
 *     Module not found: Can't resolve '../../../docs/retention-manifest.json'
 *
 * The relative path is correct (`ls` resolves it to the real 14 KB file); Next
 * 16's Turbopack simply refuses to resolve modules outside the `frontend/`
 * project root. Jest's resolver has no such restriction and reads the same path
 * happily, which is what makes the arrangement below work.
 *
 * So these rows ARE a second copy, and a second copy of a number is a drift
 * surface. Two independent things prevent that drift, and neither is optional:
 *
 *   1. `components/legal/__tests__/retention-table.a11y.test.tsx` imports the
 *      manifest directly and asserts these rows equal it FIELD BY FIELD — every
 *      category, detail, period, lawful basis and enforcement class, plus the
 *      row COUNT, so a row cannot be silently unpublished either.
 *   2. `scripts/gates/claims.manifest` carries one rule row per published
 *      number, tying it back to the manifest's flat claim keys. That gate runs
 *      in `docs-freshness.yml` on every PR, INCLUDING runs where the frontend
 *      test suite does not execute, and its M-1 invariant means deleting the
 *      sentence fails the gate rather than silently satisfying it.
 *
 * ── THE FOUR GATED PERIODS ───────────────────────────────────────────────────
 *
 * These four are the rows the manifest gives a `claim_key`, i.e. the ones this
 * platform enforces itself and can be held to. They are named constants rather
 * than inline strings for one specific reason: `grep -P` is LINE-based, so a
 * rule anchored on a row's category words cannot reach a `period:` sitting four
 * lines below its `category:`. Naming the constant after the category puts the
 * anchor and the value on one line, which is what lets each claims rule be
 * anchored on THAT row rather than degenerating into "find any integer on this
 * page" — a pattern that would match something no matter what the sentence said.
 *
 * The remaining eight rows publish no number at all (they publish prose such as
 * "Kept indefinitely"), so there is nothing for a numeric rule to gate; the
 * field-by-field test above is what holds them.
 */
const PERIOD_ABANDONED_CHECKOUTS = "24 hours"
const PERIOD_WEBHOOK_DELIVERY_RECORDS = "30 days"
const PERIOD_QUARANTINED_IMAGE_UPLOADS = "72 hours"
const PERIOD_CUSTOMER_SIGN_IN_COOKIES = "30 days"

/**
 * Exported so the test can compare it to `docs/retention-manifest.json`. The
 * order matches the manifest's `.rows[]` exactly and is part of what is asserted.
 */
export const RETENTION_ROWS: readonly RetentionRow[] = [
  {
    id: "R-1",
    category: "Abandoned checkouts",
    detail:
      "An order you started but never paid for, including the name, email and phone number typed into the checkout form.",
    period: PERIOD_ABANDONED_CHECKOUTS,
    lawfulBasis:
      "Legitimate interests (UK GDPR Art. 6(1)(f)) - holding an in-progress basket long enough for you to come back and finish it, then deleting it.",
    enforcement: "Automated",
  },
  {
    id: "R-2",
    category: "Webhook delivery records",
    detail:
      "The log of each outbound webhook a vendor's integration was sent, including the delivery attempt history.",
    period: PERIOD_WEBHOOK_DELIVERY_RECORDS,
    lawfulBasis:
      "Legitimate interests (UK GDPR Art. 6(1)(f)) - letting a vendor debug a failed integration delivery, bounded so the log does not become an open-ended store.",
    enforcement: "Automated",
  },
  {
    id: "R-3",
    category: "Quarantined image uploads",
    detail:
      "The raw bytes of an image a vendor uploaded, held in quarantine while the pipeline validates and re-encodes it. Only the validated derivative is kept afterwards; the raw upload is reclaimed.",
    period: PERIOD_QUARANTINED_IMAGE_UPLOADS,
    lawfulBasis:
      "Legitimate interests (UK GDPR Art. 6(1)(f)) - a declared horizon that lets a vendor's upload survive a broker outage instead of being destroyed as a 15-minute accident, while still bounding how long unvalidated bytes sit on the origin.",
    enforcement: "Automated",
  },
  {
    id: "R-4",
    category: "Customer sign-in cookies",
    detail:
      "The jtoye-customer-refresh and jtoye-customer-id cookies that keep you signed in to a shop between visits.",
    period: PERIOD_CUSTOMER_SIGN_IN_COOKIES,
    lawfulBasis:
      "Strictly necessary (PECR reg. 6(4)) - these cookies exist only to deliver the sign-in you asked for; no consent is required and none is sought.",
    enforcement: "Automated",
  },
  {
    id: "R-5",
    category: "Customer access cookie",
    detail:
      "The short-lived jtoye-customer-access cookie carrying the access token for the current request.",
    period:
      "The short sign-in session length set by our identity provider, renewed automatically while you stay signed in",
    lawfulBasis:
      "Strictly necessary (PECR reg. 6(4)) - required to deliver the signed-in session you asked for.",
    enforcement: "Operational",
  },
  {
    id: "R-6",
    category: "Marketing opt-outs and opt-ins",
    detail:
      "The record that you unsubscribed from a category, or that you opted in to marketing.",
    period: "Kept indefinitely - deliberately never deleted",
    lawfulBasis:
      "Legal obligation and legitimate interests (UK GDPR Art. 6(1)(c) and 6(1)(f)) - an opt-out that expired would resurrect a suppressed recipient, so honouring it permanently is the obligation, not an option.",
    enforcement: "Automated",
  },
  {
    id: "R-7",
    category: "Your personal details on completed orders",
    detail:
      "Your name, email, phone and delivery address as they appear on orders, customer records and reviews.",
    period: "Removed on request - there is no automatic timer",
    lawfulBasis:
      "Right to erasure (UK GDPR Art. 17), applied by anonymising rather than deleting so the vendor's financial record stays intact.",
    enforcement: "Operational",
  },
  {
    id: "R-8",
    category: "Audit history",
    detail:
      "The append-only history of changes to records, used to answer who changed what and when.",
    period: "Kept indefinitely",
    lawfulBasis:
      "Legitimate interests (UK GDPR Art. 6(1)(f)) - integrity, dispute resolution and security investigation. Personal data inside this history is scrubbed when an erasure request is honoured (R-7).",
    enforcement: "Operational",
  },
  {
    id: "R-9",
    category: "Order and payment records",
    detail:
      "Completed orders and the financial transactions recorded against them.",
    period: "For as long as the law requires",
    lawfulBasis:
      "Legal obligation (UK GDPR Art. 6(1)(c)) - tax and accounting record-keeping.",
    enforcement: "Operational",
  },
  {
    id: "R-11",
    category: "Order-tracking email in your browser",
    detail:
      "The email address you type to look up an order, kept in your browser's session storage so the page does not ask again while you are on it.",
    period: "Cleared when you close the tab",
    lawfulBasis:
      "Strictly necessary (PECR reg. 6(4)) - it exists only to deliver the order lookup you asked for, and never reaches our servers as a stored record.",
    enforcement: "Automated",
  },
  {
    id: "R-12",
    category: "Saved checkout email in your browser",
    detail:
      "The email address you last used at a shop's checkout, kept in your browser's local storage so it can be filled in for you next time.",
    period: "Until you clear your browser's site data",
    lawfulBasis:
      "Strictly necessary (PECR reg. 6(4)) - it is a convenience within the checkout you asked for, stays on your device, and is never read by us as a stored record.",
    enforcement: "Operational",
  },
  {
    id: "R-13",
    category: "Guest order history in your browser",
    detail:
      "The list of orders you placed as a guest, kept in your browser's local storage so you can find them again without an account.",
    period: "Until you clear your browser's site data",
    lawfulBasis:
      "Strictly necessary (PECR reg. 6(4)) - it is what lets a guest find the order they just placed, and it stays on your device.",
    enforcement: "Operational",
  },
]

const SECTIONS = [
  "How to read this schedule",
  "The retention schedule",
  "Periods we do not publish as a number",
  "Asking us about retention or erasure",
  "Related policies",
] as const

export default function RetentionPage() {
  const contact = resolveControllerContact()

  return (
    <PolicyPage
      title="Data retention schedule"
      lastUpdated="16 August 2026"
      version="1.0"
      sections={SECTIONS}
      intro={
        "This page lists every category of data we hold, how long we keep it, why we are " +
        "allowed to keep it, and — the part most retention notices leave out — whether the " +
        "period is enforced by the system itself or by people following a process."
      }
    >
      <PolicySection heading={SECTIONS[0]}>
        <p>
          The last column is the one worth reading first. It says how each period
          is actually kept, and the two values mean different things:
        </p>
        <ul className="ml-5 list-disc space-y-2">
          <li>
            <span className="font-semibold text-slate-900">Automated</span> — a
            scheduled job, a database rule or your own browser enforces the
            period without anyone deciding to act. An abandoned checkout, for
            example, is deleted {PERIOD_ABANDONED_CHECKOUTS} after you start it,
            whether or not anybody looks.
          </li>
          <li>
            <span className="font-semibold text-slate-900">Operational</span> —
            the period is real but it is kept by people following a process, not
            by a timer. We mark these honestly rather than describing them as
            automatic, because claiming an automated period we do not enforce
            would be a false statement in a document you are entitled to rely on.
          </li>
        </ul>
        <p>
          Every <span className="font-semibold text-slate-900">Automated</span>{" "}
          period in the schedule below is checked against the code that actually
          enforces it, by a test that runs before any change ships. If the two
          ever disagree, the change is blocked rather than published.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[1]}>
        <RetentionTable rows={RETENTION_ROWS} />
      </PolicySection>

      <PolicySection heading={SECTIONS[2]}>
        <p>
          Two rows above give a description instead of a number, and that is
          deliberate in both cases.
        </p>
        <p>
          <span className="font-semibold text-slate-900">
            Order and payment records
          </span>{" "}
          are kept for as long as the law requires. We have not printed a figure
          in years here, because the retention period for tax and accounting
          records is a legal position rather than something we measured, and
          publishing an unverified number in a document a regulator may rely on
          would be worse than publishing none.
        </p>
        <p>
          <span className="font-semibold text-slate-900">
            The customer access cookie
          </span>{" "}
          expires on a schedule set by our identity provider, not by us. Its
          lifetime is short and it is renewed while you stay signed in. We
          describe it rather than printing a number because the number is not
          ours to publish, and it would become false the moment that setting
          changed.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[3]}>
        <p>
          You can ask us what we hold about you, ask us to correct it, or ask us
          to erase it. Erasure of your personal details on completed orders is
          the{" "}
          <span className="font-semibold text-slate-900">Operational</span> row
          above: we anonymise the record rather than deleting it outright, so the
          vendor&apos;s financial and tax records stay intact while your personal
          details are removed from them.
        </p>
        {contact.anyRoute ? (
          <>
            <p>Reach us at:</p>
            <ul className="ml-5 list-disc space-y-2">
              {contact.email ? (
                <li>
                  <a
                    className="font-semibold text-amber-700 underline underline-offset-2 hover:text-amber-800"
                    href={contact.emailHref as string}
                  >
                    {contact.email}
                  </a>
                </li>
              ) : null}
              {contact.postal ? <li>{contact.postal}</li> : null}
            </ul>
          </>
        ) : (
          <p>
            Our published contact routes for data-protection requests are listed
            on the{" "}
            <Link
              className="font-semibold text-amber-700 underline underline-offset-2 hover:text-amber-800"
              href="/legal"
            >
              legal information page
            </Link>
            .
          </p>
        )}
      </PolicySection>

      <PolicySection heading={SECTIONS[4]}>
        <ul className="ml-5 list-disc space-y-2">
          <li>
            <Link
              className="font-semibold text-amber-700 underline underline-offset-2 hover:text-amber-800"
              href="/legal/privacy"
            >
              Privacy notice
            </Link>{" "}
            — what we collect, who is responsible for it and your rights.
          </li>
          <li>
            <Link
              className="font-semibold text-amber-700 underline underline-offset-2 hover:text-amber-800"
              href="/legal/cookies"
            >
              Cookie and browser-storage policy
            </Link>{" "}
            — every cookie and item of browser storage, and what each one does.
          </li>
          <li>
            <Link
              className="font-semibold text-amber-700 underline underline-offset-2 hover:text-amber-800"
              href="/legal/accessibility"
            >
              Accessibility statement
            </Link>{" "}
            — our conformance status and how to report a problem.
          </li>
        </ul>
      </PolicySection>
    </PolicyPage>
  )
}

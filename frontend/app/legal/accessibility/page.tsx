import type { Metadata } from "next"
import Link from "next/link"
import { getCompanyInfo, resolveControllerContact } from "@/lib/company"
import { PolicyPage, PolicySection } from "@/components/legal/policy-page"
import {
  ACCESSIBILITY_STATEMENT as STATEMENT,
  ACCESSIBILITY_STATEMENT_VERSION,
  formatStatementDate,
} from "@/lib/accessibility-statement"

export const metadata: Metadata = {
  title: "Accessibility statement — J'Toye",
  description:
    "J'Toye's WCAG 2.1 AA conformance status, known exceptions and how to report an accessibility problem.",
  alternates: { canonical: "/legal/accessibility" },
}

/**
 * The published conformance statement (LGL-02, D-12).
 *
 * THE CLAIM IS PARTIAL, AND THE PAGE CANNOT SAY OTHERWISE. The claim comes from
 * `lib/accessibility-statement.ts`, where it is a literal type with one
 * permitted value. The audit this page rests on recorded accessibility here as
 * essentially absent; a page overclaiming on top of that history would be the
 * worst outcome available, and overclaiming is itself Equality Act exposure.
 *
 * NO DATE IS WRITTEN INTO THIS FILE — NOT EVEN IN A COMMENT. Every date is
 * rendered from the declared constant, which a build gate also reads. Two
 * copies of a date drift, and when they do the gate certifies one value while
 * the reader is shown another. A test asserts that no year literal appears in
 * this source, which is why the prose here describes dates without giving one.
 *
 * THE EXCEPTION LIST IS RENDERED, NOT RETYPED. Every entry comes from the same
 * array the gate counts, and a test asserts the rendered count equals the
 * constant's — so an exception cannot be quietly dropped from the page while
 * remaining in the data, which is the failure that would make this page a lie
 * while every other check stayed green.
 *
 * THE CONTACT IS GUARDED, HEADING AND ALL. `resolveControllerContact().anyRoute`
 * gates the block per the shell's usage contract: an unconfigured deployment
 * must not ship a heading with nothing after it, and must never construct a
 * link from an empty address. The API endpoints behind data-subject requests
 * are deliberately not published here — they are endpoints, not pages, and
 * sending a person to one helps nobody.
 *
 * NOTHING HERE REOPENS THE PALETTE. The brand's primary colour is settled and
 * its contrast is asserted by a separate token test. A conformance statement is
 * the most likely place to accidentally imply that decision is under review, so
 * it is not discussed on this page at all.
 */

const SECTIONS = [
  "Conformance status",
  "Scope of this statement",
  "Known exceptions",
  "Dates and review",
  "Feedback and contact",
  "Enforcement procedure",
] as const

const CATEGORY_LABEL: Record<string, string> = {
  "out-of-scope": "Not covered by this statement",
  "third-party": "Provided by someone else",
  "known-defect": "Known problem, not yet fixed",
  "published-information": "Information not currently published",
}

export default function AccessibilityStatementPage() {
  const company = getCompanyInfo()
  const contact = resolveControllerContact(company)

  const prepared = formatStatementDate(STATEMENT.preparedOn)
  const reviewed = formatStatementDate(STATEMENT.lastReviewedOn)
  const due = formatStatementDate(STATEMENT.nextReviewDue)

  return (
    <PolicyPage
      title="Accessibility statement"
      lastUpdated={reviewed}
      version={ACCESSIBILITY_STATEMENT_VERSION}
      sections={SECTIONS}
      intro={
        <>
          {company.legalName} (company no. {company.companyNumber}) is committed
          to making this platform usable by as many people as possible. This
          statement covers the public J&apos;Toye storefront and its sign-in
          pages. It does not cover the vendor dashboard, which is not yet
          assessed.
        </>
      }
    >
      <PolicySection heading={SECTIONS[0]}>
        <p className="font-semibold text-oxblood">
          This website is partially conformant with {STATEMENT.standard} level{" "}
          {STATEMENT.level}.
        </p>
        <p>
          &quot;Partially conformant&quot; means some parts of the site do not
          fully conform to the standard. The known exceptions are listed below,
          each with the date we expect to fix it.
        </p>
        <p>
          Prepared on <strong>{prepared}</strong>. Last reviewed{" "}
          <strong>{reviewed}</strong>. Next review due <strong>{due}</strong>.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[1]}>
        <p>
          This statement applies to the following pages, which anyone can reach
          without an account:
        </p>
        <ul className="list-disc space-y-2 pl-6">
          {STATEMENT.inScopeRoutes.map((route, i) => (
            <li key={`${route.path}-${i}`}>
              <code className="rounded bg-cream-100 px-1.5 py-0.5 text-sm text-oxblood">
                {route.path}
              </code>{" "}
              — {route.label}
            </li>
          ))}
        </ul>
        <p className="pt-2 font-semibold text-oxblood">What is not covered</p>
        <ul className="list-disc space-y-2 pl-6">
          {STATEMENT.excludedSurfaces.map((surface) => (
            <li key={surface.name}>
              <strong>{surface.name}.</strong> {surface.reason}
            </li>
          ))}
        </ul>
      </PolicySection>

      <PolicySection heading={SECTIONS[2]}>
        <p>
          These are the accessibility problems we know about. Each one says what
          you would experience, why it is still outstanding, and the date by
          which we expect it to be addressed. Some sit inside pages this
          statement does cover but are built by another company, so they are
          listed here rather than left to look like part of our own claim.
        </p>
        <ul className="list-none space-y-8 pl-0">
          {STATEMENT.exceptions.map((exception) => (
            <li
              key={exception.id}
              data-exception-id={exception.id}
              id={exception.id}
              className="scroll-mt-20 border-l-4 border-amber-700 pl-4"
            >
              <h3 className="text-base font-semibold text-oxblood">
                {exception.title}
              </h3>
              <p className="mt-1 text-sm font-semibold text-amber-800">
                {CATEGORY_LABEL[exception.category] ?? exception.category}
              </p>
              <p className="mt-2">{exception.description}</p>
              <p className="mt-2 text-slate-700">{exception.reason}</p>
              {exception.routes.length > 0 ? (
                <p className="mt-2 text-sm text-slate-700">
                  Affects:{" "}
                  {exception.routes.map((r, i) => (
                    <span key={r}>
                      {i > 0 ? ", " : null}
                      <code className="rounded bg-cream-100 px-1.5 py-0.5 text-oxblood">
                        {r}
                      </code>
                    </span>
                  ))}
                </p>
              ) : null}
              <p className="mt-2 text-sm font-semibold text-slate-700">
                We expect to address this by{" "}
                {formatStatementDate(exception.remediationBy)}.
              </p>
            </li>
          ))}
        </ul>
      </PolicySection>

      <PolicySection heading={SECTIONS[3]}>
        <p>
          This statement was prepared on <strong>{prepared}</strong>. That is
          the date the accessibility testing behind it was carried out against
          the running site, not the date this page was written — a statement is
          only as current as the measurement underneath it.
        </p>
        <p>
          It was last reviewed on <strong>{reviewed}</strong>, when every
          problem listed above was checked again against the live code rather
          than copied forward.
        </p>
        <p>
          The next review is due by <strong>{due}</strong>. If that date passes
          without this statement being updated, our build fails — so a stale
          statement stops being something a reader has to notice.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[4]}>
        <p>
          If you find an accessibility problem that is not listed here, or you
          need something on this site in a different format, please tell us. We
          want to know — the list above only contains what we have found
          ourselves.
        </p>
        {contact.anyRoute ? (
          <>
            <p className="pt-2 font-semibold text-oxblood">How to reach us</p>
            <ul className="list-disc space-y-2 pl-6">
              {contact.email && contact.emailHref ? (
                <li>
                  Email{" "}
                  <a
                    href={contact.emailHref}
                    className="font-semibold text-amber-700 underline hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                  >
                    {contact.email}
                  </a>
                  . This address is monitored, and we aim to reply within one
                  working week.
                </li>
              ) : null}
              {contact.postal ? (
                <li>Write to us at {contact.postal}.</li>
              ) : null}
            </ul>
          </>
        ) : (
          <p>
            We have not yet published a dedicated accessibility contact address.
            Until we do, please use the contact routes listed in our{" "}
            <Link
              href="/legal/privacy"
              className="font-semibold text-amber-700 underline hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            >
              privacy notice
            </Link>
            , or the company details on our{" "}
            <Link
              href="/legal"
              className="font-semibold text-amber-700 underline hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            >
              legal information page
            </Link>
            .
          </p>
        )}
        <p>
          Please tell us which page you were on and what happened. If you were
          using a screen reader, magnifier or voice control, telling us which
          one helps us reproduce the problem.
        </p>
      </PolicySection>

      <PolicySection heading={SECTIONS[5]}>
        <p>
          The Equality and Human Rights Commission (EHRC) is responsible for
          enforcing the accessibility requirements that apply in the UK.
        </p>
        <p>
          If you contact us about an accessibility problem and you are not happy
          with how we respond, you can contact the{" "}
          <strong>Equality Advisory and Support Service (EASS)</strong>, which
          gives free advice to people who think they have been treated unfairly.
          You can find them at{" "}
          <a
            href="https://www.equalityadvisoryservice.com/"
            className="font-semibold text-amber-700 underline hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            equalityadvisoryservice.com
          </a>
          , and the EHRC at{" "}
          <a
            href="https://www.equalityhumanrights.com/"
            className="font-semibold text-amber-700 underline hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            equalityhumanrights.com
          </a>
          .
        </p>
        <p>
          Raising a problem with us first is usually faster, but you do not have
          to, and nothing on this page limits your rights under the Equality Act
          2010.
        </p>
      </PolicySection>
    </PolicyPage>
  )
}

import type { Metadata } from "next"
import Link from "next/link"
import { getCompanyInfo, resolveControllerContact } from "@/lib/company"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "Legal & company information — J'Toye",
  description:
    "Company registration and legal information for J'Toye Digital Ltd, the operator of the J'Toye platform.",
  alternates: { canonical: "/legal" },
}

/**
 * Public platform legal page — the operator's Companies House trading
 * disclosure, and from phase 31 the INDEX for the policy documents nested
 * beneath it. Platform-owned (J'Toye Digital Ltd), NOT a vendor storefront.
 *
 * Wrapped in PublicShell: it used to render a bare <main> with no header or
 * footer, so anyone landing here from the dashboard legal line had one text
 * link out and no brand chrome at all.
 *
 * WHAT WAS PRESERVED, AND WHY IT IS CALLED OUT. The trading disclosure below is
 * the whole reason this page existed before phase 31, and turning a page into an
 * index is the classic way to lose the thing it used to say. The identity list,
 * its getCompanyInfo() sourcing, its metadata and its canonical are unchanged.
 * Body text moved from 14px to 16px, which the UI-SPEC permits explicitly and
 * caps in one direction: it may rise, it must not shrink.
 *
 * TWO DIFFERENT LINES, BOTH TRUE. This page draws the TRADING line — vendors are
 * their own businesses and own their own trading disclosures. The privacy notice
 * draws the GDPR line, where J'Toye and the vendor are joint controllers for
 * consumer order data. They are different relationships and the prose on each
 * page must not be edited into contradicting the other.
 *
 * The four documents linked below are built in later plans of this phase, so
 * some of these routes 404 until those land. That is expected here; end-to-end
 * reachability is asserted once, in 31-17, rather than four times.
 */

const POLICY_DOCUMENTS = [
  {
    href: "/legal/privacy",
    title: "Privacy notice",
    description:
      "How J'Toye and the vendors on it use your personal data, who is responsible for what, and how to exercise your rights.",
  },
  {
    href: "/legal/cookies",
    title: "Cookie and browser-storage policy",
    description:
      "Every cookie and item of browser storage J'Toye uses, what it does, and how long it lasts.",
  },
  {
    href: "/legal/retention",
    title: "Data retention schedule",
    description:
      "How long J'Toye keeps each category of data, the lawful basis, and whether the period is enforced automatically.",
  },
  {
    href: "/legal/accessibility",
    title: "Accessibility statement",
    description:
      "J'Toye's WCAG 2.1 AA conformance status, known exceptions and how to report an accessibility problem.",
  },
] as const

export default function LegalPage() {
  const c = getCompanyInfo()
  const contact = resolveControllerContact(c)
  return (
    <PublicShell>
      <div className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
        <h1 className="text-[28px] font-semibold leading-tight tracking-tight text-oxblood">
          Legal &amp; company information
        </h1>
        <p className="mt-4 text-base leading-relaxed text-slate-600">
          The J&apos;Toye platform is operated by the company below. Individual
          vendor shops listed on the platform are run by their own businesses and
          remain responsible for their own trading disclosures.
        </p>

        <dl className="mt-8 space-y-4 text-base leading-relaxed">
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
          {/*
            Guarded on the resolver rather than on the raw field, so this page
            and the policy pages omit an unconfigured contact the same way. A
            term rendered above an empty value is simultaneously a broken page
            and a UK GDPR Art. 13 failure, and it gets triaged as the first.
          */}
          {contact.postal ? (
            <div>
              <dt className="font-semibold text-oxblood">Registered office</dt>
              <dd className="text-slate-600">{contact.postal}</dd>
            </div>
          ) : null}
          {contact.emailHref ? (
            <div>
              <dt className="font-semibold text-oxblood">
                Data protection contact
              </dt>
              <dd>
                <a
                  href={contact.emailHref}
                  className="font-semibold text-amber-700 hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                >
                  {contact.email}
                </a>
              </dd>
            </div>
          ) : null}
        </dl>

        <h2 className="mt-14 text-xl font-semibold tracking-tight text-oxblood">
          Policies and statements
        </h2>
        <ul className="mt-6 space-y-6">
          {POLICY_DOCUMENTS.map((doc) => (
            <li key={doc.href}>
              <Link
                href={doc.href}
                className="inline-flex min-h-11 items-center font-semibold text-amber-700 hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                {doc.title}
              </Link>
              <p className="text-base leading-relaxed text-slate-600">
                {doc.description}
              </p>
            </li>
          ))}
        </ul>

        <p className="mt-14 text-base">
          <Link
            href="/"
            className="inline-flex min-h-11 items-center font-semibold text-amber-700 hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            ← Back to J&apos;Toye
          </Link>
        </p>
      </div>
    </PublicShell>
  )
}

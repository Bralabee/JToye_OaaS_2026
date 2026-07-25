import type { Metadata } from "next"
import Link from "next/link"
import { getCompanyInfo } from "@/lib/company"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "Legal & company information — J'Toye",
  description:
    "Company registration and legal information for J'Toye Digital Ltd, the operator of the J'Toye platform.",
  alternates: { canonical: "/legal" },
}

/**
 * Public platform legal page — the operator's Companies House trading
 * disclosure. Platform-owned (J'Toye Digital Ltd), NOT a vendor storefront.
 *
 * Wrapped in PublicShell: it used to render a bare <main> with no header or
 * footer, so anyone landing here from the dashboard legal line had one text
 * link out and no brand chrome at all.
 */
export default function LegalPage() {
  const c = getCompanyInfo()
  return (
    <PublicShell>
      <div className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
      <h1 className="text-2xl font-bold tracking-tight text-oxblood">
        Legal &amp; company information
      </h1>
      <p className="mt-4 text-sm text-slate-600">
        The J&apos;Toye platform is operated by the company below. Individual
        vendor shops listed on the platform are run by their own businesses and
        remain responsible for their own trading disclosures.
      </p>

      <dl className="mt-8 space-y-4 text-sm">
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
        {c.registeredOffice ? (
          <div>
            <dt className="font-semibold text-oxblood">Registered office</dt>
            <dd className="text-slate-600">{c.registeredOffice}</dd>
          </div>
        ) : null}
      </dl>

      <p className="mt-10 text-sm">
        <Link href="/" className="font-medium text-amber-600 hover:text-amber-700">
          ← Back to J&apos;Toye
        </Link>
      </p>
      </div>
    </PublicShell>
  )
}

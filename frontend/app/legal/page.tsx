import type { Metadata } from "next"
import Link from "next/link"
import { getCompanyInfo } from "@/lib/company"

export const metadata: Metadata = {
  title: "Legal & company information — J'Toye",
  description:
    "Company registration and legal information for J'Toye Digital Ltd, the operator of the J'Toye platform.",
  alternates: { canonical: "/legal" },
}

/**
 * Public platform legal page — the operator's Companies House trading
 * disclosure. Platform-owned (J'Toye Digital Ltd), NOT a vendor storefront.
 */
export default function LegalPage() {
  const c = getCompanyInfo()
  return (
    <main className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
      <h1 className="text-2xl font-bold tracking-tight text-slate-900">
        Legal &amp; company information
      </h1>
      <p className="mt-4 text-sm text-slate-600">
        The J&apos;Toye platform is operated by the company below. Individual
        vendor shops listed on the platform are run by their own businesses and
        remain responsible for their own trading disclosures.
      </p>

      <dl className="mt-8 space-y-4 text-sm">
        <div>
          <dt className="font-semibold text-slate-900">Registered company name</dt>
          <dd className="text-slate-600">{c.legalName}</dd>
        </div>
        <div>
          <dt className="font-semibold text-slate-900">Company number</dt>
          <dd className="text-slate-600">{c.companyNumber}</dd>
        </div>
        <div>
          <dt className="font-semibold text-slate-900">Place of registration</dt>
          <dd className="text-slate-600">{c.registrationJurisdiction}</dd>
        </div>
        {c.registeredOffice ? (
          <div>
            <dt className="font-semibold text-slate-900">Registered office</dt>
            <dd className="text-slate-600">{c.registeredOffice}</dd>
          </div>
        ) : null}
      </dl>

      <p className="mt-10 text-sm">
        <Link href="/" className="font-medium text-orange-600 hover:text-orange-700">
          ← Back to J&apos;Toye
        </Link>
      </p>
    </main>
  )
}

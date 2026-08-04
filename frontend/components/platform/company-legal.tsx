import Link from "next/link"
import { getCompanyInfo } from "@/lib/company"

/**
 * Platform operator legal disclosure line (UK Companies Act trading
 * disclosure). Renders ONLY on platform-owned surfaces (dashboard, sign-in,
 * /legal) — see lib/company.ts for why it must never appear on tenant
 * storefronts. Plain presentational component (no hooks) so it composes in both
 * server and client trees.
 */
export function CompanyLegalLine({ className = "" }: { className?: string }) {
  const c = getCompanyInfo()
  if (!c.legalName || !c.companyNumber) return null
  return (
    <p className={`text-xs text-slate-600 dark:text-slate-400 ${className}`.trim()}>
      {c.legalName} is a company registered in {c.registrationJurisdiction}{" "}
      (company no. {c.companyNumber})
      {c.registeredOffice ? `. Registered office: ${c.registeredOffice}` : ""}.{" "}
      <Link
        href="/legal"
        className="underline underline-offset-2 hover:text-slate-700 dark:hover:text-slate-300"
      >
        Legal &amp; company information
      </Link>
    </p>
  )
}

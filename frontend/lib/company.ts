/**
 * Platform operator legal identity — J'Toye Digital Ltd.
 *
 * Verified against the UK Companies House public register (2026-07-15):
 * company no. 16471464, ACTIVE, incorporated 23 May 2025, registered in
 * England & Wales. (A dissolved namesake, 13434105, exists — disambiguate by
 * number.)
 *
 * IMPORTANT — this describes the PLATFORM OPERATOR (J'Toye), NOT individual
 * vendors. Each tenant storefront (/shop/[slug]) belongs to that vendor's own
 * legal entity, so this identity must only ever render on platform-owned
 * surfaces (dashboard, sign-in, /legal) — never on tenant storefronts.
 *
 * The values are a fixed business fact that does NOT vary by environment, so
 * the defaults live in code and the disclosure is never blank. The
 * NEXT_PUBLIC_* overrides exist for white-label deployments operating under a
 * different entity; because they are inlined into the browser bundle they must
 * be provided as BUILD args (compose/Dockerfile), mirroring the ONB-1
 * support-channel pattern.
 */
export interface CompanyInfo {
  /** Registered company name, e.g. "J'Toye Digital Ltd". */
  legalName: string
  /** Companies House registration number, e.g. "16471464". */
  companyNumber: string
  /** Place of registration, e.g. "England & Wales". */
  registrationJurisdiction: string
  /** Registered office address; empty string when not disclosed on-site. */
  registeredOffice: string
}

const DEFAULT_LEGAL_NAME = "J'Toye Digital Ltd"
const DEFAULT_COMPANY_NUMBER = "16471464"
const DEFAULT_REGISTRATION = "England & Wales"

export function getCompanyInfo(): CompanyInfo {
  return {
    legalName: process.env.NEXT_PUBLIC_COMPANY_LEGAL_NAME || DEFAULT_LEGAL_NAME,
    companyNumber: process.env.NEXT_PUBLIC_COMPANY_NUMBER || DEFAULT_COMPANY_NUMBER,
    registrationJurisdiction:
      process.env.NEXT_PUBLIC_COMPANY_REGISTRATION || DEFAULT_REGISTRATION,
    registeredOffice: process.env.NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE || "",
  }
}

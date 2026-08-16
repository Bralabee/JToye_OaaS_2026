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
  /**
   * Monitored address for UK-GDPR data-subject requests (Art. 13(1)(b)); empty
   * string when not disclosed on-site. Deliberately separate from the ONB-1
   * support channel: a general support inbox is not a data-protection contact
   * unless someone has said it is, and conflating the two publishes a promise
   * about who reads a rights request.
   */
  dataProtectionEmail: string
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
    dataProtectionEmail: process.env.NEXT_PUBLIC_DATA_PROTECTION_EMAIL || "",
  }
}

/**
 * The controller's published contact routes, resolved once so every legal page
 * degrades the same way.
 *
 * WHY A RESOLVER RATHER THAN A CONDITIONAL PER PAGE. UK GDPR Art. 13(1)(a)-(b)
 * requires the controller's identity AND contact details in a privacy notice.
 * Four pages each writing their own `{x ? <dt>…</dt> : null}` is four chances to
 * emit a term with nothing after it — and a heading followed by an empty value
 * is simultaneously a broken page and an Art. 13 failure, which is the worst
 * pairing available: it looks like a rendering bug, so it gets triaged as one.
 *
 * The rule is therefore stated once, here: a configured value becomes a route,
 * an unconfigured one becomes `null`, and the caller renders only the routes
 * that came back non-null. `anyRoute` exists so a caller can drop the whole
 * block — including its own heading — rather than render an empty container.
 *
 * This mirrors `resolveSupportChannel` in `lib/env-validation.ts` deliberately:
 * same optional-with-fallback classification, same reason (absence is a soft
 * misconfiguration worth an operator signal, not a boot failure).
 *
 * NOT RESOLVED HERE: the DSAR intake built in phase 31 is an API endpoint
 * (`POST /api/v1/public/gdpr/dsar`), not a page a person can be sent to. It is
 * deliberately absent from this type — publishing an unlinkable API path to a
 * consumer as a "contact route" would satisfy the check while helping nobody.
 */
export interface ControllerContact {
  /** Postal address for the controller, or null when unconfigured. */
  postal: string | null
  /** Data-protection address for display, or null when unconfigured. */
  email: string | null
  /** `mailto:` href for the address above, or null when unconfigured. */
  emailHref: string | null
  /** False when NOTHING is configured — the caller omits the block entirely. */
  anyRoute: boolean
}

export function resolveControllerContact(
  info: CompanyInfo = getCompanyInfo()
): ControllerContact {
  const postal = info.registeredOffice.trim() || null
  const email = info.dataProtectionEmail.trim() || null
  return {
    postal,
    email,
    emailHref: email ? `mailto:${email}` : null,
    anyRoute: Boolean(postal || email),
  }
}

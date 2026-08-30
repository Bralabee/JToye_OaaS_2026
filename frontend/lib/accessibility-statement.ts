/**
 * The accessibility conformance statement, as data (LGL-02, D-12).
 *
 * WHY A CONSTANT AND NOT PROSE ON THE PAGE. Two things need to read this: the
 * page a person visits, and a build gate that reds when the statement goes
 * stale. A gate cannot read a sentence. If the dates were written into the TSX
 * the gate would have to be given its own copy, and two copies of a date drift —
 * at which point the gate certifies one value while the reader is shown another,
 * which is worse than having no gate, because it looks discharged.
 *
 * THE CLAIM IS PARTIAL, AND THAT IS NOT A DRAFTING CHOICE. WCAG explicitly
 * supports a partial-conformance claim. Overclaiming accessibility is itself
 * Equality Act exposure, and the audit behind this statement recorded
 * accessibility on this platform as essentially absent — a page asserting full
 * conformance on top of that history would be the worst outcome available.
 * `claim` is therefore a literal type with one permitted value, so upgrading it
 * is a type error rather than an edit.
 *
 * EVERY EXCEPTION CARRIES A DATE. "In due course" is what D-12 exists to
 * forbid: an exception list with no dates stops being a commitment and becomes
 * decoration. `remediationBy` is required by the type, and the gate beside this
 * file asserts that none has passed — so the list cannot rot quietly.
 *
 * WHAT IS DELIBERATELY NOT LISTED HERE. Publishing a finding as outstanding
 * when it has been fixed is exactly as inaccurate as omitting one that has not.
 * Every candidate was re-measured against this tree before being included or
 * dropped; the ones that were dropped, and the evidence for dropping them, are
 * recorded in this plan's summary rather than being silently absent.
 */

/** The single permitted conformance claim. Widening this is a type error. */
export type ConformanceClaim = "partial"

/** Why an exception is outstanding — the reader needs the category, not a finding id. */
export type ExceptionCategory =
  /** Excluded from the claim by decision; not assessed, not claimed. */
  | "out-of-scope"
  /** Rendered by a third party whose markup we do not author. */
  | "third-party"
  /** A defect we can see, on a surface we do claim, that this work did not close. */
  | "known-defect"
  /** Information a published notice should carry that is not currently published. */
  | "published-information"

export interface AccessibilityException {
  /** Stable slug — used as the anchor for a deep link into the published list. */
  id: string
  /** Short heading for the entry. */
  title: string
  /** What a person actually experiences, in words a non-engineer can read. */
  description: string
  /** Why it is still outstanding. */
  reason: string
  category: ExceptionCategory
  /** The routes affected, as URLs. Empty when the entry is not route-specific. */
  routes: readonly string[]
  /** ISO date by which we expect this to be addressed. Never optional. */
  remediationBy: string
  /** Set true only when the entry is retained for the record after being fixed. */
  resolved?: boolean
}

export interface ScopedRoute {
  /** The URL as a reader would type it. */
  path: string
  /** What the reader will find there. */
  label: string
}

export interface ExcludedSurface {
  name: string
  reason: string
}

export interface AccessibilityStatement {
  standard: string
  level: string
  claim: ConformanceClaim
  /** ISO date the audit EVIDENCE was captured — not the date this file was written. */
  preparedOn: string
  /** ISO date the statement was last checked against the tree. */
  lastReviewedOn: string
  /** ISO date the statement expires. A past value reds the build. */
  nextReviewDue: string
  inScopeRoutes: readonly ScopedRoute[]
  excludedSurfaces: readonly ExcludedSurface[]
  exceptions: readonly AccessibilityException[]
}

/**
 * `preparedOn` is the date the axe evidence behind this statement was captured
 * against the running stack, NOT the date this file was authored. If a later
 * re-measurement moves the numbers, that plan updates this value — which is the
 * entire reason it lives in one declared place instead of in a sentence.
 *
 * `nextReviewDue` is six months rather than the twelve the standard permits.
 * The measurement underneath this statement carries a short declared validity
 * window of its own, and a twelve-month horizon on top of a fast-moving tree
 * would let the page drift a long way from what ships before anything noticed.
 * Six months is inside the permitted bound in the safe direction.
 */
export const ACCESSIBILITY_STATEMENT: AccessibilityStatement = {
  standard: "WCAG 2.1",
  level: "AA",
  claim: "partial",

  // Moved 2026-08-15 -> 2026-08-16 by plan 31-18: this is the date the FINAL
  // audit's evidence was captured, against the built tree, by the axe gate in
  // `e2e/public-a11y.spec.ts` running over all thirteen declared surfaces on
  // both viewports. Not the date the file was edited — the distinction is the
  // whole reason this field is declared rather than written into a sentence.
  //
  // WHAT THAT AUDIT CHANGED HERE: nothing but this date. Every one of the seven
  // exceptions below was re-verified against the tree and every one still holds
  // (see the plan's summary for the per-entry evidence). One violation WAS found
  // — amber-700 at 4.41:1 on the policy pages' mobile TOC panel — and it was
  // fixed rather than published, so it never became an eighth entry.
  preparedOn: "2026-08-16",
  lastReviewedOn: "2026-08-16",
  nextReviewDue: "2027-02-16",

  inScopeRoutes: [
    { path: "/", label: "The J'Toye home page" },
    { path: "/shop", label: "The list of vendors" },
    { path: "/shop/[slug]", label: "An individual vendor's shop page" },
    {
      path: "/shop/[slug]",
      label: "The dish detail panel that opens on a vendor's shop page",
    },
    { path: "/shop/[slug]/checkout", label: "Checkout" },
    { path: "/shop/signin", label: "Customer sign-in" },
    { path: "/auth/signin", label: "Vendor sign-in" },
    { path: "/legal", label: "Legal and company information" },
    { path: "/legal/accessibility", label: "This statement" },
  ],

  excludedSurfaces: [
    {
      name: "The vendor dashboard, and everything behind a vendor sign-in",
      reason:
        "The dashboard is the tool vendors use to run their shop. It has not been comprehensively assessed against WCAG 2.1 level AA, so no conformance claim is made about it — but it is no longer unmonitored: key dashboard pages are scanned automatically with axe on every pull request (a blocking check), and every dashboard route is scanned nightly in a report-only pass that surfaces new problems without gating a release. It is named here rather than left unmentioned, because a scope that quietly stops at the sign-in page reads as a claim about everything.",
    },
  ],

  exceptions: [
    // --- Outside the claim by decision -------------------------------------
    {
      id: "vendor-dashboard-not-assessed",
      title: "The vendor dashboard has not been comprehensively assessed",
      description:
        "Everything behind a vendor sign-in — the dashboard, the kitchen display and the vendor settings pages — is now scanned automatically for accessibility problems: an axe scan of key dashboard pages runs on every pull request and blocks it on a violation, and every dashboard route is scanned again nightly in a report-only pass. That is real, ongoing coverage, but it is not the same as a person comprehensively testing the standard against every page, so we do not yet make a conformance claim about it.",
      reason:
        "This round of work deliberately covered the pages a member of the public can reach without an account, because that is where an inaccessible page stops somebody buying food. Automated dashboard scanning closes part of that gap; a full assessment is next, not forgotten.",
      category: "out-of-scope",
      routes: ["/dashboard"],
      remediationBy: "2027-02-16",
    },

    // --- Outside our control ------------------------------------------------
    {
      id: "identity-provider-registration",
      title: "Creating an account happens on our identity provider's site",
      description:
        "Choosing \"Create an account\" from the customer sign-in page sends you to our identity provider, which is a different website on a different address. The pages you see there are built and controlled by that provider, not by us, so we cannot fix their markup and do not claim conformance for them.",
      reason:
        "Sign-in and registration are handled by a dedicated identity system so that we never handle your password. The trade-off is that those particular screens are outside what we author.",
      category: "third-party",
      routes: ["/shop/signin"],
      remediationBy: "2027-02-16",
    },
    {
      id: "stripe-hosted-payment-form",
      title: "The card payment form is supplied by our payment provider",
      description:
        "The fields where you type your card details sit inside our checkout page, but they are rendered by our payment provider rather than by us. We cannot change how those particular fields are labelled or announced.",
      reason:
        "Card details are deliberately never handled by J'Toye's own code, which is what keeps them out of our systems entirely. The part of the checkout page around the payment fields is ours and is covered by this statement.",
      category: "third-party",
      routes: ["/shop/[slug]/checkout"],
      remediationBy: "2027-02-16",
    },

    // --- Known, on surfaces we DO claim, and not closed by this work --------
    {
      id: "storefront-no-skip-link",
      title: "No \"skip to content\" link on the vendor and checkout pages",
      description:
        "On the shop, vendor, checkout and customer sign-in pages there is no shortcut that jumps past the header straight to the main content. Someone navigating by keyboard has to tab through the header links on every page before reaching what they came for.",
      reason:
        "The shortcut was added to the shared public pages but not to the separate shell the shop pages use. The two use different layouts, and only one was changed.",
      category: "known-defect",
      routes: ["/shop", "/shop/[slug]", "/shop/[slug]/checkout", "/shop/signin"],
      remediationBy: "2026-11-16",
    },
    {
      id: "required-fields-marked-visually-only",
      title: "Some checkout fields are marked required only by a visible asterisk",
      description:
        "On the delivery address part of checkout, the address, town and postcode labels end in an asterisk to show they are required, but that requirement is not carried in the page's code. A screen reader will not announce those three fields as required, so the asterisk is meaningless to anyone who cannot see it.",
      reason:
        "The name, email and phone fields do carry the requirement correctly; the three delivery address fields were missed when delivery was added.",
      category: "known-defect",
      routes: ["/shop/[slug]/checkout"],
      remediationBy: "2026-11-16",
    },
    {
      id: "text-contrast-below-minimum",
      title: "Some text does not have enough contrast against its background",
      description:
        "A number of smaller pieces of text — prices, secondary notes, muted helper lines and some error text — are lighter than the standard's minimum contrast against the page behind them. They are readable for most people but harder to read in bright light or with low vision.",
      reason:
        "These are enumerated with their measured contrast ratios in the codebase and are checked automatically so the set cannot grow, but the existing entries have not yet been corrected. Changing them touches the visual design of several pages and is being done deliberately rather than in a rush.",
      category: "known-defect",
      routes: ["/", "/shop/[slug]/checkout", "/auth/signin"],
      remediationBy: "2027-02-16",
    },

    // --- Published information ---------------------------------------------
    // The wording below is consumed VERBATIM from the decision that produced it
    // and is deliberately not re-derived here. It is a published-information
    // gap, not a WCAG failure, and is categorised as such so this page does not
    // imply the standard says something it does not.
    {
      id: "registered-office-not-published",
      title: "Registered office address not published",
      description:
        "J'Toye Digital Ltd (company number 16471464, registered in England & Wales) does not currently publish its registered office address on this site. UK GDPR Article 13(1)(a)-(b) requires the controller's identity and contact details in a privacy notice; the identity and an electronic contact route are published, the postal address is not. Data-protection enquiries and data-subject requests should be sent to the contact address given in the privacy notice, which is monitored. The registered office remains publicly available from the Companies House register against company number 16471464.",
      reason:
        "Status: open. Owner decision recorded during phase 31. Remediation: publish the address, or a service address, at the next review of this statement.",
      category: "published-information",
      routes: [],
      remediationBy: "2027-02-16",
    },
  ],
} as const

const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
] as const

/**
 * Render an ISO date as a UK long-form date for display.
 *
 * Parsed by hand rather than through `Date`: `new Date("2026-08-15")` is
 * midnight UTC, and formatting that in a timezone behind UTC yields the
 * PREVIOUS day. A statement whose published date silently shifts by one day
 * depending on where the server is would be a genuinely bad way to lose an
 * argument with a regulator.
 */
export function formatStatementDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!m) throw new Error(`VOID: not an ISO date: "${iso}"`)
  const month = MONTHS[Number(m[2]) - 1]
  if (!month) throw new Error(`VOID: month out of range in "${iso}"`)
  return `${Number(m[3])} ${month} ${m[1]}`
}

/** The document version shown under the title. */
export const ACCESSIBILITY_STATEMENT_VERSION = "1.0"

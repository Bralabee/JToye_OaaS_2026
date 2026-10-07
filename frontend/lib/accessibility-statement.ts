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
  // WCAG 2.2 since phase 31.1 plan 28 (#878, UXT-114). The claim stays
  // PARTIAL (D-12): moving the target is not moving the claim. The per-PR axe
  // gate in `e2e/public-a11y.spec.ts` carries the `wcag22aa` tag (axe's
  // `target-size`, WCAG 2.5.8), so the standard named here is the standard
  // that gate tests — tags and claim move together, in the same change.
  standard: "WCAG 2.2",
  level: "AA",
  claim: "partial",

  // Moved 2026-08-16 -> 2026-10-07 by phase 31.1 plan 28: the date the axe
  // evidence was captured against the built tree — every surface in the scope
  // below, both viewports, with the wcag22aa tag — and every exception was
  // re-measured rather than copied forward. Per-entry evidence (kept / removed,
  // and the command that showed it) is in that plan's summary.
  //
  // WHAT THAT AUDIT CHANGED HERE: the standard (2.1 -> 2.2); the scope (the
  // basket, the cash order confirmation, the per-shop order page and /track
  // added); "storefront-no-skip-link" removed, because the first Tab on every
  // storefront surface now lands on the skip link; and the contrast entry's
  // route list re-derived from the literal scan over the widened scope.
  preparedOn: "2026-10-07",
  lastReviewedOn: "2026-10-07",
  nextReviewDue: "2027-04-07",

  // Each entry is one surface. A PATH may repeat — the dish panel and the cash
  // order confirmation are states of a URL that is also listed for its main
  // page — but a LABEL never does (asserted by the dates test).
  inScopeRoutes: [
    { path: "/", label: "The J'Toye home page" },
    { path: "/shop", label: "The list of vendors" },
    { path: "/shop/[slug]", label: "An individual vendor's shop page" },
    {
      path: "/shop/[slug]",
      label: "The dish detail panel that opens on a vendor's shop page",
    },
    { path: "/shop/[slug]/cart", label: "Your basket at a vendor" },
    { path: "/shop/[slug]/checkout", label: "Checkout" },
    {
      path: "/shop/[slug]/checkout",
      label:
        "The order confirmation shown on the checkout page when you pay on collection or delivery",
    },
    {
      path: "/shop/[slug]/orders/[orderNumber]",
      label: "The page for one order, where you follow its progress",
    },
    { path: "/track", label: "Order tracking by order number and email address" },
    { path: "/shop/signin", label: "Customer sign-in" },
    { path: "/auth/signin", label: "Vendor sign-in" },
    { path: "/legal", label: "Legal and company information" },
    { path: "/legal/accessibility", label: "This statement" },
  ],

  excludedSurfaces: [
    {
      name: "The vendor dashboard, and everything behind a vendor sign-in",
      reason:
        "The dashboard is the tool vendors use to run their shop. It has not been comprehensively assessed against WCAG 2.2 level AA, so no conformance claim is made about it — but it is no longer unmonitored: key dashboard pages are scanned automatically with axe on every pull request (a blocking check), and every dashboard route is scanned nightly in a report-only pass that surfaces new problems without gating a release. It is named here rather than left unmentioned, because a scope that quietly stops at the sign-in page reads as a claim about everything.",
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
    // "storefront-no-skip-link" was removed by phase 31.1 plan 28 (#878): the
    // storefront layout (`app/shop/layout.tsx`) carries the same skip link as
    // the public shell, and on the built tree the first Tab on /, /shop,
    // /shop/signin, /track, a storefront, its basket and its seeded checkout
    // lands on "Skip to main content", whose #main target is the <main>
    // landmark. The axe gate now asserts that on every surface it scans, so
    // the defect cannot come back without reddening a PR.
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
      // Re-derived by plan 31.1-28 from the literal contrast scan
      // (`__tests__/contrast-literals.test.ts`, whose scan now includes
      // `app/track`): every in-scope route whose own source still carries a
      // below-AA text colour on a light surface. A clean axe run on these
      // routes is NOT evidence against the entry — many of those colours only
      // render in states a page-load scan never enters (error text, empty and
      // pending states) — which is why 31-18 kept it on a clean run too.
      routes: [
        "/",
        "/shop",
        "/shop/[slug]",
        "/shop/[slug]/cart",
        "/shop/[slug]/checkout",
        "/shop/[slug]/orders/[orderNumber]",
        "/track",
        "/auth/signin",
      ],
      remediationBy: "2027-02-16",
    },

    // --- Published information ---------------------------------------------
    // "registered-office-not-published" was removed by phase 31.1 plan 27
    // (#794, D-14): the registered office from the Companies House record for
    // 16471464, confirmed by the owner, is now built into every runtime and
    // shown on /legal, /legal/privacy and this page. Keeping the entry would
    // publish a statement that is no longer true. The category stays in the
    // type for the next published-information gap.
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

/**
 * The document version shown under the title. 1.1 (phase 31.1 plan 28): the
 * standard moved to WCAG 2.2, the scope widened and one exception was removed —
 * a change a reader comparing two copies of this page should be able to see.
 */
export const ACCESSIBILITY_STATEMENT_VERSION = "1.1"

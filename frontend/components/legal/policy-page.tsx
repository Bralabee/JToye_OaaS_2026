import Link from "next/link"
import { PublicShell } from "@/components/public/public-shell"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { cn } from "@/lib/utils"
import {
  PolicyToc,
  TOC_MIN_SECTIONS,
  sectionId,
  tocEntries,
} from "@/components/legal/policy-toc"

/**
 * The shell every /legal/* policy document renders through (UI-SPEC S2).
 *
 * WHY A SHELL AT ALL. Four policy documents land in this phase — privacy,
 * cookies, retention, accessibility. Four pages each hand-rolling their own
 * heading hierarchy, anchor scheme and "last updated" line is four chances to
 * get each of those wrong, and the ways they go wrong are not visible: a skipped
 * heading level, an index-derived anchor, a missing date. Getting them right in
 * one place is the only version of this that stays right.
 *
 * A PLAIN SERVER COMPONENT. No client directive, no route-segment config —
 * copied deliberately from PublicShell, which records the reason: the root
 * layout's force-dynamic and the CSP nonce cascade through untouched, and the
 * #89 failure mode is what happens when they do not. Everything below is
 * anchors and static markup, so nothing is given up to keep that property. The
 * below-lg disclosure uses a native details element rather than component state
 * for exactly this reason.
 *
 * IT WRAPS PublicShell AND NEVER RENDERS ITS OWN main. app/legal/page.tsx
 * carries the recorded regression: it used to render a bare main with no header
 * or footer, so anyone arriving from the dashboard legal line had one text link
 * out and no brand chrome at all. A second landmark of that kind would also
 * break the shell's skip link, which targets exactly one of them by id.
 *
 * THE DATE IS NOT DECORATION. A policy with no date cannot be relied on by
 * anybody — a reader cannot tell whether it covers what happened to them, and a
 * regulator cannot tell what was in force when. So lastUpdated and version are
 * REQUIRED props rather than optional ones: a page that forgets them does not
 * compile, which is the only enforcement that actually holds.
 */

export interface PolicyPageProps {
  /** The document title, rendered as the page's single h1. */
  title: string
  /** Human-readable date this document last changed, e.g. "16 August 2026". */
  lastUpdated: string
  /** Version identifier for the document, e.g. "1.0". */
  version: string
  /**
   * The h2 headings of this document, in document order. Used to build the
   * on-this-page nav. Pass the SAME strings to the PolicySection headings — the
   * anchor ids are derived from the text by one shared function, so passing the
   * same string twice is what keeps the nav and the document in agreement.
   */
  sections?: readonly string[]
  /** Standfirst paragraph shown under the date line, before the first section. */
  intro?: React.ReactNode
  /** The document body — normally a run of PolicySection elements. */
  children: React.ReactNode
}

/**
 * One h2 section of a policy document, carrying the anchor a deep link targets.
 *
 * The id lives on the heading and is derived from the heading's own text, so it
 * cannot drift from what it labels and cannot be invalidated by inserting a
 * section above it. scroll-margin is on the same element, because the shell
 * above it is sticky: without it the browser scrolls the heading to y=0 and the
 * header covers the very line the reader followed a link to reach.
 */
export function PolicySection({
  heading,
  children,
  className,
}: {
  heading: string
  children: React.ReactNode
  className?: string
}) {
  const id = sectionId(heading)
  return (
    <section aria-labelledby={id} className={cn("mt-12 first:mt-0", className)}>
      <h2
        id={id}
        className="scroll-mt-20 text-xl font-semibold tracking-tight text-oxblood"
      >
        {heading}
      </h2>
      <div className="mt-4 space-y-4 text-base leading-relaxed text-slate-700">
        {children}
      </div>
    </section>
  )
}

export function PolicyPage({
  title,
  lastUpdated,
  version,
  sections = [],
  intro,
  children,
}: PolicyPageProps) {
  // The nav is required at four sections and pointless below that: a two-item
  // list of links to content already on screen is noise a screen-reader user has
  // to step past.
  const showToc = sections.length >= TOC_MIN_SECTIONS
  const entries = showToc ? tocEntries(sections) : []

  return (
    <PublicShell>
      {/* THE POLICY BAND, AND WHY IT DECLARES A TIER RATHER THAN A NUMBER.

          This band is deliberately EQUAL to the Marketing content tier, which is
          what PublicShell's header and footer rails around it already render at.
          They must move together or not at all.

          They did not. The rails moved to the declared tier at 1280px while this
          band stayed on a stock scale token at 1152px, so all four policy pages
          sat 128px inside their own chrome — the nav and the document did not
          share a left edge. That is the same defect the landing page carried
          before ORCH-04, inherited here by omission rather than by decision, and
          fixing one surface while leaving its sibling is the "inconsistent half"
          the Incremental Betterment doctrine names a defect in its own right.

          WIDENING THIS BAND DOES NOT WIDEN THE PROSE, and that is the whole
          reason the change is safe. The reading measure is held independently on
          the three max-w-[68ch] elements NESTED INSIDE this one — the title
          block, the document column and the back link. A tier is a ceiling, not
          a target: this element caps the page, those three cap the line. The
          co-located test asserts both halves side by side so a later edit cannot
          quietly merge them.

          ORCH-06 (orchestrator decision, 2026-08-29); see CONTEXT.md section 4b.
          Raised mid-execution by plan 35-06 as finding D-35-06-a. The class comes
          from the vocabulary module and is never written out here: the tier
          literals exist in exactly one file and plan 35-10 gates that count. */}
      <div
        data-width-tier="marketing"
        className={cn(
          "mx-auto w-full",
          WIDTH_TIER_CLASS.marketing,
          "px-4 py-16 sm:px-6"
        )}
      >
        {/*
          A plain div, NOT a header element. HTML-AAM scopes `header` to
          `generic` when it descends from `main`, so in a correct implementation
          this would be harmless — but not every consumer implements that
          scoping, and the accessibility-name library behind the tests here maps
          it to a second `banner` landmark alongside the shell's real one. Two
          banners is a genuine navigation defect for anyone moving by landmark,
          and the title block gains nothing whatever from the element. Measured,
          not assumed: this was written as a header first and the landmark
          assertion is what found it.
        */}
        <div className="max-w-[68ch]">
          <h1 className="text-[28px] font-semibold leading-tight tracking-tight text-oxblood">
            {title}
          </h1>
          <p className="mt-3 text-sm font-semibold text-slate-600">
            Last updated: {lastUpdated} · Version {version}
          </p>
          {intro ? (
            <p className="mt-6 text-base leading-relaxed text-slate-700">
              {intro}
            </p>
          ) : null}
        </div>

        <div className="mt-10 lg:flex lg:items-start lg:gap-12">
          {showToc ? (
            <PolicyToc
              sections={entries}
              className="lg:order-2 lg:sticky lg:top-24"
            />
          ) : null}
          {/*
            max-w-[68ch] caps the measure at roughly 68 characters. min-w-0 is
            what stops a long unbroken string — a URL in a policy, which is
            common — from forcing the flex item wider than its basis and pushing
            the rail off screen.
          */}
          <div className="mt-8 min-w-0 max-w-[68ch] lg:order-1 lg:mt-0 lg:flex-1">
            {children}
          </div>
        </div>

        <p className="mt-16 max-w-[68ch] text-base">
          <Link
            href="/legal"
            className="inline-flex min-h-11 items-center font-semibold text-amber-700 hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            ← All legal &amp; policy documents
          </Link>
        </p>
      </div>
    </PublicShell>
  )
}

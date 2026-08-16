import { cn } from "@/lib/utils"

/**
 * "On this page" navigation for a legal policy document (UI-SPEC S2).
 *
 * A PLAIN SERVER COMPONENT, like PublicShell and for the same reason: no client
 * directive and no route-segment config, so the root layout's force-dynamic and
 * the CSP nonce cascade through untouched (the #89 failure mode). A list of
 * plain anchors needs no interactivity, so there is nothing to give up.
 *
 * NOT AN ACCORDION, deliberately. The UI-SPEC bars the shadcn accordion here by
 * name: it would put legally operative navigation behind a JS interaction and
 * behind a client boundary, and a crawler that does not run it sees a policy
 * with no structure. A native disclosure element needs neither.
 *
 * THREE THINGS THE ANALOG GETS WRONG, not copied here. The nav in
 * `components/marketing/business-model-guide.tsx:142` is the closest existing
 * pattern — labelled nav, anchors over sections, subject-derived ids,
 * scroll-margin — but (1) it is a client component with useState filters,
 * (2) its nav is a horizontal pill strip rather than the sticky rail plus
 * below-lg disclosure this contract calls for, and (3) its labelling attribute
 * names an element that does not exist anywhere in the document, so the nav has
 * no accessible name at all — it silently resolves to nothing. That third one
 * is why the label element below is asserted by the tests and not merely
 * written: the bug is invisible in a browser and invisible to a presence check.
 *
 * ONE INSTANCE, NOT TWO. The obvious way to get "collapsed on mobile, sticky
 * rail on desktop" is to render the nav twice under complementary `hidden`
 * classes. That is duplicated DOM, and this repo has already paid for that twice
 * (the streaming staging buffer that put a second copy of the shell in the DOM,
 * filed as a product bug at #556 and again at #593). Two navs would also mean
 * two elements racing for the same anchor-target id. So there is exactly one.
 *
 * WHY IT SHIPS EXPANDED BELOW lg, WHICH IS A KNOWN DEVIATION. The spec says
 * "collapsed disclosure below lg". The `open` state of a disclosure element is
 * server-rendered markup, not a style, so it cannot vary by viewport without
 * either a client boundary (surrenders the property above) or duplicated DOM
 * (the trap above). Between the two available failure modes — a mobile nav that
 * starts open and can be closed, versus a desktop rail that silently vanishes if
 * a CSS override does not land — this picks the one that fails safe. The
 * collapsed-below-lg half is left to the browser-level gate in 31-18 to confirm
 * or re-open; it is recorded here rather than quietly dropped.
 */

/** A resolved section: the anchor target and the label shown in the nav. */
export interface PolicySectionRef {
  id: string
  label: string
}

/**
 * The single source of anchor ids for a policy document.
 *
 * Derived from the heading TEXT and never from its position, so a regulator's
 * deep link survives a reorder. `id="section-3"` breaks the moment a section is
 * inserted above it, and nothing in the page reports that it broke — the link
 * simply lands at the top of the document, which reads as "the citation was
 * always vague" rather than as a defect.
 *
 * There is exactly one of these functions, and both the headings and the nav
 * call it, because two derivations that agree today are two derivations that
 * will disagree later. A heading whose text contains no alphanumerics resolves
 * to the empty string; that is deliberately NOT papered over with a positional
 * fallback, because the anchor-resolution test then fails loudly instead of the
 * page silently shipping a link to nowhere.
 */
// NAMED, because the two character classes below are invisible or
// near-invisible in an editor. The first is the Unicode combining-diacritic
// block that NFKD splits accents into (so "Café" folds to "cafe" rather than
// dropping the accented letter entirely); the second is the straight apostrophe
// plus both curly forms, which matter because the operator's own name carries
// one and "J'Toye" must not become "j-toye". Naming them is the difference
// between a reviewable line and a line nobody can read.
const COMBINING_MARKS = /[̀-ͯ]/gu
const APOSTROPHES = /['‘’]/gu

export function sectionId(headingText: string): string {
  return headingText
    .normalize("NFKD")
    .replace(COMBINING_MARKS, "")
    .toLowerCase()
    .replace(APOSTROPHES, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+/, "")
    .replace(/-+$/, "")
}

/** Resolve an ordered list of heading strings into anchor targets. */
export function tocEntries(headings: readonly string[]): PolicySectionRef[] {
  return headings.map((label) => ({ id: sectionId(label), label }))
}

/**
 * The number of sections at which the UI-SPEC requires an on-this-page nav.
 * Exported so the pages and the tests agree on one number rather than three.
 */
export const TOC_MIN_SECTIONS = 4

export function PolicyToc({
  sections,
  className,
}: {
  sections: readonly PolicySectionRef[]
  className?: string
}) {
  if (sections.length === 0) return null

  return (
    <nav
      aria-labelledby="on-this-page"
      className={cn("w-full lg:w-56 lg:shrink-0", className)}
    >
      <details
        open
        className="rounded-lg border border-oxblood/15 bg-cream-100/60 px-4 py-3 lg:border-0 lg:bg-transparent lg:px-0 lg:py-0"
      >
        {/*
          The label element the attribute above names. It is a summary rather
          than a heading so that one element is both the disclosure control and
          the accessible name — a second element would be a second thing to keep
          in sync, and the analog's bug is exactly a name that got out of sync
          with nothing.
        */}
        <summary
          id="on-this-page"
          className="flex min-h-11 cursor-pointer list-none items-center text-sm font-semibold text-oxblood focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 lg:cursor-default [&::-webkit-details-marker]:hidden"
        >
          On this page
        </summary>
        <ul className="mt-2 space-y-1 border-l border-oxblood/15 lg:mt-3">
          {sections.map((section) => (
            <li key={section.id}>
              <a
                href={`#${section.id}`}
                className="flex min-h-11 items-center border-l-2 border-transparent px-3 text-sm leading-snug text-amber-700 hover:border-amber-700 hover:text-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                {section.label}
              </a>
            </li>
          ))}
        </ul>
      </details>
    </nav>
  )
}

/**
 * On-this-page nav and the anchor derivation behind it (UI-SPEC S2, F4).
 *
 * THE FAILURE THIS FILE EXISTS FOR (F4). Someone renames a section heading and
 * does not update the nav. Nothing breaks visibly: the page renders, the nav
 * renders, the link is clickable, and clicking it does nothing at all — the
 * browser cannot find the target and leaves the reader where they were. On a
 * privacy notice that link may be the citation a regulator or a procurement
 * reviewer was given, so "it silently goes nowhere" is the whole defect.
 * Asserting that every generated href resolves to a real id is the only check
 * that sees it; a presence check on the nav passes on exactly that build.
 *
 * WHY THE ACCESSIBLE NAME IS ASSERTED AND NOT ASSUMED. The nearest existing
 * pattern in this repo (business-model-guide.tsx:142) points its labelling
 * attribute at an element id that exists nowhere in the document, so the nav has
 * no accessible name — it is announced as an unlabelled navigation landmark
 * alongside the header and footer ones. That bug is invisible in a browser and
 * invisible to any check that greps for the attribute, because the attribute IS
 * there. Only resolving the name catches it, so that is what is asserted.
 */
import { render, screen, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import {
  PolicyToc,
  TOC_MIN_SECTIONS,
  sectionId,
  tocEntries,
} from "@/components/legal/policy-toc"
import { PolicySection } from "@/components/legal/policy-page"

const HEADINGS = [
  "Who we are",
  "What data we collect",
  "How long we keep data",
  "Your rights and how to use them",
] as const

describe("sectionId — derived from TEXT, never from position", () => {
  it("kebab-cases the heading's own words", () => {
    expect(sectionId("How long we keep data")).toBe("how-long-we-keep-data")
    expect(sectionId("Who we are")).toBe("who-we-are")
  })

  it("produces the same id regardless of where the heading sits", () => {
    // The reorder itself. An index-derived scheme returns "section-1" then
    // "section-3" for the same heading across these two arrays and every deep
    // link into the document silently retargets; a text-derived one cannot.
    const first = tocEntries(["How long we keep data", "Who we are"])
    const second = tocEntries([
      "Your rights and how to use them",
      "Who we are",
      "How long we keep data",
    ])

    const idIn = (entries: ReturnType<typeof tocEntries>, label: string) =>
      entries.find((e) => e.label === label)?.id

    expect(idIn(first, "How long we keep data")).toBe("how-long-we-keep-data")
    expect(idIn(second, "How long we keep data")).toBe("how-long-we-keep-data")
    expect(idIn(first, "Who we are")).toBe(idIn(second, "Who we are"))

    // And prove the two arrays really did differ, so the equality above is a
    // statement about the derivation rather than about two identical inputs.
    expect(first.map((e) => e.label)).not.toEqual(second.map((e) => e.label))
  })

  it("folds the punctuation a legal heading actually contains", () => {
    // The operator's own name carries an apostrophe, so this is not academic:
    // a naive replacement turns "J'Toye" into "j-toye" and every published link
    // that predates the change breaks.
    expect(sectionId("J'Toye’s role as controller")).toBe(
      "jtoyes-role-as-controller"
    )
    expect(sectionId("Data transfers — outside the UK")).toBe(
      "data-transfers-outside-the-uk"
    )
    expect(sectionId("  Trailing and leading  ")).toBe("trailing-and-leading")
  })
})

describe("PolicyToc", () => {
  it("has an accessible name resolved from a real element", () => {
    render(<PolicyToc sections={tocEntries(HEADINGS)} />)

    // getByRole with a name filter RESOLVES the name rather than checking the
    // attribute — this query returns nothing when the labelling attribute points
    // at a missing element, which is the analog's bug.
    const nav = screen.getByRole("navigation", { name: /on this page/i })
    expect(nav).toBeInTheDocument()

    // And the element supplying that name is really in the document, so the
    // assertion above cannot be satisfied by a fallback.
    const label = document.getElementById("on-this-page")
    expect(label).not.toBeNull()
    expect(nav.getAttribute("aria-labelledby")).toBe(label?.id)
  })

  it("lists every section once, in document order", () => {
    render(<PolicyToc sections={tocEntries(HEADINGS)} />)

    const nav = screen.getByRole("navigation", { name: /on this page/i })
    const links = within(nav).getAllByRole("link")
    expect(links).toHaveLength(HEADINGS.length)
    expect(links.map((a) => a.textContent)).toEqual([...HEADINGS])
  })

  it("renders nothing when there are no sections", () => {
    const { container } = render(<PolicyToc sections={[]} />)
    expect(container.querySelector("nav")).toBeNull()
  })

  it("requires four sections before a nav is warranted", () => {
    // Pinned so the pages and the spec cannot drift apart silently.
    expect(TOC_MIN_SECTIONS).toBe(4)
  })
})

describe("F4 — every generated anchor resolves to a real section id", () => {
  /**
   * The nav and the document rendered together, the way a policy page composes
   * them. Both sides call sectionId on the same strings; this asserts that the
   * result actually meets in the DOM rather than trusting that it must.
   */
  function Document({
    navHeadings,
    bodyHeadings,
  }: {
    navHeadings: readonly string[]
    bodyHeadings: readonly string[]
  }) {
    return (
      <div>
        <PolicyToc sections={tocEntries(navHeadings)} />
        {bodyHeadings.map((h) => (
          <PolicySection key={h} heading={h}>
            <p>Body of {h}.</p>
          </PolicySection>
        ))}
      </div>
    )
  }

  it("resolves every href when the nav and the document agree", () => {
    const { container } = render(
      <Document navHeadings={HEADINGS} bodyHeadings={HEADINGS} />
    )

    const hrefs = Array.from(
      container.querySelectorAll<HTMLAnchorElement>('nav a[href^="#"]')
    ).map((a) => a.getAttribute("href") ?? "")

    // NON-VACUITY CONTROL first. An empty href list makes the loop below a
    // no-op that passes — "every one of zero links resolves" is true and means
    // nothing. This is the artefact class, so the count is asserted, not hoped.
    expect(hrefs).toHaveLength(HEADINGS.length)

    const dangling = hrefs.filter(
      (href) => container.querySelector(href) === null
    )
    expect(dangling).toEqual([])
  })

  it("goes red when a heading is renamed without updating the nav", () => {
    // The F4 defect, reproduced in-suite rather than only in a break arm, so the
    // assertion is proven capable of failing on every future run and not just on
    // the day it was written.
    const renamed = [
      "Who we are",
      "What data we collect",
      "How long we hold data", // was "How long we keep data"
      "Your rights and how to use them",
    ]

    const { container } = render(
      <Document navHeadings={HEADINGS} bodyHeadings={renamed} />
    )

    const hrefs = Array.from(
      container.querySelectorAll<HTMLAnchorElement>('nav a[href^="#"]')
    ).map((a) => a.getAttribute("href") ?? "")
    expect(hrefs).toHaveLength(HEADINGS.length)

    const dangling = hrefs.filter(
      (href) => container.querySelector(href) === null
    )
    expect(dangling).toEqual(["#how-long-we-keep-data"])
  })
})

describe("PolicySection", () => {
  it("puts the derived id on the heading and labels the section by it", () => {
    render(
      <PolicySection heading="How long we keep data">
        <p>Six years for order records.</p>
      </PolicySection>
    )

    const heading = screen.getByRole("heading", {
      level: 2,
      name: "How long we keep data",
    })
    expect(heading).toHaveAttribute("id", "how-long-we-keep-data")
    // scroll-margin is the difference between landing on the heading and
    // landing on it underneath the sticky header, where it cannot be read.
    expect(heading).toHaveClass("scroll-mt-20")

    const section = screen.getByRole("region", { name: "How long we keep data" })
    expect(section).toBeInTheDocument()
  })
})

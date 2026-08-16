/**
 * Structural and substantive contract of the privacy notice (LGL-01, D-15).
 *
 * WHY THESE ASSERTIONS AND NOT OTHERS. A privacy notice is almost entirely
 * prose, and prose is the hardest thing to keep honest with tests: nothing here
 * can check that the words are *true*. What CAN be checked, and is checked
 * below, is every property whose violation would be invisible to a reader and
 * expensive to a regulator:
 *
 *   - that the document has a navigable structure at all (one h1, no skipped
 *     level, an id on every h2 that a deep link can target);
 *   - that identity resolves to the ACTIVE company and never to the dissolved
 *     namesake — the failure this platform's marketing site has already made
 *     once, in production;
 *   - that the retention section states no period of its own, so the schedule
 *     cannot fork from the manifest that owns it;
 *   - that the rights section points somewhere that exists in BOTH
 *     configuration states, rather than shipping an empty mailto.
 *
 * EVERY SCAN IS PRECEDED BY A NON-VACUITY CONTROL, in the same test, scoped to
 * the MAIN landmark. This is D-13 and it is not ceremony: the shared footer
 * supplies headings of its own, so a document-wide heading count stays green
 * over a policy page that rendered no content whatsoever. A structural
 * assertion over an empty document passes, and passes silently.
 */
import { render, screen, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import PrivacyNoticePage, { metadata } from "@/app/legal/privacy/page"
import { getCustomerSession } from "@/lib/customer-auth"

// The shell's header polls a customer session on an interval; unmocked it
// resolves through a rejected fetch and makes every arm non-deterministic.
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

const DPO_VAR = "NEXT_PUBLIC_DATA_PROTECTION_EMAIL"
const OFFICE_VAR = "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE"

const SAMPLE_EMAIL = "privacy@olajay.co.uk"

// The ACTIVE registration. The dissolved namesake is asserted absent; both
// numbers live here in the test rather than in the page, because the page must
// source identity from getCompanyInfo() and hardcode neither.
const ACTIVE_COMPANY_NUMBER = "16471464"
const DISSOLVED_COMPANY_NUMBER = "13434105"

const savedDpo = process.env[DPO_VAR]
const savedOffice = process.env[OFFICE_VAR]

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
  // Configured arm is the default for this file; the unconfigured arm sets its
  // own state explicitly and is the one that proves the fallback.
  process.env[DPO_VAR] = SAMPLE_EMAIL
  delete process.env[OFFICE_VAR]
})

afterAll(() => {
  if (savedDpo === undefined) delete process.env[DPO_VAR]
  else process.env[DPO_VAR] = savedDpo
  if (savedOffice === undefined) delete process.env[OFFICE_VAR]
  else process.env[OFFICE_VAR] = savedOffice
})

/** The document region, excluding the shared header and footer chrome. */
function renderNotice() {
  const utils = render(<PrivacyNoticePage />)
  const main = utils.container.querySelector("main")
  if (!main) throw new Error("no main landmark — the shell did not render")
  return { ...utils, main: main as HTMLElement }
}

function headingsIn(main: HTMLElement): HTMLHeadingElement[] {
  return Array.from(main.querySelectorAll("h1, h2, h3, h4, h5, h6"))
}

function sectionByAnchor(main: HTMLElement, id: string): HTMLElement {
  const section = main.querySelector(`section[aria-labelledby="${id}"]`)
  if (!section) throw new Error(`no section anchored at #${id}`)
  return section as HTMLElement
}

describe("privacy notice — document structure", () => {
  it("carries exactly one h1, and it is the document title", () => {
    renderNotice()
    const h1s = screen.getAllByRole("heading", { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent("Privacy notice")
  })

  it("renders at least six h2 sections, then gives every one a non-empty id", () => {
    const { main } = renderNotice()

    // NON-VACUITY CONTROL, asserted BEFORE anything about ids. Without it the
    // id assertion below is a loop over an empty array, which passes.
    const h2s = Array.from(main.querySelectorAll("h2"))
    expect(h2s.length).toBeGreaterThanOrEqual(6)

    const missing = h2s
      .filter((h) => !h.id || h.id.trim() === "")
      .map((h) => h.textContent)
    expect(missing).toEqual([])
  })

  it("skips no heading level inside the document", () => {
    const { main } = renderNotice()

    // Control first: a document with no headings skips no level vacuously.
    const headings = headingsIn(main)
    expect(headings.length).toBeGreaterThanOrEqual(7)

    const levels = headings.map((h) => Number(h.tagName.slice(1)))
    const skips: string[] = []
    for (let i = 1; i < levels.length; i++) {
      if (levels[i] > levels[i - 1] + 1) {
        skips.push(
          `${headings[i - 1].tagName} "${headings[i - 1].textContent}" -> ` +
            `${headings[i].tagName} "${headings[i].textContent}"`
        )
      }
    }
    expect(skips).toEqual([])
  })

  it("resolves every on-this-page link to a heading that exists", () => {
    const { main, container } = renderNotice()

    const nav = screen.getByRole("navigation", { name: /on this page/i })
    const hrefs = Array.from(nav.querySelectorAll("a"))
      .map((a) => a.getAttribute("href") || "")
      .filter((h) => h.startsWith("#"))

    // Control: a nav with no links has no dangling links.
    expect(hrefs.length).toBeGreaterThanOrEqual(6)

    const dangling = hrefs.filter(
      (href) => !container.querySelector(`[id="${href.slice(1)}"]`)
    )
    expect(dangling).toEqual([])
    expect(main).toBeInTheDocument()
  })
})

describe("privacy notice — identity", () => {
  it("names the active company number and never the dissolved namesake", () => {
    const { main } = renderNotice()
    const text = main.textContent || ""

    // Presence first — it is the control that proves the identity block
    // rendered at all. Then the absence, which is the assertion that actually
    // fires when the wrong number is cited alongside the right one.
    expect(text).toContain(ACTIVE_COMPANY_NUMBER)
    expect(text).not.toContain(DISSOLVED_COMPANY_NUMBER)
  })

  it("reproduces the Article 26 essence rather than paraphrasing it", () => {
    const { main } = renderNotice()
    const text = (main.textContent || "").replace(/\s+/g, " ")

    // Load-bearing sentences of the published essence, verbatim from
    // docs/legal/article-26-arrangement.md. If the arrangement is edited and
    // this page is not, these fire — which is the point: the two must not drift.
    expect(text).toContain(
      "J'Toye and the shop are jointly responsible"
    )
    expect(text).toContain(
      "J'Toye does not check your order against any allergy information a shop has recorded, and does not hold allergy information about you."
    )
    expect(text).toContain(
      "No J'Toye employee can browse across shops to look at your details."
    )
  })

  it("names the trading line and the GDPR line as different questions", () => {
    const { main } = renderNotice()
    const section = sectionByAnchor(main, "the-trading-line-and-the-gdpr-line")
    const text = (section.textContent || "").replace(/\s+/g, " ")

    expect(text.length).toBeGreaterThan(200)
    expect(text).toMatch(/trading/i)
    expect(text).toMatch(/data protection/i)
    expect(text).toMatch(/jointly/i)
    // It must link to the page carrying the other line, not merely mention it.
    expect(section.querySelector('a[href="/legal"]')).toBeInTheDocument()
  })
})

describe("privacy notice — retention numbers live in exactly one place", () => {
  it("links to the retention schedule and states no period of its own", () => {
    const { main } = renderNotice()
    const section = sectionByAnchor(main, "how-long-we-keep-it")

    // Control: prove the section has substantive content before asserting an
    // absence over it. An empty section contains no period either.
    const text = (section.textContent || "").replace(/\s+/g, " ")
    expect(text.length).toBeGreaterThan(150)

    expect(
      section.querySelector('a[href="/legal/retention"]')
    ).toBeInTheDocument()

    // The assertion that keeps the manifest the single source: no digit
    // followed by a period unit anywhere in this section.
    const periods = text.match(/\d+\s*(day|days|month|months|year|years)\b/gi)
    expect(periods).toBeNull()
  })
})

describe("privacy notice — the rights section points somewhere that exists", () => {
  it("publishes the configured contact as a real address, never an empty mailto", () => {
    const { main } = renderNotice()
    const section = sectionByAnchor(main, "your-rights-and-how-to-exercise-them")

    const mailtos = Array.from(section.querySelectorAll('a[href^="mailto:"]'))
    expect(mailtos.length).toBeGreaterThanOrEqual(1)

    for (const a of mailtos) {
      const href = a.getAttribute("href") || ""
      // "mailto:" with nothing after it is the failure this guards. It renders
      // as a link, looks discharged, and goes nowhere.
      expect(href.slice("mailto:".length).trim()).not.toBe("")
      expect(href).toBe(`mailto:${SAMPLE_EMAIL}`)
      expect(a.textContent).toContain(SAMPLE_EMAIL)
    }
  })

  it("names every right the notice claims to offer", () => {
    const { main } = renderNotice()
    const section = sectionByAnchor(main, "your-rights-and-how-to-exercise-them")
    const text = (section.textContent || "").toLowerCase()

    for (const right of [
      "copy",
      "corrected",
      "erased",
      "portable",
      "object",
    ]) {
      expect(text).toContain(right)
    }
  })

  it("degrades to the routes that exist when no contact is configured", () => {
    delete process.env[DPO_VAR]
    delete process.env[OFFICE_VAR]

    const { main } = renderNotice()
    const section = sectionByAnchor(main, "your-rights-and-how-to-exercise-them")

    // No mailto at all is correct here. An empty one would be the defect.
    expect(section.querySelectorAll('a[href^="mailto:"]')).toHaveLength(0)
    expect(section.querySelector('a[href="mailto:"]')).toBeNull()

    // And it must still tell the reader what they CAN do, rather than fall
    // silent — the routes below exist regardless of configuration.
    const text = (section.textContent || "").replace(/\s+/g, " ")
    expect(text).toMatch(/shop you ordered from/i)
    expect(text).toMatch(/Information Commissioner/i)
  })

  it("still offers the ICO as a complaint route in both configuration states", () => {
    for (const configured of [true, false]) {
      if (configured) process.env[DPO_VAR] = SAMPLE_EMAIL
      else delete process.env[DPO_VAR]

      const { main, unmount } = renderNotice()
      const section = sectionByAnchor(main, "complaints")
      expect(
        section.querySelector('a[href^="https://ico.org.uk"]')
      ).toBeInTheDocument()
      unmount()
    }
  })
})

describe("privacy notice — the allergen position matches the recorded determination", () => {
  it("states that the platform does not check an order against stored allergies", () => {
    const { main } = renderNotice()
    const section = sectionByAnchor(main, "allergen-and-dietary-information")
    const text = (section.textContent || "").replace(/\s+/g, " ")

    expect(text.length).toBeGreaterThan(400)
    expect(text).toMatch(/does not consult/i)
    expect(text).toMatch(/never learn your allergies/i)
  })

  it("does NOT imply the field is withheld from its own subject", () => {
    const { main } = renderNotice()
    const section = sectionByAnchor(main, "allergen-and-dietary-information")
    const text = (section.textContent || "").replace(/\s+/g, " ")

    // D-01 and the Article 20 export are both true at once, and the notice must
    // say so. This is the sentence a careless edit deletes.
    expect(text).toMatch(/included in the copy of your data/i)
    expect(text).toMatch(/access request/i)
  })

  it("makes no claim the platform has checked the order against the reader", () => {
    const { main } = renderNotice()
    const text = (main.textContent || "").replace(/\s+/g, " ")

    // Wording prohibited by the Article 9 determination because it would claim
    // a processing operation that document records as not happening.
    //
    // THIS CHECK IS NEGATION-BLIND, DELIBERATELY, AND IT CAUGHT ITS AUTHOR.
    // The first draft of the page contained "it is not a check that the order
    // is safe for you" — a DENIAL of the banned claim — and this assertion went
    // red on it. That is a false positive against the legal risk and a true
    // positive against the rule as written, and the rule was kept rather than
    // taught to recognise negation: "not safe for you" is one careless edit,
    // one translation, or one pull-quote away from "safe for you", and the
    // phrase has no legitimate use on this surface in either polarity. The
    // page was reworded instead. Do not relax this into a negation-aware regex
    // — the strictness is the feature.
    for (const banned of [
      "allergen-free",
      "safe for you",
      "no allergens present",
      "matches your profile",
    ]) {
      expect(text.toLowerCase()).not.toContain(banned)
    }
  })
})

describe("privacy notice — presentation contract", () => {
  it("uses no weight-700 type anywhere in the document", () => {
    const { main } = renderNotice()

    // Control: prove elements were scanned at all.
    const all = Array.from(main.querySelectorAll("*"))
    expect(all.length).toBeGreaterThan(50)

    const offenders = all
      .filter((el) => (el.getAttribute("class") || "").includes("font-bold"))
      .map((el) => el.tagName)
    expect(offenders).toEqual([])
  })

  it("uses the amber link colour and never the reserved accent", () => {
    const { main } = renderNotice()
    const links = Array.from(main.querySelectorAll("a"))
    expect(links.length).toBeGreaterThanOrEqual(5)

    const accented = links
      .map((a) => a.getAttribute("class") || "")
      .filter((c) => c.includes("text-primary") || c.includes("bg-primary"))
    expect(accented).toEqual([])

    // At least the in-document prose links carry the contracted colour.
    const amber = links.filter((a) =>
      (a.getAttribute("class") || "").includes("text-amber-700")
    )
    expect(amber.length).toBeGreaterThanOrEqual(4)
  })
})

describe("privacy notice — the prose actually reads as prose", () => {
  it("loses no space where an inline element meets the text after it", () => {
    const { main } = renderNotice()

    // See the twin of this test on the cookie policy for the measured
    // mechanism. Short version: when the JSXText FOLLOWING an inline element
    // contains an HTML entity anywhere in it, the transform drops that node's
    // leading space and the words run together in the delivered HTML. The
    // source still shows a space, so review cannot catch it.
    //
    // This notice is denser in inline emphasis than any other page in the
    // phase — the reproduced Article 26 essence is almost entirely bolded
    // clauses inside sentences — so it is the page with the most boundaries to
    // get wrong, and a published essence with words fused together is a poor
    // advertisement for a document a regulator reads.
    const containers = Array.from(main.querySelectorAll("p, li, h2, h3"))
    expect(containers.length).toBeGreaterThan(20)

    const runTogether: string[] = []
    for (const el of containers) {
      const hits = el.innerHTML.match(
        /<\/(?:code|span|a|strong|em|b|i)>[A-Za-z]\w*/g
      )
      if (hits) runTogether.push(...hits)
    }
    expect(runTogether).toEqual([])
  })
})

describe("privacy notice — metadata", () => {
  it("declares its own canonical, title and description", () => {
    expect(metadata.alternates?.canonical).toBe("/legal/privacy")
    expect(metadata.title).toBe("Privacy notice — J'Toye")
    expect(String(metadata.description)).toContain(
      "who is responsible for what"
    )
  })

  it("has a title and description distinct from the legal index", async () => {
    const legalIndex = await import("@/app/legal/page")
    expect(metadata.title).not.toBe(legalIndex.metadata.title)
    expect(metadata.description).not.toBe(legalIndex.metadata.description)
    expect(metadata.alternates?.canonical).not.toBe(
      legalIndex.metadata.alternates?.canonical
    )
  })
})

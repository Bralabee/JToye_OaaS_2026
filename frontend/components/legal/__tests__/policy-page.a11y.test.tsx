/**
 * Component-level accessibility scan of the policy shell (LGL-02, D-13), plus
 * the structural contract of the /legal index (UI-SPEC S2).
 *
 * D-13 IS THE WHOLE DESIGN OF THIS FILE. A zero-violation axe result is presumed
 * an artefact until proven otherwise, because this project has already shipped
 * one: a naive "0 button-name violations" pass over tables that never mounted.
 * axe reports zero for an empty tree and zero for a perfect page, and the two
 * results are byte-identical. So every scan below is preceded, in the same test,
 * by a control that proves the thing under test actually rendered — and the
 * control is scoped to the MAIN landmark rather than to the document, because
 * the shared footer supplies h2 headings of its own and a document-wide heading
 * count would stay green over a policy page with no content at all. That is not
 * hypothetical: it is the exact shape of the historical false zero, and the
 * empty-children arm below demonstrates it firing.
 *
 * Rule-set note: this layer runs jest-axe's nested axe-core 4.10.2, while the
 * Playwright layer runs 4.13.0. The two do not share one rule set, so a clean
 * result here is not a claim about the other.
 *
 * IT ALSO HOLDS THE SHELL'S WIDTH CONTRACT (ORCH-06). The band and the reading
 * measure are two different things that a single careless edit merges into one,
 * so the assertion that the band is at the Marketing tier and the assertion that
 * the three 68ch measures are untouched live in the same file, next to each
 * other, where a reader meets both at once.
 */
import { render, screen, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import { axe, toHaveNoViolations } from "jest-axe"
import { PolicyPage, PolicySection } from "@/components/legal/policy-page"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import LegalPage from "@/app/legal/page"
import { getCustomerSession } from "@/lib/customer-auth"

expect.extend(toHaveNoViolations)

// The shell's header polls a customer session on an interval; unmocked it
// resolves through a rejected fetch and makes the arm non-deterministic.
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
})

// jsdom has no layout and axe walks the whole shell subtree; the default 5s
// timeout flakes on a loaded runner.
const AXE_TIMEOUT_MS = 30_000

const SECTIONS = [
  "Who we are",
  "What data we collect",
  "How long we keep data",
  "Your rights and how to use them",
] as const

const LAST_UPDATED = "16 August 2026"
const VERSION = "1.0"

function SampleDocument() {
  return (
    <PolicyPage
      title="Privacy notice"
      lastUpdated={LAST_UPDATED}
      version={VERSION}
      sections={SECTIONS}
      intro="How J'Toye and the vendors on it use your personal data."
    >
      {SECTIONS.map((heading) => (
        <PolicySection key={heading} heading={heading}>
          <p>Substantive body copy for the {heading.toLowerCase()} section.</p>
        </PolicySection>
      ))}
    </PolicyPage>
  )
}

/**
 * The non-vacuity control, in one place so the empty-children arm exercises the
 * SAME assertions the clean arm relies on rather than a weaker restatement.
 * Returns the main landmark so callers can go on to scope their own queries.
 */
function assertDocumentReallyRendered() {
  const main = screen.getByRole("main")
  const h1s = within(main).getAllByRole("heading", { level: 1 })
  expect(h1s).toHaveLength(1)
  // Scoped to main deliberately: the shared footer renders h2 column headings,
  // so a document-wide query is satisfied by chrome alone.
  const h2s = within(main).getAllByRole("heading", { level: 2 })
  expect(h2s.length).toBeGreaterThan(0)
  expect(
    within(main).getByText(new RegExp(`Last updated: ${LAST_UPDATED}`))
  ).toBeInTheDocument()
  return main
}

describe("PolicyPage — structure (UI-SPEC S2)", () => {
  it("renders inside the shared shell with exactly one main landmark", () => {
    render(<SampleDocument />)

    // getAllByRole, not getByRole: a second main is the regression here, and a
    // singular query reports it as a generic "found multiple" rather than as
    // the landmark violation it is.
    const mains = screen.getAllByRole("main")
    expect(mains).toHaveLength(1)
    expect(mains[0]).toHaveAttribute("id", "main")

    // The shell's chrome really came with it — a page that rendered its own
    // bare main would satisfy the count above and none of these.
    expect(screen.getByRole("contentinfo")).toBeInTheDocument()
    expect(screen.getByRole("banner")).toBeInTheDocument()
    const skip = screen.getByRole("link", { name: /skip to main content/i })
    expect(skip).toHaveAttribute("href", "#main")
  })

  it("carries one h1, h2 sections, and skips no heading level inside main", () => {
    render(<SampleDocument />)
    const main = assertDocumentReallyRendered()

    expect(
      within(main).getByRole("heading", { level: 1, name: "Privacy notice" })
    ).toBeInTheDocument()
    expect(within(main).getAllByRole("heading", { level: 2 })).toHaveLength(
      SECTIONS.length
    )
    // An h3 with no h2 above it in the same run is a level skip; none of the
    // shell's own content introduces one, so any h3 here would be ours.
    const levels = within(main)
      .getAllByRole("heading")
      .map((h) => Number(h.tagName.slice(1)))
    for (let i = 1; i < levels.length; i += 1) {
      expect(levels[i] - levels[i - 1]).toBeLessThanOrEqual(1)
    }
  })

  it("caps the prose measure at 68 characters", () => {
    const { container } = render(<SampleDocument />)

    // Asserted here rather than left to a grep on the source. A grep for the
    // class passes on a file where the only remaining occurrence is the comment
    // explaining it — three checks in this phase's first wave failed exactly
    // that way. Querying the rendered markup cannot be satisfied by prose.
    const capped = container.querySelectorAll('[class*="max-w-[68ch]"]')
    expect(capped.length).toBeGreaterThan(0)

    // And specifically on the column holding the document body, not merely
    // somewhere on the page.
    const body = screen.getByRole("heading", {
      level: 2,
      name: SECTIONS[0],
    }).closest('[class*="max-w-[68ch]"]')
    expect(body).not.toBeNull()
  })

  it("states a date AND a version under the title", () => {
    render(<SampleDocument />)
    const main = screen.getByRole("main")
    const text = main.textContent ?? ""
    // Both halves: a date with no version cannot identify WHICH text was in
    // force when two revisions land on the same day.
    expect(text).toContain(`Last updated: ${LAST_UPDATED}`)
    expect(text).toContain(`Version ${VERSION}`)
  })

  it("omits the on-this-page nav below the four-section threshold", () => {
    render(
      <PolicyPage
        title="Short notice"
        lastUpdated={LAST_UPDATED}
        version={VERSION}
        sections={["Who we are", "Contact"]}
      >
        <PolicySection heading="Who we are">
          <p>Body.</p>
        </PolicySection>
        <PolicySection heading="Contact">
          <p>Body.</p>
        </PolicySection>
      </PolicyPage>
    )

    // Control: the page really rendered, so the absence below is about the nav.
    expect(
      screen.getByRole("heading", { level: 1, name: "Short notice" })
    ).toBeInTheDocument()
    expect(
      screen.queryByRole("navigation", { name: /on this page/i })
    ).toBeNull()
  })
})

/**
 * The token the band is renamed FROM, written out exactly once.
 *
 * It has to appear as a literal somewhere for the absence to be assertable, and
 * this is the same shape `app/__tests__/landing.test.tsx` uses for the same
 * reason. It is NOT a tier literal — the three tier strings live in exactly one
 * module and plan 35-10 gates that count — so spelling this one out here costs
 * nothing.
 */
const STOCK_WIDTH_TOKEN = "max-w-6xl"

/** The reading measure, held independently of the band. */
const PROSE_MEASURE = "max-w-[68ch]"

/** The band's non-width classes, every one of which must survive the rename. */
const PRESERVED_BAND_CLASSES = [
  "mx-auto",
  "w-full",
  "px-4",
  "py-16",
  "sm:px-6",
] as const

function classTokens(el: Element): string[] {
  return (el.getAttribute("class") || "").split(/\s+/).filter(Boolean)
}

describe("PolicyPage — the Marketing tier (ORCH-06)", () => {
  it("declares the Marketing tier on exactly one band inside main", () => {
    render(<SampleDocument />)
    const main = assertDocumentReallyRendered()

    // SCOPED TO MAIN, and that scoping is the whole assertion. See the scope
    // control below: a container-wide query is satisfied by the header and
    // footer rails over a page whose band declares nothing at all.
    const declared = Array.from(main.querySelectorAll("[data-width-tier]"))
    expect(declared.map((el) => el.getAttribute("data-width-tier"))).toEqual([
      "marketing",
    ])
  })

  it("carries the tier class INSTEAD of the stock token, never beside it", () => {
    render(<SampleDocument />)
    const main = screen.getByRole("main")

    const band = main.querySelector('[data-width-tier="marketing"]')
    expect(band).not.toBeNull()
    expect(classTokens(band as Element)).toContain(WIDTH_TIER_CLASS.marketing)

    // MEASURED, not assumed (35-06 ARM C, reproduced here by ARM C over this
    // component's own render): twMerge does NOT resolve a stock max-w-* scale
    // token against a tier class — handed both, it returns BOTH. So a half-done
    // rename leaves two live caps on one element, renders identically today,
    // and diverges silently the moment the tier value moves. Only an absence
    // assertion can see it.
    //
    // The tier token is deliberately NOT spelled out in this comment: the three
    // tier literals exist in exactly one module in shipped source and plan
    // 35-10 gates that, and a comment satisfies a grep just as well as code
    // does — which is the discipline content-tier.tsx's own docblock states.
    expect(main.querySelectorAll(`[class*="${STOCK_WIDTH_TOKEN}"]`)).toHaveLength(
      0
    )
  })

  it("keeps the auto margin, the full-width base and every padding class on that same band", () => {
    render(<SampleDocument />)
    const main = screen.getByRole("main")
    const band = main.querySelector('[data-width-tier="marketing"]')
    expect(band).not.toBeNull()

    const tokens = classTokens(band as Element)
    for (const preserved of PRESERVED_BAND_CLASSES) {
      expect(tokens).toContain(preserved)
    }
  })

  it("applies the tier IN PLACE — no wrapper node was inserted around the band", () => {
    render(<SampleDocument />)
    const main = screen.getByRole("main")

    // 35-06 ARM F measured that a wrapper <div> is invisible to every width and
    // count assertion in a suite like this one; only a structural identity check
    // catches it. The shell renders the band as main's sole child, so the band
    // and main.firstElementChild are the same node or the doctrine was broken.
    expect(main.children).toHaveLength(1)
    expect(main.firstElementChild).toBe(
      main.querySelector('[data-width-tier="marketing"]')
    )
  })

  it("PRESERVATION CONTROL: leaves all three reading measures at 68ch — a tier is a ceiling, not a target", () => {
    render(<SampleDocument />)
    const main = screen.getByRole("main")

    // This case passes BEFORE the band moves as well as after, which is what
    // makes it a control rather than a restatement of the change. Widening the
    // band is only safe because the measure is held separately, on three
    // elements nested inside it; if a future edit ever merges the two, this is
    // the assertion that says so.
    const measured = Array.from(
      main.querySelectorAll(`[class*="${PROSE_MEASURE}"]`)
    )
    expect(measured).toHaveLength(3)

    // And none of them is the band itself — a band that acquired the measure
    // would satisfy a bare count while capping the whole page at 68 characters.
    const band = main.firstElementChild as Element
    expect(measured).not.toContain(band)
    expect(measured.every((el) => band.contains(el))).toBe(true)
  })

  it("SCOPE CONTROL: a container-wide tier query is satisfied by the chrome, so scoping to main is load-bearing", () => {
    const { container } = render(<SampleDocument />)
    const main = screen.getByRole("main")

    const all = Array.from(
      container.querySelectorAll('[data-width-tier="marketing"]')
    )
    const outsideMain = all.filter((el) => !main.contains(el))

    // Both shared rails already declare this exact tier (plan 35-06). So a
    // document-wide count returns a non-zero result over a policy page whose
    // band carries nothing — which is precisely how 35-07's header-rail
    // assertion passed against an unmodified tree, satisfied by the footer.
    // This case exists so the scoping above is never "simplified" away.
    expect(outsideMain.length).toBeGreaterThanOrEqual(2)
    expect(
      outsideMain.map(
        (el) => el.closest("header")?.tagName ?? el.closest("footer")?.tagName
      )
    ).toEqual(["HEADER", "FOOTER"])
  })
})

describe("PolicyPage — axe scan with its control (D-13)", () => {
  it(
    "reaches zero violations on a fully rendered document",
    async () => {
      const { container } = render(<SampleDocument />)

      // CONTROL BEFORE THE SCAN. Without it the assertion below is worth
      // nothing: axe returns zero over a tree that never mounted.
      assertDocumentReallyRendered()

      const results = await axe(container)
      expect(results).toHaveNoViolations()
    },
    AXE_TIMEOUT_MS
  )

  it(
    "ARTEFACT DEMONSTRATION: axe still reports zero over an empty document, and the control is what catches it",
    async () => {
      const { container } = render(
        <PolicyPage
          title="Privacy notice"
          lastUpdated={LAST_UPDATED}
          version={VERSION}
          sections={SECTIONS}
        >
          {null}
        </PolicyPage>
      )

      // Half one: axe is perfectly happy. This page has no content at all and
      // the scan is clean, which is precisely why a clean scan is not evidence.
      const results = await axe(container)
      expect(results.violations).toHaveLength(0)

      // Half two: the control fires. It is the ONLY thing between this build and
      // a green suite over a blank policy page.
      expect(() => assertDocumentReallyRendered()).toThrow()

      // And name the specific absence, so this stays a statement about missing
      // content rather than about any incidental throw.
      const main = screen.getByRole("main")
      expect(within(main).queryAllByRole("heading", { level: 2 })).toHaveLength(
        0
      )
    },
    AXE_TIMEOUT_MS
  )
})

describe("/legal index — the Companies House disclosure survived (preserved good)", () => {
  it(
    "still renders the operator identity from getCompanyInfo()",
    async () => {
      const { container } = render(await LegalPage())

      // The four identity terms the page existed for before it became an index.
      expect(screen.getByText("Registered company name")).toBeInTheDocument()
      expect(screen.getByText("J'Toye Digital Ltd")).toBeInTheDocument()
      expect(screen.getByText("Company number")).toBeInTheDocument()
      expect(screen.getByText("16471464")).toBeInTheDocument()
      expect(screen.getByText("Place of registration")).toBeInTheDocument()
      expect(screen.getByText("England & Wales")).toBeInTheDocument()

      // Asserting the ACTIVE number is present is measurably WEAK on this page:
      // the dissolved namesake could be added alongside it and every check above
      // would stay green. Absence is the assertion that can actually fail.
      expect(container.textContent).not.toContain("13434105")
    },
    AXE_TIMEOUT_MS
  )

  it("links all four sibling policy documents", async () => {
    const { container } = render(await LegalPage())

    for (const href of [
      "/legal/privacy",
      "/legal/cookies",
      "/legal/retention",
      "/legal/accessibility",
    ]) {
      expect(container.querySelector(`a[href="${href}"]`)).not.toBeNull()
    }
    // The pre-existing way out is still there.
    expect(container.querySelector('a[href="/"]')).not.toBeNull()
  })

  it("did not shrink its body text", async () => {
    render(await LegalPage())

    // The UI-SPEC caps this in one direction only: the index body may rise from
    // 14px to 16px, it must not shrink. text-sm is 14px, so its absence from the
    // prose is the assertion — and the positive control below proves the query
    // is looking at markup that exists rather than at nothing.
    const main = screen.getByRole("main")
    expect(main.querySelectorAll("p, dd, li").length).toBeGreaterThan(0)
    expect(main.querySelectorAll("p.text-sm, dd.text-sm")).toHaveLength(0)
    expect(main.querySelectorAll("p.text-base, dd.text-slate-600").length)
      .toBeGreaterThan(0)
  })

  it(
    "reaches zero axe violations, with its control asserted first",
    async () => {
      const { container } = render(await LegalPage())

      // Control: the identity list really mounted.
      expect(screen.getByText("16471464")).toBeInTheDocument()
      expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1)

      const results = await axe(container)
      expect(results).toHaveNoViolations()
    },
    AXE_TIMEOUT_MS
  )
})

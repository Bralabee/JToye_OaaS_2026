/**
 * The Legal column — reachability for the five published policy pages (LGL-01).
 *
 * WHY THIS TEST EXISTS AT ALL. Phase 31 published /legal/privacy, /legal/cookies,
 * /legal/retention and /legal/accessibility, and NOTHING in the app linked to any
 * of them. The single in-app link to /legal was in
 * components/platform/company-legal.tsx, which renders on platform surfaces only.
 * Reachability is the difference between a policy being published and a policy
 * existing, and one column in this shared footer supplies it on every public
 * route — including the tenant storefront, which mounts this same component from
 * app/shop/layout.tsx.
 *
 * ASSERTIONS ARE ON RENDERED OUTPUT, NEVER ON THE SOURCE FILE. A grep over a
 * component that MENTIONS "/legal/privacy" in a comment — and the one under test
 * does, twice — passes with the link deleted. Every assertion below goes through
 * the rendered DOM so that deleting a link actually fails it.
 *
 * NOTE ON API: jest's `expect` takes ONE argument. The second-argument message
 * form is Playwright's, and jest rejects it at runtime with "Expect takes at
 * most one argument" — 16 red tests, all of them the harness rather than the
 * component. Intent is carried by test names and comments instead.
 *
 * ── A CORRECTION TO THIS PLAN'S OWN CRITERION, STATED PLAINLY ──
 *
 * 31-17 asked for "the rendered footer contains no 'Companies House' string and
 * no company-number literal", to prove the platform's trading disclosure stays
 * off tenant storefronts. Both halves were measured before being trusted, and
 * NEITHER works as written:
 *
 *  - "Companies House" is 0 in this footer — and is ALSO 0 in CompanyLegalLine,
 *    which renders "…is a company registered in England & Wales (company no.
 *    16471464)" and never says "Companies House". So the plan's own break arm
 *    (add CompanyLegalLine and watch the assertion go red) CANNOT fire it. It is
 *    0 == 0 in both directions: a printed pass with no discriminating power.
 *  - "no company-number literal" is already FALSE on the clean tree. The bottom
 *    row has carried "J'Toye Digital Ltd · Registered in England & Wales ·
 *    company no. 16471464" since e484b96a (PR #232), which predates this phase.
 *
 * Replaced with two assertions that are falsifiable in both directions, and the
 * pre-existing state is PINNED rather than silently accepted:
 *
 *  1. The prose unique to CompanyLegalLine ("is a company registered in") must
 *     be absent — 0 clean, 1 the moment that component is mounted here.
 *  2. The company number must appear EXACTLY ONCE. Two occurrences means a
 *     second disclosure was added; zero means the existing Companies Act
 *     copyright line was deleted. Both are failures, in opposite directions.
 *
 * The underlying conflict — lib/company.ts:9-12 says this identity must render
 * "never on tenant storefronts", while this footer renders it on all of them —
 * is PRE-EXISTING, out of this plan's scope, and is raised as an owner question
 * in 31-17-SUMMARY.md rather than fixed here.
 */
import { render, screen, waitFor, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import { usePathname } from "next/navigation"
import { PublicFooter } from "@/components/public/public-footer"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedPathname = usePathname as jest.Mock
const mockedSession = getCustomerSession as jest.Mock

const SIGNED_IN = {
  profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
  expiresAt: Math.floor(Date.now() / 1000) + 300,
}

/**
 * Every published policy route, with the label the rest of the app uses for it.
 *
 * A PLAIN ARRAY LITERAL BOUND TO A BARE NAME, deliberately. scripts/
 * count-test-blocks.mjs resolves an `.each` table only when it is an array
 * literal declared in the same file, and it FAILS CLOSED otherwise rather than
 * miscounting. `it.each([...LEGAL_ROUTES])` — a spread — is not resolvable, and
 * it left the static count 4 short of what jest executed (1226 vs 1230), which
 * check-test-count-oracle.sh caught.
 */
const LEGAL_ROUTES: Array<{ href: string; label: RegExp }> = [
  { href: "/legal", label: /^legal & company information$/i },
  { href: "/legal/privacy", label: /^privacy notice$/i },
  { href: "/legal/cookies", label: /^cookie and browser-storage policy$/i },
  { href: "/legal/retention", label: /^data retention schedule$/i },
  { href: "/legal/accessibility", label: /^accessibility statement$/i },
]

/** The company number is a fixed business fact (lib/company.ts:41), not env. */
const COMPANY_NUMBER = "16471464"

beforeEach(() => {
  mockedPathname.mockReturnValue("/shop/test-kitchen")
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
})

/**
 * NON-VACUITY CONTROL, run first in every test below that asserts an ABSENCE.
 * A component that threw during render, or one whose session mock left it
 * empty, satisfies every "is not in the document" assertion perfectly.
 *
 * Throws (rather than returning null) when the footer is not there: getByRole
 * is the assertion, so a missing footer or a missing known-good link fails the
 * test at this line instead of silently licensing everything after it.
 */
function expectFooterActuallyRendered(): HTMLElement {
  const footer = screen.getByRole("contentinfo")
  expect(within(footer).getByRole("link", { name: /browse shops/i })).toHaveAttribute(
    "href",
    "/shop"
  )
  return footer
}

describe("PublicFooter Legal column — the five policy pages are reachable (LGL-01)", () => {
  it("renders a Legal column headed at level 2", () => {
    render(<PublicFooter />)
    expectFooterActuallyRendered()

    // Level 2 deliberately: 31-03 moved the sibling column headings up from
    // level 3 to close axe's heading-order on pages that supply no level 2 of
    // their own (/shop/signin, /legal). A level-3 heading here reintroduces it.
    expect(screen.getByRole("heading", { level: 2, name: /^legal$/i })).toBeInTheDocument()
  })

  // Asserted one route at a time, not as a set: a grouped assertion reports
  // "expected 5, got 4" and leaves you to work out which one went.
  //
  // `it.each`, NOT `it(` inside a for-loop. A loop is ONE declaration site and N
  // executed tests, and this repo counts both: scripts/docs-freshness.sh counts
  // sites, scripts/check-test-count-oracle.sh counts what jest ran, and both are
  // required checks — so a loop makes docs/metrics.json unsatisfiable and
  // docs-freshness exits 2 (VOID) rather than guessing. `it.each` is counted
  // identically by both halves. Found by the gate, not by review.
  it.each(LEGAL_ROUTES)("links $href with a crawlable anchor", ({ href, label }) => {
    const { container } = render(<PublicFooter />)
    expectFooterActuallyRendered()

    expect(screen.getByRole("link", { name: label })).toHaveAttribute("href", href)

    // A real <a href>, not a button or a click handler. LGL-01 is about a
    // crawler, and a crawler does not run onClick.
    expect(container.querySelector(`a[href="${href}"]`)).toBeInTheDocument()
  })

  it("emits every legal link in the FIRST render, before the session resolves", () => {
    // No await, session deliberately never settles: this is the render a
    // crawler receives and the one the link graph is judged on.
    mockedSession.mockReturnValue(new Promise(() => {}))
    const { container } = render(<PublicFooter />)

    const found = LEGAL_ROUTES.filter((r) => container.querySelector(`a[href="${r.href}"]`)).map(
      (r) => r.href
    )
    expect(found).toEqual(LEGAL_ROUTES.map((r) => r.href))
  })

  it("keeps every legal link for a signed-in customer on a storefront", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    const { container } = render(<PublicFooter />)

    // Wait for the post-resolution marker first. The first render is always the
    // guest one, so a bare assertion here would pass whatever the gate did.
    await screen.findByRole("link", { name: /^my orders$/i })

    const found = LEGAL_ROUTES.filter((r) => container.querySelector(`a[href="${r.href}"]`)).map(
      (r) => r.href
    )
    expect(found).toEqual(LEGAL_ROUTES.map((r) => r.href))
  })
})

describe("PublicFooter heading order is intact (F-C, 31-03)", () => {
  it("puts every column heading at level 2 and none at level 3", () => {
    render(<PublicFooter />)
    const footer = expectFooterActuallyRendered()

    // Brand carries no heading; For customers + Legal + For operators do.
    expect(within(footer).getAllByRole("heading", { level: 2 }).length).toBeGreaterThanOrEqual(3)
    // A level-3 heading here is the skip 31-03 closed.
    expect(within(footer).queryAllByRole("heading", { level: 3 })).toHaveLength(0)
    expect(within(footer).queryAllByRole("heading", { level: 4 })).toHaveLength(0)
  })
})

describe("PublicFooter OGL attribution survives the Legal column", () => {
  // A licence obligation, not a credit: all three lines are required and
  // scripts/check-geo-attribution.sh reads this component for them. That gate
  // exits 2 when it cannot find the footer, and a VOID reads exactly like a
  // missing footer — so the lines are pinned here too, in the rendered output.
  //
  // `it.each` for the same counting reason as above.
  it.each(["Ordnance Survey data", "Royal Mail data", "National Statistics data"])(
    'still renders the "%s" attribution',
    (line) => {
      render(<PublicFooter />)
      const footer = expectFooterActuallyRendered()
      expect(footer.textContent).toContain(line)
    }
  )
})

describe("PublicFooter carries NO platform trading disclosure (T-31-17-02)", () => {
  /**
   * This footer renders on /shop/[slug] via app/shop/layout.tsx, so anything
   * added here appears under a vendor's brand. lib/company.ts:9-12: the platform
   * operator's identity belongs on platform surfaces only.
   */
  it("does not render CompanyLegalLine's disclosure prose", () => {
    render(<PublicFooter />)
    const footer = expectFooterActuallyRendered()

    // The discriminator. CompanyLegalLine's exact wording, absent here and
    // present the instant that component is mounted into this footer — unlike
    // the string "Companies House", which neither renders (see file header).
    expect(footer.textContent).not.toMatch(/is a company registered in/i)
    expect(footer.textContent).not.toMatch(/registered office/i)
    expect(footer.textContent).not.toMatch(/companies house/i)
  })

  it("carries the company number exactly once — the existing copyright line, and no second copy", () => {
    render(<PublicFooter />)
    const footer = expectFooterActuallyRendered()

    const hits = (footer.textContent ?? "").split(COMPANY_NUMBER).length - 1
    // 1, not 0: the bottom row's Companies Act copyright line predates this
    // phase (e484b96a / PR #232) and deleting it is a regression, not a fix.
    // 2 would mean a second disclosure was added — which is the threat.
    expect(hits).toBe(1)
  })

  it("keeps the Legal column itself free of any company identity", () => {
    render(<PublicFooter />)
    expectFooterActuallyRendered()

    // Scoped to the column this plan added, so it fails for THIS change
    // specifically and not for the pre-existing bottom row.
    const heading = screen.getByRole("heading", { level: 2, name: /^legal$/i })
    const column = heading.closest("div")
    expect(column).not.toBeNull()

    const text = column?.textContent ?? ""
    // Non-vacuity: the column must have content before absences mean anything.
    expect(text).toContain("Privacy notice")
    expect(text).not.toContain(COMPANY_NUMBER)
    expect(text).not.toMatch(/registered in/i)
    expect(text).not.toMatch(/companies house/i)
  })
})

describe("PublicFooter columns do not move when the session resolves", () => {
  /**
   * The layout invariant the original three-track comment was written to
   * protect, re-proved after the track count changed to four. The operator
   * column is the only conditional one; grid auto-flow pulls every LATER item
   * forward when it unmounts, so Legal has to sit ahead of it. Put Legal fourth
   * and it jumps a whole track the moment a customer's session resolves.
   */
  async function columnOrder(signedIn: boolean): Promise<string[]> {
    mockedSession.mockResolvedValue(signedIn ? SIGNED_IN : null)
    const { container, unmount } = render(<PublicFooter />)
    if (signedIn) {
      await screen.findByRole("link", { name: /^my orders$/i })
    } else {
      await waitFor(() =>
        expect(screen.getByRole("link", { name: /^track order$/i })).toBeInTheDocument()
      )
    }

    const order = Array.from(container.querySelectorAll("h2")).map((h) =>
      (h.textContent ?? "").trim()
    )
    unmount()
    return order
  }

  it("holds the Legal column at the same index whether or not the operator column shows", async () => {
    const anonymous = await columnOrder(false)
    const customer = await columnOrder(true)

    // Control: the two states genuinely differ, or this proves nothing.
    expect(anonymous).toContain("For operators")
    expect(customer).not.toContain("For operators")

    expect(anonymous.indexOf("Legal")).toBeGreaterThanOrEqual(0)
    // Legal must NOT shift a grid track when the operator column unmounts.
    expect(customer.indexOf("Legal")).toBe(anonymous.indexOf("Legal"))

    // And the conditional column really is last where it is rendered.
    expect(anonymous[anonymous.length - 1]).toBe("For operators")
  })
})

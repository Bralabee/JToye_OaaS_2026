/**
 * A11Y-06 (skip link) and F-C (footer heading level) on the shared public shell.
 *
 * WHY THIS TEST EXISTS AT ALL. axe reports no violation for a missing skip link —
 * WCAG 2.4.1 Bypass Blocks has no automatable signature — so the gate 31-18 builds
 * cannot close A11Y-06 and never could. It has to be pinned at source, here.
 *
 * WHAT IT REFUSES TO ASSERT. "A link with href='#main' exists" is the assertion
 * this test is not allowed to stop at: a skip link rendered AFTER the header is
 * present, is announced, and is still useless, because the user has already
 * tabbed through the chrome by the time they reach it. So document ORDER is
 * asserted, not presence — and the break arm recorded in the SUMMARY moves the
 * link below <PublicHeader/> to prove the assertion can actually fail there.
 *
 * NON-VACUITY. Every heading assertion below sits behind a control that proves
 * the footer rendered at all. A `getAllByRole("heading", { level: 2 })` over an
 * unrendered tree returns [] and a `queryAllByRole(... level: 3)` returns [] too
 * — so "no h3s" and "the component never mounted" are the SAME observation
 * unless something positive is asserted first. That is the measured artefact
 * class in this repo (a "0 button-name violations" pass where the tables had
 * never mounted), and it is why the control comes first rather than last.
 */
import { render, screen, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import { PublicShell } from "@/components/public/public-shell"
import { getCustomerSession } from "@/lib/customer-auth"

// Anonymous visitor: both footer columns render, which is the state that has the
// most headings to check. Mocked rather than left to the real fetch so the arm is
// deterministic — the hook polls on an interval and the unmocked path resolves
// through a rejected fetch.
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
})

describe("PublicShell skip link (A11Y-06)", () => {
  it("renders a skip link that is the FIRST link in document order", () => {
    const { container } = render(
      <PublicShell>
        <p>Page body</p>
      </PublicShell>
    )

    const links = Array.from(container.querySelectorAll("a"))
    // Control: the shell really rendered its chrome, so an empty NodeList below
    // would be a failure rather than a silent pass.
    expect(links.length).toBeGreaterThan(1)

    const first = links[0]
    expect(first).toHaveAttribute("href", "#main")
    expect(first).toHaveTextContent(/skip to main content/i)

    // The bug shape this catches: a skip link that exists but sits after the
    // header. Presence alone would be green on exactly that build.
    const skipIndex = links.findIndex((a) => a.getAttribute("href") === "#main")
    expect(skipIndex).toBe(0)
  })

  it("hides the skip link until it is focused", () => {
    const { container } = render(
      <PublicShell>
        <p>Page body</p>
      </PublicShell>
    )

    const skip = container.querySelector('a[href="#main"]')
    expect(skip).toBeInTheDocument()
    // Both halves matter: sr-only alone would leave it permanently invisible even
    // to the keyboard user it is for; focus:not-sr-only alone would park a
    // permanently visible pill over the header.
    expect(skip).toHaveClass("sr-only")
    expect(skip).toHaveClass("focus:not-sr-only")
  })

  it("targets exactly one main landmark carrying id='main'", () => {
    render(
      <PublicShell>
        <p>Page body</p>
      </PublicShell>
    )

    // getAllByRole, not getByRole: a SECOND <main> is the regression a singular
    // query would report as a generic "found multiple" failure rather than as the
    // landmark-one-main violation it actually is.
    const mains = screen.getAllByRole("main")
    expect(mains).toHaveLength(1)
    expect(mains[0]).toHaveAttribute("id", "main")
    // The link and the landmark must agree — an id typo makes the skip link a
    // no-op that still passes every presence assertion above.
    const skip = screen.getByRole("link", { name: /skip to main content/i })
    expect(skip.getAttribute("href")).toBe(`#${mains[0].getAttribute("id")}`)
    // The page's own content is inside the landmark it points at.
    expect(within(mains[0]).getByText("Page body")).toBeInTheDocument()
  })
})

describe("PublicFooter heading levels (F-C)", () => {
  it("renders every footer column heading at level 2 and none at level 3", () => {
    render(
      <PublicShell>
        <p>Page body</p>
      </PublicShell>
    )

    // NON-VACUITY CONTROL, asserted BEFORE any heading query. The footer's OGL
    // attribution line is unique to this component, so its presence proves the
    // subtree under test actually mounted. Without it, "zero h3 headings" and
    // "nothing rendered" are indistinguishable.
    expect(
      screen.getByText(/Contains Ordnance Survey data/i)
    ).toBeInTheDocument()
    expect(screen.getByRole("contentinfo")).toBeInTheDocument()

    const footer = screen.getByRole("contentinfo")
    const h2s = within(footer).getAllByRole("heading", { level: 2 })
    expect(h2s.length).toBeGreaterThanOrEqual(2)
    expect(h2s.map((h) => h.textContent?.trim())).toEqual(
      expect.arrayContaining(["For customers", "For operators"])
    )

    // The level skip itself. The shell supplies no h1 and pages such as
    // /shop/signin and /legal supply no h2 of their own, so an h3 here jumps
    // straight from h1 to h3 and fires axe's heading-order.
    const h3s = within(footer).queryAllByRole("heading", { level: 3 })
    expect(h3s).toHaveLength(0)
  })
})

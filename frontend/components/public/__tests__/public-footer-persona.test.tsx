/**
 * Issue #458 (items 1a, 2) — the LAST surface still showing the operator door to
 * a signed-in customer.
 *
 * PR #508 persona-gated both headers. It deliberately left the footer alone, on
 * the reasoning that /for-operators and /track had to stay reachable somewhere.
 * That reasoning is right and is preserved below — but it left the reported
 * defect live on the exact page the report named. `app/shop/layout.tsx:73` and
 * `components/public/public-shell.tsx:19` both render <PublicFooter/>, so a
 * signed-in customer sitting on their own profile at /shop/orders scrolls down
 * to a column literally headed "For operators", with "Become a vendor" and
 * "Vendor sign in" under it, and an ungated "Track order" beside it.
 *
 * The gate is SECURITY-SHAPED even though nothing here is secret: a test that
 * only asserted the hiding half would pass on a build that hid the operator door
 * from EVERYONE, which is a worse regression than the bug. So every hiding
 * assertion below is paired with a control that proves the door is still open —
 * for an anonymous visitor anywhere, and for a signed-in customer who has
 * deliberately walked onto an operator surface.
 *
 * Assertions are on rendered links and hrefs, never on props: the whole failure
 * mode is "the component renders something the persona should not see".
 */
import { render, screen, waitFor } from "@testing-library/react"
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
 * Timing, stated because it decides what each arm can prove.
 *
 * The session resolves a tick after mount, so the FIRST render is always the
 * guest one. An arm that asserts guest content synchronously therefore passes
 * whether the gate exists or not — fine for the anonymous controls (their state
 * never changes) but vacuous for a signed-in arm. Every signed-in arm below
 * consequently waits for a post-resolution signal first.
 */
beforeEach(() => {
  mockedPathname.mockReturnValue("/shop/orders")
  mockedSession.mockReset()
})

describe("PublicFooter persona gating — signed-in customer (#458 items 1a, 2)", () => {
  it("does not render the 'For operators' column on the customer's own profile page", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    render(<PublicFooter />)

    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: /^for operators$/i })).not.toBeInTheDocument()
    )
    expect(screen.queryByRole("link", { name: /become a vendor/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /vendor sign in/i })).not.toBeInTheDocument()
  })

  it("routes no footer link to the operator identity realm for a signed-in customer", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    const { container } = render(<PublicFooter />)

    // Destination-level, not label-level: the #382 lesson is that two different
    // labels resolving to one href is the shape this class of bug takes.
    await waitFor(() =>
      expect(container.querySelectorAll('a[href="/auth/signin"]')).toHaveLength(0)
    )
    expect(container.querySelectorAll('a[href="/for-operators"]')).toHaveLength(0)
  })

  it("replaces the guest 'Track order' lookup with the customer's own orders", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    render(<PublicFooter />)

    // Not simply deleted — the profile route replaces it, and tracking lives one
    // tap behind an order card there. Nothing is lost for someone who HAS orders,
    // and nothing is dangled in front of someone who has none.
    expect(await screen.findByRole("link", { name: /^my orders$/i })).toHaveAttribute(
      "href",
      "/shop/orders"
    )
    // The reported item: a standalone tracking destination that demands an order
    // number, offered to someone whose orders the system already knows.
    expect(screen.queryByRole("link", { name: /^track order$/i })).not.toBeInTheDocument()
    // The customer half of the footer is not gutted.
    expect(screen.getByRole("link", { name: /browse shops/i })).toHaveAttribute("href", "/shop")
  })
})

/**
 * The controls. These are written to pass on the PRE-fix tree as well as the
 * post-fix one — a control arm that was already red proves nothing about what
 * the change preserved. The anonymous arms therefore assert synchronously, with
 * no wait: for a visitor with no session the footer's state never changes, so
 * there is no later render for the assertion to miss.
 */
describe("PublicFooter CONTROL — the operator door is still open", () => {
  it("anonymous visitor gets the whole operator column, unchanged", async () => {
    mockedSession.mockResolvedValue(null)
    render(<PublicFooter />)

    expect(screen.getByRole("heading", { name: /^for operators$/i })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /become a vendor/i })).toHaveAttribute(
      "href",
      "/for-operators"
    )
    expect(screen.getByRole("link", { name: /vendor sign in/i })).toHaveAttribute(
      "href",
      "/auth/signin"
    )
    expect(screen.getByRole("link", { name: /business model guide/i })).toHaveAttribute(
      "href",
      "/business-model-guide"
    )
    expect(screen.getByRole("link", { name: /how we compare/i })).toHaveAttribute(
      "href",
      "/competitive"
    )
  })

  it("anonymous visitor still gets the guest 'Track order' lookup", async () => {
    mockedSession.mockResolvedValue(null)
    render(<PublicFooter />)

    expect(screen.getByRole("link", { name: /^track order$/i })).toHaveAttribute("href", "/track")
    expect(screen.queryByRole("link", { name: /^my orders$/i })).not.toBeInTheDocument()
  })

  /*
   * These last two arms are POST-FIX ONLY, said plainly rather than dressed up
   * as controls that held all along. They wait on "My orders", which is the
   * signal that the session has resolved AND the new gate has run — neither
   * exists on the pre-fix tree, so both are red there. Their falsifiability is
   * proven by a break arm instead (delete the operator-surface exemption and
   * they go red on the FIXED tree); recorded in the PR body.
   */
  it("a signed-in customer standing ON /for-operators still gets the operator column", async () => {
    // The one case where hiding it would be the regression: they navigated to the
    // operator pitch on purpose. Removing "Vendor sign in" from the footer of the
    // page whose entire job is recruiting vendors would be a worse bug than #458.
    mockedPathname.mockReturnValue("/for-operators")
    mockedSession.mockResolvedValue(SIGNED_IN)
    render(<PublicFooter />)

    // Waiting on the post-resolution marker first is what stops this being
    // vacuous: the first render is always the guest one, so a bare assertion
    // would pass even if the gate then wrongly hid the column.
    await screen.findByRole("link", { name: /^my orders$/i })

    expect(screen.getByRole("heading", { name: /^for operators$/i })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /vendor sign in/i })).toHaveAttribute(
      "href",
      "/auth/signin"
    )
  })

  it("...and on the operator surfaces below it (/business-model-guide, /competitive)", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    for (const surface of ["/business-model-guide", "/competitive"]) {
      mockedPathname.mockReturnValue(surface)
      const { unmount } = render(<PublicFooter />)
      await screen.findByRole("link", { name: /^my orders$/i })
      expect(screen.getByRole("link", { name: /become a vendor/i })).toBeInTheDocument()
      unmount()
    }
  })
})

/**
 * Server-side crawlability. The footer is the second inbound link to
 * /for-operators and the only one to /business-model-guide (backlog #5), so the
 * gate must not cost the link graph. The session resolves asynchronously and is
 * null on the first render — which is exactly the render a crawler receives —
 * so the operator links are present in the initial output. Asserted rather than
 * assumed, because "SEO is fine" is the kind of claim that is never checked.
 */
describe("PublicFooter link graph is intact on first paint (SEO)", () => {
  it("emits the operator links before the session resolves", () => {
    // No await: assert the synchronous first render, session still unresolved.
    mockedSession.mockReturnValue(new Promise(() => {}))
    const { container } = render(<PublicFooter />)

    expect(container.querySelector('a[href="/for-operators"]')).toBeInTheDocument()
    expect(container.querySelector('a[href="/business-model-guide"]')).toBeInTheDocument()
    expect(container.querySelector('a[href="/competitive"]')).toBeInTheDocument()
    expect(container.querySelector('a[href="/track"]')).toBeInTheDocument()
  })
})

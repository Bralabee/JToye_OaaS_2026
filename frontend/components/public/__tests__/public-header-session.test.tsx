/**
 * Issue #457 — the public header must not be session-blind.
 *
 * StorefrontNav (mounted only by app/shop/layout.tsx) was the sole component
 * that rendered customer session state. `/`, `/track` and the marketing surfaces
 * render PublicShell -> PublicHeader, which had zero session references — so
 * navigating home showed "Sign in" to a customer who was still signed in.
 * Browser-verified: the session survived the navigation intact; only the header
 * could not see it.
 *
 * These assert the RENDERED result, not props: a component test that stubbed the
 * session shape rather than the seam would pass over exactly this bug.
 */
import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import { usePathname } from "next/navigation"
import { PublicHeader } from "@/components/public/public-header"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedPathname = usePathname as jest.Mock
const mockedSession = getCustomerSession as jest.Mock

beforeEach(() => {
  mockedPathname.mockReturnValue("/")
  mockedSession.mockReset()
})

describe("PublicHeader customer session awareness", () => {
  it("shows the customer name on / when signed in, not a Sign in button", async () => {
    mockedSession.mockResolvedValue({
      profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
      expiresAt: Math.floor(Date.now() / 1000) + 300,
    })

    render(<PublicHeader />)

    expect(await screen.findByText("Alice")).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByRole("link", { name: /^Sign in$/i })).not.toBeInTheDocument()
    )
  })

  it("shows My Orders on / when signed in — the storefront affordance reaches the public surface", async () => {
    mockedSession.mockResolvedValue({
      profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
      expiresAt: Math.floor(Date.now() / 1000) + 300,
    })

    render(<PublicHeader />)

    const link = await screen.findByRole("link", { name: /My Orders/i })
    expect(link).toHaveAttribute("href", "/shop/orders")
  })

  it("offers a sign-out control on the public surface, so signing out is reachable from anywhere", async () => {
    mockedSession.mockResolvedValue({
      profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
      expiresAt: Math.floor(Date.now() / 1000) + 300,
    })

    render(<PublicHeader />)

    expect(await screen.findByTitle("Sign out")).toBeInTheDocument()
  })

  it("falls back to the email when the profile carries no name", async () => {
    mockedSession.mockResolvedValue({
      profile: { sub: "u1", email: "alice@example.com", name: "", emailVerified: true },
      expiresAt: Math.floor(Date.now() / 1000) + 300,
    })

    render(<PublicHeader />)

    expect(await screen.findByText("alice@example.com")).toBeInTheDocument()
  })

  it("still shows Sign in for an anonymous visitor — the logged-out header is unchanged", async () => {
    mockedSession.mockResolvedValue(null)

    render(<PublicHeader />)

    await waitFor(() => expect(mockedSession).toHaveBeenCalled())
    expect(screen.getByRole("link", { name: /Sign in/i })).toHaveAttribute(
      "href",
      "/shop/signin"
    )
    expect(screen.queryByRole("link", { name: /My Orders/i })).not.toBeInTheDocument()
  })

  it("reads the session on /track too — the surface the report named", async () => {
    mockedPathname.mockReturnValue("/track")
    mockedSession.mockResolvedValue({
      profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
      expiresAt: Math.floor(Date.now() / 1000) + 300,
    })

    render(<PublicHeader />)

    expect(await screen.findByText("Alice")).toBeInTheDocument()
  })
})

/**
 * Issue #458 — the same persona gate as StorefrontNav, on the shared header.
 *
 * Fixing only /shop would have recreated the exact split #457 exists to prevent:
 * a signed-in shopper clicking the wordmark lands on `/`, which renders THIS
 * component, and would have met the operator pitch again one click later.
 */
describe("PublicHeader persona gating (#458)", () => {
  const SIGNED_IN = {
    profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
    expiresAt: Math.floor(Date.now() / 1000) + 300,
  }

  it("hides 'For operators' from a signed-in customer on the desktop row", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    render(<PublicHeader />)
    expect(await screen.findByText("Alice")).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /^for operators$/i })).not.toBeInTheDocument()
  })

  it("hides the standalone 'Track order' guest lookup from a signed-in customer", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    render(<PublicHeader />)
    expect(await screen.findByText("Alice")).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /^track order$/i })).not.toBeInTheDocument()
  })

  it("hides both inside the mobile sheet too — a separate code path", async () => {
    mockedSession.mockResolvedValue(SIGNED_IN)
    render(<PublicHeader />)
    expect(await screen.findByText("Alice")).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))

    expect(screen.queryByRole("link", { name: /^for operators$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /^track order$/i })).not.toBeInTheDocument()
    // ...while the customer's own door is still there.
    expect(screen.getByRole("link", { name: /my orders/i })).toHaveAttribute(
      "href",
      "/shop/orders"
    )
  })

  it("CONTROL: an anonymous visitor still gets both doors, desktop and sheet", async () => {
    mockedSession.mockResolvedValue(null)
    render(<PublicHeader />)
    await waitFor(() => expect(mockedSession).toHaveBeenCalled())

    expect(screen.getByRole("link", { name: /^for operators$/i })).toHaveAttribute(
      "href",
      "/for-operators"
    )
    expect(screen.getByRole("link", { name: /^track order$/i })).toHaveAttribute(
      "href",
      "/track"
    )

    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    expect(screen.getByRole("link", { name: /^for operators$/i })).toHaveAttribute(
      "href",
      "/for-operators"
    )
  })
})

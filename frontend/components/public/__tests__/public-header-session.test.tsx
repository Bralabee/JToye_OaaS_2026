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
import { render, screen, waitFor } from "@testing-library/react"
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

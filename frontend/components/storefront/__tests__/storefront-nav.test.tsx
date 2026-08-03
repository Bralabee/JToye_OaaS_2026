/**
 * Tests for StorefrontNav (debug: mobile-nav-operators-hidden; issue #458).
 *
 * Contract, in two halves — the same destinations, gated by persona:
 *
 * SIGNED OUT, the /shop storefront header must expose the full public
 * destination set — including /for-operators — at every breakpoint:
 *  - an inline "For operators" link for >=sm viewports
 *  - a hamburger menu control (aria-label "Open menu") for <sm viewports
 *    whose sheet contains Shops / For operators / Track order (labels match
 *    PublicHeader — the same destination must not be named differently per surface).
 * Regression guard for the Phase 19 defect where /shop shipped with no
 * mobile menu at all, leaving /for-operators reachable only via the
 * below-fold footer on a 390px viewport.
 *
 * SIGNED IN as a customer (#458), neither "For operators" nor the guest
 * "Track order" lookup is rendered — on EITHER code path. The desktop row and
 * the mobile sheet are separate JSX, so each is asserted separately; a
 * desktop-only fix would leave the link live on the viewport where the defect
 * was actually reported. "My Orders" replaces them as the customer's door.
 *
 * These jsdom assertions are a fast guard, not the proof: the shipped
 * behaviour is verified in a real browser at 1280px and 375px, signed in and
 * signed out, because a jsdom render cannot tell a hidden mobile sheet from an
 * absent one.
 */

import { render, screen, fireEvent, act } from "@testing-library/react"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

async function renderNav() {
  render(<StorefrontNav />)
  // Flush the mount-time session check (getCustomerSession promise).
  await act(async () => {})
}

function signedIn() {
  mockedSession.mockResolvedValue({
    profile: { email: "shopper@example.test", name: "Shopper" },
  })
}

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
})

describe("StorefrontNav (shop storefront header) — signed OUT", () => {
  it("renders an inline 'For operators' link to /for-operators (desktop parity with PublicHeader)", async () => {
    await renderNav()
    const link = screen.getByRole("link", { name: /^for operators$/i })
    expect(link.getAttribute("href")).toBe("/for-operators")
  })

  it("renders a mobile menu control (hamburger with aria-label 'Open menu')", async () => {
    await renderNav()
    expect(screen.getByRole("button", { name: /open menu/i })).toBeTruthy()
  })

  it("opens a sheet exposing all public destinations including For operators", async () => {
    await renderNav()
    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))

    // Radix modal aria-hides everything outside the portal while open, so the
    // accessible tree now contains exactly the SHEET's links — asserting these
    // proves the sheet itself carries the destinations.
    const operatorLink = screen.getByRole("link", { name: /^for operators$/i })
    expect(operatorLink.getAttribute("href")).toBe("/for-operators")

    const trackLink = screen.getByRole("link", { name: /^track order$/i })
    expect(trackLink.getAttribute("href")).toBe("/track")

    const shopsLink = screen.getByRole("link", { name: /^shops$/i })
    expect(shopsLink.getAttribute("href")).toBe("/shop")
  })
})

describe("StorefrontNav (shop storefront header) — signed IN as a customer (#458)", () => {
  it("does not render 'For operators' on the desktop row", async () => {
    signedIn()
    await renderNav()
    expect(screen.queryByRole("link", { name: /^for operators$/i })).toBeNull()
  })

  it("does not render the standalone 'Track order' lookup on the desktop row", async () => {
    signedIn()
    await renderNav()
    expect(screen.queryByRole("link", { name: /^track order$/i })).toBeNull()
  })

  it("does not render 'For operators' or 'Track order' inside the mobile sheet either", async () => {
    signedIn()
    await renderNav()
    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    expect(screen.queryByRole("link", { name: /^for operators$/i })).toBeNull()
    expect(screen.queryByRole("link", { name: /^track order$/i })).toBeNull()
  })

  it("still offers 'My Orders' — the customer's door to tracking — on both paths", async () => {
    signedIn()
    await renderNav()
    expect(
      screen.getByRole("link", { name: /my orders/i }).getAttribute("href")
    ).toBe("/shop/orders")

    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    expect(
      screen.getByRole("link", { name: /my orders/i }).getAttribute("href")
    ).toBe("/shop/orders")
  })
})

/**
 * Tests for StorefrontNav (debug: mobile-nav-operators-hidden).
 *
 * Contract: the /shop storefront header must expose the full public
 * destination set — including /for-operators — at every breakpoint:
 *  - an inline "For operators" link for >=sm viewports
 *  - a hamburger menu control (aria-label "Open menu") for <sm viewports
 *    whose sheet contains Browse shops / For operators / Track order.
 *
 * Regression guard for the Phase 19 defect where /shop shipped with no
 * mobile menu at all, leaving /for-operators reachable only via the
 * below-fold footer on a 390px viewport.
 */

import { render, screen, fireEvent, act } from "@testing-library/react"
import { StorefrontNav } from "@/components/storefront/storefront-nav"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

async function renderNav() {
  render(<StorefrontNav />)
  // Flush the mount-time session check (getCustomerSession promise).
  await act(async () => {})
}

describe("StorefrontNav (shop storefront header)", () => {
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

    const browseLink = screen.getByRole("link", { name: /^browse shops$/i })
    expect(browseLink.getAttribute("href")).toBe("/shop")
  })
})

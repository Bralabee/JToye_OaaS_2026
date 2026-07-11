/**
 * Tests for the public landing page (UIX-01, backlog #4).
 *
 * Contract:
 *  - `/` renders a real server-rendered landing page (NO redirect), with a
 *    split-persona heading that names BOTH audiences.
 *  - A customer door links `/shop`; an operator door links `/for-operators`.
 *  - The shared public header + footer render on `/`.
 *  - The page is a Server Component (no "use client") so the CSP nonce reaches
 *    it — asserted structurally against the source file (the #89 failure mode).
 */

import fs from "fs"
import path from "path"
import { render, screen } from "@testing-library/react"
import Home from "@/app/page"

describe("Public landing page (/)", () => {
  it("renders a split-persona H1 naming both audiences (no redirect)", () => {
    render(<Home />)
    const h1 = screen.getByRole("heading", { level: 1 })
    // Names both audiences: local kitchens (customer) + running one (operator).
    expect(h1.textContent).toMatch(/kitchen/i)
    expect(h1.textContent).toMatch(/run/i)
  })

  it("routes the customer door to /shop", () => {
    render(<Home />)
    const customerDoor = screen.getByRole("link", {
      name: /order food near you/i,
    })
    expect(customerDoor.getAttribute("href")).toBe("/shop")
  })

  it("routes the operator door to /for-operators", () => {
    render(<Home />)
    const operatorDoor = screen.getByRole("link", {
      name: /run your food business/i,
    })
    expect(operatorDoor.getAttribute("href")).toBe("/for-operators")
  })

  it("renders the shared public header (persona nav)", () => {
    render(<Home />)
    // Header nav exposes the four public routes; "For operators" is header-only
    // copy (the footer says "Become a vendor"), so it proves the header rendered.
    const headerLink = screen.getByRole("link", { name: /^for operators$/i })
    expect(headerLink.getAttribute("href")).toBe("/for-operators")
  })

  it("renders the shared public footer (allergen note + guide link)", () => {
    render(<Home />)
    expect(
      screen.getByText(/allergen info available on all products/i)
    ).toBeTruthy()
    const guideLink = screen.getByRole("link", {
      name: /business model guide/i,
    })
    expect(guideLink.getAttribute("href")).toBe("/business-model-guide")
  })

  it("is a Server Component — no client directive in the source", () => {
    const src = fs.readFileSync(
      path.join(process.cwd(), "app/page.tsx"),
      "utf8"
    )
    expect(src).not.toMatch(/["']use client["']/)
    // And it must NOT blind-redirect any more.
    expect(src).not.toMatch(/redirect\(["']\/dashboard["']\)/)
  })
})

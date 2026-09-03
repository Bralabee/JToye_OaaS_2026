/**
 * FE-2 — the global 404 is not a dead end.
 *
 * Measured in the browser at 1280 and 390 before the fix: Next's built-in page,
 * `querySelectorAll("header").length === 0`, footer 0, nav 0, and the single
 * anchor in the document was `/legal/cookies` from the root layout's cookie
 * notice. The counters return 1 / 1 / 12+ on every other public route in
 * `evidence/fe-public-sweep.json`, so a zero was a statement about this page and
 * not about the instrument.
 *
 * These are the SAME counters the browser probe uses (`probes/fe-404-deadend.js`),
 * asserted here at the source. What jest CANNOT see is the HTTP status — a
 * soft-404 (200 with this body) would trade FE-2 for FE-12 — so that half stays
 * with the browser arm and is not claimed here.
 */
import { render, screen } from "@testing-library/react"
import "@testing-library/jest-dom"
import NotFound, { metadata } from "@/app/not-found"
import { getCustomerSession } from "@/lib/customer-auth"

// Anonymous visitor, mirroring components/public/__tests__/public-shell-landmarks.test.tsx:
// the session hook polls, and the unmocked path resolves through a rejected fetch.
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
})

describe("app/not-found.tsx keeps the site chrome (FE-2)", () => {
  it("renders header, footer and nav — the three counters the live page returned 0 for", () => {
    const { container } = render(<NotFound />)

    // Control first: if the shell never mounted, every count below would be 0
    // and "0 headers" would be indistinguishable from "nothing rendered".
    expect(container.querySelectorAll("a").length).toBeGreaterThan(1)

    expect(container.querySelectorAll("header")).toHaveLength(1)
    expect(container.querySelectorAll("footer")).toHaveLength(1)
    expect(container.querySelectorAll("nav").length).toBeGreaterThanOrEqual(1)
  })

  it("offers a way out to the three destinations this product already uses", () => {
    render(<NotFound />)

    const hrefs = (name: RegExp) =>
      screen.getAllByRole("link", { name }).map((el) => el.getAttribute("href"))

    expect(hrefs(/browse kitchens/i)).toContain("/shop")
    expect(hrefs(/track an order/i)).toContain("/track")
    expect(hrefs(/back to the home page/i)).toContain("/")
  })

  it("states what happened in a single h1", () => {
    render(<NotFound />)

    const h1s = screen.getAllByRole("heading", { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent(/can.t find that page/i)
  })

  it("adds no second <main> — the shell already owns the landmark", () => {
    const { container } = render(<NotFound />)

    expect(container.querySelectorAll("main")).toHaveLength(1)
    expect(container.querySelector("main")).toHaveAttribute("id", "main")
  })

  it("is noindex but followable, so the way out is still crawlable", () => {
    expect(metadata.robots).toEqual({ index: false, follow: true })
    expect(metadata.title).toMatch(/not found/i)
  })
})

/**
 * A11Y-4: the /shop route tree (StorefrontLayout, `app/shop/layout.tsx`) omits
 * the skip-link + `id="main"` pair that `components/public/public-shell.tsx`
 * gives every OTHER public route (WCAG 2.4.1 Bypass Blocks). It is a separate
 * component tree from PublicShell — the storefront keeps its own header for
 * the cart badge/session nav — so it did not inherit that fix for free.
 *
 * Mirrors `components/public/__tests__/public-shell-landmarks.test.tsx`'s
 * assertions verbatim (document order, hide-until-focus, id agreement)
 * because this file is meant to converge on the exact same contract, not a
 * weaker one.
 */
import { render, screen, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import StorefrontLayout from "@/app/shop/layout"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
})

describe("/shop layout skip link (A11Y-4)", () => {
  it("renders a skip link that is the FIRST link in document order", () => {
    const { container } = render(
      <StorefrontLayout>
        <p>Page body</p>
      </StorefrontLayout>
    )

    const links = Array.from(container.querySelectorAll("a"))
    // Control: the layout really rendered its chrome (wordmark link + nav),
    // so an empty NodeList below would be a failure rather than a silent pass.
    expect(links.length).toBeGreaterThan(1)

    const first = links[0]
    expect(first).toHaveAttribute("href", "#main")
    expect(first).toHaveTextContent(/skip to main content/i)

    const skipIndex = links.findIndex((a) => a.getAttribute("href") === "#main")
    expect(skipIndex).toBe(0)
  })

  it("hides the skip link until it is focused", () => {
    const { container } = render(
      <StorefrontLayout>
        <p>Page body</p>
      </StorefrontLayout>
    )

    const skip = container.querySelector('a[href="#main"]')
    expect(skip).toBeInTheDocument()
    expect(skip).toHaveClass("sr-only")
    expect(skip).toHaveClass("focus:not-sr-only")
  })

  it("targets exactly one main landmark carrying id='main'", () => {
    render(
      <StorefrontLayout>
        <p>Page body</p>
      </StorefrontLayout>
    )

    const mains = screen.getAllByRole("main")
    expect(mains).toHaveLength(1)
    expect(mains[0]).toHaveAttribute("id", "main")
    const skip = screen.getByRole("link", { name: /skip to main content/i })
    expect(skip.getAttribute("href")).toBe(`#${mains[0].getAttribute("id")}`)
    expect(within(mains[0]).getByText("Page body")).toBeInTheDocument()
  })
})

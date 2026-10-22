/**
 * The wordmark link is named by its content, and that name is EXACTLY "J'Toye home"
 * on every surface that renders one (WCAG 2.5.3, Label in Name).
 *
 * WHY THIS EXISTS. axe-core 4.14 turned `label-content-name-mismatch` on by default and
 * compares a control's visible text to its accessible name word by word. The wordmark
 * used to be named `aria-label="J'Toye home"` while its visible text was `J J'Toye`
 * (the monogram plus the wordmark): the visible "J" was not in the name, and every
 * public page failed. The fix names the link by its content: the monogram is
 * `aria-hidden` (decorative, it repeats the first letter) and a visually hidden " home"
 * keeps the name a screen reader hears unchanged.
 *
 * The existing suites matched the name with `/j'toye home/i`, an unanchored substring
 * that the pre-fix markup also satisfied, so they could not fail on the regression.
 * These assertions are exact strings: `getByRole("link", { name: "J'Toye home" })`
 * matches the whole computed name, so a stray "J" (or a lost " home") fails here.
 * Shown RED against the pre-fix markup of all four surfaces before it went in.
 */
import { render, screen } from "@testing-library/react"
import "@testing-library/jest-dom"
import { PublicHeader } from "@/components/public/public-header"
import { PublicFooter } from "@/components/public/public-footer"
import StorefrontLayout from "@/app/shop/layout"
import SignInPage from "@/app/auth/signin/page"

jest.mock("next/navigation", () => ({
  usePathname: () => "/",
  useParams: () => ({}),
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), prefetch: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}))
jest.mock("next-auth/react", () => ({
  signIn: jest.fn(),
  useSession: () => ({ data: null, status: "unauthenticated" }),
}))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn().mockResolvedValue(null),
  clearCustomerSession: jest.fn(),
}))

const EXACT_NAME = "J'Toye home"

/**
 * Every link whose computed name is exactly the wordmark name — a surface may render
 * more than one (the storefront layout carries the header's and the footer's) — and
 * nothing named with a stray "J" in it.
 */
function expectWordmarkLinks() {
  const links = screen.getAllByRole("link", { name: EXACT_NAME })
  expect(links.length).toBeGreaterThan(0)
  for (const link of links) {
    expect(link).toHaveAttribute("href", "/")
    // No role-name query can see a decorative node, so pin the mechanism too: the
    // monogram is hidden from the accessibility tree, the suffix is in it.
    const monogram = link.querySelector("span[aria-hidden='true']")
    expect(monogram).not.toBeNull()
    expect(monogram).toHaveTextContent(/^J$/)
    expect(link.querySelector("span.sr-only")).toHaveTextContent(/^home$/)
    // The pre-fix shape, named by aria-label with the monogram visible, is gone.
    expect(link).not.toHaveAttribute("aria-label")
  }
  return links
}

describe("the wordmark link is named exactly \"J'Toye home\" by its content (WCAG 2.5.3)", () => {
  it("in the public header", () => {
    render(<PublicHeader />)
    expectWordmarkLinks()
  })

  it("in the public footer", () => {
    render(<PublicFooter />)
    expectWordmarkLinks()
  })

  it("in the storefront layout", () => {
    render(
      <StorefrontLayout>
        <main id="main">storefront</main>
      </StorefrontLayout>
    )
    expectWordmarkLinks()
  })

  it("on the sign-in page", () => {
    render(<SignInPage />)
    expectWordmarkLinks()
  })
})

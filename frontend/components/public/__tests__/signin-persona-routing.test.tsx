import { render, screen } from "@testing-library/react"
import "@testing-library/jest-dom"
import { PublicHeader } from "@/components/public/public-header"
import { PublicFooter } from "@/components/public/public-footer"

jest.mock("next/navigation", () => ({
  usePathname: () => "/",
}))

/**
 * The defect this pins: the public header's "Sign in" CTA and the footer's
 * "Vendor sign in" both pointed at `/auth/signin`.
 *
 * That is not a cosmetic duplication. `/auth/signin` authenticates against the
 * `jtoye-dev` STAFF realm via NextAuth; customers exist only in the
 * `jtoye-customers` realm (lib/customer-auth.ts). So a shopper clicking the primary
 * call to action on the landing page was sent to an identity pool their account is
 * not in, could not sign in, and had no route back — while a vendor following the
 * footer link reached the identical page, making the two personas indistinguishable.
 *
 * These assertions are about DESTINATIONS, deliberately, not about link text: the
 * bug was two different labels resolving to one href, so asserting the labels alone
 * would have passed on the broken build.
 */
describe("sign-in persona routing", () => {
  describe("PublicHeader", () => {
    it("routes the unqualified 'Sign in' CTA to the CUSTOMER page", () => {
      render(<PublicHeader />)
      // Desktop and mobile-sheet copies both render; both must agree.
      const ctas = screen.getAllByRole("link", { name: /sign in/i })
      expect(ctas.length).toBeGreaterThan(0)
      ctas.forEach((cta) => {
        expect(cta).toHaveAttribute("href", "/shop/signin")
      })
    })

    it("does not send shoppers to the operator realm from any header link", () => {
      const { container } = render(<PublicHeader />)
      const operatorLinks = container.querySelectorAll('a[href="/auth/signin"]')
      expect(operatorLinks).toHaveLength(0)
    })
  })

  describe("PublicFooter", () => {
    it("keeps 'Vendor sign in' pointing at the OPERATOR page", () => {
      render(<PublicFooter />)
      const vendorLink = screen.getByRole("link", { name: /vendor sign in/i })
      expect(vendorLink).toHaveAttribute("href", "/auth/signin")
    })
  })

  it("the two personas resolve to DIFFERENT destinations", () => {
    // The single assertion that would have failed on the pre-fix build, where both
    // hrefs were "/auth/signin". Everything above is detail; this is the bug.
    const { container: headerEl } = render(<PublicHeader />)
    const { container: footerEl } = render(<PublicFooter />)

    const customerHref = headerEl
      .querySelector('a[href*="signin"]')
      ?.getAttribute("href")
    const vendorHref = footerEl
      .querySelector('a[href*="signin"]')
      ?.getAttribute("href")

    expect(customerHref).toBeTruthy()
    expect(vendorHref).toBeTruthy()
    expect(customerHref).not.toBe(vendorHref)
  })
})

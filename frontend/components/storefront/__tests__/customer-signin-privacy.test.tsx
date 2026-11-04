/**
 * SW-SAM-10 — the privacy notice is linked where the account journey starts.
 *
 * The checkout half shipped in #1109 and the Keycloak register page carries its own line
 * (infra/keycloak/themes/jtoye/login/register.ftl). /shop/signin is the page a shopper reads
 * before being sent to either, so it names the notice too.
 */
import { render, screen } from "@testing-library/react"
import { CustomerSignInCard } from "../customer-signin-card"

jest.mock("next/navigation", () => ({
  useRouter: () => ({ replace: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}))

jest.mock("@/lib/customer-auth", () => ({
  customerLogin: jest.fn(),
  customerRegister: jest.fn(),
  getCustomerSession: jest.fn().mockResolvedValue(null),
  safeReturnTo: (v: string | null) => v ?? "/shop",
}))

describe("CustomerSignInCard — privacy notice (SW-SAM-10)", () => {
  it("links the privacy notice next to the sign-in and create-account buttons", () => {
    render(<CustomerSignInCard />)
    const link = screen.getByRole("link", { name: /privacy notice/i })
    expect(link).toHaveAttribute("href", "/legal/privacy")
  })
})

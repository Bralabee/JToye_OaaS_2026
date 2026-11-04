/**
 * #877 UXT-107 (persona close-out 2026-11-04): the signed-in header had a green person circle that did
 * nothing (elementFromPoint was an svg, no link or button) beside an 18 px sign-out icon, one tap, no
 * confirmation. A mis-tap on a phone signs an older customer out (and an explicit sign-out removes every
 * stored basket).
 *
 * Fixed here: the person circle IS the account control (a link to /shop/account, named "Your account"),
 * on the phone circle and on the desktop pill, and the sign-out icon has a 44 px target (WCAG 2.5.8 asks
 * 24, the project's own bar is 44).
 *
 * NOT fixed here, and recorded for the owner: a CONFIRMATION before sign-out. Every browser spec that
 * signs out does it in one tap by the control's title, and a confirm step changes that contract and trades
 * a lost basket against an extra tap, which is a product call.
 */
import { render, screen, act } from "@testing-library/react"
import { usePathname, useSearchParams } from "next/navigation"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
  customerIdpSignOut: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  ;(usePathname as jest.Mock).mockReturnValue("/shop")
  ;(useSearchParams as jest.Mock).mockReturnValue(new URLSearchParams(""))
})

async function renderNav() {
  render(<StorefrontNav />)
  await act(async () => {})
}

describe("UXT-107: the person icon is the account control", () => {
  it("signed in: a link named 'Your account' to /shop/account, with the person icon inside it", async () => {
    mockedSession.mockResolvedValue({ profile: { email: "shopper@example.test", name: "Shopper" } })
    await renderNav()
    const links = screen.getAllByRole("link", { name: /your account/i })
    expect(links.length).toBeGreaterThan(0)
    for (const link of links) expect(link).toHaveAttribute("href", "/shop/account")
    expect(links.some((l) => l.querySelector("svg.lucide-user") !== null)).toBe(true)
  })

  it("the sign-out control keeps its name and gets a 44px target", async () => {
    mockedSession.mockResolvedValue({ profile: { email: "shopper@example.test", name: "Shopper" } })
    await renderNav()
    const button = screen.getByRole("button", { name: "Sign out" })
    expect(button.className).toMatch(/\bh-11\b/)
    expect(button.className).toMatch(/\bw-11\b/)
  })

  it("CONTROL: signed out there is no account link (the Sign in link is what shows)", async () => {
    mockedSession.mockResolvedValue(null)
    await renderNav()
    expect(screen.getByRole("link", { name: /^sign in$/i })).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /your account/i })).toBeNull()
  })
})

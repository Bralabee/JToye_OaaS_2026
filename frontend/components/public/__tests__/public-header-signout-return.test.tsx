/**
 * SW-JRD-08, #1109 review round 1: the PUBLIC header's sign-out returns where the storefront's does,
 * through the same helper (`signOutReturnPath`). A page the sitemap lists returns to itself; any
 * other page falls back to /shop. Before, it called `customerLogout()` with no path, so it always
 * landed on /shop.
 */

import { render, screen, fireEvent, act } from "@testing-library/react"
import { usePathname } from "next/navigation"
import { PublicHeader } from "@/components/public/public-header"
import { customerLogout, getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedPathname = usePathname as jest.Mock
const mockedSession = getCustomerSession as jest.Mock
const mockedLogout = customerLogout as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue({
    profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
    expiresAt: Math.floor(Date.now() / 1000) + 300,
  })
  mockedLogout.mockReset()
  mockedLogout.mockResolvedValue(undefined)
})

afterAll(() => {
  mockedPathname.mockReturnValue("/")
})

describe("PublicHeader sign-out destination", () => {
  it.each([
    ["/", "/"],
    ["/legal/cookies", "/legal/cookies"],
    ["/for-operators", "/for-operators"],
    ["/track", "/shop"],
    ["/contact/../dashboard", "/shop"],
  ])("from %s it signs out towards %s", async (pathname, expected) => {
    mockedPathname.mockReturnValue(pathname)
    render(<PublicHeader />)
    await act(async () => {})
    const [button] = await screen.findAllByRole("button", { name: /sign out/i })
    await act(async () => {
      fireEvent.click(button)
    })
    expect(mockedLogout).toHaveBeenCalledTimes(1)
    expect(mockedLogout).toHaveBeenCalledWith(expected)
  })
})

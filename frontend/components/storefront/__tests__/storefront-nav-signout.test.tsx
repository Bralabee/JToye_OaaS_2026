/**
 * SW-JRD-08 (2026-10-19 staging persona swarm, epic issue 964) — the storefront header's account
 * controls.
 *
 *  - The signed-in sign-out control was an icon with only `title="Sign out"`: it now carries
 *    `aria-label="Sign out"` and its icon is decorative.
 *  - The "Sign in" link built `next=<pathname>`, which on /shop/signin pointed at itself.
 *  - Sign-out always landed on /shop. It now returns to the public storefront page the customer was
 *    on (/shop or a shop's own page) and falls back to /shop from anywhere personal.
 *
 * The sign-out itself is still `customerLogout`, which owns the teardown (marker, baskets, identity
 * stamp); only the destination it is given changes. Its clearing contract is the
 * customer-auth-signout-* suites'.
 */

import { render, screen, fireEvent, act } from "@testing-library/react"
import { usePathname, useSearchParams } from "next/navigation"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { customerLogout, getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
  customerIdpSignOut: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock
const mockedLogout = customerLogout as jest.Mock
const mockedPathname = usePathname as jest.Mock
const mockedSearchParams = useSearchParams as jest.Mock

async function renderNavAt(pathname: string) {
  mockedPathname.mockReturnValue(pathname)
  render(<StorefrontNav />)
  await act(async () => {})
}

function signedIn() {
  mockedSession.mockResolvedValue({ profile: { email: "shopper@example.test", name: "Shopper" } })
}

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
  mockedLogout.mockReset()
  mockedLogout.mockResolvedValue(undefined)
  mockedSearchParams.mockReturnValue(new URLSearchParams(""))
})

afterAll(() => {
  mockedPathname.mockReturnValue("/")
})

describe("SW-JRD-08 — the sign-out control is named, not just titled", () => {
  it("carries aria-label='Sign out' and hides its icon", async () => {
    signedIn()
    await renderNavAt("/shop")
    const button = screen.getByRole("button", { name: "Sign out" })
    expect(button).toHaveAttribute("aria-label", "Sign out")
    const icon = button.querySelector("svg")
    expect(icon).not.toBeNull()
    expect(icon).toHaveAttribute("aria-hidden", "true")
  })
})

describe("SW-JRD-08 — sign-out returns to the public page the customer was on", () => {
  it.each([
    ["/shop", "/shop"],
    ["/shop/rosies-kitchen", "/shop/rosies-kitchen"],
    ["/shop/rosies-kitchen/checkout", "/shop/rosies-kitchen"],
    ["/shop/rosies-kitchen/cart", "/shop/rosies-kitchen"],
    ["/shop/rosies-kitchen/orders/ORD-1", "/shop"],
    ["/shop/account", "/shop"],
    ["/shop/orders", "/shop"],
  ])("from %s it signs out towards %s", async (pathname, expected) => {
    signedIn()
    await renderNavAt(pathname)
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Sign out" }))
    })
    expect(mockedLogout).toHaveBeenCalledTimes(1)
    expect(mockedLogout).toHaveBeenCalledWith(expected)
  })
})

describe("SW-JRD-08 — the Sign in link does not point at itself", () => {
  it("omits next= on /shop/signin", async () => {
    await renderNavAt("/shop/signin")
    expect(screen.getByRole("link", { name: /^sign in$/i })).toHaveAttribute("href", "/shop/signin")
  })

  it("keeps the page's own next= on /shop/signin?next=... (#1109 review round 1)", async () => {
    mockedSearchParams.mockReturnValue(new URLSearchParams("next=/shop/rosies-kitchen"))
    await renderNavAt("/shop/signin")
    expect(screen.getByRole("link", { name: /^sign in$/i })).toHaveAttribute(
      "href",
      "/shop/signin?next=%2Fshop%2Frosies-kitchen"
    )
  })

  it("narrows that next= with the sign-in page's own rule (an off-site value becomes /shop)", async () => {
    mockedSearchParams.mockReturnValue(new URLSearchParams("next=//evil.example/x"))
    await renderNavAt("/shop/signin")
    expect(screen.getByRole("link", { name: /^sign in$/i })).toHaveAttribute("href", "/shop/signin?next=%2Fshop")
  })

  it("still carries next= from a shop page (control)", async () => {
    await renderNavAt("/shop/rosies-kitchen")
    expect(screen.getByRole("link", { name: /^sign in$/i })).toHaveAttribute(
      "href",
      "/shop/signin?next=%2Fshop%2Frosies-kitchen"
    )
  })
})

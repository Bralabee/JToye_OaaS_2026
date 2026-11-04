/**
 * #877 UXT-107 (J-10, P2-GRA-12): the header's sign-out icon asks before it signs anyone out.
 *
 * Measured by the persona run: a one-tap 18 px icon beside the account circle; a mis-tap signs an older
 * customer out and clears every basket stored on the device. These pin the behaviour THROUGH the
 * interaction, on both headers that show the icon: tapping it opens a dialog and signs nobody out;
 * "Stay signed in", Escape and a tap outside each leave the session alone; the dialog's own Sign out
 * signs out exactly once, towards the same page the one-tap control used to.
 */
import { render, screen, fireEvent, act, within } from "@testing-library/react"
import { usePathname, useSearchParams } from "next/navigation"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { PublicHeader } from "@/components/public/public-header"
import { customerLogout, getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
  customerIdpSignOut: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock
const mockedLogout = customerLogout as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue({
    profile: { sub: "u1", email: "shopper@example.test", name: "Shopper", emailVerified: true },
    expiresAt: Math.floor(Date.now() / 1000) + 300,
  })
  mockedLogout.mockReset()
  mockedLogout.mockResolvedValue(undefined)
  ;(usePathname as jest.Mock).mockReturnValue("/shop/rosies-kitchen")
  ;(useSearchParams as jest.Mock).mockReturnValue(new URLSearchParams(""))
})

afterAll(() => {
  ;(usePathname as jest.Mock).mockReturnValue("/")
})

type Ui = () => React.ReactElement
const storefront: Ui = () => <StorefrontNav />
const publicHeader: Ui = () => <PublicHeader />

async function open(ui: Ui) {
  render(ui())
  await act(async () => {})
  const [icon] = await screen.findAllByRole("button", { name: /sign out/i })
  // CONTROL: before the tap there is no dialog, so "a dialog after" is a measurement.
  expect(screen.queryByRole("dialog")).toBeNull()
  await act(async () => {
    fireEvent.click(icon)
  })
}

async function expectOpensAndSignsNobodyOut(ui: Ui) {
  await open(ui)
  expect(screen.getByRole("dialog", { name: /sign out\?/i })).toBeInTheDocument()
  expect(mockedLogout).not.toHaveBeenCalled()
}

async function expectStayClosesIt(ui: Ui) {
  await open(ui)
  const dialog = screen.getByRole("dialog")
  expect(within(dialog).getAllByRole("button")[0]).toHaveTextContent("Stay signed in") // first in DOM order: initial focus
  await act(async () => {
    fireEvent.click(within(dialog).getByRole("button", { name: "Stay signed in" }))
  })
  expect(screen.queryByRole("dialog")).toBeNull()
  expect(mockedLogout).not.toHaveBeenCalled()
}

async function expectEscapeClosesIt(ui: Ui) {
  await open(ui)
  await act(async () => {
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape", code: "Escape" })
  })
  expect(screen.queryByRole("dialog")).toBeNull()
  expect(mockedLogout).not.toHaveBeenCalled()
}

async function expectConfirmSignsOutOnce(ui: Ui) {
  await open(ui)
  await act(async () => {
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Sign out" }))
  })
  expect(mockedLogout).toHaveBeenCalledTimes(1)
  expect(mockedLogout).toHaveBeenCalledWith(expect.stringMatching(/^\//))
}

async function expectSaysWhatIsLost(ui: Ui) {
  await open(ui)
  expect(screen.getByRole("dialog")).toHaveTextContent(/basket/i)
}

describe("StorefrontNav: sign-out asks first", () => {
  it("tapping the icon opens a dialog and signs nobody out", () => expectOpensAndSignsNobodyOut(storefront))
  it("'Stay signed in' closes it and leaves the session alone", () => expectStayClosesIt(storefront))
  it("Escape closes it and leaves the session alone", () => expectEscapeClosesIt(storefront))
  it("the dialog's own Sign out signs out exactly once, towards a path on this site", () => expectConfirmSignsOutOnce(storefront))
  it("the dialog says what is lost", () => expectSaysWhatIsLost(storefront))
})

describe("PublicHeader: sign-out asks first", () => {
  it("tapping the icon opens a dialog and signs nobody out", () => expectOpensAndSignsNobodyOut(publicHeader))
  it("'Stay signed in' closes it and leaves the session alone", () => expectStayClosesIt(publicHeader))
  it("Escape closes it and leaves the session alone", () => expectEscapeClosesIt(publicHeader))
  it("the dialog's own Sign out signs out exactly once, towards a path on this site", () => expectConfirmSignsOutOnce(publicHeader))
  it("the dialog says what is lost", () => expectSaysWhatIsLost(publicHeader))
})

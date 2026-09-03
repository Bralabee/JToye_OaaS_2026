/**
 * FE-5 (QA council 20260902-134741, **High**) — a second person could not create
 * an account on a shared device.
 *
 * THE STATE. Customer A signs in; the APP session then lapses WITHOUT an explicit
 * sign-out (the three HttpOnly cookies stop being valid) while Keycloak's SSO
 * cookies survive on the IdP host. `/api/customer-auth/session` answers
 * `authenticated:false`, the storefront presents as anonymous — and offered NO
 * sign-out control in that state (measured: appOffersSignOutWhileAnonymous ===
 * false). Person B tapped "Create an account" and Keycloak dead-ended with "You
 * are already authenticated as different user 'A' … Please sign out first" — 0
 * links, 0 buttons, 0 forms, and A's email disclosed to whoever holds the phone.
 *
 * THE CONTROL. "Not you? Sign out" — the explicit IdP sign-out from the anonymous
 * state (`customerIdpSignOut`). Offered when the session is unauthenticated AND
 * this browser remembers a sign-in that was never explicitly signed out
 * (`jtoye-customer-last-signin`, see `lib/cart-identity.ts`). That marker is the
 * honest, client-detectable proxy for "Keycloak's SSO cookies are probably still
 * alive": those cookies live on the IdP host under /realms/<realm>/ and neither
 * this app's client nor its server can read them. Its lifecycle is R-16's —
 * written by a confirmed sign-in, removed ONLY by an explicit sign-out; a lapse
 * is not a new person and does not remove it.
 *
 * THREE ARMS, each with its own fail direction: the control must appear in the
 * lapsed state (fail: it is absent — today's tree), must NOT appear on a fresh
 * device (fail: an unconditional control would nag every first-time visitor and
 * would make arm 1 vacuous), and must NOT appear while signed in (fail: two
 * sign-out controls, and the e2e scripts that `getByTitle("Sign out")` would be
 * looking at the wrong one). The desktop row and the mobile sheet are separate
 * JSX, so both are asserted, as the #458 test in this directory does.
 *
 * These jsdom assertions are the fast guard, not the proof: the shipped
 * behaviour is `probes/fe-register-deadend.js` on the rebuilt stack.
 */

import { render, screen, fireEvent, act } from "@testing-library/react"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
import { customerIdpSignOut, getCustomerSession } from "@/lib/customer-auth"
import { CUSTOMER_LAST_SIGNIN_KEY } from "@/lib/cart-identity"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
  customerIdpSignOut: jest.fn(() => Promise.resolve("http://kc.example/logout")),
}))

const mockedSession = getCustomerSession as jest.Mock
const mockedIdpSignOut = customerIdpSignOut as jest.Mock
const NOT_YOU = /not you\? sign out/i

async function renderNav() {
  render(<StorefrontNav />)
  await act(async () => {})
}

/** A sign-in happened on this browser and nobody explicitly signed out since. */
function rememberedSignIn() {
  localStorage.setItem(CUSTOMER_LAST_SIGNIN_KEY, "sub-a")
}

function signedIn() {
  mockedSession.mockResolvedValue({
    profile: { sub: "sub-a", email: "shopper@example.test", name: "Shopper" },
  })
}

beforeEach(() => {
  localStorage.clear()
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
  mockedIdpSignOut.mockClear()
})

describe("StorefrontNav — 'Not you? Sign out' (FE-5)", () => {
  it("ARM 1: anonymous + a remembered sign-in -> offers the control on the desktop row", async () => {
    rememberedSignIn()
    await renderNav()

    expect(screen.getByRole("button", { name: NOT_YOU })).toBeTruthy()
    // Not the signed-in control: the e2e scripts locate THAT by its title and
    // must never find this one instead.
    expect(screen.queryByTitle("Sign out")).toBeNull()
  })

  it("ARM 1 (mobile sheet): the same control is inside the hamburger sheet", async () => {
    rememberedSignIn()
    await renderNav()
    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    // Radix aria-hides everything outside the open sheet, so this is the
    // SHEET's instance — the desktop row cannot satisfy it.
    expect(screen.getByRole("button", { name: NOT_YOU })).toBeTruthy()
  })

  it("clicking it runs the EXPLICIT IdP sign-out, returning to /shop/signin, and stays busy", async () => {
    rememberedSignIn()
    await renderNav()
    const btn = screen.getByRole("button", { name: NOT_YOU }) as HTMLButtonElement

    await act(async () => {
      fireEvent.click(btn)
    })

    expect(mockedIdpSignOut).toHaveBeenCalledTimes(1)
    expect(mockedIdpSignOut).toHaveBeenCalledWith("/shop/signin")
    // Same WR-06 contract as the ordinary sign-out: busy until the document
    // goes away, never reset on promise resolution.
    expect(btn.getAttribute("aria-busy")).toBe("true")
    expect(btn.disabled).toBe(true)
  })

  it("ARM 2: anonymous with NO remembered sign-in -> no control on either path (a fresh device is not nagged)", async () => {
    await renderNav()
    expect(screen.queryByRole("button", { name: NOT_YOU })).toBeNull()
    // Non-vacuity: the anonymous branch really rendered.
    expect(screen.getByRole("link", { name: /^sign in$/i })).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    expect(screen.queryByRole("button", { name: NOT_YOU })).toBeNull()
  })

  it("ARM 3: signed IN (marker present or not) -> no 'Not you?' control; the ordinary sign-out is offered instead", async () => {
    rememberedSignIn()
    signedIn()
    await renderNav()

    expect(screen.queryByRole("button", { name: NOT_YOU })).toBeNull()
    expect(screen.getByTitle("Sign out")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: /open menu/i }))
    expect(screen.queryByRole("button", { name: NOT_YOU })).toBeNull()
  })
})

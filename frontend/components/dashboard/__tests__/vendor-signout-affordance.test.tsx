import { fireEvent, render, screen, waitFor } from "@testing-library/react"

import { Sidebar } from "@/components/dashboard/sidebar"
import { MobileTabBar } from "@/components/dashboard/mobile-tab-bar"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { vendorLogout } from "@/lib/vendor-logout"

/**
 * CR-02 (code review, 2026-08-31) — the two VENDOR sign-out affordances had no
 * busy state, and that omission was not cosmetic.
 *
 * The same branch gave `disabled` + `aria-busy` to all four CUSTOMER sign-out
 * affordances with an explicit rationale — "without a busy state the shopper
 * gets no acknowledgement at all and taps again". The two vendor buttons got a
 * floated `onClick={() => vendorLogout()}` and nothing else, on a round trip
 * that is bounded at 3s and therefore visibly slow on a bad connection. The
 * reasoning applied identically to a vendor; it was simply not applied.
 *
 * And a re-tap here could OVERRIDE the pending Keycloak navigation with
 * `/auth/signin`, handing back the P0 (the mechanism, and the module-level
 * latch that closes it independently of any UI, are in
 * `lib/__tests__/vendor-logout.test.ts`). Belt AND braces on purpose: the UI
 * guard is what the vendor sees, the latch is what holds when the UI is
 * bypassed by a keyboard repeat or a click queued before the disable painted.
 *
 * `vendorLogout` is mocked here. This file is about what the BUTTON does; what
 * the function does is that file's job, and letting the real one run would put
 * a network call and a jsdom navigation into a rendering test for no gain.
 */

jest.mock("@/lib/vendor-logout", () => ({
  vendorLogout: jest.fn(() => new Promise<string>(() => {})),
  VENDOR_LOGOUT_TIMEOUT_MS: 3000,
}))

// The sidebar's shop-context switcher fetches on mount. Keep it pending so no
// async setState lands after the assertions; it stays in its loading skeleton.
jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(() => new Promise(() => {})),
}))

const mockVendorLogout = vendorLogout as jest.Mock

beforeEach(() => {
  mockVendorLogout.mockClear()
})

/**
 * Both affordances, driven through the SAME table. They are two code paths for
 * one contract, and the dashboard has already paid once for letting a pair like
 * this drift (hence the shared `navigation` array both import).
 */
const affordances: Array<[string, () => void]> = [
  [
    "desktop sidebar",
    () =>
      render(
        // The sidebar hosts the shop-context switcher, which reads this
        // context and throws without it.
        <ShopSwitcherProvider>
          <Sidebar />
        </ShopSwitcherProvider>
      ),
  ],
  [
    "mobile More sheet",
    () => {
      render(<MobileTabBar />)
      // The sign-out control lives inside the sheet, so it has to be opened.
      fireEvent.click(screen.getByRole("button", { name: /more/i }))
    },
  ],
]

describe.each(affordances)("vendor Sign Out — %s", (_label, mount) => {
  it("is enabled and not busy before it is used", () => {
    mount()
    const button = screen.getByRole("button", { name: /sign out/i })

    // CONTROL. Without it, the assertions below would be equally satisfied by a
    // button that is disabled from the very first paint — which would be a
    // worse defect than the one being fixed, and invisible to a busy-state
    // assertion taken only after a click.
    expect(button).toBeEnabled()
    expect(button).not.toHaveAttribute("aria-busy", "true")
  })

  it("goes disabled and aria-busy on the first tap, and STAYS that way", async () => {
    mount()
    const button = screen.getByRole("button", { name: /sign out/i })

    fireEvent.click(button)

    await waitFor(() => expect(button).toBeDisabled())
    expect(button).toHaveAttribute("aria-busy", "true")
    expect(mockVendorLogout).toHaveBeenCalledTimes(1)

    // A second tap reaches nothing. The mocked logout never settles, which is
    // the real shape: `location.href` only SCHEDULES a navigation, so the
    // document stays live and tappable for the whole commit window.
    fireEvent.click(button)
    expect(mockVendorLogout).toHaveBeenCalledTimes(1)
  })
})

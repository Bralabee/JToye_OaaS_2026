import { fireEvent, render, screen } from "@testing-library/react"

import { Sidebar } from "@/components/dashboard/sidebar"
import { MobileTabBar } from "@/components/dashboard/mobile-tab-bar"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { vendorLogout } from "@/lib/vendor-logout"
import {
  __resetDeliberateSignOutForTests,
  isDeliberateSignOut,
} from "@/components/dashboard/kitchen/kds-board-stopped"

/**
 * 37-18 (UI-SPEC K2, goods P2-TUN-14): pressing Sign out on a shared kitchen tablet really ends the
 * session, with no board-stopped overlay and no alarm. The kitchen tells the two apart by a flag
 * the Sign out handlers set BEFORE `vendorLogout()` runs, so a 401 the sign-out itself provokes
 * (a board poll landing between the cookie being cleared and the navigation) is not claimed.
 *
 * Both affordances, because a kitchen tablet below `md` has no sidebar: its Sign out is in the
 * mobile "More" sheet. What `vendorLogout` does is lib/__tests__/vendor-logout.test.ts's job; here it
 * records whether the flag was already set when it was called.
 */

let flagWhenSignOutRan: boolean | null = null
jest.mock("@/lib/vendor-logout", () => ({
  vendorLogout: jest.fn(() => {
    flagWhenSignOutRan = jest
      .requireActual("@/components/dashboard/kitchen/kds-board-stopped")
      .isDeliberateSignOut()
    return new Promise<string>(() => {})
  }),
  VENDOR_LOGOUT_TIMEOUT_MS: 3000,
}))

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(() => new Promise(() => {})),
}))

beforeEach(() => {
  flagWhenSignOutRan = null
  ;(vendorLogout as jest.Mock).mockClear()
  __resetDeliberateSignOutForTests()
})

it("the sidebar's Sign out marks a deliberate sign-out before it signs out", () => {
  render(
    <ShopSwitcherProvider>
      <Sidebar />
    </ShopSwitcherProvider>
  )
  expect(isDeliberateSignOut()).toBe(false)

  fireEvent.click(screen.getByRole("button", { name: /sign out/i }))

  expect(vendorLogout).toHaveBeenCalledTimes(1)
  expect(flagWhenSignOutRan).toBe(true)
})

it("the mobile More sheet's Sign out marks it too, before it signs out", () => {
  render(<MobileTabBar />)
  fireEvent.click(screen.getByRole("button", { name: /more/i }))
  expect(isDeliberateSignOut()).toBe(false)

  fireEvent.click(screen.getByRole("button", { name: /sign out/i }))

  expect(vendorLogout).toHaveBeenCalledTimes(1)
  expect(flagWhenSignOutRan).toBe(true)
})

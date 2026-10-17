/**
 * The sidebar and the mobile tab bar share ONE theme store (phase 34-02).
 *
 * The theme suite (hooks/__tests__/use-theme.test.tsx) proves two arbitrary
 * consumers agree. This file proves it for the two REAL surfaces, because a
 * structural check can pass while the function is still broken: the components
 * could import the hook and still render from a private copy of the value.
 *
 * What used to be true, and is the regression this guards: the tab bar read
 * `document.documentElement.classList.contains("dark")` in a mount effect, so it
 * only ever agreed with the sidebar if the sidebar had mounted first, and it
 * NEVER saw a later toggle without a reload.
 */

import { render, screen, fireEvent, within } from "@testing-library/react"
import { Sidebar } from "@/components/dashboard/sidebar"
import { MobileTabBar } from "@/components/dashboard/mobile-tab-bar"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import {
  DARK_THEME_AVAILABLE,
  __setDarkThemeAvailableForTests,
} from "@/hooks/use-theme"

// Keep the switcher's network off this test: a pending fetch leaves it in its
// loading skeleton, which renders fine and starts no async setState.
jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(() => new Promise(() => {})),
}))

// jsdom has no matchMedia, which the theme store reads for its system fallback.
function installMatchMedia(prefersDark = false): void {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: prefersDark && query.includes("dark"),
      media: query,
      onchange: null,
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      addListener: jest.fn(),
      removeListener: jest.fn(),
      dispatchEvent: jest.fn(),
    }),
  })
}

/** Open the tab bar's "More" sheet, where its theme control lives. */
function openMoreSheet(): void {
  fireEvent.click(screen.getByRole("button", { name: /more navigation/i }))
}

/**
 * Count theme buttons across BOTH surfaces while the sheet is open.
 *
 * `hidden: true` is load-bearing, not a loosening: the Sheet is a Radix modal
 * dialog, so while it is open everything outside it — the whole sidebar —
 * carries `aria-hidden="true"` and is absent from the accessible tree. Without
 * this the sidebar's button is invisible to the query and the count reads 1
 * whether the store is shared or not (measured: "Expected length: 2, Received
 * length: 1", and the single match was the tab bar's own button).
 */
function themeButtons(label: RegExp): HTMLElement[] {
  // queryAllByRole, not getAllByRole: the zero case is a real assertion here
  // ("no surface still offers the old label") and getAll* throws on zero.
  return screen.queryAllByRole("button", { name: label, hidden: true })
}

/* Shared by both blocks: empty storage, a bare <html>, a light system. */
beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = ""
  installMatchMedia()
})

/* ===========================================================================
 * FORCED LIGHT — owner ruling 2026-10-16 (phase 37 deferred-items.md, "Owner
 * rulings 2026-10-16"; quick 261016-edv). On the SHIPPED default (no seam call)
 * neither real surface offers a theme control, the document carries no dark
 * class, and the stored choice is left as it was. Goes red by design when
 * DARK_THEME_AVAILABLE is flipped, and is deleted with the flag.
 * ========================================================================= */
describe("dashboard theme, with the dark theme withheld (shipped default)", () => {
  it("offers no theme control on either surface, keeps Sign Out on both, and stays light", () => {
    // Every input says dark: a stored choice AND the system preference.
    localStorage.setItem("theme", "dark")
    installMatchMedia(true)

    render(
      <ShopSwitcherProvider>
        <Sidebar />
        <MobileTabBar />
      </ShopSwitcherProvider>
    )

    // The tab bar's control lived inside the More sheet: open it, so the zero
    // below is a statement about the sheet's footer and not about a closed sheet.
    openMoreSheet()
    expect(screen.getByRole("dialog")).toBeInTheDocument()

    expect(themeButtons(/dark mode|light mode/i)).toHaveLength(0)
    // Positive control: the same query reaches the footer the control shared
    // with Sign Out — one per surface.
    expect(themeButtons(/sign out/i)).toHaveLength(2)
    expect(document.documentElement.classList.contains("dark")).toBe(false)
    expect(localStorage.getItem("theme")).toBe("dark")
  })
})

/* ===========================================================================
 * FLAG ON — the pre-existing cases, unchanged, with the dark theme turned back
 * on through the seam for this block only.
 * ========================================================================= */
describe("dashboard theme, across both surfaces", () => {
  beforeEach(() => {
    __setDarkThemeAvailableForTests(true)
  })

  afterEach(() => {
    __setDarkThemeAvailableForTests(DARK_THEME_AVAILABLE)
  })

  it("shows the tab bar the sidebar's toggle with no reload", () => {
    render(
      <ShopSwitcherProvider>
        <Sidebar />
        <MobileTabBar />
      </ShopSwitcherProvider>
    )

    // Both surfaces start on the same (light) theme, so both offer "Dark Mode".
    const sidebarToggle = screen.getByRole("button", { name: /dark mode/i })

    openMoreSheet()
    const sheet = screen.getByRole("dialog")
    expect(within(sheet).getByRole("button", { name: /dark mode/i })).toBeInTheDocument()

    // Toggle from the SIDEBAR only.
    fireEvent.click(sidebarToggle)

    // The tab bar's label follows without a remount — this is the assertion the
    // old DOM-class read could not satisfy.
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: /light mode/i }))
      .toBeInTheDocument()
    expect(document.documentElement.classList.contains("dark")).toBe(true)
    expect(localStorage.getItem("theme")).toBe("dark")
  })

  it("shows the sidebar the tab bar's toggle with no reload", () => {
    render(
      <ShopSwitcherProvider>
        <Sidebar />
        <MobileTabBar />
      </ShopSwitcherProvider>
    )

    openMoreSheet()
    const sheet = screen.getByRole("dialog")

    // Toggle from the TAB BAR only — the reverse direction, which the old code
    // could not do at all: the sidebar held its own useState and never re-read.
    fireEvent.click(within(sheet).getByRole("button", { name: /dark mode/i }))

    // Two "Light Mode" buttons now: one per surface, both from the same store.
    expect(themeButtons(/light mode/i)).toHaveLength(2)
    expect(themeButtons(/dark mode/i)).toHaveLength(0)
    expect(document.documentElement.classList.contains("dark")).toBe(true)
  })

  it("renders both surfaces on a stored dark preference without either reading the other", () => {
    // Mount order is deliberately tab-bar-first: under the old code this was the
    // broken case, because the tab bar's effect ran before the sidebar had put
    // the class on documentElement.
    localStorage.setItem("theme", "dark")

    render(
      <ShopSwitcherProvider>
        <MobileTabBar />
        <Sidebar />
      </ShopSwitcherProvider>
    )

    openMoreSheet()
    expect(themeButtons(/light mode/i)).toHaveLength(2)
    expect(themeButtons(/dark mode/i)).toHaveLength(0)
    expect(document.documentElement.classList.contains("dark")).toBe(true)
  })
})

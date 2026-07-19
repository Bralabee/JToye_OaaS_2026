/**
 * Smoke test for the client-side DashboardShell. The real auth check lives
 * in the Server Component parent (frontend/app/dashboard/layout.tsx) and is
 * exercised by `next build` + middleware integration; here we just verify
 * the shell renders the passed children and the sidebar chrome.
 */

import { render, screen, within } from "@testing-library/react"
import { DashboardShell } from "../dashboard-shell"

// The real Sidebar pulls in next-auth, next/navigation etc. We stub the Sidebar
// COMPONENT but keep the real module's `navigation` export — MobileTabBar imports
// that same array (single source of truth), so it must survive the mock.
jest.mock("@/components/dashboard/sidebar", () => ({
  ...jest.requireActual("@/components/dashboard/sidebar"),
  Sidebar: () => <aside data-testid="sidebar-stub">sidebar</aside>,
}))

// The mobile top bar now mounts the shop-context switcher. Keep its network +
// session off the shell smoke test: a pending fetch leaves the switcher in its
// loading skeleton (still `data-testid="shop-switcher"`), so no async setState.
jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(() => new Promise(() => {})),
}))

describe("DashboardShell", () => {
  it("renders sidebar chrome and child content", () => {
    render(
      <DashboardShell>
        <div data-testid="content">hello</div>
      </DashboardShell>
    )
    expect(screen.getByTestId("sidebar-stub")).toBeInTheDocument()
    expect(screen.getByTestId("content")).toHaveTextContent("hello")
  })

  it("renders the mobile bottom tab bar (md:hidden) alongside the desktop sidebar", () => {
    render(
      <DashboardShell>
        <div>content</div>
      </DashboardShell>
    )
    // Desktop sidebar chrome present.
    expect(screen.getByTestId("sidebar-stub")).toBeInTheDocument()
    // Mobile bottom bar present and collapsed at md+.
    const tabBar = screen.getByTestId("mobile-tab-bar")
    expect(tabBar).toBeInTheDocument()
    expect(tabBar).toHaveClass("md:hidden")
    expect(tabBar).toHaveClass("fixed")
  })

  it("mounts the shop-context switcher in the md:hidden mobile top bar (375px chrome, MOBL-01)", () => {
    render(
      <DashboardShell>
        <div>content</div>
      </DashboardShell>
    )
    // The slim top bar is mobile-only: shown at 375px, collapsed at md+ where the
    // 256px sidebar takes over — this is the responsive contract MOBL-01 guards.
    const topbar = screen.getByTestId("mobile-topbar")
    expect(topbar).toHaveClass("md:hidden")
    // The switcher rides the mobile top bar next to the wordmark (D-06).
    expect(within(topbar).getByTestId("shop-switcher")).toBeInTheDocument()
    // At 375px the bottom tab bar is the nav (md:hidden + fixed), not the sidebar.
    const tabBar = screen.getByTestId("mobile-tab-bar")
    expect(tabBar).toHaveClass("md:hidden")
    expect(tabBar).toHaveClass("fixed")
  })

  it("exposes the 4 primary tabs plus a More trigger in the bottom bar", () => {
    render(
      <DashboardShell>
        <div>content</div>
      </DashboardShell>
    )
    const tabBar = screen.getByTestId("mobile-tab-bar")
    for (const label of ["Dashboard", "Orders", "Products", "Kitchen"]) {
      expect(within(tabBar).getByRole("link", { name: label })).toBeInTheDocument()
    }
    expect(
      within(tabBar).getByRole("button", { name: /more navigation/i })
    ).toBeInTheDocument()
  })
})

describe("Sidebar navigation", () => {
  // The shell test above stubs the Sidebar; render the REAL one here to assert
  // the "Go live" nav item ships. next-auth/next-navigation are mocked globally
  // in jest.setup.js. jsdom lacks matchMedia, which the sidebar's theme effect
  // reads — stub it here.
  beforeAll(() => {
    Object.defineProperty(window, "matchMedia", {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
        addListener: jest.fn(),
        removeListener: jest.fn(),
        dispatchEvent: jest.fn(),
      }),
    })
  })

  it("shows a 'Go live' link to /dashboard/onboarding", () => {
    const { Sidebar } = jest.requireActual("@/components/dashboard/sidebar") as {
      Sidebar: () => JSX.Element
    }
    render(<Sidebar />)
    const link = screen.getByRole("link", { name: /go live/i })
    expect(link).toHaveAttribute("href", "/dashboard/onboarding")
  })
})

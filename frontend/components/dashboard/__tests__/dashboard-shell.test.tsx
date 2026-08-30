/**
 * Smoke test for the client-side DashboardShell. The real auth check lives
 * in the Server Component parent (frontend/app/dashboard/layout.tsx) and is
 * exercised by `next build` + middleware integration; here we just verify
 * the shell renders the passed children and the sidebar chrome.
 */

import type { ReactElement } from "react"
import { render, screen, within } from "@testing-library/react"
import { usePathname } from "next/navigation"
import { DashboardShell } from "../dashboard-shell"
import { ShopSwitcherProvider } from "../shop-switcher-provider"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"

// jest.setup.js already mocks next/navigation with `usePathname: () => '/'`;
// grab the mock so individual cases can put the shell on a real route.
const mockedUsePathname = usePathname as jest.MockedFunction<typeof usePathname>

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
  // Leave the shared next/navigation mock as jest.setup.js left it, so a case
  // that pins a route cannot leak into the ones after it.
  afterEach(() => {
    mockedUsePathname.mockReturnValue("/")
  })

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

  /**
   * #450 item 1. The switcher was mounted on EVERY dashboard route, including
   * the per-tenant onboarding page, which never reads the shop context — the QA
   * council measured a switch there firing 0 API calls. The bar keeps its fixed
   * h-14, so dropping the control moves no other chrome.
   */
  it("omits the top-bar switcher on the per-tenant onboarding sub-tree", () => {
    mockedUsePathname.mockReturnValue("/dashboard/onboarding")
    const { unmount } = render(
      <DashboardShell>
        <div>content</div>
      </DashboardShell>
    )
    const topbar = screen.getByTestId("mobile-topbar")
    // The bar itself is untouched — only the dead control is gone.
    expect(topbar).toHaveClass("md:hidden")
    expect(within(topbar).getByText("J'Toye")).toBeInTheDocument()
    expect(within(topbar).queryByTestId("shop-switcher")).not.toBeInTheDocument()
    unmount()

    // Control arm — without this the case above passes on a shell that never
    // renders the switcher at all.
    mockedUsePathname.mockReturnValue("/dashboard/products")
    render(
      <DashboardShell>
        <div>content</div>
      </DashboardShell>
    )
    expect(
      within(screen.getByTestId("mobile-topbar")).getByTestId("shop-switcher")
    ).toBeInTheDocument()
  })
})

/**
 * Phase 35 — the Shell tier at the tree's ONE width call site.
 *
 * PATTERNS.md F-1: the stock shadcn width utility was consumed by exactly one
 * element in the whole repository, the band below, and all 21 dashboard routes
 * inherit their width from it — no dashboard layout or page declares a width of
 * its own. That makes this a one-line change and it is also why it is the
 * highest-blast-radius edit in the phase (T-35-06): a bad edit here blanks every
 * authenticated page at once.
 *
 * So the preserved declarations are ENUMERATED rather than assumed. That is the
 * Incremental Betterment rule made executable: the shed utility supplied three
 * declarations, each accounted for at the call site, and every class the element
 * kept is asserted by name below.
 *
 * WHY THE REMOVAL IS ASSERTED AS A TOKEN AND NOT A SUBSTRING. `DialogContent`,
 * `CardContent` and `TabsContent` are everywhere in this tree, so a naive
 * substring search for the shed class returns 269 hits across 55 files against
 * one real one (PATTERNS.md F-1). `classList.contains` is a token match by
 * definition, and the control case below proves that instrument can still see
 * the class when it is genuinely present.
 *
 * WHAT THIS SUITE IS NOT EVIDENCE ABOUT. `e2e/dashboard-mobile.spec.ts` measures
 * `main`, which sits OUTSIDE this element (PATTERNS.md B-4), so a green mobile
 * run there says nothing about this change. And per issue #683 the nightly lane
 * that would run the Shell-tier browser assertions is dark — the honest phrasing
 * for those is "covered by a spec that no current tree executes", never "covered
 * nightly". This jsdom suite proves the class is APPLIED; it cannot prove the
 * resulting element is 1700px wide in a browser.
 */
describe("DashboardShell — the Shell width tier", () => {
  /** The one content band. Throws rather than returning null, so an assertion below cannot pass on nothing. */
  function renderBand(): HTMLElement {
    const view = render(
      <DashboardShell>
        <div data-testid="content">hello</div>
      </DashboardShell>
    )
    const band = view.container.querySelector<HTMLElement>('[data-width-tier="shell"]')
    if (!band) throw new Error("no element declares the shell width tier")
    return band
  }

  it("declares the shell tier on the content band", () => {
    expect(renderBand()).toHaveAttribute("data-width-tier", "shell")
  })

  it("caps the band at the shell tier's declared utility", () => {
    // Read from the vocabulary module rather than restated, so the band and the
    // generated utility cannot drift apart in this assertion.
    expect(renderBand()).toHaveClass(WIDTH_TIER_CLASS.shell)
  })

  it("no longer carries the shadcn width class", () => {
    // Token match, not substring — see the note above.
    expect(renderBand().classList.contains("container")).toBe(false)
  })

  it("would notice that class if it were there — the control for the line above", () => {
    // NON-VACUITY CONTROL. Without it, "the class is absent" is equally
    // satisfied by an instrument that can never observe the class at all.
    const probe = document.createElement("div")
    probe.className = `container ${WIDTH_TIER_CLASS.shell} mx-auto`
    expect(probe.classList.contains("container")).toBe(true)
  })

  /**
   * The displaced-goods ledger, executable. Every class the band carried before
   * the tier was applied, named individually so a silent drop reds.
   */
  it.each([
    "mx-auto",
    "p-4",
    "pb-20",
    "sm:p-8",
    "sm:pb-20",
    "md:pb-8",
    "dark:text-slate-100",
  ])("keeps the %s declaration it already had", (kept) => {
    expect(renderBand()).toHaveClass(kept)
  })

  it("keeps the footer and its legal line inside the band", () => {
    const band = renderBand()
    const footer = band.querySelector("footer")
    expect(footer).not.toBeNull()
    expect(
      within(band).getByRole("link", { name: /legal & company information/i })
    ).toBeInTheDocument()
  })

  it("renders its children inside the band, not beside it", () => {
    expect(within(renderBand()).getByTestId("content")).toHaveTextContent("hello")
  })

  it("leaves the mobile top bar and the 55% switcher clamp outside and untouched", () => {
    const band = renderBand()
    const topbar = screen.getByTestId("mobile-topbar")
    // The bar is a SIBLING of the band; a tier applied to the wrong element
    // would swallow the sticky chrome.
    expect(band.contains(topbar)).toBe(false)
    expect(topbar).toHaveClass("md:hidden")
    // The switcher's width clamp is protected and deliberately not swept into
    // the tier vocabulary — shop-switcher.test.tsx reasons about it at 375px.
    const clamp = within(topbar).getByTestId("shop-switcher").parentElement
    expect(clamp).toHaveClass("max-w-[55%]")
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
      Sidebar: () => ReactElement
    }
    // The sidebar mounts a ShopSwitcher, which now reads the shared provider.
    render(
      <ShopSwitcherProvider>
        <Sidebar />
      </ShopSwitcherProvider>
    )
    const link = screen.getByRole("link", { name: /go live/i })
    expect(link).toHaveAttribute("href", "/dashboard/onboarding")
  })
})

/**
 * The "Developers" navigation item (37-46, UI-SPEC § E1 "Route and nav"; T-37-113).
 *
 * Shown only to a Group admin, as the server's staff/me answer decides it (`isGroupAdmin` from the
 * shell's ShopSwitcherProvider), placed after "Webhooks", with the Code2 icon, in BOTH dashboard
 * navs: the desktop sidebar and the mobile "More" sheet, which share one `navigation` array. A shop
 * manager does not see it. The page refuses a direct visit as well, and the server refuses every
 * credential call from anyone else (37-45), so this is the honest view, not the boundary.
 *
 * Spelled out as literal `it(` blocks rather than `describe.each` so the docs-freshness counter and
 * the executed count stay the same number (see vendor-signout-affordance.test.tsx).
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { Sidebar } from "@/components/dashboard/sidebar"
import { MobileTabBar } from "@/components/dashboard/mobile-tab-bar"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { fetchMyShops } from "@/lib/shops-api"

jest.mock("@/lib/vendor-logout", () => ({
  vendorLogout: jest.fn(() => new Promise<string>(() => {})),
  VENDOR_LOGOUT_TIMEOUT_MS: 3000,
}))

jest.mock("@/lib/shops-api", () => ({ fetchMyShops: jest.fn() }))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>

jest.mock("next/navigation", () => ({
  usePathname: () => "/dashboard/webhooks",
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), refresh: jest.fn() }),
}))

const USER = "11111111-1111-1111-1111-111111111111"
const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"

function answer(groupAdmin: boolean) {
  return {
    shops: [{ id: SHOP_A, name: "Peckham Kitchen", published: true }],
    isGroupAdmin: groupAdmin,
    userId: USER,
    access: {
      userId: USER,
      groupAdmin,
      grantedShopIds: groupAdmin ? null : [SHOP_A],
      tenantWideRole: null,
    },
  } as never
}

/** The desktop sidebar's link names, in order, once staff/me has answered. */
async function sidebarLinks(groupAdmin: boolean): Promise<string[]> {
  mockedFetchMyShops.mockResolvedValue(answer(groupAdmin))
  const { container } = render(
    <ShopSwitcherProvider>
      <Sidebar />
    </ShopSwitcherProvider>
  )
  await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
  // Settled: the shop switcher has left its loading state, so the provider holds the staff/me
  // answer. Without this a "not shown" read before the answer arrives would pass vacuously.
  await waitFor(() => expect(screen.getByTestId("shop-switcher")).not.toHaveAttribute("aria-busy", "true"))
  return within(container.querySelector("nav") as HTMLElement)
    .getAllByRole("link")
    .map((a) => a.textContent?.trim() ?? "")
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe("Developers nav item (37-46)", () => {
  it("a Group admin sees Developers right after Webhooks, linking to /dashboard/developers", async () => {
    const links = await sidebarLinks(true)
    expect(links.indexOf("Developers")).toBe(links.indexOf("Webhooks") + 1)
    const link = screen.getByRole("link", { name: "Developers" })
    expect(link).toHaveAttribute("href", "/dashboard/developers")
    // The Code2 icon (lucide renders it as the code-xml glyph).
    expect(link.querySelector("svg")?.getAttribute("class")).toMatch(/lucide-code/)
  })

  it("a shop manager does not see Developers, nor the other Group-admin pages Webhooks and Staff", async () => {
    // SW-TND-05 (swarm epic #964): Webhooks and Staff refuse anyone but a Group admin, so they are
    // gated like Developers now (they used to be listed for everyone and refused on the page).
    const links = await sidebarLinks(false)
    expect(links).not.toContain("Developers")
    expect(links).not.toContain("Webhooks")
    expect(links).not.toContain("Staff")
    expect(links).toContain("Shops")
  })

  it("the mobile More sheet shows Developers to a Group admin", async () => {
    mockedFetchMyShops.mockResolvedValue(answer(true))
    render(
      <ShopSwitcherProvider>
        <MobileTabBar />
      </ShopSwitcherProvider>
    )
    await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
    // Let the provider apply the resolved staff/me answer (the Group admin arm is the control
    // that this flush is enough for Developers to appear).
    await act(async () => {})
    fireEvent.click(screen.getByRole("button", { name: /more navigation/i }))
    expect(await screen.findByRole("link", { name: "Developers" })).toHaveAttribute(
      "href",
      "/dashboard/developers"
    )
  })

  it("the mobile More sheet hides Developers from a shop manager, and outside the shell", async () => {
    mockedFetchMyShops.mockResolvedValue(answer(false))
    const { unmount } = render(
      <ShopSwitcherProvider>
        <MobileTabBar />
      </ShopSwitcherProvider>
    )
    await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
    // Let the provider apply the resolved staff/me answer (the Group admin arm is the control
    // that this flush is enough for Developers to appear).
    await act(async () => {})
    fireEvent.click(screen.getByRole("button", { name: /more navigation/i }))
    expect(await screen.findByRole("link", { name: "Shops" })).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: "Developers" })).toBeNull()
    unmount()

    // No provider (a page rendered on its own): nothing is known, so nothing admin-only is offered.
    render(<MobileTabBar />)
    fireEvent.click(screen.getByRole("button", { name: /more navigation/i }))
    expect(await screen.findByRole("link", { name: "Shops" })).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: "Developers" })).toBeNull()
  })
})

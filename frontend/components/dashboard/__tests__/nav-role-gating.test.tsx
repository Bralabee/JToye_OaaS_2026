/**
 * Dashboard navigation is gated by the role that can use each page (SW-TND-05, swarm epic #964).
 *
 * The 2026-10-19 staging swarm found a STAFF member's menu offering every owner page (Finance, Staff,
 * Webhooks, Approvals, Go live, Image review, Marketing) that then refused them. Each item now carries
 * the role its page and API need, decided from the shell's staff/me answer (the same server answer the
 * Developers item has used since 37-46), in BOTH navs: the desktop sidebar and the mobile "More" sheet.
 * The server stays the boundary; this is the honest menu.
 *
 * Role floors, read from core-java: Staff, Webhooks, Approvals and Developers are Group admin
 * (requireGroupAdmin / hasRole('admin')); Finance, Go live, Marketing and Image review need SHOP_MANAGER
 * on a shop (finance summary, onboarding lifecycle, promotion writes, media keep/reprocess). Customers
 * stays for STAFF: the owner ruled (2026-10-09, option B) that counter staff may look a customer up.
 *
 * Literal `it(` blocks (no `describe.each`) so the docs-freshness counter matches the executed count.
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
  usePathname: () => "/dashboard",
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), refresh: jest.fn() }),
}))

const USER = "11111111-1111-1111-1111-111111111111"
const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-1111-1111-1111-111111111111"

const OWNER_ONLY = ["Staff", "Webhooks", "Approvals", "Developers"]
const MANAGER_UP = ["Finance", "Go live", "Marketing", "Image review"]
const EVERYONE = ["Dashboard", "Shops", "Products", "Orders", "Customers", "Kitchen"]

type Who = "groupAdmin" | "manager" | "staff" | "mixed"

function answer(who: Who) {
  const groupAdmin = who === "groupAdmin"
  const shopRoles =
    who === "groupAdmin"
      ? null
      : who === "manager"
        ? { [SHOP_A]: "SHOP_MANAGER" }
        : who === "staff"
          ? { [SHOP_A]: "STAFF" }
          : { [SHOP_A]: "STAFF", [SHOP_B]: "SHOP_MANAGER" }
  return {
    shops: [{ id: SHOP_A, name: "Peckham Kitchen", published: true }],
    isGroupAdmin: groupAdmin,
    userId: USER,
    access: {
      userId: USER,
      groupAdmin,
      grantedShopIds: groupAdmin ? null : Object.keys(shopRoles ?? {}),
      tenantWideRole: null,
      businessName: null,
      shopRoles,
    },
  } as never
}

async function sidebarLinks(who: Who): Promise<string[]> {
  mockedFetchMyShops.mockResolvedValue(answer(who))
  const { container } = render(
    <ShopSwitcherProvider>
      <Sidebar />
    </ShopSwitcherProvider>
  )
  await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
  // Settled: without this a "not shown" read before the answer arrives would pass vacuously.
  await waitFor(() => expect(screen.getByTestId("shop-switcher")).not.toHaveAttribute("aria-busy", "true"))
  return within(container.querySelector("nav") as HTMLElement)
    .getAllByRole("link")
    .map((a) => a.textContent?.trim() ?? "")
}

async function moreSheetLinks(who: Who): Promise<string[]> {
  mockedFetchMyShops.mockResolvedValue(answer(who))
  render(
    <ShopSwitcherProvider>
      <MobileTabBar />
    </ShopSwitcherProvider>
  )
  await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
  await act(async () => {})
  fireEvent.click(screen.getByRole("button", { name: /more navigation/i }))
  // Shops is in the More sheet for every role, so its presence proves the sheet rendered.
  await screen.findByRole("link", { name: "Shops" })
  return screen.getAllByRole("link").map((a) => a.textContent?.trim() ?? "")
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe("dashboard nav role gating (SW-TND-05)", () => {
  it("a Group admin's sidebar lists every page", async () => {
    const links = await sidebarLinks("groupAdmin")
    for (const name of [...EVERYONE, ...MANAGER_UP, ...OWNER_ONLY]) expect(links).toContain(name)
  })

  it("a shop manager's sidebar lists the manager pages and none of the Group-admin pages", async () => {
    const links = await sidebarLinks("manager")
    for (const name of [...EVERYONE, ...MANAGER_UP]) expect(links).toContain(name)
    for (const name of OWNER_ONLY) expect(links).not.toContain(name)
  })

  it("a STAFF member's sidebar lists only the pages STAFF can use", async () => {
    const links = await sidebarLinks("staff")
    expect(links).toEqual(EVERYONE)
  })

  it("a caller who manages one shop and is STAFF on another sees the manager pages", async () => {
    const links = await sidebarLinks("mixed")
    for (const name of MANAGER_UP) expect(links).toContain(name)
    for (const name of OWNER_ONLY) expect(links).not.toContain(name)
  })

  it("a STAFF member's mobile More sheet offers no manager or Group-admin page", async () => {
    const links = await moreSheetLinks("staff")
    for (const name of ["Shops", "Customers"]) expect(links).toContain(name)
    for (const name of [...MANAGER_UP, ...OWNER_ONLY]) expect(links).not.toContain(name)
  })

  it("a shop manager's mobile More sheet offers the manager pages but no Group-admin page", async () => {
    const links = await moreSheetLinks("manager")
    for (const name of MANAGER_UP) expect(links).toContain(name)
    for (const name of OWNER_ONLY) expect(links).not.toContain(name)
  })

  it("review R1-2: when staff/me cannot be read, the role is UNKNOWN, so nothing is hidden on it (only Developers, as before #1076)", async () => {
    mockedFetchMyShops.mockRejectedValue(Object.assign(new Error("Network Error"), { response: { status: 503 } }))
    const { container } = render(
      <ShopSwitcherProvider>
        <Sidebar />
      </ShopSwitcherProvider>
    )
    await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByTestId("shop-switcher")).not.toHaveAttribute("aria-busy", "true"))
    const links = within(container.querySelector("nav") as HTMLElement)
      .getAllByRole("link")
      .map((a) => a.textContent?.trim() ?? "")
    for (const name of [...EVERYONE, ...MANAGER_UP, "Staff", "Webhooks", "Approvals"]) {
      expect(links).toContain(name)
    }
    // Developers was never offered on a guess (37-46, T-37-113), and still is not.
    expect(links).not.toContain("Developers")
  })

  it("review R1-2: the mobile More sheet offers the same items when staff/me cannot be read", async () => {
    mockedFetchMyShops.mockRejectedValue(new Error("Network Error"))
    render(
      <ShopSwitcherProvider>
        <MobileTabBar />
      </ShopSwitcherProvider>
    )
    await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
    await act(async () => {})
    fireEvent.click(screen.getByRole("button", { name: /more navigation/i }))
    await screen.findByRole("link", { name: "Shops" })
    const links = screen.getAllByRole("link").map((a) => a.textContent?.trim() ?? "")
    for (const name of [...MANAGER_UP, "Staff", "Webhooks", "Approvals"]) expect(links).toContain(name)
    expect(links).not.toContain("Developers")
  })

  it("review R1-2: a RESOLVED shop-manager answer still hides Approvals and the other Group-admin pages", async () => {
    const links = await sidebarLinks("manager")
    expect(links).not.toContain("Approvals")
    expect(links).not.toContain("Staff")
  })
})

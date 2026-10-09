/**
 * D-08 (37-06, UI-SPEC § B3) — a signed-in person with no access gets one honest
 * page inside the dashboard shell, instead of a dashboard of empty states.
 *
 * Driven through the real `DashboardShell` and `Sidebar`: the access fact comes
 * from `GET /api/v1/staff/me` (through `fetchMyShops`, the shell's one staff/me
 * read), and a dashboard read refused with `shop-access-denied` is signalled by the
 * api-client as a window event, which makes the shell re-read staff/me.
 *
 * The heading is chosen by data (T-37-13): "You no longer have access" only after
 * this browser session has SEEN access; otherwise "You don't have access yet".
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { usePathname } from "next/navigation"
import { DashboardShell } from "../dashboard-shell"
import { fetchMyShops } from "@/lib/shops-api"
import { vendorLogout } from "@/lib/vendor-logout"

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
}))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>

jest.mock("@/lib/vendor-logout", () => ({
  vendorLogout: jest.fn(() => new Promise(() => {})),
}))
const mockedVendorLogout = vendorLogout as jest.MockedFunction<typeof vendorLogout>

const mockedUsePathname = usePathname as jest.MockedFunction<typeof usePathname>

/** The event name the api-client dispatches on a shop-access-denied 403. */
const ACCESS_DENIED_EVENT = "jtoye:shop-access-denied"

const USER = "11111111-1111-1111-1111-111111111111"
const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const shops = [{ id: SHOP_A, name: "Peckham Kitchen", published: true }]

type Access = {
  userId: string
  groupAdmin: boolean
  grantedShopIds: string[] | null
  tenantWideRole: string | null
}
const NONE: Access = { userId: USER, groupAdmin: false, grantedShopIds: [], tenantWideRole: null }
const ONE_SHOP: Access = { userId: USER, groupAdmin: false, grantedShopIds: [SHOP_A], tenantWideRole: null }

/** What `fetchMyShops` resolves to for a given staff/me answer. */
function myShops(access: Access) {
  return {
    shops: access.grantedShopIds?.length ? shops : [],
    isGroupAdmin: access.groupAdmin,
    userId: access.userId,
    access,
  } as never
}

const HEADING_NO_LONGER = "You no longer have access"
const HEADING_YET = "You don't have access yet"

function renderShell() {
  return render(
    <DashboardShell>
      <div data-testid="page-content">Orders page</div>
    </DashboardShell>
  )
}

beforeEach(() => {
  jest.clearAllMocks()
  mockedUsePathname.mockReturnValue("/dashboard/orders")
})

afterEach(() => {
  mockedUsePathname.mockReturnValue("/")
})

describe("NoAccessPage in the dashboard shell (D-08)", () => {
  it("shows 'You don't have access yet' in place of the page when staff/me has no access", async () => {
    mockedFetchMyShops.mockResolvedValue(myShops(NONE))
    renderShell()

    expect(
      await screen.findByRole("heading", { level: 1, name: HEADING_YET })
    ).toBeInTheDocument()
    expect(screen.queryByTestId("page-content")).toBeNull()
    expect(
      screen.getByText(/no one at .+ has given you access to a shop/i)
    ).toBeInTheDocument()
  })

  /**
   * FAIL ARM (T-37-13). With no access ever observed in this session, the page must
   * not claim access was taken away.
   */
  it("never says 'You no longer have access' without having seen access in this session", async () => {
    mockedFetchMyShops.mockResolvedValue(myShops(NONE))
    renderShell()

    await screen.findByRole("button", { name: /check access again/i })
    expect(screen.queryByText(HEADING_NO_LONGER)).toBeNull()
  })

  it("says 'You no longer have access' when access seen earlier is gone on a refused read", async () => {
    mockedFetchMyShops.mockResolvedValueOnce(myShops(ONE_SHOP))
    mockedFetchMyShops.mockResolvedValue(myShops(NONE))
    renderShell()

    expect(await screen.findByTestId("page-content")).toBeInTheDocument()

    act(() => {
      window.dispatchEvent(new CustomEvent(ACCESS_DENIED_EVENT))
    })

    expect(
      await screen.findByRole("heading", { level: 1, name: HEADING_NO_LONGER })
    ).toBeInTheDocument()
    expect(screen.queryByTestId("page-content")).toBeNull()
    expect(mockedFetchMyShops).toHaveBeenCalledTimes(2)
  })

  it("keeps the page when a refused read leaves the person some access", async () => {
    mockedFetchMyShops.mockResolvedValue(myShops(ONE_SHOP))
    renderShell()

    expect(await screen.findByTestId("page-content")).toBeInTheDocument()
    act(() => {
      window.dispatchEvent(new CustomEvent(ACCESS_DENIED_EVENT))
    })

    await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalledTimes(2))
    expect(screen.getByTestId("page-content")).toBeInTheDocument()
    expect(screen.queryByText(HEADING_NO_LONGER)).toBeNull()
    expect(screen.queryByText(HEADING_YET)).toBeNull()
  })

  it("'Check access again' reads 'Checking…' while in flight, then brings the page back", async () => {
    mockedFetchMyShops.mockResolvedValueOnce(myShops(NONE))
    let release: (v: unknown) => void = () => {}
    mockedFetchMyShops.mockImplementationOnce(
      () => new Promise((resolve) => (release = resolve)) as never
    )
    renderShell()

    const button = await screen.findByRole("button", { name: /check access again/i })
    // The accent button (Button default variant, orange-700), not an outline one.
    expect(button).toHaveClass("bg-primary")
    fireEvent.click(button)

    const pending = await screen.findByRole("button", { name: /checking…/i })
    expect(pending).toBeDisabled()

    await act(async () => {
      release(myShops(ONE_SHOP))
    })

    expect(await screen.findByTestId("page-content")).toBeInTheDocument()
    expect(screen.queryByText(HEADING_YET)).toBeNull()
  })

  it("keeps the page and says so when the re-check itself fails", async () => {
    mockedFetchMyShops.mockResolvedValueOnce(myShops(NONE))
    mockedFetchMyShops.mockRejectedValueOnce(new Error("Network Error"))
    renderShell()

    fireEvent.click(await screen.findByRole("button", { name: /check access again/i }))

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(
      "Couldn't check your access. Check your connection and try again."
    )
    expect(screen.getByRole("heading", { level: 1, name: HEADING_YET })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /check access again/i })).toBeEnabled()
  })

  it("keeps Sign out reachable and hides navigation that would be refused", async () => {
    mockedFetchMyShops.mockResolvedValue(myShops(NONE))
    renderShell()

    const heading = await screen.findByRole("heading", { level: 1, name: HEADING_YET })
    const page = heading.closest("[data-testid='no-access-page']") as HTMLElement
    expect(page).not.toBeNull()
    fireEvent.click(within(page).getByRole("button", { name: /sign out/i }))
    expect(mockedVendorLogout).toHaveBeenCalledTimes(1)

    for (const name of [/^orders$/i, /^products$/i, /^kitchen$/i, /^staff$/i]) {
      expect(screen.queryAllByRole("link", { name })).toHaveLength(0)
    }
  })

  it("shows the navigation when the person has access (control)", async () => {
    mockedFetchMyShops.mockResolvedValue(myShops(ONE_SHOP))
    renderShell()

    expect(await screen.findByTestId("page-content")).toBeInTheDocument()
    expect(screen.getAllByRole("link", { name: /^orders$/i }).length).toBeGreaterThan(0)
  })

  it("leaves the kitchen route to its own board-stopped state", async () => {
    mockedUsePathname.mockReturnValue("/dashboard/kitchen")
    mockedFetchMyShops.mockResolvedValue(myShops(NONE))
    renderShell()

    expect(await screen.findByTestId("page-content")).toBeInTheDocument()
    await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
    expect(screen.queryByText(HEADING_YET)).toBeNull()
  })

  it("decides nothing while staff/me has not answered", async () => {
    mockedFetchMyShops.mockImplementation((() => new Promise(() => {})) as never)
    renderShell()

    expect(screen.getByTestId("page-content")).toBeInTheDocument()
    expect(screen.queryByText(HEADING_YET)).toBeNull()
  })
})

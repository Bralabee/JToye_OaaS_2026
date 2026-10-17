/**
 * 37-12 Task 1 (UI-SPEC § B5, RWO-027 / UXT-027): per-shop finance.
 *
 * The page sends the shop switcher's selection as ?shopId= and its banner reads the
 * scope the API ECHOED, never the switcher's own value (say is not data, T-37-33).
 * A shop manager on All shops sees "Choose a shop to see its figures" and no tenant
 * totals are requested; STAFF keeps "Admin access required" (goods P2-TUN-15).
 * Reversal rows carry a "Reversal" badge, the server's "Reversal of {order}" reference
 * and a real minus sign (U+2212); the voided sale stays with a "Voided" badge.
 * "Export CSV" downloads exactly the scope shown, under the server's filename.
 *
 * Rendered inside the REAL ShopSwitcherProvider (only its data source, fetchMyShops,
 * is mocked), and the selection is the real localStorage-backed shop context, so the
 * test drives the page the way the dashboard shell does.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import FinancePage from "../page"
import DashboardPage from "../../page"
import apiClient from "@/lib/api-client"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { fetchAllMyShops, fetchMyShops, type MyAccess } from "@/lib/shops-api"
import { setShopContext } from "@/lib/shop-context"
import { formatSignedPennies } from "@/lib/finance-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
  fetchAllMyShops: jest.fn(),
  fetchMyAccess: jest.fn(),
}))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>
const mockedFetchAllMyShops = fetchAllMyShops as jest.MockedFunction<typeof fetchAllMyShops>

// The dashboard home page charts with recharts; jsdom has no layout, so stub it.
jest.mock("recharts", () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  PieChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Pie: () => null,
  Cell: () => null,
  BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Bar: () => null,
  XAxis: () => null,
  YAxis: () => null,
  CartesianGrid: () => null,
  Tooltip: () => null,
  Legend: () => null,
}))

const SHOP_A = "11111111-1111-1111-1111-111111111111"
const SHOP_B = "22222222-2222-2222-2222-222222222222"
const SHOP_A_NAME = "Mama Ade's Kitchen"
const SHOP_B_NAME = "Suya Spot Digbeth"
const SHOPS = [
  { id: SHOP_A, name: SHOP_A_NAME, slug: "mama-ades-kitchen" },
  { id: SHOP_B, name: SHOP_B_NAME, slug: "suya-spot-digbeth" },
]
const SERVER_FILENAME = "jtoye-finance-mama-ades-kitchen-start-2026-10-09.csv"

type Scope = { shopId: string | null; shopName: string | null; from: string | null; to: string | null }

/** What the mocked API echoes as its scope. Tests overwrite it to stage a disagreement. */
let echoedScope: Scope | "from-request" = "from-request"
let listContent: unknown[] = []
let listTotal = 0
let exportFails = false
let summaryStatus = 200
/** When set, the summary figures the mocked API serves instead of its fixed ones (G-37-6). */
let summaryOverride: Record<string, unknown> | null = null

function access(over: Partial<MyAccess>): MyAccess {
  return {
    userId: "user-1",
    groupAdmin: false,
    grantedShopIds: [SHOP_A, SHOP_B],
    tenantWideRole: null,
    businessName: "Ade Foods Ltd",
    ...over,
  }
}

function scopeFor(url: string): Scope {
  if (echoedScope !== "from-request") return echoedScope
  const shopId = new URL(url, "http://x").searchParams.get("shopId")
  return {
    shopId,
    shopName: shopId ? SHOPS.find((s) => s.id === shopId)?.name ?? null : null,
    from: null,
    to: null,
  }
}

function forbidden() {
  return Promise.reject({
    response: { status: 403, data: { type: "https://jtoye.uk/errors/shop-access-denied", status: 403 } },
  })
}

function apiMock(url: string) {
  if (url.startsWith("/api/v1/financial-transactions/summary")) {
    if (summaryStatus === 403) return forbidden()
    return Promise.resolve({
      data: {
        ...(summaryOverride ?? {
          totalRevenuePennies: 4500,
          totalExpensesPennies: 0,
          netAmountPennies: 4500,
          totalVatPennies: 750,
          transactionCount: 3,
          vatBreakdown: [],
        }),
        scope: scopeFor(url),
      },
    })
  }
  if (url.startsWith("/api/v1/financial-transactions/export.csv")) {
    if (exportFails) return Promise.reject({ response: { status: 500 } })
    return Promise.resolve({
      data: new Blob(["date,reference\n"], { type: "text/csv" }),
      headers: {
        "content-disposition": `attachment; filename="${SERVER_FILENAME}"`,
        "x-finance-scope": "shopId=x",
      },
    })
  }
  if (url.startsWith("/api/v1/financial-transactions")) {
    if (summaryStatus === 403) return forbidden()
    return Promise.resolve({
      data: { content: listContent, totalPages: 1, totalElements: listTotal, scope: scopeFor(url) },
    })
  }
  if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject({ response: { status: 404 } })
  return Promise.resolve({ data: { content: [], totalElements: 0 } })
}

const financeCalls = () =>
  mockedApiClient.get.mock.calls
    .map((c) => String(c[0]))
    .filter((u) => u.startsWith("/api/v1/financial-transactions"))

function renderFinance(acc: MyAccess, selected: string | null) {
  if (selected) window.localStorage.setItem("shopContext", selected)
  mockedFetchMyShops.mockResolvedValue({
    shops: SHOPS as never,
    isGroupAdmin: acc.groupAdmin,
    userId: acc.userId,
    access: acc,
  })
  return render(
    <ShopSwitcherProvider>
      <FinancePage />
    </ShopSwitcherProvider>
  )
}

const banner = () => screen.getByTestId("finance-scope-banner")

beforeEach(() => {
  jest.clearAllMocks()
  window.localStorage.clear()
  echoedScope = "from-request"
  listContent = []
  listTotal = 0
  exportFails = false
  summaryStatus = 200
  summaryOverride = null
  mockedApiClient.get.mockImplementation(apiMock as never)
  mockedFetchAllMyShops.mockResolvedValue(SHOPS as never)
})

describe("Finance scope banner (UI-SPEC § B5 'Scope banner')", () => {
  it("sends the switcher's shop as ?shopId= and the banner names the shop the API filtered on", async () => {
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    await waitFor(() => expect(banner()).toHaveTextContent(`Showing ${SHOP_A_NAME} only`))
    expect(financeCalls().some((u) => u.startsWith("/api/v1/financial-transactions/summary") && u.includes(`shopId=${SHOP_A}`))).toBe(true)
    expect(financeCalls().some((u) => u.startsWith("/api/v1/financial-transactions?") && u.includes(`shopId=${SHOP_A}`))).toBe(true)
    expect(within(banner()).getByText(SHOP_A_NAME).tagName).toBe("STRONG")
  })

  it("shows the API's scope when it disagrees with the switcher: 'Showing all shops' though shop A is selected", async () => {
    echoedScope = { shopId: null, shopName: null, from: null, to: null }
    renderFinance(access({ groupAdmin: true, grantedShopIds: null }), SHOP_A)

    await waitFor(() => expect(banner()).toHaveTextContent("Showing all shops"))
    expect(banner()).not.toHaveTextContent(SHOP_A_NAME)
  })

  it("names the shop the API echoed, not the one the switcher holds", async () => {
    echoedScope = { shopId: SHOP_B, shopName: SHOP_B_NAME, from: null, to: null }
    renderFinance(access({ groupAdmin: true, grantedShopIds: null }), SHOP_A)

    await waitFor(() => expect(banner()).toHaveTextContent(`Showing ${SHOP_B_NAME} only`))
  })

  it("a Group admin on All shops reads the tenant totals, with no shopId, under 'Showing all shops'", async () => {
    renderFinance(access({ groupAdmin: true, grantedShopIds: null }), null)

    await waitFor(() => expect(banner()).toHaveTextContent("Showing all shops"))
    expect(financeCalls()).toContain("/api/v1/financial-transactions/summary")
    expect(financeCalls().every((u) => !u.includes("shopId="))).toBe(true)
  })

  it("wraps a long shop name in the banner instead of truncating it", async () => {
    const long = "The Very Long Named Nigerian Kitchen And Grill Of Digbeth High Street Birmingham".repeat(3)
    echoedScope = { shopId: SHOP_A, shopName: long, from: null, to: null }
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    await waitFor(() => expect(banner()).toHaveTextContent(long))
    expect(banner().className).toContain("[overflow-wrap:anywhere]")
    expect(banner().className).not.toMatch(/\btruncate\b/)
  })

  it("re-runs the load when the switcher changes shop", async () => {
    renderFinance(access({ grantedShopIds: [SHOP_A, SHOP_B] }), SHOP_A)
    await waitFor(() => expect(banner()).toHaveTextContent(`Showing ${SHOP_A_NAME} only`))

    act(() => setShopContext(SHOP_B))

    await waitFor(() => expect(banner()).toHaveTextContent(`Showing ${SHOP_B_NAME} only`))
    expect(financeCalls().some((u) => u.includes(`shopId=${SHOP_B}`))).toBe(true)
  })
})

describe("Who sees which figures (UI-SPEC § B5 rows 'Shop manager' and 'Staff')", () => {
  it("a shop manager on All shops sees 'Choose a shop to see its figures' and no tenant totals are requested", async () => {
    renderFinance(access({ grantedShopIds: [SHOP_A] }), null)

    await waitFor(() => expect(screen.getByText("Choose a shop to see its figures")).toBeInTheDocument())
    expect(screen.getByText("Pick one of your shops in the shop switcher at the top of the page.")).toBeInTheDocument()
    expect(financeCalls()).toEqual([])
    expect(screen.queryByText("Revenue")).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /export csv/i })).not.toBeInTheDocument()
  })

  it("a manager with exactly ONE shop on All shops reads that shop: the switcher is a pinned label with nothing to pick (37-15 live run)", async () => {
    // Measured on the rebuilt compose stack: the server narrows a one-grant manager's
    // shop list to one shop, the switcher renders a pinned label (no <select>), the shop
    // context stays "all", and the page asked them to "Pick one of your shops in the shop
    // switcher" that offers no choice. The pinned shop IS their context.
    window.localStorage.clear()
    mockedFetchMyShops.mockResolvedValue({
      shops: [SHOPS[0]] as never,
      isGroupAdmin: false,
      userId: "user-1",
      access: access({ grantedShopIds: [SHOP_A] }),
    })
    render(
      <ShopSwitcherProvider>
        <FinancePage />
      </ShopSwitcherProvider>
    )

    await waitFor(() => expect(banner()).toHaveTextContent(`Showing ${SHOP_A_NAME} only`))
    expect(screen.queryByText("Choose a shop to see its figures")).not.toBeInTheDocument()
    expect(financeCalls().some((u) => u.startsWith("/api/v1/financial-transactions/summary") && u.includes(`shopId=${SHOP_A}`))).toBe(true)
    expect(financeCalls().every((u) => u.includes(`shopId=${SHOP_A}`))).toBe(true)
  })

  it("STAFF with a shop selected keeps 'Admin access required' when the server refuses", async () => {
    summaryStatus = 403
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    await waitFor(() => expect(screen.getByText("Admin access required")).toBeInTheDocument())
    expect(screen.queryByText("Choose a shop to see its figures")).not.toBeInTheDocument()
  })

  it("tenant-wide STAFF on All shops keeps 'Admin access required' and requests nothing", async () => {
    renderFinance(access({ tenantWideRole: "STAFF" }), null)

    await waitFor(() => expect(screen.getByText("Admin access required")).toBeInTheDocument())
    expect(financeCalls()).toEqual([])
  })
})

describe("Reversal rows (UI-SPEC § B5 'Reversal rows')", () => {
  const sale = {
    id: "tx-sale",
    tenantId: "t1",
    amountPennies: 2450,
    vatRate: "STANDARD",
    vatAmountPennies: 408,
    description: "Order ORD-1042",
    createdAt: "2026-10-08T12:00:00Z",
    entryKind: "SALE",
    orderId: "order-1",
    reversesTransactionId: null,
    shopId: SHOP_A,
  }
  const reversal = {
    ...sale,
    id: "tx-rev",
    amountPennies: -2450,
    vatAmountPennies: -408,
    description: "Reversal of ORD-1042",
    createdAt: "2026-10-08T13:00:00Z",
    entryKind: "REVERSAL",
    reversesTransactionId: "tx-sale",
  }

  it("shows a Reversal badge, the 'Reversal of' reference and a real minus sign; the sale stays with a Voided badge", async () => {
    listContent = [reversal, sale]
    listTotal = 2
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    const table = await screen.findByRole("region", { name: "Financial transactions table" })
    const rows = within(table).getAllByRole("row").slice(1)
    expect(rows).toHaveLength(2)

    const revRow = rows.find((r) => within(r).queryByText("Reversal of ORD-1042"))!
    expect(revRow).toBeDefined()
    expect(within(revRow).getByText("Reversal")).toBeInTheDocument()
    expect(revRow).toHaveTextContent("−£24.50")
    expect(revRow.textContent).not.toContain("-£24.50")
    expect(within(revRow).getByText("Reversal of ORD-1042").className).toContain("[overflow-wrap:anywhere]")

    const saleRow = rows.find((r) => within(r).queryByText("Order ORD-1042"))!
    expect(saleRow).toBeDefined()
    expect(within(saleRow).getByText("Voided")).toBeInTheDocument()
    expect(saleRow).toHaveTextContent("£24.50")
    expect(saleRow.textContent).not.toContain("−")
  })

  it("a sale with no reversal on the page carries no Voided badge", async () => {
    listContent = [sale]
    listTotal = 1
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    const table = await screen.findByRole("region", { name: "Financial transactions table" })
    expect(within(table).queryByText("Voided")).not.toBeInTheDocument()
    expect(within(table).queryByText("Reversal")).not.toBeInTheDocument()
  })
})

describe("Export CSV (UI-SPEC § B5 'Export')", () => {
  let created: Blob[] = []
  let downloads: string[] = []
  const origCreate = URL.createObjectURL
  const origRevoke = URL.revokeObjectURL
  let clickSpy: jest.SpyInstance

  beforeEach(() => {
    created = []
    downloads = []
    URL.createObjectURL = jest.fn((b: Blob) => {
      created.push(b)
      return "blob:finance"
    }) as unknown as typeof URL.createObjectURL
    URL.revokeObjectURL = jest.fn()
    clickSpy = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push(this.download)
    })
  })
  afterEach(() => {
    URL.createObjectURL = origCreate
    URL.revokeObjectURL = origRevoke
    clickSpy.mockRestore()
  })

  it("downloads exactly the scope shown, under the server's filename", async () => {
    listContent = []
    listTotal = 3
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    const button = await screen.findByRole("button", { name: /export csv/i })
    expect(button.className).toContain("h-11")
    fireEvent.click(button)

    await waitFor(() => expect(downloads).toEqual([SERVER_FILENAME]))
    const exportCall = mockedApiClient.get.mock.calls.find((c) => String(c[0]).includes("export.csv"))!
    expect(String(exportCall[0])).toBe(`/api/v1/financial-transactions/export.csv?shopId=${SHOP_A}`)
    expect(exportCall[1]).toEqual({ responseType: "blob" })
    expect(created).toHaveLength(1)
  })

  it("exports the scope the API echoed, not the switcher's value", async () => {
    echoedScope = { shopId: SHOP_B, shopName: SHOP_B_NAME, from: "2026-10-01", to: "2026-10-08" }
    listTotal = 3
    renderFinance(access({ groupAdmin: true, grantedShopIds: null }), SHOP_A)

    fireEvent.click(await screen.findByRole("button", { name: /export csv/i }))

    await waitFor(() => expect(downloads).toHaveLength(1))
    const exportCall = mockedApiClient.get.mock.calls.find((c) => String(c[0]).includes("export.csv"))!
    expect(String(exportCall[0])).toBe(
      `/api/v1/financial-transactions/export.csv?shopId=${SHOP_B}&from=2026-10-01&to=2026-10-08`
    )
  })

  it("a failed export says so inline beside the button, and toasts", async () => {
    exportFails = true
    listTotal = 3
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    fireEvent.click(await screen.findByRole("button", { name: /export csv/i }))

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("Couldn't export. Try again.")
    expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({ variant: "destructive" }))
    expect(downloads).toEqual([])
  })

  it("an empty range still downloads the server's header-only file and says there were no transactions", async () => {
    listTotal = 0
    renderFinance(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    fireEvent.click(await screen.findByRole("button", { name: /export csv/i }))

    await waitFor(() => expect(downloads).toEqual([SERVER_FILENAME]))
    expect(await screen.findByText("No transactions in this range")).toBeInTheDocument()
  })
})

/*
 * G-37-6 defect 1 (UAT test 6): at 768 px beside the 256 px sidebar the stat cards were
 * about 98 px wide, so "£400.40" was painted under the next card and read "£400.4". The
 * columns were keyed on the VIEWPORT (md:grid-cols-4); what the figures need is the grid's
 * OWN width, so the grids are container-query grids now.
 *
 * jsdom has no layout: these tests assert what jsdom can observe — the declarations
 * (container on the parent, container variants on the grid, no viewport-keyed column
 * token), the whole formatted text, and the figure classes. Whether the declared columns
 * hold the figures at 390/768/1024/1280 is measured live in Chrome by 37-59, and whether
 * Tailwind EMITS each container rule is checked against the built stylesheet.
 */
describe("Stat cards and VAT breakdown fit their own box (G-37-6)", () => {
  // The declared bound: the widest figure under a million pounds, signed.
  const BOUND = 99_999_999
  const BOUND_SUMMARY = {
    totalRevenuePennies: BOUND,
    totalExpensesPennies: BOUND,
    netAmountPennies: -BOUND,
    totalVatPennies: BOUND,
    transactionCount: 12,
    vatBreakdown: [
      { vatRate: "STANDARD", totalAmountPennies: BOUND, totalVatPennies: 16_666_666, count: 4 },
      { vatRate: "REDUCED", totalAmountPennies: -BOUND, totalVatPennies: -4_761_904, count: 3 },
      { vatRate: "ZERO", totalAmountPennies: BOUND, totalVatPennies: 0, count: 3 },
      { vatRate: "EXEMPT", totalAmountPennies: BOUND, totalVatPennies: 0, count: 2 },
    ],
  }

  const VIEWPORT_PREFIX = /^(sm|md|lg|xl|2xl):/
  const tokens = (el: Element) => el.className.split(/\s+/).filter(Boolean)
  const viewportTokens = (el: Element) => tokens(el).filter((t) => VIEWPORT_PREFIX.test(t))
  const containerWidths = (el: Element) =>
    Array.from(new Set(Array.from(el.className.matchAll(/\(min-width:(\d+(?:\.\d+)?)rem\)/g), (m) => m[1])))

  async function renderAtBound() {
    summaryOverride = BOUND_SUMMARY
    renderFinance(access({ groupAdmin: true, grantedShopIds: null }), null)
    await waitFor(() => expect(banner()).toHaveTextContent("Showing all shops"))
  }

  it("stat grid: columns from its own width (28rem two, 57rem four), no viewport-keyed column token", async () => {
    await renderAtBound()

    // Found by structure first, so on a tree without the test id the failure names the
    // viewport tokens it found rather than only "no element".
    const grid = screen.getByText("Revenue").closest(".grid") as HTMLElement
    expect(grid).not.toBeNull()
    expect(viewportTokens(grid)).toEqual([])
    expect(grid.getAttribute("data-testid")).toBe("finance-stat-grid")
    expect(grid).toHaveClass(
      "grid",
      "grid-cols-1",
      "[@container_(min-width:28rem)]:grid-cols-2",
      "[@container_(min-width:57rem)]:grid-cols-4"
    )
    expect(containerWidths(grid)).toEqual(["28", "57"])
    expect(grid.parentElement).toHaveClass("[container-type:inline-size]")
  })

  it("VAT grid: columns from its card's width (27rem two, 54rem four); each tile's text column can shrink", async () => {
    await renderAtBound()

    const grid = screen.getByText("Standard").closest(".grid") as HTMLElement
    expect(grid).not.toBeNull()
    expect(viewportTokens(grid)).toEqual([])
    expect(grid.getAttribute("data-testid")).toBe("finance-vat-grid")
    expect(grid).toHaveClass(
      "grid",
      "grid-cols-1",
      "[@container_(min-width:27rem)]:grid-cols-2",
      "[@container_(min-width:54rem)]:grid-cols-4"
    )
    expect(containerWidths(grid)).toEqual(["27", "54"])
    expect(grid.parentElement).toHaveClass("[container-type:inline-size]")

    expect(grid.children).toHaveLength(4)
    for (const label of ["Standard", "Reduced", "Zero", "Exempt"]) {
      const textColumn = within(grid).getByText(label).parentElement as HTMLElement
      expect(textColumn).toHaveClass("min-w-0", "flex-1")
    }
  })

  it("amounts: every stat and VAT figure is the whole formatted text, tabular, with a wrap safety net", async () => {
    await renderAtBound()

    const amounts = screen.getAllByTestId("finance-amount")
    expect(amounts.map((a) => a.textContent)).toEqual([
      formatSignedPennies(BOUND),
      formatSignedPennies(BOUND),
      formatSignedPennies(-BOUND),
      formatSignedPennies(BOUND),
      formatSignedPennies(BOUND),
      formatSignedPennies(-BOUND),
      formatSignedPennies(BOUND),
      formatSignedPennies(BOUND),
    ])
    // The bound, spelled out once, so a formatter change cannot move both sides together.
    expect(formatSignedPennies(-BOUND)).toBe("−£999,999.99")
    for (const amount of amounts) {
      expect(amount).toHaveClass("tabular-nums", "[overflow-wrap:anywhere]")
      expect(amount.className).not.toMatch(/\btruncate\b/)
    }
  })
})

describe("Dashboard home finance tile (UI-SPEC § B5 'Dashboard home tiles')", () => {
  function renderHome(acc: MyAccess, selected: string | null) {
    if (selected) window.localStorage.setItem("shopContext", selected)
    mockedFetchMyShops.mockResolvedValue({
      shops: SHOPS as never,
      isGroupAdmin: acc.groupAdmin,
      userId: acc.userId,
      access: acc,
    })
    return render(
      <ShopSwitcherProvider>
        <DashboardPage />
      </ShopSwitcherProvider>
    )
  }

  it("a manager with shop A selected gets shop A's summary, footnoted with the API's scope", async () => {
    renderHome(access({ grantedShopIds: [SHOP_A] }), SHOP_A)

    await waitFor(() => expect(screen.getByTestId("finance-tile-scope")).toHaveTextContent(`Showing ${SHOP_A_NAME} only`))
    expect(financeCalls()).toContain(`/api/v1/financial-transactions/summary?shopId=${SHOP_A}`)
  })

  it("a manager on All shops is not sent tenant totals and is told to choose a shop", async () => {
    renderHome(access({ grantedShopIds: [SHOP_A] }), null)

    await waitFor(() => expect(screen.getByTestId("finance-tile-scope")).toHaveTextContent("Choose a shop to see its figures"))
    expect(financeCalls()).toEqual([])
  })

  it("a Group admin on All shops reads the tenant summary, footnoted 'Showing all shops'", async () => {
    renderHome(access({ groupAdmin: true, grantedShopIds: null }), null)

    await waitFor(() => expect(screen.getByTestId("finance-tile-scope")).toHaveTextContent("Showing all shops"))
    expect(financeCalls()).toContain("/api/v1/financial-transactions/summary")
  })
})

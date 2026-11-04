/**
 * #827: the Finance page had no date control. The server has taken `from`/`to` (UK days) on the
 * summary, the list and the CSV export since 37-11, but nothing in the UI could send them, so a
 * vendor wanting "today" or "this month" had to export everything and cut it in a spreadsheet.
 *
 * Asserted through the real page inside the real ShopSwitcherProvider (only the network and the
 * shop list are faked):
 *   - with no range chosen every request is byte-identical to before (no from/to on the wire);
 *   - the presets and the two date inputs send from/to to the summary AND the list, from page 0;
 *   - the CSV export carries the range the server echoed, so the file is what the screen shows;
 *   - an inverted range is refused inline and asks for nothing;
 *   - changing the range does not swap the page for its spinner (the controls keep focus).
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import FinancePage from "../page"
import apiClient from "@/lib/api-client"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { fetchAllMyShops, fetchMyShops, type MyAccess } from "@/lib/shops-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
  fetchAllMyShops: jest.fn(),
  fetchMyAccess: jest.fn(),
}))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>
const mockedFetchAllMyShops = fetchAllMyShops as jest.MockedFunction<typeof fetchAllMyShops>

const SHOP_A = "11111111-1111-1111-1111-111111111111"
const SHOPS = [{ id: SHOP_A, name: "Mama Ade's Kitchen", slug: "mama-ades-kitchen" }]

const ADMIN: MyAccess = {
  userId: "user-1",
  groupAdmin: true,
  grantedShopIds: [SHOP_A],
  tenantWideRole: "GROUP_ADMIN",
  businessName: "Ade Foods Ltd",
} as MyAccess

/** The UK calendar day, the same way the page must compute it (Europe/London, not the machine's zone). */
function ukDay(offsetDays = 0): string {
  const now = new Date()
  const base = new Date(Date.UTC(
    Number(new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/London", year: "numeric" }).format(now)),
    Number(new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/London", month: "numeric" }).format(now)) - 1,
    Number(new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/London", day: "numeric" }).format(now)) + offsetDays
  ))
  return base.toISOString().slice(0, 10)
}

function echo(url: string) {
  const p = new URL(url, "http://x").searchParams
  return { shopId: p.get("shopId"), shopName: null, from: p.get("from"), to: p.get("to") }
}

function apiMock(url: string) {
  if (url.startsWith("/api/v1/financial-transactions/summary")) {
    return Promise.resolve({
      data: {
        totalRevenuePennies: 4500,
        totalExpensesPennies: 0,
        netAmountPennies: 4500,
        totalVatPennies: 750,
        transactionCount: 3,
        vatBreakdown: [],
        scope: echo(url),
      },
    })
  }
  if (url.startsWith("/api/v1/financial-transactions/export.csv")) {
    return Promise.resolve({
      data: new Blob(["date,reference\n"], { type: "text/csv" }),
      headers: { "content-disposition": 'attachment; filename="x.csv"' },
    })
  }
  if (url.startsWith("/api/v1/financial-transactions")) {
    return Promise.resolve({ data: { content: [], totalPages: 1, totalElements: 0, scope: echo(url) } })
  }
  if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject({ response: { status: 404 } })
  return Promise.resolve({ data: { content: [], totalElements: 0 } })
}

const calls = (kind: "summary" | "list" | "export") =>
  mockedApiClient.get.mock.calls
    .map((c) => String(c[0]))
    .filter((u) => {
      if (kind === "summary") return u.startsWith("/api/v1/financial-transactions/summary")
      if (kind === "export") return u.startsWith("/api/v1/financial-transactions/export.csv")
      return u.startsWith("/api/v1/financial-transactions?")
    })

async function renderLoaded() {
  mockedFetchMyShops.mockResolvedValue({
    shops: SHOPS as never,
    isGroupAdmin: true,
    userId: ADMIN.userId,
    access: ADMIN,
  })
  render(
    <ShopSwitcherProvider>
      <FinancePage />
    </ShopSwitcherProvider>
  )
  await screen.findByTestId("finance-scope-banner")
}

beforeEach(() => {
  jest.clearAllMocks()
  window.localStorage.clear()
  mockedApiClient.get.mockImplementation(apiMock as never)
  mockedFetchAllMyShops.mockResolvedValue(SHOPS as never)
})

describe("Finance date range (issue #827)", () => {
  it("sends no from/to until a range is chosen - the unscoped requests are unchanged", async () => {
    await renderLoaded()
    for (const u of [...calls("summary"), ...calls("list")]) {
      expect(u).not.toContain("from=")
      expect(u).not.toContain("to=")
    }
    expect(screen.getByLabelText("From")).toHaveValue("")
    expect(screen.getByLabelText("To")).toHaveValue("")
    expect(screen.getByRole("button", { name: "All time" })).toHaveAttribute("aria-pressed", "true")
  })

  it("'Today' asks the summary and the list for today's UK day, from page 0", async () => {
    await renderLoaded()
    fireEvent.click(screen.getByRole("button", { name: "Today" }))

    const today = ukDay()
    await waitFor(() => expect(calls("summary").some((u) => u.includes(`from=${today}`))).toBe(true))
    const summary = calls("summary").find((u) => u.includes("from="))!
    expect(summary).toContain(`to=${today}`)
    const list = calls("list").find((u) => u.includes("from="))!
    expect(list).toContain("page=0")
    expect(list).toContain(`from=${today}&to=${today}`)
    expect(screen.getByRole("button", { name: "Today" })).toHaveAttribute("aria-pressed", "true")
    expect(screen.getByLabelText("From")).toHaveValue(today)
    expect(screen.getByLabelText("To")).toHaveValue(today)
  })

  it("'This month' runs from the 1st of the UK month to today", async () => {
    await renderLoaded()
    fireEvent.click(screen.getByRole("button", { name: "This month" }))

    const today = ukDay()
    const first = `${today.slice(0, 8)}01`
    await waitFor(() => expect(calls("summary").some((u) => u.includes(`from=${first}`))).toBe(true))
    expect(calls("summary").find((u) => u.includes("from="))).toContain(`to=${today}`)
  })

  it("the two date inputs send exactly what was typed, and 'All time' clears them", async () => {
    await renderLoaded()
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-10-01" } })
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-10-31" } })

    await waitFor(() =>
      expect(calls("summary").some((u) => u.includes("from=2026-10-01") && u.includes("to=2026-10-31"))).toBe(true)
    )

    fireEvent.click(screen.getByRole("button", { name: "All time" }))
    await waitFor(() => expect(screen.getByLabelText("From")).toHaveValue(""))
    const last = calls("summary")[calls("summary").length - 1]
    expect(last).not.toContain("from=")
  })

  it("exports the range on screen: the CSV request carries the echoed from/to", async () => {
    await renderLoaded()
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-10-01" } })
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-10-31" } })
    await waitFor(() => expect(calls("summary").some((u) => u.includes("to=2026-10-31"))).toBe(true))
    await screen.findByTestId("finance-scope-banner")

    const clickSpy = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {})
    const create = (URL.createObjectURL = jest.fn(() => "blob:x"))
    URL.revokeObjectURL = jest.fn()
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /export csv/i }))
    })
    expect(create).toHaveBeenCalled()
    const exportUrl = calls("export")[0]
    expect(exportUrl).toContain("from=2026-10-01")
    expect(exportUrl).toContain("to=2026-10-31")
    clickSpy.mockRestore()
  })

  it("an inverted range is refused inline and asks the server for nothing", async () => {
    await renderLoaded()
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-10-01" } })
    await waitFor(() => expect(calls("summary").some((u) => u.includes("to=2026-10-01"))).toBe(true))
    const before = calls("summary").length

    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-10-31" } })

    expect(await screen.findByRole("alert")).toHaveTextContent(/start date is after the end date/i)
    expect(calls("summary").length).toBe(before)
  })

  it("changing the range keeps the controls on screen (no spinner swap) while the figures reload", async () => {
    await renderLoaded()
    const from = screen.getByLabelText("From")
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    mockedApiClient.get.mockImplementation(((url: string) => {
      if (String(url).includes("from=2026-10-01")) return gate.then(() => apiMock(String(url)))
      return apiMock(String(url))
    }) as never)

    fireEvent.change(from, { target: { value: "2026-10-01" } })

    // Mid-flight: the same input element is still mounted, with its value.
    await waitFor(() => expect(calls("summary").some((u) => u.includes("from=2026-10-01"))).toBe(true))
    expect(screen.getByLabelText("From")).toBe(from)
    expect(from).toHaveValue("2026-10-01")
    await act(async () => { release() })
  })
})

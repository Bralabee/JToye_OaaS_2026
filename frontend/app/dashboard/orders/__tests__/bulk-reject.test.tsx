/**
 * Phase 37-30 (UI-SPEC § D1 "Bulk reject (Orders page)"; D-18, SC-4; RWO-041).
 *
 * A vendor hit by a flood of junk orders clears them in one action from the Orders page: a
 * checkbox column on PENDING rows only, a header checkbox that selects every PENDING row on the
 * page, a sticky bottom bar ("{n} selected" · "Reject {n} orders" · "Clear selection"), and the
 * shared RejectOrdersDialog, which posts the selected ids to 37-29's /bulk-cancel and reports
 * the per-order outcomes.
 *
 * Driven through the REAL page (only the network, the SSE hook, the toast and the Radix Select
 * are faked). Asserted:
 *   - checkboxes exist on PENDING rows (aria-label "Select order {number}") and on no other row;
 *   - the header select-all selects exactly the page's PENDING rows;
 *   - the bar appears only while something is selected, and "Clear selection" empties it;
 *   - Reject opens the dialog titled for the count; a reason is required; the POST carries the
 *     selected ids; the summary is shown; "Close summary" closes it and the list is re-read;
 *   - with no PENDING rows there is no header checkbox and no bar (UI-SPEC D1 "empty" row —
 *     unresolved in the spec, the planner's assumption, asserted here so it is a decision).
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import OrdersPage from "@/app/dashboard/orders/page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

// One shared push so the "a checkbox tap never opens the order" case can see it was not called
// (jest.setup's default mock mints a fresh router per useRouter call).
const mockPush = jest.fn()
jest.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush, replace: jest.fn(), back: jest.fn(), forward: jest.fn(), refresh: jest.fn(), prefetch: jest.fn() }),
  usePathname: () => "/dashboard/orders",
  useParams: () => ({}),
  useSearchParams: () => ({ get: () => null }),
}))

jest.mock("@/components/ui/select", () => {
  const React = jest.requireActual("react")
  const Select = ({
    children,
    value,
    onValueChange,
    disabled,
  }: {
    children: React.ReactNode
    value?: string
    onValueChange: (v: string) => void
    disabled?: boolean
  }) => (
    <select
      data-testid="mock-select"
      value={value ?? ""}
      disabled={disabled}
      onChange={(e) => onValueChange(e.target.value)}
    >
      <option value="">—</option>
      {children}
    </select>
  )
  const passthrough = ({ children }: { children?: React.ReactNode }) => <>{children}</>
  const SelectItem = ({ value, children }: { value: string; children: React.ReactNode }) => (
    <option value={value}>{children}</option>
  )
  return { Select, SelectTrigger: passthrough, SelectValue: () => null, SelectContent: passthrough, SelectItem }
})

function orderRow(id: string, status: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    tenantId: "tenant-1",
    shopId: "shop-1",
    orderNumber: `ORD-${id}`,
    status,
    customerName: `Customer ${id}`,
    customerEmail: `${id}@example.com`,
    totalAmountPennies: 1500,
    itemCount: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...extra,
  }
}

function stubList(rows: Array<Record<string, unknown>>) {
  mockedApiClient.get.mockReset()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/orders")) {
      return Promise.resolve({ data: { content: rows, totalPages: 1, totalElements: rows.length } })
    }
    return Promise.resolve({ data: { content: [] } })
  })
  mockedApiClient.post.mockReset()
}

const table = () => screen.getByRole("table")
const rowCheckbox = (id: string) =>
  within(table()).getByRole("checkbox", { name: `Select order ORD-${id}` })
const selectAll = () => within(table()).queryByRole("checkbox", { name: /Select all/ })
const bar = () => screen.queryByRole("region", { name: "Selected orders" })

beforeEach(() => {
  jest.clearAllMocks()
})

describe("Orders page: selecting PENDING rows (UI-SPEC § D1)", () => {
  it("a checkbox on each PENDING row and none on other rows; the header checkbox selects exactly the PENDING rows", async () => {
    stubList([
      orderRow("p1", "PENDING"),
      orderRow("p2", "PENDING"),
      orderRow("p3", "PENDING"),
      orderRow("c1", "CONFIRMED"),
    ])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")

    expect(rowCheckbox("p1")).toBeInTheDocument()
    expect(rowCheckbox("p2")).toBeInTheDocument()
    expect(rowCheckbox("p3")).toBeInTheDocument()
    expect(within(table()).queryByRole("checkbox", { name: "Select order ORD-c1" })).toBeNull()
    expect(within(table()).getAllByRole("checkbox")).toHaveLength(4) // 3 rows + the header

    // Nothing selected: no bar.
    expect(bar()).toBeNull()

    const all = selectAll()
    expect(all).not.toBeNull()
    fireEvent.click(all as HTMLElement)

    expect(rowCheckbox("p1")).toHaveAttribute("aria-checked", "true")
    expect(rowCheckbox("p2")).toHaveAttribute("aria-checked", "true")
    expect(rowCheckbox("p3")).toHaveAttribute("aria-checked", "true")
    expect(bar()).not.toBeNull()
    expect(bar()).toHaveTextContent("3 selected")
    expect(within(bar() as HTMLElement).getByRole("button", { name: "Reject 3 orders" })).toBeInTheDocument()

    fireEvent.click(within(bar() as HTMLElement).getByRole("button", { name: "Clear selection" }))
    expect(bar()).toBeNull()
    expect(rowCheckbox("p1")).toHaveAttribute("aria-checked", "false")
  })

  it("selecting one row by hand reads '1 selected' · 'Reject 1 order', and ticking the row again clears it", async () => {
    stubList([orderRow("p1", "PENDING"), orderRow("p2", "PENDING")])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")

    fireEvent.click(rowCheckbox("p2"))
    expect(bar()).toHaveTextContent("1 selected")
    expect(within(bar() as HTMLElement).getByRole("button", { name: "Reject 1 order" })).toBeInTheDocument()

    fireEvent.click(rowCheckbox("p2"))
    expect(bar()).toBeNull()
  })

  it("with no PENDING rows there is no header checkbox, no row checkbox and no bar", async () => {
    stubList([orderRow("c1", "CONFIRMED"), orderRow("d1", "COMPLETED")])
    render(<OrdersPage />)
    await screen.findByText("ORD-c1")

    expect(selectAll()).toBeNull()
    expect(within(table()).queryAllByRole("checkbox")).toHaveLength(0)
    expect(bar()).toBeNull()
  })
})

describe("Orders page: rejecting the selection through RejectOrdersDialog (SC-4)", () => {
  it("Reject 3 orders -> the dialog requires a reason, posts the 3 ids, shows '2 rejected. 1 couldn't…', and Close summary re-reads the list", async () => {
    stubList([
      orderRow("p1", "PENDING"),
      orderRow("p2", "PENDING"),
      orderRow("p3", "PENDING"),
      orderRow("c1", "CONFIRMED"),
    ])
    mockedApiClient.post.mockResolvedValue({
      data: {
        results: [
          { orderId: "p1", outcome: "REJECTED" },
          { orderId: "p2", outcome: "ALREADY_ACCEPTED" },
          { orderId: "p3", outcome: "REJECTED" },
        ],
      },
    })
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")
    const listReadsBefore = mockedApiClient.get.mock.calls.filter(([u]) => String(u).startsWith("/api/v1/orders")).length

    fireEvent.click(selectAll() as HTMLElement)
    fireEvent.click(within(bar() as HTMLElement).getByRole("button", { name: "Reject 3 orders" }))

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("Reject 3 orders?")).toBeInTheDocument()
    const confirm = within(dialog).getByRole("button", { name: "Reject 3 orders" })
    expect(confirm).toBeDisabled()

    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "SUSPECTED_FAKE" } })
    expect(confirm).toBeEnabled()
    await act(async () => {
      fireEvent.click(confirm)
    })

    expect(mockedApiClient.post).toHaveBeenCalledTimes(1)
    expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/orders/bulk-cancel", {
      orderIds: ["p1", "p2", "p3"],
      reasonCode: "SUSPECTED_FAKE",
    })

    const summary = await within(dialog).findByRole("status")
    expect(summary).toHaveTextContent("2 rejected. 1 couldn't be rejected because it had already been accepted.")
    // Still open: the summary is read before anything closes.
    expect(screen.getByRole("dialog")).toBeInTheDocument()

    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Close summary" }))
    })
    expect(screen.queryByRole("dialog")).toBeNull()
    const listReadsAfter = mockedApiClient.get.mock.calls.filter(([u]) => String(u).startsWith("/api/v1/orders")).length
    expect(listReadsAfter).toBe(listReadsBefore + 1)
    await screen.findByText("ORD-p1")
    expect(bar()).toBeNull()
  })

  it("ticking a row's checkbox never opens the order (the row click is for the row, not the box)", async () => {
    stubList([orderRow("p1", "PENDING")])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")
    // The row's own onClick navigates with router.push; the checkbox cell is a row action and
    // the row handler must decline it. CONTROL first: a click on the number cell does navigate.
    fireEvent.click(screen.getByText("ORD-p1"))
    expect(mockPush).toHaveBeenCalledWith("/dashboard/orders/p1")
    mockPush.mockClear()

    fireEvent.click(rowCheckbox("p1"))
    expect(rowCheckbox("p1")).toHaveAttribute("aria-checked", "true")
    expect(mockPush).not.toHaveBeenCalled()
  })
})

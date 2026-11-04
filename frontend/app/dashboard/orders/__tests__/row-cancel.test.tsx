/**
 * #820: cancelling an order from the Orders list was ONE unconfirmed tap, with no reason.
 *
 * The kitchen's Reject and the list's bulk Reject both go through RejectOrdersDialog (a required
 * reason, kept on the order, never sent to the customer); the per-row "Cancel" button beside
 * Confirm / Start Prep called the cancel endpoint directly, so a thumb that meant "Confirm" could
 * cancel a paying customer's order with nothing to say why it happened.
 *
 * Driven through the REAL page (only the network, the SSE hook, the toast and the Radix Select are
 * faked). A row Cancel works on CONFIRMED orders too, which the bulk-cancel endpoint deliberately
 * refuses (ALREADY_ACCEPTED), so this path posts the single-order cancel with its reasonCode.
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import OrdersPage from "@/app/dashboard/orders/page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
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

function orderRow(id: string, status: string) {
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
  mockedApiClient.post.mockResolvedValue({ data: {} })
}

const rowOf = (number: string) => screen.getByText(number).closest("tr") as HTMLElement
const listReads = () =>
  mockedApiClient.get.mock.calls.filter(([u]) => String(u).startsWith("/api/v1/orders")).length

beforeEach(() => {
  jest.clearAllMocks()
})

describe("Orders list: a row Cancel asks first, and for a reason (issue #820)", () => {
  it("tapping Cancel on a PENDING row opens the dialog and posts NOTHING", async () => {
    stubList([orderRow("p1", "PENDING")])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")

    fireEvent.click(within(rowOf("ORD-p1")).getByRole("button", { name: /Cancel/ }))

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("Cancel order ORD-p1?")).toBeInTheDocument()
    expect(mockedApiClient.post).not.toHaveBeenCalled()
    // The reason is REQUIRED: the confirm stays disabled until one is chosen.
    expect(within(dialog).getByRole("button", { name: "Cancel order" })).toBeDisabled()
    // The body is the honest one: the reason is kept for the records, not sent to the customer.
    expect(dialog).toHaveTextContent("isn't sent to the customer")
  })

  it("confirming with a reason posts the SINGLE cancel with that reasonCode, then re-reads the list", async () => {
    stubList([orderRow("p1", "PENDING")])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")
    const readsBefore = listReads()

    fireEvent.click(within(rowOf("ORD-p1")).getByRole("button", { name: /Cancel/ }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "CUSTOMER_REQUEST" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Cancel order" }))
    })

    expect(mockedApiClient.post).toHaveBeenCalledTimes(1)
    expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/orders/p1/cancel", {
      reasonCode: "CUSTOMER_REQUEST",
    })
    expect(screen.queryByRole("dialog")).toBeNull()
    expect(listReads()).toBe(readsBefore + 1)
    expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({ title: "Order updated" }))
  })

  it("a CONFIRMED order can still be cancelled from its row (bulk-cancel would refuse it, so the single cancel is used)", async () => {
    stubList([orderRow("c1", "CONFIRMED")])
    render(<OrdersPage />)
    await screen.findByText("ORD-c1")

    fireEvent.click(within(rowOf("ORD-c1")).getByRole("button", { name: /Cancel/ }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "CANNOT_FULFIL" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Cancel order" }))
    })

    expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/orders/c1/cancel", { reasonCode: "CANNOT_FULFIL" })
    expect(mockedApiClient.post).not.toHaveBeenCalledWith("/api/v1/orders/bulk-cancel", expect.anything())
  })

  it("'Keep order' closes the dialog and the order is untouched", async () => {
    stubList([orderRow("p1", "PENDING")])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")

    fireEvent.click(within(rowOf("ORD-p1")).getByRole("button", { name: /Cancel/ }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Keep order" }))

    expect(screen.queryByRole("dialog")).toBeNull()
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it("a refused cancel keeps the dialog open with an inline alert, and changes nothing", async () => {
    stubList([orderRow("p1", "PENDING")])
    mockedApiClient.post.mockRejectedValue({ response: { status: 409, data: { detail: "x" } } })
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")

    fireEvent.click(within(rowOf("ORD-p1")).getByRole("button", { name: /Cancel/ }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "OTHER" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Cancel order" }))
    })

    expect(screen.getByRole("dialog")).toBeInTheDocument()
    expect(within(dialog).getByRole("alert")).toHaveTextContent(/Couldn't cancel this order/)
  })

  it("the other row actions are unchanged: Confirm still posts at once, with no dialog", async () => {
    stubList([orderRow("p1", "PENDING")])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")

    await act(async () => {
      fireEvent.click(within(rowOf("ORD-p1")).getByRole("button", { name: /Confirm/ }))
    })

    expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/orders/p1/confirm")
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("row action buttons are 44px tall on a coarse pointer", async () => {
    stubList([orderRow("p1", "PENDING")])
    render(<OrdersPage />)
    await screen.findByText("ORD-p1")

    for (const name of [/Cancel/, /Confirm/]) {
      const button = within(rowOf("ORD-p1")).getByRole("button", { name })
      expect(button.className).toContain("[@media(pointer:coarse)]:h-11")
    }
  })
})

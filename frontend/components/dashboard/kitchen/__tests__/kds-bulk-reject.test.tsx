/**
 * Phase 37-30 (UI-SPEC § K1 "Bulk reject", § D1; D-18, SC-4; RWO-041): the kitchen New lane
 * rejects several junk orders in one action, and every kitchen card shows the unverified-contact
 * badge when the stored fact says so.
 *
 * Driven through the REAL kitchen page (only the network, the socket, the toast and the Radix
 * Select are faked). Asserted:
 *   - "Reject several orders" appears in the lane header only with >= 2 New cards;
 *   - selection mode: a checkbox "Select order {number}" on each New card, the sticky bar
 *     "{n} selected · Reject {n} orders · Stop selecting"; Accept stays visible and usable
 *     (selection never covers Accept);
 *   - "Stop selecting" leaves selection mode and clears it, posting nothing;
 *   - Reject {n} orders opens RejectOrdersDialog titled for the count, posts the selected ids
 *     to /bulk-cancel with the chosen code, shows the summary, and "Close summary" takes the
 *     rejected cards off the lane and ends selection mode;
 *   - a refused outcome (ALREADY_ACCEPTED) is reported and the board is re-read on close, so the
 *     lane stops showing an order someone else accepted;
 *   - the badge on a New card and a grid card with contactVerified false, and not with null.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import KitchenPage from "@/app/dashboard/kitchen/page"

jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const mockGet = jest.fn()
const mockPost = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
  },
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

beforeAll(() => {
  Object.defineProperty(window, "print", { value: jest.fn(), writable: true })
  ;(global as unknown as { AudioContext: unknown }).AudioContext = jest.fn().mockImplementation(() => ({
    createOscillator: () => ({
      connect: jest.fn(), start: jest.fn(), stop: jest.fn(), type: "sine",
      frequency: { setValueAtTime: jest.fn(), value: 0 },
    }),
    createGain: () => ({ connect: jest.fn(), gain: { setValueAtTime: jest.fn(), exponentialRampToValueAtTime: jest.fn() } }),
    destination: {}, currentTime: 0, close: jest.fn(),
  }))
})

const shopsPayload = {
  content: [{
    id: "shop-1", tenantId: "tenant-1", name: "Test Shop", address: "1 Main St", slug: "test",
    description: null, logoUrl: null, bannerUrl: null, phone: null, email: null,
    latitude: null, longitude: null, openingHours: null, deliveryInfo: null,
    minimumOrderPennies: 0, published: true, tags: null,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  }],
}

function orderFixture(id: string, status: string, extra: Record<string, unknown> = {}) {
  const ts = new Date().toISOString()
  return {
    id, tenantId: "tenant-1", shopId: "shop-1", orderNumber: `ORD-${id}`,
    status, customerName: `Customer ${id}`, totalAmountPennies: 1000,
    items: [
      { id: `${id}-it-1`, productId: "p-1", productName: "Jollof Rice", quantity: 2, unitPricePennies: 500, totalPricePennies: 1000, createdAt: ts },
    ],
    createdAt: ts, updatedAt: ts,
    ...extra,
  }
}

function stubBoard(orders: Array<Record<string, unknown>>) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) return Promise.resolve({ data: shopsPayload })
    if (url.startsWith("/api/v1/orders/kitchen")) {
      return Promise.resolve({
        data: { content: orders, totalElements: orders.length, totalPages: 1, size: 100, number: 0, first: true, last: true },
      })
    }
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
}

const lane = () => screen.getByRole("region", { name: /New orders/ })
const bar = () => screen.queryByRole("region", { name: "Selected orders" })
const kitchenReads = () => mockGet.mock.calls.filter(([u]) => String(u).startsWith("/api/v1/orders/kitchen")).length

async function newCard(id: string): Promise<HTMLElement> {
  const card = (await screen.findByText(`ORD-${id}`)).closest("[data-testid='kds-new-card']")
  expect(card).not.toBeNull()
  return card as HTMLElement
}

beforeEach(() => {
  localStorage.clear()
})

describe("KDS New lane: selection mode (UI-SPEC § K1 'Bulk reject')", () => {
  it("'Reject several orders' shows only with two or more New cards", async () => {
    stubBoard([orderFixture("new-1", "PENDING")])
    const { unmount } = render(<KitchenPage />)
    await newCard("new-1")
    expect(within(lane()).queryByRole("button", { name: "Reject several orders" })).toBeNull()
    unmount()

    stubBoard([orderFixture("new-1", "PENDING"), orderFixture("new-2", "PENDING")])
    render(<KitchenPage />)
    await newCard("new-2")
    expect(within(lane()).getByRole("button", { name: "Reject several orders" })).toBeInTheDocument()
  })

  it("selection mode shows a checkbox per New card and the bar; Accept stays usable; Stop selecting clears it", async () => {
    stubBoard([orderFixture("new-1", "PENDING"), orderFixture("new-2", "PENDING")])
    render(<KitchenPage />)
    const card1 = await newCard("new-1")
    const card2 = await newCard("new-2")

    // Not in selection mode: no checkboxes, no bar.
    expect(within(lane()).queryAllByRole("checkbox")).toHaveLength(0)
    expect(bar()).toBeNull()

    fireEvent.click(within(lane()).getByRole("button", { name: "Reject several orders" }))

    const box1 = within(card1).getByRole("checkbox", { name: "Select order ORD-new-1" })
    const box2 = within(card2).getByRole("checkbox", { name: "Select order ORD-new-2" })
    expect(bar()).not.toBeNull()
    expect(bar()).toHaveTextContent("0 selected")
    expect(within(bar() as HTMLElement).getByRole("button", { name: "Stop selecting" })).toBeInTheDocument()
    // Selection never covers Accept.
    expect(within(card1).getByRole("button", { name: /^Accept order/ })).toBeEnabled()

    fireEvent.click(box1)
    fireEvent.click(box2)
    expect(bar()).toHaveTextContent("2 selected")
    expect(within(bar() as HTMLElement).getByRole("button", { name: "Reject 2 orders" })).toBeEnabled()

    fireEvent.click(within(bar() as HTMLElement).getByRole("button", { name: "Stop selecting" }))
    expect(bar()).toBeNull()
    expect(within(lane()).queryAllByRole("checkbox")).toHaveLength(0)
    expect(mockPost).not.toHaveBeenCalled()

    // Re-entering starts empty: the previous selection was cleared, not hidden.
    fireEvent.click(within(lane()).getByRole("button", { name: "Reject several orders" }))
    expect(bar()).toHaveTextContent("0 selected")
  })

  it("Reject 2 orders posts the selected ids with the reason, shows the summary, and Close summary takes them off the lane", async () => {
    stubBoard([orderFixture("new-1", "PENDING"), orderFixture("new-2", "PENDING"), orderFixture("new-3", "PENDING")])
    mockPost.mockResolvedValue({
      data: { results: [{ orderId: "new-1", outcome: "REJECTED" }, { orderId: "new-3", outcome: "REJECTED" }] },
    })
    render(<KitchenPage />)
    const card1 = await newCard("new-1")
    const card3 = await newCard("new-3")

    fireEvent.click(within(lane()).getByRole("button", { name: "Reject several orders" }))
    fireEvent.click(within(card1).getByRole("checkbox", { name: "Select order ORD-new-1" }))
    fireEvent.click(within(card3).getByRole("checkbox", { name: "Select order ORD-new-3" }))
    fireEvent.click(within(bar() as HTMLElement).getByRole("button", { name: "Reject 2 orders" }))

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("Reject 2 orders?")).toBeInTheDocument()
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "SUSPECTED_FAKE" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Reject 2 orders" }))
    })

    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith("/api/v1/orders/bulk-cancel", {
      orderIds: ["new-1", "new-3"],
      reasonCode: "SUSPECTED_FAKE",
    })
    expect(await within(dialog).findByRole("status")).toHaveTextContent("2 rejected.")
    // While the summary is open the cards are still on the lane and the title still names the batch:
    // taking them off at once shrinks the dialog's orders prop under the open summary ("Reject 0 orders?").
    expect(within(dialog).getByText("Reject 2 orders?")).toBeInTheDocument()
    expect(within(lane()).getByText("ORD-new-1")).toBeInTheDocument()
    expect(within(lane()).getByText("ORD-new-3")).toBeInTheDocument()

    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Close summary" }))
    })
    await waitFor(() => expect(within(lane()).queryByText("ORD-new-1")).toBeNull())
    expect(within(lane()).queryByText("ORD-new-3")).toBeNull()
    expect(within(lane()).getByText("ORD-new-2")).toBeInTheDocument()
    // Selection mode ended with the batch.
    expect(bar()).toBeNull()
    expect(within(lane()).queryAllByRole("checkbox")).toHaveLength(0)
  })

  it("an order accepted on another screen reads ALREADY_ACCEPTED in the summary, and the board is re-read on close", async () => {
    stubBoard([orderFixture("new-1", "PENDING"), orderFixture("new-2", "PENDING")])
    mockPost.mockResolvedValue({
      data: { results: [{ orderId: "new-1", outcome: "REJECTED" }, { orderId: "new-2", outcome: "ALREADY_ACCEPTED" }] },
    })
    render(<KitchenPage />)
    const card1 = await newCard("new-1")
    const card2 = await newCard("new-2")
    const readsBefore = kitchenReads()

    fireEvent.click(within(lane()).getByRole("button", { name: "Reject several orders" }))
    fireEvent.click(within(card1).getByRole("checkbox", { name: "Select order ORD-new-1" }))
    fireEvent.click(within(card2).getByRole("checkbox", { name: "Select order ORD-new-2" }))
    fireEvent.click(within(bar() as HTMLElement).getByRole("button", { name: "Reject 2 orders" }))

    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "OTHER" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Reject 2 orders" }))
    })
    expect(await within(dialog).findByRole("status")).toHaveTextContent(
      "1 rejected. 1 couldn't be rejected because it had already been accepted."
    )

    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Close summary" }))
    })
    await waitFor(() => expect(kitchenReads()).toBe(readsBefore + 1))
  })
})

describe("Kitchen cards: the unverified-contact badge reads the stored fact (F20, UI-SPEC § K1)", () => {
  it("a New card and a grid card with contactVerified false carry 'Unverified contact'; null carries nothing", async () => {
    stubBoard([
      orderFixture("new-1", "PENDING", { contactVerified: false }),
      orderFixture("new-2", "PENDING", { contactVerified: null }),
      orderFixture("cook-1", "CONFIRMED", { contactVerified: false }),
      orderFixture("cook-2", "CONFIRMED"),
    ])
    render(<KitchenPage />)

    const card1 = await newCard("new-1")
    const card2 = await newCard("new-2")
    expect(within(card1).getByText("Unverified contact")).toBeInTheDocument()
    expect(within(card2).queryByText("Unverified contact")).toBeNull()

    const grid1 = (await screen.findByText("ORD-cook-1")).closest("[data-testid='kds-grid-card']") as HTMLElement
    const grid2 = (await screen.findByText("ORD-cook-2")).closest("[data-testid='kds-grid-card']") as HTMLElement
    expect(within(grid1).getByText("Unverified contact")).toBeInTheDocument()
    expect(within(grid2).queryByText("Unverified contact")).toBeNull()
  })
})

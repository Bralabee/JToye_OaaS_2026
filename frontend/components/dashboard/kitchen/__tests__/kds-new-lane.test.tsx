/**
 * Phase 37-16 (D-11, UXT-020): the kitchen board's "New orders" lane.
 *
 * Before this plan the board read CONFIRMED/PREPARING/READY only, so a storefront order sat in
 * PENDING, invisible to the kitchen, until somebody confirmed it on the Orders page. These tests
 * drive the REAL page (only the network, the socket and the Radix Select are faked) and assert:
 *   - a PENDING order renders inside the "New orders" region and NOT in the board grid;
 *   - "Accept order" posts /api/v1/orders/{id}/confirm and, on 200, the card leaves the lane and
 *     appears in the grid as Confirmed; there is no auto-confirm (nothing posts without the press);
 *   - while the accept is in flight the button reads "Accepting…" and both buttons are disabled;
 *   - zero PENDING orders render one "No new orders waiting" row and no count badge.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import KitchenPage from "@/app/dashboard/kitchen/page"

jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

// Stable, like the real hook's module-level toast (#756: a fresh fn per render loops fetchOrders).
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
  const Select = ({ children, value, onValueChange }: {
    children: React.ReactNode
    value: string
    onValueChange: (v: string) => void
  }) => (
    <select data-testid="shop-select" value={value} onChange={(e) => onValueChange(e.target.value)}>
      {children}
    </select>
  )
  const passthrough = ({ children }: { children?: React.ReactNode }) => <>{children}</>
  const SelectItem = ({ value, children }: { value: string; children: React.ReactNode }) =>
    <option value={value}>{children}</option>
  return { Select, SelectTrigger: passthrough, SelectValue: passthrough, SelectContent: passthrough, SelectItem }
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
  mockPost.mockResolvedValue({ data: {} })
}

const lane = () => screen.getByRole("region", { name: /New orders/ })
const grid = () => screen.getByTestId("kds-board-grid")

beforeEach(() => {
  localStorage.clear()
})

describe("KDS New lane (D-11): PENDING orders reach the kitchen", () => {
  it("renders a PENDING order in the 'New orders' region, not in the board grid", async () => {
    stubBoard([orderFixture("new-1", "PENDING"), orderFixture("cooking-1", "CONFIRMED")])
    render(<KitchenPage />)

    await screen.findByText("ORD-new-1")
    expect(within(lane()).getByText("ORD-new-1")).toBeInTheDocument()
    expect(within(lane()).queryByText("ORD-cooking-1")).toBeNull()
    expect(within(grid()).getByText("ORD-cooking-1")).toBeInTheDocument()
    expect(within(grid()).queryByText("ORD-new-1")).toBeNull()

    // The heading is the lane's accessible name, and the count says how many are waiting.
    expect(within(lane()).getByRole("heading", { level: 2, name: /New orders/ })).toBeInTheDocument()
    expect(within(lane()).getByTestId("kds-new-count")).toHaveTextContent("1")

    // No auto-confirm: nothing was posted without a press.
    expect(mockPost).not.toHaveBeenCalled()
  })

  it("Accept posts /orders/{id}/confirm and, on 200, the card moves from the lane to the grid as Confirmed", async () => {
    stubBoard([orderFixture("new-1", "PENDING")])
    render(<KitchenPage />)

    const card = (await screen.findByText("ORD-new-1")).closest("[data-testid='kds-new-card']") as HTMLElement
    expect(card).not.toBeNull()
    await act(async () => {
      fireEvent.click(within(card).getByRole("button", { name: /^Accept order/ }))
    })

    expect(mockPost).toHaveBeenCalledWith("/api/v1/orders/new-1/confirm")
    await waitFor(() => expect(within(lane()).queryByText("ORD-new-1")).toBeNull())
    expect(within(grid()).getByText("ORD-new-1")).toBeInTheDocument()
    expect(within(grid()).getByText("Confirmed")).toBeInTheDocument()
    expect(within(grid()).getByRole("button", { name: /Start Preparing/ })).toBeInTheDocument()
    // The lane is empty again, and says so.
    expect(within(lane()).getByText("No new orders waiting")).toBeInTheDocument()
  })

  it("while the accept is in flight, it reads 'Accepting…' and both buttons are disabled", async () => {
    stubBoard([orderFixture("new-1", "PENDING")])
    let release: (v: unknown) => void = () => {}
    mockPost.mockImplementation(() => new Promise((r) => { release = r }))
    render(<KitchenPage />)

    const card = (await screen.findByText("ORD-new-1")).closest("[data-testid='kds-new-card']") as HTMLElement
    const accept = within(card).getByRole("button", { name: /^Accept order/ })
    await act(async () => {
      fireEvent.click(accept)
      // A second press inside the same burst must not send a second request.
      fireEvent.click(accept)
    })

    expect(within(card).getByRole("button", { name: /Accepting…/ })).toBeDisabled()
    expect(within(card).getByRole("button", { name: /^Reject order/ })).toBeDisabled()
    expect(mockPost).toHaveBeenCalledTimes(1)

    await act(async () => {
      release({ data: {} })
    })
  })

  it("with zero PENDING orders the lane is one 'No new orders waiting' row and no count badge", async () => {
    stubBoard([orderFixture("cooking-1", "CONFIRMED")])
    render(<KitchenPage />)

    await screen.findByText("ORD-cooking-1")
    expect(within(lane()).getByText("No new orders waiting")).toBeInTheDocument()
    expect(within(lane()).queryByTestId("kds-new-count")).toBeNull()
    expect(within(lane()).queryAllByTestId("kds-new-card")).toHaveLength(0)
  })

  it("the lane row exists before the board has been read, so it does not move the board when it arrives", async () => {
    let releaseShops: (v: unknown) => void = () => {}
    mockGet.mockReset()
    mockGet.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/shops")) return new Promise((r) => { releaseShops = r })
      return Promise.resolve({ data: {} })
    })
    render(<KitchenPage />)

    // First paint: the shop list is still in flight, and the lane is already there.
    expect(lane()).toBeInTheDocument()
    expect(within(lane()).getByTestId("kds-new-lane-row")).toBeInTheDocument()

    await act(async () => {
      releaseShops({ data: { content: [] } })
    })
  })
})

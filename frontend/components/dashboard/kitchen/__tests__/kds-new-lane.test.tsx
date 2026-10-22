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

/** The New card holding `ORD-{id}`. */
async function newCard(id: string): Promise<HTMLElement> {
  const card = (await screen.findByText(`ORD-${id}`)).closest("[data-testid='kds-new-card']")
  expect(card).not.toBeNull()
  return card as HTMLElement
}

/** Stub the board, make /confirm (or /cancel) fail with `error`, and serve `detail` for the re-read. */
function stubRefusal(error: unknown, detail?: Record<string, unknown>) {
  stubBoard([orderFixture("new-1", "PENDING")])
  const board = mockGet.getMockImplementation()!
  mockGet.mockImplementation((url: string) => {
    if (url === "/api/v1/orders/new-1/detail") {
      return detail ? Promise.resolve({ data: detail }) : Promise.reject(new Error("Network Error"))
    }
    return board(url)
  })
  mockPost.mockRejectedValue(error)
}

async function pressAccept(card: HTMLElement) {
  await act(async () => {
    fireEvent.click(within(card).getByRole("button", { name: /^Accept order/ }))
  })
}

/** After a failure the card stays in the lane and both buttons are usable again. */
function expectCardStaysUsable(card: HTMLElement) {
  expect(within(lane()).getByText("ORD-new-1")).toBeInTheDocument()
  expect(within(card).getByRole("button", { name: /^Accept order/ })).toBeEnabled()
  expect(within(card).getByRole("button", { name: /^Reject order/ })).toBeEnabled()
}

describe("KDS New card (D-11/D-12): the NEW strip and the 1 Hz ring", () => {
  it("carries 'NEW · waiting for you to accept' with a bell, and the flash class on the card, not the strip", async () => {
    stubBoard([orderFixture("new-1", "PENDING")])
    render(<KitchenPage />)

    const card = await newCard("new-1")
    const strip = within(card).getByTestId("kds-new-strip")
    expect(strip).toHaveTextContent("NEW · waiting for you to accept")
    expect(strip.querySelector("svg.lucide-bell-ring")).not.toBeNull()
    expect(strip).toHaveClass("bg-orange-700")
    // Only the ring moves (WCAG 2.3.1): the card carries the flash, the strip carries none.
    expect(card).toHaveClass("kds-new-flash")
    expect(Array.from(strip.classList).some((c) => c.startsWith("animate-") || c.includes("flash"))).toBe(false)
  })

  it("grid cards never flash", async () => {
    stubBoard([orderFixture("cook-1", "CONFIRMED")])
    render(<KitchenPage />)

    const title = await screen.findByText("ORD-cook-1")
    const gridCard = title.closest("[data-testid='kds-grid-card']") as HTMLElement
    expect(gridCard).not.toHaveClass("kds-new-flash")
    expect(within(gridCard).queryByTestId("kds-new-strip")).toBeNull()
  })
})

describe("KDS New card: Accept says exactly why it failed (UI-SPEC § Copywriting K1)", () => {
  it("a typed insufficient-stock refusal reads the product and the count from the problem body", async () => {
    stubRefusal({
      response: {
        status: 409,
        data: {
          type: "https://jtoye.uk/errors/insufficient-stock",
          title: "Insufficient Stock",
          status: 409,
          detail: "Insufficient stock for product 'Jollof Rice': requested 2, available 1",
          productName: "Jollof Rice",
          available: 1,
        },
      },
    })
    render(<KitchenPage />)

    const card = await newCard("new-1")
    await pressAccept(card)

    const alert = await within(card).findByRole("alert")
    expect(alert).toHaveTextContent(
      "Can't accept: only 1 Jollof Rice left in stock. Update the stock or reject this order."
    )
    expectCardStaysUsable(card)
  })

  it("an invalid transition re-reads the order: already accepted on another screen", async () => {
    stubRefusal(
      { response: { status: 400, data: { type: "https://jtoye.uk/errors/invalid-state-transition" } } },
      orderFixture("new-1", "CONFIRMED")
    )
    render(<KitchenPage />)

    const card = await newCard("new-1")
    await pressAccept(card)

    expect(await within(card).findByRole("alert")).toHaveTextContent(
      "This order was already accepted on another screen."
    )
    expect(mockGet).toHaveBeenCalledWith("/api/v1/orders/new-1/detail")
  })

  it("an invalid transition re-reads the order: already rejected on another screen", async () => {
    stubRefusal(
      { response: { status: 400, data: { type: "https://jtoye.uk/errors/invalid-state-transition" } } },
      orderFixture("new-1", "CANCELLED")
    )
    render(<KitchenPage />)

    const card = await newCard("new-1")
    await pressAccept(card)

    expect(await within(card).findByRole("alert")).toHaveTextContent(
      "This order was already rejected on another screen."
    )
  })

  it("a network failure gets the generic copy, and the card stays usable", async () => {
    stubRefusal(new Error("Network Error"))
    render(<KitchenPage />)

    const card = await newCard("new-1")
    await pressAccept(card)

    expect(await within(card).findByRole("alert")).toHaveTextContent(
      "Couldn't accept this order. Check your connection and try again."
    )
    expectCardStaysUsable(card)
  })
})

describe("KDS New card: Reject order (single, through RejectOrdersDialog — 37-30)", () => {
  it("asks first ('Reject order {number}?', a required reason, 'Keep order'), posts the one id to /bulk-cancel with the reason, and Close summary leaves the lane", async () => {
    stubBoard([orderFixture("new-1", "PENDING")])
    mockPost.mockResolvedValue({ data: { results: [{ orderId: "new-1", outcome: "REJECTED" }] } })
    render(<KitchenPage />)
    const card = await newCard("new-1")

    await act(async () => {
      fireEvent.click(within(card).getByRole("button", { name: /^Reject order/ }))
    })
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("Reject order ORD-new-1?")).toBeInTheDocument()
    expect(dialog).toHaveTextContent("The reason stays on the order for your records and isn't sent to the customer.")
    expect(within(dialog).getByRole("button", { name: "Keep order" })).toBeInTheDocument()
    const confirm = within(dialog).getByRole("button", { name: "Reject order" })
    expect(confirm).toBeDisabled()
    expect(mockPost).not.toHaveBeenCalled()

    // This file's Select mock has no placeholder option, so the native select already DISPLAYS
    // the first reason; choosing a different one is what fires the change (the dialog's own
    // state starts empty either way — the disabled confirm above is the proof).
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "CUSTOMER_REQUEST" } })
    await act(async () => {
      fireEvent.click(confirm)
    })
    expect(mockPost).toHaveBeenCalledWith("/api/v1/orders/bulk-cancel", {
      orderIds: ["new-1"],
      reasonCode: "CUSTOMER_REQUEST",
    })
    expect(await within(dialog).findByRole("status")).toHaveTextContent("1 rejected.")

    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Close summary" }))
    })
    await waitFor(() => expect(within(lane()).queryByText("ORD-new-1")).toBeNull())
  })

  it("'Keep order' closes the dialog and sends nothing", async () => {
    stubBoard([orderFixture("new-1", "PENDING")])
    render(<KitchenPage />)
    const card = await newCard("new-1")

    await act(async () => {
      fireEvent.click(within(card).getByRole("button", { name: /^Reject order/ }))
    })
    const dialog = await screen.findByRole("dialog")
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Keep order" }))
    })
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(mockPost).not.toHaveBeenCalled()
    expectCardStaysUsable(card)
  })

  it("an order accepted on another screen reads ALREADY_ACCEPTED, and the board is re-read when the summary closes", async () => {
    stubBoard([orderFixture("new-1", "PENDING")])
    mockPost.mockResolvedValue({ data: { results: [{ orderId: "new-1", outcome: "ALREADY_ACCEPTED" }] } })
    render(<KitchenPage />)
    const card = await newCard("new-1")
    const readsBefore = mockGet.mock.calls.filter(([u]) => String(u).startsWith("/api/v1/orders/kitchen")).length

    await act(async () => {
      fireEvent.click(within(card).getByRole("button", { name: /^Reject order/ }))
    })
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "OTHER" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Reject order" }))
    })
    expect(await within(dialog).findByRole("status")).toHaveTextContent(
      "1 couldn't be rejected because it had already been accepted."
    )
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Close summary" }))
    })
    await waitFor(() =>
      expect(mockGet.mock.calls.filter(([u]) => String(u).startsWith("/api/v1/orders/kitchen")).length).toBe(readsBefore + 1)
    )
  })
})

/**
 * Phase 30-06 (issue #958, UI-SPEC § S2 "New lane Accept").
 *
 * An Accept refused because the customer has not paid used to fall through to "Check your
 * connection": the re-read still says PENDING, so `movedElsewhere` knows nothing. The new FIRST branch
 * shows the server's `detail` in the card's existing inline alert. The stock, moved-elsewhere and
 * generic branches are unchanged (their arms above stay green). Accept stays enabled on an unpaid
 * card, which carries the "Awaiting payment" badge.
 */
describe("KDS New card: an unpaid order (issue #958)", () => {
  const DETAIL =
    "Order ORD-new-1 is awaiting payment. It can't be submitted, accepted or prepared until the customer's payment completes."

  it("a 409 awaiting-payment refusal shows the server detail in the card's alert, not 'Check your connection'", async () => {
    stubRefusal(
      {
        response: {
          status: 409,
          data: {
            type: "https://jtoye.uk/errors/awaiting-payment",
            title: "Awaiting payment",
            status: 409,
            detail: DETAIL,
          },
        },
      },
      // The re-read still says PENDING — exactly why the old code said "Check your connection".
      orderFixture("new-1", "PENDING", { paymentStatus: "PENDING", paymentReference: "pi_unpaid" })
    )
    render(<KitchenPage />)

    const card = await newCard("new-1")
    await pressAccept(card)

    const alert = await within(card).findByRole("alert")
    expect(alert).toHaveTextContent(DETAIL)
    expect(alert).not.toHaveTextContent("Check your connection")
    expectCardStaysUsable(card)
  })

  it("an awaiting-payment problem with an empty detail falls through to the existing branches", async () => {
    stubRefusal(
      { response: { status: 409, data: { type: "https://jtoye.uk/errors/awaiting-payment", detail: "  " } } },
      orderFixture("new-1", "PENDING")
    )
    render(<KitchenPage />)

    const card = await newCard("new-1")
    await pressAccept(card)

    expect(await within(card).findByRole("alert")).toHaveTextContent(
      "Couldn't accept this order. Check your connection and try again."
    )
  })

  it("an unpaid New card carries the 'Awaiting payment' badge in its header, and Accept stays enabled", async () => {
    stubBoard([orderFixture("new-1", "PENDING", { paymentStatus: "PENDING", paymentReference: "pi_unpaid" })])
    render(<KitchenPage />)

    const card = await newCard("new-1")
    const header = within(card).getByTestId("kds-card-header")
    expect(within(header).getByTestId("awaiting-payment")).toHaveTextContent("Awaiting payment")
    expect(within(card).getByRole("button", { name: /^Accept order/ })).toBeEnabled()
  })

  it("a vendor New card (NONE, no reference) carries no badge", async () => {
    stubBoard([orderFixture("new-1", "PENDING", { paymentStatus: "NONE", paymentReference: null })])
    render(<KitchenPage />)

    const card = await newCard("new-1")
    expect(within(card).queryByTestId("awaiting-payment")).toBeNull()
  })
})

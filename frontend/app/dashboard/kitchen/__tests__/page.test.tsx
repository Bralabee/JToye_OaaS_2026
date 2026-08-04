/**
 * Unit tests for the Kitchen Display page. We mock useStomp and apiClient so
 * the page renders synchronously and we can exercise the interactive bits:
 *  - shops load, first shop selected by default
 *  - orders render once fetched
 *  - mute toggle persists to localStorage and flips icon
 *  - empty state renders when there are no active orders
 *  - card header truncates long order numbers without the badge overlapping (#8)
 *  - elapsed time is capped/formatted, never raw uncapped minutes (#12)
 *  - age-border colour logic is preserved (green for a fresh order)
 */

import { render, screen, waitFor, fireEvent, act, within } from "@testing-library/react"
import KitchenPage from "../page"
import { useStomp } from "@/hooks/use-stomp"

// Mock useStomp — we want the page's render logic, not a real WS
jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

// Mock the toast hook — no-op
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// Mock apiClient — hand-rolled so tests control the data
const mockGet = jest.fn()
const mockPost = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
  },
}))

// Radix Select is hard to exercise in jsdom (portal + focus logic). Replace
// with a plain select so the test can interact freely.
jest.mock("@/components/ui/select", () => {
  const React = jest.requireActual("react")
  const Select = ({ children, value, onValueChange }: {
    children: React.ReactNode
    value: string
    onValueChange: (v: string) => void
  }) => (
    <select
      data-testid="shop-select"
      value={value}
      onChange={(e) => onValueChange(e.target.value)}
    >
      {children}
    </select>
  )
  const passthrough = ({ children }: { children?: React.ReactNode }) =>
    <>{children}</>
  const SelectItem = ({ value, children }: { value: string; children: React.ReactNode }) =>
    <option value={value}>{children}</option>
  return {
    Select,
    SelectTrigger: passthrough,
    SelectValue: passthrough,
    SelectContent: passthrough,
    SelectItem,
  }
})

// jsdom has no printer: `window.print` exists but throws "Not implemented". Replace
// it so the #105 print path can be asserted rather than swallowed by the try/catch.
beforeAll(() => {
  Object.defineProperty(window, "print", { value: jest.fn(), writable: true })
})
beforeEach(() => {
  ;(window.print as jest.Mock).mockClear()
})

// AudioContext mock so playBeep() does not explode the test runner
beforeAll(() => {
  ;(global as unknown as { AudioContext: unknown }).AudioContext = jest
    .fn()
    .mockImplementation(() => ({
      createOscillator: () => ({
        connect: jest.fn(),
        start: jest.fn(),
        stop: jest.fn(),
        type: "sine",
        frequency: { setValueAtTime: jest.fn(), value: 0 },
      }),
      createGain: () => ({
        connect: jest.fn(),
        gain: {
          setValueAtTime: jest.fn(),
          exponentialRampToValueAtTime: jest.fn(),
        },
      }),
      destination: {},
      currentTime: 0,
      close: jest.fn(),
    }))
})

const shopsPayload = {
  content: [
    {
      id: "shop-1",
      tenantId: "tenant-1",
      name: "Test Shop",
      address: "1 Main St",
      slug: "test",
      description: null,
      logoUrl: null,
      bannerUrl: null,
      phone: null,
      email: null,
      latitude: null,
      longitude: null,
      openingHours: null,
      deliveryInfo: null,
      minimumOrderPennies: 0,
      published: true,
      tags: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
  ],
}

function ordersPayload(statuses: string[]) {
  return {
    content: statuses.map((status, i) => ({
      id: `order-${i}`,
      tenantId: "tenant-1",
      shopId: "shop-1",
      status,
      totalAmountPennies: 1000,
      itemCount: 2,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    })),
  }
}

function orderDetailPayload(id: string, status: string, createdAt?: string) {
  const ts = createdAt ?? new Date().toISOString()
  return {
    id,
    tenantId: "tenant-1",
    shopId: "shop-1",
    orderNumber: `ORD-${id}`,
    status,
    customerName: "Alice",
    totalAmountPennies: 1000,
    items: [
      {
        id: "it-1",
        productId: "p-1",
        productName: "Burger",
        quantity: 2,
        unitPricePennies: 500,
        totalPricePennies: 1000,
        createdAt: ts,
      },
    ],
    createdAt: ts,
    updatedAt: ts,
  }
}

// `createdAt` (optional) lets a test pin the order age so the elapsed-time and
// age-border formatting can be asserted deterministically.
function stubApi(activeStatuses: string[], createdAt?: string) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: shopsPayload })
    }
    if (url.startsWith("/api/v1/orders?")) {
      return Promise.resolve({ data: ordersPayload(activeStatuses) })
    }
    const match = url.match(/\/api\/v1\/orders\/(.*)\/detail/)
    if (match) {
      const id = match[1]
      const idx = Number(id.replace("order-", ""))
      return Promise.resolve({
        data: orderDetailPayload(id, activeStatuses[idx], createdAt),
      })
    }
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
}

describe("KitchenPage", () => {
  beforeEach(() => {
    localStorage.clear()
  })

  // #266: the page previously subscribed to /topic/kitchen/{tenant}/{shop}. Everything after
  // /topic/ becomes an AMQP routing key on amq.topic and may not contain '/', so the relay
  // broker used by staging and production rejected it while dev's in-memory broker accepted
  // it. This test asserts the shape the page actually passes to useStomp — the assertion the
  // suite was missing when the bad shape shipped.
  it("subscribes to a single dot-separated KDS topic, never a slashed one", async () => {
    stubApi([])
    render(<KitchenPage />)

    await waitFor(() => {
      expect(useStomp).toHaveBeenCalledWith(
        "/topic/kitchen.tenant-1.shop-1",
        expect.any(Function),
        expect.any(Function)
      )
    })

    const topics = (useStomp as jest.Mock).mock.calls
      .map(([topic]) => topic)
      .filter((topic): topic is string => typeof topic === "string")
    expect(topics.length).toBeGreaterThan(0)
    for (const topic of topics) {
      expect(topic.slice("/topic/".length)).not.toContain("/")
    }
  })

  it("renders the empty state when no active orders are present", async () => {
    stubApi([])
    render(<KitchenPage />)
    expect(
      await screen.findByText(/Kitchen Display/i)
    ).toBeInTheDocument()
    expect(await screen.findByText(/No active orders/i)).toBeInTheDocument()
  })

  it("renders order cards for fetched active orders", async () => {
    stubApi(["CONFIRMED", "PREPARING"])
    render(<KitchenPage />)
    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith(
        expect.stringContaining("/api/v1/shops")
      )
    })
    // Customer name appears on each card
    const alice = await screen.findAllByText(/Alice/)
    expect(alice.length).toBeGreaterThanOrEqual(1)
    // Bump buttons show the current action label
    expect(screen.getAllByRole("button").some((b) => /Start Preparing|Mark Ready/.test(b.textContent || ""))).toBe(true)
  })

  it("mute toggle persists state to localStorage", async () => {
    stubApi([])
    render(<KitchenPage />)
    // Wait for initial shops load to finish and mute button to render
    const muteButton = await screen.findByTitle(/Mute alerts|Unmute alerts/)
    expect(localStorage.getItem("kds-muted")).toBeNull()

    act(() => {
      fireEvent.click(muteButton)
    })
    expect(localStorage.getItem("kds-muted")).toBe("true")

    act(() => {
      fireEvent.click(muteButton)
    })
    expect(localStorage.getItem("kds-muted")).toBe("false")
  })

  it("reads initial mute state from localStorage on mount", async () => {
    localStorage.setItem("kds-muted", "true")
    stubApi([])
    render(<KitchenPage />)
    // When muted=true the button title is "Unmute alerts"
    expect(await screen.findByTitle(/Unmute alerts/)).toBeInTheDocument()
  })

  // --- #8: card header badge-clip fix ---

  it("truncates a long order number and shields the status badge from clipping", async () => {
    stubApi(["CONFIRMED"])
    render(<KitchenPage />)
    // The order number renders as ORD-order-0 (orderNumber: `ORD-${id}`).
    const title = await screen.findByText("ORD-order-0")
    // Truncate + min-w-0 let a long ORD-… number ellipsize instead of wrapping
    // under the badge; text-lg replaces the old text-2xl.
    expect(title).toHaveClass("truncate")
    expect(title).toHaveClass("min-w-0")
    expect(title).toHaveClass("text-lg")
    expect(title).toHaveClass("font-semibold")
    // twMerge should have dropped the previous text-2xl sizing.
    expect(title).not.toHaveClass("text-2xl")

    // The status badge is flex-shrink-0 so it keeps its width and never
    // overlaps the (truncating) order number.
    const badge = screen.getByText("Confirmed")
    expect(badge).toHaveClass("flex-shrink-0")
  })

  // --- #12: elapsed-time cap/format ---

  it("shows 'just now' for an order created moments ago", async () => {
    stubApi(["CONFIRMED"], new Date().toISOString())
    render(<KitchenPage />)
    expect(await screen.findByText("just now")).toBeInTheDocument()
  })

  it("shows minutes for an order under an hour old", async () => {
    const created = new Date(Date.now() - 42 * 60 * 1000).toISOString()
    stubApi(["CONFIRMED"], created)
    render(<KitchenPage />)
    expect(await screen.findByText("42m ago")).toBeInTheDocument()
  })

  it("shows hours for an order between 1 and 24 hours old", async () => {
    const created = new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString()
    stubApi(["CONFIRMED"], created)
    render(<KitchenPage />)
    expect(await screen.findByText("3h ago")).toBeInTheDocument()
  })

  it("caps a very old order to a day form (no raw '2245m ago')", async () => {
    // 2245 minutes ≈ 1.56 days — the exact value the audit flagged (#12).
    const created = new Date(Date.now() - 2245 * 60 * 1000).toISOString()
    stubApi(["CONFIRMED"], created)
    render(<KitchenPage />)
    expect(await screen.findByText("1d ago")).toBeInTheDocument()
    expect(screen.queryByText(/2245m/)).not.toBeInTheDocument()
  })

  // --- age-border colour logic preserved (unchanged) ---

  it("keeps the green age border for a fresh order", async () => {
    stubApi(["CONFIRMED"], new Date().toISOString())
    render(<KitchenPage />)
    const title = await screen.findByText("ORD-order-0")
    // Walk up to the Card (its className includes transition-colors).
    const card = title.closest(".transition-colors")
    expect(card).not.toBeNull()
    expect(card).toHaveClass("border-green-500")
  })

  // --- QA-council FIX-4 (M2 + L2): published-shop filter + sane default ---

  function shopEntry(id: string, name: string, published: boolean) {
    return { ...shopsPayload.content[0], id, name, slug: id, published }
  }

  function stubApiWithShops(shopContent: unknown[]) {
    mockGet.mockReset()
    mockGet.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/shops")) {
        return Promise.resolve({ data: { content: shopContent } })
      }
      if (url.startsWith("/api/v1/orders?")) {
        return Promise.resolve({ data: { content: [] } })
      }
      return Promise.resolve({ data: {} })
    })
    mockPost.mockReset()
    mockPost.mockResolvedValue({ data: {} })
  }

  it("defaults to the first PUBLISHED shop and omits drafts from the selector (M2 + L2)", async () => {
    // A draft/junk shop sorted FIRST — pre-fix the blind shopList[0] default
    // selected it, so the kitchen showed "No active orders" while real orders
    // waited on the published shop.
    stubApiWithShops([
      shopEntry("shop-draft", "Draft Junk Shop", false),
      shopEntry("shop-live", "Brixton Village Grill", true),
    ])
    render(<KitchenPage />)

    const select = (await screen.findByTestId("shop-select")) as HTMLSelectElement
    await waitFor(() => expect(select.value).toBe("shop-live"))
    // Orders are fetched for the PUBLISHED shop, not the draft.
    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(expect.stringContaining("shopId=shop-live"))
    )
    // The selector no longer lists draft/junk shops.
    //
    // Scoped to the <select> since #450 5d: the board now ALSO prints the shop name
    // in its header ("Showing tickets for …"), so a bare getByText matches twice and
    // throws. Narrowing to the selector is strictly more precise than the original —
    // it asserts what the assertion was always about (what the dropdown offers),
    // rather than "this string is somewhere on the page".
    expect(within(select).getByText("Brixton Village Grill")).toBeInTheDocument()
    expect(within(select).queryByText("Draft Junk Shop")).not.toBeInTheDocument()
  })

  it("falls back to listing all shops when none are published (never selector-empty)", async () => {
    stubApiWithShops([
      shopEntry("shop-d1", "Draft One", false),
      shopEntry("shop-d2", "Draft Two", false),
    ])
    render(<KitchenPage />)

    const select = (await screen.findByTestId("shop-select")) as HTMLSelectElement
    await waitFor(() => expect(select.value).toBe("shop-d1"))
    expect(within(select).getByText("Draft One")).toBeInTheDocument()
    expect(within(select).getByText("Draft Two")).toBeInTheDocument()
  })

  // --- #450 5d: the board no longer lies about which shop it is showing ---

  it("names the boarded shop in the header, not only in the 200px selector", async () => {
    stubApiWithShops([
      shopEntry("shop-live", "Brixton Village Grill", true),
      shopEntry("shop-b", "Peckham Jollof Co.", true),
    ])
    render(<KitchenPage />)

    // waitFor, not findByTestId: the element exists from the first render reading
    // "No shop selected", so findBy* resolves on that and asserts too early.
    await waitFor(() =>
      expect(screen.getByTestId("kds-board-shop")).toHaveTextContent(
        "Showing tickets for Brixton Village Grill"
      )
    )
  })

  // --- #106: liveness is stated, not implied by a 10px dot ---

  it("renders a feed pill carrying the state as a word and a last-updated clock", async () => {
    stubApi([])
    render(<KitchenPage />)

    const pill = await screen.findByTestId("kds-feed-pill")
    await waitFor(() => expect(pill.textContent).toMatch(/\d{2}:\d{2}:\d{2}/))
    expect(pill).toHaveTextContent("Live")
    // No banner while the feed is healthy — a warning that is always on is noise.
    expect(screen.queryByTestId("kds-feed-banner")).not.toBeInTheDocument()
  })

  it("raises a banner with a refresh action when the socket is down", async () => {
    ;(useStomp as jest.Mock).mockReturnValue({ connected: false, reconnecting: true })
    stubApi([])
    render(<KitchenPage />)

    const banner = await screen.findByTestId("kds-feed-banner")
    expect(banner).toHaveAttribute("role", "alert")
    expect(banner).toHaveTextContent(/Reconnecting/i)
    expect(screen.getByRole("button", { name: /refresh now/i })).toBeInTheDocument()
    ;(useStomp as jest.Mock).mockReturnValue({ connected: true, reconnecting: false })
  })

  it("re-reads the orders list when the refresh action is pressed", async () => {
    stubApi(["CONFIRMED"])
    ;(useStomp as jest.Mock).mockReturnValue({ connected: false, reconnecting: true })
    render(<KitchenPage />)

    await screen.findByTestId("kds-feed-banner")
    const before = mockGet.mock.calls.filter(([u]: [string]) =>
      (u as string).startsWith("/api/v1/orders?")
    ).length

    // Plain fireEvent, NOT `await act(async () => …)`. Under React 19 the async act
    // wrapper never settles around any click that leads to a requestAnimationFrame —
    // measured: it hung until the 5s test timeout on the print clicks below, and it is
    // the same shape here. `waitFor` already wraps its polling in act.
    fireEvent.click(screen.getByRole("button", { name: /refresh now/i }))

    await waitFor(() =>
      expect(
        mockGet.mock.calls.filter(([u]: [string]) =>
          (u as string).startsWith("/api/v1/orders?")
        ).length
      ).toBeGreaterThan(before)
    )
    ;(useStomp as jest.Mock).mockReturnValue({ connected: true, reconnecting: false })
  })

  // --- #485: the board pages, and its requests carry page= ---

  it("asks for orders with an explicit page, keeping shopId first", async () => {
    stubApi(["CONFIRMED"])
    render(<KitchenPage />)

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(
        expect.stringMatching(/^\/api\/v1\/orders\?shopId=shop-1&page=0&size=\d+&sort=createdAt,desc$/)
      )
    )
  })

  it("pages the SHOP list too, instead of a single hardcoded size=100", async () => {
    stubApi([])
    render(<KitchenPage />)

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(expect.stringContaining("/api/v1/shops?page=0&size="))
    )
    const shopCalls = mockGet.mock.calls
      .map(([u]: [string]) => u as string)
      .filter((u) => u.startsWith("/api/v1/shops"))
    expect(shopCalls.every((u) => !u.includes("?size=100"))).toBe(true)
  })

  // --- #105: printing ---

  it("offers a print control per ticket and one for the whole board", async () => {
    stubApi(["CONFIRMED", "PREPARING"])
    render(<KitchenPage />)

    await screen.findAllByText(/Alice/)
    expect(
      await screen.findAllByRole("button", { name: /^Print ticket/i })
    ).toHaveLength(2)
    expect(screen.getByRole("button", { name: /print all/i })).toBeEnabled()
  })

  it("disables Print all on an empty board — there is nothing to print", async () => {
    stubApi([])
    render(<KitchenPage />)
    expect(await screen.findByRole("button", { name: /print all/i })).toBeDisabled()
  })

  it("mounts a ticket sheet carrying the order's real items when a ticket is printed", async () => {
    stubApi(["CONFIRMED"])
    render(<KitchenPage />)

    await screen.findByText("ORD-order-0")
    expect(screen.queryByTestId("kds-print-root")).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: /^Print ticket/i }))

    const sheet = await screen.findByTestId("kds-print-root")
    expect(within(sheet).getAllByTestId("kitchen-ticket")).toHaveLength(1)
    expect(sheet).toHaveTextContent("ORD-order-0")
    expect(sheet).toHaveTextContent("Burger")
    // The sheet is committed first and printed a frame later, so this waits.
    await waitFor(() => expect(window.print).toHaveBeenCalled())
  })

  it("puts every visible ticket on the sheet when Print all is used", async () => {
    stubApi(["CONFIRMED", "PREPARING"])
    render(<KitchenPage />)

    await screen.findAllByText(/Alice/)
    fireEvent.click(screen.getByRole("button", { name: /print all/i }))

    const sheet = await screen.findByTestId("kds-print-root")
    expect(within(sheet).getAllByTestId("kitchen-ticket")).toHaveLength(2)
  })
})

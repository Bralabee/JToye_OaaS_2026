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
// #564: the board reads `/api/v1/orders/kitchen`, which returns active orders WITH
// their line items. The old pair of stubs — a summary list plus one `/detail` per
// ticket — is gone because the requests are gone; this fake serves what the endpoint
// serves, so a test cannot pass here against a client that still fans out.
function stubApi(activeStatuses: string[], createdAt?: string) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: shopsPayload })
    }
    if (url.startsWith("/api/v1/orders/kitchen")) {
      return Promise.resolve({ data: kitchenBoardPayload(activeStatuses, createdAt) })
    }
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
}

/** One page of the kitchen board: full detail per ticket, terminating on `last`. */
function kitchenBoardPayload(activeStatuses: string[], createdAt?: string) {
  const content = activeStatuses.map((status, i) =>
    orderDetailPayload(`order-${i}`, status, createdAt)
  )
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 100,
    number: 0,
    first: true,
    last: true,
  }
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
      if (url.startsWith("/api/v1/orders/kitchen")) {
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

  // --- #561: a refused RE-READ is not a broken board ---
  //
  // The board refreshes with one request per active ticket, concurrently, and takes that
  // full path on every reconnect. Against a tenant bucket refilled in one lump per
  // minute, part of that burst coming back 429 is ordinary. It used to fail the whole
  // read and raise "Orders are not refreshing" over data the page was still holding —
  // for up to a minute, with nothing retrying. These two tests fix the line between the
  // two cases; they are a pair on purpose, because a fix that only satisfies the first
  // is how the board would go quiet about a genuinely missing ticket.
  // --- #564: the board is read in ONE request, so a refusal is total ---
  //
  // RETIRED HERE: "stays quiet when a re-read is partly refused but every ticket is
  // still renderable". That test pinned #563's partial-failure tolerance, which existed
  // because the board fetched one detail per ticket and a burst of nineteen could come
  // back partly 429. There is no burst now, so "some succeeded, some did not" is a state
  // the code cannot enter, and a test for it would assert against reality.
  //
  // Recorded rather than deleted quietly: removing the burst is strictly better than
  // tolerating it, but a just-shipped fix disappearing with no trace is how the same
  // defect gets re-learned. What survives is the half that still has meaning, below.

  it("raises the banner when the board read fails, and clears it when it succeeds", async () => {
    // The invariant #563 protected, restated for the shape #564 gives it: the board says
    // so when it could not be read. Both directions in one test on purpose — an
    // assertion that the banner APPEARS is satisfied by a board that always warns.
    mockGet.mockReset()
    mockGet.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/shops")) return Promise.resolve({ data: shopsPayload })
      return Promise.reject(new Error("Request failed with status code 429"))
    })

    const { unmount } = render(<KitchenPage />)
    const banner = await screen.findByTestId("kds-feed-banner")
    expect(banner).toHaveTextContent(/not refreshing/i)
    unmount()

    // Same page, same socket, only the read now lands.
    stubApi(["CONFIRMED"])
    render(<KitchenPage />)
    expect(await screen.findAllByText("Alice")).not.toHaveLength(0)
    expect(screen.queryByTestId("kds-feed-banner")).not.toBeInTheDocument()
  })

  it("reads the board at a cost that does not scale with ticket count", async () => {
    // #564's acceptance at the page level, stated as the COMPARISON it actually is.
    //
    // My first version of this asserted "exactly one request" and failed at 2 — the page
    // runs its load effect twice on mount, which is independent of ticket count and is
    // not what this issue is about. Asserting a constant would have made the test a
    // tripwire for unrelated render behaviour; asserting that the count is the SAME for
    // one ticket and for eight is the property #564 asked for, and it stays true however
    // the mount effects are arranged.
    const countBoardReads = () =>
      mockGet.mock.calls
        .map(([u]: [string]) => u as string)
        .filter((u) => u.startsWith("/api/v1/orders/kitchen")).length

    stubApi(["CONFIRMED"])
    const one = render(<KitchenPage />)
    await waitFor(() => expect(screen.getAllByText("Alice")).toHaveLength(1))
    const readsForOneTicket = countBoardReads()
    one.unmount()

    stubApi(["CONFIRMED", "PREPARING", "READY", "CONFIRMED", "PREPARING", "READY", "CONFIRMED", "READY"])
    render(<KitchenPage />)
    await waitFor(() => expect(screen.getAllByText("Alice")).toHaveLength(8))

    expect(countBoardReads())
      .toBe(readsForOneTicket)

    // The load-bearing half: a client that called the new endpoint AND still fanned out
    // per ticket would satisfy the comparison above only by accident. Before #564 this
    // was 8.
    expect(
      mockGet.mock.calls.map(([u]: [string]) => u as string).filter((u) => u.includes("/detail"))
    ).toHaveLength(0)
  })

  it("re-reads the orders list when the refresh action is pressed", async () => {
    stubApi(["CONFIRMED"])
    ;(useStomp as jest.Mock).mockReturnValue({ connected: false, reconnecting: true })
    render(<KitchenPage />)

    await screen.findByTestId("kds-feed-banner")
    const before = mockGet.mock.calls.filter(([u]: [string]) =>
      (u as string).startsWith("/api/v1/orders/kitchen")
    ).length

    // Plain fireEvent, NOT `await act(async () => …)`. Under React 19 the async act
    // wrapper never settles around any click that leads to a requestAnimationFrame —
    // measured: it hung until the 5s test timeout on the print clicks below, and it is
    // the same shape here. `waitFor` already wraps its polling in act.
    fireEvent.click(screen.getByRole("button", { name: /refresh now/i }))

    await waitFor(() =>
      expect(
        mockGet.mock.calls.filter(([u]: [string]) =>
          (u as string).startsWith("/api/v1/orders/kitchen")
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
        expect.stringMatching(/^\/api\/v1\/orders\/kitchen\?shopId=shop-1&page=0&size=\d+&sort=createdAt,desc$/)
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

  // --- #536: the board reserves its space instead of growing into the page ---
  //
  // The measured mechanism, at the repo's declared throttle profile (390px `isMobile`
  // + `hasTouch`, Fast-3G 1.5Mbps/40ms, 4x CPU, real API, an 18-ticket board):
  // /dashboard/kitchen scored CLS 0.8287, of which 0.6593 was ONE frame — the 16rem
  // loading band being replaced by the grid, which shoved the shell footer from y=797
  // (on screen, in an 844px viewport) 4574px down. These tests assert the two
  // structural properties that removed it. They cannot measure CLS; the number lives
  // in the PR. What they CAN do is fail the moment the structure regresses.

  /** The reserved band, as written in page.tsx. */
  const RESERVE = "min-h-[calc(100svh_-_11rem)]"

  it("reserves the same board height while loading, when empty, and when populated", async () => {
    // One height across all three states is the whole point: the footer underneath
    // must not be able to tell which state the board is in.
    stubApi([])
    const { unmount } = render(<KitchenPage />)

    // State 1 — shops/orders still loading.
    const loadingBand = await screen.findByRole("status", { name: "Loading kitchen orders" })
    expect(loadingBand.className).toContain(RESERVE)

    // State 2 — read, and empty.
    await screen.findByText(/No active orders/i)
    const empty = screen.getByText(/No active orders/i).closest("div")
    expect(empty?.className).toContain(RESERVE)
    unmount()

    // State 3 — read, and populated.
    stubApi(["CONFIRMED", "PREPARING"])
    render(<KitchenPage />)
    await screen.findAllByText(/Alice/)
    const grid = screen.getAllByText(/Alice/)[0].closest(".grid")
    expect(grid?.className).toContain(RESERVE)
  })

  it("shows ticket-shaped skeletons while the board is read, not a lone spinner", async () => {
    // A 128px spinner centred in a 16rem band told the operator nothing about what
    // was coming and reserved a height unrelated to it.
    stubApi(["CONFIRMED"])
    render(<KitchenPage />)
    expect(screen.getAllByTestId("kds-ticket-skeleton").length).toBeGreaterThan(0)
    // ...and they are gone once real tickets exist. A skeleton that outlives its
    // data is a board showing furniture instead of orders.
    await screen.findAllByText(/Alice/)
    expect(screen.queryAllByTestId("kds-ticket-skeleton")).toHaveLength(0)
  })

  it("renders the header's controls from the first paint, not once shops arrive", async () => {
    // The loading header used to carry no control row at all, so pill + selector +
    // print + mute appeared together mid-load and pushed the whole board down 108px.
    stubApi([])
    render(<KitchenPage />)
    // Synchronously, before any promise resolves:
    expect(screen.getByTestId("kds-feed-pill")).toBeInTheDocument()
    expect(screen.getByTitle(/Mute alerts|Unmute alerts/)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /print all/i })).toBeInTheDocument()
    // And the pill says it is starting rather than claiming the feed is Offline.
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("Connecting")
    await screen.findByText(/No active orders/i)
  })

  // --- #450 5d, the half PR #535 left open ---

  it("says so when the dashboard's shop is one the board cannot show", async () => {
    // The board lists PUBLISHED shops only (QA-council FIX-4). The dashboard switcher
    // lists every granted shop. Point the dashboard at an unpublished one and the
    // reconciliation effect degrades to shops[0] — until now, in silence.
    localStorage.setItem("shopContext", "shop-draft")
    stubApiWithShops([
      shopEntry("shop-draft", "Camden Prep Kitchen", false),
      shopEntry("shop-live", "Brixton Village Grill", true),
    ])
    render(<KitchenPage />)

    const notice = await screen.findByTestId("kds-other-shop-notice")
    expect(notice).toHaveTextContent("Brixton Village Grill")
    // Not the All-shops notice: the dashboard is on a specific shop, so that copy
    // would be a different, wrong explanation.
    expect(screen.queryByTestId("kds-all-shops-notice")).not.toBeInTheDocument()
  })

  it("claims no mismatch when the dashboard's shop IS the boarded shop", async () => {
    // The control arm. Without it, "always render the notice" would pass the test
    // above while making the board cry wolf on every load.
    localStorage.setItem("shopContext", "shop-live")
    stubApiWithShops([
      shopEntry("shop-live", "Brixton Village Grill", true),
      shopEntry("shop-b", "Peckham Jollof Co.", true),
    ])
    render(<KitchenPage />)

    await waitFor(() =>
      expect(screen.getByTestId("kds-board-shop")).toHaveTextContent(
        "Brixton Village Grill"
      )
    )
    expect(screen.queryByTestId("kds-other-shop-notice")).not.toBeInTheDocument()
    expect(screen.queryByTestId("kds-all-shops-notice")).not.toBeInTheDocument()
  })
})

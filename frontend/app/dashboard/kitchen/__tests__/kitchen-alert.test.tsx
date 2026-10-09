/**
 * 37-17 (D-12, UXT-020): the kitchen PAGE wires the repeating alert to its New lane.
 *
 * kds-sound.test.tsx proves the hook; this suite drives the REAL page (only the network, the
 * socket and the Radix Select are faked) so the wiring cannot drift from it:
 *   - the header's "Turn on sound" tap starts a chime that repeats while a PENDING order waits,
 *     and Accept silences it;
 *   - the tab title carries the New-lane count, in the same render as the lane;
 *   - a new order arriving over the socket never constructs an AudioContext by itself (the old
 *     per-beep `new AudioContext()` in a socket callback, which the browser suspends);
 *   - switching shop silences the previous shop's orders at once.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import KitchenPage from "@/app/dashboard/kitchen/page"
import { useStomp } from "@/hooks/use-stomp"
import {
  audioRecord,
  chimeCount,
  installFakeAudioContext,
} from "@/test-utils/fake-audio-context"

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
})

function shop(id: string, name: string) {
  return {
    id, tenantId: "tenant-1", name, address: "1 Main St", slug: id,
    description: null, logoUrl: null, bannerUrl: null, phone: null, email: null,
    latitude: null, longitude: null, openingHours: null, deliveryInfo: null,
    minimumOrderPennies: 0, published: true, tags: null,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  }
}

function orderFixture(id: string, status: string, shopId = "shop-1") {
  const ts = new Date().toISOString()
  return {
    id, tenantId: "tenant-1", shopId, orderNumber: `ORD-${id}`,
    status, customerName: `Customer ${id}`, totalAmountPennies: 1000,
    items: [
      { id: `${id}-it-1`, productId: "p-1", productName: "Jollof Rice", quantity: 2, unitPricePennies: 500, totalPricePennies: 1000, createdAt: ts },
    ],
    createdAt: ts, updatedAt: ts,
  }
}

const page = (orders: Array<Record<string, unknown>>) => ({
  data: { content: orders, totalElements: orders.length, totalPages: 1, size: 100, number: 0, first: true, last: true },
})

/** `boards` maps a shop id to its kitchen read; a missing entry never answers (a read in flight). */
function stubBoards(shops: Array<ReturnType<typeof shop>>, boards: Record<string, Array<Record<string, unknown>>>) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: { content: shops, totalElements: shops.length, totalPages: 1, last: true } })
    }
    if (url.startsWith("/api/v1/orders/kitchen")) {
      const shopId = new URL(url, "http://x").searchParams.get("shopId") ?? ""
      const orders = boards[shopId]
      return orders ? Promise.resolve(page(orders)) : new Promise(() => {})
    }
    if (url.endsWith("/detail")) {
      const id = url.split("/")[4]
      return Promise.resolve({ data: orderFixture(id, "PENDING") })
    }
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
}

const lane = () => screen.getByRole("region", { name: /New orders/ })

const advance = async (ms: number) => {
  await act(async () => {
    jest.advanceTimersByTime(ms)
  })
}

beforeEach(() => {
  localStorage.clear()
  document.title = ""
  jest.useFakeTimers()
  installFakeAudioContext("running")
})

afterEach(() => {
  jest.useRealTimers()
})

describe("Kitchen page: the repeating new-order alert (D-12)", () => {
  it("rings while a PENDING order waits once sound is turned on, and Accept silences it", async () => {
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [orderFixture("new-1", "PENDING")] })
    render(<KitchenPage />)
    await screen.findByText("ORD-new-1")

    // Nothing rings before the tap: the browser would not let it, and the board must not pretend.
    await advance(15_000)
    expect(audioRecord.instances).toHaveLength(0)

    fireEvent.click(within(screen.getByTestId("kds-sound-control")).getByText("Turn on sound"))
    expect(audioRecord.instances).toHaveLength(1)
    await advance(15_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(3)

    const card = screen.getByText("ORD-new-1").closest("[data-testid='kds-new-card']") as HTMLElement
    await act(async () => {
      fireEvent.click(within(card).getByRole("button", { name: /^Accept order/ }))
    })
    await waitFor(() => expect(within(lane()).queryByText("ORD-new-1")).toBeNull())

    audioRecord.starts.length = 0
    await advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)
  })

  it("puts the New-lane count in the tab title, and drops it when the lane empties", async () => {
    stubBoards([shop("shop-1", "Test Shop")], {
      "shop-1": [orderFixture("new-1", "PENDING"), orderFixture("new-2", "PENDING"), orderFixture("cook-1", "CONFIRMED")],
    })
    render(<KitchenPage />)
    await screen.findByText("ORD-new-1")

    expect(within(lane()).getByTestId("kds-new-count")).toHaveTextContent("2")
    expect(document.title).toBe("(2) New orders · Kitchen Display — J'Toye")

    for (const id of ["new-1", "new-2"]) {
      const card = screen.getByText(`ORD-${id}`).closest("[data-testid='kds-new-card']") as HTMLElement
      await act(async () => {
        fireEvent.click(within(card).getByRole("button", { name: /^Accept order/ }))
      })
    }
    await waitFor(() => expect(within(lane()).getByText("No new orders waiting")).toBeInTheDocument())
    expect(document.title).toBe("Kitchen Display — J'Toye")
  })

  it("never constructs an AudioContext when a new order arrives over the socket", async () => {
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [] })
    render(<KitchenPage />)
    await screen.findByText("No new orders waiting")

    const handler = (useStomp as jest.Mock).mock.calls.at(-1)?.[1] as (e: unknown) => void
    expect(typeof handler).toBe("function")
    await act(async () => {
      handler({ orderId: "new-9", newStatus: "PENDING", previousStatus: "DRAFT" })
    })
    await screen.findByText("ORD-new-9")
    await advance(15_000)
    // The old path built `new AudioContext()` inside this callback: outside a gesture, so the
    // browser suspends it and the beep is lost in silence.
    expect(audioRecord.instances).toHaveLength(0)
    expect(audioRecord.starts).toHaveLength(0)
  })

  it("stops ringing the moment the board switches shop, before the new shop's read lands", async () => {
    // shop-2's read never answers, so the map still holds shop-1's order: the alert must not
    // keep ringing for a shop the board no longer shows.
    stubBoards([shop("shop-1", "Test Shop"), shop("shop-2", "Other Shop")], {
      "shop-1": [orderFixture("new-1", "PENDING")],
    })
    render(<KitchenPage />)
    await screen.findByText("ORD-new-1")
    fireEvent.click(within(screen.getByTestId("kds-sound-control")).getByText("Turn on sound"))
    await advance(6_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(1)

    fireEvent.change(screen.getByTestId("shop-select"), { target: { value: "shop-2" } })
    audioRecord.starts.length = 0
    await advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)
  })
})

describe("Kitchen page: the sound state is shown, never assumed (K1)", () => {
  it("a fresh board says sound is off, in a bar under the header, until the tap", async () => {
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [orderFixture("new-1", "PENDING")] })
    render(<KitchenPage />)
    await screen.findByText("ORD-new-1")

    const bar = screen.getByTestId("kds-sound-bar")
    expect(bar).toHaveTextContent("Sound is off. New orders will flash but not ring.")
    expect(screen.getByTestId("kds-sound-control")).toHaveTextContent("Turn on sound")

    // The bar's own button is a real way in: it unlocks the same single context.
    fireEvent.click(within(bar).getByRole("button", { name: "Turn on sound" }))
    expect(audioRecord.instances).toHaveLength(1)
    expect(screen.queryByTestId("kds-sound-bar")).toBeNull()
    expect(screen.getByTestId("kds-sound-control")).toHaveTextContent("Mute alerts")
    await advance(15_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(3)
  })

  it("F4: a persisted kds-muted=true is visible on load (bar + control), never silent", async () => {
    localStorage.setItem("kds-muted", "true")
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [orderFixture("new-1", "PENDING")] })
    render(<KitchenPage />)
    await screen.findByText("ORD-new-1")

    expect(screen.getByTestId("kds-sound-bar")).toHaveTextContent(
      "Alerts are muted. New orders will flash but not ring."
    )
    expect(screen.getByTestId("kds-sound-control")).toHaveTextContent("Unmute alerts")
    // The key's lifetime is unchanged: reading it does not rewrite or clear it.
    expect(localStorage.getItem("kds-muted")).toBe("true")
  })

  it("muted: the New card still flashes, and nothing rings (mute silences sound, never the visual)", async () => {
    localStorage.setItem("kds-muted", "true")
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [orderFixture("new-1", "PENDING")] })
    render(<KitchenPage />)
    const card = (await screen.findByText("ORD-new-1")).closest("[data-testid='kds-new-card']") as HTMLElement
    expect(card.className).toMatch(/\bkds-new-flash\b/)
    await advance(15_000)
    expect(audioRecord.starts).toHaveLength(0)
  })

  it("'Unmute alerts' in the bar is itself the gesture: one tap unmutes AND unlocks sound", async () => {
    localStorage.setItem("kds-muted", "true")
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [orderFixture("new-1", "PENDING")] })
    render(<KitchenPage />)
    await screen.findByText("ORD-new-1")

    fireEvent.click(within(screen.getByTestId("kds-sound-bar")).getByRole("button", { name: "Unmute alerts" }))
    expect(localStorage.getItem("kds-muted")).toBe("false")
    expect(audioRecord.instances).toHaveLength(1)
    expect(screen.getByTestId("kds-sound-control")).toHaveTextContent("Mute alerts")
    expect(screen.queryByTestId("kds-sound-bar")).toBeNull()
    await advance(15_000)
    expect(chimeCount()).toBeGreaterThanOrEqual(3)
  })
})

describe("Kitchen page: the feed pill says 'Live' only when it is true (D-14, SC-1)", () => {
  it("a fresh board that cannot ring reads 'Sound off', and 'Live' only after the tap", async () => {
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [] })
    render(<KitchenPage />)
    await screen.findByText("No new orders waiting")
    const pill = await screen.findByTestId("kds-feed-pill")
    expect(pill).toHaveTextContent("Sound off")
    expect(pill).not.toHaveTextContent("Live")

    fireEvent.click(screen.getByTestId("kds-sound-control"))
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("Live")
  })

  it("F4: a persisted mute reads 'Muted' on the pill, beside the muted bar", async () => {
    localStorage.setItem("kds-muted", "true")
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [] })
    render(<KitchenPage />)
    await screen.findByText("No new orders waiting")
    const pill = await screen.findByTestId("kds-feed-pill")
    expect(pill).toHaveTextContent("Muted")
    expect(pill).not.toHaveTextContent("Live")
    expect(screen.getByTestId("kds-sound-bar")).toHaveTextContent("Alerts are muted.")
  })

  it("a board whose reads have gone stale reads 'Out of date', not 'Live', with the socket up", async () => {
    stubBoards([shop("shop-1", "Test Shop")], { "shop-1": [] })
    render(<KitchenPage />)
    await screen.findByText("No new orders waiting")
    fireEvent.click(screen.getByTestId("kds-sound-control"))
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("Live")

    // Every read from now on hangs: the socket stays "connected", the stamp stops advancing.
    mockGet.mockImplementation(() => new Promise(() => {}))
    await advance(150_000)
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("Out of date")
  })
})

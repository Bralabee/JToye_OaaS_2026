/**
 * 37-21 Task 1 (UI-SPEC K3 vendor side, D-15, RWO-022): the kitchen pauses storefront orders in one
 * tap, and the board says until when because the SERVER says so.
 *
 * 37-20 built PUT/DELETE /api/v1/shops/{id}/pause (STAFF, 20 | 40 minutes or until reopened) and
 * ShopDto's `ordersPausedUntil` / `ordersPausedIndefinitely`. Before this plan the kitchen had no way
 * to drive them (UXT-022's vendor half: "no pause switch").
 *
 * Every timed fixture is built so a CLIENT computation gives the wrong answer:
 *  - the clock is Tuesday 13 October 2026 18:20 UK time (17:20Z, BST), and the server answers a
 *    20-minute pause with 17:41:00Z. A banner that adds 20 minutes to the browser's clock prints
 *    18:40; one that prints the server's field prints 18:41;
 *  - a formatter that forgets Europe/London prints 17:41.
 * The ShopDto keeps an ENDED pause's instant (the server does not clean the column up), so a past
 * `ordersPausedUntil` must read as no pause.
 */

import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import KitchenPage from "@/app/dashboard/kitchen/page"
import { PausedBanner } from "@/components/dashboard/kitchen/paused-banner"
import { installFakeAudioContext } from "@/test-utils/fake-audio-context"

jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const mockGet = jest.fn()
const mockPost = jest.fn()
const mockPut = jest.fn()
const mockDelete = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
    put: (...args: unknown[]) => mockPut(...args),
    delete: (...args: unknown[]) => mockDelete(...args),
  },
}))

// Radix Select is replaced by a plain select, as in the kitchen page suite.
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
  const SelectItem = ({ value, children }: { value: string; children: React.ReactNode }) => (
    <option value={value}>{children}</option>
  )
  return { Select, SelectTrigger: passthrough, SelectValue: passthrough, SelectContent: passthrough, SelectItem }
})

/** Tuesday 13 October 2026, 18:20 in the UK (BST, UTC+1). */
const NOW = new Date("2026-10-13T17:20:00Z")
/** What the server answers for "20 minutes": one minute later than the browser's own sum. */
const SERVER_UNTIL = "2026-10-13T17:41:00Z"

/** Fake ONLY the clock: React, RTL and promises keep real timers. */
function freezeClock() {
  jest.useFakeTimers({
    now: NOW,
    doNotFake: [
      "setTimeout", "clearTimeout", "setInterval", "clearInterval", "setImmediate", "clearImmediate",
      "nextTick", "queueMicrotask", "requestAnimationFrame", "cancelAnimationFrame",
      "requestIdleCallback", "cancelIdleCallback", "hrtime", "performance",
    ],
  })
}

const SHOP_ID = "shop-1"

function shopDto(pause: { ordersPausedUntil?: string; ordersPausedIndefinitely?: boolean } = {}) {
  return {
    id: SHOP_ID,
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
    createdAt: NOW.toISOString(),
    updatedAt: NOW.toISOString(),
    hoursSource: "STRUCTURED",
    ordersPausedIndefinitely: false,
    ...pause,
  }
}

/** What the server holds; GET /api/v1/shops/shop-1 returns it, the pause writes replace it. */
const server: { shop: ReturnType<typeof shopDto> } = { shop: shopDto() }

function stubApi() {
  server.shop = shopDto()
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops?")) return Promise.resolve({ data: { content: [shopDto()], last: true } })
    if (url === `/api/v1/shops/${SHOP_ID}`) return Promise.resolve({ data: server.shop })
    if (url.startsWith("/api/v1/orders/kitchen")) return Promise.resolve({ data: { content: [], last: true } })
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
  mockPut.mockReset()
  mockPut.mockImplementation((_url: string, body: { minutes?: number; untilReopened?: boolean }) => {
    server.shop = body.untilReopened
      ? shopDto({ ordersPausedIndefinitely: true })
      : shopDto({ ordersPausedUntil: body.minutes === 40 ? "2026-10-13T18:01:00Z" : SERVER_UNTIL })
    return Promise.resolve({ data: server.shop })
  })
  mockDelete.mockReset()
  mockDelete.mockImplementation(() => {
    server.shop = shopDto()
    return Promise.resolve({ data: server.shop })
  })
}

beforeAll(() => {
  Object.defineProperty(window, "print", { value: jest.fn(), writable: true })
})

beforeEach(() => {
  installFakeAudioContext("running")
  stubApi()
})

afterEach(() => {
  jest.useRealTimers()
})

const trigger = () => screen.findByRole("button", { name: /^(Pause orders|Paused)/ })
const banner = () => screen.queryByTestId("kds-paused-banner")

async function renderBoard() {
  render(<KitchenPage />)
  return trigger()
}

/** Radix opens the menu from the keyboard in jsdom (no PointerEvent). */
async function openMenu() {
  const t = await trigger()
  await act(async () => {
    fireEvent.keyDown(t, { key: "Enter" })
  })
  return screen.findByRole("menu")
}

async function choose(name: string) {
  const menu = await openMenu()
  await act(async () => {
    fireEvent.click(within(menu).getByRole("menuitem", { name }))
  })
}

describe("Pause orders from the kitchen header (UI-SPEC K3 vendor, D-15)", () => {
  it("the header has 'Pause orders' (outline, h-11, PauseCircle) after the shop selector, in the row that wraps", async () => {
    freezeClock()
    const t = await renderBoard()
    expect(t).toHaveTextContent("Pause orders")
    expect(t.className).toContain("h-11")
    expect(t.className).toContain("border")
    expect(t.querySelector("svg")).not.toBeNull()
    const select = screen.getByTestId("shop-select")
    expect(select.compareDocumentPosition(t) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // UI-SPEC K3 overflow: the control row wraps, so the pause and sound controls never overflow.
    const row = t.closest(".flex-wrap")
    expect(row).not.toBeNull()
    expect(row).toContainElement(select)
  })

  it("opens a menu of '20 minutes', '40 minutes' and 'Until I reopen', each a 44px row, with no confirm step", async () => {
    freezeClock()
    await renderBoard()
    const menu = await openMenu()
    const items = within(menu).getAllByRole("menuitem")
    expect(items.map((i) => i.textContent)).toEqual(["20 minutes", "40 minutes", "Until I reopen"])
    for (const item of items) expect(item.className).toContain("min-h-11")
    await act(async () => {
      fireEvent.click(items[0])
    })
    // One tap applies: the write went out, and no dialog asked first.
    expect(mockPut).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument()
  })

  it("'20 minutes' sends PUT {minutes: 20}; the control and banner show the SERVER's pausedUntil in UK time, never a client timer", async () => {
    freezeClock()
    await renderBoard()
    await choose("20 minutes")
    expect(mockPut).toHaveBeenCalledWith(`/api/v1/shops/${SHOP_ID}/pause`, { minutes: 20 })
    expect(await trigger()).toHaveTextContent("Paused until 18:41")
    expect(banner()).toHaveTextContent("Orders paused until 18:41. Customers can see your menu but can't order.")
    // 18:40 is the browser's own sum (now + 20); 17:41 is the server's instant printed in UTC.
    expect(document.body.textContent).not.toMatch(/18:40|17:41/)
  })

  it("'40 minutes' sends PUT {minutes: 40}", async () => {
    freezeClock()
    await renderBoard()
    await choose("40 minutes")
    expect(mockPut).toHaveBeenCalledWith(`/api/v1/shops/${SHOP_ID}/pause`, { minutes: 40 })
    expect(await trigger()).toHaveTextContent("Paused until 19:01")
  })

  it("'Until I reopen' sends PUT {untilReopened: true}: the control reads 'Paused' and the banner the indefinite copy, with a primary 'Resume orders'", async () => {
    freezeClock()
    await renderBoard()
    await choose("Until I reopen")
    expect(mockPut).toHaveBeenCalledWith(`/api/v1/shops/${SHOP_ID}/pause`, { untilReopened: true })
    const t = await trigger()
    expect(t.textContent?.trim()).toBe("Paused")
    const b = banner()
    expect(b).toHaveTextContent("Orders paused until you reopen. Customers can see your menu but can't order.")
    const resume = within(b as HTMLElement).getByRole("button", { name: "Resume orders" })
    expect(resume.className).toContain("h-11")
    expect(resume.className).toContain("bg-primary")
  })

  it("the banner is white with a 2px slate-900 border, never slate-900-filled (L6: that fill is the allergy note's)", async () => {
    freezeClock()
    await renderBoard()
    await choose("20 minutes")
    const b = banner() as HTMLElement
    for (const token of ["bg-white", "border-2", "border-slate-900", "rounded-lg", "p-4"]) {
      expect(b.classList).toContain(token)
    }
    expect(Array.from(b.classList).some((c) => c === "bg-slate-900")).toBe(false)
  })

  it("while paused the menu adds 'Resume orders now'; 'Resume orders' sends DELETE and the banner is gone after the re-read", async () => {
    freezeClock()
    await renderBoard()
    await choose("20 minutes")
    const menu = await openMenu()
    expect(within(menu).getAllByRole("menuitem").map((i) => i.textContent)).toEqual([
      "20 minutes",
      "40 minutes",
      "Until I reopen",
      "Resume orders now",
    ])
    await act(async () => {
      fireEvent.keyDown(menu, { key: "Escape" })
    })
    const readsBefore = mockGet.mock.calls.filter(([u]) => u === `/api/v1/shops/${SHOP_ID}`).length
    await act(async () => {
      fireEvent.click(within(banner() as HTMLElement).getByRole("button", { name: "Resume orders" }))
    })
    expect(mockDelete).toHaveBeenCalledWith(`/api/v1/shops/${SHOP_ID}/pause`)
    await waitFor(() => expect(banner()).not.toBeInTheDocument())
    expect(mockGet.mock.calls.filter(([u]) => u === `/api/v1/shops/${SHOP_ID}`).length).toBeGreaterThan(readsBefore)
    expect((await trigger()).textContent?.trim()).toBe("Pause orders")
  })

  it("'Resume orders now' in the menu resumes too", async () => {
    freezeClock()
    await renderBoard()
    await choose("Until I reopen")
    await choose("Resume orders now")
    expect(mockDelete).toHaveBeenCalledWith(`/api/v1/shops/${SHOP_ID}/pause`)
    await waitFor(() => expect(banner()).not.toBeInTheDocument())
  })

  it("a pause made on another device shows on the board's next 60 s re-read, with the server's time (no tap here)", async () => {
    jest.useFakeTimers({ now: NOW })
    render(<KitchenPage />)
    await act(async () => {
      await jest.advanceTimersByTimeAsync(0)
    })
    expect(banner()).not.toBeInTheDocument()
    server.shop = shopDto({ ordersPausedUntil: SERVER_UNTIL })
    await act(async () => {
      await jest.advanceTimersByTimeAsync(60_000)
    })
    expect(banner()).toHaveTextContent("Orders paused until 18:41.")
  })

  it("an ended pause the shop read still carries (a past ordersPausedUntil) is no pause", async () => {
    freezeClock()
    server.shop = shopDto({ ordersPausedUntil: "2026-10-13T17:00:00Z" })
    const t = await renderBoard()
    await waitFor(() => expect(mockGet).toHaveBeenCalledWith(`/api/v1/shops/${SHOP_ID}`))
    expect(t.textContent?.trim()).toBe("Pause orders")
    expect(banner()).not.toBeInTheDocument()
  })

  it("at the stated time the board re-reads the shop: a pause extended elsewhere stays, an ended one clears", async () => {
    jest.useFakeTimers({ now: NOW })
    server.shop = shopDto({ ordersPausedUntil: SERVER_UNTIL })
    render(<KitchenPage />)
    await act(async () => {
      await jest.advanceTimersByTimeAsync(0)
    })
    expect(banner()).toHaveTextContent("Orders paused until 18:41.")
    // Another device extended it before it ended.
    server.shop = shopDto({ ordersPausedUntil: "2026-10-13T17:50:00Z" })
    await act(async () => {
      await jest.advanceTimersByTimeAsync(21 * 60_000 + 1_000)
    })
    expect(banner()).toHaveTextContent("Orders paused until 18:50.")
    // Now it ends, and the server keeps the stale instant on the row.
    await act(async () => {
      await jest.advanceTimersByTimeAsync(10 * 60_000)
    })
    expect(banner()).not.toBeInTheDocument()
  })

  it("while a pause or resume is in flight every menu item, the control and 'Resume orders' are disabled, so a second tap cannot send a conflicting request", async () => {
    freezeClock()
    server.shop = shopDto({ ordersPausedUntil: SERVER_UNTIL })
    let settle: (v: unknown) => void = () => {}
    mockPut.mockImplementation(() => new Promise((resolve) => (settle = resolve)))
    await renderBoard()
    await waitFor(() => expect(banner()).toBeInTheDocument())
    const menu = await openMenu()
    await act(async () => {
      fireEvent.click(within(menu).getByRole("menuitem", { name: "40 minutes" }))
    })
    for (const item of within(menu).getAllByRole("menuitem")) {
      expect(item).toHaveAttribute("aria-disabled", "true")
    }
    expect(within(banner() as HTMLElement).getByRole("button", { name: "Resume orders" })).toBeDisabled()
    expect(await trigger()).toBeDisabled()
    // A second tap while in flight sends nothing.
    await act(async () => {
      fireEvent.click(within(menu).getByRole("menuitem", { name: "Until I reopen" }))
      fireEvent.click(within(banner() as HTMLElement).getByRole("button", { name: "Resume orders" }))
    })
    expect(mockPut).toHaveBeenCalledTimes(1)
    expect(mockDelete).not.toHaveBeenCalled()
    await act(async () => {
      settle({ data: shopDto({ ordersPausedUntil: "2026-10-13T18:01:00Z" }) })
    })
    await waitFor(() => expect(within(banner() as HTMLElement).getByRole("button", { name: "Resume orders" })).toBeEnabled())
    expect(await trigger()).toBeEnabled()
  })

  it("a 403 shows 'You can't pause orders for this shop. Ask a manager.' and pauses nothing", async () => {
    freezeClock()
    mockPut.mockImplementation(() =>
      Promise.reject({
        response: { status: 403, data: { type: "https://jtoye.uk/errors/shop-access-denied", status: 403 } },
      })
    )
    await renderBoard()
    await choose("20 minutes")
    expect(await screen.findByRole("alert")).toHaveTextContent("You can't pause orders for this shop. Ask a manager.")
    expect(banner()).not.toBeInTheDocument()
    expect((await trigger()).textContent?.trim()).toBe("Pause orders")
  })
})

describe("PausedBanner long text (UI-SPEC K3 long-text backstop, structural half)", () => {
  it("names the shop and lets a 60-character name wrap: no truncation, no nowrap, overflow-wrap anywhere", () => {
    const longName = "Mama Put's Jollof, Suya & Small Chops Kitchen of Peckham Rye"
    expect(longName).toHaveLength(60)
    render(
      <PausedBanner
        shopName={longName}
        view={{ kind: "timed", until: SERVER_UNTIL }}
        pending={false}
        onResume={jest.fn()}
      />
    )
    const name = screen.getByText(longName)
    const text = name.parentElement as HTMLElement
    expect(text.className).toContain("min-w-0")
    expect(text.className).toContain("[overflow-wrap:anywhere]")
    let el: HTMLElement | null = name
    while (el) {
      expect(el.className).not.toMatch(/\btruncate\b|whitespace-nowrap|line-clamp/)
      el = el.parentElement
    }
  })
})

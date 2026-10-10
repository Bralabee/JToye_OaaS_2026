/**
 * 37-18 (D-14, D-08 on the kitchen route; UXT-021, UI-SPEC K2): when the kitchen board's session
 * lapses or its access ends, the board stops LOUDLY instead of quietly becoming a sign-in page.
 *
 * Two layers, both here so the plan's verify runs them together:
 *   1. KdsBoardStopped on its own: the alertdialog contract (modal, labelled, described, focus on
 *      the primary button and trapped there, the rest of the page inert), the copy, the return
 *      link, and that nothing in it animates.
 *   2. The REAL kitchen page over the REAL api-client (only the HTTP adapter, the session, the
 *      socket and the Radix Select are faked), so a 401 the silent refresh cannot fix travels the
 *      same path it does in a browser: interceptor -> cancellable `jtoye:session-lapsed` -> the
 *      kitchen claims it -> overlay. Navigation is observed the way jsdom reports it: an attempt
 *      to leave the page logs "Not implemented: navigation" and leaves `location` unchanged.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { getSession } from "next-auth/react"
import KitchenPage from "@/app/dashboard/kitchen/page"
import apiClient, { __testing as apiClientTesting } from "@/lib/api-client"
import { SESSION_LAPSED_EVENT } from "@/lib/session-lapse"
import {
  KdsBoardStopped,
  KITCHEN_SIGN_IN_HREF,
  STOPPED_TAB_TITLE,
} from "@/components/dashboard/kitchen/kds-board-stopped"
import { chimeCount, installFakeAudioContext } from "@/test-utils/fake-audio-context"

jest.mock("next-auth/react", () => ({
  getSession: jest.fn(),
  useSession: jest.fn(() => ({ data: null, status: "unauthenticated" })),
  signIn: jest.fn(),
  signOut: jest.fn(),
}))

jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
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

// ---------------------------------------------------------------------------------------------
// The HTTP layer: the real api-client, with a scripted adapter.
// ---------------------------------------------------------------------------------------------

const adapter = jest.fn()
apiClient.defaults.adapter = adapter
apiClient.interceptors.request.use((config) => {
  config.adapter = adapter
  return config
})

const SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied"
const signedIn = {
  accessToken: "token-1",
  user: { tenantId: "tenant-1", id: "u-1", name: "Cook", email: "cook@example.com" },
  expires: "2099-12-31",
}
const lapsedSession = { user: {}, error: "RefreshTokenError", expires: "2099-12-31" }

/** What the server answers for the kitchen board and the bumps: "ok", a 401, or the typed 403. */
const server: { kitchen: "ok" | 401 | 403; bump: "ok" | 401 | 403 } = { kitchen: "ok", bump: "ok" }
let boardOrders: Array<Record<string, unknown>> = []

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

function pageOf(items: Array<Record<string, unknown>>) {
  return { content: items, totalElements: items.length, totalPages: 1, size: 100, number: 0, first: true, last: true }
}

function respond(config: unknown, status: number, data: unknown) {
  if (status < 400) {
    return Promise.resolve({ data, status, statusText: "OK", headers: {}, config })
  }
  const e = new Error(`HTTP ${status}`) as Error & Record<string, unknown>
  e.isAxiosError = true
  e.config = config
  e.response = { status, data, headers: {}, statusText: "err", config }
  return Promise.reject(e)
}

function answer(config: { url?: string; method?: string }, outcome: "ok" | 401 | 403, data: unknown) {
  if (outcome === 401) return respond(config, 401, {})
  if (outcome === 403) return respond(config, 403, { type: SHOP_ACCESS_DENIED, status: 403 })
  return respond(config, 200, data)
}

function installServer() {
  adapter.mockReset()
  adapter.mockImplementation((config: { url?: string; method?: string }) => {
    const url = config.url ?? ""
    if (url.startsWith("/api/v1/shops")) return respond(config, 200, pageOf([shop("shop-1", "Test Shop")]))
    if (url.startsWith("/api/v1/orders/kitchen")) return answer(config, server.kitchen, pageOf(boardOrders))
    if (config.method === "post") return answer(config, server.bump, {})
    if (url.endsWith("/detail")) return answer(config, server.kitchen, orderFixture(url.split("/")[4], "PENDING"))
    return respond(config, 200, {})
  })
}

// ---------------------------------------------------------------------------------------------
// Navigation, as jsdom reports it.
// ---------------------------------------------------------------------------------------------

let navigationAttempts = 0
let consoleErrorSpy: jest.SpyInstance
beforeAll(() => {
  Object.defineProperty(window, "print", { value: jest.fn(), writable: true })
  consoleErrorSpy = jest.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
    if (String(args[0]).includes("Not implemented: navigation")) navigationAttempts += 1
  })
})
afterAll(() => consoleErrorSpy.mockRestore())

const advance = async (ms: number) => {
  await act(async () => {
    jest.advanceTimersByTime(ms)
  })
}

const dialog = () => screen.queryByRole("alertdialog")

/** Render the page and wait for its first board read to land. */
async function renderBoard() {
  const utils = render(<KitchenPage />)
  await waitFor(() => expect(screen.getByTestId("kds-feed-pill")).toBeInTheDocument())
  await waitFor(() => expect(adapter.mock.calls.some(([c]) => String(c.url).startsWith("/api/v1/orders/kitchen"))).toBe(true))
  return utils
}

/** The session ends at the identity provider: every read 401s and the refresh comes back empty. */
async function lapseAndPoll() {
  ;(getSession as jest.Mock).mockResolvedValue(lapsedSession)
  server.kitchen = 401
  // The board's own 60 s liveness poll is the read that discovers it.
  await advance(60_000)
}

beforeEach(() => {
  localStorage.clear()
  document.title = ""
  navigationAttempts = 0
  mockToast.mockReset()
  server.kitchen = "ok"
  server.bump = "ok"
  boardOrders = [orderFixture("cook-1", "CONFIRMED")]
  apiClientTesting.resetRefresh()
  ;(getSession as jest.Mock).mockReset()
  ;(getSession as jest.Mock).mockResolvedValue(signedIn)
  installServer()
  jest.useFakeTimers()
  installFakeAudioContext("running")
})

afterEach(() => {
  jest.useRealTimers()
})

// =============================================================================================
// 1. The overlay on its own (UI-SPEC K2)
// =============================================================================================

describe("KdsBoardStopped: the board-stopped overlay (K2)", () => {
  it("is a solid red, modal alertdialog labelled by its headline and described by its body", () => {
    render(<KdsBoardStopped reason="signedOut" />)

    const overlay = screen.getByRole("alertdialog")
    expect(overlay).toHaveAttribute("aria-modal", "true")
    expect(overlay).toHaveAccessibleName("Signed out: new orders are NOT showing")
    expect(overlay).toHaveAccessibleDescription(
      "Your sign-in has ended, so this screen has stopped receiving orders. Sign in again to bring the board back."
    )
    expect(overlay.className).toMatch(/\bfixed\b/)
    expect(overlay.className).toMatch(/\binset-0\b/)
    expect(overlay.className).toMatch(/\bbg-red-700\b/)
    expect(overlay.className).toMatch(/\boverflow-y-auto\b/)
  })

  it("moves focus to 'Sign in again', which returns the tablet to the kitchen", () => {
    render(<KdsBoardStopped reason="signedOut" />)

    const primary = screen.getByRole("link", { name: "Sign in again" })
    expect(primary).toHaveFocus()
    expect(primary).toHaveAttribute("href", KITCHEN_SIGN_IN_HREF)
    expect(KITCHEN_SIGN_IN_HREF).toBe("/auth/signin?callbackUrl=%2Fdashboard%2Fkitchen")
  })

  it("makes the rest of the page inert while it shows, and gives it back when it goes", () => {
    const { container, unmount } = render(
      <>
        <button type="button">Bump order</button>
        <KdsBoardStopped reason="signedOut" />
      </>
    )

    // The page the test rendered into sits beside the overlay, not inside it.
    expect(container).toHaveAttribute("inert")
    expect(screen.getByRole("alertdialog").closest("[inert]")).toBeNull()

    unmount()
    expect(container).not.toHaveAttribute("inert")
  })

  it("keeps Tab inside the dialog: past the last control it wraps to the first, and back", () => {
    render(<KdsBoardStopped reason="signedOut" />)
    const overlay = screen.getByRole("alertdialog")
    const focusables = Array.from(overlay.querySelectorAll<HTMLElement>("a[href], button:not([disabled])"))
    expect(focusables.length).toBeGreaterThanOrEqual(2)
    const first = focusables[0]
    const last = focusables[focusables.length - 1]

    last.focus()
    fireEvent.keyDown(last, { key: "Tab" })
    expect(first).toHaveFocus()

    fireEvent.keyDown(first, { key: "Tab", shiftKey: true })
    expect(last).toHaveFocus()
  })

  it("brings focus straight back if anything outside it takes focus", () => {
    render(
      <>
        <button type="button">Bump order</button>
        <KdsBoardStopped reason="signedOut" />
      </>
    )
    const outside = screen.getByRole("button", { name: "Bump order", hidden: true })

    act(() => outside.focus())

    expect(screen.getByRole("link", { name: "Sign in again" })).toHaveFocus()
  })

  it("is static: nothing in it animates, transitions or flashes", () => {
    render(<KdsBoardStopped reason="signedOut" />)
    const overlay = screen.getByRole("alertdialog")
    const all = [overlay, ...Array.from(overlay.querySelectorAll<HTMLElement>("*"))]
    const moving = all.filter((el) =>
      /(^|\s)(animate-|transition|motion-safe:animate|kds-new-flash)/.test(el.getAttribute("class") ?? "")
    )
    expect(moving).toEqual([])
  })
})

// =============================================================================================
// 2. The kitchen page claims the lapse (F6)
// =============================================================================================

describe("Kitchen page: a lapsed session stops the board loudly instead of redirecting (F6)", () => {
  it("claims the lapse event: the overlay shows and the page stays where it is", async () => {
    await renderBoard()
    const before = window.location.href

    const lapseEvent = new CustomEvent(SESSION_LAPSED_EVENT, { cancelable: true })
    act(() => {
      window.dispatchEvent(lapseEvent)
    })

    expect(lapseEvent.defaultPrevented).toBe(true)
    const overlay = dialog()
    expect(overlay).not.toBeNull()
    expect(overlay).toHaveAccessibleName("Signed out: new orders are NOT showing")
    expect(window.location.href).toBe(before)
    expect(navigationAttempts).toBe(0)
  })

  it("an unrefreshable 401 on a kitchen read shows the overlay, never the sign-in page (UXT-021 repro)", async () => {
    await renderBoard()
    const before = window.location.href

    await lapseAndPoll()

    await waitFor(() => expect(dialog()).not.toBeNull())
    expect(window.location.href).toBe(before)
    expect(navigationAttempts).toBe(0)
    expect(screen.getByRole("link", { name: "Sign in again" })).toHaveFocus()
  })

  it("the pill reads 'Signed out' and the tab title 'STOPPED' behind the overlay", async () => {
    await renderBoard()

    await lapseAndPoll()

    await waitFor(() => expect(dialog()).not.toBeNull())
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("Signed out")
    expect(screen.getByTestId("kds-feed-pill")).not.toHaveTextContent("Live")
    expect(document.title).toBe(STOPPED_TAB_TITLE)
    expect(STOPPED_TAB_TITLE).toBe("STOPPED · Kitchen Display — J'Toye")
  })

  it("the board's content is inert behind the overlay", async () => {
    const { container } = await renderBoard()

    await lapseAndPoll()

    await waitFor(() => expect(dialog()).not.toBeNull())
    expect(container).toHaveAttribute("inert")
    expect(within(container).getByText("ORD-cook-1")).toBeInTheDocument()
  })

  it("the new-order chime stops when the board stops", async () => {
    boardOrders = [orderFixture("new-1", "PENDING")]
    await renderBoard()
    await waitFor(() => expect(screen.getByText("ORD-new-1")).toBeInTheDocument())
    fireEvent.click(within(screen.getByTestId("kds-sound-control")).getByText("Turn on sound"))
    await advance(0)
    expect(chimeCount()).toBeGreaterThanOrEqual(1)

    act(() => {
      window.dispatchEvent(new CustomEvent(SESSION_LAPSED_EVENT, { cancelable: true }))
    })
    const chimesAtStop = chimeCount()
    await advance(15_000)

    expect(dialog()).not.toBeNull()
    expect(chimeCount()).toBe(chimesAtStop)
  })
})

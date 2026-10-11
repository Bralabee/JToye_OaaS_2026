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
import { act, fireEvent, render, renderHook, screen, waitFor, within } from "@testing-library/react"
import { getSession } from "next-auth/react"
import KitchenPage from "@/app/dashboard/kitchen/page"
import apiClient, { __testing as apiClientTesting } from "@/lib/api-client"
import { SESSION_LAPSED_EVENT } from "@/lib/session-lapse"
import { useStomp } from "@/hooks/use-stomp"
import { vendorLogout } from "@/lib/vendor-logout"
import {
  __resetDeliberateSignOutForTests,
  isDeliberateSignOut,
  KdsBoardStopped,
  KITCHEN_SIGN_IN_HREF,
  markDeliberateSignOut,
  SOUND_NEVER_ENABLED_COPY,
  STOPPED_TAB_TITLE,
} from "@/components/dashboard/kitchen/kds-board-stopped"
import {
  BOARD_STOPPED_ALARM,
  BOARD_STOPPED_ALARM_INTERVAL_MS,
  BOARD_STOPPED_ALARM_MAX_MS,
  NEW_ORDER_CHIME,
  playAlarmPattern,
} from "@/components/dashboard/kitchen/kds-sound"
import {
  SESSION_REFRESH_LEAD_MS,
  useSessionKeepAlive,
} from "@/components/dashboard/kitchen/use-session-keep-alive"
import {
  audioRecord,
  chimeCount,
  FakeAudioContext,
  installFakeAudioContext,
} from "@/test-utils/fake-audio-context"

jest.mock("next-auth/react", () => ({
  getSession: jest.fn(),
  useSession: jest.fn(() => ({ data: null, status: "unauthenticated" })),
  signIn: jest.fn(),
  signOut: jest.fn(),
}))

jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

// The access-ended overlay's "Sign out" is the real vendor sign-out; what that function does is
// lib/__tests__/vendor-logout.test.ts's job. Pending for ever, like a navigation that has not landed.
jest.mock("@/lib/vendor-logout", () => ({
  vendorLogout: jest.fn(() => new Promise<string>(() => {})),
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
  __resetDeliberateSignOutForTests()
  ;(vendorLogout as jest.Mock).mockClear()
  ;(useStomp as jest.Mock).mockClear()
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

// =============================================================================================
// 3. The alarm (UI-SPEC K2: three 440 Hz square tones, 120 ms each, 80 ms apart, every 3 s)
// =============================================================================================

const alarmStarts = () =>
  audioRecord.starts.filter((s) => s.frequency === 440 && s.type === "square").length

const turnOnSound = async () => {
  fireEvent.click(within(screen.getByTestId("kds-sound-control")).getByText("Turn on sound"))
  await advance(0)
}

const lapseNow = () =>
  act(() => {
    window.dispatchEvent(new CustomEvent(SESSION_LAPSED_EVENT, { cancelable: true }))
  })

describe("playAlarmPattern: the board-stopped alarm (K2)", () => {
  it("is three 440 Hz square tones, 120 ms each and 80 ms apart, distinct from the new-order chime", () => {
    const ctx = new FakeAudioContext()
    ctx.currentTime = 10

    playAlarmPattern(ctx as unknown as AudioContext)

    expect(audioRecord.starts).toHaveLength(3)
    expect(audioRecord.starts.every((s) => s.frequency === 440 && s.type === "square")).toBe(true)
    const at = audioRecord.starts.map((s) => s.at - 10)
    expect(at[0]).toBeCloseTo(0, 5)
    expect(at[1]).toBeCloseTo(0.2, 5)
    expect(at[2]).toBeCloseTo(0.4, 5)
    expect(BOARD_STOPPED_ALARM.toneSeconds).toBe(0.12)
    expect(BOARD_STOPPED_ALARM.gapSeconds).toBe(0.08)
    expect(BOARD_STOPPED_ALARM_INTERVAL_MS).toBe(3000)
    // Distinguishable by ear from the chime: different pitch AND a different waveform.
    expect(NEW_ORDER_CHIME.type).toBe("sine")
    expect(NEW_ORDER_CHIME.tonesHz).not.toContain(440)
  })

  it("plays nothing on a context that cannot sound", () => {
    installFakeAudioContext("suspended")
    const ctx = new FakeAudioContext()

    playAlarmPattern(ctx as unknown as AudioContext)

    expect(audioRecord.starts).toHaveLength(0)
  })
})

describe("Kitchen page: a stopped board rings the alarm (K2)", () => {
  it("rings the alarm at once and every 3 s, through the board's one AudioContext", async () => {
    await renderBoard()
    await turnOnSound()
    expect(alarmStarts()).toBe(0)

    lapseNow()
    await advance(0)
    expect(alarmStarts()).toBe(3)

    await advance(BOARD_STOPPED_ALARM_INTERVAL_MS * 3)

    expect(alarmStarts()).toBe(12)
    expect(audioRecord.instances).toHaveLength(1)
  })

  it("'Silence alarm' stops the sound only: the red screen stays", async () => {
    await renderBoard()
    await turnOnSound()
    lapseNow()
    await advance(0)
    const before = alarmStarts()

    fireEvent.click(screen.getByRole("button", { name: /alarm/i }))
    await advance(BOARD_STOPPED_ALARM_INTERVAL_MS * 3)

    expect(alarmStarts()).toBe(before)
    expect(dialog()).not.toBeNull()
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("Signed out")
  })

  it("an unsilenced alarm stops by itself 120 s after it starts, while the red screen and the pill stay (owner ruling 2026-10-10: alarm fatigue)", async () => {
    await renderBoard()
    await turnOnSound()
    lapseNow()
    await advance(0)
    expect(alarmStarts()).toBe(3)

    await advance(120_000)
    const atCap = alarmStarts()
    expect(atCap).toBeGreaterThan(3)

    await advance(30_000)

    expect(alarmStarts()).toBe(atCap)
    expect(dialog()).not.toBeNull()
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("Signed out")
    expect(BOARD_STOPPED_ALARM_MAX_MS).toBe(120_000)
  })

  it("mute never silences the alarm: with new-order alerts muted (kds-muted) a stopped board still rings", async () => {
    await renderBoard()
    await turnOnSound()
    fireEvent.click(screen.getByRole("button", { name: "Mute alerts" }))
    await advance(0)
    expect(localStorage.getItem("kds-muted")).toBe("true")

    lapseNow()
    await advance(0)
    expect(alarmStarts()).toBe(3)
    await advance(BOARD_STOPPED_ALARM_INTERVAL_MS)
    expect(alarmStarts()).toBe(6)
  })

  it("a board whose sound was never turned on still stops, says it cannot ring, and builds no AudioContext", async () => {
    await renderBoard()

    lapseNow()
    await advance(BOARD_STOPPED_ALARM_INTERVAL_MS * 3)

    const overlay = dialog()
    expect(overlay).not.toBeNull()
    expect(overlay).toHaveAccessibleDescription(expect.stringContaining(SOUND_NEVER_ENABLED_COPY))
    expect(SOUND_NEVER_ENABLED_COPY).toBe(
      "This screen can't sound an alarm because sound was never turned on."
    )
    expect(audioRecord.instances).toHaveLength(0)
    expect(alarmStarts()).toBe(0)
  })

  it("a board with sound on does not claim it cannot ring", async () => {
    await renderBoard()
    await turnOnSound()

    lapseNow()

    expect(dialog()).not.toHaveAccessibleDescription(expect.stringContaining(SOUND_NEVER_ENABLED_COPY))
  })
})

// =============================================================================================
// 4. Access ended (D-08 on the kitchen route)
// =============================================================================================

const lastStompTopic = () => {
  const calls = (useStomp as jest.Mock).mock.calls
  return calls[calls.length - 1]?.[0]
}

describe("Kitchen page: access ended stops the board (D-08)", () => {
  it("a kitchen read refused with shop-access-denied shows the access-ended overlay and disconnects the socket", async () => {
    await renderBoard()
    await waitFor(() => expect(lastStompTopic()).toBe("/topic/kitchen.tenant-1.shop-1"))

    server.kitchen = 403
    await advance(60_000)

    await waitFor(() => expect(dialog()).not.toBeNull())
    expect(dialog()).toHaveAccessibleName("Your access has ended: new orders are NOT showing")
    expect(dialog()).toHaveAccessibleDescription(
      expect.stringContaining("Someone has removed your access to this kitchen. Ask a Group admin if you need it back.")
    )
    expect(screen.getByRole("button", { name: "Sign out" })).toHaveFocus()
    expect(lastStompTopic()).toBeNull()
    expect(screen.getByTestId("kds-feed-pill")).toHaveTextContent("No access")
    expect(document.title).toBe(STOPPED_TAB_TITLE)
    expect(navigationAttempts).toBe(0)
  })

  it("a refused bump ends access the same way", async () => {
    await renderBoard()
    await waitFor(() => expect(screen.getByText("ORD-cook-1")).toBeInTheDocument())

    server.bump = 403
    fireEvent.click(screen.getByRole("button", { name: /Start Preparing/ }))

    await waitFor(() =>
      expect(dialog()).toHaveAccessibleName("Your access has ended: new orders are NOT showing")
    )
  })

  it("'Sign out' on the access-ended overlay is a deliberate sign-out", async () => {
    await renderBoard()
    server.kitchen = 403
    await advance(60_000)
    await waitFor(() => expect(dialog()).not.toBeNull())

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }))

    expect(vendorLogout).toHaveBeenCalledTimes(1)
    expect(isDeliberateSignOut()).toBe(true)
  })

  it("a stopped board stops reading: no more board polls once it has stopped", async () => {
    await renderBoard()
    server.kitchen = 403
    await advance(60_000)
    await waitFor(() => expect(dialog()).not.toBeNull())
    const reads = () =>
      adapter.mock.calls.filter(([c]) => String(c.url).startsWith("/api/v1/orders/kitchen")).length
    const readsAtStop = reads()

    await advance(180_000)

    expect(reads()).toBe(readsAtStop)
  })
})

// =============================================================================================
// 5. A deliberate sign-out never stops the board (goods P2-TUN-14)
// =============================================================================================

describe("Kitchen page: a deliberate sign-out shows no overlay and no alarm (P2-TUN-14)", () => {
  it("after Sign out, a lapse is not claimed: no overlay, no alarm, and the browser goes to sign-in as today", async () => {
    await renderBoard()
    await turnOnSound()

    markDeliberateSignOut()
    await lapseAndPoll()
    await advance(BOARD_STOPPED_ALARM_INTERVAL_MS * 3)

    expect(dialog()).toBeNull()
    expect(alarmStarts()).toBe(0)
    expect(navigationAttempts).toBeGreaterThanOrEqual(1)
  })

  it("after Sign out, a dispatched lapse event is left unclaimed", async () => {
    await renderBoard()

    markDeliberateSignOut()
    const lapseEvent = new CustomEvent(SESSION_LAPSED_EVENT, { cancelable: true })
    act(() => {
      window.dispatchEvent(lapseEvent)
    })

    expect(lapseEvent.defaultPrevented).toBe(false)
    expect(dialog()).toBeNull()
  })

  it("after Sign out, a refused read does not show the access-ended overlay either", async () => {
    await renderBoard()

    markDeliberateSignOut()
    server.kitchen = 403
    await advance(60_000)

    expect(dialog()).toBeNull()
  })
})

// =============================================================================================
// 6. The silent refresh while the SSO session lasts (D-14)
// =============================================================================================

const nowSeconds = () => Math.floor(Date.now() / 1000)

describe("useSessionKeepAlive: the board refreshes its token before it expires (D-14)", () => {
  it("asks for the session again a minute before the access token expires, and again before the next expiry", async () => {
    const calls: number[] = []
    const expiries: number[] = []
    ;(getSession as jest.Mock).mockImplementation(() => {
      calls.push(Date.now())
      // A 5-minute access token, its expiry in whole seconds as Keycloak and NextAuth give it.
      const expiresAt = nowSeconds() + 300
      expiries.push(expiresAt)
      return Promise.resolve({ ...signedIn, expiresAt })
    })

    renderHook(() => useSessionKeepAlive(true))
    await advance(0)
    expect(calls).toHaveLength(1)

    await advance(300_000 - SESSION_REFRESH_LEAD_MS - 1_000)
    expect(calls).toHaveLength(1)

    await advance(1_000)
    expect(calls).toHaveLength(2)
    // Exactly the lead before the first token's expiry: before it, never at or after it.
    expect(calls[1]).toBe(expiries[0] * 1000 - SESSION_REFRESH_LEAD_MS)
    expect(calls[1]).toBeLessThan(expiries[0] * 1000)

    await advance(300_000 - SESSION_REFRESH_LEAD_MS)
    expect(calls).toHaveLength(3)
    expect(calls[2]).toBe(expiries[1] * 1000 - SESSION_REFRESH_LEAD_MS)
  })

  it("asks nothing while the board is stopped", async () => {
    ;(getSession as jest.Mock).mockResolvedValue({ ...signedIn, expiresAt: nowSeconds() + 300 })

    renderHook(() => useSessionKeepAlive(false))
    await advance(600_000)

    expect(getSession).not.toHaveBeenCalled()
  })

  it("on the page: a minute before expiry the board asks again, and a session that keeps refreshing never stops it", async () => {
    const calls: number[] = []
    ;(getSession as jest.Mock).mockImplementation(() => {
      calls.push(Date.now())
      // A 290 s token, so the keep-alive (at 230 s) cannot coincide with the 60 s board poll.
      return Promise.resolve({ ...signedIn, expiresAt: nowSeconds() + 290 })
    })
    const firstExpiry = nowSeconds() + 290
    await renderBoard()

    for (let i = 0; i < 60; i++) await advance(10_000)

    // The keep-alive's own ask: a minute before the first token's expiry. Not a poll (those fall on
    // whole minutes from mount; this falls about 230 s in).
    expect(calls).toContain(firstExpiry * 1000 - SESSION_REFRESH_LEAD_MS)
    expect(dialog()).toBeNull()
    expect(navigationAttempts).toBe(0)
  })
})

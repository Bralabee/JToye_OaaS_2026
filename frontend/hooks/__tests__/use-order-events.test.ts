/**
 * Unit tests for the shared SSE order-events hook (#92):
 *  - connects with the NextAuth bearer token
 *  - dispatches only order-state-change events (ignores others + malformed JSON)
 *  - auto-reconnects after graceful close AND after errors (the old inline
 *    page code gave up permanently on the first error)
 *  - refreshes the token on every reconnect attempt
 *  - backs off exponentially with a cap, resetting on successful open
 *  - stops for good on unmount
 */

import { renderHook } from "@testing-library/react"
import { getSession } from "next-auth/react"

type FesOptions = {
  signal: AbortSignal
  headers: Record<string, string>
  openWhenHidden?: boolean
  onopen?: (res: { ok: boolean; status?: number }) => Promise<void>
  onmessage?: (ev: { event: string; data: string }) => void
  onerror?: (err: unknown) => number | void
}

type FesCall = {
  url: string
  options: FesOptions
  resolve: () => void
  reject: (err: unknown) => void
}

const mockFesCalls: FesCall[] = []

jest.mock("@microsoft/fetch-event-source", () => ({
  fetchEventSource: jest.fn(
    (url: string, options: FesOptions) =>
      new Promise<void>((resolve, reject) => {
        mockFesCalls.push({ url, options, resolve, reject })
      })
  ),
}))

import {
  useOrderEvents,
  getRetryDelayMs,
  SSE_INITIAL_RETRY_MS,
  SSE_MAX_RETRY_MS,
} from "../use-order-events"

// The hook's async connect loop hops through a few microtasks between
// getSession() and fetchEventSource(); drain them deterministically.
const flushMicrotasks = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
  }
}

beforeEach(() => {
  mockFesCalls.length = 0
  ;(getSession as jest.Mock).mockReset()
  ;(getSession as jest.Mock).mockResolvedValue({ accessToken: "token-1" })
  jest.useFakeTimers()
  // Deterministic jitter: factor 0.75 + 0.5*0.5 = 1.0 (delay == pure exponential)
  jest.spyOn(Math, "random").mockReturnValue(0.5)
})

afterEach(() => {
  jest.useRealTimers()
  jest.restoreAllMocks()
})

describe("getRetryDelayMs", () => {
  it("grows exponentially from the initial delay", () => {
    const noJitter = () => 0.5
    expect(getRetryDelayMs(0, noJitter)).toBe(SSE_INITIAL_RETRY_MS)
    expect(getRetryDelayMs(1, noJitter)).toBe(2 * SSE_INITIAL_RETRY_MS)
    expect(getRetryDelayMs(2, noJitter)).toBe(4 * SSE_INITIAL_RETRY_MS)
    expect(getRetryDelayMs(3, noJitter)).toBe(8 * SSE_INITIAL_RETRY_MS)
  })

  it("caps at the maximum delay, even for huge attempt counts", () => {
    const noJitter = () => 0.5
    expect(getRetryDelayMs(5, noJitter)).toBe(SSE_MAX_RETRY_MS)
    expect(getRetryDelayMs(50, noJitter)).toBe(SSE_MAX_RETRY_MS)
    expect(getRetryDelayMs(10_000, noJitter)).toBe(SSE_MAX_RETRY_MS)
  })

  it("applies jitter within [0.75, 1.25] of the exponential delay (rounded)", () => {
    expect(getRetryDelayMs(0, () => 0)).toBe(0.75 * SSE_INITIAL_RETRY_MS)
    expect(getRetryDelayMs(0, () => 0.999999)).toBeLessThanOrEqual(1.25 * SSE_INITIAL_RETRY_MS)
    expect(getRetryDelayMs(0, () => 0.999999)).toBeGreaterThan(1.2 * SSE_INITIAL_RETRY_MS)
  })
})

describe("useOrderEvents", () => {
  it("connects to the order stream with the session bearer token", async () => {
    renderHook(() => useOrderEvents(jest.fn()))
    await flushMicrotasks()

    expect(mockFesCalls).toHaveLength(1)
    expect(mockFesCalls[0].url).toBe(
      `${process.env.NEXT_PUBLIC_API_URL}/api/v1/orders/stream`
    )
    expect(mockFesCalls[0].options.headers).toEqual({
      Authorization: "Bearer token-1",
    })
    expect(mockFesCalls[0].options.openWhenHidden).toBe(true)
  })

  it("dispatches parsed order-state-change events to the callback", async () => {
    const onEvent = jest.fn()
    renderHook(() => useOrderEvents(onEvent))
    await flushMicrotasks()

    const { onmessage } = mockFesCalls[0].options
    onmessage!({
      event: "order-state-change",
      data: JSON.stringify({ orderId: "o-1", newStatus: "CONFIRMED" }),
    })

    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(onEvent).toHaveBeenCalledWith({ orderId: "o-1", newStatus: "CONFIRMED" })
  })

  it("ignores non-order events and malformed payloads without crashing", async () => {
    const onEvent = jest.fn()
    renderHook(() => useOrderEvents(onEvent))
    await flushMicrotasks()

    const { onmessage } = mockFesCalls[0].options
    onmessage!({ event: "some-other-event", data: "{}" })
    onmessage!({ event: "order-state-change", data: "not-json{{{" })

    expect(onEvent).not.toHaveBeenCalled()
  })

  it("accepts an ok open and rejects a non-ok open (so the retry loop owns it)", async () => {
    renderHook(() => useOrderEvents(jest.fn()))
    await flushMicrotasks()

    const { onopen, onerror } = mockFesCalls[0].options
    await expect(onopen!({ ok: true })).resolves.toBeUndefined()
    await expect(onopen!({ ok: false, status: 401 })).rejects.toThrow("HTTP 401")
    // onerror must rethrow: swallowing it would keep fetchEventSource's internal
    // retry running with a stale token instead of handing back to our loop.
    expect(() => onerror!(new Error("boom"))).toThrow("boom")
  })

  it("reconnects with a FRESH token after the server closes the stream", async () => {
    renderHook(() => useOrderEvents(jest.fn()))
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(1)

    // Server recycles the emitter (5-minute timeout): graceful close.
    ;(getSession as jest.Mock).mockResolvedValue({ accessToken: "token-2" })
    mockFesCalls[0].resolve()
    await flushMicrotasks()

    // attempt=0 delay with neutral jitter = SSE_INITIAL_RETRY_MS
    await jest.advanceTimersByTimeAsync(SSE_INITIAL_RETRY_MS)
    await flushMicrotasks()

    expect(mockFesCalls).toHaveLength(2)
    expect(mockFesCalls[1].options.headers).toEqual({
      Authorization: "Bearer token-2",
    })
  })

  it("retries after an error with exponential backoff instead of giving up", async () => {
    renderHook(() => useOrderEvents(jest.fn()))
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(1)

    // First failure -> retry after ~1s
    mockFesCalls[0].reject(new Error("proxy cut the connection"))
    await flushMicrotasks()
    await jest.advanceTimersByTimeAsync(SSE_INITIAL_RETRY_MS)
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(2)

    // Second failure -> delay doubles to ~2s; almost-there is NOT enough...
    mockFesCalls[1].reject(new Error("still down"))
    await flushMicrotasks()
    await jest.advanceTimersByTimeAsync(2 * SSE_INITIAL_RETRY_MS - 1)
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(2)

    // ...but the full doubled delay is.
    await jest.advanceTimersByTimeAsync(1)
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(3)
  })

  it("resets the backoff after a successful open", async () => {
    renderHook(() => useOrderEvents(jest.fn()))
    await flushMicrotasks()

    // Fail twice so the internal attempt counter climbs to 2.
    mockFesCalls[0].reject(new Error("down"))
    await flushMicrotasks()
    await jest.advanceTimersByTimeAsync(SSE_INITIAL_RETRY_MS)
    await flushMicrotasks()
    mockFesCalls[1].reject(new Error("down"))
    await flushMicrotasks()
    await jest.advanceTimersByTimeAsync(2 * SSE_INITIAL_RETRY_MS)
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(3)

    // Third attempt connects successfully, then closes gracefully.
    await mockFesCalls[2].options.onopen!({ ok: true })
    mockFesCalls[2].resolve()
    await flushMicrotasks()

    // Backoff is back to the initial delay, not 4s.
    await jest.advanceTimersByTimeAsync(SSE_INITIAL_RETRY_MS)
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(4)
  })

  it("waits and retries when there is no session yet, instead of never connecting", async () => {
    ;(getSession as jest.Mock).mockResolvedValue(null)
    renderHook(() => useOrderEvents(jest.fn()))
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(0)

    // Session appears (e.g. silent refresh finished) -> next attempt connects.
    ;(getSession as jest.Mock).mockResolvedValue({ accessToken: "late-token" })
    await jest.advanceTimersByTimeAsync(SSE_INITIAL_RETRY_MS)
    await flushMicrotasks()

    expect(mockFesCalls).toHaveLength(1)
    expect(mockFesCalls[0].options.headers).toEqual({
      Authorization: "Bearer late-token",
    })
  })

  it("stops reconnecting after unmount", async () => {
    const { unmount } = renderHook(() => useOrderEvents(jest.fn()))
    await flushMicrotasks()
    expect(mockFesCalls).toHaveLength(1)

    unmount()
    expect(mockFesCalls[0].options.signal.aborted).toBe(true)

    // Real fetchEventSource resolves when its signal aborts; simulate that.
    mockFesCalls[0].resolve()
    await flushMicrotasks()
    await jest.advanceTimersByTimeAsync(10 * SSE_MAX_RETRY_MS)
    await flushMicrotasks()

    expect(mockFesCalls).toHaveLength(1)
  })
})

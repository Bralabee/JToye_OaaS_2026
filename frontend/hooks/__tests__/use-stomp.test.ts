/**
 * Unit tests for the STOMP hook covering the flagship Milestone 2 feature:
 *  - beforeConnect fetches a fresh session and writes ws brokerURL
 *  - getSession() failure does NOT crash the hook (it logs and falls back)
 *  - reconnect attempts happen on websocket close after a successful connect
 */

import { renderHook, act } from "@testing-library/react"
import { getSession } from "next-auth/react"

// Capture the Client config passed into new Client(...)
type ClientConfig = {
  reconnectDelay?: number
  beforeConnect?: () => Promise<void> | void
  onConnect?: () => void
  onStompError?: (frame: { headers: Record<string, string> }) => void
  onWebSocketClose?: () => void
}

const mockActivate = jest.fn()
const mockDeactivate = jest.fn()
const mockSubscribe = jest.fn()

let lastConfig: ClientConfig | null = null
let lastClient: Record<string, unknown> | null = null

jest.mock("@stomp/stompjs", () => {
  return {
    Client: jest.fn().mockImplementation((cfg: ClientConfig & { brokerURL?: string }) => {
      lastConfig = cfg
      const client = {
        brokerURL: cfg.brokerURL || "",
        connectHeaders: {} as Record<string, string>,
        activate: mockActivate,
        deactivate: mockDeactivate,
        subscribe: mockSubscribe,
      }
      lastClient = client
      return client
    }),
  }
})

// After the mock is declared we can import the hook
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { useStomp } = require("../use-stomp")

beforeEach(() => {
  jest.clearAllMocks()
  lastConfig = null
  lastClient = null
  ;(getSession as jest.Mock).mockReset?.()
  ;(getSession as jest.Mock).mockResolvedValue({ accessToken: "mock-token" })
  process.env.NEXT_PUBLIC_API_URL = "http://core.local:9090"
})

describe("useStomp", () => {
  it("activates a client with a reconnect delay when given a topic", () => {
    renderHook(() => useStomp("/topic/kitchen/t/s", jest.fn(), jest.fn()))
    expect(mockActivate).toHaveBeenCalledTimes(1)
    expect(lastConfig?.reconnectDelay).toBe(5000)
  })

  it("does nothing when topic is null", () => {
    renderHook(() => useStomp(null, jest.fn(), jest.fn()))
    expect(mockActivate).not.toHaveBeenCalled()
  })

  it("beforeConnect fetches session and sets connectHeaders with token", async () => {
    renderHook(() => useStomp("/topic/kitchen/t/s", jest.fn(), jest.fn()))
    expect(lastConfig?.beforeConnect).toBeDefined()
    await lastConfig!.beforeConnect!()
    expect(getSession).toHaveBeenCalled()
    // Token should be in STOMP CONNECT headers, not the URL
    expect(lastClient?.brokerURL).toBe("ws://core.local:9090/ws")
    expect((lastClient as Record<string, unknown>)?.connectHeaders).toEqual({
      Authorization: "Bearer mock-token",
    })
  })

  it("beforeConnect does NOT crash when getSession() throws", async () => {
    const warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {})
    ;(getSession as jest.Mock).mockRejectedValueOnce(new Error("boom"))
    renderHook(() => useStomp("/topic/kitchen/t/s", jest.fn(), jest.fn()))
    await expect(lastConfig!.beforeConnect!()).resolves.toBeUndefined()
    // Falls back to an empty token so the broker can reject cleanly
    expect(lastClient?.brokerURL).toBe("ws://core.local:9090/ws")
    expect((lastClient as Record<string, unknown>)?.connectHeaders).toEqual({
      Authorization: "Bearer ",
    })
    expect(warnSpy).toHaveBeenCalled()
    warnSpy.mockRestore()
  })

  it("sets reconnecting=true after a disconnect that followed a successful connect", () => {
    const { result, rerender } = renderHook(() =>
      useStomp("/topic/kitchen/t/s", jest.fn(), jest.fn())
    )
    // Initial state
    expect(result.current.connected).toBe(false)
    // Simulate a successful connection then a websocket close
    act(() => {
      lastConfig!.onConnect!()
    })
    rerender()
    expect(mockSubscribe).toHaveBeenCalledWith(
      "/topic/kitchen/t/s",
      expect.any(Function)
    )
    act(() => {
      lastConfig!.onWebSocketClose!()
    })
    rerender()
    expect(result.current.connected).toBe(false)
    expect(result.current.reconnecting).toBe(true)
  })

  it("deactivates on unmount", () => {
    const { unmount } = renderHook(() =>
      useStomp("/topic/kitchen/t/s", jest.fn(), jest.fn())
    )
    unmount()
    expect(mockDeactivate).toHaveBeenCalled()
  })
})

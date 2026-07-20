/**
 * useShopContext (23-07) — the hook the four shop-scoped dashboard screens use to
 * read the persisted switcher selection (23-05) and re-render when it changes.
 *
 * The lib is mocked so these cases prove the HOOK's contract (the "all" → null
 * mapping, the live re-read on broadcast, and unsubscribe on unmount) rather
 * than re-testing lib/shop-context.ts, which 23-05 already covers.
 */
import { renderHook, act } from "@testing-library/react"
import { useShopContext } from "../use-shop-context"
import { getShopContext, subscribeShopContext } from "@/lib/shop-context"

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))

const mockedGetShopContext = getShopContext as jest.MockedFunction<typeof getShopContext>
const mockedSubscribe = subscribeShopContext as jest.MockedFunction<typeof subscribeShopContext>

const SHOP_A = "11111111-1111-1111-1111-111111111111"

describe("useShopContext", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedGetShopContext.mockReturnValue("all")
    mockedSubscribe.mockImplementation(() => () => {})
  })

  it('maps the "all" sentinel to a null contextShopId (All-shops context)', () => {
    mockedGetShopContext.mockReturnValue("all")

    const { result } = renderHook(() => useShopContext())

    expect(result.current.contextShopId).toBeNull()
    expect(result.current.isAllShops).toBe(true)
  })

  it("maps a persisted shopId to that contextShopId (single-shop context)", () => {
    mockedGetShopContext.mockReturnValue(SHOP_A)

    const { result } = renderHook(() => useShopContext())

    expect(result.current.contextShopId).toBe(SHOP_A)
    expect(result.current.isAllShops).toBe(false)
  })

  it("live-reacts to a switcher change broadcast (no reload needed)", () => {
    mockedGetShopContext.mockReturnValue("all")
    let broadcast: (() => void) | undefined
    mockedSubscribe.mockImplementation((cb: () => void) => {
      broadcast = cb
      return () => {}
    })

    const { result } = renderHook(() => useShopContext())
    expect(result.current.contextShopId).toBeNull()

    // The switcher persisted a new selection and dispatched 'shopcontext:change'.
    mockedGetShopContext.mockReturnValue(SHOP_A)
    act(() => {
      broadcast?.()
    })

    expect(result.current.contextShopId).toBe(SHOP_A)
    expect(result.current.isAllShops).toBe(false)
  })

  it("unsubscribes on unmount (no leaked listener)", () => {
    const unsubscribe = jest.fn()
    mockedSubscribe.mockImplementation(() => unsubscribe)

    const { unmount } = renderHook(() => useShopContext())
    expect(mockedSubscribe).toHaveBeenCalledTimes(1)

    unmount()

    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })
})

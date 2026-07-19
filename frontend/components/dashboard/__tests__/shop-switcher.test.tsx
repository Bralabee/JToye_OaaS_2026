/**
 * Unit spec for the VSA-03 shop-context switcher and its localStorage-backed
 * context helper. Covers the seven behaviours the switcher must guarantee:
 *   1. persistence  — selecting a shop writes localStorage 'shopContext'
 *   2. broadcast    — setShopContext dispatches 'shopcontext:change' and
 *                     subscribeShopContext lets 23-07's screens react live
 *   3. GA default   — a GROUP_ADMIN with no saved value lands on "All shops"
 *   4. apply-to-all — the group-wide affordance renders ONLY for a GROUP_ADMIN
 *                     AND only in the "All shops" context; never for a non-GA
 *   5. single pin   — a non-GA with exactly one grant shows a pinned label
 *   6. stale (D-13) — a revoked saved shop id degrades to an access-required
 *                     notice + resets the selection instead of crashing
 *
 * `@/lib/shops-api` is mocked (no network/session); the REAL `@/lib/shop-context`
 * runs against jsdom localStorage so the persistence + broadcast contract is
 * proven end-to-end.
 */

import { render, screen, fireEvent, act } from "@testing-library/react"
import { ShopSwitcher } from "../shop-switcher"
import {
  getShopContext,
  setShopContext,
  subscribeShopContext,
} from "@/lib/shop-context"
import { fetchMyShops } from "@/lib/shops-api"
import type { Shop } from "@/types/api"

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
}))

const mockFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>

function makeShop(id: string, name: string): Shop {
  return {
    id,
    tenantId: "tenant-1",
    name,
    address: "1 Main St",
    slug: id,
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
  }
}

const SHOP_A = makeShop("shop-a", "Brixton Grill")
const SHOP_B = makeShop("shop-b", "Peckham Jollof")

beforeEach(() => {
  window.localStorage.clear()
  mockFetchMyShops.mockReset()
})

describe("shop-context helper (lib/shop-context)", () => {
  it("persists the selection under localStorage 'shopContext' and reads it back", () => {
    setShopContext("shop-a")
    expect(window.localStorage.getItem("shopContext")).toBe("shop-a")
    expect(getShopContext()).toBe("shop-a")
  })

  it("defaults to the 'all' context when nothing is persisted", () => {
    expect(getShopContext()).toBe("all")
  })

  it("broadcasts a same-tab 'shopcontext:change' event that subscribeShopContext observes", () => {
    const cb = jest.fn()
    const unsubscribe = subscribeShopContext(cb)
    act(() => setShopContext("shop-b"))
    expect(cb).toHaveBeenCalledTimes(1)
    unsubscribe()
    act(() => setShopContext("shop-a"))
    // No further calls once unsubscribed.
    expect(cb).toHaveBeenCalledTimes(1)
  })
})

describe("ShopSwitcher", () => {
  it("defaults a GROUP_ADMIN to the 'All shops' context (D-06)", async () => {
    mockFetchMyShops.mockResolvedValue({ shops: [SHOP_A, SHOP_B], isGroupAdmin: true })
    render(<ShopSwitcher />)

    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    expect(screen.getByRole("option", { name: "All shops" })).toBeInTheDocument()
    expect(select.value).toBe("all")
  })

  it("persists a new selection to localStorage when a shop is chosen", async () => {
    mockFetchMyShops.mockResolvedValue({ shops: [SHOP_A, SHOP_B], isGroupAdmin: true })
    render(<ShopSwitcher />)

    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    fireEvent.change(select, { target: { value: SHOP_B.id } })

    expect(select.value).toBe(SHOP_B.id)
    expect(getShopContext()).toBe(SHOP_B.id)
  })

  it("shows the 'apply to all shops' action ONLY for a GROUP_ADMIN in the 'All shops' context (D-08)", async () => {
    mockFetchMyShops.mockResolvedValue({ shops: [SHOP_A, SHOP_B], isGroupAdmin: true })
    render(<ShopSwitcher />)

    // GA + All-shops → affordance visible.
    expect(await screen.findByTestId("apply-to-all")).toHaveTextContent(/apply to all/i)

    // GA switches to a single shop → affordance disappears (not the "all" context).
    fireEvent.change(await screen.findByRole("combobox"), { target: { value: SHOP_A.id } })
    expect(screen.queryByTestId("apply-to-all")).not.toBeInTheDocument()
  })

  it("never offers 'apply to all shops' to a non-GROUP_ADMIN", async () => {
    mockFetchMyShops.mockResolvedValue({ shops: [SHOP_A, SHOP_B], isGroupAdmin: false })
    render(<ShopSwitcher />)

    // Wait for load to settle (the granted-shop dropdown renders).
    await screen.findByRole("combobox")
    expect(screen.queryByTestId("apply-to-all")).not.toBeInTheDocument()
    // A non-GA has no "All shops" first-class entry.
    expect(screen.queryByRole("option", { name: "All shops" })).not.toBeInTheDocument()
  })

  it("pins the sole grant of a single-shop non-GROUP_ADMIN (no dropdown)", async () => {
    mockFetchMyShops.mockResolvedValue({ shops: [SHOP_A], isGroupAdmin: false })
    render(<ShopSwitcher />)

    const switcher = await screen.findByTestId("shop-switcher")
    expect(switcher).toHaveTextContent(SHOP_A.name)
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument()
  })

  it("degrades a stale/revoked saved selection to an access-required state and resets it (D-13)", async () => {
    window.localStorage.setItem("shopContext", "revoked-shop-id")
    mockFetchMyShops.mockResolvedValue({ shops: [SHOP_A, SHOP_B], isGroupAdmin: true })
    render(<ShopSwitcher />)

    // Access-required notice surfaces instead of crashing.
    expect(await screen.findByTestId("shop-switcher-stale")).toBeInTheDocument()
    // Selection reset to the GA default ("All shops").
    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    expect(select.value).toBe("all")
    expect(getShopContext()).toBe("all")
  })
})

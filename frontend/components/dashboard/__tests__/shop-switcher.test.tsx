/**
 * Unit spec for the VSA-03 shop-context switcher and its localStorage-backed
 * context helper.
 *
 * CR-08 falsifiability note: these cases mock the HTTP seam (`@/lib/api-client`)
 * and `next-auth/react`, NOT `fetchMyShops`. That is deliberate — the previous
 * spec mocked `fetchMyShops` directly and therefore COULD NOT observe that the
 * old `fetchMyShops` derived GROUP_ADMIN from a browser-side JWT parse instead of
 * the server. Driving the real `fetchMyShops` over a mocked `apiClient` is what
 * lets these cases fail against the pre-fix code (see 23-13-SUMMARY RED evidence).
 *
 * The REAL `@/lib/shop-context` runs against jsdom localStorage so the
 * persistence + broadcast contract is proven end-to-end.
 */

import { render, screen, fireEvent, act } from "@testing-library/react"
import type { ReactElement } from "react"
import { ShopSwitcher } from "../shop-switcher"
import { ShopSwitcherProvider } from "../shop-switcher-provider"
import {
  getShopContext,
  setShopContext,
  subscribeShopContext,
} from "@/lib/shop-context"
import apiClient from "@/lib/api-client"
import type { Shop } from "@/types/api"

/** Every switcher must sit under the shared provider (its single data source). */
function renderSwitcher(ui: ReactElement) {
  return render(ui, { wrapper: ShopSwitcherProvider })
}

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

// Present so the PRE-FIX `fetchMyShops` (which parsed the session JWT) resolves
// to a non-realm-admin under the falsifiability stash. The POST-FIX code never
// calls this — GROUP_ADMIN comes from GET /api/v1/staff/me.
jest.mock("next-auth/react", () => ({
  getSession: jest.fn().mockResolvedValue(null),
}))

const USER = "user-0000-0000-0000-000000000000"

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

/**
 * Route the two GETs `fetchMyShops` issues: the read-scoped shop list and the
 * server-authoritative access answer (GET /api/v1/staff/me → MyAccessDto).
 */
function mockAccess(opts: {
  shops: Shop[]
  groupAdmin: boolean
  grantedShopIds?: string[] | null
  userId?: string
}) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url === "/api/v1/staff/me") {
      return Promise.resolve({
        data: {
          userId: opts.userId ?? USER,
          groupAdmin: opts.groupAdmin,
          grantedShopIds:
            opts.grantedShopIds ??
            (opts.groupAdmin ? null : opts.shops.map((s) => s.id)),
        },
      })
    }
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: { content: opts.shops } })
    }
    return Promise.reject(new Error(`unexpected GET ${url}`))
  }) as never)
}

beforeEach(() => {
  window.localStorage.clear()
  mockedApiClient.get.mockReset()
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
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    renderSwitcher(<ShopSwitcher />)

    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    expect(screen.getByRole("option", { name: "All shops" })).toBeInTheDocument()
    expect(select.value).toBe("all")
  })

  // CR-08: the day-one implicit GROUP_ADMIN is NOT a Keycloak realm admin, so the
  // old JWT-parse reported them as non-GA and pinned them to their first shop with
  // no way back. The server (GET /api/v1/staff/me) answers groupAdmin:true; the
  // switcher must land them on "All shops" and offer the option to re-select it.
  it("lands a server-side GROUP_ADMIN who is NOT a realm admin on 'All shops' and never pins them to the first shop (CR-08)", async () => {
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true, grantedShopIds: null })
    renderSwitcher(<ShopSwitcher />)

    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    expect(screen.getByRole("option", { name: "All shops" })).toBeInTheDocument()
    expect(select.value).toBe("all")
    expect(select.value).not.toBe(SHOP_A.id)
  })

  // The silent-narrowing regression: a clean first load must not WRITE a shop
  // pin to localStorage. Only an actively-chosen shop or a stale-selection
  // correction (D-13) may persist.
  it("does not silently persist a shop pin on a clean first load (no setShopContext write)", async () => {
    const setItemSpy = jest.spyOn(Storage.prototype, "setItem")
    mockAccess({
      shops: [SHOP_A, SHOP_B],
      groupAdmin: false,
      grantedShopIds: [SHOP_A.id, SHOP_B.id],
    })
    renderSwitcher(<ShopSwitcher />)

    await screen.findByRole("combobox")
    expect(
      setItemSpy.mock.calls.some(([key]) => key === "shopContext")
    ).toBe(false)
    expect(window.localStorage.getItem("shopContext")).toBeNull()
    setItemSpy.mockRestore()
  })

  it("persists a new selection to localStorage when a shop is chosen", async () => {
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    renderSwitcher(<ShopSwitcher />)

    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    fireEvent.change(select, { target: { value: SHOP_B.id } })

    expect(select.value).toBe(SHOP_B.id)
    expect(getShopContext()).toBe(SHOP_B.id)
  })

  it("shows the 'apply to all shops' action ONLY for a GROUP_ADMIN in the 'All shops' context (D-08)", async () => {
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    renderSwitcher(<ShopSwitcher />)

    // GA + All-shops → affordance visible.
    expect(await screen.findByTestId("apply-to-all")).toHaveTextContent(/apply to all/i)

    // GA switches to a single shop → affordance disappears (not the "all" context).
    fireEvent.change(await screen.findByRole("combobox"), { target: { value: SHOP_A.id } })
    expect(screen.queryByTestId("apply-to-all")).not.toBeInTheDocument()
  })

  it("never offers 'apply to all shops' to a non-GROUP_ADMIN", async () => {
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: false })
    renderSwitcher(<ShopSwitcher />)

    // Wait for load to settle (the granted-shop dropdown renders).
    await screen.findByRole("combobox")
    expect(screen.queryByTestId("apply-to-all")).not.toBeInTheDocument()
    // A non-GA has no "All shops" first-class entry.
    expect(screen.queryByRole("option", { name: "All shops" })).not.toBeInTheDocument()
  })

  it("pins the sole grant of a single-shop non-GROUP_ADMIN (no dropdown)", async () => {
    mockAccess({ shops: [SHOP_A], groupAdmin: false })
    renderSwitcher(<ShopSwitcher />)

    // Wait for the loaded pinned label (the loading skeleton shares the testid).
    expect(await screen.findByText(SHOP_A.name)).toBeInTheDocument()
    expect(screen.getByTestId("shop-switcher")).toHaveTextContent(SHOP_A.name)
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument()
  })

  it("degrades a stale/revoked saved selection to an access-required state and resets it (D-13)", async () => {
    window.localStorage.setItem("shopContext", "revoked-shop-id")
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    renderSwitcher(<ShopSwitcher />)

    // Access-required notice surfaces instead of crashing.
    expect(await screen.findByTestId("shop-switcher-stale")).toBeInTheDocument()
    // Selection reset to the GA default ("All shops").
    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    expect(select.value).toBe("all")
    expect(getShopContext()).toBe("all")
  })
})

// WR-06: two switcher instances (sidebar + mobile top bar) are ONE control —
// one shared data source, one selection state, one fetch.
describe("ShopSwitcher — two instances are one control (WR-06)", () => {
  it("keeps both switchers in sync — changing one updates the other without a remount", async () => {
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    render(
      <ShopSwitcherProvider>
        <ShopSwitcher variant="sidebar" />
        <ShopSwitcher variant="topbar" />
      </ShopSwitcherProvider>
    )

    const selects = (await screen.findAllByRole("combobox")) as HTMLSelectElement[]
    expect(selects).toHaveLength(2)
    expect(selects[0].value).toBe("all")
    expect(selects[1].value).toBe("all")

    // Change the selection in the FIRST switcher…
    fireEvent.change(selects[0], { target: { value: SHOP_B.id } })

    // …and the SECOND reflects it live (shared context, no remount).
    expect(selects[0].value).toBe(SHOP_B.id)
    expect(selects[1].value).toBe(SHOP_B.id)
    expect(getShopContext()).toBe(SHOP_B.id)
  })

  it("issues ONE GET /api/v1/shops and ONE GET /api/v1/staff/me for both mounted switchers", async () => {
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    render(
      <ShopSwitcherProvider>
        <ShopSwitcher variant="sidebar" />
        <ShopSwitcher variant="topbar" />
      </ShopSwitcherProvider>
    )

    await screen.findAllByRole("combobox")

    const shopCalls = mockedApiClient.get.mock.calls.filter(([u]) =>
      String(u).startsWith("/api/v1/shops")
    )
    const meCalls = mockedApiClient.get.mock.calls.filter(
      ([u]) => u === "/api/v1/staff/me"
    )
    expect(shopCalls).toHaveLength(1)
    expect(meCalls).toHaveLength(1)
  })
})

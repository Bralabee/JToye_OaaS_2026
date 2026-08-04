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
import { fetchMyShops } from "@/lib/shops-api"
import apiClient from "@/lib/api-client"
import type { PageResponse, Shop } from "@/types/api"

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

/** A query-string value from a recorded request URL. */
function param(url: string, key: string): string | null {
  return new URLSearchParams(url.split("?")[1] ?? "").get(key)
}

/** Every `GET /api/v1/shops…` URL the code under test actually requested. */
function shopCallUrls(): string[] {
  return mockedApiClient.get.mock.calls
    .map(([u]) => String(u))
    .filter((u) => u.startsWith("/api/v1/shops"))
}

/**
 * A faithful Spring `PageImpl` page over `all` — the SHAPE the real API returns.
 *
 * `new PageImpl<>(content, pageable, total)` RECOMPUTES `total` as
 * `offset + content.size()` whenever `offset + pageSize > total`, which is why a
 * hand-written fixture whose total does not genuinely exceed the page size makes
 * CORRECT pagination look broken. This reproduces that rule instead of inventing
 * metadata, and the fixture below is 250 shops — more than one page at any page
 * size the client would sensibly request.
 */
function springPage<T>(all: T[], page: number, size: number): PageResponse<T> {
  const offset = page * size
  const content = all.slice(offset, offset + size)
  const total =
    content.length > 0 && offset + size > all.length
      ? offset + content.length
      : all.length
  const totalPages = size > 0 ? Math.ceil(total / size) : 1
  return {
    content,
    totalElements: total,
    totalPages,
    size,
    number: page,
    first: page === 0,
    last: page + 1 >= totalPages,
  }
}

/**
 * Like `mockAccess`, but the fake shops endpoint HONOURS `?page=` and `?size=`.
 *
 * That is the whole point of the #282 cases: a mock that ignored the parameters
 * would hand back every shop on page 0, the pre-fix single-request code would look
 * complete, and the case would pass against the bug it exists to catch.
 */
function mockPagedAccess(opts: {
  shops: Shop[]
  groupAdmin: boolean
  userId?: string
}) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url === "/api/v1/staff/me") {
      return Promise.resolve({
        data: {
          userId: opts.userId ?? USER,
          groupAdmin: opts.groupAdmin,
          grantedShopIds: opts.groupAdmin ? null : opts.shops.map((s) => s.id),
        },
      })
    }
    if (url.startsWith("/api/v1/shops")) {
      const page = Number(param(url, "page") ?? 0)
      const size = Number(param(url, "size") ?? 20)
      return Promise.resolve({ data: springPage(opts.shops, page, size) })
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

/**
 * Issue #282 — `fetchMyShops` requested ONE page of a hardcoded 200. A tenant with
 * more shops than fit in that page silently lost the tail: those shops could not be
 * selected in the switcher and did not appear in the staff screen's shop picker.
 */
describe("fetchMyShops — pages the whole shop list (#282)", () => {
  // 250 > any single page the client requests, so a correct implementation MUST
  // make more than one request to see the last shop.
  const MANY = Array.from({ length: 250 }, (_, i) =>
    makeShop(`shop-${String(i).padStart(3, "0")}`, `Shop ${i}`)
  )
  const TAIL = MANY[MANY.length - 1]

  afterEach(() => {
    delete process.env.NEXT_PUBLIC_SHOPS_PAGE_SIZE
  })

  it("returns every shop, including the tail past the first page", async () => {
    mockPagedAccess({ shops: MANY, groupAdmin: true })

    const { shops } = await fetchMyShops()

    expect(shops).toHaveLength(MANY.length)
    expect(shops.map((s) => s.id)).toContain(TAIL.id)
  })

  it("requests exactly the pages it needs — consecutive, one page size, none wasted", async () => {
    mockPagedAccess({ shops: MANY, groupAdmin: true })

    await fetchMyShops()

    const calls = shopCallUrls()
    const sizes = new Set(calls.map((u) => Number(param(u, "size"))))
    expect(sizes.size).toBe(1)
    const [size] = [...sizes]
    // Derived from the size the client actually asked for, so this stays true if
    // the configured page size changes — but still fails on a truncating fetch
    // (1 request) or a runaway loop (more requests than pages).
    expect(calls.map((u) => Number(param(u, "page")))).toEqual(
      Array.from({ length: Math.ceil(MANY.length / size) }, (_, i) => i)
    )
  })

  it("takes its page size from config (NEXT_PUBLIC_SHOPS_PAGE_SIZE), not a literal", async () => {
    process.env.NEXT_PUBLIC_SHOPS_PAGE_SIZE = "100"
    mockPagedAccess({ shops: MANY, groupAdmin: true })

    const { shops } = await fetchMyShops()

    expect(shopCallUrls().every((u) => param(u, "size") === "100")).toBe(true)
    expect(shopCallUrls()).toHaveLength(3)
    expect(shops).toHaveLength(MANY.length)
  })

  it("falls back to the default page size when the configured value is not a positive integer", async () => {
    process.env.NEXT_PUBLIC_SHOPS_PAGE_SIZE = "not-a-number"
    mockPagedAccess({ shops: MANY, groupAdmin: true })

    const { shops } = await fetchMyShops()

    const sizes = shopCallUrls().map((u) => Number(param(u, "size")))
    expect(sizes.every((s) => Number.isInteger(s) && s > 0)).toBe(true)
    expect(shops).toHaveLength(MANY.length)
  })

  it("offers the tail shops in the switcher itself, not just in the fetch result", async () => {
    mockPagedAccess({ shops: MANY, groupAdmin: false })
    renderSwitcher(<ShopSwitcher />)

    expect(
      await screen.findByRole("option", { name: TAIL.name })
    ).toBeInTheDocument()
  })
})

/**
 * Issue #288 — a non-GROUP_ADMIN whose grants were all revoked fell through to a
 * controlled `<select value="all">` with NO matching option: a blank, broken-looking
 * control that never explained why. The backend already denies every scoped request;
 * the screen just could not say so.
 */
describe("ShopSwitcher — zero-access non-GROUP_ADMIN (#288)", () => {
  it("renders an explanatory no-access notice instead of a blank select", async () => {
    mockAccess({ shops: [], groupAdmin: false, grantedShopIds: [] })
    renderSwitcher(<ShopSwitcher />)

    const notice = await screen.findByTestId("shop-switcher-no-access")
    expect(notice).toHaveTextContent(/no shop access/i)
    // …and says what to do about it.
    expect(notice).toHaveTextContent(/group admin/i)
    // The dead control is gone — an empty <select> IS the defect.
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument()
  })

  // The mobile top bar is a FIXED h-14 (56px) row and the switcher sits in a
  // max-w-[55%] (~206px) column, so at 375px this sentence wraps to ~4 lines and
  // would spill out of a bar that cannot grow — permanently, for this user. The chip
  // carries it visually there; the sentence stays in the accessibility tree.
  it("keeps the explanation laid out in the sidebar and visually-hidden in the mobile top bar", async () => {
    mockAccess({ shops: [], groupAdmin: false, grantedShopIds: [] })
    const { unmount } = renderSwitcher(<ShopSwitcher variant="topbar" />)

    expect(await screen.findByText(/ask a group admin/i)).toHaveClass("sr-only")
    unmount()

    renderSwitcher(<ShopSwitcher variant="sidebar" />)
    expect(await screen.findByText(/ask a group admin/i)).not.toHaveClass("sr-only")
  })

  // Over-reach guard: a GROUP_ADMIN with no shops yet is NOT locked out — they hold
  // tenant-wide access and need the "All shops" context to create the first shop.
  // (This case also passes against the pre-fix code; it constrains the fix, it does
  // not demonstrate it.)
  it("leaves a GROUP_ADMIN who has no shops yet on the 'All shops' control", async () => {
    mockAccess({ shops: [], groupAdmin: true, grantedShopIds: null })
    renderSwitcher(<ShopSwitcher />)

    const select = (await screen.findByRole("combobox")) as HTMLSelectElement
    expect(select.value).toBe("all")
    expect(screen.queryByTestId("shop-switcher-no-access")).not.toBeInTheDocument()
  })
})

/**
 * #495 / #490 — the OTHER two notes under the switcher, which #476 gated only for
 * the #288 sentence.
 *
 * The mobile top bar is a fixed `h-14` (56px) `items-center` flex row. A note laid
 * out there does not merely spill past the border — it re-centres the column and
 * LIFTS the `<select>` out of the bar. Measured in the running app at 375px on a
 * Pixel 7 profile, relative to the bar's top edge:
 *
 *   as shipped, "Apply to all shops"  badge bottom 59 (bar is 56) — select top −5
 *   as shipped, D-13 stale sentence   note  bottom 95 (bar is 56) — select
 *                                     top −40 / bottom −2, i.e. entirely above the
 *                                     viewport and untouchable
 *
 * jsdom has no layout, so these cases can only assert the MECHANISM (`sr-only` in
 * the bar, laid out in the sidebar) — the geometry itself is asserted in a real
 * browser by e2e/dashboard-mobile.spec.ts's 375px case.
 */
describe("ShopSwitcher — notes under the control fit the fixed mobile bar (#495, #490)", () => {
  it("keeps the group-wide 'Apply to all shops' badge out of the bar's flow (#495)", async () => {
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    const { unmount } = renderSwitcher(<ShopSwitcher variant="topbar" />)

    const inBar = await screen.findByTestId("apply-to-all")
    // Still announced — the affordance is hidden visually, not removed.
    expect(inBar).toHaveTextContent(/apply to all/i)
    expect(inBar).toHaveClass("sr-only")
    // …and carries none of the flow-layout classes that caused the 3px spill.
    expect(inBar).not.toHaveClass("mt-1.5")
    expect(inBar).not.toHaveClass("inline-flex")
    unmount()

    // The sidebar has vertical room, so nothing is taken away there.
    renderSwitcher(<ShopSwitcher variant="sidebar" />)
    const inSidebar = await screen.findByTestId("apply-to-all")
    expect(inSidebar).not.toHaveClass("sr-only")
    expect(inSidebar).toHaveClass("mt-1.5")
  })

  it("keeps the D-13 stale-selection notice out of the bar's flow, with a visible amber glyph in its place (#490)", async () => {
    window.localStorage.setItem("shopContext", "revoked-shop-id")
    mockAccess({ shops: [SHOP_A, SHOP_B], groupAdmin: true })
    const { unmount } = renderSwitcher(<ShopSwitcher variant="topbar" />)

    const inBar = await screen.findByTestId("shop-switcher-stale")
    // role="alert" + sr-only still announces; it is not silently dropped.
    expect(inBar).toHaveAttribute("role", "alert")
    expect(inBar).toHaveTextContent(/no longer available/i)
    expect(inBar).toHaveClass("sr-only")
    expect(inBar).not.toHaveClass("mt-1.5")
    // A SIGHTED mobile user is not left with a silently-reset selection: the
    // switcher's leading glyph becomes the amber alert mark (absolutely
    // positioned, so it costs the 56px bar no height).
    expect(screen.getByTestId("shop-switcher-stale-glyph")).toBeInTheDocument()
    unmount()

    // The sidebar keeps the sentence, so it needs no glyph substitute.
    window.localStorage.setItem("shopContext", "revoked-shop-id")
    renderSwitcher(<ShopSwitcher variant="sidebar" />)
    const inSidebar = await screen.findByTestId("shop-switcher-stale")
    expect(inSidebar).not.toHaveClass("sr-only")
    expect(inSidebar).toHaveClass("mt-1.5")
    expect(screen.queryByTestId("shop-switcher-stale-glyph")).not.toBeInTheDocument()
  })
})

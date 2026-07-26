/**
 * VSA-03 (23-07) — Marketing & Kitchen operate on the selected shop context.
 *
 * Marketing narrows client-side (its list endpoints take no shop param; the rows
 * are already grant-scoped by 23-03). Kitchen already queried per shop — the fix
 * there is single-source-of-truth: its board defaults from the GLOBAL switcher
 * context instead of a blind first-published shop.
 */
import { configure, render, screen, waitFor, fireEvent } from "@testing-library/react"
import MarketingPage from "../marketing/page"
import KitchenPage from "../kitchen/page"
import apiClient from "@/lib/api-client"
import { getShopContext } from "@/lib/shop-context"

// Under full-suite CPU contention these async renders can exceed waitFor's 1s
// default, flaking assertions that pass in isolation (the content DOES render,
// just slowly). Give the whole file generous async headroom so CI parallelism
// can't trip it. Module isolation keeps this scoped to this file.
configure({ asyncUtilTimeout: 5000 })

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))
const mockedGetShopContext = getShopContext as jest.MockedFunction<typeof getShopContext>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// The KDS websocket is irrelevant to shop scoping and would open a real
// SockJS/STOMP connection in jsdom.
jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-2222-2222-2222-222222222222"

const shops = [
  { id: SHOP_A, tenantId: "t-1", name: "Peckham Kitchen", published: true },
  { id: SHOP_B, tenantId: "t-1", name: "Brixton Bakery", published: true },
]

const PROMOTIONS = [
  {
    id: "promo-a",
    shopId: SHOP_A,
    label: "Peckham Lunch Deal",
    discountType: "PERCENTAGE",
    discountPercent: 10,
    discountAmountPennies: null,
    category: null,
    validFrom: "2026-07-01T00:00:00Z",
    validUntil: "2026-12-31T00:00:00Z",
    active: true,
    createdAt: "2026-07-01T00:00:00Z",
  },
  {
    id: "promo-b",
    shopId: SHOP_B,
    label: "Brixton Bakery Bundle",
    discountType: "PERCENTAGE",
    discountPercent: 15,
    discountAmountPennies: null,
    category: null,
    validFrom: "2026-07-01T00:00:00Z",
    validUntil: "2026-12-31T00:00:00Z",
    active: true,
    createdAt: "2026-07-01T00:00:00Z",
  },
]

const ANNOUNCEMENTS = [
  {
    id: "ann-a",
    shopId: SHOP_A,
    title: "Peckham reopening",
    body: null,
    validFrom: null,
    validUntil: null,
    active: true,
    createdAt: "2026-07-01T00:00:00Z",
  },
  {
    id: "ann-b",
    shopId: SHOP_B,
    title: "Brixton late hours",
    body: null,
    validFrom: null,
    validUntil: null,
    active: true,
    createdAt: "2026-07-01T00:00:00Z",
  },
]

/** Reads ?shopId= off a request URL, or null for the All-shops context. */
const shopIdOf = (url: string) =>
  new URLSearchParams(url.split("?")[1] ?? "").get("shopId")

const defaultMock = (url: string) => {
  // WR-04 (#280): both marketing endpoints narrow SERVER-side now, so the fake server
  // honours ?shopId= for each. A mock that ignored it would return every shop's rows,
  // making a correct implementation look broken.
  if (url.startsWith("/api/v1/promotions")) {
    const shopId = shopIdOf(url)
    const rows = shopId ? PROMOTIONS.filter((p) => p.shopId === shopId) : PROMOTIONS
    return Promise.resolve({
      data: { content: rows, totalPages: 1, totalElements: rows.length },
    })
  }
  if (url.startsWith("/api/v1/announcements")) {
    const shopId = shopIdOf(url)
    const rows = shopId ? ANNOUNCEMENTS.filter((a) => a.shopId === shopId) : ANNOUNCEMENTS
    return Promise.resolve({
      data: { content: rows, totalPages: 1, totalElements: rows.length },
    })
  }
  if (url.startsWith("/api/v1/shops")) {
    return Promise.resolve({ data: { content: shops, totalPages: 1, totalElements: shops.length } })
  }
  if (url.startsWith("/api/v1/orders")) {
    return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
  }
  return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
}

/** Kitchen board queries: /api/v1/orders?shopId=<board shop>&size=100... */
const kitchenBoardCalls = () =>
  mockedApiClient.get.mock.calls
    .map(([url]) => url as string)
    .filter((url) => url.startsWith("/api/v1/orders?shopId="))

/** All URLs requested against a given marketing collection endpoint. */
const callsTo = (prefix: string) =>
  mockedApiClient.get.mock.calls
    .map(([url]) => url as string)
    .filter((url) => url.startsWith(prefix))

describe("VSA-03 — shop-context scoping on Marketing & Kitchen", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(defaultMock as jest.Mock)
    mockedGetShopContext.mockReturnValue("all")
  })

  describe("Marketing — a specific shop is selected", () => {
    beforeEach(() => {
      mockedGetShopContext.mockReturnValue(SHOP_A)
    })

    it("narrows the promotions list server-side via ?shopId=", async () => {
      // WR-04 (#280). This previously asserted only the rendered rows, and its own
      // comment described the client-side mechanism it was locking in ("the shop filter
      // drops other shops' promotions in a follow-up render tick") — the arrangement
      // that produced wrong counts and unreachable rows. The narrow now arrives with the
      // response, so there is no follow-up tick and no need to wait one out.
      render(<MarketingPage />)

      await waitFor(() => expect(callsTo("/api/v1/promotions?").length).toBeGreaterThan(0))
      expect(callsTo("/api/v1/promotions?").some((u) => u.includes(`shopId=${SHOP_A}`))).toBe(true)

      await waitFor(() =>
        expect(screen.getByText("Peckham Lunch Deal")).toBeInTheDocument()
      )
      expect(screen.queryByText("Brixton Bakery Bundle")).not.toBeInTheDocument()
    })

    it("narrows the announcements list server-side via ?shopId=", async () => {
      // "Marketing" is two independent domains, so the defect had two endpoints and
      // needs two assertions. The announcements list is fetched lazily on tab switch
      // (the effect is gated on activeTab), so the tab has to be clicked to reach it.
      render(<MarketingPage />)

      // The whole page renders a spinner until the promotions load resolves; the tab
      // buttons do not exist before that.
      await waitFor(() =>
        expect(screen.getByText("Peckham Lunch Deal")).toBeInTheDocument()
      )

      fireEvent.click(screen.getByRole("button", { name: /announcements/i }))

      await waitFor(() => expect(callsTo("/api/v1/announcements?").length).toBeGreaterThan(0))
      expect(callsTo("/api/v1/announcements?").some((u) => u.includes(`shopId=${SHOP_A}`))).toBe(true)
    })

    it("defaults AND constrains the create-promotion shop to the selected shop (D-08)", async () => {
      render(<MarketingPage />)
      await waitFor(() =>
        expect(screen.getByText("Peckham Lunch Deal")).toBeInTheDocument()
      )

      fireEvent.click(screen.getAllByRole("button", { name: /create promotion/i })[0])

      const shopSelect = await screen.findByLabelText(/shop \*/i)
      expect(shopSelect).toHaveValue(SHOP_A)
      expect(shopSelect).toBeDisabled()
      expect(screen.queryByRole("option", { name: /select a shop/i })).not.toBeInTheDocument()
    })
  })

  describe("Marketing — All shops context (zero day-one regression)", () => {
    it("keeps promotions from every shop visible", async () => {
      render(<MarketingPage />)

      await waitFor(() =>
        expect(screen.getByText("Peckham Lunch Deal")).toBeInTheDocument()
      )
      expect(screen.getByText("Brixton Bakery Bundle")).toBeInTheDocument()
    })

    it("requests promotions without a shopId param (today's cross-shop behaviour)", async () => {
      // A7: sending shopId= in the All-shops context would silently narrow a
      // GROUP_ADMIN to one shop — a capability regression, not a fix.
      render(<MarketingPage />)

      await waitFor(() => expect(callsTo("/api/v1/promotions?").length).toBeGreaterThan(0))
      expect(callsTo("/api/v1/promotions?").every((u) => !u.includes("shopId="))).toBe(true)
    })

    it("keeps the full shop dropdown on the create-promotion form", async () => {
      render(<MarketingPage />)
      await waitFor(() =>
        expect(screen.getByText("Peckham Lunch Deal")).toBeInTheDocument()
      )

      fireEvent.click(screen.getAllByRole("button", { name: /create promotion/i })[0])

      const shopSelect = await screen.findByLabelText(/shop \*/i)
      expect(shopSelect).not.toBeDisabled()
      expect(screen.getByRole("option", { name: /select a shop/i })).toBeInTheDocument()
    })
  })

  describe("Kitchen — the global switcher is the single source of truth", () => {
    it("boards the context shop, not a blind first-published shop", async () => {
      // SHOP_B is second in the list, so the pre-23-07 blind shopList[0] default
      // would have boarded SHOP_A.
      mockedGetShopContext.mockReturnValue(SHOP_B)

      render(<KitchenPage />)

      await waitFor(() => expect(kitchenBoardCalls().length).toBeGreaterThan(0))
      expect(kitchenBoardCalls().some((url) => url.includes(`shopId=${SHOP_B}`))).toBe(true)
      expect(kitchenBoardCalls().every((url) => !url.includes(`shopId=${SHOP_A}`))).toBe(true)
    })

    it("falls back to the first published shop in the All-shops context (today's behaviour)", async () => {
      mockedGetShopContext.mockReturnValue("all")

      render(<KitchenPage />)

      await waitFor(() => expect(kitchenBoardCalls().length).toBeGreaterThan(0))
      expect(kitchenBoardCalls().some((url) => url.includes(`shopId=${SHOP_A}`))).toBe(true)
    })
  })
})

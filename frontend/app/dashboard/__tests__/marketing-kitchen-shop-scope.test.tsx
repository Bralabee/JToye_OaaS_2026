/**
 * VSA-03 (23-07) — Marketing & Kitchen operate on the selected shop context.
 *
 * Marketing narrows client-side (its list endpoints take no shop param; the rows
 * are already grant-scoped by 23-03). Kitchen already queried per shop — the fix
 * there is single-source-of-truth: its board defaults from the GLOBAL switcher
 * context instead of a blind first-published shop.
 */
import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import MarketingPage from "../marketing/page"
import KitchenPage from "../kitchen/page"
import apiClient from "@/lib/api-client"
import { getShopContext } from "@/lib/shop-context"

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

const defaultMock = (url: string) => {
  if (url.startsWith("/api/v1/promotions")) {
    return Promise.resolve({
      data: { content: PROMOTIONS, totalPages: 1, totalElements: PROMOTIONS.length },
    })
  }
  if (url.startsWith("/api/v1/announcements")) {
    return Promise.resolve({
      data: { content: ANNOUNCEMENTS, totalPages: 1, totalElements: ANNOUNCEMENTS.length },
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

    it("narrows the promotions list to the selected shop", async () => {
      render(<MarketingPage />)

      await waitFor(() =>
        expect(screen.getByText("Peckham Lunch Deal")).toBeInTheDocument()
      )
      expect(screen.queryByText("Brixton Bakery Bundle")).not.toBeInTheDocument()
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

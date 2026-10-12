/**
 * F17 (37-36, D-17, UXT-008, UI-SPEC § C2): no promotion reaches a customer.
 *
 * J'Toye does not apply a discount at checkout. A storefront that shows "20% OFF"
 * advertises a price the customer will not be charged, so the storefront must not
 * render a promotion in ANY form — the banner row, the per-product chip, or a
 * price claim taken from the shop config — and must not even ask for one.
 *
 * Since 37-35 the server answers `[]` for every published shop. This suite does NOT
 * rely on that: every block feeds the page a payload that still CARRIES a live
 * "20% OFF" promotion (a legacy SSR seed, an old server, a cached response) and
 * asserts the customer sees none of it. That is what makes the blocks able to fail:
 * restoring the chip or the banner turns them red even though the server is fixed.
 *
 * Announcements are the good being preserved (UI-SPEC § C2 "Announcements stay
 * exactly as they are"), so each block that removes promotions also proves the
 * announcement still renders in the same band — the control arm that shows the
 * page really rendered and the absence above is not an empty render.
 */

import { act, render, screen, waitFor } from "@testing-library/react"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

jest.mock("@/components/storefront/cart-provider", () => ({
  useCart: () => ({
    items: [],
    addItem: jest.fn(),
    removeItem: jest.fn(),
    updateQuantity: jest.fn(),
    clearCart: jest.fn(),
    itemCount: 0,
    totalPennies: 0,
    shopSlug: "brixton-village-grill",
  }),
  CartProvider: ({ children }: { children: React.ReactNode }) => children,
}))

import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import { loadShopDetail } from "@/lib/storefront-server"
import type { PublicAnnouncement, PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const SLUG = "brixton-village-grill"

const shop: PublicShop = {
  slug: SLUG,
  name: "Brixton Village Grill",
  description: "Flame-grilled peri peri chicken, kebabs and loaded sides.",
  address: "Unit 74, Brixton Village Market, London SW9 8PS",
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 1000,
  deliveryFeePennies: 399,
  freeDeliveryThresholdPennies: 2000,
  tags: "Grill, Halal",
}

const product: PublicProduct = {
  id: "p1",
  title: "Peri Peri Chicken",
  description: "Half a flame-grilled bird.",
  imageUrl: null,
  imageUrls: [],
  ingredientsText: "chicken",
  allergenMask: 0,
  pricePennies: 850,
  category: "Mains",
  dietaryTags: "Halal",
  preparationTimeMinutes: 15,
  featured: true,
  inStock: true,
}

const announcement: PublicAnnouncement = {
  title: "Bank holiday hours",
  body: "We close at 4pm on Monday.",
  validUntil: null,
}

/** The shape the server published until 37-35: a live percentage and a flat discount. */
const LEGACY_PROMOTIONS = [
  {
    label: "20% OFF everything",
    discountType: "PERCENTAGE",
    discountPercent: 20,
    discountAmountPennies: null,
    category: "Mains",
    validUntil: "2099-01-01T00:00:00Z",
  },
  {
    label: "£2 OFF your first order",
    discountType: "FLAT_AMOUNT",
    discountPercent: null,
    discountAmountPennies: 200,
    category: null,
    validUntil: "2099-01-01T00:00:00Z",
  },
]

const detail: ShopDetail = {
  shop,
  products: { Mains: [product] },
  reviews: [],
  reviewCount: 0,
  avgRating: 0,
  announcements: [announcement],
}

/** A seed from an older loader that still carried promotions — the type no longer admits it. */
function legacySeed(overrides: Partial<ShopDetail> = {}): ShopDetail {
  return { ...detail, ...overrides, promotions: LEGACY_PROMOTIONS } as unknown as ShopDetail
}

/**
 * Every way the page could tell a customer about a discount. "off" as a whole
 * word covers "20% off", "£2.00 off" and the labels; the percentage covers a
 * chip that drops the word.
 */
function expectNoDiscountClaim() {
  const text = document.body.textContent ?? ""
  expect(text).not.toMatch(/\boff\b/i)
  expect(text).not.toMatch(/\d+\s*%/)
  expect(text).not.toMatch(/£\d+(\.\d+)?\s*off/i)
  expect(screen.queryByText(/20% OFF everything/i)).not.toBeInTheDocument()
  expect(screen.queryByText(/£2 OFF your first order/i)).not.toBeInTheDocument()
}

function promotionRequests(): string[] {
  return mockGet.mock.calls.map((c) => String(c[0])).filter((u) => u.includes("promotion"))
}

beforeEach(() => {
  mockGet.mockReset()
})

describe("/shop/[slug] — no promotion reaches the customer (F17, D-17)", () => {
  it("a server-seeded payload that still carries a 20% OFF promotion renders no banner and no chip", async () => {
    await act(async () => {
      render(<ShopDetailClient slug={SLUG} initial={legacySeed()} />)
    })

    // Control: the page rendered its menu (the featured rail AND the category list,
    // the two places the chip used to sit) and the announcement band.
    expect(screen.getByRole("heading", { level: 1, name: "Brixton Village Grill" })).toBeInTheDocument()
    expect(screen.getAllByRole("heading", { level: 3, name: /Peri Peri Chicken/ })).toHaveLength(2)
    expect(screen.getByText("Bank holiday hours")).toBeInTheDocument()
    expect(screen.getByText("We close at 4pm on Monday.")).toBeInTheDocument()

    expectNoDiscountClaim()
    expect(mockGet).not.toHaveBeenCalled()
  })

  it("leaves no empty band where the promotions used to be when the shop has no announcement", async () => {
    await act(async () => {
      render(<ShopDetailClient slug={SLUG} initial={legacySeed({ announcements: [] })} />)
    })

    expect(screen.getByRole("heading", { level: 1, name: "Brixton Village Grill" })).toBeInTheDocument()
    expectNoDiscountClaim()
    // The band is the announcements' alone now: with none, it is not rendered at all,
    // rather than as an empty white strip above the menu (UI-SPEC § C2, CLS).
    expect(screen.queryByTestId("shop-announcements")).not.toBeInTheDocument()
  })

  it("keeps the announcement band, alone, when the shop has one", async () => {
    await act(async () => {
      render(<ShopDetailClient slug={SLUG} initial={legacySeed()} />)
    })
    const band = screen.getByTestId("shop-announcements")
    expect(band).toHaveTextContent("Bank holiday hours")
    expect(band.textContent).not.toMatch(/\boff\b/i)
  })

  it("the client load never asks for promotions, and ignores any it is handed", async () => {
    mockGet.mockImplementation(async (url: string) => {
      if (url === `/public/shops/${SLUG}`) return { data: shop }
      if (url === `/public/shops/${SLUG}/products`) return { data: { Mains: [product] } }
      if (url.startsWith(`/public/shops/${SLUG}/reviews`)) return { data: { content: [], totalElements: 0 } }
      if (url === `/public/shops/${SLUG}/config`) {
        return {
          data: {
            announcements: [],
            featuredProducts: [],
            activePromotions: [
              { label: "20% OFF everything", discountPercent: 20, category: null, validUntil: "2099-01-01T00:00:00Z" },
            ],
          },
        }
      }
      if (url === `/public/shops/${SLUG}/promotions`) return { data: LEGACY_PROMOTIONS }
      if (url === `/public/shops/${SLUG}/announcements`) return { data: [announcement] }
      throw new Error(`unexpected request ${url}`)
    })

    await act(async () => {
      render(<ShopDetailClient slug={SLUG} initial={null} />)
    })

    // Control: the client path really ran and rendered from its own fetches.
    await waitFor(() =>
      expect(screen.getByRole("heading", { level: 1, name: "Brixton Village Grill" })).toBeInTheDocument()
    )
    expect(mockGet).toHaveBeenCalledWith(`/public/shops/${SLUG}/products`)
    expect(screen.getByText("Bank holiday hours")).toBeInTheDocument()

    expect(promotionRequests()).toEqual([])
    expectNoDiscountClaim()
  })
})

describe("loadShopDetail — the server render does not fetch or carry promotions (F17, D-17)", () => {
  const realFetch = global.fetch

  afterEach(() => {
    global.fetch = realFetch
  })

  it("makes no /promotions request and returns no promotions field", async () => {
    const fetchMock = jest.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const body = /\/public\/shops\/[^/?]+$/.test(url)
        ? shop
        : url.endsWith("/products")
          ? { Mains: [product] }
          : url.endsWith("/promotions")
            ? LEGACY_PROMOTIONS
            : url.endsWith("/announcements")
              ? [announcement]
              : { content: [], totalElements: 0 }
      return { ok: true, status: 200, json: async () => body } as Response
    })
    global.fetch = fetchMock as unknown as typeof fetch

    const r = await loadShopDetail("promotions-hidden-ssr")
    expect(r.state).toBe("ok")
    if (r.state !== "ok") return

    const urls = fetchMock.mock.calls.map((c) => String(c[0]))
    // Control: the loader really fetched the page (shop, menu, reviews, announcements).
    expect(urls.some((u) => u.endsWith("/products"))).toBe(true)
    expect(urls.some((u) => u.endsWith("/announcements"))).toBe(true)
    expect(r.data.announcements).toEqual([announcement])

    expect(urls.filter((u) => u.includes("promotion"))).toEqual([])
    expect(r.data).not.toHaveProperty("promotions")
  })
})

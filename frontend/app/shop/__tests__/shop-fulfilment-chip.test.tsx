/**
 * 37-49 Task 2 (UI-SPEC G1 "Storefront", UXT-023): a customer sees on the shop page, before ordering,
 * that a shop only collects or only delivers.
 *
 * The header's status row holds the open state and the rating; the chip joins them. UI-SPEC left the
 * 375px overflow of that row unresolved, and the plan's assumption is that the row WRAPS (flex-wrap),
 * so the chip moves under the open state instead of pushing past the viewport. That half is asserted
 * structurally here; the pixel half is the 37-50 gate's (a real 375px render).
 */

import { act, render, screen } from "@testing-library/react"

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
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const SLUG = "brixton-village-grill"

const BASE: PublicShop = {
  slug: SLUG,
  name: "Brixton Village Grill",
  description: null,
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
  tags: null,
  openNow: true,
  offersDelivery: true,
  offersCollection: true,
}

const product: PublicProduct = {
  id: "p1",
  title: "Peri Peri Chicken",
  description: null,
  imageUrl: null,
  imageUrls: [],
  ingredientsText: "chicken",
  allergenMask: 0,
  pricePennies: 850,
  category: "Mains",
  dietaryTags: null,
  preparationTimeMinutes: 15,
  featured: false,
  inStock: true,
}

function detail(shop: PublicShop): ShopDetail {
  return {
    shop,
    products: { Mains: [product] },
    reviews: [],
    reviewCount: 3,
    avgRating: 4.7,
    announcements: [],
  }
}

async function renderShop(shop: PublicShop) {
  await act(async () => {
    render(<ShopDetailClient slug={SLUG} initial={detail(shop)} />)
  })
  // Control: the header rendered (so an absent chip below is not an empty render).
  expect(screen.getByRole("heading", { level: 1, name: "Brixton Village Grill" })).toBeInTheDocument()
}

const statusRow = () => screen.getByTestId("shop-header-status")

beforeEach(() => {
  mockGet.mockReset()
})

describe("the storefront header says when a shop takes only one fulfilment type (UI-SPEC G1)", () => {
  it("collection only: a 'Collection only' chip with the Store icon, in the header's status row beside the open state and rating", async () => {
    await renderShop({ ...BASE, offersDelivery: false })
    const chip = screen.getByTestId("shop-fulfilment-chip")
    expect(chip).toHaveTextContent(/^Collection only$/)
    expect(chip.querySelector("svg.lucide-store")).not.toBeNull()
    expect(statusRow()).toContainElement(chip)
    expect(statusRow()).toContainElement(screen.getByTestId("shop-availability-pill"))
    expect(statusRow()).toHaveTextContent("4.7 (3)")
  })

  it("delivery only: a 'Delivery only' chip with the Bike icon", async () => {
    await renderShop({ ...BASE, offersCollection: false })
    const chip = screen.getByTestId("shop-fulfilment-chip")
    expect(chip).toHaveTextContent(/^Delivery only$/)
    expect(chip.querySelector("svg.lucide-bike")).not.toBeNull()
  })

  it("both offered: no chip", async () => {
    await renderShop(BASE)
    expect(screen.queryByTestId("shop-fulfilment-chip")).not.toBeInTheDocument()
    expect(screen.queryByText(/Collection only|Delivery only/)).not.toBeInTheDocument()
  })

  it("an older payload with no flags: no chip (absent reads as offered)", async () => {
    const { offersDelivery: _d, offersCollection: _c, ...legacy } = BASE
    void _d
    void _c
    await renderShop(legacy)
    expect(screen.queryByTestId("shop-fulfilment-chip")).not.toBeInTheDocument()
  })

  it("the status row wraps (flex-wrap) so the chip moves under the open state at 375px instead of overflowing", async () => {
    await renderShop({ ...BASE, offersDelivery: false })
    expect(statusRow()).toHaveClass("flex", "flex-wrap")
    // The chip itself never breaks mid-phrase.
    expect(screen.getByTestId("shop-fulfilment-chip")).toHaveClass("whitespace-nowrap")
  })
})

describe("a collection-only shop does not advertise delivery in its details bar", () => {
  it("no 'Free delivery' and no delivery fee line when the shop does not deliver", async () => {
    await renderShop({ ...BASE, offersDelivery: false })
    expect(screen.queryByText("Free delivery")).not.toBeInTheDocument()
    expect(screen.queryByText(/Delivery £3\.99/)).not.toBeInTheDocument()
    // Control: the bar itself still rendered (the minimum order is in it).
    expect(screen.getByText("Min order £10.00")).toBeInTheDocument()
  })

  it("a shop that delivers keeps its fee and threshold line (preserved good)", async () => {
    await renderShop(BASE)
    expect(screen.getByText(/Delivery £3\.99/)).toBeInTheDocument()
    expect(screen.getByText("Free over £20.00")).toBeInTheDocument()
  })
})

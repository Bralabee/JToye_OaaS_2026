/**
 * A11Y-7 (storefront half): the dish-card +/- quantity stepper in
 * `shop-detail-client.tsx`'s `ProductCard` carried no accessible name at all —
 * identical defect to the cart page's stepper, same fix. This uses the REAL
 * `CartProvider` (not the mocked stub `server-seeded-islands.test.tsx` uses)
 * because the mock always reports `items: []`, which never exercises the
 * quantity-stepper branch (`quantity === 0` renders the plain "Add" button
 * instead) — the exact branch this defect lives in.
 */

import { act, render, screen } from "@testing-library/react"
import { CartProvider } from "@/components/storefront/cart-provider"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

const SLUG = "brixton-village-grill"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

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
  tags: "Grill, Peri Peri, Halal",
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
  featured: false,
  inStock: true,
}

const detail: ShopDetail = {
  shop,
  products: { Mains: [product] },
  reviews: [],
  reviewCount: 0,
  avgRating: 0,
  promotions: [],
  announcements: [],
  isOpen: true,
}

function seed(quantity: number) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      owner: null,
      items: [
        {
          productId: "p1",
          title: "Peri Peri Chicken",
          pricePennies: 850,
          quantity,
          imageUrl: null,
          category: "Mains",
        },
      ],
    })
  )
}

async function renderCard() {
  await act(async () => {
    render(
      <CartProvider shopSlug={SLUG}>
        <ShopDetailClient slug={SLUG} initial={detail} />
      </CartProvider>
    )
  })
}

describe("Storefront dish-card quantity stepper accessible names (A11Y-7)", () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockGet.mockResolvedValue({ data: { content: [], totalPages: 0, totalElements: 0 } })
  })

  afterEach(() => {
    localStorage.clear()
  })

  it("names both steppers after the dish when quantity > 1", async () => {
    seed(2)
    await renderCard()
    // CartProvider hydrates from localStorage in an effect; wait for the
    // stepper (rather than the plain "Add" button) to appear.
    expect(
      await screen.findByRole("button", { name: "Decrease quantity of Peri Peri Chicken" })
    ).toBeInTheDocument()
    expect(
      screen.getByRole("button", { name: "Increase quantity of Peri Peri Chicken" })
    ).toBeInTheDocument()
  })

  it("names the minus button as a removal once quantity is 1", async () => {
    seed(1)
    await renderCard()
    expect(
      await screen.findByRole("button", { name: "Remove Peri Peri Chicken from basket" })
    ).toBeInTheDocument()
    expect(
      screen.queryByRole("button", { name: "Decrease quantity of Peri Peri Chicken" })
    ).not.toBeInTheDocument()
  })
})

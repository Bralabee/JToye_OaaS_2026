/**
 * #862 (owner ruling 2026-11-04, persona close-out): on a kitchen's menu a customer can show only the
 * dishes that DECLARE an allergen. The filter reads the product's declared `allergenMask` (the
 * vendor's declaration), never the advisory ingredient reconciliation or the "may contain" list, and
 * its copy never says "free from".
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn() },
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
    shopSlug: "grill",
  }),
  CartProvider: ({ children }: { children: React.ReactNode }) => children,
}))

import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const SLUG = "grill"
const SHOP: PublicShop = {
  slug: SLUG,
  name: "Grill House",
  description: null,
  address: "1 Test Road",
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 0,
  deliveryFeePennies: 0,
  freeDeliveryThresholdPennies: null,
  tags: null,
  openNow: true,
  offersDelivery: true,
  offersCollection: true,
}

function product(id: string, title: string, allergenMask: number, category = "Mains"): PublicProduct {
  return {
    id,
    title,
    description: null,
    imageUrl: null,
    imageUrls: [],
    ingredientsText: "x",
    allergenMask,
    pricePennies: 800,
    category,
    dietaryTags: null,
    preparationTimeMinutes: 10,
    featured: false,
    inStock: true,
  }
}

const PEANUTS = 1 << 4
const MILK = 1 << 6

async function renderMenu() {
  const detail: ShopDetail = {
    shop: SHOP,
    products: {
      Mains: [product("1", "Suya skewers", PEANUTS), product("2", "Plain rice", 0)],
      Sides: [product("3", "Creamy slaw", MILK, "Sides")],
    },
    reviews: [],
    reviewCount: 0,
    avgRating: 0,
    announcements: [],
  }
  await act(async () => {
    render(<ShopDetailClient slug={SLUG} initial={detail} />)
  })
}

const menu = () => screen.getByTestId("shop-menu")
const select = () => screen.getByRole("combobox", { name: /show dishes that declare/i })

describe("shop menu: the declared-allergen filter (#862)", () => {
  it("shows every dish until an allergen is picked (control for the filtered arms)", async () => {
    await renderMenu()
    expect(within(menu()).getByText("Suya skewers")).toBeInTheDocument()
    expect(within(menu()).getByText("Plain rice")).toBeInTheDocument()
    expect(within(menu()).getByText("Creamy slaw")).toBeInTheDocument()
  })

  it("picking Peanuts keeps only the dishes whose DECLARED mask has the peanut bit", async () => {
    await renderMenu()
    fireEvent.change(select(), { target: { value: String(4) } })

    expect(within(menu()).getByText("Suya skewers")).toBeInTheDocument()
    expect(within(menu()).queryByText("Plain rice")).toBeNull()
    expect(within(menu()).queryByText("Creamy slaw")).toBeNull()
    expect(screen.getByText(/showing dishes that declare peanuts/i)).toBeInTheDocument()
    // An emptied category disappears with its dishes rather than leaving a bare heading.
    expect(within(menu()).queryByRole("heading", { name: "Sides" })).toBeNull()
  })

  it("an allergen no dish declares says so plainly instead of 'No items yet'", async () => {
    await renderMenu()
    fireEvent.change(select(), { target: { value: String(10) } }) // Sesame

    expect(screen.getByText(/no dishes at this kitchen declare sesame/i)).toBeInTheDocument()
    expect(screen.queryByText(/no items yet/i)).toBeNull()
    // Said as the vendor's declaration, never as a safety statement.
    expect(screen.getAllByText(/not confirmed free of it/i).length).toBeGreaterThanOrEqual(1)
  })

  it("choosing 'Any allergen' again restores the whole menu", async () => {
    await renderMenu()
    fireEvent.change(select(), { target: { value: String(4) } })
    fireEvent.change(select(), { target: { value: "" } })

    expect(within(menu()).getByText("Plain rice")).toBeInTheDocument()
    expect(within(menu()).getByText("Creamy slaw")).toBeInTheDocument()
  })

  it("never uses 'free from' or safety wording in the filter's own copy", async () => {
    await renderMenu()
    fireEvent.change(select(), { target: { value: String(10) } })
    const text = screen.getByTestId("declared-allergen-filter").textContent ?? ""
    expect(text).not.toMatch(/\bfree from\b|allergen[- ]free|\bsafe\b|guarantee/i)
  })
})

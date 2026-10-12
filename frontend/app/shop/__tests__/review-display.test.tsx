/**
 * F19 (37-36, UXT-044, UI-SPEC § C4): a public review names its author the way the
 * server publishes it, and never as "Anonymous".
 *
 * Since 37-35 the public ReviewDto.customerName is already the minimised display
 * form ("Grace P.", a single word as given, blank or missing as null), so the page
 * renders it verbatim. The page owns only the fallback: a review with no name is
 * from a "Customer". "Anonymous" falsely told the reader that the reviewer chose
 * anonymity, which nobody did (UI-SPEC § C4).
 */

import { act, render, screen, within } from "@testing-library/react"

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
import type { PublicProduct, PublicShop, Review, ShopDetail } from "@/types/storefront"

const shop: PublicShop = {
  slug: "brixton-village-grill",
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

function review(id: string, customerName: string | null, comment: string): Review {
  return {
    id,
    customerName,
    foodRating: 5,
    deliveryRating: null,
    comment,
    photoUrls: null,
    createdAt: "2026-10-01T12:00:00Z",
  }
}

function detailWith(reviews: Review[]): ShopDetail {
  return {
    shop,
    products: { Mains: [product] },
    reviews,
    reviewCount: reviews.length,
    avgRating: 5,
    announcements: [],
  }
}

/** The card a review renders in, found from its (unique) comment. */
function cardOf(comment: string): HTMLElement {
  const card = screen.getByText(comment).parentElement
  if (!card) throw new Error(`no review card holds "${comment}"`)
  return card
}

beforeEach(() => {
  mockGet.mockReset()
})

describe("/shop/[slug] reviews — the reviewer's public name (F19, UXT-044)", () => {
  it("renders the server's display name, the single-word name as given, and 'Customer' for no name", async () => {
    await act(async () => {
      render(
        <ShopDetailClient
          slug="brixton-village-grill"
          initial={detailWith([
            review("r1", "Grace P.", "Lovely jollof"),
            review("r2", "Grace", "Quick delivery"),
            review("r3", null, "Will order again"),
          ])}
        />
      )
    })

    // Control: the reviews section rendered all three cards.
    expect(screen.getByRole("heading", { level: 2, name: "Customer reviews (3)" })).toBeInTheDocument()

    expect(within(cardOf("Lovely jollof")).getByText("Grace P.")).toBeInTheDocument()
    expect(within(cardOf("Quick delivery")).getByText("Grace")).toBeInTheDocument()
    expect(within(cardOf("Will order again")).getByText("Customer")).toBeInTheDocument()
    expect(screen.queryByText(/anonymous/i)).not.toBeInTheDocument()
  })

  it("an empty or blank name is a 'Customer' too, never 'Anonymous' and never an empty label", async () => {
    await act(async () => {
      render(
        <ShopDetailClient
          slug="brixton-village-grill"
          initial={detailWith([review("r1", "", "Great wings"), review("r2", "   ", "Hot and fresh")])}
        />
      )
    })

    expect(screen.getByRole("heading", { level: 2, name: "Customer reviews (2)" })).toBeInTheDocument()
    expect(within(cardOf("Great wings")).getByText("Customer")).toBeInTheDocument()
    expect(within(cardOf("Hot and fresh")).getByText("Customer")).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/anonymous/i)
  })
})

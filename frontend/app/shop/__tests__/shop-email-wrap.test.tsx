/**
 * PR #1126 review: the shop's email in the details bar is one unbreakable word. In an inline-flex link with
 * no wrapping rule, a long address (kitchen@<slug>.example.com) is as wide as its text and pushed the page
 * past a 320px viewport at 200% text, the same defect as the allergen panel's contact line. The pixel half
 * is measured in a real browser (e2e/a11y-storefront.spec.ts, SW-MRC-10); this is the structural half:
 * the link may be no wider than its row and may break anywhere.
 */

import { act, render, screen } from "@testing-library/react"

jest.mock("@/lib/public-api-client", () => ({ __esModule: true, default: { get: jest.fn() } }))
jest.mock("@/components/storefront/cart-provider", () => ({
  useCart: () => ({
    items: [],
    addItem: jest.fn(),
    removeItem: jest.fn(),
    updateQuantity: jest.fn(),
    clearCart: jest.fn(),
    itemCount: 0,
    totalPennies: 0,
    shopSlug: "mama-ades-kitchen",
  }),
  CartProvider: ({ children }: { children: React.ReactNode }) => children,
}))

import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicShop, ShopDetail } from "@/types/storefront"

const EMAIL = "kitchen@mama-ades-kitchen-peckham-and-brixton.example.com"

const shop: PublicShop = {
  slug: "mama-ades-kitchen",
  name: "Mama Ade's Kitchen",
  description: null,
  address: "48 Rye Lane, Peckham, London SE15 5BS",
  logoUrl: null,
  bannerUrl: null,
  phone: "020 7946 0000",
  email: EMAIL,
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

const detail: ShopDetail = { shop, products: {}, reviews: [], reviewCount: 0, avgRating: 0, announcements: [] }

describe("the shop email in the details bar wraps instead of widening the page", () => {
  it("the link is capped at its row's width and may break anywhere", async () => {
    await act(async () => {
      render(<ShopDetailClient slug="mama-ades-kitchen" initial={detail} />)
    })
    const link = screen.getByTestId("shop-email-link")
    expect(link).toHaveAttribute("href", `mailto:${EMAIL}`)
    expect(link).toHaveTextContent(EMAIL)
    expect(link).toHaveClass("max-w-full", "[overflow-wrap:anywhere]")
  })

  it("control: no email, no link", async () => {
    await act(async () => {
      render(<ShopDetailClient slug="mama-ades-kitchen" initial={{ ...detail, shop: { ...shop, email: null } }} />)
    })
    expect(screen.queryByTestId("shop-email-link")).not.toBeInTheDocument()
  })
})

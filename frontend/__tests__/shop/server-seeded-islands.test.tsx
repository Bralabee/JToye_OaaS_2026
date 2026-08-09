/**
 * The #507 invariant, stated as a test: when the SERVER has already fetched, the
 * island renders that content immediately and makes NO request on mount.
 *
 * This is the property the whole change exists to create. Measured before the
 * change at the repo's throttled mobile profile (390px, 4x CPU, ~Fast 3G),
 * time-to-content was 2456 ms on /shop and 2481 ms on /shop/brixton-village-grill,
 * with 1 and 6 browser-side API calls on load. After, 926 ms and 941 ms, with 0
 * and 0. A component test cannot measure milliseconds, but it can pin the two
 * things that produce them: content present at first render, and zero fetches.
 *
 * Both blocks are written to fail on the obvious wrong implementation — an
 * island that renders the seed AND still refetches on mount would look correct
 * in a browser and would keep the spinner, the extra round-trip and the layout
 * shift that #507 is about.
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

import { ShopDiscoveryClient } from "@/app/shop/shop-discovery-client"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const shop: PublicShop = {
  slug: "brixton-village-grill",
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

beforeEach(() => {
  mockGet.mockReset()
  mockGet.mockResolvedValue({ data: { content: [], totalPages: 0, totalElements: 0 } })
})

describe("/shop — server-seeded directory", () => {
  it("renders the shops from the seed with no fetch on mount", async () => {
    await act(async () => {
      render(
        <ShopDiscoveryClient
          initial={{ content: [shop], totalPages: 1, totalElements: 1, number: 0, size: 12 }}
          initialQuery=""
          initialInterpretation={{ kind: "text" }}
        />
      )
    })

    expect(screen.getByText("Brixton Village Grill")).toBeInTheDocument()
    // No skeleton: the seed is real content, so the loading branch must not run.
    expect(screen.queryByText("No kitchens found")).not.toBeInTheDocument()
    expect(mockGet).not.toHaveBeenCalled()
  })

  it("still fetches on mount when the server deferred", async () => {
    // The control arm. Without it, "no fetch" above could be true because the
    // island never fetches at all, which would be a different bug.
    await act(async () => {
      render(
        <ShopDiscoveryClient
          initial={null}
          initialQuery=""
          initialInterpretation={{ kind: "text" }}
        />
      )
    })
    expect(mockGet).toHaveBeenCalledWith("/public/shops", expect.anything())
  })
})

describe("/shop/[slug] — server-seeded storefront", () => {
  it("renders the shop name and its menu from the seed, with no fetch on mount", async () => {
    await act(async () => {
      render(<ShopDetailClient slug="brixton-village-grill" initial={detail} />)
    })

    // The <h1> that the served HTML had zero of before this change.
    expect(
      screen.getByRole("heading", { level: 1, name: "Brixton Village Grill" })
    ).toBeInTheDocument()
    expect(screen.getByText("Peri Peri Chicken")).toBeInTheDocument()
    expect(screen.getByText("Mains")).toBeInTheDocument()
    expect(mockGet).not.toHaveBeenCalled()
  })

  it("uses the server's open/closed verdict rather than recomputing it", async () => {
    await act(async () => {
      render(
        <ShopDetailClient
          slug="brixton-village-grill"
          initial={{ ...detail, isOpen: false }}
        />
      )
    })
    // openingHours is null, which `isOpenNow` treats as ALWAYS OPEN — so a
    // "Closed" pill here can only have come from the server's flag. That is what
    // makes this block able to fail if the client starts recomputing.
    expect(screen.getByText("Closed")).toBeInTheDocument()
    expect(screen.queryByText("Open now")).not.toBeInTheDocument()
  })

  it("puts the dish title at h3, under the h2 category (#447 heading order)", async () => {
    await act(async () => {
      render(<ShopDetailClient slug="brixton-village-grill" initial={detail} />)
    })
    expect(screen.getByRole("heading", { level: 2, name: "Mains" })).toBeInTheDocument()
    expect(
      screen.getByRole("heading", { level: 3, name: /Peri Peri Chicken/ })
    ).toBeInTheDocument()
    // The level that used to be here, skipping h3 entirely.
    expect(screen.queryByRole("heading", { level: 4 })).not.toBeInTheDocument()
  })

  it("keeps the keyboard-reachable dialog trigger above the card (#446)", async () => {
    // A1's invariant, asserted from this side of the split too: the stretched
    // trigger must still exist and must still be the LAST child of the article,
    // with the "Add" control at z-10 above it. Breaking either makes the dish
    // modal unreachable by keyboard, which is the state #446 measured.
    await act(async () => {
      render(<ShopDetailClient slug="brixton-village-grill" initial={detail} />)
    })

    const trigger = screen.getByRole("button", { name: "View details for Peri Peri Chicken" })
    const article = trigger.closest("article")!
    expect(article.lastElementChild).toBe(trigger)
    expect(trigger.className).toContain("absolute inset-0")

    const add = screen.getByRole("button", { name: "Add" })
    expect(add.className).toContain("relative z-10")
    expect(article.contains(add)).toBe(true)
  })
})

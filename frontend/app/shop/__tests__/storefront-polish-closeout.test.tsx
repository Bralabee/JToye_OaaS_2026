/**
 * #877 (persona close-out 2026-11-04), the storefront half:
 *
 *   UXT-110  ratings were drawn as icons and a bare "4.7 (3)" / "5 Grace P.": a screen reader heard
 *            "5 (1)" with no unit. The header's rating and each review's stars now read as one phrase,
 *            "4.7 out of 5 stars, 3 reviews" / "5 out of 5 stars". The sighted text is unchanged.
 *   UXT-112  the postcode-search result line was inserted into the DOM already holding its text, which many
 *            screen readers do not announce. Its live region is now mounted before the text and filled
 *            after.
 */
import { act, render, screen } from "@testing-library/react"

import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import { ShopDiscoveryClient } from "@/app/shop/shop-discovery-client"
import type { PublicProduct, PublicShop, Review, ShopDetail } from "@/types/storefront"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
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

const SLUG = "brixton-village-grill"
const SHOP: PublicShop = {
  slug: SLUG,
  name: "Brixton Village Grill",
  description: null,
  address: null,
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: 51.47,
  longitude: -0.07,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 1000,
  deliveryFeePennies: 399,
  freeDeliveryThresholdPennies: 2000,
  tags: null,
  openNow: true,
}
const PRODUCT: PublicProduct = {
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
const review = (id: string, foodRating: number, customerName = "Grace P."): Review =>
  ({ id, foodRating, deliveryRating: null, comment: null, customerName, photoUrls: [], createdAt: "2026-11-01T10:00:00Z" }) as Review

function detail(reviews: Review[], reviewCount: number, avgRating: number): ShopDetail {
  return { shop: SHOP, products: { Mains: [PRODUCT] }, reviews, reviewCount, avgRating, announcements: [] }
}

async function renderShop(d: ShopDetail) {
  await act(async () => {
    render(<ShopDetailClient slug={SLUG} initial={d} />)
  })
  expect(screen.getByRole("heading", { level: 1, name: "Brixton Village Grill" })).toBeInTheDocument()
}

describe("UXT-110: ratings are said with their unit", () => {
  it("the header's rating reads '4.7 out of 5 stars, 3 reviews' and still shows '4.7 (3)'", async () => {
    await renderShop(detail([review("r1", 5)], 3, 4.7))
    const rating = screen.getByRole("img", { name: "Rated 4.7 out of 5 stars, 3 reviews" })
    expect(rating).toHaveTextContent("4.7 (3)")
  })

  it("one review is '1 review', not '1 reviews'", async () => {
    await renderShop(detail([review("r1", 5)], 1, 5))
    expect(screen.getByRole("img", { name: "Rated 5 out of 5 stars, 1 review" })).toBeInTheDocument()
  })

  it("each review's stars are one image named '5 out of 5 stars'", async () => {
    await renderShop(detail([review("r1", 5), review("r2", 3, "Tunde O.")], 2, 4))
    expect(screen.getByRole("img", { name: "5 out of 5 stars" })).toBeInTheDocument()
    expect(screen.getByRole("img", { name: "3 out of 5 stars" })).toBeInTheDocument()
  })

  it("CONTROL: a shop with no reviews says nothing about stars", async () => {
    await renderShop(detail([], 0, 0))
    expect(screen.queryByRole("img", { name: /out of 5 stars/ })).toBeNull()
  })
})

// ---- UXT-112 ------------------------------------------------------------------------------------

function page(content: PublicShop[]) {
  return { content, totalElements: content.length, totalPages: 1, size: 12, number: 0, first: true, last: true }
}

describe("UXT-112: the result line's live region exists before its text", () => {
  const region = () => document.querySelector('[data-testid="search-summary-region"]') as HTMLElement | null

  it("with no search it is mounted, polite and empty, and takes no space", () => {
    render(<ShopDiscoveryClient initial={page([SHOP])} initialQuery="" initialInterpretation={{ kind: "text" }} />)
    const el = region()
    expect(el).not.toBeNull()
    expect(el).toHaveAttribute("aria-live", "polite")
    expect(el).toHaveTextContent(/^$/)
    expect(el!.className).not.toMatch(/\bmb-\d/)
  })

  it("with a search the SAME kind of region carries the count text", () => {
    render(
      <ShopDiscoveryClient
        initial={page([SHOP])}
        initialQuery="jollof"
        initialInterpretation={{ kind: "text" }}
      />
    )
    const el = region()
    expect(el).not.toBeNull()
    expect(el).toHaveAttribute("aria-live", "polite")
    expect(el).toHaveTextContent(/1 kitchen for/i)
  })
})

// ---- UXT-112 (second half): an unfound postcode, an out-of-area one and a text miss read differently --------

describe("UXT-112: three empty searches, three different lines", () => {
  const lineFor = (q: string, interpretation: React.ComponentProps<typeof ShopDiscoveryClient>["initialInterpretation"]) => {
    render(<ShopDiscoveryClient initial={page([])} initialQuery={q} initialInterpretation={interpretation} />)
    return screen.getByTestId("search-summary-region").textContent ?? ""
  }

  it("a postcode the server never found: says so, with no 'within N miles' claim and no exclusion note", () => {
    const line = lineFor("BT1 5GS", { kind: "unrecognised-postcode", postcode: "BT15GS", precision: "unit" })
    expect(line).toMatch(/We could not find the postcode BT1 5GS/)
    expect(line).not.toMatch(/within/i)
    expect(line).not.toMatch(/further away/i)
  })

  it("the empty-state panel below does not tell the customer to try a postcode they were just told was not found", () => {
    lineFor("BT1 5GS", { kind: "unrecognised-postcode", postcode: "BT15GS", precision: "unit" })
    expect(screen.getByRole("heading", { name: "Postcode not found" })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/Try a different dish, cuisine or postcode/)
    expect(document.body.textContent).toMatch(/Check the postcode/)
  })

  it("CONTROL: a text miss keeps the generic panel", () => {
    lineFor("zzzzqq", { kind: "text" })
    expect(screen.getByRole("heading", { name: "No kitchens found" })).toBeInTheDocument()
    expect(document.body.textContent).toMatch(/Try a different dish, cuisine or postcode/)
  })

  it("a real postcode with no kitchen near it: says how far it looked", () => {
    const line = lineFor("AB10 1AA", { kind: "proximity", postcode: "AB101AA", precision: "unit", radiusKm: 5 })
    expect(line).toMatch(/No kitchens within 3\.1 miles of AB10 1AA/)
    expect(line).not.toMatch(/could not find/i)
  })

  it("a word that matches nothing: 'No kitchens match'", () => {
    const line = lineFor("zzzzqq", { kind: "text" })
    expect(line).toMatch(/No kitchens match/)
    expect(line).not.toMatch(/could not find/i)
  })
})

// ---- UXT-110 (alt text) ---------------------------------------------------------------------------------------

describe("UXT-110: a dish photo beside its own heading is decorative", () => {
  const WITH_PHOTO: PublicProduct = { ...PRODUCT, imageUrl: "https://example.test/peri.webp", imageUrls: ["https://example.test/peri.webp"] }

  it("the menu card's photo has alt='' so the dish name is read once, by its heading", async () => {
    await act(async () => {
      render(
        <ShopDetailClient slug={SLUG} initial={{ shop: SHOP, products: { Mains: [WITH_PHOTO] }, reviews: [], reviewCount: 0, avgRating: 0, announcements: [] }} />
      )
    })
    const img = document.querySelector('article img[src="https://example.test/peri.webp"]')
    expect(img).not.toBeNull() // CONTROL: the photo is rendered, so "empty alt" is a measurement
    expect(img).toHaveAttribute("alt", "")
    expect(screen.queryByAltText("Peri Peri Chicken")).toBeNull()
    expect(screen.getAllByRole("heading", { name: "Peri Peri Chicken" }).length).toBeGreaterThan(0)
  })

  it("a dish with no photo draws a placeholder that is not announced as an image named after the dish", async () => {
    await act(async () => {
      render(<ShopDetailClient slug={SLUG} initial={detail([], 0, 0)} />)
    })
    expect(screen.queryByRole("img", { name: "Peri Peri Chicken" })).toBeNull()
  })
})

// ---- UXT-109 (the half that is built): a kitchen with a photo shows it on its list card ----------------------

describe("UXT-109: the list card shows the kitchen's own photo when it has one", () => {
  const card = (shop: PublicShop) => {
    render(<ShopDiscoveryClient initial={page([shop])} initialQuery="" initialInterpretation={{ kind: "text" }} />)
    return document.querySelector(`article[data-shop-name="${shop.name}"]`) as HTMLElement
  }

  it("with a banner photo the card renders that photo, not only the gradient", () => {
    const img = card({ ...SHOP, bannerUrl: "https://example.test/banner.webp" }).querySelector("img")
    expect(img).not.toBeNull()
    expect(img).toHaveAttribute("src", "https://example.test/banner.webp")
  })

  it("CONTROL: with no photo there is no image, the gradient is what shows", () => {
    expect(card({ ...SHOP, bannerUrl: null }).querySelector("img")).toBeNull()
  })
})

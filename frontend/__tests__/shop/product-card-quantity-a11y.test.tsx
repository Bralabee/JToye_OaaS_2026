/**
 * A11Y-7 (storefront half): the dish-card +/- quantity stepper in
 * `shop-detail-client.tsx`'s `ProductCard` carried no accessible name at all —
 * identical defect to the cart page's stepper, same fix. This uses the REAL
 * `CartProvider` (not the mocked stub `server-seeded-islands.test.tsx` uses)
 * because the mock always reports `items: []`, which never exercises the
 * quantity-stepper branch (`quantity === 0` renders the plain "Add" button
 * instead) — the exact branch this defect lives in.
 */

// 37-52 (F3): the basket announcement lives in the storefront header, so the F3 block below renders
// the real StorefrontNav beside the shop. These mocks are what it needs and are inert for the rest.
jest.mock("next/navigation", () => ({
  usePathname: () => "/shop/brixton-village-grill",
  useParams: () => ({ slug: "brixton-village-grill" }),
}))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
  customerIdpSignOut: jest.fn(),
}))

import { act, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { CartProvider } from "@/components/storefront/cart-provider"
import { StorefrontNav } from "@/components/storefront/storefront-nav"
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
  announcements: [],
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
    // 37-52 (UI-SPEC F3): the card's "+" is named for what it does, "Add one more {dish}".
    expect(
      screen.getByRole("button", { name: "Add one more Peri Peri Chicken" })
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

/**
 * 37-52 (UI-SPEC F3, UXT-083, D-05). Two defects a keyboard and screen-reader customer hit on the menu:
 *
 *  1. FOCUS. "Add" is replaced by the stepper when the dish goes into the basket, so the focused
 *     element left the DOM and focus fell to <body>: the customer's place in the menu was gone. After
 *     Add, focus now moves to the stepper's "+" (named "Add one more {dish}"); after the last unit is
 *     removed, it returns to that dish's "Add".
 *  2. THE ANNOUNCEMENT. The basket link itself was the live region, holding the visible badge AND the
 *     sr-only count, so a screen reader said "1 1 item in basket" and never said what was added. There
 *     is now ONE basket live region, outside the link, saying "{Dish} added. {n} item(s) in basket." /
 *     "{Dish} removed. Basket is empty." with the count said once.
 *
 * Keyboard-driven (focus + Enter) through the REAL CartProvider and the REAL StorefrontNav, because the
 * announcement crosses from the provider to the header through the `jtoye:cart-updated` broadcast.
 */
describe("Storefront Add/Remove keeps focus and announces the dish (UXT-083)", () => {
  const lassi: PublicProduct = {
    ...product,
    id: "p-lassi",
    title: "Mango Lassi",
    description: "Sweet mango yoghurt drink.",
    category: "Drinks",
    pricePennies: 350,
  }
  const lassiDetail: ShopDetail = { ...detail, products: { Drinks: [lassi] } }

  /** Every element that is a live region on the page, with its normalised text. */
  function liveRegions(): { el: Element; text: string }[] {
    return Array.from(document.querySelectorAll("[aria-live], [role='status']")).map((el) => ({
      el,
      text: (el.textContent || "").replace(/\s+/g, " ").trim(),
    }))
  }
  /** The basket live region(s): the regions whose text speaks about the basket. */
  const basketRegions = () => liveRegions().filter((r) => /basket/i.test(r.text))

  async function renderShopWithHeader() {
    await act(async () => {
      render(
        <>
          <StorefrontNav />
          <CartProvider shopSlug={SLUG}>
            <ShopDetailClient slug={SLUG} initial={lassiDetail} />
          </CartProvider>
        </>
      )
    })
  }

  beforeEach(() => {
    mockGet.mockReset()
    mockGet.mockResolvedValue({ data: { content: [], totalPages: 0, totalElements: 0 } })
  })
  afterEach(() => {
    localStorage.clear()
  })

  it("Enter on Add moves focus to the stepper's '+', named 'Add one more Mango Lassi'", async () => {
    const user = userEvent.setup()
    await renderShopWithHeader()
    const add = await screen.findByRole("button", { name: "Add Mango Lassi to basket" })
    add.focus()
    await user.keyboard("{Enter}")

    const plus = await screen.findByRole("button", { name: "Add one more Mango Lassi" })
    expect(document.activeElement).toBe(plus)
  })

  it("announces 'Mango Lassi added. 1 item in basket.' in ONE region, the count said once", async () => {
    const user = userEvent.setup()
    await renderShopWithHeader()
    const add = await screen.findByRole("button", { name: "Add Mango Lassi to basket" })
    add.focus()
    await user.keyboard("{Enter}")

    await screen.findByRole("button", { name: "Add one more Mango Lassi" })
    const regions = basketRegions()
    // The fail direction this exists for: the old link-as-live-region read "1 1 item in basket".
    expect(regions.map((r) => r.text)).toEqual(["Mango Lassi added. 1 item in basket."])
  })

  it("pluralises on the next add and keeps focus on '+'", async () => {
    const user = userEvent.setup()
    await renderShopWithHeader()
    const add = await screen.findByRole("button", { name: "Add Mango Lassi to basket" })
    add.focus()
    await user.keyboard("{Enter}")
    await screen.findByRole("button", { name: "Add one more Mango Lassi" })
    await user.keyboard("{Enter}")

    expect(basketRegions().map((r) => r.text)).toEqual(["Mango Lassi added. 2 items in basket."])
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Add one more Mango Lassi" }))
  })

  it("removing the last unit returns focus to Add and announces 'Mango Lassi removed. Basket is empty.'", async () => {
    const user = userEvent.setup()
    await renderShopWithHeader()
    const add = await screen.findByRole("button", { name: "Add Mango Lassi to basket" })
    add.focus()
    await user.keyboard("{Enter}")
    const minus = await screen.findByRole("button", { name: "Remove Mango Lassi from basket" })
    minus.focus()
    await user.keyboard("{Enter}")

    const addAgain = await screen.findByRole("button", { name: "Add Mango Lassi to basket" })
    expect(document.activeElement).toBe(addAgain)
    expect(basketRegions().map((r) => r.text)).toEqual(["Mango Lassi removed. Basket is empty."])
  })

  it("CONTROL: loading a page with a basket already in it announces nothing", async () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        shopSlug: SLUG,
        owner: null,
        items: [{ productId: "p-lassi", title: "Mango Lassi", pricePennies: 350, quantity: 2, imageUrl: null, category: "Drinks" }],
      })
    )
    await renderShopWithHeader()
    await screen.findByRole("button", { name: "Add one more Mango Lassi" })

    // The basket link still says how many items there are, once, in its NAME (not as a live region).
    expect(screen.getByRole("link", { name: "2 items in basket" })).toBeInTheDocument()
    expect(basketRegions().map((r) => r.text)).toEqual([])
  })
})

/**
 * Phase 37-30 (UI-SPEC § D1 "Quantity stepper"; D-20; RWO-042): the storefront steppers stop at the
 * SERVER's `maxLineQuantity` with a hint, so a customer learns the per-line limit where they set
 * the quantity rather than from a 422 at checkout.
 *
 * `PublicShopDto.maxLineQuantity` (37-26) is the one source: no literal lives in the client. When
 * the shop payload carries it, "+" on the dish card, in the dish modal and in the basket drawer
 * disables at it with the 14px hint "Max {n} per order" wired by aria-describedby; when it is absent
 * (an older backend), there is no client limit and the server's 422 is the only gate.
 *
 * Driven through the real ShopDetailClient + CartProvider (the product-card-quantity-a11y harness),
 * with the drawer mounted in the same provider the way the slug layout mounts it.
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import { CartProvider } from "@/components/storefront/cart-provider"
import { CartDrawer } from "@/components/storefront/cart-drawer"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

// Radix Dialog / Sheet poke jsdom-missing APIs when they mount.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
beforeAll(() => {
  ;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {})
  Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false)
})

const SLUG = "brixton-village-grill"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

const baseShop: PublicShop = {
  slug: SLUG,
  name: "Brixton Village Grill",
  description: null,
  address: null,
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
  dietaryTags: null,
  preparationTimeMinutes: null,
  featured: false,
  inStock: true,
}

function detailFor(shop: PublicShop): ShopDetail {
  return { shop, products: { Mains: [product] }, reviews: [], reviewCount: 0, avgRating: 0, announcements: [] }
}

function seed(quantity: number) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      shopSlug: SLUG,
      owner: null,
      items: [{ productId: "p1", title: "Peri Peri Chicken", pricePennies: 850, quantity, imageUrl: null, category: "Mains" }],
    })
  )
}

async function renderShop(shop: PublicShop) {
  await act(async () => {
    render(
      <CartProvider shopSlug={SLUG}>
        <ShopDetailClient slug={SLUG} initial={detailFor(shop)} />
        <CartDrawer />
      </CartProvider>
    )
  })
}

// 37-52 (UI-SPEC F3): the dish card's "+" is named "Add one more {dish}"; since the 37-F review (F9,
// WCAG 3.2.4) the drawer's and the basket page's "+" carry the same name.
const cardPlus = () => screen.getByRole("button", { name: "Add one more Peri Peri Chicken" })

beforeEach(() => {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) =>
    Promise.resolve({
      data: String(url).endsWith("/products") ? { Mains: [product] } : { content: [], totalPages: 0, totalElements: 0 },
    })
  )
})

afterEach(() => {
  localStorage.clear()
})

describe("Storefront steppers stop at the server's maxLineQuantity (UI-SPEC § D1)", () => {
  it("dish card: '+' disables at the limit with the hint 'Max {n} per order' wired by aria-describedby; below it there is no hint", async () => {
    seed(2)
    await renderShop({ ...baseShop, maxLineQuantity: 3 })

    const plus = await screen.findByRole("button", { name: "Add one more Peri Peri Chicken" })
    expect(plus).toBeEnabled()
    expect(screen.queryByText("Max 3 per order")).toBeNull()

    fireEvent.click(plus)
    // At the limit: disabled, and the hint is the button's description.
    expect(cardPlus()).toBeDisabled()
    const hint = screen.getByText("Max 3 per order")
    expect(hint).toHaveClass("text-sm")
    expect(cardPlus()).toHaveAttribute("aria-describedby", hint.id)
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) as string).items[0].quantity).toBe(3)

    // A disabled "+" cannot add: the quantity stays at the limit.
    fireEvent.click(cardPlus())
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) as string).items[0].quantity).toBe(3)
  })

  it("absent maxLineQuantity (older backend): no client limit, no hint, '+' stays enabled at 10", async () => {
    seed(10)
    await renderShop(baseShop)

    const plus = await screen.findByRole("button", { name: "Add one more Peri Peri Chicken" })
    expect(plus).toBeEnabled()
    expect(screen.queryByText(/Max \d+ per order/)).toBeNull()
    fireEvent.click(plus)
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) as string).items[0].quantity).toBe(11)
  })

  it("dish modal: '+ one more' disables at the limit with the same hint", async () => {
    seed(3)
    await renderShop({ ...baseShop, maxLineQuantity: 3 })
    await screen.findByRole("button", { name: "Add one more Peri Peri Chicken" })

    fireEvent.click(screen.getByRole("button", { name: "View details for Peri Peri Chicken" }))
    const dialog = await screen.findByRole("dialog")
    const more = within(dialog).getByRole("button", { name: "Add one more Peri Peri Chicken to cart" })
    expect(more).toBeDisabled()
    const hint = within(dialog).getByText("Max 3 per order")
    expect(more).toHaveAttribute("aria-describedby", hint.id)
  })

  it("basket drawer: 'Add one more {dish}' disables at the limit with the same hint", async () => {
    seed(3)
    await renderShop({ ...baseShop, maxLineQuantity: 3 })
    await screen.findByRole("button", { name: "Add one more Peri Peri Chicken" })

    act(() => {
      window.dispatchEvent(new CustomEvent("jtoye:cart-open"))
    })
    const drawer = await screen.findByRole("dialog")
    const plus = within(drawer).getByRole("button", { name: "Add one more Peri Peri Chicken" })
    expect(plus).toBeDisabled()
    const hint = within(drawer).getByText("Max 3 per order")
    expect(plus).toHaveAttribute("aria-describedby", hint.id)
  })
})

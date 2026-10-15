/**
 * 37-F review F9 (WCAG 3.2.4 Consistent Identification): the basket stepper is ONE function on three
 * surfaces — the dish card on the menu, the basket drawer, and the basket page — so its controls
 * carry ONE name pattern, and the name says which dish:
 *
 *   "+"                       "Add one more {dish}"       (37-52's card name, UI-SPEC F3)
 *   "−" above one             "Decrease quantity of {dish}"
 *   "−" at one (removes it)   "Remove {dish} from basket"
 *
 * Before the fix the same "+" was "Add one more X" on the card, "Increase quantity of X" on the
 * basket page and a bare "Increase quantity" in the drawer (whose "−" was likewise a bare
 * "Decrease quantity" / "Remove item").
 */
import { Suspense } from "react"
import { act, render, screen, within } from "@testing-library/react"
import { CartProvider } from "@/components/storefront/cart-provider"
import { CartDrawer } from "@/components/storefront/cart-drawer"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import CartPage from "@/app/shop/[slug]/cart/page"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args), post: jest.fn() },
}))

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

const SLUG = "stepper-names-shop"
const DISH = "Peri Peri Chicken"

const shop: PublicShop = {
  slug: SLUG,
  name: "Stepper Names Grill",
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
  title: DISH,
  description: null,
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

const detail: ShopDetail = { shop, products: { Mains: [product] }, reviews: [], reviewCount: 0, avgRating: 0, announcements: [] }

function seed(quantity: number) {
  localStorage.setItem(
    `jtoye-cart-${SLUG}`,
    JSON.stringify({
      shopSlug: SLUG,
      owner: null,
      items: [{ productId: "p1", title: DISH, pricePennies: 850, quantity, imageUrl: null, category: "Mains" }],
    })
  )
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

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

async function renderMenu() {
  await act(async () => {
    render(
      <CartProvider shopSlug={SLUG}>
        <ShopDetailClient slug={SLUG} initial={detail} />
        <CartDrawer />
      </CartProvider>
    )
  })
}

async function openDrawer() {
  act(() => {
    window.dispatchEvent(new CustomEvent("jtoye:cart-open"))
  })
  return screen.findByRole("dialog")
}

describe("the basket stepper is named the same way on the card, the drawer and the basket page", () => {
  it("quantity 2: card and drawer both say 'Add one more {dish}' and 'Decrease quantity of {dish}'", async () => {
    seed(2)
    await renderMenu()
    // The card first (the open drawer hides the page behind it from the accessibility tree).
    expect(await screen.findByRole("button", { name: `Add one more ${DISH}` })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: `Decrease quantity of ${DISH}` })).toBeInTheDocument()

    const drawer = await openDrawer()
    expect(within(drawer).getByRole("button", { name: `Add one more ${DISH}` })).toBeInTheDocument()
    expect(within(drawer).getByRole("button", { name: `Decrease quantity of ${DISH}` })).toBeInTheDocument()
  })

  it("quantity 1: the drawer's '−' says 'Remove {dish} from basket', as the card's does", async () => {
    seed(1)
    await renderMenu()
    expect(await screen.findByRole("button", { name: `Remove ${DISH} from basket` })).toBeInTheDocument()
    const drawer = await openDrawer()
    expect(within(drawer).getByRole("button", { name: `Remove ${DISH} from basket` })).toBeInTheDocument()
  })

  it("the basket page uses the same names", async () => {
    seed(2)
    render(
      <Suspense fallback={<div>loading</div>}>
        <CartProvider shopSlug={SLUG}>
          <CartPage params={resolvedThenable({ slug: SLUG })} />
        </CartProvider>
      </Suspense>
    )
    expect(await screen.findByRole("button", { name: `Add one more ${DISH}` })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: `Decrease quantity of ${DISH}` })).toBeInTheDocument()
  })
})

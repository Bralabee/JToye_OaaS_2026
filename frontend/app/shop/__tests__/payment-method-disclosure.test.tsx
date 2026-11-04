/**
 * #829 (UXT-060, persona close-out 2026-11-04): a cash-only shop was revealed as cash-only only at
 * the bottom of checkout, after the basket was built, and its Place order button carried a card icon.
 *
 * The payment method is now stated where the customer chooses: on the shop card, on the shop's own
 * page, on the basket, and at the top of checkout (that last one in the checkout test file). Every
 * surface reads ONE helper (`paymentMethodFor`) from `PublicShop.acceptsCardPayments`, the server's
 * answer (`PaymentService.isConfigured()`, the gate createGuestOrder itself uses), and says nothing
 * when the server did not say (an older backend): an absent flag is NOT "cash".
 *
 * Absences are paired with a presence on the same surface in the same file, so a matcher that cannot
 * find the chip cannot pass an "absent" arm.
 */

import { Suspense } from "react"
import { act, render, screen, within } from "@testing-library/react"

import { paymentMethodFor } from "@/components/storefront/payment-method-note"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import { ShopDiscoveryClient } from "@/app/shop/shop-discovery-client"
import CartPage from "@/app/shop/[slug]/cart/page"
import { CartProvider } from "@/components/storefront/cart-provider"
import publicApiClient from "@/lib/public-api-client"
import type { PublicProduct, PublicShop, ShopDetail } from "@/types/storefront"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedGet = publicApiClient.get as jest.Mock

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

const CASH: PublicShop = { ...BASE, acceptsCardPayments: false }
const CARD: PublicShop = { ...BASE, acceptsCardPayments: true }

describe("paymentMethodFor", () => {
  it("cash: names the method and, when the shop takes one type only, that type", () => {
    expect(paymentMethodFor(CASH)).toMatchObject({ kind: "cash", label: "Cash on collection or delivery" })
    expect(paymentMethodFor({ ...CASH, offersDelivery: false })).toMatchObject({
      kind: "cash",
      label: "Cash on collection",
    })
    expect(paymentMethodFor({ ...CASH, offersCollection: false })).toMatchObject({
      kind: "cash",
      label: "Cash on delivery",
    })
  })

  it("cash: the customer's choice at checkout narrows the label to that type", () => {
    expect(paymentMethodFor(CASH, "COLLECTION")?.label).toBe("Cash on collection")
    expect(paymentMethodFor(CASH, "DELIVERY")?.label).toBe("Cash on delivery")
  })

  it("card: 'Card payment', whatever the fulfilment type", () => {
    expect(paymentMethodFor(CARD)).toMatchObject({ kind: "card", label: "Card payment" })
    expect(paymentMethodFor(CARD, "COLLECTION")?.label).toBe("Card payment")
  })

  it("an older backend that sent no flag, or no shop at all, says nothing: absent is not cash", () => {
    expect(paymentMethodFor(BASE)).toBeNull()
    expect(paymentMethodFor(null)).toBeNull()
    expect(paymentMethodFor(undefined)).toBeNull()
  })
})

// ---- the shop card ----------------------------------------------------------------------------

function page(content: PublicShop[]) {
  return { content, totalElements: content.length, totalPages: 1, size: 12, number: 0, first: true, last: true }
}

function renderCard(shop: PublicShop) {
  render(
    <ShopDiscoveryClient
      initial={page([shop])}
      initialQuery=""
      initialInterpretation={{ kind: "text" }}
    />
  )
  return screen.getByRole("article")
}

describe("the shop card states how you pay", () => {
  it("a cash shop's card says Cash on collection or delivery, with the banknote icon", () => {
    const article = renderCard(CASH)
    const chip = within(article).getByTestId("payment-method-chip")
    expect(chip).toHaveTextContent("Cash on collection or delivery")
    expect(chip.querySelector("svg.lucide-banknote")).not.toBeNull()
    expect(chip.querySelector("svg.lucide-credit-card")).toBeNull()
  })

  it("a card shop's card says Card payment, with the card icon", () => {
    const chip = within(renderCard(CARD)).getByTestId("payment-method-chip")
    expect(chip).toHaveTextContent("Card payment")
    expect(chip.querySelector("svg.lucide-credit-card")).not.toBeNull()
  })

  it("CONTROL: a card from an older backend (no flag) carries no payment chip", () => {
    const article = renderCard(BASE)
    // The card rendered (it has its name), so the absence below is not an empty render.
    expect(within(article).getByText("Brixton Village Grill")).toBeInTheDocument()
    expect(within(article).queryByTestId("payment-method-chip")).toBeNull()
  })

  it("a cash, collection-only card says 'Cash on collection' and still mentions no delivery", () => {
    const article = renderCard({ ...CASH, offersDelivery: false })
    expect(within(article).getByTestId("payment-method-chip")).toHaveTextContent(/^Cash on collection$/)
    expect(article.textContent).not.toMatch(/delivery/i)
  })
})

// ---- the shop's own page ----------------------------------------------------------------------

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

jest.mock("@/components/storefront/cart-provider", () => {
  const actual = jest.requireActual("@/components/storefront/cart-provider")
  return {
    ...actual,
    useCart: jest.fn((...args: unknown[]) => {
      // The real provider backs the basket page below; the shop page only needs an empty cart.
      void args
      return mockUseCart()
    }),
  }
})
let mockUseCart: () => unknown = () => ({})

function detail(shop: PublicShop): ShopDetail {
  return {
    shop,
    products: { Mains: [PRODUCT] },
    reviews: [],
    reviewCount: 0,
    avgRating: 0,
    announcements: [],
  }
}

async function renderShopPage(shop: PublicShop) {
  mockUseCart = () => ({
    items: [],
    addItem: jest.fn(),
    removeItem: jest.fn(),
    updateQuantity: jest.fn(),
    clearCart: jest.fn(),
    itemCount: 0,
    totalPennies: 0,
    shopSlug: SLUG,
  })
  mockedGet.mockReset()
  await act(async () => {
    render(<ShopDetailClient slug={SLUG} initial={detail(shop)} />)
  })
  expect(screen.getByRole("heading", { level: 1, name: "Brixton Village Grill" })).toBeInTheDocument()
}

describe("the shop's page states how you pay, in the details bar above the menu", () => {
  it("cash: Cash on collection or delivery", async () => {
    await renderShopPage(CASH)
    const chip = screen.getByTestId("payment-method-chip")
    expect(chip).toHaveTextContent("Cash on collection or delivery")
    expect(chip.querySelector("svg.lucide-banknote")).not.toBeNull()
  })

  it("card: Card payment", async () => {
    await renderShopPage(CARD)
    expect(screen.getByTestId("payment-method-chip")).toHaveTextContent("Card payment")
  })

  it("CONTROL: no flag from the server, no chip", async () => {
    await renderShopPage(BASE)
    expect(screen.queryByTestId("payment-method-chip")).toBeNull()
  })
})

// ---- the basket -------------------------------------------------------------------------------

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function seedBasket() {
  localStorage.setItem(
    `jtoye-cart-${SLUG}`,
    JSON.stringify({
      shopSlug: SLUG,
      items: [
        { productId: "p1", title: "Jollof Rice", pricePennies: 899, quantity: 1, imageUrl: null, category: "Mains" },
      ],
    })
  )
}

describe("the basket states how you pay, before Proceed to checkout", () => {
  beforeEach(() => {
    // The basket page uses the real provider.
    const actual = jest.requireActual("@/components/storefront/cart-provider")
    mockUseCart = () => actual.useCart()
  })
  afterEach(() => localStorage.clear())

  async function renderBasket(shop: PublicShop) {
    seedBasket()
    mockedGet.mockReset()
    mockedGet.mockImplementation((url: string) =>
      Promise.resolve({ data: url.endsWith("/products") ? { Mains: [{ id: "p1", allergenMask: 0 }] } : shop })
    )
    render(
      <Suspense fallback={<div>loading</div>}>
        <CartProvider shopSlug={SLUG}>
          <CartPage params={resolvedThenable({ slug: SLUG })} />
        </CartProvider>
      </Suspense>
    )
    expect(await screen.findByText("Jollof Rice")).toBeInTheDocument()
  }

  it("cash: the basket says Cash on collection or delivery and that nothing is charged online", async () => {
    await renderBasket(CASH)
    const note = await screen.findByTestId("payment-method-note")
    expect(note).toHaveTextContent("Cash on collection or delivery")
    expect(note).toHaveTextContent(/nothing is charged online/i)
  })

  it("card: the basket says Card payment", async () => {
    await renderBasket(CARD)
    expect(await screen.findByTestId("payment-method-note")).toHaveTextContent("Card payment")
  })

  it("CONTROL: the basket renders no payment note when the shop did not say", async () => {
    await renderBasket(BASE)
    // Wait for the shop to have loaded (the delivery line is drawn from it) before asserting absence.
    expect(await screen.findByText(/^Delivery$/)).toBeInTheDocument()
    expect(screen.queryByTestId("payment-method-note")).toBeNull()
  })
})

/**
 * 37-52 (UI-SPEC F6, UXT-080 basket and shop-header half; 37-51 did the trackers). At a 360px phone with
 * the text size raised to 130%, the persona's basket item names and the storefront's shop name were
 * cut to "Jollof Rice with Fri…" by `truncate` (overflow hidden + nowrap + ellipsis): the customer
 * could not read what was in their basket or which shop they were in. They now WRAP, breaking a long
 * word rather than overflowing (`break-words`).
 *
 * jsdom loads no Tailwind and computes no layout, so this pins the CLASS contract on the real
 * elements (named by role, so a renamed heading cannot satisfy it by disappearing). The computed
 * `text-overflow` and `scrollWidth <= clientWidth` at 360px / 130% are measured in a real browser by
 * e2e/a11y-storefront.spec.ts.
 */

import { Suspense } from "react"
import { act, render, screen, within } from "@testing-library/react"
import CartPage from "@/app/shop/[slug]/cart/page"
import { CartDrawer } from "@/components/storefront/cart-drawer"
import { CartProvider } from "@/components/storefront/cart-provider"
import { ShopDetailClient } from "@/app/shop/[slug]/shop-detail-client"
import publicApiClient from "@/lib/public-api-client"
import type { PublicShop, ShopDetail } from "@/types/storefront"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))
const mockedGet = publicApiClient.get as jest.Mock

const SLUG = "brixton-village-grill"
const LONG_ITEM = "Jollof Rice with Fried Plantain, Peppered Chicken and Coleslaw"
const LONG_SHOP = "Brixton Village Grill and Caribbean Kitchen at the Market"

/** The classes that clip a line to one row with an ellipsis, or clamp it. */
const CLIPPING = /(^|\s)(truncate|whitespace-nowrap|text-ellipsis|line-clamp-\d+)(\s|$)/

function expectWraps(el: HTMLElement) {
  expect(el.className).not.toMatch(CLIPPING)
  expect(el.className).toMatch(/(^|\s)break-words(\s|$)/)
}

function seed() {
  localStorage.setItem(
    `jtoye-cart-${SLUG}`,
    JSON.stringify({
      shopSlug: SLUG,
      owner: null,
      items: [{ productId: "p1", title: LONG_ITEM, pricePennies: 1250, quantity: 1, imageUrl: null, category: "Mains" }],
    })
  )
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

const shop: PublicShop = {
  slug: SLUG,
  name: LONG_SHOP,
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
  minimumOrderPennies: null,
  deliveryFeePennies: null,
  freeDeliveryThresholdPennies: null,
  tags: null,
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedGet.mockResolvedValue({ data: { content: [], totalPages: 0, totalElements: 0 } })
})
afterEach(() => {
  localStorage.clear()
})

describe("Large text wraps instead of truncating (UXT-080, F6)", () => {
  it("basket page: the item name wraps", async () => {
    seed()
    render(
      <Suspense fallback={<div>loading</div>}>
        <CartProvider shopSlug={SLUG}>
          <CartPage params={resolvedThenable({ slug: SLUG })} />
        </CartProvider>
      </Suspense>
    )
    expectWraps(await screen.findByRole("heading", { name: LONG_ITEM }))
  })

  it("basket drawer: the item name wraps", async () => {
    seed()
    await act(async () => {
      render(
        <CartProvider shopSlug={SLUG}>
          <CartDrawer />
        </CartProvider>
      )
    })
    act(() => {
      window.dispatchEvent(new CustomEvent("jtoye:cart-open"))
    })
    const drawer = await screen.findByRole("dialog")
    expectWraps(within(drawer).getByRole("heading", { name: LONG_ITEM }))
  })

  it("storefront header: the shop name <h1> wraps", async () => {
    const detail: ShopDetail = { shop, products: {}, reviews: [], reviewCount: 0, avgRating: 0, announcements: [] }
    await act(async () => {
      render(
        <CartProvider shopSlug={SLUG}>
          <ShopDetailClient slug={SLUG} initial={detail} />
        </CartProvider>
      )
    })
    expectWraps(screen.getByRole("heading", { level: 1, name: LONG_SHOP }))
  })
})

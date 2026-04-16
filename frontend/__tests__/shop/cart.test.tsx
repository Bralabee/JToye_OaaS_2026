/**
 * Tests for the standalone shop cart page (STFR-04).
 *
 * Covers:
 *  - Empty cart state when localStorage has no seeded items
 *  - Populated cart state when localStorage was hydrated before mount
 *
 * The CartProvider loads from localStorage in a useEffect, so tests MUST
 * seed localStorage BEFORE rendering the provider (see 10-RESEARCH pitfall 4).
 */

import { Suspense } from "react"
import { render, screen } from "@testing-library/react"
import CartPage from "@/app/shop/[slug]/cart/page"
import { CartProvider } from "@/components/storefront/cart-provider"

const SLUG = "jollof-express"
const STORAGE_KEY = `jtoye-cart-${SLUG}`

// React's use() hook needs a thenable. A plain Promise resolves via microtask
// which jest+jsdom does not always flush between render and the first query,
// so we hand React a pre-resolved thenable it can unwrap synchronously.
function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function renderCart() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <CartProvider shopSlug={SLUG}>
        <CartPage params={resolvedThenable({ slug: SLUG })} />
      </CartProvider>
    </Suspense>
  )
}

describe("Shop cart page (/shop/[slug]/cart)", () => {
  afterEach(() => {
    localStorage.clear()
  })

  it("renders empty state when no items", async () => {
    renderCart()

    expect(
      await screen.findByText(/your basket is empty/i)
    ).toBeTruthy()
    expect(
      screen.getByText(/add items from the menu to get started/i)
    ).toBeTruthy()

    // Back-to-menu link points to the shop detail page
    const backLinks = screen.getAllByRole("link", { name: /back to menu/i })
    expect(backLinks.length).toBeGreaterThan(0)
    expect(backLinks[0].getAttribute("href")).toBe(`/shop/${SLUG}`)
  })

  it("renders items when cart has contents", async () => {
    // Seed localStorage BEFORE rendering — CartProvider hydrates in useEffect
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        shopSlug: SLUG,
        items: [
          {
            productId: "p1",
            title: "Jollof Rice",
            pricePennies: 899,
            quantity: 2,
            imageUrl: null,
            category: "Mains",
          },
        ],
      })
    )

    renderCart()

    // Item title appears after hydration tick
    expect(await screen.findByText("Jollof Rice")).toBeTruthy()

    // 2 × £8.99 = £17.98 should be rendered (as line total and order total)
    const totals = screen.getAllByText(/£17\.98/)
    expect(totals.length).toBeGreaterThan(0)

    // Proceed-to-checkout link exists and points at the checkout route
    const checkoutLink = screen.getByRole("link", { name: /proceed to checkout/i })
    expect(checkoutLink.getAttribute("href")).toBe(`/shop/${SLUG}/checkout`)
  })
})

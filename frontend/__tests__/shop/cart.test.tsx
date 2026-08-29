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

/**
 * A11Y-7: the +/- quantity steppers carried NO accessible name at all — a
 * screen-reader user landed on an unlabelled "button" and had no way to tell
 * which line item, or which direction, it acted on. The fix names the OBJECT
 * (the dish), not just the action, and the minus button additionally states
 * removal rather than "decrease" the moment decreasing WOULD remove the line
 * (quantity === 1) — a silent removal on the last unit is a worse surprise
 * than an unlabelled button.
 */
describe("Shop cart page — quantity stepper accessible names (A11Y-7)", () => {
  afterEach(() => {
    localStorage.clear()
  })

  it("names both steppers after the item when quantity > 1", async () => {
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
    await screen.findByText("Jollof Rice")

    expect(
      screen.getByRole("button", { name: "Decrease quantity of Jollof Rice" })
    ).toBeInTheDocument()
    expect(
      screen.getByRole("button", { name: "Increase quantity of Jollof Rice" })
    ).toBeInTheDocument()
  })

  it("names the minus button as a removal once quantity is 1 (decreasing further removes the line)", async () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        shopSlug: SLUG,
        items: [
          {
            productId: "p1",
            title: "Jollof Rice",
            pricePennies: 899,
            quantity: 1,
            imageUrl: null,
            category: "Mains",
          },
        ],
      })
    )

    renderCart()
    await screen.findByText("Jollof Rice")

    expect(
      screen.getByRole("button", { name: "Remove Jollof Rice from basket" })
    ).toBeInTheDocument()
    // The generic, un-named form must not also be present under a different name.
    expect(
      screen.queryByRole("button", { name: "Decrease quantity of Jollof Rice" })
    ).not.toBeInTheDocument()
  })
})

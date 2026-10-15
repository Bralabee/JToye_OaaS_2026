/**
 * 37-F review F3: the basket broadcast names a dish ONLY for an edit this tab's customer made.
 *
 * The provider's `jtoye:cart-updated` broadcast carries `edit` (the one dish that changed), which
 * the storefront header speaks as "{dish} added. {n} items in basket." Every persist used to diff,
 * so writes the customer did not make in THIS tab were announced as theirs:
 *   - a sibling tab's add, re-read here through the `storage` event;
 *   - the account-switch / sign-out wipe (CARTS_CLEARED_EVENT);
 *   - the server's basket-changed rewrite (applyServerChanges).
 * Each of those still updates the count (the broadcast still fires); it only names no dish.
 */
import { act, render } from "@testing-library/react"
import { CartProvider, useCart, type CartItem } from "../cart-provider"
import { CARTS_CLEARED_EVENT } from "@/lib/cart-identity"

const SLUG = "own-edit-shop"
const KEY = `jtoye-cart-${SLUG}`

function line(productId: string, title: string, quantity: number): CartItem {
  return { productId, title, pricePennies: 500, quantity, imageUrl: null, category: null }
}

type Api = ReturnType<typeof useCart>
let api: Api | null = null
function Capture() {
  api = useCart()
  return null
}

let broadcasts: Array<{ itemCount: number; edit: unknown }> = []
function onUpdated(e: Event) {
  const d = (e as CustomEvent<{ slug: string; itemCount: number; edit: unknown }>).detail
  if (d.slug === SLUG) broadcasts.push({ itemCount: d.itemCount, edit: d.edit })
}

function renderProvider() {
  render(
    <CartProvider shopSlug={SLUG}>
      <Capture />
    </CartProvider>
  )
}

beforeEach(() => {
  localStorage.clear()
  broadcasts = []
  api = null
  window.addEventListener("jtoye:cart-updated", onUpdated)
})

afterEach(() => {
  window.removeEventListener("jtoye:cart-updated", onUpdated)
})

describe("only this tab's own add / remove / update names a dish", () => {
  it("control: this tab's own add IS announced", () => {
    localStorage.setItem(KEY, JSON.stringify({ shopSlug: SLUG, owner: null, items: [line("p1", "Jollof Rice", 1)] }))
    renderProvider()
    broadcasts = []
    act(() => api!.addItem({ productId: "p2", title: "Mango Lassi", pricePennies: 500, imageUrl: null, category: null }))
    expect(broadcasts.at(-1)).toEqual({ itemCount: 2, edit: { title: "Mango Lassi", kind: "added" } })
  })

  it("a sibling tab's add, re-read here through `storage`, updates the count but names no dish", () => {
    localStorage.setItem(KEY, JSON.stringify({ shopSlug: SLUG, owner: null, items: [line("p1", "Jollof Rice", 1)] }))
    renderProvider()
    broadcasts = []

    const sibling = JSON.stringify({
      shopSlug: SLUG,
      owner: null,
      items: [line("p1", "Jollof Rice", 1), line("p2", "Mango Lassi", 1)],
    })
    localStorage.setItem(KEY, sibling)
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: KEY, newValue: sibling }))
    })

    expect(broadcasts.length).toBeGreaterThan(0)
    expect(broadcasts.at(-1)!.itemCount).toBe(2)
    expect(broadcasts.at(-1)!.edit).toBeNull()
  })

  it("the account-switch / sign-out wipe names no dish", () => {
    localStorage.setItem(KEY, JSON.stringify({ shopSlug: SLUG, owner: null, items: [line("p1", "Jollof Rice", 1)] }))
    renderProvider()
    broadcasts = []

    act(() => {
      window.dispatchEvent(new Event(CARTS_CLEARED_EVENT))
    })

    expect(broadcasts.length).toBeGreaterThan(0)
    expect(broadcasts.at(-1)!.itemCount).toBe(0)
    expect(broadcasts.at(-1)!.edit).toBeNull()
  })

  it("the server's basket-changed rewrite names no dish", () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({ shopSlug: SLUG, owner: null, items: [line("p1", "Jollof Rice", 1), line("p2", "Mango Lassi", 2)] })
    )
    renderProvider()
    broadcasts = []

    act(() => api!.applyServerChanges([{ kind: "UNAVAILABLE", productId: "p2" }]))

    expect(broadcasts.length).toBeGreaterThan(0)
    expect(broadcasts.at(-1)!.itemCount).toBe(1)
    expect(broadcasts.at(-1)!.edit).toBeNull()
  })

  it("an own edit after a non-own write is still announced (the flag is per write, not latched off)", () => {
    localStorage.setItem(KEY, JSON.stringify({ shopSlug: SLUG, owner: null, items: [line("p1", "Jollof Rice", 2)] }))
    renderProvider()
    act(() => {
      window.dispatchEvent(new Event(CARTS_CLEARED_EVENT))
    })
    broadcasts = []
    act(() => api!.addItem({ productId: "p1", title: "Jollof Rice", pricePennies: 500, imageUrl: null, category: null }))
    expect(broadcasts.at(-1)).toEqual({ itemCount: 1, edit: { title: "Jollof Rice", kind: "added" } })
  })
})

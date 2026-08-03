import { render, act } from "@testing-library/react"
import { CartProvider, useCart, type CartItem } from "../cart-provider"
import { CUSTOMER_ID_KEY, cartStorageKey } from "@/lib/cart-identity"

/**
 * Issue #459 — the basket is bound to whoever wrote it.
 *
 * These cover the READ/WRITE half of the fix (the provider). The other half is
 * the sign-out clear in lib/customer-auth.ts, and the flow that ties the two
 * together — sign out as A, sign in as B, look at the basket — cannot be
 * exercised here at all: an identity change is a full Keycloak round trip. That
 * lives in frontend/e2e/cart-identity-boundary.verify.mjs, in a real browser.
 */

const SLUG = "test-shop"
const A = "sub-customer-a"
const B = "sub-customer-b"

function item(productId: string): CartItem {
  return {
    productId,
    title: productId,
    pricePennies: 500,
    quantity: 1,
    imageUrl: null,
    category: null,
  }
}

function seed(owner: string | null | undefined, ids: string[]) {
  const payload: Record<string, unknown> = { shopSlug: SLUG, items: ids.map(item) }
  // `undefined` writes the LEGACY shape — the field genuinely absent, which is
  // what every basket stored before this change looks like.
  if (owner !== undefined) payload.owner = owner
  localStorage.setItem(cartStorageKey(SLUG), JSON.stringify(payload))
}

function signedInAs(sub: string | null) {
  if (sub === null) localStorage.removeItem(CUSTOMER_ID_KEY)
  else localStorage.setItem(CUSTOMER_ID_KEY, sub)
}

function storedPayload() {
  const raw = localStorage.getItem(cartStorageKey(SLUG))
  return raw ? (JSON.parse(raw) as { owner?: string | null; items: CartItem[] }) : null
}

function Probe() {
  const { items } = useCart()
  return <div data-testid="ids">{items.map((i) => i.productId).join(",")}</div>
}

function renderCart() {
  return render(
    <CartProvider shopSlug={SLUG}>
      <Probe />
    </CartProvider>
  )
}

describe("CartProvider identity boundary", () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it("does NOT show a basket owned by a different signed-in customer", () => {
    seed(A, ["a-burger"])
    signedInAs(B)
    const { getByTestId } = renderCart()
    expect(getByTestId("ids").textContent).toBe("")
  })

  it("DOES carry an anonymous basket forward into a sign-in, and adopts it", () => {
    // The behaviour a naive "clear on any auth event" fix destroys.
    seed(null, ["anon-suya"])
    signedInAs(A)
    const { getByTestId } = renderCart()
    expect(getByTestId("ids").textContent).toBe("anon-suya")
    // Adopted: the next write stamps A, so B cannot inherit it later.
    expect(storedPayload()?.owner).toBe(A)
  })

  it("DOES carry a LEGACY (owner-less) basket forward, so the deploy empties nobody", () => {
    seed(undefined, ["legacy-jollof"])
    signedInAs(A)
    const { getByTestId } = renderCart()
    expect(getByTestId("ids").textContent).toBe("legacy-jollof")
  })

  it("shows the same customer their own basket", () => {
    seed(A, ["a-plantain"])
    signedInAs(A)
    const { getByTestId } = renderCart()
    expect(getByTestId("ids").textContent).toBe("a-plantain")
  })

  it("still shows an owned basket while nobody is signed in", () => {
    // A token that lapsed mid-shop must not cost the shopper their basket.
    seed(A, ["a-egusi"])
    signedInAs(null)
    const { getByTestId } = renderCart()
    expect(getByTestId("ids").textContent).toBe("a-egusi")
  })

  it("stamps the writer's identity on every persist", () => {
    signedInAs(A)
    const captured: Array<ReturnType<typeof useCart>> = []
    function Capture() {
      captured.push(useCart())
      return null
    }
    render(
      <CartProvider shopSlug={SLUG}>
        <Capture />
      </CartProvider>
    )
    act(() => {
      captured[captured.length - 1].addItem({
        productId: "p-1",
        title: "Suya",
        pricePennies: 1200,
        imageUrl: null,
        category: null,
      })
    })
    expect(storedPayload()?.owner).toBe(A)
    expect(storedPayload()?.items.map((i) => i.productId)).toEqual(["p-1"])
  })

  it("writes owner: null while anonymous, so the next person can still adopt it", () => {
    signedInAs(null)
    const captured: Array<ReturnType<typeof useCart>> = []
    function Capture() {
      captured.push(useCart())
      return null
    }
    render(
      <CartProvider shopSlug={SLUG}>
        <Capture />
      </CartProvider>
    )
    act(() => {
      captured[captured.length - 1].addItem({
        productId: "p-2",
        title: "Puff puff",
        pricePennies: 300,
        imageUrl: null,
        category: null,
      })
    })
    expect(storedPayload()?.owner).toBeNull()
  })

  it("drops a basket a SIBLING TAB signed out from under it", () => {
    // A shared browser is often a shared browser with two tabs. `storage` fires
    // only in the other documents, so this can never disturb a single-tab
    // sign-in — but without it this tab keeps rendering the previous
    // customer's basket and re-persists it on the next change.
    seed(A, ["a-suya"])
    signedInAs(A)
    const { getByTestId } = renderCart()
    expect(getByTestId("ids").textContent).toBe("a-suya")

    act(() => {
      // What customerLogout() does in the other tab.
      localStorage.removeItem(cartStorageKey(SLUG))
      localStorage.removeItem(CUSTOMER_ID_KEY)
      window.dispatchEvent(new StorageEvent("storage", { key: CUSTOMER_ID_KEY }))
    })

    expect(getByTestId("ids").textContent).toBe("")
  })

  it("does not thrash when a sibling tab's storage event carries no change", () => {
    // Each re-read builds a fresh array; persisting it would wake the sibling
    // tab, which would persist back, forever. The provider must bail out when
    // the content is identical.
    seed(A, ["a-suya"])
    signedInAs(A)
    const { getByTestId } = renderCart()

    const realSetItem = Storage.prototype.setItem
    const writes: string[] = []
    Storage.prototype.setItem = function (k: string, v: string) {
      if (k === cartStorageKey(SLUG)) writes.push(v)
      return realSetItem.call(this, k, v)
    }
    try {
      act(() => {
        window.dispatchEvent(new StorageEvent("storage", { key: cartStorageKey(SLUG) }))
      })
      expect(writes).toHaveLength(0)
      expect(getByTestId("ids").textContent).toBe("a-suya")
    } finally {
      Storage.prototype.setItem = realSetItem
    }
  })
})

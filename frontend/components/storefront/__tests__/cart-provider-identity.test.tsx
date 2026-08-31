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
    // R-16 — THE DEFECT, STATED. Showing the basket is only half the promise.
    // The very same render re-persists it, and until this line existed nothing
    // looked at what was written: the stamp came back `null`, and the next
    // person to sign in on this browser adopted A's basket. Asserting the
    // RENDER while the WRITE silently downgraded the payload is exactly how
    // this shipped past its own regression suite.
    expect(storedPayload()?.owner).toBe(A)
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
    // NOTE (R-16): the rule this asserts is "a FRESH basket written anonymously
    // is stamped null" — storage is empty here, so there is nothing to preserve.
    // It is NOT the broader "an anonymous write always stamps null", which is
    // false and was the defect. The two cases are separated explicitly in the
    // `owner preservation` describe below; this block is kept as-is so the
    // guest carry-forward it protects is never traded away.
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

  /**
   * R-16 (2026-08-31 customer-surface audit) — the anonymous DOWNGRADE.
   *
   * The access cookie lives 300s and the session probe runs on mount, on a 1s
   * poll and on focus, so `jtoye-customer-id` disappears routinely under a
   * shopper who never went anywhere. Before this, the very next shop-page render
   * re-persisted their basket stamped `owner: null` — and a null owner is
   * adoptable by ANYONE (`canAdoptCart`). A newly registered customer inherited
   * the previous account's basket and checked out with it under their own name.
   *
   * The rule is `current ?? prior ?? null`: a write ADDS or CONFIRMS ownership,
   * and only an explicit sign-out REMOVES it. These four blocks pin all three
   * directions of that rule, including the two that must NOT change.
   */
  describe("owner preservation across a lapsed session", () => {
    function renderWithCapture() {
      const captured: Array<ReturnType<typeof useCart>> = []
      function Capture() {
        const ctx = useCart()
        captured.push(ctx)
        return <div data-testid="ids">{ctx.items.map((i) => i.productId).join(",")}</div>
      }
      const utils = render(
        <CartProvider shopSlug={SLUG}>
          <Capture />
        </CartProvider>
      )
      return { ...utils, latest: () => captured[captured.length - 1] }
    }

    it("stamps a FRESH guest basket null, because there is nothing to preserve", () => {
      // THE CONTROL that stops the fix being over-applied. If preservation were
      // written as "never write null", a genuine guest basket would inherit a
      // stale owner and the anonymous -> sign-in carry-forward would break.
      // Passes BEFORE the fix as well as after; that is the point.
      signedInAs(null)
      const { latest } = renderWithCapture()
      act(() => {
        latest().addItem({
          productId: "guest-akara",
          title: "Akara",
          pricePennies: 300,
          imageUrl: null,
          category: null,
        })
      })
      expect(storedPayload()?.owner).toBeNull()
      expect(storedPayload()?.items.map((i) => i.productId)).toEqual(["guest-akara"])
    })

    it("does NOT erase an existing owner when the writer is anonymous", () => {
      // The defect itself. A lapsed session is overwhelmingly more often the
      // SAME person mid-shop than a new one, and sign-out is the only moment
      // where "a different person may be next" is unambiguous.
      seed(A, ["a-egusi"])
      signedInAs(null)
      const { latest } = renderWithCapture()
      act(() => {
        latest().addItem({
          productId: "a-moi-moi",
          title: "Moi moi",
          pricePennies: 450,
          imageUrl: null,
          category: null,
        })
      })
      expect(storedPayload()?.owner).toBe(A)
      // ...and the item added while anonymous is genuinely theirs, not dropped.
      expect(storedPayload()?.items.map((i) => i.productId)).toEqual([
        "a-egusi",
        "a-moi-moi",
      ])
    })

    it("hands the slot to B when B writes over a basket A owned", () => {
      // The branch that is easy to get wrong. Preservation must not become
      // "the first owner wins forever": a stamp still reading A here would mean
      // every item B adds is stored under A's name — the same leak, reversed.
      seed(A, ["a-suya"])
      signedInAs(B)
      const { getByTestId } = renderCart()
      expect(getByTestId("ids").textContent).toBe("")
      expect(storedPayload()?.owner).toBe(B)
    })

    it("adopts a legacy owner-less payload for the signed-in customer", () => {
      // `undefined` (field absent) must normalise to "no prior owner", never to
      // a preserved `undefined` that would leave the payload unstamped forever.
      seed(undefined, ["legacy-jollof"])
      signedInAs(A)
      const { getByTestId } = renderCart()
      expect(getByTestId("ids").textContent).toBe("legacy-jollof")
      expect(storedPayload()?.owner).toBe(A)
    })
  })
})

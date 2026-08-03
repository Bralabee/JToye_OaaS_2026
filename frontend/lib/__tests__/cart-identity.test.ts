import {
  CART_KEY_PREFIX,
  CUSTOMER_ID_KEY,
  canAdoptCart,
  cartStorageKey,
  clearStoredCarts,
  forgetCustomerId,
  getCurrentCustomerId,
  rememberCustomerId,
} from "@/lib/cart-identity"

/**
 * Issue #459 — the basket must not cross a CUSTOMER boundary, and must still
 * cross an anonymous -> signed-in one.
 *
 * The whole difficulty of this bug is that the obvious fix (clear the basket on
 * any auth event) satisfies the headline and breaks the product: a shopper who
 * fills a basket and THEN signs in expects to keep it. So the interesting cases
 * here are the ones where the answer is "yes, carry it" — they are what stops a
 * later tightening from quietly deleting live baskets.
 */
describe("canAdoptCart", () => {
  const A = "sub-a"
  const B = "sub-b"

  it("carries a basket built ANONYMOUSLY forward into a sign-in", () => {
    // The behaviour the product wants and the one a naive fix destroys.
    expect(canAdoptCart(null, A)).toBe(true)
  })

  it("treats a legacy payload with NO owner field as anonymous", () => {
    // Every basket stored before this shipped has no `owner`. Reading that as
    // "unknown, therefore reject" would empty live baskets on deploy.
    expect(canAdoptCart(undefined, A)).toBe(true)
  })

  it("keeps a basket for the SAME signed-in customer", () => {
    expect(canAdoptCart(A, A)).toBe(true)
  })

  it("REJECTS a basket owned by a different signed-in customer", () => {
    // The bug: A fills a basket, B signs in on the same browser.
    expect(canAdoptCart(A, B)).toBe(false)
  })

  it("keeps an owned basket readable while nobody is signed in", () => {
    // Deliberate, and the branch most likely to be 'tightened' by mistake. The
    // access cookie's life is the access token's (300s on this realm) and a
    // renewal can fail, so a shopper mid-order is routinely anonymous for a
    // moment. Rejecting here would delete their own basket on a token hiccup —
    // far more common than two customers sharing one browser, which the
    // sign-out clear covers instead.
    expect(canAdoptCart(A, null)).toBe(true)
  })

  it("never treats two blank identities as the same person", () => {
    // An empty-string id compares equal to another empty-string id, which would
    // silently re-open the carry-over. It must read as "no owner", not "match".
    expect(canAdoptCart("", B)).toBe(true)
    expect(canAdoptCart(A, "")).toBe(true)
  })
})

describe("the customer id marker", () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it("round-trips a subject id", () => {
    rememberCustomerId("sub-1")
    expect(getCurrentCustomerId()).toBe("sub-1")
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBe("sub-1")
  })

  it("ignores a blank or missing subject rather than storing an empty id", () => {
    rememberCustomerId("sub-1")
    rememberCustomerId("")
    rememberCustomerId(undefined)
    rememberCustomerId(null)
    // Still the real one — a blank write must not overwrite a known identity
    // with a value that would compare equal to the next blank one.
    expect(getCurrentCustomerId()).toBe("sub-1")
  })

  it("reads as anonymous once forgotten", () => {
    rememberCustomerId("sub-1")
    forgetCustomerId()
    expect(getCurrentCustomerId()).toBeNull()
  })
})

describe("clearStoredCarts", () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it("removes EVERY shop's basket, not just the one on screen", () => {
    // The basket that leaks is by definition one nobody is looking at, so a
    // clear scoped to the current slug would miss exactly the wrong key.
    for (const slug of ["shop-a", "shop-b", "shop-c", "shop-d", "shop-e"]) {
      localStorage.setItem(cartStorageKey(slug), JSON.stringify({ shopSlug: slug, items: [] }))
    }
    clearStoredCarts()
    const left = Object.keys(localStorage).filter((k) => k.startsWith(CART_KEY_PREFIX))
    // Five keys specifically: removing while walking the keyspace shifts every
    // later index down one and silently leaves half of them behind.
    expect(left).toEqual([])
  })

  it("leaves everything that is not a basket alone", () => {
    localStorage.setItem(cartStorageKey("shop-a"), JSON.stringify({ shopSlug: "shop-a", items: [] }))
    localStorage.setItem("jtoye-checkout-email-shop-a", "someone@example.com")
    localStorage.setItem("jtoye-orders", "[]")
    localStorage.setItem("unrelated", "keep me")

    clearStoredCarts()

    expect(localStorage.getItem(cartStorageKey("shop-a"))).toBeNull()
    // `jtoye-checkout-email-…` shares the `jtoye-` prefix but is NOT a basket;
    // a prefix match one segment too short would take it with them.
    expect(localStorage.getItem("jtoye-checkout-email-shop-a")).toBe("someone@example.com")
    expect(localStorage.getItem("jtoye-orders")).toBe("[]")
    expect(localStorage.getItem("unrelated")).toBe("keep me")
  })

  it("is a no-op when nothing is stored", () => {
    expect(() => clearStoredCarts()).not.toThrow()
    expect(localStorage.length).toBe(0)
  })
})

/**
 * 37-52 (UI-SPEC F3, UXT-083): the basket sentence and the one-dish diff behind it.
 *
 * The integrated keyboard journey is `__tests__/shop/product-card-quantity-a11y.test.tsx`; this file
 * pins the two rules that journey cannot reach cheaply: a write that changes SEVERAL lines (a
 * sign-out clearing the basket, the server's basket-changed rewrite) names no dish, and a first
 * persist (hydration) is not an edit.
 */

import { basketAnnouncement, basketCountPhrase, basketEditOf, type BasketLine } from "@/lib/basket-announcement"

const lassi = (quantity: number): BasketLine => ({ productId: "p-lassi", title: "Mango Lassi", quantity })
const jollof = (quantity: number): BasketLine => ({ productId: "p-jollof", title: "Jollof Rice", quantity })

describe("basketEditOf", () => {
  it("is null on the first persist (hydration is not something the customer did)", () => {
    expect(basketEditOf(undefined, [lassi(2)])).toBeNull()
  })

  it("names the one dish that went in", () => {
    expect(basketEditOf([], [lassi(1)])).toEqual({ title: "Mango Lassi", kind: "added" })
    expect(basketEditOf([lassi(1), jollof(1)], [lassi(2), jollof(1)])).toEqual({ title: "Mango Lassi", kind: "added" })
  })

  it("names the one dish that came out, including the line that left the basket", () => {
    expect(basketEditOf([lassi(2)], [lassi(1)])).toEqual({ title: "Mango Lassi", kind: "removed" })
    expect(basketEditOf([lassi(1), jollof(1)], [jollof(1)])).toEqual({ title: "Mango Lassi", kind: "removed" })
  })

  it("is null when nothing changed", () => {
    expect(basketEditOf([lassi(1)], [lassi(1)])).toBeNull()
  })

  it("is null when several lines changed at once (a cleared basket names no dish)", () => {
    expect(basketEditOf([lassi(1), jollof(2)], [])).toBeNull()
    expect(basketEditOf([lassi(1), jollof(2)], [lassi(2), jollof(1)])).toBeNull()
  })
})

describe("basketAnnouncement", () => {
  it("says the dish, then the count once, singular at one", () => {
    expect(basketAnnouncement({ title: "Mango Lassi", kind: "added" }, 1)).toBe("Mango Lassi added. 1 item in basket.")
  })

  it("pluralises above one and at a removal that leaves some", () => {
    expect(basketAnnouncement({ title: "Mango Lassi", kind: "added" }, 2)).toBe("Mango Lassi added. 2 items in basket.")
    expect(basketAnnouncement({ title: "Mango Lassi", kind: "removed" }, 3)).toBe("Mango Lassi removed. 3 items in basket.")
  })

  it("says the basket is empty instead of '0 items in basket'", () => {
    expect(basketAnnouncement({ title: "Mango Lassi", kind: "removed" }, 0)).toBe("Mango Lassi removed. Basket is empty.")
  })

  it("keeps the link's count phrase in the drawer's idiom", () => {
    expect(basketCountPhrase(0)).toBe("0 items in basket")
    expect(basketCountPhrase(1)).toBe("1 item in basket")
  })
})

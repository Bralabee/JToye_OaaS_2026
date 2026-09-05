/**
 * FE-5 (QA council 20260902-134741) — `jtoye-customer-last-signin` lives in the
 * identity keyspace with the basket owner stamp, and follows the same rule:
 * writes ADD or CONFIRM, only an explicit sign-out REMOVES. The lapse-side
 * helpers (`forgetCustomerId`, `clearStoredCarts` on their own) must leave it
 * alone — that independence is what makes it a truthful "somebody signed in here
 * and nobody signed out since" signal.
 */

import {
  CUSTOMER_LAST_SIGNIN_KEY,
  clearStoredCarts,
  forgetCustomerId,
  forgetLastSignIn,
  getLastSignIn,
  hasRememberedSignIn,
  rememberCustomerId,
  rememberLastSignIn,
} from "@/lib/cart-identity"

beforeEach(() => {
  localStorage.clear()
})

describe("jtoye-customer-last-signin", () => {
  it("is absent on a fresh browser", () => {
    expect(getLastSignIn()).toBeNull()
    expect(hasRememberedSignIn()).toBe(false)
  })

  it("remembers the subject of a confirmed sign-in", () => {
    rememberLastSignIn("sub-a")
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBe("sub-a")
    expect(getLastSignIn()).toBe("sub-a")
    expect(hasRememberedSignIn()).toBe(true)
  })

  it.each([
    { sub: "" as string | null | undefined, label: "blank" },
    { sub: null as string | null | undefined, label: "null" },
    { sub: undefined as string | null | undefined, label: "undefined" },
  ])("ignores a $label subject rather than writing an empty stamp", ({ sub }) => {
    rememberLastSignIn("sub-a")
    rememberLastSignIn(sub)
    expect(getLastSignIn()).toBe("sub-a")
    localStorage.clear()
    rememberLastSignIn(sub)
    expect(getLastSignIn()).toBeNull()
  })

  it("is CONFIRMED (overwritten) by a different confirmed sign-in", () => {
    rememberLastSignIn("sub-a")
    rememberLastSignIn("sub-b")
    expect(getLastSignIn()).toBe("sub-b")
  })

  it("survives the LAPSE-side teardown: forgetCustomerId + clearStoredCarts leave it alone", () => {
    rememberCustomerId("sub-a")
    rememberLastSignIn("sub-a")
    forgetCustomerId()
    clearStoredCarts()
    expect(getLastSignIn()).toBe("sub-a")
  })

  it("is removed by forgetLastSignIn — the explicit sign-out's call", () => {
    rememberLastSignIn("sub-a")
    forgetLastSignIn()
    expect(getLastSignIn()).toBeNull()
    expect(hasRememberedSignIn()).toBe(false)
  })

  it("reads as absent, without throwing, when storage is unavailable (private mode)", () => {
    const spy = jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("SecurityError")
    })
    try {
      expect(hasRememberedSignIn()).toBe(false)
      expect(getLastSignIn()).toBeNull()
    } finally {
      spy.mockRestore()
    }
  })
})

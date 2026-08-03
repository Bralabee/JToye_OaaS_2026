import { customerLogout, getCustomerSession, isLoggedIn } from "@/lib/customer-auth"
import { CUSTOMER_ID_KEY, cartStorageKey } from "@/lib/cart-identity"

/**
 * Issue #459 — the two teardown paths must NOT be the same teardown.
 *
 * A sign-OUT means "this device may be about to change hands", so the baskets
 * go with the session. A session simply going away — an access cookie that
 * aged out, a refresh the IdP declined — means nothing of the sort, and it
 * happens routinely to a shopper mid-order. Clearing baskets there would delete
 * live baskets on a token hiccup.
 *
 * Both paths run through the same file and differ by one call, which is exactly
 * the kind of distinction a later edit collapses by accident. These tests exist
 * to make that collapse loud.
 */

const SLUG = "peckham-jollof-co"
const OTHER = "brixton-village-grill"

function seedBaskets() {
  localStorage.setItem(
    cartStorageKey(SLUG),
    JSON.stringify({ shopSlug: SLUG, owner: "sub-a", items: [{ productId: "p1", quantity: 1 }] })
  )
  localStorage.setItem(
    cartStorageKey(OTHER),
    JSON.stringify({ shopSlug: OTHER, owner: "sub-a", items: [{ productId: "p2", quantity: 2 }] })
  )
}

function seedSignedIn() {
  localStorage.setItem("jtoye-customer-logged-in", "true")
  localStorage.setItem("jtoye-customer-expires-at", String(Math.floor(Date.now() / 1000) + 3600))
  localStorage.setItem(CUSTOMER_ID_KEY, "sub-a")
}

beforeEach(() => {
  localStorage.clear()
})

// customerLogout() ends by assigning window.location.href. jsdom cannot
// navigate and reports that through the virtual console rather than throwing,
// so it is silenced here instead of stubbing `location` (which jsdom now
// defines non-configurably). The teardown under test has already run by then.
const realConsoleError = console.error
beforeAll(() => {
  console.error = (...args: unknown[]) => {
    if (String(args[0]).includes("Not implemented: navigation")) return
    realConsoleError(...args)
  }
})
afterAll(() => {
  console.error = realConsoleError
})

describe("customerLogout", () => {
  it("removes every stored basket alongside the session marker", async () => {
    seedSignedIn()
    seedBaskets()

    global.fetch = jest.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes("logout-url")) {
        return { ok: true, json: async () => ({ url: "http://kc.example/logout" }) } as Response
      }
      return { ok: true, json: async () => ({}) } as Response
    }) as unknown as typeof fetch

    await customerLogout()

    expect(localStorage.getItem(cartStorageKey(SLUG))).toBeNull()
    expect(localStorage.getItem(cartStorageKey(OTHER))).toBeNull()
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBeNull()
    expect(isLoggedIn()).toBe(false)
  })

  it("still clears the baskets when the server round-trip fails", async () => {
    // A failed sign-out is precisely the shared device that keeps the previous
    // customer's basket, so the local teardown cannot be conditional on it.
    seedSignedIn()
    seedBaskets()

    global.fetch = jest.fn(async () => {
      throw new Error("network down")
    }) as unknown as typeof fetch

    await customerLogout()

    expect(localStorage.getItem(cartStorageKey(SLUG))).toBeNull()
    expect(localStorage.getItem(cartStorageKey(OTHER))).toBeNull()
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBeNull()
  })
})

describe("a session that simply went away", () => {
  it("clears the marker and identity but KEEPS the baskets", async () => {
    seedSignedIn()
    seedBaskets()

    global.fetch = jest.fn(async () =>
      ({ ok: true, json: async () => ({ authenticated: false }) }) as Response
    ) as unknown as typeof fetch

    const session = await getCustomerSession()

    expect(session).toBeNull()
    expect(isLoggedIn()).toBe(false)
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBeNull()
    // The point of the whole test file: these survive.
    expect(localStorage.getItem(cartStorageKey(SLUG))).not.toBeNull()
    expect(localStorage.getItem(cartStorageKey(OTHER))).not.toBeNull()
  })

  it("records WHO is signed in when the session comes back", async () => {
    global.fetch = jest.fn(async () =>
      ({
        ok: true,
        json: async () => ({
          authenticated: true,
          expiresAt: Math.floor(Date.now() / 1000) + 300,
          profile: { sub: "sub-renewed", email: "a@example.com", name: "A", emailVerified: true },
        }),
      }) as Response
    ) as unknown as typeof fetch

    await getCustomerSession()

    // Without this the renewal path would leave the identity blank and every
    // later basket write would look anonymous — adoptable by the next person.
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBe("sub-renewed")
  })
})

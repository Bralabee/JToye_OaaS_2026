/**
 * #840 — an EXPLICIT sign-out removes every personal key this site stores in
 * the browser; a session LAPSE removes none of them.
 *
 * WHY THROUGH THE TRANSITION AND BY STORED CONTENT (client-persisted identity
 * lifecycle contract). R-16 shipped because a test checked what the page
 * rendered while the same render erased the ownership stamp on disk. So this
 * file never asks the UI anything. It drives sign-in -> order -> lapse ->
 * explicit sign-out through the real `lib/customer-auth.ts` paths and reads
 * localStorage / sessionStorage back at every step, comparing the STORED
 * values, not their presence.
 *
 * THE KEYS ARE WRITTEN HERE AS LITERALS ON PURPOSE. They are the assertion. If
 * this file imported them from the registry it would check the registry against
 * itself, and a key dropped from the registry would silently drop out of the
 * test too.
 *
 * What was measured before the fix: `jtoye-guest-orders` (order number + the
 * email it was placed with) and every `jtoye-checkout-email-<shop>` survived an
 * explicit sign-out, so on a shared device the next person found the previous
 * customer's orders and email address.
 */
import { customerIdpSignOut, customerLogout, getCustomerSession } from "@/lib/customer-auth"
import { saveLocalOrder } from "@/lib/order-history"

const SHOP = "rosies"
const OTHER_SHOP = "peckham-jollof-co"
const ORDER_NUMBER = "ORD-ROSIES-0001"
const EMAIL = "sub-a@example.test"

// Exact names / per-shop names, as a reader would find them in devtools.
const GUEST_ORDERS = "jtoye-guest-orders"
const CHECKOUT_EMAIL = `jtoye-checkout-email-${SHOP}`
const CHECKOUT_EMAIL_OTHER = `jtoye-checkout-email-${OTHER_SHOP}`
const LAST_SIGNIN = "jtoye-customer-last-signin"
const CART = `jtoye-cart-${SHOP}`
const CUSTOMER_ID = "jtoye-customer-id"
const MARKER = "jtoye-customer-logged-in"
const EXPIRES = "jtoye-customer-expires-at"
const TRACK_EMAIL = "jtoye-track-email"

/** Not personal: must SURVIVE a sign-out, or the teardown is a blanket clear(). */
const NON_PERSONAL = {
  "jtoye-cookie-notice-ack": "1.0",
  theme: "dark",
  shopContext: "all",
  "kds-muted": "true",
} as const

const nowPlus = (s: number) => Math.floor(Date.now() / 1000) + s
const authenticated = (sub: string) => ({
  authenticated: true,
  expiresAt: nowPlus(3600),
  profile: { sub, email: `${sub}@example.test`, name: sub, emailVerified: true },
})
const LAPSED = { authenticated: false }

function sessionFetch(answer: unknown) {
  global.fetch = jest.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes("/api/customer-auth/session")) {
      return { ok: true, json: async () => answer } as Response
    }
    if (url.includes("/api/customer-auth/logout-url")) {
      return { ok: true, json: async () => ({ url: `${window.location.origin}/shop` }) } as Response
    }
    return { ok: true, json: async () => ({ ok: true, idp: "skipped" }) } as Response
  }) as unknown as typeof fetch
}

function storedOrderNumbers(): string[] | null {
  const raw = localStorage.getItem(GUEST_ORDERS)
  if (raw === null) return null
  return (JSON.parse(raw) as Array<{ orderNumber: string }>).map((o) => o.orderNumber)
}

function storedBasketOwner(): string | null | undefined {
  const raw = localStorage.getItem(CART)
  if (raw === null) return undefined
  return (JSON.parse(raw) as { owner?: string | null }).owner
}

/** Every key in an area, read by index — the same walk a devtools panel does. */
function keysOf(storage: Storage): string[] {
  const out: string[] = []
  for (let i = 0; i < storage.length; i++) {
    const k = storage.key(i)
    if (k !== null) out.push(k)
  }
  return out.sort()
}

// Both sign-outs end by assigning window.location.href; jsdom reports the
// refused navigation through the virtual console. Silenced narrowly, same
// idiom as customer-auth-signout-clears-carts.test.ts.
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

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

/** sign in as sub-a, then place an order at SHOP the way checkout does. */
async function signInAndOrder() {
  sessionFetch(authenticated("sub-a"))
  expect(await getCustomerSession()).not.toBeNull()

  saveLocalOrder({
    orderNumber: ORDER_NUMBER,
    email: EMAIL,
    shopSlug: SHOP,
    placedAt: "2026-10-06T09:00:00.000Z",
  })
  // checkout/page.tsx writes this after a successful order.
  localStorage.setItem(CHECKOUT_EMAIL, EMAIL)
  localStorage.setItem(CHECKOUT_EMAIL_OTHER, "older@example.test")
  localStorage.setItem(
    CART,
    JSON.stringify({ shopSlug: SHOP, owner: "sub-a", items: [{ productId: "p1", quantity: 2 }] })
  )
  sessionStorage.setItem(TRACK_EMAIL, EMAIL)
  for (const [k, v] of Object.entries(NON_PERSONAL)) localStorage.setItem(k, v)
}

describe("#840 — personal browser storage THROUGH sign-in -> order -> lapse -> explicit sign-out", () => {
  it("a LAPSE keeps every personal value unchanged; an explicit sign-out removes every one", async () => {
    await signInAndOrder()

    // ---- after sign-in + order: the values are really there (non-vacuity) ----
    expect(storedOrderNumbers()).toEqual([ORDER_NUMBER])
    expect(localStorage.getItem(CHECKOUT_EMAIL)).toBe(EMAIL)
    expect(localStorage.getItem(LAST_SIGNIN)).toBe("sub-a")
    expect(storedBasketOwner()).toBe("sub-a")
    const ordersBeforeLapse = localStorage.getItem(GUEST_ORDERS)
    expect(ordersBeforeLapse).toContain(EMAIL)

    // ---- the session LAPSES: nobody pressed Sign out ----
    sessionFetch(LAPSED)
    expect(await getCustomerSession()).toBeNull()
    // Control: the lapse really happened — the live-session keys are gone.
    expect(localStorage.getItem(MARKER)).toBeNull()
    expect(localStorage.getItem(CUSTOMER_ID)).toBeNull()
    // THE lapse assertions, by stored content: the SAME values, not just keys.
    expect(localStorage.getItem(GUEST_ORDERS)).toBe(ordersBeforeLapse)
    expect(storedOrderNumbers()).toEqual([ORDER_NUMBER])
    expect(localStorage.getItem(CHECKOUT_EMAIL)).toBe(EMAIL)
    expect(localStorage.getItem(LAST_SIGNIN)).toBe("sub-a")
    expect(storedBasketOwner()).toBe("sub-a")

    // ---- an EXPLICIT sign-out ----
    await customerLogout()
    expect(localStorage.getItem(GUEST_ORDERS)).toBeNull()
    expect(localStorage.getItem(CHECKOUT_EMAIL)).toBeNull()
    expect(localStorage.getItem(CHECKOUT_EMAIL_OTHER)).toBeNull()
    expect(localStorage.getItem(LAST_SIGNIN)).toBeNull()
    expect(localStorage.getItem(CART)).toBeNull()
    expect(localStorage.getItem(CUSTOMER_ID)).toBeNull()
    expect(localStorage.getItem(MARKER)).toBeNull()
    expect(localStorage.getItem(EXPIRES)).toBeNull()
    expect(sessionStorage.getItem(TRACK_EMAIL)).toBeNull()

    // Control: the teardown is targeted, not a blanket clear().
    for (const [k, v] of Object.entries(NON_PERSONAL)) {
      expect(localStorage.getItem(k)).toBe(v)
    }
    expect(keysOf(localStorage)).toEqual(Object.keys(NON_PERSONAL).sort())
  })

  it("the 'Not you? Sign out' path from the ANONYMOUS state removes them too", async () => {
    await signInAndOrder()
    sessionFetch(LAPSED)
    expect(await getCustomerSession()).toBeNull()
    expect(storedOrderNumbers()).toEqual([ORDER_NUMBER])

    await customerIdpSignOut("/shop/signin")

    expect(localStorage.getItem(GUEST_ORDERS)).toBeNull()
    expect(localStorage.getItem(CHECKOUT_EMAIL)).toBeNull()
    expect(localStorage.getItem(LAST_SIGNIN)).toBeNull()
    expect(sessionStorage.getItem(TRACK_EMAIL)).toBeNull()
    expect(localStorage.getItem("theme")).toBe("dark")
  })

  it("still removes them when the server round-trip fails", async () => {
    // A failed sign-out is precisely the shared device that keeps the previous
    // customer's data, so the local teardown cannot be conditional on it.
    await signInAndOrder()
    global.fetch = jest.fn(async () => {
      throw new Error("network down")
    }) as unknown as typeof fetch

    await customerLogout()

    expect(localStorage.getItem(GUEST_ORDERS)).toBeNull()
    expect(localStorage.getItem(CHECKOUT_EMAIL)).toBeNull()
    expect(localStorage.getItem(LAST_SIGNIN)).toBeNull()
  })
})

describe("#840 edges", () => {
  it("empty: an explicit sign-out with nothing stored completes and leaves storage empty", async () => {
    sessionFetch(LAPSED)
    expect(keysOf(localStorage)).toEqual([])
    await expect(customerLogout()).resolves.toBeUndefined()
    expect(keysOf(localStorage)).toEqual([])
    expect(keysOf(sessionStorage)).toEqual([])
  })

  it("adjacency + ordering: every per-shop key is removed, however many and however interleaved", async () => {
    // Remove-while-iterating by index skips every other key; with this many
    // interleaved per-shop keys a skip would leave at least one behind.
    const shops = Array.from({ length: 12 }, (_, i) => `shop-${i}`)
    shops.forEach((s, i) => {
      localStorage.setItem(`jtoye-checkout-email-${s}`, `${s}@example.test`)
      if (i % 2 === 0) localStorage.setItem(`jtoye-cart-${s}`, JSON.stringify({ owner: "sub-a", items: [] }))
      localStorage.setItem(`unrelated-${s}`, "keep")
    })
    sessionFetch(LAPSED)

    await customerLogout()

    expect(keysOf(localStorage).filter((k) => k.startsWith("jtoye-checkout-email-"))).toEqual([])
    expect(keysOf(localStorage).filter((k) => k.startsWith("jtoye-cart-"))).toEqual([])
    // Control: an unrelated key that merely shares no prefix is untouched.
    expect(keysOf(localStorage)).toEqual(shops.map((s) => `unrelated-${s}`).sort())
  })
})

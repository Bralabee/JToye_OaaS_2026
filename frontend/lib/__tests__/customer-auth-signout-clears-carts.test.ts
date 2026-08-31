import {
  customerLogout,
  getCustomerSession,
  handleCallback,
  isLoggedIn,
  LOGOUT_FETCH_TIMEOUT_MS,
} from "@/lib/customer-auth"
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

/**
 * What a basket HOLDS, which is the thing that matters — never whether its key
 * exists.
 *
 * A re-created EMPTY `jtoye-cart-<slug>` key is LEGITIMATE after a correct
 * sign-out: `hooks/use-stored-state.ts`'s write effect persists the (now empty)
 * cart on its next run. A key-presence gate would therefore red on a correct
 * build, which is the worst kind of gate — one that trains people to weaken it.
 */
function basketItems(slug: string): unknown[] {
  const raw = localStorage.getItem(cartStorageKey(slug))
  if (raw === null) return []
  try {
    return (JSON.parse(raw) as { items?: unknown[] }).items ?? []
  } catch {
    return []
  }
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

  /**
   * R-04 (2026-08-31 customer-surface audit) — the arm the audit specifically
   * calls for, and the one the two above could not reach.
   *
   * Both existing arms settle: one resolves, one REJECTS. Neither describes the
   * network failure that actually happens on a phone leaving a wifi cell, which
   * is a request that simply never answers. Against the pre-fix code
   * `customerLogout()` awaited two un-timeouted fetches with the teardown on the
   * far side of both, so a stall was a SILENT NO-OP SIGN-OUT: session alive,
   * basket intact, still stamped with the departing customer's `sub`, and no
   * feedback at all. The fail direction here is a jest TIMEOUT — the call never
   * resolves — which is recorded rather than smoothed over.
   */
  it("clears the local state even when the round-trip NEVER SETTLES", async () => {
    seedSignedIn()
    seedBaskets()

    jest.useFakeTimers()
    try {
      global.fetch = jest.fn(
        () => new Promise<Response>(() => {})
      ) as unknown as typeof fetch

      const pending = customerLogout()
      // TWICE the budget plus slack: customerLogout makes two sequential
      // bounded calls, and the second one's timer is only scheduled once the
      // first has given up.
      await jest.advanceTimersByTimeAsync(LOGOUT_FETCH_TIMEOUT_MS * 2 + 10)
      await pending

      expect(isLoggedIn()).toBe(false)
      expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBeNull()
      // Asserted on ITEMS, not on key presence — see `basketItems`.
      expect(basketItems(SLUG)).toHaveLength(0)
      expect(basketItems(OTHER)).toHaveLength(0)
    } finally {
      jest.useRealTimers()
    }
  })

  it("CONTROL: the helper can still SEE a basket that was not cleared", async () => {
    // Without this, `basketItems() === []` would be satisfied by a helper that
    // is simply unable to read anything, and the never-settling arm above would
    // pass over a completely broken teardown.
    seedBaskets()
    expect(basketItems(SLUG)).toHaveLength(1)
    expect(basketItems(OTHER)).toHaveLength(1)
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

/**
 * R-16 (2026-08-31 customer-surface audit) — the ACCOUNT SWITCH backstop.
 *
 * Kept in THIS file rather than a new one on purpose: sign-out, session lapse
 * and account switch are one ownership-lifecycle story told by one module, and
 * splitting them across files is precisely how the distinction between "the
 * session went away" and "a different person is here" gets collapsed by a later
 * edit. The three cases sit side by side so the collapse is loud.
 *
 * This is a BACKSTOP, not the cure. On the reported repro it is vacuous on its
 * own: the anonymous downgrade has already nulled the basket's owner before any
 * sign-in reads it, and the recorded identity is gone too. The cure is the
 * provider-side preservation rule (lib/cart-identity.ts `resolveCartOwner`).
 * What this covers is the switch that happens while the marker survives.
 */
describe("signing in as a DIFFERENT customer", () => {
  function sessionResponse(profile: Record<string, unknown> | undefined) {
    global.fetch = jest.fn(async () =>
      ({
        ok: true,
        json: async () => ({
          authenticated: true,
          expiresAt: Math.floor(Date.now() / 1000) + 300,
          profile,
        }),
      }) as Response
    ) as unknown as typeof fetch
  }

  it("clears every basket when the incoming sub differs from the recorded one", async () => {
    seedSignedIn() // records sub-a
    seedBaskets() // both owned by sub-a
    sessionResponse({ sub: "sub-b", email: "b@example.com", name: "B", emailVerified: true })

    await getCustomerSession()

    // Asserted on ITEMS, never on key presence — see `basketItems`.
    expect(basketItems(SLUG)).toHaveLength(0)
    expect(basketItems(OTHER)).toHaveLength(0)
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBe("sub-b")
  })

  it("does NOT clear when the SAME customer's session is renewed", async () => {
    // CONTROL. A renewal is the common case and fires on a 1s poll; clearing
    // here would empty a live basket several times a minute.
    seedSignedIn()
    seedBaskets()
    sessionResponse({ sub: "sub-a", email: "a@example.com", name: "A", emailVerified: true })

    await getCustomerSession()

    expect(basketItems(SLUG)).toHaveLength(1)
    expect(basketItems(OTHER)).toHaveLength(1)
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBe("sub-a")
  })

  /**
   * WR-02, the path that CREATES the ambiguous state rather than reacting to
   * it. `handleCallback` built `sub: payload.sub ?? ""`, and
   * `rememberCustomerId("")` returns early on a falsy sub — so a sub-less ID
   * token established a LIVE cookie session with no recorded identity. Every
   * later cart write then took the "preserve the prior owner" branch and
   * stamped this customer's items with the previous one's id.
   *
   * Rejected the same way a bad nonce already is, and BEFORE the login POST, so
   * no session is established at all. Keycloak always issues `sub`, so this is
   * hardening — but the fix's correctness depended on that silently, and a
   * dependency you rely on should be enforced, not assumed.
   */
  it("rejects an id token with NO sub instead of establishing an unrecorded session", async () => {
    const NONCE = "nonce-abc"
    sessionStorage.setItem("jtoye-pkce-verifier", "verifier-abc")
    sessionStorage.setItem("jtoye-oauth-state", "state-abc")
    sessionStorage.setItem("jtoye-oauth-nonce", NONCE)

    // A well-formed id token that is valid in every way EXCEPT that it carries
    // no subject — so nothing but the sub check can reject it.
    const claims = { email: "nosub@example.com", name: "No Sub", nonce: NONCE }
    const payload = btoa(JSON.stringify(claims))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "")
    const idToken = `header.${payload}.signature`

    const calls: string[] = []
    global.fetch = jest.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url.includes("openid-connect/token")) {
        return {
          ok: true,
          json: async () => ({
            access_token: "at",
            refresh_token: "rt",
            id_token: idToken,
            expires_in: 300,
          }),
        } as Response
      }
      return { ok: true, json: async () => ({}) } as Response
    }) as unknown as typeof fetch

    const profile = await handleCallback("the-code", "state-abc")

    expect(profile).toBeNull()
    // No cookie session may be established: the login POST must never happen.
    expect(calls.some((u) => u.includes("/api/customer-auth/login"))).toBe(false)
    // ...and no marker, so nothing later reads this as "somebody is signed in".
    expect(isLoggedIn()).toBe(false)
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBeNull()
  })

  it("does NOT clear when the session carries NO sub", async () => {
    // T-R16-04, the fail-DESTRUCTIVE hazard. An unknown identity is not a
    // different person. A one-sided check (`previous !== sub`) is true for
    // `undefined` too, so it would destroy a live basket every time a session
    // response arrived without a profile — a self-inflicted denial of service
    // that would look like "the basket randomly empties itself".
    seedSignedIn()
    seedBaskets()
    sessionResponse(undefined)

    await getCustomerSession()

    expect(basketItems(SLUG)).toHaveLength(1)
    expect(basketItems(OTHER)).toHaveLength(1)
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBe("sub-a")
  })
})

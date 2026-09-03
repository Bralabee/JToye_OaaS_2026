/**
 * FE-5 (QA council 20260902-134741) — the `jtoye-customer-last-signin` stamp and
 * the explicit IdP sign-out it enables, tested THROUGH the identity transitions.
 *
 * WHY THROUGH THE TRANSITIONS AND BY STORED CONTENT. R-16 shipped because a test
 * checked what the page rendered while the very same render erased the ownership
 * stamp on disk. So this file never asks the UI anything: it drives sign-in as A
 * -> lapse -> explicit sign-out -> sign-in as B through the real
 * `lib/customer-auth.ts` paths and reads `localStorage` back at every step.
 *
 * THE LIFECYCLE UNDER TEST (the same shape as the basket owner stamp):
 *   - a confirmed sign-in (`setMarker` with a sub) WRITES or CONFIRMS it;
 *   - a LAPSE — `{ authenticated:false }`, a non-2xx, a sub-less renewal —
 *     leaves it ALONE. A lapsed session is not a new person;
 *   - ONLY an explicit sign-out (`customerLogout`, `customerIdpSignOut`) removes it.
 * The stamp is what lets the storefront honestly offer "Not you? Sign out" in the
 * anonymous state: Keycloak's SSO cookies live on the IdP host and cannot be
 * read from here, but "somebody signed in on this browser and nobody signed out
 * since" can.
 *
 * `customerIdpSignOut` is the explicit sign-out from the ANONYMOUS state. With no
 * id cookie, `/api/customer-auth/logout-url` can only return an app path, so it
 * builds the `client_id` + `post_logout_redirect_uri` end-session form itself —
 * the same client-side composition `customerLogin` / `customerRegister` already
 * use for the IdP — and routes the app half through `POST /api/customer-auth/logout`
 * (`lib/customer-idp-logout.ts` back-channel, best-effort; "skipped" when there is
 * no refresh token, which is the lapsed case). The front-channel navigation is
 * what actually ends A's SSO session there.
 */

import { customerIdpSignOut, customerLogout, getCustomerSession } from "@/lib/customer-auth"
import {
  CUSTOMER_ID_KEY,
  CUSTOMER_LAST_SIGNIN_KEY,
  CUSTOMER_MARKER_KEY,
  cartStorageKey,
  hasRememberedSignIn,
} from "@/lib/cart-identity"

const SLUG = "brixton-village-grill"
const KC_LOGOUT_PATH = "/realms/jtoye-customers/protocol/openid-connect/logout"

const nowPlus = (s: number) => Math.floor(Date.now() / 1000) + s
const authenticated = (sub: string) => ({
  authenticated: true,
  expiresAt: nowPlus(3600),
  profile: { sub, email: `${sub}@example.test`, name: sub, emailVerified: true },
})
const LAPSED = { authenticated: false }

type Call = { url: string; method: string }

/** A fetch that answers the session probe with `answer` and everything else with a bland 200. */
function sessionFetch(answer: unknown, calls: Call[] = [], opts: { logoutUrl?: string; ok?: boolean } = {}) {
  global.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, method: init?.method ?? "GET" })
    if (url.includes("/api/customer-auth/session")) {
      return { ok: opts.ok ?? true, json: async () => answer } as Response
    }
    if (url.includes("/api/customer-auth/logout-url")) {
      return { ok: true, json: async () => ({ url: opts.logoutUrl ?? `${window.location.origin}/shop` }) } as Response
    }
    return { ok: true, json: async () => ({ ok: true, idp: "skipped" }) } as Response
  }) as unknown as typeof fetch
}

function seedBasketOwnedBy(sub: string) {
  localStorage.setItem(
    cartStorageKey(SLUG),
    JSON.stringify({ shopSlug: SLUG, owner: sub, items: [{ productId: "p1", quantity: 1 }] })
  )
}
const storedOwner = () => {
  const raw = localStorage.getItem(cartStorageKey(SLUG))
  return raw ? (JSON.parse(raw) as { owner?: string | null }).owner : "<key absent>"
}

// Both sign-outs end by assigning window.location.href; jsdom reports the refused
// navigation through the virtual console. Silenced narrowly, same idiom as
// customer-auth-signout-clears-carts.test.ts.
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
})

describe("jtoye-customer-last-signin — the stored stamp THROUGH sign-in / lapse / sign-out / switch", () => {
  it("A signs in -> A; the session LAPSES -> still A; explicit sign-out -> gone; B signs in -> B", async () => {
    // A signs in (the renewal path; the OAuth callback reaches the same setMarker).
    sessionFetch(authenticated("sub-a"))
    expect(await getCustomerSession()).not.toBeNull()
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBe("sub-a")
    expect(hasRememberedSignIn()).toBe(true)
    seedBasketOwnedBy("sub-a")

    // The session LAPSES — no sign-out.
    sessionFetch(LAPSED)
    expect(await getCustomerSession()).toBeNull()
    // Controls: the lapse really happened — the live-session keys are gone…
    expect(localStorage.getItem(CUSTOMER_ID_KEY)).toBeNull()
    expect(localStorage.getItem(CUSTOMER_MARKER_KEY)).toBeNull()
    // …and THE assertion: a lapse is not a sign-out, so the stamp stands,
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBe("sub-a")
    expect(hasRememberedSignIn()).toBe(true)
    // and R-16 is untouched: the basket's owner stamp survives the lapse too.
    expect(storedOwner()).toBe("sub-a")

    // An EXPLICIT sign-out removes it, along with the baskets (#459).
    await customerLogout()
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBeNull()
    expect(hasRememberedSignIn()).toBe(false)
    expect(storedOwner()).toBe("<key absent>")

    // B signs in on the same browser -> the stamp is B's, not A's.
    sessionFetch(authenticated("sub-b"))
    expect(await getCustomerSession()).not.toBeNull()
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBe("sub-b")
  })

  it("a sub-less RENEWAL neither writes a blank stamp nor erases the existing one", async () => {
    sessionFetch(authenticated("sub-a"))
    await getCustomerSession()
    sessionFetch({ authenticated: true, expiresAt: nowPlus(3600), profile: { sub: "", email: "", name: "", emailVerified: false } })
    await getCustomerSession()
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBe("sub-a")
  })

  it("a blank sub on a FIRST sign-in is not an identity: nothing is written", async () => {
    const quiet = jest.spyOn(console, "warn").mockImplementation(() => {})
    try {
      sessionFetch({ authenticated: true, expiresAt: nowPlus(3600), profile: { sub: "", email: "", name: "", emailVerified: false } })
      await getCustomerSession()
      expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBeNull()
      expect(hasRememberedSignIn()).toBe(false)
    } finally {
      quiet.mockRestore()
    }
  })

  it("a server error on the session probe is a lapse, not a sign-out: the stamp survives", async () => {
    sessionFetch(authenticated("sub-a"))
    await getCustomerSession()
    sessionFetch({}, [], { ok: false })
    expect(await getCustomerSession()).toBeNull()
    expect(localStorage.getItem(CUSTOMER_MARKER_KEY)).toBeNull()
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBe("sub-a")
  })
})

describe("customerIdpSignOut — the explicit 'Not you? Sign out' from the anonymous state", () => {
  function lapsedState() {
    localStorage.setItem(CUSTOMER_LAST_SIGNIN_KEY, "sub-a")
    seedBasketOwnedBy("sub-a")
  }

  it("LAPSED (no id cookie): POSTs the app logout, tears down the sign-out state, and returns the client_id end-session URL back to /shop/signin", async () => {
    lapsedState()
    const calls: Call[] = []
    sessionFetch(LAPSED, calls)

    const destination = await customerIdpSignOut("/shop/signin")

    const u = new URL(destination)
    expect(u.host).toBe("localhost:8085")
    expect(u.pathname).toBe(KC_LOGOUT_PATH)
    expect(u.searchParams.get("client_id")).toBe("storefront-client")
    expect(u.searchParams.get("post_logout_redirect_uri")).toBe(`${window.location.origin}/shop/signin`)
    // No id token to hint with in this state — and none invented.
    expect(u.searchParams.get("id_token_hint")).toBeNull()

    // The app half went through the server route (and so through the
    // back-channel in lib/customer-idp-logout.ts, best-effort).
    expect(
      calls.some((c) => /\/api\/customer-auth\/logout$/.test(c.url) && c.method === "POST")
    ).toBe(true)

    // An EXPLICIT sign-out: the stamp AND the baskets go.
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBeNull()
    expect(localStorage.getItem(CUSTOMER_MARKER_KEY)).toBeNull()
    expect(storedOwner()).toBe("<key absent>")
  })

  it("when an id cookie STILL exists (logout-url answers with a Keycloak URL), uses that id_token_hint form verbatim", async () => {
    lapsedState()
    const kcUrl = `http://localhost:8085${KC_LOGOUT_PATH}?id_token_hint=ID&post_logout_redirect_uri=${encodeURIComponent(`${window.location.origin}/shop/signin`)}`
    sessionFetch(LAPSED, [], { logoutUrl: kcUrl })

    const destination = await customerIdpSignOut("/shop/signin")
    expect(destination).toBe(kcUrl)
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBeNull()
  })

  it("narrows a hostile returnTo to a same-origin path (the redirect can never leave this origin)", async () => {
    lapsedState()
    sessionFetch(LAPSED)
    const destination = await customerIdpSignOut("https://evil.example/steal")
    const plr = new URL(destination).searchParams.get("post_logout_redirect_uri") as string
    expect(plr.startsWith(window.location.origin)).toBe(true)
    expect(plr).not.toContain("evil.example")
  })

  it("a network that REJECTS still tears down and still returns the end-session URL — the sign-out never silently no-ops", async () => {
    lapsedState()
    global.fetch = jest.fn(async () => {
      throw new Error("network down")
    }) as unknown as typeof fetch

    const destination = await customerIdpSignOut("/shop/signin")
    expect(new URL(destination).pathname).toBe(KC_LOGOUT_PATH)
    expect(localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY)).toBeNull()
    expect(storedOwner()).toBe("<key absent>")
  })
})

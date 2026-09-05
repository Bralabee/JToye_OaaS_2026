/**
 * Who the basket in this browser belongs to (issue #459).
 *
 * The basket lives in localStorage, which is per-browser-profile and survives a
 * sign-out. On a shared device that meant customer A could fill a basket, sign
 * out, and customer B would sign in and inherit it.
 *
 * The fix is NOT "clear the basket on every auth event". Carrying a basket built
 * while browsing anonymously forward into a sign-in is the behaviour customers
 * expect and the one the product wants — a change that clears on every auth
 * event satisfies the headline bug and breaks the product. What must not survive
 * is a change of PERSON.
 *
 * Two mechanisms, deliberately layered, because neither alone is enough:
 *
 *   1. An explicit sign-out removes every stored basket (`clearStoredCarts`).
 *      Sign-OUT is the only moment where "a different person may be next" is
 *      unambiguous — at sign-IN, "the same person who was just browsing
 *      anonymously" and "a different person on the same device" look identical.
 *
 *   2. Every stored basket records the identity that last wrote it, and is
 *      rejected on read when the current identity is a DIFFERENT signed-in
 *      customer (`canAdoptCart`). This is the backstop for the sign-outs that
 *      never happen: a closed tab, a cleared cookie, a refresh token the IdP
 *      refuses.
 *
 * The branch that is easy to get wrong, so it is spelled out: a basket owned by
 * A stays readable while NOBODY is signed in. It has to be. The access cookie's
 * lifetime is the access-token lifetime (300s on this realm — see the note in
 * app/api/customer-auth/session/route.ts) and a renewal can fail, so "currently
 * anonymous" happens routinely to a customer who is mid-shop. Rejecting there
 * would delete a live shopper's basket every time a refresh hiccuped, which is a
 * far more common event than two customers sharing a browser. Mechanism 1 is
 * what covers the shared-device case, and it fires exactly when the device
 * changes hands.
 */

/**
 * Namespace for every per-shop basket. ONE definition, imported by the provider,
 * the nav badge and the sign-out reaper alike — two copies of this string is how
 * a "clear everything" quietly starts missing keys.
 */
export const CART_KEY_PREFIX = "jtoye-cart-"

export function cartStorageKey(slug: string): string {
  return `${CART_KEY_PREFIX}${slug}`
}

/**
 * The signed-in customer's opaque Keycloak subject id, mirrored into
 * localStorage next to the existing `jtoye-customer-logged-in` marker.
 *
 * It is mirrored rather than fetched because the decision it feeds is
 * SYNCHRONOUS: the cart hydrates from localStorage on mount, long before any
 * `getCustomerSession()` promise resolves, and a basket that appears and then
 * vanishes a beat later is its own defect.
 *
 * Deliberately the `sub` and never the email or name: it is an opaque
 * identifier with no personal content, it is already readable by client JS via
 * getCustomerSession(), and it is removed the moment the session goes away.
 */
export const CUSTOMER_ID_KEY = "jtoye-customer-id"

/**
 * The non-sensitive "a session exists" marker and its expiry, owned here rather
 * than in `customer-auth.ts` so this module holds the WHOLE localStorage
 * identity keyspace — `CART_KEY_PREFIX`, `CUSTOMER_ID_KEY` and these two — in
 * one place. Two copies of a key string is how a "clear everything" quietly
 * starts missing keys; the same argument that put `CART_KEY_PREFIX` here.
 *
 * `customer-auth.ts` imports them (never the reverse — that would be a cycle)
 * and `isLoggedIn()` delegates to `hasActiveSessionMarker()` below.
 */
export const CUSTOMER_MARKER_KEY = "jtoye-customer-logged-in"
export const CUSTOMER_EXPIRES_KEY = "jtoye-customer-expires-at"

/**
 * Does this browser believe a customer session is LIVE — independent of whether
 * we managed to record WHO it belongs to?
 *
 * That independence is the entire point (WR-02). `getCurrentCustomerId()`
 * returns null for two different facts, and they demand opposite treatment on a
 * cart write:
 *
 *   nobody is signed in            -> preserve the prior owner. Correct: this is
 *                                     the 300s token lapse R-16 is about.
 *   signed in, identity unrecorded -> preserving is WRONG. The prior owner is a
 *                                     DIFFERENT person from the one now
 *                                     shopping, and stamping their items with
 *                                     it is the reverse leak through a side
 *                                     door.
 *
 * This function is what tells the two apart. It is deliberately a read of the
 * marker and its expiry — never of `CUSTOMER_ID_KEY` — so it stays true exactly
 * when the id is missing, which is the case it exists to detect.
 */
export function hasActiveSessionMarker(): boolean {
  if (typeof window === "undefined") return false
  try {
    if (window.localStorage.getItem(CUSTOMER_MARKER_KEY) !== "true") return false
    const exp = Number(window.localStorage.getItem(CUSTOMER_EXPIRES_KEY) || "0")
    if (!exp) return false
    return exp > Math.floor(Date.now() / 1000)
  } catch {
    return false
  }
}

/** The customer this browser currently believes is signed in, or null. */
export function getCurrentCustomerId(): string | null {
  if (typeof window === "undefined") return null
  try {
    return window.localStorage.getItem(CUSTOMER_ID_KEY) || null
  } catch {
    // Private mode / storage disabled. Unknown identity reads as anonymous,
    // which `canAdoptCart` treats as "carry the basket" — the same behaviour
    // this app had before #459, never a stricter one that could eat a basket.
    return null
  }
}

/**
 * Record who is signed in. A blank/missing `sub` is ignored rather than written
 * as an empty string: an empty id would compare equal to another empty id and
 * silently re-open the very carry-over this module exists to close.
 */
export function rememberCustomerId(sub: string | null | undefined): void {
  if (typeof window === "undefined") return
  if (!sub) return
  try {
    window.localStorage.setItem(CUSTOMER_ID_KEY, sub)
  } catch {
    /* storage may be unavailable (private mode) — ignore */
  }
}

export function forgetCustomerId(): void {
  if (typeof window === "undefined") return
  try {
    window.localStorage.removeItem(CUSTOMER_ID_KEY)
  } catch {
    /* ignore */
  }
}

/**
 * The subject of the LAST confirmed customer sign-in on this browser, kept
 * until an EXPLICIT sign-out — FE-5 (QA council 20260902-134741).
 *
 * WHY A FOURTH KEY. `CUSTOMER_ID_KEY` is removed the moment the session goes
 * away (that is the property `hasActiveSessionMarker` relies on), and a basket
 * owner stamp only exists once a basket has been written. So after a sign-in
 * that LAPSED — the three HttpOnly cookies stopped being valid, nobody pressed
 * Sign out — nothing on this origin said "somebody was signed in here". Meanwhile
 * Keycloak's SSO cookies for that person are very likely still alive on the IdP
 * host, and NEITHER this app's client nor its server can see them: they are set
 * for the IdP's host under `/realms/<realm>/`. The measured consequence was the
 * shared-device dead-end: person B taps "Create an account", Keycloak refuses
 * with "already authenticated as different user 'A'", 0 links / 0 buttons /
 * 0 forms, A's email on screen.
 *
 * This key is the honest, client-detectable proxy for that state: "a sign-in
 * happened on this browser and no explicit sign-out has happened since". The
 * storefront offers "Not you? Sign out" when the session is unauthenticated AND
 * this is set. It is an OFFER, not a claim about the IdP — the SSO session may
 * have expired on its own — and taking it is harmless either way.
 *
 * LIFECYCLE — the same rule as the basket owner stamp (R-16), stated so the
 * next editor does not "tidy" it into `clearMarker()`:
 *   - a confirmed sign-in (`setMarker` with a sub) WRITES or CONFIRMS it;
 *   - a lapse (`clearMarker`, `forgetCustomerId`) leaves it ALONE — a lapsed
 *     session is not a new person;
 *   - ONLY an explicit sign-out (`clearSignedOutState`) removes it.
 * Removing it on a lapse would make the control vanish in exactly the state it
 * exists for. Its through-the-transition test asserts the STORED value at every
 * step, never the rendered nav (`lib/__tests__/customer-auth-last-signin.test.ts`).
 *
 * Deliberately the opaque `sub`, never the email or name — the same argument
 * as `CUSTOMER_ID_KEY` above — and a blank sub is ignored for the same reason.
 */
export const CUSTOMER_LAST_SIGNIN_KEY = "jtoye-customer-last-signin"

export function rememberLastSignIn(sub: string | null | undefined): void {
  if (typeof window === "undefined") return
  if (!sub) return
  try {
    window.localStorage.setItem(CUSTOMER_LAST_SIGNIN_KEY, sub)
  } catch {
    /* storage may be unavailable (private mode) — ignore */
  }
}

/** Explicit sign-out only. Never called from a lapse path. */
export function forgetLastSignIn(): void {
  if (typeof window === "undefined") return
  try {
    window.localStorage.removeItem(CUSTOMER_LAST_SIGNIN_KEY)
  } catch {
    /* ignore */
  }
}

export function getLastSignIn(): string | null {
  if (typeof window === "undefined") return null
  try {
    return window.localStorage.getItem(CUSTOMER_LAST_SIGNIN_KEY) || null
  } catch {
    // Private mode / storage disabled: no evidence of a prior sign-in reads
    // as "none", which withholds the offer — never a stricter behaviour.
    return null
  }
}

/** Did a customer sign in on this browser without ever explicitly signing out? */
export function hasRememberedSignIn(): boolean {
  return getLastSignIn() !== null
}

/**
 * May a basket last written by `owner` be shown to `current`?
 *
 *   owner = null/undefined  built anonymously, or written before #459 shipped.
 *                           Adoptable by anyone — this IS the anonymous ->
 *                           signed-in carry-forward, and treating the legacy
 *                           (field-absent) shape as anonymous is what stops the
 *                           deploy itself from emptying live baskets.
 *   current = null          nobody signed in right now. Readable — see the
 *                           header note on token expiry.
 *   owner === current       the same person. Readable.
 *   otherwise               a different signed-in customer. Rejected.
 */
export function canAdoptCart(
  owner: string | null | undefined,
  current: string | null
): boolean {
  if (!owner) return true
  if (!current) return true
  return owner === current
}

/**
 * Who should be stamped on the basket about to be written — R-16.
 *
 * The header above argues that sign-OUT is the only unambiguous "a different
 * person may be next" moment. That argument was only ever applied to the READ
 * (`canAdoptCart`); the WRITE stamped `getCurrentCustomerId()` unconditionally,
 * and so quietly did the one thing the whole module forbids — it REMOVED an
 * ownership marker on an event that is not a sign-out.
 *
 * The event is routine, not exotic: the access cookie lives 300s, the session
 * probe runs on mount, on a 1s poll and on focus, and a `{ authenticated: false }`
 * answer forgets the customer id. The next shop-page render then re-persists the
 * basket — nothing has to change for that write to happen — stamped `null`. A
 * null owner is adoptable by anyone, so the very next registration on that
 * browser inherited the previous account's basket and checked out with it.
 *
 * So a write may ADD an owner or CONFIRM one; only `clearStoredCarts` removes
 * one. A TRUTHY current identity always wins; otherwise the prior one stands.
 * Deliberately truthiness and NOT the literal `current ?? prior ?? null` that
 * an earlier version of this comment claimed: a blank id is not an identity,
 * and "simplifying" this guard into nullish coalescing would re-open exactly
 * the empty-string hole `validOwner` exists to close (IN-03 / WR-01).
 *
 * Each branch is load-bearing:
 *
 *   prior null/absent, current X   -> X   the guest -> registration carry-over
 *                                         this module exists to protect. Must
 *                                         not become "preserve null forever".
 *   prior A, current null          -> A   the lapsed session. THE FIX.
 *   prior A, current B             -> B   B is signed in and writing, so B owns
 *                                         the slot. Preserving A here would be
 *                                         the same leak backwards: every item B
 *                                         adds stored under A's name.
 *   prior A, current A             -> A   unchanged.
 *
 * `sessionActive` is the THIRD fact, and it is required rather than optional so
 * that no call site can silently omit it — WR-02. `current === null` conflates
 * two states that demand opposite treatment:
 *
 *   nobody is signed in            -> preserve. The 300s lapse. Above.
 *   signed in, identity unrecorded -> do NOT preserve: resolve to null.
 *
 * In the second, the person shopping is not the person named on the stamp, so
 * preserving it would store THEIR items under the previous customer's identity
 * — the reverse leak arriving through a side door instead of the front. Falling
 * back to null makes the basket adoptable, which is the lesser harm: a writer
 * we cannot identify must not be able to make an authoritative ownership claim
 * on somebody else's behalf. `hasActiveSessionMarker()` supplies the fact, and
 * it reads the marker and never `CUSTOMER_ID_KEY`, so it stays true in exactly
 * the case it exists to detect.
 *
 * Pure and exported for its own unit tests: the decision is cart-specific, so it
 * lives here rather than in the generic `useStoredState`, and the provider's job
 * is only to supply the three facts.
 */
export function resolveCartOwner(
  priorOwner: string | null | undefined,
  current: string | null,
  sessionActive: boolean
): string | null {
  if (current) return current
  if (sessionActive) return null
  return priorOwner ?? null
}

/**
 * Broadcast name for "every stored basket just went away" — WR-03.
 *
 * ONE definition, imported by the reaper below and by whatever holds a basket in
 * memory, for the same reason `CART_KEY_PREFIX` is one definition: a typo in a
 * second copy is a listener that silently never fires.
 */
export const CARTS_CLEARED_EVENT = "jtoye:carts-cleared"

/**
 * Remove every stored basket, for every shop. Called on an explicit sign-out.
 *
 * Walks the whole keyspace rather than the slugs we happen to know about: the
 * provider only ever holds one slug, and the basket that leaks is by definition
 * one nobody is currently looking at.
 */
export function clearStoredCarts(): void {
  if (typeof window === "undefined") return
  try {
    const doomed: string[] = []
    for (let i = 0; i < window.localStorage.length; i++) {
      const k = window.localStorage.key(i)
      if (k && k.startsWith(CART_KEY_PREFIX)) doomed.push(k)
    }
    // Collect first, remove second. Removing during the walk shifts every
    // later index down by one and silently skips half the keys — which would
    // leave exactly the abandoned basket this is here to destroy.
    for (const k of doomed) window.localStorage.removeItem(k)
  } catch {
    /* private mode / quota — there is nothing stored to clear */
  }
  // WR-03 — clearing DISK is only half of it. A same-document localStorage
  // write raises NO `storage` event (that event fires only in the other
  // documents of an origin), and this function is reached from inside the
  // [slug] subtree where CartProvider is mounted: the 1s session poll, focus,
  // visibilitychange, checkout. Without this broadcast the provider keeps the
  // outgoing customer's items in React state, still on screen, and the next
  // add/remove/quantity change re-persists them stamped with the NEW
  // customer's sub — the leak made permanent and legitimate-looking.
  //
  // Dispatched OUTSIDE the try above, deliberately: if the disk removal threw,
  // in-memory holders need telling more, not less. Mirrors the existing
  // `jtoye:cart-updated` broadcast the nav badge already listens to.
  try {
    window.dispatchEvent(new CustomEvent(CARTS_CLEARED_EVENT))
  } catch {
    /* CustomEvent unavailable in an exotic environment — nothing to notify */
  }
}

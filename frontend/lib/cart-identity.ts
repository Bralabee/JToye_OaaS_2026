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
 * one. Concretely `current ?? prior ?? null`, and each branch is load-bearing:
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
 * Pure and exported for its own unit tests: the decision is cart-specific, so it
 * lives here rather than in the generic `useStoredState`, and the provider's job
 * is only to supply the prior value.
 */
export function resolveCartOwner(
  priorOwner: string | null | undefined,
  current: string | null
): string | null {
  if (current) return current
  return priorOwner ?? null
}

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
}

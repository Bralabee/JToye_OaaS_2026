import { getCustomerSession, type CustomerProfile } from "@/lib/customer-auth"

/**
 * The one place customer session state is held for UI purposes (issue #457),
 * shaped as an external store so `useSyncExternalStore` can read it
 * SYNCHRONOUSLY during render.
 *
 * WHY A STORE AND NOT A HOOK-LOCAL useState. `hooks/use-customer-session.ts`
 * hydrated with a mount-time `setState` inside a `useEffect`, carried under a
 * `react-hooks/set-state-in-effect` suppression (#202 / the `#99 follow-up`
 * marker). The rule traces into the call graph, so moving the `setProfile` into
 * a helper is not a fix — only removing the mount-time state write is. React's
 * sanctioned shape for "state that lives outside React" is an external store
 * with a subscribe / getSnapshot / getServerSnapshot triple, which is what this
 * file provides.
 *
 * Carried forward VERBATIM from the hook this replaces, because it is the reason
 * the store is shaped this way and losing it invites the next editor to
 * "simplify" it into a lie:
 *
 *   > Deliberately built on the ASYNC getCustomerSession() (server truth) rather
 *   > than the synchronous isLoggedIn() marker: the marker carries the
 *   > access-token expiry and only getCustomerSession() re-stamps it, so a
 *   > marker-only reader on a public surface goes stale and then lies.
 *
 * THREE readers share this module — `components/public/public-header.tsx`,
 * `components/public/public-footer.tsx` and
 * `components/storefront/storefront-nav.tsx` (measured 2026-08-28). One store is
 * the whole point: two independent readers is how #457 happened.
 *
 * SECURITY (T-34-03-01 / T-34-03-02 / T-34-03-03, ASVS V3/V8). `cached` is
 * module state in a client bundle, so two properties are load-bearing and are
 * enforced by this file's shape, not by convention:
 *
 *   (a) `getServerSnapshot()` returns a literal `null` and reads NO browser API.
 *       Every render is a server render here (`app/layout.tsx` sets
 *       `dynamic = "force-dynamic"`), so a server snapshot derived from module
 *       state could emit one visitor's profile into another's HTML. It cannot
 *       return anything but null.
 *
 *   (b) A `null` answer from `getCustomerSession()` ALWAYS clears the cache.
 *       An expired, revoked or signed-out session must collapse the pill on
 *       every mounted reader; leaving the last-known profile on screen is the
 *       information-disclosure failure this store is most able to cause.
 *
 * `isLoggedIn()` is never imported here, and the test suite asserts it is never
 * called: the localStorage marker is attacker-writable and must never by itself
 * produce a signed-in UI.
 */

/**
 * The two localStorage keys a cross-tab login/logout moves. Re-declared rather
 * than imported because `lib/customer-auth.ts` keeps them module-private; the
 * hook this replaces hardcoded the same two strings, so this is not new
 * duplication. `frontend/e2e/storefront-session-pill.spec.ts` plants exactly
 * these keys to prove the marker alone changes nothing.
 */
const MARKER_KEY = "jtoye-customer-logged-in"
const EXPIRES_KEY = "jtoye-customer-expires-at"

/**
 * After an OAuth redirect the callback writes the marker and navigates in the
 * SAME tab, so no `storage` event is delivered. The old effect covered that with
 * a brief poll; the numbers are carried over unchanged.
 */
const POLL_INTERVAL_MS = 1000
const POLL_WINDOW_MS = 5000

let cached: CustomerProfile | null = null
const listeners = new Set<() => void>()

let onFocus: (() => void) | null = null
let onVisibility: (() => void) | null = null
let onStorage: ((e: StorageEvent) => void) | null = null
let pollInterval: ReturnType<typeof setInterval> | null = null
let pollStop: ReturnType<typeof setTimeout> | null = null

/**
 * The key a snapshot is deduplicated on: this interface's OWN declared fields,
 * in a fixed order.
 *
 * NOT a JSON.stringify of the whole object. The session probe runs once a second
 * for five seconds after mount and again on every focus, so any field the server
 * later adds — or reorders — would churn the snapshot reference and re-render
 * three headers for no visible change. Enumerating the declared fields keeps
 * that immunity: a field this app does not know about cannot move the key.
 *
 * NOT the subject alone, either. `sub` is the identity, but `name` and `email`
 * are what the three consumers actually PUT ON SCREEN, and a key that ignores
 * them silently drops a real change: the same subject arriving with an updated
 * display name would be discarded as "equivalent" and the header would show the
 * stale one for the life of the tab. Measured on this tree — keying on `sub`
 * alone reds `components/public/__tests__/public-header-session.test.tsx:70`
 * ("falls back to the email when the profile carries no name"), because the
 * nameless profile shares `sub: "u1"` with the signed-in profile cached before
 * it. Deduplicate on what is displayed; ignore only what is unknown.
 *
 * An unidentifiable profile (no `sub`, no `email`) returns null and is
 * deliberately treated as NOT equivalent to anything, including another
 * unidentifiable profile: refusing to dedupe what cannot be identified is the
 * safe direction.
 */
function identityOf(profile: CustomerProfile | null): string | null {
  if (!profile) return null
  if (!profile.sub && !profile.email) return null
  return JSON.stringify([
    profile.sub,
    profile.email,
    profile.name,
    profile.emailVerified,
  ])
}

function emit(): void {
  // Copied before iterating: a listener that unsubscribes during notification
  // must not mutate the set being walked.
  for (const listener of Array.from(listeners)) listener()
}

/**
 * Adopt `next` as the snapshot, notifying only on a real change.
 *
 * The reference is held STABLE across equivalent answers. `useSyncExternalStore`
 * compares snapshots with `Object.is` and re-renders on any difference, so a
 * store that returned a fresh object per poll would re-render every reader once
 * a second — and one that returned a fresh object per `getSnapshot()` call would
 * loop until React threw "maximum update depth exceeded".
 */
function commit(next: CustomerProfile | null): void {
  const previousIdentity = identityOf(cached)
  const nextIdentity = identityOf(next)

  if (cached === null && next === null) return
  if (
    cached !== null &&
    next !== null &&
    previousIdentity !== null &&
    previousIdentity === nextIdentity
  ) {
    return
  }

  cached = next
  emit()
}

function attach(): void {
  if (typeof window === "undefined") return

  onFocus = () => {
    void refresh()
  }
  onVisibility = () => {
    if (document.visibilityState === "visible") void refresh()
  }
  onStorage = (e: StorageEvent) => {
    if (e.key === MARKER_KEY || e.key === EXPIRES_KEY) void refresh()
  }

  window.addEventListener("focus", onFocus)
  document.addEventListener("visibilitychange", onVisibility)
  window.addEventListener("storage", onStorage)

  pollInterval = setInterval(() => {
    void refresh()
  }, POLL_INTERVAL_MS)
  pollStop = setTimeout(() => {
    if (pollInterval !== null) {
      clearInterval(pollInterval)
      pollInterval = null
    }
  }, POLL_WINDOW_MS)

  // The mount-time check the deleted `useEffect` used to perform. Without it the
  // first reader would show "Sign in" to a signed-in customer until the poll's
  // first tick — a visible regression, not a refactor.
  void refresh()
}

function detach(): void {
  if (typeof window !== "undefined") {
    if (onFocus) window.removeEventListener("focus", onFocus)
    if (onVisibility) document.removeEventListener("visibilitychange", onVisibility)
    if (onStorage) window.removeEventListener("storage", onStorage)
  }
  onFocus = null
  onVisibility = null
  onStorage = null

  if (pollInterval !== null) {
    clearInterval(pollInterval)
    pollInterval = null
  }
  if (pollStop !== null) {
    clearTimeout(pollStop)
    pollStop = null
  }
}

/**
 * Subscribe a reader. The FIRST subscriber attaches the focus / visibilitychange
 * / storage listeners and starts the post-OAuth poll; the LAST unsubscribe
 * removes every one of them and clears both timers, with the same function
 * references it added.
 */
export function subscribe(onStoreChange: () => void): () => void {
  listeners.add(onStoreChange)
  if (listeners.size === 1) attach()

  return () => {
    listeners.delete(onStoreChange)
    if (listeners.size === 0) detach()
  }
}

/** The synchronous client snapshot. Reference-stable while the identity holds. */
export function getSnapshot(): CustomerProfile | null {
  return cached
}

/**
 * Drop everything this module is holding. TEST ISOLATION ONLY — nothing in the
 * app calls it, and nothing should: the cache outliving a reader is a FEATURE in
 * the browser (a client-side navigation from `/shop` to `/` unmounts
 * StorefrontNav and mounts PublicHeader, and the surviving snapshot is what stops
 * the new header flashing "Sign in" at a signed-in customer — the #457 symptom).
 *
 * In jsdom that same persistence is a hazard, because the module registry
 * outlives an individual `it`. Measured while building this store: with no reset,
 * `public-footer-legal`, `public-footer-persona` and `public-header-session`
 * inherited the previous test's session and three of their assertions went red —
 * including one that asserts the SYNCHRONOUS first paint
 * (`public-footer-persona.test.tsx:188`, "emits the operator links before the
 * session resolves"), whose whole premise is that no session is resolved yet.
 *
 * It is wired into `jest.setup.js` as a global `beforeEach` rather than left to
 * each suite: fourteen further suites render one of the three consumers and would
 * be one reordering away from the same failure, and a rule every author must
 * remember is not a fix.
 */
export function __resetForTests(): void {
  listeners.clear()
  detach()
  cached = null
}

/**
 * The server snapshot. ALWAYS null, reads nothing (T-34-03-03) — see (a) in the
 * header. It also keeps hydration honest: a fresh client starts at null too, so
 * server and first client snapshots agree.
 */
export function getServerSnapshot(): CustomerProfile | null {
  return null
}

/**
 * Re-read the server's answer and adopt it.
 *
 * Never throws to the caller: `getCustomerSession()` already swallows its own
 * failures, and a transport error that slipped past it must not blank a signed-in
 * header — an unknown answer leaves the previous snapshot in place. A `null`
 * answer is NOT an unknown answer; it is the server saying "nobody", and it
 * always clears (see (b) in the header).
 */
export async function refresh(): Promise<void> {
  try {
    const session = await getCustomerSession()
    commit(session?.profile ?? null)
  } catch {
    // Deliberately inert: keep the last known-good snapshot.
  }
}

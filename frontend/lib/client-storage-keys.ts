/**
 * Every localStorage and sessionStorage key this site writes, in ONE list
 * (#840).
 *
 * TWO CONSUMERS READ IT, AND THAT IS THE POINT.
 *   - The cookie and browser-storage policy (`app/legal/cookies/page.tsx`)
 *     renders its two storage tables by mapping this array. The published list
 *     IS this list, so it cannot drift from it.
 *   - An explicit sign-out (`clearSignedOutState` in `lib/customer-auth.ts`)
 *     removes every entry marked `clearedOnSignOut`, through
 *     `removeStorageEntries`.
 * A third reader keeps the list honest against the code: the source scan in
 * `lib/__tests__/client-storage-keys.test.ts` finds every `setItem` call site in
 * `app/`, `components/`, `lib/` and `hooks/` and fails on a key this list does
 * not declare.
 *
 * WHY. A hand-maintained policy list already drifted once (31-11 measured five
 * session-storage keys where the page listed two), and #840 found two more:
 * `jtoye-customer-last-signin` (written on every sign-in, never disclosed) and
 * `jtoye-guest-orders` (order numbers AND the email each was placed with, which
 * survived sign-out on a shared device).
 *
 * THE LAPSE RULE IS NOT EXPRESSED HERE, AND MUST NOT BE. `clearedOnSignOut`
 * means an EXPLICIT sign-out. A session that lapses (an access cookie that aged
 * out, a refresh the IdP declined) goes through `clearMarker()`, which removes
 * only the live-session marker and identity; the basket owner stamp, the
 * last-signin stamp and the order history all survive a lapse (R-16 / FE-5).
 * Nothing in this module is called from the lapse path.
 *
 * Constants are IMPORTED from the module that owns each key rather than re-typed,
 * so there is one definition of each string. The exceptions are keys whose
 * owning module is a client component or holds them privately (the sign-in
 * session values in `customer-auth.ts`, which imports THIS module; `theme` in
 * the "use client" `hooks/use-theme.ts`; the dashboard keys). Those are literal
 * here, and the source scan is what proves the literal matches the writer.
 *
 * The prose is legally operative copy: it is what a reader of the policy sees.
 * It was moved here verbatim from the page, corrected only where #840 changed
 * or exposed the truth (the email-bearing rows' lifetimes and the order-list
 * purpose).
 */
import { CART_KEY_PREFIX, CUSTOMER_EXPIRES_KEY, CUSTOMER_ID_KEY, CUSTOMER_LAST_SIGNIN_KEY, CUSTOMER_MARKER_KEY } from "@/lib/cart-identity"
import { CONSENT_CHOICES_KEY, COOKIE_NOTICE_ACK_KEY } from "@/lib/consent"
import { GUEST_ORDERS_KEY } from "@/lib/order-history"

export type StorageArea = "localStorage" | "sessionStorage"

export interface ClientStorageKey {
  /** The literal key, or for `match: "prefix"` the part before the shop's short name. */
  readonly name: string
  readonly match: "exact" | "prefix"
  readonly area: StorageArea
  /** Personal data: identifies, or is about, the person using the browser. */
  readonly personal: boolean
  /** Removed by an EXPLICIT sign-out (never by a lapse — see the header). */
  readonly clearedOnSignOut: boolean
  readonly purpose: string
  readonly lifetime: string
}

/**
 * Per-shop checkout email. The writers (`app/shop/[slug]/checkout/page.tsx`,
 * `app/shop/[slug]/orders/[orderNumber]/page.tsx`) build it as a template
 * literal on the shop slug.
 */
export const CHECKOUT_EMAIL_KEY_PREFIX = "jtoye-checkout-email-"

export const CLIENT_STORAGE_KEYS: readonly ClientStorageKey[] = [
  // ---------------------------------------------------------------- local
  {
    name: CART_KEY_PREFIX,
    match: "prefix",
    area: "localStorage",
    personal: true,
    clearedOnSignOut: true,
    purpose:
      "Your basket for one shop. There is one of these per shop you have added something to.",
    lifetime:
      "Until you sign out or clear your browser storage. Signing out removes every shop's basket.",
  },
  {
    name: CHECKOUT_EMAIL_KEY_PREFIX,
    match: "prefix",
    area: "localStorage",
    personal: true,
    clearedOnSignOut: true,
    purpose:
      "The email address you last used at that shop's checkout, so it can be filled in for you next time and so you can look up your order.",
    lifetime:
      "Until you sign out or clear your browser storage; no other expiry is set. This one holds an email address.",
  },
  {
    name: CUSTOMER_ID_KEY,
    match: "exact",
    area: "localStorage",
    personal: true,
    clearedOnSignOut: true,
    purpose:
      "An opaque identifier for the signed-in customer. It stamps your basket so that a second person signing in on the same device cannot inherit it. It is not your email address or your name.",
    lifetime: "Until you sign out.",
  },
  {
    name: CUSTOMER_MARKER_KEY,
    match: "exact",
    area: "localStorage",
    personal: false,
    clearedOnSignOut: true,
    purpose:
      "A yes/no marker so the page can show the right header immediately, without waiting for a request to the server.",
    lifetime: "Until you sign out.",
  },
  {
    name: CUSTOMER_EXPIRES_KEY,
    match: "exact",
    area: "localStorage",
    personal: false,
    clearedOnSignOut: true,
    purpose:
      "When the marker above stops being valid, so a stale sign-in state is not shown to you.",
    lifetime: "Until you sign out.",
  },
  {
    name: CUSTOMER_LAST_SIGNIN_KEY,
    match: "exact",
    area: "localStorage",
    personal: true,
    clearedOnSignOut: true,
    purpose:
      "An opaque identifier for the last customer who signed in on this browser, kept after a sign-in simply expires, so the shop can offer \"Not you? Sign out\" to whoever uses this device next. It is not your email address or your name.",
    lifetime:
      "Until you sign out. A sign-in that expires on its own does not remove it.",
  },
  {
    name: GUEST_ORDERS_KEY,
    match: "exact",
    area: "localStorage",
    personal: true,
    clearedOnSignOut: true,
    purpose:
      "The order numbers of your most recent orders on this device, each with the email address it was placed with, so you can find them again. Capped at the twenty most recent.",
    lifetime:
      "Until you sign out or clear your browser storage. This one holds an email address.",
  },
  {
    name: COOKIE_NOTICE_ACK_KEY,
    match: "exact",
    area: "localStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "Records that you have seen the notice about this page, so it is not shown to you on every visit.",
    lifetime: "Until you clear your browser storage.",
  },
  {
    name: CONSENT_CHOICES_KEY,
    match: "exact",
    area: "localStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "Your choices about optional storage categories, kept separately from the record above so that dismissing a notice is never treated as consent. We register no optional categories today, so nothing is written to it at present.",
    lifetime: "Until you clear your browser storage.",
  },
  {
    // lib/shop-context.ts SHOP_CONTEXT_KEY (module-private there).
    name: "shopContext",
    match: "exact",
    area: "localStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "Which shop a vendor's dashboard is currently filtered to. Dashboard only.",
    lifetime: "Until changed or cleared.",
  },
  {
    // hooks/use-theme.ts STORAGE_KEY ("use client" module — not importable here).
    name: "theme",
    match: "exact",
    area: "localStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "Whether you chose the light or dark appearance for the dashboard.",
    lifetime: "Until changed or cleared.",
  },
  {
    // app/dashboard/kitchen/page.tsx (inline literal).
    name: "kds-muted",
    match: "exact",
    area: "localStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "Whether the kitchen display's new-order sound is muted. Kitchen display only.",
    lifetime: "Until changed or cleared.",
  },
  // -------------------------------------------------------------- session
  {
    // app/shop/orders/orders-client.tsx, app/shop/[slug]/orders/[orderNumber]/page.tsx.
    name: "jtoye-track-email",
    match: "exact",
    area: "sessionStorage",
    personal: true,
    clearedOnSignOut: true,
    purpose:
      "Carries the email address you typed on one order-tracking page across to the next, so you do not type it twice. This one holds an email address.",
    lifetime: "Cleared when you sign out or close the tab.",
  },
  {
    // lib/customer-auth.ts (customerLogin / customerRegister).
    name: "jtoye-auth-return",
    match: "exact",
    area: "sessionStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "The page you were on when you started signing in, so you are returned there afterwards rather than to the home page.",
    lifetime: "Cleared when you close the tab.",
  },
  {
    // lib/customer-auth.ts PKCE_VERIFIER_KEY.
    name: "jtoye-pkce-verifier",
    match: "exact",
    area: "sessionStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "A one-time secret that proves the sign-in finishing in this tab is the same one that started here. It is what stops an intercepted sign-in from being completed by somebody else.",
    lifetime:
      "Discarded as soon as sign-in completes, and in any case when you close the tab.",
  },
  {
    // lib/customer-auth.ts OAUTH_STATE_KEY.
    name: "jtoye-oauth-state",
    match: "exact",
    area: "sessionStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "A one-time value that protects the sign-in against a cross-site request forgery attack.",
    lifetime:
      "Discarded as soon as sign-in completes, and in any case when you close the tab.",
  },
  {
    // lib/customer-auth.ts OAUTH_NONCE_KEY.
    name: "jtoye-oauth-nonce",
    match: "exact",
    area: "sessionStorage",
    personal: false,
    clearedOnSignOut: false,
    purpose:
      "A one-time value that stops a previously issued sign-in response from being replayed.",
    lifetime:
      "Discarded as soon as sign-in completes, and in any case when you close the tab.",
  },
] as const

/** Every entry an explicit sign-out removes. Never consulted on a lapse. */
export const PERSONAL_KEYS_CLEARED_ON_SIGN_OUT: readonly ClientStorageKey[] =
  CLIENT_STORAGE_KEYS.filter((entry) => entry.clearedOnSignOut)

/** Does `entry` describe `key`? A prefix needs a non-empty suffix (a shop slug). */
export function entryMatches(entry: ClientStorageKey, key: string): boolean {
  if (entry.match === "exact") return key === entry.name
  return key.startsWith(entry.name) && key.length > entry.name.length
}

/** Is `key`, written to `area`, declared (and therefore disclosed)? */
export function isRegisteredStorageKey(area: StorageArea, key: string): boolean {
  return CLIENT_STORAGE_KEYS.some((entry) => entry.area === area && entryMatches(entry, key))
}

/**
 * How the policy names an entry: the literal key, or for a per-shop key the
 * prefix with `<shop>` on the end, so a reader can match what they find in
 * their own browser.
 */
export function storageKeyDisplayName(entry: ClientStorageKey): string {
  return entry.match === "prefix" ? `${entry.name}<shop>` : entry.name
}

/**
 * Remove every key matching any of `entries`, in both storage areas.
 *
 * Collect first, remove second — the `clearStoredCarts` rule. Removing during an
 * index walk shifts every later index down by one and silently skips keys,
 * which would leave exactly the per-shop item this exists to destroy. Each area
 * is guarded on its own so a throwing sessionStorage (private mode) cannot stop
 * the localStorage teardown.
 */
export function removeStorageEntries(entries: readonly ClientStorageKey[]): void {
  if (typeof window === "undefined") return
  for (const area of ["localStorage", "sessionStorage"] as const) {
    const wanted = entries.filter((entry) => entry.area === area)
    if (wanted.length === 0) continue
    try {
      const storage = window[area]
      const doomed: string[] = []
      for (let i = 0; i < storage.length; i++) {
        const key = storage.key(i)
        if (key !== null && wanted.some((entry) => entryMatches(entry, key))) doomed.push(key)
      }
      for (const key of doomed) storage.removeItem(key)
    } catch {
      /* private mode / storage disabled — nothing stored to clear */
    }
  }
}

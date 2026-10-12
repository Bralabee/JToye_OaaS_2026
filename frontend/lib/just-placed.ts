/**
 * The one-shot "just placed" marker (37-37, UI-SPEC § C3, RWO-029 / UXT-029 #802).
 *
 * A cash order used to be confirmed IN PLACE at /shop/{slug}/checkout: unannounced, and lost on a
 * refresh. It now routes to /shop/{slug}/orders/{orderNumber}, like a card order always did. That
 * route serves every view of the order, so it needs one bit the URL cannot carry safely: "this is the
 * first view, straight after placing it". The checkout leaves this marker; the order page removes it
 * on its first render and only then says "Order confirmed" (focused, announced, in the title).
 *
 * WHAT IT HOLDS. The order number only — in the key and as the value. It is not user-scoped data: no
 * email, no name, nothing the order page trusts for order data. It is a client HINT, never an
 * authority (T-37-89): the page still reads everything it shows from the tracking endpoint, bound to
 * order number + email (goods F3, P2-KYL-P5). A forged marker can do no more than make a page the
 * reader could already open say "confirmed" once.
 *
 * WHY sessionStorage. It must survive the client-side navigation and nothing else: a new tab, a
 * restored session or a later visit is not "just placed". It is removed on first read, and in any
 * case when the tab closes. Disclosed in the storage policy through `lib/client-storage-keys.ts`
 * (the PGC-840 rule: every key this site writes is listed).
 *
 * The key is written as a template literal on purpose: the storage-key source scan
 * (`lib/__tests__/client-storage-keys.test.ts`) reduces it to its static prefix and requires that
 * prefix to be registered.
 */
export const JUST_PLACED_KEY_PREFIX = "jtoye-just-placed:"

/** Leave the marker for `orderNumber`. Storage may be unavailable (private mode): then the order page simply shows its plain view. */
export function markJustPlaced(orderNumber: string): void {
  try {
    sessionStorage.setItem(`jtoye-just-placed:${orderNumber}`, orderNumber)
  } catch {
    /* storage unavailable — the confirmation route still works, unannounced as a first view */
  }
}

/**
 * Was this order just placed in this tab? Reads AND removes the marker, so it answers true at most
 * once. A marker for a different order is left alone.
 */
export function consumeJustPlaced(orderNumber: string): boolean {
  try {
    const key = `${JUST_PLACED_KEY_PREFIX}${orderNumber}`
    const present = sessionStorage.getItem(key) !== null
    if (present) sessionStorage.removeItem(key)
    return present
  } catch {
    return false
  }
}

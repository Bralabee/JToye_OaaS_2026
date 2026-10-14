/**
 * 37-52 (UI-SPEC F3, UXT-083): what the storefront says to a screen-reader customer when the basket
 * changes, in ONE place.
 *
 * The basket link in the storefront header used to BE the live region, holding both the visible
 * numeric badge and its sr-only count, so every change was read as "1 1 item in basket" and never
 * said what had gone in or out. The link is now an ordinary link (named by its count, once), and a
 * separate polite region in the header speaks the sentence built here:
 *
 *   "{Item} added. {n} item(s) in basket."
 *   "{Item} removed. {n} item(s) in basket."   /   "{Item} removed. Basket is empty."
 *
 * The provider works out WHICH line changed (`basketEditOf`) when it persists, and broadcasts it
 * on `jtoye:cart-updated` beside the count; the header turns it into words (`basketAnnouncement`).
 * Doing the diff at the provider means every surface that edits the basket (the dish card, the dish
 * modal, the drawer, the basket page) is announced the same way without each one remembering to.
 */

export interface BasketLine {
  productId: string
  title: string
  quantity: number
}

/** One dish's quantity went up ("added") or down ("removed"). */
export interface BasketEdit {
  title: string
  kind: "added" | "removed"
}

/** "1 item in basket" / "2 items in basket" — the same idiom as the drawer and the basket page. */
export function basketCountPhrase(count: number): string {
  return `${count} item${count !== 1 ? "s" : ""} in basket`
}

/**
 * The single dish whose quantity changed between two persisted baskets, or null.
 *
 * null when there is no previous basket (the first persist after a page load is hydration, not
 * something the customer did, so nothing is announced), when nothing changed, and when more than one
 * line changed at once (a sign-out clearing the basket, the server's basket-changed rewrite): those
 * have their own messages on the surfaces that cause them, and naming one dish of several would be
 * wrong.
 */
export function basketEditOf(
  prev: readonly BasketLine[] | undefined,
  next: readonly BasketLine[]
): BasketEdit | null {
  if (!prev) return null
  const before = new Map(prev.map((l) => [l.productId, l]))
  const after = new Map(next.map((l) => [l.productId, l]))
  const changed: BasketEdit[] = []
  for (const id of new Set([...before.keys(), ...after.keys()])) {
    const was = before.get(id)?.quantity ?? 0
    const now = after.get(id)?.quantity ?? 0
    if (was === now) continue
    const title = (after.get(id) ?? before.get(id))!.title
    changed.push({ title, kind: now > was ? "added" : "removed" })
  }
  return changed.length === 1 ? changed[0] : null
}

/** The sentence the header's basket region speaks for one change and the basket's new count. */
export function basketAnnouncement(change: BasketEdit, count: number): string {
  const tail = count === 0 ? "Basket is empty." : `${basketCountPhrase(count)}.`
  return `${change.title} ${change.kind}. ${tail}`
}

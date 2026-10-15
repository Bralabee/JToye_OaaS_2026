"use client"

import {
  createContext,
  useContext,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react"
import { useStoredState } from "@/hooks/use-stored-state"
import {
  CARTS_CLEARED_EVENT,
  CUSTOMER_ID_KEY,
  canAdoptCart,
  cartStorageKey,
  getCurrentCustomerId,
  hasActiveSessionMarker,
  resolveCartOwner,
} from "@/lib/cart-identity"
import type { BasketChange } from "@/types/storefront"
import { basketEditOf } from "@/lib/basket-announcement"

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
}

/**
 * 37-34 (D-16, UI-SPEC § C1 "What the page does with the change"): the basket AFTER the server's
 * basket-changed `changes[]` (37-33), as a pure function over the lines.
 *
 *   PRICE_CHANGED  the line's price becomes the server's `current`
 *   UNAVAILABLE    the line is dropped
 *   REMOVED        the line is dropped (the entry carries only the id; the name is this basket's)
 *   SOLD_OUT       the quantity becomes what is available — never more than was asked for — or
 *                  the line is dropped at 0
 *   BELOW_MINIMUM, TOTAL_CHANGED, an id this basket does not hold:   nothing to rewrite
 *
 * LINES ONLY. Everything else a line carries (title, image, category, VAT rate) is kept, and the
 * payload's ownership stamp is not this function's to touch: the provider's one serialize path
 * stamps it (`resolveCartOwner`), so a server rewrite goes through exactly the write a shopper's
 * own edit would — ADD or CONFIRM an owner, never ERASE one (R-16). Returns the SAME array when
 * nothing changes, so a diff with no line entries causes no state churn and no re-persist.
 */
export function applyBasketChanges(items: CartItem[], changes: readonly BasketChange[]): CartItem[] {
  const byProduct = new Map<string, BasketChange[]>()
  for (const change of changes) {
    if (!change.productId) continue
    const list = byProduct.get(change.productId)
    if (list) list.push(change)
    else byProduct.set(change.productId, [change])
  }
  if (byProduct.size === 0) return items

  let touched = false
  const next: CartItem[] = []
  for (const item of items) {
    const mine = byProduct.get(item.productId)
    if (!mine) {
      next.push(item)
      continue
    }
    let line: CartItem | null = item
    for (const change of mine) {
      if (!line) break
      switch (change.kind) {
        case "PRICE_CHANGED":
          if (isCount(change.current) && change.current !== line.pricePennies) {
            line = { ...line, pricePennies: change.current }
            touched = true
          }
          break
        case "UNAVAILABLE":
        case "REMOVED":
          line = null
          touched = true
          break
        case "SOLD_OUT": {
          const available = isCount(change.available) ? change.available : 0
          if (available <= 0) {
            line = null
            touched = true
          } else if (available < line.quantity) {
            line = { ...line, quantity: available }
            touched = true
          }
          break
        }
        default:
          break
      }
    }
    if (line) next.push(line)
  }
  return touched ? next : items
}

export interface CartItem {
  productId: string
  title: string
  pricePennies: number
  quantity: number
  imageUrl: string | null
  category: string | null
  /**
   * COR-6: the line's VAT rate, captured when the item is added so the checkout can preview VAT
   * per line the way the server computes it (VatCalculator.predominantRate over the basket).
   *
   * Optional AND nullable, and BOTH absences mean "unknown": a basket persisted before COR-6
   * carries no rate at all, and lib/vat.ts resolves unknown to STANDARD — which is the
   * conservative direction and is exactly the behaviour those baskets already had. So this is
   * additive for every stored basket; nothing needs migrating and no basket is emptied.
   */
  vatRate?: string | null
}

interface CartState {
  items: CartItem[]
  shopSlug: string
  /**
   * The customer id that last wrote this basket, or null when it was written
   * anonymously. Absent on payloads stored before #459 — `canAdoptCart` reads
   * that absence as anonymous, so shipping this does not empty live baskets.
   */
  owner?: string | null
}

interface CartContextValue {
  items: CartItem[]
  addItem: (item: Omit<CartItem, "quantity">) => void
  removeItem: (productId: string) => void
  updateQuantity: (productId: string, quantity: number) => void
  clearCart: () => void
  itemCount: number
  totalPennies: number
  shopSlug: string
  /**
   * 37-30 (D-20): the shop's per-line maximum (`PublicShop.maxLineQuantity`), set by the shop
   * page from the server's payload so every stepper in this provider — the dish card, the dish
   * modal and the basket drawer, which has no shop of its own — stops at the same number with the
   * same hint. React state only, never persisted: a stored limit could outlive the shop's. null =
   * no client limit (an older backend, or the shop page not yet read), and the server's 422 is the
   * only gate.
   */
  lineLimit: number | null
  setLineLimit: (limit: number | null) => void
  /**
   * 37-34 (D-16): rewrite the stored lines to the server's basket-changed `changes[]` — new prices,
   * unavailable/removed lines dropped, sold-out quantities set (see `applyBasketChanges`). Goes
   * through the same persist as every other edit, so the ownership stamp is left exactly as the
   * R-16 rule leaves it on any write.
   */
  applyServerChanges: (changes: readonly BasketChange[]) => void
}

const CartContext = createContext<CartContextValue | null>(null)

/** Stable identity, so it can never itself trigger a state change. */
const EMPTY_ITEMS: CartItem[] = []

/**
 * The two questions a stored payload has to answer before its items are shown:
 * is it THIS shop's basket, and is it THIS person's basket.
 *
 * `undefined` means reject — useStoredState falls back to an empty cart.
 */
/**
 * The stored payload for this slot, or `undefined` when there isn't a usable
 * one. ONE definition, used by both the read path (`parseCart`) and the write
 * path (`readStoredOwner`) — WR-05.
 *
 * Those two used to hold independent copies of "parse it, reject a foreign
 * `shopSlug`", and the write-side copy was covered by no test at all: deleting
 * it left the entire suite green while a foreign payload's owner leaked into
 * this slot. Two copies of a rule is two rules, eventually.
 */
function parsePayload(raw: string, slug: string): CartState | undefined {
  try {
    const parsed = JSON.parse(raw) as CartState
    // The stored shape carries its own slug; a mismatched payload is rejected
    // so a stale key can never surface — or donate its owner to — another
    // shop's basket.
    return parsed.shopSlug === slug ? parsed : undefined
  } catch {
    // Corrupt JSON. A broken cache must never break the UI.
    return undefined
  }
}

/**
 * An owner is a non-empty opaque subject id, or it is nothing — WR-01.
 *
 * This validation is load-bearing precisely BECAUSE of R-16. Before it, every
 * write recomputed the stamp from `getCurrentCustomerId()`, so a corrupt or
 * tampered value was repaired by the first write. Preserve-semantics make it
 * PERMANENT instead:
 *
 *   owner: "" / 0 / false   falsy but not nullish, so `prior ?? null` waves it
 *                           through — and `canAdoptCart` opens with
 *                           `if (!owner) return true`, i.e. the basket becomes
 *                           adoptable by ANY signed-in customer. That is the
 *                           R-16 end state, now self-sustaining.
 *   owner: {} / []          truthy non-string, so `owner === current` is never
 *                           true: the slot becomes permanently unreadable to
 *                           every signed-in customer.
 *
 * Reaching either needs same-origin write access (XSS, devtools, an extension),
 * so this is hardening — but `owner` IS the identity boundary, and it is this
 * change that turned it from derived state into persisted state. Anything we
 * did not write degrades to "no prior owner", which is the pre-R-16 behaviour,
 * and a non-null current identity then repairs the slot on the next write.
 */
function validOwner(owner: unknown): string | undefined {
  return typeof owner === "string" && owner.length > 0 ? owner : undefined
}

function parseCart(raw: string, slug: string): CartItem[] | undefined {
  const parsed = parsePayload(raw, slug)
  if (!parsed) return undefined
  // The payload carries its own owner, so a shared browser cannot surface
  // another CUSTOMER's basket (#459). See lib/cart-identity.ts for why "nobody
  // is signed in" deliberately does NOT reject.
  if (!canAdoptCart(validOwner(parsed.owner), getCurrentCustomerId())) return undefined
  return parsed.items || []
}

/**
 * The owner already on disk for this slot, or `undefined` when there isn't one
 * we can trust — R-16.
 *
 * `undefined` deliberately covers five different unknowns, all of which must
 * degrade to today's behaviour (stamp whoever is writing) rather than to
 * anything stricter that could strand or eat a basket:
 *   - no payload stored at all;
 *   - a payload for ANOTHER shop sitting in this key, whose owner must never be
 *     allowed to donate itself to this slot (one shared `parsePayload` applies
 *     that guard to the read and write paths alike — WR-05);
 *   - an `owner` that is not a non-empty string (`validOwner` — WR-01);
 *   - corrupt JSON;
 *   - storage unavailable (private mode, quota).
 *
 * Read at serialize time rather than held in state on purpose: the identity can
 * change between the hydrate and the write (a session probe resolving, a
 * sibling tab), and the value that matters is the one on disk at the moment of
 * the write.
 */
function readStoredOwner(slug: string): string | undefined {
  if (typeof window === "undefined") return undefined
  try {
    const raw = window.localStorage.getItem(cartStorageKey(slug))
    if (raw === null) return undefined
    return validOwner(parsePayload(raw, slug)?.owner)
  } catch {
    // Storage unavailable (private mode, quota).
    return undefined
  }
}

/** A full read of a shop's stored basket, with both rules applied. */
function readCart(slug: string): CartItem[] {
  if (typeof window === "undefined") return EMPTY_ITEMS
  try {
    const raw = window.localStorage.getItem(cartStorageKey(slug))
    if (raw === null) return EMPTY_ITEMS
    return parseCart(raw, slug) ?? EMPTY_ITEMS
  } catch {
    return EMPTY_ITEMS
  }
}

export function CartProvider({
  shopSlug,
  children,
}: {
  shopSlug: string
  children: ReactNode
}) {
  // Read + write ordering (never persist a basket we have not yet hydrated for
  // THIS slug) lives in useStoredState — see the note there for the clobber and
  // the cross-shop leak it closes. Keeping it there rather than here means the
  // next storage-backed piece of state gets it right for free.
  //
  // 37-52 (UI-SPEC F3, UXT-083): the basket as it was last persisted, so each broadcast can say WHICH
  // dish changed. undefined until the first persist, which is hydration (nothing the customer did),
  // so a page load announces nothing. Reset when the slug changes: another shop's basket is not a
  // previous state of this one.
  const lastPersistedRef = useRef<{ slug: string; items: CartItem[] } | undefined>(undefined)
  // 37-F review F3: true from one of THIS tab's own add/remove/update calls until the persist that
  // writes it. Only such a persist names a dish. A sibling tab's write (re-read on `storage`), the
  // account-switch wipe and the server's basket-changed rewrite clear it, so they still update the
  // count but are never announced as something this customer just did.
  const ownEditRef = useRef(false)
  const [items, setItems] = useStoredState<CartItem[]>(
    cartStorageKey(shopSlug),
    EMPTY_ITEMS,
    {
      parse: (raw) => parseCart(raw, shopSlug),
      // Stamp the writer's identity, so the next read can tell "the same
      // person who was browsing anonymously" from "a different person on the
      // same device" — a distinction that is invisible at sign-in time.
      //
      // ADD or CONFIRM, never ERASE (R-16). The stamp used to be written
      // unconditionally, so a single signed-out render — which happens on any
      // page view once the 300s access cookie lapses — downgraded an owned
      // basket to `owner: null` and handed it to the next person who signed in.
      // `resolveCartOwner` owns that rule; see the argument in cart-identity.ts.
      serialize: (value) =>
        JSON.stringify({
          shopSlug,
          owner: resolveCartOwner(
            readStoredOwner(shopSlug),
            getCurrentCustomerId(),
            // The third fact (WR-02): is a session live even though we have no
            // id for it? Read here, at the moment of the write, alongside the
            // other two — all three describe the same instant or they describe
            // nothing.
            hasActiveSessionMarker()
          ),
          items: value,
        } satisfies CartState),
      // Broadcast so same-document listeners (the nav basket badge) update
      // without subscribing to this context.
      onPersist: (value) => {
        if (typeof window === "undefined") return
        const prev = lastPersistedRef.current?.slug === shopSlug ? lastPersistedRef.current.items : undefined
        lastPersistedRef.current = { slug: shopSlug, items: value }
        const ownEdit = ownEditRef.current
        ownEditRef.current = false
        window.dispatchEvent(
          new CustomEvent("jtoye:cart-updated", {
            detail: {
              slug: shopSlug,
              itemCount: value.reduce((sum, i) => sum + i.quantity, 0),
              totalPennies: value.reduce(
                (sum, i) => sum + i.pricePennies * i.quantity,
                0
              ),
              // The one dish this write changed, or null (hydration, a write this tab did not make —
              // see ownEditRef — no change, several lines at
              // once). The header's basket region turns it into "{dish} added. {n} items in basket."
              edit: ownEdit ? basketEditOf(prev, value) : null,
            },
          })
        )
      },
    }
  )

  // A shared browser is often a shared browser with two tabs open. `storage`
  // fires only in the OTHER documents of this origin, so this never runs during
  // a normal single-tab sign-in and cannot disturb the carry-forward — it fires
  // when a sibling tab signs somebody out (which clears the baskets) or signs a
  // different customer in. Re-reading re-applies both rules; the basket already
  // rendered here would otherwise sit on screen owned by the previous person.
  //
  // Both keys are watched, not just the identity one: a sign-out removes the
  // identity marker AND the baskets, and nothing orders the two events, so
  // reacting to only one of them can re-read between the two removals and see
  // the doomed basket as adoptable.
  useEffect(() => {
    if (typeof window === "undefined") return
    const key = cartStorageKey(shopSlug)
    const onStorage = (e: StorageEvent) => {
      // e.key === null is localStorage.clear() — everything, including us.
      if (e.key !== null && e.key !== CUSTOMER_ID_KEY && e.key !== key) return
      ownEditRef.current = false
      setItems((prev) => {
        const next = readCart(shopSlug)
        // Bail out when nothing actually changed. Without this, two tabs on the
        // same shop ping-pong forever: each re-read produces a fresh array, the
        // write effect persists it, and the sibling tab reacts to that write.
        return JSON.stringify(prev) === JSON.stringify(next) ? prev : next
      })
    }
    window.addEventListener("storage", onStorage)
    return () => window.removeEventListener("storage", onStorage)
  }, [shopSlug, setItems])

  // WR-03 — the SAME-document counterpart of the effect above.
  //
  // `storage` fires only in the OTHER documents of an origin, so a
  // `clearStoredCarts()` reached from this very page — the account-switch
  // backstop firing on the 1s session poll, on focus, at checkout — clears disk
  // and leaves this provider holding the outgoing customer's items. They stay
  // on screen, and the next setItems re-persists them under the NEW customer's
  // stamp. Dropping to empty is the only correct response: the person this
  // basket belonged to is, by construction, not the person here now.
  useEffect(() => {
    if (typeof window === "undefined") return
    const onCleared = () => {
      ownEditRef.current = false
      setItems(EMPTY_ITEMS)
    }
    window.addEventListener(CARTS_CLEARED_EVENT, onCleared)
    return () => window.removeEventListener(CARTS_CLEARED_EVENT, onCleared)
  }, [setItems])

  const addItem = useCallback((item: Omit<CartItem, "quantity">) => {
    ownEditRef.current = true
    setItems((prev) => {
      const existing = prev.find((i) => i.productId === item.productId)
      if (existing) {
        return prev.map((i) =>
          i.productId === item.productId
            ? { ...i, quantity: i.quantity + 1 }
            : i
        )
      }
      return [...prev, { ...item, quantity: 1 }]
    })
  }, [setItems])

  const removeItem = useCallback((productId: string) => {
    ownEditRef.current = true
    setItems((prev) => prev.filter((i) => i.productId !== productId))
  }, [setItems])

  const updateQuantity = useCallback((productId: string, quantity: number) => {
    ownEditRef.current = true
    if (quantity <= 0) {
      setItems((prev) => prev.filter((i) => i.productId !== productId))
    } else {
      setItems((prev) =>
        prev.map((i) =>
          i.productId === productId ? { ...i, quantity } : i
        )
      )
    }
  }, [setItems])

  const clearCart = useCallback(() => {
    setItems([])
  }, [setItems])

  // 37-34: a functional update over the CURRENT lines (a sibling-tab re-read or a shopper's edit
  // landing between the 409 and this call is not overwritten), persisted by the one serialize
  // path above — which is what keeps the owner stamp out of this function's hands (F15).
  const applyServerChanges = useCallback(
    (changes: readonly BasketChange[]) => {
      ownEditRef.current = false
      setItems((prev) => applyBasketChanges(prev, changes))
    },
    [setItems]
  )

  const itemCount = items.reduce((sum, i) => sum + i.quantity, 0)
  const totalPennies = items.reduce((sum, i) => sum + i.pricePennies * i.quantity, 0)

  // Memoize the context value so children that only read subsets (e.g. just
  // itemCount) do not re-render on every parent render. addItem/removeItem/
  // updateQuantity/clearCart are already stable via useCallback above, so the
  // only thing that should change the reference identity is the cart data
  // itself.
  // 37-30 (D-20): the shop's per-line maximum, React state only (see CartContextValue.lineLimit).
  const [lineLimit, setLineLimit] = useState<number | null>(null)

  const value = useMemo<CartContextValue>(
    () => ({
      items,
      addItem,
      removeItem,
      updateQuantity,
      clearCart,
      itemCount,
      totalPennies,
      shopSlug,
      lineLimit,
      setLineLimit,
      applyServerChanges,
    }),
    [items, addItem, removeItem, updateQuantity, clearCart, itemCount, totalPennies, shopSlug, lineLimit, applyServerChanges]
  )

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}

export function useCart() {
  const ctx = useContext(CartContext)
  if (!ctx) throw new Error("useCart must be used within a CartProvider")
  return ctx
}

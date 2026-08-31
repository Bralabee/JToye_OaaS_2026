"use client"

import {
  createContext,
  useContext,
  useCallback,
  useEffect,
  useMemo,
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

export interface CartItem {
  productId: string
  title: string
  pricePennies: number
  quantity: number
  imageUrl: string | null
  category: string | null
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
        window.dispatchEvent(
          new CustomEvent("jtoye:cart-updated", {
            detail: {
              slug: shopSlug,
              itemCount: value.reduce((sum, i) => sum + i.quantity, 0),
              totalPennies: value.reduce(
                (sum, i) => sum + i.pricePennies * i.quantity,
                0
              ),
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
    const onCleared = () => setItems(EMPTY_ITEMS)
    window.addEventListener(CARTS_CLEARED_EVENT, onCleared)
    return () => window.removeEventListener(CARTS_CLEARED_EVENT, onCleared)
  }, [setItems])

  const addItem = useCallback((item: Omit<CartItem, "quantity">) => {
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
    setItems((prev) => prev.filter((i) => i.productId !== productId))
  }, [setItems])

  const updateQuantity = useCallback((productId: string, quantity: number) => {
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

  const itemCount = items.reduce((sum, i) => sum + i.quantity, 0)
  const totalPennies = items.reduce((sum, i) => sum + i.pricePennies * i.quantity, 0)

  // Memoize the context value so children that only read subsets (e.g. just
  // itemCount) do not re-render on every parent render. addItem/removeItem/
  // updateQuantity/clearCart are already stable via useCallback above, so the
  // only thing that should change the reference identity is the cart data
  // itself.
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
    }),
    [items, addItem, removeItem, updateQuantity, clearCart, itemCount, totalPennies, shopSlug]
  )

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}

export function useCart() {
  const ctx = useContext(CartContext)
  if (!ctx) throw new Error("useCart must be used within a CartProvider")
  return ctx
}

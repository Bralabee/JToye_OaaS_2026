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
  CUSTOMER_ID_KEY,
  canAdoptCart,
  cartStorageKey,
  getCurrentCustomerId,
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
function parseCart(raw: string, slug: string): CartItem[] | undefined {
  const parsed = JSON.parse(raw) as CartState
  // Stored shape carries its own slug; a mismatched payload is rejected so a
  // stale key can never surface another shop's basket.
  if (parsed.shopSlug !== slug) return undefined
  // ...and its own owner, so a shared browser cannot surface another
  // CUSTOMER's basket (#459). See lib/cart-identity.ts for why "nobody is
  // signed in" deliberately does NOT reject.
  if (!canAdoptCart(parsed.owner, getCurrentCustomerId())) return undefined
  return parsed.items || []
}

/**
 * The owner already on disk for this slot, or `undefined` when there isn't one
 * we can trust — R-16.
 *
 * `undefined` deliberately covers four different unknowns, all of which must
 * degrade to today's behaviour (stamp whoever is writing) rather than to
 * anything stricter that could strand or eat a basket:
 *   - no payload stored at all;
 *   - a payload for ANOTHER shop sitting in this key, whose owner must never be
 *     allowed to donate itself to this slot (the cross-shop guard `parseCart`
 *     already applies on the read side, applied here on the write side too);
 *   - corrupt JSON;
 *   - storage unavailable (private mode, quota).
 *
 * Read at serialize time rather than held in state on purpose: the identity can
 * change between the hydrate and the write (a session probe resolving, a
 * sibling tab), and the value that matters is the one on disk at the moment of
 * the write.
 */
function readStoredOwner(slug: string): string | null | undefined {
  if (typeof window === "undefined") return undefined
  try {
    const raw = window.localStorage.getItem(cartStorageKey(slug))
    if (raw === null) return undefined
    const parsed = JSON.parse(raw) as CartState
    if (parsed.shopSlug !== slug) return undefined
    return parsed.owner
  } catch {
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
          owner: resolveCartOwner(readStoredOwner(shopSlug), getCurrentCustomerId()),
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

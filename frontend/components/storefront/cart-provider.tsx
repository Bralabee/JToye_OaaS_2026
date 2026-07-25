"use client"

import { createContext, useContext, useCallback, useMemo, type ReactNode } from "react"
import { useStoredState } from "@/hooks/use-stored-state"

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

function getStorageKey(slug: string) {
  return `jtoye-cart-${slug}`
}

/** Stable identity, so it can never itself trigger a state change. */
const EMPTY_ITEMS: CartItem[] = []

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
    getStorageKey(shopSlug),
    EMPTY_ITEMS,
    {
      // Stored shape carries its own slug; a mismatched payload is rejected so
      // a stale key can never surface another shop's basket.
      parse: (raw) => {
        const parsed = JSON.parse(raw) as CartState
        if (parsed.shopSlug !== shopSlug) return undefined
        return parsed.items || []
      },
      serialize: (value) =>
        JSON.stringify({ shopSlug, items: value } satisfies CartState),
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
  }, [])

  const removeItem = useCallback((productId: string) => {
    setItems((prev) => prev.filter((i) => i.productId !== productId))
  }, [])

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
  }, [])

  const clearCart = useCallback(() => {
    setItems([])
  }, [])

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

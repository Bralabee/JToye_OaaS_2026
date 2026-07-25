"use client"

import { createContext, useContext, useState, useEffect, useCallback, useMemo, type ReactNode } from "react"

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

function loadCart(slug: string): CartItem[] {
  if (typeof window === "undefined") return []
  try {
    const raw = localStorage.getItem(getStorageKey(slug))
    if (!raw) return []
    const parsed: CartState = JSON.parse(raw)
    if (parsed.shopSlug !== slug) return []
    return parsed.items || []
  } catch {
    return []
  }
}

function saveCart(slug: string, items: CartItem[]) {
  if (typeof window === "undefined") return
  const state: CartState = { shopSlug: slug, items }
  localStorage.setItem(getStorageKey(slug), JSON.stringify(state))
}

export function CartProvider({
  shopSlug,
  children,
}: {
  shopSlug: string
  children: ReactNode
}) {
  const [items, setItems] = useState<CartItem[]>([])
  // Which slug `items` currently holds a HYDRATED basket for. Null until the
  // first read completes. This gates the write effect below — see why there.
  const [hydratedSlug, setHydratedSlug] = useState<string | null>(null)

  // Load from localStorage on mount, and again whenever the shop changes.
  useEffect(() => {
    setItems(loadCart(shopSlug))
    setHydratedSlug(shopSlug)
  }, [shopSlug])

  // Persist to localStorage on change, then broadcast so same-document
  // listeners (nav basket badge) update without a context subscription.
  // Counts are computed inline from `items` to avoid new effect deps.
  useEffect(() => {
    // NEVER persist a basket we have not yet hydrated for THIS slug.
    //
    // Both the read above and this write are effects, and this one used to run
    // on the very first commit — with the pre-hydration empty state — stamping
    // `items: []` over a real stored basket. A later commit repaired it, so it
    // looked harmless; under React StrictMode's double-invoke it was not. The
    // second mount's READ happened after the first mount's WRITE had already
    // emptied the key, so the read returned nothing and the basket was really
    // gone (reproducible as an empty basket on a hard nav to /shop/[slug]/cart).
    //
    // The same ordering leaked ACROSS shops: app/shop/[slug]/layout.tsx keeps
    // one CartProvider and swaps `shopSlug` on a client-side nav, so this
    // effect would fire for the NEW slug while `items` still held the OLD
    // shop's basket — writing shop A's items into shop B's key.
    //
    // Comparing against the slug (not a plain `hydrated` boolean) is what makes
    // the cross-shop case safe: on the render where the prop has changed but
    // the re-read has not yet committed, hydratedSlug is still the old slug.
    if (hydratedSlug !== shopSlug) return

    saveCart(shopSlug, items)
    if (typeof window === "undefined") return
    window.dispatchEvent(
      new CustomEvent("jtoye:cart-updated", {
        detail: {
          slug: shopSlug,
          itemCount: items.reduce((sum, i) => sum + i.quantity, 0),
          totalPennies: items.reduce((sum, i) => sum + i.pricePennies * i.quantity, 0),
        },
      })
    )
  }, [shopSlug, items, hydratedSlug])

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

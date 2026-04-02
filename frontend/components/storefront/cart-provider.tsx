"use client"

import { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from "react"

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

  // Load from localStorage on mount
  useEffect(() => {
    setItems(loadCart(shopSlug))
  }, [shopSlug])

  // Persist to localStorage on change
  useEffect(() => {
    saveCart(shopSlug, items)
  }, [shopSlug, items])

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

  return (
    <CartContext.Provider
      value={{
        items,
        addItem,
        removeItem,
        updateQuantity,
        clearCart,
        itemCount,
        totalPennies,
        shopSlug,
      }}
    >
      {children}
    </CartContext.Provider>
  )
}

export function useCart() {
  const ctx = useContext(CartContext)
  if (!ctx) throw new Error("useCart must be used within a CartProvider")
  return ctx
}

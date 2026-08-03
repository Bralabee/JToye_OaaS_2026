"use client"

import { useEffect, useState } from "react"
import {
  canAdoptCart,
  cartStorageKey,
  getCurrentCustomerId,
} from "@/lib/cart-identity"

interface CartUpdatedDetail {
  slug: string
  itemCount: number
  totalPennies: number
}

function readCount(slug: string): number {
  if (typeof window === "undefined") return 0
  try {
    const raw = localStorage.getItem(cartStorageKey(slug))
    if (!raw) return 0
    const parsed = JSON.parse(raw) as {
      shopSlug?: string
      owner?: string | null
      items?: { quantity?: number }[]
    }
    if (parsed.shopSlug !== slug) return 0
    // Same ownership rule the provider applies (#459). The provider rewrites a
    // rejected basket and broadcasts, so this only governs the first paint —
    // but the first paint is where a badge reading "3" over somebody else's
    // basket would be seen.
    if (!canAdoptCart(parsed.owner, getCurrentCustomerId())) return 0
    return (parsed.items || []).reduce((sum, i) => sum + (i.quantity || 0), 0)
  } catch {
    return 0
  }
}

/**
 * SSR-safe live cart count for a shop slug. Returns 0 until mounted (and
 * whenever slug is undefined), hydrates from localStorage on mount, then
 * tracks (a) same-document `jtoye:cart-updated` CustomEvents dispatched by
 * CartProvider and (b) cross-tab `storage` events for the slug's key.
 */
export function useCartCount(slug: string | undefined): number {
  const [count, setCount] = useState(0)

  useEffect(() => {
    if (!slug) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- reset when navigating to a slug-less route
      setCount(0)
      return
    }
    // SSR-safe mount-time hydration from localStorage (covered by the
    // set-state-in-effect suppression above — the rule reports once per effect)
    setCount(readCount(slug))

    const onCartUpdated = (e: Event) => {
      const detail = (e as CustomEvent<CartUpdatedDetail>).detail
      if (detail?.slug === slug) setCount(detail.itemCount)
    }
    const onStorage = (e: StorageEvent) => {
      if (e.key === cartStorageKey(slug)) setCount(readCount(slug))
    }

    window.addEventListener("jtoye:cart-updated", onCartUpdated)
    window.addEventListener("storage", onStorage)
    return () => {
      window.removeEventListener("jtoye:cart-updated", onCartUpdated)
      window.removeEventListener("storage", onStorage)
    }
  }, [slug])

  return count
}

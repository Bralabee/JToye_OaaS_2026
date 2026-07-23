"use client"

import { useEffect, useState } from "react"
import {
  ALL_SHOPS_CONTEXT,
  getShopContext,
  subscribeShopContext,
} from "@/lib/shop-context"

/**
 * Reads the persisted dashboard shop-context selection (23-05's switcher) and
 * re-renders the consuming screen whenever it changes — the seam that makes
 * VSA-03's "all shop-scoped screens operate on the selected shop" true.
 *
 * `contextShopId` is `null` in the All-shops context (GROUP_ADMIN), which every
 * consumer treats as "no narrow — today's cross-shop behaviour", so the
 * All-shops path is a strict fall-through with zero day-one regression.
 *
 * IMPORTANT: this is a UX narrow, NOT a security boundary. Reads are already
 * grant-scoped server-side by 23-03 and tenant-scoped by RLS; a tampered
 * localStorage value can only hide rows within the already-authorised set, never
 * widen it, and every write is re-validated by `require(shopId, minRole)`.
 *
 * The state starts at the "all" sentinel and hydrates on mount (rather than
 * initialising straight from `getShopContext()`) so the server-rendered markup
 * and the first client render agree — the same idiom `shop-switcher.tsx` and
 * the sidebar theme toggle use.
 */
export function useShopContext(): {
  contextShopId: string | null
  isAllShops: boolean
} {
  const [value, setValue] = useState<string>(ALL_SHOPS_CONTEXT)

  useEffect(() => {
    // Hydrate from localStorage after mount, then track every subsequent change:
    // same-tab via the 'shopcontext:change' CustomEvent, cross-tab via 'storage'.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; mirrors the shop-switcher.tsx idiom
    setValue(getShopContext())
    return subscribeShopContext(() => setValue(getShopContext()))
  }, [])

  return {
    contextShopId: value === ALL_SHOPS_CONTEXT ? null : value,
    isAllShops: value === ALL_SHOPS_CONTEXT,
  }
}

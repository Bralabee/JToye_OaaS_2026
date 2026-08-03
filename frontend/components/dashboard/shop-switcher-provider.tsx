"use client"

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react"
import { fetchMyShops } from "@/lib/shops-api"
import {
  ALL_SHOPS_CONTEXT,
  getShopContext,
  setShopContext,
} from "@/lib/shop-context"
import type { Shop } from "@/types/api"

/**
 * Shared data source for the (two) dashboard shop-context switchers (WR-06).
 *
 * `ShopSwitcher` is mounted twice — the desktop sidebar and the mobile top bar.
 * Before this provider each instance ran its OWN `fetchMyShops()` on mount (two
 * `GET /api/v1/shops` + two `GET /api/v1/staff/me` per dashboard load) and its OWN
 * hydration effect, so both could write the shop context and the two could
 * disagree visually. This provider issues the fetch and runs the hydration-time
 * defaulting EXACTLY ONCE; the switchers become pure presenters that read the
 * selected value from `useShopContext()` (the shared localStorage-backed seam),
 * so they converge for free.
 *
 * Mounted in `dashboard-shell.tsx` above both switchers. It renders no DOM of its
 * own (a React context provider adds no element), so the MOBL-01-verified 375px
 * responsive shell markup is byte-for-byte unchanged.
 */
export interface ShopSwitcherData {
  /** The caller's read-scoped shop list (server-narrowed by 23-03). */
  shops: Shop[]
  /** Server-authoritative GROUP_ADMIN status (GET /api/v1/staff/me, CR-08). */
  isGroupAdmin: boolean
  /** The caller's Keycloak `sub`, or null before load. */
  userId: string | null
  loading: boolean
  /** A previously-saved selection referred to a now-ungranted shop (D-13). */
  stale: boolean
  /** Clear the stale notice once the operator picks a valid selection. */
  dismissStale: () => void
}

const ShopSwitcherContext = createContext<ShopSwitcherData | null>(null)

export function ShopSwitcherProvider({ children }: { children: ReactNode }) {
  const [shops, setShops] = useState<Shop[]>([])
  const [isGroupAdmin, setIsGroupAdmin] = useState(false)
  const [userId, setUserId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [stale, setStale] = useState(false)

  useEffect(() => {
    let active = true
    fetchMyShops()
      .then(({ shops: fetched, isGroupAdmin: ga, userId: uid }) => {
        if (!active) return
        const saved = getShopContext()
        const grantedIds = fetched.map((s) => s.id)
        // D-13: a saved *specific shop* no longer in the granted set was revoked.
        const savedIsStale =
          saved !== ALL_SHOPS_CONTEXT && !grantedIds.includes(saved)
        // SSR-safe mount-time hydration, and the SINGLE writer for both switchers.
        // (No eslint-disable needed: `react-hooks/set-state-in-effect` only fires on
        // a setState called DIRECTLY in the effect body — these run in the resolved
        // promise's callback. The directives that used to sit here were inert and
        // reported as unused-directive warnings; the rule itself is live and will
        // error if this ever moves into the effect body.)
        setShops(fetched)
        setIsGroupAdmin(ga)
        setUserId(uid)
        setStale(savedIsStale)
        setLoading(false)
        // CR-08/T-23-13-02: persist ONLY the stale correction (D-13). A clean
        // first load must not write a pin — that silently narrowed the cross-shop
        // view. This is the SOLE hydration writer, so the two switchers can never
        // both dispatch 'shopcontext:change' on mount.
        if (savedIsStale) {
          setShopContext(
            ga || fetched.length === 0 ? ALL_SHOPS_CONTEXT : fetched[0].id
          )
        }
      })
      .catch(() => {
        // The error path still settles the loading state (same rejected-promise
        // callback, so no eslint directive is needed here either).
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const dismissStale = useCallback(() => setStale(false), [])

  const value = useMemo<ShopSwitcherData>(
    () => ({ shops, isGroupAdmin, userId, loading, stale, dismissStale }),
    [shops, isGroupAdmin, userId, loading, stale, dismissStale]
  )

  return (
    <ShopSwitcherContext.Provider value={value}>
      {children}
    </ShopSwitcherContext.Provider>
  )
}

/**
 * Read the shared switcher data. Throws if used outside `ShopSwitcherProvider`
 * so a missing mount is a loud wiring bug, not a silent forever-loading switcher.
 */
export function useShopSwitcherData(): ShopSwitcherData {
  const ctx = useContext(ShopSwitcherContext)
  if (!ctx) {
    throw new Error("useShopSwitcherData must be used within a ShopSwitcherProvider")
  }
  return ctx
}

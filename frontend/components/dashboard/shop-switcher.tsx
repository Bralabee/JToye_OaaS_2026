"use client"

import { useEffect, useState } from "react"
import { Store, ChevronsUpDown } from "lucide-react"
import { cn } from "@/lib/utils"
import { fetchMyShops } from "@/lib/shops-api"
import { getShopContext, setShopContext, ALL_SHOPS_CONTEXT } from "@/lib/shop-context"
import type { Shop } from "@/types/api"

/**
 * Persisted shop-context switcher (VSA-03). Reads the caller's read-scoped shop
 * list (23-03), lands a GROUP_ADMIN on "All shops" (D-06), persists the selection
 * in localStorage (D-07), and offers the group-wide "apply to all shops" affordance
 * only to a GROUP_ADMIN in the "All shops" context (D-08). A non-GROUP_ADMIN sees
 * only their granted shops; a single grant is pinned. A stale/revoked saved
 * selection degrades to an access-required notice rather than crashing (D-13).
 *
 * `variant="sidebar"` styles it for the always-dark desktop sidebar chrome;
 * `variant="topbar"` (default) is theme-adaptive for the mobile top bar.
 */
export function ShopSwitcher({
  variant = "topbar",
  className,
}: {
  variant?: "sidebar" | "topbar"
  className?: string
}) {
  const [shops, setShops] = useState<Shop[]>([])
  const [isGroupAdmin, setIsGroupAdmin] = useState(false)
  const [selected, setSelected] = useState<string>(ALL_SHOPS_CONTEXT)
  const [loading, setLoading] = useState(true)
  const [stale, setStale] = useState(false)

  useEffect(() => {
    let active = true
    fetchMyShops()
      .then(({ shops: fetched, isGroupAdmin: ga }) => {
        if (!active) return
        const saved = getShopContext()
        const grantedIds = fetched.map((s) => s.id)
        // "all" is a real context only for a GROUP_ADMIN; a non-GA falls back to
        // their first granted shop. The default landing (no saved value) is
        // "All shops" for a GA (D-06), else the first grant.
        const fallback =
          ga || fetched.length === 0 ? ALL_SHOPS_CONTEXT : fetched[0].id
        // D-13: a saved *specific shop* no longer in the granted set was revoked —
        // surface an access-required notice and reset, never crash.
        const savedIsStale = saved !== ALL_SHOPS_CONTEXT && !grantedIds.includes(saved)
        const next =
          savedIsStale || saved === ALL_SHOPS_CONTEXT ? fallback : saved
        // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; mirrors the sidebar.tsx theme idiom
        setShops(fetched)
        setIsGroupAdmin(ga)
        setSelected(next)
        setStale(savedIsStale)
        setLoading(false)
        if (next !== saved) setShopContext(next)
      })
      .catch(() => {
        if (!active) return
        // eslint-disable-next-line react-hooks/set-state-in-effect -- error settles the loading state
        setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const onSelect = (id: string) => {
    setSelected(id)
    setStale(false)
    setShopContext(id) // persists + broadcasts 'shopcontext:change'
  }

  const dark = variant === "sidebar"

  if (loading) {
    return (
      <div className={cn("min-w-0", className)} data-testid="shop-switcher" aria-busy="true">
        <div
          className={cn(
            "h-9 w-full animate-pulse rounded-md",
            dark ? "bg-slate-800" : "bg-slate-200/70 dark:bg-slate-700/60"
          )}
        />
      </div>
    )
  }

  // Non-GROUP_ADMIN with exactly one grant → pinned label, no dropdown (D-06).
  if (!isGroupAdmin && shops.length === 1) {
    return (
      <div
        className={cn(
          "flex min-w-0 items-center gap-2 rounded-md border px-3 py-2 text-sm font-medium",
          dark
            ? "border-slate-700 bg-slate-800 text-white"
            : "border-slate-200 bg-white text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-white",
          className
        )}
        data-testid="shop-switcher"
      >
        <Store className="h-4 w-4 shrink-0 text-orange-500" aria-hidden="true" />
        <span className="truncate">{shops[0].name}</span>
      </div>
    )
  }

  return (
    <div className={cn("min-w-0", className)} data-testid="shop-switcher">
      <label htmlFor="shop-context-select" className="sr-only">
        Shop context
      </label>
      <div className="relative">
        <Store
          className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-orange-500"
          aria-hidden="true"
        />
        <select
          id="shop-context-select"
          value={selected}
          onChange={(e) => onSelect(e.target.value)}
          className={cn(
            "w-full max-w-full cursor-pointer appearance-none truncate rounded-md border py-2 pl-8 pr-8 text-sm font-medium focus:outline-none focus:ring-2 focus:ring-orange-500",
            dark
              ? "border-slate-700 bg-slate-800 text-white"
              : "border-slate-200 bg-white text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
          )}
        >
          {isGroupAdmin && <option value={ALL_SHOPS_CONTEXT}>All shops</option>}
          {shops.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
        <ChevronsUpDown
          className={cn(
            "pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2",
            dark ? "text-slate-400" : "text-slate-400"
          )}
          aria-hidden="true"
        />
      </div>

      {/* D-08: the group-wide affordance renders ONLY for a GROUP_ADMIN AND only
          in the "All shops" context — both conditions in the same guard. The
          server also re-gates group-wide writes to GROUP_ADMIN (T-23-05-02). */}
      {isGroupAdmin && selected === ALL_SHOPS_CONTEXT && (
        <p
          data-testid="apply-to-all"
          className={cn(
            "mt-1.5 inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium",
            dark ? "bg-slate-800 text-orange-300" : "bg-orange-50 text-orange-700 dark:bg-slate-800 dark:text-orange-300"
          )}
        >
          Apply to all shops
        </p>
      )}

      {/* D-13: a revoked saved selection degrades to an access-required notice. */}
      {stale && (
        <p
          role="alert"
          data-testid="shop-switcher-stale"
          className={cn(
            "mt-1.5 text-xs",
            dark ? "text-amber-300" : "text-amber-600 dark:text-amber-300"
          )}
        >
          Your previously selected shop is no longer available — access required. Showing your available shops.
        </p>
      )}
    </div>
  )
}

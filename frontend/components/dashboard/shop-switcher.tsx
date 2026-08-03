"use client"

import { Store, ChevronsUpDown, ShieldAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import { setShopContext, ALL_SHOPS_CONTEXT } from "@/lib/shop-context"
import { useShopContext } from "@/hooks/use-shop-context"
import { useShopSwitcherData } from "@/components/dashboard/shop-switcher-provider"

/**
 * Persisted shop-context switcher (VSA-03). Mounted twice — the desktop sidebar
 * and the mobile top bar — but it is ONE control: the switcher reads its data from
 * the shared `ShopSwitcherProvider` (one fetch for both, WR-06) and its SELECTED
 * value from `useShopContext()` (the shared localStorage-backed seam that already
 * subscribes to both the storage and same-tab 'shopcontext:change' events), so the
 * two instances always agree without a remount.
 *
 * A GROUP_ADMIN lands on "All shops" (D-06) and gets the group-wide "apply to all
 * shops" affordance in that context (D-08). A non-GROUP_ADMIN sees only their
 * granted shops; a single grant is pinned, and NO grants render an explanatory
 * no-access state rather than an empty dropdown (#288). A stale/revoked saved
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
  const { shops, isGroupAdmin, loading, stale, dismissStale } = useShopSwitcherData()
  const { contextShopId, isAllShops } = useShopContext()

  // Raw context value ("all" or a shopId). A non-GROUP_ADMIN has no "All shops"
  // entry, so an "all" context DISPLAYS as their first granted shop without ever
  // being persisted — the cross-shop default is preserved for a GROUP_ADMIN, and
  // a non-GA never sees a value with no matching option.
  const rawContext = isAllShops ? ALL_SHOPS_CONTEXT : contextShopId ?? ALL_SHOPS_CONTEXT
  const selected =
    rawContext !== ALL_SHOPS_CONTEXT
      ? rawContext
      : isGroupAdmin || shops.length === 0
        ? ALL_SHOPS_CONTEXT
        : shops[0].id

  const onSelect = (id: string) => {
    dismissStale()
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

  // #288: a non-GROUP_ADMIN holding NO grants (revoked, or never granted) used to
  // fall through to the <select> below with value="all" and no matching option — a
  // blank control that looked broken and explained nothing. The backend already
  // denies every scoped request, so this is honesty about a state the user is
  // genuinely in, not a new restriction. A GROUP_ADMIN with no shops is NOT this
  // case: they hold tenant-wide access and need "All shops" to create the first shop.
  if (!isGroupAdmin && shops.length === 0) {
    return (
      <div className={cn("min-w-0", className)} data-testid="shop-switcher">
        <div role="status" data-testid="shop-switcher-no-access">
          {/* Same bordered-row shape as the single-grant pinned label below, so this
              reads as the switcher in another state rather than a new component. */}
          <div
            className={cn(
              "flex min-w-0 items-center gap-2 rounded-md border px-3 py-2 text-sm font-medium",
              dark
                ? "border-slate-700 bg-slate-800 text-slate-300"
                : "border-slate-200 bg-white text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
            )}
          >
            <ShieldAlert
              className={cn(
                "h-4 w-4 shrink-0",
                dark ? "text-amber-300" : "text-amber-600 dark:text-amber-300"
              )}
              aria-hidden="true"
            />
            <span className="truncate">No shop access</span>
          </div>
          {/* Same placement + type scale as the D-13 stale notice below — but shown
              only where there is vertical room for it, which is a property of the
              VARIANT, not the viewport.

              `topbar` is the mobile bar: a fixed `h-14` (56px) flex row, with the
              switcher in a `max-w-[55%]` (~206px) column. At 375px this sentence
              wraps to ~4 lines (~64px) on top of the ~38px chip and spills out of a
              bar that has no room to grow, over the page content beneath it. Unlike
              the D-13 stale notice — transient, and dismissed on the next selection —
              this state is PERMANENT for a zero-access user, so it would be
              permanently broken. So the chip carries it visually there (it fits), and
              the sentence stays in the accessibility tree via `sr-only`; a mobile user
              also meets the full explanation on any screen that renders its own
              access-required card. */}
          <p
            className={cn(
              variant === "topbar"
                ? "sr-only"
                : "mt-1.5 text-xs text-slate-400"
            )}
          >
            You have not been granted access to any shop. Ask a group admin in your
            business to grant you a shop.
          </p>
        </div>
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

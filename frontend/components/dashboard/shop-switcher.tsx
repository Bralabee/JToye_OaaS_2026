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

/**
 * Dashboard sub-trees where the shop-context switcher has nothing to act on, so
 * mounting it would be a dead control (#450 item 1).
 *
 * Onboarding is per-TENANT: `app/dashboard/onboarding/page.tsx` never reads
 * `useShopContext()` — it carries its own `shopId` field for the application it
 * is creating — and the approvals queue under it lists the tenant's
 * applications, not one shop's. The QA council measured the consequence: a
 * switch on that page fired **0** API calls, against a control arm on
 * `/products` that re-fetched shop-scoped. A control that visibly changes and
 * changes nothing is worse than no control, so it is not rendered here.
 *
 * This is a PRESENTATION rule only. The saved context is untouched — it is still
 * whatever the user last chose, and it is honoured again the moment they leave
 * this sub-tree. Which shop is active stays server-decided.
 *
 * Prefix-matched, so a future `/dashboard/onboarding/<something>` inherits the
 * rule instead of silently reintroducing the dead control.
 */
const TENANT_SCOPED_PREFIXES = ["/dashboard/onboarding"]

export function shopSwitcherApplies(pathname: string | null | undefined): boolean {
  // Unknown route → render. Hiding chrome on a pathname we could not read would
  // fail in the direction that removes a working control.
  if (!pathname) return true
  return !TENANT_SCOPED_PREFIXES.some(
    (p) => pathname === p || pathname.startsWith(`${p}/`)
  )
}

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

  /**
   * Layout classes for a supplementary note rendered UNDER the control (#495, #490).
   *
   * `topbar` is the mobile bar: a fixed `h-14` (56px) `items-center` flex row with
   * the switcher in a `max-w-[55%]` (~206px) column. Extra FLOW content there does
   * not merely spill past the border — it re-centres the whole column and LIFTS
   * the `<select>` out of the bar. Measured in the running app at 375px on a
   * Pixel 7 profile, against the bar's own top edge:
   *
   *   "Apply to all shops"  badge bottom 59 vs a 56px bar (+3 over), select top −5
   *   D-13 stale sentence   note  bottom 95 vs a 56px bar (+39 over), select
   *                         top −40 / bottom −2 — the control is entirely above
   *                         the viewport and cannot be touched at all
   *
   * So the constraint is the fixed bar height, not the margin: shrinking `mt-1.5`
   * leaves a narrower device wrapping the text further and lifting the control
   * again. A note in `topbar` must contribute NO height. `sr-only` is absolutely
   * positioned, so it costs the column nothing while keeping the sentence in the
   * accessibility tree — which is exactly what #476 chose for the #288
   * zero-access sentence. This helper is that decision stated ONCE: #288's
   * sentence was gated and the two older notes were not, and the divergence is
   * the whole bug. A fourth note gets the same treatment by construction.
   */
  const note = (laidOut: string) => (variant === "topbar" ? "sr-only" : laidOut)

  /**
   * #450 item 5b — the id must be unique per MOUNT, not per component.
   *
   * This is one control rendered twice (desktop sidebar + mobile top bar), and
   * BOTH are always in the DOM: each is hidden by a responsive class, not
   * unmounted. So a single literal id produced two nodes on every dashboard
   * route (measured: `document.querySelectorAll('#shop-context-select').length`
   * === 2 on all 13), which is invalid HTML and makes `label[for]` associate
   * with whichever one the parser saw first — on mobile, the one that is
   * `display:none`.
   *
   * `useId()` is the React-idiomatic fix and is deliberately NOT used: the id is
   * a published selector (`e2e/dashboard-mobile.spec.ts` locates the control by
   * `#shop-context-select` in three places, and that file belongs to another
   * lane). The default `topbar` variant therefore keeps the canonical id and the
   * sidebar takes the suffixed one — unique, stable, and no published selector
   * moves.
   */
  const selectId =
    variant === "sidebar" ? "shop-context-select-sidebar" : "shop-context-select"

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
                dark ? "text-amber-300" : "text-amber-700 dark:text-amber-300"
              )}
              aria-hidden="true"
            />
            <span className="truncate">No shop access</span>
          </div>
          {/* Shown only where there is vertical room for it — a property of the
              VARIANT, not the viewport. See `note()` for the measured geometry.
              Unlike the D-13 stale notice this state is PERMANENT for a
              zero-access user, so the chip above carries it visually in the bar
              (it fits) and the sentence stays in the accessibility tree; a mobile
              user also meets the full explanation on any screen that renders its
              own access-required card. */}
          <p className={note("mt-1.5 text-xs text-slate-400")}>
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
      <label htmlFor={selectId} className="sr-only">
        Shop context
      </label>
      <div className="relative">
        {/* The leading glyph is absolutely positioned, so it costs the bar no
            height whichever mark it draws. In `topbar` the D-13 sentence below is
            `sr-only` (it cannot fit — see `note()`), which would otherwise leave a
            SIGHTED mobile user with a silently-reset selection and no signal at
            all; the amber alert mark is that signal's visible substitute. The
            sidebar keeps the sentence itself, so it needs no substitute and its
            appearance is unchanged. */}
        {stale && variant === "topbar" ? (
          <ShieldAlert
            data-testid="shop-switcher-stale-glyph"
            className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-amber-600 dark:text-amber-300"
            aria-hidden="true"
          />
        ) : (
          <Store
            className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-orange-500"
            aria-hidden="true"
          />
        )}
        <select
          id={selectId}
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
          server also re-gates group-wide writes to GROUP_ADMIN (T-23-05-02).
          #495: laid out in the sidebar, `sr-only` in the bar — the badge's own
          20px plus its margin put its bottom at 59 in a 56px bar. The selected
          option still reads "All shops" there, so a mobile GROUP_ADMIN does not
          lose the fact, only the restatement of it. */}
      {isGroupAdmin && selected === ALL_SHOPS_CONTEXT && (
        <p
          data-testid="apply-to-all"
          className={cn(
            note("mt-1.5 inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium"),
            dark ? "bg-slate-800 text-orange-300" : "bg-orange-50 text-orange-700 dark:bg-slate-800 dark:text-orange-300"
          )}
        >
          Apply to all shops
        </p>
      )}

      {/* D-13: a revoked saved selection degrades to an access-required notice.
          #490: the sentence wraps to ~4 lines (64px) in the bar's ~206px column
          and lifted the <select> clean off the top of the screen, so in `topbar`
          it is `sr-only` — still announced (role="alert"), and paired with the
          amber leading glyph above so the change is visible too. */}
      {stale && (
        <p
          role="alert"
          data-testid="shop-switcher-stale"
          className={cn(
            note("mt-1.5 text-xs"),
            dark ? "text-amber-300" : "text-amber-700 dark:text-amber-300"
          )}
        >
          Your previously selected shop is no longer available — access required. Showing your available shops.
        </p>
      )}
    </div>
  )
}

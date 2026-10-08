"use client"

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react"
import { usePathname } from "next/navigation"
import { NoAccessPage } from "@/components/dashboard/no-access-page"
import { hasAnyAccess, useShopSwitcherData } from "@/components/dashboard/shop-switcher-provider"
import { SHOP_ACCESS_DENIED_EVENT } from "@/lib/access-events"

/**
 * D-08 (37-06) — the dashboard shell's one decision about "this person has no access".
 *
 * The fact is the server's: the staff/me answer the shell already reads once for
 * the shop switchers (`ShopSwitcherProvider`). The shell shows {@link NoAccessPage}
 * in place of the page when that answer grants nothing, and re-reads it whenever the
 * api-client reports a `shop-access-denied` refusal ({@link SHOP_ACCESS_DENIED_EVENT}),
 * so access removed mid-session lands here on the next refused read, not in a
 * dashboard of empty states. A refusal on its own decides nothing: a manager refused
 * one shop keeps the dashboard.
 *
 * Before staff/me has answered, or when it could not be read, nothing is decided and
 * the page renders as before. The kitchen route is left alone: its "board stopped"
 * state (37-18, UI-SPEC K2) has to be loud, which this page is not.
 */

interface DashboardAccessState {
  /** The no-access page is showing (and the shell hides navigation that would 403). */
  noAccess: boolean
  observedAccess: boolean
  checking: boolean
  checkFailed: boolean
  checkAgain: () => void
}

const DashboardAccessContext = createContext<DashboardAccessState | null>(null)

/** True while the no-access page shows. False outside the provider (a bare test mount). */
export function useDashboardNoAccess(): boolean {
  return useContext(DashboardAccessContext)?.noAccess ?? false
}

function isKitchenRoute(pathname: string | null): boolean {
  return !!pathname && (pathname === "/dashboard/kitchen" || pathname.startsWith("/dashboard/kitchen/"))
}

export function DashboardAccessProvider({ children }: { children: ReactNode }) {
  const { access, observedAccess, reloadAccess } = useShopSwitcherData()
  const pathname = usePathname()
  const [checking, setChecking] = useState(false)
  const [checkFailed, setCheckFailed] = useState(false)
  /** One re-read at a time for refusal-driven checks: a page that fires five reads
   *  and gets five refusals costs one staff/me, not five. */
  const refusalCheckInFlight = useRef(false)

  useEffect(() => {
    const onRefused = () => {
      if (refusalCheckInFlight.current) return
      refusalCheckInFlight.current = true
      reloadAccess()
        .catch(() => {
          // A failed re-read decides nothing; the page keeps its own refusal state.
        })
        .finally(() => {
          refusalCheckInFlight.current = false
        })
    }
    window.addEventListener(SHOP_ACCESS_DENIED_EVENT, onRefused)
    return () => window.removeEventListener(SHOP_ACCESS_DENIED_EVENT, onRefused)
  }, [reloadAccess])

  const checkAgain = useCallback(() => {
    setChecking(true)
    setCheckFailed(false)
    reloadAccess()
      .catch(() => setCheckFailed(true))
      .finally(() => setChecking(false))
  }, [reloadAccess])

  const noAccess = !isKitchenRoute(pathname) && access !== null && !hasAnyAccess(access)

  const value = useMemo<DashboardAccessState>(
    () => ({ noAccess, observedAccess, checking, checkFailed, checkAgain }),
    [noAccess, observedAccess, checking, checkFailed, checkAgain]
  )

  return (
    <DashboardAccessContext.Provider value={value}>{children}</DashboardAccessContext.Provider>
  )
}

/** Renders the page, or the no-access page in its place. */
export function DashboardAccessContent({ children }: { children: ReactNode }) {
  const state = useContext(DashboardAccessContext)
  if (state?.noAccess) {
    return (
      <NoAccessPage
        observedAccess={state.observedAccess}
        checking={state.checking}
        checkFailed={state.checkFailed}
        onCheckAgain={state.checkAgain}
      />
    )
  }
  return <>{children}</>
}

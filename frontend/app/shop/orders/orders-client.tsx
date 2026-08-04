"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import Link from "next/link"
import {
  Package, Clock, CheckCircle2, ChefHat, CircleDot,
  XCircle, ArrowRight, Loader2, Store, AlertTriangle, RefreshCw
} from "lucide-react"
import { getCustomerSession } from "@/lib/customer-auth"
import { CustomerSignInPrompt } from "@/components/storefront/customer-signin-prompt"
import {
  ORDERS_FETCH_SIZE,
  ORDERS_PAGE_SIZE,
  ORDER_STATUS_OPTIONS,
  deriveOrdersView,
  isActiveOrder,
  isCapped,
  toOrdersLoad,
  type OrderStatusFilter,
  type OrderSummary,
  type OrdersLoad,
} from "@/lib/customer-orders"

/**
 * The interactive half of "My Orders" (issues #463, #467).
 *
 * WHAT MOVED AND WHY. This page used to be `"use client"` end to end, so a
 * customer waited through bundle download -> hydration -> `useEffect` ->
 * `fetch` before ANY of it appeared; the spinner covered all four. The list
 * itself is now rendered by the server component in page.tsx and handed here as
 * `initial`, so first paint carries real orders. This component owns only what
 * genuinely needs the browser: the filters, pagination, the live-refresh poll
 * and retry.
 *
 * `initial` is therefore normally non-null and there is NO spinner on the happy
 * path. It is null in exactly one case — the access-token cookie has expired
 * while the refresh cookie is still alive — because re-issuing cookies is
 * something only a route handler can do (a server component cannot write them).
 * That path probes /api/customer-auth/session, which performs the renewal, and
 * then fetches. Dropping it would have silently undone #465 and signed
 * customers out again after 300 seconds.
 */

/**
 * Poll cadence for orders that are still in flight (issue #463 asked for this
 * to be justified or changed — it is both).
 *
 * KEPT at 15s: this is a live fulfilment view. A customer watching a PREPARING
 * order expects READY to appear without a manual reload, and 15s is the
 * difference between "it updated" and "this page is broken". It remains gated
 * on there being a non-terminal order, so a history of completed orders polls
 * nothing at all.
 *
 * CHANGED: it now stops while the tab is hidden. Previously it ran every 15s
 * forever in a background tab — a request the customer cannot see, on their
 * data and battery, against the API. It restarts (and fires immediately) on
 * becoming visible, so returning to the tab shows current state rather than a
 * value up to 15s stale.
 */
const ORDERS_POLL_MS = 15000

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    day: "numeric", month: "short", hour: "2-digit", minute: "2-digit"
  })
}

const STATUS_CONFIG: Record<string, { icon: typeof Clock; color: string; label: string }> = {
  PENDING: { icon: Clock, color: "text-amber-500 bg-amber-50", label: "Received" },
  CONFIRMED: { icon: CircleDot, color: "text-blue-500 bg-blue-50", label: "Confirmed" },
  PREPARING: { icon: ChefHat, color: "text-amber-800 bg-amber-50", label: "Preparing" },
  READY: { icon: Package, color: "text-emerald-500 bg-emerald-50", label: "Ready" },
  COMPLETED: { icon: CheckCircle2, color: "text-slate-400 bg-slate-50", label: "Completed" },
  CANCELLED: { icon: XCircle, color: "text-red-500 bg-red-50", label: "Cancelled" },
}

/*
 * Hover lift is gated behind `(hover: hover) and (pointer: fine)`.
 *
 * `future.hoverOnlyWhenSupported` is NOT enabled in tailwind.config.ts, so a
 * bare `hover:`/`group-hover:` utility also fires on touch — the card would lift
 * on tap and STAY lifted after the finger left, because nothing ever un-hovers
 * it. This is a mobile-first customer surface, so that is the common case, not
 * the edge one. Touch gets `active:` press feedback instead, which is the
 * correct analogue: it confirms the tap and ends with it.
 *
 * Only transform/opacity/colour are transitioned (never `transition-all`, which
 * was here before and animates layout properties off the compositor).
 */
const CARD_MOTION =
  "transition-[transform,box-shadow] duration-200 ease-out will-change-transform " +
  "active:scale-[0.99] " +
  "[@media(hover:hover)_and_(pointer:fine)]:group-hover:-translate-y-0.5 " +
  "[@media(hover:hover)_and_(pointer:fine)]:group-hover:shadow-md " +
  "motion-reduce:transition-none motion-reduce:active:scale-100 " +
  "motion-reduce:[@media(hover:hover)_and_(pointer:fine)]:group-hover:translate-y-0"

function OrderCard({ order, shopSlug, email }: { order: OrderSummary; shopSlug?: string; email?: string }) {
  const cfg = STATUS_CONFIG[order.status] || STATUS_CONFIG.PENDING
  const Icon = cfg.icon
  const active = isActiveOrder(order)
  // WR-09: never embed the customer email in tracking URLs (PII in query
  // strings lands in history/proxy logs/analytics). It is handed over via a
  // sessionStorage handoff on click; the destination pages also pre-fill from
  // the cookie-backed customer session.
  const trackUrl = shopSlug
    ? `/shop/${shopSlug}/orders/${order.orderNumber}`
    : `/track?order=${order.orderNumber}`

  return (
    <Link
      href={trackUrl}
      onClick={() => {
        try {
          if (email) sessionStorage.setItem("jtoye-track-email", email)
        } catch {
          /* ignore — destination falls back to session pre-fill / prompt */
        }
      }}
      className="block group"
    >
      <div className={`rounded-xl bg-white border ${active ? "border-amber-300 shadow-sm" : "border-cream-100"} p-4 ${CARD_MOTION}`}>
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ${cfg.color}`}>
                <Icon className="h-3 w-3" />
                {cfg.label}
                {active && <span className="h-1.5 w-1.5 rounded-full bg-current animate-pulse" />}
              </span>
            </div>
            <p className="text-sm font-semibold text-slate-900">{order.shopName}</p>
            <p className="text-xs text-slate-600 mt-0.5">
              {order.itemCount} item{order.itemCount !== 1 ? "s" : ""} &middot; {formatPrice(order.totalAmountPennies)}
            </p>
            <p className="text-xs text-slate-400 mt-1">{formatDate(order.createdAt)}</p>
          </div>
          <div className="flex items-center gap-1 text-slate-400 [@media(hover:hover)_and_(pointer:fine)]:group-hover:text-amber-700 transition-colors mt-1">
            <span className="text-xs font-medium">{active ? "Track" : "View"}</span>
            <ArrowRight className="h-4 w-4" />
          </div>
        </div>

        {/* Mini order number */}
        <p className="mt-2 text-xs font-mono text-slate-300 truncate">{order.orderNumber}</p>
      </div>
    </Link>
  )
}

/**
 * The failure state — issue #467's durable half.
 *
 * This is the whole point of the ticket. A failed request used to land in the
 * SAME branch as a successful empty one and print "No orders found for this
 * email.", which is a confident wrong answer: the customer is told they have no
 * orders when the truth is that nobody managed to ask. It is invisible to them,
 * to a screenshot, and to any test that only asserts the page rendered.
 *
 * So this says the request failed, does not speculate about how many orders
 * exist, and offers the one action that can help.
 */
function OrdersError({
  reason,
  onRetry,
  retrying,
}: {
  reason: "upstream" | "unauthenticated"
  onRetry: () => void
  retrying: boolean
}) {
  if (reason === "unauthenticated") {
    return (
      <CustomerSignInPrompt
        message="Your session has expired. Sign in again to see your order history."
        nextPath="/shop/orders"
      />
    )
  }
  return (
    <div className="mt-10 text-center" data-testid="orders-error" role="alert">
      <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-50">
        <AlertTriangle className="h-6 w-6 text-red-500" />
      </div>
      <p className="text-sm font-semibold text-slate-900">
        We couldn&apos;t load your orders
      </p>
      <p className="mt-1 text-sm text-slate-600">
        Something went wrong reaching our servers. Your orders are safe — this is a
        problem on our side, not a sign that you have none.
      </p>
      <button
        type="button"
        data-testid="orders-retry"
        onClick={onRetry}
        disabled={retrying}
        className="mt-5 inline-flex items-center gap-2 rounded-full bg-oxblood px-5 py-2.5 text-sm font-semibold text-white transition-[transform,background-color] duration-150 ease-out active:scale-[0.97] disabled:opacity-60 disabled:active:scale-100 [@media(hover:hover)_and_(pointer:fine)]:hover:bg-oxblood-700 motion-reduce:transition-none motion-reduce:active:scale-100"
      >
        <RefreshCw className={`h-4 w-4 ${retrying ? "animate-spin" : ""}`} />
        {retrying ? "Retrying…" : "Try again"}
      </button>
      <div className="mt-4">
        <Link href="/shop" className="text-sm text-amber-700 [@media(hover:hover)_and_(pointer:fine)]:hover:text-amber-800">
          Browse shops instead
        </Link>
      </div>
    </div>
  )
}

export function OrdersClient({
  initial,
  email: initialEmail,
}: {
  /** Server-rendered first load. Null only when the session needs renewing. */
  initial: OrdersLoad | null
  email: string | null
}) {
  const [load, setLoad] = useState<OrdersLoad | null>(initial)
  const [email, setEmail] = useState<string | null>(initialEmail)
  const [retrying, setRetrying] = useState(false)
  // A background poll that fails must NOT replace orders already on screen with
  // an error page — the data is still true, only the refresh failed. This flag
  // carries that weaker signal.
  const [staleRefresh, setStaleRefresh] = useState(false)
  const [signedOut, setSignedOut] = useState(false)
  // Synced in an effect, never during render: a render-phase ref write is unsafe
  // under React 19 concurrent rendering (a render can be discarded or replayed),
  // and the ESLint rule that catches it fails the build. An effect is sufficient
  // here because the only reader is fetchOrders, which runs from the poll, the
  // retry button or mount-renewal — all after commit, never during render.
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
  }, [load])

  /** One fetch path, shared by mount-renewal, poll and retry. */
  const fetchOrders = useCallback(async (): Promise<OrdersLoad> => {
    try {
      const res = await fetch(`/api/customer-orders?size=${ORDERS_FETCH_SIZE}`, {
        credentials: "include",
        cache: "no-store",
      })
      const body = await res.json().catch(() => null)
      return toOrdersLoad(res.status, body)
    } catch {
      return { state: "error", reason: "upstream" }
    }
  }, [])

  const applyResult = useCallback((next: OrdersLoad) => {
    const current = loadRef.current
    if (next.state === "error" && current?.state === "ok") {
      // Keep the good data; flag that it may be out of date.
      setStaleRefresh(true)
      return
    }
    setStaleRefresh(false)
    setLoad(next)
  }, [])

  const refresh = useCallback(async () => {
    applyResult(await fetchOrders())
  }, [applyResult, fetchOrders])

  const retry = useCallback(async () => {
    setRetrying(true)
    const next = await fetchOrders()
    setStaleRefresh(false)
    setLoad(next)
    setRetrying(false)
  }, [fetchOrders])

  // Renewal path only. `initial` is non-null on every normal load, so this
  // effect does nothing and the first paint is not gated behind it.
  useEffect(() => {
    if (initial !== null) return
    let cancelled = false
    getCustomerSession().then(async (session) => {
      if (cancelled) return
      if (!session) {
        setSignedOut(true)
        return
      }
      setEmail(session.profile.email)
      const next = await fetchOrders()
      if (!cancelled) setLoad(next)
    })
    return () => {
      cancelled = true
    }
  }, [initial, fetchOrders])

  const orders = load?.state === "ok" ? load.orders : []
  const hasActive = orders.some(isActiveOrder)

  // Live refresh: only while something is actually in flight, and only while
  // the customer can see it.
  useEffect(() => {
    if (!hasActive) return
    let timer: ReturnType<typeof setInterval> | null = null
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
    }
    const start = () => {
      if (timer === null) timer = setInterval(refresh, ORDERS_POLL_MS)
    }
    const sync = () => {
      if (document.visibilityState === "visible") {
        start()
      } else {
        stop()
      }
    }
    const onVisible = () => {
      // Coming back to the tab: catch up immediately rather than waiting out
      // the remainder of an interval that was never running.
      if (document.visibilityState === "visible") refresh()
      sync()
    }
    sync()
    document.addEventListener("visibilitychange", onVisible)
    return () => {
      stop()
      document.removeEventListener("visibilitychange", onVisible)
    }
  }, [hasActive, refresh])

  // Filter + pagination state (STFR-05)
  const [statusFilter, setStatusFilterValue] = useState<OrderStatusFilter>("ALL")
  const [dateFrom, setDateFromValue] = useState<string>("")
  const [page, setPage] = useState(1)

  // Page reset happens in the setters, NOT in an effect. Resetting via
  // `useEffect(() => setPage(1), [statusFilter, dateFrom])` is the obvious shape
  // and is what the ESLint gate rejects: a synchronous setState inside an effect
  // renders the new filter against the OLD page first, then renders again — a
  // cascading render, and a visible flash of the wrong page on a slow device.
  // Changing a filter and going to page 1 are one user intent, so they belong in
  // one state update.
  const setStatusFilter = useCallback((value: OrderStatusFilter) => {
    setStatusFilterValue(value)
    setPage(1)
  }, [])
  const setDateFrom = useCallback((value: string) => {
    setDateFromValue(value)
    setPage(1)
  }, [])

  const { paged, filtered, totalPages } = useMemo(
    () => deriveOrdersView(orders, { statusFilter, dateFrom, page, pageSize: ORDERS_PAGE_SIZE }),
    [orders, statusFilter, dateFrom, page]
  )

  if (signedOut) {
    return (
      <CustomerSignInPrompt
        message="Sign in to view your order history and track deliveries."
        nextPath="/shop/orders"
      />
    )
  }

  // Renewal in progress. The ONLY remaining spinner, and it is reached only
  // when the access cookie has aged out mid-session.
  if (load === null) {
    return (
      <div className="mx-auto max-w-lg px-4 py-16 text-center">
        <Loader2 className="mx-auto h-8 w-8 animate-spin text-amber-500" />
        <p className="mt-3 text-sm text-slate-600">Restoring your session…</p>
      </div>
    )
  }

  if (load.state === "error") {
    return (
      <div className="mx-auto max-w-lg px-4 sm:px-6 py-6">
        <h1 className="text-xl font-bold text-slate-900">My Orders</h1>
        {/* Deliberately NOT "0 orders": the count is unknown when the request
            failed, and printing 0 is the same lie as the empty state was. */}
        {email && <p className="text-sm text-slate-400 mt-1">{email}</p>}
        <OrdersError reason={load.reason} onRetry={retry} retrying={retrying} />
      </div>
    )
  }

  const activeOrders = paged.filter(isActiveOrder)
  const pastOrders = paged.filter((o) => !isActiveOrder(o))
  const capped = isCapped(load)

  return (
    <div className="mx-auto max-w-lg px-4 sm:px-6 py-6">
      <h1 className="text-xl font-bold text-slate-900">My Orders</h1>
      <p className="text-sm text-slate-600 mt-1">
        {load.totalElements} order{load.totalElements !== 1 ? "s" : ""}
        {email && <span className="text-slate-400"> &middot; {email}</span>}
      </p>

      {/* A refresh failed while orders were already on screen. Weaker than the
          error page on purpose — the list below is real, just possibly stale. */}
      {staleRefresh && (
        <p
          className="mt-3 flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800"
          data-testid="orders-stale"
          role="status"
        >
          <AlertTriangle className="h-3.5 w-3.5 shrink-0" />
          Couldn&apos;t check for updates just now. Showing the orders we last loaded.
        </p>
      )}

      {/* Filters (STFR-05) */}
      {orders.length > 0 && (
        <div className="mt-5 grid grid-cols-1 sm:grid-cols-2 gap-3">
          <label className="block">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-600">Status</span>
            <select
              data-testid="orders-status-filter"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as OrderStatusFilter)}
              className="mt-1 w-full rounded-lg border border-cream-100 bg-white px-3 py-2 text-sm text-slate-900 focus:border-amber-400 focus:outline-none focus:ring-1 focus:ring-amber-400"
            >
              {ORDER_STATUS_OPTIONS.map((s) => (
                <option key={s} value={s}>
                  {s === "ALL" ? "All statuses" : (STATUS_CONFIG[s]?.label ?? s)}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-600">From date</span>
            <input
              type="date"
              data-testid="orders-date-from"
              value={dateFrom}
              onChange={(e) => setDateFrom(e.target.value)}
              className="mt-1 w-full rounded-lg border border-cream-100 bg-white px-3 py-2 text-sm text-slate-900 focus:border-amber-400 focus:outline-none focus:ring-1 focus:ring-amber-400"
            />
          </label>
        </div>
      )}

      {/* Filter result summary */}
      {orders.length > 0 && (
        <p className="mt-3 text-xs text-slate-400">
          Showing {paged.length} of {filtered.length} filtered order{filtered.length !== 1 ? "s" : ""}
        </p>
      )}

      {/* The page cap, STATED. The API caps `size` at 100 silently, so without
          this the customer is shown a truncated history as if it were all of it. */}
      {capped && (
        <p className="mt-1 text-xs text-slate-400" data-testid="orders-capped">
          Showing your {orders.length} most recent orders of {load.totalElements}.
        </p>
      )}

      {/* Active orders */}
      {activeOrders.length > 0 && (
        <section className="mt-6">
          <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">
            Active ({activeOrders.length})
          </h2>
          <div className="space-y-3">
            {activeOrders.map((order) => (
              <OrderCard key={order.orderNumber} order={order} email={email || undefined} />
            ))}
          </div>
        </section>
      )}

      {/* Past orders */}
      {pastOrders.length > 0 && (
        <section className="mt-8">
          <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">
            Past ({pastOrders.length})
          </h2>
          <div className="space-y-3">
            {pastOrders.map((order) => (
              <OrderCard key={order.orderNumber} order={order} email={email || undefined} />
            ))}
          </div>
        </section>
      )}

      {/* The genuine empty state. Reachable ONLY through `state: "ok"` — a
          failed request cannot land here, which is the #467 fix. */}
      {orders.length === 0 && (
        <div className="mt-12 text-center" data-testid="orders-empty">
          <Package className="mx-auto h-12 w-12 text-slate-200" />
          <p className="mt-3 text-sm text-slate-600">No orders found for this email.</p>
          <Link
            href="/shop"
            className="mt-4 inline-flex items-center gap-2 text-sm text-amber-700 [@media(hover:hover)_and_(pointer:fine)]:hover:text-amber-800"
          >
            <Store className="h-4 w-4" />
            Browse shops
          </Link>
        </div>
      )}

      {/* Pagination controls */}
      {filtered.length > 0 && (
        <div className="mt-6 flex items-center justify-between gap-3">
          <button
            type="button"
            data-testid="orders-prev-page"
            onClick={() => setPage((p) => Math.max(1, p - 1))}
            disabled={page <= 1}
            className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 transition-[transform,background-color] duration-150 ease-out active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40 disabled:active:scale-100 [@media(hover:hover)_and_(pointer:fine)]:hover:bg-slate-50 motion-reduce:transition-none motion-reduce:active:scale-100"
          >
            Previous
          </button>
          <span className="text-xs text-slate-600" data-testid="orders-page-label">
            Page {page} of {totalPages}
          </span>
          <button
            type="button"
            data-testid="orders-next-page"
            onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
            disabled={page >= totalPages || totalPages === 0}
            className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 transition-[transform,background-color] duration-150 ease-out active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40 disabled:active:scale-100 [@media(hover:hover)_and_(pointer:fine)]:hover:bg-slate-50 motion-reduce:transition-none motion-reduce:active:scale-100"
          >
            Next
          </button>
        </div>
      )}

      {filtered.length === 0 && orders.length > 0 && (
        <p className="mt-8 text-center text-sm text-slate-600">No orders match the selected filters.</p>
      )}

      {/* Auto-refresh indicator */}
      {hasActive && (
        <p className="mt-6 text-center text-xs text-slate-400">
          <span className="inline-flex items-center gap-1">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
            Live updates every 15 seconds
          </span>
        </p>
      )}
    </div>
  )
}

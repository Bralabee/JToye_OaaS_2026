"use client"

import { useEffect, useState, useCallback, useMemo } from "react"
import Link from "next/link"
import {
  Package, Clock, CheckCircle2, ChefHat, CircleDot,
  XCircle, ArrowRight, Loader2, Store
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"
import { RequireCustomerAuth } from "@/components/storefront/require-customer-auth"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { BrandPlaceholder } from "@/components/storefront/brand-placeholder"

export interface OrderSummary {
  orderNumber: string
  status: string
  shopName: string
  totalAmountPennies: number
  itemCount: number
  createdAt: string
  updatedAt: string
}

export const ORDERS_PAGE_SIZE = 10

export const ORDER_STATUS_OPTIONS = [
  "ALL",
  "PENDING",
  "CONFIRMED",
  "PREPARING",
  "READY",
  "COMPLETED",
  "CANCELLED",
] as const

export type OrderStatusFilter = typeof ORDER_STATUS_OPTIONS[number]

/**
 * Pure filter + pagination derivation for the customer orders page.
 * Extracted so it can be unit-tested without rendering the React component.
 *
 * - statusFilter === "ALL" disables the status filter.
 * - dateFrom is an ISO yyyy-mm-dd string (from <input type="date">). Empty
 *   string disables the date filter. Orders with createdAt earlier than
 *   the start of the selected day are excluded.
 * - pageSize must be > 0; callers should use ORDERS_PAGE_SIZE.
 * - totalPages is clamped to at least 1 so the UI always has a label.
 * - paged returns the slice for the requested page. An overflow page
 *   (page > totalPages) returns an empty slice — the UI effect resets
 *   `page` to 1 when the filters change.
 */
export function deriveOrdersView(
  orders: OrderSummary[],
  opts: {
    statusFilter: OrderStatusFilter | string
    dateFrom: string
    page: number
    pageSize: number
  }
): { filtered: OrderSummary[]; paged: OrderSummary[]; totalPages: number } {
  const fromTs = opts.dateFrom ? new Date(opts.dateFrom).getTime() : null
  const filtered = orders.filter((o) => {
    if (opts.statusFilter !== "ALL" && o.status !== opts.statusFilter) return false
    if (fromTs !== null && !Number.isNaN(fromTs)) {
      const created = new Date(o.createdAt).getTime()
      if (Number.isNaN(created) || created < fromTs) return false
    }
    return true
  })
  const pageSize = opts.pageSize > 0 ? opts.pageSize : 1
  const start = Math.max(0, (opts.page - 1) * pageSize)
  return {
    filtered,
    paged: filtered.slice(start, start + pageSize),
    totalPages: Math.max(1, Math.ceil(filtered.length / pageSize)),
  }
}

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    day: "numeric", month: "short", hour: "2-digit", minute: "2-digit"
  })
}

type StatusBadgeVariant = "warning" | "info" | "brand" | "success" | "subtle" | "danger"

const STATUS_CONFIG: Record<string, { icon: typeof Clock; variant: StatusBadgeVariant; label: string }> = {
  PENDING: { icon: Clock, variant: "warning", label: "Received" },
  CONFIRMED: { icon: CircleDot, variant: "info", label: "Confirmed" },
  PREPARING: { icon: ChefHat, variant: "brand", label: "Preparing" },
  READY: { icon: Package, variant: "success", label: "Ready" },
  COMPLETED: { icon: CheckCircle2, variant: "subtle", label: "Completed" },
  CANCELLED: { icon: XCircle, variant: "danger", label: "Cancelled" },
}

function OrderCard({ order, shopSlug, email }: { order: OrderSummary; shopSlug?: string; email?: string }) {
  const cfg = STATUS_CONFIG[order.status] || STATUS_CONFIG.PENDING
  const Icon = cfg.icon
  const isActive = !["COMPLETED", "CANCELLED"].includes(order.status)
  const emailParam = email ? `?email=${encodeURIComponent(email)}` : ""
  const trackUrl = shopSlug
    ? `/shop/${shopSlug}/orders/${order.orderNumber}${emailParam}`
    : `/track?order=${order.orderNumber}${email ? `&email=${encodeURIComponent(email)}` : ""}`

  return (
    <Link href={trackUrl} className="block group focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus rounded-md">
      <Card variant={isActive ? "lifted" : "default"} className="p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <Badge variant={cfg.variant} size="sm">
                <Icon className="h-3 w-3" />
                {cfg.label}
                {isActive && <span className="h-1.5 w-1.5 rounded-full bg-current animate-pulse motion-reduce:animate-none" />}
              </Badge>
            </div>
            <p className="font-display text-sm font-semibold text-ink-primary">{order.shopName}</p>
            <p className="text-xs text-ink-tertiary mt-0.5">
              {order.itemCount} item{order.itemCount !== 1 ? "s" : ""} &middot;{" "}
              <span className="font-mono tabular-nums font-semibold text-ink-primary">{formatPrice(order.totalAmountPennies)}</span>
            </p>
            <p className="text-[10px] text-ink-tertiary mt-1">{formatDate(order.createdAt)}</p>
          </div>
          <div className="flex items-center gap-1 text-ink-tertiary group-hover:text-brand-primary transition-colors mt-1">
            <span className="text-xs font-medium">{isActive ? "Track" : "View"}</span>
            <ArrowRight className="h-4 w-4" />
          </div>
        </div>

        {/* Mini order number */}
        <p className="mt-2 text-[9px] font-mono tabular-nums text-ink-tertiary truncate">{order.orderNumber}</p>
      </Card>
    </Link>
  )
}

export default function CustomerOrdersPage() {
  return (
    <RequireCustomerAuth message="Sign in to view your order history and track deliveries.">
      <CustomerOrdersContent />
    </RequireCustomerAuth>
  )
}

function CustomerOrdersContent() {
  const [orders, setOrders] = useState<OrderSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [email, setEmail] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getCustomerSession().then((session) => {
      if (cancelled) return
      if (session) setEmail(session.profile.email)
      else setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const fetchOrders = useCallback(async () => {
    if (!email) {
      setLoading(false)
      return
    }
    try {
      // NOTE: /public/orders?email= has a soft enumeration risk — tracked as a
      // milestone-4+ follow-up. See
      // .planning/phases/10-storefront-marketing-render-missing-customer-routes/10-RESEARCH.md §Pitfall 5
      const res = await publicApiClient.get<OrderSummary[]>(
        "/public/orders",
        { params: { email } }
      )
      setOrders(res.data)
    } catch {
      setOrders([])
    } finally {
      setLoading(false)
    }
  }, [email])

  useEffect(() => {
    if (email) fetchOrders()
    else setLoading(false)
  }, [email, fetchOrders])

  // Auto-refresh for active orders
  useEffect(() => {
    const hasActive = orders.some(o => !["COMPLETED", "CANCELLED"].includes(o.status))
    if (!hasActive) return
    const interval = setInterval(fetchOrders, 15000)
    return () => clearInterval(interval)
  }, [orders, fetchOrders])

  // Filter + pagination state (STFR-05)
  const [statusFilter, setStatusFilter] = useState<OrderStatusFilter>("ALL")
  const [dateFrom, setDateFrom] = useState<string>("")
  const [page, setPage] = useState(1)

  // Reset to page 1 whenever filters change
  useEffect(() => {
    setPage(1)
  }, [statusFilter, dateFrom])

  const { paged, filtered, totalPages } = useMemo(
    () => deriveOrdersView(orders, { statusFilter, dateFrom, page, pageSize: ORDERS_PAGE_SIZE }),
    [orders, statusFilter, dateFrom, page]
  )

  const activeOrders = paged.filter(o => !["COMPLETED", "CANCELLED"].includes(o.status))
  const pastOrders = paged.filter(o => ["COMPLETED", "CANCELLED"].includes(o.status))
  const hasAnyActiveOnScreen = orders.some(o => !["COMPLETED", "CANCELLED"].includes(o.status))

  if (loading) {
    return (
      <div className="mx-auto max-w-lg px-4 py-16 text-center">
        <Loader2 className="mx-auto h-8 w-8 animate-spin text-brand-primary motion-reduce:animate-none" />
        <p className="mt-3 text-sm text-ink-tertiary">Loading your orders...</p>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-lg px-4 sm:px-6 py-6">
      <h1 className="font-display text-2xl font-semibold tracking-tight text-ink-primary">My Orders</h1>
      <p className="text-sm text-ink-tertiary mt-1">
        {orders.length} order{orders.length !== 1 ? "s" : ""}
        {email && <span className="text-ink-tertiary"> &middot; {email}</span>}
      </p>

      {/* Filters (STFR-05) */}
      {orders.length > 0 && (
        <div className="mt-5 grid grid-cols-1 sm:grid-cols-2 gap-3">
          <label className="block">
            <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-tertiary">Status</span>
            <select
              data-testid="orders-status-filter"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as OrderStatusFilter)}
              className="mt-1 flex h-10 w-full rounded-md border border-border-tone bg-surface-card px-3 py-2 text-sm text-ink-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas"
            >
              {ORDER_STATUS_OPTIONS.map((s) => (
                <option key={s} value={s}>
                  {s === "ALL" ? "All statuses" : (STATUS_CONFIG[s]?.label ?? s)}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-tertiary">From date</span>
            <input
              type="date"
              data-testid="orders-date-from"
              value={dateFrom}
              onChange={(e) => setDateFrom(e.target.value)}
              className="mt-1 flex h-10 w-full rounded-md border border-border-tone bg-surface-card px-3 py-2 text-sm text-ink-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas"
            />
          </label>
        </div>
      )}

      {/* Filter result summary */}
      {orders.length > 0 && (
        <p className="mt-3 text-xs text-ink-tertiary">
          Showing {paged.length} of {filtered.length} filtered order{filtered.length !== 1 ? "s" : ""}
        </p>
      )}

      {/* Active orders */}
      {activeOrders.length > 0 && (
        <section className="mt-6">
          <h2 className="text-[11px] font-semibold text-ink-tertiary uppercase tracking-[0.08em] mb-3">
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
          <h2 className="text-[11px] font-semibold text-ink-tertiary uppercase tracking-[0.08em] mb-3">
            Past ({pastOrders.length})
          </h2>
          <div className="space-y-3">
            {pastOrders.map((order) => (
              <OrderCard key={order.orderNumber} order={order} email={email || undefined} />
            ))}
          </div>
        </section>
      )}

      {orders.length === 0 && (
        <div className="mt-12 text-center">
          <BrandPlaceholder aspect="aspect-[4/3]" className="mx-auto max-w-xs rounded-md" />
          <p className="mt-4 text-sm text-ink-secondary">No orders yet.</p>
          <Link
            href="/shop"
            className="mt-4 inline-flex items-center gap-2 text-sm text-brand-primary hover:underline underline-offset-4"
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
            className="inline-flex items-center justify-center rounded-md border border-border-tone px-3 py-1.5 text-xs font-medium text-ink-primary transition-colors hover:bg-surface-subtle disabled:cursor-not-allowed disabled:opacity-40"
          >
            Previous
          </button>
          <span className="text-xs text-ink-tertiary" data-testid="orders-page-label">
            Page <span className="font-mono tabular-nums">{page}</span> of <span className="font-mono tabular-nums">{totalPages}</span>
          </span>
          <button
            type="button"
            data-testid="orders-next-page"
            onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
            disabled={page >= totalPages || totalPages === 0}
            className="inline-flex items-center justify-center rounded-md border border-border-tone px-3 py-1.5 text-xs font-medium text-ink-primary transition-colors hover:bg-surface-subtle disabled:cursor-not-allowed disabled:opacity-40"
          >
            Next
          </button>
        </div>
      )}

      {filtered.length === 0 && orders.length > 0 && (
        <p className="mt-8 text-center text-sm text-ink-tertiary">No orders match the selected filters.</p>
      )}

      {/* Auto-refresh indicator */}
      {hasAnyActiveOnScreen && (
        <p className="mt-6 text-center text-[10px] text-ink-tertiary">
          <span className="inline-flex items-center gap-1">
            <span className="h-1.5 w-1.5 rounded-full bg-success animate-pulse motion-reduce:animate-none" />
            Live updates every 15 seconds
          </span>
        </p>
      )}
    </div>
  )
}

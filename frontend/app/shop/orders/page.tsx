"use client"

import { useEffect, useState, useCallback } from "react"
import Link from "next/link"
import {
  Package, Clock, CheckCircle2, ChefHat, CircleDot,
  XCircle, ArrowRight, Loader2, Store, Search
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"
import { RequireCustomerAuth } from "@/components/storefront/require-customer-auth"

interface OrderSummary {
  orderNumber: string
  status: string
  shopName: string
  totalAmountPennies: number
  itemCount: number
  createdAt: string
  updatedAt: string
}

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
  PREPARING: { icon: ChefHat, color: "text-orange-500 bg-orange-50", label: "Preparing" },
  READY: { icon: Package, color: "text-emerald-500 bg-emerald-50", label: "Ready" },
  COMPLETED: { icon: CheckCircle2, color: "text-slate-400 bg-slate-50", label: "Completed" },
  CANCELLED: { icon: XCircle, color: "text-red-500 bg-red-50", label: "Cancelled" },
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
    <Link href={trackUrl} className="block group">
      <div className={`rounded-xl bg-white border ${isActive ? "border-orange-200 shadow-sm" : "border-slate-100"} p-4 transition-all group-hover:shadow-md group-hover:-translate-y-0.5`}>
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ${cfg.color}`}>
                <Icon className="h-3 w-3" />
                {cfg.label}
                {isActive && <span className="h-1.5 w-1.5 rounded-full bg-current animate-pulse" />}
              </span>
            </div>
            <p className="text-sm font-semibold text-slate-900">{order.shopName}</p>
            <p className="text-xs text-slate-500 mt-0.5">
              {order.itemCount} item{order.itemCount !== 1 ? "s" : ""} &middot; {formatPrice(order.totalAmountPennies)}
            </p>
            <p className="text-[10px] text-slate-400 mt-1">{formatDate(order.createdAt)}</p>
          </div>
          <div className="flex items-center gap-1 text-slate-400 group-hover:text-orange-500 transition-colors mt-1">
            <span className="text-xs font-medium">{isActive ? "Track" : "View"}</span>
            <ArrowRight className="h-4 w-4" />
          </div>
        </div>

        {/* Mini order number */}
        <p className="mt-2 text-[9px] font-mono text-slate-300 truncate">{order.orderNumber}</p>
      </div>
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
    const session = getCustomerSession()
    if (session) {
      setEmail(session.profile.email)
    }
  }, [])

  const fetchOrders = useCallback(async () => {
    if (!email) {
      setLoading(false)
      return
    }
    try {
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

  const activeOrders = orders.filter(o => !["COMPLETED", "CANCELLED"].includes(o.status))
  const pastOrders = orders.filter(o => ["COMPLETED", "CANCELLED"].includes(o.status))

  if (loading) {
    return (
      <div className="mx-auto max-w-lg px-4 py-16 text-center">
        <Loader2 className="mx-auto h-8 w-8 animate-spin text-orange-500" />
        <p className="mt-3 text-sm text-slate-500">Loading your orders...</p>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-lg px-4 sm:px-6 py-6">
      <h1 className="text-xl font-bold text-slate-900">My Orders</h1>
      <p className="text-sm text-slate-500 mt-1">
        {orders.length} order{orders.length !== 1 ? "s" : ""}
        {email && <span className="text-slate-400"> &middot; {email}</span>}
      </p>

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

      {orders.length === 0 && (
        <div className="mt-12 text-center">
          <Package className="mx-auto h-12 w-12 text-slate-200" />
          <p className="mt-3 text-sm text-slate-500">No orders found for this email.</p>
          <Link
            href="/shop"
            className="mt-4 inline-flex items-center gap-2 text-sm text-orange-600 hover:text-orange-700"
          >
            <Store className="h-4 w-4" />
            Browse shops
          </Link>
        </div>
      )}

      {/* Auto-refresh indicator */}
      {activeOrders.length > 0 && (
        <p className="mt-6 text-center text-[10px] text-slate-400">
          <span className="inline-flex items-center gap-1">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
            Live updates every 15 seconds
          </span>
        </p>
      )}
    </div>
  )
}

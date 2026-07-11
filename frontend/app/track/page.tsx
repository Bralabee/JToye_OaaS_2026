"use client"

import { Suspense, useState, useEffect } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import {
  Package, Search, Loader2, CheckCircle2, Clock,
  ChefHat, CircleDot, XCircle, Store
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"
import { PublicShell } from "@/components/public/public-shell"

interface OrderStatus {
  orderNumber: string
  status: string
  shopName: string
  totalAmountPennies: number
  itemCount: number
  createdAt: string
  updatedAt: string
}

const STEPS = [
  { key: "PENDING", label: "Received", icon: Clock },
  { key: "CONFIRMED", label: "Confirmed", icon: CircleDot },
  { key: "PREPARING", label: "Preparing", icon: ChefHat },
  { key: "READY", label: "Ready", icon: Package },
  { key: "COMPLETED", label: "Completed", icon: CheckCircle2 },
]

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

// Guest order lookup (Surface H): order number + email, NO forced sign-in.
// Uses the IDOR-hardened public endpoint (email is mandatory proof-of-ownership,
// AUDIT-W0-02). A customer session only pre-fills the email; it is never required.
export default function TrackOrderPage() {
  return (
    <PublicShell>
      <Suspense
        fallback={
          <div className="flex items-center justify-center py-24">
            <Loader2 className="h-8 w-8 animate-spin text-orange-500" />
          </div>
        }
      >
        <TrackOrderContent />
      </Suspense>
    </PublicShell>
  )
}

function TrackOrderContent() {
  const searchParams = useSearchParams()
  const [orderNumber, setOrderNumber] = useState(searchParams.get("order") || "")
  const [email, setEmail] = useState(searchParams.get("email") || "")

  // Convenience pre-fill from a customer session — never a requirement.
  useEffect(() => {
    if (email) return
    let cancelled = false
    getCustomerSession().then((session) => {
      if (cancelled) return
      if (session?.profile?.email) setEmail(session.profile.email)
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const [order, setOrder] = useState<OrderStatus | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Auto-search if URL carries both params (e.g. from the confirmation page link).
  useEffect(() => {
    if (searchParams.get("order") && searchParams.get("email")) {
      handleSearch()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleSearch = async (e?: React.FormEvent) => {
    e?.preventDefault()
    if (!orderNumber.trim() || !email.trim()) return

    setLoading(true)
    setError(null)
    setOrder(null)

    try {
      const res = await publicApiClient.get<OrderStatus>(
        `/public/orders/${orderNumber.trim()}`,
        { params: { email: email.trim() } }
      )
      setOrder(res.data)
    } catch {
      setError("Order not found. Check your order number and email address.")
    } finally {
      setLoading(false)
    }
  }

  // Auto-refresh for active orders (15s).
  useEffect(() => {
    if (!order || order.status === "COMPLETED" || order.status === "CANCELLED") return
    const interval = setInterval(async () => {
      try {
        const res = await publicApiClient.get<OrderStatus>(
          `/public/orders/${order.orderNumber}`,
          { params: { email: email.trim() } }
        )
        setOrder(res.data)
      } catch { /* silently fail on refresh */ }
    }, 15000)
    return () => clearInterval(interval)
  }, [order, email])

  const currentStep = order ? STEPS.findIndex((s) => s.key === order.status) : -1
  const isCancelled = order?.status === "CANCELLED"

  return (
    <div className="mx-auto max-w-lg px-4 py-8 sm:py-12">
      {/* Search form */}
      <form onSubmit={handleSearch} className="rounded-xl bg-white border border-slate-100 p-5 shadow-sm">
        <h1 className="text-base font-bold text-slate-900">Track your order</h1>
        <p className="mt-1 mb-4 text-xs text-slate-500">
          Enter your order number and the email you used — no sign-in needed.
        </p>

        <div className="space-y-3">
          <div className="space-y-1.5">
            <label htmlFor="orderNumber" className="block text-xs font-medium text-slate-600">Order number</label>
            <input
              id="orderNumber"
              type="text"
              value={orderNumber}
              onChange={(e) => setOrderNumber(e.target.value)}
              placeholder="ORD-XXXXXXXX-XXXXXXXX-XXXXXXXX"
              required
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm font-mono text-slate-900 placeholder:text-slate-300 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="email" className="block text-xs font-medium text-slate-600">Email</label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@email.com"
              required
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-300 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-orange-500 py-3 text-sm font-bold text-white hover:bg-orange-600 disabled:opacity-60 transition-all"
          >
            {loading ? (
              <><Loader2 className="h-4 w-4 animate-spin" /> Looking up...</>
            ) : (
              <><Search className="h-4 w-4" /> Track order</>
            )}
          </button>
        </div>
      </form>

      {/* Error */}
      {error && (
        <div className="mt-4 rounded-xl bg-red-50 border border-red-100 p-4 text-center">
          <p className="text-sm text-red-700">{error}</p>
        </div>
      )}

      {/* Result */}
      {order && (
        <div className="mt-6 space-y-4">
          {/* Shop + total */}
          <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-semibold text-slate-900">{order.shopName}</p>
                <p className="text-xs text-slate-500">
                  {order.itemCount} item{order.itemCount !== 1 ? "s" : ""} &middot; {formatPrice(order.totalAmountPennies)}
                </p>
              </div>
              {isCancelled ? (
                <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2.5 py-1 text-xs font-medium text-red-700">
                  <XCircle className="h-3 w-3" /> Cancelled
                </span>
              ) : currentStep >= 4 ? (
                <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2.5 py-1 text-xs font-medium text-emerald-700">
                  <CheckCircle2 className="h-3 w-3" /> Complete
                </span>
              ) : (
                <span className="inline-flex items-center gap-1 rounded-full bg-orange-100 px-2.5 py-1 text-xs font-medium text-orange-700">
                  <span className="h-1.5 w-1.5 rounded-full bg-orange-500 animate-pulse" />
                  {STEPS[currentStep]?.label || order.status}
                </span>
              )}
            </div>
          </div>

          {/* Progress */}
          {!isCancelled && (
            <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm">
              <div className="flex items-center justify-between gap-1">
                {STEPS.map((step, i) => {
                  const isComplete = i <= currentStep
                  return (
                    <div key={step.key} className="flex flex-col items-center flex-1">
                      <div
                        className={`flex h-8 w-8 items-center justify-center rounded-full text-xs ${
                          isComplete
                            ? i === currentStep
                              ? "bg-orange-500 text-white ring-2 ring-orange-200"
                              : "bg-emerald-500 text-white"
                            : "bg-slate-100 text-slate-400"
                        }`}
                      >
                        <step.icon className="h-3.5 w-3.5" />
                      </div>
                      <p className={`mt-1 text-xs font-medium ${isComplete ? "text-slate-700" : "text-slate-400"}`}>
                        {step.label}
                      </p>
                    </div>
                  )
                })}
              </div>
              {/* Progress bar */}
              <div className="mt-3 h-1 bg-slate-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-orange-500 rounded-full transition-all duration-500"
                  style={{ width: `${Math.max(5, (currentStep / (STEPS.length - 1)) * 100)}%` }}
                />
              </div>
              <p className="mt-2 text-center text-xs text-slate-400">
                <span className="inline-flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
                  Auto-refreshing
                </span>
              </p>
            </div>
          )}
        </div>
      )}

      {/* Back link */}
      <div className="mt-8 text-center">
        <Link
          href="/shop"
          className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
        >
          <Store className="h-4 w-4" />
          Browse shops
        </Link>
      </div>
    </div>
  )
}

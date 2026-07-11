"use client"

import { Suspense, use, useEffect, useState, useCallback } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import {
  CheckCircle2, Store, Copy, ArrowLeft, Clock,
  ChefHat, Package, CircleDot, XCircle, Loader2
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"

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
  { key: "PENDING", label: "Received", icon: Clock, desc: "Order sent to shop" },
  { key: "CONFIRMED", label: "Confirmed", icon: CircleDot, desc: "Shop accepted your order" },
  { key: "PREPARING", label: "Preparing", icon: ChefHat, desc: "Being prepared now" },
  { key: "READY", label: "Ready", icon: Package, desc: "Ready for collection" },
  { key: "COMPLETED", label: "Completed", icon: CheckCircle2, desc: "Order complete" },
]

function getStepIndex(status: string): number {
  const idx = STEPS.findIndex((s) => s.key === status)
  return idx >= 0 ? idx : -1
}

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })
}

export default function OrderTrackingPage({
  params,
}: {
  params: Promise<{ slug: string; orderNumber: string }>
}) {
  const { slug, orderNumber } = use(params)
  return (
    <Suspense fallback={<div className="mx-auto max-w-lg px-4 py-16 text-center"><Loader2 className="mx-auto h-8 w-8 animate-spin text-orange-500" /></div>}>
      <OrderTrackingContent slug={slug} orderNumber={orderNumber} />
    </Suspense>
  )
}

function OrderTrackingContent({ slug, orderNumber }: { slug: string; orderNumber: string }) {
  const [order, setOrder] = useState<OrderStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  const searchParams = useSearchParams()

  // Use authenticated email, or fall back to the email stored during guest checkout.
  // Session is cookie-backed now, so we hydrate asynchronously.
  const checkoutEmail = typeof window !== "undefined"
    ? localStorage.getItem(`jtoye-checkout-email-${slug}`) || ""
    : ""
  const [email, setEmail] = useState<string>(checkoutEmail)
  useEffect(() => {
    let cancelled = false
    getCustomerSession().then((session) => {
      if (cancelled) return
      if (session?.profile?.email) setEmail(session.profile.email)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const fetchStatus = useCallback(async () => {
    if (!email) {
      setLoading(false)
      return
    }
    try {
      const res = await publicApiClient.get<OrderStatus>(
        `/public/orders/${orderNumber}`,
        { params: { email } }
      )
      setOrder(res.data)
      setError(null)
    } catch {
      if (!order) setError("Could not load order status.")
    } finally {
      setLoading(false)
    }
  }, [orderNumber, email, order])

  useEffect(() => {
    fetchStatus()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-refresh every 15 seconds for active orders
  useEffect(() => {
    if (!order || order.status === "COMPLETED" || order.status === "CANCELLED") return
    const interval = setInterval(fetchStatus, 15000)
    return () => clearInterval(interval)
  }, [order, fetchStatus])

  const copyOrderNumber = () => {
    navigator.clipboard.writeText(orderNumber)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const isCancelled = order?.status === "CANCELLED"
  const currentStep = order ? getStepIndex(order.status) : -1

  if (loading) {
    return (
      <div className="mx-auto max-w-lg px-4 py-16 text-center">
        <Loader2 className="mx-auto h-8 w-8 animate-spin text-orange-500" />
        <p className="mt-3 text-sm text-slate-500">Loading order status...</p>
      </div>
    )
  }

  // No email — inline email prompt (no redirect needed)
  if (!email && !order) {
    return <EmailPrompt orderNumber={orderNumber} onSubmit={(e) => {
      localStorage.setItem(`jtoye-checkout-email-${slug}`, e)
      window.location.reload()
    }} />
  }

  return (
    <div className="mx-auto max-w-lg px-4 sm:px-6 py-8">
      {/* Header */}
      {!error && order && (
        <div className="text-center mb-8">
          {isCancelled ? (
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-red-100">
              <XCircle className="h-8 w-8 text-red-500" />
            </div>
          ) : currentStep >= 4 ? (
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100">
              <CheckCircle2 className="h-8 w-8 text-emerald-600" />
            </div>
          ) : (
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-orange-100">
              <Clock className="h-8 w-8 text-orange-500" />
            </div>
          )}
          <h1 className="mt-4 text-xl font-bold text-slate-900">
            {isCancelled ? "Order Cancelled" : currentStep >= 4 ? "Order Complete!" : "Order in Progress"}
          </h1>
          <p className="mt-1 text-sm text-slate-500">{order.shopName}</p>
        </div>
      )}

      {/* Order number */}
      <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm text-center mb-6">
        <p className="text-xs font-medium text-slate-400 uppercase tracking-wider">Order number</p>
        <div className="mt-1 flex items-center justify-center gap-2">
          <p className="text-sm font-bold font-mono text-slate-900">{orderNumber}</p>
          <button
            onClick={copyOrderNumber}
            className="flex h-6 w-6 items-center justify-center rounded hover:bg-slate-100 text-slate-400 hover:text-slate-600 transition-colors"
          >
            <Copy className="h-3 w-3" />
          </button>
          {copied && <span className="text-xs text-emerald-600">Copied!</span>}
        </div>
        {order && (
          <div className="mt-2 flex items-center justify-center gap-3 text-xs text-slate-500">
            <span>{order.itemCount} item{order.itemCount !== 1 ? "s" : ""}</span>
            <span>{formatPrice(order.totalAmountPennies)}</span>
          </div>
        )}
      </div>

      {/* Error state */}
      {error && (
        <div className="rounded-xl bg-red-50 border border-red-100 p-4 text-center mb-6">
          <p className="text-sm text-red-700">{error}</p>
          <button
            onClick={fetchStatus}
            className="mt-2 text-xs font-medium text-red-600 hover:text-red-700"
          >
            Try again
          </button>
        </div>
      )}

      {/* Progress tracker */}
      {order && !isCancelled && (
        <div className="rounded-xl bg-white border border-slate-100 p-5 shadow-sm mb-6">
          <div className="space-y-0">
            {STEPS.map((step, i) => {
              const isActive = i === currentStep
              const isComplete = i < currentStep
              const isPending = i > currentStep
              const Icon = step.icon

              return (
                <div key={step.key} className="flex gap-3">
                  {/* Vertical line + circle */}
                  <div className="flex flex-col items-center">
                    <div
                      className={`flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full transition-all ${
                        isComplete
                          ? "bg-emerald-500 text-white"
                          : isActive
                          ? "bg-orange-500 text-white ring-4 ring-orange-100"
                          : "bg-slate-100 text-slate-400"
                      }`}
                    >
                      <Icon className="h-4 w-4" />
                    </div>
                    {i < STEPS.length - 1 && (
                      <div
                        className={`w-0.5 h-8 ${
                          isComplete ? "bg-emerald-500" : "bg-slate-200"
                        }`}
                      />
                    )}
                  </div>

                  {/* Label */}
                  <div className="pb-6">
                    <p
                      className={`text-sm font-medium ${
                        isComplete || isActive ? "text-slate-900" : "text-slate-400"
                      }`}
                    >
                      {step.label}
                    </p>
                    <p className={`text-xs ${isActive ? "text-orange-600" : "text-slate-400"}`}>
                      {isActive && order.updatedAt
                        ? `${step.desc} · ${formatTime(order.updatedAt)}`
                        : step.desc}
                    </p>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* Cancelled state */}
      {order && isCancelled && (
        <div className="rounded-xl bg-red-50 border border-red-100 p-5 mb-6 text-center">
          <p className="text-sm text-red-700">
            This order was cancelled. If this was unexpected, please contact the shop.
          </p>
        </div>
      )}

      {/* Auto-refresh indicator */}
      {order && !isCancelled && currentStep < 4 && (
        <p className="text-center text-xs text-slate-400 mb-6">
          <span className="inline-flex items-center gap-1">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
            Live updates every 15 seconds
          </span>
        </p>
      )}

      {/* Actions */}
      <div className="space-y-3">
        <Link
          href={`/track?order=${orderNumber}&email=${encodeURIComponent(email)}`}
          className="flex w-full items-center justify-center gap-2 rounded-2xl border border-orange-200 bg-orange-50 py-2.5 text-sm font-medium text-orange-700 hover:bg-orange-100 transition-colors"
        >
          <Package className="h-4 w-4" />
          Track this order
        </Link>
        <Link
          href={`/shop/${slug}`}
          className="flex w-full items-center justify-center gap-2 rounded-2xl bg-orange-500 py-3 text-sm font-bold text-white hover:bg-orange-600 transition-all"
        >
          <Store className="h-4 w-4" />
          Back to shop
        </Link>
        <Link
          href="/shop"
          className="flex w-full items-center justify-center gap-1 rounded-2xl border border-slate-200 py-2.5 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          Browse other shops
        </Link>
      </div>
    </div>
  )
}

function EmailPrompt({ orderNumber, onSubmit }: { orderNumber: string; onSubmit: (email: string) => void }) {
  const [emailInput, setEmailInput] = useState("")

  return (
    <div className="mx-auto max-w-lg px-4 py-10">
      <div className="text-center mb-6">
        <Package className="mx-auto h-12 w-12 text-slate-300" />
        <h2 className="mt-4 text-lg font-semibold text-slate-900">Track your order</h2>
        <p className="mt-2 text-sm text-slate-500">
          Enter the email you used when placing this order.
        </p>
      </div>
      <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm">
        <p className="text-xs font-mono text-slate-400 mb-3">{orderNumber}</p>
        <form onSubmit={(e) => { e.preventDefault(); if (emailInput.trim()) onSubmit(emailInput.trim()) }}>
          <input
            type="email"
            required
            value={emailInput}
            onChange={(e) => setEmailInput(e.target.value)}
            placeholder="your@email.com"
            className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100"
          />
          <button
            type="submit"
            className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl bg-orange-500 py-3 text-sm font-bold text-white hover:bg-orange-600 transition-all"
          >
            View order status
          </button>
        </form>
      </div>
    </div>
  )
}

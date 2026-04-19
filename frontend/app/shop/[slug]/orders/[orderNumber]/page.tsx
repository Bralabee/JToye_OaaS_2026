"use client"

import { Suspense, use, useEffect, useState, useCallback } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import { motion } from "framer-motion"
import {
  CheckCircle2, Store, Copy, ArrowLeft, Clock,
  ChefHat, Package, CircleDot, XCircle, Loader2
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"
import { Card, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { fadeUp, useReducedMotionSafe } from "@/lib/motion"
import { cn } from "@/lib/utils"

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

type BadgeVariant = "subtle" | "info" | "brand" | "success" | "danger" | "warning"

function statusVariant(status: string): BadgeVariant {
  switch (status) {
    case "PENDING":
      return "subtle"
    case "CONFIRMED":
    case "PREPARING":
      return "info"
    case "READY":
      return "brand"
    case "COMPLETED":
      return "success"
    case "CANCELLED":
      return "danger"
    default:
      return "subtle"
  }
}

function statusLabel(status: string): string {
  const match = STEPS.find((s) => s.key === status)
  if (match) return match.label
  if (status === "CANCELLED") return "Cancelled"
  return status
}

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
    <Suspense
      fallback={
        <div className="flex min-h-[60vh] items-center justify-center bg-surface-canvas">
          <Loader2 className="h-8 w-8 animate-spin text-brand-primary motion-reduce:animate-none" strokeWidth={1.5} />
        </div>
      }
    >
      <OrderTrackingContent slug={slug} orderNumber={orderNumber} />
    </Suspense>
  )
}

function OrderTrackingContent({ slug, orderNumber }: { slug: string; orderNumber: string }) {
  const [order, setOrder] = useState<OrderStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const pageVariants = useReducedMotionSafe(fadeUp)

  useSearchParams() // preserved — reserved for future query-param use

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
      <div className="bg-surface-canvas min-h-screen">
        <div className="mx-auto max-w-lg px-4 py-16 text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-brand-primary motion-reduce:animate-none" strokeWidth={1.5} />
          <p className="mt-3 text-body-sm text-ink-secondary">Loading order status…</p>
        </div>
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
    <div className="bg-surface-canvas min-h-screen">
      <motion.div
        variants={pageVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto max-w-lg px-4 sm:px-6 py-8"
      >
        {/* Header */}
        {!error && order && (
          <div className="text-center mb-8">
            {isCancelled ? (
              <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-pill bg-danger-subtle">
                <XCircle className="h-8 w-8 text-danger" strokeWidth={1.5} />
              </div>
            ) : currentStep >= 4 ? (
              <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-pill bg-success-subtle">
                <CheckCircle2 className="h-8 w-8 text-success" strokeWidth={1.5} />
              </div>
            ) : (
              <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-pill bg-brand-primary-subtle">
                <Clock className="h-8 w-8 text-brand-primary" strokeWidth={1.5} />
              </div>
            )}
            <h1 className="mt-4 font-display text-display-sm font-medium tracking-tight text-ink-primary">
              {isCancelled ? "Order cancelled" : currentStep >= 4 ? "Order complete" : "Order in progress"}
            </h1>
            <p className="mt-1 text-body-sm text-ink-secondary">{order.shopName}</p>
          </div>
        )}

        {/* Order number */}
        <Card variant="default" className="p-4 text-center mb-6">
          <CardContent className="p-0">
            <p className="text-[10px] font-medium text-ink-tertiary uppercase tracking-widest">
              Order number
            </p>
            <div className="mt-1 flex items-center justify-center gap-2">
              <p className="font-mono tabular-nums text-body-sm font-semibold text-ink-primary break-all">
                {orderNumber}
              </p>
              <button
                type="button"
                aria-label="Copy order number"
                onClick={copyOrderNumber}
                className="flex h-6 w-6 items-center justify-center rounded-sm hover:bg-surface-subtle text-ink-tertiary hover:text-ink-primary transition-colors duration-fast"
              >
                <Copy className="h-3 w-3" strokeWidth={1.5} />
              </button>
              {copied && <span className="text-[10px] text-success">Copied!</span>}
            </div>
            {order && (
              <div className="mt-2 flex items-center justify-center gap-3 text-caption text-ink-tertiary">
                <span>
                  {order.itemCount} item{order.itemCount !== 1 ? "s" : ""}
                </span>
                <span className="font-mono tabular-nums text-ink-secondary">
                  {formatPrice(order.totalAmountPennies)}
                </span>
                {order && (
                  <Badge variant={statusVariant(order.status)} size="sm" className="rounded-pill">
                    {statusLabel(order.status)}
                  </Badge>
                )}
              </div>
            )}
          </CardContent>
        </Card>

        {/* Error state */}
        {error && (
          <Card variant="flat" className="border-danger/40 bg-danger-subtle p-4 text-center mb-6">
            <CardContent className="p-0">
              <p className="text-body-sm text-danger">{error}</p>
              <Button
                variant="link"
                size="sm"
                onClick={fetchStatus}
                className="mt-2 text-danger hover:text-danger"
              >
                Try again
              </Button>
            </CardContent>
          </Card>
        )}

        {/* Progress tracker */}
        {order && !isCancelled && (
          <Card variant="lifted" className="p-5 mb-6">
            <CardContent className="p-0">
              <div className="space-y-0">
                {STEPS.map((step, i) => {
                  const isActive = i === currentStep
                  const isComplete = i < currentStep
                  const Icon = step.icon

                  return (
                    <div key={step.key} className="flex gap-3">
                      {/* Vertical line + circle */}
                      <div className="flex flex-col items-center">
                        <div
                          className={cn(
                            "flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-pill border-2 transition-all duration-default motion-reduce:transition-none",
                            isComplete
                              ? "border-brand-primary bg-brand-primary text-ink-on-brand"
                              : isActive
                              ? "border-brand-primary bg-brand-primary text-ink-on-brand shadow-lift"
                              : "border-border-tone-subtle bg-surface-card text-ink-tertiary",
                          )}
                          aria-current={isActive ? "step" : undefined}
                        >
                          <Icon className="h-4 w-4" strokeWidth={1.5} />
                        </div>
                        {i < STEPS.length - 1 && (
                          <div
                            aria-hidden="true"
                            className={cn(
                              "w-0.5 h-8 rounded-full",
                              isComplete ? "bg-brand-primary" : "bg-surface-muted",
                            )}
                          />
                        )}
                      </div>

                      {/* Label */}
                      <div className="pb-6">
                        <p
                          className={cn(
                            "text-body-sm font-medium",
                            isComplete || isActive ? "text-ink-primary" : "text-ink-tertiary",
                          )}
                        >
                          {step.label}
                        </p>
                        <p
                          className={cn(
                            "text-caption",
                            isActive ? "text-brand-primary" : "text-ink-tertiary",
                          )}
                        >
                          {isActive && order.updatedAt
                            ? `${step.desc} · ${formatTime(order.updatedAt)}`
                            : step.desc}
                        </p>
                      </div>
                    </div>
                  )
                })}
              </div>
            </CardContent>
          </Card>
        )}

        {/* Cancelled state */}
        {order && isCancelled && (
          <Card variant="flat" className="border-danger/40 bg-danger-subtle p-5 mb-6 text-center">
            <CardContent className="p-0">
              <p className="text-body-sm text-danger">
                This order was cancelled. If this was unexpected, please contact the shop.
              </p>
            </CardContent>
          </Card>
        )}

        {/* Auto-refresh indicator */}
        {order && !isCancelled && currentStep < 4 && (
          <p className="text-center text-[10px] text-ink-tertiary mb-6" aria-live="polite">
            <span className="inline-flex items-center gap-1">
              <span
                aria-hidden="true"
                className="h-1.5 w-1.5 rounded-pill bg-success motion-safe:animate-pulse"
              />
              Live updates every 15 seconds
            </span>
          </p>
        )}

        {/* Actions */}
        <div className="space-y-3">
          <Button asChild variant="primary" size="lg" className="w-full rounded-pill shadow-lift">
            <Link href={`/shop/${slug}`}>
              <Store className="h-4 w-4" strokeWidth={1.5} />
              Back to shop
            </Link>
          </Button>
          <Button asChild variant="secondary" size="lg" className="w-full rounded-pill">
            <Link href="/shop">
              <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
              Browse other shops
            </Link>
          </Button>
        </div>
      </motion.div>
    </div>
  )
}

function EmailPrompt({ orderNumber, onSubmit }: { orderNumber: string; onSubmit: (email: string) => void }) {
  const [emailInput, setEmailInput] = useState("")

  return (
    <div className="bg-surface-canvas min-h-screen">
      <div className="mx-auto max-w-lg px-4 py-10">
        <div className="text-center mb-6">
          <Package className="mx-auto h-12 w-12 text-ink-quaternary" strokeWidth={1.5} />
          <h2 className="mt-4 font-display text-display-sm font-medium tracking-tight text-ink-primary">
            Track your order
          </h2>
          <p className="mt-2 text-body-sm text-ink-secondary">
            Enter the email you used when placing this order.
          </p>
        </div>
        <Card variant="lifted" className="p-4">
          <CardContent className="p-0">
            <p className="font-mono tabular-nums text-[10px] text-ink-tertiary mb-3">
              {orderNumber}
            </p>
            <form
              onSubmit={(e) => {
                e.preventDefault()
                if (emailInput.trim()) onSubmit(emailInput.trim())
              }}
            >
              <Input
                type="email"
                tone="brand"
                autoComplete="email"
                required
                value={emailInput}
                onChange={(e) => setEmailInput(e.target.value)}
                placeholder="your@email.com"
              />
              <Button type="submit" variant="primary" size="lg" className="mt-3 w-full rounded-pill shadow-lift">
                View order status
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

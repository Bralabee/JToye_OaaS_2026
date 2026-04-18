"use client"

import { Suspense, useState, useEffect } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import { motion } from "framer-motion"
import {
  Package, Search, Loader2, CheckCircle2, Clock,
  ChefHat, CircleDot, XCircle, ArrowLeft, Store,
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { RequireCustomerAuth } from "@/components/storefront/require-customer-auth"
import { getCustomerSession } from "@/lib/customer-auth"
import { Card, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Table,
  TableBody,
  TableCell,
  TableRow,
  TableFooter,
} from "@/components/ui/table"
import {
  fadeUp,
  scaleFade,
  useReducedMotionSafe,
  DURATION,
  EASE,
} from "@/lib/motion"
import { cn } from "@/lib/utils"

interface OrderLineItem {
  title?: string
  productTitle?: string
  quantity: number
  pricePennies?: number
  priceAtPurchasePennies?: number
}

interface OrderStatus {
  orderNumber: string
  status: string
  shopName: string
  totalAmountPennies: number
  itemCount: number
  createdAt: string
  updatedAt: string
  items?: OrderLineItem[]
}

type StepKey = "PENDING" | "CONFIRMED" | "PREPARING" | "READY" | "COMPLETED"

const STEPS: Array<{ key: StepKey; label: string; icon: typeof Clock }> = [
  { key: "PENDING", label: "Received", icon: Clock },
  { key: "CONFIRMED", label: "Confirmed", icon: CircleDot },
  { key: "PREPARING", label: "Preparing", icon: ChefHat },
  { key: "READY", label: "Ready", icon: Package },
  { key: "COMPLETED", label: "Completed", icon: CheckCircle2 },
]

type BadgeVariant =
  | "subtle"
  | "info"
  | "brand"
  | "success"
  | "danger"
  | "warning"

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

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

export default function TrackOrderPage() {
  return (
    <RequireCustomerAuth message="Sign in to track your orders.">
      <Suspense
        fallback={
          <div className="flex min-h-[60vh] items-center justify-center bg-surface-canvas">
            <Loader2 className="h-8 w-8 animate-spin text-brand-primary" strokeWidth={1.5} />
          </div>
        }
      >
        <TrackOrderContent />
      </Suspense>
    </RequireCustomerAuth>
  )
}

function TrackOrderContent() {
  const searchParams = useSearchParams()
  const [orderNumber, setOrderNumber] = useState(searchParams.get("order") || "")
  const [email, setEmail] = useState(searchParams.get("email") || "")
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

  const heroVariants = useReducedMotionSafe(fadeUp)
  const resultVariants = useReducedMotionSafe(scaleFade)

  // Auto-search if URL has both params
  useEffect(() => {
    if (searchParams.get("order") && searchParams.get("email")) {
      handleSearch()
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

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
      setError("Order not found. Please check your order number and email address.")
    } finally {
      setLoading(false)
    }
  }

  // Auto-refresh for active orders
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
  const progressPct = Math.max(
    0,
    Math.min(100, (currentStep / (STEPS.length - 1)) * 100),
  )

  return (
    <div className="bg-surface-canvas min-h-screen">
      {/* Editorial hero */}
      <motion.section
        variants={heroVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 pt-12 pb-8 sm:pt-16"
      >
        <p className="text-overline uppercase tracking-widest text-ink-tertiary">
          Order status
        </p>
        <h1 className="mt-3 font-display text-display-lg font-medium tracking-tight text-ink-primary">
          Track your order
        </h1>
        <p className="mt-4 max-w-prose text-body-lg text-ink-secondary">
          Enter the order number we sent to your email. The page refreshes
          every 15 seconds while your order is in progress.
        </p>
      </motion.section>

      <div className="mx-auto max-w-3xl px-4 sm:px-6 lg:px-8 pb-24 space-y-6">
        {/* Search card */}
        <Card variant="flat" className="rounded-xl">
          <CardContent className="p-6 sm:p-8">
            <form onSubmit={handleSearch} className="space-y-4">
              <div className="space-y-2">
                <label
                  htmlFor="orderNumber"
                  className="block text-caption font-semibold text-ink-secondary"
                >
                  Order number
                </label>
                <Input
                  id="orderNumber"
                  type="text"
                  tone="brand"
                  size="lg"
                  value={orderNumber}
                  onChange={(e) => setOrderNumber(e.target.value)}
                  placeholder="ORD-XXXXXXXX-XXXXXXXX-XXXXXXXX"
                  className="font-mono"
                  required
                />
              </div>

              {email && (
                <p className="text-caption text-ink-tertiary">
                  Tracking as{" "}
                  <span className="font-medium text-ink-primary">{email}</span>
                </p>
              )}

              <Button
                type="submit"
                variant="primary"
                size="lg"
                className="w-full"
                isLoading={loading}
                disabled={loading}
              >
                {!loading && <Search className="h-4 w-4" strokeWidth={1.5} />}
                {loading ? "Looking up…" : "Track order"}
              </Button>
            </form>
          </CardContent>
        </Card>

        {/* Error */}
        {error && (
          <Card variant="flat" className="rounded-lg border-danger/40 bg-danger-subtle">
            <CardContent className="p-4 text-center">
              <p className="text-body-sm text-danger">{error}</p>
            </CardContent>
          </Card>
        )}

        {/* Result */}
        {order && (
          <motion.div
            key={order.orderNumber}
            variants={resultVariants}
            initial="hidden"
            animate="visible"
          >
            <Card variant="lifted" className="rounded-xl">
              <CardContent className="p-6 sm:p-8 space-y-6">
                {/* Order header */}
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0">
                    <p className="text-overline uppercase tracking-widest text-ink-tertiary">
                      {order.shopName}
                    </p>
                    <h2 className="mt-1 font-mono text-mono-lg font-semibold tabular-nums text-ink-primary break-all">
                      {order.orderNumber}
                    </h2>
                  </div>
                  <Badge
                    variant={statusVariant(order.status)}
                    size="md"
                    className="rounded-pill flex-shrink-0"
                  >
                    {isCancelled ? (
                      <XCircle className="h-3 w-3" strokeWidth={1.5} />
                    ) : order.status === "COMPLETED" ? (
                      <CheckCircle2 className="h-3 w-3" strokeWidth={1.5} />
                    ) : (
                      <span
                        aria-hidden="true"
                        className="inline-block h-1.5 w-1.5 rounded-full bg-current motion-safe:animate-pulse"
                      />
                    )}
                    {statusLabel(order.status)}
                  </Badge>
                </div>

                {/* Progress stepper — desktop horizontal, mobile vertical */}
                {!isCancelled && (
                  <div>
                    {/* Desktop: horizontal */}
                    <div className="hidden sm:block">
                      <div className="relative">
                        {/* Track */}
                        <div className="absolute left-0 right-0 top-4 h-0.5 bg-surface-muted rounded-full" aria-hidden="true" />
                        {/* Fill */}
                        <motion.div
                          aria-hidden="true"
                          className="absolute left-0 top-4 h-0.5 bg-brand-primary rounded-full"
                          initial={{ width: 0 }}
                          animate={{ width: `${progressPct}%` }}
                          transition={{ duration: DURATION.slow, ease: EASE.decelerate }}
                        />
                        <ol className="relative flex items-start justify-between">
                          {STEPS.map((step, i) => {
                            const done = i <= currentStep
                            const current = i === currentStep
                            const Icon = step.icon
                            return (
                              <li key={step.key} className="flex flex-col items-center gap-2 flex-1">
                                <div
                                  className={cn(
                                    "flex h-8 w-8 items-center justify-center rounded-pill border-2 transition-colors duration-default",
                                    done
                                      ? current
                                        ? "border-brand-primary bg-brand-primary text-ink-on-brand shadow-lift"
                                        : "border-brand-primary bg-brand-primary text-ink-on-brand"
                                      : "border-border-tone-subtle bg-surface-card text-ink-tertiary",
                                  )}
                                  aria-current={current ? "step" : undefined}
                                >
                                  <Icon className="h-3.5 w-3.5" strokeWidth={1.5} />
                                </div>
                                <span
                                  className={cn(
                                    "text-caption font-medium text-center",
                                    done ? "text-ink-primary" : "text-ink-tertiary",
                                  )}
                                >
                                  {step.label}
                                </span>
                              </li>
                            )
                          })}
                        </ol>
                      </div>
                    </div>

                    {/* Mobile: vertical */}
                    <ol className="sm:hidden space-y-4" aria-label="Order progress">
                      {STEPS.map((step, i) => {
                        const done = i <= currentStep
                        const current = i === currentStep
                        const Icon = step.icon
                        const isLast = i === STEPS.length - 1
                        return (
                          <li key={step.key} className="relative flex items-start gap-3">
                            <div className="flex flex-col items-center flex-shrink-0">
                              <div
                                className={cn(
                                  "flex h-8 w-8 items-center justify-center rounded-pill border-2 transition-colors duration-default",
                                  done
                                    ? "border-brand-primary bg-brand-primary text-ink-on-brand"
                                    : "border-border-tone-subtle bg-surface-card text-ink-tertiary",
                                )}
                                aria-current={current ? "step" : undefined}
                              >
                                <Icon className="h-3.5 w-3.5" strokeWidth={1.5} />
                              </div>
                              {!isLast && (
                                <div
                                  aria-hidden="true"
                                  className={cn(
                                    "mt-1 h-8 w-0.5 rounded-full",
                                    i < currentStep ? "bg-brand-primary" : "bg-surface-muted",
                                  )}
                                />
                              )}
                            </div>
                            <div className="pt-1.5">
                              <p
                                className={cn(
                                  "text-body-sm font-medium",
                                  done ? "text-ink-primary" : "text-ink-tertiary",
                                )}
                              >
                                {step.label}
                              </p>
                            </div>
                          </li>
                        )
                      })}
                    </ol>

                    {order.status !== "COMPLETED" && (
                      <p
                        className="mt-4 inline-flex items-center gap-2 text-caption text-ink-tertiary"
                        aria-live="polite"
                      >
                        <span
                          aria-hidden="true"
                          className="inline-block h-1.5 w-1.5 rounded-full bg-success motion-safe:animate-pulse"
                        />
                        Auto-refreshing every 15 seconds
                      </p>
                    )}
                  </div>
                )}

                {/* Item list */}
                {order.items && order.items.length > 0 && (
                  <div>
                    <h3 className="mb-3 text-overline uppercase tracking-widest text-ink-tertiary">
                      Items
                    </h3>
                    <Table>
                      <TableBody>
                        {order.items.map((item, i) => {
                          const title = item.title || item.productTitle || "Item"
                          const unit = item.priceAtPurchasePennies ?? item.pricePennies ?? 0
                          const line = unit * item.quantity
                          return (
                            <TableRow key={i}>
                              <TableCell className="py-3 text-ink-primary">
                                {title}
                              </TableCell>
                              <TableCell
                                numeric
                                className="py-3 text-ink-secondary"
                              >
                                ×{item.quantity}
                              </TableCell>
                              <TableCell numeric className="py-3 text-ink-primary">
                                {formatPrice(line)}
                              </TableCell>
                            </TableRow>
                          )
                        })}
                      </TableBody>
                      <TableFooter>
                        <TableRow>
                          <TableCell className="py-3 text-ink-secondary">
                            Total
                          </TableCell>
                          <TableCell numeric className="py-3 text-ink-tertiary">
                            {order.itemCount} item{order.itemCount !== 1 ? "s" : ""}
                          </TableCell>
                          <TableCell numeric className="py-3 font-mono tabular-nums text-body-lg font-semibold text-ink-primary">
                            {formatPrice(order.totalAmountPennies)}
                          </TableCell>
                        </TableRow>
                      </TableFooter>
                    </Table>
                  </div>
                )}

                {/* Fallback total when no line items are returned */}
                {(!order.items || order.items.length === 0) && (
                  <div className="flex items-center justify-between border-t border-border-tone-subtle pt-4">
                    <span className="text-body-sm text-ink-secondary">
                      Total · {order.itemCount} item{order.itemCount !== 1 ? "s" : ""}
                    </span>
                    <span className="font-mono tabular-nums text-body-lg font-semibold text-ink-primary">
                      {formatPrice(order.totalAmountPennies)}
                    </span>
                  </div>
                )}
              </CardContent>
            </Card>
          </motion.div>
        )}

        {/* Back link */}
        <div className="text-center">
          <Link
            href="/shop"
            className="inline-flex items-center gap-1.5 text-body-sm text-ink-tertiary hover:text-ink-primary transition-colors duration-fast"
          >
            <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
            <Store className="h-4 w-4" strokeWidth={1.5} />
            Browse shops
          </Link>
        </div>
      </div>
    </div>
  )
}

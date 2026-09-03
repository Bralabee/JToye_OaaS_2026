"use client"

import { Suspense, useState, useEffect, useRef } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import { m } from "framer-motion"
import { springPop } from "@/lib/motion"
import {
  Package, Search, Loader2, CheckCircle2, Clock,
  ChefHat, CircleDot, XCircle, Store
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"
import { PublicShell } from "@/components/public/public-shell"

export interface OrderStatus {
  orderNumber: string
  status: string
  shopName: string
  totalAmountPennies: number
  itemCount: number
  /**
   * COR-4 (V66): UNITS on the order — what the customer counted in the basket. `itemCount` is
   * LINES and is what this surface used to render under the word "items", so a 6-Zobo order read
   * "1 item" here and "6 items" on the basket minutes earlier.
   *
   * Optional AND nullable, and the two absences mean the same thing: NOT RECORDED. Absent = an
   * older backend; null = a row written before V66. Neither may be coalesced to 0 or replaced by
   * `itemCount` — the count is simply not rendered when it is not known.
   */
  unitCount?: number | null
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

/** Statuses that are still in flight — what a shopper actually came here for. */
export const ACTIVE_STATUSES = ["PENDING", "CONFIRMED", "PREPARING", "READY"]

/**
 * Which of a signed-in customer's own orders should the tracking view open on?
 *
 * Extracted (and exported) so the selection rule is unit-testable without
 * rendering the page — same idiom as `deriveOrdersView` on the My Orders page.
 *
 * - `wanted` (the `?order=` deep link) wins when it is one of THEIR orders. It
 *   is matched against the caller's own list rather than fetched by number, so
 *   this path can never surface an order belonging to somebody else.
 * - Otherwise: the most recent ACTIVE order, because "where is my food" is the
 *   question being asked. Only if nothing is in flight does it fall back to the
 *   most recent order overall, so the page is never blank for a real customer.
 */
export function pickTrackedOrder(
  orders: OrderStatus[],
  wanted?: string | null
): OrderStatus | null {
  if (wanted) {
    return orders.find((o) => o.orderNumber === wanted) ?? null
  }
  const active = orders.filter((o) => ACTIVE_STATUSES.includes(o.status))
  const pool = active.length > 0 ? active : orders
  const byNewest = [...pool].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )
  return byNewest[0] ?? null
}

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

// Guest order lookup (Surface H): order number + email, NO forced sign-in.
// Uses the IDOR-hardened public endpoint (email is mandatory proof-of-ownership,
// AUDIT-W0-02). A customer session only pre-fills the email; it is never required.
//
// #458: a SIGNED-IN customer no longer types anything. The session-authenticated
// proxy (/api/customer-orders -> core /public/orders/mine) already returns their
// own orders — proven by the HttpOnly access-token cookie, with no email
// parameter anywhere on that surface — so the page opens straight onto the order
// they are most likely asking about. The guest form below is UNCHANGED and still
// demands order number + email; it is simply not the first thing a signed-in
// customer sees.
export default function TrackOrderPage() {
  return (
    <PublicShell>
      <Suspense
        fallback={
          <div className="flex items-center justify-center py-24">
            <Loader2 className="h-8 w-8 animate-spin text-amber-500" />
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
  // WR-09: the email arrives OUT-OF-BAND — via the sessionStorage handoff set
  // by the confirmation/My-Orders links, or the customer-session pre-fill
  // below — never minted into our own URLs (PII in query strings persists in
  // history and access logs). A legacy ?email= param is still honoured so old
  // bookmarks keep working, but no page links with it any more.
  const [email, setEmail] = useState(() => {
    const fromParam = searchParams.get("email") || ""
    if (fromParam) return fromParam
    if (typeof window === "undefined") return ""
    try {
      return sessionStorage.getItem("jtoye-track-email") || ""
    } catch {
      return ""
    }
  })

  const [order, setOrder] = useState<OrderStatus | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // True once the page has opened itself on one of the signed-in customer's own
  // orders. Drives the "no typing required" surface; a guest never reaches it.
  const [autoResolved, setAutoResolved] = useState(false)
  // #458 item 2 — "should only be present when there's been an order". Set only
  // when we have positively established that this signed-in customer has NO
  // orders: session present, proxy answered 200, list empty. A failed or absent
  // request must never land here (the #467 lesson: "we could not ask" and "there
  // are none" are different answers, and printing the second for the first is a
  // confident lie), so it stays false on every error path.
  const [noOrdersOfTheirOwn, setNoOrdersOfTheirOwn] = useState(false)
  const [showManualForm, setShowManualForm] = useState(false)

  // Session pre-fill + #458 auto-population, in one pass.
  //
  // Pre-fill of the email is a convenience and never a requirement (unchanged).
  // What is new: for a customer with a live session we also ask the
  // session-authenticated proxy for THEIR orders and open on the right one.
  // Nothing here is reachable without the HttpOnly cookie, so the guest path
  // below is untouched — including its mandatory email challenge.
  useEffect(() => {
    let cancelled = false
    getCustomerSession().then(async (session) => {
      const sessionEmail = session?.profile?.email
      if (cancelled || !sessionEmail) return
      setEmail((prev) => prev || sessionEmail)

      try {
        const res = await fetch("/api/customer-orders?size=50", {
          credentials: "include",
          cache: "no-store",
        })
        if (!res.ok || cancelled) return
        const data = (await res.json()) as { content?: OrderStatus[] }
        if (cancelled) return
        const own = data.content ?? []
        const picked = pickTrackedOrder(own, searchParams.get("order"))
        if (!picked) {
          // Nothing to open on. Distinguish the two reasons, because they need
          // different pages: an empty history means "you have no orders yet",
          // whereas a ?order= that is not theirs is a genuine lookup they may
          // still want to complete against another email — so that one keeps
          // falling through to the form, exactly as before.
          if (own.length === 0) setNoOrdersOfTheirOwn(true)
          return
        }
        setOrder(picked)
        setOrderNumber(picked.orderNumber)
        setAutoResolved(true)
      } catch {
        /* fall through to the manual form — never a dead end */
      }
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Auto-search when the URL carries the order number and the email is
  // available from any out-of-band source (sessionStorage handoff / legacy
  // param) — e.g. arriving from the confirmation page link (WR-09).
  //
  // This used to be a MOUNT-ONLY effect reading `email`, which is "" at mount
  // whenever the address comes from the cookie-backed session (it resolves a
  // tick later). So the one case it was written for — a signed-in customer
  // following an order link — never fired and landed on an empty form. Same
  // failure mode as WR-07 on the per-shop tracking page. It now runs when the
  // email ARRIVES, guarded by a ref so it still fires exactly once.
  const autoSearched = useRef(false)

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

  useEffect(() => {
    if (autoSearched.current) return
    // Already opened on one of the customer's own orders — nothing to look up.
    if (order) {
      autoSearched.current = true
      return
    }
    if (!searchParams.get("order") || !email) return
    autoSearched.current = true
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    handleSearch()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [email, order])

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

  // A signed-in customer is shown their order, not a form. The form is still
  // one tap away (they may be chasing a guest order placed on another address).
  //
  // #458 item 2: the same applies with the opposite content when they have no
  // orders at all. Handing someone an "Order number" field and the placeholder
  // ORD-XXXXXXXX-XXXXXXXX-XXXXXXXX when the system already knows they have
  // nothing to look up is the "re-key a reference" complaint in its purest form.
  const formHidden = (autoResolved || noOrdersOfTheirOwn) && !showManualForm

  return (
    <div className="mx-auto max-w-lg px-4 py-8 sm:py-12">
      {/* The heading belongs to whichever block is carrying the message. When
          an order was found this row states it; when there is none, the card
          below is the <h1> and this row shrinks to just the escape hatch —
          saying "nothing to track yet" here AND "you haven't placed an order
          yet" immediately underneath is the same sentence twice, which is what
          filler copy looks like. */}
      {formHidden && (
        <div
          className={`mb-4 flex flex-wrap items-baseline gap-2 ${
            autoResolved ? "justify-between" : "justify-end"
          }`}
        >
          {autoResolved && (
            <div>
              <h1 className="text-xl font-bold text-oxblood">Your order</h1>
              <p className="mt-1 text-xs text-slate-600">
                Signed in — we found this one for you. No order number needed.
              </p>
            </div>
          )}
          <button
            type="button"
            data-testid="track-show-manual-form"
            onClick={() => setShowManualForm(true)}
            className="rounded-full border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 transition-colors duration-150 ease-out hover:bg-slate-50 hover:text-slate-900 active:scale-[0.97] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
          >
            {autoResolved ? "Track a different order" : "Track a guest order"}
          </button>
        </div>
      )}

      {/* #458 item 2 — the empty state, which is a DESTINATION and not a dead
          end. Reached only from a 200 with an empty list, never from a failed
          request. It says the true thing ("you have no orders") and offers the
          two moves that make sense from here; the guest form is still one tap
          away above, for an order placed on a different email. */}
      {noOrdersOfTheirOwn && !showManualForm && (
        <div
          className="rounded-xl border border-cream-100 bg-white p-6 text-center shadow-sm"
          data-testid="track-no-orders"
        >
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-cream">
            <Package className="h-6 w-6 text-slate-300" />
          </div>
          <h1 className="text-lg font-bold text-oxblood">
            You haven&apos;t placed an order yet
          </h1>
          <p className="mx-auto mt-1 max-w-xs text-sm text-slate-600">
            When you do, it will show up here automatically — you will never need
            to type an order number.
          </p>
          <Link
            href="/shop"
            className="mt-5 inline-flex items-center gap-2 rounded-full bg-oxblood px-5 py-2.5 text-sm font-semibold text-white transition-[transform,background-color] duration-150 ease-out active:scale-[0.97] [@media(hover:hover)_and_(pointer:fine)]:hover:bg-oxblood-700 motion-reduce:transition-none motion-reduce:active:scale-100"
          >
            <Store className="h-4 w-4" />
            Browse shops
          </Link>
        </div>
      )}

      {/* Search form — the GUEST path, unchanged: order number AND email are
          both required, and the email is the proof-of-ownership the public
          endpoint demands (AUDIT-W0-02). Only its VISIBILITY is conditional. */}
      <form
        onSubmit={handleSearch}
        hidden={formHidden}
        className="rounded-xl bg-white border border-cream-100 p-5 shadow-sm"
      >
        <h1 className="text-xl font-bold text-oxblood">Track your order</h1>
        <p className="mt-1 mb-4 text-xs text-slate-600">
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
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm font-mono text-slate-900 placeholder:text-slate-300 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200"
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
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-300 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-full bg-amber-500 py-3 text-sm font-bold text-amber-ink hover:bg-amber-400 disabled:opacity-60 transition-all"
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

      {/* Result. Entrance is opacity + a 0.97 scale over 240ms on a strong
          ease-out curve: it enters, so ease-out (instant movement) rather than
          ease-in, and it is well under the 300ms UI ceiling. NOT scale(0) —
          nothing in the real world appears from nothing. The transform half is
          dropped automatically under prefers-reduced-motion by the app-wide
          <MotionConfig reducedMotion="user">, leaving the opacity fade that
          still explains "something arrived". */}
      {order && (
        <m.div
          key={order.orderNumber}
          initial={{ opacity: 0, scale: 0.97, y: 8 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          transition={{ duration: 0.24, ease: [0.23, 1, 0.32, 1] }}
          className="mt-6 space-y-4"
        >
          {/* Shop + total */}
          <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-semibold text-slate-900">{order.shopName}</p>
                <p className="text-xs text-slate-600">
                  {/* COR-4: units when recorded; when not (a pre-V66 row), the price alone.
                      Falling back to itemCount would print the LINE count under the word
                      "items" — the defect — and coalescing null to 0 would claim an empty
                      order. Not rendering a number is the only honest third option. */}
                  {order.unitCount != null && (
                    <>{order.unitCount} item{order.unitCount !== 1 ? "s" : ""} &middot; </>
                  )}
                  {formatPrice(order.totalAmountPennies)}
                </p>
                <p className="mt-1 font-mono text-xs text-slate-300">{order.orderNumber}</p>
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
                <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
                  {/* An INDEFINITELY looping animation, so it is stopped under
                      prefers-reduced-motion (Tailwind emits a real
                      `@media (prefers-reduced-motion: reduce)` block for this
                      variant). The dot and its label stay — reduced motion means
                      gentler, not less information. `animate-pulse` is invisible
                      to framer-motion's MotionConfig, which only governs `m.*`
                      props, so it has to be gated in CSS. */}
                  <span className="h-1.5 w-1.5 rounded-full bg-amber-500 animate-pulse motion-reduce:animate-none" />
                  {STEPS[currentStep]?.label || order.status}
                </span>
              )}
            </div>
          </div>

          {/* Progress */}
          {!isCancelled && (
            <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
              <div className="flex items-center justify-between gap-1">
                {STEPS.map((step, i) => {
                  const isComplete = i <= currentStep
                  const isActive = i === currentStep
                  return (
                    <div key={step.key} className="flex flex-col items-center flex-1">
                      {/* Keyed on completion so a step newly reaching complete
                          remounts and springs in; active step pulses finitely. */}
                      <m.div
                        key={`${step.key}-${isComplete}`}
                        initial={{ scale: 0.6 }}
                        animate={{ scale: isActive ? [1, 1.08, 1] : 1 }}
                        transition={isActive ? { duration: 0.9, repeat: 2 } : springPop}
                        className={`flex h-8 w-8 items-center justify-center rounded-full text-xs ${
                          isComplete
                            ? isActive
                              ? "bg-amber-500 text-oxblood ring-2 ring-amber-200"
                              : "bg-emerald-500 text-white"
                            : "bg-slate-100 text-slate-400"
                        }`}
                      >
                        <step.icon className="h-3.5 w-3.5" />
                      </m.div>
                      <p className={`mt-1 text-xs font-medium ${isComplete ? "text-slate-700" : "text-slate-400"}`}>
                        {step.label}
                      </p>
                    </div>
                  )
                })}
              </div>
              {/* Progress bar — scaleX from the left, animated on status change */}
              <div className="mt-3 h-1 bg-slate-100 rounded-full overflow-hidden">
                <m.div
                  className="h-full w-full bg-amber-500 rounded-full"
                  style={{ transformOrigin: "left" }}
                  initial={false}
                  animate={{ scaleX: Math.max(0.05, currentStep / (STEPS.length - 1)) }}
                  transition={{ duration: 0.5, ease: "easeOut" }}
                />
              </div>
              <p className="mt-2 text-center text-xs text-slate-400">
                <span className="inline-flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse motion-reduce:animate-none" />
                  Auto-refreshing
                </span>
              </p>
            </div>
          )}
        </m.div>
      )}

      {/* Back links. A signed-in customer gets their order history too — the
          nav no longer carries a standalone "Track order" for them (#458), so
          the return path to the profile has to be on the page itself. Shown for
          the empty state as well: "no orders" is a fact about right now, and the
          profile is still where the next one will appear. */}
      <div className="mt-8 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-center">
        {(autoResolved || noOrdersOfTheirOwn) && (
          <Link
            href="/shop/orders"
            className="inline-flex items-center gap-1 text-sm text-slate-600 transition-colors hover:text-slate-700"
          >
            <Package className="h-4 w-4" />
            All my orders
          </Link>
        )}
        {/* Suppressed when the empty-state card is up: it already carries
            "Browse shops" as its primary action, and two identical CTAs one
            above the other is the shape that makes a page look generated. */}
        {!(noOrdersOfTheirOwn && !showManualForm) && (
          <Link
            href="/shop"
            className="inline-flex items-center gap-1 text-sm text-slate-600 transition-colors hover:text-slate-700"
          >
            <Store className="h-4 w-4" />
            Browse shops
          </Link>
        )}
      </div>
    </div>
  )
}

"use client"

import { Suspense, use, useEffect, useRef, useState, useCallback } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import { m } from "framer-motion"
import { springPop } from "@/lib/motion"
import {
  CheckCircle2, Store, Copy, ArrowLeft, Clock,
  ChefHat, Package, CircleDot, XCircle, Loader2, RefreshCw
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { getCustomerSession } from "@/lib/customer-auth"
import { RecordedAllergenSet } from "@/components/storefront/recorded-allergen-set"
import { SellerBlock } from "@/components/storefront/seller-block"
import { NoVatRegistrationNote } from "@/components/storefront/no-vat-registration-note"
import { consumeJustPlaced } from "@/lib/just-placed"
import { ORDER_STATUS_COPY, statusCopy, statusUpdateAnnouncement } from "@/lib/order-status-announcement"
import { asVatRate, vatRateLabel } from "@/lib/vat"
import type { FulfilmentType } from "@/types/api"
import type { PublicOrderAllergenRecord, PublicShop } from "@/types/storefront"

/**
 * The tracking response (`PublicOrderStatus`). The allergen record (31.1-09) and the allergy-note
 * status (31.1-13) come from {@link PublicOrderAllergenRecord}; all optional, absent = not recorded.
 */
interface OrderStatus extends PublicOrderAllergenRecord {
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
  /**
   * 37-37 (UI-SPEC C3): the breakdown the cash confirmation used to show in place at checkout, now
   * shown here. All optional: an older backend omits them, and an omitted value is NOT RENDERED —
   * never guessed (an absent fee is not "Free"; an absent fulfilment type is not "delivery").
   * totalAmountPennies = subtotalPennies + deliveryFeePennies (Order.calculateTotal); VAT is inside.
   */
  paymentStatus?: string | null
  fulfilmentType?: FulfilmentType | null
  subtotalPennies?: number | null
  deliveryFeePennies?: number | null
  vatRate?: string | null
  vatAmountPennies?: number | null
  createdAt: string
  updatedAt: string
}

// The step words live in lib/order-status-announcement.ts, shared with /track and the status
// region (37-51), so the tracker and the announcement can never name a step differently.
const STEPS = [
  { key: "PENDING", ...ORDER_STATUS_COPY.PENDING, icon: Clock },
  { key: "CONFIRMED", ...ORDER_STATUS_COPY.CONFIRMED, icon: CircleDot },
  { key: "PREPARING", ...ORDER_STATUS_COPY.PREPARING, icon: ChefHat },
  { key: "READY", ...ORDER_STATUS_COPY.READY, icon: Package },
  { key: "COMPLETED", ...ORDER_STATUS_COPY.COMPLETED, icon: CheckCircle2 },
]

/** Statuses no further update can follow — the poller stops on these (INT-8). */
const TERMINAL_STATUSES = ["COMPLETED", "CANCELLED", "REFUNDED"]

function getStepIndex(status: string): number {
  const idx = STEPS.findIndex((s) => s.key === status)
  return idx >= 0 ? idx : -1
}

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/** UK time, whatever zone the browser is in (goods P2-CHA-18): the shop and the customer read one clock. */
function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "Europe/London",
  })
}

export default function OrderTrackingPage({
  params,
}: {
  params: Promise<{ slug: string; orderNumber: string }>
}) {
  const { slug, orderNumber } = use(params)
  return (
    <Suspense fallback={<div className="mx-auto max-w-lg px-4 py-16 text-center"><Loader2 className="mx-auto h-8 w-8 animate-spin text-amber-500" /></div>}>
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

  // 37-37 (UI-SPEC C3, UXT-029): is this the FIRST view of an order the customer just placed? The
  // cash checkout leaves a one-shot sessionStorage marker (lib/just-placed.ts) and routes here; it is
  // consumed once, on mount, so a refresh or a later visit is a plain view. The ref keeps it to one
  // read under StrictMode's effect replay (the ref survives it; a second read would find nothing and
  // is never made). The marker is a hint only: everything shown is still read from the tracking
  // endpoint below, bound to number + email (F3).
  const [justPlaced, setJustPlaced] = useState(false)
  const markerReadRef = useRef(false)
  useEffect(() => {
    if (markerReadRef.current) return
    markerReadRef.current = true
    // eslint-disable-next-line react-hooks/set-state-in-effect -- sessionStorage is client-only and must be read exactly once after mount; a render-time read would double-consume under StrictMode
    if (consumeJustPlaced(orderNumber)) setJustPlaced(true)
  }, [orderNumber])

  // The seller, for the B7 VAT rule and the seller block (both carried over from the in-place cash
  // confirmation, 37-37). A GET of the public shop; a failed read proves nothing, so it shows neither.
  const [shop, setShop] = useState<PublicShop | null>(null)
  useEffect(() => {
    let cancelled = false
    publicApiClient
      .get<PublicShop>(`/public/shops/${slug}`)
      .then((res) => {
        if (!cancelled) setShop(res.data)
      })
      .catch(() => {
        /* no seller block and no VAT statement: an unloaded shop is not an undeclared seller */
      })
    return () => {
      cancelled = true
    }
  }, [slug])

  // 37-51 (F1): the last status a tracking read returned, and what the status region says about a
  // CHANGE. The first read only sets the baseline (loading is not a change, so it is not announced);
  // a read with the same status writes nothing, so the region's DOM is untouched and a screen reader
  // hears nothing; a failed read never reaches here and leaves both as they were (F1 error,
  // planner assumption: the visible error copy stays outside the region).
  const lastStatusRef = useRef<string | null>(null)
  const [statusUpdate, setStatusUpdate] = useState("")

  // F5 (37-F review): the 15 s poll, a Retry and the email-arrival effect can overlap, and their
  // answers can land out of order. Only the LATEST read's answer is applied, so an older PREPARING
  // landing after READY can neither move the tracker back nor announce the order going backwards.
  const readSeqRef = useRef(0)

  const fetchStatus = useCallback(async () => {
    const seq = ++readSeqRef.current
    if (!email) {
      setLoading(false)
      return
    }
    try {
      const res = await publicApiClient.get<OrderStatus>(
        `/public/orders/${orderNumber}`,
        { params: { email } }
      )
      if (seq !== readSeqRef.current) return
      const previous = lastStatusRef.current
      lastStatusRef.current = res.data.status
      if (previous !== null && previous !== res.data.status) {
        const update = statusUpdateAnnouncement(res.data.status, res.data.fulfilmentType)
        if (update) setStatusUpdate(update)
      }
      setOrder(res.data)
      setError(null)
    } catch {
      if (seq !== readSeqRef.current) return
      if (!order) setError("Could not load order status.")
    } finally {
      if (seq === readSeqRef.current) setLoading(false)
    }
  }, [orderNumber, email, order])

  // WR-07: fetch again whenever the email becomes available — the initial
  // value comes synchronously from localStorage, but a signed-in customer's
  // email arrives asynchronously from the cookie-backed session. A mount-only
  // fetch left that case on a dead skeleton (fetch ran with an empty email,
  // then nothing refetched and the EmailPrompt branch was skipped).
  // fetchStatus() no-ops (and clears loading) while the email is still empty.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    fetchStatus()
  }, [email]) // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-refresh every 15 seconds for active orders.
  //
  // INT-8: REFUNDED was in neither the stop list nor the STEPS array, so a
  // refunded order polled this endpoint every 15 seconds for as long as the tab
  // stayed open, and rendered "Order in Progress" while doing it.
  useEffect(() => {
    if (!order || TERMINAL_STATUSES.includes(order.status)) return
    const interval = setInterval(fetchStatus, 15000)
    return () => clearInterval(interval)
  }, [order, fetchStatus])

  // C3: the heading takes focus ONCE, on the first view, as soon as there is something to say —
  // the confirmation, or (C3 error, planner assumption) that the order was placed although its
  // details did not load. Later views leave focus where the browser puts it.
  const headingRef = useRef<HTMLHeadingElement>(null)
  const focusedRef = useRef(false)
  const settled = !loading && (order !== null || error !== null)
  useEffect(() => {
    if (!justPlaced || !settled || focusedRef.current) return
    focusedRef.current = true
    headingRef.current?.focus()
  }, [justPlaced, settled])

  // C3 title: the confirmed variant on the first view, the plain one on every other.
  useEffect(() => {
    if (!settled) return
    if (order) {
      document.title = justPlaced
        ? `Order ${orderNumber} confirmed · ${order.shopName} — J'Toye`
        : `Order ${orderNumber} · ${order.shopName} — J'Toye`
    } else {
      document.title = justPlaced ? `Order ${orderNumber} placed — J'Toye` : `Order ${orderNumber} — J'Toye`
    }
  }, [settled, justPlaced, order, orderNumber])

  const copyOrderNumber = () => {
    navigator.clipboard.writeText(orderNumber)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  // C3 error: Retry reads the tracking data again (a GET — never a re-submit) and puts focus back on
  // the heading, because the Retry button it was on is gone once the details load.
  const retryAfterPlacing = async () => {
    await fetchStatus()
    headingRef.current?.focus()
  }

  const isCancelled = order?.status === "CANCELLED"
  // INT-8: REFUNDED is terminal and is NOT in STEPS, so without its own branch
  // getStepIndex returns -1 and this page told a refunded customer their order
  // was "Order in Progress", under a pulsing amber clock.
  const isRefunded = order?.status === "REFUNDED"
  const currentStep = order ? getStepIndex(order.status) : -1

  // UXT-047 (37-14, B7) on the carried-over breakdown: a VAT row needs a DECLARED registration; the
  // note needs a LOADED shop that declares none.
  const sellerVatRegistered = Boolean(shop?.seller?.vatNumber)
  const sellerVatUndeclared = shop !== null && !sellerVatRegistered

  // The payment instruction the in-place confirmation led with (WR-08: "delivery" for a DELIVERY
  // order, never "collection"). Only for an unpaid cash order that can still be fulfilled: a card
  // order is paid, and a cancelled, refunded or completed order needs no instruction.
  const payOnLine =
    order &&
    order.paymentStatus === "NONE" &&
    order.fulfilmentType &&
    !TERMINAL_STATUSES.includes(order.status)
      ? `Pay on ${order.fulfilmentType === "COLLECTION" ? "collection" : "delivery"}`
      : null

  // C3: ONE status region, present from the first render (a live region added together with its
  // text is often not announced), written once the first view has something to say.
  const announcement = justPlaced && settled
    ? order
      ? `Order confirmed, ${orderNumber}`
      : `Order placed, ${orderNumber}`
    : ""
  // F1 (37-51): the same region carries each status CHANGE. The C3 first-view message stays its
  // first message until the order actually moves on; there is never a second region.
  const statusRegion = (
    <div role="status" aria-live="polite" className="sr-only">
      {statusUpdate || announcement}
    </div>
  )

  if (loading) {
    return (
      <>
        {statusRegion}
        <div className="mx-auto max-w-lg px-4 py-16 text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-amber-500" />
          <p className="mt-3 text-sm text-slate-600">Loading order status...</p>
        </div>
      </>
    )
  }

  // No email — inline email prompt (no redirect needed)
  if (!email && !order) {
    return (
      <>
        {statusRegion}
        <EmailPrompt orderNumber={orderNumber} onSubmit={(e) => {
          localStorage.setItem(`jtoye-checkout-email-${slug}`, e)
          window.location.reload()
        }} />
      </>
    )
  }

  // C3 heading: "Order confirmed" on the first view, "Order {number} placed" when that first view
  // could not load the details, and "Order {number}" on every other view. One element in all three,
  // so focus stays on it when a Retry turns the second into the first.
  const showPlacedFallback = justPlaced && !order && error !== null
  const headingText = justPlaced
    ? order
      ? "Order confirmed"
      : `Order ${orderNumber} placed`
    : `Order ${orderNumber}`

  return (
    <div className="mx-auto max-w-lg px-4 sm:px-6 py-8">
      {statusRegion}
      {/* Header */}
      <div className="text-center mb-8">
        {!error && order && (
          isCancelled ? (
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-red-100">
              <XCircle className="h-8 w-8 text-red-500" />
            </div>
          ) : isRefunded ? (
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-orange-100">
              <RefreshCw className="h-8 w-8 text-orange-700" />
            </div>
          ) : currentStep >= 4 || justPlaced ? (
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100">
              <CheckCircle2 className="h-8 w-8 text-emerald-600" />
            </div>
          ) : (
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-amber-100">
              <Clock className="h-8 w-8 text-amber-700" />
            </div>
          )
        )}
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="mt-4 text-2xl font-semibold text-slate-900 [overflow-wrap:anywhere] focus:outline-none"
        >
          {headingText}
        </h1>
        {!error && order && (
          <>
            {/* The status words the heading used to carry (the C3 heading is about the order). */}
            <p className="mt-1 text-sm font-semibold text-slate-700">
              {isCancelled
                ? "Order Cancelled"
                : isRefunded
                  ? "Order Refunded"
                  : currentStep >= 4
                    ? "Order Complete!"
                    : order.status === "DRAFT"
                      ? // #957: an unpaid card order is not in progress — the shop won't prepare it.
                        ORDER_STATUS_COPY.DRAFT.label
                      : "Order in Progress"}
            </p>
            <p className="mt-1 text-sm text-slate-600">{order.shopName}</p>
            {payOnLine && <p className="mt-1 text-sm font-medium text-slate-900">{payOnLine}</p>}
          </>
        )}
      </div>

      {/* Order number */}
      <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm text-center mb-6">
        {/* 31.1-28 (#878): this page joined the published accessibility scope, and the WCAG 2.2
            axe scan found the label at slate-400 (2.56:1 on white) and the copy button with no
            accessible name. The label is slate-600 (the A11Y-11 precedent); the button is named. */}
        <p className="text-xs font-medium text-slate-600 uppercase tracking-wider">Order number</p>
        <div className="mt-1 flex items-center justify-center gap-2">
          <p className="text-sm font-bold font-mono text-slate-900 [overflow-wrap:anywhere]">{orderNumber}</p>
          <button
            type="button"
            onClick={copyOrderNumber}
            aria-label="Copy order number"
            className="flex h-6 w-6 items-center justify-center rounded hover:bg-slate-100 text-slate-400 hover:text-slate-600 transition-colors"
          >
            <Copy className="h-3 w-3" aria-hidden="true" />
          </button>
          {copied && <span className="text-xs text-emerald-600">Copied!</span>}
        </div>
        {order && (
          <div className="mt-2 flex items-center justify-center gap-3 text-xs text-slate-600">
            {/* COR-4: units when recorded, nothing when not — see the note on the interface. */}
            {order.unitCount != null && (
              <span>{order.unitCount} item{order.unitCount !== 1 ? "s" : ""}</span>
            )}
            <span>{formatPrice(order.totalAmountPennies)}</span>
          </div>
        )}
      </div>

      {/* C3 error (planner assumption): the first view's tracking read failed, but the order WAS
          placed — say so, and offer a Retry, instead of an error that reads as a failed order. */}
      {showPlacedFallback && (
        <div className="rounded-xl bg-amber-50 border border-amber-600 p-4 text-center mb-6">
          <p className="text-sm text-slate-900">We couldn&apos;t load the latest details. Your order was placed.</p>
          <button
            type="button"
            onClick={retryAfterPlacing}
            className="mt-3 inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 bg-white px-4 text-sm font-medium text-slate-900 hover:bg-slate-50"
          >
            Retry
          </button>
        </div>
      )}

      {/* Error state */}
      {error && !showPlacedFallback && (
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
      {order && !isCancelled && !isRefunded && (
        <div className="rounded-xl bg-white border border-slate-100 p-5 shadow-sm mb-6">
          {/* F1 (37-51): an ordered list of the steps, the current one marked aria-current="step".
              F6 (UXT-080): labels wrap — min-w-0 lets the label column shrink, and nothing here
              truncates, so a 130% root text size at 360px breaks lines instead of clipping.
              37-53 review fix: between words (break-words breaks a word only when it cannot fit a
              line on its own), never mid-word as overflow-wrap:anywhere allows. */}
          <ol aria-label="Order progress" className="space-y-0">
            {STEPS.map((step, i) => {
              const isActive = i === currentStep
              const isComplete = i < currentStep
              const isPending = i > currentStep
              const isReached = isComplete || isActive
              // F2 (37-F review): READY says collection or delivery for THIS order — the same words
              // the status region announces (statusCopy is shared with statusUpdateAnnouncement).
              const stepDesc = statusCopy(step.key, order.fulfilmentType)?.desc ?? step.desc
              const Icon = step.icon

              return (
                <li
                  key={step.key}
                  data-tracker-step={step.key}
                  aria-current={isActive ? "step" : undefined}
                  className="flex gap-3"
                >
                  {/* Vertical line + circle */}
                  <div className="flex flex-col items-center" aria-hidden="true">
                    {/* Keyed on reach so a newly reached step remounts and
                        springs in; the active dot pulses finitely. */}
                    <m.div
                      key={`${step.key}-${isReached}`}
                      initial={{ scale: 0.6 }}
                      animate={{ scale: isActive ? [1, 1.08, 1] : 1 }}
                      transition={isActive ? { duration: 0.9, repeat: 2 } : springPop}
                      className={`flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full transition-colors ${
                        isComplete
                          ? "bg-emerald-500 text-white"
                          : isActive
                          ? "bg-amber-500 text-oxblood ring-4 ring-amber-100"
                          : "bg-slate-100 text-slate-400"
                      }`}
                    >
                      <Icon className="h-4 w-4" />
                    </m.div>
                    {i < STEPS.length - 1 && (
                      <div className="w-0.5 h-8 bg-slate-200 overflow-hidden">
                        {/* Emerald fill grows top-down when this segment's
                            step is complete */}
                        <m.div
                          className="h-full w-full bg-emerald-500"
                          style={{ transformOrigin: "top" }}
                          initial={false}
                          animate={{ scaleY: isComplete ? 1 : 0 }}
                          transition={{ duration: 0.5, ease: "easeOut" }}
                        />
                      </div>
                    )}
                  </div>

                  {/* Label */}
                  <div className="min-w-0 flex-1 pb-6 break-words">
                    <p
                      className={`text-sm font-medium ${
                        isComplete || isActive ? "text-slate-900" : "text-slate-600"
                      }`}
                    >
                      {step.label}
                    </p>
                    <p className={`text-xs ${isActive ? "text-amber-700" : "text-slate-600"}`}>
                      {isActive && order.updatedAt
                        ? `${stepDesc} · ${formatTime(order.updatedAt)}`
                        : stepDesc}
                    </p>
                  </div>
                </li>
              )
            })}
          </ol>
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

      {/* Refunded state (INT-8). Stated plainly, with the same shape as the
          cancelled panel: a refund is terminal and the customer should not be
          left reading a progress stepper about it. */}
      {order && isRefunded && (
        <div className="rounded-xl bg-orange-50 border border-orange-100 p-5 mb-6 text-center">
          <p className="text-sm text-orange-700">
            This order was refunded. If you have any questions about the refund,
            please contact the shop.
          </p>
        </div>
      )}

      {/* 37-37 (UI-SPEC C3): the breakdown the cash confirmation showed in place at checkout — kept
          whole (goods P2-REG-17: the fees and the total, not just the total). Each row renders only
          from a value the tracking response carries; a missing value is left out, never guessed. */}
      {order && order.subtotalPennies != null && (
        <div data-testid="order-breakdown" className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm mb-6">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Order total</h2>
          <div className="space-y-2">
            <div className="flex items-center justify-between gap-3 text-sm">
              <span className="text-slate-600">Subtotal</span>
              <span className="text-slate-900">{formatPrice(order.subtotalPennies)}</span>
            </div>
            {order.deliveryFeePennies != null && (
              <div className="flex items-center justify-between gap-3 text-sm">
                <span className="text-slate-600">Delivery</span>
                {order.deliveryFeePennies > 0 ? (
                  <span className="text-slate-900">{formatPrice(order.deliveryFeePennies)}</span>
                ) : (
                  <span className="text-emerald-700 font-medium">Free</span>
                )}
              </div>
            )}
            {sellerVatRegistered && (order.vatAmountPennies ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-3 text-sm">
                <span className="text-slate-600">VAT ({vatRateLabel(asVatRate(order.vatRate))})</span>
                <span className="text-slate-900">{formatPrice(order.vatAmountPennies ?? 0)}</span>
              </div>
            )}
            <div className="flex items-center justify-between gap-3 pt-2 border-t border-cream-100">
              <span className="text-base font-bold text-slate-900">Total</span>
              <span className="text-base font-bold text-slate-900">{formatPrice(order.totalAmountPennies)}</span>
            </div>
            {sellerVatUndeclared && <NoVatRegistrationNote />}
          </div>
        </div>
      )}

      {/* What this order recorded about allergens, who placed it, and whether the shop read the
          customer's allergy note (31.1-18: #785, #812; D-07, D-08, D-15). Rendered from the
          tracking response only — this page computes nothing about allergens. Shown for every
          status, cancelled and refunded included: the record is a fact about the order. */}
      {order && (
        <RecordedAllergenSet
          acknowledged={order.acknowledgedAllergens ?? null}
          recorded={order.recordedAllergens ?? null}
          flags={order.recordedAllergenFlags ?? null}
          placedVia={order.placedVia ?? null}
          allergyNoteProvided={order.allergyNoteProvided ?? null}
          allergyNoteAcknowledgedAt={order.allergyNoteAcknowledgedAt ?? null}
          shopName={order.shopName}
          className="mb-6"
        />
      )}

      {/* #789 (31.1-24, D-13), carried over from the in-place cash confirmation (37-37): who the
          customer bought from. `shop` is null only while loading or when its read failed, and a
          failed read is not "the seller has not provided their details", so nothing renders then. */}
      {order && shop && <SellerBlock seller={shop.seller} className="mb-6" />}

      {/* Auto-refresh indicator */}
      {order && !isCancelled && !isRefunded && currentStep < 4 && (
        <p className="text-center text-xs text-slate-600 mb-6">
          <span className="inline-flex items-center gap-1">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
            Live updates every 15 seconds
          </span>
        </p>
      )}

      {/* Actions */}
      <div className="space-y-3">
        <Link
          href={`/track?order=${orderNumber}`}
          onClick={() => {
            // WR-09: hand the email to /track out-of-band. Embedding it in the
            // URL left PII in browser history, proxy/access logs and anything
            // that captures location.search — on a platform whose GDPR erasure
            // machinery scrubs exactly this address elsewhere.
            try {
              if (email) sessionStorage.setItem("jtoye-track-email", email)
            } catch {
              /* storage may be unavailable — /track falls back to its prompt */
            }
          }}
          className="flex w-full items-center justify-center gap-2 rounded-2xl border border-amber-300 bg-amber-50 py-2.5 text-sm font-medium text-amber-800 hover:bg-amber-100 transition-colors"
        >
          <Package className="h-4 w-4" />
          Track this order
        </Link>
        <Link
          href={`/shop/${slug}`}
          className="flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood py-3 text-sm font-bold text-white hover:bg-oxblood-700 transition-all"
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
        <p className="mt-2 text-sm text-slate-600">
          Enter the email you used when placing this order.
        </p>
      </div>
      <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
        <p className="text-xs font-mono text-slate-600 mb-3">{orderNumber}</p>
        <form onSubmit={(e) => { e.preventDefault(); if (emailInput.trim()) onSubmit(emailInput.trim()) }}>
          <input
            type="email"
            required
            value={emailInput}
            onChange={(e) => setEmailInput(e.target.value)}
            placeholder="your@email.com"
            className="w-full rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200"
          />
          <button
            type="submit"
            className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl bg-oxblood py-3 text-sm font-bold text-white hover:bg-oxblood-700 transition-all"
          >
            View order status
          </button>
        </form>
      </div>
    </div>
  )
}

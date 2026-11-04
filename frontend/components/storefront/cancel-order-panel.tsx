"use client"

// Client component because it holds the confirm/submit state and calls the cancel proxy (#868).

import { useId, useRef, useState } from "react"
import { XCircle } from "lucide-react"
import { withFullStop } from "@/lib/sentence-end"

/**
 * #868 (owner ruling 2026-11-04, persona close-out): a customer can cancel their own order while it is
 * still PENDING, i.e. before the kitchen accepts it. After that there is no button and the copy says to
 * contact the shop. A card-paid order is refunded in full by core through the existing refund path; this
 * panel only says so and reports what core answered.
 *
 * The server decides everything: the button is shown from the status the page last received, but core
 * re-checks (a kitchen that accepted a second ago gets a typed 409, which is shown and handed to
 * `onRefused` so the page can refresh). The Idempotency-Key is made once per panel and re-used on a retry,
 * so a second tap after a failure can never refund twice.
 */
export interface CancelResult {
  orderNumber?: string
  status: string
  paymentStatus?: string
  refunded?: boolean
  refundAmountPennies?: number | null
}

const SHOW_CONTACT_STATUSES = ["CONFIRMED", "PREPARING", "READY"]

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/**
 * What core refunds on a cancel (owner ruling 2026-11-04): what was ACTUALLY paid. An ordinary card order:
 * the total. A deposit order with the balance still due: the deposit. A deposit order whose balance is also
 * paid: the total. Null when the response does not carry the figures (the sentence then stays generic).
 */
export function refundAmountPennies(o: {
  totalAmountPennies?: number | null
  depositAmountPennies?: number | null
  balanceDuePennies?: number | null
}): { pennies: number; depositOnly: boolean } | null {
  const { totalAmountPennies: total, depositAmountPennies: deposit, balanceDuePennies: balance } = o
  if (deposit != null && deposit > 0 && balance != null && balance > 0) return { pennies: deposit, depositOnly: true }
  if (total != null && total > 0) return { pennies: total, depositOnly: false }
  return null
}

/**
 * Core refuses a PENDING order permanently unless it is pay-on-delivery (NONE) or a captured card payment:
 * a card payment still PENDING/AUTHORIZED, or FAILED, is a payment-state refusal, and so is a deposit/balance
 * order (whose fields this response does not carry, so core's 409 is what tells us). An absent status is left
 * to core.
 */
function paymentBlocksSelfCancel(paymentStatus?: string | null): boolean {
  return !!paymentStatus && !["NONE", "CAPTURED"].includes(paymentStatus)
}

export function CancelOrderPanel({
  orderNumber,
  shopName,
  status,
  paymentStatus,
  email,
  totalAmountPennies,
  depositAmountPennies,
  balanceDuePennies,
  onCancelled,
  onRefused,
}: {
  orderNumber: string
  shopName: string
  status: string
  paymentStatus?: string | null
  /** The guest proof. Omitted/blank for a signed-in customer, whose token the server reads from its cookie. */
  email?: string
  /** The order's money, so a deposit order can say what will actually be refunded. All optional. */
  totalAmountPennies?: number | null
  depositAmountPennies?: number | null
  balanceDuePennies?: number | null
  onCancelled: (result: CancelResult) => void
  /** Core refused (typed 409): the order is no longer PENDING. The page should refresh it. */
  onRefused?: () => void
}) {
  const [confirming, setConfirming] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Core said, permanently, that this order can't be cancelled online (typed 409). Shown INSTEAD of the
  // button, so the customer is never offered a cancel core has just refused (a deposit order would loop).
  const [refusal, setRefusal] = useState<string | null>(null)
  const keyRef = useRef<string | null>(null)
  const headingId = useId()

  if (SHOW_CONTACT_STATUSES.includes(status)) {
    return (
      <p className="rounded-xl border border-cream-100 bg-white p-4 text-sm text-slate-700 shadow-sm">
        {shopName} has accepted this order, so it can no longer be cancelled online. To change or cancel it,
        {" "}
        {withFullStop(`please contact ${shopName}`)}
      </p>
    )
  }
  if (status !== "PENDING") return null

  if (refusal || paymentBlocksSelfCancel(paymentStatus)) {
    return (
      <p
        role={refusal ? "alert" : undefined}
        className="rounded-xl border border-cream-100 bg-white p-4 text-sm text-slate-700 shadow-sm"
      >
        {refusal ??
          `This order can't be cancelled online while its payment is being settled. ${withFullStop(`Please contact ${shopName}`)}`}
      </p>
    )
  }

  const cardPaid = paymentStatus === "CAPTURED"
  const refund = cardPaid ? refundAmountPennies({ totalAmountPennies, depositAmountPennies, balanceDuePennies }) : null

  async function submit() {
    setSubmitting(true)
    setError(null)
    if (!keyRef.current) keyRef.current = crypto.randomUUID()
    try {
      const res = await fetch(`/api/customer-orders/${encodeURIComponent(orderNumber)}/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": keyRef.current },
        body: JSON.stringify(email && email.trim() ? { email: email.trim() } : {}),
      })
      const data = await res.json().catch(() => ({}))
      if (res.ok) {
        onCancelled(data as CancelResult)
        return
      }
      if (res.status === 409) {
        setRefusal(
          typeof data.detail === "string"
            ? data.detail
            : `This order can no longer be cancelled online. ${withFullStop(`Please contact ${shopName}`)}`
        )
        // A different outcome now: start a fresh key if the customer ever tries again.
        keyRef.current = null
        onRefused?.()
      } else if (res.status === 502 && data.code === "REFUND_FAILED") {
        // Core's own typed answer: the order IS cancelled, the card refund has not completed. The same
        // Idempotency-Key is kept, so pressing the button again finishes the refund and never repeats it.
        setError(
          typeof data.detail === "string"
            ? data.detail
            : "Your order has been cancelled, but we couldn't complete the refund to your card yet. Please try again, or contact the shop."
        )
      } else if (res.status === 502) {
        // The Next proxy's own 502 (core unreachable) or any gateway fault: nothing is known about the order.
        setError(withFullStop(`We couldn't reach ${shopName} just now. Please try again in a moment`))
      } else if (res.status === 404) {
        setError("We couldn't find this order to cancel it. Check your order number and email address.")
      } else {
        setError(withFullStop(`Something went wrong cancelling the order. Please try again, or contact ${shopName}`))
      }
    } catch {
      setError(withFullStop(`Something went wrong cancelling the order. Please try again, or contact ${shopName}`))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section aria-labelledby={headingId} className="rounded-xl border border-cream-100 bg-white p-4 shadow-sm">
      <h2 id={headingId} className="text-sm font-semibold text-slate-900">
        Changed your mind?
      </h2>
      <p className="mt-1 text-xs text-slate-600">
        You can cancel until {shopName} accepts your order. After that, please contact the shop.
      </p>

      {!confirming ? (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          className="mt-3 inline-flex min-h-11 items-center gap-1.5 rounded-lg border border-red-300 bg-white px-4 py-2 text-sm font-medium text-red-800 hover:bg-red-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
        >
          <XCircle className="h-4 w-4" aria-hidden />
          Cancel this order
        </button>
      ) : (
        <div className="mt-3 space-y-3">
          <p className="text-sm text-slate-800">
            Cancel order {orderNumber}?
            {cardPaid &&
              (refund
                ? refund.depositOnly
                  ? ` The ${formatPrice(refund.pennies)} deposit you paid will be refunded to the card you paid with. You will not be asked for the balance.`
                  : ` The ${formatPrice(refund.pennies)} you paid will be refunded in full to the card you paid with.`
                : " The amount you paid will be refunded in full to the card you paid with.")}
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={submit}
              disabled={submitting}
              className="inline-flex min-h-11 items-center rounded-lg bg-red-700 px-4 py-2 text-sm font-medium text-white hover:bg-red-800 disabled:opacity-60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
            >
              {submitting ? "Cancelling…" : "Yes, cancel order"}
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirming(false)
                setError(null)
              }}
              disabled={submitting}
              className="inline-flex min-h-11 items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-800 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
            >
              Keep my order
            </button>
          </div>
        </div>
      )}

      {error && (
        <p role="alert" className="mt-3 text-sm font-medium text-red-800">
          {error}
        </p>
      )}
    </section>
  )
}

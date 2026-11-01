"use client"

import { useState, useCallback } from "react"
import { CreditCard, Loader2, Lock } from "lucide-react"
import { PaymentElement, useStripe, useElements } from "@stripe/react-stripe-js"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/**
 * The card form: the Payment Element, the Pay CTA and the "Secured by Stripe" line. Rendered inside
 * Stripe Elements context.
 *
 * Moved out of the checkout page (issue #957, Phase 30-02) so the payment-link page (/pay) uses the
 * same form. Markup, classes and copy are unchanged from the checkout's former inline PaymentForm.
 * What the page used to do itself is now passed in:
 *   - `returnUrl`: where Stripe sends a redirect-based payment method back to;
 *   - `onSucceeded`: what happens after a payment that needed no redirect (the checkout saves the
 *     order locally, clears the basket and routes to the order page, exactly as before);
 *   - `onLoadError`: the Payment Element could not load (the page shows its failure state).
 *
 * It renders no h1: the page that hosts it owns the page heading.
 *
 * `slug` and `orderNumber` name the order being paid. The form itself no longer derives anything from
 * them (the host builds `returnUrl` and owns `onSucceeded`), but both hosts pass them so the contract
 * says which order a form instance belongs to.
 */
export function CardPaymentForm({
  customerEmail,
  totalPennies,
  returnUrl,
  onSucceeded,
  onLoadError,
}: {
  slug: string
  orderNumber: string
  customerEmail: string
  totalPennies: number
  returnUrl: string
  onSucceeded: () => void
  onLoadError: () => void
}) {
  const stripe = useStripe()
  const elements = useElements()
  const [paying, setPaying] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handlePayment = useCallback(async (e: React.FormEvent) => {
    e.preventDefault()
    if (!stripe || !elements) return

    setError(null)
    setPaying(true)

    try {
      // The payment-link page (/pay) knows no customer email and passes "": Stripe is then sent no
      // receipt_email at all, rather than an empty one. The checkout always passes one, unchanged.
      const email = customerEmail.trim()
      const { error: stripeError } = await stripe.confirmPayment({
        elements,
        confirmParams: email ? { return_url: returnUrl, receipt_email: customerEmail } : { return_url: returnUrl },
        redirect: "if_required",
      })

      if (stripeError) {
        setError(stripeError.message || "Payment failed. Please try again.")
        setPaying(false)
        return
      }

      // Payment succeeded without redirect — the host decides what follows.
      onSucceeded()
    } catch {
      setError("Something went wrong. Please try again.")
      setPaying(false)
    }
  }, [stripe, elements, returnUrl, customerEmail, onSucceeded])

  return (
    <form onSubmit={handlePayment} className="space-y-4">
      <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
        <div className="flex items-center gap-2 mb-3">
          <CreditCard className="h-4 w-4 text-slate-600" />
          <h2 className="text-sm font-semibold text-slate-900">Payment details</h2>
        </div>
        <PaymentElement
          options={{
            layout: "tabs",
          }}
          onLoadError={onLoadError}
        />
      </div>

      {error && (
        <div className="rounded-xl bg-red-50 border border-red-100 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <button
        type="submit"
        disabled={paying || !stripe || !elements}
        className="flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood py-3.5 text-sm font-bold text-white hover:bg-oxblood-700 active:scale-[0.98] transition-all shadow-lg disabled:opacity-60 disabled:cursor-not-allowed"
      >
        {paying ? (
          <>
            <Loader2 className="h-4 w-4 animate-spin" />
            Processing payment...
          </>
        ) : (
          <>
            <Lock className="h-3.5 w-3.5" />
            Pay {formatPrice(totalPennies)}
          </>
        )}
      </button>

      <div className="flex items-center justify-center gap-1 text-xs text-slate-400">
        <Lock className="h-3 w-3" />
        Secured by Stripe. Your card details never touch our servers.
      </div>
    </form>
  )
}

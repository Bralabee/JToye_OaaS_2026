"use client"

import { use, useState, useCallback } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { ArrowLeft, ShoppingBag, Loader2, CreditCard, Lock } from "lucide-react"
import { loadStripe } from "@stripe/stripe-js"
import { Elements, PaymentElement, useStripe, useElements } from "@stripe/react-stripe-js"
import { useCart } from "@/components/storefront/cart-provider"
import { getCustomerSession } from "@/lib/customer-auth"
import { saveLocalOrder } from "@/lib/order-history"
import publicApiClient from "@/lib/public-api-client"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

interface OrderConfirmation {
  orderNumber: string
  status: string
  totalAmountPennies: number
  shopName: string
  itemCount: number
  clientSecret: string
}

const stripePromise = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  ? loadStripe(process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY)
  : null

/**
 * Inner payment form — rendered inside Stripe Elements context.
 */
function PaymentForm({
  slug,
  orderNumber,
  customerEmail,
  totalPennies,
}: {
  slug: string
  orderNumber: string
  customerEmail: string
  totalPennies: number
}) {
  const stripe = useStripe()
  const elements = useElements()
  const router = useRouter()
  const { clearCart } = useCart()
  const [paying, setPaying] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handlePayment = useCallback(async (e: React.FormEvent) => {
    e.preventDefault()
    if (!stripe || !elements) return

    setError(null)
    setPaying(true)

    try {
      const { error: stripeError } = await stripe.confirmPayment({
        elements,
        confirmParams: {
          return_url: `${window.location.origin}/shop/${slug}/orders/${orderNumber}`,
          receipt_email: customerEmail,
        },
        redirect: "if_required",
      })

      if (stripeError) {
        setError(stripeError.message || "Payment failed. Please try again.")
        setPaying(false)
        return
      }

      // Payment succeeded without redirect — save and navigate
      saveLocalOrder({
        orderNumber,
        email: customerEmail,
        shopSlug: slug,
        placedAt: new Date().toISOString(),
      })
      clearCart()
      router.push(`/shop/${slug}/orders/${orderNumber}`)
    } catch {
      setError("Something went wrong. Please try again.")
      setPaying(false)
    }
  }, [stripe, elements, slug, orderNumber, customerEmail, clearCart, router])

  return (
    <form onSubmit={handlePayment} className="space-y-4">
      <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm">
        <div className="flex items-center gap-2 mb-3">
          <CreditCard className="h-4 w-4 text-slate-500" />
          <h2 className="text-sm font-semibold text-slate-900">Payment details</h2>
        </div>
        <PaymentElement
          options={{
            layout: "tabs",
          }}
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
        className="flex w-full items-center justify-center gap-2 rounded-2xl bg-orange-500 py-3.5 text-sm font-bold text-white hover:bg-orange-600 active:scale-[0.98] transition-all shadow-lg disabled:opacity-60 disabled:cursor-not-allowed"
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

      <div className="flex items-center justify-center gap-1 text-[10px] text-slate-400">
        <Lock className="h-3 w-3" />
        Secured by Stripe. Your card details never touch our servers.
      </div>
    </form>
  )
}

export default function CheckoutPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params)
  const router = useRouter()
  const { items, totalPennies, itemCount, clearCart } = useCart()

  // Pre-fill from customer session if logged in
  const session = typeof window !== "undefined" ? getCustomerSession() : null
  const [customerName, setCustomerName] = useState(session?.profile.name || "")
  const [customerEmail, setCustomerEmail] = useState(session?.profile.email || "")
  const [customerPhone, setCustomerPhone] = useState("")
  const [notes, setNotes] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // After order is created, holds the Stripe client secret + order number
  const [paymentState, setPaymentState] = useState<{
    clientSecret: string
    orderNumber: string
  } | null>(null)

  if (items.length === 0 && !paymentState) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-16 text-center">
        <ShoppingBag className="mx-auto h-16 w-16 text-slate-200" />
        <h2 className="mt-4 text-lg font-semibold text-slate-900">Nothing to checkout</h2>
        <p className="mt-1 text-sm text-slate-500">Add items from the menu first.</p>
        <Link
          href={`/shop/${slug}`}
          className="mt-6 inline-flex items-center gap-2 rounded-full bg-orange-500 px-5 py-2.5 text-sm font-semibold text-white hover:bg-orange-600 transition-colors"
        >
          Browse menu
        </Link>
      </div>
    )
  }

  // Step 1: Collect customer details and create order
  const handleCreateOrder = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)

    try {
      const payload = {
        customerName: customerName.trim(),
        customerEmail: customerEmail.trim(),
        customerPhone: customerPhone.trim(),
        notes: notes.trim() || undefined,
        items: items.map((item) => ({
          productId: item.productId,
          quantity: item.quantity,
        })),
      }

      const res = await publicApiClient.post<OrderConfirmation>(
        `/public/shops/${slug}/orders`,
        payload
      )

      const confirmation = res.data

      if (!confirmation.clientSecret) {
        // Fallback: no Stripe configured — order placed directly (COD mode)
        localStorage.setItem(`jtoye-checkout-email-${slug}`, customerEmail.trim())
        saveLocalOrder({
          orderNumber: confirmation.orderNumber,
          email: customerEmail.trim(),
          shopSlug: slug,
          placedAt: new Date().toISOString(),
        })
        clearCart()
        router.push(`/shop/${slug}/orders/${confirmation.orderNumber}`)
        return
      }

      // Store email for order tracking
      localStorage.setItem(`jtoye-checkout-email-${slug}`, customerEmail.trim())

      // Move to payment step
      setPaymentState({
        clientSecret: confirmation.clientSecret,
        orderNumber: confirmation.orderNumber,
      })
    } catch (err: unknown) {
      if (err && typeof err === "object" && "response" in err) {
        const axiosErr = err as { response?: { data?: { detail?: string } } }
        setError(axiosErr.response?.data?.detail || "Failed to place order. Please try again.")
      } else {
        setError("Failed to place order. Please try again.")
      }
    } finally {
      setSubmitting(false)
    }
  }

  // Step 2: Payment form (shown after order creation)
  if (paymentState && stripePromise) {
    return (
      <div className="mx-auto max-w-2xl px-4 sm:px-6 py-6">
        <button
          onClick={() => setPaymentState(null)}
          className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700 transition-colors mb-4"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to details
        </button>
        <h1 className="text-xl font-bold text-slate-900">Payment</h1>
        <p className="text-sm text-slate-500 mt-1">
          Order {paymentState.orderNumber} &middot; {formatPrice(totalPennies)}
        </p>

        {/* Order summary */}
        <div className="mt-4 rounded-xl bg-white border border-slate-100 p-4 shadow-sm mb-4">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Order summary</h2>
          <div className="space-y-2">
            {items.map((item) => (
              <div key={item.productId} className="flex items-center justify-between text-sm">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="flex-shrink-0 h-5 w-5 rounded bg-slate-100 flex items-center justify-center text-[10px] font-bold text-slate-600">
                    {item.quantity}
                  </span>
                  <span className="text-slate-700 truncate">{item.title}</span>
                </div>
                <span className="text-slate-900 font-medium flex-shrink-0 ml-2">
                  {formatPrice(item.pricePennies * item.quantity)}
                </span>
              </div>
            ))}
          </div>
          <div className="mt-3 border-t border-slate-100 pt-3 flex items-center justify-between">
            <span className="text-base font-bold text-slate-900">Total</span>
            <span className="text-base font-bold text-slate-900">{formatPrice(totalPennies)}</span>
          </div>
        </div>

        <Elements
          stripe={stripePromise}
          options={{
            clientSecret: paymentState.clientSecret,
            appearance: {
              theme: "stripe",
              variables: {
                colorPrimary: "#f97316",
                borderRadius: "12px",
                fontFamily: "system-ui, -apple-system, sans-serif",
              },
            },
          }}
        >
          <PaymentForm
            slug={slug}
            orderNumber={paymentState.orderNumber}
            customerEmail={customerEmail}
            totalPennies={totalPennies}
          />
        </Elements>
      </div>
    )
  }

  // Step 1: Customer details form
  return (
    <div className="mx-auto max-w-2xl px-4 sm:px-6 py-6">
      {/* Header */}
      <Link
        href={`/shop/${slug}/cart`}
        className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700 transition-colors mb-4"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to basket
      </Link>
      <h1 className="text-xl font-bold text-slate-900">Checkout</h1>
      <p className="text-sm text-slate-500 mt-1">{itemCount} item{itemCount !== 1 ? "s" : ""} &middot; {formatPrice(totalPennies)}</p>

      <form onSubmit={handleCreateOrder} className="mt-6 space-y-6">
        {/* Customer details */}
        <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm space-y-4">
          <h2 className="text-sm font-semibold text-slate-900">Your details</h2>

          <div className="space-y-1.5">
            <label htmlFor="name" className="block text-xs font-medium text-slate-600">Full name *</label>
            <input
              id="name"
              type="text"
              required
              value={customerName}
              onChange={(e) => setCustomerName(e.target.value)}
              placeholder="e.g., Ade Johnson"
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="email" className="block text-xs font-medium text-slate-600">Email address *</label>
            <input
              id="email"
              type="email"
              required
              value={customerEmail}
              onChange={(e) => setCustomerEmail(e.target.value)}
              placeholder="e.g., ade@example.com"
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="phone" className="block text-xs font-medium text-slate-600">Phone number *</label>
            <input
              id="phone"
              type="tel"
              required
              value={customerPhone}
              onChange={(e) => setCustomerPhone(e.target.value)}
              placeholder="e.g., 07700 900000"
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="notes" className="block text-xs font-medium text-slate-600">Order notes (optional)</label>
            <textarea
              id="notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Any special requests or dietary requirements..."
              rows={2}
              className="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100 resize-none"
            />
          </div>
        </div>

        {/* Order summary */}
        <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Order summary</h2>
          <div className="space-y-2">
            {items.map((item) => (
              <div key={item.productId} className="flex items-center justify-between text-sm">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="flex-shrink-0 h-5 w-5 rounded bg-slate-100 flex items-center justify-center text-[10px] font-bold text-slate-600">
                    {item.quantity}
                  </span>
                  <span className="text-slate-700 truncate">{item.title}</span>
                </div>
                <span className="text-slate-900 font-medium flex-shrink-0 ml-2">
                  {formatPrice(item.pricePennies * item.quantity)}
                </span>
              </div>
            ))}
          </div>
          <div className="mt-4 border-t border-slate-100 pt-3 flex items-center justify-between">
            <span className="text-base font-bold text-slate-900">Total</span>
            <span className="text-base font-bold text-slate-900">{formatPrice(totalPennies)}</span>
          </div>
        </div>

        {/* Error */}
        {error && (
          <div className="rounded-xl bg-red-50 border border-red-100 p-3 text-sm text-red-700">
            {error}
          </div>
        )}

        {/* Submit */}
        <button
          type="submit"
          disabled={submitting}
          className="flex w-full items-center justify-center gap-2 rounded-2xl bg-orange-500 py-3.5 text-sm font-bold text-white hover:bg-orange-600 active:scale-[0.98] transition-all shadow-lg disabled:opacity-60 disabled:cursor-not-allowed"
        >
          {submitting ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" />
              Creating order...
            </>
          ) : (
            <>
              <CreditCard className="h-4 w-4" />
              Continue to payment &middot; {formatPrice(totalPennies)}
            </>
          )}
        </button>
      </form>
    </div>
  )
}

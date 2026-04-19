"use client"

import { use, useState, useCallback, useEffect, useRef } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { motion } from "framer-motion"
import { ArrowLeft, ShoppingBag, CreditCard, Lock, CheckCircle } from "lucide-react"
import { loadStripe } from "@stripe/stripe-js"
import { Elements, PaymentElement, useStripe, useElements } from "@stripe/react-stripe-js"
import { useCart } from "@/components/storefront/cart-provider"
import { getCustomerSession } from "@/lib/customer-auth"
import { saveLocalOrder } from "@/lib/order-history"
import publicApiClient from "@/lib/public-api-client"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { fadeUp, useReducedMotionSafe } from "@/lib/motion"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

interface OrderConfirmation {
  orderNumber: string
  status: string
  subtotalPennies: number
  deliveryFeePennies: number
  vatRate: string
  vatAmountPennies: number
  totalAmountPennies: number
  shopName: string
  itemCount: number
  clientSecret: string
  allergenWarnings: string[]
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
      <Card variant="lifted" className="p-4">
        <CardContent className="p-0">
          <div className="flex items-center gap-2 mb-3">
            <CreditCard className="h-4 w-4 text-ink-tertiary" strokeWidth={1.5} />
            <h2 className="text-body-sm font-semibold text-ink-primary">Payment details</h2>
          </div>
          <PaymentElement
            options={{
              layout: "tabs",
            }}
          />
        </CardContent>
      </Card>

      {error && (
        <Card variant="flat" className="border-danger/40 bg-danger-subtle p-3">
          <CardContent className="p-0 text-body-sm text-danger">{error}</CardContent>
        </Card>
      )}

      <Button
        type="submit"
        variant="primary"
        size="lg"
        className="w-full rounded-pill shadow-lift"
        isLoading={paying}
        disabled={paying || !stripe || !elements}
      >
        {paying ? (
          "Processing payment…"
        ) : (
          <>
            <Lock className="h-3.5 w-3.5" strokeWidth={1.5} />
            <span>Pay</span>
            <span className="font-mono tabular-nums">{formatPrice(totalPennies)}</span>
          </>
        )}
      </Button>

      <div className="flex items-center justify-center gap-1 text-[10px] text-ink-tertiary">
        <Lock className="h-3 w-3" strokeWidth={1.5} />
        Secured by Stripe. Your card details never touch our servers.
      </div>
    </form>
  )
}

export default function CheckoutPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params)
  const { items, totalPennies, itemCount, clearCart } = useCart()
  const pageVariants = useReducedMotionSafe(fadeUp)

  // Pre-fill from customer session if logged in (async, cookie-backed)
  const [customerName, setCustomerName] = useState("")
  const [customerEmail, setCustomerEmail] = useState("")
  useEffect(() => {
    let cancelled = false
    getCustomerSession().then((session) => {
      if (cancelled || !session) return
      setCustomerName((prev) => prev || session.profile.name || "")
      setCustomerEmail((prev) => prev || session.profile.email || "")
    })
    return () => {
      cancelled = true
    }
  }, [])
  const [customerPhone, setCustomerPhone] = useState("")
  const [notes, setNotes] = useState("")
  const idempotencyKeyRef = useRef(crypto.randomUUID())
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // After order is created, holds the Stripe client secret + order details
  const [paymentState, setPaymentState] = useState<{
    clientSecret: string
    orderNumber: string
    subtotalPennies: number
    deliveryFeePennies: number
    vatRate: string
    vatAmountPennies: number
    totalAmountPennies: number
    allergenWarnings: string[]
  } | null>(null)

  // COD confirmation — shows full breakdown before redirect
  const [codConfirmation, setCodConfirmation] = useState<{
    orderNumber: string
    subtotalPennies: number
    deliveryFeePennies: number
    vatRate: string
    vatAmountPennies: number
    totalAmountPennies: number
    allergenWarnings: string[]
  } | null>(null)

  if (items.length === 0 && !paymentState && !codConfirmation) {
    return (
      <div className="bg-surface-canvas min-h-screen">
        <motion.div
          variants={pageVariants}
          initial="hidden"
          animate="visible"
          className="mx-auto max-w-2xl px-4 py-16 text-center"
        >
          <ShoppingBag className="mx-auto h-16 w-16 text-ink-quaternary" strokeWidth={1.5} />
          <h2 className="mt-4 font-display text-display-sm font-medium tracking-tight text-ink-primary">
            Nothing to checkout
          </h2>
          <p className="mt-2 text-body-sm text-ink-secondary">Add items from the menu first.</p>
          <div className="mt-6">
            <Button asChild variant="primary" size="lg" className="rounded-pill">
              <Link href={`/shop/${slug}`}>Browse menu</Link>
            </Button>
          </div>
        </motion.div>
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
        idempotencyKey: idempotencyKeyRef.current,
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
        // COD mode — show confirmation with full breakdown before redirect
        localStorage.setItem(`jtoye-checkout-email-${slug}`, customerEmail.trim())
        saveLocalOrder({
          orderNumber: confirmation.orderNumber,
          email: customerEmail.trim(),
          shopSlug: slug,
          placedAt: new Date().toISOString(),
        })
        clearCart()
        setCodConfirmation({
          orderNumber: confirmation.orderNumber,
          subtotalPennies: confirmation.subtotalPennies,
          deliveryFeePennies: confirmation.deliveryFeePennies || 0,
          vatRate: confirmation.vatRate,
          vatAmountPennies: confirmation.vatAmountPennies,
          totalAmountPennies: confirmation.totalAmountPennies,
          allergenWarnings: confirmation.allergenWarnings || [],
        })
        return
      }

      // Store email for order tracking
      localStorage.setItem(`jtoye-checkout-email-${slug}`, customerEmail.trim())

      // Move to payment step
      setPaymentState({
        clientSecret: confirmation.clientSecret,
        orderNumber: confirmation.orderNumber,
        subtotalPennies: confirmation.subtotalPennies,
        deliveryFeePennies: confirmation.deliveryFeePennies || 0,
        vatRate: confirmation.vatRate,
        vatAmountPennies: confirmation.vatAmountPennies,
        totalAmountPennies: confirmation.totalAmountPennies,
        allergenWarnings: confirmation.allergenWarnings || [],
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

  // COD confirmation — shows full price breakdown before tracking
  if (codConfirmation) {
    return (
      <div className="bg-surface-canvas min-h-screen">
        <motion.div
          variants={pageVariants}
          initial="hidden"
          animate="visible"
          className="mx-auto max-w-2xl px-4 sm:px-6 py-6"
        >
          <div className="text-center mb-6">
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-pill bg-success-subtle">
              <CheckCircle className="h-8 w-8 text-success" strokeWidth={1.5} />
            </div>
            <h1 className="mt-4 font-display text-display-sm font-medium tracking-tight text-ink-primary">
              Order confirmed
            </h1>
            <p className="mt-1 text-body-sm text-ink-secondary">
              <span className="font-mono tabular-nums">{codConfirmation.orderNumber}</span>
              {" · Pay on collection"}
            </p>
          </div>

          <Card variant="lifted" className="p-4 mb-6">
            <CardContent className="p-0">
              <h2 className="text-body-sm font-semibold text-ink-primary mb-3">Order total</h2>
              <div className="space-y-2">
                <div className="flex items-center justify-between text-body-sm">
                  <span className="text-ink-secondary">Subtotal</span>
                  <span className="font-mono tabular-nums text-ink-primary">
                    {formatPrice(codConfirmation.subtotalPennies)}
                  </span>
                </div>
                {codConfirmation.deliveryFeePennies > 0 ? (
                  <div className="flex items-center justify-between text-body-sm">
                    <span className="text-ink-secondary">Delivery</span>
                    <span className="font-mono tabular-nums text-ink-primary">
                      {formatPrice(codConfirmation.deliveryFeePennies)}
                    </span>
                  </div>
                ) : (
                  <div className="flex items-center justify-between text-body-sm">
                    <span className="text-ink-secondary">Delivery</span>
                    <span className="text-success font-medium">Free</span>
                  </div>
                )}
                {codConfirmation.vatAmountPennies > 0 && (
                  <div className="flex items-center justify-between text-body-sm">
                    <span className="text-ink-secondary">
                      VAT (
                      {codConfirmation.vatRate === "STANDARD"
                        ? "20%"
                        : codConfirmation.vatRate === "REDUCED"
                        ? "5%"
                        : "0%"}
                      )
                    </span>
                    <span className="font-mono tabular-nums text-ink-primary">
                      {formatPrice(codConfirmation.vatAmountPennies)}
                    </span>
                  </div>
                )}
                <div className="flex items-center justify-between pt-2 border-t border-border-tone-subtle">
                  <span className="font-display text-body-lg font-semibold text-ink-primary">
                    Total
                  </span>
                  <span className="font-mono tabular-nums text-body-lg font-semibold text-ink-primary">
                    {formatPrice(codConfirmation.totalAmountPennies)}
                  </span>
                </div>
              </div>
            </CardContent>
          </Card>

          {codConfirmation.allergenWarnings.length > 0 && (
            <Card variant="flat" className="border-warning/40 bg-warning-subtle p-4 mb-6">
              <CardContent className="p-0">
                <h3 className="text-body-sm font-semibold text-ink-primary mb-2">
                  Allergen warnings
                </h3>
                <ul className="space-y-1">
                  {codConfirmation.allergenWarnings.map((warning, i) => (
                    <li key={i} className="text-body-sm text-ink-secondary">
                      {warning}
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          )}

          <div className="space-y-3">
            <Button asChild variant="primary" size="lg" className="w-full rounded-pill shadow-lift">
              <Link href={`/shop/${slug}/orders/${codConfirmation.orderNumber}`}>
                Track your order
              </Link>
            </Button>
            <Button asChild variant="secondary" size="lg" className="w-full rounded-pill">
              <Link href={`/shop/${slug}`}>
                <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
                Back to shop
              </Link>
            </Button>
          </div>
        </motion.div>
      </div>
    )
  }

  // Step 2: Payment form (shown after order creation)
  if (paymentState && stripePromise) {
    return (
      <div className="bg-surface-canvas min-h-screen">
        <motion.div
          variants={pageVariants}
          initial="hidden"
          animate="visible"
          className="mx-auto max-w-2xl px-4 sm:px-6 py-6"
        >
          <button
            type="button"
            onClick={() => setPaymentState(null)}
            className="inline-flex items-center gap-1 text-body-sm text-ink-tertiary hover:text-ink-primary transition-colors duration-fast mb-4"
          >
            <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
            Back to details
          </button>
          <h1 className="font-display text-display-sm font-medium tracking-tight text-ink-primary">
            Payment
          </h1>
          <p className="mt-1 text-body-sm text-ink-secondary">
            <span className="font-mono tabular-nums">{paymentState.orderNumber}</span>
            {" · "}
            <span className="font-mono tabular-nums">
              {formatPrice(paymentState.totalAmountPennies)}
            </span>
          </p>

          {/* Order summary */}
          <Card variant="lifted" className="mt-4 p-4 mb-4">
            <CardContent className="p-0">
              <h2 className="text-body-sm font-semibold text-ink-primary mb-3">Order summary</h2>
              <div className="space-y-2">
                {items.map((item) => (
                  <div
                    key={item.productId}
                    className="flex items-center justify-between text-body-sm"
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="flex-shrink-0 h-5 w-5 rounded-sm bg-surface-subtle flex items-center justify-center text-[10px] font-semibold text-ink-secondary font-mono tabular-nums">
                        {item.quantity}
                      </span>
                      <span className="text-ink-secondary truncate">{item.title}</span>
                    </div>
                    <span className="font-mono tabular-nums text-ink-primary font-medium flex-shrink-0 ml-2">
                      {formatPrice(item.pricePennies * item.quantity)}
                    </span>
                  </div>
                ))}
              </div>
              <div className="mt-3 border-t border-border-tone-subtle pt-3 space-y-1.5">
                <div className="flex items-center justify-between text-body-sm">
                  <span className="text-ink-secondary">Subtotal</span>
                  <span className="font-mono tabular-nums text-ink-primary">
                    {formatPrice(paymentState.subtotalPennies)}
                  </span>
                </div>
                {paymentState.deliveryFeePennies > 0 && (
                  <div className="flex items-center justify-between text-body-sm">
                    <span className="text-ink-secondary">Delivery</span>
                    <span className="font-mono tabular-nums text-ink-primary">
                      {formatPrice(paymentState.deliveryFeePennies)}
                    </span>
                  </div>
                )}
                {paymentState.deliveryFeePennies === 0 && (
                  <div className="flex items-center justify-between text-body-sm">
                    <span className="text-ink-secondary">Delivery</span>
                    <span className="text-success font-medium">Free</span>
                  </div>
                )}
                {paymentState.vatAmountPennies > 0 && (
                  <div className="flex items-center justify-between text-body-sm">
                    <span className="text-ink-secondary">
                      VAT (
                      {paymentState.vatRate === "STANDARD"
                        ? "20%"
                        : paymentState.vatRate === "REDUCED"
                        ? "5%"
                        : "0%"}
                      )
                    </span>
                    <span className="font-mono tabular-nums text-ink-primary">
                      {formatPrice(paymentState.vatAmountPennies)}
                    </span>
                  </div>
                )}
                <div className="flex items-center justify-between pt-1.5">
                  <span className="font-display text-body-lg font-semibold text-ink-primary">
                    Total
                  </span>
                  <span className="font-mono tabular-nums text-body-lg font-semibold text-ink-primary">
                    {formatPrice(paymentState.totalAmountPennies)}
                  </span>
                </div>
              </div>
            </CardContent>
          </Card>

          {paymentState.allergenWarnings.length > 0 && (
            <Card variant="flat" className="border-warning/40 bg-warning-subtle p-4 mb-4">
              <CardContent className="p-0">
                <h3 className="text-body-sm font-semibold text-ink-primary mb-2">
                  Allergen warnings
                </h3>
                <ul className="space-y-1">
                  {paymentState.allergenWarnings.map((warning, i) => (
                    <li key={i} className="text-body-sm text-ink-secondary">
                      {warning}
                    </li>
                  ))}
                </ul>
                <p className="text-caption text-ink-tertiary mt-2">
                  Your order has been created. You may proceed if you accept the allergen risk, or
                  go back to modify your order.
                </p>
              </CardContent>
            </Card>
          )}

          <Elements
            stripe={stripePromise}
            options={{
              clientSecret: paymentState.clientSecret,
              appearance: {
                theme: "flat",
                variables: {
                  // Warm Editorial Fig (brand-primary light) — hsl(1 35% 42%)
                  colorPrimary: "hsl(1, 35%, 42%)",
                  colorText: "hsl(30, 10%, 16%)",
                  colorBackground: "hsl(32, 30%, 99%)",
                  colorDanger: "hsl(358, 55%, 45%)",
                  borderRadius: "10px",
                  fontFamily: "Inter, system-ui, -apple-system, sans-serif",
                },
              },
            }}
          >
            <PaymentForm
              slug={slug}
              orderNumber={paymentState.orderNumber}
              customerEmail={customerEmail}
              totalPennies={paymentState.totalAmountPennies}
            />
          </Elements>
        </motion.div>
      </div>
    )
  }

  // Step 1: Customer details form
  return (
    <div className="bg-surface-canvas min-h-screen">
      <motion.div
        variants={pageVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto max-w-2xl px-4 sm:px-6 py-6"
      >
        {/* Header */}
        <Link
          href={`/shop/${slug}/cart`}
          className="inline-flex items-center gap-1 text-body-sm text-ink-tertiary hover:text-ink-primary transition-colors duration-fast mb-4"
        >
          <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
          Back to basket
        </Link>
        <h1 className="font-display text-display-sm font-medium tracking-tight text-ink-primary">
          Checkout
        </h1>
        <p className="mt-1 text-body-sm text-ink-secondary">
          {itemCount} item{itemCount !== 1 ? "s" : ""}
          {" · "}
          <span className="font-mono tabular-nums">{formatPrice(totalPennies)}</span>
        </p>

        <form onSubmit={handleCreateOrder} className="mt-6 space-y-6">
          {/* Customer details */}
          <Card variant="lifted" className="p-4">
            <CardContent className="p-0 space-y-4">
              <h2 className="text-body-sm font-semibold text-ink-primary">Your details</h2>

              <div className="space-y-1.5">
                <label htmlFor="name" className="block text-caption font-medium text-ink-secondary">
                  Full name *
                </label>
                <Input
                  id="name"
                  type="text"
                  tone="brand"
                  autoComplete="name"
                  required
                  value={customerName}
                  onChange={(e) => setCustomerName(e.target.value)}
                  placeholder="e.g., Ade Johnson"
                />
              </div>

              <div className="space-y-1.5">
                <label htmlFor="email" className="block text-caption font-medium text-ink-secondary">
                  Email address *
                </label>
                <Input
                  id="email"
                  type="email"
                  tone="brand"
                  autoComplete="email"
                  required
                  value={customerEmail}
                  onChange={(e) => setCustomerEmail(e.target.value)}
                  placeholder="e.g., ade@example.com"
                />
              </div>

              <div className="space-y-1.5">
                <label htmlFor="phone" className="block text-caption font-medium text-ink-secondary">
                  Phone number *
                </label>
                <Input
                  id="phone"
                  type="tel"
                  tone="brand"
                  autoComplete="tel"
                  required
                  value={customerPhone}
                  onChange={(e) => setCustomerPhone(e.target.value)}
                  placeholder="e.g., 07700 900000"
                />
              </div>

              <div className="space-y-1.5">
                <label htmlFor="notes" className="block text-caption font-medium text-ink-secondary">
                  Order notes (optional)
                </label>
                <textarea
                  id="notes"
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Any special requests or dietary requirements..."
                  rows={2}
                  className="flex w-full rounded-md border border-brand-primary-subtle bg-surface-card text-ink-primary px-3 py-2.5 text-sm placeholder:text-ink-tertiary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-primary focus-visible:ring-offset-2 ring-offset-surface-canvas transition-colors duration-fast motion-reduce:transition-none resize-none"
                />
              </div>
            </CardContent>
          </Card>

          {/* Order summary */}
          <Card variant="lifted" className="p-4">
            <CardContent className="p-0">
              <h2 className="text-body-sm font-semibold text-ink-primary mb-3">Order summary</h2>
              <div className="space-y-2">
                {items.map((item) => (
                  <div
                    key={item.productId}
                    className="flex items-center justify-between text-body-sm"
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="flex-shrink-0 h-5 w-5 rounded-sm bg-surface-subtle flex items-center justify-center text-[10px] font-semibold text-ink-secondary font-mono tabular-nums">
                        {item.quantity}
                      </span>
                      <span className="text-ink-secondary truncate">{item.title}</span>
                    </div>
                    <span className="font-mono tabular-nums text-ink-primary font-medium flex-shrink-0 ml-2">
                      {formatPrice(item.pricePennies * item.quantity)}
                    </span>
                  </div>
                ))}
              </div>
              <div className="mt-4 border-t border-border-tone-subtle pt-3 space-y-1.5">
                <div className="flex items-center justify-between text-body-sm">
                  <span className="text-ink-secondary">Subtotal</span>
                  <span className="font-mono tabular-nums text-ink-primary">
                    {formatPrice(totalPennies)}
                  </span>
                </div>
                <div className="flex items-center justify-between text-body-sm">
                  <span className="text-ink-secondary">VAT (20%)</span>
                  <span className="font-mono tabular-nums text-ink-primary">
                    {formatPrice(Math.round(totalPennies * 0.2))}
                  </span>
                </div>
                <div className="flex items-center justify-between pt-1.5">
                  <span className="font-display text-body-lg font-semibold text-ink-primary">
                    Estimated total
                  </span>
                  <span className="font-mono tabular-nums text-body-lg font-semibold text-ink-primary">
                    {formatPrice(totalPennies + Math.round(totalPennies * 0.2))}
                  </span>
                </div>
                <p className="text-[10px] text-ink-tertiary">
                  Final total confirmed after order is placed. Delivery fee may apply.
                </p>
              </div>
            </CardContent>
          </Card>

          {/* Error */}
          {error && (
            <Card variant="flat" className="border-danger/40 bg-danger-subtle p-3">
              <CardContent className="p-0 text-body-sm text-danger">{error}</CardContent>
            </Card>
          )}

          {/* Submit */}
          <Button
            type="submit"
            variant="primary"
            size="lg"
            className="w-full rounded-pill shadow-lift"
            isLoading={submitting}
            disabled={submitting}
          >
            {submitting ? (
              "Creating order…"
            ) : (
              <>
                <CreditCard className="h-4 w-4" strokeWidth={1.5} />
                <span>Place order</span>
                <span className="font-mono tabular-nums">
                  {formatPrice(totalPennies + Math.round(totalPennies * 0.2))}
                </span>
              </>
            )}
          </Button>
        </form>
      </motion.div>
    </div>
  )
}

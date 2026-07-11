"use client"

import { use, useState, useCallback, useEffect, useRef } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { ArrowLeft, ShoppingBag, Loader2, CreditCard, Lock, CheckCircle, Bike, Store } from "lucide-react"
import { loadStripe } from "@stripe/stripe-js"
import { Elements, PaymentElement, useStripe, useElements } from "@stripe/react-stripe-js"
import { useCart } from "@/components/storefront/cart-provider"
import { getCustomerSession } from "@/lib/customer-auth"
import { saveLocalOrder } from "@/lib/order-history"
import publicApiClient from "@/lib/public-api-client"
import { PublicShop } from "@/types/storefront"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/** How an order is fulfilled — mirrors the server FulfilmentType enum strings. */
export type FulfilmentType = "DELIVERY" | "COLLECTION"

/**
 * UK postcode format (UI-SPEC Surface E). Kept non-global so `.test()` carries
 * no `lastIndex` state between calls.
 */
export const UK_POSTCODE_REGEX = /^[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}$/

/** Validate a UK postcode, trimming + upper-casing first (blur normalises too). */
export function isValidUkPostcode(value: string): boolean {
  return UK_POSTCODE_REGEX.test(value.trim().toUpperCase())
}

/**
 * Client-side delivery-fee PREVIEW. Mirrors the server waiver EXACTLY
 * (PublicStorefrontService.calculateDeliveryFee): COLLECTION is always £0;
 * DELIVERY uses the shop's fee, waived to £0 once the subtotal clears the
 * free-delivery threshold. This is display-only — the server recomputes the
 * authoritative total on order creation, so tampering here cannot underpay.
 */
export function previewDeliveryFeePennies(
  subtotalPennies: number,
  fulfilmentType: FulfilmentType,
  deliveryFeePennies: number | null | undefined,
  freeDeliveryThresholdPennies: number | null | undefined
): number {
  if (fulfilmentType === "COLLECTION") return 0
  const base = deliveryFeePennies ?? 0
  if (freeDeliveryThresholdPennies != null && subtotalPennies >= freeDeliveryThresholdPennies) {
    return 0
  }
  return base
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

  // Fulfilment + conditional UK delivery address (UIX-04 / Surface E).
  // Default = Delivery; Collection is one tap away and zeroes the delivery fee.
  const [fulfilmentType, setFulfilmentType] = useState<FulfilmentType>("DELIVERY")
  const [address1, setAddress1] = useState("")
  const [address2, setAddress2] = useState("")
  const [city, setCity] = useState("")
  const [postcode, setPostcode] = useState("")
  const [fieldErrors, setFieldErrors] = useState<{
    address1?: string
    city?: string
    postcode?: string
  }>({})

  // Fetch the shop so the fee breakdown can be shown BEFORE payment. Provides
  // deliveryFeePennies + freeDeliveryThresholdPennies for the client preview;
  // failure degrades gracefully to a £0 preview (server stays authoritative).
  const [shop, setShop] = useState<PublicShop | null>(null)
  useEffect(() => {
    let cancelled = false
    publicApiClient
      .get<PublicShop>(`/public/shops/${slug}`)
      .then((res) => {
        if (!cancelled) setShop(res.data)
      })
      .catch(() => {
        /* Preview falls back to £0 delivery; server recomputes the real fee. */
      })
    return () => {
      cancelled = true
    }
  }, [slug])

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
      <div className="mx-auto flex min-h-[60vh] max-w-2xl flex-col items-center justify-center px-4 text-center">
        <ShoppingBag className="h-16 w-16 text-slate-200" />
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

    // Conditional UK-address validation (Surface E). Collection needs no address.
    if (fulfilmentType === "DELIVERY") {
      const errs: { address1?: string; city?: string; postcode?: string } = {}
      if (!address1.trim()) errs.address1 = "Add a delivery address, or switch to Collection."
      if (!city.trim()) errs.city = "Add a delivery address, or switch to Collection."
      if (!isValidUkPostcode(postcode)) {
        errs.postcode = "Enter a valid UK postcode (e.g. SW1A 1AA)"
      }
      if (Object.keys(errs).length > 0) {
        setFieldErrors(errs)
        return
      }
    }
    setFieldErrors({})
    setSubmitting(true)

    try {
      // Server contract (GuestOrderRequest, plan 19-01) is FLAT: fulfilmentType +
      // addressLine1/2 + addressCity + addressPostcode (NOT a nested address obj).
      const payload = {
        customerName: customerName.trim(),
        customerEmail: customerEmail.trim(),
        customerPhone: customerPhone.trim(),
        notes: notes.trim() || undefined,
        idempotencyKey: idempotencyKeyRef.current,
        fulfilmentType,
        ...(fulfilmentType === "DELIVERY"
          ? {
              addressLine1: address1.trim(),
              addressLine2: address2.trim() || undefined,
              addressCity: city.trim(),
              addressPostcode: postcode.trim().toUpperCase(),
            }
          : {}),
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
      <div className="mx-auto max-w-2xl px-4 sm:px-6 py-6">
        <div className="text-center mb-6">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100">
            <CheckCircle className="h-8 w-8 text-emerald-600" />
          </div>
          <h1 className="mt-4 text-xl font-bold text-slate-900">Order confirmed!</h1>
          <p className="text-sm text-slate-500 mt-1">
            Order {codConfirmation.orderNumber} &middot; Pay on collection
          </p>
        </div>

        <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm mb-6">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Order total</h2>
          <div className="space-y-2">
            <div className="flex items-center justify-between text-sm">
              <span className="text-slate-600">Subtotal</span>
              <span className="text-slate-900">{formatPrice(codConfirmation.subtotalPennies)}</span>
            </div>
            {codConfirmation.deliveryFeePennies > 0 ? (
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">Delivery</span>
                <span className="text-slate-900">{formatPrice(codConfirmation.deliveryFeePennies)}</span>
              </div>
            ) : (
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">Delivery</span>
                <span className="text-emerald-600 font-medium">Free</span>
              </div>
            )}
            {codConfirmation.vatAmountPennies > 0 && (
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">VAT ({codConfirmation.vatRate === "STANDARD" ? "20%" : codConfirmation.vatRate === "REDUCED" ? "5%" : "0%"})</span>
                <span className="text-slate-900">{formatPrice(codConfirmation.vatAmountPennies)}</span>
              </div>
            )}
            <div className="flex items-center justify-between pt-2 border-t border-slate-100">
              <span className="text-base font-bold text-slate-900">Total</span>
              <span className="text-base font-bold text-slate-900">{formatPrice(codConfirmation.totalAmountPennies)}</span>
            </div>
          </div>
        </div>

        {codConfirmation.allergenWarnings.length > 0 && (
          <div className="rounded-xl bg-amber-50 border border-amber-200 p-4 mb-6">
            <h3 className="text-sm font-semibold text-amber-800 mb-2">Allergen warnings</h3>
            <ul className="space-y-1">
              {codConfirmation.allergenWarnings.map((warning, i) => (
                <li key={i} className="text-sm text-amber-700">{warning}</li>
              ))}
            </ul>
          </div>
        )}

        <Link
          href={`/shop/${slug}/orders/${codConfirmation.orderNumber}`}
          className="flex w-full items-center justify-center gap-2 rounded-2xl bg-orange-500 py-3.5 text-sm font-bold text-white hover:bg-orange-600 active:scale-[0.98] transition-all shadow-lg"
        >
          Track your order
        </Link>
        <Link
          href={`/shop/${slug}`}
          className="flex w-full items-center justify-center gap-1 mt-3 rounded-2xl border border-slate-200 py-2.5 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to shop
        </Link>
      </div>
    )
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
          Order {paymentState.orderNumber} &middot; {formatPrice(paymentState.totalAmountPennies)}
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
          <div className="mt-3 border-t border-slate-100 pt-3 space-y-1.5">
            <div className="flex items-center justify-between text-sm">
              <span className="text-slate-600">Subtotal</span>
              <span className="text-slate-900">{formatPrice(paymentState.subtotalPennies)}</span>
            </div>
            {paymentState.deliveryFeePennies > 0 && (
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">Delivery</span>
                <span className="text-slate-900">{formatPrice(paymentState.deliveryFeePennies)}</span>
              </div>
            )}
            {paymentState.deliveryFeePennies === 0 && (
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">Delivery</span>
                <span className="text-emerald-600 font-medium">Free</span>
              </div>
            )}
            {paymentState.vatAmountPennies > 0 && (
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">VAT ({paymentState.vatRate === "STANDARD" ? "20%" : paymentState.vatRate === "REDUCED" ? "5%" : "0%"})</span>
                <span className="text-slate-900">{formatPrice(paymentState.vatAmountPennies)}</span>
              </div>
            )}
            <div className="flex items-center justify-between pt-1.5">
              <span className="text-base font-bold text-slate-900">Total</span>
              <span className="text-base font-bold text-slate-900">{formatPrice(paymentState.totalAmountPennies)}</span>
            </div>
          </div>
        </div>

        {paymentState.allergenWarnings.length > 0 && (
          <div className="rounded-xl bg-amber-50 border border-amber-200 p-4 mb-4">
            <h3 className="text-sm font-semibold text-amber-800 mb-2">Allergen warnings</h3>
            <ul className="space-y-1">
              {paymentState.allergenWarnings.map((warning, i) => (
                <li key={i} className="text-sm text-amber-700">{warning}</li>
              ))}
            </ul>
            <p className="text-xs text-amber-600 mt-2">
              Your order has been created. You may proceed if you accept the allergen risk, or go back to modify your order.
            </p>
          </div>
        )}

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
            totalPennies={paymentState.totalAmountPennies}
          />
        </Elements>
      </div>
    )
  }

  // Step 1: Customer details form.
  // Definite fee preview shown BEFORE payment — mirrors the server waiver so the
  // customer sees exactly what they'll pay (Deliveroo/Just Eat comparator).
  const subtotalPennies = totalPennies
  const deliveryFeePennies = previewDeliveryFeePennies(
    subtotalPennies,
    fulfilmentType,
    shop?.deliveryFeePennies,
    shop?.freeDeliveryThresholdPennies
  )
  const deliveryIsFree = deliveryFeePennies === 0
  const previewTotalPennies = subtotalPennies + deliveryFeePennies
  // VAT-inclusive fraction already contained within the gross (UK retail idiom,
  // unchanged): gross * 20 / 120, rounded down.
  const vatPreviewPennies = Math.floor((previewTotalPennies * 20) / 120)
  const inputBase =
    "w-full rounded-lg border px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-orange-100 focus:border-orange-300"

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
        {/* Fulfilment toggle — bespoke 2-button segmented control (no new dep) */}
        <div className="grid grid-cols-2 gap-2 rounded-xl bg-white border border-slate-100 p-1.5 shadow-sm">
          <button
            type="button"
            onClick={() => setFulfilmentType("DELIVERY")}
            aria-pressed={fulfilmentType === "DELIVERY"}
            className={`flex items-center justify-center gap-2 rounded-lg py-2.5 text-sm font-semibold transition-colors ${
              fulfilmentType === "DELIVERY"
                ? "bg-orange-500 text-white"
                : "bg-slate-100 text-slate-600 hover:bg-slate-200"
            }`}
          >
            <Bike className="h-4 w-4" />
            Delivery
          </button>
          <button
            type="button"
            onClick={() => setFulfilmentType("COLLECTION")}
            aria-pressed={fulfilmentType === "COLLECTION"}
            className={`flex items-center justify-center gap-2 rounded-lg py-2.5 text-sm font-semibold transition-colors ${
              fulfilmentType === "COLLECTION"
                ? "bg-orange-500 text-white"
                : "bg-slate-100 text-slate-600 hover:bg-slate-200"
            }`}
          >
            <Store className="h-4 w-4" />
            Collection
          </button>
        </div>

        {/* Conditional UK delivery address — only for DELIVERY */}
        {fulfilmentType === "DELIVERY" && (
          <div className="rounded-xl bg-white border border-slate-100 p-4 shadow-sm space-y-4">
            <h2 className="text-sm font-semibold text-slate-900">Delivery address</h2>

            <div className="space-y-1.5">
              <label htmlFor="address1" className="block text-xs font-medium text-slate-600">Address line 1 *</label>
              <input
                id="address1"
                type="text"
                value={address1}
                onChange={(e) => setAddress1(e.target.value)}
                placeholder="e.g., 12 Coldharbour Lane"
                className={`${inputBase} ${fieldErrors.address1 ? "border-red-300" : "border-slate-200"}`}
              />
              {fieldErrors.address1 && (
                <p className="text-xs text-red-600">{fieldErrors.address1}</p>
              )}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="address2" className="block text-xs font-medium text-slate-600">Address line 2 (optional)</label>
              <input
                id="address2"
                type="text"
                value={address2}
                onChange={(e) => setAddress2(e.target.value)}
                placeholder="Flat, building, etc."
                className={`${inputBase} border-slate-200`}
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="city" className="block text-xs font-medium text-slate-600">Town / city *</label>
              <input
                id="city"
                type="text"
                value={city}
                onChange={(e) => setCity(e.target.value)}
                placeholder="e.g., London"
                className={`${inputBase} ${fieldErrors.city ? "border-red-300" : "border-slate-200"}`}
              />
              {fieldErrors.city && (
                <p className="text-xs text-red-600">{fieldErrors.city}</p>
              )}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="postcode" className="block text-xs font-medium text-slate-600">Postcode *</label>
              <input
                id="postcode"
                type="text"
                value={postcode}
                onChange={(e) => setPostcode(e.target.value)}
                onBlur={() => setPostcode((p) => p.trim().toUpperCase())}
                placeholder="e.g., SW9 8LF"
                className={`${inputBase} ${fieldErrors.postcode ? "border-red-300" : "border-slate-200"}`}
              />
              {fieldErrors.postcode && (
                <p className="text-xs text-red-600">{fieldErrors.postcode}</p>
              )}
            </div>
          </div>
        )}

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
          <div className="mt-4 border-t border-slate-100 pt-3 space-y-1.5">
            <div className="flex items-center justify-between text-sm">
              <span className="text-slate-600">Subtotal</span>
              <span className="text-slate-900">{formatPrice(subtotalPennies)}</span>
            </div>
            {/* Delivery — mirrors the server waiver exactly (COLLECTION or above
                the free-delivery threshold => Free). */}
            <div className="flex items-center justify-between text-sm">
              <span className="text-slate-600">Delivery</span>
              {deliveryIsFree ? (
                <span className="text-emerald-600 font-semibold">Free</span>
              ) : (
                <span className="text-slate-900">{formatPrice(deliveryFeePennies)}</span>
              )}
            </div>
            <div className="flex items-center justify-between text-sm">
              {/* Prices are VAT-inclusive (UK retail): VAT is the fraction already
                  contained within the gross total, not an add-on. Extracted at the
                  standard rate (gross*20/120, round down) to match the post-order
                  confirmation screen's vatAmountPennies. */}
              <span className="text-slate-600">VAT (incl. 20%)</span>
              <span className="text-slate-900">{formatPrice(vatPreviewPennies)}</span>
            </div>
            <div className="flex items-center justify-between pt-1.5">
              <span className="text-lg font-bold text-slate-900">Total</span>
              <span className="text-lg font-bold text-slate-900">{formatPrice(previewTotalPennies)}</span>
            </div>
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
              Place order &middot; {formatPrice(previewTotalPennies)}
            </>
          )}
        </button>
      </form>
    </div>
  )
}

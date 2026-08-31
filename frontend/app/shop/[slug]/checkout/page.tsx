"use client"

import { use, useState, useCallback, useEffect, useRef, useMemo } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { ArrowLeft, ShoppingBag, Loader2, CreditCard, Lock, CheckCircle, Bike, Store, Banknote } from "lucide-react"
import { loadStripe } from "@stripe/stripe-js"
import { Elements, PaymentElement, useStripe, useElements } from "@stripe/react-stripe-js"
import { useCart } from "@/components/storefront/cart-provider"
// The refusal copy lives in the panel, which owns its own `role="alert"` region — the page sets
// only the errored flag, so there is one source for the legally-operative string.
import { OrderAllergenPanel } from "@/components/storefront/order-allergen-panel"
import { getCustomerSession } from "@/lib/customer-auth"
import { saveLocalOrder } from "@/lib/order-history"
import { describeOrderError } from "@/lib/order-error"
import publicApiClient from "@/lib/public-api-client"
import { getAllergenNames } from "@/types/api"
import { PublicShop, PublicProduct } from "@/types/storefront"

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

/**
 * Index the storefront's product catalogue by id, defensively.
 *
 * Returns `null` — meaning NOT RECORDED, never "nothing declared" — when the payload is missing,
 * malformed, or yields no usable product. That distinction is the whole point: an allergen panel
 * that says "the kitchen declared none of the 14" because a fetch failed is stating something the
 * kitchen never said, and that is the direction that injures someone.
 */
export function indexProductsById(data: unknown): Map<string, PublicProduct> | null {
  if (!data || typeof data !== "object") return null
  const index = new Map<string, PublicProduct>()
  for (const group of Object.values(data as Record<string, unknown>)) {
    if (!Array.isArray(group)) continue
    for (const candidate of group) {
      if (
        candidate &&
        typeof candidate === "object" &&
        typeof (candidate as PublicProduct).id === "string" &&
        typeof (candidate as PublicProduct).allergenMask === "number"
      ) {
        index.set((candidate as PublicProduct).id, candidate as PublicProduct)
      }
    }
  }
  return index.size > 0 ? index : null
}

/**
 * The basket's DECLARED allergen union, in words.
 *
 * `null` (NOT RECORDED) whenever ANY line cannot be resolved to a product with a declared mask —
 * a partial union would silently UNDER-state the set, and under-stating is the dangerous
 * direction. Only a fully resolved basket yields a positive statement.
 *
 * The mask -> names decode goes through `getAllergenNames` (types/api.ts), whose table is held
 * identical to the Java `AllergenCatalog` by `__tests__/allergen-table-parity.test.ts`. That gate
 * is why decoding here cannot drift from what the kitchen display will show for the same integer.
 */
export function basketAllergenNames(
  items: { productId: string }[],
  productIndex: Map<string, PublicProduct> | null
): string[] | null {
  if (!productIndex || items.length === 0) return null
  let mask = 0
  for (const item of items) {
    const product = productIndex.get(item.productId)
    if (!product || typeof product.allergenMask !== "number") return null
    mask |= product.allergenMask
  }
  return getAllergenNames(mask)
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
      <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
        <div className="flex items-center gap-2 mb-3">
          <CreditCard className="h-4 w-4 text-slate-600" />
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

  // A11Y-07: a refused submit must move focus to the control that refused, not merely paint it
  // red. These refs are the only focus-management precedent in the app besides the dish modal's
  // focus-return (product-detail-modal.tsx:117).
  const address1Ref = useRef<HTMLInputElement>(null)
  const cityRef = useRef<HTMLInputElement>(null)
  const postcodeRef = useRef<HTMLInputElement>(null)
  const ackCheckboxRef = useRef<HTMLButtonElement>(null)

  // D-02: the pre-submit allergen acknowledgement. Held per ORDER INTENT — see the basket-change
  // reset below. NOT pre-checked, and deliberately NOT wired into the submit button's `disabled`.
  const [acknowledged, setAcknowledged] = useState(false)
  const [ackError, setAckError] = useState(false)

  // The storefront catalogue, so the panel can state the basket's DECLARED set before the order
  // exists. 31-10's snapshot lives on the ORDER, which by construction is not created yet at this
  // point — its SUMMARY records that this panel's data comes from the basket and that the DTO
  // shapes are the shape to match.
  const [productIndex, setProductIndex] = useState<Map<string, PublicProduct> | null>(null)
  useEffect(() => {
    let cancelled = false
    publicApiClient
      .get(`/public/shops/${slug}/products`)
      .then((res) => {
        if (!cancelled) setProductIndex(indexProductsById(res.data))
      })
      .catch(() => {
        // Leave the index null: the panel then reads NOT RECORDED rather than claiming the
        // kitchen declared nothing.
      })
    return () => {
      cancelled = true
    }
  }, [slug])

  const declaredAllergenNames = useMemo(
    () => basketAllergenNames(items, productIndex),
    [items, productIndex]
  )

  /**
   * A stable signature of the order intent. Changing the basket produces a different one, which
   * resets the acknowledgement — acknowledging one basket must not silently carry to another.
   */
  const basketSignature = useMemo(
    () => items.map((i) => `${i.productId}:${i.quantity}`).join("|"),
    [items]
  )
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    setAcknowledged(false)
    setAckError(false)
  }, [basketSignature])

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

  // COD confirmation — shows full breakdown before redirect. Carries the
  // submitted fulfilment type so the payment instruction reads "Pay on
  // delivery" for DELIVERY orders (WR-08).
  const [codConfirmation, setCodConfirmation] = useState<{
    orderNumber: string
    fulfilmentType: FulfilmentType
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
        <p className="mt-1 text-sm text-slate-600">Add items from the menu first.</p>
        <Link
          href={`/shop/${slug}`}
          className="mt-6 inline-flex items-center gap-2 rounded-full bg-amber-500 px-5 py-2.5 text-sm font-semibold text-amber-ink hover:bg-amber-400 transition-colors"
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
        // A11Y-07: move focus to the FIRST invalid field, in visual order. The guarded shape
        // (`isConnected` before `focus()`) follows product-detail-modal.tsx:117.
        const firstInvalid = errs.address1
          ? address1Ref.current
          : errs.city
            ? cityRef.current
            : postcodeRef.current
        if (firstInvalid && firstInvalid.isConnected) firstInvalid.focus()
        return
      }
    }
    setFieldErrors({})

    // D-02 — THE ALLERGEN GATE. Refused BEFORE any network call: an error rendered alongside a
    // created order is the dangerous outcome, because the kitchen is already cooking while the
    // customer is being told off. The button deliberately stays ENABLED (see the note at the
    // submit button) so a touch user gets feedback rather than a dead press.
    if (!acknowledged) {
      setAckError(true)
      if (ackCheckboxRef.current && ackCheckboxRef.current.isConnected) {
        ackCheckboxRef.current.focus()
      }
      return
    }
    setAckError(false)
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
          fulfilmentType,
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
      // #409: this used to read ONLY `response.data.detail` (RFC 7807). The rate
      // limiter answers 429 with `Retry-After` and an `error`/`message` body, so
      // the one actionable sentence the server sent was discarded and the
      // shopper was told to "try again" — immediately, which re-trips the limit.
      setError(describeOrderError(err))
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
          <p className="text-sm text-slate-600 mt-1">
            Order {codConfirmation.orderNumber} &middot; Pay on{" "}
            {codConfirmation.fulfilmentType === "COLLECTION" ? "collection" : "delivery"}
          </p>
        </div>

        <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm mb-6">
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
            <div className="flex items-center justify-between pt-2 border-t border-cream-100">
              <span className="text-base font-bold text-slate-900">Total</span>
              <span className="text-base font-bold text-slate-900">{formatPrice(codConfirmation.totalAmountPennies)}</span>
            </div>
          </div>
        </div>

        {codConfirmation.allergenWarnings.length > 0 && (
          <div className="rounded-xl bg-amber-50 border border-amber-600 p-4 mb-6">
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
          className="flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood py-3.5 text-sm font-bold text-white hover:bg-oxblood-700 active:scale-[0.98] transition-all shadow-lg"
        >
          Track your order
        </Link>
        <Link
          href={`/shop/${slug}`}
          className="flex w-full items-center justify-center gap-1 mt-3 rounded-2xl border border-cream-100 py-2.5 text-sm font-medium text-slate-600 hover:bg-cream transition-colors"
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
          onClick={() => {
            // WR-03: the idempotency key identifies ONE order intent, not one
            // page mount. Going back to edit details starts a new intent —
            // rotate the key so an edited resubmission (e.g. switching
            // DELIVERY to COLLECTION) creates a fresh order instead of being
            // silently matched to the previous one by the server.
            idempotencyKeyRef.current = crypto.randomUUID()
            // D-02: the acknowledgement is per order intent, and rotating the key above starts a
            // new one. Acknowledging the previous basket must not carry silently into this one.
            setAcknowledged(false)
            setAckError(false)
            setPaymentState(null)
          }}
          className="inline-flex items-center gap-1 text-sm text-slate-600 hover:text-slate-700 transition-colors mb-4"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to details
        </button>
        <h1 className="text-xl font-bold text-slate-900">Payment</h1>
        <p className="text-sm text-slate-600 mt-1">
          Order {paymentState.orderNumber} &middot; {formatPrice(paymentState.totalAmountPennies)}
        </p>

        {/* Order summary */}
        <div className="mt-4 rounded-xl bg-white border border-cream-100 p-4 shadow-sm mb-4">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Order summary</h2>
          <div className="space-y-2">
            {items.map((item) => (
              <div key={item.productId} className="flex items-center justify-between text-sm">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="flex-shrink-0 h-5 w-5 rounded bg-slate-100 flex items-center justify-center text-xs font-bold text-slate-600">
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
          <div className="mt-3 border-t border-cream-100 pt-3 space-y-1.5">
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
          <div className="rounded-xl bg-amber-50 border border-amber-600 p-4 mb-4">
            <h3 className="text-sm font-semibold text-amber-800 mb-2">Allergen warnings</h3>
            <ul className="space-y-1">
              {paymentState.allergenWarnings.map((warning, i) => (
                <li key={i} className="text-sm text-amber-700">{warning}</li>
              ))}
            </ul>
            <p className="text-xs text-amber-700 mt-2">
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
  // WR-01: mirror the server-side minimum-order gate (item subtotal, delivery
  // fee excluded). The server enforces it authoritatively in createGuestOrder;
  // this just stops the user submitting an order that would be rejected.
  const minimumOrderPennies = shop?.minimumOrderPennies ?? 0
  const belowMinimum = minimumOrderPennies > 0 && subtotalPennies < minimumOrderPennies
  // VAT-inclusive fraction already contained within the gross (UK retail idiom,
  // unchanged): gross * 20 / 120, rounded down.
  const vatPreviewPennies = Math.floor((previewTotalPennies * 20) / 120)
  const inputBase =
    "w-full rounded-lg border px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-amber-200 focus:border-amber-400"

  return (
    <div className="mx-auto max-w-2xl px-4 sm:px-6 py-6">
      {/* Header */}
      <Link
        href={`/shop/${slug}/cart`}
        className="inline-flex items-center gap-1 text-sm text-slate-600 hover:text-slate-700 transition-colors mb-4"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to basket
      </Link>
      <h1 className="text-xl font-bold text-slate-900">Checkout</h1>
      <p className="text-sm text-slate-600 mt-1">{itemCount} item{itemCount !== 1 ? "s" : ""} &middot; {formatPrice(totalPennies)}</p>

      <form onSubmit={handleCreateOrder} className="mt-6 space-y-6">
        {/* Fulfilment toggle — bespoke 2-button segmented control (no new dep) */}
        <div className="grid grid-cols-2 gap-2 rounded-xl bg-white border border-cream-100 p-1.5 shadow-sm">
          <button
            type="button"
            onClick={() => setFulfilmentType("DELIVERY")}
            aria-pressed={fulfilmentType === "DELIVERY"}
            className={`flex items-center justify-center gap-2 rounded-lg py-2.5 text-sm font-semibold transition-colors ${
              fulfilmentType === "DELIVERY"
                ? "bg-oxblood text-white"
                : "bg-cream text-slate-600 hover:bg-cream-100"
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
                ? "bg-oxblood text-white"
                : "bg-cream text-slate-600 hover:bg-cream-100"
            }`}
          >
            <Store className="h-4 w-4" />
            Collection
          </button>
        </div>

        {/* Conditional UK delivery address — only for DELIVERY */}
        {fulfilmentType === "DELIVERY" && (
          <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm space-y-4">
            <h2 className="text-sm font-semibold text-slate-900">Delivery address</h2>

            <div className="space-y-1.5">
              <label htmlFor="address1" className="block text-xs font-medium text-slate-600">Address line 1 *</label>
              <input
                id="address1"
                ref={address1Ref}
                type="text"
                autoComplete="address-line1"
                value={address1}
                onChange={(e) => setAddress1(e.target.value)}
                placeholder="e.g., 12 Coldharbour Lane"
                aria-invalid={fieldErrors.address1 ? "true" : undefined}
                aria-describedby={fieldErrors.address1 ? "address1-error" : undefined}
                className={`${inputBase} ${fieldErrors.address1 ? "border-red-300" : "border-cream-100"}`}
              />
              {fieldErrors.address1 && (
                <p id="address1-error" className="text-xs text-red-600">{fieldErrors.address1}</p>
              )}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="address2" className="block text-xs font-medium text-slate-600">Address line 2 (optional)</label>
              <input
                id="address2"
                type="text"
                autoComplete="address-line2"
                value={address2}
                onChange={(e) => setAddress2(e.target.value)}
                placeholder="Flat, building, etc."
                className={`${inputBase} border-cream-100`}
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="city" className="block text-xs font-medium text-slate-600">Town / city *</label>
              <input
                id="city"
                ref={cityRef}
                type="text"
                autoComplete="address-level2"
                value={city}
                onChange={(e) => setCity(e.target.value)}
                placeholder="e.g., London"
                aria-invalid={fieldErrors.city ? "true" : undefined}
                aria-describedby={fieldErrors.city ? "city-error" : undefined}
                className={`${inputBase} ${fieldErrors.city ? "border-red-300" : "border-cream-100"}`}
              />
              {fieldErrors.city && (
                <p id="city-error" className="text-xs text-red-600">{fieldErrors.city}</p>
              )}
            </div>

            <div className="space-y-1.5">
              <label htmlFor="postcode" className="block text-xs font-medium text-slate-600">Postcode *</label>
              <input
                id="postcode"
                ref={postcodeRef}
                type="text"
                autoComplete="postal-code"
                value={postcode}
                onChange={(e) => setPostcode(e.target.value)}
                onBlur={() => setPostcode((p) => p.trim().toUpperCase())}
                placeholder="e.g., SW9 8LF"
                aria-invalid={fieldErrors.postcode ? "true" : undefined}
                aria-describedby={fieldErrors.postcode ? "postcode-error" : undefined}
                className={`${inputBase} ${fieldErrors.postcode ? "border-red-300" : "border-cream-100"}`}
              />
              {fieldErrors.postcode && (
                <p id="postcode-error" className="text-xs text-red-600">{fieldErrors.postcode}</p>
              )}
            </div>
          </div>
        )}

        {/* Customer details */}
        <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm space-y-4">
          <h2 className="text-sm font-semibold text-slate-900">Your details</h2>

          <div className="space-y-1.5">
            <label htmlFor="name" className="block text-xs font-medium text-slate-600">Full name *</label>
            <input
              id="name"
              type="text"
              autoComplete="name"
              required
              value={customerName}
              onChange={(e) => setCustomerName(e.target.value)}
              placeholder="e.g., Ade Johnson"
              className="w-full rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="email" className="block text-xs font-medium text-slate-600">Email address *</label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={customerEmail}
              onChange={(e) => setCustomerEmail(e.target.value)}
              placeholder="e.g., ade@example.com"
              className="w-full rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="phone" className="block text-xs font-medium text-slate-600">Phone number *</label>
            <input
              id="phone"
              type="tel"
              autoComplete="tel"
              required
              value={customerPhone}
              onChange={(e) => setCustomerPhone(e.target.value)}
              placeholder="e.g., 07700 900000"
              className="w-full rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200"
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
              className="w-full rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200 resize-none"
            />
          </div>
        </div>

        {/* Order summary */}
        <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Order summary</h2>
          <div className="space-y-2">
            {items.map((item) => (
              <div key={item.productId} className="flex items-center justify-between text-sm">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="flex-shrink-0 h-5 w-5 rounded bg-slate-100 flex items-center justify-center text-xs font-bold text-slate-600">
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
          <div className="mt-4 border-t border-cream-100 pt-3 space-y-1.5">
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

        {/* How you'll pay — QA-council FIX-6 (M3): disclose the payment
            method BEFORE the customer commits a binding order. Driven by the
            additive PublicShopDto.acceptsCardPayments (server derives it from
            PaymentService.isConfigured()). When the backend doesn't send the
            field (old backend), render nothing — the pre-fix behaviour. */}
        {shop?.acceptsCardPayments !== undefined && (
          <div className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-900">How you&apos;ll pay</h2>
            {shop.acceptsCardPayments ? (
              <p className="mt-1.5 flex items-start gap-2 text-sm text-slate-600">
                <CreditCard className="mt-0.5 h-4 w-4 flex-shrink-0 text-slate-400" />
                <span>
                  Pay securely by card — you&apos;ll enter your card details after
                  confirming your order.
                </span>
              </p>
            ) : (
              <p className="mt-1.5 flex items-start gap-2 text-sm text-slate-600">
                <Banknote className="mt-0.5 h-4 w-4 flex-shrink-0 text-slate-400" />
                <span>
                  Pay on {fulfilmentType === "COLLECTION" ? "collection" : "delivery"} —
                  cash to the {fulfilmentType === "COLLECTION" ? "shop" : "driver"}. No
                  payment is taken online.
                </span>
              </p>
            )}
          </div>
        )}

        {/* D-02 — the pre-submit allergen block. Deliberately the LAST thing read before
            committing: after "How you'll pay", immediately above the submit run. Not in the order
            summary, not collapsed, not behind a disclosure.

            `declaredAllergenNames` is null (NOT RECORDED) whenever the basket cannot be fully
            resolved. `allergenFlags` is null rather than []: the advisory reconciliation flags are
            computed by the SERVER (OrderAllergenAggregator, 31-04) against a ~150-term synonym
            list, and re-implementing that heuristic in TypeScript would create a second, ungated
            copy of a safety rule. Passing [] here would assert "nothing flagged", which this
            surface cannot substantiate. See 31-14-SUMMARY.md. */}
        <OrderAllergenPanel
          vendorName={shop?.name ?? "this kitchen"}
          allergenNames={declaredAllergenNames}
          allergenFlags={null}
          acknowledged={acknowledged}
          onAcknowledgedChange={(next) => {
            setAcknowledged(next)
            if (next) setAckError(false)
          }}
          errored={ackError}
          errorId="allergen-ack-error"
          checkboxRef={ackCheckboxRef}
        />

        {/* Error — A11Y-07: announced, not merely painted. Was a plain <div>. */}
        {error && (
          <div
            role="alert"
            className="rounded-xl bg-red-50 border border-red-100 p-3 text-sm text-red-700"
          >
            {error}
          </div>
        )}

        {/* Below-minimum hint (WR-01) */}
        {belowMinimum && (
          <p className="text-center text-xs font-medium text-slate-600">
            Minimum order {formatPrice(minimumOrderPennies)} — add{" "}
            {formatPrice(minimumOrderPennies - subtotalPennies)} more to place this order.
          </p>
        )}

        {/* Submit.

            ⚠ THE ACKNOWLEDGEMENT IS DELIBERATELY NOT IN THIS `disabled` EXPRESSION. `belowMinimum`
            belongs here because it has a permanent explanatory hint rendered directly above. An
            acknowledgement gate has no such hint, and a disabled button on a touch device gives NO
            feedback at all when pressed — the user learns nothing. The gate instead REFUSES in the
            submit handler and announces the refusal, which is both accessible and legally stronger:
            the refusal is evidence the gate fired. */}
        <button
          type="submit"
          disabled={submitting || belowMinimum}
          className="flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood py-3.5 text-sm font-bold text-white hover:bg-oxblood-700 active:scale-[0.98] transition-all shadow-lg disabled:opacity-60 disabled:cursor-not-allowed"
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

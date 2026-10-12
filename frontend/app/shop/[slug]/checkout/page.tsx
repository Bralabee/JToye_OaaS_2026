"use client"

import { use, useState, useCallback, useEffect, useRef, useMemo } from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { ArrowLeft, ShoppingBag, Loader2, CreditCard, Lock, Bike, Store, Banknote } from "lucide-react"
// #793: the `/pure` entry, NEVER the package default. The default entry injects js.stripe.com as
// a module side effect, so merely importing it put Stripe's script — and its `__stripe_mid` /
// `__stripe_sid` cookies and m.stripe.network calls — on every cash checkout, which takes no
// payment online. `/pure` injects nothing until `loadStripe()` is called (see getStripe below).
import { loadStripe } from "@stripe/stripe-js/pure"
import { Elements, PaymentElement, useStripe, useElements } from "@stripe/react-stripe-js"
import { applyBasketChanges, useCart, type CartItem } from "@/components/storefront/cart-provider"
// 37-34 (UI-SPEC § C1, D-16): the panel that renders the server's basket diff, and the one error-slot
// sentence for a body this page cannot act on (the 422, or a malformed 409).
import { BASKET_CONFIRMATION_REQUIRED_COPY, BasketChangedPanel } from "@/components/storefront/basket-changed-panel"
// The refusal copy lives in the panel, which owns its own `role="alert"` region — the page sets
// only the errored flag, so there is one source for the legally-operative string.
import {
  ALLERGY_NOTE_LABEL_COPY,
  ALLERGY_NOTE_MAX_LENGTH,
  OrderAllergenPanel,
  allergyNoteCountCopy,
  allergyNoteHelpCopy,
  type AllergenAckErrorKind,
} from "@/components/storefront/order-allergen-panel"
import { RecordedAllergenSet } from "@/components/storefront/recorded-allergen-set"
import { SellerBlock } from "@/components/storefront/seller-block"
import { ShopAvailabilityNotice } from "@/components/storefront/shop-availability-notice"
import { getCustomerSession } from "@/lib/customer-auth"
import { saveLocalOrder } from "@/lib/order-history"
import { markJustPlaced } from "@/lib/just-placed"
import { NoVatRegistrationNote } from "@/components/storefront/no-vat-registration-note"
import { describeOrderError } from "@/lib/order-error"
import { readShopAvailability, readShopClosedProblem, shopClosedRaceCopy } from "@/lib/shop-availability"
import { minimumShortfallPennies } from "@/lib/minimum-order"
// COR-2 / PR #726 low (b): the delivery-fee preview lives in the lib and ONLY there. This file
// used to carry a byte-identical second body; the cart page already imported the lib.
import { previewDeliveryFeePennies } from "@/lib/delivery-fee"
import { buildGuestOrderIntent, guestOrderIntentSignature } from "@/lib/checkout-idempotency"
import { asVatRate, predominantRate, vatFromGross, vatRateLabel } from "@/lib/vat"
import publicApiClient from "@/lib/public-api-client"
import { basketAllergenAttribution, basketAllergenFlags, indexProductsById } from "@/lib/basket-allergens"
// FulfilmentType is the shared two-member union (mirrors the backend enum), not a local re-declaration.
import { getAllergenNames, type FulfilmentType, type OrderAllergenFlag } from "@/types/api"
import {
  ALLERGEN_ACK_STALE_PROBLEM_TYPE,
  BASKET_CHANGED_PROBLEM_TYPE,
  BASKET_CONFIRMATION_REQUIRED_PROBLEM_TYPE,
  type AllergenAcknowledgementStaleProblem,
  type BasketChange,
  type BasketChangeKind,
  type GuestOrderConfirmation,
  type PublicProduct,
  type PublicShop,
} from "@/types/storefront"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/**
 * UK postcode format (UI-SPEC Surface E). Kept non-global so `.test()` carries
 * no `lastIndex` state between calls.
 */
export const UK_POSTCODE_REGEX = /^[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}$/

/** Validate a UK postcode, trimming + upper-casing first (blur normalises too). */
export function isValidUkPostcode(value: string): boolean {
  return UK_POSTCODE_REGEX.test(value.trim().toUpperCase())
}

// `indexProductsById` (the catalogue -> id index, NOT RECORDED on a bad payload) lives in
// lib/basket-allergens.ts since 31.1-23, so the basket page and the cart drawer share its gate.

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
  const mask = basketAllergenMask(items, productIndex)
  return mask === null ? null : getAllergenNames(mask)
}

/**
 * The basket's DECLARED allergen union as the 14-bit mask (AllergenCatalog bits 0..13) — the value
 * the checkout submits as `acknowledgedAllergenMask` (#784/#785, D-05).
 *
 * Same NOT RECORDED rule as `basketAllergenNames`, which is derived from this: `null` whenever
 * ANY line cannot be resolved. The server compares the acknowledgement by EQUALITY with the union it
 * reads at submit (31.1-03), so a partial union would be refused anyway — but the reason it must be
 * `null` and never `0` is the customer, not the server: `0` says "the kitchen declared none".
 *
 * Declared mask ONLY. `mayContainAllergens` (31.1-14, cross-contact) and the advisory reconciliation
 * flags are separate statements and are never OR-ed into the acknowledged set.
 */
export function basketAllergenMask(
  items: { productId: string }[],
  productIndex: Map<string, PublicProduct> | null
): number | null {
  if (!productIndex || items.length === 0) return null
  let mask = 0
  for (const item of items) {
    const product = productIndex.get(item.productId)
    if (!product || typeof product.allergenMask !== "number") return null
    mask |= product.allergenMask
  }
  return mask
}

// ---------------------------------------------------------------------------------------------
// 37-30 (UI-SPEC § D1; D-18, D-20): the two typed refusals a cash order can meet, each with its
// own sentence, branched on the problem TYPE before describeOrderError's generic path. The cap
// 429 carries no Retry-After by design (a slot frees when an order completes, not when time
// passes), so it must never read as the rate-limiter's "wait N seconds" and is never retried.
// The limit 422 interpolates the limit and the item FROM THE BODY: no limit lives here.
// ---------------------------------------------------------------------------------------------

export const ORDER_CAP_PROBLEM_TYPE = "https://jtoye.uk/errors/order-cap-exceeded"
export const ORDER_LIMIT_PROBLEM_TYPE = "https://jtoye.uk/errors/order-limit-exceeded"
export const PAY_BY_CARD_SUFFIX = ", or pay by card"

export type OrderCapAxis = "EMAIL" | "PHONE" | "IP"

function problemOf(err: unknown): { status?: number; data?: Record<string, unknown> } | null {
  const res = (err as { response?: { status?: number; data?: unknown } } | null)?.response
  if (!res || typeof res.data !== "object" || res.data === null) return null
  return { status: res.status, data: res.data as Record<string, unknown> }
}

function hasType(data: Record<string, unknown>, exact: string, suffix: string): boolean {
  const type = data.type
  return typeof type === "string" && (type === exact || type.endsWith(suffix))
}

/** 37-27's 429 order-cap-exceeded, with its axis (an unknown axis reads as the customer's). */
export function readOrderCapProblem(err: unknown): { axis: OrderCapAxis } | null {
  const res = problemOf(err)
  if (!res || res.status !== 429 || !res.data) return null
  if (!hasType(res.data, ORDER_CAP_PROBLEM_TYPE, "/order-cap-exceeded")) return null
  const axis = res.data.axis
  return { axis: axis === "IP" || axis === "PHONE" ? axis : "EMAIL" }
}

/** UI-SPEC § Copywriting C1-C4 "Cap 429": by axis, with the card suffix only when cards are taken. */
export function orderCapCopy(axis: OrderCapAxis, cardOffered: boolean): string {
  const suffix = cardOffered ? PAY_BY_CARD_SUFFIX : ""
  if (axis === "IP") {
    return `Too many cash orders have been placed from this connection. Try again later${suffix}.`
  }
  return `You already have the most open cash orders this shop allows for one customer. You can order again once one is completed${suffix}.`
}

export interface OrderLimitProblem {
  field: string
  value: number
  limit: number
  productName: string | null
}

/** 37-26's 422 order-limit-exceeded; null when it is not one or the body's limit is unusable. */
export function readOrderLimitProblem(err: unknown): OrderLimitProblem | null {
  const res = problemOf(err)
  if (!res || res.status !== 422 || !res.data) return null
  if (!hasType(res.data, ORDER_LIMIT_PROBLEM_TYPE, "/order-limit-exceeded")) return null
  const { field, value, limit, productName } = res.data
  if (typeof field !== "string" || typeof limit !== "number" || !Number.isFinite(limit)) return null
  return {
    field,
    value: typeof value === "number" ? value : Number.NaN,
    limit,
    productName: typeof productName === "string" && productName.length > 0 ? productName : null,
  }
}

/** UI-SPEC § Copywriting C1-C4 "Limit 422": the numbers come from the body, never from here. */
export function orderLimitCopy(p: OrderLimitProblem): string {
  switch (p.field) {
    case "quantity":
      return `You can order up to ${p.limit} of one item. Reduce ${p.productName ?? "it"} to ${p.limit} or fewer.`
    case "units":
      return `This order is bigger than this shop accepts online (up to ${p.limit} items). Contact the shop for large orders.`
    case "lines":
      return `This order is bigger than this shop accepts online (up to ${p.limit} lines). Contact the shop for large orders.`
    case "total":
      return `This order is over this shop's online limit of ${formatPrice(p.limit)}. Contact the shop for large orders.`
    default:
      return "This order is bigger than this shop accepts online. Contact the shop for large orders."
  }
}

/** The current set a stale-acknowledgement 409 carries, or null when it is not one / is malformed. */
type StaleAllergenSet = { mask: number; names: string[] }

/**
 * Recognise 31.1-03's 409 `allergen-acknowledgement-stale` and read the CURRENT set from it.
 *
 * Branched on the problem TYPE before `describeOrderError`'s generic path: the idempotency 409
 * (`idempotency-conflict`) is a different problem with a different recovery, and must keep its own.
 * Returns `{ stale: false }` for anything else; `{ stale: true, current: null }` for a body whose
 * type matches but whose set cannot be trusted (the panel then keeps what it has and re-fetches).
 */
export function readStaleAllergenProblem(
  err: unknown
): { stale: false } | { stale: true; current: StaleAllergenSet | null } {
  const res = (err as { response?: { status?: number; data?: Partial<AllergenAcknowledgementStaleProblem> } })
    ?.response
  const data = res?.data
  const isStale =
    res?.status === 409 &&
    !!data &&
    (data.type === ALLERGEN_ACK_STALE_PROBLEM_TYPE ||
      (typeof data.type === "string" && data.type.endsWith("/allergen-acknowledgement-stale")))
  if (!isStale) return { stale: false }
  return { stale: true, current: readCurrentAllergenSet(data) }
}

/** The 31.1 `currentAllergenMask` / `currentAllergens` pair, or null when it cannot be trusted. */
function readCurrentAllergenSet(data: {
  currentAllergenMask?: unknown
  currentAllergens?: unknown
}): StaleAllergenSet | null {
  const mask = data.currentAllergenMask
  const names = data.currentAllergens
  const wellFormed =
    typeof mask === "number" &&
    Number.isInteger(mask) &&
    mask >= 0 &&
    mask <= 16383 &&
    Array.isArray(names) &&
    names.every((n) => typeof n === "string")
  return wellFormed ? { mask: mask as number, names: [...(names as string[])] } : null
}

// ---------------------------------------------------------------------------------------------
// 37-34 (UI-SPEC § C1; D-16; RWO-007): the two typed refusals 37-33 added to the guest order,
// branched on the problem TYPE before every other path — the stale-allergen 409 and the
// idempotency 409 are different problems with different recoveries.
//
//   409 basket-changed {changes[]}            what the customer SAW is not what the server reads at
//                                             submit: the panel lists every change and the customer
//                                             accepts them before the SAME key is resubmitted.
//   422 basket-confirmation-required          a body without the expected prices: this client always
//                                             sends them, so this is a cached pre-37-C page, and the
//                                             generic copy asks for a refresh.
//
// A `changes[]` the page cannot read in full — missing, empty, an unknown kind, a price change
// without both prices — is NOT applied: rewriting the stored basket from a body we half-understand
// is the dangerous direction, so the whole body is treated as malformed (the `readStaleAllergenProblem`
// null precedent) and the basket is left exactly as it was.
// ---------------------------------------------------------------------------------------------

const BASKET_CHANGE_KINDS: ReadonlySet<string> = new Set<BasketChangeKind>([
  "PRICE_CHANGED",
  "UNAVAILABLE",
  "SOLD_OUT",
  "REMOVED",
  "BELOW_MINIMUM",
  "TOTAL_CHANGED",
])

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
}

/** One `changes[]` entry with exactly the fields its kind needs, or null when it cannot be trusted. */
function readBasketChange(raw: unknown): BasketChange | null {
  if (!raw || typeof raw !== "object") return null
  const r = raw as Record<string, unknown>
  if (typeof r.kind !== "string" || !BASKET_CHANGE_KINDS.has(r.kind)) return null
  const change: BasketChange = { kind: r.kind as BasketChangeKind }
  if (typeof r.productId === "string" && r.productId.length > 0) change.productId = r.productId
  if (typeof r.name === "string") change.name = r.name
  for (const field of ["expected", "current", "requested", "available", "minimum", "subtotal"] as const) {
    if (isCount(r[field])) change[field] = r[field] as number
  }
  if (typeof r.detail === "string") change.detail = r.detail
  switch (change.kind) {
    case "PRICE_CHANGED":
    case "TOTAL_CHANGED":
      return change.expected !== undefined && change.current !== undefined ? change : null
    case "SOLD_OUT":
      return change.available !== undefined ? change : null
    case "BELOW_MINIMUM":
      return (change.minimum !== undefined && change.subtotal !== undefined) || change.detail ? change : null
    default:
      return change
  }
}

/**
 * Recognise 37-33's 409 `basket-changed` and read its diff. `changes` is null for a body whose type
 * matches but whose list cannot be read in full (see above); `allergens` is the 31.1 current set
 * when the same problem carries it (the acknowledged allergen set moved too), else null.
 */
export function readBasketChangedProblem(
  err: unknown
): { changed: false } | { changed: true; changes: BasketChange[] | null; allergens: StaleAllergenSet | null } {
  const res = problemOf(err)
  if (!res || res.status !== 409 || !res.data) return { changed: false }
  if (!hasType(res.data, BASKET_CHANGED_PROBLEM_TYPE, "/basket-changed")) return { changed: false }
  const raw = res.data.changes
  let changes: BasketChange[] | null = null
  if (Array.isArray(raw) && raw.length > 0) {
    const parsed = raw.map(readBasketChange)
    changes = parsed.every((c) => c !== null) ? (parsed as BasketChange[]) : null
  }
  const allergens = res.data.currentAllergenMask === undefined ? null : readCurrentAllergenSet(res.data)
  return { changed: true, changes, allergens }
}

/** 37-33's 422 `basket-confirmation-required`: the request carried no expected prices. */
export function readBasketConfirmationRequiredProblem(err: unknown): boolean {
  const res = problemOf(err)
  if (!res || res.status !== 422 || !res.data) return false
  return hasType(res.data, BASKET_CONFIRMATION_REQUIRED_PROBLEM_TYPE, "/basket-confirmation-required")
}

/**
 * The recorded allergen set from the confirmation response (31.1-09, D-08), carried into the COD
 * confirmation and the payment step. An ABSENT field (older backend) is read as `null` — "not
 * recorded" — never as `[]`.
 */
type RecordedAllergens = {
  acknowledgedAllergens: string[] | null
  recordedAllergens: string[] | null
  recordedAllergenFlags: OrderAllergenFlag[] | null
}

function recordedAllergensOf(confirmation: GuestOrderConfirmation): RecordedAllergens {
  return {
    acknowledgedAllergens: confirmation.acknowledgedAllergens ?? null,
    recordedAllergens: confirmation.recordedAllergens ?? null,
    recordedAllergenFlags: confirmation.recordedAllergenFlags ?? null,
  }
}

/**
 * UXT-047 (#816, 37-14, UI-SPEC § B7). Every VAT row on this page renders only for a seller who
 * DECLARED a VAT registration (`shop.seller.vatNumber`, 31.1 V71; the public seller object omits
 * the field when none is declared). A seller below the registration threshold charges no VAT, so a
 * "VAT (incl. 20%)" line under their prices was a false statement about the price (T-37-38). For
 * such a seller the row is omitted and this one line sits under Total instead.
 *
 * Display only: the ledger still computes VAT as before (CONTEXT Q2, pending a legal ruling), and
 * nothing here says anything about it.
 */
/** 37-22 (K3): the availability notice's id, Place order's permanent explanation while the shop is not taking orders. */
const SHOP_AVAILABILITY_EXPLAINER_ID = "shop-availability-explainer"
/** How long after the server's stated reopening time the shop is asked again (the kitchen uses the same grace). */
const REOPEN_RECHECK_GRACE_MS = 500

// The B7 note (NoVatRegistrationNote) is components/storefront/no-vat-registration-note.tsx, shared with the order page (37-37).

/** What `loadStripe` resolves to. Derived from the `/pure` export so no default-entry import is needed for the type. */
type StripePromise = ReturnType<typeof loadStripe>

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

/** The lines-only signature the acknowledgement is reset on (D-02): `productId:quantity` per line. */
function basketSignatureOf(lines: readonly Pick<CartItem, "productId" | "quantity">[]): string {
  return lines.map((i) => `${i.productId}:${i.quantity}`).join("|")
}

export default function CheckoutPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params)
  const router = useRouter()
  const { items, totalPennies, itemCount, clearCart, applyServerChanges } = useCart()

  // Pre-fill from customer session if logged in (async, cookie-backed)
  const [customerName, setCustomerName] = useState("")
  const [customerEmail, setCustomerEmail] = useState("")
  // 37-30 (37-27, T-37-76): whether a customer is signed in decides WHERE the order is posted.
  // The access token is an HttpOnly cookie the browser cannot read, so a signed-in customer's
  // order goes through the same-origin proxy (POST /api/customer-orders?shop=) that adds
  // X-Customer-Token server-side — the /orders/mine shape — and core records the contact as
  // verified. A guest's order goes straight to core as before.
  const [customerSignedIn, setCustomerSignedIn] = useState(false)
  useEffect(() => {
    let cancelled = false
    getCustomerSession().then((session) => {
      if (cancelled || !session) return
      setCustomerSignedIn(true)
      setCustomerName((prev) => prev || session.profile.name || "")
      setCustomerEmail((prev) => prev || session.profile.email || "")
    })
    return () => {
      cancelled = true
    }
  }, [])
  const [customerPhone, setCustomerPhone] = useState("")
  const [notes, setNotes] = useState("")
  // #812 (D-15): the dedicated allergy note. React state ONLY — never written to browser storage
  // (T-31.1-55); its one destination is the POST body.
  const [allergyNote, setAllergyNote] = useState("")
  const idempotencyKeyRef = useRef<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // The shop, read once on load and again after a stated reopening time (the loader is below). It is
  // declared here because the order intent — the expected total, D-16 — needs its delivery fee.
  const [shop, setShop] = useState<PublicShop | null>(null)

  // 37-34 (UI-SPEC § C1, D-16): the server's basket diff, held until it is accepted or an order is
  // placed. `names` is the basket AS THE CUSTOMER SAW IT at the moment of the 409 — a REMOVED entry
  // carries no name on the wire and the line is gone from the cart once applied, so the panel reads
  // the name from this snapshot. `seq` keys the panel: a second 409 mounts a fresh one (focus moves
  // again).
  const [basketChanged, setBasketChanged] = useState<{
    changes: BasketChange[]
    allergensChanged: boolean
    names: Record<string, string>
    seq: number
  } | null>(null)
  const [basketChangesAccepted, setBasketChangesAccepted] = useState(false)
  // Place order was pressed before "Accept changes": the refusal line beside the panel.
  const [basketChangeRefused, setBasketChangeRefused] = useState(false)
  const acceptChangesRef = useRef<HTMLButtonElement>(null)
  const basketChangeSeqRef = useRef(0)
  // KEY POLICY (RESEARCH §37-C.1 step 5; goods P2-CHA-12/13, P2-RAV-20): the resubmit after a
  // basket-changed 409 reuses the SAME Idempotency-Key even though the body changes (new prices,
  // the new total, perhaps a re-ticked mask). The server threw the 409 inside its reserved
  // idempotency work and rolled the reservation back (37-33), so nothing is held under the key and
  // the corrected body is accepted; keeping it means one key per tap sequence — exactly one order
  // however the taps land. The flag is consumed by the next submit, so a later edit mints a fresh
  // key as before (API-4 / M3).
  const reuseKeyAfterBasketChangeRef = useRef(false)
  // Set just before the 409's rewrite changes the lines, read ONCE by the basket-signature effect
  // below: the server said, in the same problem, whether the acknowledged allergen set moved, so
  // the effect keeps the server's set (never drops back to a client view the server just refused)
  // and keeps a still-valid acknowledgement instead of resetting it as a shopper's edit would.
  const serverRewriteRef = useRef<{ keepAcknowledged: boolean } | null>(null)
  const focusAcceptChanges = () => {
    const el = acceptChangesRef.current
    if (el && el.isConnected) el.focus()
  }

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
  // Which refusal the panel's alert region is announcing, or null. "unticked" is the 31-14 gate;
  // "stale" and "unavailable" are 31.1-15's (#785, T-31.1-53).
  const [ackError, setAckError] = useState<AllergenAckErrorKind | null>(null)
  const focusAckCheckbox = () => {
    if (ackCheckboxRef.current && ackCheckboxRef.current.isConnected) ackCheckboxRef.current.focus()
  }

  // The storefront catalogue, so the panel can state the basket's DECLARED set before the order
  // exists. 31-10's snapshot lives on the ORDER, which by construction is not created yet at this
  // point — its SUMMARY records that this panel's data comes from the basket and that the DTO
  // shapes are the shape to match.
  const [productIndex, setProductIndex] = useState<Map<string, PublicProduct> | null>(null)
  // One fetch, used on mount and for the two re-fetches below (a NOT RECORDED submit, and after a
  // stale 409). A failure resolves to null: the panel then reads NOT RECORDED rather than claiming
  // the kitchen declared nothing.
  const fetchProductIndex = useCallback(async (): Promise<Map<string, PublicProduct> | null> => {
    try {
      const res = await publicApiClient.get(`/public/shops/${slug}/products`)
      return indexProductsById(res.data)
    } catch {
      return null
    }
  }, [slug])
  useEffect(() => {
    let cancelled = false
    fetchProductIndex().then((index) => {
      if (!cancelled && index) setProductIndex(index)
    })
    return () => {
      cancelled = true
    }
  }, [fetchProductIndex])

  // #785 (D-05): the CURRENT set a stale-acknowledgement 409 carried. While set, it is what the
  // panel shows and what a re-ticked submit acknowledges — the server is the authority on what it
  // will accept for this basket. Cleared when the basket changes, or when a re-fetched catalogue
  // agrees with it. A re-fetch that DISAGREES does not clear it: a further vendor edit would be
  // refused with a newer 409 carrying the newer set, so the loop converges on the server's view,
  // whereas dropping back to a client view the server has just refused would loop on the refusal.
  const [staleOverride, setStaleOverride] = useState<StaleAllergenSet | null>(null)

  const declaredAllergenNames = useMemo(
    () => (staleOverride ? staleOverride.names : basketAllergenNames(items, productIndex)),
    [items, productIndex, staleOverride]
  )
  const declaredAllergenMask = useMemo(
    () => (staleOverride ? staleOverride.mask : basketAllergenMask(items, productIndex)),
    [items, productIndex, staleOverride]
  )
  // #860 (31.1-23, D-18): which dish brings which declared allergen, and each line's D-09 flags.
  // DISPLAY ONLY: neither feeds `declaredAllergenMask`, the intent or its signature, so the body
  // this page submits is unchanged for the same basket. The panel withholds the attribution when it
  // disagrees with the set it states (a stale 409's server set).
  const allergenAttribution = useMemo(() => basketAllergenAttribution(items, productIndex), [items, productIndex])
  const allergenFlags = useMemo(() => basketAllergenFlags(items, productIndex), [items, productIndex])

  /**
   * A stable signature of the BASKET. Changing the basket produces a different one, which resets
   * the acknowledgement — acknowledging one basket must not silently carry to another. This is
   * deliberately basket-only: correcting a phone number is not a new set of allergens.
   */
  const basketSignature = useMemo(() => basketSignatureOf(items), [items])
  useEffect(() => {
    const rewrite = serverRewriteRef.current
    serverRewriteRef.current = null
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    setAckError(null)
    if (rewrite) {
      // 37-34: the SERVER rewrote the lines (a basket-changed 409 applied). It is the authority on
      // whether the allergen set moved: when the same 409 carried the 31.1 properties the box was
      // unticked and the server's set held as the override; when it did not, the acknowledged set is
      // exactly the one the remaining lines declare, and the customer is not asked to tick again.
      if (!rewrite.keepAcknowledged) setAcknowledged(false)
      return
    }
    setAcknowledged(false)
    setStaleOverride(null)
  }, [basketSignature])

  // D-16 (37-32/37-34): WHAT THE CUSTOMER SAW, sent with the order so the server can refuse a
  // difference (409 basket-changed) instead of charging it. Per line, the unit price the basket
  // holds — the number on every row of the order summary; at the top level, the total on the Place
  // order button: the line totals plus the delivery fee this page previews, which mirrors the
  // server's FulfilmentPolicy (lib/delivery-fee.ts), so the comparison is like with like. A shop
  // that has not loaded previews a £0 fee, and if the server's fee differs the customer is SHOWN the
  // difference ("Total was …, now …") rather than charged it silently.
  const expectedLines = useMemo(
    () => items.map((item) => ({ productId: item.productId, quantity: item.quantity, expectedUnitPricePennies: item.pricePennies })),
    [items]
  )
  const previewFeePennies = previewDeliveryFeePennies(
    totalPennies,
    fulfilmentType,
    shop?.deliveryFeePennies,
    shop?.freeDeliveryThresholdPennies
  )
  const expectedTotalPennies = totalPennies + previewFeePennies

  // The body this page will POST, built ONCE from the form state and used for BOTH the request
  // and the idempotency signature, so no field can be submitted without being signed
  // (lib/checkout-idempotency.ts).
  const orderIntent = useMemo(
    () =>
      buildGuestOrderIntent({
        customerName,
        customerEmail,
        customerPhone,
        notes,
        fulfilmentType,
        address1,
        address2,
        city,
        postcode,
        items: expectedLines,
        // #784/#785 (D-05): the set the panel SHOWED. Signed with everything else, so a
        // re-acknowledged resubmit after a stale 409 rotates the Idempotency-Key and an unchanged
        // resubmit after a lost reply replays.
        acknowledgedAllergenMask: declaredAllergenMask,
        allergyNote,
        expectedTotalPennies,
      }),
    [
      customerName,
      customerEmail,
      customerPhone,
      notes,
      fulfilmentType,
      address1,
      address2,
      city,
      postcode,
      expectedLines,
      declaredAllergenMask,
      allergyNote,
      expectedTotalPennies,
    ]
  )
  const intentSignature = useMemo(() => guestOrderIntentSignature(orderIntent), [orderIntent])
  // Bind the key to the last SUBMITTED payload, never to intermediate edits. A lost response
  // followed by edit -> undo must replay the same order; a genuinely changed submission needs
  // a new key to avoid the server's 422 idempotency-payload-mismatch (API-4 / PR #726 M3).
  const lastIntentSignatureRef = useRef<string | null>(null)

  // #793: Stripe is loaded LAZILY, at most once per page, and only for a real card payment —
  // getStripe() is called solely where a confirmation carrying a clientSecret moves the page to
  // the payment step. A cash order, and a card-accepting shop's checkout FORM, load nothing.
  // The ref is the memo (one loadStripe call however many submissions follow a "Back to
  // details"); the state copy is what render reads, because a ref change does not re-render.
  // Stripe's advanced fraud signals stay at their default on this card path (owner choice,
  // 31.1 RESEARCH Pattern 6), so setLoadParameters is deliberately not called.
  const stripeRef = useRef<StripePromise | null>(null)
  const [stripePromise, setStripePromise] = useState<StripePromise | null>(null)
  const getStripe = (): StripePromise | null => {
    const publishableKey = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    if (!stripeRef.current && publishableKey) {
      stripeRef.current = loadStripe(publishableKey)
    }
    return stripeRef.current
  }

  // Fetch the shop so the fee breakdown can be shown BEFORE payment. Provides
  // deliveryFeePennies + freeDeliveryThresholdPennies for the client preview;
  // failure degrades gracefully to a £0 preview (server stays authoritative).
  // (`shop` itself is declared above, beside the order intent that reads it.)
  const loadShop = useCallback(() => {
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
  useEffect(() => loadShop(), [loadShop])

  // UXT-047 (B7): a VAT row needs a DECLARED registration; the note needs a LOADED shop that
  // declares none. A shop that failed to load proves neither, so it gets neither (the SellerBlock
  // rule: a failed fetch is not "the seller has not provided their details").
  const sellerVatRegistered = Boolean(shop?.seller?.vatNumber)
  const sellerVatUndeclared = shop !== null && !sellerVatRegistered

  // 37-22 (UI-SPEC K3 "Checkout", D-15): the shop's SERVER answer at page load. When it says the shop
  // is not taking orders (paused, or openNow false), the K3 notice sits at the top of step 1 and
  // Place order is disabled with that notice as its permanent explanation (aria-describedby) — the
  // `belowMinimum` precedent: disabled only where the reason is on screen beside it. A shop that did
  // not load, or a payload without openNow, disables nothing: the server still decides at submit,
  // and its typed refusal is handled below (the checkout race).
  const shopAvailability = shop ? readShopAvailability(shop) : null
  const notTakingOrders = shopAvailability?.kind === "paused" || shopAvailability?.kind === "closed"

  // The snapshot above is the ONLY thing that disables Place order, and it was read once, on mount.
  // A customer who opened checkout during a 20-minute pause and waited it out would otherwise keep a
  // dead button the server now accepts (#916 round 1, finding 1). So just after the server's own
  // stated end — the pause end, or a closed shop's next opening — the shop is read AGAIN and that
  // answer is rendered (the kitchen's PauseOrdersControl precedent, 37-21). No clock decides the
  // state: the clock only schedules the question. A stated time already in the past (a stale
  // snapshot) is asked at once. The effect keys on the time string, so an unchanged answer does
  // not re-arm it and nothing can loop.
  const reopensAt =
    shopAvailability?.kind === "paused"
      ? shopAvailability.reopensAt
      : shopAvailability?.kind === "closed"
        ? shopAvailability.nextOpenAt
        : null
  useEffect(() => {
    if (!reopensAt) return
    const at = Date.parse(reopensAt)
    if (Number.isNaN(at)) return
    const wait = Math.max(0, at - Date.now()) + REOPEN_RECHECK_GRACE_MS
    let cancelRead: (() => void) | null = null
    const timer = setTimeout(() => {
      cancelRead = loadShop()
    }, wait)
    return () => {
      clearTimeout(timer)
      cancelRead?.()
    }
  }, [reopensAt, loadShop])

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
  } & RecordedAllergens | null>(null)

  // 37-37 (UI-SPEC C3, UXT-029): the order number of a cash order that was just placed, held only
  // while the page routes to /shop/{slug}/orders/{orderNumber}. The confirmation itself — "Pay on
  // delivery/collection" (WR-08), the breakdown, the recorded allergens and the seller — is that
  // route's now, read from the tracking response, so it is announced and survives a refresh.
  const [placedOrderNumber, setPlacedOrderNumber] = useState<string | null>(null)

  if (items.length === 0 && !paymentState && !placedOrderNumber) {
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
    // K3: the disabled button already says why; an implicit or forced submit sends nothing either.
    if (notTakingOrders) return
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

    // 37-34 (UI-SPEC § C1) — THE BASKET-CHANGED GATE. The server has said the basket is not the one
    // the customer saw, and they have not yet accepted the changes: refuse, announce it beside the
    // panel and move focus to "Accept changes". Refuse-not-disable, the 31.1 pattern below — and a
    // tap that lands as the 409 returns meets THIS, never a submit at prices not yet seen.
    if (basketChanged && !basketChangesAccepted) {
      setBasketChangeRefused(true)
      focusAcceptChanges()
      return
    }

    // D-02 — THE ALLERGEN GATE. Refused BEFORE any network call: an error rendered alongside a
    // created order is the dangerous outcome, because the kitchen is already cooking while the
    // customer is being told off. The button deliberately stays ENABLED (see the note at the
    // submit button) so a touch user gets feedback rather than a dead press.
    if (!acknowledged) {
      setAckError("unticked")
      focusAckCheckbox()
      return
    }

    // T-31.1-53 — NO SET, NO ORDER. With the catalogue unresolved there is nothing the customer
    // could have acknowledged, and the server would refuse a missing mask (422) anyway. Never send
    // a guessed or zero mask: 0 means "the kitchen declared none", which nobody said. Try the
    // catalogue once more first, so a slow first load does not strand the customer.
    if (declaredAllergenMask === null) {
      const fresh = await fetchProductIndex()
      if (fresh) setProductIndex(fresh)
      if (basketAllergenMask(items, fresh) === null) {
        setAckError("unavailable")
        focusAckCheckbox()
        return
      }
      // A set has arrived — but the customer ticked a panel that said NOT RECORDED. Sending this
      // set would record an acknowledgement of something they were never shown (D-05's defect), so
      // show it, untick, and ask for a fresh tick.
      setAcknowledged(false)
      setAckError("stale")
      focusAckCheckbox()
      return
    }

    setAckError(null)
    setSubmitting(true)

    try {
      // A new key for a changed intent — EXCEPT the one change the server itself asked for: the
      // resubmit after a basket-changed 409 keeps its key (reuseKeyAfterBasketChangeRef, above).
      if (
        !idempotencyKeyRef.current ||
        (lastIntentSignatureRef.current !== intentSignature && !reuseKeyAfterBasketChangeRef.current)
      ) {
        idempotencyKeyRef.current = crypto.randomUUID()
      }
      lastIntentSignatureRef.current = intentSignature
      reuseKeyAfterBasketChangeRef.current = false
      // One read, used for BOTH the header and the body field, so the two can never disagree.
      const idempotencyKey = idempotencyKeyRef.current
      // The body is the SIGNED intent plus the key — the same object the signature was taken
      // over, so what the server compares against the key is exactly what the key was bound to.
      // Server contract (GuestOrderRequest, plan 19-01) is FLAT: fulfilmentType +
      // addressLine1/2 + addressCity + addressPostcode (NOT a nested address obj).
      const payload = { ...orderIntent, idempotencyKey }

      // QA 20260902 Cluster E (API-3): the Idempotency-Key HEADER is the platform contract every
      // other mutating endpoint speaks; the body field is the storefront's working legacy
      // convention and stays authoritative server-side. Both are sent (additive — nothing
      // displaced), carrying the same value.
      // An ABSOLUTE same-origin URL bypasses the client's core baseURL; the proxy forwards the
      // body and the Idempotency-Key and adds the token from the cookie (never from here).
      const orderUrl = customerSignedIn
        ? `${window.location.origin}/api/customer-orders?shop=${encodeURIComponent(slug)}`
        : `/public/shops/${slug}/orders`
      const res = await publicApiClient.post<GuestOrderConfirmation>(
        orderUrl,
        payload,
        { headers: { "Idempotency-Key": idempotencyKey } }
      )

      const confirmation = res.data
      // The order exists: whatever the server said about the basket has been acted on. Only when
      // something WAS said — the common path leaves these untouched, as before 37-34.
      if (basketChanged) {
        setBasketChanged(null)
        setBasketChangesAccepted(false)
        setBasketChangeRefused(false)
      }

      if (!confirmation.clientSecret) {
        // COD mode — 37-37 (UI-SPEC C3): the card path's route, after the card path's save. The
        // email is stored first because the order page reads tracking data bound to number + email
        // (goods F3); the marker tells that page this is the first view, so it says "Order
        // confirmed" once, focused and announced. The page only READS: a refresh re-posts nothing.
        localStorage.setItem(`jtoye-checkout-email-${slug}`, customerEmail.trim())
        saveLocalOrder({
          orderNumber: confirmation.orderNumber,
          email: customerEmail.trim(),
          shopSlug: slug,
          placedAt: new Date().toISOString(),
        })
        markJustPlaced(confirmation.orderNumber)
        setPlacedOrderNumber(confirmation.orderNumber)
        clearCart()
        router.push(`/shop/${slug}/orders/${confirmation.orderNumber}`)
        return
      }

      // Store email for order tracking
      localStorage.setItem(`jtoye-checkout-email-${slug}`, customerEmail.trim())

      // Move to payment step. This is the ONLY place Stripe is loaded (#793): the order exists
      // and carries a clientSecret, so this customer is paying by card.
      setStripePromise(getStripe())
      setPaymentState({
        clientSecret: confirmation.clientSecret,
        orderNumber: confirmation.orderNumber,
        subtotalPennies: confirmation.subtotalPennies,
        deliveryFeePennies: confirmation.deliveryFeePennies || 0,
        vatRate: confirmation.vatRate,
        vatAmountPennies: confirmation.vatAmountPennies,
        totalAmountPennies: confirmation.totalAmountPennies,
        allergenWarnings: confirmation.allergenWarnings || [],
        ...recordedAllergensOf(confirmation),
      })
    } catch (err: unknown) {
      // 37-34 (UI-SPEC § C1, D-16): the basket the server read at submit is not the one the customer
      // saw — a vendor reprice, a line gone unavailable, sold out or removed, the minimum or the
      // total. No order exists and the key was not held (37-33). Show every change, keep the SAME
      // key for the resubmit, and refuse Place order until "Accept changes" is pressed. Checked
      // FIRST among the 409s: the allergen-only case never carries this type (31.1 unchanged).
      const basket = readBasketChangedProblem(err)
      if (basket.changed) {
        if (!basket.changes) {
          // A body this page cannot read in full is not applied: the generic copy, the basket as it was.
          setError(BASKET_CONFIRMATION_REQUIRED_COPY)
          return
        }
        reuseKeyAfterBasketChangeRef.current = true
        basketChangeSeqRef.current += 1
        setBasketChanged({
          changes: basket.changes,
          allergensChanged: basket.allergens !== null,
          names: Object.fromEntries(items.map((item) => [item.productId, item.title])),
          seq: basketChangeSeqRef.current,
        })
        setBasketChangesAccepted(false)
        setBasketChangeRefused(false)
        // THE BASKET FOLLOWS THE SERVER'S FACTS (UI-SPEC § C1; F15). The stored lines become the
        // server's — through the provider's own write, which leaves the ownership stamp alone — so
        // the total on the button and the prices the resubmit sends are the ones just accepted.
        // When the lines (not only prices) change, the basket-signature effect is told it was the
        // server, and whether the acknowledgement still stands (see serverRewriteRef).
        const nextItems = applyBasketChanges(items, basket.changes)
        if (basketSignatureOf(nextItems) !== basketSignature) {
          serverRewriteRef.current = { keepAcknowledged: basket.allergens === null }
        }
        applyServerChanges(basket.changes)
        if (basket.allergens) {
          // The same 409 carried the 31.1 properties: the allergen panel states the server's set,
          // unticked (D-05 composed, not beside), and the panel above says why.
          setStaleOverride(basket.allergens)
          setAcknowledged(false)
        }
        // Re-read the catalogue so prices, flags and attribution are current; the override is
        // released only when the fresh catalogue agrees with the set the server refused on (the
        // 31.1 convergence rule — a further vendor edit arrives as a newer 409, never as a loop).
        void fetchProductIndex().then((fresh) => {
          if (!fresh) return
          setProductIndex(fresh)
          if (!basket.allergens) return
          const freshMask = basketAllergenMask(nextItems, fresh)
          setStaleOverride((prev) => (prev && freshMask === prev.mask ? null : prev))
        })
        return
      }
      // The 422 a request without the expected prices meets — this client always sends them, so
      // only a cached pre-37-C page can get here: ask for a refresh, keep the basket.
      if (readBasketConfirmationRequiredProblem(err)) {
        setError(BASKET_CONFIRMATION_REQUIRED_COPY)
        return
      }
      // #785 (D-05): the set the customer acknowledged is no longer the basket's declared set — a
      // vendor edit landed after they read the panel. No order exists and the server did not hold
      // the key. Show the server's current set, untick, announce it in the panel's own alert
      // region and move focus to the box. The button stays enabled; the 31-14 gate refuses again
      // until they tick, and the changed mask rotates the key.
      const stale = readStaleAllergenProblem(err)
      if (stale.stale) {
        if (stale.current) setStaleOverride(stale.current)
        setAcknowledged(false)
        setAckError("stale")
        focusAckCheckbox()
        const basketAtSubmit = items
        void fetchProductIndex().then((fresh) => {
          if (!fresh) return
          setProductIndex(fresh)
          const freshMask = basketAllergenMask(basketAtSubmit, fresh)
          setStaleOverride((prev) => (prev && freshMask === prev.mask ? null : prev))
        })
        return
      }
      // 37-22 (UI-SPEC K3 "Checkout race"): the shop paused or closed while the customer was on the
      // page. 37-20 refuses with a typed 409 shop-closed BEFORE any write or idempotency
      // reservation, so no order exists, and nothing on this path touches the basket: it is saved,
      // and the copy says so. Recognised by type before the generic path, like the stale-allergen 409.
      const shopClosed = readShopClosedProblem(err)
      if (shopClosed) {
        setError(shopClosedRaceCopy(shop?.name ?? "This shop", shopClosed))
        return
      }
      // 37-30 (UI-SPEC § D1): the cash-order cap and the order limits, by type, before the generic
      // path. Nothing was written and no reservation held on either (37-26, 37-27); the basket is
      // kept and NOTHING retries — a cap 429 has no Retry-After to honour and must not be looped.
      const cap = readOrderCapProblem(err)
      if (cap) {
        setError(orderCapCopy(cap.axis, shop?.acceptsCardPayments === true))
        return
      }
      const limit = readOrderLimitProblem(err)
      if (limit) {
        setError(orderLimitCopy(limit))
        return
      }
      // #409: this used to read ONLY `response.data.detail` (RFC 7807). The rate
      // limiter answers 429 with `Retry-After` and an `error`/`message` body, so
      // the one actionable sentence the server sent was discarded and the
      // shopper was told to "try again" — immediately, which re-trips the limit.
      setError(describeOrderError(err))
    } finally {
      setSubmitting(false)
    }
  }

  // 37-37 (UI-SPEC C3, UXT-029): a cash order is confirmed on its own route now, not in place here.
  // Between the 201 and the navigation the basket is already empty, so this stands in for the
  // "Nothing to checkout" screen, which would read as a lost order.
  if (placedOrderNumber) {
    return (
      <div className="mx-auto flex min-h-[60vh] max-w-2xl flex-col items-center justify-center px-4 text-center" aria-busy="true">
        <Loader2 className="h-8 w-8 animate-spin text-amber-700" aria-hidden="true" />
        <p className="mt-3 text-sm text-slate-600">Opening your order {placedOrderNumber}…</p>
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
            setAckError(null)
            setBasketChanged(null)
            setBasketChangesAccepted(false)
            setBasketChangeRefused(false)
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
                <span className="text-emerald-700 font-medium">Free</span>
              </div>
            )}
            {sellerVatRegistered && paymentState.vatAmountPennies > 0 && (
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">VAT ({vatRateLabel(asVatRate(paymentState.vatRate))})</span>
                <span className="text-slate-900">{formatPrice(paymentState.vatAmountPennies)}</span>
              </div>
            )}
            <div className="flex items-center justify-between pt-1.5">
              <span className="text-base font-bold text-slate-900">Total</span>
              <span className="text-base font-bold text-slate-900">{formatPrice(paymentState.totalAmountPennies)}</span>
            </div>
            {sellerVatUndeclared && <NoVatRegistrationNote />}
          </div>
        </div>

        {/* D-08: the order exists at this step, so its record is shown before the card is taken. */}
        <RecordedAllergenSet
          acknowledged={paymentState.acknowledgedAllergens}
          recorded={paymentState.recordedAllergens}
          flags={paymentState.recordedAllergenFlags}
          className="mb-4"
        />

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

        {/* #789 (31.1-24, D-13): the seller, the platform statement and the no-cancellation
            information, before the card is taken. */}
        {shop && <SellerBlock seller={shop.seller} className="mb-4" />}

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
  // ONE preview (computed above, beside the order intent): what the Delivery row and the Place order
  // button show IS what `expectedTotalPennies` sends (D-16), by construction.
  const deliveryFeePennies = previewFeePennies
  const deliveryIsFree = deliveryFeePennies === 0
  const previewTotalPennies = expectedTotalPennies
  // WR-01: mirror the server-side minimum-order gate (item subtotal, delivery
  // fee excluded). The server enforces it authoritatively in createGuestOrder;
  // this just stops the user submitting an order that would be rejected.
  // Arithmetic shared with FloatingCartBar via lib/minimum-order (#718 F-3).
  const minimumOrderPennies = shop?.minimumOrderPennies ?? 0
  const minimumShortfall = minimumShortfallPennies(subtotalPennies, minimumOrderPennies)
  const belowMinimum = minimumShortfall !== null
  // COR-6: the VAT preview follows the BASKET's resolved rate, not a hardcoded 20%.
  //
  // This used to be Math.floor((previewTotalPennies * 20) / 120) with a "VAT (incl. 20%)" label,
  // because PublicProductDto carried no rate and the client structurally could not resolve one.
  // Most cold takeaway food is ZERO-rated (HMRC VAT Notice 709/1), so on such a basket the
  // customer was shown a VAT figure before paying and a contradicting figure on the confirmation
  // screen one screen later. The server has resolved the real rate since Issue #81 BUG 2.
  //
  // lib/vat.ts mirrors VatCalculator clause for clause: predominantRate over the line grosses,
  // then vatFromGross over the COMBINED total (subtotal + delivery), which is the single
  // truncation Order.calculateTotal performs. Preview only — the server recomputes and remains
  // authoritative, so nothing here can change what is charged.
  const basketVatRate = predominantRate(
    items.map((item) => ({
      grossPennies: item.pricePennies * item.quantity,
      rate: asVatRate(item.vatRate),
    }))
  )
  const vatPreviewPennies = vatFromGross(previewTotalPennies, basketVatRate)
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

      {/* K3: top of step 1, before anything the customer fills in. The id is Place order's
          aria-describedby while the shop is not taking orders. */}
      {notTakingOrders && shop && (
        <ShopAvailabilityNotice shop={shop} id={SHOP_AVAILABILITY_EXPLAINER_ID} className="mt-6" />
      )}

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
              placeholder="Any special requests..."
              rows={2}
              className="w-full rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200 resize-none"
            />
          </div>

          {/* #812 (D-15): the allergy note is its OWN field. It goes to the kitchen ticket and the
              shop must acknowledge reading it (31.1-13), so it must not be buried in order notes.
              Optional; no autocomplete token — it collects no autofillable data about the user. */}
          <div className="space-y-1.5">
            <label htmlFor="allergy-note" className="block text-xs font-medium text-slate-600">
              {ALLERGY_NOTE_LABEL_COPY}
            </label>
            <textarea
              id="allergy-note"
              value={allergyNote}
              onChange={(e) => setAllergyNote(e.target.value)}
              maxLength={ALLERGY_NOTE_MAX_LENGTH}
              rows={2}
              aria-describedby="allergy-note-help allergy-note-count"
              className="w-full rounded-lg border border-cream-100 px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200 resize-none"
            />
            <div className="flex items-start justify-between gap-3">
              <p id="allergy-note-help" className="text-xs text-slate-600">
                {allergyNoteHelpCopy(shop?.name ?? "the shop")}
              </p>
              <p
                id="allergy-note-count"
                data-testid="allergy-note-count"
                aria-live="polite"
                className="flex-shrink-0 text-xs tabular-nums text-slate-600"
              >
                {allergyNoteCountCopy(allergyNote.length)}
              </p>
            </div>
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
                <span className="text-emerald-700 font-semibold">Free</span>
              ) : (
                <span className="text-slate-900">{formatPrice(deliveryFeePennies)}</span>
              )}
            </div>
            {/* UXT-047 (B7): only for a seller who declared a VAT registration. */}
            {sellerVatRegistered && (
              <div className="flex items-center justify-between text-sm">
                {/* Prices are VAT-inclusive (UK retail): VAT is the fraction already
                    contained within the gross total, not an add-on. COR-6: the rate is the
                    basket's RESOLVED predominant rate, so this line matches the post-order
                    confirmation screen's vatAmountPennies on a zero- or reduced-rated basket
                    too — it did not before. */}
                <span className="text-slate-600">VAT ({vatRateLabel(basketVatRate)})</span>
                <span className="text-slate-900">{formatPrice(vatPreviewPennies)}</span>
              </div>
            )}
            <div className="flex items-center justify-between pt-1.5">
              <span className="text-lg font-bold text-slate-900">Total</span>
              <span className="text-lg font-bold text-slate-900">{formatPrice(previewTotalPennies)}</span>
            </div>
            {sellerVatUndeclared && <NoVatRegistrationNote />}
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

        {/* #789 (31.1-24, D-13): who the customer is buying from, that J'Toye is the platform and not
            the seller, and that freshly prepared food has no 14-day right to cancel (CCR 2013
            Sch 2(o), reg 28(1)(c)) — pre-contract information, so it is here, inside the form and
            above Place order. Placed ABOVE the allergen panel so that panel stays the last thing
            read before committing (D-02 below). Rendered only once the shop has loaded: a failed
            fetch is not "the seller has not provided their details". Display only — nothing in
            it is submitted. */}
        {shop && <SellerBlock seller={shop.seller} />}

        {/* 37-34 (UI-SPEC § C1, D-16): what the server found different from what the customer saw,
            DIRECTLY ABOVE the allergen panel (where the eyes and the thumb are), inside the form.
            Keyed on the 409's sequence so a second refusal mounts a fresh panel and moves focus
            again. Collapses to a one-line note once accepted; the Place order label below then
            shows the new total. */}
        {basketChanged && (
          <BasketChangedPanel
            key={basketChanged.seq}
            changes={basketChanged.changes}
            nameFor={(productId) => (productId ? (basketChanged.names[productId] ?? null) : null)}
            allergensChanged={basketChanged.allergensChanged}
            accepted={basketChangesAccepted}
            onAccept={() => {
              setBasketChangesAccepted(true)
              setBasketChangeRefused(false)
            }}
            refused={basketChangeRefused}
            acceptRef={acceptChangesRef}
            refusalId="basket-changed-refusal"
          />
        )}

        {/* D-02 — the pre-submit allergen block. Deliberately the LAST thing read before
            committing: after "How you'll pay", immediately above the submit run. Not in the order
            summary, not collapsed, not behind a disclosure.

            `declaredAllergenNames` is null (NOT RECORDED) whenever the basket cannot be fully
            resolved. `allergenFlags` are each line's `undeclaredIngredientAllergens`, the
            reconciliation the SERVER computed per product (31.1-06) — this page renders them and
            never re-implements the heuristic in TypeScript (31-14-SUMMARY.md). They are null, not
            [], whenever the basket is NOT RECORDED. `allergenAttribution` (#860) says which dish
            carries each declared allergen, beneath the chips. */}
        <OrderAllergenPanel
          vendorName={shop?.name ?? "this kitchen"}
          allergenNames={declaredAllergenNames}
          allergenFlags={allergenFlags}
          allergenAttribution={allergenAttribution}
          acknowledged={acknowledged}
          onAcknowledgedChange={(next) => {
            setAcknowledged(next)
            if (next) setAckError(null)
          }}
          errored={ackError !== null}
          errorKind={ackError ?? undefined}
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
        {minimumShortfall !== null && (
          <p className="text-center text-xs font-medium text-slate-600">
            Minimum order {formatPrice(minimumOrderPennies)} — add{" "}
            {formatPrice(minimumShortfall)} more to place this order.
          </p>
        )}

        {/* Submit.

            ⚠ THE ACKNOWLEDGEMENT IS DELIBERATELY NOT IN THIS `disabled` EXPRESSION. `belowMinimum`
            belongs here because it has a permanent explanatory hint rendered directly above. An
            acknowledgement gate has no such hint, and a disabled button on a touch device gives NO
            feedback at all when pressed — the user learns nothing. The gate instead REFUSES in the
            submit handler and announces the refusal, which is both accessible and legally stronger:
            the refusal is evidence the gate fired.

            `notTakingOrders` (37-22, K3) meets the same bar as `belowMinimum`: its permanent
            explanation is the availability notice at the top of step 1, linked here by
            aria-describedby so a screen reader hears why the button is unavailable. */}
        <button
          type="submit"
          disabled={submitting || belowMinimum || notTakingOrders}
          aria-describedby={notTakingOrders ? SHOP_AVAILABILITY_EXPLAINER_ID : undefined}
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

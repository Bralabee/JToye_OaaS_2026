"use client"

import { useCallback, useEffect, useRef, useState, type RefObject } from "react"
import { CheckCircle2, Loader2 } from "lucide-react"
import { Elements } from "@stripe/react-stripe-js"
import publicApiClient from "@/lib/public-api-client"
import { loadStripeOnce, publishableKey, STRIPE_APPEARANCE, type StripePromise } from "@/lib/stripe-loader"
import { CardPaymentForm } from "@/components/storefront/card-payment-form"
import { Skeleton } from "@/components/ui/skeleton"
import type { PaymentLinkPreview, PaymentLinkPurpose, RedeemedPaymentLink } from "@/types/storefront"

/**
 * The client island of `/pay` (PAY-04 / D-03; UI-SPEC § S3; plan 30-18).
 *
 * THE LINK IS A SINGLE-USE BEARER CREDENTIAL, and redeem SPENDS it before it answers. So:
 *
 *   1. It arrives in the URL fragment (`#token=…`, owner decision 30-16), read once on mount and held
 *      in memory (a ref). It is POSTed in a JSON body to the read-only preview, which shows who is
 *      asking and for how much before anything is spent (T-30-74).
 *   2. Only the "Continue to payment" press calls redeem, and only after publishableKey() is non-null
 *      and an AWAITED loadStripeOnce() resolved a Stripe instance (UI-SPEC § S3 steps 2a-2b, T-30-72):
 *      a link is never spent on a page that cannot show the card form.
 *   3. After a successful redeem the address bar becomes "/pay" (history.replaceState) BEFORE Stripe
 *      mounts, so the token is not in the iframe's view of the page URL or in a screenshot. Until the
 *      press it stays, so a reload of an unused link still works.
 *   4. Nothing is written to local or session storage (T-30-73). The replaceState carries a
 *      `jtoyePayResume` entry in history.state — the PaymentIntent's client secret plus display fields,
 *      never the token — so a reload before paying remounts the form (PR #1018 review finding 5).
 *
 * A direct-charge intent lives on the vendor's connected account, so Stripe.js is initialised with
 * `stripeAccount` when redeem names one (plan 30-17, T-30-76). Card entry itself is CardPaymentForm,
 * shared with the checkout; nothing about it is re-implemented here.
 */

const PREVIEW_PATH = "/api/v1/public/payment-links/preview"
const REDEEM_PATH = "/api/v1/public/payment-links/redeem"

/** The history.state key that lets a reload after redeem resume (UI-SPEC § S3 Storage, amended). */
export const PAY_RESUME_KEY = "jtoyePayResume"

export const PAY_PAGE_COPY = {
  checking: "Checking your payment link…",
  continue: "Continue to payment",
  opening: "Opening secure payment…",
  singleUse: "This link opens once. After you continue, keep this page open until you've paid.",
  loadingForm: "Loading secure card form…",
  fallbackHeading: "Pay for your order",
  received: "Payment received",
} as const

/** What the page shows about the payment, from the preview, carried across redeem and a reload. */
interface Display {
  shopName: string
  purpose: PaymentLinkPurpose
  amountPennies: number
  orderNumber: string
}

/** The history.state resume entry: the intent's client secret and display fields. Never the token. */
interface ResumeEntry extends Display {
  clientSecret: string
  stripeAccountId: string | null
}

type Phase =
  | { kind: "checking" }
  | { kind: "error" }
  | { kind: "active"; preview: PaymentLinkPreview }
  | { kind: "opening"; preview: PaymentLinkPreview }
  | { kind: "preparing"; display: Display }
  | { kind: "paying"; display: Display; stripe: StripePromise; clientSecret: string; stripeAccountId: string | null }
  | { kind: "received"; display: Display }

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

/**
 * "d MMM yyyy, HH:mm" in Europe/London. The month is mapped by number rather than taken from Intl's
 * short month, because en-GB spells September "Sept".
 */
function formatExpiry(iso: string): string | null {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  const parts: Record<string, string> = {}
  for (const p of new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/London",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date)) {
    parts[p.type] = p.value
  }
  return `${Number(parts.day)} ${MONTHS[Number(parts.month) - 1]} ${parts.year}, ${parts.hour}:${parts.minute}`
}

function purposeHeading(purpose: PaymentLinkPurpose, shopName: string): string {
  if (purpose === "DEPOSIT") return `Pay your deposit to ${shopName}`
  if (purpose === "BALANCE") return `Pay the balance to ${shopName}`
  return `Pay ${shopName}`
}

const PURPOSES: readonly string[] = ["UPFRONT", "DEPOSIT", "BALANCE"]
const STATES: readonly string[] = ["ACTIVE", "USED", "EXPIRED", "REVOKED", "PAID"]

function isPreview(data: unknown): data is PaymentLinkPreview {
  if (!data || typeof data !== "object") return false
  const p = data as Record<string, unknown>
  return (
    typeof p.shopName === "string" &&
    typeof p.orderNumber === "string" &&
    typeof p.amountPennies === "number" &&
    typeof p.purpose === "string" &&
    PURPOSES.includes(p.purpose) &&
    typeof p.state === "string" &&
    STATES.includes(p.state)
  )
}

/** The fragment's `token` parameter, or null. */
function readToken(): string | null {
  const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ""))
  const token = fragment.get("token")
  return token && token.trim() ? token.trim() : null
}

/** The current history.state as an object (Next keeps its router keys in it), or {}. */
function currentState(): Record<string, unknown> {
  const state: unknown = window.history.state
  return state && typeof state === "object" ? { ...(state as Record<string, unknown>) } : {}
}

/** Drop the resume entry, keeping every other key (Next's router state) and the current URL. */
function clearResume() {
  const state = currentState()
  if (!(PAY_RESUME_KEY in state)) return
  delete state[PAY_RESUME_KEY]
  window.history.replaceState(state, "", window.location.pathname + window.location.search)
}

const CARD = "mx-auto max-w-md rounded-2xl border border-cream-100 bg-white p-6"
const H1 = "text-2xl font-semibold leading-tight text-slate-900 focus:outline-none [overflow-wrap:anywhere]"
const BODY = "mt-3 text-sm text-slate-600 [overflow-wrap:anywhere]"
const CTA =
  "inline-flex h-12 w-full items-center justify-center gap-2 rounded-lg bg-oxblood px-5 text-sm font-semibold text-white transition-colors hover:bg-oxblood-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700 disabled:cursor-not-allowed disabled:opacity-70"

export function PayClient() {
  const tokenRef = useRef<string | null>(null)
  const started = useRef(false)
  const inFlight = useRef(false)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [phase, setPhase] = useState<Phase>({ kind: "checking" })

  // Focus follows the state: on every change the new state's h1 takes focus (the order page's
  // headingRef pattern). The first state is not focused, so a page load does not steal focus.
  const shownKind = useRef(phase.kind)
  useEffect(() => {
    if (shownKind.current === phase.kind) return
    shownKind.current = phase.kind
    headingRef.current?.focus()
  }, [phase.kind])

  const runPreview = useCallback(async () => {
    setPhase({ kind: "checking" })
    try {
      const res = await publicApiClient.post<PaymentLinkPreview>(PREVIEW_PATH, { token: tokenRef.current })
      const preview = res.data
      if (isPreview(preview) && preview.state === "ACTIVE") setPhase({ kind: "active", preview })
      else setPhase({ kind: "error" })
    } catch {
      setPhase({ kind: "error" })
    }
  }, [])

  /** Step 2d onward: Stripe on the intent's account, the intent's status, then the card form. */
  const mountIntent = useCallback(async (display: Display, clientSecret: string, stripeAccountId: string | null) => {
    setPhase({ kind: "preparing", display })
    const promise = loadStripeOnce(stripeAccountId ?? undefined)
    let stripe: Awaited<StripePromise> = null
    try {
      stripe = promise ? await promise : null
    } catch {
      stripe = null
    }
    if (!stripe || !promise) {
      setPhase({ kind: "error" })
      return
    }
    try {
      const { paymentIntent } = await stripe.retrievePaymentIntent(clientSecret)
      if (!paymentIntent) throw new Error("no payment intent")
    } catch {
      setPhase({ kind: "error" })
      return
    }
    setPhase({ kind: "paying", display, stripe: promise, clientSecret, stripeAccountId })
  }, [])

  useEffect(() => {
    // Under Strict Mode (next dev) this effect runs twice; the link is read and previewed once.
    if (started.current) return
    started.current = true
    tokenRef.current = readToken()
    // The fragment exists only in the browser, so the state can only be chosen after mount.
    runPreview()
  }, [runPreview])

  /** The press (UI-SPEC § S3 step 2). The ONLY caller of redeem. */
  async function continueToPayment(preview: PaymentLinkPreview) {
    if (inFlight.current) return
    inFlight.current = true
    setPhase({ kind: "opening", preview })
    try {
      // 2a-2b: no key, or Stripe.js does not load -> the link is NOT spent.
      if (publishableKey() === null) {
        setPhase({ kind: "error" })
        return
      }
      let platform: Awaited<StripePromise> = null
      try {
        platform = await (loadStripeOnce() ?? Promise.resolve(null))
      } catch {
        platform = null
      }
      if (!platform) {
        setPhase({ kind: "error" })
        return
      }

      // 2c: spend the link.
      let redeemed: RedeemedPaymentLink
      try {
        redeemed = (await publicApiClient.post<RedeemedPaymentLink>(REDEEM_PATH, { token: tokenRef.current })).data
      } catch {
        setPhase({ kind: "error" })
        return
      }
      if (!redeemed || typeof redeemed.clientSecret !== "string" || !redeemed.clientSecret) {
        setPhase({ kind: "error" })
        return
      }
      // The link is spent; the token has no further use on this page.
      tokenRef.current = null

      const display: Display = {
        shopName: preview.shopName,
        purpose: preview.purpose,
        amountPennies: typeof redeemed.amountPennies === "number" ? redeemed.amountPennies : preview.amountPennies,
        orderNumber: preview.orderNumber,
      }
      const stripeAccountId =
        typeof redeemed.stripeAccountId === "string" && redeemed.stripeAccountId ? redeemed.stripeAccountId : null
      const entry: ResumeEntry = { clientSecret: redeemed.clientSecret, stripeAccountId, ...display }
      // 2d: the token leaves the address bar before Stripe mounts. Next's router keys are kept.
      window.history.replaceState({ ...currentState(), [PAY_RESUME_KEY]: entry }, "", "/pay")
      await mountIntent(display, redeemed.clientSecret, stripeAccountId)
    } finally {
      inFlight.current = false
    }
  }

  function paid(display: Display) {
    clearResume()
    setPhase({ kind: "received", display })
  }

  return (
    <div className="px-4 py-8">
      <div className={CARD}>
        {phase.kind === "checking" && (
          <div role="status" className="flex flex-col items-center py-4 text-center">
            <Loader2 className="mb-4 h-8 w-8 animate-spin text-orange-700" aria-hidden="true" />
            <h1 ref={headingRef} tabIndex={-1} className={H1}>
              {PAY_PAGE_COPY.checking}
            </h1>
          </div>
        )}

        {phase.kind === "error" && (
          <h1 ref={headingRef} tabIndex={-1} className={H1}>
            {PAY_PAGE_COPY.fallbackHeading}
          </h1>
        )}

        {(phase.kind === "active" || phase.kind === "opening") && (
          <>
            <AmountBlock display={phase.preview} headingRef={headingRef} />
            {formatExpiry(phase.preview.expiresAt) && (
              <p className="mt-1 text-xs text-slate-600">{`This link expires ${formatExpiry(phase.preview.expiresAt)}`}</p>
            )}
            <p className="mt-4 text-sm text-slate-600">{PAY_PAGE_COPY.singleUse}</p>
            <button
              type="button"
              onClick={() => continueToPayment(phase.preview)}
              disabled={phase.kind === "opening"}
              className={`mt-6 ${CTA}`}
            >
              {phase.kind === "opening" && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              {phase.kind === "opening" ? PAY_PAGE_COPY.opening : PAY_PAGE_COPY.continue}
            </button>
          </>
        )}

        {phase.kind === "preparing" && (
          <>
            <AmountBlock display={phase.display} headingRef={headingRef} />
            <div className="mt-6 rounded-xl border border-cream-100 bg-white p-4 shadow-sm">
              <Skeleton data-testid="card-form-skeleton" className="min-h-60 w-full rounded-xl" />
              <p role="status" className="mt-3 text-sm text-slate-600">
                {PAY_PAGE_COPY.loadingForm}
              </p>
            </div>
          </>
        )}

        {phase.kind === "paying" && (
          <>
            <AmountBlock display={phase.display} headingRef={headingRef} />
            <div className="mt-6">
              <Elements
                stripe={phase.stripe}
                options={{ clientSecret: phase.clientSecret, appearance: STRIPE_APPEARANCE }}
              >
                <CardPaymentForm
                  slug=""
                  orderNumber={phase.display.orderNumber}
                  customerEmail=""
                  totalPennies={phase.display.amountPennies}
                  returnUrl={`${window.location.origin}/pay?acct=${phase.stripeAccountId ?? ""}`}
                  onSucceeded={() => paid(phase.display)}
                  onLoadError={() => setPhase({ kind: "error" })}
                />
              </Elements>
            </div>
          </>
        )}

        {phase.kind === "received" && (
          <div className="flex flex-col py-2">
            <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50">
              <CheckCircle2 className="h-6 w-6 text-emerald-700" aria-hidden="true" />
            </span>
            <h1 ref={headingRef} tabIndex={-1} className={H1}>
              {PAY_PAGE_COPY.received}
            </h1>
            <p className={BODY}>
              {`You've paid ${formatPrice(phase.display.amountPennies)} to ${phase.display.shopName} for order ${phase.display.orderNumber}. You can close this page.`}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

/** Who is asking and for how much: the page's focal point, kept above the card form. */
function AmountBlock({
  display,
  headingRef,
}: {
  display: Display
  headingRef: RefObject<HTMLHeadingElement | null>
}) {
  return (
    <>
      <p className="text-sm text-slate-600 [overflow-wrap:anywhere]">{display.shopName}</p>
      <h1 ref={headingRef} tabIndex={-1} className={`mt-1 ${H1}`}>
        {purposeHeading(display.purpose, display.shopName)}
      </h1>
      <p className="mt-2 text-2xl font-semibold leading-tight tabular-nums text-slate-900">
        {formatPrice(display.amountPennies)}
      </p>
      <p className="mt-2 text-sm text-slate-600 [overflow-wrap:anywhere]">{`Order ${display.orderNumber}`}</p>
    </>
  )
}

export default PayClient

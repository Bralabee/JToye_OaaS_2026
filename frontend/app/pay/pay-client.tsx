"use client"

import { useCallback, useEffect, useRef, useState, type ComponentType, type RefObject } from "react"
import { AlertTriangle, CheckCircle2, Hourglass, Link2, Loader2 } from "lucide-react"
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
 *      never the token — so a reload before paying remounts the form instead of stranding the
 *      customer (PR #1018 review finding 5). Tab-scoped history state, removed on payment received,
 *      on already paid and when the intent cannot be read (T-30-129).
 *
 * A direct-charge intent lives on the vendor's connected account, so Stripe.js is initialised with
 * `stripeAccount` when redeem (or the resume entry, or Stripe's return `acct`) names one (plan 30-17,
 * T-30-76). Card entry itself is CardPaymentForm, shared with the checkout.
 *
 * Every state is chosen from the HTTP status and the preview's `state` enum, never from the server's
 * English `detail` (T-30-75), and every refused-link state renders constant copy only.
 *
 * Mount decides the mode, in this order: a `#token=` fragment (a link), Stripe's redirect query
 * (`payment_intent_client_secret` / `redirect_status`, the return from a redirect-based method), a
 * resume entry in history.state (a reload after redeem), else the "Open your payment link" state.
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
  tryAgain: "Try again",
  checkFailedHeading: "Pay for your order",
  checkFailed: "We couldn't check this link just now. Please try again.",
  notUsed: "Card payment isn't available right now. Your link hasn't been used, so you can try again later.",
  linkOpened:
    "We couldn't load the card form, and this link has now been opened. Ask the shop to send you a new link. Nothing has been charged.",
  received: "Payment received",
  closeThisPage: "You can close this page.",
  processing: "Payment processing",
  processingTail: "You don't need to do anything else.",
  alreadyPaid: "This order is already paid",
  dead: {
    used: {
      heading: "This payment link has already been opened",
      body: "Each link opens once. If you haven't finished paying, ask the shop to send you a new link.",
    },
    gone: {
      heading: "This payment link no longer works",
      body: "It has expired, or the shop has sent you a newer one. Use the newest link you were sent, or ask the shop for a new one.",
    },
    notFound: {
      heading: "We can't find this payment link",
      body: "Check you opened the whole link from your message. If it still doesn't work, ask the shop to send it again.",
    },
  },
  redirectFailed: {
    heading: "Your payment didn't go through",
    body: "Nothing has been charged. Ask the shop to send you a new link to try again.",
  },
  noLink: {
    heading: "Open your payment link",
    body: "To pay, open the link the shop sent you by text or message.",
  },
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

type DeadLink = "used" | "gone" | "notFound"

type Phase =
  | { kind: "checking" }
  | { kind: "checkFailed" }
  | { kind: "active"; preview: PaymentLinkPreview; notUsed?: boolean }
  | { kind: "opening"; preview: PaymentLinkPreview }
  | { kind: "preparing"; display: Display }
  | { kind: "paying"; display: Display; stripe: StripePromise; clientSecret: string; stripeAccountId: string | null }
  | { kind: "linkOpened"; display: Display }
  | { kind: "dead"; which: DeadLink }
  | { kind: "alreadyPaid"; orderNumber: string | null }
  | { kind: "processing"; amountPennies: number | null }
  | { kind: "received"; display: Display | null; amountPennies: number | null }
  | { kind: "redirectFailed" }
  | { kind: "noLink" }

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

function isResumeEntry(data: unknown): data is ResumeEntry {
  if (!data || typeof data !== "object") return false
  const e = data as Record<string, unknown>
  return (
    typeof e.clientSecret === "string" &&
    e.clientSecret.length > 0 &&
    (e.stripeAccountId === null || typeof e.stripeAccountId === "string") &&
    typeof e.shopName === "string" &&
    typeof e.purpose === "string" &&
    PURPOSES.includes(e.purpose) &&
    typeof e.amountPennies === "number" &&
    typeof e.orderNumber === "string"
  )
}

/** An axios rejection's HTTP status; undefined for a failure that is not an HTTP answer. */
function statusOf(err: unknown): number | undefined {
  if (err && typeof err === "object" && "response" in err) {
    const status = (err as { response?: { status?: unknown } }).response?.status
    return typeof status === "number" ? status : undefined
  }
  return undefined
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
const ALERT = "mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900"
const CTA =
  "inline-flex h-12 w-full items-center justify-center gap-2 rounded-lg bg-oxblood px-5 text-sm font-semibold text-white transition-colors hover:bg-oxblood-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700 disabled:cursor-not-allowed disabled:opacity-70"
const RETRY =
  "mt-4 inline-flex h-11 w-full items-center justify-center rounded-lg border border-slate-300 bg-white px-5 text-sm font-semibold text-slate-900 transition-colors hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700"

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

  /** Step 1: the read-only preview. 404 is its own state; any other failure offers a retry only. */
  const runPreview = useCallback(async () => {
    setPhase({ kind: "checking" })
    try {
      const res = await publicApiClient.post<PaymentLinkPreview>(PREVIEW_PATH, { token: tokenRef.current })
      const preview = res.data
      if (!isPreview(preview)) {
        // An answer this page does not understand is never read as a payable link.
        setPhase({ kind: "checkFailed" })
      } else if (preview.state === "ACTIVE") {
        setPhase({ kind: "active", preview })
      } else if (preview.state === "PAID") {
        setPhase({ kind: "alreadyPaid", orderNumber: preview.orderNumber })
      } else if (preview.state === "USED") {
        setPhase({ kind: "dead", which: "used" })
      } else {
        // EXPIRED and REVOKED are one state, as the redeem's 410 is.
        setPhase({ kind: "dead", which: "gone" })
      }
    } catch (err: unknown) {
      const status = statusOf(err)
      if (status === 404) setPhase({ kind: "dead", which: "notFound" })
      else if (status === 409) setPhase({ kind: "dead", which: "used" })
      else if (status === 410) setPhase({ kind: "dead", which: "gone" })
      else setPhase({ kind: "checkFailed" })
    }
  }, [])

  /**
   * Step 2d onward (and the reload resume): Stripe on the intent's account, the intent's own status,
   * then the card form. `fromResume` decides what an unreadable intent means: after a redeem the link
   * is spent ("link opened"); on a reload the entry is simply dropped and the page asks for the link.
   */
  const mountIntent = useCallback(
    async (display: Display, clientSecret: string, stripeAccountId: string | null, fromResume: boolean) => {
      setPhase({ kind: "preparing", display })
      const promise = loadStripeOnce(stripeAccountId ?? undefined)
      let stripe: Awaited<StripePromise> = null
      try {
        stripe = promise ? await promise : null
      } catch {
        stripe = null
      }
      if (!stripe || !promise) {
        // The entry is kept: a reload, once Stripe.js can load, finds the intent again.
        setPhase({ kind: "linkOpened", display })
        return
      }
      let status: string | undefined
      try {
        const { paymentIntent } = await stripe.retrievePaymentIntent(clientSecret)
        status = paymentIntent?.status
      } catch {
        status = undefined
      }
      if (!status) {
        clearResume()
        setPhase(fromResume ? { kind: "noLink" } : { kind: "linkOpened", display })
        return
      }
      if (status === "succeeded") {
        clearResume()
        setPhase({ kind: "alreadyPaid", orderNumber: display.orderNumber })
        return
      }
      if (status === "processing") {
        setPhase({ kind: "processing", amountPennies: display.amountPennies })
        return
      }
      setPhase({ kind: "paying", display, stripe: promise, clientSecret, stripeAccountId })
    },
    []
  )

  /**
   * Stripe's return from a redirect-based method (UI-SPEC § S3 step 4). The intent's own status
   * decides; `redirect_status` is read only when the intent cannot be (no key, Stripe.js down).
   */
  const runReturn = useCallback(async (clientSecret: string | null, redirectStatus: string | null, acct: string | null) => {
    setPhase({ kind: "checking" })
    let status: string | null = null
    let amount: number | null = null
    if (clientSecret) {
      try {
        const stripe = await (loadStripeOnce(acct ?? undefined) ?? Promise.resolve(null))
        if (stripe) {
          const { paymentIntent } = await stripe.retrievePaymentIntent(clientSecret)
          status = paymentIntent?.status ?? null
          amount = typeof paymentIntent?.amount === "number" ? paymentIntent.amount : null
        }
      } catch {
        status = null
      }
    }
    const outcome = status ?? redirectStatus
    if (outcome === "succeeded") setPhase({ kind: "received", display: null, amountPennies: amount })
    else if (outcome === "processing") setPhase({ kind: "processing", amountPennies: amount })
    else setPhase({ kind: "redirectFailed" })
  }, [])

  /** Mount: which of the four ways in this is (see the header). */
  const start = useCallback(() => {
    tokenRef.current = readToken()
    if (tokenRef.current) {
      runPreview()
      return
    }
    const query = new URLSearchParams(window.location.search)
    const clientSecret = query.get("payment_intent_client_secret")
    const redirectStatus = query.get("redirect_status")
    if (clientSecret || redirectStatus) {
      runReturn(clientSecret, redirectStatus, query.get("acct") || null)
      return
    }
    const entry = currentState()[PAY_RESUME_KEY]
    if (isResumeEntry(entry)) {
      const { clientSecret: secret, stripeAccountId, ...display } = entry
      mountIntent(display, secret, stripeAccountId || null, true)
      return
    }
    setPhase({ kind: "noLink" })
  }, [runPreview, runReturn, mountIntent])

  useEffect(() => {
    // Under Strict Mode (next dev) this effect runs twice; the link is read and previewed once.
    if (started.current) return
    started.current = true
    // The fragment and history.state exist only in the browser, so the state is chosen after mount.
    start()
  }, [start])

  /** The press (UI-SPEC § S3 step 2). The ONLY caller of redeem. */
  async function continueToPayment(preview: PaymentLinkPreview) {
    if (inFlight.current) return
    inFlight.current = true
    setPhase({ kind: "opening", preview })
    try {
      // 2a-2b: no key, or Stripe.js does not load -> the link is NOT spent.
      if (publishableKey() === null) {
        setPhase({ kind: "active", preview, notUsed: true })
        return
      }
      let platform: Awaited<StripePromise> = null
      try {
        platform = await (loadStripeOnce() ?? Promise.resolve(null))
      } catch {
        platform = null
      }
      if (!platform) {
        setPhase({ kind: "active", preview, notUsed: true })
        return
      }

      const display: Display = {
        shopName: preview.shopName,
        purpose: preview.purpose,
        amountPennies: preview.amountPennies,
        orderNumber: preview.orderNumber,
      }

      // 2c: spend the link.
      let redeemed: RedeemedPaymentLink
      try {
        redeemed = (await publicApiClient.post<RedeemedPaymentLink>(REDEEM_PATH, { token: tokenRef.current })).data
      } catch (err: unknown) {
        const status = statusOf(err)
        if (status === 409) setPhase({ kind: "dead", which: "used" })
        else if (status === 410) setPhase({ kind: "dead", which: "gone" })
        else if (status === 404) setPhase({ kind: "dead", which: "notFound" })
        // 502, another 5xx, or no answer at all: the server may have spent the link, so say so.
        else setPhase({ kind: "linkOpened", display })
        return
      }
      if (!redeemed || typeof redeemed.clientSecret !== "string" || !redeemed.clientSecret) {
        setPhase({ kind: "linkOpened", display })
        return
      }
      // The link is spent; the token has no further use on this page.
      tokenRef.current = null
      if (typeof redeemed.amountPennies === "number") display.amountPennies = redeemed.amountPennies

      const stripeAccountId =
        typeof redeemed.stripeAccountId === "string" && redeemed.stripeAccountId ? redeemed.stripeAccountId : null
      const entry: ResumeEntry = { clientSecret: redeemed.clientSecret, stripeAccountId, ...display }
      // 2d: the token leaves the address bar before Stripe mounts. Next's router keys are kept.
      window.history.replaceState({ ...currentState(), [PAY_RESUME_KEY]: entry }, "", "/pay")
      await mountIntent(display, redeemed.clientSecret, stripeAccountId, false)
    } finally {
      inFlight.current = false
    }
  }

  function paid(display: Display) {
    clearResume()
    setPhase({ kind: "received", display, amountPennies: display.amountPennies })
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

        {phase.kind === "checkFailed" && (
          <>
            <h1 ref={headingRef} tabIndex={-1} className={H1}>
              {PAY_PAGE_COPY.checkFailedHeading}
            </h1>
            <p role="alert" className={ALERT}>
              {PAY_PAGE_COPY.checkFailed}
            </p>
            <button type="button" onClick={() => runPreview()} className={RETRY}>
              {PAY_PAGE_COPY.tryAgain}
            </button>
          </>
        )}

        {(phase.kind === "active" || phase.kind === "opening") && (
          <>
            <AmountBlock display={phase.preview} headingRef={headingRef} />
            {formatExpiry(phase.preview.expiresAt) && (
              <p className="mt-1 text-xs text-slate-600">{`This link expires ${formatExpiry(phase.preview.expiresAt)}`}</p>
            )}
            <p className="mt-4 text-sm text-slate-600">{PAY_PAGE_COPY.singleUse}</p>
            {phase.kind === "active" && phase.notUsed ? (
              <>
                <p role="alert" className={ALERT}>
                  {PAY_PAGE_COPY.notUsed}
                </p>
                <button type="button" onClick={() => continueToPayment(phase.preview)} className={RETRY}>
                  {PAY_PAGE_COPY.tryAgain}
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={() => continueToPayment(phase.preview)}
                disabled={phase.kind === "opening"}
                className={`mt-6 ${CTA}`}
              >
                {phase.kind === "opening" && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
                {phase.kind === "opening" ? PAY_PAGE_COPY.opening : PAY_PAGE_COPY.continue}
              </button>
            )}
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
                  onLoadError={() => setPhase({ kind: "linkOpened", display: phase.display })}
                />
              </Elements>
            </div>
          </>
        )}

        {phase.kind === "linkOpened" && (
          <>
            <AmountBlock display={phase.display} headingRef={headingRef} />
            <p role="alert" className={ALERT}>
              {PAY_PAGE_COPY.linkOpened}
            </p>
          </>
        )}

        {phase.kind === "dead" && (
          <Outcome
            icon={AlertTriangle}
            tone="amber"
            heading={PAY_PAGE_COPY.dead[phase.which].heading}
            body={PAY_PAGE_COPY.dead[phase.which].body}
            headingRef={headingRef}
          />
        )}

        {phase.kind === "alreadyPaid" && (
          <Outcome
            icon={CheckCircle2}
            tone="neutral"
            heading={PAY_PAGE_COPY.alreadyPaid}
            body={
              phase.orderNumber
                ? `There's nothing more to pay for order ${phase.orderNumber}. ${PAY_PAGE_COPY.closeThisPage}`
                : `There's nothing more to pay. ${PAY_PAGE_COPY.closeThisPage}`
            }
            headingRef={headingRef}
          />
        )}

        {phase.kind === "processing" && (
          <Outcome
            icon={Hourglass}
            tone="neutral"
            heading={PAY_PAGE_COPY.processing}
            body={
              phase.amountPennies !== null
                ? `Your payment of ${formatPrice(phase.amountPennies)} is being processed. ${PAY_PAGE_COPY.processingTail}`
                : `Your payment is being processed. ${PAY_PAGE_COPY.processingTail}`
            }
            headingRef={headingRef}
          />
        )}

        {phase.kind === "received" && (
          <Outcome
            icon={CheckCircle2}
            tone="success"
            heading={PAY_PAGE_COPY.received}
            body={receivedBody(phase.display, phase.amountPennies)}
            headingRef={headingRef}
          />
        )}

        {phase.kind === "redirectFailed" && (
          <Outcome
            icon={AlertTriangle}
            tone="amber"
            heading={PAY_PAGE_COPY.redirectFailed.heading}
            body={PAY_PAGE_COPY.redirectFailed.body}
            headingRef={headingRef}
          />
        )}

        {phase.kind === "noLink" && (
          <Outcome
            icon={Link2}
            tone="neutral"
            heading={PAY_PAGE_COPY.noLink.heading}
            body={PAY_PAGE_COPY.noLink.body}
            headingRef={headingRef}
          />
        )}
      </div>
    </div>
  )
}

/**
 * "Payment received" body. After an in-page payment the page knows the shop and order; after Stripe's
 * redirect return it knows only what the intent says (its amount), so it says no more than that.
 */
function receivedBody(display: Display | null, amountPennies: number | null): string {
  if (display) {
    return `You've paid ${formatPrice(display.amountPennies)} to ${display.shopName} for order ${display.orderNumber}. ${PAY_PAGE_COPY.closeThisPage}`
  }
  if (amountPennies !== null) return `You've paid ${formatPrice(amountPennies)}. ${PAY_PAGE_COPY.closeThisPage}`
  return PAY_PAGE_COPY.closeThisPage
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

const TONES = {
  amber: { ring: "bg-amber-100", icon: "text-amber-700" },
  success: { ring: "bg-emerald-50", icon: "text-emerald-700" },
  neutral: { ring: "bg-slate-100", icon: "text-slate-600" },
} as const

/**
 * A terminal state: an icon, the state's h1 and one body line. Given constant copy (and the page's
 * own amount or order number) only — there is no prop through which a server `detail` could reach it.
 */
function Outcome({
  icon: Icon,
  tone,
  heading,
  body,
  headingRef,
}: {
  icon: ComponentType<{ className?: string; "aria-hidden"?: boolean | "true" | "false" }>
  tone: keyof typeof TONES
  heading: string
  body: string
  headingRef: RefObject<HTMLHeadingElement | null>
}) {
  return (
    <div className="flex flex-col py-2">
      <span className={`mb-4 flex h-12 w-12 items-center justify-center rounded-full ${TONES[tone].ring}`}>
        <Icon className={`h-6 w-6 ${TONES[tone].icon}`} aria-hidden="true" />
      </span>
      <h1 ref={headingRef} tabIndex={-1} className={H1}>
        {heading}
      </h1>
      <p className={BODY}>{body}</p>
    </div>
  )
}

export default PayClient

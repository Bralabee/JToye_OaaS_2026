/**
 * The ONE place the browser reads the Stripe publishable key and loads Stripe.js (issue #957).
 *
 * WHY THIS MODULE EXISTS
 *   The staging card checkout never showed a payment step: the frontend image was built with no
 *   NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY (no Dockerfile ARG, no CI build-arg), so `next build` never
 *   inlined it, the page's in-line `getStripe()` read nothing and returned null, and the render
 *   fell back to the form after the server had already created an unpaid DRAFT. The build channel
 *   is fixed in frontend/Dockerfile + ci-cd.yaml; this is the read side.
 *
 * TWO RULES THE BUILD CHANNEL DEPENDS ON
 *   1. The key is read through the LITERAL member expression
 *      `process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`. Next inlines only that literal form, and
 *      k8s/scripts/check-env-contract.sh discovers the frontend's read set by the same literal, so
 *      a computed `process.env[name]` would be neither inlined nor seen by the gate.
 *   2. It is read INSIDE publishableKey(), at call time. In a built bundle the literal is already
 *      replaced by the inlined string, so nothing changes there; in Jest it lets a test set the key
 *      per case.
 *
 * #793 (lazy load) is preserved: loadStripe comes from the `/pure` entry, which injects nothing
 * until it is CALLED, and nothing here calls it at import time. The default `@stripe/stripe-js`
 * entry injects js.stripe.com as a module side effect and must never be imported.
 */
import { loadStripe } from "@stripe/stripe-js/pure"

/** What `loadStripe` returns. Derived from the `/pure` export so no default-entry import is needed for the type. */
export type StripePromise = ReturnType<typeof loadStripe>

/**
 * The publishable key baked into this bundle, or null when there is none. An image built without
 * the build-arg inlines "" (the Dockerfile declares the ARG/ENV pair), and "" is "no key", never a
 * key — handing "" to loadStripe would fail inside Stripe.js, far from the cause.
 */
export function publishableKey(): string | null {
  const key = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  return key ? key : null
}

/** One load per connected account ("" = the platform account). */
const loads = new Map<string, StripePromise>()

/**
 * Load Stripe.js at most once per connected account and return the shared promise, or null when
 * no publishable key is configured (in which case nothing is loaded and nothing is injected).
 *
 * With no account, loadStripe is called with ONE argument (the platform key) — the #793 suite
 * asserts that exact call. With an account, `{ stripeAccount }` is passed so Elements talks to the
 * connected account a direct charge is created on.
 *
 * A load that REJECTS (js.stripe.com unreachable, CSP refusal) or resolves NULL is forgotten, so a
 * later call — a "Try again" — loads again instead of handing back the same dead promise for the
 * life of the page. A successful load stays memoised.
 */
export function loadStripeOnce(stripeAccount?: string): StripePromise | null {
  const key = publishableKey()
  if (key === null) return null

  const memoKey = stripeAccount ?? ""
  const existing = loads.get(memoKey)
  if (existing) return existing

  const promise = stripeAccount ? loadStripe(key, { stripeAccount }) : loadStripe(key)
  loads.set(memoKey, promise)

  const forget = () => {
    // Only drop THIS load: a reset or a newer load under the same key must survive.
    if (loads.get(memoKey) === promise) loads.delete(memoKey)
  }
  // The handler is attached to a DERIVED promise, so the caller still sees the original rejection;
  // attaching it also means the rejection is never reported as unhandled by this module.
  promise.then((stripe) => {
    if (!stripe) forget()
  }, forget)

  return promise
}

/**
 * The Elements appearance for the payment step. UI-SPEC Color: colorPrimary moves from #f97316 to
 * #c2410c (orange-700, the app's --primary). #f97316 is --ember-bright, which globals.css (#451)
 * declares GRAPHICS ONLY at 2.80:1 on white, while Stripe's "stripe" theme draws the selected
 * payment-method tab's label text in colorPrimary. The checkout page adopts it in plan 30-02.
 */
export const STRIPE_APPEARANCE = {
  theme: "stripe",
  variables: {
    colorPrimary: "#c2410c",
    borderRadius: "12px",
    fontFamily: "system-ui, -apple-system, sans-serif",
  },
} as const

/** Test seam: forget every memoised load. */
export function resetStripeLoaderForTests(): void {
  loads.clear()
}

/**
 * Turn a failed order submission into something a shopper can act on.
 *
 * WHY THIS EXISTS (#409). The checkout previously read only
 * `response.data.detail` — the RFC 7807 field. The rate limiter does not use
 * that shape: it answers `429` with `Retry-After` and a body of
 * `{"error":"Too Many Requests","message":"Rate limit exceeded. Please try
 * again in 19 seconds."}`. So the one genuinely useful sentence the server
 * sent was discarded and the shopper was told:
 *
 *     Failed to place order. Please try again.
 *
 * which invites an IMMEDIATE retry — exactly the action that re-triggers the
 * limit. Measured on the live stack 2026-08-01.
 *
 * Nothing is persisted on a 429 (the interceptor rejects before the
 * controller), so a later retry is safe and the copy says to wait, not to
 * abandon the basket.
 */

/** The error shapes this app actually receives, across both server styles. */
interface ApiErrorLike {
  response?: {
    status?: number
    /** Header lookup is case-insensitive in practice; both forms are read. */
    headers?: Record<string, unknown>
    data?: {
      /** RFC 7807 (GlobalExceptionHandler, and the rate limiter since #413). */
      detail?: string
      /**
       * RFC 7807 extension member (#413) — the wait as a TYPED number, flattened to
       * top level by ProblemDetailJacksonMixin. Preferred over mining it out of prose.
       */
      retryAfterSeconds?: number
      /**
       * The PRE-#413 rate-limiter shape. Retained deliberately: it is what a stale
       * core-java still sends, and this file is the only thing standing between that
       * and a shopper being told to retry immediately.
       */
      message?: string
      error?: string
    }
  }
}

export const GENERIC_ORDER_ERROR = "Failed to place order. Please try again."

/**
 * #876 UXT-103: the request never got an answer. That is the one failure where the order MAY exist (the
 * request can have reached the server and only the reply been lost), so "Failed to place order" is the
 * wrong sentence: it invites a second order from someone who may already have one. Says what is known,
 * where to look, and that the basket is untouched. Retrying is still safe on this page, which keeps the
 * same Idempotency-Key for an unchanged basket, but the copy does not lean on that.
 */
export const OFFLINE_ORDER_ERROR =
  "You appear to be offline. Your order may already have been placed, so check My Orders or Track order before you place it again. Your basket is safe."

export const UNREACHABLE_ORDER_ERROR =
  "We couldn't reach the shop, so we can't tell whether your order went through. It may already have been placed: check My Orders or Track order before you place it again. Your basket is safe."

/** An axios failure that produced no HTTP response: a dropped connection, a refused one, a timeout. */
function isNetworkFailure(err: unknown): boolean {
  if (!err || typeof err !== "object") return false
  const { code, message } = err as { code?: unknown; message?: unknown }
  return (
    code === "ERR_NETWORK" ||
    code === "ECONNABORTED" ||
    code === "ETIMEDOUT" ||
    message === "Network Error"
  )
}

/** Read Retry-After regardless of header casing; returns null when unusable. */
export function retryAfterSeconds(err: unknown): number | null {
  const headers = (err as ApiErrorLike)?.response?.headers
  if (!headers) return null
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() !== "retry-after") continue
    // Only the delta-seconds form is produced here. An HTTP-date parses as NaN
    // and must fall through rather than render "try again in NaN seconds".
    //
    // A BLANK header is the trap: `Number("")` is 0, not NaN, so an empty
    // Retry-After would otherwise be quantified as "wait 0 seconds" — advice
    // that is both wrong and useless. Caught by its own test rather than by
    // reading the code. `<= 0` therefore falls through to the vaguer copy.
    const raw = String(value).trim()
    if (raw === "") return null
    const n = Number(raw)
    return Number.isFinite(n) && n > 0 ? Math.ceil(n) : null
  }
  return null
}

/**
 * Recover the wait from the rate limiter's prose body, e.g.
 * "Rate limit exceeded. Please try again in 19 seconds."
 *
 * Deliberately narrow: it must match the limiter's sentence and decline
 * anything else, so an unrelated message can never be mined for a stray number
 * and rendered as a countdown.
 */
export function secondsFromMessage(message?: string): number | null {
  if (!message) return null
  const m = /\bin\s+(\d+(?:\.\d+)?)\s+second/i.exec(message)
  if (!m) return null
  const n = Number(m[1])
  return Number.isFinite(n) && n > 0 ? Math.ceil(n) : null
}

/**
 * The message to show under the Place-order button.
 *
 * Order matters: 429 is handled BEFORE the generic body-message path, because
 * the rate limiter's own sentence ("try again in 19 seconds") is accurate but
 * reads as a suggestion. Pairing it with the header lets the UI say plainly
 * that waiting is required.
 */
export function describeOrderError(err: unknown): string {
  const res = (err as ApiErrorLike)?.response
  if (!res) {
    // UXT-103: no answer at all. A browser that knows it is offline says so; a transport failure
    // that the browser cannot explain still says the order may exist. Anything else that threw
    // without a response (not a transport failure) keeps the generic sentence.
    if (typeof navigator !== "undefined" && navigator.onLine === false) return OFFLINE_ORDER_ERROR
    if (isNetworkFailure(err)) return UNREACHABLE_ORDER_ERROR
    return GENERIC_ORDER_ERROR
  }

  if (res.status === 429) {
    // FOUR sources, most authoritative first. Each one exists because the one
    // before it has been observed to be absent in a real browser.
    //
    // 1. `Retry-After` — correct, and INVISIBLE to JS until #412. It is not a
    //    CORS-safelisted response header and the storefront calls the API
    //    cross-origin, so the browser withheld it unless the server sent
    //    Access-Control-Expose-Headers. Measured 2026-08-01: this branch never ran
    //    in production, while the unit tests passed throughout because they build
    //    the header object directly.
    // 2. `retryAfterSeconds` — the typed RFC 7807 member added by #413. Preferred
    //    over prose because it cannot be broken by rewording the sentence.
    // 3. `detail` — #413 moved the sentence here from `message`.
    // 4. `message` — the pre-#413 shape. RETAINED, not dead code: a stale
    //    core-java still sends it, and this is the only thing standing between
    //    that and a shopper being told to retry immediately (#409).
    const typed = res.data?.retryAfterSeconds
    const wait =
      retryAfterSeconds(err) ??
      (typeof typed === "number" && Number.isFinite(typed) && typed > 0
        ? Math.ceil(typed)
        : null) ??
      secondsFromMessage(res.data?.detail) ??
      secondsFromMessage(res.data?.message)
    if (wait !== null) {
      const unit = wait === 1 ? "second" : "seconds"
      return `Too many requests just now. Please wait ${wait} ${unit} and place your order again — nothing has been charged and your basket is safe.`
    }
    return "Too many requests just now. Please wait a moment and place your order again — nothing has been charged and your basket is safe."
  }

  // RFC 7807 first (the documented contract), then the rate-limit/legacy shape.
  const detail = res.data?.detail?.trim()
  if (detail) return detail
  const message = res.data?.message?.trim()
  if (message) return message

  return GENERIC_ORDER_ERROR
}

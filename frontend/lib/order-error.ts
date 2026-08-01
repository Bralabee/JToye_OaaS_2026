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
      /** RFC 7807 (GlobalExceptionHandler). */
      detail?: string
      /** Non-7807 shape used by RateLimitInterceptor. */
      message?: string
      error?: string
    }
  }
}

export const GENERIC_ORDER_ERROR = "Failed to place order. Please try again."

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
 * The message to show under the Place-order button.
 *
 * Order matters: 429 is handled BEFORE the generic body-message path, because
 * the rate limiter's own sentence ("try again in 19 seconds") is accurate but
 * reads as a suggestion. Pairing it with the header lets the UI say plainly
 * that waiting is required.
 */
export function describeOrderError(err: unknown): string {
  const res = (err as ApiErrorLike)?.response
  if (!res) return GENERIC_ORDER_ERROR

  if (res.status === 429) {
    const wait = retryAfterSeconds(err)
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

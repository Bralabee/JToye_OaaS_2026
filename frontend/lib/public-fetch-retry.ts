/**
 * 429-aware retry helpers for the public storefront surfaces (F-RATE / #88).
 *
 * The public Core API enforces an IP-keyed rate limit (#88) and returns HTTP
 * 429 + Retry-After under load. Each shop-detail view fires ~6 parallel
 * /public/* calls against a burst-20 bucket, so a handful of quick page views
 * can 429 a genuine browsing user. Collapsing a 429 into the authoritative
 * "No shops found" / "Shop not found" empty state is a correctness defect: it
 * tells a real customer the marketplace is empty when it is merely busy.
 *
 * These helpers are deliberately framework-agnostic (no React) so they can be
 * unit-tested in isolation and reused by both storefront pages. They only read
 * the axios error shape already produced by lib/public-api-client.ts (which
 * rejects the raw error unchanged, so `error.response.status` /
 * `error.response.headers` reach the caller's catch).
 */

/** First no-header retry waits this long (ms); doubles each subsequent attempt. */
export const BASE_DELAY_MS = 800

/** Hard ceiling (ms) on any single retry wait, incl. an over-large Retry-After. */
export const MAX_DELAY_MS = 10_000

/**
 * Bounded retry budget. A tight/unbounded loop would amplify the very flood the
 * limiter is defending against, so the storefront gives up after this many
 * automatic attempts and offers a manual "Try again" instead.
 */
export const MAX_RETRY_ATTEMPTS = 4

/**
 * True only when the error is an HTTP 429 (rate limited). A network error or
 * timeout (no `response`) and any other status is NOT a rate-limit signal — the
 * caller must keep its existing behaviour (e.g. a real empty/not-found state)
 * for those.
 */
export function isRateLimitError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { response?: { status?: number } }).response?.status === 429
  )
}

/**
 * How long to wait before the next retry.
 *
 * Honours the server `Retry-After` header (seconds) when it parses to a
 * positive number, clamped to MAX_DELAY_MS. Otherwise falls back to capped
 * exponential backoff: min(BASE_DELAY_MS * 2**attempt, MAX_DELAY_MS).
 */
export function getRetryDelayMs(error: unknown, attempt: number): number {
  const header = (
    error as { response?: { headers?: Record<string, unknown> } } | null
  )?.response?.headers?.["retry-after"]

  const retryAfterSeconds =
    typeof header === "string" || typeof header === "number"
      ? Number(header)
      : Number.NaN

  if (Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0) {
    return Math.min(retryAfterSeconds * 1000, MAX_DELAY_MS)
  }

  const backoff = BASE_DELAY_MS * 2 ** attempt
  return Math.min(backoff, MAX_DELAY_MS)
}

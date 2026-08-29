/**
 * Shared vendor-dashboard load-error classifier (QA-council F2 · FEB-1 / A11Y-2 / A11Y-8).
 *
 * ROOT CAUSE THIS EXISTS TO FIX. A dashboard list's fetch `catch` block either
 * toasted a transient notification or did nothing, and in both cases fell through
 * to a list that stayed at its initial `[]` — so a 429 or a genuine network
 * failure rendered the EXACT SAME "No products yet" / "No orders yet" empty
 * state as a vendor who truly has zero rows. That is a false claim about the
 * vendor's own data: "the server is busy" and "you have nothing" are different
 * facts and must render differently. The fix is NOT to make the empty state
 * smarter — it is to stop keying the render off list length at all for this
 * case. See the per-page `loadFailed` flag this feeds.
 *
 * THIS FILE DOES NOT CENTRALISE ERROR HANDLING. Each page still owns its own
 * state and its own retry — this only answers one question ("what should the
 * error panel say?") the same way everywhere, so five pages cannot drift into
 * five different definitions of "busy" vs "broken". It deliberately reuses
 * `isRateLimitError` from `lib/public-fetch-retry.ts` rather than re-deriving
 * "is this a 429": that function was proven out against the public storefront's
 * real CORS/header behaviour (see its own docstring), and a second
 * `error.response?.status === 429` here would be a second, driftable copy of
 * the same one-line fact.
 *
 * NOT WIRED INTO `lib/api-client.ts`'s INTERCEPTOR, on purpose. That client
 * deliberately rejects the raw axios error unchanged so callers can read
 * `error.response.status` themselves — centralising the classification there
 * would still require every caller to unwrap it, buying nothing. The public
 * storefront pages call a SEPARATE client (`lib/public-api-client.ts`, no auth
 * header) for an unrelated reason; this helper works against either shape
 * because it only reads the axios error, never the client that produced it.
 */
import { isRateLimitError } from "@/lib/public-fetch-retry"

export interface LoadError {
  /** True when the failure was an HTTP 429 — the server is busy, not the data being empty. */
  isRateLimited: boolean
  /** Short, human-readable copy safe to render directly in an error panel. */
  message: string
}

const RATE_LIMITED_MESSAGE =
  "The server is busy right now. Please wait a moment and try again."

const GENERIC_MESSAGE = "Something went wrong loading this data. Please try again."

interface ApiErrorLike {
  response?: {
    status?: number
    data?: {
      /** RFC 7807 (GlobalExceptionHandler). */
      detail?: string
      /** Pre-RFC-7807 / rate-limiter shape, still sent by a stale core-java. */
      message?: string
    }
  }
}

/**
 * Classifies a caught fetch error for a load-failed panel (or a mutation
 * toast — the RFC 7807 body shape is the same either way). Never swallows an
 * error into silence — an unrecognised shape still gets a message, because a
 * silently-eaten catch is the exact defect class this exists to end.
 *
 * `fallbackMessage` is ONLY used when the error carries no server body and no
 * `Error.message` (e.g. a bare rejected object) — it lets a mutation call site
 * (create/update/transition) say "Failed to create order" instead of the
 * load-page-shaped default, without duplicating the detail/message resolution
 * above it.
 */
export function describeLoadError(
  error: unknown,
  fallbackMessage: string = GENERIC_MESSAGE
): LoadError {
  if (isRateLimitError(error)) {
    return { isRateLimited: true, message: RATE_LIMITED_MESSAGE }
  }

  const response = (error as ApiErrorLike | null)?.response
  if (response) {
    const detail = response.data?.detail?.trim()
    if (detail) return { isRateLimited: false, message: detail }

    const message = response.data?.message?.trim()
    if (message) return { isRateLimited: false, message }

    // A real HTTP error with no readable body. `error.message` on an axios
    // error is its OWN generic string ("Request failed with status code 500")
    // — exactly the raw-axios-string defect (A11Y-2) — so a real HTTP failure
    // with nothing useful in the body prefers the caller's fallback over it.
    return { isRateLimited: false, message: fallbackMessage }
  }

  // No `response` at all: a thrown Error (network failure, or a genuine
  // application throw) whose own `.message` IS the useful information.
  if (error instanceof Error && error.message) {
    return { isRateLimited: false, message: error.message }
  }

  return { isRateLimited: false, message: fallbackMessage }
}

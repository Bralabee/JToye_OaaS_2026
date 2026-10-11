import axios, { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from "axios"
import { getSession } from "next-auth/react"
import type { Session } from "next-auth"
import { isShopAccessDenied, SHOP_ACCESS_DENIED_EVENT } from "@/lib/access-events"

/**
 * Hardened axios instance for the vendor dashboard.
 *
 * Adds on top of the vanilla axios client:
 *   1. Bearer token from the NextAuth session (unchanged).
 *   2. X-Tenant-Id header injected from `session.user.tenantId`, for defence
 *      in depth against broken server-side tenant derivation.
 *   3. Retry on 5xx responses and network errors (max 2 retries, 250ms then
 *      500ms backoff). 4xx is NEVER retried — except 429, below.
 *   3a. Retry a 429 from core-java's rate limiter (problem type
 *      `https://jtoye.uk/errors/rate-limited`) ONCE, when its `Retry-After` is
 *      0 <= N <= 10, after N + 1 seconds (the server floors the wait). Any other
 *      429, no header, a blank or HTTP-date value, or a longer wait rejects
 *      immediately so the caller's own error state renders.
 *   4. 401 handler that triggers a SINGLE concurrent session refresh via
 *      getSession(); parallel 401s wait on the same promise instead of
 *      stampeding to /api/auth/session. If the refreshed session is still
 *      unauthenticated, we redirect to /auth/signin.
 */

const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL,
  headers: {
    "Content-Type": "application/json",
  },
})

// --- Request interceptor: Bearer token + X-Tenant-Id ----------------------

apiClient.interceptors.request.use(
  async (config: InternalAxiosRequestConfig) => {
    const session = await getSession()
    if (session?.accessToken) {
      config.headers.set("Authorization", `Bearer ${session.accessToken}`)
    }
    // Prefer explicit session tenantId; fall back to whatever has already been
    // set on the config by callers that know better.
    const tenantId = session?.user?.tenantId
    if (tenantId && !config.headers.get("X-Tenant-Id")) {
      config.headers.set("X-Tenant-Id", tenantId)
    }
    return config
  },
  (error) => Promise.reject(error)
)

// --- Response interceptor: retry + 401 debounced refresh ------------------

interface RetryConfig extends InternalAxiosRequestConfig {
  _retryCount?: number
  _authRetried?: boolean
  _rateLimitRetried?: boolean
}

const MAX_RETRIES = 2
const RETRY_DELAYS_MS = [250, 500]
// Longest Retry-After (seconds) worth holding a request open for. Past this the
// caller is better served by failing now and rendering its own retry control.
const MAX_RATE_LIMIT_WAIT_S = 10

// The only 429 this client replays: the one RateLimitInterceptor writes from
// `preHandle`, before any controller runs. Other 429s exist (the DSAR intake's
// is raised from a service, via GlobalExceptionHandler) and may follow a write,
// so they are never replayed — the safety argument is checked, not assumed.
const RATE_LIMITED_TYPE = "https://jtoye.uk/errors/rate-limited"

// The server's `Retry-After` in whole seconds, or null when it must not be
// replayed. Not `retryAfterSeconds` from order-error: that one serves
// user-facing copy and rightly drops 0, but RateLimitInterceptor FLOORS the
// refill wait (`getNanosToWaitForRefill() / 1_000_000_000`), so the true wait
// lies in [N, N + 1) and every sub-second wait arrives as `Retry-After: 0`. The
// caller therefore waits N + 1 s; replaying at exactly N s lands early and meets
// a second 429. A missing, blank, negative or HTTP-date value means "do not retry".
function rateLimitWaitSeconds(error: AxiosError): number | null {
  const headers = error.response?.headers
  if (!headers) return null
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() !== "retry-after") continue
    const raw = String(value).trim()
    if (raw === "") return null
    const n = Number(raw)
    if (!Number.isFinite(n) || n < 0) return null
    return Math.ceil(n)
  }
  return null
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

// Module-level singleton: concurrent 401s coalesce onto this promise so we
// only hit /api/auth/session once per stampede.
let refreshPromise: Promise<Session | null> | null = null
function refreshSessionOnce(): Promise<Session | null> {
  if (!refreshPromise) {
    refreshPromise = getSession().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as RetryConfig | undefined
    const status = error.response?.status

    // --- 5xx / network retry --------------------------------------------
    const isServerError = !status || (status >= 500 && status < 600)
    if (config && isServerError) {
      config._retryCount = config._retryCount ?? 0
      if (config._retryCount < MAX_RETRIES) {
        const delay = RETRY_DELAYS_MS[config._retryCount] ?? 500
        config._retryCount += 1
        await sleep(delay)
        return apiClient.request(config)
      }
    }

    // --- 429: one retry after Retry-After ---------------------------------
    // core-java's RateLimitInterceptor answers 429 from `preHandle`, before any
    // controller runs, so a rejected request — POST included — had no side
    // effects and replaying it is safe. The bucket is per TENANT, so a burst
    // from one vendor's tabs (or one serial E2E run) drains it for everyone on
    // that tenant; a short wait is usually all it takes. ONE retry only
    // (`_rateLimitRetried`): a second 429 rejects, so this can never become a
    // loop that amplifies the flood the limiter is defending against.
    const problemType = (error.response?.data as { type?: unknown } | undefined)?.type
    if (status === 429 && config && !config._rateLimitRetried && problemType === RATE_LIMITED_TYPE) {
      const waitS = rateLimitWaitSeconds(error)
      if (waitS !== null && waitS <= MAX_RATE_LIMIT_WAIT_S) {
        config._rateLimitRetried = true
        await sleep((waitS + 1) * 1000)
        return apiClient.request(config)
      }
    }

    // --- 401 debounced refresh ------------------------------------------
    if (status === 401 && config && !config._authRetried) {
      config._authRetried = true
      const refreshed = await refreshSessionOnce()
      if (refreshed?.accessToken) {
        // Retry the original request with the fresh token
        config.headers = config.headers ?? {}
        ;(config.headers as unknown as { set: (k: string, v: string) => void }).set?.(
          "Authorization",
          `Bearer ${refreshed.accessToken}`
        )
        return apiClient.request(config)
      }
      // Still no session — bounce to signin
      if (typeof window !== "undefined") {
        window.location.href = "/auth/signin"
      }
    }

    // D-08 (37-06): tell the dashboard shell a read was refused, so it can re-read
    // staff/me and show the no-access page if nothing is left. The request still
    // rejects; the caller keeps its own handling of the refusal.
    if (isShopAccessDenied(status, problemType) && typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent(SHOP_ACCESS_DENIED_EVENT))
    }

    return Promise.reject(error)
  }
)

// Expose for tests — not part of the public contract
export const __testing = {
  resetRefresh: () => {
    refreshPromise = null
  },
}

export default apiClient
export type { AxiosRequestConfig }

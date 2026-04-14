import axios, { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from "axios"
import { getSession } from "next-auth/react"
import type { Session } from "next-auth"

/**
 * Hardened axios instance for the vendor dashboard.
 *
 * Adds on top of the vanilla axios client:
 *   1. Bearer token from the NextAuth session (unchanged).
 *   2. X-Tenant-Id header injected from `session.user.tenantId`, for defence
 *      in depth against broken server-side tenant derivation.
 *   3. Retry on 5xx responses and network errors (max 2 retries, 250ms then
 *      500ms backoff). 4xx is NEVER retried.
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
}

const MAX_RETRIES = 2
const RETRY_DELAYS_MS = [250, 500]

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

"use client"

import { useCallback, useEffect, useState } from "react"
import { getCustomerSession, type CustomerProfile } from "@/lib/customer-auth"

/**
 * The one place customer session state is read for UI purposes (issue #457).
 *
 * This logic lived inline in StorefrontNav, which is mounted only by
 * app/shop/layout.tsx. Every other public surface — `/`, `/track` and the
 * marketing pages, all rendered through PublicShell -> PublicHeader — had no
 * session awareness at all, so a signed-in customer was shown a "Sign in" button
 * the moment they went home. Measured: the session was intact the whole time;
 * only the header could not see it.
 *
 * Extracting it rather than copying it is the point. Two independent readers is
 * how this class of bug comes back, so PublicHeader and StorefrontNav share this
 * hook and neither calls getCustomerSession() directly.
 *
 * Deliberately built on the ASYNC getCustomerSession() (server truth) rather than
 * the synchronous isLoggedIn() marker: the marker carries the access-token expiry
 * and only getCustomerSession() re-stamps it, so a marker-only reader on a public
 * surface goes stale and then lies.
 */
export function useCustomerSession() {
  const [profile, setProfile] = useState<CustomerProfile | null>(null)

  const checkSession = useCallback(async () => {
    const session = await getCustomerSession()
    setProfile(session?.profile || null)
  }, [])

  useEffect(() => {
    // Check on mount
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration; refactor tracked in issue #99 follow-up
    checkSession()

    // Re-check when page gains focus (covers OAuth redirect return)
    const onFocus = () => checkSession()
    const onVisibility = () => {
      if (document.visibilityState === "visible") checkSession()
    }
    // Re-check on storage changes (covers cross-tab login via marker)
    const onStorage = (e: StorageEvent) => {
      if (e.key === "jtoye-customer-logged-in" || e.key === "jtoye-customer-expires-at") {
        checkSession()
      }
    }

    window.addEventListener("focus", onFocus)
    document.addEventListener("visibilitychange", onVisibility)
    window.addEventListener("storage", onStorage)

    // Also poll briefly after mount to catch the redirect scenario
    // (OAuth callback sets localStorage then redirects — same tab, no storage event)
    const timer = setInterval(checkSession, 1000)
    const cleanup = setTimeout(() => clearInterval(timer), 5000) // Stop polling after 5s

    return () => {
      window.removeEventListener("focus", onFocus)
      document.removeEventListener("visibilitychange", onVisibility)
      window.removeEventListener("storage", onStorage)
      clearInterval(timer)
      clearTimeout(cleanup)
    }
  }, [checkSession])

  return { profile, refresh: checkSession }
}

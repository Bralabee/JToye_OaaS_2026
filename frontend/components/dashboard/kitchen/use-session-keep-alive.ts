"use client"

import { useEffect } from "react"
import { getSession } from "next-auth/react"

/**
 * 37-18 (D-14): while the SSO session lasts, the kitchen board refreshes its access token before
 * it expires, so the board-stopped overlay means a real lapse and never a routine 5-minute
 * access-token expiry. Realm session lifetimes are NOT changed (D-14).
 *
 * HOW. The client session carries the access token's `expiresAt` (epoch seconds, lib/
 * session-callback.ts). The board asks for the session again {@link SESSION_REFRESH_LEAD_MS}
 * before that moment; the NextAuth `jwt` callback refreshes a token inside its last minute
 * (`accessTokenNeedsRefresh`), so the read comes back with a new token and a new `expiresAt`, and
 * the next refresh is scheduled from it.
 *
 * WHAT IT DOES NOT DO. It never decides that the session has lapsed. A `getSession()` that comes
 * back empty may only mean the network blinked (next-auth returns null on a failed fetch), and a
 * kitchen screen that turns red because the Wi-Fi hiccupped teaches the kitchen to ignore it. The
 * authority on a lapse is the API's 401 that the api-client's one refresh cannot fix.
 *
 * MEASURED LIMIT, recorded rather than assumed: every board read already calls `getSession()` in
 * the api-client's request interceptor, and the board polls once a minute, so while the board is
 * reading this timer is a second path to the same refresh, not the only one. It is the one that
 * does not depend on a read happening to land inside the token's last minute.
 */

/** How long before the access token's expiry the board asks for a fresh one. */
export const SESSION_REFRESH_LEAD_MS = 60_000

/**
 * The shortest wait between two asks. An `expiresAt` already inside the lead (a refresh that
 * failed and left the old expiry in place) must not turn this into a tight loop.
 */
export const SESSION_REFRESH_MIN_DELAY_MS = 30_000

/** When the session carries no `expiresAt` (a cookie minted before 37-18), ask again after this. */
export const SESSION_REFRESH_FALLBACK_MS = 60_000

/** How long to wait before asking again, given the token's expiry (epoch seconds) and "now". */
export function nextSessionRefreshDelay(expiresAt: number | undefined, nowMs: number): number {
  if (!expiresAt) return SESSION_REFRESH_FALLBACK_MS
  return Math.max(SESSION_REFRESH_MIN_DELAY_MS, expiresAt * 1000 - SESSION_REFRESH_LEAD_MS - nowMs)
}

/** Keep the board's token fresh while `enabled` (the board is mounted and has not stopped). */
export function useSessionKeepAlive(enabled: boolean): void {
  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const askAndSchedule = async () => {
      let expiresAt: number | undefined
      try {
        const session = await getSession()
        expiresAt = session?.expiresAt
      } catch {
        expiresAt = undefined
      }
      if (cancelled) return
      timer = setTimeout(askAndSchedule, nextSessionRefreshDelay(expiresAt, Date.now()))
    }

    void askAndSchedule()
    return () => {
      cancelled = true
      if (timer !== undefined) clearTimeout(timer)
    }
  }, [enabled])
}

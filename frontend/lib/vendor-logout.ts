"use client"

import { signOut } from "next-auth/react"
import { fetchWithTimeout } from "@/lib/customer-auth"

/**
 * Vendor sign-out that actually ends the session — R-01 (2026-08-31
 * customer-surface audit, **P0**).
 *
 * WHAT WAS WRONG. `components/dashboard/sidebar.tsx` and
 * `components/dashboard/mobile-tab-bar.tsx` both called a bare
 * `signOut({ callbackUrl: "/auth/signin" })`. That clears the NextAuth cookie
 * and NOTHING else: every Keycloak SSO cookie survives, so a single click on
 * "Sign in with Keycloak" walked straight back into the dashboard as the
 * departed user with no credential prompt. On a shared device that is an
 * account takeover.
 *
 * ── ORDERING IS LOAD-BEARING ────────────────────────────────────────────────
 * It mirrors the customer path (`customerLogout`) and each step has to be where
 * it is:
 *
 *   1. FETCH the end-session URL first, WHILE the session still exists — the
 *      route reads the id_token off it, so doing this after `signOut` would
 *      return an app path and quietly skip the IdP half.
 *   2. `signOut({ redirect: false })`. `redirect: false` because the navigation
 *      we want is to Keycloak, not to `/auth/signin`. Letting NextAuth navigate
 *      is exactly what abandoned the IdP half before this change.
 *   3. Navigate the browser to the end-session URL.
 *
 * Steps 2 and 3 sit in a `finally`: a failed or slow URL lookup must still sign
 * the vendor out locally and still land them on `/auth/signin`. A sign-out that
 * silently does nothing because a lookup 500'd is the same defect one step
 * milder.
 *
 * ── WHY IT RETURNS THE URL ──────────────────────────────────────────────────
 * Not decoration. jsdom refuses to navigate, reports it through the virtual
 * console and leaves `location.href` unchanged, so the return value is the unit
 * tests' only honest handle on the navigation. A test-only injected navigator
 * parameter was considered and rejected: it would let the tested path differ
 * from the shipped one.
 *
 * ── RESIDUAL, STATED RATHER THAN IMPLIED (T-QF-03) ──────────────────────────
 * This is FRONT-CHANNEL logout only. If the browser abandons the navigation the
 * IdP session survives. The strictly stronger form is a back-channel revoke —
 * what `lib/customer-idp-logout.ts` does for the storefront realm — and it is
 * NOT done here. Recorded as a follow-up; do not read this file as claiming it.
 */

/** How long the end-session lookup may take before we sign out regardless. */
export const VENDOR_LOGOUT_TIMEOUT_MS = 3000

/** Where a vendor lands when there is no end-session URL to navigate to. */
const FALLBACK_DESTINATION = "/auth/signin"

/**
 * Sign the vendor out of the app AND out of Keycloak.
 *
 * @returns the URL the browser was sent to — the Keycloak end-session URL on
 *          the happy path, `/auth/signin` on every degraded one.
 */
export async function vendorLogout(): Promise<string> {
  let destination = FALLBACK_DESTINATION
  try {
    const res = await fetchWithTimeout(
      `/api/vendor-auth/logout-url?redirect=${FALLBACK_DESTINATION}`,
      { credentials: "include", cache: "no-store" },
      VENDOR_LOGOUT_TIMEOUT_MS
    )
    if (res.ok) {
      const data = (await res.json()) as { url?: string }
      if (data?.url) destination = data.url
    }
  } catch {
    /* A broken or stalled IdP lookup must never leave a vendor signed in. */
  } finally {
    try {
      await signOut({ redirect: false })
    } catch {
      /* Even a NextAuth failure must not strand the vendor on the dashboard. */
    }
    if (typeof window !== "undefined") {
      window.location.href = destination
    }
  }
  return destination
}

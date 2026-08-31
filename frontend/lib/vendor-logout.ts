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
 * ── BOTH SLOW STEPS ARE BOUNDED, NOT JUST THE FIRST (CR-01) ─────────────────
 * The first version of this file bounded the LOOKUP and then awaited an
 * UNBOUNDED `signOut()` inside the `finally`. `next-auth/react`'s `signOut`
 * makes two un-timeouted fetches (`/api/auth/csrf`, `/api/auth/signout`), so a
 * stall there meant the `window.location.href` assignment on the next line
 * never ran at all: the vendor stayed on the dashboard with the app session AND
 * every Keycloak SSO cookie alive, and the button gave no feedback. Measured —
 * the promise had not settled after 300 s of virtual time.
 *
 * That is the R-04 defect verbatim ("a request that RESOLVES and a request that
 * REJECTS — and misses the one that actually happens on a phone leaving a wifi
 * cell"), and this file's docblock was already claiming cover for it: "a failed
 * or SLOW URL lookup must still … land them on /auth/signin". The word "slow"
 * was honoured for one of the two steps.
 *
 * Both are now bounded, and the NAVIGATION IS BEYOND THE REACH OF EITHER.
 * Ordering the priorities explicitly, because they are not equal: reaching the
 * Keycloak end-session URL is what closes the P0; dropping the local cookie is
 * a best-effort second, and the redirect back to `/auth/signin` re-evaluates
 * the app session anyway. So a `signOut` that will not answer must never be
 * allowed to hold the navigation hostage.
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
 * Wait for `work`, or give up on it after `ms` — whichever comes first. Never
 * rejects, and never reports which of the two happened, because no caller here
 * would do anything different either way.
 *
 * A PLAIN `setTimeout` RACE, deliberately, and the same reasoning
 * `fetchWithTimeout` records: `AbortSignal.timeout` is not driven by jest fake
 * timers, so the never-settling arm this exists to satisfy would hang for the
 * jest timeout instead of asserting. A plain timer is what fake timers control.
 *
 * It does NOT abort `work`. It cannot — `signOut()` owns its own fetches and
 * exposes no signal. Abandoning it is the whole point: a local cookie drop that
 * will not answer must not hold up the navigation that ends the SSO session.
 */
async function settleWithin(work: unknown, ms: number): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const deadline = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, ms)
  })
  try {
    // Both handlers collapse to `undefined`, so a rejection from `work` is
    // consumed here and can never surface as an unhandled rejection after we
    // have stopped waiting for it.
    await Promise.race([
      Promise.resolve(work).then(
        () => undefined,
        () => undefined
      ),
      deadline,
    ])
  } finally {
    if (timer) clearTimeout(timer)
  }
}

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
      // IN-01: encoded even though today's constant needs no encoding, so the
      // day this destination becomes dynamic it is not a latent injection.
      `/api/vendor-auth/logout-url?redirect=${encodeURIComponent(FALLBACK_DESTINATION)}`,
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
    // CR-01: BOUNDED. `settleWithin` swallows both a rejection and a stall, so
    // neither a NextAuth failure nor a NextAuth silence can strand the vendor
    // on the dashboard with live SSO cookies.
    await settleWithin(signOut({ redirect: false }), VENDOR_LOGOUT_TIMEOUT_MS)
    if (typeof window !== "undefined") {
      window.location.href = destination
    }
  }
  return destination
}

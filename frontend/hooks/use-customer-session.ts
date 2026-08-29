"use client"

import { useSyncExternalStore } from "react"
import {
  getServerSnapshot,
  getSnapshot,
  refresh,
  subscribe,
} from "@/lib/customer-session-store"

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
 * Extracting it rather than copying it is the point. Independent readers are how
 * this class of bug comes back, so PublicHeader, PublicFooter and StorefrontNav
 * share this hook and none of them calls getCustomerSession() directly.
 *
 * Deliberately built on the ASYNC getCustomerSession() (server truth) rather than
 * the synchronous isLoggedIn() marker: the marker carries the access-token expiry
 * and only getCustomerSession() re-stamps it, so a marker-only reader on a public
 * surface goes stale and then lies.
 *
 * The session truth now lives in `lib/customer-session-store` and is read here
 * with `useSyncExternalStore` (plan 34-03). That removed the mount-time
 * `setState`-in-effect and its `react-hooks/set-state-in-effect` suppression
 * (#202 / the `#99 follow-up` marker) — the rule traces into the call graph, so
 * hiding the write in a helper would not have been a fix. The listeners, the
 * post-OAuth poll and the `{ profile, refresh }` contract are unchanged; the
 * three consumers were not touched.
 */
export function useCustomerSession() {
  const profile = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)

  return { profile, refresh }
}

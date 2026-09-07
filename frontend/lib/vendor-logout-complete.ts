import { resolveVendorLogoutCompleteEnabled } from "@/lib/env-validation"

/**
 * FE-1 (QA council 20260902-134741, **Critical**) — the vendor app-session
 * cookie survived "Sign Out", and this module names the return leg that fixes it.
 *
 * `@auth/core/lib/actions/session.js` re-signs and RE-ISSUES the JWT session
 * cookie on every `GET /api/auth/session` (a fresh 30-day expiry each time), and
 * `lib/api-client.ts` awaits `getSession()` in its axios request interceptor —
 * one session GET per API call, ~24 on a single `/dashboard` load. The client
 * `signOut()`'s clearing `Set-Cookie` therefore raced session responses that were
 * already in flight with the still-valid cookie, and whichever landed LAST won.
 * Measured: 9/12 desktop runs re-entered `/dashboard` as the departed vendor with
 * no credential prompt, and the client-side gap was ~31 ms on passing AND failing
 * runs — more client patience cannot beat a response dispatched before the clear.
 *
 * THE FIX clears the session in the response the browser processes LAST. Keycloak
 * is told to return the vendor to `VENDOR_LOGOUT_COMPLETE_PATH`; that route calls
 * the SERVER `signOut` and lands on `/auth/signin`. By then the dashboard document
 * has been destroyed by two navigations (app -> Keycloak -> app), its fetches were
 * cancelled at the first commit, so no session response from it can exist, and
 * `/auth/signin`'s own session GET returns early with no `Set-Cookie` because the
 * cookie is gone. There is no writer left.
 *
 * ── ONE DEFINITION OF THE PATH, AND NO QUERY STRING ────────────────────────
 * `app/api/vendor-auth/logout-url/route.ts` composes the `post_logout_redirect_uri`
 * from this constant; the route itself lives at this path. Two copies of the
 * string is how the two drift. It carries NO query string, deliberately: the
 * realm check is then "does the path match the registered `/*` wildcard", never
 * "does Keycloak's matcher tolerate a query" (plan R2).
 *
 * ── WHY A FLAG (E-5) ────────────────────────────────────────────────────────
 * The URI must be REGISTERED on the vendor client of the target realm. The
 * compose realm's `core-api` client registers `http://localhost:3000/*` with
 * `post.logout.redirect.uris = "+"`, measured 302; the deployed staging and
 * production realms are external and NOT verifiable from this repository. An
 * unregistered URI makes Keycloak answer 400 WITHOUT terminating the SSO session
 * (#504) — strictly worse than the defect this closes. So the leg ships behind
 * `VENDOR_LOGOUT_COMPLETE_ENABLED`: "true" in compose, "false" in every k8s
 * overlay until `scripts/check-keycloak-logout-uri.sh` (run in the deploy job) is
 * green against that environment's realm. Off, the route keeps today's
 * `/auth/signin` landing, so the worst case is the documented defect.
 *
 * READ AT REQUEST TIME, never at module load, for the same reason
 * `logout-url/route.ts` reads its realm base per request: a module-level `const`
 * is frozen before a test can vary it, and the flag is a runtime env.
 */

/** The route Keycloak returns the vendor to. See `app/api/vendor-auth/logout-complete/route.ts`. */
export const VENDOR_LOGOUT_COMPLETE_PATH = "/api/vendor-auth/logout-complete"

/**
 * Where a vendor lands once signed out — the same destination
 * `lib/vendor-logout.ts` falls back to on every degraded path, so a flag flip
 * changes HOW the session is cleared and never WHERE the vendor ends up.
 */
export const VENDOR_SIGNIN_PATH = "/auth/signin"

/** Is the server-side return leg enabled for THIS request? Off unless set. */
export function isVendorLogoutCompleteEnabled(): boolean {
  return resolveVendorLogoutCompleteEnabled(process.env.VENDOR_LOGOUT_COMPLETE_ENABLED)
}

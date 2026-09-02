import { NextResponse } from "next/server"
import { VENDOR_SIGNIN_PATH, isVendorLogoutCompleteEnabled } from "@/lib/vendor-logout-complete"
import { clearVendorSessionInto } from "@/lib/vendor-session-clear"

/**
 * GET /api/vendor-auth/logout-complete
 *
 * FE-1 (QA council 20260902-134741, **Critical**) — Keycloak's
 * `post_logout_redirect_uri` for the vendor realm, and the response in which the
 * vendor's Auth.js session is actually ended.
 *
 * WHY THIS ROUTE EXISTS. `#711` made "Sign Out" end the Keycloak SSO session, and
 * that half works. The APP cookie survived it: `@auth/core` re-issues the JWT
 * session cookie on every `/api/auth/session` GET, the dashboard fires ~24 of
 * those per load, and the client `signOut()`'s clearing `Set-Cookie` lost the
 * race to whichever of them landed last — 9/12 desktop runs re-opened
 * `/dashboard` as the departed vendor with no credential prompt. On a shared
 * device that is an account takeover through the opposite cookie.
 *
 * WHY HERE. This response is processed AFTER two navigations (app -> Keycloak
 * -> here) have destroyed the dashboard document and cancelled its fetches, so
 * no in-flight session GET can exist to re-issue the cookie; the `/auth/signin`
 * page this redirects to then issues its own session GET, finds no cookie, and
 * `@auth/core` returns early with no `Set-Cookie`. The clear is deterministic
 * because there is no writer left — not because anybody waited longer.
 *
 * SHAPE. Mirrors `app/api/customer-auth/logout/route.ts`, which already clears
 * the storefront realm's cookies in its own response: this is the vendor realm
 * getting the same server-side leg. `middleware.ts` excludes `/api`, so there
 * is no CSP nonce path and no middleware cost.
 *
 * FLAG (E-5). Behind `VENDOR_LOGOUT_COMPLETE_ENABLED` — see
 * `lib/vendor-logout-complete.ts`. Off, this route does NOT touch the session
 * and simply lands on `/auth/signin`, which is exactly today's behaviour; the
 * `logout-url` route only ever names this path as the return leg when the flag
 * is on, so an off flag means this route is not on the sign-out path at all.
 *
 * RELATIVE `Location`, DELIBERATELY. `NextResponse.redirect()` demands an
 * absolute URL, and the only origin this handler can see for itself is
 * `nextUrl.origin` — the container's BIND address (`http://0.0.0.0:3000`,
 * measured, unmoved by the Host header; see `lib/public-origin.ts`). A relative
 * `Location` is valid (RFC 7231 §7.1.2) and the browser resolves it against the
 * URL it actually requested, which is the public origin Keycloak just sent it
 * to. No origin has to be guessed.
 *
 * RESIDUAL (plan R3, accepted): a GET route is cross-site reachable, so a
 * hostile page can force a vendor sign-out. Denial of session, not escalation —
 * the same exposure `POST /api/customer-auth/logout` carries.
 */

export const dynamic = "force-dynamic"

/** This response mutates the session; nothing may cache it (same headers as `logout-url`). */
const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  Vary: "Cookie",
} as const

export async function GET(): Promise<NextResponse> {
  const res = new NextResponse(null, {
    status: 302,
    headers: { Location: VENDOR_SIGNIN_PATH, ...NO_STORE_HEADERS },
  })

  if (!isVendorLogoutCompleteEnabled()) {
    // Today's landing, untouched. The worst case of a misjudged rollout is the
    // documented defect, never a vendor stranded on a Keycloak error page.
    return res
  }

  await clearVendorSessionInto(res, "logout-complete")
  return res
}

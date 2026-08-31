import { NextRequest, NextResponse } from "next/server"
import { auth } from "@/auth"
import { resolvePublicOrigin } from "@/lib/public-origin"

/**
 * GET /api/vendor-auth/logout-url?redirect=/auth/signin
 *
 * R-01 (2026-08-31 customer-surface audit, **P0**) — the vendor "Sign Out"
 * control ended the APP session and nothing else.
 *
 * Both dashboard affordances called a bare
 * `signOut({ callbackUrl: "/auth/signin" })`. That drops the NextAuth cookie;
 * every Keycloak SSO cookie survives, so one click on "Sign in with Keycloak"
 * re-entered the dashboard as the departed user with no credential prompt. On a
 * shared device that is an account takeover, not a UX wrinkle. No vendor
 * federated-logout route existed at all — this is it.
 *
 * Structurally this mirrors `app/api/customer-auth/logout-url/route.ts`, which
 * closed the same class of defect for the storefront realm (#504). It differs
 * only where the vendor realm genuinely differs.
 *
 * ── WHY THIS IS A ROUTE HANDLER AND NOT A CLIENT HELPER ─────────────────────
 * Two reasons, both measured:
 *
 *   1. `NEXT_PUBLIC_KEYCLOAK_URL` has NO `ARG`/`ENV` line in `frontend/Dockerfile`
 *      and that is deliberate (Dockerfile:104-108 forbids "tidying" it in): the
 *      absence is what keeps it RUNTIME-resolvable server-side, which is how
 *      compose supplies it. A CLIENT component reading it would get the
 *      build-time inline — an empty string. A SERVER handler reads the live
 *      value.
 *   2. The id token is a server-side secret's neighbour. Building the URL here
 *      keeps the composition in one auditable place.
 *
 * ── THE HOST TRAP, STATED BECAUSE THE SIBLING FILE STATES THE OPPOSITE ──────
 * `KEYCLOAK_ISSUER_INTERNAL` (http://keycloak:8080/realms/jtoye-dev) is the
 * pod-reachable issuer and `auth.ts` correctly uses it for its SERVER-TO-SERVER
 * token refresh. **It must never appear in this URL.** The URL built here is
 * navigated to by the BROWSER, which lives outside the container network and
 * cannot resolve `keycloak:8080` at all. `lib/customer-token-refresh.ts` and
 * `lib/customer-idp-logout.ts` document the internal host as the CORRECT choice
 * for their own server-side calls, so a reader arriving from either would
 * otherwise "fix" this one into a host no browser can reach — turning a working
 * sign-out back into a broken one. Public host here; internal host there.
 */

export const dynamic = "force-dynamic"

/**
 * WR-04 — the response body embeds the caller's raw `id_token`, so it must
 * never be stored by anything.
 *
 * `export const dynamic = "force-dynamic"` governs Next's RENDERING mode, not
 * the emitted `Cache-Control`. Without an explicit header, correctness rested on
 * a framework default plus every intermediary — ingress, any future CDN, a
 * corporate proxy — inferring "do not share this" from a URL that carries no
 * user-varying component. A shared cache keyed on path alone would serve user
 * A's id_token to user B. There is no CDN in front of `/api/*` today, so this is
 * defence in depth rather than a live hole; it is one line, and this is the P0
 * path.
 *
 * `Vary: Cookie` states the actual dependency: the answer is a function of the
 * session cookie, not of the URL.
 *
 * Applied to BOTH exit branches below, so the two cannot drift.
 */
const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  Vary: "Cookie",
} as const

/**
 * The PUBLIC (browser-resolvable) vendor realm base, read at REQUEST time
 * rather than at module load. Both names are listed as REQUIRED in
 * `lib/env-validation.ts`, so the null branch below is defence in depth — but
 * it is also what makes the split-horizon arm in the tests possible, since a
 * module-level `const` would be frozen before a test could vary it.
 */
function keycloakBase(): string | null {
  return (
    process.env.NEXT_PUBLIC_KEYCLOAK_URL ||
    process.env.KEYCLOAK_ISSUER ||
    null
  )
}

/**
 * Restrict the post-logout redirect to a same-origin dashboard path. The value
 * is user-controlled; only accept a relative path beginning with a single "/"
 * (reject protocol-relative "//host", backslash tricks "/\\host", and absolute
 * URLs) so the returned URL can never escape this origin. Falls back to
 * "/auth/signin" — the vendor equivalent of the customer route's "/shop".
 */
function sanitizeRedirect(raw: string | null): string {
  const fallback = "/auth/signin"
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) {
    return fallback
  }
  return raw
}

export async function GET(req: NextRequest) {
  const session = await auth()
  // Already on the session via `buildSession` (lib/session-callback.ts:19), fed
  // by `callbacks.jwt` (auth.ts:77) and preserved across refresh (auth.ts:40).
  // `auth.ts` needs no change for this route to work.
  const idToken = session?.idToken
  const redirect = sanitizeRedirect(req.nextUrl.searchParams.get("redirect"))

  // Injected, never read off the request: `nextUrl.origin` is the server's BIND
  // address inside a container (measured `http://0.0.0.0:3000`, unmoved by the
  // Host header). `resolvePublicOrigin` returns null rather than guessing.
  //
  // RESIDUAL, STATED HERE BECAUSE THIS IS THE P0 PATH (WR-04/WR-07). That
  // resolver's LAST fallback is `req.nextUrl.origin`. Inside a container
  // `isBindAddress` filters it, which is the case it was written for. OUTSIDE
  // one — a non-standalone `next dev`, or any deployment binding a real
  // interface — `nextUrl.origin` does follow the request Host, so with BOTH
  // `APP_PUBLIC_ORIGIN` and `NEXTAUTH_URL` unset an attacker-supplied
  // `Host: evil.example` would reach `post_logout_redirect_uri`, leaving only
  // Keycloak's registered-redirect-URI check — a control in another system —
  // between that and a post-authentication open redirect.
  //
  // Inherited rather than introduced (the customer route consumed this first)
  // and `lib/env-validation.ts` lists NEXTAUTH_URL as REQUIRED, so it is a
  // residual and not a live hole. Narrowing the resolver is deliberately NOT
  // done here: it is shared with the customer route and changing it is a
  // separate, separately-tested change. Recorded so the next reader of the
  // vendor path does not have to rediscover it.
  const origin = resolvePublicOrigin(req)
  const postLogoutRedirectUri = origin ? `${origin}${redirect}` : null

  const base = keycloakBase()
  if (!idToken || !base) {
    // No id token (or no configured realm) — there is no end-session URL to
    // build, so bounce back to the redirect target. With no trustworthy origin
    // the RELATIVE path is strictly safer and equally correct: the browser
    // resolves it against the page it is already on, which is this app.
    return NextResponse.json(
      { url: postLogoutRedirectUri ?? redirect },
      { headers: NO_STORE_HEADERS }
    )
  }

  const params = new URLSearchParams({ id_token_hint: idToken })
  if (postLogoutRedirectUri) {
    params.set("post_logout_redirect_uri", postLogoutRedirectUri)
  }
  // No origin => NO post_logout_redirect_uri, deliberately. The customer
  // route's measured finding applies here unchanged: against the live realm,
  // `logout?id_token_hint=…` with no redirect uri TERMINATES the session and
  // renders Keycloak's own "You are logged out" page, whereas an UNREGISTERED
  // redirect uri errors WITHOUT terminating anything. Losing the return journey
  // is a cosmetic degradation; losing the sign-out is the security defect.
  // Never trade the second away to keep the first.
  return NextResponse.json(
    { url: `${base}/protocol/openid-connect/logout?${params.toString()}` },
    { headers: NO_STORE_HEADERS }
  )
}

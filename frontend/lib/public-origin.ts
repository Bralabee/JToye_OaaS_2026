/**
 * The PUBLIC origin this app is served from — injected configuration, never
 * inferred from the request inside a container (issue #504).
 *
 * WHY THIS EXISTS. `req.nextUrl.origin` looks like "the URL the browser used",
 * and it is not. Next.js builds it from the server's BIND address, so in the
 * compose stack — where `docker-compose.full-stack.yml` sets `HOSTNAME: 0.0.0.0`
 * because the standalone server must bind all interfaces — it resolves to
 * `http://0.0.0.0:3000`. Measured against the live stack, and the Host header
 * does NOT rescue it:
 *
 *   curl                                  -> {"url":"http://0.0.0.0:3000/shop"}
 *   curl -H 'Host: shop.example.com'      -> {"url":"http://0.0.0.0:3000/shop"}
 *   curl -H 'X-Forwarded-Proto: https'    -> {"url":"https://0.0.0.0:3000/shop"}
 *
 * Only the SCHEME follows the forwarded header; the hostname is the bind
 * address in every case. Keycloak refused the resulting
 * `post_logout_redirect_uri`, so customer sign-out landed on an "Invalid
 * redirect uri" error page AND left the IdP session alive.
 *
 * WHY NOT A `NEXT_PUBLIC_*` VAR (issue #467). Next.js inlines every literal
 * `process.env.NEXT_PUBLIC_*` reference at BUILD time — into the server bundle
 * too, not just the browser one — so a `NEXT_PUBLIC_` name could not be
 * corrected per environment at runtime. It has to be a plain server env, which
 * is exactly what `NEXTAUTH_URL` already is: `k8s/base/frontend-deployment.yaml`
 * sources it from `app-config/frontend.url` (patched per overlay:
 * `https://app-staging.olajay.co.uk`, `https://app.olajay.co.uk`,
 * `http://app.jtoye.local`), compose sets `http://localhost:3000`, and
 * `lib/env-validation.ts` already lists it as REQUIRED. One value, already
 * correct in every environment, already guarded.
 *
 * `APP_PUBLIC_ORIGIN` is an optional override for the day the app's public
 * origin and NextAuth's differ, or NextAuth is replaced. Nothing sets it today
 * and nothing needs to.
 */

/**
 * A wildcard bind address is never a reachable origin. Recognising it is the
 * whole point of this module: the failure being fixed was a bind address that
 * flowed silently into a URL handed to an external IdP.
 *
 * Covers IPv4 `0.0.0.0` and every all-zero IPv6 spelling (`::`, `::0`,
 * `0:0:0:0:0:0:0:0`), with or without the brackets `new URL()` keeps on the
 * hostname. `::1` and `127.0.0.1` are deliberately NOT bind addresses — they
 * are loopback, which is a real, reachable origin for a local `next dev`.
 */
function isBindAddress(hostname: string): boolean {
  const h = hostname.replace(/^\[/, "").replace(/\]$/, "").toLowerCase()
  if (!h) return true
  if (h === "0.0.0.0") return true
  return h.includes(":") && /^[0:]+$/.test(h)
}

/**
 * Narrow an arbitrary configured value to a usable http(s) ORIGIN, or null.
 *
 * Returns `u.origin` rather than the raw string so a trailing slash or a path
 * (`NEXTAUTH_URL=https://app.example.com/`) cannot produce `//shop`, and so a
 * non-http scheme can never be handed to the IdP as a redirect target.
 */
function toOrigin(raw: string | null | undefined): string | null {
  if (!raw) return null
  let u: URL
  try {
    u = new URL(raw.trim())
  } catch {
    return null
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") return null
  if (isBindAddress(u.hostname)) return null
  return u.origin
}

/**
 * The public origin, or `null` when none can be trusted.
 *
 * `null` is a real answer, not a failure to be papered over: callers must
 * degrade to something that is safe without an origin (a relative path, or an
 * IdP logout with no `post_logout_redirect_uri`) rather than emit a URL built
 * from a bind address. Emitting the bad URL anyway is precisely the defect.
 *
 * The request origin stays in the chain as a last resort — it is correct for a
 * non-containerised `next dev` — but only after `isBindAddress` has cleared it.
 */
export function resolvePublicOrigin(req?: { nextUrl: URL } | null): string | null {
  return (
    toOrigin(process.env.APP_PUBLIC_ORIGIN) ??
    toOrigin(process.env.NEXTAUTH_URL) ??
    toOrigin(req?.nextUrl?.origin) ??
    null
  )
}

/** Exported for the unit tests that assert the bind-address classification. */
export const __testables = { isBindAddress, toOrigin }

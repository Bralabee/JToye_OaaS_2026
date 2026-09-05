/**
 * THE one same-origin redirect sanitiser — for every "where should I go next"
 * value that arrives in a URL, on either realm.
 *
 * Extracted from `lib/customer-auth.ts` (which re-exports it unchanged) so that
 * SERVER route handlers can share it without importing the storefront's
 * browser-side auth module and its localStorage-backed dependencies. PR #726
 * low (a): `app/api/vendor-auth/logout-url/route.ts` had grown its own
 * `sanitizeRedirect` beside this one, and the copy was weaker — it accepted an
 * interior backslash (`/dashboard\@evil.example`), which some browsers normalise
 * to a protocol-relative URL, and did not trim, so a leading space defeated its
 * `startsWith` checks. `app/api/customer-auth/logout-url/route.ts` carried the
 * same weak copy and followed in the same PR. Two sanitisers is how one of them
 * ends up the weak one; there is now exactly one, and the routes pass their own
 * fallback.
 *
 * This exists because `/shop/signin?next=…` (and `?redirect=…` on the logout
 * routes) puts a post-auth destination in a URL, which anyone can craft into a
 * link. Without it `?next=https://evil.example` would be stored and then handed
 * to `router.replace()` or to Keycloak's `post_logout_redirect_uri` — a textbook
 * open redirect, and a convincing one because the user really did just
 * authenticate with us before being bounced away.
 *
 * Rejected, each for a reason rather than by a general "looks odd" rule:
 *   - anything with a scheme (`https:`, and `javascript:` in particular)
 *   - protocol-relative `//host`, which a naive "starts with /" check accepts and
 *     browsers treat as absolute
 *   - backslash variants (`/\evil.com`, `\\evil.com`, `/a\@evil.com`) that some
 *     browsers normalise to a protocol-relative URL
 *   - anything not starting with a single `/`, so a bare `evil.com` cannot resolve
 *     relative to the current directory
 *
 * Deliberately NOT a route allowlist: the whole point is to return the user to
 * wherever they were, and enumerating that is a maintenance burden that would fail
 * closed onto the fallback the first time a route is added.
 *
 * @param value    the untrusted candidate
 * @param fallback where to land when the candidate is refused — `/shop` for the
 *                 storefront realm (the historical default), `/auth/signin` for the
 *                 vendor realm. The fallback is trusted configuration, never input.
 */
export function safeReturnTo(value: string | null | undefined, fallback: string = "/shop"): string {
  if (!value) return fallback
  const candidate = value.trim()
  if (!candidate.startsWith("/")) return fallback
  // `//host` and `/\host` are absolute to a browser despite the leading slash.
  if (candidate.startsWith("//") || candidate.startsWith("/\\")) return fallback
  if (candidate.includes("\\")) return fallback
  // A scheme cannot appear in a path-absolute URL; if one does, this is not one.
  if (/^[a-z][a-z0-9+.-]*:/i.test(candidate)) return fallback
  return candidate
}

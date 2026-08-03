---
quick_id: 260803-13q
slug: customer-session-refresh-and-public-header
date: 2026-08-03
issues: ["#465", "#457"]
branch: feature/customer-session-refresh-and-public-header
---

# Quick: customer session refresh (#465) + session-aware public header (#457)

Two defects with one reported symptom (*"going home when logged in logs one out"*). Both were
falsified in a real browser before any code was written — see the evidence summary in #465 and the
falsification comment on #457.

## What is broken

**#465 (P1)** — the customer session ends at exactly `accessTokenLifespan` (300s) regardless of
activity. `api/customer-auth/session/route.ts` decides `authenticated` solely on cookie presence +
the ID token's `exp`, with no renewal branch. The refresh token *is* stored HttpOnly for 30 days
(`login/route.ts:65`) and **nothing ever redeems it** — the only `grant_type: "refresh_token"` in
the frontend is `auth.ts`, the *operator* NextAuth path on the `jtoye-dev` realm.

**#457 (P1)** — `PublicHeader` (rendered by `/`, `/track`, and the marketing surfaces via
`PublicShell`) contains zero session references, so a signed-in customer sees `Sign in`. Measured:
the session survives the navigation intact; only the rendering is wrong.

## Approach

### Task 1 — server-side refresh (#465)

- `frontend/lib/customer-auth-cookies.ts` (new): single source for the three cookie names and the
  shared cookie options. Currently redeclared in three route files.
- `frontend/lib/customer-token-refresh.ts` (new, server-only): redeem the refresh token against
  `CUSTOMER_KEYCLOAK_ISSUER_INTERNAL` (already wired in `.env` and `docker-compose.full-stack.yml`
  — no new config). Mirrors the operator precedent in `auth.ts:8-42`, which documents why the
  internal URL is mandatory server-side.
- `session/route.ts`: when the access cookie is absent or the ID token has expired **and** a refresh
  cookie exists, refresh and re-issue all three cookies, then answer `authenticated: true`. On
  failure, clear cookies and answer `authenticated: false` as today.

**Two hazards this must handle, both real:**

1. **Rotation.** The realm sets `revokeRefreshToken=true` / `refreshTokenMaxReuse=0`, so the
   *rotated* `refresh_token` from each response must be persisted. Reusing the old one is rejected
   and logs the customer out exactly as today — which would look like the bug was never fixed.
2. **Concurrent redeem.** `StorefrontNav` probes the session on mount, focus, visibilitychange,
   storage **and** on a 1s interval for the first 5s. Several probes can cross an expiry boundary
   together; with rotation enforced, the second redeem of the same token is rejected and kills the
   session. Mitigated with a single-flight map keyed by the refresh token so concurrent callers
   await one in-flight redeem.

### Task 2 — one shared session source (#457)

- `frontend/hooks/use-customer-session.ts` (new): lifts the mount/focus/visibility/storage/poll
  logic currently inline in `StorefrontNav:35-73` verbatim.
- `StorefrontNav` consumes the hook — its inline duplicate is removed, so the hook is the **only**
  session reader. This is what satisfies #457's "asserted by the absence of a second independent
  check".
- `PublicHeader` consumes the same hook and renders signed-in chrome (My Orders + identity chip +
  sign out) on desktop and in the mobile sheet, replacing `Sign in` when signed in.

**Incremental Betterment:** every existing affordance is preserved. The logged-out header is
byte-identical in behaviour; the signed-in state is purely additive. `StorefrontNav`'s markup is
untouched apart from the state source.

## Acceptance criteria

Falsification-first — each must be shown to FAIL before it is trusted.

- [ ] AC-1 (#465): a customer signed in and actively using the storefront is **still signed in after
      >10 minutes** — more than two access-token lifespans. Browser-verified across the boundary;
      the current code passes every unit test while failing this.
- [ ] AC-2 (#465): rotation survives **two consecutive** refreshes, not one. A single-refresh test
      passes even when the rotated token is mishandled.
- [ ] AC-3 (#465): tokens still never reach JS — `document.cookie` carries none of the three.
- [ ] AC-4 (#457): a signed-in customer sees signed-in chrome on `/` and `/track`, and the return
      arm to `/shop` still shows it.
- [ ] AC-5 (#457): exactly one component reads the session directly. Asserted by grepping for
      `getCustomerSession` callers — expect the hook and nothing else.
- [ ] AC-6: anonymous visitors unchanged — `/api/customer-auth/session` still answers
      `200 { authenticated: false }`, never 401 (backlog #13).
- [ ] AC-7: the 6 existing `customer-auth` route tests stay green; frontend `npm run build` (the tsc
      gate — jest does not type-check) exits 0; jest suite has no new failures.

## Out of scope

- #299 (customer realm unconfigured in every k8s environment) — pre-existing, separately tracked.
  This change adds no new environment variable, so it does not widen that gap.
- Changing `accessTokenLifespan` in the realm. The fix is to honour the refresh token, not to paper
  over it with a longer access token.

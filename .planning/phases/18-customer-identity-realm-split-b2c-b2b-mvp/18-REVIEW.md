---
phase: 18-customer-identity-realm-split-b2c-b2b-mvp
reviewed: 2026-07-09T21:27:55Z
depth: standard
files_reviewed: 9
files_reviewed_list:
  - .env.example
  - .gitignore
  - docker-compose.full-stack.yml
  - frontend/Dockerfile
  - frontend/app/api/customer-auth/logout-url/route.ts
  - frontend/e2e/customer-realm-split.verify.mjs
  - frontend/lib/customer-auth.ts
  - infra/keycloak/configure-keycloak.sh
  - infra/keycloak/realm-export-customers.template.json
findings:
  critical: 0
  warning: 4
  info: 6
  total: 10
status: issues_found
---

# Phase 18: Code Review Report

**Reviewed:** 2026-07-09T21:27:55Z
**Depth:** standard
**Files Reviewed:** 9
**Status:** issues_found

## Summary

Reviewed the Keycloak B2C/B2B realm-split for Phase 18: the new `jtoye-customers`
realm template, the frontend customer-auth path (PKCE public client), the
server-side logout-url route, the render/import wiring in docker-compose, and the
standalone `.mjs` verification script. The domain intent was honoured — the
committed realm template carries no secrets, redirect URIs / web-origins are
env-injected (correct-by-design), the rendered realm JSON is git-ignored, and the
backend was left untouched.

The design is sound, but I found one genuine, empirically-confirmed correctness bug
in the OAuth callback (base64url decode) that intermittently breaks the customer
login UX, plus a security-relevant fallback chain that can silently route customer
logins into the **staff** realm — directly undermining the split this phase exists
to create. Two more warnings concern a build-arg with no default (silent
mis-bake into the browser bundle) and a verification script that can report PASS
even when its admin-API queries fail.

No structural pre-pass (`<structural_findings>`) was provided, so this report is
entirely narrative findings from direct review.

## Narrative Findings (AI reviewer)

## Warnings

### WR-01: `handleCallback` decodes the id_token with `atob()` — throws on base64url payloads, breaking login for a subset of customers

**File:** `frontend/lib/customer-auth.ts:192`
**Issue:**
```ts
const payload = JSON.parse(atob(data.id_token.split(".")[1]))
```
JWT segments are **base64url** (alphabet includes `-` and `_`), but `atob()`
implements the WHATWG "forgiving-base64 decode", whose alphabet is standard base64
(`+` / `/`). Any id_token whose payload segment contains a `-` or `_` makes `atob`
throw `InvalidCharacterError`. I verified this empirically:

- A payload with a non-ASCII name (`Tëst Custømer`) → segment contains `-`/`_` →
  `atob` **THREW `InvalidCharacterError`**; the url-safe-corrected decode succeeded.
- A plain-ASCII payload → no `-`/`_` → `atob` OK.

So the failure is content-dependent but real: any customer whose id_token payload
base64url-encodes to include `-`/`_` (highly likely once the profile carries a
multi-byte UTF-8 character — accented names are routine for a UK retail platform, or
depending on the `sub`/`sid`/`jti` UUID bytes) hits it. When it throws, the outer
`try/catch` swallows it and `handleCallback` returns `null`. The consumer
(`frontend/app/shop/auth/callback/page.tsx:21-27`) then renders
**"Authentication failed. Please try again."** and never calls
`router.replace(returnTo)` — even though `/api/customer-auth/login` already set the
HttpOnly cookies and `setMarker()` was skipped, leaving `isLoggedIn()` false while
the server session is valid. The user is stranded on an error page despite being
authenticated. Note the e2e Scenario A masks this: it asserts
`/api/customer-auth/session` (cookies set before the `atob` call), not
`handleCallback`'s return value.

**Fix:** decode base64url, not base64 (or use `jwtDecode`/`atob` on a translated string):
```ts
function decodeJwtPayload(token: string): any {
  const seg = token.split(".")[1]
  const b64 = seg.replace(/-/g, "+").replace(/_/g, "/")
  const pad = b64.length % 4 === 0 ? "" : "=".repeat(4 - (b64.length % 4))
  // handle UTF-8 correctly too
  const json = decodeURIComponent(
    atob(b64 + pad).split("").map(c =>
      "%" + c.charCodeAt(0).toString(16).padStart(2, "0")).join("")
  )
  return JSON.parse(json)
}
// ...
const payload = decodeJwtPayload(data.id_token)
```

### WR-02: Customer-auth fallback chain points at the STAFF realm, defeating the B2C/B2B split

**File:** `frontend/lib/customer-auth.ts:22-25` (and `frontend/app/api/customer-auth/logout-url/route.ts:16-19`)
**Issue:**
```ts
const KC_BASE =
  process.env.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL ||
  process.env.NEXT_PUBLIC_KEYCLOAK_URL ||          // <-- jtoye-dev (STAFF realm)
  "http://localhost:8085/realms/jtoye-customers"
```
The second link in the fallback chain is `NEXT_PUBLIC_KEYCLOAK_URL`, which in
`docker-compose.full-stack.yml:278` is `.../realms/jtoye-dev` — the **staff/vendor**
realm. The entire point of Phase 18 is to keep customer identity out of `jtoye-dev`.
If `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` is ever empty/undefined at build time (see
WR-03) while `NEXT_PUBLIC_KEYCLOAK_URL` is present, the storefront login/register/
logout will silently target the staff realm — a fail-open into the wrong identity
pool, exactly the boundary this phase establishes. The same defective fallback is
duplicated in the server-side logout-url route.

**Fix:** drop the staff-realm link from the fallback, and fail loud (or fall back
only to the customer default) when the customer var is missing:
```ts
const KC_BASE =
  process.env.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL ||
  "http://localhost:8085/realms/jtoye-customers"  // never fall back to jtoye-dev
```

### WR-03: `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` build arg has no default — silent empty bake into the browser bundle

**File:** `docker-compose.full-stack.yml:267` (vs runtime default at `:283`)
**Issue:** The frontend `build.args` entry is `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL:
${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL}` — **no** `:-default`. NEXT_PUBLIC_* values are
inlined into the client bundle at **build** time only; the runtime `environment:`
entry (line 283, which *does* have `:-http://localhost:8085/realms/jtoye-customers`)
has no effect on `customer-auth.ts` in the browser. So if `.env` omits the var, the
build bakes an empty string, `customer-auth.ts` (WR-02) falls through, and (because
`NEXT_PUBLIC_KEYCLOAK_URL` is *not* a Dockerfile build arg) lands on the hardcoded
localhost default — masking the misconfiguration in local dev but silently pointing
a production build at `localhost` (or, per WR-02, the staff realm if that var is
build-injected). The asymmetry between the build arg (no default) and the runtime
env (has default) is a footgun.

**Fix:** give the build arg the same default, or make it required:
```yaml
args:
  NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL: ${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL:?NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL must be set (baked into browser bundle)}
```

### WR-04: Scenario C reports PASS even when its admin-API queries fail

**File:** `frontend/e2e/customer-realm-split.verify.mjs:259-284`
**Issue:** `getJson` coerces any non-JSON response body to `[]`
(`const b = await r.json().catch(() => [])`) and the three "is ABSENT / is REMOVED"
checks never assert `r.status`. They only test `arrLen(body) === 0`. So a 401/403/5xx
or connection reset that yields an empty/non-array body → `arrLen([]) === 0` → the
check **passes falsely**, reporting "identity pools are isolated" when the query
never actually ran. That defeats the verification's purpose (a broken admin token or
wrong realm name would go green). This is a test-reliability defect, not a style nit.

**Fix:** assert the HTTP status before interpreting the body, and treat non-array
bodies as failures:
```js
const getJson = async (path) => {
  const r = await fetch(`${KC_ADMIN_BASE}${path}`, { headers: authHeaders })
  const b = await r.json().catch(() => null)
  return { status: r.status, ok: r.ok, body: Array.isArray(b) ? b : null }
}
// then:
check(`... query succeeded (HTTP ${custInStaff.status})`, custInStaff.ok && custInStaff.body !== null)
check(`test customer is ABSENT from ${STAFF_REALM}`, arrLen(custInStaff.body) === 0)
```

## Info

### IN-01: `configure-keycloak.sh` hardcodes `username=admin` and lacks `pipefail`

**File:** `infra/keycloak/configure-keycloak.sh:30` (and `:2`)
**Issue:** The admin-token request uses `-d "username=admin"` (literal) even though
the compose stack parameterises the admin username via `KEYCLOAK_ADMIN`. If the
admin username is ever changed, token acquisition breaks despite the password check
at line 14. Separately, the script sets `set -e` but not `set -o pipefail`; every
`curl ... | jq` pipeline swallows a curl failure because only jq's exit status is
seen. **Fix:** use `-d "username=${KEYCLOAK_ADMIN:-admin}"` and add
`set -eo pipefail`.

### IN-02: `expiresAt` becomes `NaN` when the token response omits `expires_in`

**File:** `frontend/lib/customer-auth.ts:172`
**Issue:** `const expiresAt = Math.floor(Date.now() / 1000) + data.expires_in`. If the
token endpoint ever returns a body without `expires_in`, `expiresAt` is `NaN`, which
then flows into the cookie payload and `setMarker`. `isLoggedIn()` would treat `NaN`
as not-logged-in. **Fix:** guard with a default lifespan, e.g.
`+ (Number(data.expires_in) || 300)`.

### IN-03: Customer OAuth flow omits `state` and `nonce`

**File:** `frontend/lib/customer-auth.ts:113-122, 135-144`
**Issue:** The authorization request sends no `state` and no `nonce`. PKCE (present)
does mitigate the classic authorization-code CSRF for public clients, so this is not
a blocker, but `nonce` is the standard defence against id_token replay and `state`
gives defence-in-depth / mix-up protection. Consider adding a random `state` (stored
alongside the PKCE verifier) and `nonce` for hardening.

### IN-04: Public storefront client has refresh-token rotation disabled

**File:** `infra/keycloak/realm-export-customers.template.json:10-11`
**Issue:** `"revokeRefreshToken" : false` with `"refreshTokenMaxReuse" : 0`. For a
**public** SPA/PKCE client, refresh-token rotation (`revokeRefreshToken: true`) is
the recommended posture because the refresh token lives in a browser-managed cookie.
`refreshTokenMaxReuse: 0` is inert while rotation is off. Acceptable for local dev;
revisit before any non-dev deployment.

### IN-05: `frontend/Dockerfile` uses a deprecated npm flag and an unused deps stage

**File:** `frontend/Dockerfile:11` (and `:27, :46, :47`)
**Issue:** `npm ci --only=production` in the `deps` stage is deprecated (use
`--omit=dev`), and that stage's `node_modules` is immediately overwritten by the full
`npm ci` in the `builder` stage (line 23) and never used by the `runner` (which pulls
its deps from `.next/standalone`), so the `deps` stage is dead weight. Also
`ENV NEXT_TELEMETRY_DISABLED 1` / `ENV NODE_ENV production` use the legacy
space-separated `ENV` form that newer BuildKit warns on. Cosmetic / hygiene only.

### IN-06: `logout-url` redirect parameter is unvalidated (bounded, low risk)

**File:** `frontend/app/api/customer-auth/logout-url/route.ts:23-25`
**Issue:** The user-controlled `redirect` query param is concatenated onto
`req.nextUrl.origin` with only a leading-slash normalisation. It stays same-origin in
practice (and Keycloak validates `post_logout_redirect_uri` in the session branch),
and the only caller passes a fixed `/shop`, so this is not an exploitable open
redirect today. Still, the no-session branch (line 27-30) returns the URL without any
Keycloak validation; if a future caller forwards attacker input here, allow-list the
redirect target (or restrict to a known set of storefront paths).

---

_Reviewed: 2026-07-09T21:27:55Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

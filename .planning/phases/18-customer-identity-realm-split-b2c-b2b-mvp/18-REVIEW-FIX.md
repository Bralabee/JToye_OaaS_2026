---
phase: 18-customer-identity-realm-split-b2c-b2b-mvp
fixed_at: 2026-07-10T06:49:31Z
review_path: .planning/phases/18-customer-identity-realm-split-b2c-b2b-mvp/18-REVIEW.md
iteration: 2
fix_scope: all
findings_in_scope: 10
fixed: 10
skipped: 0
status: all_fixed
---

# Phase 18: Code Review Fix Report

**Fixed at:** 2026-07-10T06:49:31Z
**Source review:** .planning/phases/18-customer-identity-realm-split-b2c-b2b-mvp/18-REVIEW.md
**Iteration:** 2
**Fix scope:** `all` (4 Warnings + 6 Info)

**Summary:**
- Findings in scope: 10 (WR-01..WR-04, IN-01..IN-06)
- Fixed: 10 (4 Warnings applied in iteration 1 + 6 Info applied this run)
- Skipped: 0

This iteration completes the phase-18 review: the 4 Warnings were fixed and
committed in iteration 1 (verified still applied in the working tree, see below),
and this run applied all 6 Info findings. All work was performed in an isolated git
worktree, verified per-fix, committed atomically (one commit per finding), then
fast-forwarded onto `feature/phase-18-customer-realm-split`. Frontend TypeScript
changes were gated on `cd frontend && npm run build` (Next.js production build,
exit 0). The shell change was gated on `bash -n`. The realm template was
parse-validated as JSON (env placeholders substituted). The Dockerfile was verified
structurally (no build/rebuild performed here — the orchestrator rebuilds images and
re-runs the 3-scenario E2E after this report).

## Fixed Issues

### Warnings (applied in iteration 1 — verified still present)

These four were fixed and committed in the prior pass. This run confirmed each is
still applied in the working tree before proceeding; none were re-applied or
duplicated.

- **WR-01** — id_token decoded as base64url (UTF-8-safe `decodeJwtPayload`) in
  `frontend/lib/customer-auth.ts`. Commit `73b6b76`. Verified: the
  `decodeJwtPayload` helper is present and used at the callback site.
- **WR-02** — staff-realm (`NEXT_PUBLIC_KEYCLOAK_URL`) link removed from both
  `KC_BASE` fallback chains (`frontend/lib/customer-auth.ts`,
  `frontend/app/api/customer-auth/logout-url/route.ts`). Commit `75af492`. Verified:
  both chains fall back only to the `jtoye-customers` dev default.
- **WR-03** — `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` build arg made required
  (`${...:?...}`) in `docker-compose.full-stack.yml`. Commit `11cf4bd`. Verified at
  line 269.
- **WR-04** — Scenario C admin-API queries now assert HTTP status; `getJson` returns
  `{status, ok, body}` with non-array bodies coerced to `null`
  (`frontend/e2e/customer-realm-split.verify.mjs`). Commit `6ef9d7b`. Verified.

### Info (applied this run)

### IN-01: `configure-keycloak.sh` hardcoded `username=admin` and lacked `pipefail`

**Files modified:** `infra/keycloak/configure-keycloak.sh`
**Commit:** `ade4c72`
**Applied fix:** Changed `set -e` → `set -eo pipefail` (line 2) so a failing `curl`
in any `curl ... | jq` pipeline is no longer masked by jq's exit status. Changed the
admin-token request from the literal `-d "username=admin"` → `-d
"username=${KEYCLOAK_ADMIN:-admin}"` so the username tracks the compose-provided
`KEYCLOAK_ADMIN` var while defaulting to `admin` (unchanged behaviour when the var is
absent). No change to the target realm (`jtoye-dev` only); staff/test fixtures
(test-client, tenant-a-user, tenant-b-user, tenant_id mapper) untouched.
**Verification:** `bash -n infra/keycloak/configure-keycloak.sh` exit 0. The added
`pipefail` does not affect the happy path (curl+jq both exit 0 normally); it only
tightens failure detection.

### IN-02: `expiresAt` became `NaN` when the token response omits `expires_in`

**Files modified:** `frontend/lib/customer-auth.ts`
**Commit:** `4286933`
**Applied fix:** Guarded the expiry computation in `handleCallback`:
`Math.floor(Date.now()/1000) + (Number(data.expires_in) || 300)`. A missing/invalid
`expires_in` now yields a finite timestamp (300s default) instead of `NaN`, which
would otherwise poison the cookie payload and the localStorage marker and make
`isLoggedIn()` always false. (Note: the live line drifted from the review's cited
`:172` to `:208` because iteration 1's WR-01 helper was inserted above; the fix was
adapted to the current code.)
**Verification:** `npm run build` exit 0.

### IN-03: Customer OAuth flow omitted `state` and `nonce`

**Files modified:** `frontend/lib/customer-auth.ts`,
`frontend/app/shop/auth/callback/page.tsx`
**Commit:** `233ec5d`
**Applied fix:** Implemented `state` (CSRF/mix-up defence) and `nonce` (id_token
replay defence) end-to-end, using the SAME `sessionStorage` mechanism the PKCE
verifier already uses:
- Added a `randomToken()` CSPRNG helper (32 bytes, URL-safe base64) and refactored
  `generateCodeVerifier()` to reuse it (identical output).
- Added `storeAuthTransients(verifier, state, nonce)` / `clearAuthTransients()` and
  three key constants (`jtoye-pkce-verifier`, `jtoye-oauth-state`,
  `jtoye-oauth-nonce`).
- `customerLogin` and `customerRegister` now generate a fresh `state` + `nonce`,
  persist all three transients together, and append `state` & `nonce` to the
  authorization request.
- `handleCallback(code, returnedState)` now validates `returnedState === storedState`
  BEFORE the token exchange (rejects + clears transients on mismatch), and after
  decoding the id_token validates `payload.nonce === storedNonce`. Added `nonce?` to
  `IdTokenClaims`. On success it clears all transients via `clearAuthTransients()`
  (replacing the previous single verifier removal).
- Updated the sole caller (`frontend/app/shop/auth/callback/page.tsx`) to pass
  `searchParams.get("state")`.
Happy path preserved: Keycloak echoes the exact `state` and mints the id_token with
the exact `nonce` we send, so a legitimate login validates cleanly; the code can only
be exchanged once regardless.
**Verification:** `npm run build` exit 0. **Requires human/E2E verification:** this
adds new security-gating logic on the live login path. Syntax/type checks pass, but
the happy-path login (state matches, nonce present in id_token) must be confirmed by
the orchestrator's 3-scenario E2E re-run after the frontend image rebuild — the
browser bundle only picks up `customer-auth.ts` changes after a rebuild.

### IN-04: Public storefront client had refresh-token rotation disabled

**Files modified:** `infra/keycloak/realm-export-customers.template.json`
**Commit:** `32fd85a`
**Applied fix:** Set `"revokeRefreshToken" : true` (was `false`), enabling
refresh-token rotation — the recommended posture for a public SPA/PKCE client whose
refresh token lives in a browser-managed cookie. Left `"refreshTokenMaxReuse" : 0`
(now meaningful under rotation: strict, no reuse). This edits the committed template
(the source of truth); a live realm re-import is NOT triggered by this change and is
left to the deployment step.
**Verification:** JSON parse-validated after substituting the `${...}` env
placeholders with valid tokens; confirmed `revokeRefreshToken === true` and
`refreshTokenMaxReuse === 0`.

### IN-05: `frontend/Dockerfile` used a deprecated npm flag and a dead deps stage

**Files modified:** `frontend/Dockerfile`
**Commit:** `2a6c513`
**Applied fix:** Removed the entire `deps` stage (its `npm ci --only=production`
`node_modules` was immediately overwritten by the builder's `npm ci` and never used
by the runner, which pulls deps from `.next/standalone`). The builder is now stage 1
and installs all deps directly (`COPY package.json package-lock.json* ./` → `npm ci`
→ `COPY . .`, with `node_modules` excluded via `.dockerignore`). Modernized every
legacy space-separated `ENV` to the `KEY=value` form
(`NEXT_TELEMETRY_DISABLED=1`, `NODE_ENV=production`, `PORT=3000`,
`HOSTNAME="0.0.0.0"`) to clear the BuildKit deprecation warnings. The two remaining
stages (builder, runner), standalone COPYs, non-root user, healthcheck, ports, and
labels are unchanged.
**Verification:** Re-read; grep confirms no remaining `--only=production`, no `deps`
references, and no legacy `ENV key value` lines. hadolint not available (Tier 2
skipped). No image build performed here — the orchestrator rebuilds and validates.

### IN-06: `logout-url` redirect parameter was unvalidated

**Files modified:** `frontend/app/api/customer-auth/logout-url/route.ts`
**Commit:** `66c3b6b`
**Applied fix:** Added a `sanitizeRedirect()` allow-list that only accepts a
same-origin relative path beginning with a single `/` — rejecting protocol-relative
`//host`, backslash tricks `/\\host`, and absolute URLs — falling back to `/shop`.
The GET handler now runs the user-controlled `redirect` query param through it before
composing `post_logout_redirect_uri`, closing the theoretical open-redirect in the
no-session branch (which returns the URL without Keycloak validation). The only
caller passes a fixed `/shop`, so the happy path is unchanged.
**Verification:** `npm run build` exit 0.

## Skipped Issues

None — all 10 in-scope findings are fixed (4 in iteration 1, 6 in this run).

## Notes for the orchestrator

- **IN-03 needs live confirmation.** It gates the customer login on `state`/`nonce`
  validation. The build passes, but only the E2E re-run (Scenario A, real Keycloak
  login) confirms the happy path still succeeds. If Scenario A regresses, inspect
  commit `233ec5d`.
- **Rebuild required for frontend behaviour.** IN-02, IN-03, IN-06 change
  browser/route code; the running frontend image must be rebuilt before E2E.
- **IN-04 is template-only.** The committed realm template now sets
  `revokeRefreshToken: true`; the live `jtoye-customers` realm is unchanged until a
  re-import.
- **docs-freshness untouched.** No `test()`/`it()`/`Test*` invocations were added or
  removed; `.verify.mjs` remains a non-counted script. Counts stay 771 / 5.

---

_Fixed: 2026-07-10T06:49:31Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 2_

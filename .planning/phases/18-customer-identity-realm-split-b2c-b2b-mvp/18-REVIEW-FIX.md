---
phase: 18-customer-identity-realm-split-b2c-b2b-mvp
fixed_at: 2026-07-09T22:52:07Z
review_path: .planning/phases/18-customer-identity-realm-split-b2c-b2b-mvp/18-REVIEW.md
iteration: 1
fix_scope: critical_warning
findings_in_scope: 4
fixed: 4
skipped: 0
out_of_scope: 6
status: all_fixed
---

# Phase 18: Code Review Fix Report

**Fixed at:** 2026-07-09T22:52:07Z
**Source review:** .planning/phases/18-customer-identity-realm-split-b2c-b2b-mvp/18-REVIEW.md
**Iteration:** 1
**Fix scope:** `critical_warning` (4 Warnings; the 6 Info findings are out of scope for this run)

**Summary:**
- Findings in scope: 4 (WR-01, WR-02, WR-03, WR-04)
- Fixed: 4
- Skipped: 0
- Out of scope (not attempted): 6 (IN-01 .. IN-06)

All fixes were applied inside an isolated git worktree, validated, and committed
atomically (one commit per finding). TypeScript changes were validated with
`cd frontend && npm run build` (Next.js production build, exit 0). The `.mjs`
verification script change was validated with `node --check`. The compose change was
validated by parsing the YAML and confirming the build-arg scalar is intact.

## Fixed Issues

### WR-01: `handleCallback` decoded the id_token with `atob()` — threw on base64url payloads

**Files modified:** `frontend/lib/customer-auth.ts`
**Commit:** `73b6b76`
**Applied fix:** Added a browser-safe `decodeJwtPayload(token)` helper (typed
`IdTokenClaims`) that translates base64url → standard base64 (`-`→`+`, `_`→`/`),
re-pads, then UTF-8-decodes via `decodeURIComponent(...)` so multi-byte characters
(accented names) survive. Replaced the throwing
`JSON.parse(atob(data.id_token.split(".")[1]))` at the callback site with
`decodeJwtPayload(data.id_token)` plus a `null` guard (`if (!payload) return null`),
and made `sub` null-safe (`payload.sub ?? ""`). This mirrors the correct base64url
handling already present server-side in
`frontend/app/api/customer-auth/session/route.ts` (that route uses Node `Buffer` and
was already correct, so it was left untouched — the bug was browser-only).

**Verification:** `npm run build` exit 0. Additional behavioral check (node,
replicating the helper): confirmed the OLD `atob()` throws `InvalidCharacterError`
on a base64url payload containing `_`, while the NEW decoder returns the correct
claims and preserves accented names (`Zoë Ürström`, `Tëst Custømer`). Behavioral
confirmation on the live login path will be covered by the orchestrator's 3-scenario
E2E re-run after the frontend image is rebuilt (the browser bundle only picks up
`customer-auth.ts` changes after a rebuild).

### WR-02: Customer-auth fallback chain pointed at the STAFF realm, defeating the B2C/B2B split

**Files modified:** `frontend/lib/customer-auth.ts`, `frontend/app/api/customer-auth/logout-url/route.ts`
**Commit:** `75af492`
**Applied fix:** Removed the `process.env.NEXT_PUBLIC_KEYCLOAK_URL` (the `jtoye-dev`
staff/vendor realm) middle link from both `KC_BASE` fallback chains. `KC_BASE` now
resolves to `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` or, if unset, the
`http://localhost:8085/realms/jtoye-customers` dev default only — it can never
silently fall through to the staff realm. Updated the explanatory comments in both
files to document the "never fall back to jtoye-dev" invariant. Happy path is
preserved: `.env` sets `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`, so the first link still
wins on the working stack.

**Verification:** `npm run build` exit 0.

### WR-03: `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` build arg had no default — silent empty bake into browser bundle

**Files modified:** `docker-compose.full-stack.yml`
**Commit:** `11cf4bd`
**Applied fix:** Changed the frontend `build.args` entry from
`${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL}` to
`${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL:?NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL must be set (baked into browser bundle)}`,
making the var required at build time so a missing value fails the build loudly
instead of baking an empty string into the client bundle. Added a comment explaining
why the runtime `environment:` default (line ~284) cannot compensate for a build-time
bake. Does not break the working stack: the var is present in `.env`.

**Verification:** Parsed the compose file with a YAML parser and confirmed
`services.frontend.build.args.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` is the intact
single-scalar required-var expression (the `:?...` message contains no unquoted
`colon-space`, so it does not mis-parse as a nested mapping).

### WR-04: Scenario C reported PASS even when its admin-API queries failed

**Files modified:** `frontend/e2e/customer-realm-split.verify.mjs`
**Commit:** `6ef9d7b`
**Applied fix:** Hardened `getJson` to return `{ status, ok, body }` where the body is
coerced to `null` for any non-array response (so a 401/403/5xx or connection reset can
no longer masquerade as an empty result — `catch(() => [])` became
`catch(() => null)` with `Array.isArray(b) ? b : null`). Added an explicit
`... query succeeded (HTTP <status>)` assertion (`r.ok && body !== null`) before each
of the three ABSENT/REMOVED checks. Because `arrLen(null)` returns `-1`, a null body
now also fails the downstream `=== 0` check, fully closing the false-PASS path. Kept
the file a standalone `.verify.mjs` and added NO `test()`/`it()` blocks, so the
`docs-freshness` gate (771 / 5 counts) stays green.

**Verification:** `node --check` exit 0; confirmed zero real `test()`/`it()`
invocations (the only regex match is a prose comment documenting the gate).

## Skipped Issues

None — all 4 in-scope findings were fixed.

## Out of Scope (Not Attempted)

The following Info findings are outside this run's `critical_warning` scope and were
intentionally not attempted. Left for a future `--fix all` pass or manual triage:

- **IN-01** — `configure-keycloak.sh` hardcodes `username=admin` and lacks `pipefail` (`infra/keycloak/configure-keycloak.sh:30, :2`).
- **IN-02** — `expiresAt` becomes `NaN` when the token response omits `expires_in` (`frontend/lib/customer-auth.ts:172`).
- **IN-03** — Customer OAuth flow omits `state` and `nonce` (`frontend/lib/customer-auth.ts:113-122, 135-144`).
- **IN-04** — Public storefront client has refresh-token rotation disabled (`infra/keycloak/realm-export-customers.template.json:10-11`).
- **IN-05** — `frontend/Dockerfile` uses a deprecated npm flag and an unused deps stage (`frontend/Dockerfile:11, :27, :46, :47`).
- **IN-06** — `logout-url` redirect parameter is unvalidated (bounded, low risk) (`frontend/app/api/customer-auth/logout-url/route.ts:23-25`).

---

_Fixed: 2026-07-09T22:52:07Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_

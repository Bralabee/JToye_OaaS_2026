---
phase: 18-customer-identity-realm-split-b2c-b2b-mvp
plan: 02
subsystem: auth
tags: [keycloak, realm-hardening, self-registration, admin-api, playwright, b2b, b2c]

# Dependency graph
requires:
  - phase: "18-01"
    provides: "jtoye-customers realm + Scenario A verify + .last-customer-email export"
provides:
  - "Hardened jtoye-dev staff/vendor realm: self-registration disabled (no Register link on the core-api admin login page) and storefront-client removed — committed script + reconciled live realm"
  - "Extended node Playwright verification (Scenarios A + B + C) proving admin login still works with no Register link, and the two identity pools are disjoint"
  - "Backend-untouched assertion for the whole phase (core-java/edge-go diff empty)"
affects: [customer-auth, storefront, keycloak, admin-dashboard]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Committed template + envsubst is the reproducible source of truth; a one-time non-destructive master-admin-API reconcile fixes an already-persisted realm that --import-realm will not overwrite"
    - "Single standalone .verify.mjs runs all three scenarios (browser + admin-API) and stays off the docs-freshness counted-spec surface (.spec.ts only)"
    - "Round-trip GET→mutate-two-fields→PUT on the realm representation so only registration flags change and clients/users are untouched"

key-files:
  created: []
  modified:
    - "frontend/e2e/customer-realm-split.verify.mjs"
    - "infra/keycloak/configure-keycloak.sh"

key-decisions:
  - "Reconciled the running jtoye-dev realm via master admin-API (registrationAllowed:false, registrationEmailAsUsername:false, DELETE storefront-client) because --import-realm never overwrites an existing realm and `down -v` is forbidden (shared app+keycloak Postgres)"
  - "Left the residual `customer` realm role in place on jtoye-dev (harmless with no registration path) rather than risk touching default-roles composites that admin-user login depends on"
  - "Scenario B asserts absence of any /registration or /registrations anchor (robust to Keycloak's singular href) plus the #kc-registration block; Scenario C uses per-realm admin-API lookups for disjointness"

patterns-established:
  - "Verify-only final task: git-diff backend-untouched assertion + full E2E green + docs-freshness gate, no source edits"

requirements-completed: [CID-01]

# Metrics
duration: ~12min
completed: 2026-07-09
---

# Phase 18 Plan 02: Harden Staff Realm (jtoye-dev) Summary

**The staff/vendor realm `jtoye-dev` is now a clean staff-only surface — self-registration disabled (no Register link on the `core-api` admin login) and `storefront-client` removed, both in the committed `configure-keycloak.sh` and reconciled on the live realm — while `admin-user` still signs into `/dashboard`; the two identity pools are proven disjoint and the backend was never touched.**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-07-09T21:05Z (post 18-01 completion)
- **Completed:** 2026-07-09T21:18Z
- **Tasks:** 3/3 (Task 3 verify-only — no source edits, no commit)
- **Files modified:** 2 committed (0 created, 2 modified) + one-time live admin-API reconcile (operational, not a file change)

## Accomplishments
- **Task 1 (RED):** Filled the Scenario B + C stubs in `frontend/e2e/customer-realm-split.verify.mjs`. Scenario B drives the real admin sign-in (`/auth/signin` → "Sign in with Keycloak" → jtoye-dev `core-api` login), asserts NO `/registration(s)` anchor, then logs in as `admin-user` and lands on `/dashboard`. Scenario C uses a master admin-API token to prove the test customer is absent from `jtoye-dev`, `admin-user` is absent from `jtoye-customers`, and `storefront-client` is gone from `jtoye-dev`. Scenario A kept intact. Confirmed RED (exit 1: Register link present + storefront-client still on jtoye-dev).
- **Task 2 (GREEN — hardening):** Removed the self-service-registration PUT, the public storefront OIDC client creation, and the `customer` realm-role + default-role branches from `configure-keycloak.sh` (net −74 lines), keeping the admin-token bootstrap, `test-client` + `tenant_id` mapper, and `tenant-a/b-user` seeding. Reconciled the running realm out-of-band via master admin-API: `registrationAllowed:false`, `registrationEmailAsUsername:false`, `storefront-client` DELETEd. Verified live: `core-api`/`edge-api`/`test-client` + `admin-user` all intact, no Register link on the login page, both realms 200.
- **Task 3 (verify-only):** Backend-untouched assertion green (`git diff core-java edge-go` since `merge-base HEAD main` is EMPTY; no `SecurityConfig.java`/`AudienceValidator.java`/`edge-go/*jwt` changes). Full 3-scenario Playwright verify exits 0 (A + B + C all pass). docs-freshness gate green — `total_logical_invocations` 771, `playwright_specs` 5 unchanged (the verification is a `.verify.mjs`, not a counted `.spec.ts`).

## Task Commits

Feature branch `feature/phase-18-customer-realm-split` (no Co-Authored-By per project policy):

1. **Task 1: Scenario B + C (RED)** — `bf73ca1` (test) — RED: fails on live jtoye-dev (Register link present, storefront-client still on the staff realm).
2. **Task 2: harden jtoye-dev — strip customer-signup branches** — `f236b1b` (feat) — GREEN enabler: committed script hardened + live realm reconciled.
3. **Task 3: backend-untouched + full E2E green + docs-freshness** — verify-only, no source change (no commit).

**Plan metadata:** this commit (docs: complete plan).

## Files Created/Modified
- `frontend/e2e/customer-realm-split.verify.mjs` (modified) — Scenarios B + C implemented alongside intact Scenario A; `readFileSync` import + admin-API constants added; runs all three, exits non-zero if any fail. Secrets read from `.env`, never logged.
- `infra/keycloak/configure-keycloak.sh` (modified) — customer-signup branches deleted; replaced with a hardening NOTE explaining jtoye-dev is staff/vendor-only; staff/test fixtures retained; `bash -n` clean.

## Live Operational Change (not a file diff)
- Master admin-API reconcile of the persisted `jtoye-dev` realm (one-time, non-destructive; committed template/script are the durable source of truth per threat T-18-10): `registrationAllowed:true→false`, `registrationEmailAsUsername:true→false`, `storefront-client` (1→0) DELETEd. No stack rebuild required (frontend untouched; Keycloak reflects realm settings per-request).

## Decisions Made
- Live-realm reconcile via admin-API — see key-decisions frontmatter (import never overwrites, `down -v` forbidden).
- Residual `customer` role left on jtoye-dev — harmless with no registration path, avoids touching default-roles composites that staff login relies on.

## Deviations from Plan
None - plan executed exactly as written.

## Threat Model Outcomes
- **T-18-06 (EoP, self-registration):** mitigated — `registrationAllowed:false` in committed template AND on the live realm; no Register link.
- **T-18-07 (Spoofing, residual storefront-client):** mitigated — removed from `configure-keycloak.sh` and DELETEd from the live jtoye-dev realm; exists only in jtoye-customers.
- **T-18-08 (Info disclosure, cross-realm pools):** mitigated — Scenario C proves disjoint pools via per-realm admin-API lookup.
- **T-18-09 (Tampering, backend JWT drift):** mitigated — explicit empty-diff assertion on core-java/edge-go.
- **T-18-10 (Repudiation, reconcile without record):** accepted — committed script/template are the auditable durable source of truth.

## Issues Encountered
None. Scenario A re-registers a fresh customer each run and rewrites `.last-customer-email`, which Scenario C then reads for the disjointness check — self-consistent within a single run.

## User Setup Required
None for local dev. For any environment whose persisted Keycloak volume predates this hardening, run the equivalent one-time admin-API reconcile (or drop+`--import-realm` the jtoye-dev template, which already ships `registrationAllowed:false`) so the running realm matches the committed source of truth.

## Next Phase Readiness
- Phase 18 (B2C/B2B customer identity realm split) is functionally complete: customers self-register/login on `jtoye-customers`; `jtoye-dev` is staff-only with no self-registration; pools proven disjoint; backend untouched.
- Deferred (Phase 2): Google/social login (needs user-supplied Google OAuth client); backend multi-issuer validation only if authenticated customer→backend endpoints are ever added.
- No blockers.

## Self-Check: PASSED

- Modified files exist: `frontend/e2e/customer-realm-split.verify.mjs`, `infra/keycloak/configure-keycloak.sh`, `18-02-SUMMARY.md`.
- Task commits exist: `bf73ca1` (test/RED), `f236b1b` (feat/harden).
- Live gates confirmed: full 3-scenario verify exit 0 (A+B+C green), jtoye-dev + jtoye-customers well-known 200, no Register link on jtoye-dev login page, storefront-client removed, backend diff empty, docs-freshness 771/5.

---
*Phase: 18-customer-identity-realm-split-b2c-b2b-mvp*
*Completed: 2026-07-09*

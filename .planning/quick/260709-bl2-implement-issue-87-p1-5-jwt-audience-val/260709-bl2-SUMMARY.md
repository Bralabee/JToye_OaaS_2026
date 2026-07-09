---
phase: quick-260709-bl2
plan: 01
subsystem: auth
tags: [jwt, oauth2, keycloak, audience, nextauth, spring-security, gin, security]

# Dependency graph
requires:
  - phase: 260708-rlp (#83 P1-1)
    provides: KeycloakRealmRoleConverter + jwtAuthenticationConverter wiring (preserved untouched here)
provides:
  - core-java additive JWT audience validation (issuer strengthened + audience) on the NimbusJwtDecoder
  - edge-go fail-closed audience enforcement with a documented default constant
  - hardened Keycloak realm template (bruteForceProtected, passwordPolicy, core-api audience mapper)
  - refresh-token-free vendor NextAuth session (server-side refresh preserved)
affects: [keycloak-realm-runtime-reimport, edge-go-auth, nextauth-session, resource-server-security]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Additive DelegatingOAuth2TokenValidator (issuer default + AudienceValidator) — never replaces the #83 authority converter"
    - "Fail-closed edge audience with a documented default constant + env override (no inert opt-in path)"
    - "Pure testable session builder (buildSession) delegated from the NextAuth session callback"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/AudienceValidator.java
    - core-java/src/test/java/uk/jtoye/core/security/AudienceValidatorTest.java
    - frontend/lib/session-callback.ts
    - frontend/__tests__/session-callback.test.ts
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
    - core-java/src/main/resources/application.yml
    - edge-go/internal/middleware/jwt.go
    - edge-go/internal/middleware/jwt_test.go
    - docker-compose.full-stack.yml
    - infra/keycloak/realm-export.template.json
    - frontend/auth.ts
    - frontend/types/next-auth.d.ts
    - docs/metrics.json
    - CLAUDE.md

key-decisions:
  - "core-java uses a custom JwtDecoder bean, so audience is enforced via setJwtValidator (not spring...jwt.audiences, which only binds the auto-configured decoder)"
  - "AudienceValidator throws on a blank expected audience at construction so enforcement can never silently no-op"
  - "createDefaultWithIssuer STRENGTHENS issuer validation (withJwkSetUri default was timestamp-only) — additive, not a weakening"
  - "edge audience is fail-closed: EDGE_JWT_AUDIENCE unset falls back to defaultJWTAudience=core-api rather than disabling the check"
  - "Refresh token stays on the server-side JWT (callbacks.jwt + refreshAccessToken); only the client-visible session drops it"
  - "Canonical expected audience is core-api (matches KEYCLOAK_CLIENT_ID) across both tiers"

patterns-established:
  - "Additive token validation: DelegatingOAuth2TokenValidator layering issuer+timestamp defaults with a custom AudienceValidator"
  - "Fail-closed security default constant with an env override and a test that asserts the default resolves"

requirements-completed: [ISSUE-87-P1-5]

# Metrics
duration: 23min
completed: 2026-07-09
---

# Quick 260709-bl2: JWT Audience Validation + Realm Hardening + Session Hygiene Summary

**Closed the token-confusion hole on both tiers (core-java + edge-go) with additive, fail-closed audience validation, hardened the Keycloak realm (brute-force + password policy + core-api audience mapper), and stopped leaking the vendor refresh token into the client session — all three test suites green (552 Java + 75 Go + 104 Jest).**

## Performance

- **Duration:** ~23 min
- **Started:** 2026-07-09T07:27:24Z
- **Completed:** 2026-07-09T07:50:52Z
- **Tasks:** 3/3
- **Files modified/created:** 14

## Accomplishments
- **core-java (Task 1):** `AudienceValidator` rejects any JWT whose `aud` does not contain `core-api`, wired as a `DelegatingOAuth2TokenValidator` alongside `JwtValidators.createDefaultWithIssuer` on the `NimbusJwtDecoder`. Issuer validation is now enforced (previously the `withJwkSetUri` decoder validated timestamp only), and the #83 `jwtAuthenticationConverter`/role mapping is untouched. Expected audience is env-overridable (`JWT_EXPECTED_AUDIENCE`, default `core-api`), never hardcoded; a blank value throws at construction.
- **edge-go (Task 2):** Audience enforcement is now fail-closed. `defaultJWTAudience = "core-api"` is applied when `EDGE_JWT_AUDIENCE` is unset (previously the check was inert), and the gate runs unconditionally before the tenant check. Pre-existing tests that must pass the gate now carry `aud: core-api`; `MissingTenantRejected` still reaches the tenant check (401 "missing tenant claim", not "invalid audience"). New `TestJWTMiddleware_Validate_Audience_DefaultWhenUnset` proves the default path.
- **Realm hardening (Task 3A):** `bruteForceProtected: true`, a `passwordPolicy` string (`length(12) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1) and notUsername`), and an `oidc-audience-mapper` on the `core-api` client emitting `aud=core-api` on the access token. Template remains valid JSON.
- **Session hygiene (Task 3B):** New pure `buildSession` copies `accessToken`+`idToken` to the client session but omits (and defensively deletes) the refresh token; `auth.ts` delegates to it; `refreshToken` removed from the `Session` type but kept on the server-side `JWT` so `refreshAccessToken` still works.
- **Docs (Task 3C):** `docs/metrics.json` regenerated 746 → 755; `CLAUDE.md` prose updated; `docs-freshness` gate green.

## Task Commits

Each task was committed atomically (code only; docs artifacts intentionally left uncommitted for the orchestrator):

1. **Task 1: core-java additive audience validation** - `9f6f788` (feat)
2. **Task 2: edge-go fail-closed audience enforcement** - `40874da` (feat)
3. **Task 3: realm hardening + refresh-token-free session + docs regen** - `074a639` (feat)

_Note: per the launching agent's session-limit-resilience instruction, each task was committed the moment it passed its own verification, rather than using separate RED/GREEN commits; every committed state is green against its task's verify._

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/security/AudienceValidator.java` (created) - `OAuth2TokenValidator<Jwt>` enforcing `aud` contains the expected audience; throws on blank config.
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` - `setJwtValidator(DelegatingOAuth2TokenValidator(createDefaultWithIssuer, AudienceValidator))`; role converter untouched.
- `core-java/src/main/resources/application.yml` - `jtoye.security.jwt.expected-audience: ${JWT_EXPECTED_AUDIENCE:core-api}`.
- `core-java/src/test/java/uk/jtoye/core/security/AudienceValidatorTest.java` (created) - 5 decoder-level cases.
- `edge-go/internal/middleware/jwt.go` - `defaultJWTAudience` const, fail-closed default, always-on gate.
- `edge-go/internal/middleware/jwt_test.go` - `aud` added to gate-passing tokens + new default-when-unset test.
- `docker-compose.full-stack.yml` - `EDGE_JWT_AUDIENCE: ${EDGE_JWT_AUDIENCE:-core-api}` on edge-go.
- `infra/keycloak/realm-export.template.json` - brute-force + password policy + core-api audience mapper.
- `frontend/lib/session-callback.ts` (created) - pure `buildSession`, no refresh token.
- `frontend/auth.ts` - session callback delegates to `buildSession`.
- `frontend/types/next-auth.d.ts` - `refreshToken` removed from `Session`, kept on `JWT`.
- `frontend/__tests__/session-callback.test.ts` (created) - 3 Jest assertions.
- `docs/metrics.json`, `CLAUDE.md` - test-count sync (755).

## Verification — Full 3-Suite Gate

| Suite | Command | Result |
|-------|---------|--------|
| core-java unit | `./gradlew :core-java:test` | **452 tests, 0 failures, 0 errors, 1 skipped** |
| core-java integration (Testcontainers/RLS) | `./gradlew :core-java:integrationTest` | **100 tests, 0 failures, 0 errors, 1 skipped** (BUILD SUCCESSFUL 8m 46s) |
| edge-go | `go test ./... -count=1` (+ earlier `-race`) | **all 6 packages ok** (75 top-level Test funcs) |
| frontend | `npm test` | **19 suites, 104 tests passed** |
| docs-freshness | `scripts/docs-freshness.sh --write && scripts/docs-freshness.sh` | **OK — 755 logical invocations, no drift** |
| realm template | `jq empty` + `jq -e '.bruteForceProtected==true and (.passwordPolicy|type=="string")'` | **valid JSON; hardening flags + core-api audience mapper present** |

## Deviations from Plan

**None affecting scope.** Minor, plan-anticipated adjustments:

1. **[Task 2 — plan-directed audit]** `TestJWTMiddleware_ExtractTenantID` was listed as potentially needing `aud`, but on inspection it does not invoke `Validate()` (it replicates the tenant-extraction loop inline), so no `aud` was required — no change made. The four tests that actually route through `Validate()` and must pass the gate (`ValidTokenWithTenant`, `MissingTenantRejected`, `ConcurrentRefresh`, `ValidToken`) received `aud: core-api`.
2. **[Task 2 — Rule 2 robustness]** `TestJWTMiddleware_Validate_Audience_DefaultWhenUnset` adds a defensive assertion `m.audience == defaultJWTAudience` before the sub-tests, so ambient `EDGE_JWT_AUDIENCE` contamination in the shared test process fails loudly instead of silently skipping the default path (the plan flagged this env-sharing risk).
3. **[Task 3B — Rule 2 defense-in-depth]** `buildSession` explicitly `delete`s `refreshToken` from the session object (not just omitting the assignment), guaranteeing no refresh token can ride along even if a caller passes a session that already carried one. Verified by a dedicated test case.
4. **[Env note]** Updated `CLAUDE.md` test-count prose (746 → 755) as instructed by the launching agent, even though it is outside the plan's `files_modified` list, to keep the documented invariant honest.

## Authentication Gates

None. No package-manager installs were required (existing Spring Security, gin/jwt, and NextAuth deps only). `npm ci` was run once to hydrate the frontend `node_modules` from the committed lockfile (no new packages added; lockfile unchanged).

## Runtime / Out-of-CI Notes (Keycloak audience mapper)

Per the plan's threat `T-bl2-06` (accepted) and env notes: the `core-api` audience mapper only changes the **token contents** once Keycloak re-imports the realm. CI validates JSON only — it does **not** run Keycloak. To activate the `aud=core-api` claim on real tokens in a running stack, the Keycloak DB must be dropped and the realm re-imported (per env-gotchas), then verified against a live token. This was **not** attempted here (out of CI scope). Until that re-import happens against a live realm, real Keycloak-minted tokens may lack the `core-api` audience and would be rejected by the now-strict validators — this is a deliberate fail-closed posture and a documented runtime/E2E step, not a CI concern.

## Known Stubs

None. No hardcoded empty values, placeholders, or unwired data sources were introduced.

## Threat Flags

None. All new surface is covered by the plan's existing `<threat_model>` (T-bl2-01..06). No new endpoints, auth paths, file access, or schema changes were introduced beyond the planned validators, realm config, and session change.

## Self-Check: PASSED

- All created files present: `AudienceValidator.java`, `AudienceValidatorTest.java`, `session-callback.ts`, `session-callback.test.ts`, hardened `realm-export.template.json`, this SUMMARY.
- All 3 task commits present in git history: `9f6f788`, `40874da`, `074a639`.
- SUMMARY.md left uncommitted (untracked) per the orchestrator handoff contract.

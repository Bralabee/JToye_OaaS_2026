---
phase: 22-notifications-comms
plan: 02
subsystem: notification/consent
tags: [consent, gdpr, pecr, rls, hmac, unsubscribe, suppression, multi-tenant]
requires:
  - "V51 current_tenant_id() RLS helper + RlsContractTest sweep"
  - "TenantSetLocalAspect (GUC pin from TenantContext on @Transactional DB ops)"
  - "SecurityConfig permitAll on /public/** + /api/v1/public/**"
  - "notification.unsubscribe.signing-secret config key (added by 22-01)"
provides:
  - "NotificationCategory enum (ORDERS/ONBOARDING/FINANCIAL/MARKETING) — owned here, consumed by 22-04 dispatch"
  - "ConsentGate.allows(tenantId, recipient, category) — the may-we-send gate"
  - "SuppressionService (isSuppressed / hasMarketingOptIn / idempotent suppress)"
  - "UnsubscribeTokenService (stateless HMAC token + constant-time verify)"
  - "PublicUnsubscribeController (no-auth /api/v1/public/unsubscribe)"
  - "V54 notification_suppression + marketing_opt_in tables (ENABLE+FORCE RLS)"
affects:
  - "22-04 dispatch (calls ConsentGate before every send; generates unsubscribe links via UnsubscribeTokenService)"
tech-stack:
  added: []
  patterns:
    - "V51 current_tenant_id() RLS helper form on both new tables"
    - "IdempotencyKeysRlsPolicyIntegrationTest NOSUPERUSER role-downgrade RLS proof"
    - "INSERT ... ON CONFLICT DO NOTHING idempotent write idiom"
    - "javax.crypto.Mac HmacSHA256 + MessageDigest.isEqual constant-time compare (JDK-native)"
    - "PublicStorefrontController dual-mapping {/public,/api/v1/public} no-auth surface"
key-files:
  created:
    - core-java/src/main/resources/db/migration/V54__notification_consent.sql
    - core-java/src/main/java/uk/jtoye/core/notification/consent/NotificationCategory.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/NotificationSuppression.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/NotificationSuppressionRepository.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/MarketingOptIn.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/MarketingOptInRepository.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/SuppressionService.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/ConsentGate.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/UnsubscribeTokenService.java
    - core-java/src/main/java/uk/jtoye/core/notification/consent/PublicUnsubscribeController.java
    - core-java/src/test/java/uk/jtoye/core/notification/consent/ConsentTablesRlsPolicyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/consent/ConsentGateTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/consent/UnsubscribeTokenServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/consent/PublicUnsubscribeControllerIntegrationTest.java
  modified: []
decisions:
  - "COMMS-03 marked complete: the plan assigns COMMS-03 solely to 22-02 (22-04 owns COMMS-01/02); all its must-have truths (gate refuses suppressed + un-opted-in marketing; unsubscribe writes suppression; RLS proven under NOSUPERUSER) are built and tested here. Dispatch (22-04) will exercise the gate at send time."
  - "Two tables (notification_suppression opt-out + marketing_opt_in) over a single tri-state preference table (RESEARCH Q2) — simpler to reason about; transactional default-on needs only the opt-out row, marketing needs only the opt-in row."
  - "Suppression is bounded-by-UNIQUE, NEVER time-pruned (threat T-22-02-04): a GDPR/PECR opt-out must not expire — deliberate divergence from any 'prune suppression' reading."
  - "UnsubscribeTokenService reads notification.unsubscribe.signing-secret via @Value env-default, NOT 22-01's NotificationProperties bean — keeps the plan parallel-safe; both read the same key."
  - "category is the NotificationCategory enum name (uppercase) on the wire — the token binds category.name(), and 22-04's link generator uses the same enum, so the contract is internally consistent."
metrics:
  duration: ~20m
  completed: 2026-07-15
  tasks: 3
  commits: 4
  files: 14
---

# Phase 22 Plan 02: Consent & Suppression Governance Summary

**One-liner:** GDPR/PECR consent backend for COMMS-03 — V54 `notification_suppression` + `marketing_opt_in` (ENABLE+FORCE RLS via `current_tenant_id()`, NOSUPERUSER-proven for both), a `ConsentGate` (transactional default-on with per-category suppression; marketing requires explicit opt-in), a stateless HMAC `UnsubscribeTokenService` (constant-time verify), and a no-auth `PublicUnsubscribeController` that writes suppression idempotently.

## What Was Built

**Task 1 — V54 migration + category enum + entities/repos + RLS proof (commit `8339620`)**
- `V54__notification_consent.sql`: two tenant-scoped tables, each `ENABLE` + `FORCE ROW LEVEL SECURITY` with policy `FOR ALL USING (tenant_id = current_tenant_id()) WITH CHECK (...)` — the V51 helper form, never the raw `current_setting(...)::uuid` cast. `notification_suppression(id, tenant_id, recipient, category, created_at, UNIQUE(tenant_id,recipient,category))` and `marketing_opt_in(id, tenant_id, recipient, opted_in_at, UNIQUE(tenant_id,recipient))`. Bounded-by-UNIQUE, no `_aud`, no prune (opt-out must persist).
- `NotificationCategory` enum (ORDERS/ONBOARDING/FINANCIAL/MARKETING) in `notification/consent` — owned here, consumed by 22-04.
- JPA entities + tenant-scoped Spring Data repos (`existsBy…`, `findBy…`); suppression has a native `insertIfAbsent` (`INSERT … ON CONFLICT DO NOTHING`) returning rows-affected.
- `ConsentTablesRlsPolicyIntegrationTest` (copied from `IdempotencyKeysRlsPolicyIntegrationTest`): under the NOSUPERUSER `rls_test_role`, cross-tenant SELECT → 0 rows and cross-tenant forged INSERT → `DataAccessException` "row-level security" — asserted for **both** tables.

**Task 2 — ConsentGate + SuppressionService + UnsubscribeTokenService (TDD: RED `a369af3` → GREEN `6837ac5`)**
- `UnsubscribeTokenService`: `tokenFor` = `base64url(HMAC-SHA256(secret, tenantId|email|category))`; `verify` recomputes and compares with `MessageDigest.isEqual` (constant-time). Secret via `@Value("${notification.unsubscribe.signing-secret:}")`.
- `SuppressionService`: `@Transactional` reads (`isSuppressed`, `hasMarketingOptIn`) + idempotent `suppress` (returns `true` only on a fresh opt-out).
- `ConsentGate.allows`: suppression refuses any category; transactional categories default-on; MARKETING requires an opt-in row.
- 11 unit tests (round-trip verify, category-scoping, determinism, single-char-flip + null-token rejection; transactional default-on/suppressed, marketing opt-in matrix).

**Task 3 — PublicUnsubscribeController (commit `6b6c933`)**
- Dual-mapped `{/public,/api/v1/public}/unsubscribe` (one-click POST + GET companion), permitAll inherited (no SecurityConfig change). Constant-time verify → on match pins the tenant from the verified token (`TenantContext` try/finally) and writes suppression idempotently; returns `unsubscribed` / `already_unsubscribed` / `invalid`.
- Non-enumerable (no address-exists signal) and PII-safe (never logs/echoes email or the token value).
- `PublicUnsubscribeControllerIntegrationTest` (MockMvc + Testcontainers + full security chain): valid token → exactly one row; replay → still one row (`already_unsubscribed`); tampered token → `invalid` + zero rows; reachable with no Bearer token.

## Verification

- `./gradlew :core-java:test --tests 'uk.jtoye.core.notification.consent.*'` — green (11 unit tests).
- `./gradlew :core-java:integrationTest --tests 'ConsentTablesRlsPolicyIntegrationTest' --tests 'PublicUnsubscribeControllerIntegrationTest' --tests 'RlsContractTest'` — green (EXIT=0).
- Acceptance greps: V54 has **0** raw `current_setting('app.current_tenant_id'` casts; controller source shows **no** cleartext `log.*email` / `log.*token`.

## Threat Model Coverage

| Threat ID | Mitigation | Where |
|-----------|-----------|-------|
| T-22-02-01 Unsubscribe-link forgery | HMAC-SHA256 token binding tenant+email+category; constant-time `MessageDigest.isEqual` | `UnsubscribeTokenService` |
| T-22-02-02 Email-existence enumeration | Identical write regardless of address; no "exists" signal; no PII in body/logs | `PublicUnsubscribeController` |
| T-22-02-03 Cross-tenant read of consent | ENABLE+FORCE RLS via `current_tenant_id()` on both tables; NOSUPERUSER-proven per table | V54 + `ConsentTablesRlsPolicyIntegrationTest` |
| T-22-02-04 GDPR opt-out expiring | Suppression bounded by UNIQUE upsert, no retention/prune job | V54 (documented) |
| T-22-02-SC Package installs | None installed (N/A) | — |

## Deviations from Plan

None — plan executed exactly as written. No Rule 1/2/3 auto-fixes were needed; no architectural (Rule 4) decisions arose. No authentication gates. No package installs (threat T-22-02-SC accept holds).

## Known Stubs

None. Every artifact is fully wired: entities → repos → services → controller, with RLS enforced at the DB. The consent gate is consumed by 22-04's dispatch (that wiring is 22-04's scope, not a stub here).

## Notes for Downstream (22-04)

- Call `ConsentGate.allows(tenantId, recipient, category)` before every send; refuse when `false`.
- Build unsubscribe links with `UnsubscribeTokenService.tokenFor(...)` and the uppercase `category.name()`; the public endpoint is `/api/v1/public/unsubscribe?tenant=&email=&category=&token=`.
- The signing secret is `notification.unsubscribe.signing-secret` (already in `application.yml`, empty default → inert until configured).

## Self-Check: PASSED

- All 14 created files verified present on disk.
- All 4 commits verified in `git log` (`8339620`, `a369af3`, `6837ac5`, `6b6c933`).

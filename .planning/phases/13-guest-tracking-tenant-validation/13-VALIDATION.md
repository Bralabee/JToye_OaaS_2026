---
phase: 13-guest-tracking-tenant-validation
plan: 01
requirement: SEC-01
nyquist_compliant: false   # Flips to true once Task 13-01-05 completes the full suite green
created: 2026-04-18
status: scaffold
---

# Phase 13 Validation Report (Nyquist)

**Phase:** 13 — Guest Tracking Tenant Validation
**Requirement:** SEC-01
**Nyquist compliance:** pending execution — flips to `true` upon completion of Task 13-01-05.

This file is the planner's scaffold. Task 13-01-05 updates the Status column
with PASS/FAIL after the full test run and flips `nyquist_compliant: true`
once every task verify command has succeeded.

## Per-Task Verification Map

| Task | Type | Verify Command (automated) | Expected | Status |
|------|------|----------------------------|----------|--------|
| 13-01-01 | RED (integration) | `./gradlew :core-java:test --tests "CrossTenantSpoofIntegrationTest.crossTenantJwtReturns403OnProducts" -PincludeIntegration` | Test FAILS with 200 (not 403) — vulnerability reproduced; compile succeeds | TBD |
| 13-01-02 | RED (compile) | `./gradlew :core-java:compileTestJava` | Compile FAILS with `cannot find symbol: method resolvePublicShopForSlug` | TBD |
| 13-01-03 | GREEN (helper + refactor) | `./gradlew :core-java:test --tests "PublicStorefrontServiceTest.resolvePublicShopForSlug*" --tests "CrossTenantSpoofIntegrationTest.crossTenantJwtReturns403OnProducts" -PincludeIntegration` | 5 tests PASS green | TBD |
| 13-01-04 | GREEN (ReviewService) | `./gradlew :core-java:test --tests "CrossTenantSpoofIntegrationTest.crossTenantJwtReturns403OnReviews" -PincludeIntegration` | PASS green; ReviewService helper applied to both getShopReviews + createReview | TBD |
| 13-01-05 | Regression gate | `./gradlew :core-java:test -PincludeIntegration` | Full suite PASS (390+5 baseline); log-capture assertion passes; VALIDATION.md flipped to `nyquist_compliant: true` | TBD |

## Phase Success Criteria → Test Coverage (ROADMAP Phase 13 lines 82-87)

| SC | Criterion | Test Method(s) | File | Status |
|----|-----------|----------------|------|--------|
| SC-1 | Guest session on tenant A cannot retrieve tenant B data via `/public/shops/{B-slug}/...` — rejected with 403 + structured audit log entry | `crossTenantJwtReturns403OnProducts`, `crossTenantJwtReturns403OnReviews`, `crossTenantJwtReturns403OnCreateOrder`, `crossTenantRequestLogsSpoofEvent` | `core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java` | TBD |
| SC-2 | Legitimate browse flow on tenant A still passes (no regression) | `sameTenantJwtSucceeds`, `anonymousGuestSucceeds`, plus full existing `PublicStorefrontServiceTest` green | `core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java` + `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` | TBD |
| SC-3 | Cross-tenant spoof covered by integration test — seed 2 tenants + attempt spoof + assert 403 | `CrossTenantSpoofIntegrationTest` entire class (two hardcoded tenant UUIDs, published shops per tenant, MockMvc + JWT post-processor) | `core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java` | TBD |
| SC-4 | `GuestTrackingService` (or equivalent — resolved to `PublicStorefrontService`) has explicit unit tests for tenant-match, tenant-mismatch, missing-tenant | `resolvePublicShopForSlug_whenNoUpstreamTenant_setsContextFromSlug`, `_whenUpstreamMatches_setsContextFromSlug`, `_whenUpstreamMismatches_throwsTenantAccessDeniedException`, `_whenSlugUnknown_throwsResourceNotFoundException` | `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` | TBD |

## Threat Mitigation Coverage

| Threat | Test Method | Expected Outcome |
|--------|-------------|------------------|
| T-13-01 Tampering (URL slug spoof) | `crossTenantJwtReturns403OnProducts` | 403 before TenantContext override |
| T-13-02 Information Disclosure (cross-tenant READ) | `crossTenantJwtReturns403OnProducts`, `crossTenantJwtReturns403OnReviews` | 403 before any RLS-scoped query runs |
| T-13-03 Elevation of Privilege (cross-tenant WRITE — highest severity) | `crossTenantJwtReturns403OnCreateOrder` | 403 before `Order.setTenantId` or row insert |

## Audit Log Contract

Exact structured log format pinned by `crossTenantRequestLogsSpoofEvent`:

```
event=tenant_spoof_attempt slug=<slug> slugTenant=<uuid> upstreamTenant=<uuid> outcome=403
```

Reviews-path variant includes additional discriminator:
```
event=tenant_spoof_attempt slug=<slug> slugTenant=<uuid> upstreamTenant=<uuid> outcome=403 source=reviews
```

Both parseable by Loki/ELK (Phase 9 infrastructure); alertable via Alertmanager.

## TDD Gate Compliance

- RED commits expected: 2 (Task 01, Task 02) — prefixed `test(13-01): RED —`
- GREEN commits expected: 2 (Task 03, Task 04) — prefixed `feat(13-01): GREEN —`
- Regression-guard commit expected: 1 (Task 05) — prefixed `test(13-01):`
- Total atomic commits expected on feature branch: 5

## Compliance Checklist (flipped to true in Task 05)

- [ ] Every task has an automated `<verify>` block (Nyquist)
- [ ] Testcontainers Postgres used (NOT H2) — Phase 12 Deviation #4 applied
- [ ] JWT claim key is `tenant_id` (Pitfall 3 avoided)
- [ ] 4 ROADMAP Phase 13 Success Criteria mapped to test methods (SC-1..SC-4)
- [ ] STRIDE T-13-01 / T-13-02 / T-13-03 mitigations exercised
- [ ] 403 response body generic per ASVS V4.1.5 (tenant UUIDs NOT leaked to client)
- [ ] ReviewService.java coverage added (RESEARCH.md Assumption A2 resolved: lines 52-56 confirmed vulnerable)
- [ ] Feature branch, no direct commit to main (global git policy)

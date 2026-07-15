---
phase: 22-notifications-comms
plan: 03
subsystem: api
tags: [webhooks, rls, ssrf, hmac, postgres, spring-boot, secure-random, rfc7807]

# Dependency graph
requires:
  - phase: 22-notifications-comms (22-01/22-02)
    provides: NotificationChannel seam + V54 consent (parallel-safe siblings; own webhook/* package)
  - phase: prior milestones
    provides: current_tenant_id() RLS helper (V51), GlobalExceptionHandler (RFC 7807), TenantContext + TenantFilter, IdempotencyKeys RLS test harness, out-of-order Flyway
provides:
  - webhook_subscription table (V55, ENABLE+FORCE RLS via current_tenant_id(), plaintext signing_secret)
  - WebhookEventType enum (4 families → order.state.* / order.refunded / onboarding.state.* / payment.*)
  - WebhookSubscription entity + tenant-scoped repository (status ACTIVE/PAUSED/AUTO_PAUSED/REVOKED)
  - WebhookUrlValidator (HTTPS-only + SSRF block, config-toggled)
  - WebhookSubscriptionService (SecureRandom secret, create/list/get/rotate/pause/resume/revoke)
  - WebhookSubscriptionController (/api/v1/webhooks CRUD + custom actions, RFC 7807, secret-once)
affects: [22-05 (webhook delivery engine consumes subscriptions), 22-06 (dashboard webhook UI)]

# Tech tracking
tech-stack:
  added: []  # zero new dependencies — JDK SecureRandom/InetAddress + existing Spring/Jakarta/Testcontainers
  patterns:
    - "V51 helper-form RLS (current_tenant_id()) on every new table; NOSUPERUSER-proven"
    - "Plaintext credential under FORCE RLS (V50 response_body rationale) — secret shown once, never re-fetchable"
    - "Anti-SSRF egress-target validation: HTTPS-only + InetAddress private/loopback/link-local block, fail-closed, config toggle"
    - "Hard-coded /api/v1 prefix for controllers outside WebConfig.API_V1_PACKAGES (RefundController precedent)"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V55__webhook_subscription.sql
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookEventType.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscription.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscriptionRepository.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookUrlValidator.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscriptionService.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscriptionController.java
    - core-java/src/main/java/uk/jtoye/core/webhook/dto/CreateWebhookSubscriptionRequest.java
    - core-java/src/main/java/uk/jtoye/core/webhook/dto/WebhookSubscriptionDto.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookSubscriptionRlsPolicyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookSubscriptionServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookUrlValidatorTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookSubscriptionControllerIntegrationTest.java
  modified: []

key-decisions:
  - "Controller mounts /api/v1/webhooks hard-coded (webhook pkg is NOT in WebConfig.API_V1_PACKAGES); mirrors RefundController — avoids editing WebConfig and stays within webhook/*"
  - "event_types persisted as TEXT[] of enum names (Review.photoUrls mapping); service converts to/from typed WebhookEventType at its boundary"
  - "Status enum nested in WebhookSubscription; create/rotate secret response nested as WebhookSubscriptionDto.WithSecret — keeps everything in the plan's declared files"
  - "SSRF host-resolution is config-toggled (webhook.target.block-private-ranges, default ON); controller integration test disables it for hermeticity while HTTPS stays enforced"

patterns-established:
  - "Anti-SSRF webhook target validation reusable by the 22-05 delivery worker (re-validate at egress)"
  - "Plaintext-secret-once contract: returned on create+rotate only, never on GET/list; rotate regenerates via SecureRandom"

requirements-completed: [COMMS-04]

# Metrics
duration: 50min
completed: 2026-07-15
---

# Phase 22 Plan 03: Vendor Webhook Subscriptions Summary

**Tenant-scoped webhook subscription resource (V55 FORCE-RLS table + REST API) with SecureRandom HMAC secrets shown once, HTTPS-only + SSRF-blocked target URLs, and NOSUPERUSER-proven isolation.**

## Performance

- **Duration:** ~50 min (5 Testcontainers boots; environment clock skew noted)
- **Started:** 2026-07-15T02:49:29Z
- **Completed:** 2026-07-15T03:01:53Z
- **Tasks:** 3
- **Files modified:** 13 created (0 modified)

## Accomplishments
- **V55 `webhook_subscription`** — ENABLE+FORCE RLS via the `current_tenant_id()` helper (not the raw `::uuid` cast), plaintext `signing_secret` as a FORCE-RLS-protected credential, `(tenant_id, status)` index. Applies cleanly under `out-of-order=true`; `RlsContractTest` green.
- **Cross-tenant isolation proven** — `WebhookSubscriptionRlsPolicyIntegrationTest` shows a cross-tenant list returns 0 rows and a forged write is denied under the `rls_test_role` NOSUPERUSER downgrade.
- **SSRF-aware `WebhookUrlValidator`** — rejects non-HTTPS and loopback / any-local / link-local (incl. `169.254.169.254` metadata) / RFC1918 / multicast / IPv6-ULA targets; fail-closed on unresolvable hosts.
- **`WebhookSubscriptionService`** — `SecureRandom` 256-bit base64url secret; create/list/getById/rotate-secret/pause/resume(reset failures)/revoke; secret returned plaintext exactly once, never logged.
- **`WebhookSubscriptionController`** — `/api/v1/webhooks` CRUD + custom actions; secret-once on create+rotate, never on GET; non-HTTPS → 400 and unknown id → 404, both RFC 7807.

## Task Commits

1. **Task 1: V55 migration + entity + repo + WebhookEventType** — `590b3bc` (feat)
2. **Task 2 (RED): failing validator/service/RLS tests** — `53483a6` (test)
3. **Task 2 (GREEN): WebhookUrlValidator + WebhookSubscriptionService** — `24956c6` (feat)
4. **Task 3: WebhookSubscriptionController + integration test** — `06e0ce7` (feat)
5. **Deferred-items log (OpenAPI snapshot)** — `07a6632` (docs)

_TDD: Task 2 followed RED (`53483a6`, 13/14 failing against stubs) → GREEN (`24956c6`)._

## Files Created/Modified
- `db/migration/V55__webhook_subscription.sql` — FORCE-RLS table, helper-form policy, plaintext secret, (tenant,status) index
- `webhook/WebhookEventType.java` — 4 event families + AMQP routing keys
- `webhook/WebhookSubscription.java` — JPA entity (event_types TEXT[], nested Status enum, timestamp callbacks)
- `webhook/WebhookSubscriptionRepository.java` — tenant-scoped finders (findByTenantId / findByStatus / findByIdAndTenantId)
- `webhook/WebhookUrlValidator.java` — HTTPS + anti-SSRF guard, config-toggled, fail-closed
- `webhook/WebhookSubscriptionService.java` — SecureRandom secret + lifecycle, secret-once, no secret logging
- `webhook/WebhookSubscriptionController.java` — /api/v1/webhooks CRUD + rotate/pause/resume/revoke (RFC 7807)
- `webhook/dto/CreateWebhookSubscriptionRequest.java` — @Valid HTTPS @Pattern + @NotEmpty eventTypes
- `webhook/dto/WebhookSubscriptionDto.java` — read DTO (no secret) + nested `WithSecret` create/rotate response
- `webhook/*Test.java` (4) — validator unit, service unit, RLS integration, controller integration

## Decisions Made
- **Controller path `/api/v1/webhooks` hard-coded.** `uk.jtoye.core.webhook` is deliberately not in `WebConfig.API_V1_PACKAGES`; adding it would edit a file outside `webhook/*` (against the plan invariant). Hard-coding the prefix mirrors `RefundController` and keeps the change contained. `ServletUriComponentsBuilder.fromCurrentRequest()` still builds correct Location headers.
- **`event_types` as `TEXT[]` of enum names.** Mirrors the proven `Review.photoUrls` array mapping; the service is the typed-enum boundary. Request/response DTOs use `List<WebhookEventType>` for a machine-parseable typed contract (unknown values → 400 via Jackson).
- **Nested `Status` and `WithSecret`.** Kept as nested types inside `WebhookSubscription` / `WebhookSubscriptionDto` so no file outside the plan's declared list was introduced.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Controller prefix hard-coded instead of WebConfig-prefixed**
- **Found during:** Task 3 (controller)
- **Issue:** The plan specified `@RequestMapping("/webhooks")` "→ /api/v1/webhooks via the WebConfig prefix", but `WebConfig.API_V1_PACKAGES` does not include `uk.jtoye.core.webhook`, so the bare mapping would serve at `/webhooks` (unversioned). Adding the package to WebConfig would touch a file outside `webhook/*`, violating the plan invariant.
- **Fix:** Hard-coded `@RequestMapping("/api/v1/webhooks")` (RefundController precedent); Location via `ServletUriComponentsBuilder`.
- **Verification:** Controller integration test hits `/api/v1/webhooks*` and passes (create 201, GET/rotate/revoke, 400/404).
- **Committed in:** `06e0ce7`

**2. [Rule 3 - Blocking, deferred] OpenAPI snapshot is stale (cross-plan) — not regenerated here**
- **Found during:** Task 3 (controller)
- **Issue:** Adding a controller changes `/v3/api-docs`, which `OpenApiSnapshotTest` byte-asserts against `docs/api/openapi-snapshot.json`. Regenerating showed the committed snapshot was already missing Phase 21 onboarding endpoints and 22-02's `/api/v1/public/unsubscribe` — i.e. `OpenApiSnapshotTest` is already red on this branch independent of 22-03. The snapshot is a whole-spec artifact; a webhook-only partial regen is impossible.
- **Fix:** Per the SCOPE BOUNDARY rule and the "stay within webhook/* + V55" invariant, the snapshot was left untouched and the reconciliation logged to `deferred-items.md` for the phase gate (`updateOpenApiSnapshot` alongside `docs-freshness --write`). Verified the regeneration is additive (removes no endpoint).
- **Files modified:** `.planning/phases/22-notifications-comms/deferred-items.md`
- **Committed in:** `07a6632`

---

**Total deviations:** 2 (1 blocking-fixed within webhook/*, 1 blocking-deferred as out-of-scope cross-plan reconciliation)
**Impact on plan:** No behavior scope creep. All plan artifacts delivered within the `webhook/*` package + V55 as required.

## Issues Encountered
- **Java TDD RED for new classes:** a test referencing a not-yet-existing class fails to *compile* rather than *assert*. Handled the standard way — the RED commit (`53483a6`) includes minimal validator/service stubs so the failure is an assertion failure (13/14 red), then GREEN (`24956c6`) implemented the logic.
- **Environment clock skew:** Testcontainers log timestamps and `date` disagreed by ~50 min; duration is the wall-clock estimate.

## Threat Flags
None — the surface introduced (webhook subscription CRUD, plaintext secret, vendor-supplied egress URL) is exactly the `<threat_model>` register (T-22-03-01..04). Mitigations applied: SSRF validator (T-01), FORCE-RLS + secret-not-in-read-DTO (T-02/03), rotate-replaces-secret (T-04).

## Known Stubs
None — no placeholder/empty-data stubs. (The RED-phase stubs in `53483a6` were fully implemented in `24956c6`.)

## User Setup Required
None - no external service configuration required. (`webhook.target.block-private-ranges` defaults ON; the yml key is documented for 22-05, which owns application.yml webhook config.)

## Next Phase Readiness
- **22-05 (delivery engine):** consumes `WebhookSubscription` (ACTIVE subs by event type), the plaintext `signing_secret` (for HMAC re-signing), and `consecutive_failures`/`AUTO_PAUSED` (auto-pause). `WebhookUrlValidator` is reusable for egress-time re-validation.
- **22-06 (UI):** `WebhookSubscriptionDto` (no secret) + `WithSecret` (create/rotate) back the management + secret-reveal screens; status taxonomy (ACTIVE/PAUSED/AUTO_PAUSED/REVOKED) drives the badge map.
- **Phase-gate blocker:** OpenAPI snapshot needs one `updateOpenApiSnapshot` (see `deferred-items.md`) before the full `integrationTest` suite is green.

## Self-Check: PASSED

All 13 declared files exist on disk; all 5 commits (`590b3bc`, `53483a6`, `24956c6`, `06e0ce7`, `07a6632`) are present in git history.

---
*Phase: 22-notifications-comms*
*Completed: 2026-07-15*

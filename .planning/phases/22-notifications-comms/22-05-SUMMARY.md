---
phase: 22-notifications-comms
plan: 05
subsystem: webhook/delivery
tags: [webhooks, hmac, rls, ssrf, backoff, skip-locked, scheduled, rabbitmq, retention, replay, tdd, comms-05]

# Dependency graph
requires:
  - phase: 22-notifications-comms (22-03)
    provides: WebhookSubscription / WebhookEventType / WebhookUrlValidator / plaintext signing_secret / consecutive_failures + AUTO_PAUSED status
  - phase: 22-notifications-comms (22-04)
    provides: webhook.deliveries fanout queue (WEBHOOK_DELIVERIES_QUEUE) bound to all four families
  - phase: prior milestones
    provides: current_tenant_id() RLS helper (V51), PaymentEventOutboxFlusher (SKIP-LOCKED + computeBackoffMillis), ScheduledCleanupService, CompaniesHouseClient (WebClient egress), OrderStateChangeListener GUC preamble, TenantSetLocalAspect, IdempotencyKeys RLS test harness, out-of-order Flyway
provides:
  - webhook_delivery table (V56, ENABLE+FORCE RLS via current_tenant_id(), claim/retention/subscription indexes)
  - WebhookSigner (HMAC-SHA256 Stripe t=,v1= over exact POSTed bytes; constant-time verify)
  - WebhookEventEnvelope (LOCKED { id, type, tenantId, occurredAt, version, data } wire contract)
  - WebhookFanoutListener (webhook.deliveries consumer → INSERT one PENDING row per matching ACTIVE subscription; no inline HTTP)
  - WebhookDeliveryWorker (@Scheduled per-tenant SKIP-LOCKED delivery + bounded backoff + auto-pause + per-subscription isolation)
  - WebhookRetentionCleanup (@Scheduled bounded prune of webhook_delivery ONLY)
  - WebhookDeliveryController (delivery log + tagged manual replay, RFC 7807)
  - WebhookProperties + webhook.* config keys (all tunables config-injected)
affects: [22-06 (dashboard webhook delivery-log + replay UI consumes these endpoints)]

# Tech tracking
tech-stack:
  added: []  # zero new deps — JDK javax.crypto.Mac + existing WebClient/AMQP/Testcontainers
  patterns:
    - "Dedicated per-(subscription,event) delivery table drained by a per-tenant TransactionTemplate + FOR UPDATE SKIP LOCKED claim (PaymentEventOutboxFlusher shape) for per-subscription isolation"
    - "HMAC-SHA256 t=,v1= signing over the EXACT stored bytes (serialize-once envelope) — sign what you POST (Pitfall 6)"
    - "Auto-pause IS the per-subscription circuit breaker (a SHARED resilience4j breaker would itself be a head-of-line block)"
    - "SSRF re-validation at egress via WebhookUrlValidator (defence against DNS-rebinding / pre-tightening subscriptions)"
    - "Replay = a NEW tagged row reusing the original envelope id so receiver dedupe makes retries idempotent"
    - "WebClient egress mocked via injected ExchangeFunction + MockClientHttpRequest body capture (no cert, no network) for HMAC-over-exact-bytes proof"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V56__webhook_delivery.sql
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDelivery.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryRepository.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookProperties.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSigner.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookEventEnvelope.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryWorker.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookRetentionCleanup.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryController.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookDeliveryRlsPolicyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookSignerTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookDeliveryWorkerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookRetentionCleanupTest.java
  modified:
    - core-java/src/main/resources/application.yml

key-decisions:
  - "Envelope data = the domain EVENT record (OrderStateChangeEvent / RefundEvent / OnboardingStateChangeEvent / PaymentEvent), not a re-loaded OrderDto — reuses the exact DTO already flowing through the outbox (honours D-05 'reuse existing DTOs, no bespoke minimal shape') without coupling the fanout to every domain's repo/mapper or a stale 'order gone' edge"
  - "No annotation @CircuitBreaker on egress — a SHARED webhook breaker would itself HOL-block healthy subscriptions when a hostile one trips it; per-subscription auto-pause is the correct breaker. WebClient + config timeout for egress"
  - "Nested WebhookDeliveryView record inside WebhookDeliveryController (stay within the plan's file list; 22-03 WithSecret precedent); the log view omits payload"
  - "Controller mounts /api/v1/webhooks/{id}/deliveries hard-coded (webhook pkg NOT in WebConfig.API_V1_PACKAGES; 22-03 / RefundController precedent)"

requirements-completed: [COMMS-05]

# Metrics
duration: ~32min
completed: 2026-07-15
---

# Phase 22 Plan 05: Signed Webhook Delivery Engine Summary

**The machine channel (COMMS-05, absorbed #205): a V56 FORCE-RLS `webhook_delivery` table + HMAC-SHA256 (`t=,v1=`) signer + versioned envelope + a fanout listener that INSERTs one PENDING row per matching ACTIVE subscription, drained by a `@Scheduled` per-tenant SKIP-LOCKED worker that signs the exact stored bytes, POSTs with bounded exponential backoff, auto-pauses a permanently-failing subscription with NO head-of-line block to healthy ones, and a bounded retention prune + tagged manual replay — all NOSUPERUSER-proven and TDD-driven.**

## Performance

- **Duration:** ~32 min (multiple Testcontainers boots; environment clock skew noted, as in 22-03/22-04)
- **Started:** 2026-07-15T03:39:58Z
- **Completed:** 2026-07-15T04:12:01Z
- **Tasks:** 3
- **Files:** 15 (14 created, 1 modified)

## What Was Built

**Task 1 — V56 FORCE-RLS delivery table + entity/repo + WebhookProperties + config + RLS test (`599d2d5`, feat)**
- `V56__webhook_delivery.sql`: `(id, tenant_id, subscription_id, event_id, event_type, payload, status, attempt_count, next_attempt_at, last_http_status, last_error, is_replay, replay_of, created_at, updated_at)`; ENABLE+FORCE RLS via `current_tenant_id()` (helper form, NOT the raw `::uuid` cast — `RlsContractTest.noPolicyUsesRawTenantGucCast` green); claim index `(status, next_attempt_at)`, retention index `(created_at)`, subscription index.
- `WebhookDelivery` entity + `WebhookDeliveryRepository` (`claimDueBatch` native `FOR UPDATE SKIP LOCKED`, `deleteByCreatedAtBefore` prune finder, paged `findLog` filter, `findByIdAndSubscriptionId` replay lookup).
- `WebhookProperties` (`@ConfigurationProperties(prefix="webhook")`): every tunable config-injected (interval / batch / max-attempts / backoff base+cap / auto-pause threshold / retention-days + interval / timeout / signature tolerance / envelope version / block-private-ranges) — GLOBAL_RULE_6.
- `WebhookDeliveryRlsPolicyIntegrationTest`: cross-tenant SELECT under the NOSUPERUSER `rls_test_role` returns 0 rows; cross-tenant forged write denied.

**Task 2 — HMAC signer + versioned envelope + fanout listener (TDD: RED `1078d1e` → GREEN `0385dc2`)**
- `WebhookSigner`: JDK `Mac` HmacSHA256 → `t=<unixSeconds>,v1=<hex>` over `ts + "." + rawBody`; constant-time `verify` (`MessageDigest.isEqual`). `WebhookSignerTest` (RED 3/4 failing against a stub) asserts the `^t=\d+,v1=[0-9a-f]{64}$` scheme, determinism, byte-flip invalidation, and secret-rotation failure.
- `WebhookEventEnvelope`: LOCKED `{ id, type, tenantId, occurredAt, version, data }` record.
- `WebhookFanoutListener`: `@RabbitListener(webhook.deliveries)` with `@RabbitHandler` per family; per-tenant `TransactionTemplate` + `TenantContext`/GUC preamble; serializes the envelope ONCE and INSERTs one PENDING row per matching ACTIVE subscription (one shared envelope id per event) — NO inline HTTP.

**Task 3 — delivery worker + retention prune + log/replay controller (TDD: RED `4a18a0f` → GREEN `02f266c`)**
- `WebhookDeliveryWorker` (`@Scheduled`): per-tenant `TransactionTemplate` + `claimDueBatch` SKIP-LOCKED; reads the subscription's CURRENT secret, re-validates the URL (SSRF), signs the exact stored bytes with a fresh timestamp, POSTs via `WebClient` with `X-JToye-Signature`/`X-JToye-Event-Id`/`X-JToye-Event-Type`; 2xx → DELIVERED (reset failures), failure → RETRYING with `computeBackoffMillis` (copied verbatim incl. the overflow guard) or FAILED at max-attempts; consecutive-failure counter → AUTO_PAUSED at threshold; a paused/revoked subscription's claimed rows are dropped to FAILED; each row processed defensively so one failure never rolls back the batch (isolation).
- `WebhookRetentionCleanup` (`@Scheduled`): per-tenant prune of `webhook_delivery` older than `retention-days` — scoped to `webhook_delivery` ONLY, never `notification_suppression` (SPEC AC #13).
- `WebhookDeliveryController` (`/api/v1/webhooks/{subscriptionId}/deliveries`): paged GET log (status + event-type filters) + `POST /{deliveryId}/replay` (tagged `is_replay`/`replay_of`, reuses the original envelope id, reads `Idempotency-Key`); RFC 7807 via `GlobalExceptionHandler`; nested `WebhookDeliveryView`.

## Verification

- `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.webhook.WebhookDeliveryRlsPolicyIntegrationTest' --tests 'uk.jtoye.core.security.RlsContractTest'` — green (V56 FORCE-RLS proven; raw-cast sweep clean with V56 present).
- `./gradlew :core-java:test --tests 'uk.jtoye.core.webhook.WebhookSignerTest'` — green 4/4 (RED 3/4 → GREEN 4/4).
- `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.webhook.WebhookDeliveryWorkerIntegrationTest' --tests 'uk.jtoye.core.webhook.WebhookRetentionCleanupTest'` — green: no-HOL (healthy DELIVERED while failing FAILED + AUTO_PAUSED), HMAC recomputed over `t + "." + rawBody` matches `X-JToye-Signature`, tagged replay (one new `is_replay` row, original untouched, same event id), retention prunes old + keeps recent + never touches suppression.
- `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.webhook.*'` — green (22-03 subscription CRUD/RLS coexist with the new beans; no regression).

## Threat Model Coverage

| Threat ID | Mitigation | Where |
|-----------|-----------|-------|
| T-22-05-01 Forged/tampered payload | HMAC-SHA256 `t=,v1=` per-subscription secret; sign the EXACT POSTed bytes (serialize once) | `WebhookSigner` + worker; `WebhookSignerTest` + worker HMAC assertion |
| T-22-05-02 Replay of a captured webhook | signed timestamp + receiver tolerance (config 300s) + envelope `id` (`X-JToye-Event-Id`) dedupe; manual replay REUSES the original id | signer + fanout + controller replay |
| T-22-05-03 SSRF at delivery time (DNS-rebinding) | re-apply `WebhookUrlValidator` (HTTPS + private/link-local block) before egress; WebClient timeout | `WebhookDeliveryWorker.attemptDelivery` |
| T-22-05-04 Head-of-line block | per-`(subscription,event)` rows + SKIP LOCKED + per-tenant tx + per-row defensive handling + auto-pause | worker + `WebhookDeliveryWorkerIntegrationTest` (500+200 proof) |
| T-22-05-05 Unbounded webhook_delivery growth (#107) | `WebhookRetentionCleanup` @Scheduled prune by retention window | `WebhookRetentionCleanup` + test |
| T-22-05-06 signing_secret in logs | log HTTP status / failure class only, never the secret | worker `recordSuccess`/`recordFailure` |
| T-22-05-07 Cross-tenant read of delivery payloads | webhook_delivery ENABLE+FORCE RLS via `current_tenant_id()`, NOSUPERUSER-proven | V56 + `WebhookDeliveryRlsPolicyIntegrationTest` |
| T-22-05-SC package installs | none installed (N/A) | — |

## Deviations from Plan

### Auto-fixed / interpretation decisions

**1. [Rule 3 - Interpretation] Envelope `data` = the domain EVENT record, not a re-loaded full OrderDto**
- **Found during:** Task 2 (fanout).
- **Issue:** the plan/D-05 says wrap "the full existing DTO (OrderDto)". The fanout listener receives the domain *event* (`OrderStateChangeEvent` etc.); re-loading `OrderDto` would couple the fanout to every domain's repository + mapper and introduce an "order no longer exists" edge in the delivery path.
- **Fix:** the envelope `data` carries the exact domain event record already flowing through the V46 outbox. This honours D-05's actual intent ("reuse existing DTOs — no bespoke minimal shape"): the event IS the established DTO, not a new minimal shape. The versioned envelope + event id + type give the vendor everything needed to identify the event and GET the full resource.
- **Files:** `WebhookFanoutListener.java`.

**2. [Rule 3 - Blocking] No annotation `@CircuitBreaker` on egress**
- **Found during:** Task 3 (worker).
- **Issue:** the plan referenced a `@CircuitBreaker`-guarded WebClient (CompaniesHouseClient posture). A SHARED `webhook` circuit breaker would itself be a head-of-line block — one hostile endpoint tripping the breaker would starve healthy subscriptions, contradicting COMMS-05's per-subscription isolation. (An annotation CB would also be a self-invocation no-op inside the worker.)
- **Fix:** per-subscription **auto-pause** IS the correct per-subscription breaker; egress uses `WebClient` + config `timeout-seconds`. The `resilience4j.circuitbreaker.instances.webhook` entry is left PRE-DECLARED in `application.yml` (house style, mirroring the fhrs/companies-house pre-declaration) as a future per-target hook, intentionally not wired.
- **Files:** `WebhookDeliveryWorker.java`, `application.yml`.

**3. [Rule 2 - Config completeness] Added `webhook.delivery.retention-interval-ms` + a `webhook` resilience4j instance to `application.yml`**
- **Reason:** the retention `@Scheduled` needs a cadence key (not in the plan's exact tunable list); added as `${ENV:default}` (daily). The `webhook` CB instance is pre-declared per decision #2.
- **Files:** `application.yml`.

**Total deviations:** 3 (all within `webhook/*` + `V56` + `application.yml`; no behavioural scope creep — the LOCKED wire contract, RLS, backoff/auto-pause, retention and replay semantics are exactly as specified).

## Known Stubs

None. The RED-phase stubs (`WebhookSigner` in `1078d1e`; Worker/RetentionCleanup/Controller in `4a18a0f`) were fully implemented in their GREEN commits (`0385dc2`, `02f266c`).

## Deferred (phase-gate reconcile — see deferred-items.md)

- **OpenAPI snapshot:** `WebhookDeliveryController` adds `GET .../deliveries` + `POST .../{deliveryId}/replay`; the whole-spec `docs/api/openapi-snapshot.json` is regenerated ONCE at the phase gate (already stale for Phase 21 / 22-02 / 22-03 — additive only).
- **`docs/metrics.json` counts:** 22-05 adds 10 test methods (signer ×4, RLS ×2, worker ×3, retention ×1); the `docs-freshness` aggregate is reconciled once at the phase gate (RESEARCH sampling plan), not per-plan.

## User Setup Required

None for dev/test. All `webhook.*` keys are `${ENV:default}`; the delivery worker + retention prune run in-JVM (`@Scheduled`) with safe defaults (max-attempts 8, backoff 1s→1h, auto-pause 10, retention 30d, timeout 10s). Prod may override via the `WEBHOOK_*` env vars.

## Next Phase Readiness

- **22-06 (dashboard):** the delivery-log + replay UI consumes `GET /api/v1/webhooks/{subscriptionId}/deliveries` (paged, `status` + `eventType` filters) and `POST .../{deliveryId}/replay` (with `Idempotency-Key`); the `WebhookDeliveryView` status taxonomy (PENDING/DELIVERED/RETRYING/FAILED) + `is_replay`/`attempt_count`/`last_http_status` drive the log table + badges.

## Self-Check: PASSED

- All 14 created files verified present on disk; `application.yml` modified.
- All 5 task commits present in `git log`: `599d2d5` (Task 1 feat), `1078d1e` (Task 2 RED), `0385dc2` (Task 2 GREEN), `4a18a0f` (Task 3 RED), `02f266c` (Task 3 GREEN).
- Green suites: signer 4/4, delivery RLS + RlsContractTest, worker + retention integration, full `uk.jtoye.core.webhook.*` integration (22-03 coexistence).

## TDD Gate Compliance

Tasks 2 and 3 followed RED → GREEN: a `test(22-05)` commit precedes each `feat(22-05)` implementation commit (`1078d1e`→`0385dc2`, `4a18a0f`→`02f266c`). Task 1 (migration/config/entity + RLS proof) is exempt per the plan (`type="auto"`, non-behaviour-adding infra).

---
*Phase: 22-notifications-comms*
*Completed: 2026-07-15*

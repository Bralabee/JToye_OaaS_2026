---
phase: 30-live-stripe-test-mode-execution
plan: 25
subsystem: payment
tags: [stripe, billing, per-location, rls, idempotency, shop]
requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-01 StripeProperties.Billing + 30-24 StripeBillingService.subscribeOrUpdatePlan"
provides:
  - "D-04 owner ruling recorded in .planning/phases/30-live-stripe-test-mode-execution/evidence/30-25-d04-decision.txt (all-shops)"
  - "ShopRepository.countByTenantId(UUID) + StripeBillingService.locationQuantity(UUID, BillingPlanMode) setting SubscriptionCreateParams.Item.setQuantity(quantity) on SAAS_HYBRID_STANDARD and SAAS_FLAT_PREDICTABLE"
  - "BillingLocationSync updating Stripe SubscriptionItem quantity after shop create/delete commit via PROPAGATION_REQUIRES_NEW read tx + deterministic idempotency keys"
affects: [30-26, 30-27]
tech-stack:
  added: []
  patterns:
    - "Explicit tenant_id predicate on ShopRepository.countByTenantId(UUID) so shops_public_read (V16) never leaks other tenants' published shops into a tenant's location count under NOSUPERUSER"
    - "After-commit Stripe quantity reconciliation via TransactionSynchronization.afterCommit() + PROPAGATION_REQUIRES_NEW read transaction + RequestOptions idempotency keys"
key-files:
  created:
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-25-d04-decision.txt"
    - "core-java/src/main/java/uk/jtoye/core/payment/BillingLocationSync.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/BillingLocationCountIntegrationTest.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/BillingLocationSyncTest.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/BillingLocationSyncIntegrationTest.java"
  modified:
    - "core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java"
    - "core-java/src/main/java/uk/jtoye/core/payment/StripeBillingService.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/ShopService.java"
    - "core-java/src/test/java/uk/jtoye/core/payment/StripeBillingServiceTest.java"
key-decisions:
  - "D-04 all-shops rule: every shop row belonging to the tenant (ShopRepository.countByTenantId) is a billable location on SAAS_HYBRID_STANDARD and SAAS_FLAT_PREDICTABLE; WALK_SETUP_STEPDOWN bills quantity = 1 per account."
  - "Why ShopRepository.countByTenantId is load-bearing even under FORCE RLS: shops_public_read (V16) permits SELECT on published = true across all tenants, so a bare count() under Tenant A counts Tenant A's shops plus every other tenant's published shops. Proved by non-vacuity control and break arm in BillingLocationCountIntegrationTest."
  - "Why BillingLocationSync opens a PROPAGATION_REQUIRES_NEW read transaction inside afterCommit(): in afterCommit() the outer ShopService transaction has already committed (so isActualTransactionActive() is false), and reading inside REQUIRES_NEW both activates TenantSetLocalAspect (SET LOCAL app.current_tenant_id) under NOSUPERUSER and closes the JDBC connection before any Stripe HTTP call."
patterns-established:
  - "Stripe RequestOptions idempotency keys on billing mutations: billing-price-<tenant>-<mode>-<uuid>, billing-subscription-<tenant>-<priceId>, and billing-qty-<subscriptionItemId>-<quantity>-<eventToken>"
requirements-completed: [PAY-02]
completed: 2026-10-31
---

# Phase 30 Plan 25: Per-Location Billing Quantity & After-Commit Location Sync Summary

**Per-location Stripe subscription quantity (`SubscriptionCreateParams.Item.setQuantity(locationCount)`) on `SAAS_HYBRID_STANDARD` and `SAAS_FLAT_PREDICTABLE` plus after-commit `BillingLocationSync` on shop create and delete.**

## Accomplishments

- **Task 1 (`6bbcead6`) — Owner D-04 Decision Recorded**:
  - Recorded the owner-approved `all-shops` rule in `.planning/phases/30-live-stripe-test-mode-execution/evidence/30-25-d04-decision.txt`: `SAAS_HYBRID_STANDARD` (£39/location/mo) and `SAAS_FLAT_PREDICTABLE` (£79–£149/location/mo) set `quantity = ShopRepository.countByTenantId(tenantId)`; `WALK_SETUP_STEPDOWN` sets `quantity = 1` per account; `0` shops yields `quantity = 0`; any repository failure propagates before any Stripe call (`T-30-96`).
- **Task 2 (`6bbcead6`) — Per-Location Subscription Quantity & Idempotency**:
  - Added `long countByTenantId(UUID tenantId)` to `ShopRepository`.
  - Updated `StripeBillingService` with `isPerLocationMode(BillingPlanMode)` and `locationQuantity(UUID, BillingPlanMode)`, wired `SubscriptionCreateParams.Item.builder().setPrice(price.getId()).setQuantity(quantity)` in `subscribeOrUpdatePlan`, and passed `RequestOptions` idempotency keys on `Price.create` (`billing-price-<tenant>-<mode>-<uuid>`) and `Subscription.create` (`billing-subscription-<tenant>-<priceId>`) (`T-30-99`).
  - Added unit tests in `StripeBillingServiceTest` (9 tests) and Testcontainers `BillingLocationCountIntegrationTest` under `NOSUPERUSER`.
- **Task 3 (`2adcc942`) — After-Commit `BillingLocationSync` on Shop Create/Delete**:
  - Created `BillingLocationSync` registering `TransactionSynchronization.afterCommit()` in `syncAfterCommit(tenantId, eventToken)`, reading the committed location count inside a `PROPAGATION_REQUIRES_NEW` read transaction with `TenantContext` pinned (`T-30-98`), updating `SubscriptionItem` with idempotency key `billing-qty-<itemId>-<qty>-<eventToken>` (`T-30-99`), and swallowing/metering any Stripe error on `jtoye.billing.location_sync.failed` (`T-30-100`).
  - Wired `BillingLocationSync.syncAfterCommit` into `ShopService.createShop` and `ShopService.deleteShop`.
  - Added `BillingLocationSyncTest` (4 unit tests) and Testcontainers `BillingLocationSyncIntegrationTest` (2 integration tests under `NOSUPERUSER NOBYPASSRLS`).

## Verification & Break-Arm Proofs

- **Task 2 RED -> GREEN -> Break Arm**:
  - RED: `StripeBillingServiceTest` failed (`9 tests completed, 4 failed`, `rc=1`).
  - GREEN: `StripeBillingServiceTest` (`9/9`, `rc=0`) and `BillingLocationCountIntegrationTest` (`1/1`, `rc=0`).
  - Break arm: Replacing `countByTenantId` with an unscoped `@Query("SELECT COUNT(s) FROM Shop s")` caused `BillingLocationCountIntegrationTest` to fail (`rc=1`, `expected: 2L but was: 4L` because `shops_public_read` exposes other tenants' published shops under `NOSUPERUSER`). Restored by content.
- **Task 3 RED -> GREEN -> Break Arm**:
  - RED: Against stub `BillingLocationSync`, `BillingLocationSyncTest` (`4 tests completed, 4 failed`, `rc=1`) and `BillingLocationSyncIntegrationTest` (`2 tests completed, 2 failed`, `rc=1`) failed.
  - GREEN: `BillingLocationSyncTest` (`4/4`, `rc=0`), `uk.jtoye.core.shop.*` (`rc=0`), and `BillingLocationSyncIntegrationTest` (`2/2`, `rc=0`).
  - Break arm: Running `sync` synchronously inside `createShop` without `afterCommit` and without swallowing Stripe errors caused `BillingLocationSyncIntegrationTest` to fail (`2 tests completed, 2 failed`, `rc=1`). Restored by content (`git diff --stat` empty).

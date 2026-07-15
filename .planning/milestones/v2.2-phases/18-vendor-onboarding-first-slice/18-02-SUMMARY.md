---
phase: 18-vendor-onboarding-first-slice
plan: 02
subsystem: onboarding
tags: [vendor-onboarding, state-machine, spring-statemachine, guards, async, tenant-context, rls, gate-chain, auto-approve]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice (plan 01)
    provides: V43 schema, onboarding enums/entities/repos, OnboardingProperties (isAutoApprove)
  - phase: milestone-2 (order flow)
    provides: OrderStateMachineConfig/Service triad mirrored here; Spring StateMachine already on the classpath
provides:
  - Onboarding state machine (10 events, 9 states) with APPROVE/GO_LIVE/REINSTATE gate guards
  - VendorOnboardingService — create/submit/getMyOnboarding + single canonical transition (sole Shop.published writer)
  - OnboardingGate registry interface + GateResult record + GateChainRunner (materialise + @Async runAndRecompute)
  - onboarding.auto-approve is now a LIVE, consumed setting (auto-advances PENDING_APPROVAL -> APPROVED)
  - POST /api/v1/onboarding, POST /api/v1/onboarding/submit, GET /api/v1/onboarding/me
affects: [18-03-fhrs-gate, 18-04-companies-house-gate, 18-05-allergen-gate-and-go-live, 18-06-phase-closure]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Guard-veto detection via unchanged state: Spring StateMachine reports a guard-denied transition as ACCEPTED (event consumed) but does NOT change state; the service throws when state is unchanged after an accepted event"
    - "Data-driven gate registry: List<OnboardingGate> auto-collected by Spring; later slices add a gate by adding a bean, zero runner edits"
    - "@Async tenant propagation: runAndRecompute takes tenantId and re-establishes TenantContext in try/finally (no TaskDecorator exists) so RLS writes are honoured on the worker thread"
    - "@Lazy back-reference breaks the runner<->service cycle while keeping one canonical transition path"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineConfig.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineService.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingGate.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/GateResult.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/dto/CreateOnboardingRequest.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/dto/OnboardingDto.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/dto/GateDto.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/GateChainRunnerTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingSubmitIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
    - core-java/src/main/java/uk/jtoye/core/config/WebConfig.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java

key-decisions:
  - "Guard vetoes are detected by an unchanged post-event state, not only ResultType — Spring reports a guard-denied transition as ACCEPTED, so a ResultType-only check (as OrderStateMachineService uses, since Order has no guards) would silently let a blocked APPROVE/GO_LIVE through"
  - "GateChainRunner owns the async gate recompute and drives transitions through VendorOnboardingService (@Lazy) so approved_at stamping + the published side effect stay in ONE canonical transition method"
  - "Added uk.jtoye.core.onboarding to WebConfig.API_V1_PACKAGES so the controller mirrors ShopController's /api/v1 versioned surface (the plan mandated mirroring ShopController, which is /api/v1)"

patterns-established:
  - "Onboarding state-machine triad mirroring the Order triad, extended with repository-backed guards"
  - "TDD across a data + async slice: RED state-machine test (compile-fail) -> GREEN; RED Mockito gate-runner test -> GREEN; Testcontainers HTTP proof for the auto-approve toggle both ways"

requirements-completed: [VOB-01]

# Metrics
duration: 32min
completed: 2026-07-11
---

# Phase 18 Plan 02: Vendor Onboarding — Submit Slice + State Machine Summary

**A vendor can create -> submit -> read onboarding status end-to-end over HTTP, driven by an onboarding state machine (10 events / 9 states) that is the sole writer of `Shop.published`; a data-driven gate-chain registry + `@Async`, tenant-safe recompute stands ready for concrete gates, and `onboarding.auto-approve` is now a live setting that auto-advances a fully-passing onboarding `PENDING_APPROVAL -> APPROVED` (the APPROVE guard still enforced).**

## Performance

- **Duration:** ~32 min
- **Completed:** 2026-07-11
- **Tasks:** 3 (2 TDD)
- **Files:** 16 (13 created, 3 modified); 24 new Java `@Test` methods across 3 files

## Accomplishments
- **Onboarding state machine** (`VendorOnboardingStateMachineConfig` + `Service`) mirrors the Order triad: 10 events, 9 states, `REJECTED`/`WITHDRAWN` terminal. APPROVE/GO_LIVE/REINSTATE carry repository-backed `Guard`s that read the `onboardingId` message header and check the materialised gate rows.
- **Guard-veto correctness fix (found via the RED test):** Spring StateMachine reports a guard-denied transition as `ACCEPTED` (event consumed) but does not change state. The service now throws `InvalidStateTransitionException` when the post-event state is unchanged, so a blocked APPROVE/GO_LIVE surfaces as HTTP 400 instead of silently "succeeding".
- **`VendorOnboardingService`** does create/submit/getMyOnboarding with one canonical `transition()` (load -> `sendEvent` -> set status -> stamp timestamp -> GO_LIVE/SUSPEND/REINSTATE published side effect -> save). The GO_LIVE/SUSPEND/REINSTATE branch is the **only** caller of `ShopService.setPublished`, and the only path that flips `published=true`.
- **`ShopService.setPublished(UUID, boolean)`** is the single `published` mutation point (documented as the sole authorised writer of `published=true`); the `boolean` param autoboxes into the nullable `Boolean` field (no primitive migration).
- **Gate-chain registry**: `OnboardingGate` interface + `GateResult` record + `GateChainRunner`. `materialise` inserts one PENDING row per registered gate bean (idempotent on `(onboarding_id, gate_type)`); `runAndRecompute` is `@Async @Transactional`, re-establishes `TenantContext` from its `tenantId` param inside a `try/finally`, evaluates automatic gates, then recomputes over the gate ROWS and fires GATES_PASSED / GATE_FAILED — consuming `onboarding.auto-approve` to also fire APPROVE when enabled.
- **`onboarding.auto-approve` is now REAL and consumed:** proven both ways on real Postgres — `true` drives a fully-passing onboarding to `APPROVED` (`approvedAt` stamped, no explicit APPROVE call); `false` halts at `PENDING_APPROVAL`.
- **HTTP surface** at `/api/v1/onboarding` (create 201 DRAFT, submit 200 VERIFYING, GET /me), tenant resolved server-side, `GateDto` withholds raw `evidence`/`externalRef` from the vendor payload.

## Task Commits

1. **Task 1 (RED):** `64bf9a3` `test(18-02): add failing onboarding state-machine transition test`
2. **Task 1 (GREEN):** `01047d4` `feat(18-02): onboarding state machine config + service`
3. **Task 2 (RED):** `af49f71` `test(18-02): add failing gate-chain runner unit test`
4. **Task 2 (GREEN):** `a047b51` `feat(18-02): onboarding service + gate-chain registry + Shop.published owner`
5. **Task 3:** `8772f80` `feat(18-02): onboarding controller + DTOs + submit & auto-approve integration test`

## Decisions Made
- **Guard vetoes detected by unchanged state** rather than `ResultType` alone — the Order triad checks only `ACCEPTED` because Order has no guards; the onboarding guards required the stronger check (RED test drove this out).
- **`@Lazy` back-reference** of `VendorOnboardingService` into `GateChainRunner` breaks the runner<->service cycle (Spring prohibits circular refs by default) while keeping a single canonical transition path so `approved_at` stamping and the `published` side effect live in one place.
- **Recompute only advances from `VERIFYING`** (defensive): if a prior recompute already moved the onboarding on, a second (possibly racing) recompute skips rather than throwing an illegal-transition exception on the async thread — makes the auto-approve outcome deterministic regardless of interleaving with `submit()`'s own async kick.

## Deviations from Plan

### Adjustments (not scope changes)

**1. [Rule 1 - Bug] State-machine service detects guard vetoes via unchanged state**
- **Found during:** Task 1 (RED run — two guard-rejection tests did not throw).
- **Issue:** Spring StateMachine reports a guard-denied transition as `ResultType.ACCEPTED` (the event was consumed) with the state unchanged. A ResultType-only check (the Order pattern) let blocked APPROVE/GO_LIVE through.
- **Fix:** After `sendEvent`, throw `InvalidStateTransitionException` when the post-event state equals the source state. Every onboarding transition targets a different state, so an unchanged state after an accepted event means a guard vetoed it.
- **Files:** `VendorOnboardingStateMachineService.java`. **Committed in:** `01047d4`.

**2. [Rule 3 - Blocking] Added `uk.jtoye.core.onboarding` to `WebConfig.API_V1_PACKAGES`**
- **Issue:** The onboarding package was not in the `/api/v1` prefix list, so `@RequestMapping("/onboarding")` would have served at literal `/onboarding` — inconsistent with the ShopController conventions the plan mandated mirroring (ShopController is `/api/v1/shops`).
- **Fix:** Added the package so the endpoints are `/api/v1/onboarding`. **Files:** `WebConfig.java`. **Committed in:** `8772f80`.

**3. [Rule 2 - Correctness/clarity] `uq_onboarding_tenant` 409 branch in `GlobalExceptionHandler`**
- **Issue:** A second create per tenant violates `UNIQUE(tenant_id)`; the generic message was "Data integrity constraint violated".
- **Fix:** Added a message branch (reusing the existing `DataIntegrityViolationException -> 409` convention) for a clear "An onboarding already exists for this tenant". **Files:** `GlobalExceptionHandler.java`. **Committed in:** `a047b51`.

**4. [Rule 3 - Blocking] Output DTOs created in Task 2 rather than Task 3**
- **Issue:** `VendorOnboardingService` (Task 2) returns `OnboardingDto`/`GateDto`, so they must exist for Task 2's `compileJava` gate. `CreateOnboardingRequest` remained in Task 3.
- **Fix:** Created `OnboardingDto` + `GateDto` in the Task 2 GREEN commit. **Committed in:** `a047b51`.

**5. [Rule 2 - Added test coverage] `GateChainRunnerTest` (Mockito unit)**
- **Issue:** Task 2 is `tdd="true"` but its plan verify was `compileJava` only, with behaviour otherwise proven only in Task 3's integration test — no genuine RED for Task 2.
- **Fix:** Added a fast Mockito unit test asserting `materialise` idempotency, the recompute decision table, the auto-approve consumption, and the `TenantContext` clear — giving Task 2 a real RED->GREEN cycle. **Committed in:** `af49f71` (RED) / `a047b51` (GREEN).

**6. [Rule 3 - Adjustment] Gradle invocation matches the multi-project layout**
- Ran `./gradlew :core-java:test` (unit) and `:core-java:integrationTest` (the `@Tag("testcontainers")` task) from the repo root, without `-x checkstyleMain` (no such task) — same correction wave-1 documented.

---

**Total deviations:** 6 (1 bug fix surfaced by TDD, 2 shared-file additions for consistency/clarity, 1 DTO reordering, 1 added TDD coverage, 1 invocation fix). **Impact:** No scope change — all plan tasks delivered; the guard-veto fix and the added unit test strengthen correctness and the TDD gate.

## Issues Encountered
- Testcontainers logged a transient Postgres socket connect retry during container/JPA startup on the combined suite run; it self-recovered and the build was SUCCESSFUL (no test failed).

## TDD Gate Compliance
- **Task 1** (`tdd="true"`): RED `64bf9a3` (`test(...)`, compile-failed before the config/service existed) preceded GREEN `01047d4` (`feat(...)`). The RED run also surfaced the guard-veto bug — RED did real work.
- **Task 2** (`tdd="true"`): RED `af49f71` (`test(...)`, compile-failed before the runner/service existed) preceded GREEN `a047b51` (`feat(...)`).
- No REFACTOR commits were needed. RED preceded GREEN in git history for both.

## Known Stubs
None. This slice ships **zero concrete gate beans by design** — the `GateChainRunner` registry is intentionally empty until 18-03/04/05 add gate beans. This is not a stub: `materialise`/`runAndRecompute` are fully wired and exercised (the auto-approve integration test seeds a mandatory PASSED gate row directly to drive the recompute), and the design defers concrete gates to later plans.

## Threat Flags
None beyond the plan's `<threat_model>`. All four listed mitigations are implemented and tested:
- **T-18-02-T** (forced go-live): `ShopService.setPublished` is the single `published` writer, reached only from the guarded GO_LIVE/SUSPEND/REINSTATE side effect.
- **T-18-02-S** (tenant spoof): DTOs carry no `tenantId`; the service resolves it via `CurrentTenant.require()`.
- **T-18-02-E** (async tenant): `runAndRecompute` sets `TenantContext` in `try/finally`.
- **T-18-02-E2** (auto-approve bypass): auto-approve fires APPROVE only after GATES_PASSED, and the APPROVE guard re-checks all mandatory gates (proven can-veto in the unit test).

## Next Phase Readiness
- The gate registry + async runner are wired and tenant-safe; 18-03/04/05 add a concrete gate purely by adding an `OnboardingGate` bean — no edit to `GateChainRunner`.
- Go-live (`GO_LIVE`) and its `published=true` side effect + guard exist; 18-05 wires the vendor's go-live endpoint and the direct-update hardening regression test (T-18-02-T follow-up).
- **Note for 18-06 (closure):** this plan adds **24 Java `@Test` methods across 3 files** (13 state-machine unit + 7 gate-runner unit + 4 Testcontainers HTTP). Reconcile `docs/metrics.json` via `scripts/docs-freshness.sh --write` in the closure plan to keep the `docs-freshness` CI gate green (schema head unchanged at V43).

## Self-Check: PASSED
- All 13 created files verified present on disk.
- All 5 task commits (`64bf9a3`, `01047d4`, `af49f71`, `a047b51`, `8772f80`) verified in git history.
- `./gradlew :core-java:test --tests 'uk.jtoye.core.onboarding.*'` and `:core-java:integrationTest --tests 'uk.jtoye.core.onboarding.*'` both green.
- `setPublished(...true)` reachable only from the VendorOnboardingService GO_LIVE/REINSTATE side effect; `isAutoApprove` consumed in `GateChainRunner`.

---
*Phase: 18-vendor-onboarding-first-slice*
*Completed: 2026-07-11*

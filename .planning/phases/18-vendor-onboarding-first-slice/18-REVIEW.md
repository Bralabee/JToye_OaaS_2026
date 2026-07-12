---
phase: 18-vendor-onboarding-first-slice
reviewed: 2026-07-11T03:57:29Z
depth: standard
files_reviewed: 58
files_reviewed_list:
  - core-java/build.gradle.kts
  - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
  - core-java/src/main/java/uk/jtoye/core/config/WebConfig.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/client/CompaniesHouseClient.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/client/CompanyProfile.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/client/FhrsClient.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/client/FhrsEstablishment.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/dto/CreateOnboardingRequest.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/dto/GateDto.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/dto/OnboardingDto.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/gate/AllergenCompletenessGate.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/gate/CompaniesHouseGate.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/gate/FhrsGate.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/GateResult.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/GateStatus.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/GateType.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEvent.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingGate.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingModel.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingProperties.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingState.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingGate.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingGateRepository.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboarding.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingRepository.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineConfig.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineService.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
  - core-java/src/main/resources/application.yml
  - core-java/src/main/resources/db/migration/V43__vendor_onboarding.sql
  - core-java/src/test/java/uk/jtoye/core/onboarding/client/CompaniesHouseClientTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/client/FhrsClientTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/gate/AllergenCompletenessGateTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/GateChainRunnerTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/gate/CompaniesHouseGateTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/gate/FhrsGateTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingGoLiveIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingPropertiesTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingSubmitIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/VendorOnboardingEndToEndIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/VendorOnboardingPersistenceIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/VendorOnboardingRlsIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineServiceTest.java
  - docs/CHANGELOG.md
  - docs/metrics.json
  - frontend/app/dashboard/onboarding/page.tsx
  - frontend/app/dashboard/onboarding/__tests__/page.test.tsx
  - frontend/app/dashboard/page.tsx
  - frontend/app/dashboard/__tests__/page.test.tsx
  - frontend/components/dashboard/sidebar.tsx
  - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
  - frontend/components/marketing/operator-pitch.tsx
  - frontend/components/marketing/__tests__/operator-pitch.test.tsx
  - frontend/types/api.ts
findings:
  critical: 3
  warning: 3
  info: 10
  total: 16
status: issues_found
---

# Phase 18: Code Review Report

**Reviewed:** 2026-07-11T03:57:29Z
**Depth:** standard
**Files Reviewed:** 58
**Status:** issues_found

## Summary

Reviewed the vendor-onboarding first slice: V43 migration (aggregate + gate tables, FORCE RLS + `_aud` mirrors), the Spring StateMachine and its guards, the async gate-chain runner, the two external API clients (Companies House, FHRS), the allergen-completeness gate, the vendor-facing controller/service, the `Shop.published` sole-writer hardening in `ShopService`, and the frontend onboarding page, dashboard banner, sidebar nav, and marketing pitch, plus all unit/integration tests.

The security invariants specified in the review brief largely hold and are proven by tests: RLS is enforced on all four new tables (NOSUPERUSER integration proof), `TenantContext` is re-established with correct try/finally discipline in the async runner, `Shop.published` has exactly one writer path (create forces `false`, update snapshots/restores, `setPublished` is tenant-scoped), the Companies House key is env-injected, redacted in `toString`, never logged, and fails closed when unset, and the state-machine service correctly converts a guard veto into `InvalidStateTransitionException` via the post-event state-equality check.

However, three ship-blocking defects remain: (1) the `@Async` gate run is kicked from inside the uncommitted submit transaction, so on an adverse thread schedule the worker sees pre-submit state, no gates are ever evaluated, and the onboarding is stuck in VERIFYING forever with no retry path; (2) `createOnboarding` never validates that the submitted `shopId` exists in — or belongs to — the caller's tenant, letting a tenant bind its onboarding to another tenant's shop (the FK check bypasses RLS) and pass the FHRS hygiene gate on another tenant's published establishment; (3) the ACTION_REQUIRED state is a dead end (no RESUBMIT endpoint, FAILED gate rows are never re-evaluated) while the UI ships a "Re-run checks" button that can only ever return 400.

## Critical Issues

### CR-01: `@Async` gate run fires inside the uncommitted submit transaction — race leaves onboarding permanently stuck in VERIFYING

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java:83-93` (and `core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java:101-175`)
**Issue:** `submit()` is `@Transactional`. Inside that still-open transaction it (a) transitions DRAFT→VERIFYING, (b) materialises the PENDING gate rows, and (c) calls `gateChainRunner.runAndRecompute(...)`, which is `@Async @Transactional`. The async task is *enqueued at call time*, before the request transaction commits. The worker thread opens its own connection/transaction; under READ COMMITTED it cannot see the uncommitted VERIFYING status or the uncommitted gate rows. If the worker wins the race it: finds no gate rows (`row == null` → skip all evaluation), reads status DRAFT, hits the `status != VERIFYING` early-return at `GateChainRunner.java:147`, and exits without doing anything. After the request transaction then commits, the onboarding sits in VERIFYING with all gates PENDING **forever** — the runner is never re-kicked (no scheduler, no RESUBMIT, and a second `POST /submit` is an illegal transition → 400). This is timing-dependent and invisible in CI: `OnboardingSubmitIntegrationTest` intentionally asserts only "still VERIFYING", and `VendorOnboardingEndToEndIntegrationTest` calls `gateChainRunner.runAndRecompute(...)` directly *after* the submit response (i.e., after commit), masking the production race.
**Fix:** Defer the async kick until after commit. Minimal change in `submit()`:
```java
transition(onboarding, OnboardingEvent.SUBMIT);
gateChainRunner.materialise(onboarding);

final UUID onboardingId = onboarding.getId();
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        gateChainRunner.runAndRecompute(onboardingId, tenantId);
    }
});
```
(or publish an application event consumed by `@TransactionalEventListener(phase = AFTER_COMMIT) @Async`). Add an integration test that relies solely on the submit-kicked run (no direct `runAndRecompute` call) to prove the ordering.

### CR-02: `createOnboarding` accepts an unvalidated `shopId` — cross-tenant shop binding, hygiene-gate evidence spoofing, and an unrecoverable aggregate

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java:61-76` (interacts with `core-java/src/main/java/uk/jtoye/core/onboarding/gate/FhrsGate.java:80` and `V43__vendor_onboarding.sql:23`)
**Issue:** The request `shopId` is copied straight onto the aggregate with no ownership or existence check. Three concrete consequences:
1. **Cross-tenant binding is possible.** The V43 FK `shop_id REFERENCES shops(id)` is checked by Postgres referential-integrity machinery, which *bypasses RLS*, so an INSERT referencing another tenant's shop succeeds. Published shop UUIDs are public (storefront API), so they are trivially obtainable.
2. **Compliance evidence spoofing.** `FhrsGate` reads the shop via `shopRepository.findById(shopId)` — an RLS-only read, and the V16 `shops_public_read` policy OR-permits `published = true` rows cross-tenant. Tenant A can therefore point its onboarding at tenant B's published, well-rated shop and have the FOOD_HYGIENE_RATING gate evaluate — and store PASSED evidence + `external_ref` — against **someone else's FSA establishment**. (Go-live is ultimately blocked because `ShopService.setPublished` uses `findByIdAndTenantId`, and the allergen gate reads products under tenant-only RLS, but the recorded compliance evidence is already false, and with a future model change or admin approval this becomes a live bypass.)
3. **Unrecoverable state.** With `UNIQUE(tenant_id)` and no update/withdraw/delete endpoint in this slice, an onboarding created with a wrong, foreign, or since-deleted `shopId` (even by honest mistake — the UI free-populates from a select, but the API is open) permanently wedges the tenant: go-live 404s (`Shop not found`), and no second onboarding can be created. Additionally, a nonexistent `shopId` surfaces as an FK `DataIntegrityViolationException`, which `GlobalExceptionHandler:89-106` maps to a misleading `409 "Duplicate Entry"` (also a shop-UUID existence oracle).
**Fix:** In `createOnboarding`, resolve the shop through the tenant-scoped finder before persisting:
```java
UUID tenantId = CurrentTenant.require();
shopRepository.findByIdAndTenantId(shopId, tenantId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
```
For defence-in-depth, make `FhrsGate` use `findByIdAndTenantId(shopId, onboarding.getTenantId())` instead of the RLS-only `findById`, so a published foreign shop can never feed gate evidence.

### CR-03: ACTION_REQUIRED is a dead-end state, and the UI ships a "Re-run checks" button that can never succeed

**File:** `frontend/app/dashboard/onboarding/page.tsx:483-487` (with `core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java:69-96` and `GateChainRunner.java:132-134`)
**Issue:** Any mandatory gate FAILURE (e.g. FHRS rating below 2, or one product missing allergen data — the *primary expected failure path* of this feature) moves the onboarding to ACTION_REQUIRED. From there:
- The frontend renders a primary CTA "Re-run checks" which calls `POST /api/v1/onboarding/submit`. The state machine only accepts SUBMIT from DRAFT (`VendorOnboardingStateMachineConfig.java:64-69`); RESUBMIT (ACTION_REQUIRED→VERIFYING) is modelled but **no endpoint fires it**. The button therefore *always* returns 400, and the catch-all toast ("Update the flagged information on your products, then try again") tells the vendor to retry an action that can never work.
- Even if a RESUBMIT endpoint existed, `runAndRecompute` skips every non-PENDING gate row (`GateChainRunner.java:132`), and the recompute only advances from VERIFYING — FAILED rows are never reset to PENDING, so a re-run would still change nothing.
The result: every vendor who fails a single check is permanently stuck, with a UI that actively misleads them. This is the shipped failure path, not an edge case.
**Fix:** Either (a) add `POST /onboarding/resubmit` that fires `OnboardingEvent.RESUBMIT` and resets FAILED (and optionally MANUAL_REVIEW) gate rows to PENDING before re-kicking `runAndRecompute`, and point the ACTION_REQUIRED button at it; or (b) if resubmit is genuinely deferred to slice 2, remove the "Re-run checks" CTA and render "Contact support" guidance instead. Option (a) is strongly preferred — the state machine and runner already anticipate it.

## Warnings

### WR-01: `runAndRecompute` has no exception handling — a guard-vetoed auto-APPROVE (or any late failure) rolls back all gate results silently

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java:101-175`
**Issue:** The whole async run is one transaction with no try/catch around the transition calls. If `transition(onboardingId, APPROVE)` is vetoed by the APPROVE guard (the comment at line 164 explicitly says the guard "can still veto"), `InvalidStateTransitionException` propagates, the **entire transaction rolls back** — discarding all persisted gate evaluations *and* the already-fired GATES_PASSED transition — and the exception is swallowed by Spring's default async uncaught-exception handler (a log line, nothing else). The onboarding is left in VERIFYING with PENDING gates, i.e. the same stuck state as CR-01, and the external API calls (Companies House / FHRS) have already been consumed. The comment "Default false stops at PENDING_APPROVAL" is contradicted by this rollback behavior: with auto-approve on and a veto, it stops at *VERIFYING*, not PENDING_APPROVAL. The same total-rollback applies if `AllergenCompletenessGate.evaluate` throws (it has no catch, unlike the two client gates) or an `OptimisticLockException` hits any save.
**Fix:** Wrap the recompute step so a vetoed APPROVE cannot destroy committed progress:
```java
if (allPassed) {
    vendorOnboardingService.transition(onboardingId, OnboardingEvent.GATES_PASSED);
    if (onboardingProperties.isAutoApprove()) {
        try {
            vendorOnboardingService.transition(onboardingId, OnboardingEvent.APPROVE);
        } catch (InvalidStateTransitionException e) {
            log.warn("Auto-approve vetoed for onboarding {}: {}", onboardingId, e.getMessage());
        }
    }
}
```
and add a top-level catch (or `AsyncUncaughtExceptionHandler`) that logs at ERROR with the onboarding id so a failed run is observable. Consider `REQUIRES_NEW` for the gate-evaluation phase so evaluated results survive a recompute failure.

### WR-02: `companyNumber` is completely unvalidated — over-length input surfaces as a misleading 409 "Duplicate Entry"

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/dto/CreateOnboardingRequest.java:23` (and `VendorOnboardingService.java:69`)
**Issue:** No `@Size`/`@Pattern` on `companyNumber`. A value longer than the V43 `VARCHAR(32)` column throws `DataIntegrityViolationException` at flush, which `GlobalExceptionHandler` maps to `409 Conflict` titled **"Duplicate Entry"** — the wrong status (should be 400) and an actively wrong message for a length violation. The value is also stored untrimmed (`"  12345678  "` persists with whitespace; `CompaniesHouseGate` trims only for the lookup, so the stored aggregate and the evidence diverge). Companies House numbers are 8 characters (2 letters + 6 digits, or 8 digits) — free-text here means garbage inputs reach the external API instead of being rejected at the boundary.
**Fix:**
```java
@Size(max = 32, message = "companyNumber must be at most 32 characters")
@Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "companyNumber must be a valid Companies House number")
private String companyNumber;
```
and normalise (`trim()`, uppercase) in `createOnboarding` before persisting.

### WR-03: GO_LIVE/REINSTATE guard trusts a stale ALLERGEN_DATA_COMPLETE row — the "before publish" legal check is a TOCTOU

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineConfig.java:200-211`
**Issue:** The gate row is evaluated once during the async run after submit. `goLiveGuard()` then only checks that the *row* is PASSED. Between gate evaluation and `POST /go-live` (hours, days — auto-approve=false deliberately parks onboardings at PENDING_APPROVAL awaiting a human), the vendor can freely add products with no `durabilityType`/`shelfLifeDays`/`ingredientsText` or blank an existing product's ingredients. Go-live then publishes a storefront whose catalogue violates the exact Natasha's Law completeness rule this gate exists to enforce ("a distance-selling storefront must not go live until every product it sells carries the data a compliant PPDS label requires" — the gate's own javadoc). The same staleness applies to REINSTATE after a suspension.
**Fix:** Re-evaluate the allergen gate at transition time. In `VendorOnboardingService.transition`, for GO_LIVE/REINSTATE, call `allergenCompletenessGate.evaluate(onboarding)` and update the row (status + checkedAt + reason) *before* `stateMachineService.sendEvent(...)`, so the guard reads fresh data. This is a cheap same-DB read (no external API), so there is no outage/latency argument for skipping it.

## Info

### IN-01: Unused import `Rocket` in the onboarding page

**File:** `frontend/app/dashboard/onboarding/page.tsx:34`
**Issue:** `Rocket` is imported from `lucide-react` but never rendered (it is used in `sidebar.tsx`, not here). Dead import; lint warning.
**Fix:** Remove `Rocket` from the import list.

### IN-02: Dynamically composed Tailwind classes (`hover:${...}`) are never generated

**File:** `frontend/app/dashboard/onboarding/page.tsx:405,538`
**Issue:** `` className={`${stateMeta.badge} hover:${stateMeta.badge}`} `` builds class strings at runtime (e.g. `hover:bg-emerald-100 text-emerald-700` — note the `hover:` prefix only lands on the first token). Tailwind's compiler only emits classes it can see verbatim in source, so these hover variants don't exist in the CSS; the intended "suppress Badge hover colour shift" doesn't take effect.
**Fix:** Put complete literal class strings in `STATE_META`/`GATE_STATUS_META` (e.g. `badge: "bg-emerald-100 text-emerald-700 hover:bg-emerald-100"`), or use a non-hover Badge variant.

### IN-03: CHANGELOG test baseline (873) contradicts `docs/metrics.json` (907)

**File:** `docs/CHANGELOG.md` (Phase 18 entry, line 13) vs `docs/metrics.json:12`
**Issue:** The Phase 18 entry claims "Test baseline 802 → 873 logical invocations" but `metrics.json` (which `scripts/docs-freshness.sh` confirms matches source) totals 907. The CI gate checks only metrics.json, so this prose drift will not be caught mechanically.
**Fix:** Update the CHANGELOG entry to the reconciled total (907) or note the additional marketing/dashboard test additions that account for the difference.

### IN-04: Gate rows' `updated_at` never maintained on evaluation

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java:135-141`
**Issue:** The runner sets status/evidence/externalRef/reason/checkedAt but never `setUpdatedAt(...)`; `VendorOnboardingGate` has no `@UpdateTimestamp`. `updated_at` stays NULL after every evaluation, making the column misleading for ops queries (the parent aggregate *does* get `updatedAt` stamped in `VendorOnboardingService.transition`).
**Fix:** Either add `@UpdateTimestamp` to `VendorOnboardingGate.updatedAt` or set it alongside `checkedAt` in the runner.

### IN-05: Raw exception messages persisted into vendor-visible gate `reason`

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/gate/CompaniesHouseGate.java:94`, `core-java/src/main/java/uk/jtoye/core/onboarding/gate/FhrsGate.java:91`
**Issue:** `GateResult.manualReview("... " + e.getMessage())` stores the exception text on the row, and `GateDto` exposes `reason` to the vendor. `WebClientResponseException` messages include the full upstream URL and status ("500 Internal Server Error from GET https://api.company-information.service.gov.uk/company/..."); circuit-breaker messages leak breaker names. Low sensitivity, but internal detail is leaking to the client for no user benefit.
**Fix:** Persist a fixed, human-readable reason ("Business register temporarily unavailable — a reviewer will check this manually") and keep `e.getMessage()` in the WARN log only.

### IN-06: Dashboard banner copy is wrong for SUSPENDED/REJECTED/WITHDRAWN

**File:** `frontend/app/dashboard/page.tsx:39-44`
**Issue:** The fallback branch labels SUSPENDED, REJECTED and WITHDRAWN with "Finish setting up your shop to go live." and CTA "Start onboarding" — misleading for a suspended or rejected vendor (there is nothing to "start"; the onboarding page itself says "contact support").
**Fix:** Add a third branch for `SUSPENDED | REJECTED | WITHDRAWN` with accurate copy (e.g. "Your storefront is not live. View details.") or hide the banner for terminal states.

### IN-07: Import statement placed after executable code

**File:** `frontend/app/dashboard/page.tsx:53-66`
**Issue:** The `date-fns` and `recharts` imports sit below two function declarations (lines 28-52). Legal (imports hoist) but violates the codebase's import-organisation convention and trips `import/first`-style lint rules.
**Fix:** Move all imports above `onboardingBannerContent`/`onboardingHttpStatus`.

### IN-08: `IllegalStateException` → 400 mapping conflicts with the documented 500 convention for missing tenant context

**File:** `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java:65-71`
**Issue:** Project docs (CLAUDE.md error-handling section) state `IllegalStateException` (TenantContext not set) indicates a security configuration error → 500. The handler returns 400 with the raw message. `CurrentTenant.require()` (now on every onboarding endpoint) relies on this path, so a server-side tenant-mapping failure is reported to clients as *their* bad request, and `CompaniesHouseClient`'s fail-closed `IllegalStateException` would also surface as 400 if it ever escaped a gate. Pre-existing, but this phase adds new load onto it.
**Fix:** Introduce a dedicated `MissingTenantContextException` (or check the message) so genuine server misconfiguration maps to 500 while request-shape problems stay 400.

### IN-09: Gate `mandatory()` is hardcoded and ignores the onboarding model

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingGate.java:24`, all three gate implementations
**Issue:** The design doc (VENDOR_ONBOARDING_STATE_MODEL.md §3.1, cited in `OnboardingModel`'s javadoc: "Which gates are mandatory differs by model") is not representable: `mandatory()` takes no model and all three gates return a constant `true`. Fine for slice 1 (these three plausibly bind both models), but slice 2's model-dependent gates (PAYMENTS_CONNECTED is MARKETPLACE-only) will force an interface change. The per-row `mandatory` column already supports data-driven variance — the bean interface is the only blocker.
**Fix:** Change the signature to `boolean mandatory(OnboardingModel model)` now, while there are only three implementations.

### IN-10: State machine not stopped on the exception path

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineService.java:82-93`
**Issue:** `stateMachine.stopReactively().block()` runs only after a successful transition; on the throw path (line 87) the machine is abandoned un-stopped. Per-call machines are GC-eligible so impact is minimal, and this mirrors `OrderStateMachineService`, but it is an inherited resource-hygiene wart now duplicated.
**Fix:** Move the stop into a `finally` block.

---

_Reviewed: 2026-07-11T03:57:29Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

---

## Fix Round 1

**Fixed at:** 2026-07-11 (gsd-code-fixer)
**Scope:** all 3 Critical + all 3 Warning findings. Info findings (IN-01..IN-10) are OUT of scope → deferred as follow-ups.
**Gates:** `./gradlew :core-java:test :core-java:integrationTest` green; frontend `npm run build` (tsc) + full Jest suite (130 blocks) green; `scripts/docs-freshness.sh` green (907 → 918 logical invocations). Each fix committed atomically; every fix carries a proving test.

| ID | Status | Commit | What changed | Proving test(s) |
|----|--------|--------|--------------|-----------------|
| CR-01 | FIXED | `2abfe38` | `submit()` now defers the `@Async runAndRecompute` dispatch to an `afterCommit` transaction synchronization, so the worker always sees the committed VERIFYING status + PENDING gate rows (no more permanent-VERIFYING race). | `VendorOnboardingEndToEndIntegrationTest` adjusted to rely SOLELY on the submit-kicked run (no direct `runAndRecompute` call) — proves the ordering end-to-end. |
| CR-02 | FIXED | `a12b617` | `createOnboarding` validates shop ownership via `shopRepository.findByIdAndTenantId` (404 on missing/foreign shop, no more FK→409 oracle); `FhrsGate` reads the shop via the tenant-scoped finder (defence-in-depth) so a foreign published shop can never feed hygiene evidence. | `OnboardingCreateCrossTenantIntegrationTest` (foreign published shop → 404, not bound; nonexistent → 404; own shop → 201) + updated `FhrsGateTest`. |
| CR-03 | FIXED | `c20fbf0` | New `POST /onboarding/resubmit` fires `RESUBMIT` (ACTION_REQUIRED→VERIFYING) and resets FAILED/MANUAL_REVIEW gate rows to PENDING (PASSED/WAIVED stay trusted) before re-kicking the chain; UI "Re-run checks" button now calls `/resubmit` (was `/submit`, which always 400'd). State-machine throw-on-veto contract preserved. | `OnboardingResubmitIntegrationTest` (happy re-run advances to PENDING_APPROVAL; illegal resubmit from DRAFT → 400) + frontend `page.test.tsx` (Re-run hits `/resubmit`, not `/submit`). |
| WR-01 | FIXED | `e1e41a3` | `runAndRecompute` wraps the auto-APPROVE step in a targeted `catch (InvalidStateTransitionException)` so a veto no longer rolls back the committed gate evaluations + GATES_PASSED (parks at PENDING_APPROVAL, evidence survives); added a top-level ERROR log so a failed async run is observable. | `GateChainRunnerTest.recomputeSwallowsVetoedAutoApprove` (veto is caught, GATES_PASSED still fired, no exception propagates). |
| WR-02 | FIXED | `4a15b24` | `companyNumber` now carries `@Size(max=32)` + a lenient `@Pattern` (2–10 alphanumerics, whitespace-tolerant) → 400 (not the misleading 409 "Duplicate Entry") for over-length/garbage; the service trims + uppercases and stores blank as null, so the persisted value matches the gate lookup. | `OnboardingCompanyNumberValidationIntegrationTest` (over-length → 400; `"  sc123456  "` → stored `SC123456`; blank → null). |
| WR-03 | FIXED | `c118823` | `transition()` re-evaluates the `ALLERGEN_DATA_COMPLETE` gate (a cheap same-DB read) for GO_LIVE/REINSTATE BEFORE `sendEvent`, so the guard reads fresh data and the go-live TOCTOU is closed. FHRS/CH rows are intentionally left as recorded (external calls) — documented in the code. | `OnboardingGoLiveIntegrationTest`: new TOCTOU test (stale PASSED allergen row + now-incomplete product → 400, shop unpublished) + the positive test now seeds a compliant product. |

**Deferred (Info, follow-ups):** IN-01 (unused `Rocket` import), IN-02 (dynamic `hover:${...}` Tailwind classes), IN-03 (CHANGELOG 873 vs metrics), IN-04 (`updated_at` on gate rows), IN-05 (raw exception text in vendor-visible `reason`), IN-06 (dashboard banner copy for terminal states), IN-07 (import after code), IN-08 (`IllegalStateException`→400 vs documented 500), IN-09 (`mandatory()` ignores model), IN-10 (state machine not stopped on exception path).

_Fixed: 2026-07-11_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_

---

## Fix Round 2 (#178 slice 2 — deferred Info findings)

**Fixed at:** 2026-07-12 (branch `feature/178-admin-approval-queue`)
**Scope:** the deferred Info findings that were still open. IN-01/IN-02/IN-06 were already closed by intervening work (verified against main @ ea2cfed: no `Rocket` import remains in `onboarding/page.tsx`, `STATE_META`/`GATE_STATUS_META` carry literal class strings + `pointer-events-none`, and `dashboard/page.tsx` has the terminal-state banner branch). IN-03 was overtaken by the docs-freshness baseline moving to 988 — the CHANGELOG has carried reconciled totals since Phase 19.

| ID | Status | What changed | Proving test(s) |
|----|--------|--------------|-----------------|
| IN-04 | FIXED | `VendorOnboardingGate.updatedAt` now carries `@UpdateTimestamp` (existing V43 column — no migration), so every evaluation/reset write stamps it. | `VendorOnboardingPersistenceIntegrationTest.gateUpdateStampsUpdatedAt` (asserts the real column, not just the entity field). |
| IN-05 | FIXED | `CompaniesHouseGate`/`FhrsGate` persist fixed human-readable reasons on client failure ("Business register / Food hygiene service temporarily unavailable — a reviewer will check this manually"); raw exception text stays in the WARN log. | Existing gate unit tests (reason assertions unaffected — they never pinned the raw text). |
| IN-07 | FIXED | `dashboard/page.tsx` — `date-fns` + `recharts` imports moved above the two function declarations. | `npm run build` + full jest (behaviour-neutral). |
| IN-08 | FIXED | New `MissingTenantContextException` (extends `IllegalStateException`) thrown by `CurrentTenant.require()`, mapped to **500** with a generic RFC-7807 body; generic `IllegalStateException` stays 400. | `OnboardingAdminQueueIntegrationTest.missingTenantOnAuthenticatedRequestIs500ServerFault`. |
| IN-09 | FIXED | `OnboardingGate.mandatory(OnboardingModel)` — model is now part of the contract; all three slice-1 gates return `true` for both models; `GateChainRunner.materialise` passes the onboarding's model. | Updated gate identity tests (both models asserted) + `GateChainRunnerTest`. |
| IN-10 | FIXED | `VendorOnboardingStateMachineService.sendEvent` stops the per-call machine in a `finally`, covering the veto/denied throw path. | Existing `VendorOnboardingStateMachineServiceTest` (illegal-transition + guard-veto paths exercise the finally). |

_Fixed: 2026-07-12_
_Iteration: 2_

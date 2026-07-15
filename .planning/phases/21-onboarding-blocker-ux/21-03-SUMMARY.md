---
phase: 21-onboarding-blocker-ux
plan: 03
subsystem: api
tags: [spring-boot, state-machine, onboarding, rls, envers, testcontainers, java]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice
    provides: "GateChainRunner async recompute (advances from VERIFYING on all-passed/waived, GATE_FAILED on any-failed; skips non-PENDING rows; re-establishes tenant GUC on the worker), kickGateChainAfterCommit (CR-01 afterCommit trigger), requireOnboardingById (RLS/FORCE V43 -> 404 foreign tenant), VendorOnboardingGate @Audited (_aud mirror), hand-built toDto/toAdminDto"
  - phase: 21-01
    provides: "withdraw()/updateCompanyNumber() already call toDto — unaffected by the record widening"
  - phase: 21-02
    provides: "GateChainRunner MANUAL_REVIEW stall emission — the same recompute path gate-resolve triggers"
provides:
  - "Vendor OnboardingDto widened: reviewPending (D-03 derived) + rejectionReason (D-09), derived once in toDto"
  - "POST /onboarding/admin/{id}/gates/{gateType}/resolve — admin unsticks a gate (PASS|WAIVE|FAIL+reason); writes only the gate row (Envers-audited) then recompute-after-commit advances the SM"
  - "GET /onboarding/admin/reviews — NEW admin queue listing VERIFYING + MANUAL_REVIEW applications (/pending untouched)"
  - "GateDecision enum (PASS|WAIVE|FAIL) + ResolveGateRequest (@NotNull decision, @Size reason)"
  - "VendorOnboardingService.resolveGate + listReviewPending; VendorOnboardingGateRepository.existsByOnboardingIdAndStatus"
  - "15 Testcontainers proofs (advance PASS->PENDING_APPROVAL / FAIL->ACTION_REQUIRED, Envers _aud, 403/404, foreign-tenant NOSUPERUSER RLS invisibility, FAIL-blank-reason 400, reviewPending derivation x4, review-queue x4)"
affects: [21-04-frontend, 21-05-playwright, 24-outbound-webhooks]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "DTO-derived lifecycle flag: reviewPending computed at the single toDto site where the gate list is already loaded — the UI renders, never re-derives gate math"
    - "Admin override -> SM advance without a direct status write: resolveGate writes the gate row + kickGateChainAfterCommit; the existing recompute is the sole advancer (state machine stays sole writer of status/published)"
    - "Recompute AFTER commit only (CR-01): the async worker on its own connection must see the committed gate write — resolveGate never calls runAndRecompute inline"
    - "New admin queue over mutated endpoint (Incremental Betterment, A4): a NEW /reviews keeps the /pending approve/reject contract clean"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/onboarding/GateDecision.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/dto/ResolveGateRequest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingReviewQueueIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingGateResolveIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/onboarding/dto/OnboardingDto.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingAdminController.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingGateRepository.java

key-decisions:
  - "reviewPending derived once in toDto with the exact D-03 predicate (VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING); rejectionReason passed through from the entity (already stored) — hand-built record, zero migration"
  - "resolveGate writes ONLY the gate row (Envers-audited) + kickGateChainAfterCommit; NO runAndRecompute inline (CR-01), NO status/published write — the recompute (GATES_PASSED/GATE_FAILED) advances the SM, keeping it the sole authority"
  - "FAIL requires a reason enforced server-side (IllegalArgumentException -> 400); reason optional for PASS/WAIVE (A5) — so @Size (not @NotBlank) on ResolveGateRequest"
  - "Review queue is a NEW GET /reviews (A4) — /pending approve/reject contract untouched (Incremental Betterment)"
  - "Interim resolver = the tenant's own admin (D-01), documented in the controller Javadoc; a cross-tenant J'Toye platform-operator console is a deferred phase"
  - "ONBD-03/ONBD-05 NOT marked complete — their user-visible vendor-UI halves (in-review copy + polling back-off, rejection-reason + support channel) land in 21-04; matches the 21-01/21-02 precedent of no false-complete signal"

requirements-completed: []  # ONBD-03 spans 21-02 (outbox seam) + 21-03 (this: reviewPending/gate-resolve/review-queue) + 21-04 (vendor in-review UI); ONBD-05 spans 21-03 (DTO field) + 21-04 (render). Backend halves delivered here; NOT marked complete to avoid a false-complete signal.

# Metrics
duration: 21min
completed: 2026-07-14
---

# Phase 21 Plan 03: Manual-Review Visibility + Gate-Resolve (Backend) Summary

**Makes manual-review onboardings visible to both parties and unstickable: the vendor `OnboardingDto` now derives `reviewPending` (D-03) and surfaces `rejectionReason` (D-09) at the single `toDto` site; an admin can unstick a parked gate via `POST /onboarding/admin/{id}/gates/{gateType}/resolve` (PASS/WAIVE/FAIL+reason) which writes ONLY the Envers-audited gate row and lets the EXISTING recompute advance the state machine — never a direct status/published write, never an inline recompute (CR-01); and a NEW `GET /onboarding/admin/reviews` queue lists VERIFYING+MANUAL_REVIEW applications while the `/pending` approve/reject contract stays untouched. Zero Flyway migrations.**

## Performance

- **Duration:** ~21 min
- **Tasks:** 3 (all TDD)
- **Commits:** 6 task commits (RED+GREEN per task) + this metadata commit
- **Files:** 4 created, 4 modified

## Accomplishments

- **Task 1 — vendor DTO derivation (ONBD-03/05):** widened `OnboardingDto` with `String rejectionReason` + `boolean reviewPending`; `VendorOnboardingService.toDto` derives `reviewPending = status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING` (the exact D-03 predicate) and passes `onboarding.getRejectionReason()` — the single derivation site where the gate list is already loaded, so the UI never re-computes gate lifecycle logic. All existing `toDto` callers (create/submit/resubmit/withdraw/updateCompanyNumber/goLive/getMyOnboarding) kept compiling unchanged.
- **Task 2 — gate-resolve (ONBD-03 / D-01 seam):** `GateDecision` enum (PASS/WAIVE/FAIL) + `ResolveGateRequest` (`@NotNull` bounded enum + `@Size(500)` reason); `resolveGate(...)` resolves the tenant server-side, `requireOnboardingById` (RLS 404 for a foreign tenant), enforces FAIL-requires-reason (`IllegalArgumentException` → 400), overrides the gate row status (Envers auto-writes `vendor_onboarding_gate_aud`), then `kickGateChainAfterCommit` — never `runAndRecompute` inline, never a `status`/`published` write. `POST /onboarding/admin/{id}/gates/{gateType}/resolve` + a class-Javadoc note documenting the interim resolver (D-01).
- **Task 3 — admin review queue (ONBD-03 / D-04):** `VendorOnboardingGateRepository.existsByOnboardingIdAndStatus`; `listReviewPending()` selects VERIFYING onboardings that have a MANUAL_REVIEW gate (oldest submission first, cloning `listPendingApproval`); a NEW `GET /onboarding/admin/reviews` returning `AdminOnboardingDto`. The `/pending` approve/reject queue is untouched (Incremental Betterment).

## Task Commits

Each TDD task committed RED (failing test) → GREEN (implementation):

1. **Task 1 — vendor DTO reviewPending + rejectionReason**
   - `5cc1792` — test(21-03): add failing vendor DTO reviewPending/rejectionReason derivation test (RED)
   - `f2e4ccb` — feat(21-03): derive vendor reviewPending + expose rejectionReason on OnboardingDto
2. **Task 2 — gate-resolve endpoint + service**
   - `afa0c90` — test(21-03): add failing gate-resolve integration test (RED)
   - `d316163` — feat(21-03): add admin gate-resolve endpoint + service (write gate row -> recompute)
3. **Task 3 — admin review queue**
   - `096e5b0` — test(21-03): add failing admin review-queue tests (RED)
   - `1e1209c` — feat(21-03): add admin review queue (GET /reviews) + listReviewPending

**Plan metadata:** committed as `docs(21-03): complete manual-review visibility + gate-resolve plan` (SUMMARY + STATE + ROADMAP).

## Files Created/Modified

- `onboarding/GateDecision.java` — NEW: PASS/WAIVE/FAIL decision enum (bounded input, ASVS V5) mapping to the GateStatus override.
- `onboarding/dto/ResolveGateRequest.java` — NEW: `@NotNull GateDecision decision` + `@Size(max=500) String reason` (NOT `@NotBlank` — FAIL-requires-reason enforced in the service so PASS/WAIVE reason stays optional, A5).
- `onboarding/dto/OnboardingDto.java` — MOD: record widened with `String rejectionReason` + `boolean reviewPending` (Javadoc updated).
- `onboarding/VendorOnboardingService.java` — MOD: `toDto` derives `reviewPending` + passes `rejectionReason`; added `resolveGate(...)` (gate-row write + Envers + kickGateChainAfterCommit) and `listReviewPending()` (VERIFYING + MANUAL_REVIEW).
- `onboarding/OnboardingAdminController.java` — MOD: `POST /{id}/gates/{gateType}/resolve` + `GET /reviews`; class Javadoc documents the interim gate-resolve authority (D-01).
- `onboarding/VendorOnboardingGateRepository.java` — MOD: `existsByOnboardingIdAndStatus` finder for the review-queue filter.
- `onboarding/OnboardingReviewQueueIntegrationTest.java` — NEW: 8 Testcontainers tests (4 vendor `reviewPending`/`rejectionReason` derivation via GET /onboarding/me + 4 admin `/reviews` queue).
- `onboarding/OnboardingGateResolveIntegrationTest.java` — NEW: 7 Testcontainers tests (PASS→advance, FAIL→ACTION_REQUIRED, Envers `_aud`, non-admin 403, nonexistent 404, foreign-tenant NOSUPERUSER RLS invisibility, FAIL-blank-reason 400).

**Test delta:** +15 Java `@Test` methods (2 new integration classes: 8 + 7). `docs/metrics.json` was deliberately **NOT** reconciled here — `scripts/docs-freshness.sh --write` is plan 21-05's closing task (phase guardrail).

## Verification

Run from the repo root (`core-java` is a Gradle subproject; the wrapper is at the root — the plan's `cd core-java && ./gradlew` form does not apply, per the environment correction). Testcontainers spins its own Postgres 15, independent of the compose stack.

- `./gradlew :core-java:integrationTest --tests "*OnboardingReviewQueueIntegrationTest*"` → `tests="8" skipped="0" failures="0" errors="0"`
- `./gradlew :core-java:integrationTest --tests "*OnboardingGateResolveIntegrationTest*"` → `tests="7" skipped="0" failures="0" errors="0"`
- `./gradlew :core-java:integrationTest --tests "*OnboardingAdminQueueIntegrationTest*"` → `tests="11" ... failures="0"` (pre-existing /pending queue — no regression)
- **Full onboarding sweep** — `./gradlew :core-java:test :core-java:integrationTest --tests "*Onboarding*"` → **BUILD SUCCESSFUL in 6m 1s**; aggregate across 23 onboarding test classes: **124 tests, 0 failures, 0 errors**.

Guardrail / acceptance greps:
- `resolveGate` calls `kickGateChainAfterCommit(` and contains NO `runAndRecompute(` (inline) and NO `setPublished(` / onboarding `setStatus(` (only the gate `row.setStatus(newStatus)` — the allowed gate-row write). ✔
- `GateDecision.java` declares exactly `PASS`, `WAIVE`, `FAIL`. ✔
- `OnboardingAdminController.java` contains `@PostMapping("/{id}/gates/{gateType}/resolve")` and `@GetMapping("/reviews")`; the pre-existing `/pending` mapping is unchanged. ✔
- `VendorOnboardingService.toDto` contains the three-clause predicate (`VERIFYING`, `anyMatch(... MANUAL_REVIEW`, `noneMatch(... PENDING`). ✔
- **Zero** new files under `core-java/src/main/resources/db/migration/`. ✔

## Decisions Made

- **Single derivation site.** `reviewPending` is computed in `toDto` (hand-built record, not MapStruct) where the gate list is already loaded, so the flag is server-authoritative and the UI never re-derives gate math (D-03). `rejectionReason` simply passes through from the entity (already stored, already on `AdminOnboardingDto`).
- **Override the gate row, let the SM advance itself.** `resolveGate` writes only the gate row and registers the existing `kickGateChainAfterCommit`; the recompute fires `GATES_PASSED`/`GATE_FAILED`, so the state machine stays the sole writer of `status`/`Shop.published`. The admin-set row survives the re-run because recompute skips non-PENDING rows (GateChainRunner).
- **Recompute AFTER commit only (CR-01).** Never inline — the async worker on its own READ COMMITTED connection must see the committed gate write, proven by the PASS→advance test.
- **FAIL requires a reason (A5), enforced in the service.** `ResolveGateRequest.reason` is `@Size`-bounded (not `@NotBlank`), so PASS/WAIVE reason is optional; a blank FAIL reason is an `IllegalArgumentException` → 400.
- **New /reviews queue (A4).** Keeps the `/pending` approve/reject contract clean (Incremental Betterment).
- **Interim resolver (D-01).** The gate-resolve/review authority is the tenant's own `admin` — documented in the controller Javadoc as interim; a cross-tenant J'Toye platform-operator console is a deferred phase.
- **ONBD-03/ONBD-05 left OPEN.** The backend halves ship here; the user-visible vendor-UI halves (in-review copy + polling back-off, rejection-reason + support channel) land in 21-04. Not marked complete — matches the deliberate 21-01/21-02 no-false-complete precedent.

## Deviations from Plan

None — plan executed exactly as written (3 tasks, in order, all TDD RED→GREEN).

_(Execution note, not a plan deviation: the plan's `<verify>` blocks use `cd core-java && ./gradlew …`, which fails in this repo — there is no `core-java/gradlew`. `core-java` is a Gradle subproject and the wrapper lives at the repo root, so every command was run as `./gradlew :core-java:<task>` from the root, per the environment correction supplied with the task. The build output directory is `core-java/build-local/` in this environment.)_

_(Minor in-task fix, not a plan deviation: the Task 3 test additions initially failed to compile — the AssertJ `assertThat` static import was missing after the derivation-only Task 1 version. Added `import static org.assertj.core.api.Assertions.assertThat;`; RED then failed at runtime as intended. Rule 3, blocking-issue fix, within the same task.)_

## Known Stubs

None — every new field/endpoint is fully wired: `reviewPending`/`rejectionReason` flow from real entity/gate state, `resolveGate` drives the real recompute, `/reviews` reads real VERIFYING+MANUAL_REVIEW rows. No placeholder data or empty returns. The vendor-UI rendering of these fields is 21-04 scope (documented, not a stub).

## Threat Flags

None — no new security surface beyond the plan's `<threat_model>`. `resolveGate` resolves the tenant via `CurrentTenant.require()` (never the body) and loads under RLS/FORCE (V43) so a foreign tenant is a clean 404 with no existence oracle (T-21-03-01, proven under the NOSUPERUSER role); the admin surfaces stay `@PreAuthorize("hasRole('admin')")` → non-admin 403 (T-21-03-02); the gate write never touches `status`/`published` and recompute fires only after commit (T-21-03-03/04, proven by the advance test + grep); `decision` is a bounded `@NotNull` enum and `reason` is `@Size`-bounded with server-enforced FAIL-requires-reason (T-21-03-05).

## Issues Encountered

None beyond the environment-correction note and the one in-task import fix above. Each TDD RED failed for the intended reason (absent field / absent endpoint), and each GREEN passed on first implementation with no auto-fix cycles. No auth gates, no checkpoints, no architectural (Rule 4) decisions.

## Next Phase Readiness

- **21-04 (frontend)** consumes this plan directly: `OnboardingDto.reviewPending`/`rejectionReason` drive the honest in-review copy + polling back-off and the rejection-reason + config support channel; `GET /onboarding/admin/reviews` + `POST /onboarding/admin/{id}/gates/{gateType}/resolve` back the admin review-queue + gate-resolve UI. The TS `OnboardingDto` in `frontend/types/api.ts` must mirror the two new fields (`rejectionReason: string | null`, `reviewPending: boolean`) — run `npm run build` (tsc) after (Pitfall 6). This plan deliberately did NOT touch frontend TS.
- **21-05** reconciles `docs/metrics.json` (+15 Java `@Test` from this plan) via `scripts/docs-freshness.sh --write` as its closing task.
- **ONBD-03 / ONBD-05** remain OPEN in REQUIREMENTS traceability pending their 21-04 vendor-UI halves.

## Self-Check: PASSED

- Files verified present: `GateDecision.java`, `dto/ResolveGateRequest.java`, `OnboardingReviewQueueIntegrationTest.java`, `OnboardingGateResolveIntegrationTest.java` (created); `dto/OnboardingDto.java`, `VendorOnboardingService.java`, `OnboardingAdminController.java`, `VendorOnboardingGateRepository.java` (modified) — all FOUND.
- Commits verified in `git log`: `5cc1792`, `f2e4ccb`, `afa0c90`, `d316163`, `096e5b0`, `1e1209c` — all FOUND.

---
*Phase: 21-onboarding-blocker-ux*
*Completed: 2026-07-14*

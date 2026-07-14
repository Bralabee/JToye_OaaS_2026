---
phase: 21-onboarding-blocker-ux
plan: 01
subsystem: api
tags: [spring-boot, state-machine, onboarding, rfc7807, rls, testcontainers, java]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice
    provides: "VendorOnboarding state machine (WITHDRAW event + 5 pre-live transitions already wired), canonical transition() path, CreateOnboardingRequest @Size/@Pattern validation, normaliseCompanyNumber, RLS/FORCE V43 schema"
provides:
  - "POST /onboarding/withdraw — vendor withdraws an in-progress application from any pre-live state (terminal WITHDRAWN)"
  - "POST /onboarding/company-number — vendor corrects the onboarding company number (blank = sole trader), gated to DRAFT/ACTION_REQUIRED"
  - "VendorOnboardingService.withdraw() and updateCompanyNumber() service methods"
  - "UpdateOnboardingRequest DTO reusing create's company-number validation verbatim"
  - "Testcontainers proofs for both endpoints (withdraw 4 tests, company-number 5 tests)"
affects: [21-04-frontend, 21-05-playwright, 24-outbound-webhooks]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Vendor endpoint = thin body-less/validated-body POST delegating to a service method that resolves tenant server-side (CurrentTenant.require())"
    - "Lifecycle exit routed through the canonical transition() path; SM stays sole writer of Shop.published"
    - "Data-only edit (company number) fires NO state-machine event and guards the editable-state window with InvalidStateTransitionException -> RFC 7807 400"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/onboarding/dto/UpdateOnboardingRequest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingWithdrawIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingCompanyNumberUpdateIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java

key-decisions:
  - "Withdraw reuses the already-wired WITHDRAW SM transitions via transition(o, OnboardingEvent.WITHDRAW) — no SM change; terminal source -> RFC 7807 400 automatically"
  - "Company-number update is a data edit firing NO SM event, gated to DRAFT/ACTION_REQUIRED, reusing create's @Size(32)+@Pattern verbatim; blank/whitespace = sole trader (null)"
  - "Update endpoint verb = POST /onboarding/company-number (matches the all-POST vendor surface; no @PatchMapping precedent in the controller)"

patterns-established:
  - "Exit-through-transition: a vendor lifecycle exit (withdraw) never writes status/published directly — it drives the canonical transition() so the state machine remains the sole authority"
  - "Guarded data-correction: a boundary DTO re-validates identically to create, and the service enforces the editable-state window before persisting"

requirements-completed: []  # ONBD-01/02 are only PARTIALLY delivered by this plan (backend halves). Per REQUIREMENTS traceability they also span the frontend plan (withdraw confirm dialog + inline company-number edit + their Jest tests). NOT marked complete here to avoid a false-complete signal.

# Metrics
duration: 18min
completed: 2026-07-14
---

# Phase 21 Plan 01: Onboarding Withdraw + Company-Number Correction (Backend) Summary

**Two net-new vendor endpoints — `POST /onboarding/withdraw` (reachable exit via the already-wired WITHDRAW state-machine transitions) and `POST /onboarding/company-number` (correctable data, DRAFT/ACTION_REQUIRED-gated, create-identical validation) — with zero Flyway migrations and the state machine kept as sole writer of `Shop.published`.**

## Performance

- **Duration:** 18 min
- **Started:** 2026-07-14T10:19:49Z
- **Completed:** 2026-07-14T10:38:05Z
- **Tasks:** 2 (both TDD)
- **Files modified:** 5 (2 main modified, 1 DTO created, 2 test files created)

## Accomplishments
- `POST /onboarding/withdraw`: a vendor can withdraw an in-progress application from any of the 5 pre-live states (DRAFT/VERIFYING/ACTION_REQUIRED/PENDING_APPROVAL/APPROVED → terminal WITHDRAWN); a terminal source (REJECTED/WITHDRAWN/LIVE/SUSPENDED) is rejected with RFC 7807 400 and left unchanged. Proven not to flip `Shop.published`.
- `POST /onboarding/company-number`: a vendor can correct the company number (blank/whitespace = sole trader → null) only in DRAFT/ACTION_REQUIRED; any other state → RFC 7807 400 with the stored value unchanged; a malformed value → clean RFC 7807 400 from bean-validation before the service (garbage never reaches the Companies House client).
- 9 new Testcontainers integration tests (real Postgres 15 + RLS + real state machine); the pre-existing 13-test SM WITHDRAW proof (`VendorOnboardingStateMachineServiceTest`) stays green — no duplication.

## Task Commits

Each task was executed TDD (RED test → GREEN implementation) and committed atomically:

1. **Task 1: Withdraw endpoint (ONBD-01)**
   - `c17878a` — test(21-01): add failing withdraw endpoint integration test (RED)
   - `78c3aab` — feat(21-01): add POST /onboarding/withdraw endpoint (GREEN)
2. **Task 2: Company-number update endpoint + DTO (ONBD-02)**
   - `ad0b2e4` — test(21-01): add failing company-number update integration test (RED)
   - `be39f5e` — feat(21-01): add POST /onboarding/company-number update endpoint (GREEN)

**Plan metadata:** committed as `docs(21-01): complete onboarding withdraw + company-number plan` (SUMMARY + STATE + ROADMAP).

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/onboarding/dto/UpdateOnboardingRequest.java` — NEW; carries only `companyNumber` with `@Size(max = 32)` + `@Pattern` copied verbatim from `CreateOnboardingRequest` (no tenantId — resolved server-side).
- `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java` — added `withdraw()` (clones `goLive()`: require → transition(WITHDRAW) → toDto) and `updateCompanyNumber(String)` (state guard → `InvalidStateTransitionException`; `normaliseCompanyNumber`; save; NO SM event). Added `InvalidStateTransitionException` import.
- `core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java` — added body-less `POST /withdraw` and validated-body `POST /company-number` (both vendor-self-scoped, no `@PreAuthorize`), each with `@Operation`/`@ApiResponses`. Added `UpdateOnboardingRequest` import.
- `core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingWithdrawIntegrationTest.java` — NEW; 4 Testcontainers tests (DRAFT→200/WITHDRAWN, ACTION_REQUIRED→200/WITHDRAWN, terminal REJECTED→400 problem+json/unchanged, published-not-flipped).
- `core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingCompanyNumberUpdateIntegrationTest.java` — NEW; 5 Testcontainers tests (DRAFT normalised, ACTION_REQUIRED persisted, blank→null, VERIFYING→400 problem+json/unchanged, malformed→400 before service).

## Verification

Run from the repo root (translated from the plan's `cd core-java && ./gradlew` form per the environment correction — `core-java` is a Gradle subproject, wrapper is at the root):

- `./gradlew :core-java:integrationTest --tests "*OnboardingWithdrawIntegrationTest*"` → BUILD SUCCESSFUL (4 tests, 0 failures)
- `./gradlew :core-java:integrationTest --tests "*OnboardingCompanyNumberUpdateIntegrationTest*"` → BUILD SUCCESSFUL (5 tests, 0 failures)
- `./gradlew :core-java:test --tests "*VendorOnboardingStateMachineServiceTest*"` → BUILD SUCCESSFUL (13 tests, 0 failures — pre-existing SM WITHDRAW proof still green)
- `./gradlew :core-java:test :core-java:integrationTest --tests "*Onboarding*"` → BUILD SUCCESSFUL in 4m 49s (full onboarding unit + Testcontainers suite green — regression check across all sibling onboarding tests)

XML report tallies (proof):
```
OnboardingWithdrawIntegrationTest            tests="4" skipped="0" failures="0" errors="0"
OnboardingCompanyNumberUpdateIntegrationTest tests="5" skipped="0" failures="0" errors="0"
VendorOnboardingStateMachineServiceTest      tests="13" skipped="0" failures="0" errors="0"
```

Guardrail checks:
- No new file under `core-java/src/main/resources/db/migration/` (zero-migration boundary held).
- `withdraw()` calls `transition(onboarding, OnboardingEvent.WITHDRAW)` and contains NO `setStatus(`/`setPublished(`.
- `updateCompanyNumber()` calls `normaliseCompanyNumber(` and contains NO `transition(`/`sendEvent` call.

## Decisions Made
- **Withdraw reuses existing SM wiring.** `OnboardingEvent.WITHDRAW` and its 5 source transitions already existed and were unit-proven; this plan added only the endpoint + service method, routing through the canonical `transition()` (WITHDRAW lands in the `default` arm — a no-side-effect status change, so `Shop.published` is untouched).
- **Company-number update is a data edit, not a transition.** Per D-06 it fires no `OnboardingEvent`; it guards `status ∈ {DRAFT, ACTION_REQUIRED}` and throws `InvalidStateTransitionException` (→ RFC 7807 400) otherwise, keeping the SM as the sole writer of `status`/`published`.
- **Verb = POST `/company-number`** (not PATCH) to match the all-POST vendor surface; the controller has no `@PatchMapping` precedent.
- **ONBD-01/02 not marked fully complete.** These requirements also cover the frontend confirm dialog / inline edit + their Jest tests (a later plan), so `requirements-completed` is intentionally empty here to avoid signalling a false completion. The backend halves are delivered.

## Deviations from Plan

None - plan executed exactly as written.

_(Execution note, not a plan deviation: the plan's `<verify>` blocks use `cd core-java && ./gradlew test ...`, which fails in this repo — there is no `core-java/gradlew`. `core-java` is a Gradle subproject and the wrapper lives at the repo root, so every command was run as `./gradlew :core-java:<task>` from the root, exactly as CI runs. This was the documented environment correction supplied with the task, not a change to the plan's intent.)_

## Known Stubs
None — both endpoints are fully wired to the service and state machine; no placeholder data or empty returns.

## Threat Flags
None — no new security surface beyond the plan's `<threat_model>`. Both endpoints resolve the tenant via `CurrentTenant.require()` (never the body) and load under RLS/FORCE (V43); the company-number value is bounded by `@Size(32)`+`@Pattern` before it can reach the Companies House client (T-21-01-01), and neither endpoint writes `status`/`Shop.published` directly (T-21-01-03).

## Issues Encountered
None beyond the environment-correction note above. Each TDD RED phase failed for the intended reason (endpoint absent → 404), and each GREEN phase passed on first implementation with no auto-fix cycles.

## User Setup Required
None - no external service configuration required (zero new dependencies, zero migrations, zero env vars).

## Next Phase Readiness
- The backend exit + correction endpoints are live for the Phase 21 frontend plan (21-04): the withdraw confirm dialog wires to `POST /onboarding/withdraw`; the inline company-number edit wires to `POST /onboarding/company-number` then the existing `POST /onboarding/resubmit`.
- Sibling backend plan 21-02 (outbox stall-event seam) and 21-03 (manual-review visibility + gate-resolve) remain to be executed; they do not depend on this plan's files beyond the shared `VendorOnboardingService`.
- ONBD-01 and ONBD-02 remain OPEN in REQUIREMENTS traceability pending their frontend halves.

## Self-Check: PASSED
- Files verified present: `OnboardingController.java`, `VendorOnboardingService.java`, `dto/UpdateOnboardingRequest.java`, `OnboardingWithdrawIntegrationTest.java`, `OnboardingCompanyNumberUpdateIntegrationTest.java` — all FOUND.
- Commits verified in `git log`: `c17878a`, `78c3aab`, `ad0b2e4`, `be39f5e` — all FOUND.

---
*Phase: 21-onboarding-blocker-ux*
*Completed: 2026-07-14*

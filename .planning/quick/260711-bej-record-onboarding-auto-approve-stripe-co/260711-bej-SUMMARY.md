---
phase: quick-260711-bej
plan: 01
subsystem: onboarding
tags: [spring-boot, state-machine, configuration-properties, stripe-connect, adr, vendor-onboarding]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice
    provides: VendorOnboarding aggregate + GateChainRunner recompute + OnboardingProperties + OnboardingModel enum
provides:
  - Model-aware auto-approve (WHITE_LABEL auto, MARKETPLACE manual) governed by onboarding.auto-approve-models
  - onboarding.auto-approve retained as a global force-on override
  - ADR-0001 recording both the approval stance and the Stripe money-flow decision
  - Phase 18 UAT item 5 closed (passed 5 / pending 0)
affects: [stripe-connect-money-flow, onboarding-admin-approval-queue, vendor-onboarding]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ADR convention seeded at docs/architecture/decisions/ (ADR-0001 is the first)"
    - "Per-model policy as a bound List<Enum> ConfigurationProperties field + a separate public helper (autoApprovesModel) kept out of isAutoApprove() so a @SpyBean stub still governs the global path"

key-files:
  created:
    - docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md
  modified:
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingProperties.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/onboarding/GateChainRunnerTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingPropertiesTest.java
    - docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md
    - .planning/phases/18-vendor-onboarding-first-slice/18-HUMAN-UAT.md
    - docs/metrics.json
    - README.md
    - CLAUDE.md

key-decisions:
  - "Decision 1: hybrid auto-approve keyed to OnboardingModel — WHITE_LABEL auto-approves on green gates, MARKETPLACE always manual; onboarding.auto-approve stays a global force-on override; per-model list onboarding.auto-approve-models defaults to WHITE_LABEL"
  - "Decision 2: Stripe money flow = Stripe Connect keyed to model — destination charges for MARKETPLACE, direct charges + application fee for WHITE_LABEL (recorded only, no Stripe code)"

patterns-established:
  - "ADR directory (docs/architecture/decisions/) seeded with ADR-0001"
  - "Auto-approve decision is two external calls on OnboardingProperties (isAutoApprove OR autoApprovesModel) to keep the Phase 18 @SpyBean E2E path intact"

requirements-completed:
  - "GH-178-item1-auto-approve-stance"
  - "GH-102-stripe-money-flow-decision"
  - "UAT-18-item5-auto-approve-production-decision"

# Metrics
duration: ~20min
completed: 2026-07-11
---

# Quick Task 260711-bej: Onboarding auto-approve + Stripe money-flow decisions Summary

**Model-aware onboarding auto-approve (WHITE_LABEL auto, MARKETPLACE manual) via `onboarding.auto-approve-models`, plus ADR-0001 pinning both the approval stance and the Stripe-Connect-keyed-to-model money flow; Phase 18 UAT item 5 closed and both tracking issues commented (kept open).**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-07-11T07:10:00Z
- **Completed:** 2026-07-11T07:30:00Z
- **Tasks:** 3
- **Files modified:** 10 (9 modified + 1 created)

## Accomplishments
- WHITE_LABEL onboardings now auto-approve on green gates under the default config, while MARKETPLACE halts at `PENDING_APPROVAL`; `onboarding.auto-approve=true` still force-approves both models (backward compatible).
- Two product decisions pinned in a versioned ADR (ADR-0001): hybrid auto-approve by model, and Stripe Connect keyed to model (destination charges for MARKETPLACE, direct charges + application fee for WHITE_LABEL — recorded only).
- Phase 18 vendor-onboarding UAT item 5 flipped to PASS (Summary passed 5 / pending 0, status complete); state-model §9 item 1 marked DECIDED.
- GitHub issues #178 and #102 each carry a decision comment and both remain OPEN.

## Task Commits

Each task was committed atomically:

1. **Task 1: Model-aware auto-approve (Decision 1) + tests + metrics sync** - `196ed2f` (feat)
2. **Task 2: Decision records — ADR + section 9 + UAT item 5** - `d936d6e` (docs)
3. **Task 3: Comment the decisions on GitHub issues #178 and #102** - no repo commit (GitHub side effects only)

_Task 1 combined the TDD test + implementation + metrics sync into one atomic commit because the docs-freshness gate requires code, tests and `docs/metrics.json` (+ prose counts) to move together._

## Files Created/Modified
- `core-java/.../onboarding/OnboardingProperties.java` - added `autoApproveModels` List (default `[WHITE_LABEL]`) + `autoApprovesModel(model)` helper; documented `autoApprove` as global force-on override; extended redacted `toString()`.
- `core-java/.../onboarding/GateChainRunner.java` - APPROVE now fires on `isAutoApprove() || autoApprovesModel(onboarding.getModel())` (two external calls); recompute Javadoc updated for the model-aware policy; WR-01 veto try/catch kept exactly as-is.
- `core-java/src/main/resources/application.yml` - new `onboarding.auto-approve-models` key (`${ONBOARDING_AUTO_APPROVE_MODELS:WHITE_LABEL}`); `auto-approve` comment updated to note global force-on semantics.
- `core-java/.../onboarding/GateChainRunnerTest.java` - +2 tests: WHITE_LABEL auto-approves and MARKETPLACE halts, both under global auto-approve off.
- `core-java/.../onboarding/OnboardingPropertiesTest.java` - +1 test: default `[WHITE_LABEL]` + null-safe `autoApprovesModel` behaviour.
- `docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md` - NEW; seeds the ADR directory; records both decisions with context/options/consequences.
- `docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md` - §9 item 1 rewritten to DECIDED (hybrid by model, refs ADR-0001).
- `.planning/phases/18-vendor-onboarding-first-slice/18-HUMAN-UAT.md` - item 5 → PASS; Summary passed 5 / pending 0; status → complete.
- `docs/metrics.json` - re-synced 918 → 921 logical invocations (690 → 693 Java @Test).
- `README.md` / `CLAUDE.md` - test-count prose bumped to match metrics.json.

## Decisions Made
- **Task 1 committed atomically (not TDD split test→feat):** the docs-freshness CI gate requires the new @Test methods, the regenerated `docs/metrics.json`, and the README/CLAUDE prose counts to land in the same commit as the code, so a separate `test(...)` RED commit would have left the tree docs-inconsistent between commits. The plan's `<action>` explicitly specifies one atomic `feat` commit for Task 1.
- Followed pitfall #1 exactly: `autoApprovesModel` is a separate public method that never calls `isAutoApprove()`, and the runner evaluates the two conditions as separate external calls — preserving the Phase 18 `@SpyBean` E2E behaviour.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- The onboarding integration run emitted a transient Hikari `Connection refused` stack during Testcontainers Postgres startup, but the pool retried and both suites passed (`BUILD SUCCESSFUL`); the result XMLs confirm 2 tests each, 0 failures. Build output lives under `core-java/build-local/` (not `core-java/build/`, which is intentionally avoided due to the known root-owned-directory issue).

## Verification
- `./gradlew :core-java:test --tests GateChainRunnerTest --tests OnboardingPropertiesTest` — green.
- `./gradlew :core-java:test` (full unit suite) — BUILD SUCCESSFUL.
- `./gradlew :core-java:integrationTest --tests VendorOnboardingEndToEndIntegrationTest --tests OnboardingSubmitIntegrationTest` — green (2 tests each, 0 failures; MARKETPLACE E2E `autoApproveFalse…HaltsAtPendingApproval` confirms the net-additive change).
- `bash scripts/docs-freshness.sh` — `OK: metrics match source (total logical invocations: 921)`, exit 0.
- ADR-0001 exists; state-model §9 item 1 reads DECIDED; UAT item 5 = PASS with passed 5 / pending 0.
- `gh issue view 178` and `gh issue view 102` — both `state=OPEN` with one matching decision comment each.

## No new Flyway migration
Confirmed: change is config + logic + tests + docs only. V44 remains reserved; schema stays at V43.

## Next Phase Readiness
- The Stripe money-flow phase can proceed against ADR-0001 (destination-charge MARKETPLACE flow first).
- #178 slice 2 (admin approve/reject queue for MARKETPLACE) is still required before MARKETPLACE vendors can reach LIVE; #178 remains open.

## Self-Check: PASSED
- Created file present: `docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md` — FOUND.
- Commit `196ed2f` (Task 1) — FOUND in git log.
- Commit `d936d6e` (Task 2) — FOUND in git log.
- Task 3 has no repo commit by design; verified via `gh issue view` (both OPEN, one matching comment each).

---
*Quick task: 260711-bej-record-onboarding-auto-approve-stripe-co*
*Completed: 2026-07-11*

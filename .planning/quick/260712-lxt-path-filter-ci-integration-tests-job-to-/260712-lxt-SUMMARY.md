---
phase: 260712-lxt
plan: 01
subsystem: infra
tags: [ci, github-actions, dorny-paths-filter, testcontainers, gradle]
status: complete

# Dependency graph
requires:
  - phase: "#71 integrationTest CI enablement"
    provides: "the integration-tests job (Testcontainers RLS suite) this plan path-filters"
provides:
  - "pull_request-scoped path filter on the integration-tests job — skips ~24.5-min Gradle work on diffs that cannot affect the Java integration suite while the job still reports SUCCESS"
affects: [ci-cd, pull-request-wall-time, "#99"]

# Tech tracking
tech-stack:
  added: ["dorny/paths-filter@de90cc6 (# v3.0.2, SHA-pinned)"]
  patterns:
    - "In-job STEP gating (not job-level if:) so a filtered job stays a satisfiable required check"
    - "Reusable gate expression: github.event_name != 'pull_request' || steps.filter.outputs.<f> == 'true' — push/release bypass the filter"

key-files:
  created: []
  modified:
    - ".github/workflows/ci-cd.yaml"
    - "docs/CHANGELOG.md"

key-decisions:
  - "Step-level gating instead of a job-level if: — keeps the job green (SUCCESS, not 'skipped') so build-and-push never sees a skipped dependency and the job remains a satisfiable required check."
  - "Filter scoped to pull_request only — push and release always run the full suite."
  - "SHA-pin the third-party action with a trailing # v3.0.2 comment per repo convention (supply-chain mitigation T-lxt-01)."

patterns-established:
  - "Path-filter conservatively: any touch of the compile/test inputs (core-java/**, root Gradle inputs, or the workflow file itself) forces the full suite; a Java change that skips the suite is not expressible."

requirements-completed: [ISSUE-99-do-now-path-filter]

# Metrics
duration: ~8min
completed: 2026-07-12
---

# Phase 260712-lxt Plan 01: Path-filter the CI integration-tests job Summary

**The ~24.5-min Testcontainers `:core-java:integrationTest` job now skips on `pull_request` runs whose diff cannot affect the Java integration suite (via SHA-pinned `dorny/paths-filter@v3.0.2`), while still reporting SUCCESS — push/release always run the full suite.**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-07-12T14:47Z
- **Completed:** 2026-07-12T14:55Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added an SHA-pinned `dorny/paths-filter` step (`id: filter`) to the `integration-tests` job matching `core-java/**`, the root Gradle inputs (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`, `gradlew`, `gradlew.bat`), and `.github/workflows/ci-cd.yaml`.
- Gated the JDK setup, suite run, and artifact upload steps with `github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true'` — in-job STEP gating, no job-level `if:`, so the job stays green and remains a satisfiable required check.
- Added a skip-notice step (inverse pull_request condition) that logs *why* nothing ran (names the trigger paths and states the SUCCESS-on-purpose intent).
- Left `push`/`release` behaviour unchanged: the gate short-circuits on `github.event_name != 'pull_request'`, so `build-and-push` never sees a skipped dependency.
- Recorded the change in `docs/CHANGELOG.md` under `[Unreleased]` with the reviewer-facing design rationale; `docs/metrics.json` untouched (no test-count change).

## Task Commits

Each task was committed atomically:

1. **Task 1: Add dorny/paths-filter step + gate the integration-tests job (pull_request only)** — `c8311fe` (ci)
2. **Task 2: Add docs/CHANGELOG.md entry under [Unreleased]** — `748ca8a` (docs)

_No plan-metadata commit here — per orchestrator constraints for quick tasks, docs artifacts (SUMMARY.md, STATE.md, PLAN.md) are committed by the orchestrator._

## Files Created/Modified
- `.github/workflows/ci-cd.yaml` — `integration-tests` job now has the filter step, skip-notice step, and step-level gates; no other job touched.
- `docs/CHANGELOG.md` — new `[Unreleased]` CI entry documenting the path-filter change.

## Decisions Made
- **In-job STEP gating over a job-level `if:`** — a job-level `if:` would report the job as "skipped", which is not a SUCCESS and would block `build-and-push` (which `needs: integration-tests`) on push/release and could break branch protection. Step gating keeps the job green.
- **Filter scoped to `pull_request` only** — push and release must always run the full suite so the release path is never coverage-degraded.
- **Conservative trigger set** — any touch of `core-java/**`, root Gradle inputs, or the workflow file itself forces the full suite (STRIDE T-lxt-02 accepted: a Java change that evades the suite is not expressible).

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None. `actionlint` (at `/home/sanmi/go/bin/actionlint`) reported clean on the modified workflow both after Task 1 and at final verification.

## Verification
- `actionlint .github/workflows/ci-cd.yaml` — CLEAN.
- `dorny/paths-filter@de90cc6fb38fc0963ad72b210f1f284cd68cea36 # v3.0.2` present.
- `steps.filter.outputs.integration` appears 4× (skip-notice, JDK setup, suite run, upload) — meets the ≥4 gate.
- Skip-notice inverse condition `github.event_name == 'pull_request' && steps.filter.outputs.integration != 'true'` present.
- `git diff --stat 0611fda..HEAD` shows ONLY `.github/workflows/ci-cd.yaml` and `docs/CHANGELOG.md`; `docs/metrics.json` NOT in the diff.
- Behavioural note: because this change touches `.github/workflows/ci-cd.yaml` (a trigger path), the filter MUST evaluate TRUE on this PR's own CI run, so the integration suite still runs here. The skip path will first be exercised by a docs-only PR after merge.

## Self-Check: PASSED
- FOUND: `.github/workflows/ci-cd.yaml` (modified, actionlint clean)
- FOUND: `docs/CHANGELOG.md` (modified, entry present)
- FOUND commit: `c8311fe` (Task 1, ci)
- FOUND commit: `748ca8a` (Task 2, docs)
- CONFIRMED untouched: `docs/metrics.json`

## Next Phase Readiness
- Ready to open a PR. Since this PR touches the workflow file, its own integration-tests job will run the full suite (expected). The skip path is proven by the first docs-only / frontend-only / edge-go-only / k8s-only PR merged afterward.
- No follow-up code work required.

---
*Phase: 260712-lxt-path-filter-ci-integration-tests-job-to-*
*Completed: 2026-07-12*

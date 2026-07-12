---
phase: 260712-lxt
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .github/workflows/ci-cd.yaml
  - docs/CHANGELOG.md
autonomous: true
requirements: [ISSUE-99-do-now-path-filter]

must_haves:
  truths:
    - "On a pull_request whose diff touches core-java/**, root Gradle inputs, or the workflow itself, the integration-tests job still runs ./gradlew :core-java:integrationTest (full suite)."
    - "On a pull_request whose diff touches NONE of those paths (docs-only / frontend-only / edge-go-only / k8s-only), the integration-tests job skips the Gradle work AND still reports SUCCESS (green, not 'skipped')."
    - "On push and release events the filter is bypassed — the full suite always runs, so build-and-push never sees a skipped dependency."
    - "When the suite is skipped, the job log states WHY (skip-notice echo names the trigger paths)."
    - "docs/CHANGELOG.md records this CI change under [Unreleased]."
  artifacts:
    - path: ".github/workflows/ci-cd.yaml"
      provides: "integration-tests job with dorny/paths-filter step + step-level gates + skip notice"
      contains: "dorny/paths-filter"
    - path: "docs/CHANGELOG.md"
      provides: "[Unreleased] entry for the path-filter change"
      contains: "path-filter"
  key_links:
    - from: ".github/workflows/ci-cd.yaml filter step (id: filter)"
      to: "each downstream step's if: condition"
      via: "steps.filter.outputs.integration"
      pattern: "steps\\.filter\\.outputs\\.integration"
    - from: "gate condition"
      to: "pull_request-only scoping"
      via: "github.event_name guard"
      pattern: "github\\.event_name != 'pull_request'"
---

<objective>
Path-filter the "Integration Tests (Testcontainers RLS)" CI job so its ~24.5-min Gradle `integrationTest` work is skipped on pull_request runs whose diff cannot affect the Java integration suite (docs-only / frontend-only / edge-go-only / k8s-only PRs), while the job still reports SUCCESS.

Purpose: The integration job is ~24.5 min of a ~28-min pipeline and currently runs on every PR regardless of what changed (HANDOFF.md decision 4, Issue #99 do-now slice). Skipping it on irrelevant diffs cuts PR wall-time dramatically without weakening coverage on PRs that touch Java.

Output: Modified `.github/workflows/ci-cd.yaml` (in-job step gating via dorny/paths-filter) + a `docs/CHANGELOG.md` entry. No source/test changes; `docs/metrics.json` untouched.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

# The workflow to modify. Target: job `integration-tests` (lines 122-144).
@.github/workflows/ci-cd.yaml

<interfaces>
<!-- Current shape of the integration-tests job the executor is modifying. -->
<!-- Verified live: job at ci-cd.yaml:122-144. -->

  integration-tests:
    name: Integration Tests (Testcontainers RLS)
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin', cache: 'gradle' }
      - name: Run Testcontainers integration suite
        run: ./gradlew :core-java:integrationTest --no-daemon
      - name: Upload integration test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: integration-test-results
          path: core-java/build-local/reports/tests/integrationTest/

<!-- build-and-push depends on this job (needs: [test, integration-tests, security-scan]) -->
<!-- and runs ONLY on push/release (if: github.event_name == 'push' || 'release'). -->
<!-- That is WHY the filter is scoped to pull_request only: push/release must always -->
<!-- run the full suite so build-and-push never sees a skipped dependency. -->

<!-- Repo convention: every third-party action in this workflow is SHA-pinned with a -->
<!-- trailing "# vX" version comment (e.g. dorny/paths-filter@<sha> # v3.0.2). -->

<!-- Verified repo-root Gradle files (the integration-relevant trigger paths): -->
<!-- build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/, gradlew, gradlew.bat -->
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add dorny/paths-filter step and gate the integration-tests job (pull_request only)</name>
  <files>.github/workflows/ci-cd.yaml</files>
  <action>
Modify ONLY the `integration-tests` job (ci-cd.yaml:122-144). Do NOT touch any other job. Apply in-job STEP gating — never a job-level `if:` (the job must still run and report SUCCESS on filtered PRs so it remains a satisfiable required check if branch protection is ever added; per locked decision 1).

Insert, immediately AFTER the existing "Checkout code" step and BEFORE "Set up JDK 21", a new filter step:
- `name: Filter paths affecting the Java integration suite`
- `id: filter`
- `uses: dorny/paths-filter@de90cc6fb38fc0963ad72b210f1f284cd68cea36 # v3.0.2` (SHA-pinned with version comment per repo convention — locked decision 3; on pull_request events it lists changed files via the GitHub API, so it works with the default shallow checkout).
- `with.filters` defines a single filter named `integration` matching these globs (locked decision 4 — every input that can affect `./gradlew :core-java:integrationTest`): `core-java/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`, `gradlew`, `gradlew.bat`, `.github/workflows/ci-cd.yaml`.

Define the reusable gate expression (locked decision 2): `github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true'`. This means push/release ALWAYS run; pull_request runs only when a relevant path changed.

Add a "skip notice" step right after the filter step, gated with `if: github.event_name == 'pull_request' && steps.filter.outputs.integration != 'true'`, that echoes a one-line explanation of WHY nothing ran (names the trigger paths and states the job reports success on purpose) — locked decision 5.

Apply the gate expression as an `if:` to the "Set up JDK 21" step and the "Run Testcontainers integration suite" step (locked decision 5).

Change the "Upload integration test results" step condition from `if: always()` to `if: always() && (github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true')` so it does not fail on missing artifacts when the suite was skipped (locked decision 5).

Preserve exact indentation (2-space YAML), the existing `# v3.0.2`-style comment convention, and leave the informative block comment above the job (lines 116-121) intact. Do not reorder or edit the `test`, `k8s-validate`, `openapi-compat`, `security-scan`, `build-and-push`, or deploy jobs.
  </action>
  <verify>
    <automated>actionlint .github/workflows/ci-cd.yaml && grep -q 'dorny/paths-filter@de90cc6fb38fc0963ad72b210f1f284cd68cea36 # v3.0.2' .github/workflows/ci-cd.yaml && [ "$(grep -c \"steps.filter.outputs.integration\" .github/workflows/ci-cd.yaml)" -ge 4 ] && grep -q "github.event_name == 'pull_request' && steps.filter.outputs.integration != 'true'" .github/workflows/ci-cd.yaml && echo GATES_OK</automated>
  </verify>
  <done>actionlint passes; the SHA-pinned dorny/paths-filter step exists with the version comment; `steps.filter.outputs.integration` appears in at least 4 places (JDK step, run step, upload step, skip-notice inverse check); the skip-notice inverse condition is present. Push/release paths still run the full suite (gate expression short-circuits on `github.event_name != 'pull_request'`).</done>
</task>

<task type="auto">
  <name>Task 2: Add docs/CHANGELOG.md entry under [Unreleased]</name>
  <files>docs/CHANGELOG.md</files>
  <action>
Add a new entry at the top of the `## [Unreleased]` section (above the most recent existing `### ...` entry), following the repo's Keep-a-Changelog format (`### {title} — {date}` header + a `#### Changed` or `#### CI` subsection — match the style of neighbouring entries). Date is 2026-07-12.

Content: state that the "Integration Tests (Testcontainers RLS)" CI job is now path-filtered on pull_request events via SHA-pinned `dorny/paths-filter@v3.0.2` — its ~24.5-min `:core-java:integrationTest` run is skipped when a PR's diff touches nothing that can affect the Java integration suite (docs-only / frontend-only / edge-go-only / k8s-only PRs), cutting PR wall-time on the ~28-min pipeline. Note the design choices that matter for reviewers: (a) in-job STEP gating (not a job-level `if:`) so the job still reports SUCCESS and stays a satisfiable required check; (b) filter scoped to pull_request only, so push/release always run the full suite and `build-and-push` never sees a skipped dependency; (c) trigger paths are `core-java/**`, the root Gradle inputs (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`, `gradlew`, `gradlew.bat`), and the workflow file itself. Reference Issue #99 (do-now slice). Explicitly note: no test-count/metrics change (no tests added or removed; `docs/metrics.json` untouched).

Do NOT edit `docs/metrics.json` and do NOT alter any test counts anywhere.
  </action>
  <verify>
    <automated>grep -n "path-filter" docs/CHANGELOG.md && grep -n "dorny/paths-filter" docs/CHANGELOG.md && git diff --name-only docs/metrics.json | grep -q . && echo "METRICS_CHANGED_FAIL" || echo CHANGELOG_OK</automated>
  </verify>
  <done>CHANGELOG has a new [Unreleased] entry mentioning the path-filter change and dorny/paths-filter; `docs/metrics.json` is unmodified (verify prints CHANGELOG_OK, not METRICS_CHANGED_FAIL).</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| GitHub Actions runner → third-party action | A new external action (dorny/paths-filter) executes in the CI job with read access to the diff |
| PR author → CI gate | An attacker-authored PR could try to shape its diff to evade the integration suite |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-lxt-01 | Tampering (supply chain) | dorny/paths-filter action | mitigate | SHA-pin `@de90cc6fb38fc0963ad72b210f1f284cd68cea36 # v3.0.2` (immutable ref, repo convention). No floating tag. |
| T-lxt-02 | Elevation of Privilege | PR crafted to skip the Java suite | accept | Filter is intentionally conservative — any touch of `core-java/**`, root Gradle inputs, or the workflow itself forces the full suite. A PR that changes Java without touching those paths is not expressible. push/release always run the full suite. |
| T-lxt-03 | Denial of Service | build-and-push sees a skipped dependency and stalls | mitigate | Step-level gating keeps the job green (SUCCESS, not skipped); filter scoped to pull_request only, and build-and-push runs on push/release where the filter is bypassed. |
</threat_model>

<verification>
1. `actionlint .github/workflows/ci-cd.yaml` passes (no syntax/expression errors).
2. `git diff .github/workflows/ci-cd.yaml` shows changes confined to the `integration-tests` job.
3. `git diff --stat` shows only `.github/workflows/ci-cd.yaml` and `docs/CHANGELOG.md` changed — `docs/metrics.json` is NOT in the diff.
4. Behavioural proof (post-push, on the PR's own CI run): because this PR touches `.github/workflows/ci-cd.yaml` (itself a trigger path), the filter MUST evaluate TRUE and the integration suite MUST still run on this PR. The skip path is proven later by the first docs-only PR after merge (job goes green with the skip-notice in its log and no Gradle work).
</verification>

<success_criteria>
- [ ] `integration-tests` job gated by in-job STEP conditions (no job-level `if:`).
- [ ] dorny/paths-filter SHA-pinned with `# v3.0.2` comment.
- [ ] Gate expression `github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true'` applied to JDK setup, suite run, and (as `always() && (...)`) the upload step.
- [ ] Skip-notice step present with the inverse pull_request condition.
- [ ] push/release events always run the full suite; build-and-push never sees a skipped dependency.
- [ ] CHANGELOG [Unreleased] entry added; `docs/metrics.json` untouched.
- [ ] actionlint clean.
</success_criteria>

<output>
Create `.planning/quick/260712-lxt-path-filter-ci-integration-tests-job-to-/260712-lxt-SUMMARY.md` when done.
</output>

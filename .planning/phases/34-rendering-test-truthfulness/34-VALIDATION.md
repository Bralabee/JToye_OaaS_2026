---
phase: 34
slug: rendering-test-truthfulness
status: planned
nyquist_compliant: true
wave_0_complete: true
created: 2026-08-28
updated: 2026-08-28
---

# Phase 34 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Every one of the 27 tasks in this phase carries an `<automated>` verify command, so there is no
> Wave 0 test-infrastructure gap: Jest, Playwright, `go test`, Gradle/JUnit and the `scripts/check-*.sh`
> gate family are all installed and green on this tree today.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Frameworks** | Jest 29.7.0 (frontend unit) · Playwright 1.62.1 (E2E) · JUnit 5 + Testcontainers (Java) · `go test` (Go) · bash gate scripts (`scripts/check-*.sh`) |
| **Config files** | `frontend/jest.config.js`, `frontend/playwright.config.ts`, `core-java/build.gradle.kts`, `scripts/gates/*.conf` |
| **Quick run command** | `cd frontend && npx eslint . && npx jest <path> --ci --watchAll=false` |
| **Full suite command** | `cd frontend && npm test -- --ci --watchAll=false` (120 suites / 1230 tests / ~12s) |
| **E2E (needs the live Compose stack)** | `cd frontend && npx playwright test <spec> --project=desktop` |
| **Estimated runtime** | Jest ~12s · a single Playwright spec ~30-90s · `:core-java:test` ~4-6 min · `:core-java:integrationTest` ~20-40 min |

---

## Sampling Rate

- **After every task commit:** `cd frontend && npx eslint .` + `bash scripts/check-e2e-typecheck.sh` + the Jest or Playwright file the task touched.
- **After every plan:** the plan's own `<verification>` block, in full.
- **After every wave:** `cd frontend && npm test -- --ci --watchAll=false`, `npm run build`, and the `scripts/check-*.sh` sweep.
- **Before `/gsd:verify-work`:** plan 34-10's Task 3 — a rebuilt runtime, a full Playwright suite whose report digest matches the tree, and every gate green.
- **Max feedback latency:** ~12s (Jest) for the unit tier; ~90s for a single E2E spec.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 34-01-01 | 01 | 1 | TRUTH-01 | T-34-01-04 | A shared test helper declares no base URL, so it cannot escape the base-URL contract gate | e2e | `bash scripts/check-e2e-typecheck.sh && cd frontend && npx playwright test e2e/storefront-ssr-seo.spec.ts --project=desktop` | ✅ | ⬜ pending |
| 34-01-02 | 01 | 1 | TRUTH-01 | T-34-01-01 / T-34-01-02 | Unauthenticated `/shop/orders` serves the sign-in wall and no order content | e2e | `cd frontend && npx playwright test e2e/ssr-coverage.spec.ts --project=desktop` | ❌ W1 (created by this task) | ⬜ pending |
| 34-01-03 | 01 | 1 | TRUTH-01 | T-34-01-03 | The stack-free probe server is killed by captured PID and the port proven to refuse | e2e | `cd frontend && npx playwright test e2e/storefront-ssr-seo.spec.ts e2e/ssr-coverage.spec.ts --project=desktop` | ✅ | ⬜ pending |
| 34-02-01 | 02 | 1 | TRUTH-01 | — | RED gate: suite fails before the hook exists | unit | `cd frontend && npx jest hooks/__tests__/use-theme.test.tsx --ci --watchAll=false` | ❌ W1 (created by this task) | ⬜ pending |
| 34-02-02 | 02 | 1 | TRUTH-01 | T-34-02-01 / T-34-02-02 | Only a boolean reaches `classList`; SSR render has a server snapshot and does not throw | unit | `cd frontend && npx jest hooks/__tests__/use-theme.test.tsx --ci --watchAll=false` | ✅ | ⬜ pending |
| 34-02-03 | 02 | 1 | TRUTH-01 | T-34-02-03 | Listener teardown symmetry; the ESLint rule fires on a reintroduced defect | lint+unit | `cd frontend && npx eslint . && npx jest components/dashboard hooks --ci --watchAll=false` | ✅ | ⬜ pending |
| 34-03-01 | 03 | 1 | TRUTH-01 | — | RED gate: store contract written before the store | unit | `cd frontend && npx jest hooks/__tests__/use-customer-session.test.tsx --ci --watchAll=false` | ❌ W1 (created by this task) | ⬜ pending |
| 34-03-02 | 03 | 1 | TRUTH-01 | T-34-03-01 / T-34-03-02 / T-34-03-03 | A null session clears the cache; the server snapshot is null; the marker is never trusted | unit | `cd frontend && npx jest hooks/__tests__/use-customer-session.test.tsx components --ci --watchAll=false && npx eslint .` | ✅ | ⬜ pending |
| 34-03-03 | 03 | 1 | TRUTH-01 | T-34-03-01 | A planted stale marker renders signed OUT in a real browser | e2e | `cd frontend && npx playwright test e2e/storefront-session-pill.spec.ts --project=desktop --project=mobile` | ❌ W1 (created by this task) | ⬜ pending |
| 34-04-01 | 04 | 1 | TRUTH-01 | T-34-04-01 / T-34-04-03 | Error copy is a constant, never built from query input; the one-time code is not exchanged twice | lint+build | `cd frontend && npx eslint app/shop/auth/callback/page.tsx && npm run build` | ✅ | ⬜ pending |
| 34-04-02 | 04 | 1 | TRUTH-01 | T-34-04-04 | Only a synthetic code is used; no real IdP material is recorded | e2e | `cd frontend && npx playwright test e2e/storefront-auth-callback.spec.ts --project=desktop` | ❌ W1 (created by this task) | ⬜ pending |
| 34-05-01 | 05 | 1 | TRUTH-02 | T-34-05-02 / T-34-05-03 | The overflow assertion has a presence control and cannot pass over an empty page | e2e | `cd frontend && npx playwright test e2e/dashboard-mobile.spec.ts --project=mobile` | ✅ | ⬜ pending |
| 34-05-02 | 05 | 1 | TRUTH-02 | T-34-05-01 | No new credential literal; the stub count is re-measured, not inherited | e2e | `cd frontend && npx playwright test e2e/dashboard-mobile.spec.ts --list --project=desktop` | ✅ | ⬜ pending |
| 34-06-01 | 06 | 1 | TRUTH-02 | T-34-06-03 | A skip means "nobody checked this"; "not applicable here" becomes an enumeration tag | e2e | `cd frontend && npx playwright test e2e/onboarding-blocked-flow.spec.ts --list --project=mobile` | ✅ | ⬜ pending |
| 34-06-02 | 06 | 1 | TRUTH-02 | T-34-06-01 / T-34-06-04 | The gate's VOID is recorded as VOID and not worked around | gate | `bash scripts/check-e2e-skip-budget.sh; echo "rc=$?"` (expected rc=2 until 34-10) | ✅ | ⬜ pending |
| 34-07-01 | 07 | 2 | TRUTH-01 | T-34-07-01 / T-34-07-02 / T-34-07-03 | Self-testing classifier; zero discovery VOIDs; comments stripped before matching | gate | `bash scripts/check-ssr-coverage-contract.sh; echo "rc=$?"` | ❌ W2 (created by this task) | ⬜ pending |
| 34-07-02 | 07 | 2 | TRUTH-01 | T-34-07-04 | Every dashboard CLIENT reason states the ASVS V3/V4 constraint for a future conversion | gate | `bash scripts/check-ssr-coverage-contract.sh; echo "rc=$?"` | ✅ | ⬜ pending |
| 34-07-03 | 07 | 2 | TRUTH-01 | T-34-07-05 / T-34-07-06 | The stack-free job states what its green does not cover | gate | `bash scripts/check-gate-enforcement.sh; echo "rc=$?"` | ✅ | ⬜ pending |
| 34-08-01 | 08 | 3 | TRUTH-02 | T-34-08-01 | An empty or unparseable profile VOIDs; it never reads as 0% | gate | `cd edge-go && go test -coverprofile=coverage.out ./... >/dev/null && cd .. && bash scripts/check-go-coverage.sh; echo "rc=$?"` | ❌ W3 (created by this task) | ⬜ pending |
| 34-08-02 | 08 | 3 | TRUTH-02 | T-34-08-02 / T-34-08-03 | Each threshold observed failing once; the scope decision recorded before the number | unit | `cd frontend && npx jest --coverage --coverageReporters=text-summary --ci --watchAll=false` | ✅ | ⬜ pending |
| 34-08-03 | 08 | 3 | TRUTH-02 | T-34-08-04 | The count oracle still reads the Jest run after `--coverage` is added | gate | `bash scripts/check-gate-enforcement.sh; echo "rc=$?"` | ✅ | ⬜ pending |
| 34-09-01 | 09 | 4 | TRUTH-02 | T-34-09-03 / T-34-09-SC | Reports read from `build-local`; core Gradle plugin with a pinned tool version | build | `./gradlew :core-java:test :core-java:jacocoTestReport --no-daemon; echo "rc=$?"` | ❌ W4 (created by this task) | ⬜ pending |
| 34-09-02 | 09 | 4 | TRUTH-02 | T-34-09-02 / T-34-09-04 | A unit-only report cannot pass as the aggregate; missing input VOIDs | gate | `bash scripts/check-jacoco-coverage.sh; echo "rc=$?"` | ❌ W4 (created by this task) | ⬜ pending |
| 34-09-03 | 09 | 4 | TRUTH-02 | T-34-09-01 | A skipped integration job VOIDs and its skip notice says coverage was not evaluated | gate | `bash scripts/check-gate-enforcement.sh; echo "rc=$?"` | ✅ | ⬜ pending |
| 34-10-01 | 10 | 5 | TRUTH-01, TRUTH-02 | T-34-10-03 | Counts regenerated, never computed; both runner oracles agree | gate | `bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh; echo "rc=$?"` | ✅ | ⬜ pending |
| 34-10-02 | 10 | 5 | TRUTH-01, TRUTH-02 | — | Assumptions A4/A6 named as assumptions, not as findings | doc | `test -s .planning/phases/34-rendering-test-truthfulness/deferred-items.md && rg -uu -c 'REMOVE WHEN\|Removal condition' .planning/phases/34-rendering-test-truthfulness/deferred-items.md` | ❌ W5 (created by this task) | ⬜ pending |
| 34-10-03 | 10 | 5 | TRUTH-01, TRUTH-02 | T-34-10-01 / T-34-10-02 / T-34-10-04 | Runtime parity by content and identity; the skip gate re-earned on a digest-matching report | gate+e2e | `bash scripts/check-e2e-skip-budget.sh; echo "rc=$?"` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

**Existing infrastructure covers all phase requirements.** Every framework, runner and gate this
phase needs is installed and was executed during research on 2026-08-28 (Playwright 1.62.1, Jest
29.7.0, Go 1.26 `go tool cover`, Gradle 8.10.2 + JaCoCo 0.8.12, `jq`, `gh`). No package is installed
by any plan — the phase-wide supply-chain assertion is
`git diff --name-only origin/main -- frontend/package.json frontend/package-lock.json edge-go/go.mod edge-go/go.sum mcp-server/package.json` printing nothing, enforced in plan 34-10.

The `❌` entries in the map above are files each task CREATES; none is a missing framework.

---

## Manual-Only Verifications

**All phase behaviours have automated verification.** Two clarifications so nothing is assumed:

| Behaviour | Requirement | Why it is not "manual" | How it is automated |
|---|---|---|---|
| The full nightly-equivalent E2E suite | TRUTH-02 | It needs the live Compose stack and a rebuild first, but the run itself is a command | Plan 34-10 Task 3 reproduces `e2e-nightly.yml:294-331` locally and reads the verdict from the report with `jq` |
| The JaCoCo floor as CI will measure it | TRUTH-02 | A CI figure can differ from a local one (assumption A2) | Plan 34-09 Task 3 pushes the branch (matches the `phase-*` push trigger), watches the run and reads the measured ratios from its artefacts |

---

## Falsification Contract (project-standing, applies to every row above)

Every acceptance criterion in every plan of this phase carries an explicit BREAK ARM, per the
project's falsifiable-evidence contract. The recurring shapes this phase must not repeat, each
already measured on this tree:

- A grep whose pattern never matched — every count assertion records its pre-change positive control.
- `git grep -l '"use client"'` as a conversion counter — measurably wrong by 4, and wrong in the direction that makes finished work look unfinished.
- A gate satisfied by its own prose — comments are stripped before matching in the SSR contract gate.
- `cmd | grep -q X` under `pipefail` inverting on match — here-strings only.
- An exit code read after an intervening command — `rc=$?` on the same line, every time.
- An empty coverage profile read as 0% — VOID (exit 2), never a coverage failure.
- A restore verified by `git diff --stat` — restores are verified by `git hash-object`, and every break arm runs clean → arm → clean on a committed tree.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify — 27 of 27
- [x] Sampling continuity: no 3 consecutive tasks without an automated verify
- [x] Wave 0 covers all MISSING references — none exist; every framework is installed
- [x] No watch-mode flags (`--ci --watchAll=false` everywhere)
- [x] Feedback latency < 90s for the per-task tier
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-08-28 (planner)

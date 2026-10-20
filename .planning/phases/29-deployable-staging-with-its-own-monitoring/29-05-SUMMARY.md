---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 05
subsystem: infra
tags: [postgres, azure-flexible-server, connection-budget, hikari, hpa, ci-gate, bash, tdd]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-02: the quick static set (check-render-invariants, render-golden, check-env-contract, check-no-plaintext-secrets, check-connection-math) asserted with no FAIL / PARSE ERROR text"
provides:
  - "k8s/scripts/connection-budget.conf: rows compose (compose/3), k8s-staging (429/15), k8s-production (compose/3) with provenance"
  - "k8s/scripts/check-connection-math.sh: per-target budget from CONNECTION_BUDGET_CONF, no RESERVED literal, PARSE ERROR rc 2 on a missing/non-numeric/leading-zero/duplicate/unknown/absent row"
  - "check-connection-math.sh --selftest (ARM d25 / missing / non-numeric / absent-target; CONNECTION_MATH_SELFTEST_FORMAT=tap), wired into the k8s-validate CI job"
  - "INFO counterfactual line on every run: k8s-production at Azure's reserve 15 needs 155, budget 148, Phase 32 must size max_connections >= 209 (computed)"
affects: [29-14, phase-32, k8s-validate]

actuals:
  tokens: 11500
  tasks: 1
  commits: 3
plan_head_before: e47cfe02369f3d2fac8695760c7a2260c7292605
plan_head_after: 6ac758095a70939ed1ad9de8ae4e9edae8b859e7

tech-stack:
  added: []
  patterns:
    - "Per-target datastore budget as configuration: one row per target, every target required, the compose row must say `compose` (no second copy of the compose value)"
    - "Gate selftest re-invokes the gate itself against synthetic config files and judges rc and output, with a TAP mode for the RED classifier"

key-files:
  created:
    - k8s/scripts/connection-budget.conf
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-05-t1-red-d25.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-05-t1-red-missing.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-05-t1-red-non-numeric.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-05-t1-red-absent-target.json
  modified:
    - k8s/scripts/check-connection-math.sh
    - k8s/DEPLOYMENT.md
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "The counterfactual reserve is read from the k8s-staging row (the Azure Flexible Server target), not a second literal; the minimum max_connections is computed as reserve + ceil(demand*100/80), boundary measured (208 does not fit, 209 fits)"
  - "The d25 selftest arm reads the staging demand from the reserve-3 run and asserts the premise (148 < demand <= 157) before judging the reserve-15 run, so it reports WRONG rather than passing vacuously if the pool or maxReplicas later moves"
  - "Counts reject a leading zero (bash arithmetic reads 015 as octal 13), reserved >= max_connections is a PARSE ERROR, and the provenance column is required"
  - "The INFO counterfactual is printed before any check can exit, so a failing target never hides the Phase 32 finding"

patterns-established:
  - "connection-budget.conf row format `<target> <max_connections|compose> <reserved> <provenance...>`"

requirements-completed: [DPLY-01]

coverage:
  - id: D1
    description: "The gate budgets each target at 80% of its own max_connections minus its own reserve, read from connection-budget.conf; staging at 429/15"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-connection-math.sh (rc 0, staging_reserved15=1, counterfactual_209=1, fail_or_parse=0; on the RED-commit script the same line gives staging_reserved15=0 counterfactual_209=0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The D-25 fail arm and the three parse arms are proven by --selftest, which ran RED against the RESERVED=3 script first"
    requirement: DPLY-01
    verification:
      - kind: unit
        ref: "bash k8s/scripts/check-connection-math.sh --selftest (ARM d25/missing/non-numeric/absent-target ok, rc 0; RED: all four WRONG, rc 1, RED_EVIDENCE_OK x4)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The production counterfactual (155 vs 148 at reserve 15; >= 209 needed) prints on every run and is recorded in k8s/DEPLOYMENT.md"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "INFO counterfactual line in the real-tree run; production row at 208 prints 'does not fit', at 209 prints 'fits'"
        status: pass
    human_judgment: false

duration: 5min
completed: 2026-10-18
status: complete
---

# Phase 29 Plan 05: Per-target Postgres connection budget (D-25) Summary

**check-connection-math.sh now budgets each Postgres target at 80% of its own max_connections minus its own reserve, read from connection-budget.conf. The fixed RESERVED=3 literal is gone. Staging is 429/15, so 155 ≤ 331. The selftest proves the gate rejects the D-25 case (155 > 148 at 200/15). On every run the gate also prints that production at Azure's reserve needs max_connections ≥ 209.**

## Performance

- **Duration:** about 5 min (measured from the start stamp)
- **Started:** 2026-10-18T22:09:44Z
- **Completed:** 2026-10-18T22:14:35Z
- **Tasks:** 1 (TDD: RED, GREEN, then docs)
- **Files modified:** 8 (3 source/config/CI, 1 doc, 4 RED evidence records)

## Accomplishments

- `k8s/scripts/connection-budget.conf` holds three rows, each with provenance:
  - `compose` (`compose`/3)
  - `k8s-staging` (429/15: Flexible Server B2s, measured on the August server, to be re-measured by 29-14)
  - `k8s-production` (`compose`/3, unchanged until Phase 32)
- The gate prints one budget line per target and evaluates each target against its own line:
  - compose: 157
  - k8s-staging: 331
  - k8s-production: 157
- Every bad row is a PARSE ERROR with rc 2, never a default: missing, non-numeric, leading-zero, duplicated, unknown, absent, reserved ≥ max, a missing provenance, a numeric compose row, and an unreadable config path.
- Every run prints `INFO counterfactual: k8s-production at reserved 15 … needs 155, budget 148 — does not fit at max_connections=200; Phase 32 must size max_connections ≥ 209 or lower the pool`.
- The `--selftest` is now a k8s-validate CI step.

## Task Commits

1. **Task 1: Per-target budget with a selftest that fails on the old literal first**
   - RED `dbae41a8`: `test(29-05)` adds the selftest and 4 RED records.
   - GREEN `2927aa3b`: `feat(29-05)` adds the config, the per-target gate and the CI step.
   - Docs `6ac75809`: `docs(29-05)` updates the DEPLOYMENT.md budget section.

## TDD evidence

**RED** (`dbae41a8`): the selftest was added to the unchanged script (fixed `RESERVED=3`, no config read). Output, rc 1 (the inner output was indented on each arm and is trimmed here):

```
ARM d25 WRONG reserved=15 at max_connections=200 should FAIL rc 1 (155 > 148), got rc=0
    Postgres budget: max_connections=200, reserved=3, app-usable=197, 80% budget line=157
    ...
    k8s staging (HPA max+surge)  11 replicas x pool 12  + keycloak(20)+backup(1)+exporter(2) = 155  -> OK (<= 157)
    ...
ARM missing WRONG expected PARSE ERROR rc 2, got rc=0
ARM non-numeric WRONG expected PARSE ERROR rc 2, got rc=0
ARM absent-target WRONG expected PARSE ERROR rc 2, got rc=0
```

- **Classifier:** `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK` (`target_test_failed`, TAP, 4 tests, 4 failing) for each of d25, missing, non-numeric and absent-target. The records are in `evidence/29-05-t1-red-*.json`.
- **Semantic assessment:** all four arms executed. In d25, the reserve-3 half passed and its premise held (demand 155, inside (148, 157]), so the harness worked. The reserve-15 half returned rc 0 because the old script never reads `CONNECTION_BUDGET_CONF` and still budgets at 157. Each parse arm returned a normal PASS because there was no per-target parsing yet. Every failure was the planned assertion, not a syntax, fixture or harness fault.
- **Harness fix before the RED commit:** the first run printed `tmp: unbound variable` from an EXIT trap that referenced a function-local variable. The trap now uses a global (`SELFTEST_TMP`). After the fix, a before/after count of `/tmp/tmp.*` showed no leaked temp directory.

**GREEN** (`2927aa3b`), verify line 1:

```
ARM d25 ok
ARM missing ok
ARM non-numeric ok
ARM absent-target ok
rc=0
missing=0 wrong=0
```

Verify line 2 (real tree):

```
Postgres budget [compose]: max_connections=200, reserved=3, app-usable=197, 80% budget line=157
Postgres budget [k8s-staging]: max_connections=429, reserved=15, app-usable=414, 80% budget line=331
Postgres budget [k8s-production]: max_connections=200, reserved=3, app-usable=197, 80% budget line=157
INFO counterfactual: k8s-production at reserved 15 (the Azure Flexible Server reserve of the k8s-staging row) needs 155, budget 148 — does not fit at max_connections=200; Phase 32 must size max_connections ≥ 209 or lower the pool

k8s-production (HPA max+surge)  11 replicas x pool 12  + keycloak(20)+backup(1)+exporter(2) = 155  -> OK (<= 157)
k8s-staging (HPA max+surge)     11 replicas x pool 12  + keycloak(20)+backup(1)+exporter(2) = 155  -> OK (<= 331)
compose (--scale 2)              2 replicas x pool 20  + keycloak(20)+exporter(2)+healthcheck(2) = 64  -> OK (<= 157)
...
rc=0 staging_reserved15=1 counterfactual_209=1 fail_or_parse=0
```

The fail direction of verify line 2 was run against the RED-commit script: `rc=0 staging_reserved15=0 counterfactual_209=0`, so the line exits 1 there.

**Further break arms** (each on a scratch config passed through `CONNECTION_BUDGET_CONF`; the repo file was not touched):

| Arm | Result |
|-----|--------|
| real config, staging reserved `fifteen` | rc 2 `target k8s-staging: reserved 'fifteen' is not a count` |
| duplicate k8s-staging row | rc 2 `duplicate row for target k8s-staging` |
| unknown target `k8s-stagin` | rc 2 `unknown target 'k8s-stagin'` |
| reserved `015` (octal trap) | rc 2 `reserved '015' is not a count` |
| compose row with a numeric 200 | rc 2 `max_connections must be the word 'compose'` |
| staging 15/15 | rc 2 `reserved (15) must be below max_connections (15)` |
| production row with no provenance | rc 2 `provenance column is missing` |
| staging 200/15 | rc 1 `FAIL: k8s-staging needs 155 > 148 connections …` |
| production 208/3 | rc 0, `budget 154 — does not fit at max_connections=208; … ≥ 209` |
| production 209/3 | rc 0, `budget 155 — fits at max_connections=209` |
| config path absent / a directory | rc 2 `connection budget config not readable` |
| mutant gate: every target uses the production budget | selftest rc 1, `ARM d25 WRONG …`; the mutant file was deleted |

**Acceptance criteria:**

- `command grep -nE '^RESERVED=' k8s/scripts/check-connection-math.sh` returns no line, rc 1.
  - Positive control: the same grep on the RED-commit script finds line 185, rc 0.
  - `grep -c CONNECTION_BUDGET_CONF` returns 3.
- The real-tree run gives rc 0 and prints the counterfactual line naming 209.

**Other gates after the change:**

| Gate | Result |
|------|--------|
| 29-02 quick static set | `rc=0 fail_or_parse_lines=0 output_lines=86` |
| `check-gate-enforcement.sh` | rc 0 |
| `check-doc-citations.sh` (jtoye-ops env) | rc 0, 46 verified, 0 violations; same as before the change |
| `check-claims.sh` | rc 0, 50 compared |
| `check-doc-metrics.sh` | rc 0 |
| `docs-freshness.sh` | rc 0 |
| `ci-cd.yaml` | parses with PyYAML; the new step sits directly after the connection-math step |

## Files Created/Modified

- `k8s/scripts/connection-budget.conf`: the per-target max_connections, reserve and provenance. The header records the format, why the reserve is per target (D-25), the Microsoft Learn limits page for the 15 reserved connections, and that 29-14 re-measures the 429.
- `k8s/scripts/check-connection-math.sh`: the per-target parse and budgets, the counterfactual and `--selftest`. The drift guard and HPA memory guard are unchanged.
- `k8s/DEPLOYMENT.md`:
  - "Database Connection Budget" now has a per-target table, a demand table corrected to pool 12 / 155, the Phase 32 counterfactual, and the production max_connections=200 requirement.
  - Both static-gates tables are updated.
- `.github/workflows/ci-cd.yaml`: new k8s-validate step `./k8s/scripts/check-connection-math.sh --selftest`.
- `evidence/29-05-t1-red-{d25,missing,non-numeric,absent-target}.json`: the RED records.

## Decisions Made

See `key-decisions` in the frontmatter. In short:

- The counterfactual reserve comes from the staging (Azure) row.
- The minimum max_connections is computed, not hard-coded.
- The d25 arm checks its own premise.
- Leading-zero counts are rejected.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical functionality] Wired `--selftest` into CI**
- **Found during:** Task 1, GREEN.
- **Issue:** the plan adds a selftest that proves the D-25 fail arm, but no file in `files_modified` runs it. A selftest that no workflow calls proves nothing after the commit that adds it (the repo's "unwired gates run nowhere" class).
- **Fix:** added one k8s-validate step that runs `./k8s/scripts/check-connection-math.sh --selftest` after the existing gate step.
- **Files modified:** `.github/workflows/ci-cd.yaml`, which is not in the plan's `files_modified`.
- **Commit:** `2927aa3b`

**2. [Rule 1 - Bug] Corrected a stale demand table in DEPLOYMENT.md**
- **Found during:** Task 1, docs.
- **Issue:** the table said pool 10 and a total of 133. The gate measures pool 12 and 155.
- **Fix:** rewrote the table from the gate's own output.
- **Files modified:** `k8s/DEPLOYMENT.md`
- **Commit:** `6ac75809`

The plan named the third commit REFACTOR. No code refactor was needed, so the DEPLOYMENT.md update was committed as `docs(29-05)`.

## TDD Gate Compliance

- RED `dbae41a8` (`test(29-05)`) comes before GREEN `2927aa3b` (`feat(29-05)`).
- RED was classified `RED_EVIDENCE_OK` for all four arms; the semantic assessment is above.
- There is no `refactor(29-05)` commit: none was needed.

## Issues Encountered

These are out of scope, recorded here and not filed:
- Pre-existing pool-10 prose that this change did not cause: `docs/guides/ENVIRONMENT_STRATEGY.md:299` says "11 x 10".
- Comments that are still true of the production and compose targets (157 is their line) but are not per-target:
  - `docs/runbooks/messaging.md:108` ("<= 157")
  - `k8s/base/configmap.yaml:177` and `application.yml:562` ("166 > 157")
  - Editing `configmap.yaml` would move the render goldens, so it was left alone.
- `k8s/LOCAL.md:2085` quotes "133 <= 157". That is a dated historical gate-run record, so it was left as it is.

## User Setup Required

None.

## Next Phase Readiness

- **29-14:** run `SHOW max_connections;` on the new B2s server. If the result is not 429, update the `k8s-staging` row in the same change.
- **Phase 32:** size the production Flexible Server at max_connections ≥ 209 (the INFO line recomputes this from the live pool and replica count), or lower the pool. When it is provisioned, change the `k8s-production` row to its measured max_connections and reserve 15.

## Threat Model

T-29-16 (connection exhaustion on Flexible Server) is mitigated:
- The per-target reserve is enforced in CI.
- The fail arm is proven by the selftest, which also runs in CI.
- The production counterfactual prints on every run.

No new security surface.

## Self-Check: PASSED

- FOUND: `k8s/scripts/connection-budget.conf`, `k8s/scripts/check-connection-math.sh`, `k8s/DEPLOYMENT.md`, and the 4 RED evidence records.
- FOUND commits: `dbae41a8`, `2927aa3b`, `6ac75809` (ancestors of HEAD).
- Commits were measured with `git rev-list --count e47cfe02..HEAD` = 3 when this SUMMARY was written.

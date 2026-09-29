---
phase: 36-azure-blob-storage-throughout
plan: 17
subsystem: docs
tags: [metrics, docs-freshness, handoff, phase-29, azure-blob, workload-identity, requirements]

requires:
  - phase: 36-azure-blob-storage-throughout (36-01..36-16)
    provides: "the tests whose counts are regenerated here; 36-05's provisioning runbook; 36-16's residue gate; every SUMMARY the requirement rows cite"
provides:
  - "docs/metrics.json regenerated once for the whole phase (4042 -> 4130), with the prose counts in CLAUDE.md, AGENTS.md and README.md reconciled"
  - ".planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md: superseded decisions, operator secrets 7 -> 3, provisioning order, verification items, a 35-file merge-conflict map"
  - "BLOB-01..BLOB-10 traceability rows stating real status; BLOB-09 and BLOB-10 closed"
  - "HANDOFF.md resume delta for the end of 36-17"
affects: [36-18, phase-29, phase-29-research merge]

actuals:
  tokens: 12386      # chars/4 over `git diff 25908982..a7027815` (the two task commits)
  tasks: 2
  commits: 2         # git rev-list --count 25908982..HEAD before this SUMMARY commit
plan_head_before: 25908982b18f4e3e566d69c71f44fb564383d149

tech-stack:
  added: []
  patterns:
    - "One regeneration per phase: docs/metrics.json is written by scripts/docs-freshness.sh --write, and a hand count per family from the phase diff must equal the regenerated delta"
    - "A merge-conflict map computed with git only (merge-base diffs + comm -12), one resolution rule per file, generated files regenerated rather than merged"

key-files:
  created:
    - .planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md
  modified:
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/REQUIREMENTS.md
    - HANDOFF.md

key-decisions:
  - "The operator-secret change is derived, not asserted: the 7 are the AWS_* and ALERTMANAGER_SMTP_* entries of REQUIRED_VALUES in staging-secrets.sh on phase-29-research (23 entries); removing AWS_* leaves 3, and REQUIRED_VALUES becomes 19"
  - "The conflict map uses the measured intersection (35), not the plan's '46'; the plan's own command gives 35"
  - "BLOB-10 is closed on the phase branch (the documents are complete) with the note that it reaches main at the Phase 36 merge; executing the provisioning is tracked under BLOB-02/BLOB-06 and WINDOWS #1/#3"
  - "BLOB-02, BLOB-04 and BLOB-06 are recorded Partial with the exact remaining limb (staging read-backs for Phase 29; the nightly on a runner for 36-18)"

patterns-established:
  - "A git grep secret check on a new file is vacuous until the file is tracked: check the working file with a real grep plus a positive control, then run the git grep form after commit"

requirements-completed: [BLOB-10, BLOB-09]

coverage:
  - id: D1
    description: "docs/metrics.json regenerated once (4130) and every prose count check-doc-metrics reads updated; the count oracle agrees"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "bash scripts/docs-freshness.sh; bash scripts/check-doc-metrics.sh; bash scripts/check-test-count-oracle.sh (all rc=0; each rc=1 on a planted off-by-one)"
        status: pass
      - kind: other
        ref: "hand count per family from git diff db725c94..HEAD equals the regenerated delta (+68/+8, +14/+2, +5/+0, +1/+1, 0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "36-PHASE29-HANDOFF.md: superseded decisions, 7 -> 3 operator secrets, provisioning order, A6/A7/A10/A12 + #626 + WORM checkpoint, network caveat, 35-row conflict map"
    requirement: BLOB-10
    verification:
      - kind: other
        ref: "token counts for enable-workload-identity, azure.workload.identity/client-id, render-golden.sh --write, D-12, A6, A7, A10, A12 all > 0; table rows == comm -12 intersection (35 == 35); git grep for key/SAS shapes rc=1"
        status: pass
    human_judgment: true
    rationale: "The per-file resolution rules are only proven correct by performing the phase-29-research merge; the automated checks prove presence and row count, not that each rule is right"
  - id: D3
    description: "BLOB-01..BLOB-10 traceability rows each name a delivering 36-NN plan and a Complete/Partial status matching the SUMMARYs"
    requirement: BLOB-10
    verification:
      - kind: other
        ref: "awk over .planning/REQUIREMENTS.md: 10 BLOB rows, each with 36-NN and Complete|Partial (rc=1 on a planted row with no plan)"
        status: pass
    human_judgment: false
  - id: D4
    description: "HANDOFF.md resume delta for the end of 36-17 with true gate-count and state claims"
    verification:
      - kind: other
        ref: "bash scripts/check-handoff-contract.sh rc=0 (rc=1 with EXPECT 46 -> 47)"
        status: pass
    human_judgment: true
    rationale: "check-handoff-contract cannot detect semantic rot in prose (its own header says so); the new delta's facts were measured, but a reader should confirm it reads as the right resume point"

duration: 13min
completed: 2026-09-29
status: complete
---

# Phase 36 Plan 17: Metrics regenerated once, and Phase 29's handoff Summary

**`docs/metrics.json` was regenerated once for the whole phase, from 4042 to 4130 logical invocations. A per-family hand count from the phase diff matches the regenerated numbers exactly. The three prose docs now agree with it. Phase 29 has one document on `main` to act on: the two AWS decisions this phase superseded; the operator secrets cut from 7 to 3, derived from its own `staging-secrets.sh`; the provisioning order; what it must verify; and a 35-file merge-conflict map with a rule for each file. `phase-29-research` was read, never written: its head is `ebee67fe` before and after.**

## Performance

- **Duration:** 13 min
- **Started:** 2026-09-29T09:08:59Z
- **Completed:** 2026-09-29T09:22Z
- **Tasks:** 2
- **Files modified:** 7 (1 created, 6 modified)

## Accomplishments

- Test metrics regenerated once and reconciled. docs-freshness, check-doc-metrics and the count oracle are all green, and each goes red on a planted off-by-one.
- The Phase 29 handoff (BLOB-10), with a merge-conflict map computed by git only.
- The requirement ledger tells the truth. BLOB-09 and BLOB-10 are closed. BLOB-02, 04 and 06 are Partial, each with its remaining limb named.
- HANDOFF.md resumes at 36-18, and its state table carries V66 and 4130.

## Task Commits

1. **Task 1: Regenerate the test metrics once and reconcile the quoted counts**: `720be25d` (docs)
2. **Task 2: The Phase 29 handoff with the conflict map, requirement status and HANDOFF.md**: `a7027815` (docs)

**Plan metadata:** this SUMMARY commit, then the ROADMAP/REQUIREMENTS bookkeeping commit.

## Task 1 evidence

**Pre-write (the carried red):**

| Gate | rc | Output |
|---|---|---|
| `scripts/docs-freshness.sh` | **1** | committed 4042 (Java 1897/295, Go 84/11, Jest 1873/172, Playwright 127/27, MCP 61/8); computed 4130 (1965/303, 98/13, 1878/172, 128/28, 61/8) |
| `scripts/check-test-count-oracle.sh` | **1** | jest runner=1878 manifest=1873; playwright runner=128 manifest=127; vitest 61=61 PASS |
| `scripts/check-doc-metrics.sh` | 0 | `PASS: all 37 prose metric claim(s)`: the prose and the manifest were stale **together**, so this gate could not see the drift before the regeneration. Its real red came right after `--write`: **rc=1**, 25 FAIL lines across README.md, CLAUDE.md and AGENTS.md (for example, `CLAUDE.md [total_logical_invocations]: doc says 4042, docs/metrics.json says 4130`). |

**Hand count vs regenerated delta** (`git diff --name-only db725c94..HEAD`; per file, the same content regex at base and HEAD; no renames or deletes among test paths, checked with `--name-status -M`):

| Family | Hand count (files with a non-zero delta) | Hand delta | Regenerated delta |
|---|---|---|---|
| Java `@Test` | MediaPipelineAzuriteIntegrationTest +3, ProductImageCrossTenantBlobDeleteIntegrationTest +3, AzureBlobObjectStoreTest +2, AzuriteStorageIntegrationTest +10, StorageConfigShapeTest +21, StorageDeleteTenantGuardIntegrationTest +3, StorageServiceTest +11 (15→26), StorageStartupValidatorIntegrationTest +10, WorkloadIdentityCredentialBuildTest +5 | **+68 methods, +8 files** | 1897→1965 (+68), 295→303 (+8) |
| Go `func Test*` | infra/backups/blobctl/commands_test.go +9, config_test.go +5 | **+14 funcs, +2 files** | 84→98 (+14), 11→13 (+2) |
| Jest `it/test` | frontend/__tests__/csp-headers.test.ts +5 (15→20); ReviewQueue and asset-image changed with 0 delta | **+5 blocks, +0 files** | 1873→1878 (+5), 172→172 |
| Playwright `test()` | frontend/e2e/storage-images.spec.ts +1 (new); public-layout.spec.ts 0 | **+1 block, +1 spec** | 127→128 (+1), 27→28 (+1) |
| MCP vitest | no file changed | **0** | 61→61, 8→8 |
| **Total** | | **+88** | 4042→**4130** (+88) |

The counter (`scripts/count-test-blocks.mjs`) agrees on the two JS files independently: csp-headers 20 blocks, storage-images 1.

**Post-write and break arms** (committed first; restored by `git checkout` from HEAD; the sha256 of `docs/metrics.json`, `CLAUDE.md` and `README.md` was identical before and after):

| Criterion | Real tree | Planted break |
|---|---|---|
| docs-freshness | rc=0 `metrics match source (total logical invocations: 4130)` | jest_blocks 1878→1879 in metrics.json: **rc=1** (`"jest_blocks": 1879` vs `1878`) |
| count oracle (jest) | rc=0, runner=1878 manifest=1878 | same break: **rc=1** `the runner says 1878, docs/metrics.json says 1879` |
| count oracle (playwright) | rc=0, 128=128, specs 28=28 | playwright_blocks 128→129: **rc=1** `runner says 128 … says 129` |
| check-doc-metrics | rc=0, 37/37 | CLAUDE.md 4130→4131: **rc=1** `doc says 4131, docs/metrics.json says 4130`; README Go 98→97: **rc=1** `doc says 97 … says 98` |
| `jq -r '.go_test_funcs, .playwright_specs'` > 84 and > 27 | PASS go=98 specs=28 | the pre-phase manifest (`git show db725c94:docs/metrics.json`): **FAIL** go=84 specs=27 |

The oracle is a separate instrument, not a separate source of truth. It runs the real jest, playwright and vitest runners and compares their counts with `docs/metrics.json`. The manifest stays the single source of truth, and the oracle stays in step because the manifest is regenerated from the tree it counts.

**Regression gates after the CLAUDE.md edit:** the 36-16 residue gate rc=0 (2518 files, 52 entries, 0 violations, 0 stale entries). The edits replaced text on the same lines and CLAUDE.md, AGENTS.md and README.md kept their line counts, so no line-level allowlist entry moved. `check-doc-citations.sh` under `jtoye-ops` gave rc=1 with **5 violations, all pre-existing `.planning/codebase`** (the baseline). `check-claims` was 47/47.

## Task 2 evidence

**Computed facts (all read-only against `phase-29-research`):**

- The merge-base with origin/main is `bb2ae65d`. The branch is 96 commits ahead of it. P29 = **112** files.
- The Phase 36 merge-base is `db725c94`. P36 = **219** files at `720be25d`. The intersection is **35**; after the Task 2 commit it is still 35.
- The operator secrets: `REQUIRED_VALUES` has **23** entries. `AWS_*|ALERTMANAGER_SMTP_*` gives **7**, and without `AWS_*` it gives **3**. The same 7 names appear in `29-PROVISIONING-EVIDENCE.md` §9.2 ("populated=0 empty=7 of 7").
- `azure-staging-provision.sh` contains `--enable-workload-identity` **0** times. The control, `--enable-oidc-issuer`, appears 2 times.
- The plan states that staging Redis moved to 10000. That is **confirmed**: `k8s/staging/configmap-patch.yaml` has `redis.port: "10000"` (Azure Managed Redis, plan 29-10), and INV-7's second arm declares `__REDIS_PORT__`.
- Residue a merge would carry in: **115** lines added by Phase 29 match the 36-16 gate's pattern classes. 71 are in `.planning/` (path-allowlisted). 24 are in goldens (regenerated away; they come from the base pg-backup CronJob, and the current staging golden has 0). **20 lines in 6 files** must be rewritten. Positive and negative controls on the combined pattern: a planted "MinIO" line = 1 match, an "azurite" line = 0.
- INV-9 hazard: the Phase 29 goldens reference `s3-media-credentials` and `s3-backup-credentials`. Phase 29 **inherited** those refs from the base (3 and 2 refs at `bb2ae65d`). It did not add them. So the merge takes Phase 36's removal.

**Criteria, both directions** (committed first; clean → arms → clean; the sha256 of the three files was identical at the close):

| Criterion | Real tree | Planted break |
|---|---|---|
| Plan verify: token counts | enable-workload-identity=4, azure.workload.identity/client-id=1, render-golden.sh --write=3, D-12=3, A6=1, A7=1, A10=1, A12=1 | every `A12` → `A1x`: `ZERO: A12`, **rc=1** |
| `check-handoff-contract.sh` | rc=0 (1 gate-count claim, 28 state claims) | `EXPECT 46` → `47`: **rc=1** `says 'EXPECT 47 x rc=0' but the repo has 46 gate script(s)` |
| Conflict table rows == intersection | 35 == 35 | row 35 deleted: 34 != 35, **rc=1** |
| `phase-29-research` unchanged | `ebee67fe` before Task 2, after the commit, and after the arms | (a sha comparison; its fail direction is any other sha) |
| No key/SAS shapes (`git grep -E 'AccountKey\|SharedAccessSignature\|sig='`) | **rc=1** (file tracked, after commit) | a planted `AccountKey=planted` line: **rc=0**, match printed |
| Every BLOB row names a 36-NN plan and a Complete/Partial status | 10 rows, rc=0 | BLOB-10 row → plans `tbd`, status `Done`: `BAD row: BLOB-10`, **rc=1** |

## Files Created/Modified

- `docs/metrics.json`: regenerated (4130).
- `CLAUDE.md`, `AGENTS.md`: the line-15 Testing constraint, with all five families, their file counts and the total.
- `README.md`: the test badge (line 7) and the test list (lines 291-296).
- `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md`: new; the BLOB-10 handoff.
- `.planning/REQUIREMENTS.md`: the BLOB-02/04/06/09/10 traceability rows.
- `HANDOFF.md`: the 2026-09-29 resume delta, the header dates, and the schema and test-manifest rows.

## Decisions Made

- See `key-decisions` in the frontmatter. In short: the 7→3 figure is derived from the file, the conflict map uses the measured 35, BLOB-10 closes on the branch, and three requirements stay Partial with their limb named.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The plan's secret-shape criterion was vacuous as written**
- **Found during:** Task 2
- **Issue:** `git grep … -- 36-PHASE29-HANDOFF.md` searches tracked content only. Before the commit the file was untracked (`git ls-files --error-unmatch` rc=1), so the check returned rc=1 whatever the file said.
- **Fix:** Before the commit, I ran the same regex with `/usr/bin/grep` on the file: rc=1. A copy with an `AccountKey=` line appended matched: rc=0. After the commit I ran the plan's `git grep` form as written, with a planted arm (table above).
- **Files modified:** none (a verification change)
- **Commit:** a7027815 (the file under check)

**2. [Rule 1 - Bug] My first draft of the handoff misattributed four facts, caught before commit**
- **Found during:** Task 2, while verifying each claim against the branch
- **Issue:** The draft put the "Media storage (s3.*) is deliberately NOT overridden" comment in the production patch; it is in the **staging** patch. It described the gate-enforcement.conf change as "exemptions"; it is a comment block only. It gave `staging-secrets.sh`'s residue lines as "36-39"; they are 39/245/576/578/582/663/664. And it summed the residue to "19 lines in 7 files"; the measured figure is 20 lines in 6 files. The draft also described the local kustomization change too vaguely: it is `rabbitmq-cluster-delete-patch.yaml` plus egress replacements.
- **Fix:** Each fact was re-measured with `git show`/`git diff` on the branch and corrected. Rows 22, 28, 29, 33 and 35, §2 and §6.2 were edited.
- **Commit:** a7027815

**3. [Rule 1 - Bug] A HANDOFF.md claim I was about to write was false**
- **Found during:** Task 2
- **Issue:** The draft delta said the branch was "not pushed". `git ls-remote` shows `origin/phase-36-azure-blob-storage` at `5b6e76bd`, pushed when the phase opened, 111 commits behind local HEAD. `gh pr list --head` returns none.
- **Fix:** The delta states the measured remote sha and that no PR exists.
- **Commit:** a7027815

---

**Total deviations:** 3 auto-fixed (all Rule 1)
**Impact on plan:** Every fix makes a claim true or a check able to fail. No scope change.

**Plan-text discrepancies recorded, not deviations:** the objective's "46 of this phase's files" measured as **35** with the plan's own command, and the handoff says so. `check-doc-metrics` was not red before the regeneration (explained above), so its red direction is the post-`--write` run and two planted arms.

## Issues Encountered

- My first residue measurement used two `-P` patterns. `/usr/bin/grep` refused them ("the -P option only supports a single pattern") and the pipeline printed `files=0`. I caught it because the rc and stderr were printed. I re-ran it as one alternation, with a positive and a negative control. It is recorded as a reminder that an empty result there was an error, not a zero.

## Known Stubs

None.

## Open Items (for 36-18, Phase 29, and the phase verifier)

- **36-18** (not autonomous): the owner approves the push and one nightly dispatch. That run closes BLOB-04's last limb and WINDOWS #2.
- **Phase 29:** everything in `36-PHASE29-HANDOFF.md` §§2-6. That includes WINDOWS #1 (register `Microsoft.Storage` and re-run check-name) and #3 (the live AKS admission path, A6). BLOB-02 and BLOB-06 close when those read-backs are recorded.
- `deferred-items.md` item 2 (the frontend NetworkPolicy prose naming the old store) now measures **0** such mentions in `10-frontend.yaml` and `networkpolicies/README.md`, so it appears resolved in the tree, most likely by 36-16's rewrite. Its `status: open` was not changed, because that file is outside this plan's `files_modified`. Items 1 and 3 remain open (`RETENTION_DAYS` still appears once in `k8s/base/pg-backup-cronjob.yaml`).
- jtoye-orgos charters flagged by 36-15 remain an open item. That repo was not touched.
- The HANDOFF.md "Resume here" section below the deltas still describes the `main` checkout as of #726. Its state table's schema and manifest rows now carry measured values. The dated "Gate sweep 2026-08-25" row is left as a historical record.

## User Setup Required

None. This plan configures no external service. The Azure provisioning it hands over is Phase 29's operator work, per the runbook.

## Next Phase Readiness

Ready for **36-18** (the nightly on a runner and the final parity readings). It needs an owner checkpoint to push. The phase-29-research merge has its map; re-run §6.1's commands at merge time, because `main` will have moved.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

## Self-Check: PASSED

- Created file exists: `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md`
- Commits found: `720be25d`, `a7027815` (`git rev-list --count 25908982..HEAD` = 2 before this SUMMARY commit)
- Closing gates: docs-freshness 0, check-doc-metrics 0 (37/37), count oracle 0 (jest/playwright/vitest), residue 0, handoff-contract 0, check-claims 0 (47/47), doc-citations at the baseline of 5
- `phase-29-research` head: `ebee67fe`, unchanged

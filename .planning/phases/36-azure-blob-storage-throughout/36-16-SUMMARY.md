---
phase: 36-azure-blob-storage-throughout
plan: 16
subsystem: ci-gates
tags: [residue-gate, git-grep, pcre, allowlist, ci, falsifiability, azure-blob, tdd]

requires:
  - phase: 36-09
    provides: networkpolicies without the in-cluster object-store rule (comments this plan finished)
  - phase: 36-10
    provides: k8s docs/overlays on Workload Identity; LOCAL.md region-aware allowance; .env.example delete line
  - phase: 36-12
    provides: check-media-urls-resolve.sh (names the retired origin because it rejects it)
  - phase: 36-14
    provides: core-java clean apart from the applied V42 migration comment
  - phase: 36-15
    provides: the line-level exception list this allowlist is built from
provides:
  - scripts/check-no-object-store-residue.sh (exit 0/1/2; env GATE_SCAN_PATHSPEC)
  - scripts/gates/object-store-residue-allowlist.conf (52 reasoned line/region/file/dir entries)
  - scripts/gates/object-store-residue-selftest.sh (41 hermetic TAP arms, run in CI before the gate)
  - CI step "Assert no retired object-store residue in tracked files (Phase 36)" in ops-contracts
  - the last script/CI/live-doc residue rewritten (20 files)
affects: [36-17, 36-18, any-future-doc-or-script-edit]

actuals:
  tokens: 22446        # chars/4 over git diff 74bcd4e8..HEAD before the SUMMARY commit
  tasks: 3
  commits: 5           # MEASURED: git rev-list --count 74bcd4e8..HEAD before the SUMMARY commit
plan_head_before: 74bcd4e83f766029efd994aa5a8b42bdc8ecbac4

tech-stack:
  added: []
  patterns:
    - "A text gate self-tests every pattern class on the SAME engine and flags as its scan (git grep --no-index -P) against positive and negative controls before it scans; a miss either way is VOID"
    - "Allowlist entries are line/region-level by default and fail when stale, shadowed, duplicated or unreasoned, so the list cannot rot into a blanket pass"
    - "A gate's arms live in a committed hermetic harness (throwaway git repo per arm, TAP output) that CI runs before the gate"

key-files:
  created:
    - scripts/check-no-object-store-residue.sh
    - scripts/gates/object-store-residue-allowlist.conf
    - scripts/gates/object-store-residue-selftest.sh
    - .planning/phases/36-azure-blob-storage-throughout/evidence/36-16-red-evidence.json
    - .planning/phases/36-azure-blob-storage-throughout/evidence/36-16-arms.txt
  modified:
    - .github/workflows/ci-cd.yaml
    - HANDOFF.md
    - scripts/seed-media-review-fixtures.sh
    - scripts/check-dependency-horizons.sh
    - scripts/check-no-measured-placeholders.sh
    - infra/load-testing/baseline.sh
    - infra/load-testing/media-pipeline-arm.sh
    - .github/dependabot.yml
    - .gitignore
    - .env.example
    - k8s/scripts/check-env-contract.sh
    - k8s/scripts/check-render-invariants.sh
    - k8s/base/networkpolicies/10-frontend.yaml
    - k8s/base/networkpolicies/20-core-java.yaml
    - k8s/base/networkpolicies/40-datastores.yaml
    - k8s/base/networkpolicies/README.md
    - k8s/PRODUCTION_READINESS_REPORT.md
    - scripts/restore-drill.sh
    - scripts/check-media-urls-resolve.sh
    - scripts/dev-media-reseed.sh
    - infra/monitoring/README.md
    - infra/dependency-horizons.yaml

key-decisions:
  - "Wiring, the two ci-cd.yaml comment rewrites and the HANDOFF count bump landed in the GREEN commit that creates the gate, not in a later Task 3 commit. The plan's own must-have says 'wired in the same commit that creates it', and a split would leave check-gate-enforcement, check-handoff-contract and the gate itself red at an intermediate commit. Task 3's commit carries the wiring arm evidence."
  - "Allowlist granularity is stricter than the plan's list: V42 and ADR-0002 are allowed by LINE (V42:6, ADR-0002:21), not as whole files. Directory and whole-file entries exist only where the plan classes the record as historical (.planning/, docs/archive|audit|analysis|planning|reports/, CHANGELOG, MEDIA-BACKFILL-PLAN)."
  - "Comment-only 'removed in Phase 36' notes in live scripts and manifests were reworded to 'the retired object store' instead of allowlisted, so the list carries fewer line entries in frequently edited scripts. Verbatim records (fenced Phase 26 output in LOCAL.md, quoted lines, the Phase 26 report section) stay verbatim and are allowlisted line by line."
  - "R-1 is strictly stronger than the plan's: the old origin also matches 127.0.0.1:9000, and the case-sensitive env rule is (?<![A-Za-z0-9])S3_[A-Z][A-Z_]* so a prefixed AWS_S3_BUCKET is caught (the plan's \\bS3_ misses it, because '_' is a word character)."
  - "BLOB-09 is NOT marked complete: requirements.ready-ids reports 0/1 because 36-17 also declares it."

patterns-established:
  - "Line allowances are unforgiving: an edit that shifts an allowed line makes the old number stale and the moved line a violation (arm recorded), so a renumber has to happen in the same reviewed change"

requirements-completed: []   # plan declares [BLOB-09]; it stays open until 36-17 (metrics + handoff) also completes

coverage:
  - id: D1
    description: "Repo-wide residue gate over tracked files: R-1 tokens, case-sensitive S3_ env names, R-2 prose; exit 0 on the tree"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "bash scripts/check-no-object-store-residue.sh -> rc=0, files_scanned=2516 raw_hits=1657 allowlisted=1502 violations=0 stale=0"
        status: pass
      - kind: other
        ref: "same gate on the pre-phase tree 394335ce (scratch worktree) -> rc=1, 657 violations"
        status: pass
    human_judgment: false
  - id: D2
    description: "The gate fails on planted residue (fresh file AND next to an allowlisted line), passes negative controls, and VOIDs on empty scope, broken allowlist, blind or invalid patterns and missing git"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "scripts/gates/object-store-residue-selftest.sh -> 41/41 (RED 0/41 against a do-nothing stub; tdd-red-evidence RED_EVIDENCE_OK)"
        status: pass
      - kind: other
        ref: "/tmp/claude-3616-arms.sh -> 27 in-tree arms MATCH, 0 mismatches, restores verified by blob hash (evidence/36-16-arms.txt)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Allowlist hygiene: every entry reasoned, no duplicates, no stale or shadowed entries"
    verification:
      - kind: other
        ref: "awk reason check -> '52 0' (broken arm: '52 1'); blank/duplicate/stale/shifted arms rc=1"
        status: pass
    human_judgment: false
  - id: D4
    description: "Wired into ci-cd.yaml ops-contracts (harness then gate); check-gate-enforcement and check-handoff-contract green"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "check-gate-enforcement rc=0; step removed -> rc=1 naming check-no-object-store-residue.sh; restored by hash -> rc=0"
        status: pass
      - kind: other
        ref: "check-handoff-contract rc=0 (EXPECT 46); EXPECT 45 arm -> rc=1; actionlint ci-cd.yaml rc=0"
        status: pass
    human_judgment: false
  - id: D5
    description: "The last script, CI and live-doc residue rewritten without changing behaviour"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "bash -n on 10 scripts rc=0; horizons 0, env-contract 0, render-invariants 0, render-golden 0, placeholders 0, doc-citations at baseline 5"
        status: pass
    human_judgment: false
  - id: D6
    description: "The step actually runs and passes on a GitHub-hosted runner (git built with PCRE, fresh checkout)"
    verification: []
    human_judgment: true
    rationale: "Nothing was pushed (executor instructions). Every run above is local. The gate VOIDs loudly if the runner's git lacks PCRE, but the first real CI run is the proof; 36-18 / the PR will show it."

duration: 22min
completed: 2026-09-29
status: complete
---

# Phase 36 Plan 16: Retired object-store residue gate Summary

**A tracked-file gate (`git grep -P`, self-tested on its own engine) that fails on any retired object-store token outside a 52-entry, line-level, reasoned allowlist. It is proven by 41 hermetic CI arms and 27 in-tree arms, finds 657 violations on the pre-phase tree, and is wired into ops-contracts in the commit that creates it. The last 20 files of script, CI and live-doc residue are rewritten.**

## Performance

- **Duration:** about 22 min (08:43Z to 09:05Z)
- **Started:** 2026-09-29T08:43Z (first read); first commit 08:5xZ
- **Completed:** 2026-09-29T09:05:31Z
- **Tasks:** 3/3
- **Files:** 27 changed (949 insertions, 31 deletions), 5 created

## Accomplishments

- **Task 1: residue cleared.** The plan's 7 files were rewritten. So were 13 more files with comment/text misses that the full R-1/R-2 scan found and no earlier plan listed:
  - `.env.example` (a stale "until 36-10" clause)
  - `check-env-contract.sh`: 3 lines, including the `CSP_UPGRADE_INSECURE_REQUESTS` reason, which now names Azurite on :10000
  - `check-render-invariants.sh`: 2 comments
  - the networkpolicy comments and README
  - `restore-drill.sh`: a comment and an echo
  - the monitoring README, the horizons note, and the headers of the media-URL gate and the reseed tool
  - `k8s/PRODUCTION_READINESS_REPORT.md`: a dated "Superseded in part" note was appended; the historical text is untouched

  No executable behaviour changed. The positive control was the pre-edit hit list: 176 lines. After the edits, the only hits outside historical records were the 2 CI comments that Task 3 owns.
- **Task 2: the gate** `scripts/check-no-object-store-residue.sh`:
  - **Self-test first.** Every pattern class is run through `git grep --no-index -P`, with and without `-i`: the same engine and flags as the scan. It must see all 31 positive controls and none of the 7 negative controls.
  - **Scan.** It then enumerates `git ls-files -z` and scans with `git grep -z -n -I` into files, because NUL bytes cannot live in a bash variable. Each hit is attributed to the most specific allowlist entry.
  - **Allowlist rules.** A blank reason, a duplicate, and a stale or shadowed entry each fail. A line entry that an edit has shifted fails loudly, and the message names where the hits now are.
  - **VOID** on: git missing, PCRE unsupported or a pattern invalid, an empty scope, and a missing, empty or unparseable allowlist.
- **Allowlist:** 52 entries:
  - 8 plan-accepted historical directories/files
  - 44 line or region entries: V42:6, ADR-0002:21, the runbook history lines, the Phase 26 report lines, 18 LOCAL.md lines, the functional retired-origin lines, the blobctl negative test, and 14 "S3 = section/step label" lines in frontend/
- **Hermetic arms** `scripts/gates/object-store-residue-selftest.sh`: 41 TAP arms, each in a throwaway git repository. CI runs them before the gate, so a gate that can no longer fail goes red.
- **Task 3: wiring.**
  - New ops-contracts step (harness, then gate) with a comment saying it is static by construction.
  - The two CI comments that named the retired store (l.412, l.636) are rewritten.
  - Both HANDOFF gate-count claims moved 45 -> 46.

## Task Commits

1. **Task 1: clear the remaining residue:** `7a41324e` (chore)
2. **Task 2 RED: hermetic arms:** `1de24656` (test)
3. **Task 2 GREEN: gate + allowlist + CI wiring + HANDOFF:** `1c50a239` (feat)
4. **Task 2 REFACTOR: a narrowed-scope PASS says how much it judged:** `27276b77` (refactor)
5. **Task 3: wiring, pre-phase and in-tree arm evidence:** `b32cea10` (test)

**Plan metadata:** the commit that adds this SUMMARY.

## TDD Gate Compliance

- RED `1de24656` (`test(36-16)`). The harness was run against a stub gate that exits 0: 0/41 pass. The target arm "planted MinIO in a fresh tracked file exits 1 naming file:line" failed on its assertion. `gsd check tdd-red-evidence` returned `RED_EVIDENCE_OK`; the record is `evidence/36-16-red-evidence.json`. With no gate at all the harness VOIDs (rc=2); that run was not used as RED.
- GREEN `1c50a239` (`feat(36-16)`): 41/41.
- REFACTOR `27276b77` (`refactor(36-16)`). Arm 30 gained the assertion "were NOT judged". It fails against the GREEN gate (40/41) and passes against the refactored gate (41/41).

## Evidence, both directions

Every break arm ran after its target was committed. Each restore was done with `git show HEAD:<f> > <f>` and verified by blob hash. Every set ended with 0 tracked changes. Full output: `evidence/36-16-arms.txt`.

| Criterion | Real tree | Break |
|---|---|---|
| T1 verify: `bash -n` on the 5 plan scripts, plus the 5 out-of-plan scripts | all rc=0 | n/a (syntax check) |
| T1 verify: horizons gate | **rc=0** (run in conda env `jtoye-ops`; see Issues) | not armed: comment-only edit |
| T1 AC: `git grep -i minio -- scripts infra dependabot .gitignore` (gate files excluded) | **rc=1**, no lines | same grep at HEAD before T1: **rc=0, 7 hits** |
| T1 AC: remaining hits are Task 3's or allowlistable | 2 non-historical hits left, both ci-cd.yaml (l.412, l.636) | pre-edit list: 176 hit lines, 54 files |
| T1 A11: crontab / user systemd | 27 cron lines, 257 units, **0** matches for the product name | control: `claude-memory-backup` matched 2 units |
| T2 verify: gate on the tree | **rc=0**, `files_scanned=2516 raw_hits=1657 allowlisted=1502 violations=0` | **rc=1** on the tree before GREEN (the 2 CI comments) |
| T2 arm: planted name in a fresh tracked file | n/a | `the MinIO console`, `minio lower`, `MINIO_ROOT_USER`: **rc=1** `VIOLATION zz-arm-fresh.md:2` |
| T2 arm: the other four shapes, fresh file | n/a | `S3_BUCKET=` [R-1b], `localhost:9000`, `quay.io/minio/minio`, `s3-media-credentials`: **rc=1** each, naming `:2` |
| T2 arm: next to an allowlisted line | n/a | all 5 shapes appended to `.env.example:193` (192 is allowed): **rc=1** naming `:193` only. Token on `LOCAL.md:1107` (past region 1105-1106): **rc=1** |
| T2 arm: negative controls | fresh tracked file of SES host, `blob:`, `jtoye-images`, `jtoye-db-backups`, `UI-SPEC S3`: **rc=0** | n/a |
| T2 arm: blank reason | n/a | `docs/reports/|`: **rc=1** `blank reason` |
| T2 arm: duplicate | n/a | `k8s/LOCAL.md:139` twice: **rc=1** `duplicate entry` |
| T2 arm: stale | n/a | `sealed-secrets.md:37`: **rc=1** `stale entry`. One inserted line in `check-media-urls-resolve.sh`: **rc=1**, 2 violations (:23, :92) + 2 stale |
| T2 arm: empty pathspec | n/a | `GATE_SCAN_PATHSPEC=no-such-dir/`: **rc=2** |
| T2 VOIDs (beyond the plan's list) | n/a | allowlist missing / comments-only / unparseable: **rc=2** each. R-1 without the product name: **rc=2** (`matched 22 of 25 positive controls`). R-2 invalid PCRE: **rc=2** (git grep rc=128). git not on PATH: **rc=2** |
| T2 AC: pre-phase tree `394335ce` | n/a | **rc=1, 657 violations** (> 50) plus 22 stale entries |
| T2 AC: awk reason count | `52 0` | blank-reason arm: `52 1` |
| T2 hermetic arms (CI) | **41/41**, rc=0 | stub gate: **0/41**, rc=1 |
| T3 verify: check-gate-enforcement | **rc=0** (45 gates) | CI step removed: **rc=1** `check-no-object-store-residue.sh` unwired; restored by hash, rc=0 |
| T3 verify: check-handoff-contract | **rc=0** (EXPECT 46) | EXPECT 45: **rc=1** `H-1 … the repo has 46 gate script(s)` |
| T3 AC: `git grep check-no-object-store-residue -- ci-cd.yaml` | rc=0, 2 lines (975, 977) | step removed: 0 workflow references |
| T3 AC: `git grep -i minio -- .github/workflows` | **rc=1** | at `7a41324e` (before GREEN): **rc=0**, l.412 + l.636 |
| actionlint ci-cd.yaml | rc=0 | not armed |
| doc-citations | rc=1 with **the same 5** `.planning/codebase` violations as baseline | not armed |

## Files Created/Modified

See `key-files`. Nothing was deleted (`git diff --diff-filter=D` is empty). Only one line was removed as a line: the `.gitignore` entry for the retired store's local data directory. No file under `frontend/`, `core-java/`, `edge-go/` or `mcp-server/` changed (`git diff --stat 74bcd4e8..HEAD` over those paths is empty). **No running image's build inputs changed**, so this plan does not make the runtime stale.

## Decisions Made

See `key-decisions`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Sequencing, needed for the plan's own truth] Wiring landed in the creating commit**
- **Found during:** Task 2
- **Issue:** The plan splits the gate (Task 2) from its wiring (Task 3), but its must-have says the gate is "wired in the same commit that creates it". With the split, the gate commit would leave check-gate-enforcement red (an unwired gate), check-handoff-contract red (46 vs 45) and the gate itself red (the 2 CI comments).
- **Fix:** GREEN `1c50a239` contains the gate, the allowlist, the CI step, both ci-cd comment rewrites and the HANDOFF bump. Task 3's commit `b32cea10` records the wiring arms.

**2. [Rule 2, TDD] Hermetic arms harness added: `scripts/gates/object-store-residue-selftest.sh`**
- **Issue:** `tdd_mode` needs a RED test before the gate. The plan's arms were one-off working-tree runs that nothing would ever repeat.
- **Fix:** A committed 41-arm TAP harness that CI runs before the gate. It is the third file the gate excludes by path, because it must name every token. It is not a `scripts/check-*.sh`, so the gate counts are unaffected.

**3. [Rule 2, stronger check] R-1 is wider than the plan's patterns**
- `(localhost|127\.0\.0\.1):9000` instead of `localhost:9000`.
- `(?<![A-Za-z0-9])S3_[A-Z][A-Z_]*` instead of `\bS3_[A-Z_]+`, which cannot see `AWS_S3_BUCKET`.
- **Effect:** 3 more allowlist lines (LOCAL.md:1089, :1091, :1913), all Phase 26 history. Both widened classes have positive controls in the self-test and arms in the harness.

**4. [Rule 2, strictness] Line-level where the plan listed whole files**
- V42 and ADR-0002 are allowed by line. Every allowlist entry was measured against the tree; the stale check rejects any speculative one.

**5. [Rule 1/2] Out-of-plan comment/text residue fixed in Task 1** (13 files; the list is in Accomplishments)
- These are misses from earlier plans. Each is a comment, prose or string edit.
- Every touched script passed `bash -n` and its own gate: env-contract 0, render-invariants 0, render-golden 0.
- 36-15 had listed `check-env-contract.sh:105`, `check-render-invariants.sh:161/:421`, `20-core-java.yaml:119`, `40-datastores.yaml:77`, `check-media-urls-resolve.sh:9`, `dev-media-reseed.sh:6` and `dependency-horizons.yaml:204` as allowable history. These were reworded instead ("the retired object store"). The claims are unchanged, and those frequently edited files need fewer line entries.
- `check-env-contract.sh:210` was in no plan's list and not in 36-15's list either. Its reason string told operators that MinIO images live at localhost:9000.

**6. [Rule 2, accuracy] k8s/PRODUCTION_READINESS_REPORT.md**
- The Phase 26 pre-activation section tells an operator to create the retired media Secret.
- **Fix:** It is kept verbatim and allowlisted by line (626, 631, 634). An appended, dated note says that storage is now Workload Identity, that INV-9 fails a build carrying such a Secret, and that `StorageStartupValidator` probes the containers. This follows the ADR-0002 append-only precedent.
- No citation points into the shifted lines (checked with `git grep 'PRODUCTION_READINESS_REPORT.md:[0-9]'`: rc=1).

**7. [Plan wording] "HANDOFF.md's two gate-count claims"**
- H-1 reads only one of them, the EXPECT line. The second is the prose at l.146, which H-1 does not read. Both were updated, and the prose now also records "46 since plan 36-16".

**8. [Rule 1, found by an arm] REFACTOR `27276b77`**
- A narrowed-scope PASS claimed that all 52 entries were live, when none had been judged. It now names the scope and the count of entries it did not judge. A harness assertion guards this.

---

**Total deviations:** 8 (1 sequencing, 3 strengthening, 3 accuracy/scope, 1 bug found by an arm). **Impact:** the gate is stricter than planned. There is no scope creep beyond comment and text edits and one new harness file.

## Issues Encountered

- **Two gates need PyYAML, and the machine's python shim refuses bare `python3`:** `check-dependency-horizons.sh`, and `check-doc-citations.sh` for its YAML sources. Both VOID (rc=2) in a plain shell. They were run inside the conda env `jtoye-ops`, where both gave their true results (0, and baseline 5). This is the environment, not code; CI has python3 + PyYAML.
- **Full gate sweep (in `jtoye-ops`)** has 9 non-zero gates, none caused by this plan:
  - Runtime VOIDs, because the monitoring stack is not running: alert-liveness, alert-metrics, alert-mute, container-config-drift, infra-exposure.
  - e2e-skip-budget VOID: the report describes a different spec set.
  - doc-citations rc=1: the same 5 baseline violations.
  - Inherited reds owned by 36-17: `docs-freshness.sh`, and `check-test-count-oracle.sh`. The oracle reports **both** jest 1878 vs 1873 **and** playwright 128 vs 127; the handoff named only the Playwright one. This plan touched no test file.
- One edit early in Task 2 was made with a stdlib `/usr/bin/python3 -` stdin script, not a `-c` one-liner (a process note; stdlib only). Later edits used the Edit tool.

## Open items

- **Line-entry churn is by design.** An edit above an allowed line fails CI until the entry is renumbered in the same change. This applies to `k8s/LOCAL.md` (18 entries), `frontend/e2e/customer-signout-idp-session.verify.mjs` (9), the Phase 31-14 label files and the two retired-origin scripts. The gate's message names the new line numbers. If this proves too noisy, narrowing R-2 for the "S3" step labels is the lever, not a whole-file entry.
- **`.planning/` is allowlisted as a whole directory** (plan-accepted). So `.planning/codebase/`, which 36-15 cleaned, is not protected by this gate.
- **`scripts/gates/postgres-major-parity.conf`:14-27** still says "Two real lines in this tree defeat a bare-token match". Since 36-08 the second example (`infra/backups/Dockerfile:4`) no longer exists; line 22 is now a historical quotation and is allowlisted. The present-tense claim is stale. It is a comment, not gated, and left for the conf's owner.
- **The CI step has not yet run on a hosted runner** (nothing pushed). It needs git with PCRE; the gate VOIDs loudly if the runner's git lacks it.
- **BLOB-09 stays open** until 36-17 lands (requirements.ready-ids 0/1).
- **jtoye-orgos registry** (from 36-15): still upstream of the AGENTS.md roster; not this repository.

## User Setup Required

None.

## Next Phase Readiness

Ready for 36-17 (metrics + handoff). When 36-17 regenerates `docs/metrics.json` and the prose counts, it should keep `EXPECT 46`, unless it adds another `scripts/check-*.sh`. 36-18 does not need to rebuild any image for this plan: no build input changed.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

## Self-Check: PASSED

- Created files exist: gate, allowlist, selftest, red-evidence.json, arms.txt
- Commits found: 7a41324e, 1de24656, 1c50a239, 27276b77, b32cea10
- `git rev-list --count 74bcd4e8..HEAD` = 5 before this SUMMARY commit
- Closing runs: residue gate 0, hermetic arms 41/41, gate-enforcement 0, handoff-contract 0, actionlint 0, doc-citations at baseline 5

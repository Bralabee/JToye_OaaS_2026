---
phase: 36-azure-blob-storage-throughout
plan: 08
subsystem: infra
tags: [backups, azure-blob, azurite, blobctl, pg_dump, rls, restore-drill, e2e-nightly, dependency-horizons, runbook]
status: complete

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-02: compose azurite (jtoye-azurite) on the compose network; 36-04: blobctl (upload 0/3/1, list, download 4, emulator-only connection string, no delete); 36-05: jtoye-db-backups container, write-only backup role, human time-bound Reader for restores"
provides:
  - "Image ghcr.io/bralabee/jtoye-pg-backup:15-blob (built locally, 628 MB vs 832 MB for the pre-plan build): golang:1.27-alpine builder for blobctl, postgres:15-bookworm runtime, no AWS CLI, uid 1000"
  - "k8s-backup.sh env contract: required BACKUP_CONTAINER + STORAGE_AUTH_MODE (checked before the dump), optional BACKUP_PREFIX (default backups); upload = blobctl upload; no prune, no list, no delete"
  - "Gate scripts/check-backup-restore-drill.sh (exit 0/1/2; overrides PG_BACKUP_IMAGE, PG_CONTAINER, AZURITE_CONTAINER, ENV_FILE, DRILL_ARM_A_USER, DRILL_ARM_B_USER, DRILL_STEP_TIMEOUT)"
  - "Nightly step 'Gate — a backup restored from Blob carries tenant rows, and a zero-row dump is caught' (if: always() && steps.stack-up.outcome == 'success')"
  - "docs/runbooks/backups.md rewritten for Blob; docs/archive/backups-rehearsal-evidence-2026-07.md (verbatim)"
  - "Horizon sites infra/backups/Dockerfile:32 (golang) and infra/backups/blobctl/go.mod:3 (go-toolchain)"
affects: [36-09, 36-17, 36-18, phase-29-provisioning]

actuals:
  tokens: 20130        # chars/4 over git diff 21abbda5..b1c076e9 (the four task commits), before this SUMMARY
  tasks: 3
  commits: 4           # MEASURED: git rev-list --count 21abbda5..HEAD before the SUMMARY commit
plan_head_before: 21abbda549db613651b776399bc6b4f558805582

tech-stack:
  added:
    - "golang:1.27-alpine as a build-only stage of the pg-backup image"
  removed:
    - "the Debian awscli package from the pg-backup image"
  patterns:
    - "A counterexample arm split in two when the plan's single form is impossible on a correct tree: A-job proves the rc safety net, A-dump proves the content checks are blind"
    - "Every restored count is returned beside current_database() and must name this run's scratch DB, so a count from the wrong DB cannot pass"
    - "Credentials reach containers only via 0600 --env-file in a 0700 dir; psql gets the role password with \\getenv from a name-only docker exec -e; every captured output is scanned for every credential value"
    - "Fault-injecting throwaway images (PG_BACKUP_IMAGE override) as break arms for storage-side corruption the job's own checks cannot see"

key-files:
  created:
    - scripts/check-backup-restore-drill.sh
    - docs/archive/backups-rehearsal-evidence-2026-07.md
  modified:
    - infra/backups/Dockerfile
    - infra/backups/k8s-backup.sh
    - .github/workflows/e2e-nightly.yml
    - docs/runbooks/backups.md
    - infra/dependency-horizons.yaml
    - HANDOFF.md
    - scripts/check-postgres-major-parity.sh

key-decisions:
  - "Arm A is split into A-job and A-dump. The plan's A (the backup job as jtoye_app exiting 0) is impossible on a correct tree: pg_dump asks for row_security=off and Postgres refuses it to a non-BYPASSRLS role, so the job exits 1. A-job asserts that exit and that nothing reached Blob; A-dump takes the hollow dump with --enable-row-security, shows it passes the size floor read out of the image and the TOC read, then restores 0"
  - "Only products is asserted (A = 0, B = live > 0). orders/customers/shops are reported: shops has a public read policy (shops_public_read), so an app-role read legitimately sees 3 published shops"
  - "The nightly step runs at the END with if: always() && steps.stack-up.outcome == 'success' (the Wait step gained id: stack-up), so a red suite cannot skip the backup proof and a red drill cannot skip the suite"
  - "The role bootstrap passes the password with psql \\getenv instead of -v backup_password=<value>, keeping it off every command line (the plan's own T-36-28 rule)"
  - "Real-account restores use az storage blob download --auth-mode login under a human's time-bound Reader, not blobctl: the CronJob identity is write-only by design and blobctl has no human auth path. blobctl download is the Azurite path"
  - "The Dockerfile's documented build tag stays :15 until 36-09 (D-10: tag references, CronJob, horizons pin and goldens move in one change)"

patterns-established:
  - "A restore gate must show FAILING on truncated, bit-rotted, empty and missing dumps and on a count from the wrong database, not only on a wrong role"

requirements-completed: []   # plan declares [BLOB-06]; requirements.ready-ids 0/1 — 36-09 (the :15 -> :15-blob tag move in the manifests, D-10) also declares it and has no SUMMARY

coverage:
  - id: D1
    description: "The pg-backup image ships blobctl and no AWS CLI, builds multi-stage on the unchanged postgres:15-bookworm base, runs as uid 1000, and fails fast on missing Blob config"
    requirement: BLOB-06
    verification:
      - kind: other
        ref: "docker build -t ghcr.io/bralabee/jtoye-pg-backup:15-blob infra/backups (build=0); sh -c 'command -v aws' -> 127; entrypoint blobctl -> 2; id -u -> 1000; pre-plan image built from 21abbda5: aws=0, blobctl=127"
        status: pass
      - kind: other
        ref: "docker run with STORAGE_AUTH_MODE unset -> rc=1 at line 37 before any pg_dump output; BACKUP_CONTAINER unset -> rc=1; control with both set reaches pg_dump"
        status: pass
      - kind: other
        ref: "bash scripts/check-postgres-major-parity.sh rc=0 (arm FROM postgres:16-bookworm rc=1, restored by hash, closing rc=0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "k8s-backup.sh keeps its dump/verify half byte-identical, uploads with blobctl and fails the job on a non-zero upload, and has no prune, listing or delete"
    requirement: BLOB-06
    verification:
      - kind: other
        ref: "sha256 of the log()..'Dump verified' block, old vs new: identical 8b9f29ec...; bash -n 0; retired=0 upload=1 (pre-plan file: retired=13 upload=0); sha256 of /usr/local/bin/backup in the image == repo file"
        status: pass
    human_judgment: false
  - id: D3
    description: "The two-arm restore drill runs as an executable gate: A-job exits non-zero and uploads nothing, the hollow A-dump passes the pipeline's content checks yet restores products=0, B restores products equal to live (23 > 0); FAILs on a wrong role, a wrong database, truncated/bit-rotted/empty/missing dumps and a leaked credential; VOIDs on a stopped Azurite, an absent image, a CHANGE_ME or absent credential and a zero live count; cleans up"
    requirement: BLOB-06
    verification:
      - kind: integration
        ref: "bash scripts/check-backup-restore-drill.sh against compose postgres + azurite: rc=0 (first run and closing run), A=0 B=23 live=23"
        status: pass
      - kind: integration
        ref: "break arms: DRILL_ARM_B_USER=jtoye_app rc=1; DRILL_ARM_A_USER=jtoye_backup rc=1; fault images truncated/bitrot/empty/missing rc=1 each; count from live DB rc=1 (identity check), and with the identity check removed rc=1 (arm A); leaking image rc=1; Azurite stopped rc=2; absent image rc=2; CHANGE_ME rc=2; absent key rc=2; live products 0 rc=2"
        status: pass
      - kind: other
        ref: "post-run: pg_database like 'jtoye_restore_drill_%' = 0; /tmp/backup-drill.* = 0; awk docker-run-with-PASSWORD = 0 (planted copy = 1)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The drill is wired into the nightly on every run where the stack came up, and gate-enforcement / handoff-contract stay green"
    requirement: BLOB-06
    verification:
      - kind: other
        ref: "actionlint 0; bash -n on all 18 run blocks 0 failures (missing-esac arm rc=2); .env append logic simulated on CHANGE_ME / absent / real value; check-gate-enforcement rc=0 (drill unwired arm rc=1); check-handoff-contract rc=0 (before the HANDOFF edit rc=1, 'EXPECT 43' vs 44)"
        status: pass
    human_judgment: true
    rationale: "The nightly step has never executed on a GitHub runner; only its YAML and shell were validated locally. 36-18 runs the nightly (WINDOWS.md entry 2)."
  - id: D5
    description: "The live runbook describes Blob, Workload Identity and blobctl with no retired object-store instruction; the dated evidence is archived byte-for-byte; the new Go build stage is tracked by the horizons gate"
    requirement: BLOB-06
    verification:
      - kind: other
        ref: "git grep -i 'minio|aws s3|awscli|aws-cli|S3_BUCKET' -- docs/runbooks/backups.md rc=1 (HEAD file: 7 hits); blobctl=8 drill=2 (HEAD: 0 / 0); archived body cmp to the HEAD sections: identical (sha256 65c8984f...; one-byte mutation cmp=1); relative links resolve (control BROKEN)"
        status: pass
      - kind: other
        ref: "check-dependency-horizons.sh (conda jtoye-ops) rc=0; golang:1.26-alpine at infra/backups/Dockerfile:32 rc=2 naming the site; go 1.26.0 in blobctl/go.mod rc=2 naming the site; the pre-plan manifest with the same drift rc=0 (blind)"
        status: pass
    human_judgment: false

duration: 23min
completed: 2026-09-29
---

# Phase 36 Plan 08: pg-backup on Blob and the Executed Restore Drill Summary

**The pg-backup image now builds `blobctl` in a Go stage and ships no AWS CLI (628 MB, down from 832 MB). The script uploads with `blobctl`, never prunes, and fails before the dump if Blob config is missing. `scripts/check-backup-restore-drill.sh` restores real dumps from Azurite and counts them: arm A restores 0, arm B restores exactly the live 23 products. It fails on a wrong role, a wrong database, and a truncated, bit-rotted, empty or missing dump, and it VOIDs when the stack or a credential is absent. The nightly runs it whenever the stack comes up.**

## Performance

- **Duration:** about 23 min
- **Started:** 2026-09-29T00:38:37Z
- **Completed:** 2026-09-29T01:01:30Z
- **Tasks:** 3 of 3
- **Files:** 9 (2 created, 7 modified), +972 / -225

## Accomplishments

- **Image.** `infra/backups/Dockerfile` is now two stages:
  - `golang:1.27-alpine AS builder` runs `go mod download && go mod verify` ("all modules verified") and builds a static, stripped, `-trimpath` blobctl.
  - `postgres:15-bookworm` (unchanged) installs only `ca-certificates`.
  - uid 1000, `HOME=/tmp`, same ENTRYPOINT.
  - Built locally as `ghcr.io/bralabee/jtoye-pg-backup:15-blob`: id `63505f559187`, 628 MB. A pre-plan build from `21abbda5` was 832 MB.
- **Script.**
  - `k8s-backup.sh` keeps its dump/verify block byte-identical: the 20-line `log()`…`Dump verified` block hashes the same before and after.
  - It requires `BACKUP_CONTAINER` and `STORAGE_AUTH_MODE` before the dump.
  - It uploads with `blobctl upload`; any non-zero exit fails the job.
  - The prune, `RETENTION_DAYS` and every listing call are gone. A comment names where retention lives now.
- **Gate.** `scripts/check-backup-restore-drill.sh` (476 lines). A PASS requires both arms:
  - **B:** the image runs exactly as the CronJob does, as `jtoye_backup`, into `jtoye-db-backups/drill/<run>/B/`. A second container lists exactly one dump, downloads it, restores it into a scratch DB and counts.
  - **A-job:** the same job run as `jtoye_app` must exit non-zero and upload nothing.
  - **A-dump:** the zero-row dump passes the pipeline's own floor, read out of the image, and its TOC read, then restores 0.
  - Every count carries `current_database()`.
  - Credentials only ever travel in 0600 env files or through `\getenv`.
- **Nightly.** The drill step is the last gate:
  - It builds the image and appends an ephemeral `DB_BACKUP_PASSWORD` only when the value is empty or `CHANGE_ME`.
  - It is keyed on `steps.stack-up.outcome`.
  - The header's FAIL-CLOSED CONTRACT names it.
- **Runbook.** The CronJob half of `docs/runbooks/backups.md` is rewritten:
  - destination (D-01/D-11) and Workload Identity with a write-only role (D-02)
  - the image and the env contract
  - what the checks cannot see
  - retention by WORM, soft delete and lifecycle
  - the re-measured RLS mechanism
  - restore procedures for both real accounts and Azurite
  - the drill, what Azurite cannot prove, and a Phase 29 pending list
- **Archive and horizons.**
  - The 2026-07 evidence moved byte-for-byte to `docs/archive/backups-rehearsal-evidence-2026-07.md`.
  - The horizon rows `golang` and `go-toolchain` gained the new sites.

## Task Commits

1. **Task 1: image + script.** `511be793` feat(36-08): ship blobctl in the pg-backup image and upload dumps to Blob
2. **Task 2: drill gate + nightly + HANDOFF.**
   - `1adf6793` feat(36-08): execute the two-arm restore drill from Blob as a nightly gate
   - `bf0b93a8` fix(36-08): name what reached Blob when the app-role job uploads a dump (wording found by a break arm)
3. **Task 3: runbook, archive, horizons.** `b1c076e9` docs(36-08): rewrite the backup runbook for Blob and archive the 2026-07 evidence

**Plan metadata:** the SUMMARY commit that follows, plus a ROADMAP/REQUIREMENTS progress commit.

TDD: `workflow.tdd_mode` is on, but the plan is `type: execute` and no task carries `tdd="true"`. The gate is itself the test, and it is proven by its break arms below. No RED/GREEN pair was required and none is claimed.

## Evidence: every criterion, both directions

Scratch space was `/tmp/claude-3608`. Every break arm ran after its task's commit, and every in-place edit was restored by sha256 and followed by a closing clean run.

### Task 1

| Criterion | PASS (real tree) | FAIL (deliberately broken input) |
|---|---|---|
| verify 1: build / aws / blobctl | `build=0`, `aws=127`, `blobctl=2` (usage on stderr) | Image built from the pre-plan `infra/backups` (`git archive 21abbda5`): `aws=0` (`/usr/bin/aws`), `blobctl=127`. Removed afterwards |
| verify 2: `bash -n`, retired, upload | `bash -n` 0, `retired=0`, `upload=1` | The pre-plan script: `retired=13`, `upload=0` |
| `id -u` prints 1000 | `1000` | Not armed (a USER change would be a different image); the Dockerfile's `USER 1000:1000` is unchanged from before |
| fail-fast with `STORAGE_AUTH_MODE` unset | rc=1, `line 37: STORAGE_AUTH_MODE: … is required`, with **no** "Starting backup" line; `BACKUP_CONTAINER` unset gives rc=1 at line 36 | Control with both set: the log reaches `Starting backup` and `pg_dump` (rc=1 on the unresolvable host), which proves the two earlier rc=1s came before the dump |
| parity gate rc=0 | rc=0, 8 sites, all 15 | `FROM postgres:16-bookworm`: rc=1, `FAIL: TOOLING majors disagree … 15 16`. Restored by hash (`a3170750…`), closing rc=0 |
| dump/verify half unchanged | `log()`…`Dump verified` block sha256 `8b9f29ec…`, the same old and new | n/a (an equality of two hashes) |
| image content = repo | `/usr/local/bin/backup` in the image `b460d790…` = `infra/backups/k8s-backup.sh` | Image `LastTagTime` 00:40:15Z. The commit touching `infra/backups` followed 30 s later, from the identical tree, so it is the hash that proves parity, not the time |

### Task 2

**First real run:** rc=0, against compose `postgres` + `azurite` only. `core-java` was never started: `docker ps --filter name=core-java -q` was empty throughout.

```
live    products=23  (orders|customers|shops=67|7|5, reported only)
Arm B   job rc=0 · 15813530 bytes · LISTED=1 · restored products=23 in jtoye_restore_drill_…_b
Arm A-job  job rc=1 (row-level security refusal on "customers") · blobs under A-job/: 0
Arm A-dump FLOOR=1000 SIZE=15762631 TOC=412 UPLOADED=1 · restored products=0 in …_a (orders|customers|shops=0|0|3)
arm A restored products = 0 / arm B restored products = 23 / live products = 23
PASS
```

| Arm | Result |
|---|---|
| (plan 1) arm B forced to `jtoye_app` | **rc=1**. `FAIL: arm B: the backup job exited 1 as jtoye_app`. The plan predicted "B restores 0"; the real mechanism is that the job fails, see deviation 1 |
| arm A forced to `jtoye_backup` (the count check) | **rc=1** with three failures: A-job SUCCEEDED; 1 blob reached `A-job/`; A restored **23, not 0** |
| truncated dump (fault image) | **rc=1**. The job exited 0 (the fault is past its checks). The restore failed: B `BYTES=4096 ERR=pg_restore rc=1`, and the same for A |
| bit-rotted dump (4 KiB zeroed mid-file) | **rc=1**, `pg_restore rc=1` on both arms, at the full size of 15813530 bytes |
| empty dump | **rc=1**, `BYTES=0 ERR=pg_restore rc=1` |
| missing dump (upload skipped, job rc=0) | **rc=1**, `LISTED=0 ERR=expected exactly 1 dump … found 0` |
| count read from the LIVE database | **rc=1**, `FAIL: arm B: the count came from database 'jtoye', not this run's scratch database …` |
| the same, with the identity check also removed | **rc=1**. Arm B **passes vacuously** (23 = 23, read from live); only arm A catches it (`restored products=23, not 0`). Arm B alone cannot tell |
| a step prints `PGPASSWORD` (fault image) | **rc=1**, `FAIL: step job-b printed a credential value … output suppressed`. The saved output contains 0 occurrences of each of the three credential values |
| (plan 2) Azurite stopped | **rc=2**, `VOID: container 'jtoye-azurite' is 'exited', not running` |
| image absent | **rc=2** |
| `DB_BACKUP_PASSWORD=CHANGE_ME` | **rc=2**, before the role bootstrap, so the role's password was not reset |
| `DB_MIGRATION_PASSWORD` absent | **rc=2** |
| live products = 0 (scratch DB with empty tables via `ENV_FILE`) | **rc=2**, `the live database holds 0 products — zero-vs-zero proves nothing`. The first try VOIDed for a different reason (no `orders` table); it was redone so this branch is the one exercised |
| **closing clean run** (on the committed `ef947da5…` file) | **rc=0**, A=0, B=23, live=23 |

Other criteria:
- `git grep -n 'check-backup-restore-drill' -- .github/workflows/e2e-nightly.yml` gives **2 lines**: :32 is the header comment, :454 the invocation. Note that `check-gate-enforcement` counts a comment as a reference too (pre-existing).
- `awk '/docker run/ && /(PGPASSWORD|PASSWORD)=/'` gives **0**. On a copy with a planted `docker run -e PGPASSWORD="$SUPER_PASS"` it gives **1**.
- After every run, `pg_database LIKE 'jtoye_restore_drill_%'` is **0**, and `/tmp/backup-drill.*` count is **0**.
- **Network auth is real, not loopback trust.** From the image on the compose network: a garbage password gives rc=2 `FATAL: password authentication failed`, and the `.env` password gives rc=0 `jtoye_backup|23`. `pg_hba` is `trust` on 127.0.0.1 only and `scram-sha-256` for everything else.
- **Nightly wiring.**
  - actionlint v1.7.12: 0 findings, on both the base and the new file.
  - `bash -n` on all 18 extracted `run:` blocks: 0 failures. The drill block with its `esac` deleted gives rc=2; a first try whose sed matched nothing was caught as vacuous by the empty diff and redone.
  - The `.env` append logic, run on scratch files: CHANGE_ME → appended; absent → appended; a real value → untouched.
- `check-gate-enforcement.sh`: rc=0, 43 gates. With the reference removed from the workflow: rc=1, `check-backup-restore-drill.sh (invokes: docker psql)`. Restored by hash, closing rc=0.
- `check-handoff-contract.sh`: **rc=1** before the HANDOFF edit (`'EXPECT 43 x rc=0' but the repo has 44`), **rc=0** after it (H-2 28/28 against the forge).

### Task 3

| Criterion | PASS | FAIL |
|---|---|---|
| retired-store grep over the runbook | `rc=1`, no lines | The HEAD runbook: 7 matching lines. Stronger `rg -uu -i '\bs3\b|\baws\b|bucket|endpoint-url|RETENTION_DAYS'`: one hit, `RETENTION_DAYS` in the **host** `backup.sh` knob table (l.78), which is legitimately that script's own prune |
| blobctl ≥ 2, drill ≥ 1 | `blobctl=8`, `drill=2` | HEAD runbook: `0`, `0` |
| archive byte-identical | The body between the VERBATIM markers `cmp` equals the two sections extracted from `git show HEAD:docs/runbooks/backups.md` (5698 bytes, sha256 `65c8984f…`) | One byte changed (`products=25`→`26`, line 9): `cmp` rc=1, "differ: byte 537". A first mutation targeted the wrong line and changed nothing, so it was caught and redone |
| horizons gate | rc=0 (under conda `jtoye-ops`; PyYAML), `missing-row=0`, `site-unresolvable=0`, `pin-not-at-site=2` (go-ci-setup, pre-existing) | `golang:1.26-alpine` at `infra/backups/Dockerfile:32`: **rc=2**, `H-5 golang: declared pin … NOT FOUND … (declared site infra/backups/Dockerfile:32)`. `go 1.26.0` in `blobctl/go.mod`: **rc=2**, naming `infra/backups/blobctl/go.mod:3`. **Sites load-bearing:** the pre-plan manifest with the same Dockerfile drift gives rc=0 (blind). All restored by hash; closing rc=0 |
| relative links | 4/4 resolve | control path: BROKEN |
| other doc gates | `check-claims` 47/47, `check-doc-metrics` 37/37, `check-doc-versions` 148/148, all rc=0 | n/a (regression only; none reads backups.md) |

## Decisions Made

See `key-decisions` in the frontmatter. The load-bearing one is the arm A split (deviation 1).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Unsatisfiable criterion] Arm A as specified cannot pass on a correct tree**
- **Found during:** Task 2 design, measured before writing the gate.
- **Issue:** The plan asked for the backup job, run as `jtoye_app`, to exit 0 and restore 0.
  - `pg_dump` requests `row_security=off`, which Postgres refuses for a non-BYPASSRLS role on a FORCE-RLS table, so it exits 1. Measured: `plain_rc=1`, leaving a 170541-byte partial file. 26-07 recorded the same.
  - Satisfying the criterion would have meant removing the job's rc check, or adding `--enable-row-security` to the production script, which would destroy a safety net.
- **Fix:** A-job (the real job must fail and upload nothing) plus A-dump (the hollow dump taken with `--enable-row-security` must pass the size floor, read out of the image, and the TOC read, then restore 0).
  - The plan's truth "arm A passes the pipeline's own checks yet restores products = 0" holds for A-dump's content checks.
  - The job's rc check is now asserted too.
  - The plan's fail arm 1 (B as the app role) therefore fails on the job's exit, not on "B restores 0". This is recorded as observed.
- **Committed in:** `1adf6793`

**2. [Rule 1 - Wrong expected exit] Horizons H-5 arm exits 2, not 1**
- **Found during:** Task 3.
- **Issue:** The plan expected exit 1. H-5 drift is the gate's VOID class (exit 2), the same finding 36-02 recorded (deviation 1 there). The site is named in the output, so the criterion's intent holds, with a stronger code.

**3. [Rule 2 - Correctness] Assertions narrowed to `products`; other tables reported only**
- **Found during:** Task 2, measured.
- **Issue:** As `jtoye_app` with no GUC, shops = **3** because of `shops_public_read (published = true)`. An A == 0 assertion on shops would red a correct tree. The 26-07 record (`shops=0`) no longer describes this DB. B == live for orders/customers/shops could also race a running core-java in the nightly.
- **Fix:** Assert `products` (as the plan does); print the rest.

**4. [Rule 2 - Security] The role bootstrap uses `\getenv`, not `-v backup_password=<value>`**
- **Found during:** Task 2.
- **Issue:** The plan's key link spells the password on the psql command line, which contradicts its own threat mitigation T-36-28.
- **Fix:** `{ printf '\getenv backup_password DRILL_BACKUP_PW\n'; cat create-backup-role.sql; } | DRILL_BACKUP_PW=… docker exec -i -e DRILL_BACKUP_PW …`, with a name-only `-e`. The runbook documents the same form for operators.

**5. [Rule 2 - Missing critical] Additional defences the plan did not name**
- A `current_database()` identity check on every count. It is proven load-bearing: without it, a count read from the live database passes arm B.
- A scan of all captured output for every credential value.
- `pg_restore --exit-on-error`, and a rule that a failed restore is never a zero count.
- Fault-image arms for storage-side corruption. The job exits 0 in all four, so only the restore catches them.
- `POSTGRES_PASSWORD` is also required: the restore runs over the network as the superuser.

**6. [Rule 2 - Coverage] Nightly placement**
- **Issue:** Placed right after the Wait step (the plan's reading), a failing drill would skip the whole Playwright suite and every gate behind it, which is the decay this workflow's cart-identity comment documents.
- **Fix:** The step sits last with `if: always() && steps.stack-up.outcome == 'success'`. It still runs on every run where the stack came up. The Wait step gained `id: stack-up`.

**7. [Rule 2 - Accuracy] The parity gate's closing NOTE**
- **Issue:** It said "nothing in this repo executes a restore drill". This plan made that false, and `scripts/restore-drill.sh` already had.
- **Fix:** It now names both scripts. The file is outside `files_modified`; the edit is one output line in `1adf6793`.

**8. [Rule 1 - Runbook] Restore from a real account uses `az … --auth-mode login`, not `blobctl`**
- **Issue:** The plan says "restore procedure using blobctl download". The CronJob identity is write-only (D-02 / provisioning §6), and blobctl has no human auth path, so blobctl cannot read a real backup account.
- **Fix:** The runbook documents `az storage blob download` (every line pins `--subscription`) under a human's time-bound Reader, and `blobctl download` for Azurite.

**9. [Plan text] `docker ps --filter name=jtoye-core-java -q` cannot see core-java**
- **Issue:** Compose names it `jtoye_oaas_2026-core-java-1` (the service has no `container_name`), so that filter is blind.
- **Fix:** Used `name=core-java`. It was empty throughout, and `docker ps -a` showed only `jtoye-postgres` and `jtoye-azurite`.

---

**Total deviations:** 9 (1 unsatisfiable criterion replaced, 1 wrong exit code, 5 correctness/security/coverage additions, 1 runbook correction, 1 blind criterion strengthened).
**Impact on plan:** Deviations 1 and 5 make the drill able to fail in the ways that matter. No scope beyond `files_modified` except the one-line parity NOTE (deviation 7).

## Issues Encountered

- The horizons gate needs PyYAML. It was run under `conda activate jtoye-ops`, as 36-02 and 36-04 did.
- Two of my own break arms were vacuous at first: the `esac` deletion and the archive mutation. Each was caught because its diff/cmp showed no change, and both were redone. Recorded above.

## Runtime left behind (override 8)

| Item | State |
|---|---|
| Containers `8138df7a4cfb` (jtoye-postgres), `538b5e9caef6` (jtoye-azurite) | Created by `compose up -d postgres azurite`; stopped and removed by ID |
| Network `3270f5216456` (`jtoye_oaas_2026_jtoye-network`) | Removed |
| Container, network and volume lists | **Identical** to the pre-plan snapshot (`diff` empty for all three) |
| Fault images | Five throwaway `jtoye-pg-backup-3608-fault:*` images removed |
| Kept images | `ghcr.io/bralabee/jtoye-pg-backup:15-blob` (a plan artifact) and `postgres:15-alpine` (pulled for compose) |
| **Existing volumes** | Not deleted or recreated, but written to by the drill, as the plan requires: |
| &nbsp;&nbsp;`azurite_data` | Now holds **20 blobs, all under `drill/`** (241 MB; blobctl has no delete, and there is no local lifecycle) |
| &nbsp;&nbsp;`postgres_data` | Now has the role `jtoye_backup` (BYPASSRLS, LOGIN, password = `.env` `DB_BACKUP_PASSWORD`), which did not exist before. Every scratch database was dropped |

No MinIO image was pulled. `k8s/` was not touched.

## Known reds inherited (not fixed, per override 10)

- `scripts/docs-freshness.sh`: rc=1, the same as 36-04/36-07 left it (Java 1914/303, Jest 1878, Go 98/13). This plan moves **0** counts, since docs-freshness counts no bash.
- `k8s/scripts/check-env-contract.sh`: untouched (36-09).
- Pre-existing and unrelated: `check-doc-citations` rc=1 (5 `.planning/codebase` citations), and `check-terminal-states` VOID (the pyshim refuses its bare `python3`).

## Threat Flags

None beyond the register. Each mitigation was shown failing above:
- **T-36-28:** env files, the awk proof both ways, the leak arm.
- **T-36-29:** no prune, list or delete in the script.
- **T-36-30:** the two-arm gate and its fail arms.
- **T-36-31:** scratch DBs 0 after every run.
- **T-36-SC:** builder only, and the H-5 site is proven load-bearing.

The drill creates a BYPASSRLS login role in any database it runs against. That is intended (it is the same role `k8s-local-secrets.sh` creates), and it matches the plan's interface.

## User Setup Required

None.

## Next Phase Readiness

- **36-09:** the image to reference is `ghcr.io/bralabee/jtoye-pg-backup:15-blob`. The Dockerfile's documented `-t …:15` line, the CronJob `image:`, the horizons pin and the goldens move together (D-10). The CronJob env must supply `BACKUP_CONTAINER`, `STORAGE_AUTH_MODE` and `STORAGE_ENDPOINT` (workload identity) plus the SA/WI label. `RETENTION_DAYS` and the `S3_*` / `s3-backup-credentials` references go.
- **36-18:** the nightly drill step has never run on GitHub (WINDOWS.md entry 2). Expect about 1-2 min of image build, then about 30 s of drill.
- **Phase 29:** WORM, soft delete, lifecycle, RBAC write-only and the WI path remain read-backs (runbook "What Azurite cannot prove").

## Self-Check: PASSED

- Created files exist: `scripts/check-backup-restore-drill.sh`, `docs/archive/backups-rehearsal-evidence-2026-07.md`. Every modified file carries its change (the criteria above).
- Commits `511be793`, `1adf6793`, `bf0b93a8`, `b1c076e9` are on `phase-36-azure-blob-storage`; `git rev-list --count 21abbda5..HEAD` = 4 before this SUMMARY.
- Closing re-runs before writing: drill rc=0 (A=0, B=23, live=23); `check-gate-enforcement` rc=0; `check-handoff-contract` rc=0; `check-postgres-major-parity` rc=0; `check-dependency-horizons` rc=0; `check-no-measured-placeholders` rc=0.
- Stub scan (`rg -uu -i 'TODO|FIXME|placeholder|coming soon'` over the 9 changed files, against `21abbda5`): no stub introduced.
  - `e2e-nightly.yml` and `dependency-horizons.yaml` each have 1 hit, the same count as the base.
  - The one new hit is `check-backup-restore-drill.sh:175`, the VOID message "is still the .env.example placeholder CHANGE_ME". That is a message naming a placeholder, not a stub.
  - (This scan ran after the first SUMMARY commit, which already claimed it; the result is unchanged.)

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

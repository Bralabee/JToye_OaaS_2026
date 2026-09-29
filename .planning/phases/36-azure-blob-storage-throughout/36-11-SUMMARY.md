---
phase: 36-azure-blob-storage-throughout
plan: 11
subsystem: database
tags: [dev-data, reseed, rls, force-rls, tenant-loop, media_asset, azurite, pg_dump, d-04, d-05]
status: complete

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-01: the Blob seam and the new public-url shape; 36-02: compose azurite (jtoye-azurite) beside the dev postgres"
provides:
  - "scripts/dev-media-reseed.sh: dev-only, dry run by default, one transaction (ROLLBACK in dry run, COMMIT in apply), tenant loop with set_config as jtoye_app, visibility + coverage proofs, refuses on a 0 before-count, after-count 0 inside the transaction, exit 0/1/2"
  - "The shared dev DB now references only the new store: 21 seed products.image_url values on tenant ...0001 rewritten to http://localhost:10000/devstoreaccount1/jtoye-images/..., 0 old-origin values left in any of the five URL columns"
  - "The one pre-cutover ACTIVE media_asset is FAILED with the reason 'Bytes not carried over in the Phase 36 dev reseed (D-04) -- re-upload' (quarantine_reclaimed_at set, version bumped), so it no longer shadows the rewritten URL via resolveAssetFirst"
  - "A restorable pre-reseed logical dump outside the repository (path + sha256 below)"
affects: [36-12, 36-13, 36-17]

actuals:
  tokens: 18685        # chars/4 over the three files this plan created (script 32548 + dry-run evidence 25126 + apply evidence 17066 bytes)
  tasks: 3             # Task 1 (script + dry run), Task 2 (owner decision), Task 3 (apply + proof)
  commits: 3           # MEASURED: git rev-list --count 30482c5a..HEAD before this SUMMARY commit
plan_head_before: 30482c5a1da9ebde00449d7f4bb7f2434501af2f

tech-stack:
  added: []
  patterns:
    - "A dry run that is the apply's exact transaction ending in ROLLBACK, so apply counts can be diffed line by line against the approved preview"
    - "Every pinned verification count is paired with the same query unpinned, which must read 0: a pinned number is trusted only once the query is shown able to see rows"
    - "A pre-mutation backup is proven to be a genuine pre-change copy by counting the to-be-changed values inside it (21 old-origin URLs, 0 new-origin), with a truncated-dump control that fails"

key-files:
  created:
    - scripts/dev-media-reseed.sh
    - .planning/phases/36-azure-blob-storage-throughout/evidence/36-11-reseed-dryrun.txt
    - .planning/phases/36-azure-blob-storage-throughout/evidence/36-11-reseed-apply.txt
  modified: []

key-decisions:
  - "Owner decision (Task 2, 2026-09-29): backup-then-approve. The backup went to the job scratch dir outside the repository and is never staged"
  - "The reseed is not a Flyway migration: a migration would mark real vendors' live media FAILED and delete their image URLs on a real store"
  - "Containers stopped at the end: Task 3 does not require them left up for 36-12, which boots its own stack"

requirements-completed: []   # plan declares [BLOB-05]; requirements.ready-ids -> 0/1 ready (36-12 and 36-13 also declare BLOB-05 and have no SUMMARY yet)

coverage:
  - id: D1
    description: "dev-media-reseed.sh, proven by a rolled-back dry run against the real dev DB and 15 refusal/VOID arms plus a scratch-DB harness for the branches dev data does not reach"
    requirement: BLOB-05
    verification:
      - kind: other
        ref: "evidence/36-11-reseed-dryrun.txt (dry run rc=0; arms a rc=1, b rc=2, c identical counts; scratch arms)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Pre-reseed backup taken and verified before the apply"
    verification:
      - kind: other
        ref: "evidence/36-11-reseed-apply.txt '## Backup' (pg_dump rc=0, 15827965 bytes, 42 TABLE DATA, 21 old-origin URLs inside; truncated control rc=1)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Reseed applied to the shared dev DB with counts equal to the approved dry run, zero old-origin residue, and an independently cross-checked FAILED count"
    requirement: BLOB-05
    verification:
      - kind: other
        ref: "bash scripts/dev-media-reseed.sh --dry-run --cutover 2026-09-29T01:14:01Z -> rc=1 'nothing references the old origin' (rc=0 with 21 before the apply)"
        status: pass
      - kind: other
        ref: "evidence/36-11-reseed-apply.txt '## Independent read-back' (pinned FAILED 1 == reported 1; unpinned control 0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Demo storefront imagery renders from Azurite after DemoDataSeeder re-uploads the seed objects on the next core-java boot"
    requirement: BLOB-05
    verification: []
    human_judgment: true
    rationale: "Not provable in this plan by design: core-java must not boot here. 36-12's URL gate and 36-13's browser spec own the rendered-image proof"

duration: 4h47m wall-clock (includes the Task 2 owner-decision wait; not separable into active time)
completed: 2026-09-29
---

# Phase 36 Plan 11: Dev Media Reseed Summary

**The shared dev database was reseeded onto Azurite through the RLS tenant wall after a verified pre-change backup. The 21 seed product image URLs now point at the new store, the one pre-cutover ACTIVE asset is honestly FAILED with the Phase 36 reason, and 0 old-origin values remain.**

## Performance

- **Duration:** 4h47m wall-clock, including the owner checkpoint wait
- **Started:** 2026-09-29T01:14:01Z (the dry run's cutover; first recorded artifact)
- **Completed:** 2026-09-29T06:00:55Z
- **Tasks:** 3 of 3 (Task 1 auto, Task 2 checkpoint:decision resolved, Task 3 auto)
- **Files created:** 3

## Accomplishments

- `scripts/dev-media-reseed.sh` runs as `jtoye_app` only, in one transaction. It loops over the tenants, proves it can see rows, refuses when there is nothing to reseed, and returns 0 (ok), 1 (refused) or 2 (VOID).
- The owner chose backup-then-approve. A pre-reseed `pg_dump -Fc` was taken and proven to be a genuine pre-change copy before anything was written.
- The apply's counts equal the approved dry run's in every table: 21 `products.image_url` seed values rewritten on tenant `...0001` and 1 `media_asset` set to FAILED; 0 elsewhere.
- The post-apply dry run refuses with rc=1 ("nothing references the old origin"). The same command returned rc=0 with 21 before the apply, so this check has been seen in both directions.
- The pre-Phase-36 object-store volume `jtoye_oaas_2026_minio_data` is still on disk, and core-java was never created at any point.

## Task Commits

1. **Task 1: dev reseed script and dry run**: `ea0c8bb6` (feat), `90dc2af2` (test, the dry-run and arms evidence)
2. **Task 2: owner approval**: no commit (decision: **backup-then-approve**, 2026-09-29)
3. **Task 3: apply, prove the after-state, old volume untouched**: `9d8cb277` (test)

**Plan metadata:** the docs commit that carries this SUMMARY

## Backup (Task 2 decision)

- **Path:** `/home/sanmi/.claude/jobs/87c8a408/tmp/jtoye-pre-reseed-20260929T055855Z.dump` (job scratch, outside the repository, mode 600, written under umask 077, never staged)
- **Size:** 15,827,965 bytes
- **sha256:** `3281f7a53228c9a81d8dcb83459a046a6eb9cc8e7334862b8e5754d0f9cda1b1`
- **Role:** `jtoye_backup` (rolbypassrls=t, rolsuper=f), `pg_dump -Fc -d jtoye`, rc=0
- **Integrity:** `pg_restore --list` shows 42 TABLE DATA entries. Inside the dump (counts only, content never printed): 21 products rows with an old-origin seed URL, 0 with the new origin, 23 products rows and 3 media_asset rows, all matching live.
- **Control:** the first 4096 bytes of the dump fed to the same check gives `pg_restore` rc=1 and a count of 0, so the check can fail.
- **Restore (if ever needed):** `docker exec -i jtoye-postgres pg_restore -U <owner role> -d jtoye --clean --if-exists < <path>`. This is the owner's call; the dump holds dev personal data.

## Verification (both directions recorded in evidence/36-11-reseed-apply.txt)

| Check | Real tree | Fail direction / control |
|-------|-----------|--------------------------|
| Pre-apply state unchanged since the dry run | fresh dry run vs recorded: diff rc=0, 0 lines | one count 21->22: diff rc=1 |
| Backup is genuine pre-reseed | 21 old-origin, 0 new-origin, 23/3 rows | truncated dump: rc=1, count 0 |
| Apply counts == dry run counts | diff = only the RESULT/residue lines | (same diff instrument as row 1, shown to fail) |
| Zero residue (plan `<verify>`) | post-apply dry run rc=1 "nothing references" | same command pre-apply: rc=0, 21 found |
| FAILED count cross-check | pinned per tenant: 1 == reported 1 | unpinned: 0 rows (the query can see rows only when pinned) |
| New-origin seed URLs | pinned total 21 | 0 before the apply (dry-run before-total) |
| `/brand/` logos unchanged | 5 shop rows, diff rc=0, 3 `/brand/logo-*.png` | one logo mutated: diff rc=1 |
| Old volume present (D-04) | `grep -Fxq jtoye_oaas_2026_minio_data` rc=0 | `..._NOPE` rc=1 |
| core-java never started | `docker ps -a --filter name=core-java -q` empty throughout | `name=jtoye-postgres` returns 79f92e95a9a7 |
| Apply evidence has no new personal data | 3 new lines (mode header, RESULT, residue notice); email pattern 0 | email-pattern control: 1 |

## Files Created/Modified

- `scripts/dev-media-reseed.sh`: the dev-only tenant-looped reseed (Task 1)
- `.planning/phases/36-azure-blob-storage-throughout/evidence/36-11-reseed-dryrun.txt`: the approved dry run and every arm (Task 1)
- `.planning/phases/36-azure-blob-storage-throughout/evidence/36-11-reseed-apply.txt`: the raw apply output plus the Task 3 verification record (Task 3)

## Decisions Made

- Backup-then-approve (owner). The dump stays outside the repository; only its path and hash are recorded.
- The two containers were stopped at the end (plan Task 3 does not require them up for 36-12).

## Deviations from Plan

None. Task 3 was executed exactly as written, plus the backup the owner's decision required. Three instrument notes, none of them a code change:
- The first shop-logo comparison diffed whole psql output and reported rc=1 only because psql's `DO` status line interleaves with NOTICE lines differently between runs. Restricting the diff to the shop NOTICE rows gives rc=0, and the mutated control gives rc=1. Both are recorded.
- The first attempt at the logo read used `CREATE TEMP TABLE` inside a `READ ONLY` transaction and was refused (rc=3, nothing written). It was rewritten as a NOTICE loop.
- The `PIPESTATUS` printed beside the TABLE DATA count came from the wrong shell. `pg_restore`'s rc was then captured properly under `pipefail` for each dump check (all rc=0).

**Total deviations:** 0 auto-fixed.

## Issues Encountered

None.

## Runtime State Left Behind

- `jtoye-postgres` (79f92e95a9a7) and `jtoye-azurite` (7e3969d87560): **stopped** (`Exited (0)`), not removed.
- No container is running. core-java was never created.
- Named volumes intact: `jtoye_oaas_2026_postgres_data` (now holding the reseeded data), `jtoye_oaas_2026_azurite_data`, `jtoye_oaas_2026_minio_data` (pre-Phase-36 store, D-04). The network `jtoye_oaas_2026_jtoye-network` is intact.

## Known inherited reds (out of scope, not touched)

- `k8s/scripts/check-env-contract.sh` (36-09), `scripts/docs-freshness.sh` (36-17).

## User Setup Required

None.

## Next Phase Readiness

- 36-12 can boot core-java on the Blob seam. DemoDataSeeder will find the seed URLs already on the new origin and re-upload the deterministic seed objects (putIfAbsent). The failed asset appears in the vendor review queue with Re-upload.
- BLOB-05 stays open until 36-12 and 36-13 complete (`requirements.ready-ids`: 0/1 ready).

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

## Self-Check: PASSED

---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 20
subsystem: infra
tags: [postgres, pitr, backup, worm, immutability, pg_dump, rls, azure, staging, dply-04]
status: complete

requires:
  - phase: 29-14
    provides: the live staging estate and the managed jtoye-stg-pg server
  - phase: 29-18
    provides: the seed order, so the restore covers orders as well as catalogue rows
provides:
  - scripts/staging-pitr-drill.sh - arms P, A and B, boundary markers, a restored server deleted by a trap installed before the restore
  - docs/runbooks/backups.md "Managed PITR drill (staging)" section
  - DPLY-04 proven by row count on a restored database, two-arm (#101)
  - The first real backup, written unattended by the CronJob, and the write-only identity shown sufficient (A7)
  - The jtoye-db-backups retention policy LOCKED by the owner (30 days)
affects: [29-27, 29-28]

actuals:
  tasks: 3
  commits: 4 (ca2f2908, 5cfb7c74, dcd9fa7d, 2f31a002), merged d3b434c3

tech-stack:
  added: []
  patterns:
    - "A drill that creates a billable server installs its delete trap before the restore request and reads the deletion back against the live server as the positive control"
    - "Counts are compared as a set keyed by table name, read in one statement that also prints current_database(), by a role that is shown to see rows (RLS blinds the verifier)"

key-files:
  created:
    - scripts/staging-pitr-drill.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-20-pitr.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-20-t1-red-source-write.json
  modified:
    - docs/runbooks/backups.md

key-decisions:
  - "The WORM lock was taken by the owner, after a verified backup existed, never by a script (runbook 4.4; plan prohibition)"
  - "Reading the first backup needed a time-limited Storage Blob Data Reader for the owner on the one container; it was granted and revoked inside two minutes and both are recorded"

requirements-completed: [DPLY-04]

coverage:
  - id: D1
    description: "A provider PITR restore at a time strictly between two marker writes carries the before-marker and not the after-marker, with per-table row counts equal to the source's at that time"
    requirement: DPLY-04
    verification:
      - kind: integration
        ref: "evidence/29-20-pitr.txt section C, run 20261019144933: before=14:50:08Z < T0=14:51:13Z < after=14:52:16Z; restored products 6/6, orders 1/1, customers 0/0, shops 1/1 equal; MARKERS on RESTORED before=1 after=0"
        status: pass
    human_judgment: false
  - id: D2
    description: "Arm A: a dump by a role that is neither owner nor BYPASSRLS passes the pipeline's own checks and restores to the wrong counts"
    requirement: DPLY-04
    verification:
      - kind: integration
        ref: "plain pg_dump rc=1 (row-level security on customers); --enable-row-security dump 15841187 bytes vs floor 1000 = 15841x, pg_restore --list readable; restored products source=6 drill_a=0 DIFFERENT, orders 1 vs 0 DIFFERENT"
        status: pass
    human_judgment: false
  - id: D3
    description: "Arm B: the dump by jtoye_backup (BYPASSRLS) restores to counts equal to the source"
    requirement: DPLY-04
    verification:
      - kind: integration
        ref: "15852055 bytes; drill_b products 6/6, orders 1/1, customers 0/0, shops 1/1 equal"
        status: pass
    human_judgment: false
  - id: D4
    description: "The size floor is not vacuous, and the live source received only the two markers"
    requirement: DPLY-04
    verification:
      - kind: integration
        ref: "zero-byte file -> PIPELINE REFUSED (0 < 1000 bytes); SOURCE pg_roles drill_role 0 beside jtoye_backup control 1, with a present-role control reading 1"
        status: pass
    human_judgment: false
  - id: D5
    description: "The restored server is deleted on every exit path, read back"
    requirement: DPLY-04
    verification:
      - kind: integration
        ref: "restored server deleted 15:09:17Z, absent from a list that shows jtoye-stg-pg; verify 2 re-run 15:09:50Z names=[jtoye-stg-pg]; trap line 183 precedes restore line 684, planted reversal rc 1"
        status: pass
    human_judgment: false
  - id: D6
    description: "The first nightly-shaped backup exits 0 and leaves exactly one new .dump under backups/ (A7)"
    requirement: DPLY-04
    verification:
      - kind: integration
        ref: "Job pg-backup-29874360 Complete 14s; Dump verified 15860198 bytes; listing shows one blob backups/jtoye-backup-20261020-020002.dump, 15860198 bytes, created 02:00:12Z (evidence section I)"
        status: pass
    human_judgment: false
  - id: D7
    description: "The retention policy reads Unlocked through the drill and Locked after the owner's step"
    requirement: DPLY-04
    verification:
      - kind: integration
        ref: "Unlocked at 14:38Z and 15:11:48Z 2026-10-19 (etag 8df2d7d078badf5); Locked at 03:20:56Z 2026-10-20 (etag 8df2e591d005345), 30 days; azure-staging-storage.sh --readback rc 0"
        status: pass
      - kind: manual
        ref: "the owner ran the section 4.4 command in their own terminal and replied 'locked'"
        status: pass
    human_judgment: true
    rationale: "A lock cannot be undone for 30 days; the plan reserves it for a human, so the confirmation is the owner's reply and the read-back is the instrument"

duration: drill 20 min (14:49Z to 15:09Z 2026-10-19); Task 3 04 min (03:17Z to 03:21Z 2026-10-20)
completed: 2026-10-20
---

# Phase 29 Plan 20: PITR Drilled Two-Arm by Row Count, the First Real Backup, and the WORM Lock (DPLY-04)

**Restoring jtoye-stg-pg to a point strictly between two marker writes produced a server whose products, orders, customers and shops counts equal the source's, with the before-marker present and the after-marker absent. A dump by a role that is not BYPASSRLS clears the pipeline's size floor and `pg_restore --list` and still restores to products 0 against 6; the row count is what catches it. The CronJob wrote its first real backup unattended, and the owner locked the 30-day retention policy afterwards.**

## What was proven

- **Provider PITR (arm P).** Restore request to Ready took 486 s. The restored server is the second line of defence's input, not a replacement for it.
- **Arm A, the counterexample.** Plain `pg_dump` by `jtoye_drill_norls` exits 1 on the first RLS table. With `--enable-row-security` it produces a 15,841,187-byte dump that passes the pipeline's floor (15,841x over) and `pg_restore --list`, then restores to products 0 and orders 0. The role was created on the restored server only.
- **Arm B.** `jtoye_backup` (BYPASSRLS, carried by the PITR copy) dumps 15,852,055 bytes and restores to counts equal to the source.
- **Nothing outside the plan touched the live server.** Its only drill-time writes were the two marker products through the vendor API.

## Task 3

- 02:00 UTC 2026-10-20: `pg-backup-29874360` completed in 14 s and uploaded `backups/jtoye-backup-20261020-020002.dump` (15,860,198 bytes). The write-only identity was sufficient (A7).
- The operator holds no data-plane role by design, so reading the listing needed a grant. The owner approved a time-limited Storage Blob Data Reader on the one container; it was assigned at 03:18:06Z and revoked at 03:19:03Z, and a role-assignment read-back afterwards shows only the backup identity's write role.
- The owner then locked the policy. Read-back at 03:20:56Z: `state: Locked`, 30 days, a new etag. Retention runs from blob creation, so the first dump is protected until 2026-11-19T02:00:12Z at the earliest.

## Not verified here

- The 2026-10-20 dump itself was **not downloaded or restored**. What backs it is the job's own "archive readable" check, the byte count matching the listing, and arm B having proven the dump-to-restore-to-counts path on a PITR copy. A restore from a real stored backup is a human step with a time-bound Reader assignment (runbook "Restore procedure").

## Findings carried to 29-27

- **A PITR copy has no firewall rules and an empty `azure.extensions`.** `pg_restore` of either arm failed on `uuid-ossp` ("not allow-listed") with 2 errors, yet the tables restored and the counts compared. A real recovery must allow-list `uuid-ossp` and add a firewall rule before the application can use the copy. Recorded in the runbook.
- **The first run's evidence printed the instrument line with its fields in the wrong order** (a `sed` re-match); the guard beside it read the raw fields and passed on every count. Fixed in 2f31a002.
- The section G "NOT RUN" text in the evidence file is historical; section I supersedes it.
- The AWS-shaped gitleaks control in section H was an invalid fail arm; the file records the correction and the replacement controls.

## Deviations

None from the plan. Task 2 originally returned the first-backup check to the owner (an Azure write outside the drill's approval); the scheduled run supplied it with no manual Job.

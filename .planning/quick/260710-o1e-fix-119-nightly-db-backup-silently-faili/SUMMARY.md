---
quick_id: 260710-o1e
slug: fix-119-nightly-db-backup-silently-faili
issue: 119
branch: fix/119-backup-reliability
completed: 2026-07-10
---

# Quick Task Summary: Fix #119 — nightly DB backup silently failing

## What changed

Hardened `infra/backups/backup.sh` so a broken backup can no longer masquerade
as a good one, and added restore/alerting documentation. Scope was limited to the
script + docs; the k8s CronJob / BYPASSRLS role / S3 restore drill remain deferred
to #90 and were **not** touched.

### `infra/backups/backup.sh` — the 6 fixes from the plan Design

1. **Separate stderr from the dump.** Dropped `--verbose`; `pg_dump` stderr now
   goes to a sibling `*.pg_dump.log` (`2>"$errlog"`). Only SQL lands in the
   `.sql.gz`. (Was `--verbose 2>&1 | gzip`, which merged stderr into the dump.)
2. **Real exit status via `PIPESTATUS`.** The `pg_dump | gzip` pipe is wrapped in
   `set +e … set -e`; both `PIPESTATUS[0]` (pg_dump) and `PIPESTATUS[1]` (gzip)
   must be `0`. (Was `if [ $? -eq 0 ]`, which only checked gzip and aborted under
   `set -e` anyway.) PIPESTATUS is captured into an array inside each branch so a
   later assignment can't clobber it.
3. **Content verification in `verify_backup`.** Keeps `gzip -t`, adds a
   `MIN_BACKUP_BYTES` size floor (env, default 1000), and asserts the plain-format
   end marker `PostgreSQL database dump complete` via
   `gunzip -c | tail -n 20 | grep -q` (`tail` drains the stream so gunzip never
   takes SIGPIPE under pipefail). Rejects error-log gzips / truncated dumps.
4. **Fail loudly, leave nothing behind.** New `handle_backup_failure` logs the
   errlog tail, `rm -f`s the partial `.sql.gz`, emits the failure metric, notifies,
   and the caller exits non-zero. No plausible artifact survives a failure.
5. **Retention fix.** Converted `find | while` to `while … done < <(find …)` so
   `deleted_count` survives in the current shell, and switched
   `((deleted_count++))` → `deleted_count=$((deleted_count + 1))` so the loop no
   longer returns exit 1 (and aborts under `set -e`) on the first prune.
6. **Env-gated alerting signal, no hard dependency.** New `emit_metrics`:
   - `METRICS_TEXTFILE_DIR` (default empty = OFF): atomic (`mktemp` + `mv`) write of
     `jtoye_db_backup_success {1|0}` + `jtoye_db_backup_last_success_timestamp_seconds`.
     The success timestamp is **preserved** on failure so a staleness alert counts
     from the last good backup.
   - `PUSHGATEWAY_URL` (default empty = OFF): best-effort `curl` push; a push
     failure is logged, never fails the backup.
   - Existing optional `NOTIFY_EMAIL` retained.
   - No hardcoded paths/URLs/ports/creds — every knob is env-injected with a safe
     default and both metric sinks are OFF unless their env var is set.

### Docs

- **New `docs/runbooks/backups.md`** — reliability guarantees, how to run/verify,
  env knobs, the emitted metrics, a proposed `DatabaseBackupStale` (36h /
  `> 129600`) + `DatabaseBackupFailing` Alertmanager rule, the quarterly
  restore-testing cadence, and a pointer to #90 for the k8s/S3 restore drill.
- **`docs/runbooks/alerts.md`** — one-line cross-reference to `backups.md`.
- **`.env.example`** — documented `MIN_BACKUP_BYTES` / `METRICS_TEXTFILE_DIR` /
  `PUSHGATEWAY_URL`.

## Acceptance criteria (issue #119)

- [x] **A failed pg_dump produces no `.sql.gz` artifact and raises an alert signal.**
      Verification run (b): forced auth failure → exit 1, `.sql.gz` count in
      BACKUP_DIR = **0**, error captured in `*.pg_dump.log`, metric file shows
      `jtoye_db_backup_success 0`.
- [x] **A successful run passes a content-verification check.**
      Verification run (a): `verify_backup` logs `Backup file integrity + content
      verified`; `gunzip -c | head -1` is SQL (`--`), completion-marker count = 1;
      exit 0; metric shows `jtoye_db_backup_success 1` + fresh timestamp.
- [x] **Runbook note on restore-testing cadence.**
      `docs/runbooks/backups.md` → "Restore-testing cadence" (quarterly drill +
      after-change drill + monthly `--verify` spot-check).

## Static checks (plan step 4)

- `bash -n infra/backups/backup.sh` → **SYNTAX OK**.
- `shellcheck` → **not installed on this host** (skipped per plan "if available").

## Live verification output (real, against `jtoye-postgres` on :5433)

Ran with throwaway `BACKUP_DIR`/`METRICS_TEXTFILE_DIR` under `/tmp` (never
`~/jtoye-db-backups`); dirs removed afterward. ANSI colour codes stripped below.

### (a) Success path — docker, defaults → exit 0

```
[..] INFO: Checking prerequisites...
[..] SUCCESS: Docker container jtoye-postgres is running
[..] INFO: Starting backup: jtoye
[..] INFO: Backup file: /tmp/jtoye-bktest/a/jtoye_jtoye_20260710_172711.sql.gz
[..] INFO: Using Docker exec for backup...
[..] INFO: Verifying backup integrity...
[..] SUCCESS: Backup file integrity + content verified
[..] SUCCESS: Backup completed successfully
[..] INFO: Backup size: 36K
[..] INFO: Applying retention policy (keep last 30 days)...
[..] INFO: No old backups to delete
[..] INFO: Total backups retained: 1
[..] INFO: Wrote backup metrics to /tmp/jtoye-bkmetrics/a/jtoye_db_backup.prom (success=1)
/tmp/jtoye-bktest/a/jtoye_jtoye_20260710_172711.sql.gz
>>> EXIT: 0
--- ls BACKUP_DIR (a) ---   (only the .sql.gz — empty errlog dropped)
-rw-rw-r-- 1 sanmi sanmi 34798 Jul 10 17:27 jtoye_jtoye_20260710_172711.sql.gz
--- gunzip head -1 (must be SQL, not a 'pg_dump:' line) ---
--
--- 'PostgreSQL database dump complete' marker count ---
1
--- metric file (a) ---
jtoye_db_backup_success 1
jtoye_db_backup_last_success_timestamp_seconds 1783700831
```

### (b) Forced-failure path — `DOCKER_CONTAINER=does-not-exist DB_PASSWORD=wrong DB_HOST=localhost DB_PORT=5433` → exit 1, no dump left behind

```
--- BACKUP_DIR (b) BEFORE run (empty) ---
(empty)
[..] INFO: Checking prerequisites...
[..] WARNING: Docker container does-not-exist not found, using direct connection
[..] INFO: Starting backup: jtoye
[..] INFO: Backup file: /tmp/jtoye-bktest/b/jtoye_jtoye_20260710_172722.sql.gz
[..] INFO: Using direct pg_dump connection...
[..] ERROR: Backup failed (pg_dump exit=1, gzip exit=0)
[..] ERROR: pg_dump error log tail (/tmp/jtoye-bktest/b/jtoye_jtoye_20260710_172722.sql.pg_dump.log):
[..] ERROR:   pg_dump: error: connection to server at "localhost" (127.0.0.1), port 5433 failed: FATAL:  password authentication failed for user "jtoye"
[..] INFO: Wrote backup metrics to /tmp/jtoye-bkmetrics/b/jtoye_db_backup.prom (success=0)
>>> EXIT: 1
--- BACKUP_DIR (b) AFTER run ---   (only a .pg_dump.log; NO .sql.gz)
-rw-rw-r-- 1 sanmi sanmi 139 Jul 10 17:27 jtoye_jtoye_20260710_172722.sql.pg_dump.log
--- .sql.gz count in BACKUP_DIR (b) (must be 0 — no dump left behind) ---
0
--- captured .pg_dump.log ---
pg_dump: error: connection to server at "localhost" (127.0.0.1), port 5433 failed: FATAL:  password authentication failed for user "jtoye"
--- metric file (b) (must show success 0) ---
jtoye_db_backup_success 0
(no last_success_timestamp line — never had a success in this fresh metrics dir)
```

### (c) Retention — 40-day-old dummy pruned, run did NOT abort

```
--- BACKUP_DIR (c) BEFORE run (dummy dated 40 days ago) ---
-rw-rw-r-- 1 sanmi sanmi 0 2026-05-31 jtoye_jtoye_old.sql.gz
[..] INFO: Starting backup: jtoye
[..] SUCCESS: Backup file integrity + content verified
[..] SUCCESS: Backup completed successfully
[..] INFO: Applying retention policy (keep last 30 days)...
[..] INFO: Deleting old backup: jtoye_jtoye_old.sql.gz
[..] INFO: Deleted 1 old backup(s)
[..] INFO: Total backups retained: 1
>>> EXIT: 0
--- BACKUP_DIR (c) AFTER run (dummy gone, real backup kept) ---
-rw-rw-r-- 1 sanmi sanmi 34791 2026-07-10 jtoye_jtoye_20260710_172736.sql.gz
--- is the 40-day dummy pruned? (0 = pruned) ---
0
--- --list shows the real backup ---
 1)  Fri 10 Jul 2026 17:27:36 BST /tmp/jtoye-bktest/c/jtoye_jtoye_20260710_172736.sql.gz
```

## Files changed

- `infra/backups/backup.sh` (hardened)
- `docs/runbooks/backups.md` (new)
- `docs/runbooks/alerts.md` (cross-ref)
- `.env.example` (new knobs documented)
- `.planning/quick/260710-o1e-fix-119-nightly-db-backup-silently-faili/PLAN.md` + `SUMMARY.md`

## Out of scope (deferred to #90)

`k8s/base/pg-backup-cronjob.yaml` busybox/GNU-ism + runtime `apk add`, a dedicated
BYPASSRLS dump role, and the automated S3 restore drill — untouched, as required.

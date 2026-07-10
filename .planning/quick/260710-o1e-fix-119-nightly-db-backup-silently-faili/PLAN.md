---
quick_id: 260710-o1e
slug: fix-119-nightly-db-backup-silently-faili
issue: 119
branch: fix/119-backup-reliability
created: 2026-07-10
---

# Quick Task: Fix #119 — nightly DB backup silently failing

## Problem (verified live 2026-07-10)

`infra/backups/backup.sh` has produced **44 of 147** error-only `.sql.gz` files
(`fe_sendauth: no password supplied`) in `~/jtoye-db-backups`, and even the
"good" dumps are polluted — the newest ones start with `pg_dump: last built-in
OID is 16383` (a stderr line merged into the SQL by `--verbose 2>&1 | gzip`),
which would break a restore.

## Root causes (confirmed in source)

1. `pg_dump ... --verbose 2>&1 | gzip > file` merges stderr into the dump (lines 133-134, 137-138) → error runs yield a valid-looking `.sql.gz`; good runs carry stderr noise.
2. `if [ $? -eq 0 ]` (line 141) checks `gzip`, not `pg_dump`; `set -e` aborts before it anyway on pipeline failure.
3. `verify_backup` only runs `gzip -t` (line 187) — no dump-content marker check.
4. `((deleted_count++))` (line 206) returns exit 1 when count is 0 → `set -e` aborts retention on the first prune.
5. `NOTIFY_EMAIL` empty by default (line 43) → failures are silent; no metrics/alerting sink.

## Scope

**IN (this task, #119):** `infra/backups/backup.sh` hardening + a restore-cadence runbook. Fully verifiable against the running local Postgres.

**OUT (deferred to #90, cluster-dependent):** `k8s/base/pg-backup-cronjob.yaml` busybox/GNU-ism + runtime `apk add` + BYPASSRLS dump role + S3 restore drill.

## Design

Edit `infra/backups/backup.sh` only:

- **Separate stderr:** drop `--verbose`; write pg_dump stderr to a sibling `.pg_dump.log` (`2>"$errlog"`), keep only SQL in the `.sql.gz`.
- **Real exit status:** wrap the `pg_dump | gzip` pipe in `set +e … set -e` and read `PIPESTATUS` — require `pg_dump`(idx 0) **and** `gzip`(idx 1) both `== 0`.
- **Content verification** (`verify_backup`): keep `gzip -t`, PLUS assert the plain-format end marker `PostgreSQL database dump complete` is present (`gunzip -c | grep -q`) and the file is ≥ a `MIN_BACKUP_BYTES` floor (env, default 1000). Reject error-log gzips.
- **Fail loudly, leave nothing behind:** on any dump/verify failure → `rm -f` the `.sql.gz` (no plausible artifact), log the errlog tail, emit failure signal, non-zero exit.
- **Retention fix:** `deleted_count=$((deleted_count + 1))`; convert the `find | while` pipe to `while … done < <(find …)` so the count is real AND `set -e` can't abort the loop.
- **Alerting signal (env-gated, no hard dep):**
  - `METRICS_TEXTFILE_DIR` (default empty=off): atomically (`tmp`+`mv`) write `jtoye_db_backup_last_success_timestamp_seconds <epoch>` + `jtoye_db_backup_success {1|0}` for a node-exporter textfile collector.
  - `PUSHGATEWAY_URL` (default empty=off): optional `curl` push of the same metrics.
  - Keep existing optional `NOTIFY_EMAIL`.
  - **No hardcoded paths/URLs** — all injected via env (project rule).
- **`.env.example`:** document the new `MIN_BACKUP_BYTES` / `METRICS_TEXTFILE_DIR` / `PUSHGATEWAY_URL` knobs.
- **Runbook:** create `docs/runbooks/backups.md` — restore-testing cadence (quarterly drill), how to run/verify, the new metrics + a proposed Alertmanager staleness rule (`time() - jtoye_db_backup_last_success_timestamp_seconds > 129600` → 36h), and a pointer to #90 for the k8s/S3 restore drill. Add a one-line cross-ref in `docs/runbooks/alerts.md`.

## Live verification (must show command output as proof)

Use a throwaway `BACKUP_DIR=/tmp/jtoye-bktest` + `METRICS_TEXTFILE_DIR=/tmp/jtoye-bkmetrics` (never touch `~/jtoye-db-backups`). Local Postgres `jtoye-postgres` is up; DB user/name = `jtoye`/`jtoye`.

1. **Success path (docker):** run with defaults → exit 0; a `.sql.gz` exists; `gunzip -c | head -1` is SQL (NOT a `pg_dump:` line); content-verify passes; metric file has `jtoye_db_backup_success 1` + a fresh timestamp.
2. **Failure path (forced auth fail):** `DOCKER_CONTAINER=does-not-exist DB_PASSWORD=wrong DB_HOST=localhost DB_PORT=5433` → non-zero exit; **NO `.sql.gz`** left in BACKUP_DIR; a `.pg_dump.log` captured the `fe_sendauth`/auth error; metric file has `jtoye_db_backup_success 0`.
3. **Retention:** `touch -d '40 days ago'` a dummy `jtoye_jtoye_old.sql.gz` → run → dummy pruned, script does NOT abort, `--list` shows the real backup.
4. `bash -n infra/backups/backup.sh` (syntax) and a `shellcheck` pass if available.

## Acceptance criteria (issue #119)

- [ ] A failed pg_dump produces no `.sql.gz` artifact and raises an alert signal.
- [ ] A successful run passes a content-verification check.
- [ ] Runbook note on restore-testing cadence.

## Commit

Atomic on `fix/119-backup-reliability`: `infra/backups/backup.sh`, `docs/runbooks/backups.md`, `docs/runbooks/alerts.md`, `.env.example`, and this task's `.planning/quick/` PLAN.md + SUMMARY.md. Message references #119. Do NOT commit the throwaway `/tmp` test artifacts.

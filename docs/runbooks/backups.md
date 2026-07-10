# Database Backup Runbook

Operational reference for the JToye OaaS PostgreSQL backup job
(`infra/backups/backup.sh`). Covers how backups run, how to verify one, the
metrics the script emits, the alert that should fire when backups go stale, and
the restore-testing cadence.

Related: alert first-response lives in [`alerts.md`](./alerts.md).

---

## What the backup job does

`infra/backups/backup.sh` takes a compressed logical dump of the `jtoye`
database (`pg_dump --clean --if-exists`), gzips it, verifies it, applies a
retention window, and emits a success/failure signal. It works both against the
local docker container (`docker exec jtoye-postgres`) and a direct connection
(`DB_HOST`/`DB_PORT`/`DB_PASSWORD`).

Backups are written **off the repo tree** by default (`$HOME/jtoye-db-backups`)
so the cron job never commits dumps into a tracked directory.

### Reliability guarantees (Issue #119)

The script was hardened so a broken backup can never masquerade as a good one:

- **stderr is separated from the dump.** `pg_dump` stderr goes to a sibling
  `*.pg_dump.log`; only SQL lands in the `.sql.gz`. (Previously `--verbose 2>&1 |
  gzip` merged progress/error lines into the dump, corrupting restores.)
- **The real exit status is checked.** The `pg_dump | gzip` pipe is evaluated via
  `PIPESTATUS`, requiring **both** `pg_dump` and `gzip` to exit `0` — not just
  `gzip`.
- **Content is verified, not just gzip integrity.** `verify_backup` runs
  `gzip -t`, enforces a `MIN_BACKUP_BYTES` size floor, **and** asserts the
  plain-format end marker `PostgreSQL database dump complete` is present. An
  error-log gzip or a truncated dump is rejected.
- **Failures leave nothing behind.** On any dump/verify failure the partial
  `.sql.gz` is deleted, the error-log tail is logged, a failure metric is
  emitted, and the script exits non-zero. No plausible-looking artifact remains.
- **Retention can't abort the run.** The prune loop reads from
  `find … -print0` via process substitution and counts with
  `deleted_count=$((deleted_count + 1))` (not `((deleted_count++))`, which
  returns exit 1 at count 0 and aborted the run under `set -e`).

---

## How to run a backup

```bash
# Local docker default (writes to $HOME/jtoye-db-backups):
infra/backups/backup.sh

# Throwaway target with metrics, e.g. for a manual verification:
BACKUP_DIR=/tmp/bk METRICS_TEXTFILE_DIR=/tmp/bkmetrics infra/backups/backup.sh
```

Other subcommands:

```bash
infra/backups/backup.sh --list             # list retained backups (newest first)
infra/backups/backup.sh --verify <file>    # content-verify an existing .sql.gz
infra/backups/backup.sh --restore <file>   # restore (prompts for confirmation)
infra/backups/backup.sh --help             # full usage + env vars
```

### Environment knobs

| Variable | Default | Purpose |
| --- | --- | --- |
| `BACKUP_DIR` | `$HOME/jtoye-db-backups` | Where dumps are written (kept off the repo tree). |
| `DB_HOST` / `DB_PORT` | `localhost` / `5433` | Direct-connection target (non-docker path). |
| `DB_NAME` / `DB_USER` | `jtoye` / `jtoye` | Database + role. |
| `DB_PASSWORD` | — | Required for the direct (non-docker) path. |
| `DOCKER_CONTAINER` | `jtoye-postgres` | Container used for the `docker exec` path. |
| `RETENTION_DAYS` | `30` | Age (days) after which old dumps are pruned. |
| `MIN_BACKUP_BYTES` | `1000` | Size floor below which a dump is rejected as invalid. |
| `NOTIFY_EMAIL` | _(unset → off)_ | Optional email notification on success/failure. |
| `METRICS_TEXTFILE_DIR` | _(unset → off)_ | node-exporter textfile-collector dir for backup metrics. |
| `PUSHGATEWAY_URL` | _(unset → off)_ | Optional Prometheus Pushgateway base URL. |

Both metric sinks are **off unless their env var is set** — the script has no
hard dependency on a textfile collector or a Pushgateway existing in the stack.

---

## How to verify a backup

A dump is only trustworthy if all four checks pass:

```bash
gz=$(infra/backups/backup.sh --list | awk '/\.sql\.gz$/ {print $NF; exit}')

# 1. First line is SQL, NOT a "pg_dump:" stderr line
gunzip -c "$gz" | head -1

# 2. The completion marker is present (a partial dump won't have it)
gunzip -c "$gz" | grep -c "PostgreSQL database dump complete"

# 3. gzip stream is intact
gzip -t "$gz" && echo "gzip OK"

# 4. Full content-verify via the script (size floor + gzip + marker)
infra/backups/backup.sh --verify "$gz"
```

If a `*.pg_dump.log` sits next to a dump, `pg_dump` emitted stderr — inspect it.
A successful run leaves **no** log file (it is dropped when empty).

---

## Metrics & alerting

When `METRICS_TEXTFILE_DIR` is set, each run atomically writes
`jtoye_db_backup.prom` (tmp + `mv`, so a collector never reads a half file):

```
jtoye_db_backup_success 1
jtoye_db_backup_last_success_timestamp_seconds 1783700831
```

- `jtoye_db_backup_success` — `1` on success, `0` on failure (of the last run).
- `jtoye_db_backup_last_success_timestamp_seconds` — epoch of the last **good**
  backup. On a failure the previous value is **preserved** (not bumped), so a
  staleness alert keeps counting from the last successful backup.

When `PUSHGATEWAY_URL` is set, the same metrics are pushed to
`<url>/metrics/job/jtoye_db_backup/instance/<db>` (best-effort; a push failure is
logged but never fails the backup).

### Proposed Alertmanager staleness rule

Add to `infra/monitoring/prometheus/alerts.yml` once a textfile collector /
Pushgateway is wired into the stack (see [#90](#deferred-work-90)):

```yaml
- alert: DatabaseBackupStale
  expr: time() - jtoye_db_backup_last_success_timestamp_seconds > 129600  # 36h
  for: 10m
  labels:
    severity: critical
    service: platform
  annotations:
    summary: "No successful DB backup in over 36h"
    description: "Last successful jtoye DB backup was {{ $value | humanizeDuration }} ago. See docs/runbooks/backups.md."

- alert: DatabaseBackupFailing
  expr: jtoye_db_backup_success == 0
  for: 5m
  labels:
    severity: warning
    service: platform
  annotations:
    summary: "Last DB backup run failed"
    description: "The most recent jtoye DB backup exited non-zero. Check the *.pg_dump.log next to BACKUP_DIR."
```

36h = one missed nightly run plus margin. First-response: run the backup manually
(see above), read the `*.pg_dump.log`, confirm the DB is reachable.

---

## Restore-testing cadence

A backup that has never been restored is a hypothesis, not a safeguard.

- **Quarterly restore drill (mandatory):** restore the latest dump into a
  throwaway database and confirm the app boots and core tables are populated.
  ```bash
  docker exec jtoye-postgres createdb -U jtoye jtoye_restore_test
  gunzip -c <latest>.sql.gz | docker exec -i jtoye-postgres psql -U jtoye -d jtoye_restore_test
  docker exec jtoye-postgres psql -U jtoye -d jtoye_restore_test -c '\dt' | head
  docker exec jtoye-postgres dropdb -U jtoye jtoye_restore_test
  ```
  Record the drill date + result in the ops log. A drill that restores cleanly is
  the only proof the pipeline works end-to-end.
- **After any change to `backup.sh` or the DB schema:** run a one-off restore
  drill before relying on the next nightly dump.
- **Monthly spot-check:** `--verify` the newest dump (cheap, catches silent
  corruption between drills).

---

## Deferred work (#90)

The following are **out of scope for #119** and tracked under **#90**
(cluster-dependent):

- `k8s/base/pg-backup-cronjob.yaml` busybox/GNU-ism cleanup + runtime `apk add`.
- A dedicated `BYPASSRLS` dump role for the in-cluster CronJob.
- An automated **S3 restore drill** (upload to object storage + periodic
  restore-into-scratch-DB verification in CI/cron).

Until #90 lands, the quarterly restore drill above is performed manually.

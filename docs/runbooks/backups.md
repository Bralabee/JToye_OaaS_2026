# Database Backup Runbook

Operational reference for the JToye OaaS PostgreSQL backups. Two paths exist: the
host script (`infra/backups/backup.sh`, local/docker → gzip'd plain dump) covered
first, and the in-cluster **Kubernetes CronJob** (`k8s/base/pg-backup-cronjob.yaml`
→ custom-format dump to S3) covered in [Kubernetes CronJob backups to S3](#kubernetes-cronjob-backups-to-s3-90).
Covers how backups run, how to verify one, the metrics the script emits, the alert
that should fire when backups go stale, and the restore-testing cadence.

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

## Kubernetes CronJob backups to S3 (#90)

The in-cluster nightly backup (`k8s/base/pg-backup-cronjob.yaml`) is separate from
the host `backup.sh` above: it streams a **custom-format** dump straight to S3.

### The image
Built from `infra/backups/Dockerfile` (`FROM postgres:15-bookworm`) with `pg_dump`,
`pg_restore`, **aws-cli**, and GNU coreutils/grep baked in, running
`infra/backups/k8s-backup.sh` as its ENTRYPOINT. This replaces the old
`postgres:15-alpine` + runtime `apk add aws-cli` (which failed on busybox GNU-isms
and under the default-deny NetworkPolicy). Build & push so the tag matches the
manifest:
```bash
docker build -t ghcr.io/bralabee/jtoye-pg-backup:15 infra/backups
docker push ghcr.io/bralabee/jtoye-pg-backup:15
```

### The BYPASSRLS dump role (critical — FORCE RLS trap)
Tenant tables use **FORCE ROW LEVEL SECURITY**, which applies RLS even to the table
owner. A `pg_dump` run as the app role with no `app.tenant_id` GUC set therefore
**silently captures ZERO rows** from every tenant table. Proven against the live DB:

| Connected as | `SELECT count(*) FROM products` |
| --- | --- |
| `jtoye_app` (app role, FORCE RLS, no tenant) | **0** ← the trap |
| superuser / `jtoye_backup` (BYPASSRLS) | **25** |

Create the least-privilege dump role **as the postgres superuser** (not a Flyway
migration — the app role can't grant `BYPASSRLS`). It needs SELECT on tables **and
sequences** (`pg_dump` reads `last_value`, else it fails with "permission denied for
sequence revinfo_seq"):
```bash
psql -U <superuser> -d jtoye \
  -v backup_password="$(<secret manager>)" \
  -f infra/backups/create-backup-role.sql
```
Put the same password in the `postgres-credentials` secret's `backup-password` key,
and the S3 creds in `s3-backup-credentials` (reference shape:
`k8s/base/secrets-template.yaml.example`). Both secrets must be created
out-of-band — the kustomize builds ship no Secret objects (#100); see
`docs/runbooks/sealed-secrets.md` for the required-secrets table.

### Local end-to-end proof (2026-07-10, dev-sized DB)
Run against the local stack (Postgres + MinIO), backup image + restore drill:

- **Backup:** exit **0**; 133 KiB custom-format dump; verified (size floor +
  `pg_restore --list`); uploaded to `s3://jtoye-db-backups/backups/`.
- **Retention:** a seeded `…-20250101-…` object was **pruned**; recent kept; job did
  not abort (`Pruned 1 old backup(s)`).
- **Restore drill:** downloaded from S3 → `pg_restore` into a scratch DB in **~5s
  (RTO)**; restored row counts **products=25, orders=57, customers=4, shops=10** —
  i.e. the BYPASSRLS dump captured the full tenant data the app-role dump would have
  dropped.
- **RPO:** nightly schedule → **≤24h**; dump itself completes in ~2s on the dev DB.

> These figures are from the **dev-sized** DB. RPO/RTO scale with data volume —
> re-measure on the first prod-cluster drill and record here.

### Restore procedure (custom format)
```bash
aws s3 cp s3://<bucket>/backups/<file>.dump /tmp/r.dump   # + --endpoint-url for MinIO
createdb -U <superuser> jtoye_restore_drill
pg_restore -U <superuser> -d jtoye_restore_drill --no-owner --no-acl /tmp/r.dump
psql -U <superuser> -d jtoye_restore_drill -c 'SELECT count(*) FROM products;'
dropdb -U <superuser> jtoye_restore_drill
```

### Falsifying the dump — the two-arm recipe (added 2026-07-25, Phase 26 / INFRA-02c)

**Every automated check in this pipeline passes on a schema-only, zero-row dump.** That is the whole
reason this section exists. `infra/backups/k8s-backup.sh` verifies the artifact two ways:

- `MIN_BACKUP_BYTES` (default **1000**) — a size floor. Sixty Flyway migrations of DDL comfortably
  exceed 1 KiB, so an empty database clears it easily.
- `pg_restore --list` — a table-of-contents read. A zero-row dump lists its schema perfectly.

Combine that with the FORCE RLS trap documented above (a `pg_dump` as the app role with no tenant GUC
captures **zero rows** from every tenant table, silently) and you have a pipeline that can report a
verified, uploaded, retained backup containing no data at all. A confirmation adds nothing here. Only
a **restore-and-count** falsifies it, and only with the counterexample alongside:

| Arm | Take the dump as | Restore, then `SELECT count(*) FROM products` | What it establishes |
|---|---|---|---|
| **A — the counterexample** | the **app** role (`jtoye_app`: NOSUPERUSER, subject to FORCE RLS, no `app.current_tenant_id` GUC set) | must be **`products = 0`** | That the trap is real in *this* database, so the size floor and the TOC listing are demonstrably not the thing doing the work. A non-zero count here means RLS is not enforcing and the isolation model needs investigating before the backup does. |
| **B — the real backup** | the **BYPASSRLS** dump role (`jtoye_backup`) | must be **`products > 0`** | That the artifact the CronJob actually uploads carries tenant data. |

Run **both, in the same session, against the same database.** Arm B on its own is exactly the result a
broken pipeline also produces once, by luck; arm A is what makes arm B mean something. Record both
counts, not just "restored OK".

Use the commands already proven in [Restore procedure (custom format)](#restore-procedure-custom-format)
verbatim for each arm — they are not repeated here, so they cannot drift. The only difference between
the arms is which role took the dump; the restore side is identical (restore as the superuser into a
throwaway database, count, drop).

Producing arm A is a one-off `pg_dump` under the app role's credentials — it is not something the
CronJob will ever do for you, because the CronJob is wired to the `backup-username` /
`backup-password` keys of `postgres-credentials` on purpose.

For the local-cluster rehearsal of this recipe (how to trigger the CronJob on demand, and where the
captured counts are recorded), see `k8s/LOCAL.md` § "Backup rehearsal" and its rehearsal-evidence row
**L4**. The BYPASSRLS role is bootstrapped there by `scripts/k8s-local-secrets.sh`, which invokes
`infra/backups/create-backup-role.sql` rather than restating the role's privileges.

### Pending (needs a live cluster — flagged, not yet done)
The following ACs require the prod/staging cluster (AKS `sipbihs2aks` currently
unreachable; no local cluster):

- [ ] CronJob completes **in-cluster** (exit 0) with the artifact in the **prod** S3
  bucket.
- [ ] A **prod restore drill** executed against prod S3, with prod-scale RPO/RTO
  recorded above.

The mechanism for both is proven locally (above); only the in-cluster execution is
outstanding.

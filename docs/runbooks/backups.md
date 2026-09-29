# Database Backup Runbook

Operational reference for the JToye OaaS PostgreSQL backups. Two paths exist: the
host script (`infra/backups/backup.sh`, local/docker → gzip'd plain dump) covered
first, and the in-cluster **Kubernetes CronJob** (`k8s/base/pg-backup-cronjob.yaml`
→ custom-format dump to Azure Blob Storage) covered in
[Kubernetes CronJob backups to Azure Blob](#kubernetes-cronjob-backups-to-azure-blob-90-phase-36).
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

- **Quarterly restore drill (mandatory) — run the script:**
  ```bash
  bash scripts/restore-drill.sh          # 0 = verified · 1 = restore is WRONG · 2 = VOID
  ```
  It dumps with the tooling `infra/backups/Dockerfile` actually declares, as the
  BYPASSRLS role the CronJob actually uses, restores into a **throwaway** server of
  the deployed major, and compares **per-table row counts plus the Flyway migration
  count** between source and restore. Measured 2026-08-04: 40 tables / 8095 rows /
  flyway 60/60, restored clean. Record the date + result in the ops log.

  <details><summary>Why the previous manual recipe was replaced (keep for reference)</summary>

  ```bash
  # SUPERSEDED — kept so the difference is visible, not because it should be run.
  docker exec jtoye-postgres createdb -U jtoye jtoye_restore_test
  gunzip -c <latest>.sql.gz | docker exec -i jtoye-postgres psql -U jtoye -d jtoye_restore_test
  docker exec jtoye-postgres psql -U jtoye -d jtoye_restore_test -c '\dt' | head
  docker exec jtoye-postgres dropdb -U jtoye jtoye_restore_test
  ```

  Four problems, each of which lets a broken backup pass:

  1. **It restores into the LIVE server.** `createdb` on `jtoye-postgres` puts drill
     data beside production data and makes the blast radius of a typo the real
     database. The script uses a disposable container with no published ports.
  2. **Wrong artifact format.** The k8s CronJob writes **custom format** (`pg_dump -Fc`,
     `k8s-backup.sh:57`); `gunzip -c … | psql` only reads a plain-SQL dump. The
     documented drill could not have opened the artifact the pipeline produces.
  3. **`\dt | head` is a truncating filter used as proof.** It lists table *names*,
     never row counts, and `head` cuts the list. A restore that creates 40 empty
     tables passes it. The script compares counts per table and fails on any
     difference.
  4. **It says nothing about RLS.** Most tables are ENABLE + FORCE RLS, so a count
     run as a non-BYPASSRLS role with no tenant GUC silently returns fewer rows —
     measured here as **741 vs 2224** on `media_asset_aud`. Count both sides that
     way and a hollow restore compares equal. The script counts as a BYPASSRLS role
     and runs a **control** proving the blind method really is blind before it will
     report success.
  </details>
- **After any change to `backup.sh` or the DB schema:** run a one-off restore
  drill before relying on the next nightly dump.
- **Monthly spot-check:** `--verify` the newest dump (cheap, catches silent
  corruption between drills).

---

## Kubernetes CronJob backups to Azure Blob (#90, Phase 36)

The in-cluster nightly backup (`k8s/base/pg-backup-cronjob.yaml`) is separate from the host
`backup.sh` above: it writes a **custom-format** dump and uploads it to **Azure Blob Storage**
with `blobctl`. It never prunes and never deletes.

> **Status (2026-09-29).** The image and script below are the Blob pipeline. The CronJob
> manifest, the image-tag references tracked by the parity gate, and the rendered goldens move to
> the `:15-blob` tag and the Workload Identity wiring **together**, in one change (D-10). Until
> that lands, `k8s/base/pg-backup-cronjob.yaml` still names the pre-Blob image. The Azure estate
> itself is provisioned in Phase 29.

### Destination (D-01, D-11)

| | Staging | Production |
|---|---|---|
| Storage account | `jtoyestgbackup` (`ukwest`) | `jtoyeprodbackup` (`ukwest`) |
| Container | `jtoye-db-backups` (**private**) | `jtoye-db-backups` (**private**) |
| Blob name | `backups/jtoye-backup-YYYYMMDD-HHMMSS.dump` | same |

- The backup account is **dedicated**: separate from the media account, and in a different region
  from staging (`uksouth`). `uksouth` and `ukwest` are an Azure region pair.
- **The trade D-01 records, deliberately:** this survives object deletion and a compromised app
  credential. It does **not** survive loss of the whole Azure subscription. An off-Azure copy was
  considered and not chosen; revisit it before real customer data if that risk is re-weighed.
- The first line of defence is the managed database's own point-in-time restore. This dump is the
  second line.
- Account, container, data protection, identities and RBAC are specified in
  [`azure-blob-provisioning.md`](./azure-blob-provisioning.md). Every `az` line there pins the
  subscription. That runbook is executed in Phase 29.

### Credential: Workload Identity, no Secret (D-02)

- The job runs under ServiceAccount **`pg-backup`**, with the pod-template label
  `azure.workload.identity/use: "true"` (staging identity `jtoye-staging-backup-id`, federated
  subject `system:serviceaccount:jtoye-staging:pg-backup`).
- The webhook projects a federated token and sets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` and
  `AZURE_FEDERATED_TOKEN_FILE`. The job sets `STORAGE_AUTH_MODE=workload-identity` and
  `STORAGE_ENDPOINT=https://jtoyestgbackup.blob.core.windows.net`.
- **No account key, SAS or connection string is stored in any Secret**, sealed or not.
- The identity's data-plane role is **write-only** on `jtoye-db-backups`: it can create blobs; it
  cannot read, list or delete (provisioning runbook §6). So the job has **no post-upload read-back**
  — it cannot list what it wrote — and a compromised job cannot read, delete or replace old dumps,
  which hold every tenant's PII.
- The database half is unchanged: `DB_USER` / `PGPASSWORD` come from the `postgres-credentials`
  secret's `backup-username` / `backup-password` keys, and must be the BYPASSRLS role below.

### The image

Built from `infra/backups/Dockerfile`, a two-stage build:

1. `golang:1.27-alpine` compiles **`blobctl`** (`infra/backups/blobctl`) after `go mod verify`,
   as a static, stripped binary. The stage is build-only; only the binary is copied out.
2. `postgres:15-bookworm` supplies `pg_dump` / `pg_restore` and GNU coreutils. Its major is
   governed by `scripts/check-postgres-major-parity.sh`; do not change it on its own. Only
   `ca-certificates` is added.

The image runs `infra/backups/k8s-backup.sh` as its ENTRYPOINT, as uid 1000, with `HOME=/tmp`.

CI publishes it. The `pg-backup` leg of `build-and-push` in `.github/workflows/ci-cd.yaml`
builds this image, puts it through the same Trivy image gate as the other shipped images, and on
every push to the default branch also pushes the exact tag the CronJob pulls. That tag is read
from `k8s/base/pg-backup-cronjob.yaml` and both goldens, never restated in the workflow. The leg
fails before pushing if those three disagree, or if the repository they name is not the one the
job publishes to. So a tag change is a manifest change, made in the one D-10 change, and the next
main build publishes it. A local build, for example for the restore drill, is:

```bash
docker build -t ghcr.io/bralabee/jtoye-pg-backup:15-blob infra/backups
```

The tag changed from `:15` because the contents changed (D-10).

`blobctl` in one paragraph: `upload <file> <container> <blob>` never overwrites (it sends
`If-None-Match: *` on both its single-shot and its staged path, and exits **3** if the blob
exists); `list <container> <prefix>` prints sorted names; `download <container> <blob> <out>`
writes a new 0600 file and exits **4** if the blob is missing. It has **no delete** code path.
One switch, `STORAGE_AUTH_MODE`, selects `workload-identity` or `connection-string`, and
connection-string mode accepts **only** the Azurite emulator form
(`UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://<host>`). Exit codes: 0 ok, 1 error,
2 usage, 3 exists, 4 not found.

### Environment contract of `k8s-backup.sh`

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `DB_HOST` / `DB_NAME` | yes | — | Source database. |
| `DB_PORT` | no | `5432` | |
| `DB_USER` / `PGPASSWORD` | yes | — | Must be a **BYPASSRLS** role (see below). |
| `BACKUP_CONTAINER` | yes | — | The Blob container, `jtoye-db-backups`. |
| `STORAGE_AUTH_MODE` | yes | — | `workload-identity` (staging/production) or `connection-string` (Azurite). |
| `BACKUP_PREFIX` | no | `backups` | Blob name prefix; the blob is `<prefix>/jtoye-backup-<UTC timestamp>.dump`. |
| `MIN_BACKUP_BYTES` | no | `1000` | Size floor. |
| `STORAGE_ENDPOINT` | WI only | — | `https://<account>.blob.core.windows.net`, read by `blobctl`. |
| `STORAGE_CONNECTION_STRING` | Azurite only | — | The emulator form only, read by `blobctl`. |

A missing required variable fails the job **before** the dump is taken.

### What the job verifies — and what it cannot

1. `pg_dump` runs with an **explicit return-code check**. A partial file after a non-zero exit is
   deleted, never uploaded.
2. A **size floor** (`MIN_BACKUP_BYTES`).
3. `pg_restore --list`: the archive's table of contents is readable.
4. `blobctl upload`; any non-zero exit (including 3, "already exists") fails the job.

**Checks 2 and 3 both pass on a dump that holds no tenant rows.** The schema and the non-tenant
reference data alone are far above the floor, and a table of contents lists perfectly with nothing
behind it. Only a restore-and-count tells a real backup from a hollow one. That is the two-arm
drill below, and it runs every night.

### Retention: the container, not the script (D-01)

There is **no prune in the script**; it was removed in Phase 36. Retention is enforced by the
backup container itself (values recorded in D-11, applied by the provisioning runbook §4):

- **Blob soft delete, 14 days.** Enabled first.
- **Container-level time-based immutability (WORM), 30 days.** It stays **unlocked** through the
  Phase 29 restore drill; then a human locks it (provisioning runbook §4.4). A locked policy
  cannot be shortened or removed until it lapses. Under WORM a blob can be created but never
  overwritten, and not deleted inside retention.
- **A lifecycle rule** deletes `backups/` blobs after **35 days**, once WORM allows it.

Because expiry is the account's job, the CronJob identity needs create permission only.

**Locally, against Azurite, there is no lifecycle rule and no WORM.** Dev dumps and the drill's
own dumps (under `drill/<run>/`) therefore accumulate in the `azurite_data` volume. On the current
dev database a dump is about 15 MB, and each drill run writes two. This is accepted: it is dev
only, and the nightly's Azurite is destroyed with its runner.

### The BYPASSRLS dump role (critical — FORCE RLS trap)

Tenant tables use **FORCE ROW LEVEL SECURITY**, which applies RLS even to the table owner. Measured
on the dev database, 2026-09-29 (PostgreSQL 15):

| Connected as | What happens |
| --- | --- |
| `jtoye_app` (owner, FORCE RLS, no tenant GUC) — plain `SELECT count(*) FROM products` | **0**, silently. That is the trap. |
| `jtoye_app` — `pg_dump` | **Exits 1**: `ERROR: query would be affected by row-level security policy for table "customers"`. `pg_dump` asks for `row_security=off`, which Postgres refuses to a non-BYPASSRLS role. |
| `jtoye_app` — `pg_dump --enable-row-security` | **Exits 0** with a dump holding **zero** tenant rows, which passes the size floor and the TOC read. |
| `jtoye_backup` (BYPASSRLS) | **23** products, the full data. |

So the job's return-code check is a real safety net, and so is its deletion of the partial file.
Neither makes the BYPASSRLS role optional: a hollow dump is still one flag away.

Create the least-privilege dump role **as the postgres superuser** (not a Flyway migration: the
app role cannot grant `BYPASSRLS`). It needs SELECT on tables **and sequences**; `pg_dump` reads
`last_value` and otherwise fails with "permission denied for sequence revinfo_seq". Hand the
password to psql through the environment so it never appears on a command line:

```bash
export BACKUP_PW="$(<secret manager>)"
{ printf '\\getenv backup_password BACKUP_PW\n'; cat infra/backups/create-backup-role.sql; } \
  | psql -U <superuser> -d jtoye -v ON_ERROR_STOP=1
```

Put the same password in the `postgres-credentials` secret's `backup-password` key. There is **no
storage credential to create**: Blob access is the Workload Identity above. The kustomize builds
ship no Secret objects (#100); see `docs/runbooks/sealed-secrets.md` for the required-secrets table.

### Restore procedure (custom format)

**Who reads a dump.** The CronJob's identity is write-only, so it cannot download anything. A
restore from a real backup account is done by a **human** with a **time-bound Storage Blob Data
Reader** assignment on `jtoye-db-backups`, removed afterwards (provisioning runbook §6):

```bash
SUB=c483d353-5f61-4587-a790-addb9ab5fb94          # the J'Toye subscription, always explicit
ACCT=jtoyestgbackup                                # or jtoyeprodbackup
az storage blob list --subscription "$SUB" --account-name "$ACCT" --auth-mode login \
  --container-name jtoye-db-backups --prefix backups/ --query '[].name' -o tsv   # names sort by time
az storage blob download --subscription "$SUB" --account-name "$ACCT" --auth-mode login \
  --container-name jtoye-db-backups --name backups/<file>.dump --file /tmp/r.dump
```

**Locally, against Azurite,** use `blobctl` from the backup image. This is exactly what the drill
does:

```bash
docker run --rm --network <compose network> \
  -e STORAGE_AUTH_MODE=connection-string \
  -e 'STORAGE_CONNECTION_STRING=UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite' \
  -v /tmp:/out --entrypoint blobctl ghcr.io/bralabee/jtoye-pg-backup:15-blob \
  download jtoye-db-backups backups/<file>.dump /out/r.dump
```

**Then restore into a throwaway database, never the live one:**

```bash
createdb -U <superuser> jtoye_restore_drill
pg_restore -U <superuser> -d jtoye_restore_drill --no-owner --no-acl --exit-on-error /tmp/r.dump
psql -U <superuser> -d jtoye_restore_drill -c 'SELECT current_database(), count(*) FROM products;'
dropdb -U <superuser> jtoye_restore_drill
```

Count as the superuser (or a BYPASSRLS role). A count taken as the app role returns 0 on a full
table, and a zero-vs-zero comparison proves nothing.

### The two-arm drill — executed by `scripts/check-backup-restore-drill.sh`

A confirmation adds nothing here. Only a **restore-and-count** falsifies the pipeline, and only
with the counterexample alongside it:

| Arm | Dump taken as | Must | What it establishes |
|---|---|---|---|
| **A-job** | the backup **job** as the app role `jtoye_app` | exit non-zero and upload **nothing** | the return-code check and partial-file deletion keep a refused dump out of Blob |
| **A-dump — the counterexample** | `pg_dump --enable-row-security` as `jtoye_app` | pass the size floor and the TOC read, then restore to **`products = 0`** | the trap is real in *this* database, so the content checks demonstrably do not do the work. A non-zero count means RLS is not enforcing: investigate isolation before the backup |
| **B — the real backup** | the backup job as the **BYPASSRLS** `jtoye_backup` | restore to **`products` = the live count, > 0** | the artifact the CronJob uploads carries the tenant data |

Run all arms in the same session against the same database. Arm B on its own is exactly the result
a broken pipeline also produces once, by luck (a count read from the wrong database, for instance,
passes arm B and fails arm A). The gate therefore also requires every count to report
`current_database()` equal to that run's scratch database.

**How the gate runs it.** It ensures `jtoye_backup` from `infra/backups/create-backup-role.sql`,
with the password taken from `.env`'s `DB_BACKUP_PASSWORD`. It reads the live `products` count as
that role; a count of 0 is a VOID. Then it runs the pg-backup image exactly as the CronJob does,
against the compose Postgres and Azurite. A second container of the same image lists, downloads,
restores into a scratch database and counts. Credentials travel only in 0600 env files and are
never printed; scratch databases are dropped on exit.

```bash
docker build -t ghcr.io/bralabee/jtoye-pg-backup:15-blob infra/backups
docker compose -f docker-compose.full-stack.yml up -d postgres azurite    # core-java is not needed
bash scripts/check-backup-restore-drill.sh      # 0 PASS · 1 an arm failed · 2 VOID
```

**Nightly.** `.github/workflows/e2e-nightly.yml` builds the image and runs the gate on every
scheduled run once the stack is up. A red or VOID drill fails the nightly. It is the restore path's
only standing coverage: nothing else in CI builds the image.

**First execution, 2026-09-29 (dev DB):**
- arm A restored products = **0**
- arm B restored products = **23**, equal to live **23**
- the hollow A-dump was 15,762,631 bytes against a floor of 1000, with a 412-entry TOC — it passed
  both content checks
- the A-job exited 1 with the row-security refusal and uploaded nothing

The fail directions recorded with it:
- a job run as the app role in arm B → exit 1
- arm A run as `jtoye_backup` → exit 1
- a truncated, bit-rotted, empty or missing dump in Blob → exit 1
- a count read from the live database → exit 1
- Azurite stopped → exit 2

### What Azurite cannot prove

- **Blob immutability (WORM), soft delete and lifecycle deletion** are not in Azurite's support
  matrix. The retention half of D-01 is therefore proven only by the Phase 29 read-backs
  (provisioning runbook §8): `az storage container immutability-policy show`,
  `az storage account blob-service-properties show`, `az storage account management-policy show`.
- **RBAC.** Azurite does not enforce data-plane roles, so the write-only backup role can only be
  probed on the real account.
- **The Workload Identity path.** Locally the job authenticates with the emulator connection string.
- **Row contents.** The drill compares row counts, not values.

### Pending (Phase 29)

- [ ] Backup account, container, soft delete, WORM (unlocked) and lifecycle created, with the
  provisioning runbook §8 read-backs recorded both ways.
- [ ] The first staging CronJob run exits 0 and leaves exactly one new `.dump` under `backups/`.
- [ ] A **staging restore drill** from the real account (a human with a time-bound Reader, the
  procedure above), with staging-scale RPO and RTO recorded here.
- [ ] After that drill, a human locks the WORM policy (provisioning runbook §4.4).

### History

The dated rehearsal records of the pre-Phase-36 pipeline are kept verbatim in
[`docs/archive/backups-rehearsal-evidence-2026-07.md`](../archive/backups-rehearsal-evidence-2026-07.md):
- the 2026-07-10 local end-to-end proof (products=25, RTO about 5s)
- the 2026-07-25 in-cluster rehearsal on the local minikube (plan 26-07), with its two-arm table
  (A = 0, B = 47) and the first measurement of the `pg_dump` row-security refusal
- the pending list as it stood then

The dump/verify half of the job is unchanged since then, so those measurements still describe it.

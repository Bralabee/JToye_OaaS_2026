# Backup rehearsal evidence, 2026-07 (archived)

Archived 2026-09-29 from `docs/runbooks/backups.md` by Phase 36 plan 36-08. These sections are
the dated rehearsal records of the Kubernetes backup CronJob as it was **before Phase 36**, when
the dump went to an S3-API object store (local MinIO; an AWS S3 target that was never
provisioned) through the AWS CLI, and the script pruned old dumps itself. Phase 36 moved the
destination to Azure Blob Storage (Azurite locally), replaced the AWS CLI with `blobctl`, and
removed the in-script prune (D-01). The records are kept **verbatim, unedited**, as history: they
carry measured facts (row counts, RTO, the two-arm result, the pg_dump row-security mechanism)
that the live runbook builds on.

**Nothing here describes the current pipeline.** Commands, bucket paths, secret names and the
"Pending" list below refer to the retired destination. For the live procedure see
[`docs/runbooks/backups.md`](../runbooks/backups.md); for the executed drill see
`scripts/check-backup-restore-drill.sh`.

The two sections below were separated in the runbook by the restore procedure and the two-arm
recipe, which stay live (rewritten for Blob); they are joined here with nothing between them.

<!-- BEGIN VERBATIM (docs/runbooks/backups.md at bf0b93a8, before 36-08 Task 3) -->

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

### In-cluster result — local minikube (2026-07-25, Phase 26 / plan 26-07)

The first execution of this CronJob **inside a real Kubernetes cluster**. Namespace
`jtoye-local` on the `jtoye` minikube profile, image
`ghcr.io/bralabee/jtoye-pg-backup:15` (`sha256:943a78f6…`, rebuilt during this run — the
on-host `:15` tag beforehand dated 2026-07-10 and predated Phases 23–25).

Triggered on demand with
`kubectl --context jtoye -n jtoye-local create job pg-backup-rehearsal --from=cronjob/pg-backup`.

- **Job result:** `.status.succeeded` = **1**; `kubectl wait --for=condition=complete`
  reported `condition met`, exit 0.
- **Connection:** dumped as **`jtoye_backup`** against `host.minikube.internal:5433` —
  the in-cluster job reached the compose-hosted Postgres over the pod host, on the port
  supplied by the `postgres-credentials` secret rather than a hardcoded 5432.
- **Artifact:** `s3://jtoye-db-backups/backups/jtoye-backup-20260725-204829.dump`,
  **214370 bytes**, verified by the size floor and `pg_restore --list`, uploaded via the
  `--endpoint-url` path to host MinIO (`s3.backup.endpoint` =
  `http://host.minikube.internal:9000`).
- **Retention:** `Pruned 0 old backup(s)` — correct, the bucket was new. The prune loop
  tolerated the near-empty listing without aborting the job.
- **Not world-readable:** an unauthenticated `GET` of that object key returns **403**,
  while the same probe against a known `jtoye-images` object returns **200**. Existence
  was confirmed from the job log's key plus the bucket listing *before* the 403 was
  interpreted — MinIO answers 403 for a nonexistent key too, so an unordered probe would
  be satisfiable by absence.

**Both arms of the falsification, run in the same session against the same database:**

| Arm | Dump taken as | Restored counts | Verdict |
|---|---|---|---|
| **A — counterexample** | `jtoye_app` (NOSUPERUSER, FORCE RLS, no tenant GUC) | `products=0 orders=0 customers=0 shops=0` | the trap is real in this database |
| **B — the real backup** | `jtoye_backup` (BYPASSRLS), i.e. the object above | `products=47 orders=23 customers=12 shops=5` | the uploaded artifact carries tenant data |

Arm B cross-checks exactly against the live database read through the BYPASSRLS role
(`products=47 customers=12 orders=23 shops=5`), so the dump captured the full data rather
than a subset. Restore was `pg_restore` rc=0 with 0 errors, **RTO 9s** on this dev-sized
DB. Both scratch databases were dropped afterwards.

**Why arm A is not optional.** Arm A's zero-row artifact **passes both of this pipeline's
automated verifications**: 149268 bytes against a `MIN_BACKUP_BYTES` floor of 1000 (149x
clear), and a clean `pg_restore --list` with 393 TOC entries. Neither check can tell the
two arms apart. Only the row count does.

**One correction to the mechanism described above.** This section previously said an
app-role dump "silently captures ZERO rows". Measured on PostgreSQL 15 with 36 tables
`ENABLE` RLS, all 36 `FORCE`, the behaviour is two-part:

- a plain `SELECT` as `jtoye_app` with no GUC does return **0 rows, silently** — that is
  the trap, and it is what arm A's restore surfaces;
- `pg_dump` additionally requests `row_security=off`, which Postgres **refuses** for a
  non-BYPASSRLS role on a FORCE-RLS table, so `pg_dump` itself **exits 1** with
  `ERROR: query would be affected by row-level security policy for table "customers"`.

So `pg_dump` fails loudly rather than silently — a safety net this runbook did not claim.
It does **not** retire the BYPASSRLS role and does not make arm A redundant: the partial
artifact left behind still clears both content checks while restoring to zero rows.
`k8s-backup.sh`'s explicit return-code check and its `rm -f "$TMP"` on failure are what
prevent that artifact reaching S3 — both are load-bearing, and neither is implied by the
size floor or the TOC read.

Full captured evidence, including the verbatim job log, is in `k8s/LOCAL.md` §11 rows
**L3** and **L4**.

### Pending (needs a live cluster — flagged, not yet done)
The following ACs require the prod/staging cluster (AKS `sipbihs2aks` currently
unreachable):

- [x] CronJob completes **in-cluster** (exit 0) — done 2026-07-25 on the **local**
  minikube cluster, artifact in the **local MinIO** `jtoye-db-backups` bucket (see the
  dated section above). The **prod** S3 bucket half of this AC is NOT met and is carried
  by the unticked item below.
- [ ] The artifact in the **prod** S3 bucket, from a CronJob run in the prod cluster.
- [ ] A **prod restore drill** executed against prod S3, with prod-scale RPO/RTO
  recorded above.

The mechanism is now proven **in-cluster** rather than only on the host; what remains is
execution against production infrastructure.

<!-- END VERBATIM -->

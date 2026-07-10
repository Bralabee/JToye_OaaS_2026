---
quick_id: 260710-u1q
title: "Harden k8s pg-backup CronJob + BYPASSRLS dump role + restore drill (#90)"
closes_issue: 90
status: complete
branch: feature/90-k8s-backup
---

# Quick Task 260710-u1q — k8s backup hardening (#90 / P1-8) Summary

Made the never-working k8s backup actually work, and proved the full
dump→S3→restore loop against the local stack. Two ACs need a live cluster and are
flagged pending (the mechanism for both is proven locally).

## What changed
- **`infra/backups/Dockerfile` (new):** `FROM postgres:15-bookworm` with `pg_dump`/
  `pg_restore` + **aws-cli** + GNU coreutils baked in. Kills the two root failures:
  busybox GNU-isms (`date -d`/`grep -oP`) and runtime `apk add aws-cli` (blocked by
  the default-deny NetworkPolicy). Runs as `USER 1000`.
- **`infra/backups/k8s-backup.sh` (new, baked ENTRYPOINT):** hardened S3 backup —
  custom-format dump, fail-loud rc check, size floor + `pg_restore --list` content
  verify, delete-artifact-on-failure, S3 upload (optional `--endpoint-url`), and a
  retention prune that tolerates an empty bucket (no pipefail abort) and never aborts
  on a single failed delete. All config from env (nothing hardcoded).
- **`infra/backups/create-backup-role.sql` (new):** least-privilege `jtoye_backup`
  role WITH `BYPASSRLS` (superuser bootstrap, not a Flyway migration). SELECT on
  tables **and sequences** — the sequence grant was added after live testing caught
  `pg_dump` failing on `revinfo_seq`.
- **`k8s/base/pg-backup-cronjob.yaml`:** rewired from **non-existent** resources
  (`jtoye-secrets`/`jtoye-config`) to the real `postgres-credentials` + `app-config`
  + new `s3-backup-credentials`; uses the new image (no inline script/`apk add`);
  dumps as the BYPASSRLS role.
- **`secrets-template.yaml` / `configmap.yaml`:** added `backup-username`/
  `backup-password`, the `s3-backup-credentials` secret, and `s3.backup.*` config
  keys (all `REPLACE_WITH_*` placeholders — no real secrets). The `pg-backup-allow`
  NetworkPolicy already grants the needed egress.
- **`docs/runbooks/backups.md`:** new "Kubernetes CronJob backups to S3" section —
  image build, BYPASSRLS role setup, the FORCE-RLS trap + evidence, restore
  procedure, local drill RPO/RTO, and the pending prod-cluster ACs.

## Verification (proof)
- **AC#2 (FORCE-RLS trap) — proven live:** `jtoye_app` (FORCE RLS, no tenant) sees
  **0** products; superuser/`jtoye_backup` (BYPASSRLS) sees **25**.
- **AC#1 (mechanism) — proven locally:** built the image, ran it against local
  Postgres (as `jtoye_backup`) + MinIO → **exit 0**, 133 KiB dump verified, object
  landed in `s3://jtoye-db-backups/backups/`.
- **AC#3 (retention) — proven:** seeded a `…-20250101-…` object → re-run **pruned it**
  (`Pruned 1 old backup(s)`), kept recent, job exit 0.
- **AC#4 (restore drill) — executed locally:** S3 → `pg_restore` into scratch DB in
  **~5s (RTO)**; restored counts **products=25, orders=57, customers=4, shops=10** →
  the BYPASSRLS dump captured the full tenant data. **RPO ≤24h** (nightly), dump ~2s.
- **Manifests:** `kubectl kustomize k8s/base` builds (27 resources); all 11 CronJob
  env refs resolve to defined keys; `bash -n` clean. (Server-side `--dry-run` needs
  the unreachable AKS API for OpenAPI, so schema validation was client-parse only.)
- Local dev DB + MinIO restored to prior state (demo role + test bucket removed).

## Pending (needs a live cluster — flagged)
- [ ] CronJob completes **in-cluster** (exit 0) with artifact in the **prod** S3 bucket.
- [ ] **Prod restore drill** with prod-scale RPO/RTO recorded.

AKS `sipbihs2aks` is unreachable and no local cluster exists, so these two ACs can't
be executed here. The image + role + manifests + script are proven end-to-end
locally; only in-cluster execution remains. Also: the backup image must be built &
pushed to the registry (`ghcr.io/bralabee/jtoye-pg-backup:15`) before the CronJob
can pull it (not wired into CI in this PR).

## Caveats
- Image not yet added to the CI build matrix (documented build/push command in runbook).
- `backup.sh` (host path) retention was already fixed in #139; unchanged here.

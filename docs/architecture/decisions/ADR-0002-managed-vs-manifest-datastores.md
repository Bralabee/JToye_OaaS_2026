# ADR-0002: Managed services vs in-cluster manifests for stateful infrastructure

**Status:** Accepted (2026-08-10) — signed by the owner as proposed; recorded on main 2026-10-18 (see the last section)
**Refs:** #101 (no PITR / no DB HA), #98 (observability), #100 (sealed secrets), SYSTEM_DESIGN_V2 §3.2/§7.1

## Context

The k8s deployment covers the app tier only. There are **no datastore or RabbitMQ
manifests**: production-shaped PostgreSQL is a single instance with a daily logical
dump (RPO ≤ 24h, no PITR — #101), Redis has no k8s story, and the RabbitMQ STOMP
relay the frontend depends on in prod is only guaranteed in docker-compose. For a
payments platform, the PostgreSQL gap is the critical one: WAL-based PITR and HA
have to come from somewhere, and the choice shapes all of #101's remaining work.

## Options

1. **Fully managed** — Azure Database for PostgreSQL Flexible Server (built-in PITR
   up to 35 days, zone-redundant HA, automated patching), Azure Cache for Redis;
   RabbitMQ has no first-party Azure service (CloudAMQP via marketplace).
2. **Fully self-hosted in-cluster** — CloudNativePG (or Zalando) operator + WAL
   archiving to Blob/S3, Redis operator, RabbitMQ cluster operator. Full control,
   but the team owns failover, upgrades, backup engineering, and the 3 AM pages.
3. **Hybrid** — managed for data-critical stateful services, operator manifests for
   the small/replaceable ones.

## Decision (proposed)

**Hybrid (option 3):**

- **PostgreSQL → managed** (Azure Flexible Server). Closes #101's PITR + HA
  acceptance criteria with configuration instead of a Patroni estate; the restore
  drill becomes a documented runbook against provider tooling. The existing
  `pg-backup-cronjob` logical dump stays as a provider-independent second line.
- **Redis → managed** (Azure Cache). Cache-only workload; cheapest to outsource.
- **RabbitMQ → in-cluster manifest** via the RabbitMQ cluster operator. Small
  footprint, we need explicit control of the STOMP plugin, and losing it degrades
  (frontend falls back) rather than destroys.

## Consequences

- #101 implementation = provisioning + connection-string/secret changes + a
  rehearsed restore runbook; no WAL-G/Patroni engineering in-cluster.
- k8s NetworkPolicies and sealed-secrets (#100) must account for out-of-cluster
  datastore endpoints.
- Monthly infra cost rises (managed premiums); offset by not staffing DB ops.
- Local dev is unaffected — docker-compose remains the dev stack.
- SYSTEM_DESIGN_V2 §3.2's Patroni/PgBouncer TARGET diagram is superseded if this
  ADR is accepted.

## 2026-07-29 — Open question (Phase 27, plan 27-02)

**The staging/production RabbitMQ broker's version is unverifiable from this repository, and nobody
owns it.** Recorded here rather than silently fixed, because plan 27-02 could not fix it and will
not pretend otherwise.

Measured 2026-07-29:

- `k8s/base/configmap.yaml` points the application at
  `rabbitmq.jtoye-infrastructure.svc.cluster.local`, but **there is no RabbitMQ manifest anywhere
  under `k8s/`** — only a host, a port and a `rabbitmq-credentials` secret reference. The broker is
  provisioned outside this repository.
- Its **version is therefore unknown and unknowable from this checkout**. No file declares it, no
  gate reads it, and `scripts/check-runtime-freshness.sh` structurally cannot see it (it discovers
  only compose services with a `build:` stanza).
- The dev/compose broker moved **3.12.14 → 4.3.4** on 2026-07-29 by fresh install. That path
  destroys every queued message and is **not** available for a broker holding real traffic; the
  supported chain is 3.12 → 3.13 → 4.2 → 4.3. See
  [`docs/runbooks/rabbitmq-broker-upgrade.md`](../../runbooks/rabbitmq-broker-upgrade.md) §2 and §7.
- The in-cluster RabbitMQ **cluster-operator option proposed in this ADR on 2026-07-12 remains
  unsigned** and was never built.

Tracked as the `rabbitmq-k8s` row in `infra/dependency-horizons.yaml`: `pin: unknown`,
`owner: UNASSIGNED`, with a dated `manual_review` that **expires 2026-10-26**. That expiry is the
mechanism — the horizon gate turns this from a note nobody reads into a finding that fires on its
own if the question is still open by then.

**Status deliberately unchanged.** Resolving the operator question needs owner sign-off, which is a
human decision and not an agent's to record.

## Superseded in part (2026-09-28)

Phase 36 (Azure Blob Storage throughout) supersedes two storage assumptions in this record. The
text above is left as it was written; read it with these two changes applied.

- **D-01 — the logical-dump destination.** The `pg-backup-cronjob` logical dump that this ADR keeps
  "as a provider-independent second line" now goes to a **dedicated Azure storage account**,
  separate from the media account and in a different Azure region from staging, with an
  immutability (WORM) policy and soft delete on its container (`jtoye-db-backups`). The job
  authenticates with AKS Workload Identity and no longer prunes: retention is the container's
  immutability period, soft delete and lifecycle rule. The earlier "off-cluster AND off-Azure"
  destination is dropped, and the trade is recorded in the phase context: this survives object
  deletion and a compromised app credential, but not loss of the whole Azure subscription.
- **D-06 — the media origin.** Staging and production serve images from the **raw Blob endpoint**
  (`https://<account>.blob.core.windows.net/jtoye-images/...`); no CDN or custom domain in Phase 36.
  Option 2's "WAL archiving to Blob/…" now has exactly one object store to target: Azure Blob.

Decision record: [`.planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md`](../../../.planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md)
(D-01, D-06). Procedures: [`docs/runbooks/azure-blob-provisioning.md`](../../runbooks/azure-blob-provisioning.md)
and [`docs/runbooks/backups.md`](../../runbooks/backups.md).

This note does not change the ADR's status: the PostgreSQL / Redis / RabbitMQ hosting decision above
is still Proposed and still needs owner sign-off.

## 2026-10-16 — Manual review re-check (Phase 39, plan 39-01)

Re-checked ahead of the `2026-10-26` `rabbitmq-k8s` `manual_review` expiry:

- `rg -uu -n 'image:.*rabbitmq' k8s/` still returns zero matches (and `k8s/local/configmap-patch.yaml:136`
  routes the local minikube overlay to the host Compose broker via `host.minikube.internal`).
  Staging/production RabbitMQ remains provisioned outside this repository and its version remains
  unverifiable from this checkout.
- Upstream check against `https://www.rabbitmq.com/release-information` (`2026-10-16`): `4.3.6`
  (`16 Sept 2026`) is the latest release (no `4.4` release exists yet on GitHub or Docker Hub), and
  the vendor extended **4.3 End of Community Support to `31 Jan 2027` (`2027-01-31`)** (End of
  Commercial Support `30 Apr 2028`).
- `rabbitmq-k8s.manual_review` in [`infra/dependency-horizons.yaml`](../../../infra/dependency-horizons.yaml)
  is refreshed to `last_checked: "2026-10-16"`, `expires: "2027-01-16"` (90-day cadence). Status of
  this ADR remains `Proposed` pending owner sign-off.

## 2026-10-18 — Accepted (owner sign-off of 2026-08-10, recorded on main)

The owner signed this ADR **as proposed** on **2026-08-10**, during the Phase 29 discussion: the
hybrid of option 3. PostgreSQL goes to Azure Database for PostgreSQL Flexible Server, Redis to managed
Azure Cache, and RabbitMQ in-cluster through the RabbitMQ cluster operator. That sign-off is
decision D-09 in the Phase 29 context, which exists only on the `phase-29-research` branch
(commit `9555dcd6`, `.planning/phases/29-deployable-staging-with-its-own-monitoring/29-CONTEXT.md`, D-09). The
2026-10-17 salvage audit ruled that branch is re-planned on main, not merged. So until now the
acceptance existed only off main, and every section above still read Proposed. This entry records
it on main. The Status line is changed to match. Every section above is left as written, per this
file's convention that dated records are appended, not edited.

What this entry does **not** carry over from the branch, deliberately:

- **Concrete pins.** The branch named an operator version, a broker image, a cert-manager version
  and a PostgreSQL major version for the managed server. They were measured in August against an
  estate that is being deleted, and the dev stack has since moved to PostgreSQL 18 and RabbitMQ 4.3.6.
  Phase 29, re-planned on main, re-derives and records them.
- **The PostgreSQL version floor stays a Phase 29 input.** It is not decided here. The branch found
  that the `jtoye_backup` role (`BYPASSRLS`, `infra/backups/create-backup-role.sql`) cannot be
  created by `azure_pg_admin` on PostgreSQL 15 or earlier. Without that role the backup cannot read
  tenant data. A plain `pg_dump` (default `row_security=off`) fails with an error on the first
  FORCE-RLS table. A dump run with `--enable-row-security`, or any plain `SELECT`, silently
  returns zero tenant rows. So the managed server must be at least 16. Phase 29 verifies this
  against current Microsoft documentation before provisioning.
- **The managed Redis product and tier are Phase 29 inputs too.** The original decision says only
  "Azure Cache". Phase 29 checks Microsoft's retirement schedule for Azure Cache for Redis, and
  the alternative Azure Managed Redis, before choosing a product and tier.

The `rabbitmq-k8s` horizon row (`infra/dependency-horizons.yaml`) stays as it is. Accepting the
operator route decides *how* the broker's version becomes declared in-repo; the row closes when
Phase 29 actually ships that manifest, not when this entry lands.

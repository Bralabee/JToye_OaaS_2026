# ADR-0002: Managed services vs in-cluster manifests for stateful infrastructure

**Status:** Accepted (2026-08-10) — signed by the owner as proposed, in the Phase 29 discussion; recorded as D-09 in `.planning/phases/29-deployable-staging-with-its-own-monitoring/29-CONTEXT.md`
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

## 2026-08-10 — Signed, and the open question above is closed (Phase 29, plan 29-01)

The owner signed this ADR **as proposed** during the Phase 29 discussion on 2026-08-10, recorded as
decision **D-09** in `.planning/phases/29-deployable-staging-with-its-own-monitoring/29-CONTEXT.md`.
The Status line at the top of this file now reads Accepted. **DPLY-04 (PITR per #101) is thereby
unblocked by a record rather than by an assumption.**

Two things are deliberately *not* rewritten, per this file's own convention that dated records are
appended rather than edited: the `## Decision (proposed)` heading keeps its original wording as the
historical artefact it is, and the 2026-07-29 section above is left byte-identical even though the
question it raises is answered here.

### The `rabbitmq-k8s` open question is closed

The 2026-07-29 section recorded that the staging/production broker's version was "unknown and
unknowable from this checkout", tracked as the `rabbitmq-k8s` row in `infra/dependency-horizons.yaml`
with `pin: unknown`, `owner: UNASSIGNED` and a `manual_review` expiring **2026-10-26**. The
in-cluster route this ADR proposed is now signed, which answers it:

- **RabbitMQ cluster operator v2.22.3** (released 2026-07-17), image
  `ghcr.io/rabbitmq/cluster-operator:2.22.3`, vendored as the single `cluster-operator.yml` manifest
  into namespace `rabbitmq-system` — no Helm, consistent with D-16.
- The broker itself is pinned to **`rabbitmq:4.3.4-management-alpine`**, the same version the
  dev/compose stack moved to on 2026-07-29. The STOMP plugin is enabled through the operator's
  `additionalPlugins`, which is precisely the "explicit control of the STOMP plugin" this ADR gave
  as the reason to keep RabbitMQ in-cluster rather than managed.

So the version stops being a property of infrastructure nobody owns and becomes **a declared,
in-repo pin** — which is what the horizon row was waiting for. Note that the operator manifest
carries `cert-manager.io/v1` `Certificate` and `Issuer` objects, so **cert-manager (v1.21.1) must be
installed first** or the operator apply fails on unknown kinds.

### PostgreSQL **16** is a requirement of this decision, not a preference

Choosing managed PostgreSQL has a consequence that was not visible when this ADR was drafted, and it
is recorded here rather than left to surface as a SKU argument in a provisioning script.

`infra/backups/create-backup-role.sql` creates `jtoye_backup` with **`BYPASSRLS`**, and its own
header notes that BYPASSRLS can only be granted by a superuser. On Azure Database for PostgreSQL
Flexible Server the admin login `azure_pg_admin` is a **pseudo**-superuser — Microsoft retains the
real one. Microsoft documents that on **PostgreSQL 15 and earlier you cannot create non-admin users
with BYPASSRLS**, and that **PostgreSQL 16 removed the superuser requirement**, so an
`azure_pg_admin`-created role can hold it from PG16 onward.

The failure mode if this is got wrong is quiet and severe: without `jtoye_backup`, the logical dump
runs as a role subject to FORCE RLS and captures **zero rows from every tenant-scoped table**. The
backup still exists, still has a plausible size, and still passes `pg_restore --list` — a green
backup over an empty database. That is exactly the defect `create-backup-role.sql` was written to
prevent, and exactly what DPLY-04's arm A exists to catch.

**This is a deliberate, staging-only version skew, and it is written down rather than slipped in.**
It diverges from two places that say PostgreSQL 15:

- `CLAUDE.md`'s tech-stack line, which states **PostgreSQL 15** for the platform; and
- the **`postgres:15-alpine`** pin that docker-compose uses for local development and that
  `infra/dependency-horizons.yaml` tracks.

Local dev is unaffected and stays on 15 (this ADR's original "Local dev is unaffected — docker-compose
remains the dev stack" consequence still holds). The alternative — moving compose to 16 — is a real
option but is its own change with its own test surface, and is not folded in here. Corroboration
that 16 is the natural default anyway: the pre-existing `snackpass-pg` Flexible Server in the same
resource group already reports `version: 16` `[VERIFIED: az postgres flexible-server show, 2026-08-10]`.

Two adjacent constraints measured on that same live server, recorded because they bite at first
deploy rather than later:

- `azure.extensions` reads `vector,pgcrypto`. `V1__base_schema.sql` runs
  `CREATE EXTENSION IF NOT EXISTS "uuid-ossp"` (the sole exemption in
  `scripts/check-no-create-extension.sh`). **`uuid-ossp` must be added to the `azure.extensions`
  allowlist before the first Flyway run**, or V1 fails and nothing after it executes.
- Azure reserves **15** connections for replication and monitoring, while
  `k8s/scripts/check-connection-math.sh` assumes `RESERVED=3`. On a `Standard_B2s` server
  (429 total / 414 user) the 155-connection budget still fits comfortably; on `B1ms` (50/35) it
  does not, by roughly a factor of three. This is one reason the free-tier B1ms allowance cannot
  simply be pointed at staging.

### Known, non-blocking horizons created by this decision

Accepting managed services means accepting their retirement clocks. Neither of these blocks Phase 29;
both are recorded so they are foreseen rather than discovered.

| Horizon | Date | Impact |
|---|---|---|
| **Azure Cache for Redis Basic/Standard/Premium retires** in favour of Azure Managed Redis | **2028-09-30** (Enterprise tiers retire 2027-03-30; a CLI migration path lands in phases from Feb 2026) | The `Basic C0` cache this ADR selects for Redis must migrate before then. Long horizon, no action this phase |
| **The `snackpass-pg` free-tier window closes** | **~2027-07-21** (server created 2026-07-21 + 12 months; exact date to be confirmed from Azure Portal → Cost Management → Credits + offers) | Measured 2026-08-10: that server's meters are named `B1MS Compute - Free` and `Storage Data Stored - Free`, i.e. the £0.00 is zero-rated usage, not deferred billing. When the window closes it begins billing at roughly £21/month, **breaching the ~£150/month estate ceiling with no deploy and no code change** |

Both belong in `infra/dependency-horizons.yaml` alongside the `rabbitmq-k8s` row that plan 29-09
updates; they are recorded here first so the reason survives independently of the row.

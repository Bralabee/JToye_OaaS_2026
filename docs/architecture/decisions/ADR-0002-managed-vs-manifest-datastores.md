# ADR-0002: Managed services vs in-cluster manifests for stateful infrastructure

**Status:** Proposed (2026-07-12) — needs owner sign-off before #101 implementation starts
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

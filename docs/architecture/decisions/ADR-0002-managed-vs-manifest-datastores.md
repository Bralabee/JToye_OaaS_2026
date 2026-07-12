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

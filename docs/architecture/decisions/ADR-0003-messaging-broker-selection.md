# ADR-0003: Messaging broker selection — remain on RabbitMQ

**Status:** Accepted (2026-07-26)
**Refs:** [Messaging Broker Evaluation 2026-07-26](../../analysis/MESSAGING-BROKER-EVALUATION-2026-07-26.md)
(full evidence), ADR-0002 (undeclared prod broker), #93 / V46 (outbox reliability), #266 / #269
(STOMP destination grammar), P3-13 (no load baseline)

## Context

An external analysis recommended migrating from RabbitMQ to NATS (with Redpanda as the alternative),
on the grounds of performance headroom, Go-native fit with `edge-go`, broker-level multi-tenancy via
NATS Accounts, and replacing the transactional outbox with Debezium CDC.

The recommendation was independently re-investigated against the actual codebase. Its load-bearing
premise — that `edge-go` uses a message broker — is false: `edge-go/go.mod` contains no broker
dependency and the module has never had one. Two further claims (Debezium eliminating the outbox;
NATS Accounts aligning with the RLS tenant model) are false or inverted.

Relevant facts about the system as it stands:

- The broker is **peripheral, not central**: 11 of 326 main Java files touch AMQP; **0 of the REST
  endpoints** fail if the broker is down, because every producer writes an outbox row inside the
  caller's transaction.
- **The outbox is where reliability lives**, and it is broker-agnostic — it would work identically
  over Kafka, NATS or SQS.
- Real-time delivery runs through a **RabbitMQ STOMP relay**, whose `TenantChannelInterceptor`
  enforces the tenant wall and the shop grant-check on every SUBSCRIBE.
- **PostgreSQL logical decoding bypasses row-level security** (verified by two-arm experiment). This
  system has 93 RLS policies over 46 FORCE-RLS tables and its tenant identity is a session GUC that
  is never written to the WAL.
- Design point is **tens of events/sec**, and **no load baseline exists** (P3-13). Throughput is
  3–4 orders of magnitude below the lowest adversarially-measured ceiling of any candidate.

## Options

1. **Remain on RabbitMQ**, upgrade it, and fix the layer's real defects.
2. **Migrate to NATS (JetStream).** Apache-2.0, single binary, native WebSocket, live per-tenant
   account provisioning.
3. **Migrate to Redpanda.** Kafka API, log-based replay, C++ single binary.

## Decision

**Option 1 — remain on RabbitMQ.**

Reasons, in order of weight:

- **Redpanda is disqualified on two independent grounds:** it is **BSL 1.1** (source-available, not
  open source, with an Additional Use Grant restricting third-party-triggered topic creation), and it
  has **no multi-tenancy primitive** — isolation would be topic prefixes plus ACLs, with RBAC behind
  an enterprise licence. It also has **no browser push**: the KDS would need a WebSocket gateway
  built and operated.
- **NATS is good technology and the wrong fit here.** It has **no STOMP** — the entire real-time
  transport would be rewritten — and with `nats.ws` the browser talks to the broker directly, which
  deletes `TenantChannelInterceptor` and moves tenant authorization onto the critical path of every
  kitchen screen. Its Spring integration is a **0.6.x Spring Cloud Stream binder** last published
  2025-05-27, against a project using first-party `spring-boot-starter-amqp`; there is no
  `@NatsListener` and no declarative equivalent of the 4 DLX/DLQ topologies.
- **Debezium CDC is rejected outright.** It does not eliminate the outbox table (its own docs require
  one), it discards the #93 reliability guarantees, and it would install the system's first
  cross-tenant reader beneath the RLS wall.
- **Migration cost is ~1,100–1,400 LOC across ~100 non-doc files** plus an unbounded real-time
  authorization redesign, for a solo developer mid-milestone, against a broker that is not a
  bottleneck by any measurement.

## Consequences

- The broker layer is treated as **commodity infrastructure to be maintained**, not re-platformed.
  Effort goes to the five defects recorded in the evaluation §6, scheduled as **Phase 27 (Messaging
  Layer Hardening)**.
- **RabbitMQ 3.12 is out of support** (community 2024-02-29, commercial 2025-06-30) and must be
  upgraded to a supported 4.x series. This is now the single largest broker-related risk.
- **If event replay ever becomes a product requirement, the first move is RabbitMQ 4.x Streams**, not
  a Kafka-family migration — same broker, no migration.
- The undeclared staging/production broker (no k8s manifest, unknown version) remains an open gap
  tracked against ADR-0002.
- **Revisit triggers** (any one is sufficient): event replay becomes a product requirement; sustained
  throughput exceeds ~10k msg/s; untrusted third parties need to consume directly from the bus
  (today solved correctly via HMAC-signed HTTPS webhooks); or a licence/support event forces a move.
  Of these, a Broadcom support-policy change is the most plausible — track
  rabbitmq.com/release-information.

# Messaging Broker Evaluation — RabbitMQ vs Redpanda vs NATS

**Date:** 2026-07-26
**Branch at time of investigation:** `feature/phase-26-local-k8s-overlay` (Phase 26 closed 9/9, v2.3 build 6/6)
**Trigger:** an external analysis recommended migrating from RabbitMQ to NATS. This document is the
independent re-investigation of that recommendation.
**Decision record:** [ADR-0003](../architecture/decisions/ADR-0003-messaging-broker-selection.md)

---

## Verdict

**Stay on RabbitMQ. Neither Redpanda nor NATS improves this architecture; both make it worse for
reasons specific to this codebase.**

This is not a defence of RabbitMQ. The messaging layer has **five real defects** (§6). None of them
is the broker, and none is fixed by changing it. They became Phase 27.

---

## 1. What this system actually is

The framing "centered around RabbitMQ AMQP + STOMP" is wrong in a way that changes the decision.

| Measure | Value | How verified |
|---|---|---|
| Java files under `core-java/src/main` touching AMQP | **11 of 326** | `grep -rln 'org.springframework.amqp\|com.rabbitmq'` |
| `@RabbitListener` / `@RabbitHandler` declarations | **9 / 5** | `grep -rn '^\s*@RabbitListener'` |
| Code paths that publish to AMQP | **2** | `PaymentEventOutboxFlusher.java:277`, `MediaEventOutboxFlusher.java:202` |
| REST endpoints that fail if the broker is down | **0** | every producer writes an outbox row in the caller's tx |
| `edge-go` broker dependencies | **0** | `go.mod` = gin, jwt/v5, prometheus, gobreaker, swaggo, zap. Grep run with a control to prove it can match |
| Frontend components consuming STOMP | **1** | `frontend/app/dashboard/kitchen/page.tsx:324` |

This is a **synchronous REST/JPA monolith with a transactional outbox in front of an async
side-effect bus**. `OrderEventPublisher.java:36` states the contract explicitly: *"callers still
don't see AMQP exceptions — the outbox INSERT is local to the DB."*

**The architectural consequence is decisive: the broker is already a commodity in this design.** The
outbox is where reliability lives, and it would work identically over Kafka, NATS or SQS. That is an
argument for *not touching it*.

### 1.1 Topology census (as at 2026-07-26)

- **8 exchanges**: `order.events`, `payment.events`, `onboarding.events`, `media.events` (topic) +
  4 fanout DLXs. Plus `amq.topic` used implicitly by the STOMP relay.
- **13 queues**: 9 consumed, **4 DLQs with no consumer**.
- **5 payload types / 13 concrete routing keys**: `OrderStateChangeEvent` (8 keys),
  `PaymentEvent` (2), `RefundEvent` (3 variants, 1 key), `OnboardingStateChangeEvent` (1),
  `MediaProcessingEvent` (1).
- **Two real-time transports, not redundant**: STOMP relay → KDS only; SSE (`/api/v1/orders/stream`,
  per-replica `AnonymousQueue` fan-out) → order dashboards. A relay outage darkens the KDS; SSE is
  unaffected.
- All of it is defined in one file: `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java`
  (412 lines, ~40 `@Bean`s).

---

## 2. Where the external analysis was wrong

Thirteen claims were checked adversarially. Four are materially false; these are the ones that
change the answer.

### 2.1 "NATS is Go-native — perfectly matches your edge-go component. Integration would be seamless."

**FALSE, and it is the load-bearing premise of the entire recommendation.**
`edge-go/go.mod` declares gin, `golang-jwt/jwt/v5`, prometheus, `sony/gobreaker`, swaggo, zap.
Nothing else. A case-insensitive grep of the whole module for `amqp|rabbit|nats|kafka|stomp` returns
zero matches (verified with a control grep to prove the search works).

edge-go proxies exactly **two** business routes — `POST /api/v1/sync/batch` and the WhatsApp webhook
— over HTTP through a circuit breaker. Adopting NATS there **adds** a broker dependency to a service
that has none. Strip this premise and "unify your Go and Java components" has nothing to unify.

### 2.2 "Replace MediaEventOutboxFlusher with Debezium CDC, eliminating the flusher and improving database performance."

**FALSE on all three sub-claims. The third is dangerous.**

- Debezium's own docs: the Outbox Event Router *"should capture changes that occur in an outbox table
  only."* **CDC *is* the outbox pattern.** It replaces the poller, not the table.
- The flusher does five things CDC does not: exponential backoff, `MAX_ATTEMPTS=10`, the resurrect
  pass, poison detection, and per-tenant GUC pinning. These are the documented output of the Issue
  #93 (V46) reliability hardening. Deleting the flusher deletes them.
- **PostgreSQL logical decoding bypasses row-level security.** RLS is applied by the planner at
  query-rewrite time; WAL decoding never reaches the planner.

**Verified by experiment**, because published secondary sources contradict each other and the
PostgreSQL docs are ambiguous. On `postgres:15-alpine` with `wal_level=logical`, table with
`ENABLE` + `FORCE ROW LEVEL SECURITY` and policy `USING (tenant_id = 'A')`, role
`LOGIN REPLICATION NOSUPERUSER NOBYPASSRLS`:

| Arm | Command | Result |
|---|---|---|
| **Control** (proves the policy is real and *can* filter) | `SELECT * FROM orders` | **1 row** — tenant A only |
| **Test** | `pg_logical_slot_peek_changes('s1', NULL, NULL)` | **3 rows** — all tenants, including those marked `SHOULD-BE-HIDDEN` |

> Nuance worth carrying: the **initial snapshot** in logical *replication* is query-based, so RLS
> does apply there. It is the **streaming change feed** that does not. PostgreSQL's own
> §29.11 line — *"If the role lacks SUPERUSER and BYPASSRLS, publisher row security policies can
> execute"* — refers to the query-based path, not to WAL decoding.

This system has **93 RLS policies across 46 FORCE-RLS tables**, and the tenant identity lives
entirely in the session GUC `app.current_tenant_id`, **which is never written to the WAL**. Putting
Debezium in front of that database installs the first-ever cross-tenant reader underneath the whole
isolation model — directly contradicting the recorded position that there is no platform-operator
identity and all admin is tenant-scoped.

The only documented mitigation is a **publication row filter** (`CREATE PUBLICATION … WHERE
(tenant_id = 'A')`), verified working — but it is a *static per-publication* clause, so per-tenant
filtering means one publication and one replication slot per tenant. That does not scale and
reintroduces the `max_replication_slots` ceiling.

Also: `wal_level=logical` is not set anywhere in this repo. This would not be a refactor.

### 2.3 "NATS Accounts perfectly align with your RLS/TenantContext philosophy."

**Inverted.** Broker-level isolation walls off *untrusted subscribers*. Every AMQP consumer here is
the **same core-java JVM** that already pins the tenant GUC before its first query
(`OrderStateChangeListener.java:84-91`, `MediaEventOutboxFlusher.java:133-149`,
`WebhookFanoutListener.java:39-52`). There is no adversary at that boundary.

Tenant creation is one `save()` in `TenantLifecycleService.java:77-89`. Per-tenant NATS accounts
would turn it into a distributed transaction across Postgres and a JWT-signing key custodian. There
is existing scar tissue from exactly this shape: V49 had to make Keycloak deprovisioning
best-effort-after-commit so an IdP outage could not roll back an offboard.

**And the external-consumer seam is already solved correctly**: `WebhookFanoutListener` fans out to
vendors as HMAC-signed HTTPS POSTs. Third parties never touch the broker.

### 2.4 "Ops Overhead: RabbitMQ Moderate (Erlang/JVM)."

**FALSE.** RabbitMQ runs on the Erlang **BEAM** VM and never on the JVM. A small error, but it
indicates the comparison table was pattern-matched rather than known.

### 2.5 Unverifiable claims presented as fact

- *"RabbitMQ's performance ceiling is lower"* — **no measurement exists in this repo.**
  `docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md:134` (P3-13): *"No load-test baseline of any kind
  … every peak-load claim is untested."*
- *"Erlang-based, harder to debug at resource limits"* — **zero** RabbitMQ resource/memory/
  flow-control incidents anywhere in `docs/`, `.planning/` or `CHANGELOG.md`. The one real broker
  defect (#266) was a **destination-grammar** bug. NATS subjects are dot-separated with their own
  token rules, so a migration *reproduces* that bug class rather than fixing it.

---

## 3. Redpanda assessed against this system

| Dimension | Finding |
|---|---|
| **Licence** | BSL 1.1 (Licensor: Redpanda Data, Inc.), converts to Apache-2.0 after 4 years. Source-available, **not open source**. The Additional Use Grant forbids use for a commercial offering where third parties *"directly or indirectly"* cause topic creation. Internal infra is probably fine; a per-tenant-topic design walks toward the prohibited zone. **Legal question, not technical.** |
| **Multi-tenancy** | **No primitive at all** — no namespace, no virtual cluster. Isolation = topic prefixes + ACLs, identical to vanilla Kafka. **RBAC is enterprise-licensed.** Schema Registry "Contexts" isolate schemas/subjects only, not topics or data. |
| **Browser push** | **None.** pandaproxy (:8082) is REST poll-only. The KDS would need a WebSocket gateway built and operated — the job the RabbitMQ STOMP relay does today for free. |
| **Kafka compat** | 0.11+ with exceptions. Notably **KIP-890 not implemented** (Kafka 4.x clients fall back to V1 transactions); one SASL/SCRAM mechanism per user; no per-user quotas. |
| **The actual pitch (replay)** | Real — but not needed here, and if it ever is, **RabbitMQ 4.x Streams** provides a replayable append-only log with broker-side offset tracking on the broker already running. That is an upgrade, not a migration. |

**Net: strictly worse for this system.**

---

## 4. NATS assessed against this system

Genuine positives, stated fairly: Apache-2.0, single binary, ~40 MiB idle, **native WebSocket** with
an official `nats.ws` browser client, and live per-tenant account provisioning via the NATS-based
resolver with no restart. Greenfield and Go-first, it would be a strong pick.

Against this codebase:

- **No STOMP. None.** Confirmed by two NATS maintainers including Derek Collison — there is no
  official STOMP bridge. The entire real-time transport (`@stomp/stompjs 7.3`,
  `enableStompBrokerRelay`, `StompDestinations`, `TenantChannelInterceptor`) is rewritten, not
  ported. MQTT is capped at **3.1.1**; RabbitMQ ships MQTT **5.0**.
- **The tenant wall moves onto the critical path.** With `nats.ws` the browser talks to the broker
  *directly*, deleting `TenantChannelInterceptor` — the component enforcing tenant-position parsing
  and the CR-02 shop grant-check on every SUBSCRIBE. That authorization would be rebuilt as
  per-connection NATS subject permissions, i.e. the account-provisioning problem, now blocking every
  kitchen screen.
- **Spring integration is thin.** `io.nats:nats-spring-boot-starter` is official (nats-io org) and
  the repo is active, but it is **0.6.x**, last published to Maven Central **2025-05-27**, and it is
  a *Spring Cloud Stream binder* — this project uses plain `spring-boot-starter-amqp`. **There is no
  `@NatsListener`.** The 40-`@Bean` topology (13 queues, 8 exchanges, 16 bindings, 4 DLX/DLQ pairs,
  a `RetryOperationsInterceptor`) has no declarative equivalent; JetStream's `max_deliver` +
  advisories is a different model. The starter's README also requires connection config in
  `.properties`, not YAML — this project's entire config surface is YAML.
- **Dedup is a rolling 2-minute window** (`StreamDefaultDuplicatesWindow = 2 * time.Minute`, verified
  in nats-server source). The outbox backoff caps at 300s, so a retry landing outside the window is
  by construction not deduped — `processed_order_events` would be kept anyway.
- **Governance risk was demonstrated, not hypothetical.** April 2025: Synadia moved to relicense
  nats-server to BUSL and withdraw it from the CNCF. Resolved 2025-05-01 — trademarks assigned to
  the Linux Foundation, repos and domain retained by CNCF, **Apache-2.0 retained**. Outcome fine;
  the attempt happened.

**Costed migration:** ~100 non-doc files, **~1,100–1,400 LOC**, including a full rewrite of
`RabbitMQConfig.java`, 38 test files, four DLQ topologies, and the real-time authorization redesign
— which is unbounded from a line count and is the actual risk. For a solo developer mid-milestone.

---

## 5. On throughput — the argument that does not apply

The design point is **tens of events/sec** (`.planning/research/ARCHITECTURE.md:243`), unmeasured.

- Confluent's own (adversarial) benchmark could not push RabbitMQ past ~30k msg/s.
- RabbitMQ's own 4.0 benchmark reached 83k–112k msg/s on an Intel NUC (p50 1 ms, p99 2 ms).
- **No credible benchmark compares these three below ~1000 msg/s** — that comparison has not been
  published, because at that scale nobody cares.

This system sits 3–4 orders of magnitude below the lowest *adversarially measured* ceiling of any of
the three. **Choose on licence, protocol fit, multi-tenancy model and ops burden — not throughput.**

One honest counterpoint worth keeping: SoftwareMill's independent `mqperf` (2021) recorded RabbitMQ
at 2,064 msg/s with **18,980 ms processing latency** at low concurrency under its specific durability
config. Low load does not automatically mean low latency — *durability and replication
configuration* dominate there, which is a tuning question on any broker.

---

## 6. What is actually wrong (→ Phase 27)

Ranked by what hurts first.

| # | Defect | Evidence |
|---|---|---|
| **1** | **A broker outage >15 min destroys user uploads.** `MediaProperties.java:66` sets `reaperGraceMs = 900_000`. `MediaPendingReaper.java:78-80` then calls `storageService.deleteByKey(asset.getObjectKey())` and flips the row to FAILED ("please re-upload"). The reaper was built for a *crashed worker*; it cannot tell that from *a worker that never ran*. The outbox protects the **event**; nothing protects the **object**. | `MediaPendingReaper.java:70-90`, `MediaProperties.java:66` |
| **2** | **Running an unsupported broker.** `docker-compose.full-stack.yml:144` pins `rabbitmq:3.12-management-alpine`. Per rabbitmq.com/release-information (fetched 2026-07-26): 3.12 community support ended **2024-02-29**, commercial support ended **2025-06-30**. Current series is 4.3. Under the post-2024 policy, out-of-support series receive no patches for non-paying users. | `docker-compose.full-stack.yml:144` |
| **3** | **Messages die silently.** Four DLQs (`order.state-changes.dlq`, `payment.events.dlq`, `webhook.deliveries.dlq`, `media.process.dlq`) with **no consumer**. **No `RabbitMQDown` alert** (contrast `RedisDown` at `alerts.yml:225`). **No queue-depth alert on any AMQP queue** — the sole messaging alert watches STOMP subscription queues. The `*.outbox.dead_letter` Micrometer counters are registered and never alerted on. | `RabbitMQConfig.java`, `infra/monitoring/prometheus/alerts.yml:236-253` |
| **4** | **Consumer concurrency entirely unconfigured.** No prefetch, no `concurrentConsumers`, no `spring.rabbitmq.listener.*` anywhere. Spring defaults apply: **prefetch 250, one consumer thread per queue**. `media.process` does CPU-heavy decode/transcode — effectively **serial per replica** while holding a 250-message prefetch. | absence verified across `core-java/src` |
| **5** | **The staging/production broker is undeclared.** There is **no RabbitMQ manifest in `k8s/` at all**. `k8s/base/configmap.yaml:28` points at `rabbitmq.jtoye-infrastructure.svc.cluster.local` — a namespace with no manifest in this repo. Version, HA posture, resource footprint and owner are unknowable from here. ADR-0002 proposed an in-cluster cluster-operator deployment; it was never built. | `k8s/base/configmap.yaml:28`, ADR-0002 |

Secondary, cheap: the #266 destination-shape guard is **SUBSCRIBE-only**. `StompDestinations.kitchen()`
is the sole publisher so publishes are safe by construction today, but a future hand-built
`convertAndSend` with a slashed destination has no guard and would fail silently
(`OrderStateChangeListener.java:117-119` swallows broadcast exceptions).

---

## 7. When to revisit this decision

Revisit only when one of these becomes true. None is true today.

1. **Event replay becomes a product requirement** (vendor-visible order-history rebuild, analytics
   reprocessing) → **RabbitMQ 4.x Streams first.** Same broker, no migration.
2. **Sustained throughput exceeds ~10k msg/s** → by then it will have been measured; there is
   currently no baseline at all (P3-13).
3. **Untrusted third parties consume directly from the bus** → the only scenario where NATS
   Accounts' broker-level isolation is the right boundary. Note this is already solved differently
   and correctly via HMAC-signed HTTPS webhooks.
4. **A licence or support event forces a move** — e.g. Broadcom narrowing OSS RabbitMQ further. Track
   rabbitmq.com/release-information; this is the most plausible of the four.

---

## 8. Method and falsifiability

Every load-bearing claim here was verified directly rather than taken from a subagent report:

- `edge-go` dependency absence — grep **with a control grep** proving the pattern can match.
- Image pins (`rabbitmq:3.12-management-alpine`, `postgres:15-alpine`) — read from
  `docker-compose.full-stack.yml`.
- Listener counts (9 / 5) — anchored grep excluding comment mentions.
- `reaperGraceMs = 900_000` — read from `MediaProperties.java:66`.
- RabbitMQ support dates — fetched live from rabbitmq.com/release-information.
- RLS vs logical decoding — **run as a two-arm experiment with a control that proves the RLS policy
  can filter**, because a test arm alone would not prove the check was capable of failing.

Claims that could **not** be verified are recorded as such rather than smoothed over: whether this
deployment shape falls inside Redpanda's BSL prohibition (a legal question); RabbitMQ's official
single-node dev-container RAM figure (the production checklist gives both 256 MiB and 4 GiB without
reconciling them); and real adoption volume for the NATS Spring artifacts (Maven Central publishes no
download counts).

---

## Related

- [ADR-0003 — Messaging broker selection](../architecture/decisions/ADR-0003-messaging-broker-selection.md)
- [ADR-0002 — Managed vs manifest datastores](../architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md) (the undeclared-broker gap, §6 #5)
- `docs/architecture/SYSTEM_DESIGN_V2.md` §1 — canonical comms topology
- `docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md` P3-13 — the missing load baseline
- `docs/runbooks/alerts.md` — current alert coverage

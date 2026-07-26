---
phase: 27-messaging-layer-hardening
plan: 03
type: execute
wave: 1
depends_on: []
files_modified:
  - infra/monitoring/prometheus/prometheus.yml
  - infra/monitoring/prometheus/alerts.yml
  - scripts/check-alert-rules.sh
  - scripts/check-alert-metrics.sh
  - scripts/dlq-inspect.sh
  - docs/runbooks/alerts.md
  - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  - core-java/src/test/java/uk/jtoye/core/config/RabbitMQDeadLetterTopologyTest.java
  - core-java/src/test/java/uk/jtoye/core/config/RabbitMQRetryExhaustedCounterTest.java
  - .github/workflows/ci-cd.yaml
  - docs/metrics.json
autonomous: true
requirements: [MSG-03]

must_haves:
  truths:
    - "Every DLQ that fills is announced to an operator within 5 minutes, per-queue, by name (D-02/D-03)"
    - "The messaging alerts reference metric names AND label sets that are proven to exist in a live scrape, not merely to parse (D-09)"
    - "A rule whose series selector matches zero series fails an executable gate — the defect class that made StompBrokerLag permanently silent cannot recur (D-09)"
    - "A retry-exhausted message is counted even on a queue that has no DLX, so onboarding.notifications stops failing invisibly (D-05)"
    - "Every alert rule in alerts.yml has a runbook section naming an inspection command an operator can paste (D-08)"
    - "The core-java scrape target is UP on the canonical local runtime, so application-metric alerts have data (D-04)"
    - "No new HTTP surface exposes cross-tenant DLQ payloads this work-item; redrive is deferred with a named trigger (D-06)"
  artifacts:
    - path: "scripts/check-alert-metrics.sh"
      provides: "Live-Prometheus series-existence gate over every rule in alerts.yml"
      contains: "api/v1/query"
    - path: "scripts/check-alert-rules.sh"
      provides: "Static promtool + label/runbook-coverage lint, CI-wired"
      contains: "promtool"
    - path: "scripts/dlq-inspect.sh"
      provides: "Non-destructive operator DLQ triage over the management API (no new app surface)"
      contains: "reject_requeue_true"
    - path: "infra/monitoring/prometheus/alerts.yml"
      provides: "messaging_alerts group rewritten: RabbitMQDown + 5 queue-level rules on proven series"
      contains: "rabbitmq_detailed_queue_messages"
  key_links:
    - from: "infra/monitoring/prometheus/alerts.yml"
      to: "infra/monitoring/prometheus/prometheus.yml"
      via: "rabbitmq-queues scrape job supplying rabbitmq_detailed_* series"
      pattern: "metrics/detailed"
    - from: "infra/monitoring/prometheus/alerts.yml"
      to: "docs/runbooks/alerts.md"
      via: "one '## <AlertName>' heading per rule, enforced by check-alert-rules.sh"
      pattern: "DeadLetterQueueNonEmpty"
    - from: "core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java"
      to: "infra/monitoring/prometheus/alerts.yml"
      via: "jtoye.amqp.retries_exhausted counter -> jtoye_amqp_retries_exhausted_total"
      pattern: "retries_exhausted"
---

<objective>
Make a dead message impossible to lose silently.

Today four dead-letter queues fill with nobody watching, the single messaging alert in the repo is
provably incapable of firing, and the local Prometheus cannot scrape core-java at all — so the
outbox dead-letter counters that already exist have never had a consumer either. This work-item
closes the **detection** and **triage** halves (alerts on proven series + a runbook + a
non-destructive inspection script), deliberately **defers redrive**, and — the load-bearing part —
adds an executable gate that fails when an alert rule references a series that does not exist, so
the "syntactically perfect rule that can never fire" class cannot come back.

Scope boundary: this plan does not add a consumer, an HTTP endpoint, or an auth gate. It changes one
Java file (a counter in an existing `@Bean`), two monitoring config files, one runbook, and adds
three bash gates plus two unit-test files.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md

<verified_problem_statement>
Every claim below was verified against the code and against the **running** local stack on
2026-07-26. Where the briefing was wrong, the correction is marked **[CORRECTION]**.

**F-1 — Four DLQs, zero consumers. CONFIRMED.**
Declared in `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java`:
`order.state-changes.dlq` (:27, bean :98), `payment.events.dlq` (:32, bean :163),
`webhook.deliveries.dlq` (:62, bean :307), `media.process.dlq` (:78, bean :367).
`grep -rn "@RabbitListener"` over `core-java/src/main/java` returns 8 listener sites
(`OrderStateChangeListener:75`, `PaymentEventAuditListener:19`, `MediaProcessingWorker:89`,
`OrderSseFanoutListener:36`, `FinancialNotificationListener:47` and `:58`,
`OnboardingNotificationListener:42`, `OrderNotificationListener:50`, plus the class-level
`WebhookFanoutListener:52`) — **none** names a `.dlq` queue. Confirmed live against the broker:

```
webhook.deliveries.dlq   msgs=9   consumers=0
order.state-changes.dlq  msgs=0   consumers=0
payment.events.dlq       msgs=0   consumers=0
media.process.dlq        msgs=0   consumers=0
```

**There are nine real dead messages sitting on the dev broker right now.** One, peeked
non-destructively, is an `OrderStateChangeEvent` (`__TypeId__` header) dead-lettered from
`webhook.deliveries` with `x-first-death-reason: rejected`, `x-death[0].time = 1784115978`
(≈2026-07-15) — **eleven days undetected**, and its payload carries
`"tenantId":"00000000-0000-0000-0000-000000000001"`. This is the problem statement, not a
hypothetical.

The retry path is as described: `RabbitMQConfig.retryInterceptor()` (:390-399) —
`maxAttempts(3)`, `backOffOptions(1000, 2.0, 10000)`, recoverer throws
`AmqpRejectAndDontRequeueException`; `rabbitListenerContainerFactory` sets
`setDefaultRequeueRejected(false)` (:409).

**F-2 — `onboarding.notifications` has no DLX. CONFIRMED, and the reason still holds — but not for
the reason the comment gives.** `RabbitMQConfig:233-241`: the queue is
`QueueBuilder.durable(ONBOARDING_NOTIFICATIONS_QUEUE).build()` with the comment *"No DLX:
onboarding.events has none, and a repeatedly-failing best-effort notification is dropped after the
retry interceptor exhausts."* Live broker confirms `onboarding.notifications args={}`.
**[CORRECTION to the implied fix]** — do **not** "just add the DLX". `x-dead-letter-exchange` is a
queue argument; redeclaring an existing durable queue with different arguments makes the broker
reply `PRECONDITION_FAILED (406)` and kills the declaring channel, so adding it would break startup
against every broker that already has the queue (including this dev stack and any deployed
environment). Changing it requires a delete-and-redeclare (losing in-flight messages) or a new queue
name plus a rebind. That is a separate, riskier work-item. The *visibility* gap it causes is closed
here instead by D-05, which counts retry-exhaustion at the interceptor — DLX or no DLX.
Also note the second silent drop on this path: `WebhookFanoutListener:105`'s
`@RabbitHandler(isDefault = true)` swallows any unexpected payload type at `log.debug`.

**F-3 — No `RabbitMQDown`; the one messaging alert cannot fire. CONFIRMED, and worse than stated.**
`infra/monitoring/prometheus/alerts.yml:236-253` contains exactly one messaging rule,
`StompBrokerLag`, on
`sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}) > 0`.
There is no `RabbitMQDown` (contrast `RedisDown` at :225), no domain-queue depth rule, no DLQ rule.

The new finding: **the rule is vacuous by construction.** The `rabbitmq_prometheus` plugin's default
`/metrics` endpoint returns metrics *aggregated by name* — primary docs (rabbitmq.com/docs/prometheus:
*"RabbitMQ returns aggregated metrics on this endpoint by default"*), and proven on the live broker:

```
rabbitmq_queue_messages_ready 9        <- no vhost label, no queue label, node-wide sum
```

and confirmed inside Prometheus itself:

```
query=rabbitmq_queue_messages_ready
  -> {job="rabbitmq", instance="jtoye-rabbitmq:15692", ...} = 9
query=sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}) > 0
  -> {"resultType":"vector","result":[]}
```

A `queue=~` selector against a series that has no `queue` label matches nothing. The alert has
never been able to fire, and the 9 stuck messages it would arguably have caught are the proof.
`promtool check rules` passes this file — syntax validity is exactly the assurance that is worthless
here. Per-queue labels require either `prometheus.return_per_object_metrics = true`,
`GET /metrics/per-object`, or `GET /metrics/detailed?family=...` (`rabbitmq_detailed_` prefix), all
verified live (see `<interfaces>`).

**F-4 — Outbox dead-letter counters exist, are never alerted on. CONFIRMED, with names verified in a
live scrape.** `PaymentEventOutboxFlusher.java:94-103` registers `payment.outbox.dead_letter` and
`payment.outbox.resurrected`, incremented at `:293` (poison / `JsonProcessingException`) and `:304`
(`attempts >= MAX_ATTEMPTS`, `MAX_ATTEMPTS = 10` at :59). `MediaEventOutboxFlusher.java:84-92`
mirrors it, incremented at `:218` / `:227`. Live `curl :9090/actuator/prometheus` proves all four
exist and are **eagerly registered** (present at 0.0 before any increment, so an alert on them is
not dark until first failure):

```
payment_outbox_dead_letter_total 0.0     payment_outbox_resurrected_total 0.0
media_outbox_dead_letter_total   0.0     media_outbox_resurrected_total   0.0
```

`grep -rn alerts.yml` for any of those four names: zero hits.
**`poison=true` rows are surfaced nowhere.** `poison` appears only in the two entities
(`PaymentEventOutbox.java:81`, `MediaEventOutbox.java:77`), the two repositories' resurrection
queries as `AND poison = FALSE` (`PaymentEventOutboxRepository.java:60`,
`MediaEventOutboxRepository.java:54`), and the flushers. No repository count method, no metric, no
endpoint, no log line after the initial `log.error`. A poisoned row is invisible forever the moment
its log line rotates out.

**F-5 — Two halves, both partly [CORRECTION].**
(a) *Webhook delivery failures DO have visibility* — the briefing implies none.
`WebhookDeliveryController` (`/api/v1/webhooks/{subscriptionId}/deliveries`) serves a paged,
tenant-scoped, status-filterable delivery log **and** a `POST /{deliveryId}/replay` with an
`Idempotency-Key` contract. `WebhookDeliveryWorker` auto-pauses a subscription after N consecutive
failures (`:230`) and logs `event=webhook_delivery_failed` (`:237`). What it lacks is a *metric*
(no `MeterRegistry` in the class) — so failures are visible per-tenant on demand, never proactively.
And critically: the gap that produced the 9 dead messages is *upstream* of that log — the fanout
listener died before any `webhook_delivery` row existed, so the replay endpoint has nothing to
replay.
(b) *`RabbitHealthIndicator` is exposed but nothing acts on it.* No `management.health.rabbit.enabled:
false` anywhere; `spring-boot-starter-amqp` auto-configures it; `health` is exposed in every profile
(`application.yml:275` `include: health,info`; `application-prod.yml:113` adds `prometheus`). But:
`show-details: when-authorized` (`application.yml:278`) means the live unauthenticated response is
`{"status":"UP","groups":["liveness","readiness"]}` — the rabbit component is not in it; and
`/actuator/health/readiness` returns `{"status":"UP"}`, i.e. the readiness group is `readinessState`
only, so **no Kubernetes probe reacts to a broker outage**. The only consumer of the aggregate is the
compose healthcheck (`docker-compose.full-stack.yml:255`, `curl -f .../actuator/health`), and
`restart: unless-stopped` does nothing with `unhealthy`. So: exposed, invisible, unused.

**F-6 — NEW DEFECT (not in the briefing): the core-java scrape target is DOWN on the canonical local
runtime, so every application-metric alert is dark.**

```
job=core-java  health=down  url=http://core-java:9091/actuator/prometheus
  lastError: dial tcp 172.18.0.2:9091: connect: connection refused
```

`prometheus.yml` scrapes port **9091** (correct for the *prod* profile, which moves actuator to
`MANAGEMENT_SERVER_PORT:9091` — `application-prod.yml:107`). The compose stack runs
`SPRING_PROFILES_ACTIVE=dev` with `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus`
and **no** separate management port, so the endpoint is on **9090** — verified
`curl :9090/actuator/prometheus -> 200`, 1132 lines. In compose, `9091` is merely the *host* port of
a second `--scale` replica (`docker-compose.full-stack.yml:251`, `"9090-9091:9090"`), not a container
port. Consequence: `HighErrorRate`, `HighResponseTime`, `DatabaseConnectionPoolExhausted`,
`HighMemoryUsage`, `FrequentGarbageCollection`, `NoOrdersCreated`, `TenantIsolationFailure`,
`PaymentFailureSpike` — and the outbox alerts this plan adds — all have **zero data locally**.
`ServiceDown{job="core-java"}` is firing right now and has been treated as background noise.

**F-7 — NEW, minor: duplicate near-identical counters.** The live scrape carries both
`jtoye_payment_failed_total` (used by `PaymentFailureSpike`) and `jtoye_payments_failed_total`
("Total payment failures", referenced by nothing). Not this plan's job to fix; recorded so the
D-09 gate's output is not misread as a false positive, and logged to `deferred-items.md`.

**F-8 — There is no CI validation of `alerts.yml` at all.** `grep -rn "promtool\|alerts.yml\|amtool"
.github/workflows/ scripts/` returns zero hits. And `k8s/` deploys no monitoring stack — Prometheus,
Alertmanager and Grafana exist only in `infra/monitoring/docker-compose.monitoring.yml`. So this
plan's alerting layer is compose-scoped by construction; k8s alerting is a separate, already-known
gap (record N/A, do not silently widen scope).
</verified_problem_statement>

<interfaces>
<!-- Everything here was read out of the running system, not inferred. Implement as stated. -->

**I-1 — RabbitMQ per-queue series (verified live, `rabbitmq:3.12-management-alpine`, plugin 3.12.14).**
`infra/rabbitmq/enabled_plugins` = `[rabbitmq_management,rabbitmq_management_agent,rabbitmq_prometheus,rabbitmq_stomp]`;
`rabbitmq-diagnostics listeners` confirms `[::]:15692 http/prometheus`.

| endpoint | series shape | size |
|---|---|---|
| `/metrics` (current job) | `rabbitmq_queue_messages_ready 9` — **no labels** | 3129 lines |
| `/metrics/per-object` | `rabbitmq_queue_messages_ready{vhost="/",queue="webhook.deliveries.dlq"} 9` | 3471 lines |
| `/metrics/detailed?family=queue_coarse_metrics` | `rabbitmq_detailed_queue_messages_ready{vhost="/",queue="…"} 9` | **73 lines** |

`family=queue_coarse_metrics` yields exactly `rabbitmq_detailed_queue_messages_ready`,
`rabbitmq_detailed_queue_messages_unacked`, `rabbitmq_detailed_queue_messages`,
`rabbitmq_detailed_queue_process_reductions_total` (+ `rabbitmq_build_info`,
`rabbitmq_identity_info`, `telemetry_*`). Adding `&family=queue_consumer_count` adds
`rabbitmq_detailed_queue_consumers{vhost,queue}` — verified `webhook.deliveries.dlq`=0,
`order.state-changes`=1.

**I-2 — the 13 live queues** (names the regexes must handle): `order.state-changes`,
`order.state-changes.dlq`, `order.state-changes.sse.<base64>` (anonymous, auto-delete, per-JVM),
`order.notifications`, `onboarding.notifications`, `payment.events`, `payment.events.dlq`,
`payment.notifications`, `refund.notifications`, `webhook.deliveries`, `webhook.deliveries.dlq`,
`media.process`, `media.process.dlq`. No `stomp-subscription-*` / `amq.gen-*` queue exists while no
KDS client is subscribed — so the existing rule's regex is *doubly* empty today.

**I-3 — DLQ message anatomy** (peeked with `ackmode=reject_requeue_true`, non-destructive):
```json
{"properties":{"delivery_mode":2,"content_type":"application/json","headers":{
   "__TypeId__":"uk.jtoye.core.order.OrderStateChangeEvent",
   "x-death":[{"count":1,"exchange":"order.events","queue":"webhook.deliveries",
               "reason":"rejected","routing-keys":["order.state.pending"],"time":1784115978}],
   "x-first-death-exchange":"order.events","x-first-death-queue":"webhook.deliveries",
   "x-first-death-reason":"rejected"}},
 "routing_key":"order.state.pending","exchange":"webhook.deliveries.dlx",
 "payload":"{\"orderId\":\"…\",\"tenantId\":\"00000000-0000-0000-0000-000000000001\",…}"}
```
Redrive target = `x-death[0].exchange` + `x-death[0].routing-keys[0]`, **not** the DLQ's own
`exchange` field (which is the DLX). Tenant identity lives in the JSON body, not in a header — so
any redrive must parse the payload to know whose data it is handling. That single fact is the whole
argument in D-06.

**I-4 — management API access.** Port 15672 IS host-published
(`docker-compose.full-stack.yml:152`); **15692 is not** (`ports:` lists only 5672/15672/61613).
Credentials: `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` from `.env`. The rabbitmq container
has **no `curl`**, only busybox `wget`, and `wget http://localhost:15692` inside it is refused
(listener is `[::]`); scrape from a peer container on `jtoye-network` instead —
`docker exec jtoye-prometheus wget -qO- http://jtoye-rabbitmq:15692/metrics` works and is the
command the runbook must carry.

**I-5 — Micrometer → Prometheus naming, all verified in the live `:9090/actuator/prometheus`:**
`payment.outbox.dead_letter` → `payment_outbox_dead_letter_total`; `media.outbox.dead_letter` →
`media_outbox_dead_letter_total`; likewise `*_resurrected_total`. `tenant_context_missing_total` and
`jtoye_payment_failed_total` also exist (so those two rules are name-valid; only their *scrape* is
broken, per F-6).

**I-6 — house style for the bash gates:** copy `k8s/scripts/check-connection-math.sh` /
`scripts/check-runtime-freshness.sh` — `set -euo pipefail`, `SCRIPT_DIR`/`REPO_ROOT` from
`$BASH_SOURCE`, `fail()`/`void()` helpers, **exit 0 clean / 1 violation / 2 VOID** (missing tooling,
unreachable service, or an empty discovery result — "found nothing" is never "clean"), final
one-line `PASS:` summary.

**I-7 — pipefail trap, mandatory.** `cmd | grep -q X` under `set -o pipefail` **inverts on match**
(grep exits early → SIGPIPE → 141). Every match in these scripts must use a here-string:
`grep -q X <<<"$out"`. This exact bug already made one repo guard fail open.

**I-8 — build layout.** Gradle build dir is redirected: `core-java/build.gradle.kts:15`
`layout.buildDirectory.set(file("build-local"))`. **`core-java/build/` is stale — never read it.**
Unit tests live in `:core-java:test`; `integrationTest` is a separate task
(`build.gradle.kts:126`) sharing the same source set with `excludeTags("testcontainers")`. Run
`./gradlew :core-java:cleanTest :core-java:test` — without `cleanTest`, Gradle reports
`UP-TO-DATE` and executes nothing.

**I-9 — docs-freshness.** `scripts/docs-freshness.sh:46` counts literal `@Test\b` in
`core-java/src/test/**`. New Java tests move `java_test_methods` / `java_test_files` /
`total_logical_invocations` in `docs/metrics.json`; the `docs-freshness` CI gate fails until
`scripts/docs-freshness.sh --write` is run. Bash and YAML contribute 0. Baseline today:
`java_test_methods=1157`, `java_test_files=203`, `total_logical_invocations=1736`.
`docs/metrics.json` is a known merge-conflict hotspot — reconcile with `--write`, never by hand.
</interfaces>

<decisions>

**D-01 — Get per-queue labels via a second scrape job on `/metrics/detailed`, not by switching the
broker to per-object mode.**
Add job `rabbitmq-queues` → `metrics_path: /metrics/detailed`,
`params: {family: [queue_coarse_metrics, queue_consumer_count]}`, same target
`jtoye-rabbitmq:15692`. Trade-offs weighed:

| option | cost | why rejected / chosen |
|---|---|---|
| `prometheus.return_per_object_metrics = true` | new `rabbitmq.conf` mount + broker restart; **replaces** the aggregated series | rejected: a broker restart to fix a monitoring gap, and it silently deletes `rabbitmq_queue_messages_ready` (unlabeled), breaking any rule written against it |
| `metrics_path: /metrics/per-object` on the existing job | 3471 lines/15s, grows with connections+channels; same silent replacement | rejected: unbounded cardinality for 13 queues' worth of signal |
| **second job on `/metrics/detailed`** | 73 lines/15s; distinct `rabbitmq_detailed_` prefix so it cannot collide; no broker change | **chosen**: bounded, additive, reversible, and the docs explicitly design the prefix for concurrent use |

Cost admitted: a second job means a second `up{job="rabbitmq-queues"}` series, which the existing
`ServiceDown` catch-all covers — accept, and say so in the runbook so nobody reads it as a new fault.

**D-02 — Rewrite `StompBrokerLag` onto `rabbitmq_detailed_queue_messages_ready`, and report it as a
correction, not a tweak.** The rule was never capable of firing (F-3). Fixing it silently would be
exactly the "substitute a weaker form and report the vacuous pass" failure this project fights. The
SUMMARY must state: *this rule was vacuous from its introduction; here is the empty-vector proof;
here is the corrected rule firing.*

**D-03 — Six messaging rules, all on series proven to exist.** Full group in Task 2. Severity split
is deliberate: `payment.events.dlq` is money, so it gets its own `critical`/`for: 1m` rule and is
*excluded* from the generic warning rule (`queue!="payment.events.dlq"`) so the two can never
double-page. The backlog and consumer rules use **negative** selectors
(`queue!~".*\\.dlq|order\\.state-changes\\.sse\\..*"`) rather than an allowlist of the 13 current
queues, so a queue added in a later phase is covered the day it exists — an allowlist would rot
silently, which is the same class of defect as the one being fixed.

**D-04 — Fix the core-java scrape port to 9090 and correct the stale comment.** `infra/monitoring/`
is scoped to `docker-compose.full-stack.yml`, which is `SPRING_PROFILES_ACTIVE=dev` by definition
(CLAUDE.md: compose is the canonical local runtime; k8s ships no Prometheus). Prometheus has no
env-var substitution — the repo already had to solve that for Alertmanager with a
template+`sed` entrypoint, and dragging that machinery in for one port is disproportionate. So:
hardcode 9090, and replace the `issue #98` comment with one that states *both* ports and which
profile serves which, so a future prod-profile compose run is a comment lookup rather than a
re-diagnosis. Without this, **every alert this plan adds on a core-java counter would be dark on
delivery** — shipping alerts into a down scrape target is precisely a green-by-construction ship.

**D-05 — Count retry exhaustion at the interceptor, tagged by queue.** One counter,
`jtoye.amqp.retries_exhausted` (`→ jtoye_amqp_retries_exhausted_total`), incremented in the existing
`retryInterceptor()` recoverer in `RabbitMQConfig`. This is the only change that gives visibility to
a queue with **no DLX** (`onboarding.notifications`, and the SSE fanout queue), which no queue-depth
alert can ever see because the message is dropped rather than parked. Tag `queue` is read from
`args[0]` → `Message.getMessageProperties().getConsumerQueue()`.
Cardinality guard, mandatory: the SSE queue name is `order.state-changes.sse.<random>` and changes on
**every JVM restart** — an untagged-per-instance label would grow without bound. Collapse any name
starting with `RabbitMQConfig.ORDER_EVENTS_FANOUT_QUEUE_PREFIX` to the constant literal
`order.state-changes.sse`, and a null/blank queue to `unknown`.
Chosen over a `@RabbitListener` on each DLQ (needs 4 consumers and turns parked messages into
consumed ones — destroying the very evidence an operator needs) and over per-listener counters
(8 edit sites vs 1, and misses future listeners).

**D-06 — Redrive is DEFERRED. This is the recommendation, not a scope compromise.**
Cheapest sufficient scope for "messages die silently and nobody is told" is *tell somebody* +
*give them a way to look*. Redrive answers a different question ("put it back"), which nothing has
yet asked in anger. Against building it now:
- **An automatic redrive consumer re-poisons by construction.** The message is in the DLQ *because*
  a deterministic handler failed it 3×; republishing to `x-death[0].exchange` re-enters the same
  handler, fails again, and returns — a hot loop bounded only by `x-death.count`, which nothing
  reads. Any auto-redrive would need a poison-detector, which is a bigger design than the alerting.
- **A manual admin endpoint collides with an architectural fact: this platform has no cross-tenant
  operator identity** (all admin is tenant-scoped — `@PreAuthorize("hasRole('admin')")` in
  `TenantAdminController`, `GdprController`, `RefundController` all resolve *within* a tenant). A
  DLQ is inherently cross-tenant: one queue, every tenant's payloads, and AMQP basic-get is
  FIFO — you **cannot** selectively fetch "only my tenant's messages". Serving a tenant-scoped
  inspection endpoint would mean pulling other tenants' payloads into the JVM and filtering, i.e.
  building a cross-tenant read path with no identity authorised to use it.
- **The recorded `@PreAuthorize` trap makes the endpoint disproportionately expensive.** Adding a
  scope gate has twice silently reddened existing `integrationTest` classes that a per-plan executor
  never runs (Phase 25: 10 failures across 4 classes, caught only by the orchestrator's full-suite
  run). A new gated endpoint therefore costs a full `:core-java:test :core-java:integrationTest`
  (~40 min) plus a blast-radius grep — for a capability nobody has yet needed twice.

**Deferred with a named trigger, not "later":** build redrive when either (a) a
`DeadLetterQueueNonEmpty` alert has fired 3+ times in one quarter, or (b) the manual
`rabbitmqadmin`/shovel procedure in the runbook has actually been executed twice. Record in
`.planning/phases/27-*/deferred-items.md` together with the four constraints already established
here (redrive target = `x-death[0]`; tenant identity is in the body; poison-loop detector required;
no operator identity exists) so the follow-up starts from evidence rather than rediscovering it.
What ships instead: `scripts/dlq-inspect.sh` — an **operator CLI** against the management API using
broker credentials, which are already operator-only. No new HTTP surface, no new auth gate, no
integrationTest blast radius, and it works when core-java is the thing that is down.

**D-07 — Do NOT add a DLX to `onboarding.notifications` in this work-item.** Redeclaring a durable
queue with new arguments is `PRECONDITION_FAILED (406)` (F-2) — it would break boot against every
existing broker. The visibility gap is closed by D-05 instead. Record the queue-migration option
(new queue name + rebind + drain) in `deferred-items.md`.

**D-08 — Every rule gets a runbook section, enforced by a lint.** `docs/runbooks/alerts.md` today
has a filled `ServiceDown` section and TODO stubs for 8 others; `StompBrokerLag` has **no heading at
all**. The lint asserts a `## <AlertName>` heading exists for every `- alert:` in `alerts.yml`
(presence, not prose — an executable rule must assert something objective). It is RED on the current
tree before any change, which is its own fail-direction evidence.

**D-09 — The load-bearing artifact: a live series-existence gate, split from the static gate.**
Two scripts, mirroring the existing runtime-parity precedent exactly
(`check-branch-behind-base.sh` runs in CI, `check-runtime-freshness.sh` deliberately does not):
- `scripts/check-alert-rules.sh` — **static, CI-wired**: `promtool check rules`, plus every rule has
  `severity`/`component`/`service` labels and `summary`+`description` annotations, plus the D-08
  runbook-heading assertion.
- `scripts/check-alert-metrics.sh` — **live, NOT CI-wired** (a CI runner has no Prometheus, so it
  could only ever be VOID there — the same reasoning already documented for
  `check-runtime-freshness.sh`): for each rule, strip comparisons/aggregations down to the bare
  **series selectors** (`name{labels}`), query each against `/api/v1/query`, and require ≥1 series.
  Querying the *selector* rather than the whole expression is the entire point: `X > 100` legitimately
  returns empty on a healthy system, while `X{queue="…"}` returning empty means the rule is
  incapable of firing. **Checking the label set, not just the metric name, is what catches the actual
  defect found here** — `rabbitmq_queue_messages_ready` exists; `rabbitmq_queue_messages_ready{queue=~…}`
  does not. Exit 2 (VOID) on unreachable Prometheus, absent `promtool`/`jq`, or zero extracted
  selectors. Commented-out rules (`node_filesystem_*`) are not parsed; a small, reasoned
  `EXPECT_EMPTY` allowlist is permitted but each entry needs a non-empty reason and a stale entry
  (now non-empty) must FAIL, same hygiene rule as `check-env-contract.sh`.

**D-10 — Handle the 9 real dead messages as the first live exercise of the runbook, not as
cleanup.** Archive the payloads to a **gitignored** scratch file (they carry `tenantId`, order ids
and order numbers — never commit them), determine the failure from `x-death` + the core-java logs
around 2026-07-15, record the finding in the SUMMARY, then purge and re-fire deliberately (Task 6).
Purging first would destroy the only real firing input available.
</decisions>
</context>

<tasks>

<task type="auto">
  <name>Task 1: prometheus.yml — fix the dark core-java target, add the per-queue scrape job</name>
  <files>infra/monitoring/prometheus/prometheus.yml</files>
  <read_first>
    - infra/monitoring/prometheus/prometheus.yml (the `core-java` job's `issue #98` comment — it is the stale claim to correct, and the `rabbitmq` job at the end)
    - docker-compose.full-stack.yml lines 143-164 (rabbitmq ports: 15692 NOT published) and 165-263 (core-java: SPRING_PROFILES_ACTIVE=dev, `"9090-9091:9090"`)
    - core-java/src/main/resources/application-prod.yml lines 96-114 (why 9091 is right for prod and wrong here)
  </read_first>
  <action>
Two edits, both additive in effect:

1. **`core-java` job** — change the target to `core-java:9090`. Replace the existing comment with
   one that states the split honestly: the dev profile (which `docker-compose.full-stack.yml` runs)
   serves actuator on the app port 9090 with `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus`;
   the prod profile moves it to `MANAGEMENT_SERVER_PORT:9091`; this file scrapes the compose stack,
   so 9090 is correct, and a compose run switched to the prod profile must move it back. Do not
   change the `relabel_configs` block or the `service`/`component` labels.

2. **New `rabbitmq-queues` job** immediately after the existing `rabbitmq` job (leave that job
   untouched — it supplies node-level series):
   ```yaml
   - job_name: 'rabbitmq-queues'
     metrics_path: '/metrics/detailed'
     params:
       family: ['queue_coarse_metrics', 'queue_consumer_count']
     static_configs:
       - targets: ['jtoye-rabbitmq:15692']
         labels:
           service: 'rabbitmq'
           component: 'messaging'
   ```
   Comment it with: why a second job exists (the default `/metrics` is aggregated and carries NO
   `queue` label — the reason `StompBrokerLag` could never fire), the `rabbitmq_detailed_` prefix,
   the two families and the four+one metrics they yield, the measured payload size (73 lines vs 3471
   for `/metrics/per-object`), and that this adds a second `up{job="rabbitmq-queues"}` series
   already covered by `ServiceDown`.

Note in the comment that `/-/reload` is not enabled (existing file comment): applying requires
`docker compose -f infra/monitoring/docker-compose.monitoring.yml restart prometheus`.
  </action>
  <verify>
    <automated>docker compose -f infra/monitoring/docker-compose.monitoring.yml restart prometheus &amp;&amp; sleep 20 &amp;&amp; curl -s http://localhost:9091/api/v1/targets | jq -r '.data.activeTargets[] | "\(.labels.job)\t\(.health)"' | sort</automated>
  </verify>
  <acceptance_criteria>
    - After restart, `curl -s http://localhost:9091/api/v1/query --data-urlencode 'query=up{job="core-java"}' | jq -r '.data.result[0].value[1]'` prints `1`. **Fail direction (already observed on the pre-change tree, record it):** the same query today returns `0` and `ServiceDown{job="core-java"}` is firing with `lastError: dial tcp 172.18.0.2:9091: connect: connection refused`. Record both.
    - `curl -s http://localhost:9091/api/v1/query --data-urlencode 'query=payment_outbox_dead_letter_total' | jq '.data.result | length'` is `>= 1` — proving the outbox counters are now reachable *from Prometheus*, not merely from `curl :9090/actuator/prometheus`. This is the F-6 proof and it is the precondition for Task 2's outbox rule being anything but decorative.
    - `curl -s http://localhost:9091/api/v1/query --data-urlencode 'query=rabbitmq_detailed_queue_messages{queue="webhook.deliveries.dlq"}' | jq -r '.data.result[0].value[1]'` prints `9` (the nine real dead messages). **Fail direction:** the same query with `queue="does.not.exist"` returns `[]`, and `rabbitmq_queue_messages_ready{queue="webhook.deliveries.dlq"}` (the *old* job's series) also returns `[]` — proving the new job, not the old one, is what supplies the label. Record all three.
    - `curl -s http://localhost:9091/api/v1/query --data-urlencode 'query=count(rabbitmq_detailed_queue_consumers)' | jq -r '.data.result[0].value[1]'` is `>= 13`.
    - `curl -s http://localhost:9091/api/v1/targets | jq -r '[.data.activeTargets[] | select(.health!="up")] | length'` prints `0`.
    - Scrape-size guard: `curl -s http://localhost:9091/api/v1/query --data-urlencode 'query=scrape_samples_scraped{job="rabbitmq-queues"}' | jq -r '.data.result[0].value[1]'` is `< 200` — the bounded-cardinality claim in D-01 is measured, not asserted.
    - `git diff infra/monitoring/prometheus/prometheus.yml | grep '^-' | grep -vE '^---' | grep -cE "job_name: 'rabbitmq'|job_name: 'edge-go'|job_name: 'postgres'|job_name: 'keycloak'|job_name: 'redis'"` returns `0` — no existing job was modified or removed.
  </acceptance_criteria>
  <done>Prometheus scrapes core-java successfully, `rabbitmq_detailed_queue_messages{queue="webhook.deliveries.dlq"}` returns 9, all targets are up, and the added scrape costs under 200 samples.</done>
</task>

<task type="auto">
  <name>Task 2: alerts.yml — rewrite messaging_alerts onto proven series</name>
  <files>infra/monitoring/prometheus/alerts.yml</files>
  <read_first>
    - infra/monitoring/prometheus/alerts.yml (whole file — the `RedisDown` rule at :225 is the shape to mirror for `RabbitMQDown`; the commented-out `DiskSpaceLow` block at :174-210 is the established convention for a rule that must not be live because its series does not exist, and is the precedent for how the vacuous `StompBrokerLag` was *supposed* to have been handled)
    - infra/monitoring/prometheus/prometheus.yml (Task 1 output — the job that supplies `rabbitmq_detailed_*`)
    - the queue-name inventory in `<interfaces>` I-2
  </read_first>
  <action>
Replace the `messaging_alerts` group body. Keep the group name, `interval: 30s`, and the existing
label/annotation conventions (`severity`, `component: messaging`, `service: rabbitmq` or
`core-java`; `summary` + `description`).

1. **`RabbitMQDown`** — `up{job="rabbitmq"} == 0`, `for: 1m`, `severity: critical`. Mirror
   `RedisDown` exactly. Description must state the blast radius verified in F-1/F-5: outbox flush
   backs up (rows accumulate PENDING, backoff engages, `*_outbox_dead_letter_total` rises after
   ~20 min), KDS STOMP broadcasts stop, webhook fanout stops, media processing stops; and that
   core-java's compose healthcheck will flip `unhealthy` without anything restarting it.

2. **`PaymentDeadLetterQueueNonEmpty`** —
   `rabbitmq_detailed_queue_messages{queue="payment.events.dlq"} > 0`, `for: 1m`,
   `severity: critical`. Its own rule because a lost payment event is money.

3. **`DeadLetterQueueNonEmpty`** —
   `rabbitmq_detailed_queue_messages{queue=~".*\\.dlq", queue!="payment.events.dlq"} > 0`,
   `for: 5m`, `severity: warning`. Per-queue (no `sum()`), so `{{ $labels.queue }}` names the DLQ in
   the email. The `queue!=` exclusion is what stops rules 2 and 3 double-paging.

4. **`DomainQueueBacklog`** —
   `rabbitmq_detailed_queue_messages_ready{queue!~".*\\.dlq|order\\.state-changes\\.sse\\..*"} > 100`,
   `for: 10m`, `severity: warning`. Negative selector per D-03. Excludes the SSE fanout queue
   (fire-and-forget UI pushes, `RabbitMQConfig:121` — a backlog there is not a fault).

5. **`MessagingConsumerMissing`** —
   `rabbitmq_detailed_queue_consumers{queue!~".*\\.dlq"} == 0`, `for: 5m`, `severity: critical`.
   This is the rule that catches the failure mode no depth alert can: core-java is UP, the broker is
   UP, but a listener container has died or lost its channel, so events vanish into a queue nobody
   reads. `for: 5m` absorbs a normal restart. DLQs are excluded because zero consumers is their
   *correct* state — say so in the comment, or a future reader will "fix" it into a false positive.

6. **`OutboxDeadLetterRising`** —
   `increase(payment_outbox_dead_letter_total[15m]) > 0 or increase(media_outbox_dead_letter_total[15m]) > 0`,
   `for: 0m`, `severity: warning`, `service: core-java`. Names verified live (I-5). Description must
   distinguish the two increment paths (poison payload → permanent, never resurrected; vs
   `attempts >= 10` → resurrection pass will re-lease it) because the operator response differs
   completely, and must state that `poison=true` rows are visible **only** in the database
   (F-4) with the query in the runbook.

7. **`StompBrokerLag`** — rewrite onto
   `sum(rabbitmq_detailed_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}) > 0`,
   `for: 30s`, `severity: warning`. Keep the name so alert history is continuous. Add a comment
   recording that the previous form used the unlabeled aggregated series and **could never fire**,
   with the empty-vector proof, so nobody "simplifies" it back.

Do not touch any other group in the file.
  </action>
  <verify>
    <automated>docker run --rm -v "$PWD/infra/monitoring/prometheus:/rules:ro" prom/prometheus:v2.48.0 promtool check rules /rules/alerts.yml</automated>
  </verify>
  <acceptance_criteria>
    - `promtool check rules` reports `SUCCESS` and a rule count 6 higher than the pre-change count (capture both numbers; a bare "SUCCESS" is not evidence — it was also SUCCESS on the vacuous rule).
    - **Every one of the 7 rules' series selectors returns ≥1 series live.** For each, run `curl -s http://localhost:9091/api/v1/query --data-urlencode "query=<selector>"` and require `.data.result | length >= 1`. This is the anti-vacuity check and it must be recorded per rule, not as a single aggregate pass.
    - **`DeadLetterQueueNonEmpty` is proven FIRING on the current tree without any synthetic input** — the 9 real messages in `webhook.deliveries.dlq` satisfy it. After 5m: `curl -s http://localhost:9091/api/v1/alerts | jq -r '.data.alerts[] | select(.labels.alertname=="DeadLetterQueueNonEmpty") | "\(.state) \(.labels.queue) \(.value)"'` prints `firing webhook.deliveries.dlq 9`. **Fail direction (Task 6 supplies it):** after the DLQ is archived+purged, the same query returns nothing and the alert resolves. Record both directions with timestamps.
    - **`PaymentDeadLetterQueueNonEmpty` fire drill** (single-queue blast radius, deliberately NOT the order path — `order.state.*` is bound to four queues and would populate three DLQs at once): publish one malformed body to the media pipeline, which is bound to exactly one queue:
      ```bash
      curl -s -u "$RMQ_USER:$RMQ_PASS" -H 'content-type:application/json' \
        -X POST 'http://localhost:15672/api/exchanges/%2F/media.events/publish' \
        -d '{"routing_key":"media.process","payload":"NOT_JSON","payload_encoding":"string",
             "properties":{"content_type":"application/json",
               "headers":{"__TypeId__":"uk.jtoye.core.media.MediaProcessingEvent"}}}'
      ```
      A malformed body is a fatal `MessageConversionException`, so it dead-letters on the first
      delivery (`x-death.count=1`) rather than after 3 retries — deterministic, and sufficient to
      fill the DLQ. Confirm `DeadLetterQueueNonEmpty{queue="media.process.dlq"}` fires within 5m,
      then purge `media.process.dlq` and confirm it resolves. Record both. Separately, to exercise
      the retry-exhaustion path (3 attempts, ~11s of backoff) rather than the fatal path, publish a
      **well-formed** `MediaProcessingEvent` naming a non-existent `assetId` and confirm
      `x-death[0].count == 3` on the resulting DLQ message.
    - **`RabbitMQDown` fire drill:** `docker compose -f docker-compose.full-stack.yml stop rabbitmq`; after 1m confirm `RabbitMQDown` state `firing`; `start` it; confirm it resolves AND that core-java's listeners reconnected (`docker logs <core-java> --since 5m | grep -c "Attempting to connect\|Created new connection"` is `>= 1`). Per the project's runtime rule, `start` is correct **only because no source changed during the drill** — if any rebuild happened in between, the stack must be rebuilt, not started. State which applied.
    - **`MessagingConsumerMissing` fire drill:** `docker compose stop core-java`; after 5m confirm firing with `$labels.queue` naming at least `order.state-changes`, `payment.events`, `media.process`, `webhook.deliveries`; restart and confirm resolution. Note in the SUMMARY that this drill also fires `ServiceDown` — expected, not a defect.
    - `MessagingConsumerMissing` must NOT fire for any `.dlq` queue at any point in the drill: `curl -s http://localhost:9091/api/v1/alerts | jq -r '[.data.alerts[] | select(.labels.alertname=="MessagingConsumerMissing") | .labels.queue] | map(select(endswith(".dlq"))) | length'` prints `0`. This is the false-positive guard for the exclusion selector — and it is a real risk, since all four DLQs sit at 0 consumers permanently.
    - `git diff infra/monitoring/prometheus/alerts.yml | grep -c '^-.*alert: \(HighErrorRate\|ServiceDown\|RedisDown\|KeycloakDown\|DatabaseDown\|TenantIsolationFailure\|PaymentFailureSpike\)'` returns `0` — no existing non-messaging rule was touched.
  </acceptance_criteria>
  <done>Seven messaging rules exist, each proven to match ≥1 live series, and four of them proven to transition firing→resolved against a real or deliberately-created input.</done>
</task>

<task type="auto">
  <name>Task 3: check-alert-rules.sh (static, CI) + check-alert-metrics.sh (live, not CI)</name>
  <files>scripts/check-alert-rules.sh, scripts/check-alert-metrics.sh, .github/workflows/ci-cd.yaml</files>
  <read_first>
    - scripts/check-runtime-freshness.sh (house style AND the documented precedent for a gate that deliberately does not run in CI — copy its reasoning verbatim in the header)
    - scripts/check-branch-behind-base.sh (the CI-wired sibling; exit-code convention, VOID handling)
    - k8s/scripts/check-env-contract.sh (allowlist-hygiene pattern: reasoned entries, stale-entry FAIL)
    - .github/workflows/ci-cd.yaml (the `k8s-validate` job — the `chmod +x` + run step shape to mirror)
  </read_first>
  <action>
**`scripts/check-alert-rules.sh`** (static, CI-wired). Asserts, over
`infra/monitoring/prometheus/alerts.yml`:
- `promtool check rules` passes (via the pinned `prom/prometheus:v2.48.0` image, matching the
  deployed version — do not assume a host promtool);
- every `- alert:` carries `severity`, `component`, `service` labels and `summary` + `description`
  annotations;
- every `- alert:` has a matching `^## <AlertName>$` heading in `docs/runbooks/alerts.md` (D-08);
- the extracted alert-name count is `> 0` (VOID at exit 2 otherwise — an empty parse must never
  read as clean).
Exit 0/1/2 per I-6. All matching via here-strings (I-7).

**`scripts/check-alert-metrics.sh`** (live, **not** CI-wired). For each rule:
- strip the `expr` down to bare series selectors — remove aggregation wrappers
  (`sum(`/`count(`/`increase(`/`rate(`/`histogram_quantile(`), range selectors (`[5m]`), comparison
  operators and their right-hand literals, and arithmetic — leaving `metric_name` or
  `metric_name{label="v",label2=~"re"}` tokens;
- query each selector against `${PROM_URL:-http://localhost:9091}/api/v1/query`;
- require `.data.result | length >= 1`; report every empty one as a violation naming the alert, the
  selector, and the reason ("this rule can never fire");
- `EXPECT_EMPTY` allowlist, `SELECTOR|reason` entries, with the same hygiene as
  `check-env-contract.sh`: empty reason → FAIL, duplicate → FAIL, entry that now returns non-empty →
  FAIL as STALE;
- VOID (exit 2) if Prometheus is unreachable, `jq` is absent, or zero selectors were extracted.
Header must state why it is not in CI (a CI runner has no Prometheus, so it could only ever be VOID
there — identical reasoning to `check-runtime-freshness.sh`), and that it is the gate that would have
caught `StompBrokerLag` on the day it was written.

**CI wiring**: add `check-alert-rules.sh` only (`chmod +x` + run), mirroring the existing gate steps.
Do not wire the live gate. Do not modify any existing step or the kubectl pin.
  </action>
  <verify>
    <automated>bash scripts/check-alert-rules.sh; echo "static=$?"; bash scripts/check-alert-metrics.sh; echo "live=$?"</automated>
  </verify>
  <acceptance_criteria>
    - Both exit 0 against the post-Task-2 tree with a `PASS:` line stating the number of rules and selectors checked (a non-zero count is part of the pass — a gate reporting "0 rules checked, PASS" is the vacuous shape).
    - **The live gate is proven RED against the real historical defect — run this BEFORE Task 2's rewrite, or on a stashed copy:** point it at the pre-change `alerts.yml` and confirm exit 1 naming `StompBrokerLag` and the selector `rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}`. This is the single most important line in the plan: the gate must be shown to catch the bug that motivated it, on the real artifact, not on a synthetic one.
    - Live-gate falsifiability #2: temporarily add a rule `expr: rabbitmq_detailed_queue_messages{queue="typo.not.a.queue"} > 0`; confirm exit 1 naming it. Restore, confirm exit 0.
    - Live-gate falsifiability #3 (**label-level, the subtle one**): temporarily change one working rule's label from `queue=` to `queue_name=` — a name that exists, a label that does not. Confirm exit 1. A gate that only checked metric *names* would pass this, and that is exactly the defect found in F-3.
    - Live-gate VOID proof: `PROM_URL=http://127.0.0.1:1 bash scripts/check-alert-metrics.sh; echo $?` prints `2`, not `0` and not `1`.
    - **Static gate is RED on the pre-change tree with no deliberate break** — `StompBrokerLag` has no `## StompBrokerLag` heading in `docs/runbooks/alerts.md` today. Record that output as the natural fail direction, then confirm it goes green after Task 4 adds the heading.
    - Static gate falsifiability #2: temporarily delete the `severity` label from one rule; confirm exit 1 naming that rule. Restore, confirm exit 0.
    - Static gate VOID proof: run it against a temp copy of `alerts.yml` containing only `groups: []`; confirm exit `2` (zero alerts extracted), not 0.
    - pipefail-inversion guard: `grep -cE '\|\s*grep -q' scripts/check-alert-rules.sh scripts/check-alert-metrics.sh` returns `0` for both — every match uses a here-string. **This grep is NOT self-evidently non-vacuous** (it would also be 0 on an empty file), so additionally assert `grep -cE 'grep -q .* <<<' scripts/check-alert-metrics.sh` is `>= 1`, proving the here-string form is actually present.
    - `bash -n` exits 0 and `test -x` succeeds for both scripts.
    - `grep -c 'check-alert-rules.sh' .github/workflows/ci-cd.yaml` is `>= 1`; `grep -c 'check-alert-metrics.sh' .github/workflows/ci-cd.yaml` returns **0** (the live gate is deliberately not CI-wired — assert the absence so a future edit that wires it up fails review).
    - `git diff .github/workflows/ci-cd.yaml | grep '^-' | grep -vE '^---' | wc -l` returns `0` — additions only.
  </acceptance_criteria>
  <done>A rule referencing a non-existent metric OR a non-existent label fails an executable gate; both gates are proven RED on the real pre-change tree, and both fail closed at exit 2 rather than passing when they cannot check.</done>
</task>

<task type="auto">
  <name>Task 4: docs/runbooks/alerts.md — a messaging section with commands that were actually run</name>
  <files>docs/runbooks/alerts.md</files>
  <read_first>
    - docs/runbooks/alerts.md (the `ServiceDown` section is the only filled one — match its structure exactly: What it means / Expected impact / First-response steps / Escalation)
    - `<interfaces>` I-3 and I-4 above (the DLQ anatomy and the access facts every command depends on)
  </read_first>
  <action>
Add a `## Messaging` block with one section per new/changed rule, in `ServiceDown`'s structure. Every
command must be one that was actually executed during this work-item — no invented flags.

Shared **inspection path** (state it once, reference it from each section):
```bash
# 0. credentials
RMQ_USER=$(grep -E '^RABBITMQ_DEFAULT_USER=' .env | cut -d= -f2-)
RMQ_PASS=$(grep -E '^RABBITMQ_DEFAULT_PASS=' .env | cut -d= -f2-)

# 1. what is where (management API, port 15672 IS host-published)
curl -s -u "$RMQ_USER:$RMQ_PASS" http://localhost:15672/api/queues/%2F \
  | jq -r '.[] | "\(.name)\tmsgs=\(.messages)\tconsumers=\(.consumers)"' | sort

# 2. peek WITHOUT consuming  (reject_requeue_true puts it straight back)
curl -s -u "$RMQ_USER:$RMQ_PASS" -H 'content-type:application/json' \
  -X POST http://localhost:15672/api/queues/%2F/<QUEUE>/get \
  -d '{"count":5,"ackmode":"reject_requeue_true","encoding":"auto","truncate":50000}' \
  | jq -r '.[] | {reason:.properties.headers["x-first-death-reason"],
                  from_exchange:.properties.headers["x-death"][0].exchange,
                  from_queue:.properties.headers["x-death"][0].queue,
                  routing_key:.properties.headers["x-death"][0]["routing-keys"][0],
                  attempts:.properties.headers["x-death"][0].count,
                  died_at:(.properties.headers["x-death"][0].time|todate),
                  type:.properties.headers["__TypeId__"]}'

# 3. the raw scrape (15692 is NOT host-published — go via a peer container)
docker exec jtoye-prometheus wget -qO- \
  'http://jtoye-rabbitmq:15692/metrics/detailed?family=queue_coarse_metrics'
```
Call out explicitly: `ackmode: get` **removes** the message; only `reject_requeue_true` is
non-destructive. And that the DLQ message's own `exchange` field is the **DLX** — the redrive target
is `x-death[0].exchange` + `x-death[0]["routing-keys"][0]`.

Per-alert first-response, keyed to the failure classes verified here:
- **`RabbitMQDown`** — impact list from Task 2; check `docker logs jtoye-rabbitmq`, disk (Rabbit
  blocks publishers on a disk alarm — `rabbitmq-diagnostics alarms`), then confirm core-java
  reconnected rather than assuming it did.
- **`PaymentDeadLetterQueueNonEmpty` / `DeadLetterQueueNonEmpty`** — peek → read `x-death.reason`
  (`rejected` = handler failed 3× or fatal conversion error; `expired`/`maxlen` = neither is
  configured here, so seeing one means the topology changed) → correlate `died_at` with core-java
  logs → decide *fix-then-redrive* vs *discard*. **Do not purge before archiving**: the payload is
  the only remaining copy of the event.
- **Manual redrive procedure (documented, not automated — D-06)**: shovel/`rabbitmqadmin` from the
  DLQ to `x-death[0].exchange` with the original routing key; state the three hazards in bold —
  (i) redriving before fixing the handler re-poisons immediately; (ii) the payload carries
  `tenantId` and the operator is looking at **every** tenant's data, so treat the terminal buffer as
  tenant data; (iii) `__TypeId__` must be preserved or the consumer's `@RabbitHandler` dispatch
  picks the wrong overload (or the `isDefault` sink at `WebhookFanoutListener:105`, which silently
  discards at `log.debug`).
- **`DomainQueueBacklog`** — consumer alive but slow: check `MessagingConsumerMissing` first, then
  core-java thread/DB saturation (`DatabaseConnectionPoolExhausted`).
- **`MessagingConsumerMissing`** — the "everything is UP and nothing is working" alert. Check
  core-java logs for AMQP connection loss, then `rabbitmq-diagnostics list_connections`.
- **`OutboxDeadLetterRising`** — distinguish the two paths, and give the poison-row query, with the
  RLS caveat spelled out (`payment_event_outbox` is FORCE RLS, so a plain `SELECT` as the app role
  returns **zero rows** and looks reassuring):
  ```sql
  -- as the superuser (bypasses RLS) — the only way to see all tenants at once
  SELECT tenant_id, id, event_type, attempts, poison, last_error, created_at
  FROM payment_event_outbox WHERE status = 'FAILED' ORDER BY created_at DESC LIMIT 50;
  -- as the app role: pin the tenant GUC first, or you will see nothing and believe it
  SET LOCAL app.current_tenant_id = '<tenant-uuid>';
  ```
  Same for `media_event_outbox`. State that `poison = true` rows are **never** resurrected
  (`PaymentEventOutboxRepository:60`) and need a human decision.
- **`StompBrokerLag`** — keep the existing intent; add the correction note that the previous
  expression could not fire and why (so its absence from alert history is not read as "all was well").

Also: add the missing `## StompBrokerLag` heading (D-08 lint dependency), and correct the
`ServiceDown` section's rabbitmq bullet (:46) which claims "DLQ inaccessible" — the DLQ is a durable
queue and is perfectly accessible; what stops is publishing and consuming. Finally, add a one-line
note under the header that `rabbitmq-queues` is a second scrape job on the same broker, so
`ServiceDown` can name either job for one outage. Update the `Last updated:` footer line.
  </action>
  <verify>
    <automated>bash scripts/check-alert-rules.sh</automated>
  </verify>
  <acceptance_criteria>
    - `scripts/check-alert-rules.sh` exits 0 — i.e. every rule in `alerts.yml`, including all seven messaging rules, now has a `## <AlertName>` heading. **Fail direction:** delete the `## MessagingConsumerMissing` heading, confirm exit 1 naming it, restore, confirm exit 0.
    - Every `curl`/`jq`/`docker exec` command block in the new section was executed verbatim during this work-item and its real output is pasted into the SUMMARY. A runbook command that has never been run is a guess. In particular the `reject_requeue_true` peek must be shown returning a decoded `x-death` block, and the queue-list command must be shown listing 13 queues.
    - Non-destructiveness proof for the peek command: record `messages` for the target queue immediately before and immediately after running it; both must read `9`. **Fail direction:** run the same command once with `"ackmode":"get"` against a throwaway queue created for the drill (never against `webhook.deliveries.dlq`) and show the count decrementing — proving the two ackmodes genuinely differ and that the runbook names the safe one.
    - `grep -c 'ackmode' docs/runbooks/alerts.md` is `>= 2` and `grep -c 'reject_requeue_true' docs/runbooks/alerts.md` is `>= 1`.
    - `grep -c 'x-death' docs/runbooks/alerts.md` is `>= 2` (the redrive-target rule appears in both the shared path and the redrive procedure).
    - `grep -c 'SET LOCAL app.current_tenant_id' docs/runbooks/alerts.md` is `>= 1` — the RLS caveat is the difference between "no poison rows" and "no visibility".
    - `git diff docs/runbooks/alerts.md | grep '^-' | grep -vE '^---' | grep -c 'DLQ inaccessible'` returns `1` — the one factual correction was made and nothing else was deleted; `git diff --stat` shows deletions `<= 3` lines.
  </acceptance_criteria>
  <done>An operator with no prior context can go from alert email to decoded dead message to a decision, using commands that have all been executed at least once.</done>
</task>

<task type="auto">
  <name>Task 5: retry-exhaustion counter + dlq-inspect.sh + topology tests</name>
  <files>core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java, core-java/src/test/java/uk/jtoye/core/config/RabbitMQDeadLetterTopologyTest.java, core-java/src/test/java/uk/jtoye/core/config/RabbitMQRetryExhaustedCounterTest.java, scripts/dlq-inspect.sh, docs/metrics.json</files>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java (:384-411 — the `retryInterceptor()` bean and the container factory; :24 `ORDER_EVENTS_FANOUT_QUEUE_PREFIX`)
    - core-java/src/test/java/uk/jtoye/core/config/RabbitMQConfigFanoutQueueTest.java (the exact test style: plain `new RabbitMQConfig()`, no Spring context, `@DisplayName` explaining WHY the property is the fix)
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java:79-104 (the `ObjectProvider<MeterRegistry>` + null-guard idiom to copy, so the config still works with no registry)
    - scripts/check-runtime-freshness.sh (bash house style)
  </read_first>
  <action>
**(a) Counter (D-05).** Inject `ObjectProvider<MeterRegistry>` into the `retryInterceptor()` bean
method (Spring resolves bean-method parameters — do not add a field or a constructor to a
`@Configuration` class that is also instantiated bare by the existing unit test). In the recoverer,
before rethrowing `AmqpRejectAndDontRequeueException`, increment
`Counter.builder("jtoye.amqp.retries_exhausted").tag("queue", <normalised>)`. Normalisation:
`args[0] instanceof Message m ? m.getMessageProperties().getConsumerQueue() : null`; if it starts
with `ORDER_EVENTS_FANOUT_QUEUE_PREFIX`, collapse to the literal `order.state-changes.sse`; if null
or blank, `unknown`. Extract the normalisation into a package-private `static String
normaliseQueueTag(String)` so it is unit-testable without a broker. Keep the existing `log.error` and
the rethrow byte-identical — the rethrow **is** the dead-letter mechanism; changing it silently
disables all four DLQs. Add a comment saying exactly that.

**(b) `scripts/dlq-inspect.sh` (D-06).** Read-only operator CLI over the management API:
`--list` (all queues, depth, consumers), `--peek <queue> [n]` (`reject_requeue_true` + the `x-death`
decode from Task 4), `--summary` (the four DLQ depths, one line each). Refuse to run any destructive
ackmode — hardcode `reject_requeue_true`, never accept it as an argument. VOID (exit 2) on missing
`jq`/`curl`, unreachable management API, or missing credentials; exit 1 if any DLQ is non-empty
(so it doubles as a pre-release check); exit 0 otherwise. Header must state: this is deliberately a
CLI and not an HTTP endpoint (D-06 rationale, one sentence), and that its output contains **every
tenant's** payloads and is therefore tenant data — do not paste it into a shared channel or a PR.

**(c) Tests.**
- `RabbitMQDeadLetterTopologyTest` — for each of the four `x-dead-letter-exchange`-carrying queues,
  assert the argument's value equals the intended DLX constant, and that each DLQ bean is durable and
  carries **no** `x-dead-letter-exchange` of its own (a DLQ that dead-letters is a loop). Assert
  `onboardingNotificationsQueue()` has **no** `x-dead-letter-exchange` — pinning F-2/D-07 as a
  deliberate, documented state rather than an oversight a future reader "fixes" into a 406.
- `RabbitMQRetryExhaustedCounterTest` — drive `normaliseQueueTag` across: a plain queue name
  (passthrough), a `order.state-changes.sse.<random>` name (collapses to the constant — the
  cardinality guard), `null`, `""`. Then build the interceptor against a
  `SimpleMeterRegistry`, invoke the recoverer, assert it throws
  `AmqpRejectAndDontRequeueException` **and** that `jtoye.amqp.retries_exhausted` incremented by 1
  with the expected `queue` tag. Also assert the interceptor still builds and the recoverer still
  throws when the registry is absent (`ObjectProvider` empty) — the null-guard path.

**(d)** Run `scripts/docs-freshness.sh --write` and commit `docs/metrics.json` in the same change
(I-9).
  </action>
  <verify>
    <automated>./gradlew :core-java:cleanTest :core-java:test --tests '*RabbitMQ*' &amp;&amp; bash scripts/dlq-inspect.sh --summary; echo "inspect=$?"</automated>
  </verify>
  <acceptance_criteria>
    - `./gradlew :core-java:cleanTest :core-java:test --tests '*RabbitMQ*'` passes and the console shows tests **executed**, not `UP-TO-DATE`. Assert on the live report dir: `grep -c 'testcase' core-java/build-local/test-results/test/TEST-uk.jtoye.core.config.RabbitMQRetryExhaustedCounterTest.xml` is `>= 5`. **Note `build-local`, not `build`** (I-8) — reading `core-java/build/` returns a stale artifact and is one of this repo's recorded vacuous shapes.
    - Counter falsifiability: temporarily change the recoverer to skip the increment; confirm `RabbitMQRetryExhaustedCounterTest` FAILS with the counter-value assertion (paste the failure). Restore, confirm green.
    - Cardinality-guard falsifiability: temporarily make `normaliseQueueTag` a passthrough; confirm the SSE-name case FAILS. Restore.
    - Topology-test falsifiability: temporarily add `.withArgument("x-dead-letter-exchange", DLX_EXCHANGE)` to `onboardingNotificationsQueue()`; confirm `RabbitMQDeadLetterTopologyTest` FAILS naming that queue. Restore. (This is the D-07 pin: the test's job is to make the *absence* deliberate.)
    - Dead-letter mechanism untouched: `git diff core-java/.../RabbitMQConfig.java | grep '^-' | grep -vE '^---' | grep -c 'AmqpRejectAndDontRequeueException\|setDefaultRequeueRejected\|maxAttempts(3)'` returns `0`.
    - **End-to-end counter proof on the running stack** (a passing unit test does not prove the wiring): rebuild and restart core-java (`docker compose -f docker-compose.full-stack.yml up -d --build core-java` — `start` does **not** rebuild, and source changed here, so a rebuild is mandatory), then republish the malformed media message from Task 2 and confirm `curl -s http://localhost:9090/actuator/prometheus | grep '^jtoye_amqp_retries_exhausted_total'` shows a non-zero value tagged `queue="media.process"`. **Fail direction:** capture the same grep *before* the publish and show it absent-or-zero.
    - Image-freshness proof (runtime parity, half (b) of the standing contract): `docker image inspect <core-java image> --format '{{.Metadata.LastTagTime}}'` is newer than the commit touching `RabbitMQConfig.java`, and the running container's image ID equals the tag's. Equivalently, `bash scripts/check-runtime-freshness.sh` exits 0.
    - `bash scripts/dlq-inspect.sh --summary` exits **1** while `webhook.deliveries.dlq` holds 9 (non-empty DLQ = violation) and exits **0** after Task 6 purges it. Both recorded. VOID proof: with `RABBITMQ_DEFAULT_PASS` unset, exit is `2`.
    - `grep -cE "ackmode\"?:\s*\"?get" scripts/dlq-inspect.sh` returns `0` and `grep -c 'reject_requeue_true' scripts/dlq-inspect.sh` is `>= 1`.
    - `docs/metrics.json` `java_test_methods` increased by exactly the number of `@Test` methods added, and `bash scripts/docs-freshness.sh` (check mode) exits 0.
  </acceptance_criteria>
  <done>Retry exhaustion is counted on every queue including the DLX-less one, the topology's deliberate asymmetries are pinned by tests, and an operator has a read-only DLQ CLI that refuses to consume.</done>
</task>

<task type="auto">
  <name>Task 6: triage the nine real dead messages, then re-fire deliberately</name>
  <files>(no repository files — investigation + SUMMARY only)</files>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java (:78-140 — `fanout` pins the tenant GUC then `insertPendingRows`; the `RabbitMQConfig:284-293` WR-05 comment predicted exactly this failure and is why the DLX exists)
    - docs/runbooks/alerts.md (Task 4 — this task is its first real execution)
  </read_first>
  <action>
Execute the runbook against the nine messages in `webhook.deliveries.dlq`, in this order:

1. **Archive first.** Dump all nine via the `reject_requeue_true` peek to
   `<scratchpad>/webhook-dlq-archive-20260726.json`. **Gitignored path only — never commit.** They
   carry `tenantId`, `orderId` and `orderNumber` (I-3), which is tenant data under this project's
   PII rules.
2. **Characterise**: `x-death[0].time` distribution, `count` distribution, distinct
   `routing-keys`, distinct `__TypeId__`, distinct `tenantId`. State whether all nine are one
   incident or several.
3. **Root-cause**: correlate the death timestamps against `docker logs` for core-java around
   2026-07-15 (note the container has been up 10h, so the logs may have rotated — say so rather than
   inventing a cause). The WR-05 comment names the expected class: a transient failure inside
   `insertPendingRows` (DB blip), *not* "no matching subscriptions", which returns early without
   throwing. Confirm or refute against the evidence, and if the logs are gone, say "undetermined —
   logs rotated" rather than guessing.
4. **Decide and record**: are these replayable (does the tenant still have an ACTIVE subscription
   for `ORDER_STATE_CHANGED`?) or discardable (the orders are 11 days old; the webhook contract is
   at-least-once with no delivery-time guarantee)? Recommend discard-with-archive unless the
   subscription is live, and say why.
5. **Purge** `webhook.deliveries.dlq` and record `DeadLetterQueueNonEmpty` resolving (the fail
   direction Task 2 needs).
6. **Re-fire deliberately** with the Task 2 media-pipeline recipe to confirm the resolved alert can
   fire again from empty — proving the resolution was a real state change, not the alert breaking.
   Purge afterwards.
  </action>
  <verify>
    <automated>bash scripts/dlq-inspect.sh --summary; echo "exit=$?"</automated>
  </verify>
  <acceptance_criteria>
    - The archive file exists, contains 9 entries, and is under the scratchpad (assert `git status --porcelain | grep -c 'webhook-dlq-archive'` returns `0` — the archive must not be stageable).
    - The SUMMARY records: death-time range, `x-death.count` values, distinct routing keys, distinct tenants, and a root cause **or** an explicit "undetermined — logs rotated". A confident cause with no log evidence is worse than an honest gap.
    - `bash scripts/dlq-inspect.sh --summary` exits 0 after the purge, and `DeadLetterQueueNonEmpty` shows no `webhook.deliveries.dlq` alert (`curl -s http://localhost:9091/api/v1/alerts | jq -r '[.data.alerts[] | select(.labels.queue=="webhook.deliveries.dlq")] | length'` prints `0`).
    - The deliberate re-fire is recorded firing→resolved with timestamps, from an **empty** starting state — this is the criterion that proves the alert reacts to the transition and not merely to a value that was already true when it was written.
    - Blast-radius check on the re-fire: after the media publish, only `media.process.dlq` is non-empty (`bash scripts/dlq-inspect.sh --summary` names exactly one). Confirms the single-queue choice in Task 2 was correct and that nothing leaked into the order-family DLQs.
  </acceptance_criteria>
  <done>The nine real messages are archived, explained (or honestly marked undetermined), and cleared; the alert is proven to fire from an empty baseline afterwards.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| dead-lettered message → operator's terminal | A DLQ is a **single queue holding every tenant's event payloads**; any inspection path crosses the tenant wall that RLS enforces everywhere else |
| broker management API (15672) → host | Credentials in `.env` grant full broker control: purge, publish, delete queues, read every message |
| Prometheus/Alertmanager → email | Alert `description` templates interpolate `{{ $labels.* }}` and `{{ $value }}` into outbound email |
| alert rule → operator belief | A rule that cannot fire manufactures false confidence — the failure mode that produced this work-item |
| CI → merged monitoring config | CI is the only automatic reviewer of a rule that silently stops matching |

## STRIDE Threat Register (ASVS L1)

| ID | Category | Component | Disposition | Mitigation |
|---|---|---|---|---|
| T-27-01 | Information Disclosure | DLQ payloads carry `tenantId`/`orderId`/`orderNumber` and cross the tenant wall | **mitigate** | ASVS V8.3. **No HTTP surface is added (D-06)** — the only inspection path is `scripts/dlq-inspect.sh`, gated by broker credentials that are already operator-only, run on the operator's own machine. Its header and the runbook both state the output is tenant data and must not be pasted into a shared channel or PR. The Task-6 archive is scratchpad-only with an explicit not-stageable assertion. |
| T-27-02 | Elevation of Privilege | a future DLQ redrive endpoint | **defer + constrain** | ASVS V4.1. Deferred with the constraint recorded that **no cross-tenant operator identity exists** in this platform, so a redrive endpoint has no correct `@PreAuthorize` subject today. Building one before that is resolved would create a cross-tenant read path with no authorised caller. |
| T-27-03 | Tampering | redrive re-poisons / infinite loop | **avoid** | No automatic redrive consumer (D-06). The manual procedure states in bold that redriving before fixing the handler re-poisons, and that `x-death[0].count` is the evidence. |
| T-27-04 | Information Disclosure | alert email interpolates `{{ $labels.queue }}` and `{{ $value }}` | **accept, bounded** | Queue names and integer depths only. **No rule may interpolate a message payload or a tenant id** — Alertmanager's receiver is a shared ops mailbox (`ALERTMANAGER_SMTP_TO`, default `ops@jtoye.local`). Assert: no proposed rule references any label other than `queue`, `job`, `instance`, `service`, `severity`, `component`. |
| T-27-05 | Spoofing / Tampering | broker credentials in scripts | **mitigate** | Both scripts read `RABBITMQ_DEFAULT_USER`/`_PASS` from the environment or `.env`; never accept a password as a CLI argument (it would land in shell history and `ps`); never echo them. gitleaks already scans; no literal is introduced. |
| T-27-06 | Denial of Service | per-object metric cardinality | **mitigate** | `/metrics/detailed` with two families = 73 lines measured, vs 3471 for `/metrics/per-object` which grows with connections and channels (D-01). Measured, not asserted, by the `scrape_samples_scraped < 200` criterion in Task 1. |
| T-27-07 | Denial of Service | unbounded Micrometer tag cardinality | **mitigate** | `jtoye.amqp.retries_exhausted{queue}` collapses the per-JVM SSE queue name to a constant (D-05); unit-tested, with the passthrough fail direction proven. Left unguarded, one tag series would leak per JVM restart, forever. |
| T-27-08 | Repudiation | an alert that cannot fire | **mitigate** | `scripts/check-alert-metrics.sh` (D-09) fails on any rule whose **series selector including labels** matches nothing live, proven RED against the real historical `StompBrokerLag` defect. This is the structural fix; the rest of the plan is the instance. |
| T-27-09 | Tampering | a drill left the stack modified | **mitigate** | Every fire drill in Tasks 2/5/6 pairs a break with a restore and a re-assertion. Task 5's restart uses `up -d --build` because source changed — `docker compose start` does not rebuild, and `scripts/check-runtime-freshness.sh` must exit 0 before the work-item is called done. |
| T-27-10 | Tampering | the gates themselves | **accept** | A contributor can delete a CI step. Mitigated by review; each script's header names the specific defect it pins (`StompBrokerLag`, F-3) so removal has a visible cost. |
| T-27-SC | Tampering | dependency supply chain | **n/a** | No package added in any ecosystem. Only pinned images already in the repo (`prom/prometheus:v2.48.0`) are used, and only as a throwaway `promtool` runner. |

## Other Quality Contracts

- **Web performance (mobile-first): N/A** — no user-facing page, route, bundle or image changes. No
  frontend file is touched.
- **SEO / discoverability: N/A** — no public/unauthenticated surface changes.
- **AI agent-readiness: N/A, with a recorded reason** — no API surface, OpenAPI contract, error
  shape, or credential scope changes; D-06 explicitly declines to add an endpoint, so there is no
  mutating operation needing an Idempotency-Key and no new capability needing an MCP tool. If the
  deferred redrive endpoint is ever built, it inherits the full agent-readiness contract
  (Idempotency-Key, RFC 7807, scoped credential, MCP tool or a recorded reason) — noted in
  `deferred-items.md` so the follow-up cannot skip it.
- **Security: applicable** — register above.
- **Falsifiable evidence + runtime parity: applicable, and the point of the work-item.**
  (a) Every acceptance criterion above carries a deliberate break and its expected fail output;
  three criteria are RED on the unmodified tree *before* any change (the live gate against
  `StompBrokerLag`, the static gate's missing runbook heading, and `up{job="core-java"} == 0`),
  which is the strongest available evidence that they can fail.
  (b) Task 5 changes Java source, so its verification requires `up -d --build` and
  `scripts/check-runtime-freshness.sh` exiting 0; `scripts/check-branch-behind-base.sh` must be
  clean before the PR.
</threat_model>

<verification>
```bash
# static, CI-equivalent
bash scripts/check-alert-rules.sh                                   # 0
bash scripts/docs-freshness.sh                                      # 0
./gradlew :core-java:cleanTest :core-java:test --tests '*RabbitMQ*' # green, EXECUTED (not UP-TO-DATE)

# live (requires the compose stack + monitoring stack up)
bash scripts/check-alert-metrics.sh                                 # 0
bash scripts/dlq-inspect.sh --summary                               # 0 after Task 6
curl -s http://localhost:9091/api/v1/targets \
  | jq -r '[.data.activeTargets[] | select(.health!="up")] | length' # 0

# runtime parity (Java source changed)
bash scripts/check-runtime-freshness.sh                             # 0
bash scripts/check-branch-behind-base.sh                            # 0
```
Full-suite note: this plan adds **no** `@PreAuthorize` gate and no controller, so the recorded
scope-gate `integrationTest` regression class does not apply — that is a *consequence* of D-06, not
a coincidence. Still run `./gradlew :core-java:cleanTest :core-java:test` in full once (the
`RabbitMQConfig` bean signature changed, and `rabbitListenerContainerFactory` calls
`retryInterceptor()` directly at :408, so every test that loads a Spring context touches it).
</verification>

<success_criteria>
- Each of the four DLQs filling produces a named, per-queue alert within 5 minutes (1 minute for
  `payment.events.dlq`), demonstrated firing→resolved from an empty baseline.
- `RabbitMQDown` exists, mirrors `RedisDown`, and was observed firing with the broker stopped.
- `StompBrokerLag` is corrected onto a series that exists, and the correction is reported as a
  defect fix with the empty-vector proof — not as a tweak.
- `scripts/check-alert-metrics.sh` exits 1 against the pre-change `alerts.yml`, naming
  `StompBrokerLag` — the gate is proven to catch the bug that motivated it, on the real artifact.
- The core-java scrape target is UP, so `payment_outbox_dead_letter_total` and
  `media_outbox_dead_letter_total` are queryable in Prometheus and `OutboxDeadLetterRising` has data.
- A retry-exhausted message on `onboarding.notifications` — a queue with no DLX and no DLQ —
  increments `jtoye_amqp_retries_exhausted_total{queue="onboarding.notifications"}`.
- Every rule in `alerts.yml` has a runbook section; every command in the messaging section has been
  executed and its real output recorded.
- The nine pre-existing dead messages are archived, explained or honestly marked undetermined, and
  cleared.
- Redrive is deferred **with a written trigger and four recorded constraints**, not left unmentioned.
</success_criteria>

<output>
Create `.planning/phases/27-messaging-layer-hardening/27-03-SUMMARY.md`. Record verbatim:
the pre-change empty-vector proof for `StompBrokerLag`; the pre/post `up{job="core-java"}` values;
both directions of every falsifiability probe (RED output and the restored GREEN); the
`scrape_samples_scraped{job="rabbitmq-queues"}` measurement; the Task-6 forensics
(death-time range, `x-death.count` distribution, distinct tenants, root cause or "undetermined");
and the final `docs/metrics.json` delta.
Add to `.planning/phases/27-messaging-layer-hardening/deferred-items.md`: the DLQ redrive endpoint
(with its trigger and the four constraints from D-06), the `onboarding.notifications` DLX migration
(D-07, with the 406 hazard), the duplicate `jtoye_payments_failed_total` counter (F-7), a
`webhook.delivery` failure **metric** for `WebhookDeliveryWorker` (F-5a — the log line and the
per-tenant endpoint exist; the proactive signal does not), and k8s-side alerting (F-8 — `k8s/`
ships no Prometheus at all).
</output>

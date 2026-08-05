# Alert Runbook

This document is the first-response reference for every Prometheus alert rule defined in `infra/monitoring/prometheus/alerts.yml`. Alerts route via Alertmanager (`infra/monitoring/docker-compose.monitoring.yml`) to an email receiver — Mailhog in dev, SMTP relay in prod.

**When an alert fires:**
1. Open the alert notification (email body lists the alert name, severity, service, started-at)
2. Find the matching section below
3. Follow the first-response steps
4. If unresolved after first-response, follow the escalation path

**Mailhog UI (dev):** http://localhost:8025
**Alertmanager UI:** http://localhost:9093
**Prometheus UI:** http://localhost:9091

> Database backup alerts (`DatabaseBackupStale` / `DatabaseBackupFailing`) and the
> restore-testing cadence live in [`backups.md`](./backups.md) (Issue #119).

---

## ServiceDown

**Rule:** `alerts.yml` group `api_alerts`
**Expression:** `up == 0`
**Duration:** fires after 2 minutes of down-state
**Severity:** critical
**Service label:** `platform` (this alert is a platform-wide catch-all — any `up==0` triggers it regardless of which target)

### What it means

Prometheus cannot scrape one of its configured targets. The scraped target could be any of:
- `jtoye-core-java:9090` (Spring Boot actuator)
- `jtoye-edge-go:9101` (Go edge gateway — its **management** port, injected as
  `EDGE_GO_METRICS_PORT`. Since issue #550 the edge serves `/metrics` there and NOT on
  its application port 8080/8089; if this target is down, curl it from inside the
  network, not from the host — the management port is deliberately unpublished)
- `jtoye-postgres-exporter:9187`
- `jtoye-keycloak:8080`
- `jtoye-rabbitmq:15692`
- `redis-exporter:9121`

Check the `job` label on the firing alert — it identifies which target is down.

### Expected impact

- **core-java down** — full API outage; customers cannot place orders, vendors cannot manage anything, kitchen WebSocket broadcasts stop
- **edge-go down** — rate limiting + JWT validation fallback path is dead; customer storefront may still partially work via direct core-java calls
- **postgres-exporter down** — no database metrics, but the database itself may still be serving traffic; check separately via `DatabaseDown` alert
- **keycloak down** — no new logins; existing JWTs work until expiry (default 15 min)
- **rabbitmq down** — payment outbox flush backs up, kitchen broadcasts stop fanning out, and **publishing and consuming stop**. (Corrected 2026-07-29: this bullet previously claimed the DLQ becomes inaccessible, which is wrong and misleads first response. A dead-letter queue is an ordinary durable queue; it does not become unreachable, it simply stops receiving and stops being readable *over AMQP*. Nothing is lost. See `## RabbitMQDown` for the verified blast radius.)
- **redis-exporter down** — cache metrics lost; cache itself may still work

> **Two scrape jobs point at the same broker.** `rabbitmq` (aggregated `/metrics`) and
> `rabbitmq-queues` (`/metrics/detailed`, per-queue) are separate Prometheus jobs against
> the same RabbitMQ on the same port. One broker outage can therefore raise `ServiceDown`
> naming *either* job — that is one fault, not two. `RabbitMQDown` is deliberately scoped
> to `job="rabbitmq"` alone so it can never double-page alongside it.

### First-response steps

1. **Confirm scope** — how many targets are firing?
   ```bash
   curl -s http://localhost:9091/api/v1/query?query=up==0 | jq .
   ```
2. **Check container health** — is it restarting, crashed, or OOM-killed?
   ```bash
   docker ps --filter 'name=jtoye-' --format 'table {{.Names}}\t{{.Status}}'
   docker logs --tail 100 jtoye-<service-name>
   ```
3. **If the container is restart-looping** — likely a bad config or env var change. Inspect:
   ```bash
   docker compose config | less     # Validates + renders
   docker compose logs --tail 200 <service>
   ```
4. **If the container is healthy but Prometheus cannot scrape it** — network or DNS issue. Confirm both services are on the same docker network:
   ```bash
   docker network inspect jtoye_oaas_2026_jtoye-network | grep <service>
   ```
5. **If all targets are down simultaneously** — likely a docker-daemon / host / disk-full issue. Check:
   ```bash
   docker info
   df -h
   systemctl status docker
   ```

### Escalation

- **Production outage** — page the on-call engineer immediately. Expected RTO: 15 minutes
- **Unresolved after 30 minutes** — open an incident in the tracker with the full `docker logs` output and the Prometheus query response from step 1
- **Recurring** (same target, more than 3× per week) — treat as a systemic issue: open a post-incident review ticket, investigate root cause, add a rule-specific runbook entry below

---

## HighErrorRate

<!-- TODO: fill in when a real incident provides first-response lessons. -->
<!-- Rule: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m]) > 5% for 5m, service=core-java -->
<!-- First response: check core-java logs for exception stack traces; check DatabaseConnectionPoolExhausted / TooManyDatabaseConnections for downstream cause -->

## HighResponseTime

<!-- TODO: fill in. Rule: P95 latency > 1s for 5m. service=core-java -->

## DatabaseConnectionPoolExhausted

<!-- TODO: fill in. Rule: hikaricp_connections_active/max > 90% for 5m. -->

## DatabaseDown

**Expression:** `up{job="postgres"} == 0 or pg_up == 0` · **for:** 1m · **severity:** critical

### What it means

One of two different things, and **step 1 exists to tell them apart**:

- `up{job="postgres"} == 0` — Prometheus cannot scrape the **exporter**.
- `pg_up == 0` — the exporter is answering, but **it cannot reach the database**.

The `pg_up` arm was added on 2026-07-29 (plan 27-03, D-11) because without it this rule was
structurally incapable of detecting a database outage. Measured on this stack: `up{job="postgres"}`
read **1** while `pg_up` read **0** — the exporter was healthy, not one PostgreSQL metric was being
collected, and the alert reported everything fine.

### First-response steps

1. **Distinguish an exporter failure from a database outage. Do this first.**
   ```bash
   docker exec jtoye-postgres pg_isready
   ```
   `pg_isready` speaks to the database directly. If it says `accepting connections`, the database
   is fine and you are looking at an exporter fault. Then read the exporter's own log:
   ```bash
   docker logs jtoye-postgres-exporter --tail 20
   ```
2. **The known exporter fault, and its exact signature.** `sslmode=require` against a PostgreSQL
   with no TLS produces, in the exporter log:
   ```
   Error opening connection to database (postgresql://...?sslmode=require): pq: SSL is not enabled on the server
   ```
   and `pg_up = 0` while the database is perfectly healthy. The DSN is built in
   `infra/monitoring/docker-compose.monitoring.yml` from `${POSTGRES_EXPORTER_SSLMODE:-require}`;
   the local runtime sets `disable` in `.env`. A deployed environment that enables TLS on
   PostgreSQL should drop that override rather than keep it.
3. **If the database really is down**, check disk first, then the container log:
   ```bash
   df -h
   ```

### Escalation

Total API outage. Page immediately.

## TooManyDatabaseConnections

<!-- TODO: fill in. Rule: pg_stat_database_numbackends > 100 for 5m. -->

## HighMemoryUsage

**Expression:** `(jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100 > 85` · **for:** 5m

**The alert now names the JVM it actually measured.** Until 2026-07-29 this rule carried a static
`service: core-java` label. A static rule label *overrides* the series' own label of the same name,
and the annotation interpolates `{{ $labels.service }}` — so the email said "core-java" while
describing whichever JVM was scraped, which for a long period was **Keycloak's** and nothing else.
The static label is deliberately gone (plan 27-03, D-11); read `{{ $labels.service }}` in the alert
body to know which process is in trouble. Do not re-add it — `scripts/check-alert-rules.sh` records
this rule as a `service`-label exemption and fails as STALE if the label comes back.

## FrequentGarbageCollection

**Expression:** `rate(jvm_gc_pause_seconds_count[5m]) > 50` · **for:** 5m

Same correction as `HighMemoryUsage` above: the static `service: core-java` label is gone, so the
alert reports the JVM it measured rather than the one someone assumed.

## NoOrdersCreated

**Expression:** `increase(http_server_requests_seconds_count{uri=~"/api/v[0-9]+/orders|/public/shops/[^/]+/orders",method="POST",status="201"}[30m]) < 1`
· **for:** 30m · **severity:** info

### What it means

No order was successfully created in the last 30 minutes. This is a **business** signal, not an
outage — nothing is down, and no page is warranted on its own.

### The trap: this is a REQUEST counter, not a database fact

`http_server_requests_seconds_count` is a Micrometer request counter. It is **created on the first
matching request and destroyed when core-java restarts.** Two consequences that have each cost time
already:

- **Seeding an order row in the database does not create the series.** Neither does any read
  endpoint. Only a real `POST` that returns `201` does.
- **This project mandates rebuilding all containers after any code change**, so after every rebuild
  the alert is *blind* until the first order — precisely when you would most want it.

### Two different symptoms, two different fixes — do not confuse them

| symptom | what it means | fix |
|---|---|---|
| `scripts/check-alert-metrics.sh` fails on it | the stack was rebuilt, the counter is gone, the alert is **blind** | `bash scripts/seed-order-metric.sh` |
| the alert is **firing** in Prometheus | the counter exists, no order landed in 30m — the alert is **working** | `FORCE=1 bash scripts/seed-order-metric.sh` |

The gate asks *does the series exist*; the alert asks *was there a recent order*. Both can be true at
once, which is why the plain invocation exits early and places no order — it cannot silence a firing
alert, and it says so itself.

**Do not add a `KNOWN_DATALESS` entry for this.** That gate's own header calls it the wrong fix.

### Silencing it locally

On a quiet local stack this fires every 30 minutes forever, and the `FORCE=1` remedy above buys
silence by writing a **real order row into the dev database on every run**. Prefer the mute:

```bash
# in .env — LOCAL ONLY
ALERTMANAGER_MUTE_ALERTNAMES=NoOrdersCreated
```

then recreate Alertmanager (`docker compose -f infra/monitoring/docker-compose.monitoring.yml up -d
--force-recreate alertmanager`). The startup log will say so explicitly.

This **withholds the notification only**. The rule keeps evaluating and the alert still shows as
firing in Prometheus and in the Alertmanager UI — the mute cannot hide it from anyone looking at it.

**It must never be set outside local development.** `scripts/check-alert-mute.sh` assertion M-5 fails
if any file under `k8s/` sets the variable, and runs standalone via `MODE=static` so CI enforces that
half without a running stack. Confirm the mute is absent from a given runtime by reading the config
out of the container — never the host template, which is not what Alertmanager loaded:

```bash
docker exec jtoye-alertmanager cat /etc/alertmanager/alertmanager.yml | grep -A3 mute-null   # expect no output
```

## TenantIsolationFailure

<!-- TODO: fill in. Rule: rate(tenant_context_missing_total[5m]) > 0.1. Security-critical — investigate the request path that bypassed JwtTenantFilter. -->

## KeycloakDown

**Expression:** `up{job="keycloak"} == 0` · **for:** 2m · **severity:** critical

### What it means

Prometheus cannot scrape Keycloak. Since Keycloak is the only identity provider, this is an
authentication outage in waiting.

### Expected impact

No new logins and no token refreshes. **Already-issued JWTs keep working until they expire**, so the
platform degrades gradually rather than stopping — which is why this can go unnoticed without the
alert. Vendors mid-session keep working; anyone who signs in, or whose token rolls over, cannot.

### First-response steps

1. Confirm the container and its health:
   ```bash
   docker ps --filter 'name=jtoye-keycloak' --format 'table {{.Names}}\t{{.Status}}'
   ```
2. Confirm the realm actually answers — a container can be `healthy` while the realm is not:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8085/realms/jtoye-dev/.well-known/openid-configuration
   ```
3. Keycloak is **Postgres-backed**. If it is restart-looping, check `DatabaseDown` first — a
   Keycloak outage is frequently a database outage wearing a different hat.

### Escalation

Page if it does not recover within one token lifetime (default 15 minutes), because that is the
point at which working sessions start failing.

## RedisDown

**Expression:** `up{job="redis"} == 0 or redis_up == 0` · **for:** 1m · **severity:** critical

### What it means

Either the redis-exporter target is unreachable, **or** the exporter is up and reporting that *it*
cannot reach Redis. The two disjuncts are different failures and step 2 tells them apart.

**History — read this before you trust an old silence.** Until 2026-07-29 this rule watched only
`up{job="redis"}`, which is the health of the *exporter*, not of Redis. `redis_up` was live in the
scrape and referenced by no rule (`grep -c redis_up alerts.yml` → 0). Measured with `jtoye-redis`
stopped and the exporter left running: `up{job="redis"}` read **1**, `redis_up` read **0**, and the
old expression matched **0 samples** — a total loss of the cache would have paged nobody. Tracked as
TS-15; fixed by issue #342 item 5, the Redis half of the `DatabaseDown`/D-11 correction.

### Expected impact

Cache misses fall through to PostgreSQL, so the platform stays *correct* and gets slower. Watch
`DatabaseConnectionPoolExhausted` — a cold cache moves load straight onto the pool.

### First-response steps

1. ```bash
   docker ps --filter 'name=jtoye-redis' --format 'table {{.Names}}\t{{.Status}}'
   ```
2. **Find out which disjunct fired** — they mean different things:
   ```bash
   curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=up{job="redis"}'
   curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=redis_up'
   ```
   `up`=0 → Prometheus cannot scrape the exporter (a metrics outage, possibly nothing more).
   `up`=1 and `redis_up`=0 → the exporter is fine and **Redis itself is unreachable**. That is the
   real service outage, and it is the one the old rule could not see.
3. **Ask Redis directly**, rather than asking the exporter about Redis:
   ```bash
   docker exec jtoye-redis redis-cli PING
   ```
4. If Redis is up and only the exporter is down, this is a metrics outage, not a service outage —
   downgrade the response accordingly and fix the exporter at leisure.

### Escalation

Only if `DatabaseConnectionPoolExhausted` follows it.

## PaymentFailureSpike

**Expression:** `rate(jtoye_payment_failed_total[5m]) > 0.1` · **for:** 5m · **severity:** warning

### What it means

`PaymentService.handlePaymentIntentFailed` is incrementing — Stripe is sending
`payment_intent.payment_failed` webhooks faster than one every ten seconds.

### Expected impact

Money. Customers are reaching checkout and not completing. The platform is *working*; the
transactions are not.

### First-response steps

1. Separate "cards are declining" from "we are broken". Check the Stripe dashboard for the decline
   reason distribution first — a spike of `insufficient_funds` is the world, a spike of
   `api_error` or `authentication_required` is us.
2. Check Stripe's status page.
3. Check core-java for our own errors on the payment path:
   ```bash
   docker logs jtoye_oaas_2026-core-java-1 --since 30m 2>&1 | tail -40
   ```
4. Correlate with a deploy. A checkout regression looks exactly like a decline spike from here.

### Escalation

Page if the rate is sustained for 15 minutes or coincides with a deploy.

## StompBrokerLag

**STATUS: DORMANT — the rule is commented out in `alerts.yml`. This section is where its re-enable
trigger lives.**

### Why it is not live

As originally written this rule was **incapable of firing from the day it shipped**. It selected
`rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}` against the series
family emitted by RabbitMQ's *aggregated* `/metrics` endpoint, which carries **no `queue` label at
all**. Measured 2026-07-29:

```
query=sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}) > 0
  -> {"resultType":"vector","result":[]}
control query=rabbitmq_queue_messages_ready
  -> one series, value 9, metric keys: __name__ component instance job service
```

The corrected expression, on the per-queue series added by the `rabbitmq-queues` scrape job, is:

```promql
sum(rabbitmq_detailed_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}) > 0
```

It is preserved verbatim in the commented block in `alerts.yml`, so re-enabling is an uncomment,
not a re-derivation. It has been **observed evaluating `> 0`** against a throwaway queue named
`stomp-subscription-probe27`, so the expression is known to work — an alert never observed firing
is not an alert.

It nevertheless ships dormant, because it cannot match anything here and no user action can make it:

- the canonical local runtime is `STOMP_BROKER_MODE=in-memory`, so nothing is relayed to RabbitMQ
  and a subscribing kitchen client creates **no broker queue at all**;
- measured: **zero** STOMP connections to the broker;
- relay mode is a `k8s/base` setting, and `k8s/` ships **no Prometheus**.

### Re-enable trigger and owner

**Issue #304** — rework `frontend/e2e/stomp-relay.spec.ts` to be ingress-capable. Whoever makes that
spec establish a real `SUBSCRIBE` in relay mode is the person who should uncomment this rule. Any
deliberate `STOMP_BROKER_MODE=relay` is the same trigger.

### This dormancy cannot rot

`scripts/check-alert-metrics.sh` carries a `DORMANT_RULES` entry for `StompBrokerLag` and **fails
when the selector starts returning series** — "this rule now has data, re-enable it". The red is the
instruction, and the only remedy is to act on it. Known residual: the selector is a name pattern, so
the guard also fires on any queue *coincidentally* named `stomp-subscription*`/`amq.gen-*`, and the
only fix for that is a manual edit of the `DORMANT_RULES` list. Track that red to closure like any
other.

## DiskSpaceLow

**STATUS: DORMANT** — commented out in `alerts.yml`. `node_filesystem_*` series are emitted only by
node-exporter, which is not deployed. **Re-enable trigger:** issue **#98** — deploy node-exporter and
confirm it is scraped. Guarded by a `DORMANT_RULES` entry in `scripts/check-alert-metrics.sh`, which
fails the day those series appear.

## DiskSpaceCritical

**STATUS: DORMANT** — same reason, same trigger, same guard as `## DiskSpaceLow` above.

---

# Messaging

Everything in this section was added by phase 27, plan 27-03. Before it, this repo had exactly one
messaging alert and it could not fire, while four dead-letter queues filled with nobody watching.

## Shared inspection path

Every messaging section below refers back to these commands. They are the ones actually used during
triage; none of them modifies anything.

```bash
RMQ_USER=$(grep -E '^RABBITMQ_DEFAULT_USER=' .env | cut -d= -f2-); RMQ_PASS=$(grep -E '^RABBITMQ_DEFAULT_PASS=' .env | cut -d= -f2-); test -n "$RMQ_USER" -a -n "$RMQ_PASS" && echo "credentials loaded from .env (never passed as an argument — shell history, and ps shows it)"
```

```bash
curl -s -u "$RMQ_USER:$RMQ_PASS" http://localhost:15672/api/queues/%2F | jq -r '.[] | "\(.name)\tmsgs=\(.messages)\tconsumers=\(.consumers)"' | sort
```

```bash
curl -s -u "$RMQ_USER:$RMQ_PASS" -H 'content-type:application/json' -X POST http://localhost:15672/api/queues/%2F/webhook.deliveries.dlq/get -d '{"count":5,"ackmode":"reject_requeue_true","encoding":"auto","truncate":50000}' | jq -r '.[] | {reason:.properties.headers["x-first-death-reason"], from_exchange:.properties.headers["x-death"][0].exchange, from_queue:.properties.headers["x-death"][0].queue, routing_key:.properties.headers["x-death"][0]["routing-keys"][0], attempts:.properties.headers["x-death"][0].count, died_at:(.properties.headers["x-death"][0].time|todate), type:.properties.headers["__TypeId__"]}'
```

```bash
docker exec jtoye-prometheus wget -qO- 'http://jtoye-rabbitmq:15692/metrics/detailed?family=queue_coarse_metrics' | head -20
```

**Two things that will cost you the evidence if you get them wrong:**

- **`"ackmode":"get"` REMOVES the message.** Only `reject_requeue_true` is non-destructive. Prefer
  `scripts/dlq-inspect.sh --peek <queue>`, which hardcodes the safe mode and will not accept an
  ackmode argument at all.
- **A dead message's own `exchange` field is the DLX, not where it came from.** The redrive target is
  `x-death[0].exchange` plus `x-death[0]["routing-keys"][0]`. Republishing to the message's own
  `exchange` field sends it back to the dead-letter exchange, i.e. straight back to the DLQ.

**Everything in a DLQ is tenant data.** One queue holds every tenant's payloads, and `tenantId` is in
the JSON body rather than a header. Do not paste raw output into a shared channel, an issue, or a PR.

Note on port 15692: it is **not** host-published (the compose `ports:` list carries only 5672, 15672
and 61613), which is why the raw-scrape command goes via a peer container. The rabbitmq container
itself has no `curl`, only busybox `wget`, and its own listener is `[::]`, so `wget` from inside it
is refused.

## RabbitMQDown

**Expression:** `up{job="rabbitmq"} == 0` · **for:** 1m · **severity:** critical

### What it means

The broker is unreachable. Deliberately scoped to `job="rabbitmq"` only — the `rabbitmq-queues` job
hits the same broker, so widening the selector would double-page one outage. `ServiceDown` already
covers the second job.

### Expected impact (verified, not assumed)

- the transactional outbox flushers stop draining: rows accumulate `PENDING`, exponential backoff
  engages, and `payment_outbox_dead_letter_total` / `media_outbox_dead_letter_total` begin rising
  once attempts reach 10;
- kitchen-display broadcasts stop;
- outbound webhook fan-out stops;
- media processing stops;
- core-java's compose healthcheck flips to `unhealthy` and **nothing restarts it** —
  `restart: unless-stopped` does not act on health.

### First-response steps

1. ```bash
   docker logs jtoye-rabbitmq --tail 20 2>&1 | tail -20
   ```
2. **Check the disk alarm before anything else.** RabbitMQ blocks *publishers* on a disk alarm, which
   presents as a total messaging stall with a healthy-looking broker:
   ```bash
   docker exec jtoye-rabbitmq rabbitmq-diagnostics alarms
   ```
3. After the broker is back, **confirm core-java actually reconnected** — do not assume it did:
   ```bash
   docker logs jtoye_oaas_2026-core-java-1 --since 24h 2>&1 | grep -c "Attempting to connect\|Created new connection"
   ```
   Note the container is `jtoye_oaas_2026-core-java-1`, not `jtoye-core-java`; compose dropped
   `container_name` to support `--scale`.

### Escalation

Page. Nothing asynchronous works without it.

## PaymentDeadLetterQueueNonEmpty

**Expression:** `rabbitmq_detailed_queue_messages{queue="payment.events.dlq"} > 0` · **for:** 1m ·
**severity:** critical

### What it means

At least one payment event failed three delivery attempts and was parked. It has its own rule,
separate from `DeadLetterQueueNonEmpty` and at `critical`/1m, because a lost payment event is money.
The generic rule explicitly excludes this queue so the two can never double-page.

### First-response steps

Follow `## DeadLetterQueueNonEmpty` below — the procedure is identical — but treat the timeline as
tighter and **archive before doing anything else**.

### Escalation

Page immediately, and notify finance if any message is older than one business day.

## DeadLetterQueueNonEmpty

**Expression:** `rabbitmq_detailed_queue_messages{queue=~".*[.]dlq", queue!="payment.events.dlq"} > 0`
· **for:** 5m · **severity:** warning

### What it means

Messages are parked on a dead-letter queue. **Dead-letter queues have no consumer by design, so this
will not clear itself** — it clears when a human decides what to do with the messages.

### Is this the known state?

**No.** Post plan 27-05 the steady-state baseline for `webhook.deliveries.dlq` is **0**. 27-05 fixed
the producing fault — a `Jackson2JsonMessageConverter` constructed with no trusted packages, which
dead-lettered 100% of outbound webhooks from the day Phase 22 shipped. A
`DeadLetterQueueNonEmpty{queue="webhook.deliveries.dlq"}` firing after 27-05 has landed and the
historic batch has been disposed of is a **real regression**: either the converter fix did not take,
or a second producer exists. Do not learn to ignore it.

### First-response steps

1. **Peek — do not consume.**
   ```bash
   bash scripts/dlq-inspect.sh --peek webhook.deliveries.dlq 2
   ```
2. **Read `x-death[0].reason`:**
   - `rejected` — the handler failed three times, or a conversion error was fatal. Normal.
   - `expired` / `maxlen` — **neither TTL nor a length limit is configured on any queue here**, so
     seeing one of these means the topology changed. Investigate that before the message.
3. **Correlate `died_at` with core-java's log** at that timestamp. **The exception class is the ONLY
   thing that distinguishes the two dead-letter paths** — neither counter nor `x-death` does:
   - `MessageConversionException` — the payload or `__TypeId__` cannot be deserialized. Redriving it
     unchanged will fail again; fix the producer or discard.
   - any other listener exception — the handler failed. A redrive may succeed once the cause is fixed.

   **Two things that look like they distinguish these paths, and do not:**

   - **`jtoye_amqp_retries_exhausted_total{queue="..."}` increments on BOTH.** The message converter
     runs inside `MessagingMessageListenerAdapter`, which is *wrapped by the advice chain*, so a
     conversion failure is retried exactly like a handler failure and reaches the same recoverer.
     Measured in the 27-03 Task 7 drill: one malformed publish to `media.events` took the counter
     `1 -> 2` on `queue="media.process"`, with the log reading
     `MessageConversionException -> ListenerExecutionFailedException -> "after 3 retries"`.
     Do **not** read "the counter did not move" as "it was a conversion failure".
   - **`x-death[0].count` reads `1` on both.** It counts dead-letterings, not delivery attempts, and
     the retry interceptor retries in-process. Measured twice independently on the real batch.
     Do not read a `1` there as "it was never retried".
4. **Decide: fix-then-redrive, or discard.** **Archive before any purge.** The payload is the only
   remaining copy of the event.

### Manual redrive procedure (deliberately not automated)

There is no redrive endpoint and no automatic redrive consumer. Both are deferred with a written
trigger — see the phase's `deferred-items.md`. Until then, redrive by hand with a shovel or
`rabbitmqadmin`, republishing from the DLQ to `x-death[0].exchange` with the original routing key.

**Three hazards, all of which have a way of being learned the expensive way:**

1. **Redriving before fixing the handler re-poisons immediately.** The message is in the DLQ
   *because* a deterministic handler failed it three times. Republishing re-enters the same handler,
   fails again, and returns — a hot loop bounded only by `x-death.count`, which nothing reads.
2. **The payload carries `tenantId`, and you are looking at every tenant's data.** A DLQ is a single
   queue holding every tenant's events, and AMQP `basic.get` is FIFO — you cannot fetch "only my
   tenant's messages". Treat your terminal buffer as tenant data.
3. **`__TypeId__` must be preserved.** Drop it and the consumer's `@RabbitHandler` dispatch picks the
   wrong overload — or falls into `WebhookFanoutListener`'s `isDefault` sink, which discards the
   message at `log.debug` and tells you nothing.

## DomainQueueBacklog

**Expression:**
`rabbitmq_detailed_queue_messages_ready{queue!~".*[.]dlq|order[.]state-changes[.]sse[.].*"} > 100` ·
**for:** 10m · **severity:** warning

### What it means

A live queue has more than 100 messages ready for ten minutes. The consumer is **alive but not
keeping up** — that is the distinction from `MessagingConsumerMissing`, and it is worth confirming
which one you have before doing anything.

### First-response steps

1. Check `MessagingConsumerMissing` first. A dead consumer is a different fault with a different fix.
2. If the consumer is alive, look upstream at saturation: `DatabaseConnectionPoolExhausted`, then
   core-java thread state. A queue backs up because something *downstream of the consumer* is slow.
3. Confirm the depth independently of Prometheus:
   ```bash
   bash scripts/dlq-inspect.sh --list
   ```

## MessagingConsumerMissing

**Expression:** `rabbitmq_detailed_queue_consumers{queue!~".*[.]dlq"} == 0` · **for:** 5m ·
**severity:** critical

### What it means

**The "everything is UP and nothing is working" alert.** core-java is up, the broker is up, and a
queue has no consumer — so events land and nothing processes them. No depth alert catches this early,
because the depth only grows once traffic arrives.

`for: 5m` absorbs a normal restart. DLQs are excluded because zero consumers is their *correct and
permanent* state; widening the selector to `.*` pages four times forever — that exact widening was
run as a falsification arm and produced precisely four false positives, one per DLQ.

### First-response steps

1. Look for AMQP connection loss in core-java:
   ```bash
   docker logs jtoye_oaas_2026-core-java-1 --since 24h 2>&1 | grep -ci 'amqp\|listener\|channel shutdown'
   ```
2. Ask the broker who is connected:
   ```bash
   docker exec jtoye-rabbitmq rabbitmq-diagnostics list_connections
   ```
3. If the listener container died without the connection dropping, restarting core-java restores it —
   but capture the log first, because the restart destroys the evidence.

### Escalation

Page. This is silent data loss in slow motion.

## OutboxDeadLetterRising

**Expression:**
`increase(payment_outbox_dead_letter_total[15m]) > 0 or increase(media_outbox_dead_letter_total[15m]) > 0`
· **for:** 0m · **severity:** warning

### What it means

A transactional-outbox row was abandoned. **Two causes, and the operator response is completely
different:**

| cause | condition | what happens next |
|---|---|---|
| poison payload | `JsonProcessingException` while serialising | **permanent** — never resurrected, because the resurrection queries carry `AND poison = FALSE` |
| attempts exhausted | `attempts >= 10` | the resurrection pass **will** re-lease it |

### First-response steps

1. **`poison = true` rows are visible ONLY in the database.** There is no metric, no endpoint, and no
   log line after the initial `log.error`. Once that line rotates out, the row is invisible forever
   unless you run this query.

   **The RLS caveat is the whole difficulty.** `payment_event_outbox` is `FORCE` RLS, so a plain
   `SELECT` as the application role returns **zero rows** and looks reassuring:

   ```sql
   -- as the superuser (bypasses RLS) — the only way to see all tenants at once
   SELECT tenant_id, id, event_type, attempts, poison, last_error, created_at
   FROM payment_event_outbox WHERE status = 'FAILED' ORDER BY created_at DESC LIMIT 50;
   ```

   ```sql
   -- as the app role: pin the tenant GUC FIRST, or you will see nothing and believe it
   SET LOCAL app.current_tenant_id = '<tenant-uuid>';
   SELECT id, event_type, attempts, poison, last_error FROM payment_event_outbox WHERE status = 'FAILED';
   ```

   Substitute `media_event_outbox` for the media pipeline; it is an exact clone.
   *(Both SQL blocks require a `psql` session and a tenant id, so they are exempt from the
   command-replay harness in this document's verification — listed there with that reason.)*
2. **A poisoned row needs a human decision.** It will never be retried. Either repair the payload and
   re-insert, or record the loss deliberately.
3. If the counter rose from the `attempts >= 10` path instead, look for a broker outage in the same
   window — `RabbitMQDown` at any point in the last 15 minutes explains it, and the resurrection pass
   will clear it without intervention.

---

*Last updated: 2026-07-29 — phase 27 plan 27-03. Added the Messaging section and the four missing
alert sections (`KeycloakDown`, `RedisDown`, `PaymentFailureSpike`, `StompBrokerLag`) plus the two
dormant `DiskSpace*` stubs; corrected the `DatabaseDown` and JVM sections for D-11 and the RabbitMQ
impact bullet under `ServiceDown`. Coverage is enforced by `scripts/check-alert-rules.sh`, which
fails when a live or dormant rule has no `## <AlertName>` heading here — so this file cannot silently
fall behind `alerts.yml` again. Remaining TODO stubs are pre-existing and are the ones no incident
has yet exposed.*

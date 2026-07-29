# Runbook — terminal failure states

One section per row in [`docs/ops/terminal-states.yaml`](../ops/terminal-states.yaml). A terminal
state is one where work has permanently stopped and will not resume on its own.

`scripts/check-terminal-states.sh` enforces that this file has a section per register row, and
`scripts/check-alert-liveness.sh` proves the alerts can actually see and tell.

> **What the transport proof does and does not mean.** `check-alert-liveness.sh`'s L-3 assertion
> proves a message left Alertmanager and arrived at the configured sink.
> It does NOT prove a human reads that sink.
> Today that sink is Mailhog at `ops@jtoye.local`, with no human behind it — a real `ServiceDown`
> fired here for over 46 hours and reached nobody. A green L-3 must never be reported as
> "operators are now notified".
>
> *(That sentence is kept on one line deliberately: `AC-4.12` asserts it by literal single-line
> match in both this file and the script header, and a line-wrapped version silently defeats the
> check — measured.)*

**Alert-specific runbook sections live in [`alerts.md`](alerts.md)** — this file is about the
underlying states, not the rules.

---

## TS-01 — order.state-changes.dlq

**What stopped.** Order state-change events that exhausted retries. The kitchen display and the
vendor order list stop reflecting reality for those orders; no customer-facing status updates again.

**How to see it.**
```bash
docker exec jtoye-rabbitmq rabbitmqctl list_queues name messages consumers | grep order.state-changes.dlq
```

**What to do.** Peek without consuming, read `x-death` for the original exchange and failure count,
decide replay vs discard, and record the decision before acting.
```bash
docker exec jtoye-rabbitmq rabbitmqadmin get queue=order.state-changes.dlq \
  ackmode=reject_requeue_true count=10
```

**What NOT to do.** **NEVER ack.** `ackmode=ack_requeue_false` destroys the message and the evidence
with it. Do not re-run a state transition without checking the order's current status first — the
order may have been corrected manually since.

## TS-02 — payment.events.dlq

**What stopped.** Payment events that exhausted retries. Money moved at Stripe but the ledger never
learned, so vendor revenue figures and the customer receipt disagree with the card statement.

**How to see it.**
```bash
docker exec jtoye-rabbitmq rabbitmqctl list_queues name messages | grep payment.events.dlq
```

**What to do.** Peek non-destructively, then reconcile **each** event against Stripe before deciding.
```bash
docker exec jtoye-rabbitmq rabbitmqadmin get queue=payment.events.dlq \
  ackmode=reject_requeue_true count=10
```

**What NOT to do.** **NEVER ack.** A discarded payment event is a permanent ledger gap, not a lost
notification. Never replay without reconciling first — a double-applied payment event is worse than
a missing one.

## TS-03 — webhook.deliveries.dlq

**What stopped.** Vendor webhook deliveries that exhausted retries. The vendor's integration silently
stops receiving events — no error reaches them, their automation just goes quiet.

**How to see it.**
```bash
docker exec jtoye-rabbitmq rabbitmqctl list_queues name messages | grep webhook.deliveries.dlq
```

**What to do.** Peek non-destructively. Assert on the **delta**, never on absolute depth.
```bash
docker exec jtoye-rabbitmq rabbitmqadmin get queue=webhook.deliveries.dlq \
  ackmode=reject_requeue_true count=10
```

**What NOT to do.** **NEVER ack.** This queue holds **nine real vendor events** as of 2026-07-27,
dead since 2026-07-15, deliberately preserved byte-identical (payloads, `x-death` and `__TypeId__`
intact). **Do not purge them.** 27-03 archives and characterises the batch; 27-02's human checkpoint
decides replay vs discard before the broker volume is destroyed. Do not pre-empt that decision. The
producer defect that filled this queue is already fixed (27-05, `2f8eeca`), so the depth should be
**static, not growing** — a growing depth is a new fault.

## TS-04 — media.process.dlq

**What stopped.** Image-processing jobs that exhausted retries. The vendor's photo stays stuck on
"processing" forever and the product goes to market without an image.

**How to see it.**
```bash
docker exec jtoye-rabbitmq rabbitmqctl list_queues name messages | grep media.process.dlq
```

**What to do.** Peek non-destructively, then cross-reference each job against `media_asset` rows in
`PENDING` or `FAILED` (see TS-07).
```bash
docker exec jtoye-rabbitmq rabbitmqadmin get queue=media.process.dlq \
  ackmode=reject_requeue_true count=10
```

**What NOT to do.** **NEVER ack.** Do not assume a replay will work: if the reaper has already run,
the quarantined bytes are gone and only a fresh vendor upload can recover the asset.

## TS-05 — payment_event_outbox poison

**What stopped.** A payment event that failed to publish often enough to be marked poison. It will
never be retried — the same ledger divergence as TS-02, but silently inside Postgres with no queue
to inspect.

**How to see it.** The table is RLS-scoped; **set the tenant GUC first or it returns zero rows and
reads as clean**.
```sql
SELECT set_config('app.current_tenant_id', '<tenant-uuid>', false);
SELECT id, aggregate_id, attempts, last_error FROM payment_event_outbox WHERE poison = true;
```

**What to do.** Fix the underlying cause, then clear `poison` so the flusher's resurrection pass
picks the row up.

**What NOT to do.** Do not clear `poison` before fixing the cause — the row will simply exhaust its
retries again and mask the real fault with churn.

## TS-06 — media_event_outbox poison

**What stopped.** A media event that failed to publish often enough to be marked poison. The upload
pipeline never starts for that asset — TS-04's symptom with no queue entry to find it by.

**How to see it.** RLS-scoped, same GUC caveat as TS-05.
```sql
SELECT id, aggregate_id, attempts, last_error FROM media_event_outbox WHERE poison = true;
```

**What to do.** Same resurrect procedure as TS-05.

**What NOT to do.** Same as TS-05.

## TS-07 — media_asset FAILED

**What stopped.** A vendor image upload that never reached `ACTIVE`. Since 27-01 (V60) there are
**two sub-cases and they are not equally bad** — read `quarantine_reclaimed_at` before you tell a
vendor anything:

| | sub-case | bytes | recovery |
|---|---|---|---|
| **(a)** | dispatch stall or poison payload — the reaper flipped a stale `PENDING` row whose event *was* dispatched. It performs **no** object delete. | **retained** | vendor presses **Re-process** (`POST /api/v1/media/{assetId}/reprocess`) until `quarantine_expires_at` |
| **(b)** | worker veto on validation, or the retention sweep reclaimed at the horizon | **gone** | re-upload only |

Sub-case (a) is distinguished by `quarantine_reclaimed_at IS NULL`. Before 27-01 every `FAILED`
asset had its bytes deleted first, so this state was unrecoverable by retry in *all* cases and a
broker outage longer than `reaperGraceMs` (900 000 ms) destroyed every upload in flight.

**How to see it.** RLS-scoped.
```sql
SELECT id, tenant_id, created_at, failure_reason,
       quarantine_reclaimed_at, quarantine_expires_at
FROM   media_asset
WHERE  status = 'FAILED';
```

**What to do.** Check **[TS-17](#ts-17)** first — a suspended stall sweep means nothing is being
classified at all, and the rows you are looking at are not the whole picture. Then split the result
on `quarantine_reclaimed_at`: NULL rows are recoverable and the vendor can Re-process them from the
review queue; stamped rows, and anything past `quarantine_expires_at`, need a fresh upload. Check
broker availability across the window before concluding a file was invalid.

**What NOT to do.** Do not tell the vendor their file was rejected until you have ruled out a broker
outage — during one, valid files fail too. Do not tell a sub-case (a) vendor to re-upload; their
original is still there and Re-process is cheaper and lossless.

## TS-08 — webhook_delivery FAILED

**What stopped.** One outbound webhook delivery, terminally. The vendor's system never learns that
this specific order, refund or status change happened, so their data diverges by exactly the events
that failed.

**How to see it.** RLS-scoped.
```sql
SELECT id, subscription_id, event_type, attempts, last_status_code
FROM webhook_delivery WHERE status = 'FAILED';
```

**What to do.** Read `last_status_code`: **4xx** means the vendor endpoint rejected it (their fix);
**5xx or timeout** means theirs was down and replay is appropriate.

**What NOT to do.** Do not replay anything financial without contacting the vendor first — a
duplicate order-paid webhook can trigger duplicate fulfilment on their side.

## TS-09 — webhook_subscription AUTO_PAUSED

**What stopped.** **Every future event** for one vendor subscription, not just the failed one. The
worker flips the subscription to `AUTO_PAUSED` after sustained failure
(`WebhookDeliveryWorker.java:229`) and **nothing tells the vendor**. They discover it from missing
data, not from an error. This is the highest-consequence unowned state in the register.

**How to see it.** RLS-scoped.
```sql
SELECT id, tenant_id, target_url, updated_at FROM webhook_subscription WHERE status = 'AUTO_PAUSED';
```

**What to do.** Confirm the vendor endpoint is healthy, **then notify the vendor before re-enabling**.

**What NOT to do.** Do not silently resume. Re-enabling can flood a vendor with a backlog they did
not expect, at a time they are not watching for it.

## TS-10 — tenants.keycloak_deprovisioned_at NULL on an OFFBOARDED tenant

**What stopped.** Identity-layer offboarding. The tenant is `OFFBOARDED` and `TenantStatusInterceptor`
rejects their requests, but their Keycloak users were never disabled — so a stolen or cached token
can still mint at the IdP.

**How to see it.** The `tenants` table is deliberately RLS-free.
```sql
SELECT id, name, offboarded_at FROM tenants
WHERE status = 'OFFBOARDED' AND keycloak_deprovisioned_at IS NULL;
```

**What to do.** Re-trigger (idempotent):
`POST /api/v1/admin/tenants/{id}/keycloak/deprovision`

**What NOT to do.** Do not assume it worked because the endpoint returned 200 — it returns RFC 7807
`400 "not configured"` unless `jtoye.keycloak.admin.enabled=true` and a base-url is set. Re-read the
column.

## TS-11 — onboarding stalled

**What stopped.** A vendor's application to join the platform. The `ONBOARDING_STALLED` event is
published correctly and reaches the outbox — and then **nobody receives it**, because no cross-tenant
operator identity exists in this system. A prospective vendor waits indefinitely.

**How to see it.** RLS-scoped, so **loop tenants or the result is empty and reads as clean**.
```sql
SELECT id, tenant_id, status, updated_at FROM vendor_onboarding
WHERE status IN ('VERIFYING','ACTION_REQUIRED') AND updated_at < now() - interval '7 days';
```
Then work `GET /api/v1/onboarding/admin/reviews` — which is tenant-scoped and **cannot show you
every tenant at once**.

**What NOT to do.** Do not treat an empty result as "no stalled applications" without confirming the
tenant GUC was set. Do not assign an owner in the register: closing this needs a product decision
about operator identity, not a rule.

## TS-12 — scrape target down

**What stopped.** All observability for one service. Every alert depending on that target's metrics
evaluates against zero series and stays inactive — the monitoring reports healthy *because* it can
see nothing.

**How to see it.**
```bash
curl -sG http://localhost:9091/api/v1/targets --data-urlencode 'state=any' \
  | jq -r '.data.activeTargets[] | "\(.labels.job) \(.health) \(.lastError)"'
bash scripts/check-alert-liveness.sh
```

**What to do.** Fix the scrape path. The core-java port is injected via `CORE_JAVA_METRICS_PORT`
(dev/compose `9090`, prod/k8s `9091`) — change the variable, never the rendered file.

**What NOT to do.** Do not "fix" `CORE_JAVA_METRICS_PORT` back to 9091 on compose: that re-blinds six
alerts and mis-binds two more. Do not read a firing `ServiceDown` as "someone was told" — see the
note at the top of this file.

## TS-13 — exporter up but blind (PostgreSQL)

**RESOLVED.** The rule half landed with 27-03 — `DatabaseDown` reads
`up{job="postgres"} == 0 or pg_up == 0` (`alerts.yml:85`). The register row was left deferred until
2026-07-29 on a reason that had already become false, which is its own lesson: **a deferral is only
re-read on its expiry date**, so this one would have sat stale until 2026-09-30. See TS-15.

**What stopped.** Every PostgreSQL metric. `DatabaseDown` watched `up{job="postgres"}`, which is 1
because the *exporter's* HTTP endpoint answers, so the database reported healthy while nothing about
it was measured. `TooManyDatabaseConnections` matched zero series for the same reason.

**How to see it.** The disagreement between these two **is** the state:
```bash
curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=pg_up'
curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=up{job="postgres"}'
docker logs jtoye-postgres-exporter --tail 20
```

**What to do.** Two causes were found **stacked** here: `sslmode=require` against a server with
`ssl=off`, and a `POSTGRES_EXPORTER_PASSWORD` that never matched the database. TLS negotiation
precedes authentication, so the first masked the second entirely. Assert `pg_up == 1` after any fix.

**What NOT to do.** Do not verify credentials over `127.0.0.1` from inside the postgres container —
stock `pg_hba.conf` maps loopback to `trust`, so **any** password succeeds and the check proves
nothing. Test over the container network. Do not stop at the first cause.

## TS-14 — alert bound to the wrong subject

**What stopped.** core-java heap exhaustion and GC storms are unobserved. `HighMemoryUsage`
(`alerts.yml:96`) and `FrequentGarbageCollection` (`:111`) carry `service: core-java` but their
`jvm_*` selectors are unqualified, so while core-java's target was down they bound to the only JVM
Prometheus could see — **Keycloak's**.

**How to see it.**
```bash
curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=count(jvm_memory_used_bytes) by (job)'
```
Both `job="core-java"` and `job="keycloak"` must appear. Before the scrape-port fix only `keycloak`
did.

**What to do.** Qualify every rule selector by the job matching its `service:` label. L-2b in
`check-alert-liveness.sh` enforces this.

**What NOT to do.** Do not accept "the selector matches ≥1 series" as proof a rule works — that is
exactly the check these two rules pass while watching the wrong process.

## TS-15 — RedisDown watches the scrape target, not the exporter gauge

**RESOLVED 2026-07-29** (issue #342 item 5). `RedisDown` now evaluates
`up{job="redis"} == 0 or redis_up == 0`. Kept here because the *state* still exists — only the
blindness to it is gone — and because how it was proven is the reusable part.

**What stopped.** Nothing ever, observably — this was **latent**, which is why it outlived the
review that fixed its postgres twin. `RedisDown` evaluated `up{job="redis"} == 0`, which only proves
the exporter answers. `redis_up`, the exporter's own upstream-health gauge, was live and referenced
by **no rule**. Sessions and cache could have been entirely unreachable while `RedisDown` stayed
inactive.

**How to see it.** Same shape as TS-13 — the disagreement between the two IS the state:
```bash
curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=redis_up'
curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=up{job="redis"}'
grep -c redis_up infra/monitoring/prometheus/alerts.yml    # >= 1 since the fix; was 0
```

**How it was proven — induce it, do not argue it.** A latent defect cannot be verified by observing
the healthy state, and both gauges read 1 in steady state, so the fix and the bug are
indistinguishable there. Stop Redis and leave the exporter up:
```bash
docker stop jtoye-redis        # exporter keeps answering; this is the state that matters
# wait for one scrape, then:
curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=up{job="redis"} == 0'
curl -sG http://localhost:9091/api/v1/query --data-urlencode 'query=up{job="redis"} == 0 or redis_up == 0'
docker start jtoye-redis
```
Measured 2026-07-29: `up{job="redis"}`=**1**, `redis_up`=**0**, old expression **0 samples**, new
expression **1 sample**; both 0 again after restore. Without the down-arm the two expressions are
identical, so a verification run only in the healthy state proves nothing at all.

**What NOT to do.** Do not treat this as postgres-specific. Measured 2026-07-27: `pg_up` 0 rule
references, `redis_up` 0, `rabbitmq_up` no series at all. Any gate written for one exporter must
generalise to all of them — `check-alert-liveness.sh` L-1b VOIDs on an exporter job it has no gauge
mapping for, which is what keeps that generalisation from rotting.

## TS-16 — running container config drifted from the compose file

**What stopped.** Nothing user-facing — but this is the detection gap that lets everything else here
go stale unnoticed. `check-runtime-freshness.sh` compares **built** services against their source; a
service running a third-party image whose **compose config** changed is outside its scope entirely.

**How to see it.** Compare content, not timestamps:
```bash
docker inspect jtoye-<svc> --format '{{json .Config.Healthcheck}}'
# vs the service block in infra/monitoring/docker-compose.monitoring.yml
```

**What to do.** Recreate the affected service. A plain `restart` does **not** re-read config, and the
compose project directory is `infra/monitoring/`, which has no `.env`:
```bash
docker compose --env-file "$(git rev-parse --show-toplevel)/.env" \
  -f infra/monitoring/docker-compose.monitoring.yml up -d --force-recreate <svc>
```

**What NOT to do.** Do not detect this by comparing container-creation time against commit time. That
produces false positives — it flagged `jtoye-prometheus` as stale when the container had been
recreated an hour *before* the commit touching its block. **Proven instance:**
`jtoye-redis-exporter` ran a `wget` healthcheck its scratch-based image cannot execute, reporting
"unhealthy" for 20 days while working perfectly; the compose file had already removed that
healthcheck in `7dcaf93`, 84 minutes after the container was created.

## TS-17 — media stall sweep suspended

> Filed as TS-17, not TS-13. The 27-01 plan drafted this row as "TS-13"; that id was already taken by
> the PostgreSQL exporter row. The number moved, the content did not.

**What stopped.** Classification of **every** stalled upload, platform-wide. `MediaPendingReaper`
probes the dispatch path before it touches any tenant, and when that probe fails the whole tick
returns at `MediaPendingReaper.java:185`. This **fails closed by design** (D-05): while suspended, no
`PENDING` asset can be wrongly flipped to `FAILED` — but none is classified either, so a vendor sees
the DELAYED affordance indefinitely instead of a terminal state with a Re-process button.

Eight distinct causes share this one state, and the log names which one fired:

```
BROKER_ADMIN_ABSENT · QUEUE_INFO_THREW · QUEUE_INFO_NULL · BROKER_WIDE_ZERO_CONSUMERS
REGISTRY_ABSENT · NO_LOCAL_CONTAINER · LOCAL_CONTAINER_STOPPED · LOCAL_CONTAINER_NO_CONSUMERS
```

**How to see it.** The counter is the signal, but read the log line first — it tells you which probe
failed, which the counter cannot:
```bash
docker compose -f docker-compose.full-stack.yml logs core-java \
  | grep 'event=media_reaper_suspended'
# -> event=media_reaper_suspended reason=LOCAL_CONTAINER_STOPPED
```
```promql
increase(media_reaper_suspended_total[30m]) > 0
```

**What to do.** Confirm the broker is reachable and that `media.process` has consumers
(`rabbitmq_queue_consumers{queue="media.process"}`, or `rabbitmqctl list_queues name consumers`). If
the queue is healthy, the local listener container is the suspect — restart `core-java` and re-check
the log line above.

**Urgency.** `MediaQuarantineRetentionSweep` is deliberately **not** gated on this and keeps
reclaiming bytes at `jtoye.media.quarantine-retention-ms` (72 h default). A suspension outlasting
that horizon silently converts recoverable stalled uploads into expired, unrecoverable ones — it
turns [TS-07](#ts-07) sub-case (a) into sub-case (b), one asset at a time.

**What NOT to do.** Do not "fix" a suspension by disabling the liveness probe. The probe is what
stops the reaper destroying user data during a broker outage; removing it restores the exact defect
27-01 exists to close. Also do not read a suspension as proof the whole platform's dispatch is dead —
the probe only sees **this JVM's** listener registry, so a remote replica's dead consumer is *not*
observable here, and a healthy local probe does not clear a remote one.

**Absent series is not absent risk.** Micrometer only exports a counter after its first increment, so
`media_reaper_suspended_total` does **not** appear in `/actuator/prometheus` on a stack that has never
suspended. Its sibling `media_reaper_undispatched_skipped_total` is present precisely because it *has*
fired. Any 27-03 rule written against this metric must confirm the series exists first, or it will be
a rule matching zero series — the F-1 defect that motivated this register.

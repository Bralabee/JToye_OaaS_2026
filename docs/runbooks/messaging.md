# Runbook — RabbitMQ consumer tuning

Owner: Phase 27 / plan 27-04. Scope: the `jtoye.rabbit.*` listener tunables and the eight
outbox-flusher keys. Alert rules and DLQ triage are **not** here — `docs/runbooks/alerts.md`
owns those (27-03).

---

## 1. The thing to understand before changing anything

`spring.rabbitmq.listener.simple.*` **does nothing in this application.**

`RabbitMQConfig` declares a bean named `rabbitListenerContainerFactory`. Boot's
`RabbitAnnotationDrivenConfiguration.simpleRabbitListenerContainerFactory` is annotated
`@ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")`, so Boot's factory backs off
— and `SimpleRabbitListenerContainerFactoryConfigurer`, the only consumer of that property
family, never runs.

27-04 repaired this: the factory now applies the configurer when it is present, then re-asserts
this project's three deliberate overrides, then the `jtoye.rabbit.*` values. **Tune through
`jtoye.rabbit.*`, not through `spring.rabbitmq.listener.simple.*`.**

If you set a `spring.rabbitmq.listener.simple.*` value and nothing changes, that is why.

---

## 2. Keys, units, defaults

| Key | Env | Unit | Default | What it governs |
|---|---|---|---|---|
| `jtoye.rabbit.default-prefetch` | `JTOYE_RABBIT_DEFAULT_PREFETCH` | messages | `250` | Unacked window for the eight non-media queues |
| `jtoye.rabbit.default-concurrency` | `JTOYE_RABBIT_DEFAULT_CONCURRENCY` | consumers | `1` | Consumers per non-media queue |
| `jtoye.rabbit.media-prefetch` | `JTOYE_RABBIT_MEDIA_PREFETCH` | messages | `2` | Unacked window on `media.process` |
| `jtoye.rabbit.media-concurrency` | `JTOYE_RABBIT_MEDIA_CONCURRENCY` | consumers | `1` | Starting consumers on `media.process` |
| `jtoye.rabbit.media-max-concurrency` | `JTOYE_RABBIT_MEDIA_MAX_CONCURRENCY` | consumers | `2` | Ceiling under sustained backlog |
| `payment.outbox.flush-interval-ms` | `PAYMENT_OUTBOX_FLUSH_INTERVAL_MS` | ms | `5000` | Payment outbox publish sweep |
| `payment.outbox.resurrect-interval-ms` | `PAYMENT_OUTBOX_RESURRECT_INTERVAL_MS` | ms | `300000` | FAILED → PENDING sweep |
| `payment.outbox.backoff-base-ms` | `PAYMENT_OUTBOX_BACKOFF_BASE_MS` | ms | `5000` | Retry backoff base |
| `payment.outbox.backoff-cap-ms` | `PAYMENT_OUTBOX_BACKOFF_CAP_MS` | ms | `300000` | Retry backoff cap |
| `media.outbox.flush-interval-ms` | `MEDIA_OUTBOX_FLUSH_INTERVAL_MS` | ms | `5000` | **Paces the whole media pipeline — see §4** |
| `media.outbox.resurrect-interval-ms` | `MEDIA_OUTBOX_RESURRECT_INTERVAL_MS` | ms | `300000` | FAILED → PENDING sweep |
| `media.outbox.backoff-base-ms` | `MEDIA_OUTBOX_BACKOFF_BASE_MS` | ms | `5000` | Retry backoff base |
| `media.outbox.backoff-cap-ms` | `MEDIA_OUTBOX_BACKOFF_CAP_MS` | ms | `300000` | Retry backoff cap |

The eight outbox keys existed only as inline `@Value`/`@Scheduled` defaults before 27-04 — there
was no way to change a flush interval without rebuilding the image. The values above are
byte-identical to those defaults, so surfacing them changed no behaviour.

---

## 3. Why the numbers are what they are

From `infra/load-testing/baselines/2026-07-28-media-A-baseline.md` (200 uploads, container pinned
to 1 CPU to match the `1000m` pod limit):

```
mean service time   606.0 ms/message
throughput          1.650 msg/s/consumer
peak container CPU  97.8%   (mean 21.3%)
peak queue depth    0       across all 197 samples
peak unacked        0
```

- **`media-concurrency: 1`** — one consumer already peaks a full core. The pod limit is `1000m`
  and `scrimage-webp` forks a native `cwebp` per encode (two encodes per message), so a second
  consumer competes for a core that is already saturated.
- **`media-max-concurrency: 2`** — a burst ceiling, not a target. Both connection budgets land on
  2 independently; see §5.
- **`media-prefetch: 2`** — low on purpose. At 250 the defect is **unfair distribution across
  replicas**, not head-of-line blocking within one: on a burst the first replica to attach can
  buffer up to 250 unacked messages while the other two sit idle. That gets worse as replicas
  rise (production runs 3). 2 hides fetch latency at ~600 ms of service time while leaving the
  rest of a burst available to other replicas.
- **`default-concurrency: 1`** — do **not** raise this globally. `order.state-changes` above
  concurrency 1 loses per-order ordering: `processed_order_events` is keyed
  `(tenant_id, order_id, new_status)`, which prevents *repeats*, not *reordering*, so a PREPARING
  email could overtake a CONFIRMED one. The SSE fan-out queue is a per-JVM `AnonymousQueue` where
  extra consumers only churn UI ordering.

---

## 4. The media pipeline is outbox-paced, not queue-paced

The single most useful operational fact here, and it is counter-intuitive.

Arm A measured a **peak `media.process` queue depth of 0** — under a sequential driver *and*
under an 8-way concurrent burst. The queue never backs up because `media.outbox.flush-interval-ms`
(5000) governs arrival: uploads land in `media_event_outbox` and reach the broker in ~5-second
batches, which one consumer at 606 ms/message drains inside the interval.

**Consequences when triaging "media is slow":**

1. A queue depth of 0 does **not** mean the pipeline is idle. Check
   `jtoye_media_process_seconds_count` on the scrape endpoint, not the queue.
2. Raising `media-concurrency` will not help if there is no backlog. Confirm depth is actually
   non-zero first.
3. If uploads are visibly slow to become ACTIVE and the queue is empty, the flush interval is the
   suspect — lower `MEDIA_OUTBOX_FLUSH_INTERVAL_MS` before touching concurrency.

---

## 5. Before raising `media-max-concurrency`

Two gates bound it from opposite directions and both are tight at the shipped values:

```bash
bash k8s/scripts/check-consumer-thread-budget.sh   # intra-pod:  Σconcurrency + httpReserve <= pool
bash k8s/scripts/check-connection-math.sh          # cluster-wide: replicas × pool + extras <= 157
```

- **3 media consumers** breaks the intra-pod budget: 8 default + 3 media + 2 reserve = 13 > pool 12.
- **Raising `DB_POOL_SIZE` to 13** to accommodate that breaks the cluster-wide budget:
  11 × 13 + 23 = 166 > 157.

So raising concurrency past 2 is not a config change — it requires lowering HPA `maxReplicas` or
raising Postgres `max_connections` everywhere it is set. Treat it as a deliberate, separate change
with its own measurement.

`httpReserve` is 2 and is **not** the knob to lower. It exists because no readiness *group* is
configured in any `application*.yml`, so Spring's default applies and
`/actuator/health/readiness` contains `readinessState` only — never the `db` indicator. A pod with
zero free connections keeps returning 200, so Kubernetes keeps routing traffic to it and the
starvation is invisible to the orchestrator.

---

## 6. Verifying a change actually took effect

A correct `application.yml` over a stale image is indistinguishable from a working change.
Verify from the **running** system, two independent ways.

```bash
# 1. The broker's view — out-of-band, immune to a stale image.
CORE_CID="$(docker compose -f docker-compose.full-stack.yml ps -q core-java | head -1)"
curl -sf -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" \
  http://localhost:15672/api/queues/%2F/media.process \
  | jq '{consumers, prefetch: [.consumer_details[].prefetch_count]}'

# 2. The running artifact's own startup line (one per factory, so expect 2).
docker logs "$CORE_CID" 2>&1 | grep 'event=rabbit_factory_configured'
```

Note `docker exec jtoye-core-java` does **not** work — `docker-compose.full-stack.yml` removed
`container_name` to support `--scale`, so the id must be derived as above.

To read the shipped config out of the fat jar rather than the source tree:

```bash
docker exec "$CORE_CID" sh -c 'unzip -p /app/app.jar BOOT-INF/classes/application.yml' \
  | grep -A6 'rabbit:'
```

A filesystem `find` inside the container returns a misleading `0` — the config is inside the
archive, not on the filesystem.

---

## 7. Re-running the measurement

```bash
./infra/load-testing/media-pipeline-arm.sh <label> 200
```

It pins the container to 1 CPU (matching the pod limit), drives real JPEG uploads, samples the
broker at 1 Hz, and asserts `media.process.dlq` did not grow. It releases the CPU pin via a
`trap` on every exit path.

**Do not release the pin with `docker update --cpus=0`** — measured on Docker 29.6.2, that exits
0 and changes nothing. Use `--cpu-quota=-1`, and verify with the container's own cgroup:

```bash
docker exec "$CORE_CID" cat /sys/fs/cgroup/cpu.max    # "max 100000" = released
```

`docker inspect -f '{{.HostConfig.NanoCpus}}'` is stale metadata and reads identically in the
pinned and released states — it cannot tell you which one you are in.

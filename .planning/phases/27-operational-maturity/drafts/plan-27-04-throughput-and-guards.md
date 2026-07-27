---
phase: 27-messaging-layer-hardening
plan: 04
type: execute
wave: 2
depends_on: []
files_modified:
  - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  - core-java/src/main/java/uk/jtoye/core/config/RabbitListenerProperties.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingMetrics.java
  - core-java/src/main/java/uk/jtoye/core/websocket/StompDestinations.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java
  - core-java/src/main/resources/application.yml
  - core-java/src/main/resources/application-prod.yml
  - core-java/src/main/resources/application-staging.yml
  - core-java/build.gradle.kts
  - .env.example
  - k8s/base/configmap.yaml
  - k8s/base/core-java-deployment.yaml
  - k8s/scripts/check-connection-math.sh
  - infra/load-testing/media-pipeline-baseline.sh
  - docs/runbooks/messaging.md
  - core-java/src/test/java/uk/jtoye/core/config/RabbitListenerContainerFactoryTest.java
  - core-java/src/test/java/uk/jtoye/core/config/MediaListenerConcurrencyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/websocket/StompDestinationsTest.java
  - core-java/src/test/java/uk/jtoye/core/websocket/StompPublishGuardTest.java
  - docs/metrics.json
autonomous: false
requirements: [MSG-01, MSG-02, MSG-03]

must_haves:
  truths:
    - "The container factory HONOURS the Spring config layer — proven by the fail direction, where setting the yml alone (with the factory left hand-built) changes nothing at the running broker"
    - "media.process runs at a measured concurrency with a low prefetch; every other queue keeps concurrency 1, so per-order STOMP/email ordering is not traded away"
    - "The chosen numbers come from a recorded baseline-vs-candidate measurement under a 1-CPU constraint, with the control arm run"
    - "Consumer count is read from the RUNNING broker, never from the yml source"
    - "A slashed STOMP destination cannot be published, not merely cannot be subscribed to"
    - "Every new tunable is env-indirected in application.yml, .env.example and the k8s ConfigMap, so check-env-contract.sh covers it"
    - "check-connection-math.sh accounts for consumer threads, so a future concurrency bump cannot silently starve the HTTP pool"
  artifacts:
    - path: "core-java/src/main/java/uk/jtoye/core/config/RabbitListenerProperties.java"
      provides: "@ConfigurationProperties(prefix = \"jtoye.rabbit\") — the single source of the listener tunables"
    - path: "infra/load-testing/media-pipeline-baseline.sh"
      provides: "The smallest honest measurement: burst upload + broker-sampled queue depth + Timer-derived latency"
    - path: "core-java/src/test/java/uk/jtoye/core/config/RabbitListenerContainerFactoryTest.java"
      provides: "Asserts the factory applies the configured prefetch/concurrency — the regression guard for the inert-factory class"
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java"
      to: "SimpleRabbitListenerContainerFactoryConfigurer"
      via: "constructor injection into the factory bean"
      pattern: "SimpleRabbitListenerContainerFactoryConfigurer"
---

<objective>
Make the RabbitMQ consumer layer configurable, measured and guarded.

Three things are wrong and they are causally linked. (1) The listener container factory is
hand-built, so Spring Boot's factory backs off and the `spring.rabbitmq.listener.simple.*`
property family is **inert** — the media worker runs one thread per replica holding a 250-message
prefetch, and 22 test files' `auto-startup=false` has never once taken effect. (2) Nobody can pick a
replacement number, because the project has no load baseline of any kind. (3) The #266 destination
shape guard runs on SUBSCRIBE only, and the sole publish path swallows its own failures.

Output: a factory that honours the config layer, a dedicated low-prefetch/higher-concurrency
container for `media.process` only, a measurement harness that produces the number, a publish-side
shape guard, and the six orphaned outbox keys surfaced in the config layer.

**This plan is `autonomous: false`.** Task 1 is a measurement whose result Tasks 3 and 6 consume;
the numbers in this document are placeholders until that run fills them.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md

<interfaces>
<!-- Everything below was read from source, from library bytecode, or from a real test-run
     artifact during planning. Where the brief's framing was wrong it is corrected and marked.
     Do not substitute a remembered value for one of these. -->

## A. Consumer concurrency and prefetch — CONFIRMED, and the cause is not the one stated

**A1 — confirmed.** There is no `prefetch`, `concurrentConsumers`, `concurrency` or
`listener.` anywhere under `core-java/src/main/resources/`. Verified with a positive control on the
same directory (`grep -rc "rabbitmq" core-java/src/main/resources/*.yml` → `application.yml:3`), so
this is a real zero and not an already-0 grep.

**A2 — THE CORRECTION. The properties would not have worked even if they were set.**
`RabbitMQConfig.java:402-411` declares:

```java
@Bean
public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
```

The bean name is `rabbitListenerContainerFactory`. Boot's
`RabbitAnnotationDrivenConfiguration.simpleRabbitListenerContainerFactory` is annotated
`@ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")` — read from the bytecode of
`spring-boot-autoconfigure-3.5.16.jar` during planning (`javap -v` on
`RabbitAnnotationDrivenConfiguration.class`, `name=["rabbitListenerContainerFactory"]`). So Boot's
factory backs off, and `SimpleRabbitListenerContainerFactoryConfigurer` — **the only consumer of
`spring.rabbitmq.listener.simple.*`** — is never applied to this project's factory.

Consequence: adding `spring.rabbitmq.listener.simple.prefetch: 4` to `application.yml` today would
be a no-op that reads as a fix. **The factory must be repaired before any property can matter.**
This is the single most important finding in this plan and the fail direction of AC-2 is built on it.

**A3 — confirmed defaults.** With the configurer never running, the container's own defaults apply:
`AbstractMessageListenerContainer.DEFAULT_PREFETCH_COUNT = 250` (bytecode-verified via
`javap -constants` on `spring-rabbit-3.2.12.jar`) and `SimpleMessageListenerContainer` starts one
consumer per queue. Boot's own configuration metadata lists `spring.rabbitmq.listener.simple.prefetch`
and `.concurrency` with `defaultValue: null`, i.e. Boot adds no override of its own. So the brief's
"prefetch 250, one consumer thread per queue" is right, for a different reason.

**A4 — confirmed CPU-heavy and serial.** `MediaProcessingWorker.onMediaEvent`
(`MediaProcessingWorker.java:89-121`) is a single `@RabbitListener` on `media.process`.
`MediaNormalizer.normalize` (`MediaNormalizer.java:75-108`) runs, in order: magic-byte sniff →
header-only megapixel bomb guard → full decode → aspect-fit → WebP encode → thumbnail bound → second
WebP encode. Two encodes per message.

**A5 — CORRECTION to the head-of-line framing.** With exactly one consumer the messages are serial
regardless of prefetch, so prefetch 250 does not add head-of-line blocking *within* a replica. What
it does add is **unfair distribution across replicas**, and that is strictly worse at 3 replicas
than at 1: on a burst of 300 uploads the first replica to attach can buffer up to 250 unacked
messages in its local prefetch window while replicas 2 and 3 sit idle with nothing to fetch. Replica
counts are `k8s/base/core-java-deployment.yaml:10` = 3, `k8s/staging/kustomization.yaml:63-65` = 2,
`k8s/production/kustomization.yaml:60-62` = 3, `k8s/local/kustomization.yaml:162-164` = 1. State the
defect as unfair distribution, not as head-of-line blocking.

**A6 — NEW, not in the brief, and it caps the answer.** The pod CPU limit is `1000m` and memory
`1Gi` (`k8s/base/core-java-deployment.yaml:432-436`). WebP encoding goes through
`scrimage-webp:4.6.6` (`core-java/build.gradle.kts:54-55`), which forks the native `cwebp` binary
per encode (`core-java/Dockerfile:33` pins `-Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin`). So
concurrency above ~2 on a 1-core pod buys no throughput and costs process and RSS headroom against a
1Gi limit whose JVM already commits 75% as heap (`core-java/Dockerfile:60,76-77`). **The measurement
in Task 1 must be run under a 1-CPU constraint or it will produce a number that only exists on the
dev workstation.**

**A7 — NEW, and it settles the global-vs-per-listener question.** `OrderStateChangeListener`
(`OrderStateChangeListener.java:75-123`) at concurrency > 1 loses per-order ordering. Its dedup table
`processed_order_events` is keyed `(tenant_id, order_id, new_status)` — it prevents *repeats*, not
*reordering*. Two rapid transitions on one order could deliver the PREPARING email before the
CONFIRMED one and push the KDS states out of order. A global concurrency setting therefore trades
away a working good to fix an unrelated queue. **Reject global; go per-listener.**

**A8 — NEW.** `orderEventsFanoutQueue` is an `AnonymousQueue`, which is
`durable=false, exclusive=true, autoDelete=true` (bytecode-verified on
`spring-amqp-3.2.12.jar`; also asserted by `RabbitMQConfigFanoutQueueTest.fanoutQueueIsEphemeral`).
It is a per-JVM SSE fan-out queue: extra consumers on it add nothing and churn UI ordering. Leave at 1.

**A9 — listener inventory (8 endpoints), with each one's DB coupling, verified by reading each file:**

| Endpoint | Queue | Tenant GUC pin | DB work | Concurrency verdict |
|---|---|---|---|---|
| `MediaProcessingWorker:89` | `media.process` | yes, `:95-102`, `@Transactional` | yes | **RAISE** — the only CPU-bound consumer |
| `OrderStateChangeListener:75` | `order.state-changes` | yes, `:84-91`, `@Transactional` | yes | keep 1 (A7 ordering) |
| `OrderNotificationListener:50` | `order.notifications` | yes, `:61-64`, `@Transactional` | yes | keep 1 |
| `OnboardingNotificationListener:42` | `onboarding.notifications` | yes, `:52-55`, `@Transactional` | yes | keep 1 |
| `FinancialNotificationListener:47,58` | `payment.notifications`, `refund.notifications` | yes, `:68-71`, `@Transactional` | yes | keep 1 |
| `WebhookFanoutListener:52` | `webhook.deliveries` | yes, `:118`, `:174-176` via `TransactionTemplate` | yes | keep 1 |
| `PaymentEventAuditListener:19` | `payment.events` | **none** | **none** — pure `log.info`/`log.warn` | keep 1 (nothing to gain) |
| `OrderSseFanoutListener:36` | per-JVM `AnonymousQueue` | none | none — in-memory CHM emitters | keep 1 (A8) |

## B. `setForkEvery(4)` — comment verified, mechanism now PROVEN, root cause identified

`setForkEvery(4)` is at `core-java/build.gradle.kts:144`; the comment the brief cites is at
`:136-143`. Its claim — "RabbitMQ listener + reactive HttpClient selector threads are not all
reclaimed between classes" — is **confirmed from a real run artifact**, not inferred.
`core-java/build-local/test-results/integrationTest/TEST-uk.jtoye.core.media.MediaProcessingWorkerIntegrationTest.xml`
(run 2026-07-25T22:58:24, 4 tests, 0 failures) contains:

- 54 occurrences of `SimpleMessageListenerContainer`, including stack frames through
  `SimpleMessageListenerContainer$AsyncMessageProcessingConsumer.run(SimpleMessageListenerContainer.java:1328)`
- 45 occurrences of `CachingConnectionFactory`
- 27 occurrences of `Attempting to connect to: [localhost:0]`

So listener containers **do** start in the Testcontainers suite and **do** spawn consumer threads
that connect-retry a dead port for the life of the context.

**The root cause is A2.** Twenty-two test files register
`spring.rabbitmq.listener.simple.auto-startup=false` — including the shared
`IntegrationTestSupport.java:67`, whose comment calls it "belt-and-braces … so the context boots
without a live broker even if a future profile tweak re-enables it". Because Boot's configurer never
runs, that property has never had any effect; the only thing preventing a broker connection is the
dead port `spring.rabbitmq.port=0` at `IntegrationTestSupport.java:66`. Repairing the factory makes
22 files' stated intent effective for the first time, which should remove the thread accumulation and
may retire or relax `forkEvery(4)`.

**Do not assume it.** Task 6 measures the suite with and without `forkEvery` and only then decides.
Note also that `application-test.yml:5-7` excludes `RabbitAutoConfiguration`, yet the artifact above
shows a live `CachingConnectionFactory` — so that exclusion is not taking effect either at runtime.
Task 6 must record what actually changes rather than reason forward from the yml.

## C. No load baseline — CONFIRMED, and it is a genuine blocker

`docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md:134` (P3-13, issue #115): "No load-test baseline of
any kind (no k6/Gatling/hey scripts or results; `infra/load-testing/` unreferenced by any workflow)
— every peak-load claim is untested." `infra/load-testing/load-test.sh` is the directory's only file,
drives `hey`/`ab` against `$API_BASE_URL` HTTP endpoints only, and is referenced by nothing in
`.github/`, `scripts/` or a Makefile (grep returned no hits).

**Existing instrumentation, checked before proposing any new meter:**

- `BusinessMetricsService.java:38` holds the **only** `io.micrometer.core.instrument.Timer` in main
  source (`jtoye.orders.fulfillment`). There is no timer on any consumer path.
- Counters only: `payment.outbox.dead_letter` / `payment.outbox.resurrected`
  (`PaymentEventOutboxFlusher.java:95,100`), `media.outbox.dead_letter` / `media.outbox.resurrected`
  (`MediaEventOutboxFlusher.java:85,90`), `tenant.context.missing` (`JwtTenantFilter.java:45`),
  `jtoye.ratelimit.fail_open` (`RateLimitInterceptor.java:64`).
- So there is **no** meter for media processing latency, queue depth or consumer utilisation. Exactly
  one new `Timer` is justified; it must follow the established null-safe
  `ObjectProvider<MeterRegistry>` idiom used by all five counters above.
- `media_asset` has `created_at` (`V53__*.sql:70`) but **no `updated_at`**, so per-message latency
  cannot be derived from the database. The Timer is required, not optional.
- Compose already exposes the scrape endpoint locally: `docker-compose.full-stack.yml:209` sets
  `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,info,prometheus`, and the base profile
  (`application.yml:275`) would otherwise expose only `health,info`.
- The broker's management API is reachable at `localhost:15672`
  (`docker-compose.full-stack.yml:152`, image `rabbitmq:3.12-management-alpine`) — this is the
  out-of-band instrument for queue depth and consumer count.

## D. STOMP publish-side guard — CONFIRMED exactly as described

- Guard: `TenantChannelInterceptor.java:136-141`, inside the SUBSCRIBE branch only.
- Sole builder: `StompDestinations.kitchen` (`StompDestinations.java:51-53`).
- Sole publisher in main source: `SimpMessagingTemplate.convertAndSend` appears **once**, at
  `OrderStateChangeListener.java:111`, wrapped by `try { … } catch (Exception e) { log.warn(…) }`
  at `:107-117`. A rejected destination would be a WARN line and nothing else — the KDS silently
  stops updating.
- `StompDestinationsTest` has 4 tests, all asserting properties of the string `kitchen()` returns.
  None can observe a future hand-built destination passed straight to `convertAndSend`.

## E. Config strategy — CONFIRMED, with a sharper form than stated

Six keys exist only as `@Value`/`@Scheduled` inline defaults and appear in **no** yml, env file,
`.env.example`, k8s manifest or shell script (grep over `*.yml *.yaml *.env* *.example *.sh` returned
nothing):

| Key | Declared at |
|---|---|
| `payment.outbox.flush-interval-ms` | `PaymentEventOutboxFlusher.java:156` |
| `payment.outbox.resurrect-interval-ms` | `PaymentEventOutboxFlusher.java:200` |
| `payment.outbox.backoff-base-ms` | `PaymentEventOutboxFlusher.java:80` |
| `payment.outbox.backoff-cap-ms` | `PaymentEventOutboxFlusher.java:81` |
| `media.outbox.flush-interval-ms` | `MediaEventOutboxFlusher.java:120` |
| `media.outbox.resurrect-interval-ms` | `MediaEventOutboxFlusher.java:156` |
| `media.outbox.backoff-base-ms` | `MediaEventOutboxFlusher.java:71` |
| `media.outbox.backoff-cap-ms` | `MediaEventOutboxFlusher.java:72` |

(Eight, not six — the brief undercounted.) **Yes, this violates the project rule**, and the
contrapositive is one file away: `jtoye.media.reaper-interval-ms` uses the identical
`@Scheduled(fixedDelayString = …)` shape at `MediaPendingReaper.java:55` and *is* surfaced at
`application.yml:214` as `reaper-interval-ms: ${MEDIA_REAPER_INTERVAL_MS:600000}`. So the project's
own standard is met for one scheduler and missed for two. (`jtoye.media.reaper-grace-ms`,
`MediaProperties.java:66`, is also unsurfaced — a third, smaller instance.)

**Why the existing gate cannot catch this, and what that means for the new tunables.**
`k8s/scripts/check-env-contract.sh` compares *uppercase `${}` env placeholders* in
`application*.yml` against the env names injected by `k8s/base/core-java-deployment.yaml`. The eight
keys above are lowercase Spring property placeholders with inline defaults and no env indirection at
all, so they are invisible to both directions of the gate. It follows that **every new tunable this
plan introduces must be declared env-indirected in `application.yml`** (`${JTOYE_RABBIT_…:n}`) — not
as a bare `@Value` default — precisely so the gate does cover it. Declaring them the other way would
create a ninth instance of the same defect.

## F. The connection-budget interaction — NEW, and it is why "global concurrency" is unsafe

`k8s/scripts/check-connection-math.sh` parses HPA `maxReplicas` × Hikari pool size and asserts the
total fits Postgres `max_connections=200` with ≥20% headroom. It knows **nothing about consumer
threads**. Prod/staging pool is `${DB_POOL_SIZE:10}` per pod
(`application-prod.yml:14`, `application-staging.yml:11`); base/dev is 20 (`application.yml:18`).

There are 8 listener endpoints. Today that is at most 8 consumer threads per pod against a pool of
10 — already tight, and six of the eight take a connection. A global `concurrency: 5` would be 40
threads competing for 10 connections, starving HTTP request handling: a self-inflicted DoS that the
existing gate would pass. This is a second, independent reason the answer is per-listener, and the
gate must be extended to include the consumer-thread term.
</interfaces>
</context>

<decisions>

- **D-01 — Repair the factory before touching any property.** Inject Boot's
  `SimpleRabbitListenerContainerFactoryConfigurer` into the existing bean and call
  `configurer.configure(factory, connectionFactory)` **first**, then re-apply the project's three
  deliberate overrides (`jsonMessageConverter`, `retryInterceptor` advice chain,
  `defaultRequeueRejected=false`) **after**, so the config layer is honoured without losing any
  current behaviour. Do not delete the bean and fall back to Boot's: that would drop the retry
  interceptor and the DLQ routing contract.

- **D-02 — Per-listener, not global.** A9 shows exactly one of eight consumers benefits, and A7/A8/F
  show a global bump actively harms three of the others. Global default stays concurrency 1.

- **D-03 — A dedicated `SimpleRabbitListenerContainerFactory` for `media.process`, not a
  `concurrency` attribute on the annotation.** `@RabbitListener(concurrency = "2-4")` would put a
  tuning number in a Java annotation, which the project's own rule forbids (the same rule
  `MediaProperties` exists to satisfy — `MediaProperties.java:11-15`). A second named factory bean
  reading `RabbitListenerProperties` keeps every number in the config layer, and
  `@RabbitListener(containerFactory = "mediaRabbitListenerContainerFactory")` is a one-word change
  at `MediaProcessingWorker.java:89`.

- **D-04 — Low prefetch on media, default prefetch elsewhere.** Media prefetch targets 1–2: the work
  is seconds-per-message and fair distribution across replicas matters more than fetch latency
  (A5). The notification/webhook queues are IO-bound and short; they keep the existing behaviour
  this phase — changing them is out of scope and would need its own measurement.

- **D-05 — Numbers come from Task 1 or they do not ship.** Every number in Tasks 3 and 6 is a
  placeholder written as `<<MEASURED>>` until the baseline run fills it. An executor that hardcodes
  a folklore value (`concurrency: 4`, "prefetch 1 is best practice") has failed this plan.

- **D-06 — The measurement runs pinned to 1 CPU.** Per A6 the k8s pod limit is `1000m`. An unpinned
  workstation run overstates the achievable concurrency and would ship a number that regresses in
  the cluster.

- **D-07 — The control arm is mandatory.** Baseline (concurrency 1 / prefetch 250) and candidate are
  both run and both recorded. A candidate-only run proves nothing; this repo has already been burned
  by a clean run that meant nothing until the control was executed.

- **D-08 — Consumer count is read from the broker, never from the yml.** The proof instrument is
  `GET http://localhost:15672/api/queues/%2F/media.process` → the `consumers` field, plus
  `consumer_details[]`. It is out-of-band, it observes the delivered runtime, and it is immune to a
  correct-yml/stale-image mismatch. The artifact half is proven separately by reading
  `application.yml` from **inside** the fat jar.

- **D-09 — The publish-side guard is a runtime assertion in `StompDestinations`, plus a test.**
  A unit test alone cannot see a hand-built string; an ArchUnit rule would have to name the forbidden
  construct and would fire on its own definition (a known vacuous shape in this repo). So:
  `StompDestinations.assertPublishable(String destination)` throws `IllegalArgumentException` on a
  `/` after the prefix, and `OrderStateChangeListener:111` calls it **outside** the swallowing
  try/catch at `:107-117` — an invalid destination must be loud, and a broker/transport failure must
  stay fire-and-forget. Add a unit test for both arms.

- **D-10 — `forkEvery(4)` is re-evaluated, not removed on faith.** Task 6 measures. If the suite is
  green and peak thread count drops, relax it and record both numbers; if not, keep it and correct
  the comment to name the inert-property root cause.

</decisions>

<tasks>

<task type="auto" id="T1-instrument">
**Add the one missing meter.** New `MediaProcessingMetrics` holding a single
`Timer` named `jtoye.media.process` (tags: `outcome` = `active|failed|skipped`), built with the
null-safe `ObjectProvider<MeterRegistry>` idiom copied from `MediaEventOutboxFlusher.java:83-92`.
Wire it into `MediaProcessingWorker.onMediaEvent` so the sample starts after the GUC pin and stops
in the `finally` at `:118-120`. No other meter is added — C confirms queue depth and consumer count
come from the broker API, so duplicating them in-process would be a second source of truth.

Files: `core-java/src/main/java/uk/jtoye/core/media/MediaProcessingMetrics.java` (new),
`core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java`.
</task>

<task type="checkpoint:human-action" gate="blocking" id="T2-measure">
**Run the baseline measurement. This gates Tasks 3 and 6.**

Write `infra/load-testing/media-pipeline-baseline.sh` — the smallest thing that produces a
defensible number, against the canonical compose stack (never minikube; the compose-XOR rule holds):

1. **Pin CPU (D-06):** `docker update --cpus=1 jtoye-core-java` before each arm; record
   `docker inspect -f '{{.HostConfig.NanoCpus}}'` in the evidence.
2. **Drive:** POST N=200 real JPEGs (generated, ~1.5MB each, mixed dimensions under the 40MP cap) to
   the media upload endpoint at a fixed arrival rate, using the existing auth pattern from
   `infra/load-testing/load-test.sh:26-28` (`KC_SEED_USER_PASSWORD` from `.env`; never a literal).
3. **Sample the broker every 1s** for the duration plus 60s drain:
   `curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" http://localhost:15672/api/queues/%2F/media.process`
   → record `messages`, `messages_unacknowledged`, `consumers`, and per-consumer
   `consumer_details[].prefetch_count`. This is the queue-depth-over-time series **and** the
   unfair-distribution evidence (A5).
4. **Sample latency** from `curl -s localhost:9090/actuator/prometheus | grep jtoye_media_process`
   → count, sum, max; compute mean and read the histogram if percentiles are published.
5. **Sample thread saturation:** `jtoye_executor_*` / JVM thread gauges from the same scrape, plus
   `docker stats --no-stream` CPU% per arm.

**Arms (both mandatory — D-07):**
- **Arm A (control/baseline):** current tree. Expect `consumers: 1`, `prefetch_count: 250`.
- **Arm B (candidate):** after T3 lands, per candidate setting.

**Define "good" numerically before running Arm B**, and record the definition in the summary:
- *Fairness (the actual A5 defect):* at ≥2 replicas, no replica holds more than `prefetch` unacked
  messages while another replica's unacked count is 0, at any sample.
- *Drain:* `messages` returns to 0 within a stated wall-clock budget after the last upload.
- *Latency:* p-mean and max `jtoye.media.process` under the burst, and the budget must be expressed
  as a **config-declared value**, not an ad-hoc number in prose.
- *Saturation:* candidate CPU% under a 1-CPU cap must not sit pegged at 100% with a growing queue —
  that is the signal that concurrency has overshot what one core can do (A6).

**Output:** the two arms' raw series, committed as evidence, and the chosen
`prefetch` / `concurrency` / `max-concurrency` values with the reasoning that ties each to a number
in the series. Fill every `<<MEASURED>>` placeholder in this plan.

Human gate because this starts and CPU-constrains the shared dev stack, and because the numbers are
a judgement call the plan will not make on the executor's behalf.
</task>

<task type="auto" id="T3-factory">
**Repair the factory and add the media container (D-01, D-03).**

1. New `RabbitListenerProperties` (`@ConfigurationProperties(prefix = "jtoye.rabbit")`), hand-written
   getters/setters, no Lombok — mirroring `MediaProperties`. Fields: `defaultPrefetch`,
   `defaultConcurrency`, `mediaPrefetch`, `mediaConcurrency`, `mediaMaxConcurrency`. Register on
   `CoreApplication`'s existing `@ConfigurationPropertiesScan`/`@EnableConfigurationProperties` path,
   whichever `MediaProperties` already uses.
2. `RabbitMQConfig.rabbitListenerContainerFactory` takes
   `SimpleRabbitListenerContainerFactoryConfigurer` as a parameter; body becomes
   `configurer.configure(factory, connectionFactory);` **then** the three existing overrides, **then**
   the properties-driven prefetch/concurrency. Keep the bean name — renaming it would un-back-off
   Boot's factory and create two.
3. New `mediaRabbitListenerContainerFactory` bean, same shape, reading the `media*` properties.
4. `MediaProcessingWorker.java:89` becomes
   `@RabbitListener(queues = RabbitMQConfig.MEDIA_EVENTS_QUEUE, containerFactory = "mediaRabbitListenerContainerFactory")`.
   Extend the class Javadoc's tenant-GUC paragraph (`:30-37`) to state that the pin is now exercised
   concurrently and why that is safe (T-27-03 below).
5. Add a startup `log.info` on each factory bean recording the effective prefetch/concurrency —
   a runtime-readable line for AC-3's second instrument.

Files: `RabbitListenerProperties.java` (new), `RabbitMQConfig.java`, `MediaProcessingWorker.java`.
</task>

<task type="auto" id="T4-config-layer">
**Surface every tunable, and close the pre-existing E defect in the same change (D-E).**

1. `application.yml`: new `jtoye.rabbit.*` block, every value env-indirected —
   `${JTOYE_RABBIT_DEFAULT_PREFETCH:250}`, `${JTOYE_RABBIT_DEFAULT_CONCURRENCY:1}`,
   `${JTOYE_RABBIT_MEDIA_PREFETCH:<<MEASURED>>}`, `${JTOYE_RABBIT_MEDIA_CONCURRENCY:<<MEASURED>>}`,
   `${JTOYE_RABBIT_MEDIA_MAX_CONCURRENCY:<<MEASURED>>}`. Default prefetch is deliberately declared
   as today's effective 250 so this change is provably behaviour-preserving for the seven untouched
   queues.
2. Same file: add the **eight orphaned outbox keys** from E as env-indirected entries with their
   current inline defaults, so the values do not change and the keys become visible. Also
   `jtoye.media.reaper-grace-ms`.
3. `application-prod.yml` / `application-staging.yml`: override only where the pod's 1-CPU limit
   makes the dev value wrong (A6).
4. `.env.example`: add every new `JTOYE_RABBIT_*` and outbox var next to the existing `RABBITMQ_*`
   block (`.env.example:40-47`), each with a one-line comment.
5. `k8s/base/configmap.yaml`: add the non-secret values beside the existing `rabbitmq.host`/`.port`
   entries (`:28-29`); `k8s/base/core-java-deployment.yaml`: inject them under the exact uppercase
   env names used in step 1, so `check-env-contract.sh` direction (a) sees a name it can match.
6. `k8s/scripts/check-connection-math.sh`: add the consumer-thread term (F) — parse
   `jtoye.rabbit.*` concurrency from `application*.yml`, multiply by the listener-endpoint count, add
   it to the per-pod demand, and fail if the total breaches the existing headroom rule. Extend the
   header comment to explain the new term the way the file already explains the pool term.
7. `docs/runbooks/messaging.md`: document every new key, its unit, its default, and what to change it
   to under load.
</task>

<task type="auto" id="T5-stomp-guard">
**Publish-side shape guard (D-09).**

1. `StompDestinations`: add
   `public static String assertPublishable(String destination)` — returns the destination, or throws
   `IllegalArgumentException` if it does not start with `TOPIC_PREFIX` or contains `/` after it.
   Reuse `TOPIC_PREFIX` and the exact reason text already used at
   `TenantChannelInterceptor.java:138-141`, so the two walls cannot drift in wording either.
2. `OrderStateChangeListener`: call it at `:110` on the built topic, **outside** the `try` at `:107`.
   The catch at `:115-117` keeps swallowing transport failures (fire-and-forget per D-06 of Phase 26)
   but must no longer be able to swallow a shape defect.
3. `StompDestinationsTest`: add the guard's accept and reject arms (the reject arm asserts the
   thrown type and that the message names the offending destination).
4. New `StompPublishGuardTest`: with a mocked `SimpMessagingTemplate`, prove that a slashed
   destination fails the listener loudly and that `convertAndSend` is never called with it —
   the assertion the existing 4 tests structurally cannot make.
</task>

<task type="auto" id="T6-forkevery">
**Re-evaluate `setForkEvery(4)` (D-10).** With T3 landed, `auto-startup=false` becomes effective for
the first time in 22 files. Run `:core-java:integrationTest` three ways — `forkEvery(4)` as-is,
`forkEvery(0)`, and `forkEvery(0)` on the pre-T3 tree — recording peak thread count and wall time for
each. `cleanTest` before every run (a cached `UP-TO-DATE` task reports success while executing
nothing). Then either relax the setting and rewrite the comment to name the real root cause, or keep
it and rewrite the comment anyway — the current text names a symptom whose cause is now known.
Read results from `core-java/build-local/`, never `core-java/build/`.
</task>

<task type="auto" id="T7-tests-docs">
**Tests and the docs gate.**

- `RabbitListenerContainerFactoryTest` (unit, no broker): build both factory beans through a Spring
  context with `jtoye.rabbit.*` set to distinctive values, create a container from each via
  `createListenerContainer(...)`, and assert the effective prefetch and `concurrentConsumers`.
  This is the permanent regression guard for the inert-factory class — it fails on the pre-T3 tree.
- `MediaListenerConcurrencyIntegrationTest` (`@Tag("testcontainers")`, real RabbitMQ container,
  following the `OrderEventFanoutTopologyIntegrationTest:60-170` precedent, which is the repo's only
  existing real-broker listener test): publish K messages, assert ≥2 distinct consumer tags served
  them and that each ran under its own tenant GUC.
- **Tenant-isolation test under concurrency (T-27-03):** two tenants' media events interleaved across
  concurrent consumers, with the Postgres role downgraded `NOSUPERUSER` per the
  `IntegrationTestSupport` RLS-caveat recipe, asserting no asset is ever written under the wrong
  tenant. This is the load-bearing security test of the plan.
- `docs/metrics.json`: current counts are `java_test_methods: 1157` / `java_test_files: 203` /
  `total_logical_invocations: 1736`. Regenerate with `scripts/docs-freshness.sh --write` — do not
  hand-edit; it is a known merge-conflict hotspot and the script is the arbiter. Beware that the gate
  greps the literal `it(`/`test(` token, so any table-driven test added here will not be counted.
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|---|---|
| broker → consumer thread | Message payload names the tenant; the thread has no ambient identity |
| consumer thread → Postgres session | The GUC pin is the only thing standing between the worker and every other tenant's rows |
| consumer thread pool ↔ Hikari pool | Two bounded resources sharing one budget; exhausting one starves the API |
| publisher → STOMP relay | A destination string becomes an AMQP routing key on a shared broker |
| measurement harness → shared dev stack | The baseline run CPU-constrains and loads a stack a second session may own |

## STRIDE Threat Register (ASVS L1)

| ID | Category | Component | Disposition | Mitigation |
|---|---|---|---|---|
| **T-27-01** | **Information Disclosure** | **tenant GUC under concurrency > 1** | **mitigate** | **ASVS V4/V8 — the headline risk of this plan.** Verified safe by construction, and the verification is recorded because the conclusion is not obvious: (a) `TenantContext` is a `ThreadLocal<UUID>` (`TenantContext.java:7`), so N consumer threads hold N independent values; (b) each `@Transactional` listener invocation binds its own `EntityManager` and therefore its own pooled connection, so `session.doWork` (`MediaProcessingWorker.java:96-102`) pins **that** connection only; (c) the pin uses `set_config('app.current_tenant_id', ?, true)` — `is_local = true`, i.e. **transaction-scoped**, so the value is discarded at commit and cannot ride a recycled Hikari connection into another tenant's thread. Raising concurrency multiplies independent instances of a safe pattern; it does not create a shared one. **The residual risk is a future edit flipping that third argument to `false`**, which today would leak on one thread and after this change would leak on N. Mitigated by the T7 two-tenant concurrent test under a `NOSUPERUSER` role downgrade (so RLS is genuinely enforced, per the `IntegrationTestSupport` RLS caveat) — it fails if the GUC is ever not transaction-local. |
| T-27-02 | Denial of Service | consumer threads vs Hikari pool | mitigate | ASVS V11. Finding F: 8 endpoints against a prod pool of 10 (`application-prod.yml:14`); a global concurrency bump would starve HTTP handling and `check-connection-math.sh` would pass it. Mitigated twice: D-02 keeps concurrency 1 on 7 of 8 endpoints, and T4.6 adds the consumer-thread term to the gate so the class cannot recur. |
| T-27-03 | Denial of Service | `cwebp` process fan-out under a 1Gi/1-CPU pod | mitigate | A6. Each concurrent encode forks a native binary inside a container whose JVM already commits 75% of 1Gi as heap. Bounded by `mediaMaxConcurrency` from the config layer and by D-06's 1-CPU measurement, which is what makes the ceiling empirical rather than guessed. |
| T-27-04 | Tampering | a slashed STOMP destination reaching the relay | mitigate | D. Today the only publish path swallows the failure (`OrderStateChangeListener.java:115-117`) and the KDS silently stops updating. T5 makes a shape defect throw before the send, outside the swallowing catch. |
| T-27-05 | Repudiation | "concurrency is now N" claimed from the yml | mitigate | AC-3 reads `consumers` from the running broker's management API and `application.yml` from **inside** `/app/app.jar`. A correct source file over a stale image cannot satisfy either. |
| T-27-06 | Elevation of Privilege | the measurement's Keycloak credential | mitigate | ASVS V7. The harness reuses `infra/load-testing/load-test.sh:26-28`'s pattern — `KC_SEED_USER_PASSWORD` sourced from `.env`, never a literal. `.env.example` gets names only. gitleaks allowlists `.planning` PLAN/RESEARCH but **not** CONTEXT, and GitGuardian is not a required check, so no password-shaped string may appear in any evidence pasted into a tracked file. |
| T-27-07 | Denial of Service | loading a stack a second session owns | mitigate | The `env_concurrent_working_tree` condition applies. T2 is a blocking human checkpoint; the CPU constraint and its reversal (`docker update --cpus=0`) are itemised in the approval text. |
| T-27-08 | Tampering | ordering regression from a careless global setting | mitigate | A7/A8. An executor that "simplifies" T3 into one global `spring.rabbitmq.listener.simple.concurrency` silently reorders order-state emails and KDS pushes with every test still green. Called out in D-02 and asserted by AC-5, which reads the *default* factory's concurrency and requires it to be 1. |
| T-27-SC | Tampering | npm/pip/cargo installs | n/a | No new dependencies. `hey`/`ab` are pre-existing optional host tools already assumed by `load-test.sh:38-45`; the harness must degrade with a clear message rather than install anything. |

## Other Quality Contracts

- **Web performance (mobile-first):** **PARTIALLY APPLICABLE.** No page, route, bundle or image
  asset changes, so the CWV half is **N/A**. But the IMG-04 vendor UI renders `PENDING → processing`
  while this queue drains, so time-to-ACTIVE is a real perceived-performance budget. T2 must express
  it as a **config-declared** budget (the contract's wording) rather than an ad-hoc prose number.
- **SEO / discoverability:** **N/A** — nothing public or unauthenticated changes; the media pipeline
  sits behind vendor auth.
- **AI agent-readiness:** **MOSTLY N/A** — no endpoint, OpenAPI contract, error shape or credential
  scope changes, and no new capability warrants an MCP tool. One narrow item is **applicable**: the
  media upload's 202-accept contract carries an implied processing SLA that an agent polls against,
  so if T2's measured latency budget moves materially, `docs/runbooks/messaging.md` records it.
  No RFC 7807 type or Idempotency-Key behaviour is touched.
- **Security:** **APPLICABLE** — full register above; T-27-01 is the load-bearing one.
- **Falsifiable evidence + runtime parity:** **APPLICABLE** — this plan's core. Every criterion below
  carries its deliberate break; AC-2 exists specifically because the obvious fix is the vacuous one.
</threat_model>

<verification>
```bash
# Fresh shell: bind names without printing values.
set -a; . ./.env; set +a
RMQ() { curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" "http://localhost:15672/api/queues/%2F/$1"; }

# --- runtime parity FIRST (a green result on a stale image proves nothing) -------------
bash scripts/check-runtime-freshness.sh;    echo "runtime-freshness exit=$? (expect 0)"
bash scripts/check-branch-behind-base.sh;   echo "branch-behind-base exit=$? (expect 0)"

# --- the RUNNING broker's view (D-08) --------------------------------------------------
RMQ media.process        | tr ',' '\n' | grep -E '"consumers"|"messages"'
RMQ media.process        | tr ',' '\n' | grep -E '"prefetch_count"'
RMQ order.state-changes  | tr ',' '\n' | grep -E '"consumers"'        # MUST remain 1 (A7)
RMQ webhook.deliveries   | tr ',' '\n' | grep -E '"consumers"'        # MUST remain 1

# --- the RUNNING artifact's view (fat jar, not the filesystem) -------------------------
docker exec jtoye-core-java sh -c 'unzip -p /app/app.jar BOOT-INF/classes/application.yml' \
  | grep -A6 'jtoye:' | grep -E 'rabbit|prefetch|concurrency'
# A filesystem `find` inside the container returns a misleading 0 — do not substitute it.

# --- the startup line the factories log ------------------------------------------------
docker logs jtoye-core-java 2>&1 | grep -c 'event=rabbit_factory_configured'   # expect 2

# --- guards (here-strings: `cmd | grep -q X` under pipefail inverts via SIGPIPE->141) ---
grep -c 'assertPublishable' \
  <<< "$(cat core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java)"   # expect >=1

# --- tests: cleanTest is load-bearing; UP-TO-DATE reports success while running nothing -
./gradlew :core-java:cleanTest :core-java:test --tests '*RabbitListenerContainerFactoryTest*' \
                                               --tests '*StompDestinationsTest*' \
                                               --tests '*StompPublishGuardTest*'
./gradlew :core-java:cleanTest :core-java:integrationTest \
  --tests '*MediaListenerConcurrencyIntegrationTest*'
# Read results from build-local/, NEVER build/ (that tree is stale in this repo).
ls core-java/build-local/test-results/test/ | grep -c RabbitListenerContainerFactory   # expect 1

# --- static gates ----------------------------------------------------------------------
bash k8s/scripts/check-env-contract.sh && bash k8s/scripts/check-connection-math.sh \
  && bash k8s/scripts/check-render-invariants.sh && bash k8s/scripts/render-golden.sh \
  && bash scripts/docs-freshness.sh
```
</verification>

<success_criteria>

Each criterion states its **deliberate break** and the **expected fail output**. A criterion recorded
only in the passing direction is not satisfied.

**AC-1 — The eight-key config defect is closed and the gate can see it.**
All eight `payment.outbox.*` / `media.outbox.*` keys plus `jtoye.media.reaper-grace-ms` appear
env-indirected in `application.yml`, in `.env.example`, and injected by
`k8s/base/core-java-deployment.yaml`.
*Break:* delete the `JTOYE_RABBIT_MEDIA_PREFETCH` env entry from the deployment while leaving
`${JTOYE_RABBIT_MEDIA_PREFETCH:…}` in `application.yml`.
*Expected fail:* `check-env-contract.sh` exits non-zero on direction (b) naming
`JTOYE_RABBIT_MEDIA_PREFETCH` as expected-but-unsupplied. Record both directions' real output.
*Note:* this criterion is only meaningful because the keys are env-indirected; declared as bare
`@Value` defaults they would be invisible to the gate (finding E), i.e. the criterion would be
vacuous. Say so explicitly in the summary.

**AC-2 — The factory repair, not the yml, is what makes the setting effective. (THE key criterion.)**
*Break:* revert **only** `RabbitMQConfig.java` to the hand-built factory, leaving every new
`jtoye.rabbit.*` yml value in place; rebuild; restart.
*Expected fail:* `RMQ media.process` still reports `"consumers":1` and
`"prefetch_count":250` despite the yml asking for the measured values, and
`RabbitListenerContainerFactoryTest` fails with the configured-vs-effective mismatch.
This is the fail direction that distinguishes a real fix from the plausible one, and it must be run
and recorded before the pass is accepted.

**AC-3 — Concurrency is read from the running thing, twice, by two independent instruments.**
(a) `RMQ media.process` → `"consumers": <<MEASURED>>` and per-consumer
`"prefetch_count": <<MEASURED>>`; (b) `unzip -p /app/app.jar BOOT-INF/classes/application.yml`
shows the same numbers.
*Break:* rebuild the image from a tree with the old values but leave the **running container**
untouched (i.e. `docker compose start`, no rebuild).
*Expected fail:* `scripts/check-runtime-freshness.sh` exits non-zero on the core-java image's
`.Metadata.LastTagTime` versus the newest commit touching its build paths, **and** the two
instruments disagree. A `docker compose start` does not rebuild; that is the whole point.

**AC-4 — The measurement exists, has two arms, and the numbers trace to the series.**
Arm A (baseline) and Arm B (candidate) raw series are committed; the chosen prefetch/concurrency
values each cite a specific observation.
*Break:* delete Arm A and present Arm B alone.
*Expected fail:* the fairness criterion is unevaluable — "no replica starved" has no comparison
point, and the drain time has no baseline to beat. A candidate-only run is rejected on its face.
*Additionally:* if the 1-CPU pin is omitted, record that the run is invalid rather than reporting it
— an unpinned number does not transfer to a `1000m` pod.

**AC-5 — The seven untouched queues are provably untouched.**
`RMQ order.state-changes`, `RMQ webhook.deliveries`, `RMQ order.notifications`,
`RMQ payment.notifications`, `RMQ refund.notifications`, `RMQ onboarding.notifications`,
`RMQ payment.events` each report `"consumers":1`.
*Break:* set `jtoye.rabbit.default-concurrency: 3`.
*Expected fail:* each of the seven reports `"consumers":3`, and the extended
`check-connection-math.sh` fails on the consumer-thread term (8 endpoints × 3 = 24 threads against a
10-connection prod pool). This is the A7/T-27-08 ordering regression made visible.
*Warning against a vacuous shape:* do **not** phrase this as "grep the yml for `concurrency: 3` and
expect 0" — that grep is already 0 on the correct tree and would pass without the change.

**AC-6 — A slashed destination cannot be published.**
*Break:* in a scratch branch, replace `StompDestinations.kitchen(...)` at
`OrderStateChangeListener.java:110` with a hand-built
`"/topic/kitchen/" + tenantId + "/" + shopId`.
*Expected fail:* `StompPublishGuardTest` fails with `IllegalArgumentException` naming the
destination, and `verify(simpMessagingTemplate, never()).convertAndSend(...)` holds. On the current
tree the same break produces a passing suite and a single `WARN` line at
`OrderStateChangeListener.java:116` — run that arm too and record it, because it is the exact
silent-failure this criterion removes.

**AC-7 — `forkEvery` is decided by measurement.**
Three recorded runs (as-is / `forkEvery(0)` post-fix / `forkEvery(0)` pre-fix) with peak thread count
and wall time, each preceded by `cleanTest`.
*Break:* run `:core-java:integrationTest` twice without `cleanTest`.
*Expected fail:* the second run reports `UP-TO-DATE` and finishes in ~0s — success while executing
nothing. Record that output as the reason `cleanTest` is mandatory here.
*Also:* the run artifacts must come from `core-java/build-local/`; reading `core-java/build/`
returns a stale tree.

**AC-8 — Tenant isolation holds under concurrency.**
The T7 two-tenant interleaved test passes with the Postgres role downgraded `NOSUPERUSER`.
*Break:* change the worker's pin to `set_config('app.current_tenant_id', ?, false)` (session-scoped).
*Expected fail:* the test fails with an asset written or read under the wrong tenant once a pooled
connection is reused across consumer threads. If it passes with `false`, the test is not exercising
connection reuse and must be strengthened — **do not** report that pass as satisfaction.
*Guard against a fail-open harness:* the test must exit non-zero on missing tooling, an unparseable
result, or an EMPTY result set. "Found nothing" is VOID, never clean.

**AC-9 — Docs and counts stay honest.**
`docs/metrics.json` regenerated via `scripts/docs-freshness.sh --write` (never hand-edited);
`docs/runbooks/messaging.md` documents every new key with unit, default and tuning guidance.
*Break:* add a test method and commit without regenerating.
*Expected fail:* the `docs-freshness` CI gate fails on the `java_test_methods` delta from the
recorded 1157.

</success_criteria>

<output>
Create `.planning/phases/27-messaging-layer-hardening/27-04-SUMMARY.md`. Record: the human's approval
text for T2 and the CPU-pin reversal; both measurement arms' raw series with the
`docker inspect NanoCpus` value per arm; the chosen numbers and the specific observation each traces
to; the **verbatim AC-2 fail-direction output** (yml set, factory reverted, broker still reporting
`consumers:1` / `prefetch_count:250`) alongside the pass; both AC-3 instrument readings; the AC-6
pre-fix arm showing the single swallowed `WARN`; all three AC-7 timings with peak thread counts and
the `forkEvery` decision with its reasoning; the AC-8 result under `NOSUPERUSER` including the
`set_config(..., false)` break arm; and an explicit note that the eight orphaned outbox keys were
pre-existing defects fixed here, not introduced by this plan.
</output>

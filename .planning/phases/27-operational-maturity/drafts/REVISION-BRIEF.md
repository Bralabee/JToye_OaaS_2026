# Phase 27 — Revision Brief (binding on all five plans)

**Written:** 2026-07-26 · **Status:** drafts in this directory are UNREVISED and NOT executable.
**Source:** two adversarial audits (correctness, robustness) over 162 acceptance criteria, plus
direct re-verification. Where an audit claim was re-run and found wrong, this brief records the
correction — audits are evidence, not authority.

---

## 0. BASE FACTS THAT INVALIDATE PARTS OF EVERY DRAFT

All five drafts were written against `feature/phase-26-local-k8s-overlay` @ `78eaa99`.

1. **That branch is merged and dead, and is 6 commits behind `origin/main` (`213e06f`).** New work
   must branch from `origin/main`, not from it.
2. **Verified: none of the plan-relevant files changed on main.** `git diff --stat HEAD..origin/main`
   over `core-java/.../media/`, `config/RabbitMQConfig.java`, `order/OrderStateChangeListener.java`,
   `core-java/src/main/resources/`, `infra/monitoring/`, `docker-compose.full-stack.yml`, `k8s/`
   returns **empty**. So every code diagnosis in the drafts survives. The 6 commits are
   product/marketing shop-scoping, the frontend Dockerfile, and the openapi snapshot.
3. **`docs/metrics.json` DID change: 1736 → 1759** (java test methods 1157 → **1176**; schema stays
   V59). **Every metrics-delta criterion in every draft is computed off the wrong baseline** and must
   be rebased to 1759.
4. **Six already-open issues overlap this phase.** No draft references any of them. Each plan must
   either close its issue or state why it doesn't:
   - **#115 [P3-13] No load-test baseline** — this *is* 27-00's GAP 3.
   - **#284 `@Async`/`@Scheduled`/`@RabbitListener` propagate no SecurityContext** — bears directly on
     27-04's concurrency change and its tenant-isolation criterion.
   - **#289 STOMP shop-gate hard-coded to the kitchen topic** — bears directly on 27-04 D-09.
   - **#304 Rework `frontend/e2e/stomp-relay.spec.ts` to be ingress-capable.**
   - **#205 [AI-3] Outbound tenant webhooks fed from the transactional outbox** — the webhook DLQ.
   - **#209 EPIC AI-readiness.**
5. **`.planning/ROADMAP.md` has no Phase 27 entry**, and the requirement IDs used across the drafts
   (`MSG-01..03`, `OPS-01..05`) are **defined nowhere** and are double-claimed. Phase registration is
   a `/gsd-phase` action and is a prerequisite, not part of these plans.

---

## 1. CENTRAL DECISIONS (resolve cross-plan conflicts; binding)

### D-A — Prometheus scrape strategy: 27-03's approach wins
27-00 wanted `/metrics/per-object` on the existing `rabbitmq` job; 27-03 wanted a second job on
`/metrics/detailed`. Measured on the live broker: **`/metrics/per-object` = 3475 lines**,
**`/metrics/detailed?family=queue_coarse_metrics&family=queue_consumer_count` = 65–77 lines**.

- **27-03 owns** the new `rabbitmq-queues` job on `/metrics/detailed`. The existing aggregated
  `rabbitmq` job stays untouched, so no existing expression breaks.
- **27-00 drops** its per-object change entirely.
- The SSE `AnonymousQueue` cardinality drop-rule (27-00) **moves to 27-03's** new job.

### D-B — One owner per deliverable (kills four duplications)
| Deliverable | Owner | Others |
|---|---|---|
| dependency support horizons (manifest + gate + CI job) | **27-00** | 27-02 **deletes** its `docs/support-horizon.yaml` + `check-support-horizon.sh`; it consumes 27-00's instead |
| live alert-selector liveness gate | **27-00** owns the *mechanism* | 27-03 owns the *rules* + runbook sections and consumes the gate |
| load-baseline harness | **27-00** owns the generic harness | 27-04 adds only the media-specific arm on top |
| `## StompBrokerLag` runbook section | **27-03** | 27-00 drops it |
| `.github/workflows/ci-cd.yaml` edits | **27-00** Task 7 only | 27-03 drops its CI task |
| `core-java:9091 → 9090` scrape fix | **27-00** Task 1 only | 27-03 drops it and declares the dependency |

### D-C — The nine dead letters: adjudicate before destroying
`webhook.deliveries.dlq` holds 9 tenant-bearing messages.

> **⚠ SETTLED 2026-07-26 — the batch is NOT historical. The producer is LIVE.** Measured via the
> host-published management API (`ackmode=reject_requeue_true`, depth re-asserted at **9** afterwards,
> control: `/api/overview` → `3.12.14`):
> **oldest x-death `2026-07-15T11:46:18Z`, newest `2026-07-26T15:33:51Z` — today, hours ago.**
> Both earlier claims were half right: one agent saw the oldest, another the newest.
>
> **Consequence: purging without fixing the producer just resets a counter.** The disposition
> checkpoint (D-C) must decide about an ongoing fault, not an archived one.
>
> **And it points at a different root cause than assumed.** 27-02 measured `webhook_subscription` =
> **0 rows across 6 tenants** and `webhook_delivery` = 0 — so there is nothing to deliver to, yet
> `webhook.deliveries` messages are still exhausting retries and dead-lettering *today*. That is not
> "a vendor endpoint is down"; it is the fan-out consumer failing on every event. **Diagnose the
> producing fault before purging** — it belongs to 27-03, and it is currently unowned by any task.
- **27-03 Task 6 becomes archive-and-characterise ONLY. The purge is removed.**
- **The purge and its disposition move into 27-02 Task 2's existing `checkpoint:human-action`**, which
  exists for exactly this and must run before the broker volume is destroyed.
- 27-03 keeps its real-9 firing proof (T2.3). Its firing→**resolved** transition proof uses the
  manufactured payment-DLQ drill (T2.4) on a throwaway queue instead of destroying real data.
- 27-02's checkpoint must additionally present **whether the owning tenant still has an ACTIVE
  `ORDER_STATE_CHANGED` subscription** — that is the decisive fact and no draft surfaces it.

### D-D — One phase identity
`phase: 27-operational-maturity` in all five. One output directory. Requirement IDs are **not** to be
invented by the plans — they come from the `/gsd-phase` registration.

### D-E — 27-04 D-01 must not hard-inject the configurer

> **⚠ CORRECTED 2026-07-26 after the 27-04 revision agent pushed back and was proven right.**
> The *facts* below about the two files are correct. The *consequence* originally stated here — "D-01
> as written breaks the suite" — **was wrong**, and the criterion it prescribed was itself
> unfalsifiable. Both files land on the test runtime classpath under the same name and **Gradle puts
> `resources/test` first, so it SHADOWS `main/resources` and the `RabbitAutoConfiguration` exclusion
> is NOT in effect under `./gradlew test`.**
>
> Measured:
> - both files present: `core-java/build-local/resources/{main,test}/application-test.yml`
> - a **passing** integrationTest (`failures="0"`) contains **36 `CachingConnectionFactory`**
>   occurrences — that bean exists only if `RabbitAutoConfiguration` ran
> - there is **no** `@Bean`-declared AMQP `ConnectionFactory` anywhere in main source, so
>   `rabbitListenerContainerFactory(ConnectionFactory, …)` can only be satisfied by that
>   autoconfiguration. Were the exclusion effective, the context would already fail today.
>
> **Still adopt `ObjectProvider`** — it is correct under both classpath orders and removes a
> dependency on an undocumented shadowing accident. **But the criterion must change**: "remove the
> null-guard and watch every Spring test fail" cannot fail. The stronger form (adopted in
> `27-04-PLAN.md` AC-9) *renames* the test-resources file to unshadow the exclusion, then shows the
> hard-injection form throwing `NoSuchBeanDefinition` while the guarded form boots with its WARN.
>
> **Lesson, worth more than the fix:** this same two-file question was got wrong three times in one
> day — the draft cited the wrong path, the correctness audit read only `test/resources` and drew the
> opposite conclusion, and this brief had the right facts with the wrong consequence. It was settled
> only by measuring the **built runtime artifact** (`build-local/resources/`, and a real passing test
> XML) rather than reasoning from the source tree. Prefer the runtime over the tree whenever the two
> can disagree.
There are **two** `application-test.yml` files:
- `core-java/src/main/resources/application-test.yml:7` excludes **`RabbitAutoConfiguration`**
- `core-java/src/test/resources/application-test.yml:9-10` excludes **Redis** autoconfiguration

From the jar: `RabbitAutoConfiguration` carries
`@Import([RabbitAnnotationDrivenConfiguration, RabbitStreamConfiguration])`, and
`RabbitAnnotationDrivenConfiguration` is what declares `simpleRabbitListenerContainerFactoryConfigurer()`.
So on the `test` profile that bean **does not exist**, and constructor-injecting it into
`RabbitMQConfig.rabbitListenerContainerFactory` fails context startup for every Spring test on that
profile. **Use `ObjectProvider<SimpleRabbitListenerContainerFactoryConfigurer>` and no-op when
absent.** Add a criterion whose break removes the null-guard and shows the test context failing.

> The draft asserted the exclusion citing the wrong path; the correctness audit checked only
> `test/resources`, declared the draft's fact false, and concluded D-01 "is nonetheless feasible".
> Both were wrong. The exclusion is real and D-01 as written breaks the suite.

### D-F — 27-04 D-09 must not throw into the AMQP ack path
The destination is built **inside** `orderRepository.findById(...).ifPresent(...)`, inside the `try`
at `OrderStateChangeListener.java:107`, so the prescribed placement is impossible. And a throw that
escapes the swallowing catch propagates out of the `@RabbitListener` → 3 retries → dead-letters, and
`sendEmailForState(event)` never runs. A KDS-only degradation would become an order-processing and
email outage for that tenant — an Incremental Betterment violation.
**Validate inside `StompDestinations.kitchen()`** (the sole builder), which is implementable, catches
the defect at construction, and cannot abort the listener. Cross-reference **#289**.

### D-G — New live defect discovered during audit; assign it to 27-00
```
pg_up{job="postgres"}  = 0     ← exporter cannot reach the database (DNS, since ~2026-05-05)
up{job="postgres"}     = 1     ← Prometheus scrapes the exporter fine
```
`DatabaseDown` alerts on `up{job="postgres"} == 0`, which is **1**, so it reports healthy.
`pg_up` — the correct signal, present and 0 right now — is referenced by **no rule**. This is a
fourth instance of the same contract failure and must be in the terminal-states register and the
liveness gate.

> **⚠ ROOT CAUSE — CORRECTED TWICE. Read all of this; the first correction in this brief was wrong.**
>
> **What is CONFIRMED and currently blocking: `sslmode=require` against a server with TLS off.**
> Measured: `psql -tAc "show ssl;"` → **`off`**, while the DSN at
> **`infra/monitoring/docker-compose.monitoring.yml:118`** is
> `?sslmode=${POSTGRES_EXPORTER_SSLMODE:-require}`. That combination **can never connect**, whatever
> DNS does. Both revision agents (27-00 and 27-03) reached this independently; 27-03 reports observing
> `pq: SSL is not enabled on the server`. **Their citation was RIGHT and mine was wrong.**
>
> **⚠ CORRECTED AGAIN 2026-07-26 — my fourth broken probe.** This brief previously said "it is not
> `.env.example:14` (`grep -n sslmode .env.example` → no match)". That grep was **case-sensitive**
> against an **uppercase** key. Measured: `grep -c "sslmode" .env.example` → **0**;
> `grep -ci "sslmode" .env.example` → **1**, namely `.env.example:14:POSTGRES_EXPORTER_SSLMODE=require`.
> The key exists, at exactly the line the agents cited. 27-00 correctly overrode the brief here.
> **Consequence for the plans:** the `.env.example` change is a MODIFICATION (`require`→`disable`),
> not an addition — any criterion phrased as "adds one key" is wrong, and a `grep -c '…=disable'`
> assertion must account for the existing `…=require` line.
>
> **What is NOT established: DNS.** This brief previously asserted DNS as the live fault, citing
> `err="... dial tcp: lookup postgres on 127.0.0.11:53: server misbehaving"`. **Those log lines are
> from a PREVIOUS RUN of the container and are not current evidence.** Measured:
> `StartedAt=2026-07-25T13:00:51Z`, `RestartCount=0`, and `docker logs --since 30m` is **empty** — the
> exporter has logged nothing since it was last started, so the newest lines in the buffer
> (`ts=2026-05-05`) predate the current run. Reading them as the live cause is exactly the
> *"reading a stale artifact"* trap in this project's own doctrine, committed by me.
>
> **The only current facts are** `pg_up 0` and `pg_exporter_last_scrape_error 1`, read from the live
> `:9187/metrics`.
>
> **Therefore:** fix the sslmode first (it is deterministic and cannot be working). Then re-read
> `pg_up`. **If it does not go to 1, diagnose DNS then — with fresh evidence, not the old buffer.**
> Do not pre-commit to a DNS fix that may be fixing nothing.
>
> **Acceptance is two-armed regardless:** `pg_up == 1` **and** a rule that actually references
> `pg_up`. Fixing the connection without adding the rule leaves `DatabaseDown` still blind.
>
> **My own isolation probe was broken and I nearly filed its output as a finding:**
> `docker exec jtoye-postgres-exporter sh -c 'getent hosts postgres || echo CANNOT RESOLVE'` printed
> `CANNOT RESOLVE` — but the next line was `sh: getent: not found`. The `||` fired because the tool is
> absent, not because DNS failed. Third broken probe of the day. **Always check that a negative result
> came from the thing you are testing.** Also live and unrecorded: `HighMemoryUsage` and `FrequentGarbageCollection` currently
evaluate **Keycloak's JVM while labelled `service: core-java`**.

### D-H — 27-02: snapshot before destroying, and stop demanding history be rewritten
1. **Snapshot the volume before `docker volume rm`**, and state the restore command. There is no
   downgrade path from 4.3.4 (Khepri) back to 3.12.14; the tarball is the only way back.
   Volume name verified: `jtoye_oaas_2026_rabbitmq_data`.
2. **AC-2 / AC-12 are unsatisfiable as scoped and would falsify dated records.** Measured: 7 hits and
   **25 hits across 14–15 files**, including `docs/analysis/MESSAGING-BROKER-EVALUATION-2026-07-26.md`
   (this plan's own evidence base) and `ADR-0003-messaging-broker-selection.md` (Accepted 2026-07-26).
   Exclude `docs/analysis/**`, `docs/architecture/decisions/**`, `docs/planning/**` by the same rule
   that already protects `.planning/phases/**`, and scope the assertion to **live config + operator
   docs**. Allowlist the new upgrade runbook with a reason — it must name `3.12` to document the
   3.12 → 3.13 → 4.x chain (a doc rule that forbids the string it must contain).
3. **Drop `--check`.** Verified from source — `render-golden.sh` has
   `*) echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;`, and check mode is the **default
   (no argument)**. Three runs: exit 2 every time.
   > The robustness audit twice claimed this exits 0 and called it a proven fail-open. **It is not.**
   > The gate fails closed. Do not "fix" it.
4. AC-8's "local" arm is unrunnable: `render-golden.sh:90` sets `TARGETS=(staging production)` and
   `k8s/goldens/` holds only those two.
5. Correct the EOL gate's blind spot: endoflife.date reports rabbitmq **4.3 → `eol: false`**, while
   the vendor table says community support ends **2026-11-30**. A `false` is a *missing horizon*, not
   a cataloguing nit, and it lands on the exact pin being adopted. Vendor date wins; catalogue
   disagreement of this shape must fail, not NOTE.

---

## 2. PER-PLAN REQUIRED FIXES

### 27-00 spine
- Apply D-A (drop per-object), D-B (take sole ownership of horizons / liveness gate / load harness /
  CI wiring / the 9091→9090 fix), D-D, D-G (add `pg_up` + the Keycloak mis-attribution).
- **Blast-radius count is wrong in both directions.** `HighMemoryUsage` and
  `FrequentGarbageCollection` *do* have data — from Keycloak — so the liveness gate would have
  reported them green while they were blind to core-java. A ninth alert
  (`TooManyDatabaseConnections`) is dataless for the `pg_up` reason. Recount and restate.
- **Do not write the L-2 exemptions.** Verified live: `tenant_context_missing_total 0.0`,
  `jtoye_payment_failed_total 0.0`, `hikaricp_connections_active 0.0` — all eagerly registered. Once
  the port is fixed these selectors match, and the plan's own "stale exemption → FAIL" rule fires.
- **EOL slugs must be recorded per row.** Measured: `node` → **301** → `nodejs`, `alpine` → **301** →
  `alpine-linux`, `minio` → 404, `ollama` → 404, `redis` cycle `7` → CYCLE NOT FOUND (use `7.4`).
  Written the obvious way the gate is permanently VOID. Add an `eol_slug` field, `curl -L` with a
  same-host assertion (both redirects measured are same-host).
- **Restate GAP-1's residual risk honestly**: the register covers the *five declared files*, not all
  files containing terminal states. `PaymentStatus`, `GateStatus`, `OnboardingEvent`,
  `TenantLifecycleService`, `TenantStatusInterceptor` all hold terminal states and are outside it.
- **The liveness gate nobody must run**: require its exit code + timestamp in every phase SUMMARY.
- **State plainly that this phase delivers the alert *transport*, not the *recipient***: the
  destination remains the Mailhog sink with no human.
  > **CORRECTED 2026-07-26 — the 27-00 revision agent was right and this brief was wrong.** `.env`'s
  > Slack keys are **not empty**: `ALERTMANAGER_SLACK_WEBHOOK_URL` is **set to a PLACEHOLDER value**
  > and `ALERTMANAGER_SLACK_CHANNEL` is set to a real-looking value. So an "omit the receiver when
  > empty" render rule would happily render a receiver pointing at a dead URL. The render condition
  > must treat `PLACEHOLDER` / `CHANGE_ME` / `example.com` as unset.
  > **And they reach nothing regardless**: `infra/monitoring/docker-compose.monitoring.yml`'s
  > alertmanager service has an `environment:` block with **no `SLACK` entry**, so the variables never
  > enter the container. That file was missing from both the draft and this brief; add it to
  > `files_modified`. `.env.example` still has no `ALERTMANAGER_SLACK_*` key — add them there too.
- Vacuous: AC-1.5, AC-1.6, AC-4.9, AC-7.4, AC-7.5 are expected-0 diff greps with no working control.
  Note `grep -c` returning 0 **exits 1**, which kills the harness under `set -e`.
- Fix `ServiceDown` activeAt to the measured `2026-07-25T13:01:41.425388701Z`.

### 27-01 media durability
Diagnosis is exact; every sampled citation checked out. Fix the design:
- **M1 — the retention sweep re-deletes forever.** Its only "done" marker is setting
  `quarantineExpiresAt = null`, which is *already* null for every row the legacy arm selects. So the
  same objects are re-selected every hour indefinitely, and `StorageService.deleteByKey` swallows
  every exception (`:288-299`) so nothing complains. **Add a real sentinel column in V60.**
- **M2 — the consumer-liveness probe cannot observe its own case.** `AmqpAdmin.getQueueInfo` returns
  **broker-wide** consumer count, and the worker and the reaper are in the **same JVM** — so
  `consumerCount >= 1` whenever the reaper ticks. The "dispatched but consumer down" case is
  undetectable; only the throw branch ever fires. Either use a different signal or stop claiming the
  broker-health circuit is subsumed.
- **M3 — the re-drive creates a worker/worker race and calls it harmless without evidence.** Two
  concurrent workers on one asset both `putBytes`, one loses the V59 optimistic lock → DLQ, and both
  call `placeAsset`, which repoints `product_media` and physically deletes the displaced asset at
  ref-count 0. Establish or remove.
- **M4 — enumerate the good being displaced.** Today a stuck upload becomes vendor-visible `FAILED` +
  "please re-upload" in 15 min. After D-01 it spins indefinitely with no reason and no affordance,
  while the 72h sweep deletes the bytes anyway. Add a distinct vendor-visible delayed/stalled state.
  The objective's "closes a P0 data-loss defect" overstates: unbounded-loss-at-15-min becomes
  bounded-loss-at-72h **plus a new indefinite-limbo state**.
- **AC-3.2(a) is vacuous** — the exact defence-in-depth trap D-03 claims to avoid. Its ACTIVE fixture
  has a `/media/` key, so guard 2 blocks it and removing guard 1 cannot produce the stated RED. Give
  the fixture a `/quarantine/` key.
- **AC-6.3 is vacuous** — `touch` does not change content, so `git diff --name-only` returns nothing
  in both directions. Append a real line instead.
- AC-1.2's EXPLAIN arm inverts on a small table; AC-1.3 is an already-0 grep that also exits 1.
- Add the terminal-states register row (D-G/27-00 owns the register; this plan must contribute), and
  note that TS-07's locator points at code this plan **deletes**.

### 27-02 broker upgrade
Vendor research is the strongest in the set — the release table matches rabbitmq.com verbatim and the
queue-property table matches the running broker row for row. Apply D-B, D-C, D-H, and:
- **M15 ordering hazard:** destroying the broker before 27-01 lands drops in-flight `media.process`
  messages, leaving `media_asset` rows PENDING with a `SENT` outbox row — which the **current** reaper
  turns into `deleteByKey` + FAILED at the next 10-minute tick. That is the very P0 27-01 exists to
  close. Either sequence 27-01 first or add a pre-recreate PENDING check.
- AC-4's `default_queue_type=quorum` break deliberately breaks the shared dev stack with a one-line
  prose revert. Wrap it in a `trap` and make the post-revert state a criterion, not a note.
- AC-10 contradicts its own spec (`unknown` → "exit 1, never 0" vs PASS requiring exit 0). Resolve
  with a distinct exit state; and state the CI-goes-red date, because 4.3's 2026-11-30 horizon with a
  90-day window turns CI amber ~2026-09-01 and red 2026-12-01 with no code change.
- D-10's pin list omits the monitoring compose set (prometheus, grafana, alertmanager, both exporters)
  and the second `ollama` pin — moot once D-B moves horizons to 27-00, but the coverage gap is real.

### 27-03 failure visibility
Best problem statement of the four; `check-alert-metrics.sh` is the most valuable artefact in the
phase. Apply D-A (own the scrape job), D-B (drop CI + the 9091 fix + the duplicate gate), D-C
(archive only), and:
- **M9/T2.2/T3.1/T4.1 — the rewritten `StompBrokerLag` still cannot fire.** Enumerated live: 13
  queues, **none** matching `stomp-subscription.*` or `amq[.]gen-.*`; those queues exist only while a
  KDS client is subscribed. Requiring every selector to return ≥1 series is unachievable on a correct
  tree. Either establish a KDS subscription as part of the criterion, or keep the rule commented with
  a stated reason (the file's own `DiskSpaceLow` precedent). **Do not `EXPECT_EMPTY` it** — that
  neuters the gate for the rule that motivated the work and arms a fail-on-success trap.
- **T4.1 is unreachable from Task 4's actions.** Four rules lack runbook headings, not one:
  `KeycloakDown`, `RedisDown`, `PaymentFailureSpike`, `StompBrokerLag`. And the extractor must skip
  the two commented-out `DiskSpace*` rules or it demands six.
- **The promtool invocation is broken.** `docker run prom/prometheus:v2.48.0 promtool check rules …`
  → exit 1, `error: unexpected promtool` (the ENTRYPOINT is `/bin/prometheus`). Use
  `--entrypoint=promtool`, or `docker exec jtoye-prometheus promtool`. `promtool` is **not** installed
  on the host. Fails closed, but the stated command never works.
- **m8 — regexes written as `queue=~".*\\.dlq"` inside an `expr: |` block scalar** survive as two
  characters and RE2 reads *literal backslash + any char* → matches nothing, reproducing the very
  defect being fixed.
- Unaddressed compile break: `RabbitMQConfig.java:408` when `retryInterceptor()` gains a parameter.
- Vacuous: T1.7, T2.8, T3.12, T5.5, T5.9 are expected-0 diff greps. **T5.5 additionally fires on
  27-04's correct change** (which moves `setDefaultRequeueRejected(false)`) — replace with a
  behavioural assertion on the built factory.
- T5.8's VOID arm fails if `dlq-inspect.sh` sources `.env` itself. Specify.

### 27-04 throughput and guards
AC-2 (the inert-factory proof) is the single best criterion in the phase — keep it exactly. Apply
D-E, D-F, and:
- **M5 — the container name does not exist.** Compose removed `container_name` to support
  `--scale core-java=N` (`docker-compose.full-stack.yml:170`); the live container is
  **`jtoye_oaas_2026-core-java-1`**. Four commands (`docker exec/update/logs jtoye-core-java`) fail
  with "No such container", including AC-3's break and T2's CPU pin.
- **M6 — `check-connection-math.sh` is the wrong budget.** It computes
  `maxReplicas × Hikari pool` against Postgres `max_connections`. **A pod cannot open more
  connections than `maximum-pool-size`** — the pool is the cap, so adding consumer threads
  double-counts and asserts an exhaustion that cannot occur. The real hazard (consumers starving HTTP
  inside one pod) is `endpoints × concurrency ≤ poolSize − httpReserve` — a different equation in a
  different place. Verified live: the gate reports `64 -> OK (<= 157)`, so the stated 24-thread break
  would not fire anyway.
- **AC-1 is vacuous.** `check-env-contract.sh` direction (b) fails only on *no default* or a default
  containing a local-only word; a plain integer default is "pass by rule". Break by removing the
  default **and** the injection, or assert set-wise presence across all three files.
- **AC-8 is vacuous** — and it is the plan's own load-bearing tenant-isolation proof. Flipping the GUC
  to session scope still passes because every worker re-pins before use. The break must **omit the
  pin entirely** on one of two interleaved consumers, and additionally assert
  `current_setting('app.current_tenant_id', true)` is empty at the start of each worker transaction on
  a reused pooled connection. Cross-reference **#284**.
- **AC-3 names the wrong detector**: after a rebuild `.Metadata.LastTagTime` is *newer*, so that term
  passes; the term that fires is the running-container-image-ID vs tag-ID comparison.
- **M7 — the test-profile claim.** See D-E: the exclusion is real but in `main/resources`, not
  `test/resources`. Fix the citation *and* the design.
- **m3 — there are 9 listener endpoints, not 8** (`FinancialNotificationListener` declares two).
- Add a criterion that `<<MEASURED>>` placeholders never reach `application.yml`, `.env.example` or
  `k8s/`, and prove it by leaving one in.
- Wrap T2's `docker update --cpus=1` in a `trap` — an abandoned session leaves the shared stack CPU-
  pinned and silently corrupts every later measurement in 27-01 and 27-03.

---

## 3. STANDING RULES FOR THE REVISION

1. **Rebase every metrics-delta criterion to 1759 / 1176 java methods.**
2. **Every criterion keeps its PASS / BREAK / expected-RED triple**, and any criterion that cannot be
   made to fail is labelled UNFALSIFIABLE and replaced with a stronger form — never silently kept.
3. **`grep -c` returning 0 exits 1.** Under `set -e` an expected-0 grep kills its own harness. Use
   `| wc -l` or `|| true` with an explicit comparison.
4. **Here-strings, never `cmd | grep -q X`** under `set -o pipefail` (SIGPIPE → 141 inverts on match).
5. **Gates fail closed**: missing tooling, unreachable dependency, empty discovery, or unparseable
   output must exit **2 (VOID)**, never 0.
6. **`cleanTest` is load-bearing** (Gradle reports `UP-TO-DATE` while executing nothing) and counts
   are read from **`build-local`**, not the stale `core-java/build/`.
7. **The five RED-on-current-tree baselines must be captured before the first edit** — they are
   unreproducible afterwards without deliberately re-breaking the tree: core-java target down;
   `StompBrokerLag` selector empty with control 9; 4 alerts with no runbook heading; six past-EOL
   pins; no load tool installed. Add a sixth: `pg_up = 0` while `up{job="postgres"} = 1`.
8. **Seventh RED baseline, found during revision — a pre-existing production risk, not one Phase 27
   introduces.** The corrected consumer-thread budget (`Σconcurrency + httpReserve ≤
   maximum-pool-size`, per D-E/M6 in 27-04) **fails on the unmodified tree**: 9 listener endpoints
   against a prod Hikari pool of 10 leaves **1** connection for HTTP handling. Capture it as a
   baseline, escalate it at 27-04's human checkpoint, and **do not tune `httpReserve` to make the
   gate green** — that would be fixing the thermometer.

---

# CONSOLIDATED FIX PASS (2026-07-26) — decisions D-I .. D-P

Written after three re-audits (correctness/DAG, regression-by-omission, falsifiability) plus direct
re-verification. **These supersede any conflicting earlier decision in this brief.**

## D-I — Sequencing, superseding D-C

The declared waves deadlocked: 27-02 (wave 2) purged the nine dead letters before 27-03 (wave 4)
could archive them, so 27-03 STOPs at its own T0.8. That split was assigned without checking the wave
order. New order, with every plan a **single schedulable unit**:

| Wave | Plans | Why |
|---|---|---|
| 1 | **27-00**, **27-01**, **27-05** | no dependencies; 27-05 must precede any DLQ disposition |
| 2 | **27-04** | needs 27-00's load harness |
| 3 | **27-03** | needs 27-00 (scrape fix + liveness mechanism), **27-04** (the `RabbitMQConfig` signature it rebases onto), 27-05 (the diagnosis) |
| 4 | **27-02** | needs 27-01 (PENDING check), 27-03 (archive done), 27-05 (replay viable) |
| 4 | **27-06** (new) | CI wiring — needs the gate scripts from 27-00 **and** 27-03 to exist. Wave 4 alongside 27-02: they share no dependency, and the DAG permits it (verified 0 violations) |

> **CORRECTED 2026-07-27 — my fourth sequencing error.** The first version of this table put 27-03
> and 27-04 both in wave 2, while simultaneously requiring 27-03 to `depends_on: [27-04]`. A plan
> cannot depend on one executing beside it. Waves are now assigned by longest path, so every
> `depends_on` points strictly backwards. Verify with:
> `for f in 27-0*-PLAN.md; do grep -m1 '^wave:' $f; grep -m1 '^depends_on:' $f; done`
> — every dependency's wave must be **strictly less** than the dependent's.

Archive stays with 27-03; purge stays with 27-02; the wave order now actually permits it.
27-03 drops T0.8's STOP-if-not-9 and asserts "≥ 1 message present" instead.

## D-J — Extract CI wiring into a new plan 27-06

**M3 found a cycle**: `27-00{T1,T4} → 27-03 → 27-00{T7}`, invisible because the back-edge was prose.
Root cause: 27-00 is one schedulable unit whose tasks its own narrative spreads across five waves.
**Move 27-00 Task 7 out into `27-06-PLAN.md` — "CI gate wiring"**, `wave: 4`,
`depends_on: [27-00, 27-03]`. The graph becomes a true DAG at plan granularity.

27-06 wires **three** static gates, not two:
`check-dependency-horizons.sh`, `check-terminal-states.sh`, **and `check-alert-rules.sh`**.

## D-K — `check-alert-rules.sh` was dropped by both owners. Restore it.

The regression audit's headline. 27-03 gave up CI wiring per D-B; 27-00 never took it
(`grep -c check-alert-rules 27-00-PLAN.md` → **0**, against 16 mentions in 27-03 asserting "27-00
Task 7 wires it"). Worse, 27-00's **AC-7.3 asserts `grep -c 'chmod +x'` == 2**, which actively forbids
the third step. Without it, 27-03's own headline finding — F-8, "there is no CI validation of
`alerts.yml` at all" — ends the phase unclosed. In 27-06: three steps, and the assertion becomes
**== 3**.

## D-L — 27-02's rollback is inert. Fix the hostname ordering.

**Verified:** live broker hostname `53955960a605`; volume holds `rabbit@53955960a605` (live) plus 9
orphaned node dirs. AC-13 restores with `--hostname jtoye-rabbitmq` → looks for
`rabbit@jtoye-rabbitmq` → **boots empty**. Its BREAK arm (no `--hostname`) also boots empty. **Both
arms return 0**; the criterion can neither pass nor discriminate. The same inversion appears in Task 2
step 4 and in the rollback statement — and since there is no downgrade path from 4.3.4 Khepri, this
is the *sole* rollback.

Fix: apply the `hostname:` pin **after** the fresh 4.3.4 install, or rename the node dir inside the
volume before recreating. The restore probe must use the hostname the tarball was taken under
(`53955960a605`), not the future pinned name. Also correct: `:209` says "nine node directories"
including the live one — measured truth is **10 dirs, 9 orphaned + 1 live**; `:870` is right, `:209`
is wrong and its figure is quoted into a shipped compose comment.

## D-M — 27-04 AC-9 is self-refuting. Replace it.

Its break renames `test/resources/application-test.yml` to unshadow the exclusion, expecting
`NoSuchBeanDefinition` for the configurer. Two independent reasons it cannot work:
1. There is **no `@Bean` AMQP `ConnectionFactory` anywhere**, so with `RabbitAutoConfiguration` truly
   excluded, parameter 0 fails **before** the configurer parameter — the hard-injection and
   `ObjectProvider` forms fail *identically*, which is the exact vacuity AC-9 replaced.
2. That file also supplies the **entire H2 test datasource**, `flyway.enabled: false`,
   `cache.type: none` and the rabbit dead-port shim. Renaming it makes the context die on the
   datasource (falling back to `jdbc:postgresql://localhost:5432` — nothing listens there; the
   container publishes **5433**). A negative result that did not come from the thing being tested.

`ObjectProvider` stays. Prove it differently: a slice/unit test that builds the factory bean with the
provider **empty** and asserts it boots with the WARN, and with the provider **populated** asserts
`configure(...)` was invoked. No file renaming.

## D-N — 27-03's stated DLQ diagnosis is wrong and must not reach GitHub

27-03:632 attributes the nine to *"a transient failure inside `insertPendingRows`"*. That method has
**never executed** for any order event. The real cause is the untrusted-package converter defect now
owned by **27-05**. 27-03 must: cite 27-05 as the cause, drop the wrong diagnosis, and **remove the
#205 comment from T6.5** — posting a wrong root cause publicly is worse than posting nothing. 27-05
owns the #205 comment.

## D-O — Depth-is-exactly-9 assertions are unsafe

The producer is live (oldest x-death `2026-07-15T11:46:18Z`, newest `2026-07-26T15:33:51Z`; 5
arrivals in ~25 h). Affected: 27-03 T0.8, T2.3, T5.9, T6.4, T7.7; 27-02 Task 2 step 4, AC-13, and its
`must_haves` truth. Replace every literal `9` with **`>= 1`** plus the count recorded from the
archive, or pin to the archived message IDs. After 27-05 lands the arrival rate goes to zero, but the
plans must not assume that before it does.

## D-P — `POSTGRES_EXPORTER_SSLMODE` is a MODIFICATION, and it is double-owned

`.env.example:14` already reads `POSTGRES_EXPORTER_SSLMODE=require` (see the corrected D-G block —
my earlier "no match" was a case-sensitive grep against an uppercase key). Therefore:
- it is `require` → `disable`, **not** an addition; 27-03's "adds exactly one key" is wrong
- **27-00 owns the `.env.example` edit** (it already owns the Slack keys there); 27-03 owns the
  compose/DSN change and declares the key as a precondition
- 27-00's AC-1.7 must assert the **modification** (`=disable` == 1 **and** `=require` == 0), not a
  bare addition count

## D-Q — 27-00's horizon schema must accept 27-02's handover

27-02 hands over a `rabbitmq-k8s` row with `kind: out_of_repo`, `owner: UNASSIGNED` and an UNKNOWN
state, and its AC-10 asserts a three-state contract (0 CLEAN / 1 FINDING incl. UNKNOWN / 2 VOID).
27-00's schema has none of these: `kind: image|toolchain|first_party`, no `owner`, no UNKNOWN, and
H-1 discovers rows *from source files* — an out-of-repo pin has no source to discover. Add the kind,
the `owner` field and the UNKNOWN exit state, or 27-02's AC-10 is unsatisfiable and the property
"an unknown horizon is a finding, not a pass" is lost for the one dependency it was invented for.

## D-R — Wave metadata must agree

`27-01` frontmatter says wave 1 while 27-00's narrative says wave 2; `27-04` says wave 2 while 27-00
says wave 3 and 27-03 reasons from "27-04 is wave 3". Set every frontmatter to the D-I table and
delete contradicting prose. 27-03 must additionally declare `depends_on` on **27-04** (the
`RabbitMQConfig` signature it rebases onto) and **27-05**, and 27-02 on **27-03** and **27-05**.

## D-S — STANDING ENVIRONMENT FACTS (verified 2026-07-26; they void criteria silently)

1. **`grep` in this shell is a bash function dispatching to ugrep 7.5.0, not GNU grep.**
   ```
   type grep            → grep is a function
   grep --version       → ugrep 7.5.0
   /usr/bin/grep        → GNU grep 3.11
   grep -rn PATTERN .        → docker-compose.full-stack.yml:144      (no ./ prefix)
   /usr/bin/grep -rn ... .   → ./docker-compose.full-stack.yml:144    (./ prefix)
   ```
   **Any path-exclusion regex written against `./docs/analysis/...` matches nothing here.** 27-02's
   AC-2 recorded a control of 5 that measures **34** in this shell. Fix pattern: enumerate with
   `git ls-files` + an explicit in-scope path list and use `grep -F`; record `grep --version` in the
   SUMMARY. **Never write a path-prefix-dependent exclusion.**

2. **`grep -c` returning 0 exits 1.** Under `set -e` an expected-0 criterion kills its own harness.
   Use `| wc -l`, or `|| true` with an explicit comparison.

3. **Greps are case-sensitive by default and this repo mixes cases.** `grep -c "sslmode" .env.example`
   → **0**; `grep -ci` → **1** (`POSTGRES_EXPORTER_SSLMODE=require` at `:14`). This produced a wrong
   correction in this very brief. Use `-i` or match the real case.

4. **`PATH=/nonexistent bash <script>` exits 127 — the script never runs.** It tests nothing, so it is
   a vacuous VOID arm. Use a PATH carrying bash + coreutils but **not** the tool under test; proven:
   `render-golden.sh` then exits **2** with `ERROR: kubectl not on PATH`.

5. **PromQL unescapes `\\` before RE2 sees it** — so `queue=~".*\\.dlq"` means a literal dot and works
   identically to `[.]`. Measured: `3\\.12.*` → 1 series, `3[.]12.*` → 1, control `9[.]99` → EMPTY,
   and a single-backslash `\.` is a **parse error** (`bad_data: unknown escape sequence`). The earlier
   m8 claim in this brief — that `\\.` "matches nothing" — was **wrong**. `[.]` remains fine as house
   style, but not as a correctness fix.

6. **`awk '/^  job:/,/^  [a-z-]+:$/'` collapses to one line** when the start pattern also matches the
   end pattern. Verified against `branch-parity`, `openapi-compat`, `security-scan` (1 line each).
   Use `awk '/^  job:/{f=1;next} f && /^  [a-z-]+:$/{exit} f'` and always pair it with a control on a
   job known to have N steps (`k8s-validate` → 5 `chmod +x`).

7. **`docs-freshness.sh --write` recomputes from the working tree**, so `git diff --quiet
   docs/metrics.json` is clean on *any* base and cannot detect a stale baseline. On this branch it
   exits 0 at **1736** while `origin/main` is **1759**. Assert the base explicitly:
   `git show origin/main:docs/metrics.json | jq -e '.total_logical_invocations==1759'`.

8. **`core-java/build/` is stale (2025-12-27); the live dir is `build-local` (2026-07-26).**

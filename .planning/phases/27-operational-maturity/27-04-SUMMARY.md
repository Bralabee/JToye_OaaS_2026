# 27-04 SUMMARY — the listener-factory family was a silent no-op, and tenant isolation now has a test that can fail

**Branch:** `feature/27-04-consumer-concurrency` · **Merged:** PR **#331** → `9858370`, 2026-07-29 ·
**Wave 2** · **Requirements:** OPS-03 · **Tasks 1–8 complete (8/8).**

`RabbitMQConfig` declared a bean named `rabbitListenerContainerFactory`. Boot's own factory is
`@ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")`, so Boot backed off and
`SimpleRabbitListenerContainerFactoryConfigurer` — the **only** consumer of
`spring.rabbitmq.listener.simple.*` — never ran. That entire property family was inert, including
the `spring.rabbitmq.listener.simple.auto-startup=false` that **22 test files** register. This plan
repairs the factory, adds a dedicated `mediaRabbitListenerContainerFactory` on a config layer, and
raises media consumer concurrency behind two independently-derived budget walls.

Full falsification record: `27-04-EVIDENCE.md`.

---

## What shipped

| Task | Deliverable | Commit (pre-squash) |
|---|---|---|
| 1 | `media-process` timer, sampled after the GUC pin | `3d450a8` |
| 5.1 | `check-consumer-thread-budget.sh` — was **rc=1** before this plan | `ecef0ae` |
| 2 | Measurement harness + Arm A baseline | `febfe70` |
| 3 | Factory repair + dedicated media container | `841e487` |
| 4 | Config layer, `DB_POOL_SIZE` 10→12, T5.2 gate | `922a69f` |
| 2 | Arm B + budget provenance | `bfa2d0d` |
| 6 | STOMP construction-time destination guard | `98c66bf` |
| 7 | `forkEvery` decision + evidence | `92fd370` |
| 8 | Factory + concurrency tests | `8416539` |
| 8 | AC-10 tenant-isolation test | `9f5cfef`, `7b470f2`, `a1c7ad8` |
| 8 | **AC-10 harness fix — the terminal assertion was blind** | `8c2a253` |
| 8 | The falsified arm matrix, in test and worker javadoc | `0a0b306` |

**Shipped numbers:** `mediaConcurrency=1`, `mediaMaxConcurrency=2`, `mediaPrefetch=2`,
`DB_POOL_SIZE 10→12` (user-approved). Both budget walls land independently on 2: concurrency 3
breaks `check-consumer-thread-budget.sh` against a pool of 12, and raising the pool to 13 to fit it
breaks `check-connection-math.sh` at 166 > 157.

**AC-2 proven both directions on the running system:**

```
FAIL arm (factory reverted to hand-built, yml left in place):
  the running broker    : {"consumers":1,"prefetch":[250]}      factory startup lines: 0
PASS arm:
  the running broker    : {"consumers":1,"prefetch":[2]}
  event=rabbit_factory_configured factory=media   configurerPresent=true prefetch=2   concurrency=1 maxConcurrency=2
  event=rabbit_factory_configured factory=default configurerPresent=true prefetch=250 concurrency=1 maxConcurrency=1
```

---

## AC-10 — written, green, and worthless for three break arms

AC-10 is this plan's load-bearing security proof (threat **T-27-01**, issue **#284**). It was
written, passed, and **survived three successive break arms**, which meant it was not evidence. It
was recorded as such (`f3f7440`) rather than reported as a pass.

**The recorded hypothesis was wrong.** It held that `ALTER ROLE … NOSUPERUSER` does not reach
Hikari's already-established sessions, so the workers kept superuser and *bypassed* FORCE RLS. The
opposite was true: **RLS was genuinely enforced, and its enforcement is what blinded the assertion.**

`MediaProcessingWorker` returns *without throwing* when RLS hides its row
(`reason=asset_not_visible`), so an isolation failure surfaces **only** as a row left `PENDING`. The
terminal check counted PENDING rows on an *untransacted* connection with no tenant GUC pinned — under
the downgraded role `current_tenant_id()` is NULL, the policy filters every row, and the count is
structurally 0. Measured with a probe placed immediately after the downgrade, when all 12 seeded
assets are provably PENDING and no worker has run:

```
[VACUITY PROBE: all 12 seeded assets are PENDING and no worker has run]
expected: 12
 but was: 0
```

Nothing else in the test was processing-sensitive: the worker never rewrites `tenant_id`, so the
ownership loop held either way, and the no-throw early return kept the `failures` list empty.

**Fix:** the read-back goes through the tenant-pinned path and carries `status`. The probe is kept as
a **permanent non-vacuity guard on the instrument** — before any worker runs, the read-back must SEE
`PER_TENANT` PENDING rows per tenant, so a later "nothing is PENDING" cannot be blindness.

### The arm matrix — all four run on the real tree

| arm | `TenantContext` | explicit `set_config` | result |
|---|---|---|---|
| pass | correct | present | **GREEN** |
| 1 — *the break the plan prescribes* | correct | **DELETED** | **GREEN** |
| 2 | **wrong (random UUID)** | present | **RED** |
| 3 | **wrong (random UUID)** | **DELETED** | **RED** |

Both RED arms fail on the still-PENDING isolation assertion, naming all 6 of a tenant's assets — the
assertion itself, not a harness accident.

---

## Three claims this plan REFUTES — do not re-assert them

1. **"The worker has two independent tenant pins."** No. `TenantSetLocalAspect` re-pins the GUC from
   `TenantContext` before **every** repository call, so it is the **last writer** before the claim
   query and it **overwrites** a correct explicit pin with a wrong ThreadLocal. The pins are
   **ordered, not redundant**: the explicit `set_config` is redundant while `TenantContext` is
   correct (arm 1 GREEN) and powerless when it is wrong (arm 2 RED). **`TenantContext.set` is the
   single dominant control.** `MediaProcessingWorker`'s javadoc asserted the opposite and is
   corrected.
2. **The plan's own prescribed break arm** ("omit the `session.doWork` pin") is **vacuous** —
   measured GREEN. The working break arm is a wrong `TenantContext`. Recorded, not silently
   substituted.
3. **The plan's expected-RED prediction** that "assertion (b) fails independently" — it did **not**
   fire in either RED arm. (b) checks `is_local` scoping, which is unaffected by *which* tenant is
   pinned. Only assertion (a)'s status half fired.

---

## Measurement findings that should shape future work

Artifacts: `infra/load-testing/baselines/2026-07-28-media-{A-baseline,B-candidate}.md`,
`.planning/phases/27-operational-maturity/baselines/T7-*`.

1. **The media pipeline is outbox-paced, not queue-paced.** `media.outbox.flush-interval-ms` is 5000,
   so arrival is batched and one consumer drains a batch inside the interval. Depth stayed **0** even
   under an 8-way concurrent burst. **A depth of 0 does NOT mean idle**, and raising concurrency
   cannot help without a backlog.
2. **One consumer already saturates one core** (97.8% under a 1-CPU pin). Dropping prefetch 250→2
   cost ~3%, inside the run-to-run spread — the fairness fix is effectively free.
3. **D-11 is REFUTED, and this is recorded in `build.gradle.kts`.** The repair did **not** make
   `forkEvery` removable. There were TWO causes and 27-04 fixed one: pre-fix the OOM lands on
   `RabbitListenerEndpointContainer#7-37`; post-fix on `HttpClient-N-SelectorManager` and
   `idle-connection-reaper` (reactive WebClient + AWS SDK v2). **Do not remove `forkEvery(4)`** on the
   reasoning that the listener bug is fixed.

```
forkEvery(4), post-fix   2337s   peak 209 threads (median 80)     SUCCESS 102/414
forkEvery(0), post-fix   3601s   peak 859 threads (median 820)    OOM at the 1h ceiling
forkEvery(0), PRE-fix     937s   peak 1880 threads (median 1543)  OOM before its ceiling
```

---

## Verification at close

| suite | result |
|---|---|
| `:core-java:cleanTest test` | **116 classes / 832 tests / 0 fail / 0 err / 1 skip** |
| `:core-java:cleanIntegrationTest integrationTest` | **104 classes / 416 tests / 0 fail / 0 err / 1 skip** (42m29s local, 47m42s CI) |

First end-to-end `integrationTest` run on the tree; it also covered three **major** dependency bumps
the base merge brought (spring-statemachine 3.2.1→4.0.2, stripe-java 28.2.0→33.1.1, awssdk bom
2.47.6→2.49.2). The +2 classes / +2 tests vs the 102/414 baseline is **explained**: exactly two new
`@Tag("testcontainers")` classes since `92fd370`.

All 10 static gates rc=0. Runtime parity proven **by content**: all 4 built services rebuilt and
recreated, `check-runtime-freshness.sh` rc=0 (0 unverified), and the shipped values read out of the
running artifact (`unzip -p /app/app.jar BOOT-INF/classes/application.yml`).

---

## Acceptance criteria

| proven both directions | pass direction only |
|---|---|
| AC-2, AC-4, AC-5, AC-6, AC-7, AC-8, **AC-10**, AC-11, AC-12 | AC-3 (break arm Case B), AC-9 (break arms), AC-1 (set-wise assertion), AC-13 |

---

## Handed on

- **27-03** rebases onto this plan's `RabbitMQConfig` signature (the bean is no longer named
  `rabbitListenerContainerFactory`; there is now a dedicated `mediaRabbitListenerContainerFactory`)
  and must replace its diff-scan T5.5 with the behavioural assertion in
  `RabbitListenerContainerFactoryTest` — a diff grep fires on this plan's *correct* change.

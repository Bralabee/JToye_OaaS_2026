# Handoff: 27-04 is 7/8 done — only T8's AC-10 (tenant isolation under concurrency) remains

**Generated:** 2026-07-28 ~13:00 BST. Supersedes the 27-01-era handoff that was on this branch.
NOTE: a *newer* handoff also exists on `feature/domain-olajay` (PR #317) covering the domain move
and the AKS scoping; its still-open items are carried forward in §7 below.

---

## 0. WHERE TO RESUME — one thing

**Write AC-10, the tenant-isolation-under-concurrency test.** It is the only outstanding piece of
plan 27-04 and it is the plan's *load-bearing security proof* (threat T-27-01, issue **#284**).
Everything else is done, committed, pushed and proven in both directions.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-04-consumer-concurrency
git fetch origin && bash scripts/check-branch-behind-base.sh   # expect rc=0
```

Spec: `.planning/phases/27-operational-maturity/27-04-PLAN.md` — read **AC-10** and the **T8** block.
Short version:

- Two tenants' media events interleaved across **concurrent** consumers, Postgres role downgraded
  `NOSUPERUSER` so RLS is genuinely enforced (see the RLS caveat in
  `core-java/src/test/java/uk/jtoye/core/testsupport/IntegrationTestSupport.java`).
- Assert **both** halves; (b) is what makes it capable of failing:
  - (a) no asset is written or read under the wrong tenant;
  - (b) at the **start of each worker transaction**, on a connection known to have been reused from
    the Hikari pool, `SELECT current_setting('app.current_tenant_id', true)` returns **empty**.
- **Break arm = OMIT THE PIN ENTIRELY** on one of the two interleaved consumers (delete its
  `session.doWork(...)` block in `MediaProcessingWorker`). Do **NOT** use the draft's break of
  flipping `is_local` to `false`: every worker re-pins before use, so the test passes with `false`
  and the criterion is vacuous.
- Missing tooling / unparseable / **EMPTY** result set → exit **2 (VOID)**, never 0.

Why it matters now: before 27-04 a weakened pin would leak on one thread; after it, on N.

---

## 1. Git & environment

| | |
|---|---|
| Checkout | `/home/sanmi/IdeaProjects/JToye_OaaS_2026` |
| Branch | **`feature/27-04-consumer-concurrency`** @ `8416539` — clean, pushed, **0 behind** `origin/main` |
| Commits | 9 ahead of main (includes one merge of `origin/main` for #319) |
| `main` | `33ef87c` |
| Open PRs | **#317** (domain -> olajay.co.uk) and **#318** (Trivy fix) — both OPEN, both green, no failing checks. 23 dependabot PRs untouched. |
| Stack | Compose UP and healthy; `core-java` rebuilt at handoff; all 4 services FRESH |
| Java | JDK 21, `./gradlew` from repo root |

### Gate state at handoff (real output, rc captured correctly)

```
docs-freshness                 rc=0   (1831)
check-branch-behind-base       rc=0
check-no-measured-placeholders rc=0
check-consumer-thread-budget   rc=0   <- was rc=1 BEFORE this plan
check-connection-math          rc=0
check-env-contract             rc=0
check-render-invariants        rc=0
render-golden                  rc=0
check-no-plaintext-secrets     rc=0
check-runtime-freshness        rc=0   (all 4 services FRESH)
```

Unit suite: **116 classes / 832 tests / 0 failures / 0 errors / 1 skipped.**

**`integrationTest` has NOT been run end-to-end on the final tree.** It ran three times during T7,
but the last full green run (arm A: 2337s, 102 classes / 414 tests / 0 fail) predates T6 and T8.
Run `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest` (~40 min) before the PR.

---

## 2. What 27-04 delivered

| task | state | commit |
|---|---|---|
| T1 media-process timer | done | `3d450a8` |
| T5.1 consumer-thread budget gate | done | `ecef0ae` |
| T2 harness + Arm A | done | `febfe70` |
| T3 factory repair + media container | done | `841e487` |
| T4 config layer + pool 10->12 + T5.2 gate | done | `922a69f` |
| T2 Arm B + budget provenance | done | `bfa2d0d` |
| T6 STOMP construction-time guard | done | `98c66bf` |
| T7 forkEvery decision + evidence | done | `92fd370` |
| T8 factory + concurrency tests | **partial** | `8416539` |
| T8 **AC-10** | **NOT DONE** | — |

### The core fix

`RabbitMQConfig` declared a bean named `rabbitListenerContainerFactory`; Boot's factory is
`@ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")`, so Boot backed off and
`SimpleRabbitListenerContainerFactoryConfigurer` — the ONLY consumer of
`spring.rabbitmq.listener.simple.*` — never ran. That whole family was a silent no-op, including
the `auto-startup=false` that **22 test files** register.

**AC-2 proven both directions on the running system:**

```
FAIL arm (factory reverted to hand-built, yml left in place):
  yml inside the running jar : media-prefetch: ${JTOYE_RABBIT_MEDIA_PREFETCH:2}
  the running broker         : {"consumers":1,"prefetch":[250]}
  factory startup lines      : 0

PASS arm:
  the running broker         : {"consumers":1,"prefetch":[2]}
  event=rabbit_factory_configured factory=media   configurerPresent=true prefetch=2   concurrency=1 maxConcurrency=2
  event=rabbit_factory_configured factory=default configurerPresent=true prefetch=250 concurrency=1 maxConcurrency=1
```

### Shipped numbers

`mediaConcurrency=1`, `mediaMaxConcurrency=2`, `mediaPrefetch=2`, `DB_POOL_SIZE 10->12`
(user-approved 2026-07-28). Both budget walls independently land on 2: concurrency 3 breaks
`check-consumer-thread-budget.sh` against a pool of 12, and raising the pool to 13 to fit it breaks
`check-connection-math.sh` at 166 > 157.

---

## 3. Measurement results — read before changing any number

Artifacts: `infra/load-testing/baselines/2026-07-28-media-{A-baseline,B-candidate}.md` and
`.planning/phases/27-operational-maturity/baselines/T7-*`.

**Media pipeline (1-CPU pin, 200/200 accepted 202):**

```
arm A (prefetch 250)  606.0 ms/msg  1.650 msg/s/consumer  peak CPU 97.8%
arm B (prefetch 2)    627.2 ms/msg  1.594 msg/s/consumer  peak CPU 96.9%
peak queue depth 0 and peak unacked 0 in BOTH arms
```

Two findings that should shape future work here:

1. **The pipeline is outbox-paced, not queue-paced.** `media.outbox.flush-interval-ms` is 5000, so
   arrival is batched and one consumer drains a batch inside the interval. Depth stayed 0 even under
   an 8-way concurrent burst. A depth of 0 does NOT mean idle, and raising concurrency cannot help
   without a backlog. See `docs/runbooks/messaging.md` section 4.
2. **One consumer already saturates one core** (97.8% under a 1000m-equivalent pin). Dropping
   prefetch 250->2 cost ~3%, inside the run-to-run spread — the fairness fix is effectively free.

**T7 forkEvery (three arms, same 88 tagged classes):**

```
forkEvery(4), post-fix  2337s  peak 209 threads (median 80)    SUCCESS 102/414
forkEvery(0), post-fix  3601s  peak 859 threads (median 820)   OOM, hit the 1h ceiling
forkEvery(0), PRE-fix    937s  peak 1880 threads (median 1543) OOM, died before its ceiling
```

**The plan's D-11 hypothesis is REFUTED, and this is now recorded in `build.gradle.kts`.** The
repair did NOT make `forkEvery` removable. There were TWO causes and 27-04 fixed one: pre-fix the
OOM lands on `RabbitListenerEndpointContainer#7-37`; post-fix it lands on
`HttpClient-N-SelectorManager` and `idle-connection-reaper` (reactive WebClient + AWS SDK v2).
**Do not remove `forkEvery(4)` on the reasoning that the listener bug is fixed.**

---

## 4. Traps found THIS session — each cost real time

- **`docker update --cpus=0` does not release a CPU pin** on Docker 29.6.2 (exits 0, changes
  nothing). Use `--cpu-quota=-1`. And **`docker inspect .HostConfig.NanoCpus` is stale metadata** —
  it read `1000000000` in all three states (released/pinned/released), so it cannot discriminate,
  and the plan's AC-5 written on it can never reach its pass direction. Assert the container's own
  `/sys/fs/cgroup/cpu.max` (`max <period>` = released). Memory: `trap_docker_cpu_pin_release`.
- **`echo "$(basename $g) rc=$?"` reports the SUBSHELL's status, not the command's.** A 10-gate
  sweep printed rc=0 for everything, including a genuinely red gate. Capture `rc=$?` on its own line.
- **`[ "$x" = "y" ] && VAR=...` as a function's last command returns 1 when the test fails**, and
  `set -e` turns that into a silent abort. Survived two smoke runs because every upload was 202.
- **`grep` exits 1 on zero matches**; under `set -o pipefail` that killed a new gate silently on the
  clean tree. Use `{ grep ... || true; } | wc -l`.
- **A 200-upload run outlives the access token** — the first clean run returned `[401]=26`. Refresh
  on an interval and always assert the status distribution.
- **Adding a `@Tag("testcontainers")` class mid-measurement invalidates the comparison.** The two T8
  test files had to be held out of the tree so all three T7 arms ran the same 88 tagged classes.
- **`SimpleMessageListenerContainer.getConcurrentConsumers()` / `getMaxConcurrentConsumers()` are
  not public, and `getAdviceChain()` is protected** — read them via `ReflectionTestUtils.getField`.

Standing traps still live: `grep` is a bash function -> use `command grep` in scripts; the repo
squash-merges so ancestry lies; read counts from `core-java/build-local/`, NEVER `core-java/build/`;
`cleanTest`/`cleanIntegrationTest` are load-bearing (see section 5); `docs/metrics.json` is a
cross-branch conflict hotspot and `CLAUDE.md:15` + `AGENTS.md:15` quote the totals and must change
in the SAME commit; a second session may share this checkout, so stage by explicit path and never
`git add -A`; `git stash -u` is unsafe here (root-owned untracked paths under `infra/monitoring/`).

---

## 5. Evidence that the harness itself can fail (AC-8)

```
run 1 (with cleanTest):       > Task :core-java:integrationTest             BUILD SUCCESSFUL in 54s
run 2 (identical, no clean):  > Task :core-java:integrationTest UP-TO-DATE  BUILD SUCCESSFUL in 5s
```

Success while executing ZERO tests. Every T7 arm ran `cleanIntegrationTest` first for this reason.
A first attempt used a class that is not `@Tag("testcontainers")`, so the task found no tests and
FAILED — and a failed task is never UP-TO-DATE, so it proved nothing.

---

## 6. Acceptance criteria status

| proven both directions | not yet |
|---|---|
| AC-2 (the key one), AC-4, AC-5, AC-6, AC-7, AC-8, AC-11, AC-12 | **AC-10** |
| AC-3 pass direction (both instruments); AC-9 pass direction (7/7 green) | AC-3 break arm (Case B); AC-9 break arms; AC-1 set-wise assertion; AC-13 |

AC-9's recorded observation confirmed finding B empirically:
`ClassLoader.getResource("application-test.yml")` -> `build-local/resources/test/application-test.yml`,
i.e. the test-resources file shadows the main-resources `RabbitAutoConfiguration` exclusion. That
accident is exactly why `ObjectProvider` is used rather than a hard parameter.

---

## 7. Carried forward (not 27-04)

- [ ] **PR #317** and **PR #318** — both green, ready to merge. #318 turns `main` green again
      (12 fixable Trivy HIGHs on the core-java image — an earlier handoff said 2; it was 12).
- [ ] **Phase 27 remaining:** 27-03 (wave 3, depends on 27-04 — it rebases onto this plan's
      `RabbitMQConfig` signature and must replace its diff-scan T5.5 with the behavioural assertion
      supplied in `RabbitListenerContainerFactoryTest`), then 27-02 and 27-06 (wave 4).
- [ ] **AKS deployment** — decided, scoped, NOT started. Blocking: Keycloak hosting decision; no
      `jtoye-infrastructure` manifests exist in this repo; 25 secret keys; no DNS A records on
      `olajay.co.uk` (NS1, not Azure DNS). Detail is in the handoff on `feature/domain-olajay`.
- [ ] 23 dependabot PRs — triage, do not bulk-merge (several violate the pinned stack).
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has
      still never been captured. #266 fixed but unproven.
- [ ] #274 gitleaks allowlists inert; #276 matrix `fail-fast: false`.
- [ ] Wire jest-dom into `tsconfig.json` so the type-error count becomes a real gate.

---

## 8. Residue from this session

- ~400 media uploads were driven through the dev stack across the two measurement arms. The CoW
  ref-counting cleaned up after itself: **one** live `media_asset` remains, not 400.
- The CPU pin **is released** — verified `cpu.max = max 100000` at handoff.
- Compose stack left UP and healthy, `core-java` rebuilt so all four services are FRESH.

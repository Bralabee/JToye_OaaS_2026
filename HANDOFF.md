# Handoff: 27-04 COMPLETE — AC-10 falsified, full suite green, runtime fresh

**Generated:** 2026-07-28 ~22:50 BST. Supersedes the "AC-10 not yet falsified" handoff.

**Branch:** `feature/27-04-consumer-concurrency` @ `1c1aeaf` — clean, **0 behind** `origin/main`
(merged `4774528`, no conflicts). Plan **27-04 is 8/8**.

---

## 0. WHERE TO RESUME

**Push and open the PR** (nothing is blocking it), then move to **27-03** (wave 3).

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-04-consumer-concurrency
git push -u origin feature/27-04-consumer-concurrency
```

⚠ **`origin/main` is currently RED** and it is **not** caused by this branch — see §5.

---

## 1. What closed this session: AC-10

AC-10 is 27-04's load-bearing security proof (T-27-01, issue **#284**). It went from
"written, green, and worthless" to falsified. Full record:
`.planning/phases/27-operational-maturity/27-04-EVIDENCE.md`.

### The defect in the test

The previous handoff's leading hypothesis — that `ALTER ROLE … NOSUPERUSER` misses Hikari's
established sessions, leaving the workers superuser and *bypassing* FORCE RLS — is **REFUTED**. The
truth was the opposite, and more dangerous: **RLS was working, and its working is what hid the
evidence.**

`MediaProcessingWorker` returns *without throwing* when RLS hides its row
(`reason=asset_not_visible`, a WARN and a `return`), so an isolation failure surfaces **only** as a
row left `PENDING`. The terminal `stillPending` count ran on an **untransacted** connection with no
tenant GUC — under the downgraded role `current_tenant_id()` is NULL, the policy filters every row,
and the count is structurally 0. Measured with a probe placed right after the downgrade, when all 12
seeded assets are provably PENDING and no worker has run:

```
[VACUITY PROBE: all 12 seeded assets are PENDING and no worker has run]
expected: 12
 but was: 0
```

**Fix:** read back through the tenant-pinned path, per tenant, carrying `status`. The probe is kept
as a **permanent non-vacuity guard on the instrument** — it must SEE the PENDING rows before the
test is allowed to conclude anything from their absence.

### The arm matrix — all four run on the real tree

| arm | `TenantContext` | explicit `set_config` | result |
|---|---|---|---|
| pass | correct | present | **GREEN** |
| 1 — *the break the plan prescribes* | correct | **DELETED** | **GREEN** |
| 2 | **wrong** | present | **RED** |
| 3 | **wrong** | **DELETED** | **RED** |

Both RED arms fail on the isolation assertion itself, naming all 6 of a tenant's assets.

### Three claims this refutes — do not re-assert them

1. **"Two independent tenant pins."** No. `TenantSetLocalAspect` re-pins from `TenantContext`
   before every repository call, so it is the **last writer** and **overwrites** a correct explicit
   pin with a wrong ThreadLocal (arm 2 RED). The pins are **ordered, not redundant**;
   `TenantContext.set` is the single dominant control. `MediaProcessingWorker`'s javadoc asserted
   the opposite and is corrected in `0a0b306`.
2. **The plan's prescribed break arm** ("omit the `session.doWork` pin") is **vacuous** — measured
   GREEN. Recorded, not silently substituted.
3. **The plan's expected-RED prediction** that "assertion (b) fails independently" — it did **not**
   fire in either RED arm. (b) checks `is_local` scoping, which is unaffected by *which* tenant is
   pinned. Only assertion (a)'s status half fired.

---

## 2. Verification state — all real output, rc captured on its own line

```
docs-freshness                 rc=0        check-consumer-thread-budget   rc=0
check-branch-behind-base       rc=0        check-connection-math          rc=0
check-no-measured-placeholders rc=0        check-env-contract             rc=0
check-runtime-freshness        rc=0        check-render-invariants        rc=0
check-no-plaintext-secrets     rc=0        render-golden                  rc=0
```

| suite | result |
|---|---|
| `:core-java:cleanTest test` | **116 classes / 832 tests / 0 fail / 0 err / 1 skip** (42s) |
| `:core-java:cleanIntegrationTest integrationTest` | **104 classes / 416 tests / 0 fail / 0 err / 1 skip** (42m29s) |

**This is the first end-to-end `integrationTest` run on this tree** — the previous handoff's open
item. It also covers the three **major** dependency bumps the merge brought (spring-statemachine
3.2.1→4.0.2, stripe-java 28.2.0→33.1.1, awssdk bom 2.47.6→2.49.2).

Delta vs the recorded 102/414 T7 baseline is **+2 classes / +2 tests, and is explained**: exactly
two `@Tag("testcontainers")` classes were added since `92fd370` —
`MediaListenerConcurrencyIntegrationTest` and `MediaTenantIsolationUnderConcurrencyIntegrationTest`.
`RabbitListenerContainerFactoryTest` is untagged and lands in the unit suite. Counts read from
`core-java/build-local/` — **never** `core-java/build/`, which is a stale 2025-12-27 artifact
reporting a false RED.

### Runtime parity — proven by content, not status

All 4 built services rebuilt and recreated, `check-runtime-freshness.sh` **rc=0, 0 unverified**.
The merge made edge-go and frontend stale (`go.mod`, `package.json`) and the AC-10 javadoc made
core-java stale; all three were rebuilt with `up -d --build`. Read out of the running artifact:

```
inside /app/app.jar : media-prefetch: ${JTOYE_RABBIT_MEDIA_PREFETCH:2}
                      media-concurrency: ${…:1}   media-max-concurrency: ${…:2}
running broker      : media.process  consumers=1
fresh container log : event=rabbit_factory_configured factory=media   configurerPresent=true prefetch=2   concurrency=1 maxConcurrency=2
                      event=rabbit_factory_configured factory=default configurerPresent=true prefetch=250 concurrency=1 maxConcurrency=1
```

Matches the recorded AC-2 PASS arm exactly.

---

## 3. Acceptance criteria

| proven both directions | pass direction only |
|---|---|
| AC-2, AC-4, AC-5, AC-6, AC-7, AC-8, **AC-10**, AC-11, AC-12 | AC-3 (break arm Case B), AC-9 (break arms), AC-1 (set-wise assertion), AC-13 |

---

## 4. What 27-04 delivered

The core fix: `RabbitMQConfig` declared a bean named `rabbitListenerContainerFactory`; Boot's factory
is `@ConditionalOnMissingBean(name = …)`, so Boot backed off and
`SimpleRabbitListenerContainerFactoryConfigurer` — the ONLY consumer of
`spring.rabbitmq.listener.simple.*` — never ran. That whole family was a silent no-op, including the
`auto-startup=false` that **22 test files** register.

Shipped: `mediaConcurrency=1`, `mediaMaxConcurrency=2`, `mediaPrefetch=2`, `DB_POOL_SIZE 10→12`.
Both budget walls independently land on 2.

**Measurement findings that should shape future work** (artifacts in
`infra/load-testing/baselines/2026-07-28-media-{A-baseline,B-candidate}.md`):

1. **The pipeline is outbox-paced, not queue-paced** — `media.outbox.flush-interval-ms` is 5000, so
   depth stayed 0 even under an 8-way burst. Depth 0 does NOT mean idle, and raising concurrency
   cannot help without a backlog.
2. **One consumer already saturates one core** (97.8% under a 1-CPU pin). prefetch 250→2 cost ~3%,
   inside the run-to-run spread — the fairness fix is effectively free.
3. **D-11 is REFUTED and this is recorded in `build.gradle.kts`.** The repair did NOT make
   `forkEvery` removable — there were TWO causes and 27-04 fixed one. Post-fix the OOM lands on
   `HttpClient-N-SelectorManager` + `idle-connection-reaper` (reactive WebClient + AWS SDK v2).
   **Do not remove `forkEvery(4)` on the reasoning that the listener bug is fixed.**

---

## 5. ⚠ `origin/main` is RED, and it is not this branch

`CI/CD Pipeline` **failed on `main` @ `1500f22`** — job **Integration Tests (Testcontainers RLS)**,
with Postgres containers refusing connections on every mapped port (`Connection to localhost:32771
refused`, then 32772, 32773 …) and Hikari timing out at `total=0, active=0, idle=0`.

**Evidence it is an infrastructure flake, not a code regression:** `1500f22` touched **only**
`.github/dependabot.yml`. The three major dependency bumps landed *earlier* (`e9ae960`, `f8ab847`,
`6c2f2c9`) and every one of those runs was **green**. The same suite is green locally on this branch
at 104/416. A re-run of that workflow is the likely fix; do not chase a code cause first.

(The same startup-race signature appeared briefly in the local run and Testcontainers retried
through it — `scripts/fix-bridge-network.sh` / `fix-testcontainers-docker.sh` exist for this.)

---

## 6. Traps confirmed this session

- **RLS blinds the verification query.** Under a `NOSUPERUSER` downgrade an *unpinned* query returns
  0 rows on a full table, so `assertThat(count).isZero()` is structurally satisfied and survives
  every break arm. It fails in the *safe-looking* direction and breaking the production code does
  not un-blind it. **Prove the instrument can SEE the rows before trusting its silence.**
- **The tenant pin sits under a global aspect** — recorded from 27-01, and it **recurred here with
  the plan prescribing the vacuous break**. New detail: the aspect does not merely supply a missing
  pin, it *overwrites a correct one*.
- A worker that **returns without throwing** on the failure path removes the test's only exception
  signal — the row's state becomes the sole observable, so blinding one query kills the whole test.
- Restores after a break arm were verified **by token** (`break_tokens=0`, the pin line present,
  `dirty=0`), never by `git diff --stat`.

Standing traps still live: `grep` is a bash function → `command grep` in scripts;
`cleanTest`/`cleanIntegrationTest` are load-bearing (without them the task reports UP-TO-DATE while
executing NOTHING); the repo squash-merges so ancestry lies; `docs/metrics.json` is a cross-branch
conflict hotspot and `CLAUDE.md:15` + `AGENTS.md:15` quote the totals; a second session may share
this checkout, so stage by explicit path and never `git add -A`; `git stash -u` is unsafe here
(root-owned untracked paths under `infra/monitoring/`).

---

## 7. Carried forward (not 27-04)

- [ ] **Phase 27 remaining:** 27-03 (wave 3, depends on this plan — it rebases onto this
      `RabbitMQConfig` signature and must replace its diff-scan T5.5 with the behavioural assertion
      in `RabbitListenerContainerFactoryTest`), then 27-02 and 27-06 (wave 4).
- [ ] **`main` is red** — see §5. Re-run the workflow.
- [ ] **AKS deployment** — decided, scoped, NOT started. Blocking: Keycloak hosting decision; no
      `jtoye-infrastructure` manifests in this repo; 25 secret keys; no DNS A records on
      `olajay.co.uk` (NS1, not Azure DNS).
- [ ] Dependabot PRs — triage, do not bulk-merge (several violate the pinned stack). Note the merge
      already brought **three major bumps** onto this branch: spring-statemachine 4.0.2,
      stripe-java 33.1.1, awssdk bom 2.49.2. `CLAUDE.md` still records "Stripe Java SDK 28.2.0" and
      is now stale on that line.
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has
      still never been captured. #266 fixed but unproven.
- [ ] #274 gitleaks allowlists inert; #276 matrix `fail-fast: false`.
- [ ] Wire jest-dom into `tsconfig.json` so the type-error count becomes a real gate.

## 8. Residue

- Compose stack UP and healthy; all 4 built services FRESH and recreated from this tree.
- No CPU pin is in place (this session set none).

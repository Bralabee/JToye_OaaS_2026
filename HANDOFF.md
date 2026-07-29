# Handoff: 27-04 MERGED — next is 27-03 (wave 3)

**Generated:** 2026-07-29 ~03:40 BST. Supersedes the "27-04 is 8/8, open the PR" handoff.

| | |
|---|---|
| `origin/main` | **`9858370`** — 27-04 merged via PR **#331** (squash), CI **green** |
| Phase 27 | 27-00, 27-01, 27-04 **done**; **27-03 next** (wave 3), then 27-02 + 27-06 (wave 4) |
| Stack | Compose UP; all 4 built services rebuilt and FRESH as of the 27-04 close |

---

## 0. WHERE TO RESUME

**Plan 27-03 (wave 3).** Two things it must inherit from 27-04:

1. It **rebases onto the `RabbitMQConfig` signature that just landed on main** — the bean is no
   longer named `rabbitListenerContainerFactory`, and there is now a dedicated
   `mediaRabbitListenerContainerFactory`.
2. It must **replace its diff-scan T5.5 with the behavioural assertion** already provided in
   `core-java/src/test/java/uk/jtoye/core/config/RabbitListenerContainerFactoryTest.java`.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch origin && git switch -c feature/27-03-<name> origin/main
```

---

## 1. What 27-04 shipped (merged, `9858370`)

`RabbitMQConfig` declared a bean named `rabbitListenerContainerFactory`; Boot's factory is
`@ConditionalOnMissingBean(name = …)`, so Boot backed off and
`SimpleRabbitListenerContainerFactoryConfigurer` — the **only** consumer of
`spring.rabbitmq.listener.simple.*` — never ran. That whole family was a silent no-op, including
the `auto-startup=false` that **22 test files** register.

Shipped: `mediaConcurrency=1`, `mediaMaxConcurrency=2`, `mediaPrefetch=2`, `DB_POOL_SIZE 10→12`.

Full AC-10 record: `.planning/phases/27-operational-maturity/27-04-EVIDENCE.md`.

### Three conclusions that must not be re-asserted

1. **`TenantContext` is the single dominant tenant pin — the two pins are NOT independent.**
   `TenantSetLocalAspect` re-pins from `TenantContext` before every repository call, so it is the
   **last writer** and **overwrites** a correct explicit `set_config` with a wrong ThreadLocal.
   Measured: explicit pin deleted + context correct → GREEN; context wrong + explicit pin intact →
   **RED**.
2. **`forkEvery(4)` must stay.** D-11 is refuted and recorded in `build.gradle.kts`. There were TWO
   OOM causes and 27-04 fixed one; post-fix it lands on `HttpClient-N-SelectorManager` +
   `idle-connection-reaper` (reactive WebClient + AWS SDK v2).
3. **The media pipeline is outbox-paced, not queue-paced** (`media.outbox.flush-interval-ms` 5000).
   Queue depth stayed 0 even under an 8-way burst — depth 0 ≠ idle, and raising concurrency cannot
   help without a backlog. One consumer already saturates one core (97.8% under a 1-CPU pin).

---

## 2. Traps confirmed in the 27-04 close

- **RLS blinds the verification query.** Under a `NOSUPERUSER` downgrade an *unpinned* query returns
  0 rows on a full table, so `assertThat(count).isZero()` is structurally satisfied and survives
  every break arm. It fails in the *safe-looking* direction, and breaking production code does not
  un-blind it. **Prove the instrument can SEE the rows before trusting its silence** — the AC-10
  probe read `expected: 12 but was: 0` with all 12 rows provably PENDING.
- A worker that **returns without throwing** on its failure path (`asset_not_visible`) removes the
  test's only exception signal, leaving the row's state as the sole observable.
- **The Testcontainers-startup flake is real and it reads as a code failure.** `main` went red at
  `1500f22` on *Integration Tests (Testcontainers RLS)* — Postgres containers refusing connections
  on every mapped port, Hikari at `total=0, active=0, idle=0`. A re-run of the **identical SHA**
  went green. Before chasing a code cause, re-run; `scripts/fix-bridge-network.sh` and
  `fix-testcontainers-docker.sh` exist for this.
- Restores after a break arm must be verified **by token**, never by `git diff --stat`.

Standing traps: `grep` is a bash function → `command grep` in scripts; `cleanTest` /
`cleanIntegrationTest` are load-bearing (without them the task reports UP-TO-DATE while executing
NOTHING); counts come from `core-java/build-local/`, **never** `core-java/build/` (a stale
2025-12-27 artifact reporting a false RED); the repo squash-merges so ancestry lies — verify a merge
**by content**; `docs/metrics.json` is a cross-branch conflict hotspot; a second session may share
this checkout, so stage by explicit path and never `git add -A`; `git stash -u` is unsafe here
(root-owned untracked paths under `infra/monitoring/`).

---

## 3. Baselines to measure against

| suite | last full green |
|---|---|
| `:core-java:cleanTest test` | 116 classes / 832 tests / 0 fail / 0 err / 1 skip |
| `:core-java:cleanIntegrationTest integrationTest` | 104 classes / 416 tests / 0 fail / 0 err / 1 skip (~42 min local, ~48 min CI) |

Any delta must be **explained**, not waved through: the last +2/+2 was traced to exactly two new
`@Tag("testcontainers")` classes.

---

## 4. Carried forward

- [ ] **27-03**, then 27-02 + 27-06.
- [ ] **AKS deployment** — decided, scoped, NOT started. Blocking: Keycloak hosting decision; no
      `jtoye-infrastructure` manifests in this repo; 25 secret keys; no DNS A records on
      `olajay.co.uk` (NS1, not Azure DNS).
- [ ] Dependabot PRs — triage, do not bulk-merge (several violate the pinned stack). Note main
      already carries three **major** bumps: spring-statemachine 4.0.2, stripe-java 33.1.1,
      awssdk bom 2.49.2.
- [x] **Doc version drift** — FIXED and now GATED. `scripts/check-doc-versions.sh` compares every
      documented version against `build.gradle.kts` / `package.json` / `go.mod` and runs in the
      `docs-freshness` workflow. It covers `.planning/codebase/STACK.md` as well as `CLAUDE.md` and
      `AGENTS.md`, because STACK.md is the **generated-from source** for the stack section — gating
      only the derived files would let the next GSD regeneration copy stale versions back in.
      `.planning/PROJECT.md` is deliberately **not** gated: its line ~113 is a dated historical
      record ("Spring Boot 3.4.2 … Verified 2026-04-18 post-v2.1") that is correct as history.
      Note the gate checks **all** occurrences, not the first — the drift included a stale
      "Spring Boot Gradle Plugin 3.4.2" sitting below a correct "Spring Boot 3.5.16", which a
      first-match check calls clean (it slipped past two of my own passes before the gate caught it).
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has
      still never been captured. #266 fixed but unproven.
- [ ] #274 gitleaks allowlists inert; #276 matrix `fail-fast: false`.
- [ ] Wire jest-dom into `tsconfig.json` so the type-error count becomes a real gate.

# Handoff: Phase 27 — 27-00, 27-05 and #315 MERGED; 27-01 Tasks 1–5 of 6 done and pushed

> **Update (2026-07-27 evening): 27-01 Tasks 4 AND 5 are COMPLETE and pushed** (`c78072d`).
> Branch is **0 behind** `origin/main` (`9d6ce8c`), 21 ahead at `c3b3300`, tree clean, pushed.
> **ONLY TASK 6 REMAINS** (plan line ~1514) — metrics reconcile, full Java suite, terminal-states
> rows, runtime parity.
>
> | Task | Commits |
> |---|---|
> | 4 | `a94ce77` impl, `fbfedb9` AC-4.5 rewrite, `9501630` OpenAPI snapshot, `17dca23` arms |
> | 5 | `3c23bb7` impl, `033e162` AC-5.5 browser spec, `c78072d` arms |
>
> Read **§3c** (Task 4) and **§3d** (Task 5) before continuing — between them they withdrew or
> corrected **four** criteria the plan states but that cannot fail as written.
>
> **The stack was rebuilt during Task 5** and runtime parity is currently GREEN
> (`check-runtime-freshness.sh` → `PASS: 4 running built service(s) match the source tree`).
> Task 6 must re-run it after any further source change.
>
> Earlier in this handoff: PR #315 is MERGED (`9d6ce8c`) — `runcheck.sh` is on `main` at
> `.planning/phases/27-operational-maturity/baselines/runcheck.sh`. Task 3 is COMPLETE
> (`7d216c4`, `e99efb7`, `c69f373`, `fb4b77d`); §3a/§3b still worth reading.

**Generated:** 2026-07-27; last updated by the session that executed 27-01 **Tasks 4 and 5**.
**Supersedes** the 27-00 handoff. Its §5/§7/§8/§9 content is still live and carried forward below —
do not lose it.

---

## 1. Git & environment

| | |
|---|---|
| Checkout | `/home/sanmi/IdeaProjects/JToye_OaaS_2026` |
| Branch | **`feature/27-01-media-durability`** — **0 behind** `origin/main` (`9d6ce8c`); 21 ahead at `c3b3300`, **pushed** |
| Working tree | clean |
| Other branch | `docs/27-00-planning-artifacts` → PR #315 **MERGED** |
| Stack | Compose up, all 10 services healthy. **`core-java` + `frontend` REBUILT in Task 5** — parity green. (`jtoye-redis-exporter` unhealthy — pre-existing, unrelated, not a compose service) |
| minikube `jtoye` | Stopped — compose XOR k8s, never both |
| `hey` | still at `~/go/bin`, not on PATH by default |

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-01-media-durability
```

### Gate state at handoff (real output, measured at close of Task 5)

```
scripts/check-branch-behind-base.sh   rc=0   21 ahead, 0 behind (base 9d6ce8c) at c3b3300
scripts/docs-freshness.sh             rc=1   <- EXPECTED MID-PLAN. Task 6 owns metrics.json.
scripts/check-runtime-freshness.sh    rc=0   <- GREEN: Task 5 rebuilt core-java + frontend
scripts/check-terminal-states.sh      rc=1   <- still correct until 27-03
scripts/check-alert-liveness.sh       rc=1   <- still correct until 27-03
```

**The three remaining rc=1 are correct right now. None is a regression. Do not "fix" any of them.**

- `docs-freshness` — `metrics.json` is deliberately NOT updated mid-plan. **Task 6 writes it once**;
  the full measured table (manifest vs computed vs what the plan predicted) is in §4 below.
- `check-runtime-freshness` is now **rc=0**: Task 5 rebuilt `core-java` and `frontend` because
  AC-5.5 needed a real browser against real code. Parity was proven BY CONTENT, not by timestamp —
  see §4. It will go stale again the moment Task 6 changes source.

### Test state at close of Task 5 (counts read from `build-local/test-results/`, not from "BUILD SUCCESSFUL")

```
./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.media.*'
  -> classes=5   tests=38  failures=0 errors=0 skipped=0     (was 36 at Task 3 -> +2)

./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests 'uk.jtoye.core.media.*'
  -> classes=18  tests=63  failures=0 errors=0 skipped=0     (was 56 at Task 3 -> +7)

./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest \
    --tests 'uk.jtoye.core.security.*' --tests '*ScopedCatalogAccess*' --tests '*GateStrictness*'
  -> classes=22  tests=118 failures=0 errors=0 skipped=0

cd frontend && npx jest --ci
  -> Test Suites: 62 passed, 62 total   Tests: 419 passed, 419 total

cd frontend && npm run build
  -> ✓ Compiled successfully   (rc 0)

cd frontend && npx playwright test --project=mobile media-review-320.spec
  -> 1 passed   (AC-5.5, against the REBUILT stack)
```

The security run was deliberate: `trap_scope_gate_integrationtest_regression` says a new
`@PreAuthorize` gate has repeatedly broken *existing* integrationTests, and Task 4 adds one
(`POST /{assetId}/reprocess`). **It did not fire this time.** That is NOT a substitute for Task 6's
full-suite run — it covers the auth surface only.

**The FULL Java suite has still NOT been run since Task 1.** Task 6 owns it. The frontend suite HAS
been run in full (above).

---

## 2. What happened this session

### a) PR #314 merged; #315 opened to repair what the merge dropped

`main` is now `60cb641` (27-00's ops spine). The `.planning`-filtered PR workflow shipped the code
but **none of 27-00's execution record** — while every earlier phase (21/22/23) keeps its
`*-SUMMARY.md` on `main`. **PR #315** carries the 14 missing files back, byte-identical and
mode-preserved, including `baselines/runcheck.sh`, which every later plan's break arms depend on.
**Merge it** — until then `runcheck.sh` exists only on `feature/27-00-ops-spine`.

### b) 27-05 was already done — the ROADMAP was lying

The ROADMAP listed 27-05 as unstarted. It was **merged 5 hours earlier as PR #310**. Acting on the
stale checkbox is exactly what nearly happened. It reads as unmerged because the repo
squash-merges, so `git merge-base --is-ancestor f5faaf2 origin/main` says "no" —
**verify by content, never by ancestry.** Verified live:

```
unzip -p /app/app.jar BOOT-INF/classes/.../RabbitMQConfig.class | strings | grep -cF 'uk.jtoye.core'
  -> 3   (uk.jtoye.core.order / .payment / .onboarding)
find / -name RabbitMQConfig.class   -> 0   (misleading by design)
webhook.deliveries.dlq depth        -> 9   (intact for 27-03's archive / 27-02's disposition)
```

Both checkboxes are ticked in #315.

### c) 27-01 Tasks 1–2 executed

| Commit | What |
|---|---|
| `c2e0015` | **Task 1** — V60 columns + Envers mirrors + 2 indexes, `deleteByKeyChecked`, `findReclaimableQuarantine`, `lockForProcessing`, `findLatestDispatchStateForAssets` |
| `88ba77c` | **Task 2** — reaper rewrite (no `StorageService`, no enqueue, dispatch-evidence gate, suspension circuit) + worker claim lock |
| `a9ec510` | **AC-2.8 / AC-2.11** — the claim lock proven against real Postgres |

**Every criterion was run in both directions and the RED recorded.** Highlights:

- AC-1.4 break → `column "process_attempts" of relation "media_asset_aud" does not exist`
- AC-2.6(a) break → **16 completed, 1 failed** — only arm (a); (b) and (c) stayed green, which is
  the independence proof. Breaking one arm and seeing "the test failed" proves nothing about the others.
- AC-2.8 break (drop `@Lock`) → **`Wanted 1 time … But was 2 times`** — both workers ran the
  pipeline on the same derivative key.
- AC-2.11 pass → **loser fails at 507ms with SQLSTATE 55P03 while the holder keeps the row for
  6000ms.** The gap is the evidence.

---

## 3a. Task 3 — DONE. What it proved, and two findings worth keeping

Commits `7d216c4` (sweep + config + D-07 split) and `e99efb7` (AC-3.7/3.8).

`MediaQuarantineRetentionSweep` is now the ONLY component that reclaims quarantine bytes on a
timeout-class path. It is deliberately unconditional — not gated on dispatch evidence or consumer
liveness — which is what makes "reaper forever suspended" survivable. The delete sits BETWEEN two
transactions so a rolled-back batch cannot leave objects deleted with rows still claiming them.

**Finding 1 — the unit form of AC-3.2/3.3(a) was proven vacuous, not assumed.** The plan asserted a
mocked repository never executes the JPQL. Measured: the same `@Query` break that turns the
integration tests RED leaves the unit class **GREEN (rc=0)**. Guard 1 (`status <> ACTIVE`) and
guard 3 (the sentinel) live in the `@Query` → Testcontainers. Guard 2 (`/quarantine/` path) lives in
the sweep's Java → correctly a unit test. Do not "tidy" these back into one class.

**Finding 2 — AC-3.8's break could not fail as written.** Moving the success stamp after
`saveAndFlush` produced **rc=0**. Root cause read from source: `MediaAssetService.placeAsset`
reaches the `@Modifying(clearAutomatically = true)` `repoint` ONLY when a slot already exists —
`if (slot.isEmpty()) { attachPlacement(...); return; }`. The fixture seeded a fresh product, so the
context clear never ran and a mis-ordered stamp survived to commit regardless. Fixed by seeding an
existing ACTIVE primary so the placement DISPLACES it. Break now fires (rc=1); both directions of
the fixture change are recorded in the commit message.

**AC-3.5's two halves were cross-checked**: break 1 trips the env-contract gate but not the
membership loop; break 2 trips membership but not the gate. They test different properties.

## 3b. AC-3.6 — CLOSED (`c69f373`, `fb4b77d`). Task 3 is now complete.

`MediaSweepTenantScopeIntegrationTest`, 2 tests. This one was hard and produced three findings.

**The downgrade had to be redesigned twice.** Two approaches are documented in the class javadoc as
rejected, so they are not re-attempted: (1) `SET LOCAL ROLE` inside the test — the sweep opens its
OWN transactions on pooled connections, so a role set on the test's connection never reaches them;
(2) `ALTER ROLE … SET role` + `softEvictConnections()` — **measurably flaky, 1 failure in 3 runs**,
because soft eviction retires idle connections but not in-use ones. What ships: the application
datasource authenticates as `rls_sweep_role` (NOSUPERUSER/NOBYPASSRLS) from its first connection
while **Flyway keeps the superuser**, with `ALTER DEFAULT PRIVILEGES` issued before context start so
Flyway's tables are auto-granted. No eviction, no race. 3/3 and 4/4 clean runs.

**The plan's stated break does not fail — and this matters beyond 27-01.** Removing the sweep's
explicit `pinTenantGuc` alone leaves the test GREEN, proven as a recorded control arm.
`TenantSetLocalAspect` (`security/TenantSetLocalAspect.java`) pins `app.current_tenant_id` from
`TenantContext` **before every Spring Data repository call inside an active transaction**, so every
explicit `pinTenantGuc` in this codebase is defence-in-depth layered under a global aspect. Any
future criterion of the form "prove component X pins the tenant" must break `TenantContext.set`,
not the explicit pin. Recorded arms: break `TenantContext.set` → rc=1; remove only the explicit pin
→ rc=0 (control).

**The VOID guard was itself vacuous, and the VOID arm is what caught it.** With the downgrade
removed entirely the test still passed, because `assertDowngradeIsReal` opened its own
`DriverManager` connection using the sweep role's credentials — so it always observed the
downgraded role regardless of the datasource under test. It now probes the injected `DataSource`;
the VOID arm then fires with `VOID: the app datasource is not the downgraded role`. **Run the VOID
arm on every guard you write.**

## 3c. Task 4 — DONE. Three findings, one withdrawn criterion

Full arm-by-arm record with real output: **`.planning/phases/27-operational-maturity/baselines/AC-4-ARMS.md`**.
Read it before writing Task 5's criteria.

**Finding 1 — AC-4.3's stated break is NOT implementable, and its stated RED was wrong.** The plan
says "move `shopAccessService.require(...)` above the `findById`", expecting `404 → 403`. Under RLS
there is no asset above the `findById` to resolve an owning shop from, and the request 404s
afterwards regardless. The arm that DOES falsify the property is **removing the datasource
downgrade**, and it produced something worse than the anticipated oracle: with the wall not
filtering, the re-drive **SUCCEEDS on another tenant's asset (202)**. *What delivers the 404 is the
RLS wall, not the ordering* — the ordering is what AC-4.4 proves. Generalise this: for any
"a foreign X is 404" criterion in this codebase, the break is the WALL, not the call order.

**Finding 2 — AC-4.5's first form could not discharge its own criterion.** Both fixtures looped
through `andExpect(status().isConflict())`. `andExpect` aborts at the first failure and reports an
unlabelled `expected 409 but was 202` — byte-identical whichever fixture broke — while the stated
RED is explicitly one-sided ("the SECOND fixture 202s while the first still 409s"). Rewritten
(`fbfedb9`) so both requests run before any assertion; the break now reports
`[half 2 — the bytes were claimed and have since been reclaimed] expected: 409 but was: 202` with
half 1 green. **A criterion whose RED cannot name what broke is not discharging that criterion.**

**Finding 3 — `MediaReviewQueueIntegrationTest` needed NO edit**, though the plan enumerated it as
"expected to need a fixture assertion update". Its `pendingId` fixture is created at `now()`, well
inside the 15-minute `reaper-grace-ms`, so the D-10 widening genuinely does not select it. The file
is unchanged since `74c2846`. The widening is additive **in fact**, not merely asserted to be.

Also worth carrying: **AC-4.1's break left every status assertion GREEN** (`PENDING`,
`process_attempts=1`, `failure_reason IS NULL`) and turned only the outbox count RED — which is
precisely why the count is the load-bearing half. And the **VOID arm fired on all 7 tests** with
`VOID: the app datasource is not the downgraded role`, proving the guard probes the injected
`DataSource` rather than a connection it opened itself (the defect AC-3.6 exposed).

### One deliberate deviation from the plan's wording

The plan said the two derived bits are computed "in `MediaAssetService.toDto`". They are computed in
**`MediaAssetDto.from(asset, url, thumbnailUrl, delayCutoff)`**, with the service computing the
cutoff once per request and passing it in. This is the reading that satisfies the plan's own stated
goal ("so the mapping stays testable"): the derivation is a pure, Spring-free transform exercised at
the exact boundary by `MediaAssetDtoMappingTest`, rather than only end-to-end. AC-4.8's break
("hardcode `redrivable` to `false`") applies one file over and was run — RED at **both** layers.

## 3d. Task 5 — DONE. Two criteria were found VACUOUS and corrected before being trusted

Full record: **`.planning/phases/27-operational-maturity/baselines/AC-5-TASK5-ARMS.md`**
(screenshots in `baselines/ac55-screenshots/`). Commits `3c23bb7`, `033e162`, `c78072d`.

**Finding 1 — AC-5.5's stated assertion cannot detect the defect it guards.** The plan specifies
`document.documentElement.scrollWidth <= 320` and predicts `≈380` under the break. Measured under the
break: **`scrollWidth` is 320** — it *passes* on a visibly clipped layout. The dashboard shell nests
content in `overflow-y-auto` inside `overflow-hidden`, and those absorb the overflow before it
reaches the document element:
```
PROBE docEl.scrollWidth  = 320                  <-- the plan's assertion, GREEN on a broken page
PROBE row overflow       = { scrollWidth: 408, clientWidth: 238 }
PROBE re-process box     = { x: 249, width: 200 }   -> right edge 449, far past the 320 viewport
PROBE clipping ancestors = [ MAIN overflowX=auto, DIV overflowX=hidden ]
```
The check that actually fires is **per-control**: each action's `x + width <= 320`, which reported
`449`. Both are kept (whole-page overflow is a different, real defect) but the per-control one is
load-bearing and the plan did not specify it. **Generalise: in this app shell, never assert layout
overflow via `documentElement.scrollWidth` — measure the control's own box.**

**Finding 2 — the first AC-5.5 break arm was a FALSE GREEN, and its confirming marker was vacuous.**
`docker compose up -d --build frontend` leaves the OLD container running **and healthy** while the
new image builds, so a wait-loop polling `Health=healthy` returns immediately and the test runs
against the pre-break image (rc=0). The marker used to "confirm" the rebuild —
`grep -rl 'flex-nowrap' /app/.next` — matched in **both** directions, because Tailwind emits that
utility class regardless of whether the component uses it. Fixed by polling on a marker present
**only** in the broken build (`min-w-[200px]`, asserted to be 0 files on the restored image).

**Finding 3 — AC-5.4's "`tsc --noEmit` count unchanged" clause is unsatisfiable.** jest-dom's type
augmentation is not wired into `tsconfig.json`, so every `expect(...).toBeInTheDocument()` counts as
one error: 368 → 378, **all ten in the identical pre-existing class**, no new class. The count is a
monotonic function of how many jest-dom assertions exist, so it can only stay "unchanged" if a task
adds zero test assertions. Recorded, not silently substituted; the load-bearing half (`npm run build`
exits 0, **proven capable of failing** — `next.config.mjs` carries no `ignoreBuildErrors`) is green.
Wiring jest-dom into `tsconfig` would make this a real gate and is a flagged follow-up.

**Finding 4 — `git stash -u` is unsafe in this repo.** Used for a baseline measurement, it
half-failed on root-owned untracked paths under `infra/monitoring/` (`Permission denied`): the stash
entry was created but the checkout never completed, and `git stash pop` then refused with *"local
changes would be overwritten"*. The tree was left holding the edits beside a duplicate stash — one
step from `trap_break_arm_revert_eats_fixes`. Resolved by diffing `git diff` against
`git stash show -p` (byte-identical → redundant), then committing and dropping.
**Use `git worktree add --detach <path> <ref>` for baseline measurements** (symlink
`frontend/node_modules` in so `tsc` runs).

## 4. WHERE TO RESUME — 27-01 Task 6 (the last task)

### Do this first (2 minutes, expected outcomes stated)

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-01-media-durability     # expect: clean tree, 0 unpushed
git fetch origin && bash scripts/check-branch-behind-base.sh   # expect rc=0, 0 behind
```
If it reports *behind*, `git merge origin/main --no-edit` before doing anything else — a branch
behind its base ships a runtime missing already-merged work and no rebuild fixes it.

Then read, in this order:
1. `.planning/phases/27-operational-maturity/27-01-PLAN.md` **lines 1514–1665** (Task 6 only).
2. §3c and §3d above — four withdrawn/corrected criteria you must not re-derive.
3. §5 traps, especially trap 1 (it cost three fixes in this plan).

### Numbers Task 6 needs — MEASURED, not predicted

`docs-freshness.sh` computed-from-source at the close of Task 5 (manifest still un-written; the
gate is correctly rc=1 until Task 6 runs `--write`):

```
                        origin/main   computed now    plan predicted
java_test_methods            1182          1226          1204
java_test_files               207           212           209
jest_blocks                   416           424           422
playwright_blocks              42            43            (not in the plan)
playwright_specs               12            13            (not in the plan)
schema_version                 59            60            60
total_logical_invocations    1765          1818          1793
```

**The plan's predicted deltas are all short.** State the ACTUALS and let `docs-freshness.sh --write`
arbitrate — do NOT try to reconcile to the plan's table. Two notes:
- `playwright_blocks`/`specs` moved because Task 5 added `frontend/e2e/media-review-320.spec.ts`;
  the plan's Task 6 table does not mention Playwright at all.
- `jest_blocks` **424** ≠ jest's runtime `419 tests`. The gate counts literal `it(`/`test(` tokens
  (`trap_docs_freshness_block_counter`), which is not the number executed. Both are recorded.
- `CLAUDE.md:15` and `AGENTS.md:15` quote the totals and must change in the SAME commit.

### Runtime parity is currently GREEN — but it will go stale if you touch source

Task 5 rebuilt `core-java` and `frontend`. Verified BY CONTENT, not by timestamp:

```
check-runtime-freshness.sh -> PASS: 4 running built service(s) match the source tree (0 unverified)
unzip -p /app/app.jar BOOT-INF/classes/uk/jtoye/core/media/MediaController.class | strings | grep -c reprocess  -> 4
unzip -p /app/app.jar BOOT-INF/classes/application.yml | grep -c quarantine-retention-ms                        -> 2
curl -s localhost:9090/v3/api-docs | grep -c 'media/{assetId}/reprocess'                                        -> 1
```
AC-6.5 can be discharged against this — **re-run it, do not assume it**, and note the container name
is `jtoye_oaas_2026-core-java-1` (resolve with `ps -q core-java`, never hardcode).

### AC-5.5's dev-DB fixtures are still seeded — leave them or the spec cannot re-run

Three rows keyed `…/quarantine/ac55-fixture-*` in the demo tenant
(`00000000-0000-0000-0000-000000000001`). The seeding SQL is in `AC-5-TASK5-ARMS.md`. There is also a
**pre-existing** real FAILED `.gif` in that tenant with no retained bytes — the AC-5.5 spec derives
its counts from the page precisely so that unrelated dev data cannot break it.

### What Task 6 still owes

- `docs-freshness.sh --write` + `CLAUDE.md`/`AGENTS.md` counts, in one commit.
- The **full** Java suite (`cleanTest`/`cleanIntegrationTest` are load-bearing — read counts from
  `build-local/test-results/`, never `core-java/build/`). Only the media package + the auth surface
  have been run so far.
- AC-6.3 (Go asserted not-run), AC-6.4 (branch not behind base), AC-6.5 (runtime parity),
  AC-6.7 (terminal-states register rows — **VOID if 27-00's `docs/ops/terminal-states.yaml` is
  absent**; it landed, so it should be checkable).
- The plan says `./gradlew :core-java:updateOpenApiSnapshot` again — Task 4 already regenerated and
  committed it (`9501630`). Re-run to confirm it is still clean rather than assuming.

### Task 6 correction you must carry

**The plan's metrics baseline is STALE.** It says `origin/main` reads `1759 / 1176 / 206`. The real
`origin/main` is **`1765 / 1182 / 207`** — 27-05 moved it after the plan was written. Compute every
Task 6 delta off the real number.

---

## 4. Things that will bite you (new this session)

1. **`git checkout <file>` after a break arm restores from the INDEX/HEAD — and silently discards
   any edit you made after staging.** This bit **three times** in this plan: it wiped
   `deleteByKeyChecked`, then the AC-3.6 de-flaking fix, then the AC-3.6 guard fix. Staging is not
   enough if you edit again afterwards. **COMMIT before running break arms**, and if a break arm
   run reveals a fix, commit that fix before running the next arm.
2. **The Bash tool's CWD persists across calls.** A `cd` into `build-local/test-results/` made a
   later `git add` fail with `did not match any files`. Use absolute paths (it is GLOBAL_RULE_0).
3. **`List.of(new Object[]{a,b,c})` yields `List<Object>`, not a one-row `List<Object[]>`** — the
   array is swallowed by the varargs slot. Native `Object[]` projections must use
   `List.<Object[]>of(...)`. Compile error is `no suitable method found for thenReturn(List<Object>)`.
4. **A `@Transactional` test class cannot prove a lock.** Both "sessions" would share one connection,
   the lock would be uncontended, and the criterion passes vacuously. `MediaClaimLockIntegrationTest`
   is deliberately NOT `@Transactional`, and probes from a third connection first to prove
   contention exists at all (VOID guard).
5. **Envers writes `_aud` rows during `beforeTransactionCompletion`.** A `@Transactional` test that
   never commits sees an empty `_aud` table and the mirror criterion passes for the wrong reason.
6. **`atthasmissing` was probed before being trusted** — it is `true` on both empty and populated
   tables (so not vacuous on a fresh Testcontainers DB) and `false` under the rewrite form.

---

## 5. Deliberate deviations recorded (do not "fix" these)

- **`MediaPendingReaperTest#staleOrphanReapedToFailed` was DELETED.** It asserted
  `verify(storageService).deleteByKey(...)` — the data-destroying behaviour the plan removes. Its
  pre-change PASS was recorded first as AC-2.2's historical baseline. The plan's alternative
  ("run the new tests against the pre-fix tree via `git stash`") **cannot work**: the new tests
  reference the new constructor arity, so stashing yields a *compile error*, and a compile error is
  not evidence about behaviour.
- **AC-2.8/AC-2.11 live in a new `MediaClaimLockIntegrationTest`**, not in
  `MediaDurabilityIntegrationTest` as the plan said — see trap 4 above.
- **AC-2.11's second break was substituted.** The plan's literal form ("issue `SET LOCAL` on a
  different `doWork` outside the transaction") is not implementable: the GUC pin and the claim are
  both inside the one `@Transactional` method. The property actually at stake is that the statement
  must **precede** the claim; break 2 moves it *after* the claim and goes RED.
- **AC-2.9's "no method whose body could reach an outbox write" clause was withdrawn** — reflection
  cannot inspect method bodies. The exact-declared-field-set assertion holds the property instead.

---

## 6. Falsification discipline (unchanged, mandatory)

```bash
runcheck.sh <expected_rc|any> "<label>" -- <command...>
```
Exits 1 when observed ≠ expected, so an arm that fails to break cannot be recorded as a pass.
Currently at `.planning/phases/27-operational-maturity/baselines/runcheck.sh` on
`feature/27-00-ops-spine` and in **PR #315** — merge #315 and it is on `main`.

---

## 7. CARRIED FORWARD — the CI blocker (unchanged, still red)

`main`'s pipeline fails at **Build and Push Images (frontend)**:
`FATAL: refusing to build the frontend image — required NEXT_PUBLIC_* build-arg(s) are empty`.
`gh variable list` is still empty.

```bash
gh variable set FRONTEND_PUBLIC_API_URL --body '<origin>'
gh variable set FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL --body '<origin>'
```

**Needs a human decision**: `jtoye.co.uk` is **NOT REGISTERED** (NXDOMAIN) while
`jtoyedigital.co.uk` is, yet every staging/prod hostname in `k8s/base` targets the unregistered name.

---

## 8. CARRIED FORWARD — open, independent of 27-01

- [ ] **Merge PR #315** (27-00's execution record + the two ROADMAP checkboxes).
- [ ] **Decide the production domain**, then set the two CI variables (§7).
- [ ] **#274** — gitleaks allowlists are inert. One env line: `GITLEAKS_VERSION: "8.27.2"`.
- [ ] **#276** — no base-image refresh path; add `fail-fast: false` to the build matrix.
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has
      still never been captured. #266 is fixed (`d964a85`) but unproven.
- [ ] 6 open security + 7 code-review warnings from Phase 26 — `deferred-items.md`; #270/#271/#272 sharpest.
- [ ] **20 open dependabot PRs** — triage, do not bulk-merge. Several majors violate the pinned stack.
- [ ] **57 open issues.** #284 and #289 bear on 27-04; #205 is owned by 27-05 (done).

---

## 9. Standing traps (carried forward, all still live)

- **`grep` here is a bash function → ugrep 7.5.0.** Use `command grep` in scripts.
- **`grep -c` returning 0 exits 1** — under `set -e` an expected-0 criterion kills its own harness.
- **`cmd | grep -q X` under `pipefail` INVERTS on match** (SIGPIPE→141). Use here-strings.
- **Capture exit codes on the same line**: `out=$(cmd 2>&1); rc=$?`.
- **`cleanTest` / `cleanIntegrationTest` are load-bearing** or Gradle reports success while
  executing nothing. Always confirm counts from the result XML, not from `BUILD SUCCESSFUL`.
- **`core-java/build/` is stale (2025-12-27); the live dir is `build-local`.**
- **`PageImpl` silently recomputes `totalElements`** — fixture total must exceed page size.
- **`docs/metrics.json` is a cross-branch conflict hotspot.** Recipe: merge →
  `docs-freshness.sh --write` → `docs-freshness.sh`. `CLAUDE.md:15` and `AGENTS.md:15` quote the
  counts and must change in the same commit.
- **A second Claude session may share this checkout.** Stage by explicit path — `git add -A` is unsafe.
- **The repo squash-merges**, so ancestry lies (see §2b). Verify merged-ness by content or PR state.
- **Do not add `Co-Authored-By` trailers.**
- **Do not hand-run `state.record-session`** — it corrupts `STATE.md` mid-plan.

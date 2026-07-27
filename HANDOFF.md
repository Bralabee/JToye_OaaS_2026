# Handoff: Phase 27 — 27-00, 27-05 and #315 MERGED; 27-01 Tasks 1–4 of 6 done and pushed

> **Update (new session, 2026-07-27 evening): 27-01 Task 4 is COMPLETE and pushed.**
> Commits `a94ce77` (implementation), `fbfedb9` (AC-4.5 test rewrite), `9501630` (OpenAPI snapshot),
> `17dca23` (the arms record). Branch is **0 behind** `origin/main` (`9d6ce8c`), tree clean.
> **Resume at Task 5** (plan line ~1420) — the frontend DELAYED affordance.
> Read **§3c** below first: Task 4 produced three findings, one of which (AC-4.3) withdrew a
> criterion the plan stated but that is not implementable.
>
> Earlier in this handoff: PR #315 is MERGED (`9d6ce8c`) — `runcheck.sh` is on `main` at
> `.planning/phases/27-operational-maturity/baselines/runcheck.sh`. Task 3 is COMPLETE
> (`7d216c4`, `e99efb7`, `c69f373`, `fb4b77d`); §3a/§3b still worth reading.

**Generated:** 2026-07-27; last updated by the session that executed 27-01 **Task 4**.
**Supersedes** the 27-00 handoff. Its §5/§7/§8/§9 content is still live and carried forward below —
do not lose it.

---

## 1. Git & environment

| | |
|---|---|
| Checkout | `/home/sanmi/IdeaProjects/JToye_OaaS_2026` |
| Branch | **`feature/27-01-media-durability`** — **0 behind** `origin/main` (`9d6ce8c`); 16 ahead as of `17dca23`, **pushed** |
| Working tree | clean |
| Other branch | `docs/27-00-planning-artifacts` → PR #315 **MERGED** |
| Stack | Compose up, healthy (`jtoye-redis-exporter` unhealthy — pre-existing, unrelated) |
| minikube `jtoye` | Stopped — compose XOR k8s, never both |
| `hey` | still at `~/go/bin`, not on PATH by default |

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-01-media-durability
```

### Gate state at handoff (real output, measured at close of Task 4)

```
scripts/check-branch-behind-base.sh   rc=0   16 ahead, 0 behind (base 9d6ce8c) at 17dca23
scripts/docs-freshness.sh             rc=1   <- EXPECTED MID-PLAN. Task 6 owns metrics.json.
scripts/check-runtime-freshness.sh    rc=1   <- EXPECTED MID-PLAN. See below.
scripts/check-terminal-states.sh      rc=1   <- still correct until 27-03
scripts/check-alert-liveness.sh       rc=1   <- still correct until 27-03
```

**All four rc=1 are correct right now. None is a regression. Do not "fix" any of them.**

- `docs-freshness` — `metrics.json` is deliberately NOT updated mid-plan; Task 5 will move the
  numbers again. **Task 6 writes it once.** Baseline to compute deltas from is the REAL
  `origin/main`: **1765 / 1182 / 207**, *not* the plan's stale `1759 / 1176 / 206`.
  Measured drift after Task 4 (`docs-freshness.sh` output, verbatim):
  ```
  manifest:  java_test_methods 1182  java_test_files 207  schema_version 59  total 1765
  computed:  java_test_methods 1226  java_test_files 212  schema_version 60  total 1809
  ```
  i.e. **+44 Java methods / +5 files** across Tasks 1–4, and `schema_version` already reads V60.
  The plan's Task 6 table predicted `+28 / +3`; state the ACTUALS and let `--write` arbitrate.
- `check-runtime-freshness` — verbatim:
  ```
  core-java  DRIFT  [image-not-rebuilt]  image tagged 2026-07-27 11:07:34 UTC
                    / newest build-input commit fb4b77d (2026-07-27 17:20:51 UTC)
  ```
  This is the gate doing its job: Java source changed and the container was not rebuilt. **Task 6
  owns the rebuild + parity proof** (`docker compose ... up -d --build core-java` — `start` does not
  rebuild — then read the value out of the running fat jar, not the filesystem).

### Test state at close of Task 4 (counts read from `build-local/test-results/`, not from "BUILD SUCCESSFUL")

```
./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.media.*'
  -> classes=5   tests=38  failures=0 errors=0 skipped=0     (was 36 at Task 3 -> +2)

./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests 'uk.jtoye.core.media.*'
  -> classes=18  tests=63  failures=0 errors=0 skipped=0     (was 56 at Task 3 -> +7)

./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest \
    --tests 'uk.jtoye.core.security.*' --tests '*ScopedCatalogAccess*' --tests '*GateStrictness*'
  -> classes=22  tests=118 failures=0 errors=0 skipped=0
```

The third run was deliberate: `trap_scope_gate_integrationtest_regression` says a new
`@PreAuthorize` gate has repeatedly broken *existing* integrationTests, and Task 4 adds one
(`POST /{assetId}/reprocess`). **It did not fire this time.** That is NOT a substitute for Task 6's
full-suite run — it covers the auth surface only.

**The FULL suite has still NOT been run since Task 1.** Task 6 owns it.

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

## 4. WHERE TO RESUME — 27-01 Task 5

### Do this first (2 minutes, expected outcomes stated)

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-01-media-durability     # expect: clean tree, 0 unpushed
git fetch origin && bash scripts/check-branch-behind-base.sh   # expect rc=0, 0 behind
```
If it reports *behind*, `git merge origin/main --no-edit` before doing anything else — a branch
behind its base ships a runtime missing already-merged work and no rebuild fixes it.

Then read, in this order:
1. `.planning/phases/27-operational-maturity/27-01-PLAN.md` **lines 1420–1512** (Task 5 only).
2. §3c above, then §3a/§3b — the findings that change how you write criteria in this codebase.
3. §5 traps, especially trap 1 (it cost three fixes in this plan).

### The wire contract Task 5 consumes (shipped and proven — do not re-derive it)

`MediaAssetDto` now ends with two derived booleans. Both are in `docs/api/openapi-snapshot.json`.

| field | true when | UI meaning |
|---|---|---|
| `redrivable` | `quarantine_expires_at IS NOT NULL AND quarantine_reclaimed_at IS NULL` | the original bytes are still on disk → offer **Re-process** |
| `delayed` | `status == PENDING` and older than `jtoye.media.reaper-grace-ms` (15 min) | the upload has visibly stalled → replace the spinner |

- `POST /api/v1/media/{assetId}/reprocess` → **202** `{assetId, status}`. Requires an
  `Idempotency-Key` header (reuse `ImageUploader`'s generator — do not re-implement).
- The three 409s carry a machine-parseable `code`, which Task 5's error toast must surface
  verbatim rather than a generic message: `media.quarantine_not_retained`, `media.already_active`,
  `media.redrive_budget_exhausted`.
- `GET /api/v1/media/review-queue` now also returns stalled PENDING rows (D-10), so `ReviewQueue.tsx`
  will receive a status it has never rendered before. That is the M4 surface.

### Task 5 in one paragraph

`frontend/types/api.ts` gains `redrivable`/`delayed` on `MediaAsset`; `frontend/lib/media-api.ts`
gains `reprocessAsset(assetId)`. `asset-image.tsx`'s PENDING branch splits: `!delayed` keeps the
existing spinner **byte-for-byte**, `delayed` renders an amber `role="status"` card ("Taking longer
than usual" + a **Check again** control). Its FAILED branch keeps **Re-upload** unchanged
(Incremental Betterment — it is the working good) and adds **Re-process** as a secondary action only
when `redrivable`. `ReviewQueue.tsx` gets the same treatment plus optimistic removal and a 409 toast
carrying the RFC 7807 `code`. AC-5.5 requires a **real browser at 320 px against the running Compose
stack** — the plan explicitly REMOVED the "if the stack is unavailable, mark DEFERRED" escape, so if
the stack is down that criterion is **VOID (exit 2) and the plan is not done**.

Remaining after Task 5: **Task 6** — metrics reconcile, full suite, terminal-states rows, runtime
parity. Plan: `.planning/phases/27-operational-maturity/27-01-PLAN.md` (1926 lines); Task 5 at
**line ~1420**, Task 6 at **line ~1514**.

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

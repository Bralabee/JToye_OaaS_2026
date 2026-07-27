# Handoff: Phase 27 — 27-00 and 27-05 MERGED; 27-01 Tasks 1–2 of 6 done and pushed

**Generated:** 2026-07-27 (session that merged #314, opened #315, and executed 27-01 Tasks 1–2).
**Supersedes** the 27-00 handoff. Its §5/§7/§8/§9 content is still live and carried forward below —
do not lose it.

---

## 1. Git & environment

| | |
|---|---|
| Checkout | `/home/sanmi/IdeaProjects/JToye_OaaS_2026` |
| Branch | **`feature/27-01-media-durability`** — 3 ahead of `origin/main` (`60cb641`), 0 behind, **pushed** |
| Working tree | clean |
| Other branch | `docs/27-00-planning-artifacts` → **PR #315 OPEN**, needs merging |
| Stack | Compose up, healthy (`jtoye-redis-exporter` unhealthy — pre-existing, unrelated) |
| minikube `jtoye` | Stopped — compose XOR k8s, never both |
| `hey` | still at `~/go/bin`, not on PATH by default |

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/27-01-media-durability
```

### Gate state at handoff (real output)

```
scripts/check-branch-behind-base.sh   rc=0   3 ahead, 0 behind
scripts/docs-freshness.sh             rc=1   <- EXPECTED MID-PLAN. Task 6 owns metrics.json.
                                             committed 1182/207/59/1765
                                             computed  1206/210/60/1789
scripts/check-terminal-states.sh      rc=1   <- still correct until 27-03
scripts/check-alert-liveness.sh       rc=1   <- still correct until 27-03
```

**Do not "fix" `docs-freshness` by running `--write` and committing on its own.** It is Task 6's
job, and Tasks 3–5 will move the numbers again.

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

## 3. WHERE TO RESUME — 27-01 Task 3

Plan: `.planning/phases/27-operational-maturity/27-01-PLAN.md` (1926 lines). Task 3 starts at
**line 1033**. Remaining: Tasks 3, 4, 5, 6.

- **Task 3** — `MediaQuarantineRetentionSweep` (per-tenant, GUC-pinned, sentinel-stamped,
  `/quarantine/` guard in **Java** not JPQL so guards 1/2 stay independently breakable) + the four
  new `jtoye.media.*` keys in `application.yml` + the D-07 worker split
  (`failRetainingBytes` vs `failAndDiscard`). Note `claim-lock-timeout-ms` **already exists** in
  `MediaProperties` (added in Task 2) but is **not yet in `application.yml`** — Task 3 adds it there.
- **Task 4** — `POST /api/v1/media/{assetId}/reprocess` + `delayed`/`redrivable` DTO bits +
  review-queue widening + OpenAPI snapshot.
- **Task 5** — frontend DELAYED affordance (`asset-image.tsx`, `ReviewQueue.tsx`), 6 Jest blocks.
- **Task 6** — metrics reconcile, full suite, terminal-states rows, runtime parity.

### Task 6 correction you must carry

**The plan's metrics baseline is STALE.** It says `origin/main` reads `1759 / 1176 / 206`. The real
`origin/main` is **`1765 / 1182 / 207`** — 27-05 moved it after the plan was written. Compute every
Task 6 delta off the real number.

---

## 4. Things that will bite you (new this session)

1. **`git checkout <file>` on UNSTAGED work restores from HEAD and silently wipes the whole task's
   edit, not just your break.** It destroyed `deleteByKeyChecked` mid-run. **Stage or commit before
   running any break arm** — then `git checkout --` restores *your* version. Every arm after that
   point followed this.
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

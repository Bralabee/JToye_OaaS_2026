# 27-01 SUMMARY — media durability: a broker outage no longer destroys vendor uploads

**Branch:** `feature/27-01-media-durability` · **Executed:** 2026-07-27 · **Tasks 1–6 complete.**

Before this plan, `MediaPendingReaper` deleted a quarantined object from storage *before* flipping
its row to `FAILED`. A broker outage longer than `reaperGraceMs` (15 min) therefore destroyed every
upload in flight, permanently and silently — an infrastructure liveness property was deciding
whether user data survived. This plan removes that capability from the class entirely, gates the
flip on durable dispatch evidence, retains the bytes for a bounded window, and gives the vendor a
way back.

---

## What shipped

| Task | Deliverable | Commits |
|---|---|---|
| 1 | V60 columns + Envers mirrors + 2 indexes; `deleteByKeyChecked`, `findReclaimableQuarantine`, `lockForProcessing`, `findLatestDispatchStateForAssets` | `c2e0015` |
| 2 | Reaper rewrite — no `StorageService`, no enqueue, dispatch-evidence gate, suspension circuit; worker claim lock | `88ba77c`, `a9ec510` |
| 3 | `MediaQuarantineRetentionSweep` — the only component that reclaims quarantine bytes; tenant-scoped under a real NOSUPERUSER downgrade | `7d216c4`, `e99efb7`, `c69f373`, `fb4b77d` |
| 4 | `POST /api/v1/media/{assetId}/reprocess` — scoped, idempotent, attempt-bounded; two derived DTO bits | `a94ce77`, `fbfedb9`, `9501630`, `17dca23` |
| 5 | The vendor-visible DELAYED affordance + Re-process, strictly additive | `3c23bb7`, `033e162`, `c78072d` |
| 6 | Metrics manifest reconcile, register rows, full regression sweep, runtime parity | `2ca4a2a`, `20374c0`, *(this task)* |

**The delete capability is gone, not merely unused:** the reaper performs **0** executable
`storageService.deleteByKey` calls (1 total — a javadoc line that deliberately records the history).

---

## Falsification record

Every acceptance criterion was run in both directions through `baselines/runcheck.sh`, which exits 1
when observed ≠ expected so an arm that fails to break cannot be recorded as a pass. Full arm-by-arm
output:

- `baselines/AC-4-ARMS.md` — Task 4
- `baselines/AC-5-TASK5-ARMS.md` — Task 5 (+ `baselines/ac55-screenshots/`)
- `baselines/AC-6-TASK6-ARMS.md` — Task 6

### Eight criteria could not fail as written. All eight were caught by running the fail direction.

| # | criterion | what was wrong | disposition |
|---|---|---|---|
| 1 | AC-3.2/3.3(a) unit form | the `@Query` break a mock repository never executes leaves the unit class GREEN | split: guards 1+3 → Testcontainers, guard 2 → unit |
| 2 | AC-3.8 | fixture seeded a fresh product, so the `clearAutomatically` repoint never ran and a mis-ordered stamp survived | fixture seeds an existing ACTIVE primary so the placement DISPLACES it |
| 3 | AC-3.6 stated break | removing the explicit `pinTenantGuc` leaves the test GREEN — `TenantSetLocalAspect` pins the GUC before every repo call anyway | break `TenantContext.set` instead; control arm recorded |
| 4 | AC-3.6 VOID guard | `assertDowngradeIsReal` opened its own connection, so it always observed the downgraded role | probe the injected `DataSource` |
| 5 | AC-4.3 | stated break not implementable; the 404 comes from the RLS wall, not call ordering | break the wall — and it revealed a **202 on another tenant's asset** |
| 6 | AC-4.5 | both fixtures looped through `andExpect`, so the RED could not name which half broke | run both requests before any assertion |
| 7 | AC-5.5 | `documentElement.scrollWidth` is **320 on a visibly clipped page** — the shell's `overflow` ancestors absorb it | assert each control's own `x + width <= 320` |
| 8 | **AC-6.3** | **the plan's own corrected break is still vacuous** — `git diff A..HEAD` is a commit range; a working-tree edit cannot move it | falsify by **committing** the change |

Plus two false-greens in the harness itself: `docker compose up -d --build frontend` leaves the OLD
container healthy while the new image builds (so a health-poll returns immediately and the test runs
against the pre-break image), and the marker used to confirm that rebuild (`grep -rl 'flex-nowrap'`)
matched in **both** directions because Tailwind emits the class regardless.

### Two criteria in Task 6 were vacuous in a way worth generalising

**AC-6.3 — match the break to the assertion's *domain*, not just its file.** The plan had already
corrected `touch` → append-a-line, having found that `touch` changes only mtime. But both operate on
the working tree while the assertion is a commit-to-commit diff, so the corrected arm returned `0`
too. Only a committed change falsifies it.

**AC-6.7 — a doc rule that must name the string it forbids, tripped by its own fix.** The check
asserts the register no longer cites the deleted locator. The first draft of the corrective
`deferred.reason` *explained* the fix by naming that locator — so the register still contained it and
the check went RED on my own prose. Separately, the companion probe `grep -c
'storageService.deleteByKey'` returns **1** on the correct tree, because the reaper's javadoc records
the removed call on purpose: an expected-0 that is 1 when everything is right, and whose "fix" would
have been to delete accurate history.

---

## Task 6 findings

### The plan's metrics baseline and every predicted delta were wrong

```
                        origin/main   computed now    plan predicted
java_test_methods            1182          1226          1204
java_test_files               207           212           209
jest_blocks                   416           424           422
playwright_blocks              42            43       (absent from the plan)
playwright_specs               12            13       (absent from the plan)
schema_version                 59            60            60
total_logical_invocations    1765          1818          1793
```

The plan computes off `1759 / 1176 / 206`; 27-05 moved the real baseline to `1765 / 1182 / 207`
after the plan was written. The plan also claims the branch copy was "stale at `1736`" — it read
`1765`, byte-identical to main, so that second break arm's premise was false and a runnable
equivalent was substituted (write the plan's *predicted* table, confirm the gate rejects it → rc=1).

Per `trap_docs_freshness_block_counter` the computed number was not trusted alone: the +44 was
enumerated per file by hand and the two agree exactly.

### 27-00's register described behaviour this plan deletes — and named 27-01 as the owner

`TS-07` told an operator *"the object is already deleted; the only remedy is asking the vendor to
re-upload"* — the exact opposite of what this plan delivers. Its own `deferred.reason` said *"this
row's locator must be updated in the same PR"* with `tracked_by: "27-01"`, and the runbook section
said *"27-01 owns the fix and will change the code this section points at."* Both were corrected
here despite the plan's D-B ("this plan does not edit the register"), which was written before 27-00
landed a row assigning the work to 27-01. Leaving factually-inverted operator guidance in place
would have been a regression by omission that every gate stayed green through.

`TS-17` (media stall sweep suspended) is the new terminal state this plan introduces — filed as
TS-17 because the plan's proposed "TS-13" was already the PostgreSQL-exporter row.

### `MediaReviewQueueIntegrationTest` needed no edit

The plan enumerated it as *"expected to need a fixture assertion update"*. It is unchanged since
`74c2846`: its `pendingId` fixture is created at `now()`, well inside the 15-minute grace, so the
D-10 widening genuinely does not select it. The widening is additive **in fact**, not merely
asserted to be. Of the twelve classes AC-6.6 names as the regression surface, exactly one changed —
`MediaAssetDtoMappingTest` (+61/−5), for AC-4.8.

### Full regression sweep — green, and executed

Counts read from `core-java/build-local/test-results/`, never from `BUILD SUCCESSFUL` and never from
`core-java/build/` (stale since 2025-12-27, still reports three failures).

```
                 classes  tests  failures  errors  skipped   wall
unit                 114    820         0       0        1   43s
integrationTest      102    414         0       0        1   40m 05s
jest                  62    419         0       -        -   (62 suites)
frontend build          —      —         —       —        —   rc=0
playwright AC-5.5       1      1         0       -        -   2.5s, real browser, running stack
```

Both Java suites **beat** the plan's expected floor (unit ≈104/767, integration ≈98/392); a class-count
drop would have been the red flag. This is the first full-suite run since Task 1 — Tasks 3–5 ran only
the media package plus the auth surface.

The `cleanTest` in that command was proven load-bearing, not decorative: `:core-java:test` run twice
without it prints `> Task :core-java:test UP-TO-DATE` and `BUILD SUCCESSFUL in 1s` while executing
**zero** tests, with `test-results/` mtime frozen at 20:08:03 and counts unchanged.

**Executions (1234) ≠ counted `@Test` tokens (1226)** — parameterized methods execute more than once
while contributing one literal token, and one test in each suite is skipped. Both numbers are recorded
so neither is later read as drift, the same way jest's runtime `419 tests` differs from the gate's
`424` blocks.

### Gate state at close

```
docs-freshness              rc=0   (1818, written and hand-reconciled)
check-runtime-freshness     rc=0   (4 built services FRESH, 0 unverified)
check-branch-behind-base    rc=0   (0 behind origin/main 9d6ce8c)
check-env-contract          rc=0
check-render-invariants     rc=0
check-terminal-states       rc=1   <- X-3 only, 27-03 owns it. NOT a regression.
check-alert-liveness        rc=1   <- correct until 27-03. NOT a regression.
```

### Runtime parity proven by content, against a real stale artifact

```
running jar   unzip -p /app/app.jar BOOT-INF/classes/application.yml | grep -c quarantine-retention-ms  -> 2
              MediaController.class | strings | grep -c reprocess                                       -> 4
              live /v3/api-docs advertises media/{assetId}/reprocess                                     -> 1
ghcr.io/…jtoye-core-java:local  (2026-07-25, pre-27-01)  same probe                                      -> 0 / 0
ghcr.io/…jtoye-core-java:2.1.0  (2026-07-13, pre-27-01)  same probe                                      -> 0 / 0
control       find /app -name application.yml                                                            -> 0  (misleading in BOTH directions, as the plan warns)
```

The plan's break (restart with no rebuild) would have required building a deliberately stale image.
Two genuinely pre-27-01 images were already present, so the identical probe was run against them —
a stronger arm, because the stale artifact is real rather than synthetic.

---

## Deliberate deviations (do not "fix" these)

- **`MediaPendingReaperTest#staleOrphanReapedToFailed` was DELETED** — it asserted the byte-delete
  this plan removes. Its pre-change PASS is AC-2.2's historical baseline. The plan's alternative
  (run the new tests against the pre-fix tree via `git stash`) cannot work: the new tests reference
  the new constructor arity, so stashing yields a *compile error*, and a compile error is not
  evidence about behaviour.
- **AC-2.8/AC-2.11 live in a new `MediaClaimLockIntegrationTest`**, not `MediaDurabilityIntegrationTest`:
  a `@Transactional` test class cannot prove a lock — both "sessions" share one connection, the lock
  is uncontended, and the criterion passes vacuously.
- **AC-2.11's second break was substituted** — the plan's literal form is not implementable (the GUC
  pin and the claim are both inside one `@Transactional` method). The property at stake is that the
  statement must *precede* the claim; break 2 moves it after.
- **AC-2.9's "no method whose body could reach an outbox write" clause was withdrawn** — reflection
  cannot inspect method bodies.
- **The two derived DTO bits are computed in `MediaAssetDto.from(...)`**, not `MediaAssetService.toDto`
  as the plan's wording said — which is the reading that satisfies the plan's own stated goal ("so
  the mapping stays testable").
- **AC-5.4's "`tsc --noEmit` count unchanged" clause is unsatisfiable** — jest-dom's type augmentation
  is not wired into `tsconfig.json`, so every `toBeInTheDocument()` counts as one error (368 → 378,
  all in the identical pre-existing class). The count is a monotonic function of how many jest-dom
  assertions exist. Wiring jest-dom into `tsconfig` is a flagged follow-up.

---

## Follow-ups this plan does not close

- **A separate non-public quarantine bucket** (T-27-04). F-3 is pre-existing; this change lengthens
  the exposure window from ~seconds to 72 h. Exposure is confirmation-of-possession only — the key
  is `<tenant>/quarantine/<sha256-of-the-raw-bytes>` — but it must be filed as its own issue rather
  than silently absorbed.
- **Wire jest-dom into `tsconfig.json`** so AC-5.4's type-error count becomes a real gate.
- **`frontend/e2e/media-review-320.spec.ts:23` documents the wrong port.** Its run instructions say
  `PLAYWRIGHT_BASE_URL=http://localhost:3100`; this stack publishes the frontend on **3000**
  (`curl :3100` → `000`, `:3000/auth/signin` → `200`). Following the comment produces a **false RED**
  on a passing spec. Left as a follow-up rather than edited mid-Task-6 because the port is
  environment-dependent and `PLAYWRIGHT_BASE_URL` is already the injection point — the fix is to stop
  hardcoding a port in prose, not to swap one literal for another (GLOBAL_RULE_6).
- **`27-03` owns** the four X-3 runbook sections and the alert rules behind `MediaStallFailures` /
  `MediaReaperSuspended`. Both new signals name real exported counters, but Micrometer only exports a
  counter after its first increment — a rule written against `media_reaper_suspended_total` on a
  healthy stack would match zero series, which is the F-1 defect this register exists to prevent.

# 27-01 Task 6 — acceptance-criterion arms, both directions

> Not to be confused with `AC-6-ARMS.md`, which belongs to plan **27-00** Task 6. This file is
> **27-01 Task 6** (the phase-gate reconcile).

Every arm run through `baselines/runcheck.sh <expected_rc> "<label>" -- <cmd>`, which exits 1 when
observed ≠ expected, so an arm that fails to break cannot be recorded as a pass. Implementation was
committed before each break arm (handoff trap 1: `git checkout --` restores from the index and
silently eats edits made after staging).

**Four criteria in this task were found unfalsifiable or wrong as written.** Each is recorded below
with the substituted form and both directions' real output — never silently replaced.

---

## AC-6.1 — the manifest matches source, off the RIGHT baseline

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed (`2ca4a2a`) | 0 | `docs-freshness OK: metrics match source (total logical invocations: 1818).` |
| BREAK | add one `@Test` to `MediaAssetDtoMappingTest` without re-running `--write` | 1 | `ERROR: documentation metrics are stale` — committed `java_test_methods: 1226` / `total 1818` vs computed `1227` / `1819` |
| RESTORE | `git checkout --` the test file | 0 | back to `1818`; `@Test` count 12 → 11 |

### The plan's baseline is stale, and its predicted deltas are all short

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

The plan computes off `1759 / 1176 / 206`. The real `origin/main` is `1765 / 1182 / 207` — 27-05
moved it after the plan was written.

### SECOND BREAK — substituted, and why

The plan's second break says "compute the delta against the branch's stale `1736`". **There is no
`1736` on this branch to compute against**: the branch copy of `docs/metrics.json` read `1765`,
byte-identical to `origin/main`. (`1736` was real historically — `git log -S` finds it at `a67f50d`
Phase 26 and `d8b7c05` — but it was superseded before this plan ran.) The arm's premise is false, so
it was replaced with a runnable equivalent that tests the same property: **adopt the plan's own
predicted table and confirm the gate rejects it.**

| dir | arm | rc | evidence |
|---|---|---|---|
| SECOND BREAK | write the plan's predicted `1204 / 209 / 422 / 1793` into `metrics.json` | 1 | `ERROR: documentation metrics are stale` — committed `1204 / 1793` vs computed `1226 / 1818` |
| RESTORE | `git checkout -- docs/metrics.json` | 0 | `1818` |

That is the proof the baseline matters: had the plan's arithmetic been trusted, the gate would have
failed with a number that looked carefully derived.

### `trap_docs_freshness_block_counter` — the count was enumerated by hand, not trusted

The gate greps literal tokens, so the number alone is not evidence. Per-file enumeration against
`origin/main`:

| | file | Δ |
|---|---|---|
| NEW | `MediaClaimLockIntegrationTest` | +2 |
| NEW | `MediaDurabilityIntegrationTest` | +11 |
| NEW | `MediaQuarantineRetentionSweepTest` | +6 *(plan said +3)* |
| NEW | `MediaRedriveControllerTest` | +7 *(plan said +6)* |
| NEW | `MediaSweepTenantScopeIntegrationTest` | +2 *(unforeseen — AC-3.6)* |
| MOD | `MediaPendingReaperTest` 2 → 16 | +14 *(plan said 2 → 10)* |
| MOD | `MediaAssetDtoMappingTest` 9 → 11 | +2 *(unforeseen — AC-4.8)* |
| | **enumerated** | **+44 = computed +44** |

jest +8 (`asset-image` +5, `ReviewQueue` +3) and playwright +1 (`media-review-320.spec.ts`)
likewise enumerated and matching. `MediaPendingReaperTest` is **net** of the deleted
`staleOrphanReapedToFailed`.

*Measurement note.* A hand probe using `grep -cE` (counts **lines**) reports `415/423` where the gate
reports `416/424`, because the gate uses `grep -hoE | wc -l` (counts **occurrences**) and one line
carries two tokens. The deltas are identical; only the absolutes differ. Recorded so the discrepancy
is not later mistaken for drift.

---

## AC-6.2 — the full suite is green and was actually executed

Counts read from `core-java/build-local/test-results/`, **never** `core-java/build/` (stale since
2025-12-27, reports three failures).

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | `:core-java:cleanTest :core-java:test` | 0 | `BUILD SUCCESSFUL in 43s` |
| PASS | `:core-java:cleanIntegrationTest :core-java:integrationTest` | 0 | `BUILD SUCCESSFUL in 40m 5s` |
| **BREAK** | `:core-java:test` **without** `cleanTest`, twice in a row | — | `> Task :core-java:test UP-TO-DATE` / `BUILD SUCCESSFUL in 1s` then `in 806ms`, **zero tests executed**, mtime frozen |

```
                 classes  tests  failures  errors  skipped   results mtime
unit                 114    820         0       0        1   2026-07-27 20:08:03
integrationTest      102    414         0       0        1   2026-07-27 20:48:09
```

**Both beat the plan's floor** (unit ≈104/767, integration ≈98/392). A *drop* in class count would
have been the red flag; there is none.

### The UP-TO-DATE break, in full

```
mtime BEFORE:      2026-07-27 20:08:03
run 1  -> Task :core-java:test UP-TO-DATE · BUILD SUCCESSFUL in 1s   · 5 tasks up-to-date
mtime after run 1: 2026-07-27 20:08:03          <-- did not advance
run 2  -> Task :core-java:test UP-TO-DATE · BUILD SUCCESSFUL in 806ms · 5 tasks up-to-date
mtime after run 2: 2026-07-27 20:08:03          <-- did not advance
counts after both: classes=114 tests=820        <-- unchanged
```

`BUILD SUCCESSFUL` twice while executing **nothing**. This is the proof that the `cleanTest` /
`cleanIntegrationTest` in the pass command is load-bearing rather than decorative, and why every
count above is read from the result XML instead of from the build's own verdict.

*Directory discipline:* counts come from `core-java/build-local/test-results/`. `core-java/build/` is
a stale artifact (2025-12-27) that still reports three failures; the live dir is set at
`core-java/build.gradle.kts:15`.

### Executions (1234) ≠ counted `@Test` tokens (1226)

820 + 414 = **1234** executed, against **1226** literal `@Test` tokens in `docs/metrics.json`. Not a
discrepancy: parameterized/repeated methods execute more than once while contributing one token, and
one test in each suite is skipped. The two numbers measure different things and both are recorded so
neither is later mistaken for drift — the same distinction as jest's `419 tests` vs the gate's `424`
blocks.

### OpenAPI snapshot — re-run, not assumed

The plan says to run `updateOpenApiSnapshot` again; Task 4 already regenerated and committed it
(`9501630`). Re-running confirms it is still current rather than trusting that commit:

```
> Task :core-java:updateOpenApiSnapshot        (1 executed — not UP-TO-DATE, so this is real)
BUILD SUCCESSFUL in 21s
git status --porcelain docs/api/openapi-snapshot.json  -> 0 modified paths
grep -c 'media/{assetId}/reprocess'                    -> 1
```

### AC-6.6 regression surface — which of the twelve named classes changed, and why

Exactly **one**: `MediaAssetDtoMappingTest` (+61/−5), for AC-4.8's two derived DTO bits. The other
eleven are byte-identical to `origin/main`, including **`MediaReviewQueueIntegrationTest`**, which the
plan explicitly expected to need a fixture update. It did not: its `pendingId` fixture is created at
`now()`, well inside the 15-minute `reaper-grace-ms`, so the D-10 widening genuinely does not select
it. The widening is additive **in fact**, not merely asserted to be.

`RlsContractTest` is recorded here as a regression check and deliberately **not** as a criterion —
V60 adds no table and no policy, so nothing in this plan can turn it red. It is green within the 102
integration classes above.

---

## AC-6.3 — Go asserted not-run — **the plan's break arm is vacuous, and so was its predecessor**

The plan already corrected one vacuity here: the draft used `touch`, which changes only mtime, so
`git diff --name-only` returned nothing in **both** directions. The correction — append a real line —
**is still vacuous**, for a different reason the plan did not reach.

The assertion is `git diff --name-only "$PHASE_BASE"..HEAD`, a **commit-to-commit** range. Appending
a line to the **working tree** cannot move it. Measured:

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | `go files changed since 9d6ce8c: 0` |
| CONTROL | `touch edge-go/internal/core/client.go` (the draft's arm) | 0 | `0` — cannot fail, as the plan says |
| **BREAK as the plan states it** | `printf '\n// probe\n' >> …/client.go`, working tree only | **0** | **`0` — MISMATCH, expected 1. The plan's own corrected arm cannot fail either.** |
| BREAK, correct domain | **commit** that same appended line | 1 | `go files changed since 9d6ce8c: 1` → `Go sources changed — the Go suite must run` |
| RESTORE | `git reset --hard 8fcb350` | — | HEAD back to `8fcb350`, probe absent, tree clean |

**Generalise:** an assertion over a commit range can only be falsified by a commit. Matching the
break to the *file* was not enough; it has to match the assertion's *domain*.

---

## AC-6.4 — the branch is not behind its base

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | at session start | 0 | `HEAD contains every commit on origin/main (9d6ce8c); 22 ahead, 0 behind` |
| PASS | re-run immediately before the PR | 0 | *(see final run below — the plan requires this twice, not once)* |

---

## AC-6.5 — the delivered runtime matches the branch

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | `scripts/check-runtime-freshness.sh` | 0 | `PASS: 4 running built service(s) match the source tree (0 unverified)` — core-java tagged 18:47:26Z ≥ newest build-input commit `fbfedb9` 17:56:57Z |
| PASS | read the key from **inside** the running jar | — | `unzip -p /app/app.jar BOOT-INF/classes/application.yml \| grep -c quarantine-retention-ms` → **2** |
| PASS | other 27-01 artifacts in the running jar | — | `MediaController.class \| strings \| grep -c reprocess` → **4**; live `/v3/api-docs` advertises `media/{assetId}/reprocess` → **1** |
| control | the misleading filesystem read | — | `find /app -name application.yml` → **0**, as the plan warns — which is why the read must come from inside the archive |
| control | nonsense token inside the jar | — | **0** — the probe is capable of returning 0 |

### The stale-artifact RED was obtained from a real stale artifact

The plan's break (`docker compose start` with no rebuild) would require building a deliberately old
image. Two genuinely pre-27-01 core-java images were already present, so the identical probe was run
against them instead — a stronger arm, because the artifact is real rather than synthetic:

| image | built | `quarantine-retention-ms` | `reprocess` symbols |
|---|---|---|---|
| `ghcr.io/bralabee/jtoye-core-java:local` | 2026-07-25 | **0** | **0** |
| `ghcr.io/bralabee/jtoye-core-java:2.1.0` | 2026-07-13 | **0** | **0** |
| **running image** | 2026-07-27 | **2** | **4** |

Container resolved, never hardcoded: `docker compose ps -q core-java` →
`/jtoye_oaas_2026-core-java-1`.

---

## AC-6.7 — the register rows this plan owns point at live code

**Not VOID.** 27-00 landed, so `docs/ops/terminal-states.yaml` exists and the criterion is checkable.

### What was found: both the register AND its runbook described behaviour this plan deletes

`TS-07.what_stops` claimed *"the reaper deletes the quarantined object from storage BEFORE flipping
the row to FAILED (MediaPendingReaper.java:79 …), so the bytes are gone"*, and `operator_action` told
a human *"the object is already deleted; the only remedy is asking the vendor to re-upload."* The
runbook section repeated it. On this tree the reaper performs **0** executable
`storageService.deleteByKey` calls (1 total — a javadoc line that deliberately records the history),
and line 79 is prose.

Both files named 27-01 as the owner of the fix (`tracked_by: "27-01"`; *"27-01 owns the fix and will
change the code this section points at"*), so they were corrected here despite the plan's D-B.

### Three deviations from the plan's `<terminal_states_contribution>`, all stated

1. **The new row is TS-17, not TS-13.** TS-13 was already the PostgreSQL-exporter row.
2. **TS-07's locator stays at the enum constant.** The plan asserts it should hold
   `setStatus(MediaAsset.Status.FAILED)`. The register locates `entity_status` rows at the enum
   constant — TS-07/08/09, **3 of 3** — which is what `covers:` declares and what
   `check-terminal-states.sh` X-1 D-3 matches on. Relocating TS-07 into the reaper would break that
   alignment *and* the X-1 check. The anchor asserted is therefore the row's own `covers` constant,
   which ties locator and covers together instead of testing one in isolation.
3. **`runbook:` stays at `terminal-states.md`.** The plan sets `alerts.md#mediastallfailures`; the
   register's header states all rows point at `terminal-states.md` and that `alerts.md` is 27-03's,
   so that would be a dangling reference.

**Adopted from the plan**, because both signals are real exported counters rather than 27-00's prose
(`'count of media_asset rows in status FAILED'` is not a query and could not back an alert):
`media_reaper_stalled_failed_total` and `media_reaper_suspended_total`.

### Arms

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed (`20374c0`) | 0 | TS-07 → `public enum Status { PENDING, ACTIVE, FAILED }`; TS-17 → `increment("media.reaper.suspended");` |
| BREAK 1 | TS-17 locator +100 (185 → 285) | 1 | `anchor 'media.reaper.suspended' not found at …:285 — locator is stale` / `line reads: } finally {` — **TS-07 stayed OK** |
| BREAK 2 | TS-07 locator +100 (44 → 144) | 1 | `anchor 'FAILED' not found at …MediaAsset.java:144` / `line reads: private OffsetDateTime quarantineReclaimedAt;` — **TS-17 stayed OK** |
| BREAK 3 | re-introduce a citation of the deleted locator | 1 | `'MediaPendingReaper.java:79' … = 1 (must be 0)` |
| RESTORE | `cp` the backup back | 0 | all three green |

Breaks 1 and 2 each fire on their own row while the other stays `OK` — the independence proof.
Breaking one arm and seeing "the check failed" would prove nothing about the other.

### Two vacuity findings inside this criterion itself

1. **The corrective prose re-tripped its own rule.** The first draft of `TS-07.deferred.reason`
   *explained* the fix by naming the stale locator — so the register still contained
   `MediaPendingReaper.java:79` and the check went RED on my own text. This is the documented "a doc
   rule that must name the string it forbids" trap, arriving through the author rather than the
   linter. The reason was reworded to describe the line without citing it.
2. **The `deleteByKey` probe counted a comment.** A bare `grep -c` returns **1** on the correct tree,
   because the reaper's javadoc still records the removed call on purpose — an expected-0 that is 1
   on a correct tree, and whose "fix" would have been to delete accurate history. The probe now
   excludes comment lines and reports both numbers: `0 executable, 1 total incl. javadoc`.

### Gate state — unchanged, and deliberately not "fixed"

```
rows 16 -> 17   X-1 missing-row=0   X-2 missing-alert=0   expired-deferral=0
X-3 missing-runbook=4   <- KeycloakDown, PaymentFailureSpike, RedisDown, StompBrokerLag
rc=1, owned by 27-03
```

---

## AC-5.5 re-run in the full sweep — and two FALSE REDs worth recording

Task 5's browser criterion was re-run against the running stack rather than assumed from the
handoff. It **passes**: `1 passed (2.5s)`, fixtures still seeded.

Getting there took two failed runs, **neither of which was a regression**. A false RED costs as much
as a false green — it invites someone to "fix" working code — so both are recorded:

| run | invocation | result | actual cause |
|---|---|---|---|
| 1 | `npx playwright test --project=mobile media-review-320.spec` | `1 failed` — `waitForURL(/\/dashboard/)` timeout | **no `E2E_VENDOR_PASSWORD`**, so the spec fell back to its placeholder `password123`; Keycloak correctly answered `Invalid username or password.` The page snapshot in `error-context.md` names it exactly — read that before suspecting the app. |
| 2 | `PLAYWRIGHT_BASE_URL=http://localhost:3100 E2E_VENDOR_PASSWORD=… …` | `1 failed` — earlier, at the sign-in page load | **the spec's own header comment is stale.** It documents port **3100**; this stack publishes the frontend on **3000** (`docker compose ps frontend` → `0.0.0.0:3000->3000/tcp`; `curl :3100` → `000`, `curl :3000/auth/signin` → `200`). |
| 3 | `E2E_VENDOR_PASSWORD=… npx playwright test --project=mobile media-review-320.spec` | **`1 passed (2.5s)`** | correct: config default `baseURL` 3000 + the real seed password from `.env`. |

**Follow-up:** `frontend/e2e/media-review-320.spec.ts:23` tells the next reader to use port 3100.
Either the comment or the port is wrong; on this stack it is the comment. Left as a flagged
follow-up rather than edited mid-Task-6, because the port is environment-dependent and
`PLAYWRIGHT_BASE_URL` already exists as the injection point (GLOBAL_RULE_6 — the value belongs in
config, not in a literal in a comment).

---

## Regression gates (recorded, not claimed as this plan's evidence)

| gate | rc | note |
|---|---|---|
| `k8s/scripts/check-env-contract.sh` | 0 | 49 injected env names, 122 placeholders, 0 violations |
| `k8s/scripts/check-render-invariants.sh` | 0 | INV-1..6 across 4 kustomize targets; LOC-1..6 on `k8s/local` |
| `scripts/check-terminal-states.sh` | 1 | X-3 only — 27-03 owns it |
| `scripts/check-alert-liveness.sh` | 1 | correct until 27-03 |

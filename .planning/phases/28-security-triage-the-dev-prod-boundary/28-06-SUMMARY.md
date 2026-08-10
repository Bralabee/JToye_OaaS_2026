---
phase: 28-security-triage-the-dev-prod-boundary
plan: 06
subsystem: auth
tags: [authorization, shop-access, threadlocal, background-jobs, rabbitmq, scheduled, async, testcontainers]

# Dependency graph
requires:
  - phase: 23-vendor-scoped-access
    provides: ShopAccessService, the isInternalCaller bypass, isDeclaredMachineClient (the precedent copied here)
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline
    provides: MediaProcessingWorker -> MediaAssetService.placeAsset — the documented #284 near-miss
  - phase: 28-security-triage-the-dev-prod-boundary
    plan: 04
    provides: currentVendorUserId(), the SSE per-emit re-check whose liveness arm this plan re-ran
provides:
  - SystemPrincipal — the explicit internal-trust declaration and its scoped asSystem() wrappers (#283 closed)
  - A behavioural #284 guard that reds when a gated service becomes reachable from an undeclared background path
  - AsSystemHarness — a greppable, per-class declaration for tests that drive services as the system
  - The FULL :core-java:test + :core-java:integrationTest proof discharging 28-04's deferred obligation
affects: [28-11 phase close-out, 28-11 docs/metrics.json manifest, any future background entry point reaching a gated service]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Declaration over inference: trust is asserted by the caller, never implied by a missing identity"
    - "Scoped-only marker API (no unbalanced begin/end), so a declaration structurally cannot outlive its work"
    - "A behavioural guard that asserts the background path's OUTCOME, so it cannot go green over a dead feature"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/access/SystemPrincipal.java
    - core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalGuardTest.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/AsSystemHarness.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/SystemHarnessExtension.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessFailClosedIntegrationTest.java
    - "11 integration test classes declaring @AsSystemHarness (enumerated below)"

key-decisions:
  - "Marker shape: a ThreadLocal flag, NOT a sentinel ROLE_SYSTEM Authentication — a sentinel would satisfy every other Spring Security check on the thread, widening one narrow bypass into a general authenticated identity."
  - "ZERO background entry points were wrapped, because ZERO reach a gated call. Declaring one that reaches nothing gated grants a bypass nothing needs."
  - "Failing tests were fixed by DECLARING the harness, not by a realm-admin principal: entering the gate with a JWT also runs onRequest(), whose user_directory upsert is a WRITE that would change what those tests observe."
  - "The plan's stated fail direction for Task 2/3 was vacuous by construction (no declaration existed to remove); replaced with a strictly stronger arm — ADD a gate to the background path and confirm the guard reds."

patterns-established:
  - "@AsSystemHarness: a test claiming system status must SAY so in one greppable line, so `rg AsSystemHarness` enumerates every such claim"
  - "Non-vacuity control inside the guard itself: assert the gate is live on the same thread whose success is being asserted"

requirements-completed: [SEC-04]

# Metrics
duration: 75min
completed: 2026-08-10
---

# Phase 28 Plan 06: Declared Internal Trust (#283 / #284) Summary

**`auth == null` no longer grants anything: internal trust is now an explicit `SystemPrincipal.asSystem` declaration, an undeclared no-principal thread is denied with the typed 403, and a behavioural guard reds the build the moment a gated service becomes reachable from an undeclared background path — proven across a FULL 1650-test suite that was RED at 36 before the triage and is green after it.**

## Performance

- **Duration:** ~75 min (03:32 base → 04:47)
- **Tasks:** 3 of 3
- **Files:** 5 created, 3 modified + 11 test classes annotated

## Task Commits

1. **Task 1: the marker replaces the null-principal inference** — `554f5338` (feat)
2. **Task 2: ZERO entry points needed declaring; the near-miss names its guard** — `6d7c767e` (docs)
3. **Task 3: the #284 guard + the 11 harness classes triaged** — `164cf390` (test)

The #284 guard itself landed in `554f5338` rather than Task 3's commit, because it lives in
`SystemPrincipalGuardTest` alongside the deny/allow arms Task 1's acceptance criteria required.
Splitting one class across two commits would have left the tree red in between.

## The marker shape, and why

**Chosen: a `ThreadLocal<Boolean>` flag. Rejected: a sentinel `Authentication` carrying `ROLE_SYSTEM`.**

Both satisfy #283. The trade is *how far the declaration reaches*. The ThreadLocal is visible to
exactly one consumer — `ShopAccessService.isInternalCaller()` — and grants nothing anywhere else:
a `@PreAuthorize` method or any future authorization component still sees an unauthenticated
thread and still refuses it. A sentinel would install an authenticated principal into the
`SecurityContext` and thereby satisfy *every* other check on that thread at once; #283 asks for
one narrow bypass to become explicit, not for it to widen into a general-purpose identity. Written
into the class javadoc so the trade is legible to the next reader.

**RLS still tenant-scopes a declared system caller.** The marker is an authorisation declaration
about the shop-scope gate only — it says nothing about which tenant's rows are visible, and cannot
reach another tenant's data. Stated in the javadoc (T-28-28).

**Lifecycle.** `asSystem` restores the PRIOR value in a `finally`, and on the outermost exit
`remove()`s the entry rather than setting it `false` — so a nested scope cannot drop the outer
declaration (T-28-26) and a pooled thread is left carrying no entry at all.

## Task 2 — how many background entry points were wrapped: **ZERO**, and why

The candidate surface was **11 `@RabbitListener` files, 9 `@Scheduled`, 6 `@Async`** — plus **2
`@EventListener(ApplicationReadyEvent)` entry points the plan's list did not count**
(`ShopCoordinateBackfill`, `DatabaseConfigurationValidator`). **28 candidates, 0 wrapped.**

Only three reach a gated *class* at all, and **none reaches a gated *call***:

| Background entry point | Reaches | Gated? |
|---|---|---|
| `MediaProcessingWorker` (`@RabbitListener`) | `MediaAssetService.placeAsset` | **No** — the one entry point of four that does not gate |
| `OrderSseFanoutListener` (`@RabbitListener`) | `OrderSseService.broadcast` | **No** — calls `resolveMembership`, which is not a gate |
| `GateChainRunner` (`@Async`) | `VendorOnboardingService` → `ShopService.setPublished` | **No** |

Everything else injects only repositories/infra (`EntityManager`, `TransactionTemplate`,
`RabbitTemplate`, `JdbcTemplate`, `WebClient`, `StorageService`).

**Interface dispatch was checked explicitly**, because that is exactly where text search goes blind
on this codebase (a rename driven from grep output edits the docs and misses the code). No
`NotificationChannel` implementation (`EmailChannel`, `WhatsAppSmsChannel`) and no
`OnboardingGate` implementation (`AllergenCompletenessGate`, `CompaniesHouseGate`, `FhrsGate`)
touches a gated service. `AllergenCompletenessGate` only *mentions* `ProductLabelService` in
javadoc — it deliberately duplicates the predicate rather than calling it.

**Instrument — stated, because it matters.** The `idea` MCP was **not available** in this
environment (no `mcp__idea__*` tools), so per the plan's fallback: reading plus search, confirmed
with `searchcheck` **and a positive control**:

- `searchcheck -F 'shopAccessService.' core-java/src/main/java` → **PASS, 13 files, all search paths agree**
- The first attempt, `searchcheck 'shopAccessService\.'`, reported DISAGREEMENT — that was **my
  pattern's artefact**, not a hidden-file problem: the `-F` arm searched for a literal backslash.
  The load-bearing arms (default vs `-uu`) agreed at 13 both times. Recorded rather than quietly
  re-run, because a disagreement that is dismissed without explanation is how a real one gets missed.
- Positive control for every negative finding: the same patterns DO match elsewhere (13 files for
  the gate calls; 9 and 12 hits for `ShopAccessDeniedException` in the two deny-asserting classes).
  An empty grep is otherwise evidence about the pattern, not the code.

A negative finding from the weaker instrument is a hypothesis, so the conclusion does **not** rest
on search alone: it rests on reading every candidate's injected dependencies and on the behavioural
guard, which fails if the conclusion ever stops being true.

What Task 2 *did* add is the pointer that was missing: `MediaProcessingWorker.placeOnActive` now
names #283/#284, states that `placeAsset` being ungated is load-bearing, and names the test that
fails if that changes — so the person who adds the gate finds the one-line remedy at the site.

## Break arms — three run, both directions recorded

Source was **committed before each arm**, so the restore target was a committed state, and every
restore was verified **by content** (`git hash-object` == `git rev-parse HEAD:<path>`), never by
`git diff --stat`. Clean → arms → **clean again**.

| Arm | Break applied | Result | Reads as |
|---|---|---|---|
| **1** | `isInternalCaller()` returns `true` unconditionally | **6 of 7 RED** in `SystemPrincipalGuardTest` and **6 of 7 RED** in `ShopAccessFailClosedIntegrationTest` | the deny arms can fire; the only survivor is the ALLOW arm, which an unconditional grant should still pass |
| **2** | `finally` does an unconditional `SYSTEM.remove()` | **exactly 2 of 8 RED** — `anInnerScopeReturningLeavesTheOuterDeclarationIntact`, `anInnerScopeThrowingLeavesTheOuterDeclarationIntact` | the nesting arms are specific to restore-prior-value, not passing because everything passes |
| **3** | `shopAccessService.requireGroupAdmin()` ADDED to `MediaAssetService.placeAsset` | **exactly 1 of 7 RED** — `backgroundListenerPathStillCompletesUndeclared`, with `ShopAccessDeniedException: requires GROUP_ADMIN (group-wide)` | the #284 guard fires on precisely the event it exists to catch, and nothing else does |

**Arm 3 replaced a vacuous criterion, and the substitution is recorded rather than silent.** The
plan's stated fail direction for Tasks 2 and 3 was *"remove the `asSystem` declaration from one
wrapped entry point"*. **Zero entry points are wrapped, so that arm could never fail — it is
vacuous by construction.** The strictly stronger form actually run is the inverse and is closer to
the real threat: *add* a gate to the background path, simulating the exact future edit #284 fears,
and confirm the guard reds. It did, and only it did.

Restores verified by content: `ShopAccessService.java` → `782e5c43…`, `SystemPrincipal.java` →
`89c5348f…`, `MediaAssetService.java` → `7ce90ab6…`, each equal to its committed blob, asserted
again after the final suite as the closing clean arm.

## Source assertions — and the one that could not be met as written

| Pattern | Before | After | Note |
|---|---|---|---|
| `getAuthentication() == null` **in code** | **1** (line 624, inside `isInternalCaller`) | **0** | the change itself |
| `getAuthentication() == null` **raw grep** | 1 | **1** | the survivor is **javadoc line 629**, quoting the expression it removed |
| `auth == null` (the `onRequest()` occurrence) | 1 | **1** (line 547) | `onRequest()` untouched — it does not appear in the diff at all |

The plan's criterion was *after-count 0* for the raw pattern. **It measures 1, and the reason is
the documented trap of a rule that must name the string it forbids** — the new javadoc explains
what the method *used to* return, so the grep fires on the explanation. Rather than delete the
explanation to satisfy a grep, the assertion was replaced with a strictly stronger, comment-excluding
form, and **its positive control was run**: the same filter applied to the pre-change file at
`8da7b451` still finds the occurrence at line 624, so the 0 is evidence about the code and not an
artefact of a filter that can no longer match.

## Test counts — no regression by omission

**FULL run, both tasks, `GRADLE_RC=0`, 17m 25s:**

| Task | Classes | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| `:core-java:test` | 148 | **1095** | 0 | 0 | 1 |
| `:core-java:integrationTest` | 125 | **555** | 0 | 0 | 1 |
| **Total** | **273** | **1650** | **0** | **0** | 2 |

**Executed, not cached** — the vacuous shape this project has recorded. Evidence: the log shows
`> Task :core-java:test` and `> Task :core-java:integrationTest` **without** `UP-TO-DATE`, and
`6 actionable tasks: 2 executed, 4 up-to-date`; and **all 148 fast + all 125 integration XML files
were written during the run** (`find -newermt` against the run's start).

**Comparison against before the plan:**

| Measure | Before | After | Delta |
|---|---|---|---|
| `:core-java:test` | 147 classes / **1087** tests | 148 / **1095** | +1 class, +8 (`SystemPrincipalTest`) |
| `:core-java:integrationTest` | 124 classes / **548** tests (derived) | 125 / **555** | +1 class, +7 (`SystemPrincipalGuardTest`) |
| `@Test` methods, whole test tree | **1607** across 259 files | **1622** across 261 files | **+15, zero deletions** |

The integration "before" is derived rather than measured (no pre-plan integration run exists), so
it is corroborated by the static count, which is the measurement that actually answers the
question the criterion asks — *was a test deleted?* — and answers it **no**: +15 is exactly 8 + 7.

**Three `@Tag("testcontainers")` files produced no integration results, and none is a skipped
test:** `IntegrationTestSupport` and `LiveCacheTestSlice` contain **zero** `@Test` methods (they
are support classes whose javadoc mentions the tag), and `PublicUnsubscribeRequestShapeTest` **ran
in the fast task**. Checked rather than assumed, because "125 ran of 126 tagged" is exactly how a
silently-skipped class hides inside a green run.

## The 36 failures — every one triaged, and the bucket split

**36 failures / 11 classes, ALL bucket (a) — "relied on the removed bypass, test updated to declare
itself". ZERO bucket (b) real regressions.**

The fast task was green throughout (1095/1095) because its no-principal tests mock
`ShopAccessService` rather than entering the real gate; the blast radius #283 measured at 62
no-principal files landed entirely in `integrationTest`. **The plan's insistence on the full run was
load-bearing: a targeted run would have shipped 36 red tests.**

Every class was checked individually before it was touched:

1. the gated call is **scaffolding** (seeding, or reaching the behaviour under test), never the **subject** — none asserts an authorization outcome;
2. **none asserts a denial** — verified, with a positive control proving the pattern can match (9 and 12 hits in the two classes that do). So a class-level declaration cannot turn a deny-assertion green. The classes that DO assert denials (`ShopAccessFailClosedIntegrationTest`, `SystemPrincipalGuardTest`, `ShopAccessEnforcementIntegrationTest`, `CrossTenantAuthzIntegrationTest`) deliberately do **not** carry it.

### Every test file that gained a declaration, and why it legitimately represents system work

| # | Class | Failures | Why the declaration is honest |
|---|---|---|---|
| 1 | `ScheduledCleanupServiceIntegrationTest` | 1 | seeds DRAFT orders via `orderService.createOrder`; **the `@Scheduled` service under test reaches no gate at all** — the harness does |
| 2 | `MediaDedupAttachIntegrationTest` | 4 | drives the gated media accept (:118) to reach the dedup behaviour |
| 3 | `MediaDurabilityIntegrationTest` | 1 of 11 | `markerLifecycle()` seeds through the gated accept; inert for the other 10 |
| 4 | `MediaReviewQueueIntegrationTest` | 1 of 3 | `keepDismissesFlag()` reaches `mediaAssetService.keep` (:439) as scaffolding |
| 5 | `ConcurrentStockDecrementIntegrationTest` | 1 | drives `createOrder`/`confirmOrder` to reach the stock race |
| 6 | `OrderControllerIntegrationTest` | 7 | despite the name there is **no MockMvc** — it calls `orderService` directly |
| 7 | `ProductSearchFtsIntegrationTest` | 11 of 17 | subject is full-text search correctness, reached via `productService.search` |
| 8 | `RedisFaultInjectionIntegrationTest` | 1 | subject is cache degradation under a Redis outage |
| 9 | `CrossTenantMcpWriteRlsIntegrationTest` | 1 of 3 | **subject is the RLS wall BELOW the gate** — see below |
| 10 | `ShopImageCrossTenantIntegrationTest` | 7 | **subject is the cross-tenant IDOR 404** — see below |
| 11 | `GuestCheckoutStockConvergenceIntegrationTest` | 1 of 2 | the guest half is unauthenticated by design; the **vendor** `confirmOrder` half is the harness acting as the system |

Plus one test whose expected outcome the fix deliberately **inverted**, recorded in place rather
than quietly rewritten:

- `ShopAccessFailClosedIntegrationTest` **case 5** was the *preservation guard* for the retained
  `auth == null` bypass ("an internal caller with no Authentication still passes the gate"). That
  bypass is the thing #283 removes, so it now asserts **denied undeclared, allowed declared**, and
  both the method and the class javadoc say so. It was not "made to pass".

**Two of the eleven deserve emphasis because their subject IS security, and the declaration
RESTORES the assertion rather than weakening it.** `ShopImageCrossTenantIntegrationTest` (PR #70 /
issue #71) and `CrossTenantMcpWriteRlsIntegrationTest` assert the cross-tenant **404**, which lives
*below* the shop gate in `require()` → FC-1 `requireShopInCallerTenant`. Undeclared, they stopped
at a **403** and never reached the assertion they exist to make. Declared, the real code path
answers again. Read the other way round, this is a small piece of evidence for the change itself:
after it, an undeclared caller does not even get far enough to learn whether a foreign shop exists.

### Mechanism, and why it is a declaration rather than a reinstated bypass (T-28-27)

`@AsSystemHarness` — an `@ExtendWith` meta-annotation — is one greppable line per class, so
`rg AsSystemHarness core-java/src/test` **enumerates every test claiming system status**, which the
old implicit rule made impossible to ask. The interceptor uses the **scoped `asSystem` API only**;
no unbalanced `begin()`/`end()` was added to production code, because an API that can leave a
marker set is precisely how a declaration becomes the permanent bypass being removed.

**A realm-admin principal was considered instead and rejected**, and the reason is not stylistic:
entering the gate with a JWT principal also runs `ShopAccessService.onRequest()`, whose throttled
`user_directory` upsert is a **WRITE**. That would add rows several of these tests do not expect —
a "fix" that alters the thing under test. Leaving the principal absent keeps the side effects
exactly as they were.

### The two classes that needed a per-site declaration — and what that proves

`ConcurrentStockDecrementIntegrationTest` (`pool.submit` workers) and
`RedisFaultInjectionIntegrationTest` (`assertTimeoutPreemptively` runs its body on its own thread)
stayed RED after the class-level annotation. **`SystemPrincipal` is a plain `ThreadLocal` and is
deliberately NOT inherited by a spawned thread**, so the declaration correctly did not reach those
workers. The declaration was made *inside* each worker, next to the `TenantContext.set` that is
there for exactly the same reason.

This is the marker's non-inheritance property — asserted in
`SystemPrincipalTest.aSpawnedThreadDoesNotInheritTheDeclaration` — showing up as a real failure
rather than as a passing unit test: *"exactly one CONFIRM succeeds" saw **zero** succeed*. A
declaration that leaked to child threads would have made both classes pass silently and handed the
bypass to every thread any legitimate `asSystem` ever forks.

## Plan 28-04's SSE arms — re-run, green, no regression

The plan sequenced this after 28-04 precisely so the interaction would be observed here (T-28-29).
`OrderSseService.broadcast` resolves membership on a thread with no principal, which is exactly
what this change re-classifies — but it calls `resolveMembership`, which is **not** a gate, so it
is unaffected. Measured rather than reasoned:

| 28-04 arm | 28-04 recorded | Now |
|---|---|---|
| `OrderSseGrantRecheckTest` | 7 | **7, 0 failures** |
| `OrderSseGrantRecheckIntegrationTest` (liveness + security) | 2 | **2, 0 failures** |
| `OrderSseServiceTest` | 12 | **12, 0 failures** |
| `OrderSseServiceTenantIsolationTest` | 5 | **5, 0 failures** |
| `OrderSseFanoutListenerTest` | — | **4, 0 failures** |

**This plan's full run also discharges 28-04's deferred full-suite obligation** (28-04-SUMMARY:
*"The full-suite proof for this change lands with 28-06"*). `OrderSseService.java` and 28-04's
change to `ShopAccessService` are both in the tree that produced the 1650-test green run, so the
`trap_scope_gate_integrationtest_regression` risk 28-04 carried is now closed with evidence.

## Deviations from Plan

### Auto-fixed / recorded

**1. [Rule 3 — Blocking] The worktree was one commit behind its declared base.**
- **Found during:** startup. HEAD was `1078e09e`; the assigned base `8da7b451` (wave 1 merged) is its **descendant**, so the phase-28 PLAN files did not exist in the tree.
- **Fix:** the sanctioned `git reset --hard 8da7b451` from the executor's own branch check. No commits were lost (HEAD was an ancestor).

**2. [Rule 2 — Missing critical functionality] Two spawned-thread call sites needed their own declaration.** Documented above; without it the concurrency and cache-degradation behaviours under test never ran.

**3. Two acceptance criteria were vacuous as written; both replaced with strictly stronger forms and BOTH recorded.** The `getAuthentication() == null` after-count of 0 (the javadoc names the string it removed) and the "remove one `asSystem` declaration" break arm (no declaration exists). Neither was silently substituted.

### Deliberate, recorded substitutions

**4. A second and third test file beyond the plan's `files_modified`.** The plan named only
`SystemPrincipalGuardTest`. Its Task 1 criteria additionally require a **nested-scope** arm, which
needs no database — so marker lifecycle lives in the fast `SystemPrincipalTest` (8 tests) and gate
behaviour in the Testcontainers `SystemPrincipalGuardTest` (7 tests). This also makes Task 1's
verify command (`:core-java:test --tests '*SystemPrincipal*'`) match something, which it could not
have if the only class were testcontainers-tagged.

**5. `@AsSystemHarness` + `SystemHarnessExtension` are new test-support files not named in the plan.**
The alternative was editing dozens of individual call sites across 11 classes — more churn, less
greppable, and it would have made "which tests claim system status?" unanswerable.

**6. Verify commands corrected (as 28-04 also recorded).** The plan's `cd core-java && ./gradlew …`
cannot work — the wrapper is at the repo root. Run as `./gradlew :core-java:… ` from the root. The
live build directory is `core-java/build-local`, not `core-java/build`.

**7. A javadoc claim I wrote was wrong and was corrected rather than left.** `SystemPrincipal`
initially warned that `asSystem(() -> valueReturningCall())` would be an ambiguous overload. It is
not: JLS 15.12.2.5 makes `Supplier` strictly more specific than `Runnable` for a value-producing
expression lambda. Verified by compilation and the javadoc now states the actual rule.

**Total deviations:** 1 × Rule 3, 1 × Rule 2, plus 5 recorded substitutions/corrections.
**Scope:** nothing added beyond what #283, #284 and the plan's own threat model require.

## Threat model — dispositions

| Threat | Disposition | Evidence |
|---|---|---|
| T-28-24 EoP: granting on an absent `Authentication` (#283) | **closed** | `isInternalCaller()` reads the declaration; deny arms green and proven able to fail (arm 1) |
| T-28-25 EoP: a future background caller reaching a gate undeclared (#284) | **closed** | behavioural guard exercising the real `MediaProcessingWorker` path; proven able to fail (arm 3) |
| T-28-26 EoP: a pooled thread retaining a stale marker | **closed** | prior value restored, entry removed on outermost exit; nesting arms proven able to fail (arm 2) |
| T-28-27 EoP: the bypass reinstated wholesale across 62 test files | **closed** | 11 classes named + justified individually; every claim greppable via `rg AsSystemHarness`; realm-admin alternative considered and its rejection recorded |
| T-28-28 Spoofing: a system declaration mistaken for a tenancy escape | **closed** | javadoc states RLS still tenant-scopes a system caller; the marker is authorisation-only |
| T-28-29 DoS (self-inflicted): 28-04's SSE re-check breaking | **closed** | all five SSE arms re-run green, liveness included |

Cross-cutting dimensions: web-perf **N/A**, SEO **N/A**, agent-readiness **N/A** (no API surface
change; `isDeclaredMachineClient` untouched). Falsifiable evidence: three break arms, two vacuous
criteria replaced and recorded, before/after counts from JUnit XML plus a static `@Test` census.

## Threat Flags

None. No new network endpoint, auth path, file access pattern or schema change was introduced —
this plan **narrows** an existing authorization decision and adds no surface.

## Known Stubs

None.

## Deferred / Notes for downstream plans

- **`docs/metrics.json` and the prose counts were deliberately NOT touched — 28-11 owns the
  manifest, so a red `check-doc-metrics` between here and 28-11 is DESIGNED, exactly as the plan
  states.** Numbers for 28-11, measured with the gate's own method
  (`@Test\b` occurrences under `core-java/src/test`):

  | | manifest today | tree now |
  |---|---|---|
  | `java_test_methods` | 1595 | **1623** |
  | `java_test_files` | 256 | **261** |

  This plan contributes **+15 methods / +2 files**; the remainder of the gap is wave 1's (28-01
  through 28-04, which also deferred the manifest). 28-11 should regenerate with
  `scripts/docs-freshness.sh --write` rather than doing arithmetic.

- **The running compose stack was NOT rebuilt or restarted by this plan.** No `docker compose`
  command of any kind was issued; every arm was Testcontainers or a unit test, as the plan's
  RUNTIME CONSTRAINT requires (28-05 was running a live token arm in the same wave).

- **`STATE.md` / `ROADMAP.md` were NOT modified** — the orchestrator owns those writes after the
  wave completes.

- **For any future background entry point:** if it reaches a gated call, wrap it in
  `SystemPrincipal.asSystem(...)` at the entry point — a one-line change — and do **not** touch the
  gate. `SystemPrincipalGuardTest.backgroundListenerPathStillCompletesUndeclared` is the test that
  will tell you, and `MediaProcessingWorker.placeOnActive` carries the pointer at the site.

## Self-Check: PASSED

Files claimed as created — all present:
- `core-java/src/main/java/uk/jtoye/core/security/access/SystemPrincipal.java`
- `core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalTest.java`
- `core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalGuardTest.java`
- `core-java/src/test/java/uk/jtoye/core/testsupport/AsSystemHarness.java`
- `core-java/src/test/java/uk/jtoye/core/testsupport/SystemHarnessExtension.java`
- `.planning/phases/28-security-triage-the-dev-prod-boundary/28-06-SUMMARY.md`

Commits claimed — all present in `git log`: `554f5338`, `6d7c767e`, `164cf390`.

Working tree clean; the three break-arm files re-verified by content hash against HEAD **after**
the final suite (closing clean arm).

---
*Phase: 28-security-triage-the-dev-prod-boundary*
*Completed: 2026-08-10*

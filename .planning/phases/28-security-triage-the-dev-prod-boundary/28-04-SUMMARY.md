---
phase: 28-security-triage-the-dev-prod-boundary
plan: 04
subsystem: auth
tags: [sse, rls, multi-tenancy, shop-staff, revocation, rabbitmq, testcontainers, stomp]

# Dependency graph
requires:
  - phase: 23-vendor-scoped-access
    provides: ShopAccessService, resolveMembership + the shopMembership cache, shop_staff (V52/V57), canAccessShop
  - phase: 16.1
    provides: OrderSseService per-tenant emitter registry (AUDIT-W0-01)
provides:
  - Per-emit shop-grant re-check on the SSE order stream — a revoked user's open connection delivers nothing (#281)
  - ShopAccessService.currentVendorUserId() — the non-throwing identity accessor
  - The measurement that TenantContext.set alone is a sufficient tenant pin on the @RabbitListener fan-out thread
  - A measured, filed disposition for the STOMP transport's equivalent temporal gap (#627)
affects: [28-05 triage doc, 28-11 phase close-out, any future work on the KDS real-time transports]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Narrowing re-check: a temporal authorization check ANDed with the retained snapshot, never substituted for it"
    - "grantBacked provenance: record WHY a subscriber is unrestricted, so a re-check can tell 'row gone' from 'no row was ever needed'"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/order/OrderSseGrantRecheckTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderSseGrantRecheckIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderSseServiceTenantIsolationTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderSseServiceTest.java

key-decisions:
  - "Pin shape: TenantContext.set ALONE is sufficient — measured, not assumed. No explicit set_config was added."
  - "The re-check is a narrowing filter ANDed with the snapshot, so no revocation-era widening can ever grant an emit the snapshot excluded."
  - "A subscriber whose unrestricted status is not shop_staff-backed (realm admin, day-one implicit admin) is deliberately NOT re-checked — shop_staff cannot see their status, so its absence is not evidence of revocation."
  - "STOMP gates only at SUBSCRIBE — filed as #627 rather than absorbed into this plan."

patterns-established:
  - "Paired security/liveness arms built from an IDENTICAL world, so each can fail independently"
  - "Instrument-validity test asserting the NOSUPERUSER downgrade took and that no CacheManager exists"

requirements-completed: [SEC-04]

# Metrics
duration: 35min
completed: 2026-08-10
---

# Phase 28 Plan 04: Revoked SSE Stream (#281) Summary

**A revoked user's already-open KDS stream now delivers nothing — the per-emit re-check resolves the subscriber's live `shop_staff` grant under a tenant pinned on the fan-out thread, with the liveness half proven by a break arm that reproduces the dead-KDS failure mode on demand.**

## Performance

- **Duration:** ~35 min
- **Tasks:** 3 of 3
- **Files modified:** 4 modified, 2 created

## Accomplishments

- **#281 closed.** `broadcast()` consults the subscriber's CURRENT membership before every emit. The socket may still linger to `SSE_TIMEOUT`, but it carries no events while it does.
- **The liveness risk was real and was caught by measurement, not review.** A naive re-check would have denied every emit to every realm admin — including this project's own `admin-user` E2E account — because `resolveMembership` reads `shop_staff`, and JIT provisioning explicitly skips realm admins (`ShopAccessService.java:590-592`, "implicit GROUP_ADMIN — no row needed"). See Deviation 1.
- **The pin shape was decided by a measurement, not a guess** (see "The pin question").
- **The STOMP transport was measured and filed as #627**, not silently absorbed.

## Task Commits

1. **Task 1: Capture userId and re-check the grant before every emit** — `8106f45e` (feat)
2. **Task 2: Both arms — revoked blocked, granted delivered — plus the off-thread cache-miss proof** — `d6be9e81` (test)
3. **Task 3: Measure the STOMP channel and record the disposition** — no repo change *by plan design* (the plan states `docs/security/PENTEST-TRIAGE.md` is NOT touched here; the deliverable is issue **#627** plus this SUMMARY, for 28-05 to transcribe)

## The pin question — ANSWERED: `TenantContext.set` alone

The plan left this open and required a measurement to settle it.

**Result: no explicit `set_config` was needed, and none was added.** `broadcast()` pins only
`TenantContext`. The reason it suffices: `ShopAccessService` is `@Transactional` at class level, and
`TenantSetLocalAspect` (`TenantSetLocalAspect.java:27-39`, plus its `@Before` on every repository
call at `:43-55`) issues `SELECT set_config('app.current_tenant_id', ?, true)` from `TenantContext`
once a real transaction is active. The global aspect performs the GUC write that
`MediaProcessingWorker` does by hand.

**The measurement that decided it:** `OrderSseGrantRecheckIntegrationTest.grantResolvesOffThreadOnACacheMiss`
is GREEN with `TenantContext.set` only — a real `shop_staff` row resolved from a thread with no
`SecurityContext`, no `TenantContext` and no GUC, under `ALTER ROLE ... NOSUPERUSER`. Had it been
RED, the plan's fallback (copy `MediaProcessingWorker.java:154-172`, inject `EntityManager`) would
have applied. It was not.

**Consequence:** the constructor was NOT changed, so no existing test needed a construction update
for that reason (they needed stub updates for a different reason — see Deviation 2).

## Falsifiability — all three break arms run, both directions recorded

Clean → arms → clean again. Every restore verified **by content**
(`git hash-object` == `git rev-parse HEAD:<path>` == `926618e69cf4df8db01f2324e64ffc823421f092`),
never by `git diff --stat`. Source was committed before the arms ran, so the restore target was a
committed state.

| Arm | Break applied | Result | Reads as |
|---|---|---|---|
| **1** | re-check consult skipped | **security arm RED**, liveness arm GREEN, null-shopId + realm-admin arms GREEN | the security arm can fire, and is not passing merely because everything passes |
| **2** | re-check denies unconditionally | **liveness arms RED** (both), **security arm GREEN** | the pair distinguishes "revoked user blocked" from "everyone blocked" — the liveness arm is NOT vacuous |
| **3** | `TenantContext.set` neutralised (NOT the aspect's `set_config`) | **off-thread integration arm RED**: `"Actually, there were zero interactions with this mock"` | the pin is the dominant control, and its absence reproduces the dead-KDS signature exactly |

Arm 2 is the important one: under an unconditional deny the **security arm passed perfectly** while
the KDS was dead for every subscriber. That is precisely the failure mode T-28-14 names, and only
the liveness arms caught it.

Break arm 3 was neutralised at `TenantContext.set` deliberately, not at the `set_config` —
`trap_tenant_pin_is_under_a_global_aspect`: the aspect would have re-established the GUC and the arm
would have been vacuous.

## Test counts — no regression by omission

| Suite | Before | After |
|---|---|---|
| `OrderSseServiceTenantIsolationTest` | **5** | **5** |
| `OrderSseServiceTest` | **12** | **12** |
| `OrderSseGrantRecheckTest` | — | **7** (new) |
| `OrderSseGrantRecheckIntegrationTest` | — | **2** (new) |

Counts read from the JUnit XML, not inferred. Final run forced with `--rerun-tasks` because the
first closing run completed in 4s and a cached "BUILD SUCCESSFUL" that executes nothing is a known
vacuous shape; the forced run's XML timestamps confirm real execution.

**Source assertions (fail direction recorded first):** on the pre-change file both
`resolveMembership(` and `TenantContext.set(` returned **0** (rc=1). After: `resolveMembership(` = 2
occurrences, `TenantContext.set(` at `:168` and `:200`, `TenantContext.clear()` at `:202`. The
after-count is therefore evidence of the change, not of a pattern that always matched.

`ShopAccessService.requireVendorUserId()` is **unchanged** — `git diff` on that file is purely
additive (one import + one new method).

## Non-vacuity controls in the integration arm

Both asserted in `theInstrumentIsValid()`, not assumed:

1. **`usesuper` is read back and asserted false.** The Testcontainers bootstrap role is a superuser
   and bypasses even FORCE RLS; without the downgrade the arm would pass with the tenant pin removed
   entirely.
2. **`CacheManager` is asserted absent.** `CacheConfig` is `@Profile("!test")`, so `@Cacheable` is
   inert and every `resolveMembership` executes its body against the database. This is **stronger
   than the plan's "clear the cache"** — a clear can be undone by any intervening read, whereas
   structural absence cannot. Recorded as a deliberate substitution, not a silent one.
3. **Denominator:** the seeded grant is counted as visible (=1) under its own pinned tenant, so the
   off-thread success is readable.

## Task 3 — STOMP disposition: **SUBSCRIBE-only. Filed as #627.**

**Answer: the STOMP path gates the shop grant ONLY at SUBSCRIBE; it does NOT re-check per message.**

Coordinates:
- `TenantChannelInterceptor.java:65-70` — dispatch by STOMP command: `CONNECT` authenticates,
  `SUBSCRIBE` validates, `SEND` only propagates tenant context. No per-delivery branch exists.
- `TenantChannelInterceptor.java:175-176` → `validateShopSubscription` → **`:234`
  `shopAccessService.canAccessShop(...)`** — the one and only shop-grant call, on the SUBSCRIBE arm.
- `WebSocketConfig.java:90-92` — the interceptor is registered on `configureClientInboundChannel`
  **only**. `configureClientOutboundChannel` appears nowhere in the repo.
- `WebSocketConfig.java:66-74` — in `relay` mode the broker delivers straight to the subscriber
  session, so there is no application-side outbound frame to gate at all.

**Instrument used — stated, because it matters:** the `idea` MCP was **not available** in this
execution environment, so per the plan's fallback I read both classes in full and confirmed the
negative finding with `searchcheck` **plus a positive control**:

- `searchcheck configureClientOutboundChannel` → **0 files**, all search paths agree
- `searchcheck configureClientInboundChannel` → **5 files**, all search paths agree

The positive control is what makes the zero meaningful: an empty grep is otherwise evidence about
the pattern, not the code (`trap_grep_pattern_shape_false_negative`). The finding does **not** rest
on an empty grep alone — it rests on reading the command dispatch and the channel registration.

**Filed:** **#627** — *"STOMP shop grant is gated only at SUBSCRIBE — a revoked subscriber keeps
receiving on the open session (same class as #281, one transport down)"*, OPEN, sanitized (impact +
fix + acceptance only, no repro payload), cross-referencing **#289** and **#281**. Not fixed here:
a WebSocket message-path change has a different blast radius (two broker modes, a pooled
inbound-channel thread with a documented tenant-leak hazard, and no outbound frame in relay mode).

**Worth carrying to 28-05/28-11:** the STOMP gap is *less* bounded than #281 was. #281 was capped at
5 minutes by `SSE_TIMEOUT` forcing a reconnect and re-gate; a STOMP subscription has no equivalent
bound and can live for a whole shift.

## #281 disposition — for the triage doc (28-05)

**Status: FIXED** by this plan's per-emit re-check.

Residual, both numbers written out with their sources — neither rounded away:

| Residual | Bound | Source |
|---|---|---|
| The idle connection may linger after revocation (delivering **nothing**) | **5 minutes** | `SSE_TIMEOUT = 300_000L`, `OrderSseService.java:19` |
| Cross-replica revocation latency (a replica that did not serve the revoke may hold a cached allow) | **5 minutes** | `shopMembership` TTL, `CacheConfig.java:97` |

Freshness on the node that served the revoke is immediate
(`ShopAccessService.evictMembershipAfterCommit`, `:513-524`); the TTL is the backstop and therefore
the cross-replica number.

Two further residuals, stated rather than implied:
- A **realm admin** is not re-checked (see Deviation 1). Revoking a realm admin is a token-layer
  change, bounded on an open stream by `SSE_TIMEOUT` (5 min).
- A **strict-scoping config flip** is not honoured mid-stream. It is a static `@Value`, not a
  runtime-refreshable flag, so this is not reachable without a restart.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 — Missing critical functionality] The prescribed re-check would have killed the KDS for every realm admin**

- **Found during:** Task 1
- **Issue:** The plan prescribed deriving the re-check from the freshly resolved `Membership`
  (`isGroupAdmin()` and `perShopRole()`). Measured: `ShopAccessService.onRequest()` **skips JIT
  provisioning for realm admins** (`:590-592`, comment: *"implicit GROUP_ADMIN — no row needed"*), so
  a realm admin has **no `shop_staff` row at all** and `resolveMembership` returns
  `Membership(false, false, {})` for them even though nothing has been revoked. The literal
  implementation would therefore have denied **every emit to every realm admin** — including
  `admin-user`, this project's committed live-vendor E2E account — and to any day-one implicit admin
  who had not yet triggered a JIT provision. That is T-28-14 in a form the plan's four prescribed
  fast arms would not have caught, and per-PR CI runs only 2 of 126 Playwright specs, so it could
  have shipped.
- **Fix:** `ShopScope` carries a fourth field, `grantBacked`, computed at subscribe time as
  `groupAdmin && resolveMembership(userId).isGroupAdmin()` — i.e. *was this subscriber's unrestricted
  status backed by a fact a revoke can remove?* The re-check denies an unrestricted subscriber only
  when that status **was** `shop_staff`-backed and has since gone. This adds no policy duplication:
  it does not re-derive strict-scoping or the day-one rule, it only asks whether the specific row
  that backed the subscription still exists.
- **Files modified:** `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java`
- **Verification:** `groupAdminLivenessArm_realmAdminIsNotDeniedByAShopStaffRecheck` (green), and
  shown able to fail — it goes RED under break arm 2. Its counterpart
  `groupAdminArm_revokedTenantWideGrantStopsDelivery` proves a grant-backed admin IS still denied on
  revoke.
- **Committed in:** `8106f45e`

**2. [Rule 3 — Blocking issue] A third existing test class also constructs the service**

- **Found during:** Task 1
- **Issue:** The plan named only `OrderSseServiceTenantIsolationTest` as the direct-construction
  test to update. `OrderSseServiceTest` (**12 tests**) also constructs `OrderSseService` with a
  mocked `ShopAccessService`. Mockito returns `Optional.empty()` for `currentVendorUserId()` and
  `null` for `resolveMembership(...)` by default, so `subscribe()` would have refused and
  `broadcast()` would have denied — breaking ~11 of its 12 tests.
- **Fix:** Both classes stub `currentVendorUserId()` and `resolveMembership(...)` as a stable,
  still-granted subscriber, with a comment saying why. No existing test was deleted or weakened.
- **Files modified:** `OrderSseServiceTest.java`, `OrderSseServiceTenantIsolationTest.java`
- **Verification:** 12 → 12 and 5 → 5, zero failures.
- **Committed in:** `8106f45e`

### Deliberate, recorded substitutions (not silent)

**3. Cache-MISS proof is structural absence, not a cache clear.** The plan said "clear the cache".
Under the `test` profile `CacheConfig` is `@Profile("!test")`, so there is no `CacheManager` and
`@Cacheable` is inert — every resolve is structurally a MISS. This is strictly stronger than a clear
and is asserted (`assertThat(cacheManager).isNull()`) rather than assumed.

**4. Downgrade mechanism.** The plan named `ALTER ROLE ... NOSUPERUSER`; that is exactly what was
used (not the `SET LOCAL ROLE` house variant), because the broadcast runs on a **different thread
with its own pooled connection**, which a transaction-scoped `SET LOCAL ROLE` would never reach.

**5. A second new file.** The plan's `files_modified` named only `OrderSseGrantRecheckTest`, but its
Task 2 action text requires a Testcontainers arm. That arm cannot live in the fast `test` task, so
`OrderSseGrantRecheckIntegrationTest` was created as a separate file.

**6. `OrderController.java` was listed in `files_modified` but needed no change.** Its
`streamOrderEvents()` is a one-line delegation to `sseService.subscribe()`; the refusal keeps the
existing `IllegalStateException` shape, so there was nothing to change. Not touched, rather than
touched for the sake of the list.

**7. Verify command corrected.** The plan's `cd core-java && ./gradlew ...` cannot work — the
wrapper lives at the repo root. Run as `./gradlew :core-java:test ...` from the root. Also note the
live build directory is `core-java/build-local`, not `core-java/build`.

---

**Total deviations:** 2 auto-fixed (1 × Rule 2, 1 × Rule 3) + 5 recorded substitutions/corrections.
**Impact on plan:** Deviation 1 is the substantive one and is a correctness requirement — the plan
as literally written would have shipped a dead KDS for realm admins. No scope creep: nothing was
added beyond what the plan's own threat model (T-28-14) demands.

## Deferred / Notes for downstream plans

- **FULL integration suite not run here — by design.** This plan touches the auth path
  (`ShopAccessService`), and `trap_scope_gate_integrationtest_regression` says a new auth gate can
  silently break existing integrationTests. The orchestrator scoped that proof to **plan 28-06
  Task 3**, which runs the full suite once for both changes. Only the scopes named in this plan's
  verify blocks were run here. **The full-suite proof for this change lands with 28-06.**
  Risk assessment: the change to `ShopAccessService` is purely additive (a new method), and
  `OrderSseService` is consumed only by `OrderController.streamOrderEvents` and
  `OrderSseFanoutListener`, so the blast radius is small — but it is unproven until 28-06 runs.
- **#627** must appear in `docs/security/PENTEST-TRIAGE.md` (28-05) and in the 28-11 close-out.
- **Test-count bookkeeping (`docs/metrics.json` + prose):** deliberately NOT touched — plan 28-11
  owns the manifest. This plan adds **9** Java `@Test` methods (7 fast + 2 integration).

## Issues Encountered

The integration test's first run failed for a reason unrelated to the behaviour under test: an
`@AfterEach` tried to restore `ALTER ROLE ... SUPERUSER`, which a role that has just been made
NOSUPERUSER cannot do for itself. Both tests reported a `BadSqlGrammarException` that masked the
real assertions. Fixed by seeding once before the downgrade and never restoring; the reason is
written into the class javadoc so the next person does not re-introduce it.

## Next Phase Readiness

- **28-05** can transcribe #281's disposition (FIXED, both 5-minute residuals with sources) and
  #627 straight from this SUMMARY.
- **28-06** must run the FULL `:core-java:test :core-java:integrationTest` to discharge the auth-path
  regression risk for this change as well as its own.
- **28-11** owns `docs/metrics.json` and the prose counts; +9 Java tests from this plan.

## Self-Check: PASSED

Files claimed as created/modified — all present:
- `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java`
- `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java`
- `core-java/src/test/java/uk/jtoye/core/order/OrderSseGrantRecheckTest.java`
- `core-java/src/test/java/uk/jtoye/core/order/OrderSseGrantRecheckIntegrationTest.java`
- `.planning/phases/28-security-triage-the-dev-prod-boundary/28-04-SUMMARY.md`

Commits claimed — both present in `git log`: `8106f45e`, `d6be9e81`.

Issue claimed — **#627** confirmed OPEN via `gh issue view 627`.

---
*Phase: 28-security-triage-the-dev-prod-boundary*
*Completed: 2026-08-10*

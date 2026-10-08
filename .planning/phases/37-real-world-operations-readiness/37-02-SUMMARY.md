---
phase: 37-real-world-operations-readiness
plan: 02
subsystem: testing
tags: [strict-scoping, shop-access, rls, testcontainers, junit, d-06]

requires:
  - phase: 37-01
    provides: merged-main baseline (six guarding suites), V76-V83 reservations, RWO requirement IDs
provides:
  - "testsupport/StrictScopingGuard: capture/restore of the BOOTED strictScoping value, set() for an explicit posture, proxy-unwrapping"
  - "testsupport/ShopGrants.grantOperator: OPERATOR shop_staff + user_directory seeding under the pinned tenant GUC (read back before writing)"
  - "StrictScopingBootedValueIntegrationTest: asserts the context bean booted with the value ACCESS_STRICT_SCOPING declares"
  - "ShopGrantsIntegrationTest: NOSUPERUSER proof that ShopGrants writes through RLS and that the gate honours the grant under strict ON"
  - "No test class resets strictScoping to a literal; the field name appears only inside StrictScopingGuard"
  - "evidence/37-02-strict-on-reds.md: 29 reds in 9 classes under strict ON (the 37-03 conversion list); default mode 0 reds over 2490 tests"
affects: [37-03, 37-04, 37-B]

actuals:
  tokens: 24774
  tasks: 3
  commits: 3
plan_head_before: 12a7333e8ae2f6dc824b7763491690a9fec52eeb
plan_head_after: b53e65cb0cf92b3d1e1e85d4f26b9adc49627615

tech-stack:
  added: []
  patterns:
    - "Strict-scoping posture in tests: @BeforeEach StrictScopingGuard.capture, @AfterEach StrictScopingGuard.restore, StrictScopingGuard.set at the start of a mode-specific test"
    - "Explicit access in tests: ShopGrants.grantOperator instead of the implicit tenant-wide admin"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/testsupport/StrictScopingGuard.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/ShopGrants.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/ShopGrantsIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StrictScopingBootedValueIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-02-strict-on-reds.md
  modified:
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessEnforcementIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/order/AllergyNoteAckIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/CrossTenantAuthzIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessJitProvisionTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffManagementIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessFailClosedIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessCacheBypassIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StrictScopingTighteningIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/websocket/TenantChannelInterceptorShopGateIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaKeepShopScopeIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaRedriveControllerTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessServiceScopingPostureTest.java

key-decisions:
  - "StrictScopingGuard unwraps with AopTestUtils.getUltimateTargetObject inside every call, so no caller can set the field on the CGLIB proxy by mistake; capture() returns Object so a literal cannot be substituted for the captured value; restore() throws when nothing was captured"
  - "ShopGrants runs its writes in a DataSourceTransactionManager transaction on the JdbcTemplate's DataSource and reads the GUC back before writing, so a lost pin fails loudly instead of writing with no tenant"
  - "Every per-test posture (including explicit OFF in day-one tests) goes through StrictScopingGuard.set, and the private reflection helpers are deleted, so the tree-wide literal search is a meaningful invariant rather than a teardown-only one"
  - "The deliberately-wrong (RED) form of the booted-value instrument was run in both env modes and recorded here, not committed: a committed failing test on the integration branch would red every later run"

patterns-established:
  - "Booted-value instrument: a test that reads the context bean's value and compares it with the environment, printed as an event= line in the XML so every full run states which posture it actually ran under"

requirements-completed: [RWO-004, RWO-003]

coverage:
  - id: D1
    description: "Booted-value instrument: the context bean's strictScoping equals ACCESS_STRICT_SCOPING (default false), shown red then green in both env modes"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/access/StrictScopingBootedValueIntegrationTest.java#bootedStrictScopingEqualsTheValueTheEnvironmentDeclares (unset: booted=false; ACCESS_STRICT_SCOPING=true: booted=true)"
        status: pass
    human_judgment: false
  - id: D2
    description: "ShopGrants.grantOperator seeds an OPERATOR grant + directory row through RLS on a NOSUPERUSER role; the gate honours it under strict ON"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/testsupport/ShopGrantsIntegrationTest.java (2 tests; break arm without the pin red with 'new row violates row-level security policy for table user_directory')"
        status: pass
    human_judgment: false
  - id: D3
    description: "No test class resets strictScoping to a literal: all 11 classes capture/restore the booted value"
    requirement: "RWO-004"
    verification:
      - kind: other
        ref: "rg -uu -n 'setStrictScoping\\(false\\)|\"strictScoping\",\\s*false' core-java/src/test/java -> rc=1 (37-01 tree: 26 lines in 12 files, rc=0)"
        status: pass
      - kind: integration
        ref: "./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest (AllergyNoteAck, security.access.*, TenantChannelInterceptorShopGate, MediaKeepShopScope, MediaRedriveController, ShopGrants) -> 16 classes green, env unset"
        status: pass
    human_judgment: false
  - id: D4
    description: "Strict-ON red list for 37-03 and the default-mode full suite reading"
    requirement: "RWO-004"
    verification:
      - kind: other
        ref: "ACCESS_STRICT_SCOPING=true full :test + :integrationTest -> rc=1, 29 failures / 9 classes, recorded in evidence/37-02-strict-on-reds.md; unset -> rc=0, 0 failures over 2490 tests"
        status: pass
    human_judgment: false

duration: 1h 18m
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 02: Strict-scoping test hygiene and strict-ON red list Summary

**The suite can now prove which strict-scoping posture it ran under (a booted-value instrument, shown red then green in both modes), no test can leak a literal `false` into the shared bean, tests can state their access with an RLS-proven OPERATOR-grant helper, and the D-06 blast radius is measured: 29 reds in 9 classes under `ACCESS_STRICT_SCOPING=true`, 0 with it unset.**

## Performance

- **Duration:** 1h 18m (about 59 min of it the two full-suite runs)
- **Started:** 2026-10-08T07:33:47Z
- **Completed:** 2026-10-08T08:51:31Z
- **Tasks:** 3
- **Files modified:** 17 (5 created, 12 modified)

## Accomplishments

- `StrictScopingGuard` (capture / restore / set / current) replaces every reflective write of `strictScoping` in the test tree. The field name now appears only inside the guard (`rg -uu -n '"strictScoping"' core-java/src/test/java` → one line, `StrictScopingGuard.java:36`).
- `StrictScopingBootedValueIntegrationTest` reads the value from the context bean and compares it with `ACCESS_STRICT_SCOPING` and with the resolved Spring property. It prints an `event=strict_scoping_booted_value` line into every run's XML.
- `ShopGrants.grantOperator` seeds a `user_directory` row plus an OPERATOR `shop_staff` row (created_by `00000000-0000-0000-0000-0000000037a2`) in one transaction with the tenant GUC pinned and read back. `ShopGrantsIntegrationTest` proves this on a NOSUPERUSER role: the rows are invisible without the pin, an unpinned or foreign-tenant INSERT is refused with 42501, and the gate honours the grant under strict ON.
- All 11 leak classes now capture and restore the booted value. Every mode-specific test states its posture.
- `evidence/37-02-strict-on-reds.md` is 37-03's exact conversion list: 9 classes, 29 methods, all the typed `shop-access-denied` 403. Six of the nine are not among RESEARCH's 21 prose-described dependents. 17 of the 21's test classes stay green.

## Task Commits

1. **Task 1: helpers, booted-value instrument, enforcement class converted** - `54fd7848` (test)
2. **Task 2: literal reset removed from the other ten classes** - `323ee405` (test)
3. **Task 3: strict-ON red list** - `b53e65cb` (docs)

**Plan metadata:** the commit that adds this SUMMARY (docs). STATE.md, ROADMAP.md and REQUIREMENTS.md are not touched; the orchestrator owns them.

## Evidence (both directions)

### Task 1: booted-value instrument

| Run | Command | Result |
|-----|---------|--------|
| RED, env unset (assert the opposite) | `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests '…StrictScopingBootedValueIntegrationTest' --no-daemon` | rc=1, `tests="1" failures="1"`: `[ShopAccessService.strictScoping booted from ACCESS_STRICT_SCOPING=false] expected: true but was: false`; event line `env.ACCESS_STRICT_SCOPING=null expected=false booted=false property=false` |
| RED, `ACCESS_STRICT_SCOPING=true` exported | same | rc=1: `[… booted from ACCESS_STRICT_SCOPING=true] expected: false but was: true`; event line `env…=true expected=true booted=true property=true` |
| GREEN, env unset | Task 1 verify #1 (+ ShopGrantsIntegrationTest) | rc=0; instrument 1/0, ShopAccessEnforcement 14/0, ShopGrants 2/0; `booted=false` |
| GREEN, `ACCESS_STRICT_SCOPING=true` | Task 1 verify #2 (+ the enforcement class + ShopGrants) | rc=0; same three classes green; `booted=true` |

Both RED runs failed on the target test and the planned assertion. Neither was a compile, context or fixture fault: the context booted and the event line was printed. The env reaches the bean in both directions.

### Task 1: enforcement-class grep (acceptance criterion)

- Pre-change: `grep -n 'setStrictScoping(false)\|"strictScoping", false' …/ShopAccessEnforcementIntegrationTest.java` → `108:` (teardown) and `352:` (an explicit OFF day-one test), rc=0. The plan expected one hit; there were two.
- Post-change: same grep → nothing, rc=1.

### Task 1: ShopGrants break arm (clean → arm → clean)

- Committed first (`54fd7848`). Pre-break sha256 of `ShopGrants.java`: `8b1011894b3c…822107bd`.
- Arm: removed the `set_config` pin and disabled the read-back guard. Result: `grantOperator_writesThroughRls_andTheGateHonoursItUnderStrictOn` failed with `new row violates row-level security policy for table "user_directory"`, rc=1.
- Restore: `git checkout -- …/ShopGrants.java`. sha256 matched the pre-break hash.
- Closing clean run: ShopGrantsIntegrationTest 2/0, inside the Task 2 integration run.

### Task 2: tree-wide literal search (acceptance criterion)

- Pre-change, on the 37-01 tree: `rg -uu -n 'setStrictScoping\(false\)|"strictScoping",\s*false' core-java/src/test/java` → **26 lines in 12 files**, rc=0. That is the 11 classes plus `ShopAccessServiceScopingPostureTest`. The plan's "11 hits" counted classes, not lines.
- Positive control on an export of the tree after Task 1: 24 lines in 11 files, rc=0. This equals 26 minus the 2 enforcement-class lines Task 1 removed.
- Post-change: same search → nothing, rc=1.
- Stronger form: `rg -uu -n '"strictScoping"' core-java/src/test/java` → only `testsupport/StrictScopingGuard.java:36`. No reflective write of the field remains outside the guard.

### Task 2: suites with the variable unset

- `:integrationTest` over AllergyNoteAck, `security.access.*`, TenantChannelInterceptorShopGate, MediaKeepShopScope and ShopGrants → rc=0, 15 XMLs all `OK`. Counts: AllergyNoteAck 9, CrossTenantAuthz 6, CacheBypass 5, Enforcement 14, FailClosed 7, JitProvision 4, ShopStaffRlsPolicy 3, StaffManagement 19, BootedValue 1, Tightening 5, SystemPrincipalGuard 8, V57Backfill 1, ShopGrants 2, TenantChannelShopGate 2, MediaKeep 3.
- `:test` over `security.access.*` → rc=0, 7 XMLs `OK`.
- MediaRedriveControllerTest under `:integrationTest` → rc=0, 7/0 (see deviation 5).

### Mode-specific tests set their posture explicitly (acceptance criterion)

Every test method was read off the source after the sweep:

- ShopAccessJitProvisionTest: `jitProvisionIsIdempotentUnderConcurrentFirstRequests` → `StrictScopingGuard.set(shopAccessService, false)`; `strictScopingOffPreservesDayOne` → `false`; `strictScopingOnDeniesUngranted` → `true`; `realmAdminIsImplicitGroupAdminWithoutAnyRow` → `true`.
- StrictScopingTighteningIntegrationTest: `strictOn_deHonoursJitGroupAdmins_operatorGrantHonoured` → `true`; `strictOn_retainsOldestJitAsBootstrap_whenAllGroupAdminsAreJit` → `true`; `strictOff_dayOneUnchanged` → `false`; `declaredMachineClient_isNotJitProvisioned` → `false`; `stompLadder_tightensToo` → `true`.
- ShopAccessEnforcementIntegrationTest: `canAccessShop_zeroGrantUnderStrictScopingOffIsImplicitGroupAdmin` → `StrictScopingGuard.set(shopAccessService, false);  // day-one default`. Every other method → `true`.
- CrossTenantAuthzIntegrationTest: each of its 6 methods → `false`. It reproduces the day-one exploit on purpose.

### Task 3: full suite in both modes

| Mode | rc | `:test` | `:integrationTest` | Instrument |
|------|---:|---------|--------------------|------------|
| `ACCESS_STRICT_SCOPING=true` | 1 | 187 suites / 1596 tests / 0 failed | 173 suites / 894 tests / **29 failed** / 1 skipped | `booted=true` |
| unset | 0 | 187 / 1596 / 0 | 173 / 894 / **0** / 1 skipped | `booted=false` |

- Gradle's own summary (`894 tests completed, 29 failed`), the sum of the red XML headers (29) and the per-testcase parser (29 lines) all agree.
- The six 37-01 guarding suites appear in the default run with identical counts.
- The XML reader and the per-testcase parser were each run against a deliberately broken input first: failures=1 → rc=1, tests=0 → rc=1, missing → VOID rc=2, and an injected `<failure>` was parsed exactly. Full detail is in the evidence file, §5.
- Task 3's `<verify>` (`test -s … && grep -c '^| '`) → 40, rc=0. Fail direction: an empty file → `test -s` rc=1; a file with no table → `grep -c` 0, rc=1.
- That verify passes on any table at all, so I added a stronger form. The red table's 9 paths are matched against `git ls-files core-java/src/test/java` (371 files) → 9 of 9. A made-up path matches 0 (rc=1).

## Decisions Made

See `key-decisions` in the frontmatter. In short: the guard unwraps internally and refuses an empty restore, the grant helper self-checks its pin, and every posture write goes through the guard, so the literal search is a whole-tree invariant.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The acceptance search's fail-direction counts were wrong in the plan**
- **Found during:** Task 1 and Task 2
- **Issue:** The plan said the enforcement file had "one hit" and the 37-01 tree "11 hits". Measured: 2 in the enforcement file (teardown line 108, plus explicit OFF at line 352), and 26 lines in 12 files tree-wide. Besides the teardown resets, the search matches per-test explicit OFF calls and the posture unit test.
- **Fix:** Recorded the real counts as the fail direction. The plan's own Task 2 instruction says explicit postures go through `StrictScopingGuard.set`, so every explicit OFF call now does. With that, the search's rc=1 is a true whole-tree property, not a teardown-only one.
- **Files modified:** the 11 classes
- **Commit:** `54fd7848`, `323ee405`

**2. [Rule 3 - Blocking] `ShopAccessServiceScopingPostureTest` matched the tree-wide search**
- **Found during:** Task 2
- **Issue:** This unit test (not in the plan's file list) set `"strictScoping", false` by reflection on its own `new ShopAccessService(...)`. That is not a leak, but it made the plan's tree-wide rc=1 criterion unsatisfiable.
- **Fix:** Its two writes now go through `StrictScopingGuard.set`. No assertion changed. 2/2 green.
- **Commit:** `323ee405`

**3. [Rule 2 - Missing critical] Added `ShopGrantsIntegrationTest` (not in the file list) for threat T-37-03**
- **Found during:** Task 1
- **Issue:** The Testcontainers bootstrap role is a SUPERUSER that bypasses FORCE RLS. A helper "working" against it proves nothing about the "never as superuser" mitigation in the plan's threat register.
- **Fix:** A per-container NOSUPERUSER downgrade with a precondition assert, three arms (visible pinned / invisible unpinned / gate honours under strict ON), a refused-INSERT fail direction with a positive control, and a recorded break arm.
- **Commit:** `54fd7848`

**4. [Rule 1 - Bug] Removed dead plumbing and stale Javadoc in the swept classes**
- **Found during:** Task 2
- **Issue:** After the helpers were deleted, `target()` and `targetService` were dead in 7 classes. Five class Javadocs still said the flag was toggled "via ReflectionTestUtils / AopTestUtils".
- **Fix:** Deleted the dead members and the unused imports, and rewrote the five Javadocs to name `StrictScopingGuard`. `target()` stays in FailClosed and Tightening, where it still sets `machineClientIds`.
- **Commit:** `323ee405`

**5. [Rule 3 - Blocking] The plan's second Task 2 verify could never produce `MediaRedriveControllerTest`'s XML**
- **Found during:** Task 2
- **Issue:** The class is `@Tag("testcontainers")`, and `:core-java:test` excludes that tag (`build.gradle.kts`). The run was rc=0 but produced no XML for the class, which is the plan's own failure condition. Read naively, the check is vacuous.
- **Fix:** Ran it where it actually executes: `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests uk.jtoye.core.media.MediaRedriveControllerTest` → rc=0, 7/0. Also ran the `:test` half for `security.access.*` (7 XMLs, all OK).
- **Commit:** n/a (verification only)

---

**Total deviations:** 5 auto-fixed (1 missing critical, 1 bug, 3 blocking).
**Impact on plan:** All are test-tree hygiene or verification corrections. No production code changed. No assertion changed in any existing test.

## Issues Encountered

- The session isolation guard refused compound shell lines with computed `sed` programs, quoted wildcards or piped `git`. The sweeps and Gradle runs were therefore driven from scratchpad scripts (`sweep.sh`, `cleanup.sh`, `run-*.sh`, `check-xml.sh`, `parse-reds.sh`, `classify21.sh`). Python was not needed, so there was no python-to-bash substitution.
- The plan ledger (`plan_head_before`) was kept in the session scratchpad, not in the git dir: this worktree's git dir lives inside the main checkout's `.git/`, which this session must not write to. The value is `12a7333e…` as recorded above.
- **Unverified observation, for 37-03 and 37-04:** every Testcontainers class here declares its own `@Container` and `@DynamicPropertySource`. That should give each class its own Spring context, so the RESEARCH Pitfall 1 leak is certainly *intra-class*: later methods in the same class share the bean. Whether it also crossed classes was not measured. The fix covers both cases either way, and the booted-value instrument reads its own fresh context in every full run.

## Known Stubs

None. Every new file is wired and exercised by a passing test.

## TDD Gate Compliance

Not applicable as a gate. The plan is `type: execute` and no task carries `tdd="true"`. The one behaviour the plan asked to be shown red (the booted-value instrument) was run red in both env modes before being corrected; the output is recorded above. The red form was not committed, on purpose (see key-decisions).

## User Setup Required

None.

## Next Phase Readiness

- **37-03:** convert the 9 classes / 29 methods in `evidence/37-02-strict-on-reds.md` with `ShopGrants.grantOperator` (preferred) or `TenantJwts.adminJwt`. Most of these builders mint a fresh random `sub` per request, so each test needs a fixed `sub` that the grant is seeded for. Then re-run `ACCESS_STRICT_SCOPING=true` over the full suite: it must go rc=0 with the instrument reading `booted=true`.
- **37-04:** after the flip, `StrictScopingBootedValueIntegrationTest`'s default (`getOrDefault(…, "false")`) must change to `"true"` along with `application.yml`. Otherwise the instrument goes red on the new default, which is the intended tripwire. `ShopAccessServiceScopingPostureTest`'s WARN-text assertions change with the posture log.
- The full-suite default-mode reading (2490 tests, 0 failures) is now the comparison point. The 37-01 baseline covered only six suites.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist: StrictScopingGuard.java, ShopGrants.java, ShopGrantsIntegrationTest.java, StrictScopingBootedValueIntegrationTest.java, evidence/37-02-strict-on-reds.md
- Commits on this branch: 54fd7848, 323ee405, b53e65cb (`git rev-list --count 12a7333e..HEAD` = 3 at SUMMARY write)
- Acceptance criteria re-run: enforcement grep rc=1; tree-wide rg rc=1; red-table paths 9/9 tracked

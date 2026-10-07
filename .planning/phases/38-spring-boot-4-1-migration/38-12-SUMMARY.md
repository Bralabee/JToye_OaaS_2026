---
phase: 38-spring-boot-4-1-migration
plan: 12
subsystem: build
tags: [spring-boot-4, jackson-3, jackson-2-bridge-removal, contract-test, production-classpath, deliberate-jackson2]
status: complete

requires:
  - phase: 38-03
    provides: "the INTERIM-JACKSON2-BRIDGE line (spring-boot-jackson2) that this plan removes"
  - phase: 38-05
    provides: "the jackson3-defaults decision, locked by Jackson3WireContractTest (the third clause of BOOT4-04)"
  - phase: 38-06..38-10
    provides: "every main-code Jackson-2 user moved to Boot's Jackson-3 JsonMapper"
  - phase: 38-19
    provides: "every test injector moved off the bridge bean, and the closed 3-file DELIBERATE-JACKSON2-LIST with the widened scan pattern"
  - phase: 36-06
    provides: "-Djtoye.productionRuntimeClasspath on tasks.test (the resolved runtimeClasspath the contract test reads)"
provides:
  - "core-java/build.gradle.kts without spring-boot-jackson2: no Jackson-2 auto-configuration module on the production runtime classpath (forbidden=0)"
  - "JacksonLineContractTest (unit, 5 tests): production classpath free of spring-boot-jackson2 / classic / gRPC jars; main code and config free of Jackson 2; test-side Jackson-2 users == the closed DELIBERATE-JACKSON2 list, scanned with the package-qualified Spring-adapter pattern"
  - "Boot4ModuleLivenessIntegrationTest.jackson3MapperExistsAndNoJackson2MapperBean: Boot's Jackson-3 JsonMapper exists, no Jackson-2 ObjectMapper bean exists, and the only Jackson-2-typed beans are Spring Data's two inert modules (closed set)"
  - "End-state suites on the bridge-free classpath: unit 1490/0/1 skipped, integration 769/1 (OpenApiSnapshotTest, 38-14)"
affects: [38-13, 38-14, 38-16, 38-17, 38-18]

actuals:
  tokens: 11747    # chars/4 over the added lines of 31f8a36e..fbaf3d44 (46989 chars, 4 files)
  tasks: 2
  commits: 4       # MEASURED: git rev-list --count 31f8a36e..HEAD at SUMMARY write
plan_head_before: 31f8a36e4921de6e7385809129b889fe2ecb3a7c
plan_head_after: fbaf3d4462edbcf0823b98d5030492e26334b6f0

tech-stack:
  added: []
  removed: ["org.springframework.boot:spring-boot-jackson2 (the interim bridge)"]
  patterns:
    - "A source-scan contract test that names no forbidden package or adapter by its qualified name, so it can never match its own scan (checked: own pattern over the file rc=1)"
    - "Context-level absence check by type hierarchy (getType(name, false), nothing initialised) with a positive control on the same walk, and the forbidden package derived from a legal one so the test file stays off the list"
    - "Closed sets that shrink with the tree: the test allowlist and the inert-bean set both fail when an entry disappears, not only when one appears"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/boot4/JacksonLineContractTest.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-12-jackson-end-state.txt
  modified:
    - core-java/build.gradle.kts
    - core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java

key-decisions:
  - "38-12: JacksonLineContractTest scans main AND test code with one pattern: any com.fasterxml.jackson package except annotation, or a package-qualified Spring Jackson2*/MappingJackson2*/GenericJackson2* adapter. It is wider than the plan's databind/core/datatype/module, and it carries 38-19's adapter half, so a test reaching Jackson 2 through a Spring adapter fails (arm D: the plan's narrow grep printed nothing for the same file)"
  - "38-12: Spring Data registers its Jackson-2 GeoModule and PageModule beans because Jackson 2 stays on the classpath transitively (springdoc and others). They are inert: no Jackson-2 mapper exists, and their Jackson-3 counterparts are live on Boot's mapper (measured). They are held as a closed set in the context check, not suppressed: suppressing them would be a main-code change with no behaviour to gain"
  - "38-12: no residual. No main or test class needed the bridge, so no main-code change was made"

patterns-established:
  - "The Jackson line is now enforced by a permanent unit test: any re-added spring-boot-jackson2 / classic / gRPC jar, main-code Jackson-2 use, converter switch, jackson2 key, or unlisted test-side Jackson-2 use fails by name"

requirements-completed: [BOOT4-04]  # plan declares [BOOT4-04, BOOT4-02]; requirements.ready-ids: BOOT4-04 ready, BOOT4-02 blocked (shared with 38-13, not yet summarised)

coverage:
  - id: D1
    description: "The INTERIM-JACKSON2-BRIDGE line is gone; no spring-boot-jackson2, classic or gRPC jar is on the production runtime classpath, and the contract test proves it from that classpath"
    requirement: "BOOT4-02"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/JacksonLineContractTest.java#productionClasspathCarriesNoForbiddenModule (RED on spring-boot-jackson2-4.1.1.jar before removal; green after; arm A red again)"
        status: pass
      - kind: other
        ref: "./gradlew -q :core-java:dependencies --configuration runtimeClasspath -> forbidden=0 (PLAN_BASE capture: 1)"
        status: pass
    human_judgment: false
  - id: D2
    description: "No Jackson-2 ObjectMapper bean exists in the application context; Boot's Jackson-3 JsonMapper does"
    requirement: "BOOT4-02"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java#jackson3MapperExistsAndNoJackson2MapperBean (RED with the bridge: jackson2ObjectMapper and 3 bridge modules; arm A red in its final form)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Main code and configuration are on Jackson 3 only (annotations legal), guarded permanently"
    requirement: "BOOT4-04"
    verification:
      - kind: unit
        ref: "JacksonLineContractTest#mainCodeHasNoJackson2Use (arm B red naming SyncItem.java:4) and #configurationSetsNoJackson2Switch (arm C red naming application.yml) and #configCheckCanFail"
        status: pass
    human_judgment: false
  - id: D4
    description: "Test-side Jackson-2 users equal the closed DELIBERATE-JACKSON2 list, each with its reason"
    requirement: "BOOT4-04"
    verification:
      - kind: unit
        ref: "JacksonLineContractTest#testCodeJackson2UsersAreExactlyTheAllowlist (arm D adapter import, arm D2 FQCN adapter use, arm E missing reason: each red)"
        status: pass
      - kind: other
        ref: "evidence verify 3: widened git grep -P == the constant (3 = 3); at c2c64e78 the same scan gives 41 files"
        status: pass
    human_judgment: false
  - id: D5
    description: "Both full suites are green on the bridge-free classpath except OpenApiSnapshotTest (38-14), read from fresh XML; no context failed for want of a Jackson-2 bean"
    requirement: "BOOT4-02"
    verification:
      - kind: integration
        ref: "./gradlew :core-java:cleanTest :core-java:cleanIntegrationTest :core-java:test :core-java:integrationTest --continue: unit 1490/0/1 skipped, integration 769/1 failure (OpenApiSnapshotTest)/1 skipped; missing_jackson_bean=0"
        status: pass
    human_judgment: false

duration: 47min
completed: 2026-10-05
---

# Phase 38 Plan 12: The Jackson line at its end state Summary

**The INTERIM-JACKSON2-BRIDGE line is gone. No Jackson-2 auto-configuration module, classic starter or gRPC module is on the production runtime classpath, and no Jackson-2 `ObjectMapper` bean exists in the application context. Both suites are green on that classpath apart from the 38-14-owned `OpenApiSnapshotTest`. A permanent `JacksonLineContractTest` now guards the end state. It reads the real production classpath, main code, every application config file, and the test tree, which it scans with 38-19's Spring-adapter pattern. Six bracketed arms each turned it red, naming the offender. No main or test class needed the bridge, so there was no residual to fix.**

## Performance

- **Duration:** about 47 min (about 28 of them in the full-suite run)
- **Started:** 2026-10-05T13:59:18Z
- **Completed:** 2026-10-05T14:46Z
- **Tasks:** 2 of 2
- **Files:** 4. Two test files: one created, one extended. One build line was deleted, and one evidence file was added. No main code changed.

## Accomplishments

- **RED came first, on the classpath alone.** With the bridge still committed, `JacksonLineContractTest` ran 5 tests with 1 failure: `productionClasspathCarriesNoForbiddenModule`, on `spring-boot-jackson2-4.1.1.jar`. The main, config and test scans were already green, so 38-06..38-10 and 38-19 left no residual. `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK`.
- **GREEN after the bridge was removed.** Deleting the line made `compileJava` pass (main code needs nothing from the module) and the contract test pass 5/5. The runtime classpath check went from `forbidden=1` to `forbidden=0`, and transitive Jackson-2 databind stays, as expected.
- **Context-level proof.** No static scan can show the plan's first truth, so a sixth liveness test was added to `Boot4ModuleLivenessIntegrationTest`. With the bridge present it was RED and listed `jackson2ObjectMapper` plus three bridge modules. After removal it found two Jackson-2-typed beans that were still left: Spring Data's `GeoModule` and `PageModule`. They are classpath-conditional and inert, and their Jackson-3 counterparts are live (measured). The test now asserts that no Jackson-2 `ObjectMapper` exists and that the Jackson-2-typed beans are exactly that closed pair.
- **Arms.** Each arm was bracketed clean, armed, clean; each restore was sha256-verified against the HEAD blob; the closing run was green.
  - A: the bridge re-added. Classpath red, and the context check red on `jackson2ObjectMapper`.
  - B: a main-code databind import. Red, naming `SyncItem.java:4`.
  - C: the converter switch in `application.yml`. Red, naming it.
  - D: a Spring `Jackson2JsonMessageConverter` import in an unlisted test. Red. The plan's narrow grep printed nothing for the same file.
  - D2: an FQCN `MappingJackson2HttpMessageConverter` use. Red.
  - E: a listed file stripped of its reason line. Red.
- **End-state suites.** The run was `cleanTest cleanIntegrationTest test integrationTest --continue` from fresh XML:
  - unit: 1490 tests, 0 failures, 1 skipped;
  - integration: 769 tests, 1 failure (`OpenApiSnapshotTest`), 1 skipped;
  - `missing_jackson_bean=0`.
  - The increase is +5 unit tests and +1 integration test, exactly the tests this plan added. All the other figures equal the wave-4 gate and 38-19's 1485/0/1.
- **For 38-14:** the served OpenAPI spec is byte-identical with the bridge on and off (sha256 `60c52aa1…` both ways), so removing the bridge does not change the snapshot diff 38-14 must regenerate.

## Task Commits

1. **Task 1 (tracer): RED, GREEN and arms**
   - `738cd0ea` test(38-12): JacksonLineContractTest, RED on the INTERIM-JACKSON2-BRIDGE jar
   - `a2b52b22` feat(38-12): remove the INTERIM-JACKSON2-BRIDGE; no Jackson-2 module or mapper bean left
   - `e2941b6e` docs(38-12): contract-test arms A-E red with the named offender, restores sha256-verified, closing run green
   - Tracer gate: interactive, end-of-phase mode, automated-only verify. It was re-run green after the commits, then expanded.
2. **Task 2: end-state suites and the allowlist read**
   - `fbaf3d44` docs(38-12): end-state suites bridge-free, no residual

**Plan metadata:** the docs commit that adds this SUMMARY.

## Files Created/Modified

- `core-java/build.gradle.kts`: the `spring-boot-jackson2` line, the one marked INTERIM-JACKSON2-BRIDGE, is deleted (2 lines removed). The `productionRuntimeClasspath` property was already wired by 36-06, so this plan needed no build change for it.
- `core-java/src/test/java/uk/jtoye/core/boot4/JacksonLineContractTest.java` (new, 5 unit tests): checks the classpath, main code and config, runs a config fail-direction control, and enforces the closed test allowlist (`DELIBERATE_JACKSON2_LIST`, verbatim from 38-19).
- `core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java`: adds `jackson3MapperExistsAndNoJackson2MapperBean` and a type-hierarchy bean walker.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-12-jackson-end-state.txt`: baselines, RED/GREEN, the Spring Data finding, the arms, the suites, verify 1-3, the OpenAPI comparison and the allowlist with its reasons.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| Contract test (verify 1) | 5/5 green, fresh XML, test task executed | RED 5/1 on the bridge; arms A-E each red on one test |
| runtimeClasspath forbidden (verify 2 of Task 1) | `forbidden=0`, rc=0 | `forbidden=1` on the PLAN_BASE capture |
| AC1: bridge marker absent | rc=1 | the same grep on HEAD 738cd0ea: `build.gradle.kts:283`, rc=0 |
| AC2: no databind/core/datatype import in main | rc=1; positive control 6 annotation files | 35 lines at c7d1a473 (before 38-06) |
| Context check | 6/6 green | RED with the bridge (4 bridge beans plus Spring Data's pair); arm A red on `jackson2ObjectMapper` |
| `missing_jackson_bean` (verify 2 of Task 2) | 0 | 1 on 38-19's tracer failure text |
| Verify 3: test list == constant | widened scan == constant (3 = 3); narrow grep 2 files, both listed and marked | 41 files at c2c64e78 |
| No golden or snapshot change | no names printed | 38 names over 38-01's capture commit (rc is 0 in both directions; the name count is the check) |
| Contract test cannot match itself | its own pattern over its file rc=1 | the same grep over AmqpJackson2CompatibilityTest rc=0 |

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- One widened pattern scans main and test code.
- Spring Data's inert Jackson-2 modules are a closed set, not suppressed.
- There was no residual.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] Context-level check for the plan's first truth**
- **Found during:** Task 1.
- **Issue:** the truth "no Jackson-2 ObjectMapper bean exists in the application context" had no check. The contract test is a unit test and cannot boot a context.
- **Fix:** `Boot4ModuleLivenessIntegrationTest.jackson3MapperExistsAndNoJackson2MapperBean` was added. This file is outside the plan's `files_modified`.
- **Verification:** RED with the bridge, GREEN without it, arm A red.
- **Committed in:** `738cd0ea` (RED form) and `a2b52b22` (final form).

**2. [Rule 1 - Bug in the new check] The first form of the context check was too broad**
- **Found during:** the GREEN run of Task 1.
- **Issue:** bridge-off, it still listed Spring Data's `GeoModule` and `PageModule`. These are Jackson-2 `Module` beans that Spring Data registers whenever Jackson 2 is on the classpath. They are not mappers.
- **Fix:** the check now asserts no Jackson-2 `ObjectMapper` (the plan's truth exactly), plus an exact closed set of Jackson-2-typed beans. That kept every bridge bean visible: arm A was red on `jackson2ObjectMapper`.
- **Verification:** a throwaway probe measured the live Jackson-3 counterparts (`jackson3GeoModule`, `jackson3pageModule`); its restore was sha256-verified. The refined check is green bridge-off.
- **Committed in:** `a2b52b22`.

**3. [Strengthened] The scan pattern is wider than the plan's**
- **Main scan:** every `com.fasterxml.jackson` package except `annotation`, instead of databind/core/datatype/module. It also includes the package-qualified Spring Jackson-2 adapter half, which the plan applied to tests only through the orchestrator's 38-19 carry.
- **Classpath list:** the forbidden jar prefixes add `spring-boot-starter-jackson2` and `spring-boot-starter-grpc` to the plan's four literals.
- **Config scan:** keys are compared in relaxed form (camelCase and underscores), and it covers all four `preferred-json-mapper` keys in Boot 4.1.1 plus every key with a `jackson2` segment.
- **Positive control:** `spring-boot-4.x.jar` is matched by pattern rather than as the literal `4.1.1`, so a 4.x patch bump does not break the Jackson contract.

**4. [Strengthened] Arms D, D2 and E were added to the plan's A-C**
- D and D2 discharge the orchestrator's 38-19 obligation: a re-introduced adapter use fails the widened scan, while the narrow grep cannot see it.

**5. [Plan defect, recorded] Two criteria cannot fail as written**
- **The no-golden criterion's `rc=0`:** `git diff --name-only` exits 0 whether or not it prints names. The name count was used instead and shown failing (38 names over 38-01's capture).
- **Verify 3's narrow grep:** it cannot print `AmqpJackson2CompatibilityTest`, the same defect 38-19 recorded. The list was also read with the widened pattern, which equals the constant.

---

**Total deviations:** 5: 1 missing-critical, 1 bug in a new check, 2 strengthened, 1 plan defect.
**Impact on plan:** no main code changed. No golden file, resource or snapshot was touched. No test was disabled or weakened, and no compose service was touched. The one extra file is a test that makes a plan truth checkable.

## Issues Encountered

None blocking. The full integration run takes about 28 min, so it ran in the background with a bounded wait loop.

## Known Stubs

None. The scan for TODO/FIXME/placeholder/coming soon/not available over the added lines returned rc=1, and the positive control returned rc=0.

## Threat Flags

None. No new surface was added: one dependency line was removed, and only test code was added.
- T-38-32: mitigated by JacksonLineContractTest and the context check; arms A-E, D2 are red.
- T-38-33: mitigated. The same test forbids the classic and gRPC jars on the production classpath.
- T-38-SC: no package was installed, and one dependency line was removed.

## User Setup Required

None. No compose service was started, stopped or rebuilt. Testcontainers ran its own throwaway containers.

## Next Phase Readiness

- **38-13:** the production classpath now carries no Jackson-2 module (`forbidden=0`). `JacksonLineContractTest` is the permanent BOOT4-02 jar check, and BOOT4-02 stays open for 38-13.
- **38-14:** OpenApiSnapshotTest is still the only red, as expected. The bridge removal does not change the served spec (byte-identical both ways). The test is still on the DELIBERATE-JACKSON2 list. If 38-14 moves its normalizer to Jackson 3, the contract test turns red until that path comes off `DELIBERATE_JACKSON2_LIST`, which is the intended behaviour.
- **38-16 (metrics):** 1 new Java test file (JacksonLineContractTest, 5 tests) and 1 new test method in an existing file (Boot4ModuleLivenessIntegrationTest, now 6). Unit is 1490 and integration 769. ADR-0006 may say that the bridge is removed and that Spring Data's two Jackson-2 modules remain registered and inert.
- **38-17:** the rebuilt jar should carry no `BOOT-INF/lib/spring-boot-jackson2-*.jar`. That is a by-content check on the runtime artifact.

## Self-Check: PASSED

- FOUND: `JacksonLineContractTest.java`, `Boot4ModuleLivenessIntegrationTest.java` (modified), `build.gradle.kts` (bridge absent) and the evidence file.
- FOUND commits: 738cd0ea, a2b52b22, e2941b6e, fbaf3d44. `git rev-list --count 31f8a36e..HEAD` = 4.
- All acceptance criteria and the plan verification were re-run in both directions (the table above, and the evidence).

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

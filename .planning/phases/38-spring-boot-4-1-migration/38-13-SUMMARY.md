---
phase: 38-spring-boot-4-1-migration
plan: 13
subsystem: testing
tags: [spring-boot-4.1.1, boot4-migration, auto-configuration, census, condition-evaluation-report, classic-starter, zipkin, testcontainers]
status: complete

requires:
  - phase: 38-03
    provides: "the D-02 explicit starter set in core-java/build.gradle.kts"
  - phase: 38-04
    provides: "Boot4ModuleLivenessIntegrationTest (context shape reused; re-run green in the closing run)"
  - phase: 38-12
    provides: "the end-state classpath: no spring-boot-jackson2, classic or gRPC jar (the census runs on it)"
provides:
  - "AutoConfigurationCensusIntegrationTest: the activated auto-configuration set from ConditionEvaluationReport, written to $JTOYE_CENSUS_OUT when set, and 21 must-have Boot 4.1.1 auto-configurations asserted by name"
  - "Census evidence: explicit build 130 activated, classic swap 133; classic-only 3, all INTENDED (Gson, an empty Spring Integration metrics placeholder, Jackson 2); explicit-only 0; no starter added"
  - "Zipkin arm: removing spring-boot-starter-zipkin removes BraveAutoConfiguration, ZipkinAutoConfiguration and ZipkinWithBraveTracingAutoConfiguration, adds NoopTracerAutoConfiguration, and the must-have test names all three"
affects: [38-14, 38-16, 38-17, 38-18]

actuals:
  tokens: 13341    # chars/4 over the added lines of 26c5c5ff..e1748dbb (53363 chars, 4 files)
  tasks: 2
  commits: 3       # MEASURED: git rev-list --count 26c5c5ff..HEAD at SUMMARY write
plan_head_before: 26c5c5ff877fccb1e68ab65313eda0eb24ab0d24
plan_head_after: e1748dbbbfe6333820b3322d9a6206fe5ca901d9

tech-stack:
  added: []
  patterns:
    - "Auto-configuration census: full-match ConditionEvaluationReport sources plus unconditional classes, normalised to the declaring class and intersected with ImportCandidates.load(AutoConfiguration.class)"
    - "A census context must undo the test profile's capability switches (excluded auto-configurations, @Profile(\"!test\") @EnableCaching), or a module is invisible in both arms; an exclusions-empty assertion guards that"
    - "Classic comparison = the spike's route: ADD the classic pair to the explicit build (classic carries modules, not libraries), so the comparison isolates modules"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/boot4/AutoConfigurationCensusIntegrationTest.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-13-census.txt
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-13-census-explicit.txt
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-13-census-classic.txt
  modified: []

key-decisions:
  - "38-13: the classic swap ADDS spring-boot-starter-classic/-test-classic (plus the spike's gRPC exclusions) and keeps the explicit starters, as the spike did. The plan's literal 'replace' was measured: the classic POM carries Boot modules only, so tomcat, lettuce, spring-rabbit, jakarta.mail, hibernate-validator and spring-websocket all drop to 0 and the app cannot compile"
  - "38-13: all 3 classic-only auto-configurations are INTENDED (GsonAutoConfiguration: Gson only transitive via stripe-java, unused in main; IntegrationMetricsAutoConfiguration: an empty unconditional class, Spring Integration not on any classpath; Jackson2AutoConfiguration: removed by D-01). No starter or module added; the build file is unchanged"
  - "38-13: the census context clears the test profile's Data Redis exclusion and stands in for CacheConfig's @Profile(\"!test\") @EnableCaching, so spring-boot-data-redis and spring-boot-cache are measurable; it asserts that no auto-configuration is excluded"

patterns-established:
  - "Must-have auto-configuration list: a future Boot upgrade that drops one of the 21 fails AutoConfigurationCensusIntegrationTest by capability and class name"

requirements-completed: [BOOT4-02]

coverage:
  - id: D1
    description: "The census harness measures the activated auto-configuration set and permanently asserts 21 must-have auto-configurations by name"
    requirement: "BOOT4-02"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/AutoConfigurationCensusIntegrationTest.java#mustHaveAutoConfigurationsActivate (green on 38d4d688 and in the closing run; red on the invented constant, RED_EVIDENCE_OK; red on the zipkin arm naming 3 classes)"
        status: pass
      - kind: integration
        ref: "AutoConfigurationCensusIntegrationTest#exclusionsAreCleared"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every auto-configuration the classic route activates and the explicit build does not is classified; none is a missing module"
    requirement: "BOOT4-02"
    verification:
      - kind: other
        ref: "evidence/38-13-census.txt section 3: comm over 38-13-census-explicit.txt (130) and 38-13-census-classic.txt (133): classic-only 3 INTENDED, explicit-only 0; awk unresolved_missing=0 (injected line -> 1)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The census can detect a missing module: the zipkin arm drops the three tracing auto-configurations and the must-have test names them"
    requirement: "BOOT4-02"
    verification:
      - kind: integration
        ref: "evidence/38-13-census.txt section 4: zipkin arm rc=1 naming BraveAutoConfiguration, ZipkinAutoConfiguration, ZipkinWithBraveTracingAutoConfiguration; restore sha256 == HEAD blob; closing census 2/0 and liveness 6/0, census identical to the committed list"
        status: pass
    human_judgment: false
  - id: D4
    description: "No classic starter survives in the committed build file"
    requirement: "BOOT4-02"
    verification:
      - kind: other
        ref: "git grep -n -e 'spring-boot-starter-classic' -e 'spring-boot-starter-test-classic' -- core-java/build.gradle.kts -> rc=1 (armed: the two swap lines, rc=0)"
        status: pass
    human_judgment: false

duration: 14min
completed: 2026-10-05
---

# Phase 38 Plan 13: Auto-configuration Census, Explicit vs Classic Summary

**D-02 is now measured rather than assumed. On the end-state commit, the explicit starters activate 130 auto-configurations and a throwaway classic swap of the same commit activates 133. The 3 classic-only ones are all intended absences: Gson (only transitive through stripe-java), an empty Spring Integration metrics placeholder, and the Jackson-2 mapper D-01 removed. No capability is silently lost and no starter was added. A permanent census test asserts 21 must-have auto-configurations by name. With `spring-boot-starter-zipkin` removed it fails naming the Brave, Zipkin and Zipkin-with-Brave auto-configurations, and the census shows a no-op tracer taking their place.**

## Performance

- **Duration:** about 14 min. Each census run took about 40 s.
- **Started:** 2026-10-05T14:47:18Z
- **Completed:** 2026-10-05T15:01:29Z
- **Tasks:** 2 of 2
- **Files:** 4 created: 1 test class and 3 evidence files. No main code or build file changed (build file sha256 `56ab71db…4e1c` at the start and the end).

## Accomplishments

- **The census harness (Task 1, tracer).**
  - `AutoConfigurationCensusIntegrationTest` builds the activated set from `ConditionEvaluationReport`: full-match sources plus unconditional classes, normalised to the declaring class and kept only if listed in some `AutoConfiguration.imports`.
  - It asserts 21 must-have Boot 4.1.1 auto-configurations by capability and class name: the plan's 17 plus four companions (DispatcherServlet, Data JPA repositories, Zipkin-with-Brave, RestTemplate).
  - It writes the set to `$JTOYE_CENSUS_OUT` when that variable is set.
- **The fail direction came first.** An invented must-have failed and named only that class, so all 21 real constants were already active (`RED_EVIDENCE_OK`). The verify on the committed tree is green from fresh XML, with 130 entries.
- **Census blind spots closed.** The `test` profile excludes the Data Redis auto-configurations, and `CacheConfig` (the only `@EnableCaching`) is `@Profile("!test")`. Either one would hide its module in both arms.
  - The census context clears the exclusion and stands in for the `@EnableCaching`.
  - A second test asserts that nothing is excluded.
- **Classic swap and classified diff (Task 2).**
  - Classic adds 57 Boot modules (65 → 122). The census rose 130 → 133; there are 0 explicit-only entries.
  - Each classic-only entry is INTENDED. The evidence names its technology and gives `git grep` proof (with a positive control) that main code does not use it.
  - The residual check found that only 2 of the 57 classic-only modules have their library on the classpath at all (gson, and h2console, whose H2 is test-only and property-gated). So no deploy profile can activate anything the census missed.
- **The zipkin arm went red, and the closing run was green.**
  - The three tracing auto-configurations disappear and `NoopTracerAutoConfiguration` appears: that is the silent failure itself.
  - The restore was sha256-verified.
  - The closing run: census 2/0, liveness 6/0. The closing census equals the committed list line for line.

## Task Commits

1. **Task 1: census harness and must-have assertions (tracer)**
   - `38d4d688` test(38-13): auto-configuration census harness with permanent must-have assertions
   - `44554152` docs(38-13): explicit-build census (130 auto-configurations), invented-name arm red, verify green
   - Tracer gate: interactive, end-of-phase mode, automated-only verify. The verify was re-run on the committed tree and was green, so the plan expanded to Task 2.
2. **Task 2: classic swap, classified diff, zipkin arm**
   - `e1748dbb` docs(38-13): classic-swap census and classified diff (3 classic-only, all intended), zipkin arm red

**Plan metadata:** the docs commit that adds this SUMMARY.

## Files Created/Modified

- `core-java/src/test/java/uk/jtoye/core/boot4/AutoConfigurationCensusIntegrationTest.java`: the census writer (`JTOYE_CENSUS_OUT`), 21 must-have constants, and the exclusions-cleared guard.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-13-census-explicit.txt`: 130 activated auto-configurations on 38d4d688, with a SHA header.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-13-census-classic.txt`: 133 under the throwaway classic swap, with a header giving the swap's sha256 and the restore.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-13-census.txt`: the RED and GREEN record, the literal-swap probe, the module diff, the classified diff with counts, the residual analysis, the zipkin arm and the criteria in both directions.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| must-have assertion | green, 21 constants, fresh XML | invented constant: red naming only it (RED_EVIDENCE_OK; wrong target gives INVALID_RED); zipkin arm: red naming 3 classes |
| census verify (entries) | entries=130 | /dev/null gives entries=0; a missing file gives `awk: cannot open`, rc=2 |
| exclusions cleared | `exclusions: []` in both arms | not run separately. Without the clear, DataRedisAutoConfiguration could not be in the set, and it is a must-have that is green |
| unresolved_missing | 0 | injected unresolved line gives 1; with ADDED gives 0; missing file gives rc=2 |
| no classic starter committed | `git grep` rc=1 | swap lines re-applied: both printed, rc=0; restored by sha256 |
| restore of each arm | sha256 `56ab71db…4e1c` == HEAD blob, three times | the closing census equals the committed list (130), shown by content |
| no main/build change | `git diff --name-only 26c5c5ff..HEAD -- core-java/src/main core-java/build.gradle.kts` prints nothing | 38-12's range c7d1a473..26c5c5ff gives 26 names |
| pin guard | PIN OK in the project root | FATAL root-mismatch from the dispatching repo, rc=1 |

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- The classic swap adds the classic pair rather than replacing the starters, as the spike did. The literal replacement was measured not to compile.
- All three classic-only entries are intended, so nothing was added.
- The census context undoes the test profile's two capability switches.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking, plan defect] The classic swap adds the classic pair instead of replacing the starters**
- **Found during:** Task 2.
- **Issue:** `spring-boot-starter-classic` is spring-boot-starter plus `spring-boot-autoconfigure-classic-modules`: Boot's modules without the technology libraries. Replacing the explicit starters with it was probed with `dependencies --configuration runtimeClasspath`. It gives tomcat-embed-core, lettuce-core, spring-rabbit, jakarta.mail, hibernate-validator and spring-websocket all at 0, against 3/1/1/2/1/1 on the committed tree. Main code imports those types, so the build cannot compile.
- **Fix:** I used the spike's own route from 38-SPIKE.patch: ADD `spring-boot-starter-classic` and `spring-boot-starter-test-classic`, keep every other line, and add the spike's two gRPC exclusions. The classic build is then a module superset (65 → 122 modules, 0 explicit-only), which is exactly what the comparison has to isolate.
- **Verification:** evidence section 2a-2c. The restore was sha256-verified, and `git grep` for the classic starters gives rc=1.
- **Committed in:** e1748dbb (evidence only; the swap itself was never committed).

**2. [Rule 2 - Census correctness] The census context undoes the test profile's capability switches**
- **Found during:** Task 1.
- **Issue:** `application-test.yml` excludes DataRedisAutoConfiguration and DataRedisRepositoriesAutoConfiguration. `CacheConfig`, the only `@EnableCaching`, is `@Profile("!test")`. In both arms that makes `spring-boot-data-redis` and `spring-boot-cache` invisible, so the plan's Data Redis and cache must-haves could not be met, and a missing module there could not be detected.
- **Fix:** the test registers `spring.autoconfigure.exclude=""` and `spring.cache.type=redis`, and adds a nested `@TestConfiguration @EnableCaching` stand-in. `exclusionsAreCleared()` asserts that nothing is excluded. Nothing connects to Redis.
- **Verification:** DataRedisAutoConfiguration and CacheAutoConfiguration are in both census lists, and `exclusions: []` was printed in every run.
- **Committed in:** 38d4d688.

**3. [Rule 1 - Vacuous arm caught] First zipkin arm VOID**
- **Found during:** Task 2, zipkin arm.
- **Issue:** deleting the `spring-boot-starter-zipkin` line breaks `compileTestJava`, because 38-11's `RenamedConfigKeysBindingTest` imports `ZipkinProperties`. No test ran and no XML was written, so it is not a red.
- **Fix:** the arm instead replaces the line with `testCompileOnly(...)`. The application loses the starter on both runtime classpaths (`spring-boot-zipkin` 0 and `spring-boot-micrometer-tracing-brave` 0 on testRuntimeClasspath), while that test still compiles. The arm then went red as the plan expects.
- **Verification:** evidence section 4.
- **Committed in:** e1748dbb.

**4. [Strengthened] 21 must-have constants instead of 17**
- Added four companions: DispatcherServlet, Data JPA repositories, Zipkin-with-Brave span export and the RestTemplate builder. That is why the zipkin arm names three classes, not two.

---

**Total deviations:** 4: 1 plan defect worked around, 1 correctness addition to the census context, 1 vacuous arm caught and redone, 1 strengthening.
**Impact on plan:** none on scope. No main code or build file changed, and no compose service was touched.

## TDD Gate Compliance

- **RED:** the census test with an invented must-have failed on the target test. `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK (target_test_failed)`; aimed at the passing test, the same XML gives `INVALID_RED (no_target_test_failure)`.
- **GREEN:** there is no `feat(38-13)` commit, and that is recorded rather than faked. The plan changes no main or build code, because the census found nothing missing, so the GREEN step was the removal of the invented constant before the test commit (`test(38-13)` 38d4d688). The real list passed on the committed tree.
- **REFACTOR:** none.

## Issues Encountered

None open.

## Known Stubs

None. A scan of the test file for TODO/FIXME/placeholder/coming soon/not available returned rc=1.

## Threat Flags

None. Only a test class and evidence were added.
- T-38-34 is mitigated: classic vs explicit census, every classic-only entry classified, the zipkin arm red, and the permanent must-have assertions.
- T-38-SC: no package was installed and no starter was added, because nothing was classified as missing.

## User Setup Required

None. No compose service was started, stopped or rebuilt; Testcontainers ran its own Postgres.

## Next Phase Readiness

- **38-14:** unaffected; OpenApiSnapshotTest is still its red.
- **38-16 (metrics):** +1 Java test file (AutoConfigurationCensusIntegrationTest, 2 `@Test` methods, `@Tag("testcontainers")`). The integration count rises by 2. ADR-0006 can cite the census: explicit 130 vs classic 133, with 3 intended classic-only entries.
- **38-17:** the census is a test-profile context. The live tracing check on the rebuilt stack is still 38-17's.
- **38-18:** the PR body can state that D-02 was checked against a classic baseline: no capability lost and no starter added.

## Self-Check: PASSED

- FOUND: AutoConfigurationCensusIntegrationTest.java (contains `ConditionEvaluationReport`), 38-13-census.txt, 38-13-census-explicit.txt, 38-13-census-classic.txt.
- FOUND commits: 38d4d688, 44554152, e1748dbb. `git rev-list --count 26c5c5ff..HEAD` = 3.
- Acceptance re-run on the final tree: 21 constants; `unresolved_missing=0`; the classic-starter `git grep` gives rc=1; the build file sha256 equals HEAD.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

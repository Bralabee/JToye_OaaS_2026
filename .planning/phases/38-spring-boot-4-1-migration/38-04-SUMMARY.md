---
phase: 38-spring-boot-4-1-migration
plan: 04
subsystem: testing
tags: [spring-boot-4.1.1, boot4-migration, liveness, flyway, rls, micrometer-tracing, zipkin, prometheus, spring-statemachine, spring-security-access, jdeps, testcontainers]
status: complete

requires:
  - phase: 38-03
    provides: "core-java on Boot 4.1.1 with the D-02 explicit starters, spring-security-access, and the tracing/metrics test modules"
  - phase: 38-01
    provides: "GoldenFixturesIntegrityTest (5 unit tests counted in the suite ledger)"
  - phase: 38-02
    provides: "RequestBodyConstraintEnforcementTest, JsonbColumnsReadBackIntegrationTest and the *_boot35Baseline methods (counted in the ledger)"
provides:
  - "Suite ledger on Boot 4.1.1: unit 1337/2, integration 750/5, from fresh build-local XML; every red is a named expected red with its owner"
  - "Boot4ModuleLivenessIntegrationTest: 5 probes (Flyway applied set, Brave Tracer + Zipkin sender, PrometheusMeterRegistry, the three HTTP client builders, both state-machine factories)"
  - "StatemachineSecurityAccessTest: the 16 jdeps-derived security.access classes load from the production runtime classpath"
  - "Every arm recorded both ways: Flyway, tracing (B and B2), restclient, spring-security-access, fail-closed, and the two state-machine arms"
affects: [38-06, 38-07, 38-10, 38-14, 38-16]

actuals:
  tokens: 10652
  tasks: 3
  commits: 5
plan_head_before: c7d1a473461918eb792eb0d7f1f833eba351c9e0
plan_head_after: 7595c530bd627bdc1e5406e8537a3178dbd35c3e

tech-stack:
  added: []
  patterns:
    - "A liveness probe checks the IMPLEMENTATION class, not only that a bean exists: a no-op Tracer bean survives the loss of the Brave module"
    - "A probe reads the capability's own record with plain SQL (flyway_schema_history), so a missing module reads as 0 and not as a missing bean"
    - "Library-reference checks come from jdeps on the production classpath, written into the test as constants with the command in the Javadoc"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/boot4/StatemachineSecurityAccessTest.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-04-suite-and-liveness.txt
  modified: []

key-decisions:
  - "No Boot-4 fallout needed fixing: category (b) and (c) are empty, and 1330+7 / 745+5 shows no test was lost. This plan changes no main code and no build file."
  - "The Tracer probe asserts the class name contains 'Brave'. Arm B showed that without the Brave module the context still holds a no-op Tracer$1 bean, so a presence-only probe would have passed."
  - "Added sub-arm B2 (spring-boot-zipkin only) because in arm B the Brave assertion fails first and the sender assertion is never reached."
  - "Arm C fails at context startup (NoClassDefFoundError RestTemplateBuilder via SecurityConfig), not at the probe. That is recorded as the plan allowed. The probe stays as the guard for the day main code stops injecting the builders."
  - "The security.access constants are the 16 DISTINCT jdeps targets (37 reference lines). The spike's '24' could not be reproduced because its command was not recorded."
  - "Task 2 and Task 3 TDD: RED is not applicable, and this is recorded rather than faked. Both tests pin existing behaviour and the plan forbids main changes. Falsifiability comes from the committed-first break arms."

patterns-established:
  - "Liveness probe per Boot-4 module, each assertion naming the module it guards"
  - "Production-classpath loader tests fail closed and carry a positive control before any absence is trusted"

requirements-completed: [BOOT4-02, BOOT4-03, BOOT4-10]

coverage:
  - id: D1
    description: "Both suites run in full on Boot 4.1.1 and every red has a named owner (category (c) empty)"
    requirement: BOOT4-02
    verification:
      - kind: other
        ref: "./gradlew :core-java:cleanTest :core-java:cleanIntegrationTest :core-java:test :core-java:integrationTest --continue -> unit 1337/2/0/1, IT 750/5/0/1; evidence section 1"
        status: pass
    human_judgment: false
  - id: D2
    description: "Flyway liveness: applied set == V*.sql set (67); the spring-boot-flyway arm gives 0 applied and RlsContractTest 4/7 red"
    requirement: BOOT4-03
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java#flywayAppliedEveryVersionedMigration"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "Module liveness for tracing, Zipkin export, Prometheus, RestClient/RestTemplate/WebClient builders and both state-machine factories; arms B, B2, C red"
    requirement: BOOT4-02
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java"
        status: pass
    human_judgment: false
  - id: D4
    description: "spring-statemachine 4.0.2 on Framework 7: 28 machine tests + 4 transition-driving integration classes green, two break arms red, linkage scan 0, security.access references resolve on the production classpath"
    requirement: BOOT4-10
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/StatemachineSecurityAccessTest.java"
        status: pass
      - kind: unit
        ref: "./gradlew :core-java:test --tests '*StateMachine*' -> 28/28"
        status: pass
      - kind: integration
        ref: "OrderControllerIntegrationTest 7, MoneyPathExecutionIntegrationTest 4, RefundWebhookHandlingIntegrationTest 7, VendorOnboardingEndToEndIntegrationTest 2"
        status: pass
    human_judgment: false

duration: 39 min
completed: 2026-10-05
---

# Phase 38 Plan 04: Boot 4.1.1 Suites, Module Liveness and the State Machine on Framework 7 Summary

**Both suites ran in full on Boot 4.1.1: unit 1337/2 and integration 750/5. All 7 reds are named expected reds owned by 38-06, 38-07 and 38-14, and no test was lost against the 1330/745 baseline. Two permanent tests now turn D-02's "no silently missing module" and D-03's "the state machine still works on Framework 7" into checks that fail. The arms show they do: Flyway gives 0 of 67 and RlsContractTest 4/7, a no-op Tracer is caught, restclient fails the context, and the access jar fails on its 9 classes.**

## Performance

- **Duration:** about 39 min
- **Started:** 2026-10-05T00:49:44Z
- **Completed:** 2026-10-05T01:29:18Z
- **Tasks:** 3 of 3
- **Files:** 3 created (2 test classes, 1 evidence file); no main code or build file changed

## Accomplishments

- **Suite ledger (Task 1, tracer).** The counts below come from fresh `build-local` XML: 170 and 151 files, all newer than the run start. A marker touched after the run gives 0, which shows the freshness filter can fail.
  - unit: 1337 tests, 2 failures, 0 errors, 1 skip. That is 1330 plus 7 added by 38-01 and 38-02.
  - integration: 750 tests, 5 failures, 0 errors, 1 skip. That is 745 plus 5 added by 38-02.
  - All 67 migrations were applied in 144 contexts.
  - Reds:
    - `ProblemDetailAuthenticationEntryPointTest` (unit) and `UnauthenticatedProblemDetailIntegrationTest` (4 methods) go to 38-06.
    - `KeycloakAdminClientTest` goes to 38-07.
    - `OpenApiSnapshotTest` goes to 38-14.
  - `JsonbColumnsReadBackIntegrationTest` is green.
- **Boot4ModuleLivenessIntegrationTest (Task 2).** It has 5 probes, uses `@AutoConfigureTracing` and `@AutoConfigureMetrics`, and runs on Testcontainers Postgres. Each assertion names the module it guards.
- **StatemachineSecurityAccessTest (Task 3).** jdeps over the production classpath finds 16 distinct `org.springframework.security.access` classes that statemachine-core references. All 16 load from a loader built only from `-Djtoye.productionRuntimeClasspath`. The test fails closed without that property, and `StateMachine` is loaded first as a positive control.
- **State machine on Framework 7.** 28 of 28 machine tests and the 4 transition-driving integration classes pass. The library logged the guarded APPROVE and GO_LIVE transitions. The linkage-error scan finds 0 here and 2 in the access-arm XML, so the scan can fire.

## Task Commits

1. **Task 1: Tracer: full suites on Boot 4.1.1 with a named expected-red ledger.** `d276c553` (docs). The tracer gate re-ran the verify at that commit: rc=1, 1338 tests (1337 plus 1 scaffold test), 2 failures, both in the ledger. It then expanded to Tasks 2 and 3.
2. **Task 2: Module liveness probes:** test `ffa1babc` (committed before the arms), evidence `5dd7e358` (docs).
3. **Task 3: The state machine on Framework 7:** test `340ea66f` (committed before the arms), evidence `7595c530` (docs).

**Plan metadata:** the docs commit that follows this SUMMARY.

## Files Created/Modified

- `core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java`: hard liveness probes per Boot-4 module.
- `core-java/src/test/java/uk/jtoye/core/boot4/StatemachineSecurityAccessTest.java`: a production-classpath proof that the security.access references resolve.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-04-suite-and-liveness.txt`: the ledger, every arm in both directions, and the raw jdeps output.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| unit/IT counts read from fresh XML | 170 / 151 fresh | marker touched after the run: 0 / 0 |
| Flyway applied == V*.sql | 67 == 67 | arm A: `applied 0 of the 67`, RlsContractTest 4/7 (non-vacuity controls fired) |
| Brave Tracer | BraveTracer | arm B: `io.micrometer.tracing.Tracer$1` (no-op) does not contain "Brave" |
| Zipkin BytesMessageSender | present | arm B2: null, `spring-boot-zipkin is missing` |
| RestClient/RestTemplate builders | present | arm C: context fails, `NoClassDefFoundError: org/springframework/boot/restclient/RestTemplateBuilder` |
| security.access classes load | 16/16 | access arm: the 9 access-jar classes, ClassNotFoundException each |
| fail closed without the property | n/a | noprop arm: `Expecting not blank but was: null` |
| order happy path | 28/28 | SM-A: `expected: <COMPLETED> but was: <CANCELLED>` |
| APPROVE guard rejects PENDING gate | 28/28 | SM-B: `Expected InvalidStateTransitionException ... nothing was thrown` |
| linkage-error scan | 0 (unit and IT, log and XML) | 2 on the access-arm XML |
| no main/build change (`git diff --name-only c7d1a473..HEAD -- core-java/src/main core-java/build.gradle.kts`) | prints nothing, rc=0 | over 38-03's range `ddeb0ea6..c7d1a473` it prints build.gradle.kts and the main files |
| pin guard | PIN OK in the project root | FATAL root-mismatch from the dispatching repo, rc=1 |

Every arm was run after its test was committed. Each restore used `git checkout` and was verified by sha256 against the committed blob (`d9cb6f42…833d` for the build file, `726a8c74…` and `8d9c5748…` for the two main sources). Each arm set ended with a closing clean run.

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- No fallout fixes were needed.
- The Tracer probe checks the Brave class name, because a no-op Tracer survives.
- B2 was added so the sender assertion has its own fail direction.
- Arm C's context failure is recorded as the plan allowed.
- The constants are the distinct jdeps targets.
- TDD RED is recorded as not applicable, and the arms provide the falsification.

## Deviations from Plan

### Auto-added (Rule 2 - falsifiability of criteria)

**1. [Rule 2] Sub-arm B2 (spring-boot-zipkin only)**
- **Found during:** Task 2, arm B.
- **Issue:** In arm B the Brave-class assertion fails first, so the Zipkin-sender assertion in the same method was never shown to fail.
- **Fix:** Added arm B2, which excludes only spring-boot-zipkin. The sender assertion goes red: `no zipkin2.reporter.BytesMessageSender bean`.
- **Committed in:** 5dd7e358 (evidence).

**2. [Rule 2] Fail-closed arm "noprop" for StatemachineSecurityAccessTest**
- **Found during:** Task 3.
- **Issue:** The behaviour requires the test to fail closed without the property, but nothing showed that branch firing.
- **Fix:** Ran an arm that clears `tasks.test` jvmArgumentProviders. The result was `initializationError: Expecting not blank but was: null`. The restore was verified by sha256.
- **Committed in:** 7595c530 (evidence).

### Criterion interpretation (recorded, not silently substituted)

**3. [Rule 1] "jdeps reference count equals the number of constants"**
- **Issue:** jdeps prints 37 reference LINES that name 16 distinct classes. A constant per line would duplicate names, so the literal reading cannot hold on a correct test.
- **Fix:** The constants are the 16 distinct targets. Both numbers and the full raw output are in evidence section 3a. The spike's "24" is noted as not reproducible.
- **Committed in:** 340ea66f, 7595c530.

**4. [Rule 3 - Blocking, environment] Python helper under the base-python guard**
- **Issue:** The machine's `block-base-python` hook refused a scratch XML-parsing helper run with `/usr/bin/python3`.
- **Fix:** Ran it inside the existing `engineering-doctrine` conda env, as the executor contract prescribes.
- **Committed in:** none (tooling only).

**Total deviations:** 4. Two added arms, one criterion interpretation, and one environment fix. **Impact:** none on scope; all are stricter than the plan.

## TDD Gate Compliance

- **Task 2 (`ffa1babc`) and Task 3 (`340ea66f`): RED is not applicable, and this is recorded rather than faked.**
  - Both tests pin behaviour that already exists on the 38-03 tree, and the plan forbids main or build changes. Their first run was therefore green, which is the "unexpected GREEN" case. It was investigated: the probed modules and the access jar are present.
  - Falsifiability comes from the arms, all run after the commit and each failing on its own named assertion: A, B, B2, C, access, noprop, SM-A and SM-B.
  - There is no `feat` commit because there is no implementation to write.
- **Task 1** is a tracer that measures the tree. Its gate re-ran the verify after commit, and that run is recorded.

## Issues Encountered

None open. The 7 red methods belong to other plans:
- 38-06: 1 unit and 4 integration methods.
- 38-07: 1 unit method.
- 38-14: 1 integration method.

Notes for 38-06: the two `*_boot35Baseline` methods now see 200 from `/.well-known/oauth-protected-resource`. Security 7.1 serves that path, which is D-05's premise measured on this tree.

## Known Stubs

None.

## Threat Flags

None. This plan adds only tests and evidence:
- T-38-09 (Flyway/RLS) is mitigated by the Flyway probe and arm A.
- T-38-10 (state machine on Framework 7) is mitigated by the machine tests, the integration classes, SM-A/SM-B, the linkage scan and StatemachineSecurityAccessTest.
- T-38-SC: no package was installed, and the build file is unchanged at plan end.

## Next Phase Readiness

Ready for 38-05 (the next wave-3 plan, run in sequence).

The remaining reds are a closed list:
- 38-06: ProblemDetailAuthenticationEntryPointTest and UnauthenticatedProblemDetailIntegrationTest.
- 38-07: KeycloakAdminClientTest.
- 38-14: OpenApiSnapshotTest.

38-16 records the D-03 risk (no Framework-7 release; an EnumMap replacement stays a separate, non-blocking decision) in ADR-0006 and CONCERNS.md.

## Self-Check: PASSED

- FOUND: Boot4ModuleLivenessIntegrationTest.java (contains `AutoConfigureTracing`), StatemachineSecurityAccessTest.java (contains `jtoye.productionRuntimeClasspath`), evidence/38-04-suite-and-liveness.txt
- FOUND commits: d276c553, ffa1babc, 5dd7e358, 340ea66f, 7595c530
- `commits:` measured with `git rev-list --count c7d1a473..HEAD` = 5
- No main/build change: `git diff --name-only c7d1a473..HEAD -- core-java/src/main core-java/build.gradle.kts` printed nothing, rc=0
- Stub scan over both new test files: no TODO/FIXME/placeholder (grep rc=1)

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

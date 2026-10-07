---
phase: 38-spring-boot-4-1-migration
plan: 03
subsystem: infra
tags: [spring-boot-4.1.1, boot4-migration, gradle, explicit-starters, flyway, rls, cve-pins, jackson3, tomcat-11, testcontainers]
status: complete

requires:
  - phase: 38-01
    provides: "jackson2-golden fixtures and GoldenFixturesIntegrityTest (compiled and moved mechanically here, not edited)"
  - phase: 38-02
    provides: "RequestBodyConstraintEnforcementTest, JsonbColumnsReadBackIntegrationTest, the *_boot35Baseline methods (compiled on Boot 4 here, not edited)"
provides:
  - "core-java on the Spring Boot 4.1.1 plugin (both build files) with the D-02 explicit starter set and per-technology test starters"
  - "CVE pins on the Boot-4 lines: no netty pin, tomcat 11.0.26, amqp-client 5.34.0, jackson-2-bom.version 2.22.3, jackson-bom.version 3.1.7"
  - "One marked INTERIM-JACKSON2-BRIDGE line (spring-boot-jackson2), removed by 38-12"
  - "Six main-code package/API moves; mechanical Boot-4 test-API moves across 99 test files"
  - "Tracer proof: full Boot 4.1.1 context on Testcontainers Postgres applies 67 migrations; RlsContractTest 7/7; Flyway-module arm 4/7 red"
  - "Evidence file with the RED compile, tracer, arms, dependencyInsight, census, the unit suite, the rewritten-file list and the 'Interim red (expected; owner 38-16)' section"
affects: [38-04, 38-06, 38-07, 38-08, 38-10, 38-11, 38-12, 38-14, 38-15, 38-16, 38-19]

actuals:
  tokens: 44324
  tasks: 2
  commits: 2
plan_head_before: ddeb0ea6d233d551b40d65552cabca56c9149867
plan_head_after: 8c9ca8e7fb64377747d8aa4b2253a193ee8776cf

tech-stack:
  added:
    - "org.springframework.boot plugin 4.1.1 (was 3.5.16)"
    - "spring-boot-starter-webmvc, -flyway, -aspectj, -security-oauth2-resource-server, -restclient, -webclient, -zipkin"
    - "org.springframework.security:spring-security-access (BOM-managed 7.1.1)"
    - "spring-retry 2.0.13 (explicit), springdoc-openapi-starter-webmvc-ui 3.1.1, resilience4j-spring-boot4 2.4.0"
    - "test: spring-boot-starter-webmvc-test, -security-test, -data-jpa-test, spring-boot-configuration-metadata, spring-boot-micrometer-tracing-test, spring-boot-micrometer-metrics-test"
    - "interim: spring-boot-jackson2 (INTERIM-JACKSON2-BRIDGE)"
  patterns:
    - "Explicit per-module Boot-4 starters; a missing module is proven by a liveness arm, not assumed"
    - "Version-key re-keying: a Boot-3 BOM key that changes meaning under Boot 4 is re-keyed, not deleted, and its old value is kept as history"

key-files:
  created:
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-03-boot4-tracer.txt
  modified:
    - build.gradle.kts
    - core-java/build.gradle.kts
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
    - core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java
    - core-java/src/main/java/uk/jtoye/core/security/CustomerJwtVerifier.java
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
    - core-java/src/test/java/ (99 files, mechanical moves; listed in the evidence file, section 8)

key-decisions:
  - "The RED compile has a sharper cause than 'starters moved': the Boot-3 pin jackson-bom.version=2.21.7 re-points the JACKSON 3 BOM under Boot 4 to a nonexistent tools.jackson:jackson-bom:2.21.7, the BOM import fails and every managed version vanishes. Recorded in the pin comment as the measured reason for the re-key (T-38-08 observed live)."
  - "spring-boot-starter-webclient is declared ALONGSIDE spring-boot-starter-webflux (D-02 lists webflux literally; its rule is 'declare each module the app uses')."
  - "spring-security-access is verified with the rendering-independent form spring-security-access(:| -> )7.1.1 plus dependencyInsight '7.1.1 (selected by rule)'; the plan's literal 'spring-security-access:7.1.1' cannot match a version-less direct dependency in Gradle's dependencies output."
  - "check-dependency-horizons.sh needs python3 with PyYAML, which this host's python shim only provides inside a conda env; the gate was run under an existing env (engineering-doctrine, PyYAML 6.0.3) with no gate variable set. The first, env-less run VOIDed on the shim alone and is recorded."
  - "The netty pin's long rationale was replaced by a short Boot-4 note pointing to git history, as the plan directed; Tomcat, amqp-client and Jackson comments keep their history and append the Boot-4 reasoning."

patterns-established:
  - "A tracer for a framework move is a migration-applying RLS test on a full context, not a compile"
  - "Combined counts across files can hide a dead module: count per file for the context under test"

requirements-completed: [BOOT4-01, BOOT4-02, BOOT4-03, BOOT4-10, BOOT4-12]

coverage:
  - id: D1
    description: "core-java resolves and compiles main and test on the Boot 4.1.1 plugin in both build files"
    requirement: BOOT4-01
    verification:
      - kind: other
        ref: "./gradlew :core-java:compileJava :core-java:compileTestJava --no-daemon (rc=0; RED rc=1 recorded in evidence section 1)"
        status: pass
      - kind: other
        ref: "dependencyInsight org.springframework.boot:spring-boot runtimeClasspath -> 4.1.1 (selected by rule)"
        status: pass
    human_judgment: false
  - id: D2
    description: "D-02 explicit starters: no classic aggregate starter, no gRPC/classic auto-config module on runtimeClasspath, flyway/zipkin/restclient/webclient modules present, spring-security-access 7.1.1"
    requirement: BOOT4-02
    verification:
      - kind: other
        ref: "evidence section 5: grpc_classic=0 (arm A with the classic starter: 4); modules=4 (arm B without starter-zipkin: 3); access 7.1.1 = 1 (line removed: 0)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Full Boot 4.1.1 context boots on Testcontainers Postgres, Flyway applies all 67 migrations, RlsContractTest 7/7, FreshChainMigrationIntegrationTest 5/5"
    requirement: BOOT4-03
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/FreshChainMigrationIntegrationTest.java"
        status: pass
      - kind: other
        ref: "evidence section 3: Flyway-module arm -> RlsContractTest 4/7 red, its migration count 0; restored by sha256; closing clean 7/7"
        status: pass
    human_judgment: false
  - id: D4
    description: "Mechanical Boot-4 test-API moves; no @MockBean/@SpyBean left; no test disabled, deleted or weakened"
    requirement: BOOT4-10
    verification:
      - kind: other
        ref: "git grep @MockBean/@SpyBean -- core-java/src/test -> rc=1 (98 hits at PLAN_BASE)"
        status: pass
      - kind: unit
        ref: "./gradlew :core-java:test -> 1337 tests, 2 failures, both named expected reds (KeycloakAdminClientTest -> 38-07, ProblemDetailAuthenticationEntryPointTest -> 38-06)"
        status: pass
    human_judgment: false
  - id: D5
    description: "CVE pins on the Boot-4 lines from the first Boot-4 commit (netty pin removed, tomcat 11.0.26, amqp-client 5.34.0, jackson-2-bom 2.22.3, jackson-bom 3.1.7)"
    requirement: BOOT4-12
    verification:
      - kind: other
        ref: "evidence sections 5-6: git grep per pin (now 1 / base 0; netty now rc=1 / base 1 line); resolved tomcat 11.0.26, netty 4.2.17.Final, amqp-client 5.34.0, databind 2.22.3, tools.jackson databind 3.1.7"
        status: pass
    human_judgment: false
  - id: D6
    description: "Both version gates see the half-done state and their interim red is recorded and owned by 38-16"
    requirement: BOOT4-01
    verification:
      - kind: other
        ref: "evidence 'Interim red (expected; owner 38-16)': horizons rc=2 h5_spring_boot=2 h2_fetch_void=0; doc-versions rc=2 VOID Resilience4j; both rc=0 at PLAN_BASE"
        status: pass
    human_judgment: false

duration: 14 min
completed: 2026-10-05
---

# Phase 38 Plan 03: Boot 4.1.1 Tracer on Explicit Starters Summary

**core-java now builds and boots on Spring Boot 4.1.1 using explicit per-module starters. A full Boot 4.1.1 / Spring 7.0.9 context on Testcontainers Postgres applies all 67 Flyway migrations, and RlsContractTest passes 7/7. Removing the Flyway module turns it 4/7 red. The CVE pins are re-keyed onto the Boot-4 lines in this same first Boot-4 commit.**

## Performance

- **Duration:** about 14 min
- **Started:** 2026-10-05T00:33:33Z
- **Completed:** 2026-10-05T00:47:00Z
- **Tasks:** 2 of 2
- **Files modified:** 108 (2 build files, 6 main, 99 test, 1 evidence)

## Accomplishments

- **Plugin:** Boot 4.1.1 in both `build.gradle.kts:2` and `core-java/build.gradle.kts:2`.
- **Starters (D-02):** webmvc, data-jpa, flyway, aspectj, security, security-oauth2-resource-server, validation, actuator, data-redis, cache, amqp, websocket, mail, webflux, plus restclient, webclient and zipkin. The test slices are per technology. No classic starter is declared, and no gRPC or classic auto-config module is on the runtime classpath.
- **CVE pins:**
  - The netty pin is deleted; it resolves to 4.2.17.Final.
  - Tomcat is pinned to 11.0.26 (Boot manages 11.0.24).
  - amqp-client stays at 5.34.0 (Boot manages 5.30.0).
  - The Jackson 2 floor is re-keyed to `jackson-2-bom.version` 2.22.3.
  - A Jackson 3 floor is added: `jackson-bom.version` 3.1.7.
- **Interim bridge:** one `INTERIM-JACKSON2-BRIDGE` line keeps the Jackson-2 ObjectMapper bean until 38-12 removes it. The deprecated converter switch is not set.
- **Main code:**
  - Five package moves: PropertyReferenceException, SimpleRabbitListenerContainerFactoryConfigurer, MessageListenerContainer, and RestTemplateBuilder in two files.
  - The Lettuce 7 `setDefaultTimeout` call is removed. The same `redisCommandTimeout` Duration still applies through `RedisURI.withTimeout`.
- **Tests:** mechanical moves across 99 test files: MockitoBean/MockitoSpyBean, `webmvc.test.autoconfigure`, `resttestclient` plus `@AutoConfigureTestRestTemplate`, DataJpaTest, the Flyway/DataSource auto-config, PropertyPath/TypeInformation, and the 3-argument NoResourceFoundException.
- **Unit suite:** 1337 tests, 2 failures. Both are the spike's named behaviour changes, each owned by a later plan.
- **Version gates:** both are recorded red as expected (horizons rc=2 on the two spring-boot sites, doc-versions rc=2 on Resilience4j). Both are green at PLAN_BASE. 38-16 Task 1 clears them.

## Task Commits

1. **Task 1: Tracer: Boot 4.1.1 on explicit starters boots a full context and applies all 67 migrations** - `f2a3717f` (feat)
2. **Task 2: Record the two version gates' expected interim red, owned and cleared by 38-16** - `8c9ca8e7` (docs)

**Plan metadata:** recorded in the final docs commit after this SUMMARY.

## Files Created/Modified

- `build.gradle.kts`: root plugin declaration moved to 4.1.1 (`apply false`).
- `core-java/build.gradle.kts`: the plugin, the D-02 starters, the Boot-4 CVE pins with their reasoning, and the interim Jackson-2 line.
- Six main-code files: package moves; the RateLimitConfig timeout call and its javadoc.
- `core-java/src/test/java/**`: 99 files changed by the mechanical moves (listed in evidence section 8).
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-03-boot4-tracer.txt`: every measurement and arm.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| compile | rc=0 | plugin-only bump rc=1 (BOM import failed: `tools.jackson:jackson-bom:2.21.7` missing) |
| tracer integrationTest | rc=0, 7/7 + 5/5 | Flyway-module exclusion: rc=1, RlsContractTest 4/7, FreshChain 2/5 |
| RlsContractTest migrations applied | 1 | 0 under the Flyway arm |
| grpc/classic modules on runtimeClasspath | 0 | 4 with the classic starter added (arm A) |
| flyway/zipkin/restclient/webclient modules | 4 | 3 with starter-zipkin removed (arm B) |
| classic names in the build files (git grep) | rc=1 | rc=0 under arm A |
| spring-security-access 7.1.1 (replacement form) | 1 | 0 with the line removed |
| netty pin | rc=1 | 1 line at PLAN_BASE |
| tomcat / jackson-2-bom / jackson-bom / INTERIM lines | 1 each | 0 each at PLAN_BASE |
| @MockBean/@SpyBean in tests | rc=1 | 98 hits at PLAN_BASE |
| horizons gate | rc=2, 2 H-5 spring-boot | rc=0 at PLAN_BASE |
| doc-versions gate | rc=2, VOID Resilience4j | rc=0 at PLAN_BASE (151 claims) |
| docs untouched (git diff) | rc=0, 0 lines | positive control prints `core-java/build.gradle.kts` |

All arms ran on the uncommitted tree from a scratch backup of `core-java/build.gradle.kts`. Each restore was verified with `sha256sum -c` and followed by a closing clean run.

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- The RED compile was traced to the Jackson BOM key change, and that measurement is written into the pin comment.
- webclient is declared alongside webflux.
- The spring-security-access check uses a form that matches however Gradle renders the dependency.
- The horizons gate was run inside a conda env, the machine's sanctioned route to a python with PyYAML.

## Deviations from Plan

### Criterion corrections (recorded, not silently substituted)

**1. [Rule 1 - Bug] The spring-security-access acceptance criterion could not pass on a correct tree**
- **Found during:** Task 1 acceptance.
- **Issue:** The plan's `awk '/spring-security-access:7\.1\.1/'` printed 0 on the correct tree. Gradle renders a version-less direct dependency as `spring-security-access -> 7.1.1`.
- **Fix:** Replaced with `awk '/spring-security-access(:| -> )7\.1\.1/'`, which prints 1 (and 0 with that line removed). It is backed by dependencyInsight `spring-security-access:7.1.1 (selected by rule)`. Both forms are recorded in evidence section 5.
- **Committed in:** f2a3717f (evidence).

**2. [Rule 1 - Bug] The plan's applied67 awk does not fail under a Flyway-module break**
- **Found during:** Task 1, Flyway arm.
- **Issue:** The awk sums both XML files. FreshChainMigrationIntegrationTest drives Flyway through its own API, so under the arm the sum is still 1 and `fails_when: applied67=0` does not fire.
- **Fix:** The plan's form was kept and reported (4 clean). The strictly stronger per-file count for RlsContractTest (the full context) was added: 1 when clean, 0 under the arm. The integrationTest rc and RlsContractTest's 4 red cases also catch the arm.
- **Committed in:** f2a3717f (evidence section 3).

**3. [Rule 3 - Blocking] check-dependency-horizons.sh VOIDed on this host's python shim**
- **Found during:** Task 2.
- **Issue:** The gate runs `python3 -c 'import yaml…'`. The machine's shim refuses a bare python3 outside a conda env, so the first run was a VOID that said nothing about the spring-boot row.
- **Fix:** Re-ran it under an existing conda env that has PyYAML. No gate variable was set and no input was edited. The VOIDed run is recorded.
- **Committed in:** 8c9ca8e7.

**4. [Rule 2 - Missing critical] The RateLimitConfig javadoc still cited the removed API**
- **Found during:** Task 1.
- **Issue:** The javadoc still pointed `{@link RedisClient#setDefaultTimeout(Duration)}` at a method Lettuce 7 removed.
- **Fix:** Reworded it to name the `withTimeout` mechanism that still applies, and added the one-line code comment the plan asked for.
- **Committed in:** f2a3717f.

**Total deviations:** 4, all auto-fixed (two criterion corrections, one blocking environment issue, one stale doc link). **Impact:** none on scope. Every plan-literal check is reported next to its stronger replacement.

## Issues Encountered

None open. The unit run has 2 expected reds, each with a named owner:
- `KeycloakAdminClientTest` goes to 38-07.
- `ProblemDetailAuthenticationEntryPointTest` goes to 38-06.

The full integration suite, including the `*_boot35Baseline` methods, OpenApiSnapshotTest and JsonbColumnsReadBackIntegrationTest, is 38-04's run and was not executed here.

## Known Stubs

None. The interim `spring-boot-jackson2` line is a dated bridge with a named removal plan (38-12), not a stub.

## Threat Flags

None beyond the plan's threat model:
- T-38-05 (Flyway/RLS) is mitigated by the tracer and its arm.
- T-38-06 (the classic starter's gRPC auto-config) is mitigated by arm A plus the census.
- T-38-07 (Tomcat CVEs) is mitigated by the 11.0.26 pin.
- T-38-08 (Jackson BOM key semantics) was observed live in the RED run and mitigated by the re-key.
- T-38-48 (the gates' interim red) is mitigated by the recorded red with its owner.

## Next Phase Readiness

Ready for 38-04: the full suites, the auto-configuration census, and the permanent Flyway/tracing liveness arms. Later plans own the rest:
- The Jackson-2 serializers and the 25 test injectors: 38-06..38-10 and 38-19.
- The five Boot-3 redis exclude strings: 38-11.
- Re-proving the CVE pins and the Trivy scan: 38-15.
- Turning both version gates green: 38-16.

## Self-Check: PASSED

- FOUND: .planning/phases/38-spring-boot-4-1-migration/evidence/38-03-boot4-tracer.txt
- FOUND: f2a3717f, 8c9ca8e7 (git log)
- Re-ran Task 1 verify on the committed tree: compile rc=0; tracer rc=0 (7/7, 5/5), applied67=4
- Re-ran Task 2 acceptance after its commit: docs-untouched git diff rc=0 with 0 lines; the evidence holds the heading once and names 38-16 Task 1

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

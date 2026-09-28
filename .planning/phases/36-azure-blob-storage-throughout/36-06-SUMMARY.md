---
phase: 36-azure-blob-storage-throughout
plan: 06
subsystem: storage
tags: [azure-blob, azurite, fail-fast, startup-probe, workload-identity, "#626", D-02, D-08]
status: complete

requires:
  - phase: 36-01
    provides: StorageProperties/StorageConfig/BlobObjectStore/AzureBlobObjectStore, StorageConfigurationException, AzuriteTestSupport, the jna-group exclusion on azure-identity
  - phase: 36-02
    provides: compose Azurite with STORAGE_CREATE_CONTAINERS=true
  - phase: 36-03
    provides: hybrid runtime Azurite and application-local.yml create-containers (D-08)
provides:
  - StorageProperties.Blob.validateShape(Function<String,String> env) with internal enum AuthMode, run first inside the Blob client bean factory
  - emulator-only connection-string rule in every profile and both auth modes (no real account key configurable anywhere)
  - StorageStartupValidator, the boot-time #626 container probe (ApplicationStartedEvent), gated by the literal storage.blob.validate-on-startup
  - A1 proof on the SHIPPED classpath (production runtimeClasspath loader) plus a linkage-error control arm
  - test-context opt-outs with reasons (application-test.yml, 2 dev-profile ITs, the ownership IT)
affects: [36-07, 36-09, 36-11, 36-14, 36-15, 36-17, phase-29-handoff]

actuals:
  tokens: 20525        # chars/4 over git diff 4ba8a1f6..HEAD (code commits), before this SUMMARY
  tasks: 3
  commits: 7           # MEASURED: git rev-list --count 4ba8a1f6..HEAD, before the SUMMARY commit
plan_head_before: 4ba8a1f648020e35f6d3c9c5ae49ca33fe485951

tech-stack:
  added: []
  patterns:
    - "Shape rules run inside the @Bean factory through one env lookup, so a misconfiguration fails before any network call and the tests drive workload identity without the real environment"
    - "A boot probe that must precede ApplicationRunners listens on ApplicationStartedEvent, not ApplicationReadyEvent"
    - "A classpath-absence claim about what SHIPS is tested in a loader built from the production runtimeClasspath, never with Class.forName in the test JVM"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/storage/StorageStartupValidator.java
    - core-java/src/test/java/uk/jtoye/core/storage/StorageConfigShapeTest.java
    - core-java/src/test/java/uk/jtoye/core/storage/WorkloadIdentityCredentialBuildTest.java
    - core-java/src/test/java/uk/jtoye/core/storage/StorageStartupValidatorIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java
    - core-java/src/main/java/uk/jtoye/core/storage/StorageConfig.java
    - core-java/src/main/resources/application.yml
    - core-java/build.gradle.kts
    - core-java/src/test/resources/application-test.yml
    - core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/config/DatabaseConfigurationValidatorOwnershipTest.java
    - core-java/src/test/java/uk/jtoye/core/storage/AzureBlobObjectStoreTest.java
    - .gitleaksignore

key-decisions:
  - "The probe listens on ApplicationStartedEvent, not the plan's ApplicationReadyEvent: Boot runs ApplicationRunners between the two, so a ready-time probe let DemoDataSeeder's first writes on a fresh Azurite volume fail with ContainerNotFound (measured, arm B)"
  - "The emulator-only rule also applies in workload-identity mode to any non-blank connection string: the emulator default is ignored there, a real key is refused, so no runtime can hold one even unused"
  - "The emulator-account form requires an explicit plain-http BlobEndpoint addressing /devstoreaccount1 on a non-Azure host, and an AccountKey (the SDK rejects AccountName without one)"
  - "DevelopmentStorageProxyUri must be a bare http host (no port, path, userinfo, query), mirroring blobctl, because the SDK silently drops a port or path"
  - "A1 is proven in a class loader built from the production runtimeClasspath that tasks.test hands the test; the test classpath carries jna 5.13.0 via Testcontainers' docker-java transport"
  - "DatabaseConfigurationValidatorOwnershipTest opts out too: it boots the default profile, and the started-time storage probe would fail both directions before its database validator runs"

patterns-established:
  - "Test-context opt-out of a startup check: application-test.yml for the test profile, @TestPropertySource or boot args for any other context, each with a written reason; never weaken the check"

requirements-completed: []   # plan declares [BLOB-01, BLOB-02]; requirements.ready-ids reports 0/2 ready (36-07 and 36-14 also declare them and have no SUMMARY yet)

coverage:
  - id: D1
    description: "Every malformed storage.blob.* configuration is refused with StorageConfigurationException while the Blob client bean is built, before any network call; no real account key or SAS is configurable in any profile or mode"
    requirement: BLOB-01
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storage/StorageConfigShapeTest.java (43 tests)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storage/AzureBlobObjectStoreTest.java (2, now through the shape rules)"
        status: pass
    human_judgment: false
  - id: D2
    description: "At boot, core-java asserts both containers exist, the public one is access level blob (CONTAINER refused as #626) and the quarantine one private; with create-containers it creates them at those levels first, before ApplicationRunners, and never repairs an existing container"
    requirement: BLOB-02
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storage/StorageStartupValidatorIntegrationTest.java (10 tests, Azurite 3.37.0 digest-pinned)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The probe is ON in every runtime: validate-on-startup is a literal true in application.yml, not env-mapped, no main profile sets false, and no runtime surface sets it in any spelling"
    requirement: BLOB-02
    verification:
      - kind: other
        ref: "git grep -n -E 'validate-on-startup: *\\$\\{' -- core-java/src/main/resources (rc=1); git grep -n -E 'validate-on-startup: *false' -- core-java/src/main (rc=1); rg -uu -i 'validate[-_]?on[-_]?startup' k8s infra scripts docker-compose*.yml .github (rc=1)"
        status: pass
      - kind: integration
        ref: "StorageStartupValidatorIntegrationTest#probeBeanFollowsTheProperty"
        status: pass
    human_judgment: false
  - id: D4
    description: "The Workload Identity credential builds and walks its token path to a network failure with no linkage error in a loader made only of the production runtimeClasspath; JNA and the persistence extension are absent from it"
    requirement: BLOB-01
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storage/WorkloadIdentityCredentialBuildTest.java (5 tests incl. the AzurePowerShellCredential control arm)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The full unit and integration suites stay green with the probe in place"
    verification:
      - kind: unit
        ref: "./gradlew :core-java:cleanTest :core-java:test (165 classes, 1288 tests, 0 failures, 0 errors, 1 skipped)"
        status: pass
      - kind: integration
        ref: "./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest (146 classes, 729 tests, 0 failures, 0 errors, 6 skipped)"
        status: pass
    human_judgment: false
  - id: D6
    description: "A real compose / hybrid / k8s-local core-java boot passes the probe against its own Azurite (create-containers on a fresh volume, then the demo seeder's images land)"
    verification: []
    human_judgment: true
    rationale: "No runtime boot was run: the project images were pruned 2026-09-27 and this plan does not require a compose build. The SpringApplication arms prove the ordering and the fail-to-start against Azurite; the first real boot belongs to the plan that brings the stack up (36-11 / 36-17 runtime parity)."

duration: 47min
completed: 2026-09-28
---

# Phase 36 Plan 06: Fail-Fast Storage Configuration and Boot-Time #626 Probe Summary

**Two startup checks now stop core-java on a storage misconfiguration, and neither needs a first upload. The Blob client bean refuses any connection string that is not an Azurite emulator form, in every profile, plus every other malformed `storage.blob.*` shape. A started-time probe asserts at every boot that the public container is at level `blob` (never `container`, #626) and the quarantine container is private. In emulator mode it first creates both containers, before the demo seeder writes to them.**

## Performance

- **Duration:** 47 min
- **Started:** 2026-09-28T22:59:50Z
- **Completed:** 2026-09-28T23:46:32Z
- **Tasks:** 3 of 3
- **Files:** 14 (4 created, 10 modified)
- **Commits:** 7 code/test commits, plus this SUMMARY commit

## Accomplishments

- **D-02, shape (BLOB-01).** `StorageProperties.Blob.validateShape(env)` runs first inside `StorageConfig.buildClient`, so a bad config stops the context while the bean is built. The rules:
  - `auth-mode` must be exactly `connection-string` or `workload-identity`.
  - Any non-blank connection string must be an emulator form, in every profile and in both modes. The accepted forms are `UseDevelopmentStorage=true` with an optional bare-http proxy host, or `AccountName=devstoreaccount1` with an `AccountKey` and a plain-http, non-Azure, path-style `BlobEndpoint`.
  - Workload identity needs `https://<account>.blob.core.windows.net` and the three webhook `AZURE_*` values, read through the same lookup that builds the credential. It refuses `create-containers`.
  - Container names must follow the Blob naming rules and must differ from each other.
  - `public-url` must be absolute http(s) and end with `/<public-container>`.
  - The retry budget must be at least 1.
  - Messages name the property and its env var. They never repeat a connection-string value, an account name taken from one, or a key.
- **D-02 and D-08, probe (BLOB-02).** `StorageStartupValidator` runs on `ApplicationStartedEvent`.
  - It checks that both containers exist, that the public one is `BLOB`, and that the quarantine one is `PRIVATE`.
  - `CONTAINER` is refused with "allows anonymous LIST ... #626 requires access level blob".
  - With `create-containers` on, it first creates both containers at exactly those levels. An existing container is never repaired.
  - Any other failure, including an unreachable store, is rethrown as `StorageConfigurationException` with the cause kept.
  - The switch `storage.blob.validate-on-startup` is a literal `true` in application.yml and is not mapped from any env var.
- **A1, proven on what ships.** The Workload Identity credential builds in a class loader made only of the production `runtimeClasspath`. There, a token request fails on the network, never on linkage. JNA and `msal4j-persistence-extension` are absent from that loader, and azure-identity is present as a positive control.
- **No regressions.** Full unit suite: 1288 tests. Full integration suite: 729 tests. 0 failures and 0 errors in both. Every context that lacks Azurite opts out explicitly, with a reason.

## Task Commits

1. **Task 1: shape validation**
   - `f9bfbaf8` test(36-06): add failing shape tests for storage.blob configuration (RED, inert `validateShape` skeleton)
   - `0f17f0f5` feat(36-06): refuse malformed storage.blob configuration while the bean is built (GREEN)
   - `cea6d2b8` fix(36-06): keep a secret-shaped literal out of the shape test (deviation 5)
2. **Task 2: A1 proof and boot probe**
   - `f0c224b8` test(36-06): add failing Azurite arms for the boot-time storage probe (RED, inert validator skeleton)
   - `f024fd76` test(36-06): prove the Workload Identity credential on the shipped classpath (existing behaviour, see TDD Gate Compliance)
   - `09b7edfa` feat(36-06): assert the #626 container levels at every boot (GREEN)
3. **Task 3: test-context opt-outs**
   - `fbccb1fd` test(36-06): opt test contexts without Azurite out of the storage probe

## Files Created/Modified

- `storage/StorageProperties.java`: `validateShape`, the emulator/proxy/endpoint/container/public-url rules, `authModeValue()`, `validateOnStartup`, and the package-private `enum AuthMode` (exact match; the property stays String-typed so an unknown value gets this message instead of a binding error).
- `storage/StorageConfig.java`: `blobServiceClient` delegates to `static buildClient(properties, env)`, which validates first, then switches on `AuthMode`. The `AZURE_*` values come from `env`. 36-01's ad-hoc mode throw is gone.
- `storage/StorageStartupValidator.java`: the probe (new).
- `resources/application.yml`: `storage.blob.validate-on-startup: true` (a literal, with the reason).
- `build.gradle.kts`: `tasks.test` passes `-Djtoye.productionRuntimeClasspath` (the resolved `runtimeClasspath`, declared as a task input). This was not in the plan's file list (deviation 2).
- `test/resources/application-test.yml`: `storage.blob.validate-on-startup: false`, a test-context opt-out with its reason.
- `RedisFaultInjectionIntegrationTest`, `PublicRateLimitIntegrationTest`: `@TestPropertySource(properties = "storage.blob.validate-on-startup=false")` with a reason.
- `DatabaseConfigurationValidatorOwnershipTest`: the boot arg `storage.blob.validate-on-startup=false` with a reason (deviation 4).
- `AzureBlobObjectStoreTest`: the closed-port connection string is now the emulator-account form, with a one-byte placeholder key (deviation 3).
- `.gitleaksignore`: one fingerprint for `f9bfbaf8` (deviation 5).
- Tests (new): `StorageConfigShapeTest` (43), `WorkloadIdentityCredentialBuildTest` (5), `StorageStartupValidatorIntegrationTest` (10, `@Tag("testcontainers")`).

## Evidence: every acceptance criterion, both directions

All test runs used `cleanTest` / `cleanIntegrationTest`, and the logs show the test task executing. Counts come from `core-java/build-local/test-results/**/TEST-*.xml`, never from the stale `core-java/build/`.

### Task 1

| Criterion | Pass (real tree) | Fail (deliberately broken input) |
|---|---|---|
| StorageConfigShapeTest >= 14 cases | 43 tests, 0 failures (`tests="43" failures="0"`) | **RED** (inert skeleton): 42 tests, 36 failed, all `Expected StorageConfigurationException to be thrown, but nothing was thrown`; the 6 valid shapes passed. The record passed `gsd_run check tdd-red-evidence` with **RED_EVIDENCE_OK** (target "connection-string: a real account name is refused as emulator-only, and the key is never echoed") |
| emulator-only break arm | closing run 43/43 | removing the `validateEmulatorConnectionString(...)` call: **11 of 43 red**, exactly the emulator-only family (real account, SAS, real host, https, missing BlobEndpoint, missing AccountKey, 3 proxy URIs, the bean-factory case, the WI real-key case). Restore verified by hash `8161a7a2c90e4ac8` |
| `git grep -n -E 'getActiveProfiles\|acceptsProfiles' -- core-java/src/main/java/uk/jtoye/core/storage` | rc=1 | a planted `// env.getActiveProfiles()` line in StorageConfig.java: rc=0, the line is listed. Restored by hash. Control: the same pattern over `core-java/src/main` finds ActiveProfileValidator and SecurityConfig (rc=0) |
| `git grep -n 'AZURE_' -- core-java/src/main/resources` | rc=1 | a planted `# ARM ${AZURE_CLIENT_ID}` line in application.yml: rc=0. Restored by hash; closing grep rc=1 |
| Azurite tracer still green through the new rules (Testcontainers' connection-string form) | AzuriteStorageIntegrationTest 10/10 | covered by the shape test's `testcontainersFormIsValid` and by the emulator-only arm |

### Task 2

| Criterion | Pass | Fail |
|---|---|---|
| StorageStartupValidatorIntegrationTest >= 6 cases | 10 tests, 0 failures | **RED** (inert skeleton): 10 tests, 9 failed on assertions, 0 errors. **RED_EVIDENCE_OK** (target "public container at level CONTAINER (anonymous LIST) is refused as a #626 regression") |
| #626 break arm | closing run 10/10 | **arm A** (level `container` accepted: the CONTAINER refusal disabled and only PRIVATE refused): 3 red. They are the #626 arm, "never repairs", and "SpringApplication fails to start". Restore verified by hash `fe190bac79ae48a6` |
| event ordering (deviation 1) | `containersExistBeforeRunners` passes: the runner's write is read back, and the levels are BLOB and PRIVATE | **arm B** (the plan's literal `ApplicationReadyEvent`): exactly that test red. The runner's write failed with `Status code 404, ContainerNotFound`. Restored by hash |
| the property gate | `probeBeanFollowsTheProperty` passes | **arm C** (`@ConditionalOnProperty` removed): exactly that test red. Restored by hash. Closing run 10/10 |
| WorkloadIdentityCredentialBuildTest | 5 tests, 0 failures, 7.4 s | see the next two rows and deviation 2. The first version (`Class.forName` in the test JVM) was **red on the correct tree**: `Expected ClassNotFoundException ... nothing was thrown`, because the test classpath carries `net.java.dev.jna:jna:5.13.0` via `docker-java-transport-zerodep:3.4.2`. The production `runtimeClasspath` has 0 jna lines |
| "jna assertion fails when the msal4j-persistence-extension exclude is removed" (the plan's wording) | closing run 5/5 | **arm D1** (only that exclude removed): red, but only on the new URL assertion, `Expecting no elements of [...]` because the persistence-extension jar appears. The jna `ClassNotFoundException` assertion itself **stayed green**, because the jna-group exclude still holds. As written, the plan's criterion could not fail. **arm D2** (the `net.java.dev.jna` group exclude removed): the jna `ClassNotFoundException` assertion goes red, and the control arm flips too (the PowerShell credential no longer hits a JNA linkage error and reaches `CredentialUnavailableException`). Both restored by hash `af7a3c5e359d8fd6`; closing run 5/5 |
| the token-path linkage assertion can fail | control arm: `AzurePowerShellCredential` in the same loader surfaces `NoClassDefFoundError: com/sun/jna/...` | n/a (the control is itself the fail direction) |
| `git grep -n -E 'validate-on-startup: *\$\{' -- core-java/src/main/resources` | rc=1. Positive control: `validate-on-startup: true` is found at application.yml:595 | the line rewritten as `${STORAGE_VALIDATE_ON_STARTUP:true}`: rc=0. Restored by hash |
| `git grep -n -E 'validate-on-startup: *false' -- core-java/src/main` | rc=1 | a planted `storage.blob.validate-on-startup: false` in application-dev.yml: rc=0. Restored by hash; closing rc=1 |

### Task 3

| Criterion | Pass | Fail |
|---|---|---|
| opt-out falsification | all four classes green in the full run (Audit 5/5, RedisFault 1/1, PublicRateLimit 1/1, Ownership 2/2) | all four opt-outs removed (application-test.yml line, both `@TestPropertySource`, the ownership boot arg), one run: **AuditIntegrationTest 5/5 failed, RedisFaultInjection 1/1, PublicRateLimit 1/1, DatabaseConfigurationValidatorOwnership 2/2**. Every XML carries `Storage startup check FAILED: the object store could not be checked`. The ownership test's own assertion names the masking: `startup failure must be the ownership SecurityConfigurationException, not some unrelated boot error ... Expecting actual not to be null`. All 4 files restored, verified by hash |
| full suites | **unit:** 165 classes, **1288 tests, 0 failures, 0 errors, 1 skipped**. **integration:** 146 classes, **729 tests, 0 failures, 0 errors, 6 skipped**. Both tasks executed (`BUILD SUCCESSFUL in 25m 2s`, rc=0; `test` and `integrationTest` not UP-TO-DATE). The base (36-01 SUMMARY) was 163/1240/1 and 145/719/6. The deltas are exactly this plan's additions: +2 unit classes and +48 tests (43 + 5), +1 IT class and +10 tests | the four-class arm above |
| `git grep -n 'validate-on-startup=false' -- core-java/src/test` | lists the two dev-profile ITs (lines 75 and 63), plus two lines in `StorageStartupValidatorIntegrationTest`. Those two are the switch's own test (the DisplayName and the `withPropertyValues`), not an opt-out | at the base commit the widened pattern finds nothing (rc=1). The plan's pattern **misses** the ownership test's map-form opt-out. The stronger `git grep -n -E 'validate-on-startup(=\|", "\|: *)false'` lists all of them: application-test.yml:54, the two dev ITs, DatabaseConfigurationValidatorOwnershipTest.java:152, and the switch test. Each opt-out carries a reason comment |

### Other proofs

- **Secrets.** `gitleaks git --config .gitleaks.toml --log-opts=4ba8a1f6..HEAD` is **rc=0** after `cea6d2b8`. Before it, rc=1 with 1 finding (`generic-api-key`, StorageConfigShapeTest.java:32, commit `f9bfbaf8`). A `gitleaks dir` scan of the current `storage/` test directory is rc=0. Control: the same scan over the `f9bfbaf8` version of the file is rc=1.
- **T-36-03 (no key in logs).** `rg -uu -c 'AccountKey|Rk9PRk9P'` over every captured unit and IT log finds one hit, a test display name ("devstoreaccount1 without AccountKey is refused..."), not a logged value. Positive control: the same search finds `Configuring Blob storage client` in 139 IT XMLs, and `Storage startup check passed` in the probe IT.
- **Env surface (T-36-24).** `rg -uu -n -i 'validate[-_]?on[-_]?startup' k8s infra scripts docker-compose*.yml .github .env*` is rc=1. Positive control: `STORAGE_CREATE_CONTAINERS` is found in compose and start-dev.sh.
- **Containers.** Every container this plan caused (Azurite and Postgres/Redis via Testcontainers, and Ryuk) was reaped by Testcontainers. `docker ps -a` is empty at the end.

## TDD Gate Compliance

| Task | RED | GREEN | Evidence |
|---|---|---|---|
| 1 | `f9bfbaf8` | `0f17f0f5` | `/tmp/claude-36-06/red-task1.json`: **RED_EVIDENCE_OK** (target_test_failed) |
| 2 (probe) | `f0c224b8` | `09b7edfa` | `/tmp/claude-36-06/red-task2-it.json`: **RED_EVIDENCE_OK** (target_test_failed) |
| 2 (A1) | none | none (`f024fd76` is `test(...)`) | **Unexpected GREEN, investigated.** A1 is existing behaviour: 36-01's `80532db2` added the exclusion. The test's ability to fail is shown by arms D1 and D2 and by the control arm. Not claimed as RED |
| 3 | n/a | n/a | `type="auto"` without `tdd`; falsified by the four-class opt-out arm |

RED records are the JUnit XML translated faithfully to TAP by `/tmp/claude-36-06/junit2red.js`, 36-01's translator with the target file parameterised.

## Decisions Made

See `key-decisions` above. The two with the widest effect:
- **ApplicationStartedEvent.** The probe runs after the context is fully built but before any `ApplicationRunner`. Readiness has not flipped at either event, so no traffic is served by an unchecked store. A storage misconfiguration now fails before the DB validator's ready-time check, so on a doubly broken runtime the storage error is reported first.
- **The emulator-only rule in both modes.** The plan said the connection string is "ignored" in workload-identity mode. It is still ignored when it holds the emulator default, but a real-account string is refused there too. That makes "a real account key cannot be configured in ANY runtime" literally true.

## Deviations from Plan

**1. [Rule 1: bug the plan would ship] The probe listens on ApplicationStartedEvent, not ApplicationReadyEvent**
- **Found during:** Task 2 RED. The runner arm failed with a Blob 404 `ContainerNotFound`.
- **Issue:** Spring Boot calls `ApplicationRunner`s between ApplicationStartedEvent and ApplicationReadyEvent, and `DemoDataSeeder` (dev) is an `ApplicationRunner`. With the planned ready-time probe, on a fresh Azurite volume (compose and the hybrid runtime both set create-containers) every seed `putIfAbsent` hits a missing container, before the probe creates it. The seeder catches each entry's error as a WARN, so first boot would silently seed no demo images. That is regression by omission.
- **Fix:** `@EventListener(ApplicationStartedEvent.class)`, explained in the class Javadoc. The must-have artifact marker `contains: "ApplicationReadyEvent"` is now matched only by that Javadoc explanation, not by the listener. The verifier should read the listener as a deliberate deviation.
- **Verification:** `containersExistBeforeRunners` passes. Arm B (the plan's literal event) turns exactly that test red.
- **Commit:** `09b7edfa`.

**2. [Rule 1: criterion could not pass on a correct tree] A1 is tested in a production-classpath loader; build.gradle.kts changed**
- **Found during:** Task 2, the first unit run.
- **Issue:** `Class.forName("com.sun.jna.Native")` in the test JVM succeeds, because Testcontainers puts `jna:5.13.0` on `testRuntimeClasspath`. The production `runtimeClasspath` has none. The planned assertion was red on the correct tree and could never say what ships. Also, the plan's arm (remove only the persistence-extension exclude) leaves the jna assertion green, because the jna-group exclude from 36-01 is what keeps JNA out (arm D1).
- **Fix:**
  - `tasks.test` passes the resolved `runtimeClasspath` as `-Djtoye.productionRuntimeClasspath`, declared as an input. The test fails closed when the property is absent.
  - The test loads azure-identity from a `URLClassLoader` over only those jars.
  - A persistence-extension URL assertion is added.
  - A token-path linkage test (`disableInstanceDiscovery`, `maxRetry(0)`, closed authority port) is added, with an `AzurePowerShellCredential` control arm.
- **Files:** `core-java/build.gradle.kts` (not in the plan's file list), `WorkloadIdentityCredentialBuildTest.java`. **Commit:** `f024fd76`.

**3. [Rule 3: blocking] The closed-port test needed the emulator-account form**
- **Found during:** Task 1 GREEN.
- **Issue:** 36-01's `AzureBlobObjectStoreTest` used a bare `BlobEndpoint=` string, which the emulator-only rule refuses. `AccountName=devstoreaccount1` without a key is rejected by the SDK itself ("Invalid connection string"). So the rule now also requires `AccountKey` in that form, which gives the operator the property name instead of the SDK's generic error.
- **Fix:** the test uses `AccountName=devstoreaccount1;AccountKey=eA==;BlobEndpoint=http://127.0.0.1:<closed>/devstoreaccount1`. `eA==` is base64 of one byte, a placeholder, not the emulator key. The Javadoc explains it.
- **Commit:** `0f17f0f5`.

**4. [Rule 3: blocking] A third test context needed the opt-out**
- **Issue:** `DatabaseConfigurationValidatorOwnershipTest` boots `CoreApplication` under the default profile with no Azurite. The started-time probe fails it before its subject runs. This was measured in the four-class arm: 2/2 red.
- **Fix:** the boot arg `storage.blob.validate-on-startup=false`, with a reason. The validator was not weakened.
- **Commit:** `fbccb1fd`.

**5. [Rule 1: CI gate] gitleaks generic-api-key on the fake key literal**
- **Issue:** `REAL_KEY` (a base64 stand-in used to prove the messages never echo a key) tripped `generic-api-key` in `f9bfbaf8`. gitleaks-action scans every PR commit.
- **Fix:** the live value is built at run time (`"Rk9P".repeat(10) + "=="`), and `.gitleaksignore` fingerprints the historical commit. That follows the repo's own convention for historical false positives. The branch's commits are unpushed, but rewriting history was not attempted.
- **Commit:** `cea6d2b8`.

**6. [Scope note] Stricter-than-planned shape rules, each with its own test**
- The emulator-account form also requires `BlobEndpoint`: without one, the SDK targets `devstoreaccount1.blob.core.windows.net` in the public cloud.
- The endpoint must address `/devstoreaccount1` path-style.
- `DefaultEndpointsProtocol` must be http.
- Duplicate keys are refused.
- The proxy URI must be a bare http host, as blobctl requires.
- The workload-identity endpoint must have no path.
- These only narrow what is accepted. Every runtime's real value passes: compose, the hybrid runtime, the application.yml default and Testcontainers.

**Total deviations:** 6 (2 Rule 1 plan-premise, 1 Rule 1 CI gate, 2 Rule 3 blocking, 1 scope note). **Impact:** each one either makes a criterion able to fail, stops a silent first-boot regression, or keeps a gate green. None widens runtime behaviour beyond the plan's intent.

## Issues Encountered

- Heredoc Python is refused by the machine's python guard, as recorded in 36-01. The edits were made with the Edit tool.
- The first token-path run hung past the 90 s bound because of retry backoff. It was fixed with `disableInstanceDiscovery()` and `maxRetry(0)`, as 36-01's out-of-band probe used; the class now runs in 7 s.

## Known reds inherited (recorded, not fixed)

- **`scripts/docs-freshness.sh` rc=1, owned by 36-17.** The tree now counts `java_test_methods: 1950`, `java_test_files: 300` and `total_logical_invocations: 4114`, against the recorded 1897 / 295 / 4042. 36-01's share was +17 methods and +2 files; this plan adds +3 files. `docs/metrics.json` was not regenerated.
- **`k8s/scripts/check-env-contract.sh`, owned by 36-09.** It was not re-run here. This plan adds no env read and no `${...}` placeholder: the literal `validate-on-startup: true` is not a placeholder, and the `AZURE_*` reads already existed in 36-01's StorageConfig.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: config-override | core-java/src/main/resources/application.yml | The literal `validate-on-startup: true` stops an explicit env mapping, but Spring relaxed binding still lets an OS env var (`STORAGE_BLOB_VALIDATEONSTARTUP=false`) override it, as it can for any property. No runtime surface sets it today (rg over k8s, infra, scripts, compose and .github: rc=1). Closing this fully would take a guard in 36-09's env-contract gate or a source-aware condition. It is recorded rather than added here, because it needs manifest write access, which could equally change the image |

## Known Stubs

None.

## User Setup Required

None.

## Next Phase Readiness

- Every later Blob IT can rely on the shape rules accepting `AzuriteTestSupport`'s connection string. A test-profile context gets the opt-out automatically. Any other context must opt out explicitly, as the three here do.
- **Not proven yet:** the first real core-java boot through compose, the hybrid runtime or k8s-local against its Azurite (coverage D6). k8s-local runs profile `prod` with connection-string mode, which the shape rules accept; whether its overlay provides Azurite and `STORAGE_CREATE_CONTAINERS` belongs to 36-09.
- In staging and production the probe needs the identity to be able to read container properties (Storage Blob Data Reader or Contributor), plus containers provisioned at `blob` and private. Both are in the 36-05 runbook for Phase 29.
- BLOB-01 and BLOB-02 are not marked complete: `requirements.ready-ids` reports 0/2 while 36-07 and 36-14 are open.

## Self-Check: PASSED

- All 4 created files exist on disk. All 7 commit hashes resolve (`git cat-file -e`).
- Must-have markers are present: `validateShape` in StorageConfig.java:49; `StorageConfigurationException` in StorageConfigShapeTest (23 references); `@Tag("testcontainers")` in the probe IT; `containerAccess`, `containerExists` and `createContainerIfMissing` in the validator (5 references). `ApplicationReadyEvent` appears only in the validator's Javadoc, by design (deviation 1).
- No container is left running (`docker ps -a` is empty). No attribution lines in any commit (rc=1).

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-28*

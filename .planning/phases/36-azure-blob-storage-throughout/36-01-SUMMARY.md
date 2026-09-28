---
phase: 36-azure-blob-storage-throughout
plan: 01
subsystem: storage
tags: [azure-blob, azurite, testcontainers, storage-seam, workload-identity, "#626", quarantine]
status: complete

requires:
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline
    provides: StorageService key-addressed I/O (putBytes/getBytes/deleteByKey) and the quarantine key shape
  - phase: 27-operational-maturity
    provides: deleteByKeyChecked contract (27-01 F-5) and the quarantine sweep sentinel
provides:
  - BlobObjectStore port (no SDK types) + AzureBlobObjectStore, the one Azure Blob implementation
  - storage.blob.* config with the single auth-mode switch (connection-string | workload-identity)
  - key-derived container routing: <tenant>/quarantine/... -> private jtoye-quarantine, all else -> public jtoye-images
  - AzuriteTestSupport, the shared digest-pinned Azurite fixture for every later Blob IT
  - AzuriteStorageIntegrationTest tracer (10 tests) incl. #626 and quarantine privacy both directions
  - StorageUnavailableException / StorageConfigurationException
affects: [36-02, 36-03, 36-06, 36-09, 36-11, 36-15, 36-17, phase-29-handoff]

actuals:
  tokens: 28849        # chars/4 over git diff 97c89494..HEAD (code commits), before this SUMMARY
  tasks: 3
  commits: 9           # MEASURED: git rev-list --count 97c89494..HEAD, before the SUMMARY commit
plan_head_before: 97c89494b58e8290ebe14cfcb8ee88b4d4da6510

tech-stack:
  added:
    - com.azure:azure-storage-blob 12.35.1
    - com.azure:azure-identity 1.18.6 (excluding msal4j-persistence-extension AND the net.java.dev.jna group)
    - org.testcontainers:azure 1.21.4 (test)
    - mcr.microsoft.com/azure-storage/azurite:3.37.0@sha256:830430c1...1fd5 (test image)
  removed:
    - software.amazon.awssdk:bom 2.54.9 + software.amazon.awssdk:s3
    - extra["httpcore5.version"] pin (nothing resolves httpcore5 any more)
  patterns:
    - "Port + single adapter: StorageService depends on BlobObjectStore; only AzureBlobObjectStore touches the SDK"
    - "Container derived from the key, never chosen by a caller or persisted"
    - "Exception split: BlobStorageException (service answered) propagates; any other RuntimeException -> StorageUnavailableException"
    - "Anonymous-access proofs are behavioural: plain HttpClient GET/LIST with no Authorization header against Azurite"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/storage/BlobObjectStore.java
    - core-java/src/main/java/uk/jtoye/core/storage/AzureBlobObjectStore.java
    - core-java/src/main/java/uk/jtoye/core/storage/StorageUnavailableException.java
    - core-java/src/main/java/uk/jtoye/core/storage/StorageConfigurationException.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/AzuriteTestSupport.java
    - core-java/src/test/java/uk/jtoye/core/storage/AzuriteStorageIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storage/AzureBlobObjectStoreTest.java
  modified:
    - core-java/build.gradle.kts
    - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
    - core-java/src/main/java/uk/jtoye/core/storage/StorageConfig.java
    - core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/storage/StorageServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/storage/LegacyImageUploadPipelineTest.java
    - core-java/src/test/java/uk/jtoye/core/storage/ShopBrandImageKeyTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaQuarantineRetentionSweepTest.java
    - scripts/check-doc-versions.sh
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "azure-identity's net.java.dev.jna group is excluded in addition to msal4j-persistence-extension: 1.18.6 declares jna-platform 5.17.0 DIRECTLY, so the plan's single exclusion left jna on the runtime classpath"
  - "Closed-port store test uses an endpoint-only BlobEndpoint= connection string: the SDK ignores any port in DevelopmentStorageProxyUri and always targets :10000"
  - "putSeedImage writes create-only (If-None-Match *, 409 BlobAlreadyExists = already present), replacing the head-then-put pair; Azurite proven to answer 409"
  - "netty-tcnative-boringssl-static kept (assumption A2 not taken): its musl fallback cannot be proven locally because Azurite is plain http"
  - "Testcontainers needs asCompatibleSubstituteFor for the tag+digest Azurite reference (the tag parses into the repository part)"
  - "StorageConfig reads the AZURE_* workload-identity env via System.getenv, never ${AZURE_*} in application.yml (env-contract direction b)"

patterns-established:
  - "Every later Blob IT reuses AzuriteTestSupport (digest-pinned, API-version check skipped via env, no key literal)"
  - "A privacy test must probe the key in EVERY container a routing mistake could send it to, not only the intended one"

requirements-completed: []   # plan declares [BLOB-01, BLOB-02]; neither is FULLY closed here (see Requirements below)

coverage:
  - id: D1
    description: "core-java stores, reads and deletes media through the Azure Blob SDK behind the unchanged StorageService surface; URL = storage.blob.public-url + '/' + key"
    requirement: BLOB-01
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storage/AzuriteStorageIntegrationTest.java (10 tests)"
        status: pass
      - kind: unit
        ref: "StorageServiceTest(20) LegacyImageUploadPipelineTest(11) ShopBrandImageKeyTest(8) MediaQuarantineRetentionSweepTest(6)"
        status: pass
    human_judgment: false
  - id: D2
    description: "No AWS SDK in core-java main code, build file, runtime classpath or the built image's app.jar"
    requirement: BLOB-01
    verification:
      - kind: other
        ref: "git grep -n -E 'software\\.amazon|S3Client|awssdk' -- core-java/src/main core-java/build.gradle.kts (rc=1; base 22 matches)"
        status: pass
      - kind: other
        ref: "./gradlew -q :core-java:dependencies --configuration runtimeClasspath (software.amazon 0; base 175)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Quarantine keys land in the private container with no public URL and no immutable header; public container serves anonymous GET but refuses anonymous LIST (#626)"
    requirement: BLOB-02
    verification:
      - kind: integration
        ref: "AzuriteStorageIntegrationTest#quarantinedUploadIsPrivate, #publicContainerIsReadableByUrlButNotListable, #quarantineKeyRoundTripsThroughThePrivateContainer"
        status: pass
      - kind: unit
        ref: "StorageServiceTest#putBytesQuarantineKeyIsPrivateWithoutUrlOrImmutableHeader, #urlForKeyRefusesQuarantineKeys"
        status: pass
    human_judgment: false
  - id: D4
    description: "Unreachable store maps to StorageUnavailableException; demo seeding aborts once and dev boot stays green"
    requirement: BLOB-01
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storage/AzureBlobObjectStoreTest.java (2)"
        status: pass
      - kind: integration
        ref: "RedisFaultInjectionIntegrationTest / PublicRateLimitIntegrationTest captured log: 'Object store unreachable; skipping demo image seeding' once each, tests green"
        status: pass
    human_judgment: false
  - id: D5
    description: "Built core-java image passes the CI Trivy image gate flags; doc-version gate follows the new SDK"
    verification:
      - kind: other
        ref: "aquasec/trivy:0.70.0 image --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 (rc=0, 0/0)"
        status: pass
      - kind: other
        ref: "bash scripts/check-doc-versions.sh (rc=0, 148 claims)"
        status: pass
    human_judgment: false

duration: 61min
completed: 2026-09-28
---

# Phase 36 Plan 01: Blob Storage Seam Tracer Summary

**StorageService now runs on the Azure Blob SDK through a BlobObjectStore port, with quarantine keys routed to a private container and the #626 no-list rule proven against a digest-pinned Azurite in both directions.**

## Performance

- **Duration:** 61 min
- **Started:** 2026-09-28T20:57:18Z
- **Completed:** 2026-09-28T21:59:02Z
- **Tasks:** 3 of 3
- **Files modified:** 20 (7 created, 13 modified)
- **Commits:** 9 code/test/doc commits, plus this SUMMARY commit

## Accomplishments

- The storage spine works end to end on Azure Blob: StorageService is built through StorageConfig's own bean methods, writes through the SDK to Azurite 3.37.0, and an anonymous browser-style GET reads the stored derivative back with its bytes, `image/webp` and `public, max-age=31536000, immutable`.
- Quarantine privacy is enforced and proven by behaviour. Raw uploads (`<tenant>/quarantine/...`) now go to the private `jtoye-quarantine` container with no cache header and no URL. Before this, they were anonymously readable in the public bucket for the 72 h horizon (T-36-01 closed).
- #626 is proven against a real emulator: the public container serves an anonymous GET and refuses an anonymous LIST (not 200, no `<EnumerationResults`).
- The AWS SDK is gone from main code, the build file, the runtime classpath and the shipped jar. No JNA, no httpcore5, no emulator key literal anywhere.
- The four compile-bound unit tests were ported with identical `@Test` counts (15, 11, 8, 6).
- Full regression on the swapped seam: the unit suite ran 1240 tests (0 failed, 1 pre-existing skip) and the integration suite 719 tests across 145 classes (0 failed, 6 pre-existing skips).

## Task Commits

1. **Task 1 (tracer): StorageService -> Blob SDK -> Azurite -> anonymous GET**
   - `b829e82f` test(36-01): add failing Azurite tracer for the Blob storage seam (RED, with the compile surface and an inert adapter skeleton)
   - `e84349bf` feat(36-01): implement the Azure Blob adapter behind StorageService (GREEN)
   - `80532db2` fix(36-01): keep JNA off the runtime classpath for azure-identity (deviation 1)
2. **Task 2: private quarantine and #626, both directions**
   - `b9ff002f` test(36-01): add failing tests for private quarantine and #626 listing (RED)
   - `c951dfba` feat(36-01): keep quarantined uploads private with no public URL (GREEN)
   - `a8052fc7` test(36-01): make the quarantine privacy test able to see mis-routing (deviation 3)
3. **Task 3: pipeline semantics, unreachable-store mapping, supply chain**
   - `2347920c` test(36-01): prove pipeline semantics and store-unreachable mapping
   - `4982ab92` chore(36-01): point the doc-version gate at the Azure SDK claims
   - `26aa40dd` docs(36-01): retire stale object-store wording in touched files

## Files Created/Modified

- `storage/BlobObjectStore.java`: the port. It has no SDK type in any signature and a nested `ContainerAccess {PRIVATE, BLOB, CONTAINER}`.
- `storage/AzureBlobObjectStore.java`: the only SDK user.
  - `put`: `uploadWithResponse` (overwrites).
  - `putIfAbsent`: `If-None-Match: *`, with 409 BlobAlreadyExists mapped to false.
  - `get`: `downloadContent`.
  - `deleteIfExists`: never `delete()`.
  - access level and container creation.
  - A `BlobStorageException` propagates; any other `RuntimeException` becomes `StorageUnavailableException`.
- `storage/StorageConfig.java`: the one `auth-mode` switch. `connection-string` uses `builder.connectionString`. `workload-identity` uses an explicit `WorkloadIdentityCredentialBuilder` fed from `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE` and optionally `AZURE_AUTHORITY_HOST`. Any other value raises `StorageConfigurationException`. It logs mode, endpoint host and container names only.
- `storage/StorageProperties.java`: `storage.blob.*`. `endpointHostForLog()` never returns the connection string. `maxFileSizeBytes` and `allowedContentTypes` are byte-identical.
- `storage/StorageService.java`:
  - Constructor takes `BlobObjectStore`.
  - Package-private `isQuarantineKey` and private `containerFor(key)`.
  - Quarantine `putBytes` has no cache header and returns null.
  - `urlForKey` refuses quarantine keys.
  - `putSeedImage` is create-only.
  - `deleteByKeyChecked` counts absent as gone.
  - The public surface signatures are unchanged.
- `storage/StorageUnavailableException.java` and `StorageConfigurationException.java`: plain `RuntimeException` subclasses.
- `dev/DemoDataSeeder.java`: `catch (StorageUnavailableException e)` aborts image seeding. "Never fatal to dev boot" is kept.
- `resources/application.yml`: `storage.blob.*` from `STORAGE_*` env, with no Spring placeholder inside a comment.
- `build.gradle.kts`:
  - azure-storage-blob 12.35.1 and azure-identity 1.18.6, excluding msal4j-persistence-extension and the jna group.
  - testcontainers:azure 1.21.4.
  - The AWS BOM, the s3 artifact and the httpcore5 pin are removed.
  - The netty pin comment now names azure-core-http-netty.
  - The forkEvery note is updated.
- `testsupport/AzuriteTestSupport.java`: the shared digest-pinned fixture (`AZURITE_IMAGE`, `newAzurite()`, `blobEndpoint`, `storageProperties`, `createContainers`, `registerAzuriteProperties`).
- `storage/AzuriteStorageIntegrationTest.java`: 10 tests, `@Tag("testcontainers")`, no Spring context.
- `storage/AzureBlobObjectStoreTest.java`: 2 closed-port unit tests.
- The four ported tests: `StorageServiceTest` (15, plus 5 Task 2 cases = 20), `LegacyImageUploadPipelineTest` (11), `ShopBrandImageKeyTest` (8), `MediaQuarantineRetentionSweepTest` (6).
- `scripts/check-doc-versions.sh`, `CLAUDE.md`, `AGENTS.md`: the retired SDK row and claim are replaced by "Azure Storage Blob SDK (12.35.1)" and "Azure Identity (1.18.6)".

## Evidence: every acceptance criterion, both directions

All commands were run from the repo root. The test runs used `cleanTest` or `cleanIntegrationTest`, and the logs show the test tasks executing, not UP-TO-DATE.

### Task 1

| Criterion | Pass direction (real tree) | Fail direction (deliberately broken input) |
|---|---|---|
| AWS SDK absent from main + build file | `git grep -n -E 'software\.amazon\|S3Client\|awssdk' -- core-java/src/main core-java/build.gradle.kts` prints nothing, `rc=1` | same pattern at `97c89494`: **22 matches**, `rc=0` |
| runtimeClasspath | `dependencies --configuration runtimeClasspath` rc=0: `software.amazon`=**0**, `azure-storage-blob:12.35.1`=**1**, `msal4j-persistence-extension\|net.java.dev.jna`=**0** | base-commit worktree: `software.amazon`=**175**, blob=0, httpcore5 lines=4. With only the plan's exclusion, jna lines=**2**; deviation 1 fixes this |
| Tracer XML | `TEST-...AzuriteStorageIntegrationTest.xml`: tests=4, failures=0, errors=0 (10 after Task 3) | RED run (inert adapter): 4 run, 3 failed on assertions (`expected: 200 but was: 403`). **Break arm**: public-url at `/jtoye-images-missing` gave 2 failures (`anonymous GET ... expected: 200 but was: 403`). Restore verified by blob hash `b287f46a…`; closing run 4/4 |
| Only storage/, DemoDataSeeder, application.yml changed in main | the filter over `git diff --name-only 97c89494..HEAD -- core-java/src/main` prints nothing outside the allowed set (`rc=1`) | same filter over `core-java/src` lists the 6 test files (`rc=0`), so the filter can report outsiders |
| `@Test` counts unchanged | before/after: StorageServiceTest 15/15, LegacyImageUploadPipelineTest 11/11, ShopBrandImageKeyTest 8/8, MediaQuarantineRetentionSweepTest 6/6 (each `git show` rc=0) | n/a (an equality check; each file has a non-zero count) |
| exactly one `catch (StorageUnavailableException` | 1 line (`DemoDataSeeder.java:472`) | at `97c89494`: 0 lines, `rc=1` |
| BlobObjectStore not referenced outside storage/ in main | `rc=1` | without the exclusion, the same pattern matches 4 files in storage/ (`rc=0`) |
| no `AccountKey=` in core-java | `rc=1` | a planted untracked file is found (`git grep --untracked`, `rc=0`); `rc=1` again after removal |
| no compose core-java container created | `docker ps -a --filter name=jtoye-core-java` is empty | **cannot fail in this environment**: `docker ps -a` lists no containers at all, so the stronger statement "no container of any name exists" is what is recorded |

### Task 2

| Criterion | Pass | Fail |
|---|---|---|
| arm (1): public container at `CONTAINER` level | closing run 7/7 | LIST test red at the `isNotEqualTo(200)` line (status **200**), body began `<?xml … <EnumerationResults ServiceEndpoint=…`. Restore verified by hash `3eac6a8d…` |
| arm (2): `containerFor` returns public for every key | closing run 7/7 | First run: the quarantine GET test **stayed green** (deviation 3), only the round-trip test went red. After strengthening: quarantine test red with `anonymous GET of …/jtoye-images/<t>/quarantine/<sha>.jpg — Expecting actual: 200 not to be equal to: 200`, and the round-trip test red with `expected: 404 but was: 200`. Restore verified by hash `ac7482b8…` |
| `QUARANTINE_SEGMENT = "/quarantine/"` still present | 1 line (`MediaQuarantineRetentionSweep.java:81`) | n/a |
| no media/ main change | `git diff --name-only 97c89494..HEAD -- core-java/src/main/java/uk/jtoye/core/media/` is empty | control: the same command on `storage/` lists files |
| `compileTestJava` exit 0 | `compileJava compileTestJava --rerun-tasks` rc=0 (both tasks executed) | n/a |

### Task 3

| Criterion | Pass | Fail |
|---|---|---|
| Azurite semantics (never-written delete, seed once, missing key = 404 service answer) | 10/10 | **arm B** (drop `If-None-Match`): seed test red, second bytes served. **arm C** (wrap `BlobStorageException`): missing-key test red, got `StorageUnavailableException`. **arm D** (`delete()` in place of `deleteIfExists()`): 3 tests red (`Expecting value to be true but was false`). Each restore verified by hash `02c1c9d9…`; closing run 10/10 |
| closed port -> StorageUnavailableException (A4) | `AzureBlobObjectStoreTest` 2/2 in 1.7 s | **arm A** (rethrow raw): both red, and the raw type was `reactor.core.Exceptions$ReactiveException` wrapping netty `AnnotatedConnectException`. That confirms A4: the refusal is not a `BlobStorageException` |
| local Trivy with CI flags exits 0 | `docker run aquasec/trivy:0.70.0 image --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 --format table jtoye-core-java-scan:36-01` returned **rc=0**. Report Summary: alpine 3.24.2 **0**, app/app.jar **0**. CI's trivy-action pin `ed142fd0…` defaults to v0.70.0 (read from its action.yaml) | same image with `--severity CRITICAL,HIGH,MEDIUM,LOW,UNKNOWN` and without `--ignore-unfixed`: **rc=1**, `Total: 5 (MEDIUM: 5)`, all pre-existing jars (jackson-databind CVE-2026-54515, commons-lang3 CVE-2025-48924, log4j-api CVE-2026-49844). No bump was needed |
| check-doc-versions exits 0 | rc=0, `PASS: all 148 version claim(s) across 7 doc(s)` | before the edit the gate VOIDed (rc=2, `could not resolve the real version for 'AWS SDK v2'`). CLAUDE.md Blob claim set to 12.35.0: **rc=1** `DRIFT Azure Storage Blob SDK doc=12.35.0 actual=12.35.1`. AGENTS.md Identity claim set to 1.18.5: **rc=1**. Both restored by hash; closing rc=0 |
| no "AWS SDK" in CLAUDE.md, AGENTS.md, check-doc-versions.sh | `rc=1` | at `97c89494`: 1 match in each of the 3 files |
| transitional check-env-contract red recorded | see Transitional reds | base-commit worktree: rc=0 PASS |

### Artifact-level proofs

- **Built image contents** (`unzip -l /app/app.jar` inside the built image): 192 libs. AWS SDK jars: 0; the only `aws`-named jar is `brave-propagation-aws-1.3.0` (Brave X-Ray header propagation, not the AWS SDK). JNA: 0. httpcore5: 0. Present: `azure-storage-blob-12.35.1`, `azure-identity-1.18.6`, `azure-core-1.59.1`, `azure-core-http-netty-1.16.7`, `msal4j-1.23.1`, `netty-tcnative-boringssl-static-2.0.81.Final`. The scan image was removed afterwards.
- **Secrets:** `gitleaks git --config .gitleaks.toml --log-opts=97c89494..HEAD` scanned 8 commits with no leaks (rc=0). Control: a scratch dir holding a random `AccountKey=` connection string gave `leaks found: 1` (rc=1).
- **T-36-03 logging:** none of the captured test logs (`test` and `integrationTest` XMLs) contains `AccountKey`, although the Azurite ITs build from a key-bearing connection string. The config log line appears in 138 IT XMLs, which shows the search can see the logs. The logged form is `mode=connection-string, endpointHost=localhost, publicContainer=jtoye-images, quarantineContainer=jtoye-quarantine`.
- **Dev boot without a store** (the real path): `RedisFaultInjectionIntegrationTest` and `PublicRateLimitIntegrationTest` each logged `Object store unreachable; skipping demo image seeding (0 image(s) applied…): Object store unreachable during putIfAbsent jtoye-images/…/products/seed/` exactly once, and both passed.

### Tests actually executed (final)

- `AzuriteStorageIntegrationTest` 10/10. `AzureBlobObjectStoreTest` 2/2. `StorageServiceTest` 20/20. `LegacyImageUploadPipelineTest` 11/11. `ShopBrandImageKeyTest` 8/8. `MediaQuarantineRetentionSweepTest` 6/6 (3 + 3 nested).
- **Full `:core-java:test`:** 163 classes, **1240 tests, 0 failures, 0 errors, 1 skipped**. The skip is `ProductLabelGoldenFileTest.captureGoldenOnce`, a pre-existing disabled capture helper.
- **Full `:core-java:integrationTest`:** 145 classes, **719 tests, 0 failures, 0 errors, 6 skipped**. The skips are pre-existing, in `FinancialSummaryGoldenFileTest` and three notification-listener ITs. Wall clock 24m58s with 4 forks.

## TDD Gate Compliance

| Task | RED | GREEN | Evidence |
|---|---|---|---|
| 1 | `b829e82f` | `e84349bf` | RED record passed through `gsd_run check tdd-red-evidence` and returned **RED_EVIDENCE_OK** (target_test_failed), target "A stored derivative is anonymously readable…". The first attempt was **INVALID_RED** (a fixture failure: the Testcontainers image-compatibility refusal), was fixed, and was not used |
| 2 | `b9ff002f` | `c951dfba` | unit record **RED_EVIDENCE_OK** (target "putBytes - a quarantine key goes to the PRIVATE container…"); Azurite record **RED_EVIDENCE_OK** (target "A quarantined upload lands in the PRIVATE container…") |
| 3 | `2347920c` (test) | none | **Unexpected GREEN, investigated.** The plan's Task 1 action already specifies every behaviour Task 3 tests: the If-None-Match 409 mapping, `deleteIfExists`, and the transport-versus-service exception split. So Task 3's tests prove existing behaviour, not new behaviour, and there is no GREEN commit. Their ability to fail is shown by break arms A, B, C and D above. This is recorded here as the gate requires, and it is not claimed as RED |

The RED records are JUnit XML reports translated faithfully to TAP (one line per testcase; a testcase is "not ok" if and only if it has `<failure>` or `<error>`), because the checker parses node TAP. Translator: `/tmp/claude-36-01/junit2red.js`. Records: `/tmp/claude-36-01/red-task1.json`, `red-task2.json`, `red-task2-it.json`.

## Decisions Made

- **Adapter shape (discretion).** The plan's port-plus-one-implementation seam, so the unit tests mock the 7-method port and never the SDK's fluent chain.
- **netty-tcnative kept** (assumption A2 not taken). Its fallback on the musl image cannot be proven locally, because Azurite is plain http. The jar is present in the image and Trivy-clean.
- **Package legitimacy.** `com.azure:azure-storage-blob` 12.35.1, `com.azure:azure-identity` 1.18.6 and `org.testcontainers:azure` 1.21.4 were checked against Maven Central and the official github.com/Azure/azure-sdk-for-java and testcontainers-java repos in 36-RESEARCH.md §Package Legitimacy Audit. The registry-legitimacy seam supports only npm, pypi and crates, so it could not run for Maven.
- **`create-containers`** exists as a property only. Nothing in main code acts on it yet: the fixture creates containers through the port, and the startup behaviour belongs to 36-06.

## Deviations from Plan

**1. [Rule 1: plan premise wrong] JNA stayed on the runtime classpath after the planned exclusion**
- **Found during:** Task 1, acceptance criterion 2.
- **Issue:** azure-identity 1.18.6's POM declares `net.java.dev.jna:jna-platform:5.17.0` directly, not only through `msal4j-persistence-extension`. With the planned exclusion alone, runtimeClasspath still had 2 jna lines, which fails the criterion.
- **Fix:** also `exclude(group = "net.java.dev.jna")` on azure-identity.
- **Evidence it is safe on the Workload Identity path:**
  - Class-file scan of the 104 classes in azure-identity: JNA is referenced only by the Windows credential store, the Linux keyring, the IntelliJ and VS Code caches, `PersistentTokenCacheImpl`, `PowershellManager`, and one `Platform.isWindows()` call in `IdentityClient.authenticateWithAzurePowerShell`.
  - Runtime probe on the JNA-free runtimeClasspath: `WorkloadIdentityCredential` builds, and `getTokenSync` goes through ClientAssertionCredential, IdentityClient and MSAL to `ConnectException` against a closed port (rc=0, no linkage error).
  - Control arm: `AzurePowerShellCredential` gives `NoClassDefFoundError: com/sun/jna/Platform` (rc=3), so the probe can detect the failure it looks for.
  - 36-06's credential-build test remains the in-repo proof.
- **Files:** `core-java/build.gradle.kts`. **Commit:** `80532db2`.

**2. [Rule 1: plan fact wrong] The closed-port connection string in the plan could not address a closed port**
- **Found during:** Task 3.
- **Issue:** the SDK builds the emulator endpoint from `DevelopmentStorageProxyUri`'s scheme and host only and always appends `:10000/devstoreaccount1`. Evidence: the `StorageEmulatorConnectionString` bytecode, and a built client whose `getAccountUrl()` is `http://127.0.0.1:10000/devstoreaccount1` for a proxy URI naming port 45678. The plan's form would reach a running dev Azurite, not the closed port.
- **Fix:** the test uses `BlobEndpoint=http://127.0.0.1:<released port>/devstoreaccount1`, which addresses the chosen port and holds no credential. It is still built through StorageConfig with maxTries=1 and tryTimeoutSeconds=2.
- **Files:** `AzureBlobObjectStoreTest.java`. **Commit:** `2347920c`.

**3. [Rule 1: test could not fail] The quarantine anonymous-GET test was blind to mis-routing**
- **Found during:** Task 2, break arm (2).
- **Issue:** it probed only `jtoye-quarantine/<key>`. With routing swapped, the object is simply absent there (404) while it is anonymously readable in `jtoye-images`, so the test stayed green on the exact regression it exists for.
- **Fix:** it now probes the key in both containers. Arm (2) then turns it red with status 200, as the plan requires.
- **Files:** `AzuriteStorageIntegrationTest.java`. **Commit:** `a8052fc7`.

**4. [Rule 3: blocking] Testcontainers refused the tag+digest image reference**
- **Found during:** Task 1, first RED attempt (INVALID_RED, a fixture failure).
- **Issue:** the tag parses into the repository part, so the image does not look like Azurite.
- **Fix:** `.asCompatibleSubstituteFor("mcr.microsoft.com/azure-storage/azurite")`, keeping the exact pin.
- **Files:** `AzuriteTestSupport.java`. **Commit:** `b829e82f`.

**5. [Scope note] The RED commit for Task 1 carries the compile surface of the swap**
- An assertion-level RED needs the new types to exist. `b829e82f` therefore includes the port, the config, the service swap and the ported tests, with `AzureBlobObjectStore` as an inert skeleton. `e84349bf` is only the adapter.

**Total deviations:** 5 (3 Rule 1, 1 Rule 3, 1 scope note). **Impact:** every one either makes a criterion capable of failing or corrects a factual premise; none widens scope.

## Transitional reds this plan knowingly causes (recorded, not fixed)

**`k8s/scripts/check-env-contract.sh`: rc=1, owned by 36-09.** It was rc=0 at `97c89494`, measured in a scratch worktree. Verbatim excerpt:
```
core-java
  (a) injected env names                           64
  (a) read by some application*.yml                57
  (a) allowlisted (reasoned)                       1
  (a) VIOLATIONS                                   6
  (b) distinct ${} placeholders                    158
  (b) VIOLATIONS (local-only default)              1

DIRECTION (a) VIOLATION [core-java] — env injected by the manifest but read by NO application*.yml:
  - S3_ACCESS_KEY
  - S3_BUCKET
  - S3_ENDPOINT
  - S3_PUBLIC_URL
  - S3_REGION
  - S3_SECRET_KEY

DIRECTION (b) VIOLATION [core-java] — placeholder whose default is LOCAL-ONLY and that no manifest supplies:
  - STORAGE_PUBLIC_URL  (default: 'http://localhost:10000/devstoreaccount1/jtoye-images'  — local-only token: 'localhost')

FAIL: the env contract is broken — see the violations above. Fix the manifest, the build args or the code; only widen an allowlist when the omission is genuinely reviewed and you can state why.
```

**`scripts/docs-freshness.sh`: rc=1, owned by 36-17 (single regeneration).** The tree now counts `java_test_methods: 1914` (was 1897; +17 = 5 StorageServiceTest + 10 Azurite + 2 adapter) and `java_test_files: 297` (was 295), giving `total_logical_invocations: 4059`. The prose counts in CLAUDE.md, AGENTS.md and README move with the same regeneration.

## Issues Encountered

- The machine's Python guard refuses heredoc Python outside a declared conda env, so the StorageService edits were made with the Edit tool instead. There was no effect on the result.
- `DemoDataSeeder` under the two dev-profile ITs now takes one exponential-retry cycle (maxTries 3) against `127.0.0.1:10000` before aborting. Both tests passed within their normal time.

## Known Stubs

None. The Task 1 RED skeleton of `AzureBlobObjectStore` was replaced in full by `e84349bf`.

## Left for later plans (observed, not in this plan's scope)

- `.planning/codebase/STACK.md:70` still claims the retired SDK. It is now ungated, because its row is gone. It is the upstream of AGENTS.md's generated stack block, and 36-15 owns it. A regeneration before 36-15 would copy the stale line back into AGENTS.md without turning any gate red.
- The rest of the object-store prose in CLAUDE.md and AGENTS.md, the build.gradle.kts forkEvery history (annotated, not rewritten), and repo-wide MinIO/S3 residue belong to 36-15 and the BLOB-09 gate.
- `storage.blob.create-containers` is bound but not acted on in main code. The startup shape validation and the access-level probe (D-02 fail-fast; BLOB-01 and BLOB-02's "asserted at boot") belong to 36-06.
- The pre-existing MEDIUM findings in jackson-databind, commons-lang3 and log4j-api are below the CRITICAL/HIGH gate and are not from this plan.

## Requirements

`BLOB-01` and `BLOB-02` are **not** marked complete. This plan delivers their storage-seam halves. "Misconfiguration fails at startup" (BLOB-01) and "asserted by core-java at boot in every environment" (BLOB-02) are delivered by 36-06.

## User Setup Required

None. This plan needs no external service configuration.

## Next Phase Readiness

- The storage spine is proven on Blob semantics, so 36-02 onwards (compose, k8s, backup, reseed, frontend) can build on `AzuriteTestSupport` and the `STORAGE_*` env names.
- Until 36-09 lands, `check-env-contract.sh` stays red, and until 36-17 lands, docs-freshness stays red. Both are expected.
- No compose service and no core-java container was started. The Azurite and Trivy images remain pulled locally; the scan image was removed.

## Self-Check: PASSED

- All 7 created files exist on disk. All 9 commit hashes resolve (`git cat-file -e`).
- The must_have artifact markers are present: `interface BlobObjectStore`, `deleteIfExists`, `WorkloadIdentityCredentialBuilder`, `@Tag("testcontainers")`, the Azurite digest, `containerFor`, `STORAGE_AUTH_MODE`.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-28*

---
phase: 36-azure-blob-storage-throughout
plan: 07
subsystem: storage
tags: [azure-blob, azurite, testcontainers, D-09, cross-tenant, rls, media-pipeline, quarantine]
status: complete

requires:
  - phase: 36-01
    provides: StorageService on the BlobObjectStore port, key-derived container routing, AzuriteTestSupport
  - phase: 36-06
    provides: the test-profile opt-out of the boot probe (these ITs create their own containers)
provides:
  - D-09 tenant guard in StorageService.delete(String), fail closed on an absent context
  - the D-09 proof on real storage, both unit-level and through the real attack path under NOSUPERUSER RLS
  - MediaPipelineAzuriteIntegrationTest, the Phase 24/27 pipeline on an unstubbed Azurite
  - AzuriteImagePinParityTest, fixture pin == both compose pins == dependency-horizons pin
affects: [36-11, 36-14, 36-17, phase-29-handoff]

actuals:
  tokens: 12770        # chars/4 over git diff 6331b523..HEAD (51080 chars), before this SUMMARY
  tasks: 3
  commits: 5           # MEASURED: git rev-list --count 6331b523..HEAD, before the SUMMARY commit
plan_head_before: 6331b5232b0d03aeb169d1c36a80484dfd259fdd

tech-stack:
  added: []
  patterns:
    - "A URL-addressed delete of a shared container is authorised by the key's tenant segment against TenantContext, fail closed"
    - "Storage outcomes are read back from the store (port read for private, anonymous GET for public), never inferred from a DB row"
    - "A transient outage is simulated by pausing the Testcontainers emulator, with max-tries=1 and a short try-timeout"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/storage/StorageDeleteTenantGuardIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductImageCrossTenantBlobDeleteIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaPipelineAzuriteIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storage/AzuriteImagePinParityTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
    - core-java/src/test/java/uk/jtoye/core/storage/StorageServiceTest.java

key-decisions:
  - "The guard also refuses keys with '.'/'..' segments, a backslash or '%' (Rule 2). The equality check alone passes 'A/../B/...' on a store that normalises paths; Azurite does not, so this is proven at unit level only"
  - "The corrupt-upload case asserts the SHIPPED behaviour, which discards the bytes (failAndDiscard). failRetainingBytes is proven separately, with a real outage (emulator paused during the read)"
  - "BLOB-03 marked complete. BLOB-02 is NOT: its 'asserted at boot in every environment' needs the real runtime boot, which is 36-06 D6, open, owned by 36-11/36-17, and its staging half is a Phase 29 read-back"
  - "Rejecting foreign-tenant URLs at create/update time was not done. The guard is the fix D-09 decided; rejection-on-write is recorded as a note only"

patterns-established:
  - "An RLS-real cross-tenant proof goes through the real attack path (the client-supplied URL persisted by the service) under NOSUPERUSER, with a same-tenant control that shows the probe can see a deletion"

requirements-completed: [BLOB-03]   # plan declares [BLOB-03, BLOB-02]; BLOB-02 deliberately left open (see key-decisions)

coverage:
  - id: D1
    description: "StorageService.delete(url) deletes only a key whose tenant segment is the TenantContext tenant; foreign tenant, no context, no tenant segment, and dot/encoded traversal are refused with a WARN; external URLs are still skipped"
    requirement: BLOB-02
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storage/StorageServiceTest.java#d09* (6 cases, 26/26 in class)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storage/StorageDeleteTenantGuardIntegrationTest.java (3/3, Azurite)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Through the real attack path under NOSUPERUSER RLS, tenant A saving tenant B's URL on its own product and then removing the image or deleting the product leaves B's image served (200); A's own image is deleted (404)"
    requirement: BLOB-02
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/product/ProductImageCrossTenantBlobDeleteIntegrationTest.java (3/3, Postgres + Azurite)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Media pipeline on unstubbed storage: raw bytes are privately quarantined; the worker publishes a WebP derivative and thumbnail (200, image/webp, immutable); the raw is then gone and a checked delete reports gone; a corrupt upload ends FAILED with no derivative; a transient outage ends FAILED with the bytes retained"
    requirement: BLOB-03
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/media/MediaPipelineAzuriteIntegrationTest.java (3/3, Postgres + Azurite, no spy/mock)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The Testcontainers Azurite pin equals the compose (full stack and hybrid) and dependency-horizons pins"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storage/AzuriteImagePinParityTest.java (3/3)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The existing stubbed media suites still pass (regression only, not storage evidence)"
    requirement: BLOB-03
    verification:
      - kind: integration
        ref: "full :core-java:integrationTest, uk.jtoye.core.media.* = 20 classes / 67 tests, 0 failures, 0 errors"
        status: pass
    human_judgment: false

duration: 43min
completed: 2026-09-29
---

# Phase 36 Plan 07: Tenant-Guarded URL Delete and the Pipeline on Real Storage Summary

**`StorageService.delete(url)` now refuses any key outside the caller's tenant (D-09), and fails closed when there is no tenant context. The cross-tenant delete it closes was reproduced end to end first, under NOSUPERUSER RLS: tenant A saved tenant B's image URL on its own product and removed it, and B's image returned 404. The Phase 24/27 media pipeline is now proven against a real Azurite with nothing stubbed.**

## Performance

- **Duration:** 43 min
- **Started:** 2026-09-28T23:52:05Z
- **Completed:** 2026-09-29T00:35:22Z
- **Tasks:** 3 of 3
- **Files:** 6 (4 created, 2 modified; 1 main-code file)

## Accomplishments

- **D-09 closed (T-36-25, T-36-27).** After extracting the key, `delete(String)` checks four things. It needs a non-empty first segment. It needs a `TenantContext`, and fails closed without one. The segment must equal the context tenant. The key must have no `.`/`..` segment, no `\` and no `%`. On any failure it WARNs with the key (query stripped) and returns. `deleteByKey`/`deleteByKeyChecked` are unchanged.
- **The defect was reproduced before the fix, through the real attack path.** `ProductService.createProduct` persists a client-supplied `imageUrl` (ProductMapper). Tenant A created a product carrying tenant B's URL and called `removeImage` / `deleteProduct`. The test ran on real Postgres with the role downgraded to NOSUPERUSER. B's image went from 200 to **404**. After the fix it stays 200, with its bytes intact.
- **The pipeline on real storage (T-36-26).** Accept puts the exact raw bytes in `jtoye-quarantine`, and neither container serves them anonymously. The worker makes the asset ACTIVE. The `.webp` derivative and `_thumb.webp` are anonymously readable, with `image/webp`, `public, max-age=31536000, immutable` and RIFF/WEBP magic. The quarantine blob is then gone (`deleteIfExists` → false), and `deleteByKeyChecked` still reports gone (true).
- **27-01's two failure dispositions, both on real storage.** A corrupt upload ends FAILED with no derivative, its hostile bytes discarded and the sentinel closed. A real outage during the read (the emulator paused) ends FAILED with "Could not read the quarantined upload" and the sentinel open. The quarantine bytes survive byte for byte.
- **The pin cannot drift silently.** `AzuriteTestSupport.AZURITE_IMAGE` must equal the default in `docker-compose.full-stack.yml`, the default in the hybrid `infra/docker-compose.yml`, and the azurite row's pin in `infra/dependency-horizons.yaml`.

## Task Commits

1. **Task 1: D-09 guard (TDD)**
   - `000d4db7` test(36-07): add failing tests for the D-09 cross-tenant URL delete (RED)
   - `67c2bb78` feat(36-07): refuse a URL delete outside the caller's tenant (D-09) (GREEN)
   - `696df168` test(36-07): label the Azurite traversal case as store evidence
2. **Task 2: pipeline on unstubbed Azurite**: `0241b82a` test(36-07): prove the media pipeline on an unstubbed Azurite
3. **Task 3: pin parity**: `e02b1ba0` test(36-07): keep the test Azurite and the stack Azurite on one pin

## Files Created/Modified

- `storage/StorageService.java`: the D-09 guard in `delete(String)`, plus the package-private `isPlainPath(key)` and the private `withoutQuery(key)`. Javadoc names the callers and why they all carry a context.
- `storage/StorageServiceTest.java`: 6 D-09 cases and an `@AfterEach` context clear. The two existing delete tests now set the owning tenant (see deviation 2).
- `storage/StorageDeleteTenantGuardIntegrationTest.java` (new, `@Tag("testcontainers")`, no Spring): 3 tests on Azurite.
- `product/ProductImageCrossTenantBlobDeleteIntegrationTest.java` (new, `@Tag("testcontainers")`, Spring + Postgres + Azurite, NOSUPERUSER): 3 tests (deviation 1).
- `media/MediaPipelineAzuriteIntegrationTest.java` (new, `@Tag("testcontainers")`, Spring + Postgres + Azurite): 3 tests, no `@SpyBean`/`@MockBean`.
- `storage/AzuriteImagePinParityTest.java` (new, untagged unit test): 3 parameterised cases.

## Evidence: every criterion, both directions

All runs used `cleanTest`/`cleanIntegrationTest`, and the logs show the test task executing, not UP-TO-DATE. Counts come from the `core-java/build-local/test-results` XML. Logs are in `/tmp/claude-36-07/`.

### Task 1 (D-09)

| Criterion | Pass (real tree) | Fail direction |
|---|---|---|
| RED recorded | n/a | Unit: 26 run, **4 failed** with `NeverWantedButInvoked: store.deleteIfExists` (foreign tenant, no context, no segment, traversal). Azurite IT: 3 run, **2 failed**: `tenant B's blob after tenant A asked to delete … expected: 200 but was: 404` and `blob after an unscoped delete … expected: 200 but was: 404`. RLS IT: 3 run, **2 failed**: `tenant B's image … after tenant A removed it from its own product expected: 200 but was: 404` (and the same for deleteProduct); the same-tenant control passed. `gsd_run check tdd-red-evidence` returned **RED_EVIDENCE_OK** (target_test_failed) on all three records (`red-task1-{unit,it,rls}.json`) |
| Unit + GdprServiceTest green | StorageServiceTest 26/26, GdprServiceTest 6/6, rc=0 | RED above |
| Guard-removal arm (equality check disabled) | closing run: all 4 IT classes green (3+3+7+3), unit 26/26 | **Azurite IT**: B-survives test red, `expected: 200 but was: 404`. **RLS IT**: removeImage and deleteProduct both red, `expected: 200 but was: 404`. That is the cross-tenant delete **succeeding** on the broken arm. **Unit**: the foreign-tenant case red. Restored by `git checkout` from the committed state; hash verified `7ec62566…` |
| `TenantContext.set` arm (set made a no-op, per the tenant-pin trap) | closing runs green | Unit: 4 red. Own-tenant delete, both existing delete tests (store never reached), and the foreign-tenant WARN (the no-context WARN fired instead) all failed. Azurite IT: **positive control red**, `tenant A's own blob IS deleted … expected: 404 but was: 200`. B's blob still survived, so the guard failed closed. This shows the guard reads the live context. Restored, hash `6ac43e4e…` |
| Path-check arm (`isPlainPath` disabled) | closing green | Unit traversal case red. The Azurite traversal IT stayed **green** here, and also on the RED run with no guard at all. See deviation 3 |
| `git grep -n 'TenantContext.get()' -- …/StorageService.java` in delete(String) | `287: Optional<UUID> contextTenant = TenantContext.get();` (delete spans lines 271-308), rc=0 | the same grep at the RED commit `000d4db7`: empty, rc=1 |
| Caller suites pass | ProductImageDeleteIntegrationTest 3/3, ShopImageCrossTenantIntegrationTest 7/7, GdprServiceTest 6/6. No caller fixture asserted a cross-tenant delete, because all three stub or mock StorageService | n/a |

### Task 2 (pipeline)

| Criterion | Pass | Fail direction |
|---|---|---|
| IT ≥ 2 tests, 0 failures | 3/3 (ACTIVE path 0.45 s, corrupt 1.0 s, outage 3.2 s). Worker log lines: `disposition=discarded reason=Failed to read image header`, `media_process_active`, `disposition=bytes_retained` | arms below |
| `git grep -n -E '@SpyBean\|@MockBean'` on the file prints only rc=1 | rc=1, re-run **after** the commit. Before the commit the file was untracked, so `git grep` could not see it. That first rc=1 was vacuous and is not counted | a planted `// @SpyBean planted` on the class line: rc=0 (line 71). Restored, hash `828ee79e…` |
| Arm (1): the worker's quarantine delete skipped (MediaProcessingWorker:273) | closing 3/3 | ACTIVE-path test red: `the worker already deleted the quarantined raw … Expecting value to be false but was true`. Restored, hash `69dc0a7f…` |
| Arm (2): the corrupt fixture fed through the success path | closing 3/3 | red on the DB assertion first (`expected: ACTIVE but was: FAILED`). That alone does not prove the storage assertions can fail, so arm **2b** also removed the two DB-row assertions. It went red on storage: `anonymous GET of <derivative url> expected: 200 but was: 404`. Restored, hash `828ee79e…` |
| Extra arm (3): the read failure calls failAndDiscard instead of failRetainingBytes | closing 3/3 | the outage test red: `sentinel open: the bytes still exist expected: null but was: 2026-09-29T00:07:49Z`. Restored, hash `69dc0a7f…` |

### Task 3 (pin parity and regression)

| Criterion | Pass | Fail direction |
|---|---|---|
| Parity test | 3/3 (full stack, hybrid, horizons) | **digest arm**: the last hex digit of the fixture digest changed `…fd5`→`…fd6`. All 3 red, and the message names both values and `infra/dependency-horizons.yaml`. Restored, hash `b287f46a…` |
| Fails (never passes) when a line or file is missing | n/a | the hybrid compose var renamed to `AZURITE_TAG`: red, `expected exactly one image line … found 0`. The horizons parameter pointed at a missing file: red, `Could not locate repository file 'infra/no-such-horizons.yaml' … refusing to proceed`. Both restored by hash (`34801a22…`, `75cd67c1…`) |
| Media package regression | `uk.jtoye.core.media.*` in the full integration run: **20 classes, 67 tests, 0 failures, 0 errors, 0 skipped**. That is the 19 pre-existing classes plus MediaPipelineAzuriteIntegrationTest (3). **Regression only.** The 19 pre-existing classes stub or spy StorageService, or never touch storage, so they are not evidence of storage behaviour (RESEARCH Pitfall 1). The storage evidence is MediaPipelineAzuriteIntegrationTest | n/a. The counts come from the full run's XML, not a separate `--tests 'uk.jtoye.core.media.*'` invocation; it is the same task over a superset |

### Full suites (after all changes, HEAD `e02b1ba0`)

- **`:core-java:test`**: 166 classes, **1297 tests, 0 failures, 0 errors, 1 skipped**. The skip is the pre-existing `ProductLabelGoldenFileTest.captureGoldenOnce`. rc=0.
- **`:core-java:integrationTest`**: 149 classes, **738 tests, 0 failures, 0 errors, 6 skipped**, in 21m18s, rc=0. The skips are the same pre-existing four classes 36-01 recorded: FinancialSummaryGoldenFileTest plus the Onboarding, Financial and Order notification-listener ITs. No existing class went red from the new check.
- No container left behind: `docker ps -a` is empty after the runs. Testcontainers/ryuk managed every container; none was started by hand.

## TDD Gate Compliance

| Task | RED | GREEN | Notes |
|---|---|---|---|
| 1 | `000d4db7` | `67c2bb78` | three RED records, all **RED_EVIDENCE_OK** |
| 2 | none | none | **Unexpected GREEN, investigated.** The task adds no production behaviour. It proves the existing pipeline on real storage, so the tests passed on first run. Break arms 1, 2b and 3 show they can fail. This is recorded here, not claimed as RED |
| 3 | n/a | n/a | `type="auto"`, not TDD. The digest and missing-input arms show the test can fail |

## Decisions Made

- The key's tenant segment must match the `UUID.toString()` form exactly, which is case-sensitive. Every key is minted from `UUID.toString()`, and blob names are case-sensitive, so an upper-cased own-tenant URL never names a stored object anyway.
- `'%'` is refused outright. Measured: none of the 21 bundled demo seed images has `%` in its name, and every other key is `<uuid>/<prefix>/<uuid>/<uuid>.webp`.
- The corrupt-upload case asserts discard, as the code ships (27-01 D-07). The plan's must-have said the bytes are retained; that describes `failRetainingBytes`, which only a read failure reaches. Both paths are now proven on real storage (deviation 4).
- BLOB-02 stays open (see key-decisions).
- Rejecting a foreign-tenant URL at create or update time (ProductMapper/ShopMapper/ReviewService) is **not** done. It is recorded here as a note. Today such a URL can still be SAVED, and it still renders another tenant's image on the attacker's product. It can no longer DELETE anything.

## Deviations from Plan

**1. [Rule 2, orchestrator override 6] An RLS-real proof through the real attack path**
- **Found during:** Task 1.
- **Issue:** the plan's D-09 IT has no Spring context and no database. The dispatch required a proof on Testcontainers Postgres with the NOSUPERUSER role, and a break arm showing the cross-tenant delete succeeding.
- **Fix:** added `ProductImageCrossTenantBlobDeleteIntegrationTest`. It uses the `ShopImageCrossTenantIntegrationTest` recipe: ALTER ROLE NOSUPERUSER inside the test transaction. It drives `createProduct` → `removeImage`/`deleteProduct`, which is the client-URL attack path. Each test first asserts `rolsuper=false`, that tenant A **sees** its own row (so the verification query can see rows), and that tenant B does not. It has a same-tenant control.
- **Verification:** RED 2/3 (B's image 404), GREEN 3/3, guard-removal arm 2/3 red.
- **Commit:** `000d4db7`.

**2. [Rule 1] Two existing unit tests deleted with no tenant context**
- `testDelete_ValidStoredUrl` and `testDelete_StorageError` called `delete(url)` with no `TenantContext`. No production caller does that. Under the guard, the first would fail and the second would pass vacuously, because the store would never be reached. Both now set the owning tenant. `testDelete_StorageError` also verifies that the store was called, so its catch is really exercised. Neither asserted a cross-tenant delete. **Commit:** `000d4db7`.

**3. [Rule 2 + a test that cannot fail] Traversal hardening, and the honest label on its Azurite case**
- **Issue:** the plan's first-segment check alone accepts `<A>/../<B>/…`. On a store that normalises paths, that would delete B's key.
- **Fix:** `isPlainPath` refuses dot segments, `\` and `%`, proven by the unit case (RED, then green; its arm is red).
- **Measured:** the Azurite version of the case stayed green with **no guard at all** (the RED run) and with only the path check removed. Azurite and the SDK address such keys literally.
- **Label:** that IT case is therefore relabelled "store-behaviour evidence, NOT evidence of the guard". It is kept for the day a store normalises paths. **Commit:** `696df168`.

**4. [Rule 1: plan premise wrong] "A corrupt upload … its quarantine bytes retained (failRetainingBytes)"**
- **Issue:** in the shipped worker, an undecodable upload takes `failAndDiscard` (27-01 D-07: hostile bytes are discarded). `failRetainingBytes` is reached only when the read fails. Asserting retention for a corrupt upload would have required changing Phase 27 behaviour, which this phase excludes.
- **Fix:** the corrupt case asserts FAILED, no derivative, bytes discarded and the sentinel closed. A separate test proves `failRetainingBytes` with a genuine transient outage: `docker pause` on the emulator, with `max-tries=1` and `try-timeout-seconds=3` for this class only. The bytes survive byte for byte and the sentinel stays open. **Commit:** `0241b82a`.

**5. [Rule 2] The parity test also covers the hybrid compose and the horizons row**
- The plan named only `docker-compose.full-stack.yml` and asked for a message pointing at the horizons file. `infra/docker-compose.yml` (D-08) carries a third copy of the pin, and the horizons row a fourth. All three are asserted, so none can drift. **Commit:** `e02b1ba0`.

**Total deviations:** 5 (3 Rule 2, 2 Rule 1). **Impact:** each makes a criterion able to fail, corrects a premise, or closes a bypass. The one main-code change is the D-09 guard the plan asked for.

## Issues Encountered

- A pre-commit `git grep` on a file not yet committed is vacuous: `git grep` skips untracked files. It was caught and re-run after the commit (Task 2 table).

## Known reds inherited (not fixed here)

- `scripts/docs-freshness.sh` exits 1, owned by 36-17. This plan moves the Java counts by **+15 `@Test` methods and +3 files**. Measured with `git grep -E '^\s*@Test\b'` at `6331b523` against HEAD: 1949/300 → 1964/303. The script's own counter reads `java_test_methods 1965`, `java_test_files 303` at HEAD. `AzuriteImagePinParityTest` uses `@ParameterizedTest`, so it adds 0 `@Test` methods.
- `k8s/scripts/check-env-contract.sh`, owned by 36-09: not re-run. This plan adds no env read and no `${...}` placeholder in main code.

## Threat Flags

None. No new endpoint, auth path or schema. The change narrows an existing delete path.

## User Setup Required

None.

## Next Phase Readiness

- The D-09 guard is in the image the next runtime rebuild ships. Every production caller already runs under a tenant context.
- 36-11 / 36-17 own the first real runtime boot (36-06 D6), which BLOB-02 still needs.
- Every later Blob IT can copy `MediaPipelineAzuriteIntegrationTest`'s shape: Postgres + Azurite, containers created in `@BeforeEach`, and verdicts read back from the store.

## Self-Check: PASSED

- All 6 key files FOUND on disk.
- All 5 task commits FOUND (`000d4db7`, `67c2bb78`, `696df168`, `0241b82a`, `e02b1ba0`). `git rev-list --count 6331b523..HEAD` = 5.
- Must-have markers present: `AzuriteTestSupport` in the pipeline IT (11 references), `MediaProcessingWorker` (3), `TenantContext` in the guard IT (8), `docker-compose.full-stack.yml` in the parity test (1), `TenantContext.get` inside `delete(String)` (line 287).
- No AI-attribution lines in any commit of this plan: a case-insensitive scan for co-authored, claude and generated over `6331b523..HEAD` returned rc=1.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

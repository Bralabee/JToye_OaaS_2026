---
phase: 38-spring-boot-4-1-migration
plan: 10
subsystem: api
tags: [jackson3, idempotency, boot4-migration, golden-fixtures, rls, dsar, jsonb, persisted-json]

requires:
  - phase: 38-01
    provides: "jackson2-golden/idempotency (7 fingerprints + request-hashes.tsv) and responses/ (OrderDto, CustomerDto, MediaAcceptDto, WebhookDeliveryView, DsarIntakeAck), GoldenSamples"
  - phase: 38-02
    provides: "JsonbColumnsReadBackIntegrationTest and its corrupted-span fail arm"
  - phase: 38-03
    provides: "core-java on Boot 4.1.1 with the INTERIM spring-boot-jackson2 bridge"
provides:
  - "IdempotencyJson: the frozen idempotency JSON format (builderWithJackson2Defaults + Boot 3.5's mapper settings), owning request_hash and response_body"
  - "IdempotencyService without any injected mapper; serialize/deserialize route through IdempotencyJson"
  - "DsarIntakeService on Boot's Jackson-3 JsonMapper"
  - "IdempotencyFingerprintGoldenTest (13 tests), IdempotencyLegacyHashReplayIntegrationTest (rls_test_role, 2 tests), DsarAckCompatibilityTest (2 tests)"
  - "evidence/38-10-idempotency.txt"
affects: [38-12, 38-16, 38-17, 38-18, 38-19]

actuals:
  tokens: 11157    # chars/4 over the added lines of 295e37df..d6da758a (44628 chars, 7 files incl. the evidence file)
  tasks: 2
  commits: 6       # git rev-list --count 295e37df..d6da758a, before this SUMMARY's commit
plan_head_before: 295e37df81856de34759ecb4ba3414530d8405f0
plan_head_after: d6da758ae593e60cb05673b75f37f58642923823

tech-stack:
  added: []
  patterns:
    - "A persisted JSON format is owned by a dedicated, frozen mapper (IdempotencyJson), never by the app-wide mapper; a change needs a dual-hash window"
    - "RED against a constructor signature that does not exist yet: reflective lookup that fails as an assertion, replaced by a direct call in a REFACTOR commit"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyJson.java
    - core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java
    - core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyLegacyHashReplayIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/DsarAckCompatibilityTest.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-10-idempotency.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeService.java

key-decisions:
  - "IdempotencyJson = JsonMapper.builderWithJackson2Defaults() minus WRITE_DATES_AS_TIMESTAMPS, WRITE_DURATIONS_AS_TIMESTAMPS, FAIL_ON_UNKNOWN_PROPERTIES and DEFAULT_VIEW_INCLUSION (Boot 3.5's JacksonAutoConfiguration). No other feature: all 7 golden hashes and all 4 stored response types matched with exactly these."
  - "HibernateJsonFormatConfig NOT created: JsonbColumnsReadBackIntegrationTest is green 3/3 on Boot 4.1.1 / Hibernate 7 (its fail direction is 38-02's corrupted-span arm)."
  - "Continuity comes from the hash itself matching; the legacyRequestBody seam is untouched (git diff of IdempotencyService names no 'legacy' line)."

patterns-established:
  - "Persisted-format freeze: a package-private final class with a private static mapper, a Javadoc stating why it is frozen, what it reproduces, the proof test and the dual-hash rule"

requirements-completed: [BOOT4-08]  # plan declares [BOOT4-08, BOOT4-04]; requirements.ready-ids reports 1/2 ready: BOOT4-08 ready (38-01, 38-02, 38-10 all summarised), BOOT4-04 blocked (38-12, 38-19 not summarised)

coverage:
  - id: D1
    description: "Every adopter's idempotency fingerprint is byte-identical to the Boot-3.5 bytes and its sha256 equals the stored golden hash (7 rows)"
    requirement: BOOT4-08
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java#fingerprintMatchesTheBoot35Hash (7 rows) + everyHashRowHasASampleAndEverySampleARow"
        status: pass
    human_judgment: false
  - id: D2
    description: "The four stored response types cross the deploy both ways: the Boot-3.5 body reads into an equal DTO (by instant) and IdempotencyJson writes the sample byte-identical to the fixture"
    requirement: BOOT4-08
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java#storedResponseCrossesTheDeployBothWays (4 rows) + instantComparisonIsNotVacuous"
        status: pass
    human_judgment: false
  - id: D3
    description: "A Boot-3.5 orders.create reservation replays its original 201 under the NOSUPERUSER rls_test_role without running the work; tenant B under the role cannot see it and gets a fresh reservation"
    requirement: BOOT4-08
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyLegacyHashReplayIntegrationTest.java (2 tests)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The DSAR acknowledgement a Boot-3.5 pod stored replays unchanged through DsarIntakeService on Boot's Jackson-3 JsonMapper, and a fresh lodge stores the Boot-3.5 bytes"
    requirement: BOOT4-08
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/gdpr/DsarAckCompatibilityTest.java (2 tests)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/gdpr/DsarIntakeIntegrationTest.java (13 tests)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The three jsonb columns still read back on Boot 4 (no HibernateJsonFormatConfig needed)"
    requirement: BOOT4-08
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/JsonbColumnsReadBackIntegrationTest.java (3 tests)"
        status: pass
    human_judgment: false
  - id: D6
    description: "IdempotencyService, IdempotencyJson and DsarIntakeService carry no Jackson-2 databind/core import (part of BOOT4-04, which stays open)"
    requirement: BOOT4-04
    verification:
      - kind: other
        ref: "git grep -n -e 'com.fasterxml.jackson.databind' -e 'com.fasterxml.jackson.core' -- core-java/src/main/java/uk/jtoye/core/common/idempotency core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeService.java -> rc=1 (rc=0 with 4 lines at 295e37df)"
        status: pass
    human_judgment: false

duration: 21min
completed: 2026-10-05
status: complete
---

# Phase 38 Plan 10: Idempotency fingerprint, stored responses, DSAR ack and jsonb continuity Summary

**A frozen `IdempotencyJson` mapper (Jackson 3's Jackson-2-defaults builder plus Boot 3.5's four mapper settings) now owns `idempotency_keys.request_hash` and `response_body`. It reproduces all 7 golden fingerprints and all 4 stored response bodies byte for byte. A key reserved on a Boot-3.5 pod replays its original 201 on Boot 4 under the NOSUPERUSER role, not the 422.**

## Performance

- **Duration:** 21 min
- **Started:** 2026-10-05T11:47:13Z
- **Completed:** 2026-10-05T12:08:43Z
- **Tasks:** 2 (both TDD: RED → GREEN, plus one REFACTOR)
- **Files modified:** 7 (4 created source/test files, 2 modified main files, 1 evidence file)

## Accomplishments

- **The format no longer follows the app-wide mapper.** `IdempotencyJson` is package-private and final, and its mapper is a private static. `IdempotencyService` has no mapper field at all. Its Javadoc says why the format is frozen, what it reproduces and which test proves it. It also gives the rule for any future change: add a second accepted hash in a deliberate dual-hash window, and never edit the mapper in place.
- **The golden proof covers every adopter.** `IdempotencyFingerprintGoldenTest` has 13 tests:
  - 7 `request-hashes.tsv` rows, each checked by sha256 and by byte identity;
  - 4 stored response types, each in both directions;
  - a coverage test that ties the tsv rows to exactly the 7 samples;
  - a comparator control: a 1 ns shift still fails.
- **The replay is proven under the runtime-like role.** `IdempotencyLegacyHashReplayIntegrationTest` seeds the Boot-3.5 row: the golden hash and the golden `OrderDto` body. Under `SET LOCAL ROLE rls_test_role`, a retried `orders.create` gets 201 and the stored order, compared by instant, and the work runs 0 times. In the tenant-B control, the unscoped count is 1 as superuser and 0 under the role. Tenant B's identical key is a fresh reservation (the work runs once).
- **DSAR is on Boot's Jackson-3 `JsonMapper`.** The golden Boot-3.5 acknowledgement replays unchanged through `lodge → replay → deserialize`, and a fresh lodge stores the Boot-3.5 bytes.
- **jsonb needs nothing new.** `JsonbColumnsReadBackIntegrationTest` is green 3/3 on Boot 4, so `HibernateJsonFormatConfig` was not created.

## Task Commits

1. **Task 1 (tracer): a Boot-3.5 orders.create reservation replays through Boot 4 under NOSUPERUSER**
   - `c8108e61` test(38-10): RED. The hash was `10da4462…` where `7c58d685…` was expected, and the replay threw `IdempotencyPayloadMismatchException`.
   - `3b1a5af0` feat(38-10): GREEN.
2. **Task 2: every adopter's golden hash, response bodies both ways, DSAR ack and jsonb continuity**
   - `61796405` test(38-10): RED. Both DSAR tests failed; the golden extension was already green on the Task 1 tree.
   - `333450da` feat(38-10): GREEN.
   - `c8699df7` refactor(38-10): the DSAR test builds the service directly.
   - `d6da758a` docs(38-10): the arms, the restores, the closing runs and the regressions.

**Plan metadata:** the commit that adds this SUMMARY, then the STATE/ROADMAP/REQUIREMENTS commit.

## Files Created/Modified

- `core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyJson.java`: the frozen fingerprint and response-body mapper (`write`, `read`). It wraps `JacksonException` with the two existing messages.
- `core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyService.java`: the constructor is now `(JdbcTemplate, EntityManager)`. `serialize` and `deserialize` delegate to `IdempotencyJson`, and the class Javadoc gains the "Persisted format" paragraph. The `legacyRequestBody` path is untouched.
- `core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeService.java`: injects `tools.jackson.databind.json.JsonMapper` and catches `JacksonException`. The failure messages are unchanged.
- `core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java`: the golden-hash and response-body proofs.
- `core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyLegacyHashReplayIntegrationTest.java`: the NOSUPERUSER replay, with the tenant-B control.
- `core-java/src/test/java/uk/jtoye/core/gdpr/DsarAckCompatibilityTest.java`: the DSAR read path and the write bytes, against a JDBC stub.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-10-idempotency.txt`: every RED, GREEN, arm, restore and closing run.

## Decisions Made

- **The mapper settings are exactly the four in the plan's interfaces.** No other feature was added, because no measured golden mismatch remained.
  - `builderWithJackson2Defaults()` already turns off property sorting and forward-slash escaping, among others. Its full feature list was read from the 3.1.7 bytecode and is in the evidence file.
- **`HibernateJsonFormatConfig` was not needed.** The jsonb read-back is green on Boot 4.
- **The DSAR RED went through a reflective constructor lookup.** The service still took the Jackson-2 bean, so the test failed as an assertion rather than a compile error (`RED_EVIDENCE_OK`). A REFACTOR commit then replaced the lookup with a direct call.

## Measured findings (for later plans)

- **Plain Jackson 3 sorts CLASS properties alphabetically but keeps RECORDS in declaration order.** In the plain-mapper arm, only 4 golden rows went red:
  - the `orders.create` fingerprint;
  - both `storefront.guest-order` fingerprints;
  - the `OrderDto` body.

  The record-shaped adopters (customers.create, media.upload, media.reprocess, webhooks.replay) would have kept their hashes anyway. The frozen mapper is what saves orders and guest checkout, which are the highest-volume creates. This agrees with 38-05's "records are byte-identical".
- **The Jackson-2-defaults builder turns `FAIL_ON_UNKNOWN_PROPERTIES` and `WRITE_DATES_AS_TIMESTAMPS` back on.** Raw Jackson-2 defaults are not Boot 3.5's mapper. That is why the Boot-3.5 deltas have to be re-applied on top of it.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] MediaUploadIdempotencyTest cannot run in the plan's unit command**
- **Found during:** Task 2 RED
- **Issue:** `MediaUploadIdempotencyTest` is `@Tag("testcontainers")`, and `tasks.test` excludes that tag (`core-java/build.gradle.kts` l.316-323). The plan's `:core-java:test --tests '…MediaUploadIdempotencyTest'` therefore produced no XML, and Gradle stayed green because the other filters matched.
- **Fix:** The test was added to the Task 2 `integrationTest` command, where it ran 4/0. The plan's unit command was run unchanged, and the missing XML is recorded.
- **Files modified:** none (verification command only)
- **Verification:** `TEST-uk.jtoye.core.media.MediaUploadIdempotencyTest.xml` under `build-local/test-results/integrationTest/`, tests=4 failures=0.

**2. [Rule 2 - Missing critical] Extra regression classes and arm D**
- **Found during:** Task 2 GREEN and the arms
- **Issue:** Three more classes exercise `IdempotencyService` or `DsarIntakeService` and were not in the plan's regression list: `DsarSubjectAndGlobalRateLimitIntegrationTest`, `CrossTenantMcpWriteRlsIntegrationTest` and `WebhookDeliveryLogIntegrationTest`. The DSAR test also had no fail-direction arm.
- **Fix:**
  - The three classes were added to the integration run: 2/0, 3/0 and 11/0.
  - Arm D was added: drift the ACK status, and both DSAR tests go red. It was restored by blob and sha256.
  - The full unit suite was run once as a regression: 1468 tests, 0 failures.
- **Files modified:** none beyond the evidence file
- **Committed in:** `d6da758a` (evidence)

**3. [Plan wording] The tenant-B control is strengthened beyond "work IS invoked"**
- **Found during:** Task 1 RED
- **Issue:** `IdempotencyService` scopes its own queries by `tenant_id`. A tenant-B call would therefore never see tenant A's row, whether RLS works or not, so "work is invoked" alone cannot show the role is RLS-constrained.
- **Fix:** The control first runs an UNSCOPED count: 1 as superuser (positive control), then 0 under `rls_test_role` with tenant B's GUC. Only after that does it run `execute` and see the work run once.
- **Committed in:** `c8108e61`

---

**Total deviations:** 3: one blocking verify-command defect, one missing-coverage addition, one strengthened control.
**Impact on plan:** None of them changes scope. Each makes a check able to fail where the plan's wording could not.

## TDD Gate Compliance

- **Task 1:** RED `c8108e61` came before GREEN `3b1a5af0`.
- **Task 2:** RED `61796405` came before GREEN `333450da`, followed by REFACTOR `c8699df7`.
- **Gate greps:** `^test\((0*38)-(0*10)\):` finds 2 commits, `^feat\(…\):` 2 and `^refactor\(…\):` 1. The control grep `^feat\((0*38)-(0*99)\):` finds 0.
- **`gsd check tdd-red-evidence`:** RED_EVIDENCE_OK (target_test_failed) for 4 records:
  - Task 1: the orders.create fingerprint test and the Boot-3.5 replay test;
  - Task 2: both DSAR tests.

  Two records naming a test that did not fail gave INVALID_RED, as they should. The control test named in the Task 1 IT record gave `no_target_test_failure`. The golden test named as Task 2's target gave `nonzero_exit_without_test_failure`.
- **The Task 2 golden extension was green on RED.** This is recorded, not hidden: Task 1's GREEN already reproduced every row. Its fail direction is arm P, where 4 of its 13 tests went red.

## Issues Encountered

None blocking. The machine's search guard refused one `rg -c` on a gitignored build directory. It was re-run with `rg -uu`, because `build-local/` is gitignored.

## Known Stubs

None. The RED-only stub mapper in `IdempotencyJson` was replaced in `3b1a5af0`. The reflective DSAR constructor lookup was removed in `c8699df7`.

## Threat model

- **T-38-26** (hash drift): mitigated. The 7 hashes and 4 bodies are byte-identical. The replay test passes under the role. Arm P turns both red.
- **T-38-27** (cross-tenant replay): mitigated. The tenant-B control under `rls_test_role` shows an unscoped count of 0 and a fresh reservation.
- **T-38-28** (jsonb misread): mitigated. `JsonbColumnsReadBackIntegrationTest` is green, and its explicit config was not needed.
- **T-38-SC:** no package was installed, and the build file is unchanged.
- No new security surface.

## User Setup Required

None. No external service configuration is required, and no compose service was started, stopped or rebuilt.

## Next Phase Readiness

- **38-12:**
  - `IdempotencyService` and `DsarIntakeService` no longer inject the Jackson-2 `ObjectMapper`.
  - `IdempotencyJson`, `IdempotencyService` and `DsarIntakeService` carry no `com.fasterxml.jackson` import.
  - The new tests import only `tools.jackson` (DSAR via `BootJsonMapper`), so they need no allowlist entry.
- **38-16 / 38-18 (ADR-0006, PR body):**
  - Idempotency needs no deploy step: reservations made before the deploy keep matching.
  - The persisted idempotency format is now frozen in `IdempotencyJson`; a future change needs a dual-hash window.
  - DSAR acks and jsonb values read back unchanged.
- **38-19:** nothing new from this plan for the test sweep.
- **Requirements:** BOOT4-08 is ready to mark complete (1/2 from `requirements.ready-ids`). BOOT4-04 stays open, shared with 38-12 and 38-19.
- **Blocker:** none.

## Self-Check: PASSED

- All 5 created files were found on disk with `[ -f ]`.
- All 6 commits were found with `git cat-file -e`: `c8108e61`, `3b1a5af0`, `61796405`, `333450da`, `c8699df7`, `d6da758a`.
- The acceptance criteria were re-run in both directions; they are recorded in the evidence file.
- The plan-level verification is green: closing runs plus the full unit suite, 1468/0.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

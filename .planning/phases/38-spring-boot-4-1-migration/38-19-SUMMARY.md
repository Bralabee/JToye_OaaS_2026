---
phase: 38-spring-boot-4-1-migration
plan: 19
subsystem: testing
tags: [spring-boot-4, jackson-3, test-migration, interim-bridge, testcontainers, deliberate-jackson2]
status: complete

requires:
  - phase: 38-03
    provides: "the INTERIM-JACKSON2-BRIDGE line, and Boot's Jackson-3 JsonMapper bean beside the bridge's Jackson-2 bean"
  - phase: 38-05
    provides: "the jackson3-defaults decision and the wire diff (class properties alphabetical, records in declaration order)"
  - phase: 38-07
    provides: "testsupport/BootJsonMapper (Boot's auto-configured Jackson-3 JsonMapper for unit tests)"
  - phase: 38-08
    provides: "the two DELIBERATE-JACKSON2 classes, and JacksonJsonMessageConverter as the production converter"
  - phase: 38-10
    provides: "IdempotencyJson, the frozen Boot-3.5 fingerprint format, golden-pinned per adopter"
provides:
  - "All 24 test classes that autowired the bridge's Jackson-2 ObjectMapper (the plan's 23 plus the 38-07 residual TenantLifecycleAdminIntegrationTest) inject Boot's tools.jackson.databind.json.JsonMapper, and each is green with the bridge line removed"
  - "Nine local-mapper test files on JsonMapper.builder(); both golden-file tests migrated, with their comparison green and the goldens untouched"
  - "Five test files that reached Jackson 2 through Spring's deprecated adapters (invisible to the plan's grep and independent of the bridge) moved to the Jackson-3 adapters"
  - "The closed DELIBERATE-JACKSON2-LIST (3 files) in evidence/38-19-test-jackson3-sweep.txt: AmqpJackson2CompatibilityTest, OutboxPayloadCompatibilityTest, OpenApiSnapshotTest"
  - "The widened test-scan pattern 38-12's JacksonLineContractTest needs (com.fasterxml.jackson.{databind,core,datatype} OR a package-qualified Spring Jackson2*/MappingJackson2*/GenericJackson2* adapter)"
affects: [38-12, 38-14, 38-16]

actuals:
  tokens: 14180    # chars/4 over the added lines of c2c64e78..0a8916bd (56722 chars, 40 files)
  tasks: 3
  commits: 9       # MEASURED: git rev-list --count c2c64e78..HEAD at SUMMARY write
plan_head_before: c2c64e78448b2ee7b9f2b1c7d44a6d59f190c19b
plan_head_after: 0a8916bdaed57f7e80d19d003d7c7df57426a8e4

tech-stack:
  added: []
  patterns:
    - "A per-batch bridge-off probe: cp the build file, drop the marker line with awk, run, git checkout HEAD, sha256 of the restore against git show HEAD:<path>; always on a committed tree"
    - "A test that plants data a Boot-3.5 pod wrote (a legacy hash) uses the frozen Boot-3.5 format recipe, never Boot's app-wide Jackson-3 mapper"
    - "Unit tests that must write problem documents as production does use JacksonJsonHttpMessageConverter(BootJsonMapper.get()), not a bare mapper"

key-files:
  created:
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-19-test-jackson3-sweep.txt
  modified:
    - core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/GuestCheckoutIdempotencyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java
    - core-java/src/test/java/uk/jtoye/core/common/GlobalExceptionHandlerRequestShapeTest.java
    - core-java/src/test/java/uk/jtoye/core/common/OptimisticLockExceptionHandlerTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderEventFanoutTopologyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/MailhogAssertions.java
    - "(and 32 more test files; full list under Files Created/Modified)"

key-decisions:
  - "38-19: GuestCheckoutIdempotencyIntegrationTest plants its legacy body-only request_hash with IdempotencyJson's recipe (JsonMapper.builderWithJackson2Defaults() minus the four Boot-3.5 settings), which IdempotencyFingerprintGoldenTest pins to the Boot 3.5.16 bytes for this exact body. Boot's alphabetical Jackson-3 mapper made it a hash no Boot-3.5 pod wrote, and the owning-shop replay went red. That red was measured, and production is unchanged. Every assertion is kept"
  - "38-19: both golden-file tests are migrated (comparison green, golden untouched). Neither stays on Jackson 2; a bracketed golden-value arm turned each red"
  - "38-19: the five Spring-adapter Jackson-2 users the plan's grep cannot see are migrated, not allowlisted. The two exception-handler tests now use Boot's Jackson-3 mapper, which is what their own comments required on Boot 4; a bare-mapper arm proved the choice load-bearing"
  - "38-19: the DELIBERATE-JACKSON2-LIST is closed at 3 files, and 38-12's contract test must scan with the widened pattern, or a Spring-adapter regression passes it unseen"

patterns-established:
  - "Bridge-off probe per batch, restored by sha256 against the HEAD blob, on a committed tree"
  - "Reconciliation scan = the import grep plus a package-qualified Spring Jackson-2 adapter pattern; bare class names match Javadoc history and are not used"

requirements-completed: []  # plan declares [BOOT4-02, BOOT4-04]; requirements.ready-ids: 0/2 ready (both shared with 38-12 and later plans not yet summarised)

coverage:
  - id: D1
    description: "The 23 bridge-bean injectors (plus the 38-07 residual) inject Boot's Jackson-3 JsonMapper and pass with the bridge on and with the bridge line removed"
    requirement: "BOOT4-02"
    verification:
      - kind: integration
        ref: "evidence/38-19-test-jackson3-sweep.txt Batch A: 11 classes / 92 tests green, bridge ON and OFF (restore sha256 d9cb6f42... == HEAD blob)"
        status: pass
      - kind: integration
        ref: "evidence/38-19-test-jackson3-sweep.txt Batch B: 13 classes / 62 tests green, bridge ON and OFF (restore sha256 equal)"
        status: pass
      - kind: integration
        ref: "tracer: unmigrated ShopControllerIntegrationTest RED 6/6 with the bridge removed ('Unsatisfied dependency expressed through field objectMapper'), GREEN 6/6 after migration"
        status: pass
    human_judgment: false
  - id: D2
    description: "The local-mapper files are on Jackson 3; both golden comparisons are green with their goldens untouched and can still fail"
    requirement: "BOOT4-04"
    verification:
      - kind: unit
        ref: "Batch C unit: InsufficientStock 1, InvalidReviewPhoto 1, ShopServiceGeocode 26 (3 nested suites), ProductLabelGoldenFile 2 (1 @Disabled), PublicUnsubscribeRequestShape 7; bridge ON and OFF"
        status: pass
      - kind: integration
        ref: "Batch C integration: TenantHeaderAbsentDocument 3 (nested), FinancialSummaryGoldenFile 2 (1 @Disabled), WebhookDeliveryLog 11, Financial/Onboarding/Order NotificationListener 2/1/2 (Mailhog reachable, 0 skipped); bridge ON and OFF"
        status: pass
      - kind: other
        ref: "golden arm: one perturbed value per golden turned each test red; restores sha256-equal; closing run green"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every test file still on Jackson 2 carries a DELIBERATE-JACKSON2 line, and the closed list equals the widened scan"
    requirement: "BOOT4-04"
    verification:
      - kind: other
        ref: "verify 5 widened at HEAD: 3 files, each deliberate=1, EQUAL to the 3 DELIBERATE-JACKSON2-LIST lines; at PLAN_BASE: 41 files, 39 with deliberate=0"
        status: pass
    human_judgment: false
  - id: D4
    description: "No test weakened, disabled or deleted; no golden, resource, snapshot, main or build file changed"
    verification:
      - kind: other
        ref: "verify 6: 39 changed test files, base == head for every one (the arm deleting one @Test differs); git diff PLAN_BASE..HEAD over resources/docs/api/main is empty (the same command over 38-07's range prints 7); the full unit suite reads 1485/0/1 skipped, equal to the wave-4 gate"
        status: pass
    human_judgment: false

duration: 45min
completed: 2026-10-05
---

# Phase 38 Plan 19: Test-side Jackson-3 sweep before the bridge goes Summary

**All 24 test classes that autowired the bridge's Jackson-2 `ObjectMapper` now inject Boot's Jackson-3 `JsonMapper`, and every batch was proven green with the INTERIM-JACKSON2-BRIDGE line removed. 38-12 can therefore remove the bridge without a single test context failing. The test tree's Jackson-2 holdouts are a closed list of three files, each with a reason, and every other path back to Jackson 2 is gone. That includes five test files that reached Jackson 2 through Spring's deprecated adapters, which neither the plan's grep nor the bridge removal would have caught.**

## Performance

- **Duration:** about 45 min
- **Started:** 2026-10-05T13:11:45Z
- **Completed:** 2026-10-05T13:56Z
- **Tasks:** 3 of 3
- **Files:** 40 (39 test files, 1 evidence file). No main, resource, golden, snapshot or build file changed.

## Accomplishments

- **Tracer, red then green.** With the bridge line removed, the unmigrated `ShopControllerIntegrationTest` was RED 6/6 on `Unsatisfied dependency expressed through field 'objectMapper'`. The application context itself started, so no main bean needs the bridge, and 38-12's premise holds. After the field moved to `JsonMapper`, the same probe was GREEN 6/6.
- **Batch A** (11 classes, 92 tests) and **batch B** (12 classes plus the 38-07 residual `TenantLifecycleAdminIntegrationTest`, 62 tests) are green with the bridge on and with it off. Each probe ran on a committed tree and was restored from HEAD, with sha256 equal to the HEAD blob (`d9cb6f42…`).
- **One red was diagnosed, not loosened.** `GuestCheckoutIdempotencyIntegrationTest.legacyBodyOnlyHashAllowsOnlyMatchingBodyAndOwningShop` plants a "legacy" hash. Written by Boot's alphabetical mapper, it became a hash no Boot-3.5 pod wrote, and the owning-shop replay was refused (`IdempotencyPayloadMismatchException`). The planted hash now uses the frozen Boot-3.5 recipe, which is golden-pinned for this exact body. Every assertion is kept, and production is untouched.
- **Batch C.** Nine local mappers are on `JsonMapper.builder()`. Both golden tests are migrated with their comparison green and their golden untouched. A bracketed golden-value arm turned each red, and both restores are sha256-equal. `MailhogAssertions`' three consumers really ran against Mailhog (HTTP 200, 0 skipped). OpenApiSnapshotTest gets only its DELIBERATE-JACKSON2 line.
- **Closed list:** `AmqpJackson2CompatibilityTest`, `OutboxPayloadCompatibilityTest` (38-08) and `OpenApiSnapshotTest`. The list equals the widened scan at HEAD (3 = 3). At PLAN_BASE that scan printed 41 files, 39 of them unmarked.
- **Full unit suite on HEAD:** 1485 tests, 0 failures, 1 skipped, equal to the wave-4 gate.

## Task Commits

1. **Task 1 (tracer): batch A**
   - `eb29318c` test(38-19): tracer class
   - `a607c541` test(38-19): batch A
   - `846646a0` docs(38-19): bridge-off probe and criteria
   - Tracer gate: interactive, end-of-phase mode, automated-only verify. It was re-run green, then expanded.
2. **Task 2: batch B**
   - `23fa9c8e` test(38-19): batch B, plus TenantLifecycleAdmin
   - `65ded369` docs(38-19): bridge-off probe and criteria
3. **Task 3: batch C and the reconciliation**
   - `ea458cc9` test(38-19): batch C, plus the OpenApiSnapshotTest marker
   - `975511a5` test(38-19): the five Spring-adapter residuals
   - `2e0239e0` docs(38-19): reconciliation and DELIBERATE-JACKSON2-LIST
   - `0a8916bd` docs(38-19): full unit regression read

**Plan metadata:** the docs commit that adds this SUMMARY.

## Files Created/Modified

- Evidence: `evidence/38-19-test-jackson3-sweep.txt` holds PLAN_BASE, the tracer arm, every bridge-on and bridge-off read, every sha256 pair, the golden decisions, both arms, the reconciliation and the DELIBERATE-JACKSON2-LIST.
- Batch A (bean field to `JsonMapper`): `integration/{ShopController,CustomerController,FinancialTransactionController}IntegrationTest`, `integration/LocationHeaderContractTest`, `storefront/{GuestCheckoutIdempotency,PublicApiVersionAlias,PublicStorefrontPostcodeSearch}IntegrationTest`, `gdpr/Dsar{Fanout,Intake,SubjectAndGlobalRateLimit,Verification}IntegrationTest`.
- Batch B: `onboarding/{OnboardingAdminQueue,OnboardingCompanyNumberValidation,OnboardingCreateCrossTenant,OnboardingResubmit,OnboardingReviewQueue,OnboardingStallOutbox,OnboardingSubmit,OnboardingSubmitterResolver,VendorOnboardingEndToEnd}IntegrationTest`, `payment/PaymentEventOutbox{FlusherCrossTenant,Reliability}IntegrationTest`, `webhook/WebhookSubscriptionControllerIntegrationTest`, `tenant/TenantLifecycleAdminIntegrationTest`.
- Batch C: `common/{InsufficientStock,InvalidReviewPhoto}ExceptionHandlerTest`, `shop/ShopServiceGeocodeTest`, `product/ProductLabelGoldenFileTest`, `finance/FinancialSummaryGoldenFileTest`, `config/TenantHeaderAbsentDocumentTest`, `notification/consent/PublicUnsubscribeRequestShapeTest`, `webhook/WebhookDeliveryLogIntegrationTest`, `testsupport/MailhogAssertions`, and `integration/OpenApiSnapshotTest` (marker only).
- Residuals: `common/{GlobalExceptionHandlerRequestShape,OptimisticLockExceptionHandler}Test`, `config/{RabbitListenerContainerFactory,RabbitMQListenerFactoryBehaviour}Test`, `order/OrderEventFanoutTopologyIntegrationTest`.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| Bridge-off probe can fail | batches A, B, C and residuals green, rc=0 | unmigrated tracer RED 6/6 on the `objectMapper` field |
| Restore of the build file | sha256 restored == HEAD blob (6 probes) | the probe worktree read `bridge_lines_in_worktree=0` |
| T1/T2 no Jackson-2 import in the batch | rc=1 | 11 and 12 lines at PLAN_BASE, rc=0 |
| T1/T2 positive control (`JsonMapper` import) | 11 and 12 lines | 0 lines at PLAN_BASE, rc=1 |
| Bridge still committed | `core-java/build.gradle.kts:1` | 0 in every probe worktree |
| T3 32 non-listed files carry no Jackson-2 import | rc=1 | 32 lines at PLAN_BASE |
| Verify 5 / list closed | widened scan 3 files, all deliberate, EQUAL to the list | PLAN_BASE: 41 files, 39 deliberate=0; list vs base scan DIFFERENT |
| Golden comparisons still bite | green, golden untouched | one perturbed value per golden turned each red |
| Exception tests need Boot's mapper | green | bare mapper: "No value at JSON path $.property" and "$.code" |
| Verify 6 (no weakening) | 39 files, base == head | a copy with one @Test deleted gives 7/0 vs 6/0 |
| No resource/main/snapshot change | empty diff | the same command over 38-07's range prints 7 paths |
| Legacy-hash replay | 13/13 | Boot's mapper bytes: the replay refused (recorded red) |

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- The legacy hash is planted in the frozen Boot-3.5 format.
- Both golden tests are migrated.
- The Spring-adapter residuals are migrated, not allowlisted.
- 38-12 needs the widened scan.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug, test emulation] The legacy request_hash planted with Boot's mapper**
- **Found during:** Task 1 (the first bridge-on run of batch A).
- **Issue:** after the field move, `legacyBodyOnlyHashAllowsOnlyMatchingBodyAndOwningShop` went red at the owning-shop replay. The planted hash was Boot's alphabetical Jackson-3 bytes, not the Boot-3.5 bytes that production's frozen `IdempotencyJson` hashes (the 38-05 property-order mechanism).
- **Fix:** a `BOOT35_FINGERPRINT_WRITER` with IdempotencyJson's recipe, golden-pinned for `storefront.guest-order.legacy`. All assertions are kept.
- **Verification:** 13/13 with the bridge on and off. The red run is the fail direction.
- **Committed in:** `a607c541`.

**2. [Rule 3 - Blocking] TenantLifecycleAdminIntegrationTest was not in the plan's list**
- **Issue:** this 38-07 residual still autowired the bridge bean, so it would have failed 38-12.
- **Fix:** it was migrated in batch B and carried through both probes.
- **Committed in:** `23fa9c8e`.

**3. [Rule 2 - Missing critical] Five Spring-adapter Jackson-2 users invisible to the plan's grep**
- **Issue:** `OrderEventFanoutTopologyIntegrationTest` (a 38-08 residual) and four unowned unit tests (`RabbitListenerContainerFactoryTest`, `RabbitMQListenerFactoryBehaviourTest`, `GlobalExceptionHandlerRequestShapeTest`, `OptimisticLockExceptionHandlerTest`) used `Jackson2JsonMessageConverter`, `MappingJackson2HttpMessageConverter` or `Jackson2ObjectMapperBuilder`. They do not depend on the bridge, so 38-12 would not have caught them. The two exception tests' own comments justified Jackson 2 as "what Spring Boot builds its mapper with", which is false on Boot 4.
- **Fix:** they moved to the Jackson-3 adapters. The exception tests use `BootJsonMapper`.
- **Verification:** green with the bridge on and off. The bare-mapper arm was red on `$.property` and `$.code`.
- **Committed in:** `975511a5`.

**4. [Plan defect] PublicUnsubscribeRequestShapeTest is a unit test**
- **Issue:** it has no `testcontainers` tag (the regex hit was its own comment). The plan's integration filter for it matches nothing.
- **Fix:** it was run and read in the unit task, in both bridge states.

**5. [Plan defect] `@Nested` classes**
- **Issue:** `ShopServiceGeocodeTest` and `TenantHeaderAbsentDocumentTest` write `TEST-<fqcn>$<Nested>.xml`, so the plan's reader prints MISSING for them.
- **Fix:** the nested files were read instead (26 and 3 tests).

**6. [Plan defect] Verify 5 is inconsistent with its own acceptance criterion**
- **Issue:** the narrow grep cannot print `AmqpJackson2CompatibilityTest`, yet the list must include it and equal the printed set.
- **Fix:** the list is checked against the widened scan (equal, 3 = 3). The narrow scan prints the list minus that file. The first widened attempt used bare class names and matched Javadoc history in `RabbitMQConfigMessageConverterTest`, so it was narrowed to package-qualified names.

**7. [Strengthened] Additions beyond the plan's minimum**
- Batch C's unit classes were also run bridge-off.
- Bracketed golden-value and bare-mapper arms.
- A full unit suite regression read.

---

**Total deviations:** 7: 1 bug in test emulation, 1 blocking, 1 missing-critical, 3 plan defects, 1 strengthened.
**Impact on plan:** no asserted value, golden, resource, snapshot, main or build file changed. Scope widened only to test files that are Jackson-2 users under any reasonable reading of D-01, and every one of those is recorded with its owner.

## Issues Encountered

- A `sleep 240` wait was refused by the machine's wait-loop guard. Waits after that use bounded until-loops with a deadline.
- The plan's "three mock response bodies" in ShopServiceGeocodeTest are in fact request bodies to a standalone MockMvc. Key order is immaterial there too.

## Known Stubs

None. The scan for TODO/FIXME/placeholder/coming soon/not available over the added lines returned rc=1, and the positive control returned rc=0.

## Threat Flags

None. Only test code changed.
- T-38-45: mitigated. Verify 6 gives base == head for all 39 files, the one red was diagnosed rather than loosened, and every read came from fresh XML after a clean.
- T-38-46: mitigated. No path under resources or docs/api changed, and the golden arms were transient and restored by sha256.
- T-38-47: mitigated. Every batch was proven bridge-off, and the tracer showed the probe can fail.
- T-38-SC: no package was installed and no dependency line changed.

## User Setup Required

None. No compose service was started, stopped or rebuilt. Mailhog was already up and was only read with a GET.

## Next Phase Readiness

- **38-12:** removing the bridge breaks no test context. That was measured for all 24 bean injectors, all local-mapper files and all residuals.
  - The allowlist constant is the three DELIBERATE-JACKSON2-LIST paths, verbatim from the evidence.
  - JacksonLineContractTest's test scan must use the widened pattern: `com.fasterxml.jackson.{databind,core,datatype}` OR `org.springframework.<pkg>.(Jackson2*|MappingJackson2*|GenericJackson2*)`, package-qualified.
  - Baselines from this plan: unit 1485/0/1 skipped; per-batch bridge-off counts are A 92, B 62 (incl. 10 residual), C unit 37 / integration 21, residuals 19 + 1.
- **38-14:** OpenApiSnapshotTest is untouched apart from its DELIBERATE-JACKSON2 Javadoc line. Its normalizer is still Jackson 2, and it is still red until the snapshot is regenerated.
- **38-16:** 39 test files changed, but no test count changed (unit 1485, the same as the wave-4 gate).

## Self-Check: PASSED

- FOUND: the evidence file and all 39 modified test files (`git diff --name-only c2c64e78..HEAD`).
- FOUND commits: eb29318c, a607c541, 846646a0, 23fa9c8e, 65ded369, ea458cc9, 975511a5, 2e0239e0, 0a8916bd. `git rev-list --count c2c64e78..HEAD` = 9.
- All acceptance criteria and the plan verification were re-run in both directions (the table above, and the evidence).

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

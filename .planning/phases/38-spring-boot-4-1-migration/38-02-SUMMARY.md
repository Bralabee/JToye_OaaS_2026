---
phase: 38-spring-boot-4-1-migration
plan: 02
subsystem: testing
tags: [boot4-migration, spring-boot-3.5, bean-validation, openapi-required, jsonb, hibernate-json, www-authenticate, oauth-protected-resource, baselines]

requires:
  - phase: 38-01
    provides: "jackson2-golden tree, its MANIFEST format and GoldenFixturesIntegrityTest"
provides:
  - "RequestBodyConstraintEnforcementTest (untagged, permanent): structural inventory of every @RequestBody NotNull/NotBlank/NotEmpty property, with @Valid and null-rejection proofs, plus one real 400"
  - "evidence/38-02-request-body-constraints-boot35.tsv: 55 rows, 20 DTOs, the 'enforced today' oracle 38-14 checks newly-required OpenAPI fields against"
  - "jackson2-golden/jsonb/: Jackson-2-era stored text of shops.opening_hours, products.allergen_spans, vendor_onboarding_gate.evidence (capture commit f548d6c8)"
  - "JsonbColumnsReadBackIntegrationTest (testcontainers, permanent read-back)"
  - "UnauthenticatedProblemDetailIntegrationTest: exact WWW-Authenticate values and two /.well-known Boot 3.5 baseline methods"
  - "evidence/38-02-baselines.txt: every pass and fail direction for the three tasks"
affects: [38-06, 38-10, 38-12, 38-14]

actuals:
  tokens: 15556
  tasks: 3
  commits: 7
plan_head_before: 2e601289e1fdc25c4da01b6168cfc4d8fb6d70f9
plan_head_after: 505eb4ffe5aea067fd7e783075500f16637050e3

tech-stack:
  added: []
  patterns:
    - "Structural constraint inventory: RequestMappingHandlerMapping x Bean Validation metadata, cascades recursed, an empty inventory refused"
    - "jsonb golden capture: env-gated one-shot capture, committed, then deleted; read-back writes the fixture text with JDBC so the mapper under test never wrote it"
    - "Baseline-pinning methods named *_boot35Baseline: they assert the measured pre-migration behaviour and are expected to go red until the plan that changes it on purpose"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/boot4/RequestBodyConstraintEnforcementTest.java
    - core-java/src/test/java/uk/jtoye/core/boot4/JsonbColumnsReadBackIntegrationTest.java
    - core-java/src/test/resources/jackson2-golden/jsonb/MANIFEST.tsv
    - core-java/src/test/resources/jackson2-golden/jsonb/shops.opening_hours.json
    - core-java/src/test/resources/jackson2-golden/jsonb/products.allergen_spans.json
    - core-java/src/test/resources/jackson2-golden/jsonb/vendor_onboarding_gate.evidence.json
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-02-request-body-constraints-boot35.tsv
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-02-baselines.txt
  modified:
    - core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java
    - core-java/src/test/resources/jackson2-golden/README.md

key-decisions:
  - "D-05's premise is half-true on Boot 3.5.16: /.well-known/oauth-protected-resource returns 404 errors/not-found only to an AUTHENTICATED caller. An anonymous caller gets 401 errors/unauthorized with WWW-Authenticate: Bearer. 38-06 still implements the locked 404 for every caller, so the anonymous answer changes from 401 to 404; this is recorded, not re-opened."
  - "The jsonb fixtures join the 38-01 integrity guard as a 'jsonb' family (41 fixtures). Excluding them was the alternative; adding them is stronger, because the new fixtures also get sha256 and length checks."
  - "jsonb/MANIFEST.tsv paths are relative to jsonb/, not jackson2-golden/: GoldenFixturesIntegrityTest resolves each row against its own manifest's directory, so the plan's literal wording would not resolve."
  - "The jsonb capture commit is f548d6c833ccc696d891ba686d8baf4e831e815b. The capture method exists only there."
  - "Read-back writes the fixture text into a freshly created row with a JDBC UPDATE ... CAST(? AS jsonb), not a full JDBC INSERT. The row is created through the repository with the jsonb column null, so the mapper under test never wrote the value, and no NOT NULL column has to be hand-maintained."

patterns-established:
  - "*_boot35Baseline test methods pin measured pre-migration behaviour; the plan that changes it updates them deliberately"
  - "jackson2-golden/jsonb is an oracle like the rest of jackson2-golden: never re-capture it on a Boot-4 tree"

requirements-completed: [BOOT4-13, BOOT4-09, BOOT4-08]

coverage:
  - id: D1
    description: "Structural inventory of every enforced request-body constraint on Boot 3.5 (55 rows, 20 DTOs, cascades recursed), with @Valid and null-rejection asserted per row"
    requirement: BOOT4-13
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/RequestBodyConstraintEnforcementTest.java#everyRequiredRequestBodyPropertyIsEnforced"
        status: pass
      - kind: other
        ref: ".planning/phases/38-spring-boot-4-1-migration/evidence/38-02-baselines.txt (vacuity arm, arm A @Valid, arm B null)"
        status: pass
    human_judgment: false
  - id: D2
    description: "One real HTTP request missing customerEmail returns 400 application/problem+json errors/validation naming the field; with the field present the same request passes validation"
    requirement: BOOT4-13
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/RequestBodyConstraintEnforcementTest.java#guestOrderWithoutCustomerEmailIsRejected"
        status: pass
    human_judgment: false
  - id: D3
    description: "Jackson-2-era jsonb stored text for the three Hibernate JSON columns, captured on Boot 3.5 and read back to equal Java values"
    requirement: BOOT4-08
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/JsonbColumnsReadBackIntegrationTest.java (3 methods)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java#manifestsCoverEveryFixtureByteForByte"
        status: pass
    human_judgment: false
  - id: D4
    description: "Exact 401 challenge (missing token = 'Bearer'; garbage token = RFC 6750 error form, no resource_metadata) and the measured /.well-known Boot 3.5 baseline (401 anonymous, 404 authenticated)"
    requirement: BOOT4-09
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java (5 methods)"
        status: pass
      - kind: other
        ref: ".planning/phases/38-spring-boot-4-1-migration/evidence/38-02-baselines.txt (Bearer realm arm and three more)"
        status: pass
    human_judgment: false

duration: 18min
completed: 2026-10-05
status: complete
---

# Phase 38 Plan 02: Boot 3.5 Baselines Summary

**Three behaviours that later Phase 38 plans must keep or change on purpose are now measured on Boot 3.5.16 and held by permanent tests.**

- **Required request fields:** a structural inventory lists the 55 required request-body fields across 20 DTOs, each proven behind `@Valid` and rejecting null.
- **jsonb read-back:** the Jackson-2-era stored text of the three `jsonb` columns is captured and reads back to equal Java values.
- **401 challenge:** it is pinned by exact value. `/.well-known/oauth-protected-resource` is recorded as 401 for an anonymous caller and 404 for an authenticated one.

## Performance

- **Duration:** 18 min
- **Started:** 2026-10-05T00:11:01Z
- **Completed:** 2026-10-05T00:29:08Z
- **Tasks:** 3 of 3
- **Files:** 11 paths (2 new test classes, 1 strengthened test, 1 extended integrity test, 4 fixture files, 1 README row, 2 evidence files)

## Accomplishments

- **The request-field oracle (BOOT4-13).** `RequestBodyConstraintEnforcementTest` builds its inventory from structure, not text:
  - it walks the live `RequestMappingHandlerMapping` and every `@RequestBody`, then the Bean Validation metadata of that body's type;
  - it recurses into cascaded element types, which is how it reaches `OrderItemRequest` and `GuestOrderItemRequest`;
  - it fails on an empty inventory, on a constrained body without `@Valid`/`@Validated`, and on a property that accepts null;
  - it runs untagged, so every later plan's fast `test` task runs it.

  Its 55 rows are committed as the Boot 3.5.16 oracle. Plan 38-14 checks every newly-`required` OpenAPI field against them. `CreateCustomerRequest`, the DTO the MCP tools consume, is in it (2 rows).
- **One real 400.** `POST /public/shops/any-slug/orders` without `customerEmail` returns 400 `application/problem+json`, type `errors/validation`, with `"errors":{"customerEmail":"Email is required"}`. With the field present, the request gets past validation. No filter or interceptor answers first.
- **jsonb continuity (BOOT4-08).** Hibernate's JSON mapper on the 3.5 tree wrote three values, and `SELECT col::text` captured them at `f548d6c8`.
  - The read-back test writes that text into a fresh row with JDBC, loads the entity through its production repository, and compares it with the Java value.
  - The capture method has been deleted; it survives only at that commit.
  - The fixtures are under the 38-01 sha256 guard as a new `jsonb` family.
- **D-04/D-05 baselines (BOOT4-09).** The two existence checks could pass on the Security-7 defect. They are replaced by exact values:
  - a missing token gets `Bearer`;
  - a garbage token gets a value that starts with `Bearer error="invalid_token"` and has no `resource_metadata`.

  Two `*_boot35Baseline` methods pin what `/.well-known/oauth-protected-resource` returns today.

## Findings for later plans (measured, not assumed)

- **38-06: D-05's "404 matches Boot 3.5" is half-true.** On 3.5.16 an anonymous caller gets **401** `errors/unauthorized` with `WWW-Authenticate: Bearer`, because the path is unmapped and falls to `anyRequest().authenticated()`. Only an authenticated caller gets 404 `errors/not-found`, via `NoResourceFoundException`. Implementing the locked 404 for every caller therefore changes the anonymous answer from 401 to 404. 38-06 must update both `_boot35Baseline` methods deliberately.
- **38-14: the inventory has 55 rows.** An OpenAPI `required` that is not in `evidence/38-02-request-body-constraints-boot35.tsv` is not proven enforced today.
- **jsonb normalises key order**, as A6 in the research assumed. For example, the stored text is `[{"end": 5, "start": 0}, …]` and `{"scheme": "FHRS", "sources": […], …}`. Only value formats are under test: integer, decimal (`4.5`), boolean, list and nested map.
- **The full garbage-token challenge on 3.5.16** is `Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Malformed token", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"`.

## Task Commits

1. **Task 1 (tracer), structural inventory and real 400:** `341872c5` (test). The tracer gate re-ran the verify command at that commit: rc=0, 2/2, `cleanTest` executed.
2. **Task 2 (TDD), jsonb capture and read-back:**
   - RED: `42647438` (test);
   - GREEN, the capture commit: `f548d6c8` (feat);
   - retirement: `7ae5799c` (chore);
   - evidence: `79e29ba6` (docs).
3. **Task 3 (TDD), exact 401 and /.well-known baseline:** test `d2459302`, evidence `505eb4ff` (docs).

Plan metadata: the docs commit that follows this SUMMARY.

## TDD Gate Compliance

- **Task 2 RED (`42647438`):** the read-back was written first, and 3/3 failed on the missing-fixture assertion. `gsd check tdd-red-evidence` returned `RED_EVIDENCE_OK` (target_test_failed, 3 failing).
- **Task 2 GREEN (`f548d6c8`):** after the capture, 3/3 passed with the capture method skipped. The refactor step is the retirement commit `7ae5799c`, which removes the generator. After it, 3/3 still pass.
- **Task 3: RED is not applicable, and this is recorded rather than faked.** The task pins existing behaviour on an unchanged production tree; the plan forbids any production change. A RED run would need a broken implementation, so the tests are green from the first run, which is the "unexpected GREEN" case and was investigated. Falsifiability comes from four bracketed arms instead, all after commit `d2459302`. Each failed on its own assertion:
  - the plan's `Bearer realm`;
  - the garbage-token `resource_metadata` check, armed to `invalid_token`;
  - well-known anonymous, armed to 404;
  - well-known authenticated, armed to 200.

## Verification (both directions; full output in `evidence/38-02-baselines.txt`)

| Check | Pass direction | Fail direction |
|---|---|---|
| T1 inventory non-empty | root `uk.jtoye.core`: 55 rows, 2/2 green | root `uk.jtoye.nonexistent`: failed on the vacuity assertion |
| T1 every row behind `@Valid` | 0 unvalidated | `validated` forced false: failed, naming `AnnouncementController#create` and others |
| T1 every row rejects null | 0 accepting null | `Size` counted as required: failed, naming `CreateShopRequest.description` and others |
| T1 real 400 | 400, `errors/validation`, `customerEmail` named | the same body with `customerEmail`: 500 `errors/internal`, past validation |
| T1 TSV rows / customerEmail / CreateCustomerRequest | 55 / 1 / 2 | `/dev/null` 0, `/nonexistent` rc=2 / `customerEmailX` 0 / `CreateCustomerRequestX` 0 |
| T2 read-back | 3/3 after capture and after retirement | RED: 3/3 failed on the missing fixture; corrupted span (`end` 17→18) failed, naming `products.allergen_spans` |
| T2 MANIFEST | 3 rows; `sha256sum -c` 3 OK, rc=0 | first hex digit flipped: 3 FAILED, rc=1; `/dev/null` 0 rows |
| T2 integrity guard covers jsonb | 5/5 green, 41 fixtures | corrupted span: failed, naming its sha256; `jsonb` family removed: failed on the fixture set |
| T2 capture retired | `git grep JTOYE_GOLDEN_CAPTURE` rc=1 at HEAD | at `f548d6c8` rc=0 (line 189) |
| T3 exact header and baselines | 5/5 green; `exists` 2→0; `oauth-protected-resource` 0→4; `@Test` 4→6 (strict 3→5) | four arms: 4/5 failed, each on its own assertion |
| Restores | content-verified: sha256 = backup or HEAD blob for every armed file; clean-again runs green | n/a (closing clean arm) |
| No main or build change | `git diff 2e601289..HEAD` over `core-java/src/main`, the build files and compose: empty | `bc3341d4` lists `core-java/build.gradle.kts` |

Every Gradle run used `cleanTest` or `cleanIntegrationTest`, and the logs show it executing. Results were read from `core-java/build-local/test-results`, never from `build/`.

## Decisions Made

All decisions are under key-decisions in the frontmatter. The two that matter most downstream:
- D-05's parity premise holds only for authenticated callers.
- The jsonb fixtures are part of the 38-01 integrity guard.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] GoldenFixturesIntegrityTest would go red on the new jsonb fixtures**
- **Found during:** Task 2 design.
- **Issue:** The 38-01 test walks the whole `jackson2-golden/` tree and asserts exactly 38 fixtures in 5 families. Any file under `jsonb/` turns it red. The family arm proved this: with the family line removed, the test fails on the fixture set.
- **Fix:** Added a `jsonb` family with the 3 exact file names, so the total is 41, and a README row. This is stronger than excluding the directory, because the jsonb fixtures also get the sha256, length and exactly-listed checks.
- **Files modified:** `GoldenFixturesIntegrityTest.java`, `jackson2-golden/README.md`. These are outside the plan's `files_modified`.
- **Committed in:** `f548d6c8`.

**2. [Rule 1 - Bug in the plan text] MANIFEST paths are relative to `jsonb/`, not `jackson2-golden/`**
- **Found during:** Task 2.
- **Issue:** The plan said "paths relative to jackson2-golden/". The 38-01 reader resolves each row against its own manifest's directory, so `jsonb/x.json` in `jsonb/MANIFEST.tsv` would resolve to `jsonb/jsonb/x.json` and fail.
- **Fix:** Rows are named relative to `jsonb/`. This is the actual 38-01 format, and the acceptance awk (3 rows) and `sha256sum -c` both pass.
- **Committed in:** `f548d6c8`.

**3. [Rule 2 - Strengthened checks] Extra fail-direction arms beyond the plan's**
- **Task 1:** arms for the `@Valid` assertion and the null-rejection assertion, plus a with-field HTTP arm. The plan named only the vacuity arm.
- **Task 2:** the integrity test was run against the corrupted span, and a family arm was added.
- **Task 3:** three arms besides `Bearer realm`.
- **Why:** each new assertion had to be seen failing before it was trusted.
- **Committed in:** the evidence file, `341872c5`, `79e29ba6` and `505eb4ff`.

**4. [Plan wording] Read-back uses a JDBC UPDATE into a freshly created row**
- **Issue:** The plan said "inserts each fixture's text into a fresh row with JDBC".
- **What was done:** The row is created through the repository with the jsonb column null, then `UPDATE … SET col = CAST(? AS jsonb)` writes the fixture text. The property the plan needs still holds: the mapper under test never wrote the stored value. This also avoids hand-maintaining every NOT NULL column of three tables. It is stated in the class Javadoc.

**Total deviations:** 4. That is 1 Rule 3, 1 Rule 1 (plan text), 1 Rule 2 and 1 wording note.
**Impact on plan:** No production class, DTO annotation, build file or compose service was touched. The two out-of-list test-tree edits keep 38-01's guard green and make it cover the new fixtures.

## Issues Encountered

- With `customerEmail` present, the H2 test context answers the guest-order request with 500 `errors/internal`, past validation, for a slug that does not exist. Its cause was not investigated. It is the fail-direction arm only and supports no claim.
- **The docs-freshness gate is red on this branch**, and it was already red after 38-01. `scripts/docs-freshness.sh` reports 309 Java test files and 4195 total invocations, against 306 and 4183 in `docs/metrics.json`. This plan adds 7 `@Test` methods and 2 files. Per CONTEXT and RESEARCH, `docs/metrics.json` is regenerated once by the phase's docs plan (BOOT4-14), so it is not regenerated here.
- The host JDK is Ubuntu OpenJDK 25.0.4.1, the same caveat as 38-01 and the spike.

## User Setup Required

None. No compose service was started, stopped or rebuilt. Testcontainers Postgres was used for the two integration classes.

## Next Phase Readiness

- 38-14 can check every newly-`required` OpenAPI field against the 55-row inventory. `RequestBodyConstraintEnforcementTest` stays green on Boot 4 only if enforcement does.
- 38-06 has exact pre-change values for D-04 and D-05. The `_boot35Baseline` methods are expected to go red on Boot 4 until 38-06 updates them. The `Bearer` exact checks should stay green once D-04's wrapper lands.
- BOOT4-08, BOOT4-09 and BOOT4-13 are shared with later plans. This plan supplies their baselines and oracle, not their final proof, and the shared-ID gate decides completion.
- **Blocker:** none.

## Self-Check: PASSED

- FOUND: RequestBodyConstraintEnforcementTest.java, JsonbColumnsReadBackIntegrationTest.java, jsonb/MANIFEST.tsv, the 3 jsonb fixtures, 38-02-request-body-constraints-boot35.tsv and 38-02-baselines.txt.
- FOUND commits: 341872c5, 42647438, f548d6c8, 7ae5799c, 79e29ba6, d2459302 and 505eb4ff. That is 7, which equals `git rev-list --count 2e601289..HEAD`. The control hash `deadbeef` was reported MISSING, so the check can fail.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

---
phase: 38-spring-boot-4-1-migration
plan: 05
subsystem: api
tags: [spring-boot-4, jackson-3, json, wire-contract, request-acceptance, golden-fixtures]

requires:
  - phase: 38-01
    provides: jackson2-golden fixtures (responses/, outbox/) and GoldenSamples, captured on Boot 3.5.16
  - phase: 38-02
    provides: the 38-02 request-body inventory (the request DTOs to check for exposure)
  - phase: 38-03
    provides: the interim Boot 4.1.1 tree that holds both Boot's JsonMapper and the bridge's Jackson-2 ObjectMapper
provides:
  - "Owner decision on Boot's Jackson defaults: jackson3-defaults, with no spring.jackson key and no preferred-json-mapper"
  - "Permanent Jackson3WireContractTest (12 tests) that locks the decided wire and acceptance contract of Boot's JsonMapper"
  - "Measured wire/acceptance diff, VERDICT VALUE-DIFF, and the two accepted contract changes (evidence/38-05-jackson-wire-diff.md)"
affects: [38-07, 38-11, 38-12, 38-16, 38-18, 38-19]

actuals:
  tokens: 22339    # chars/4 over the realized diff: final Jackson3WireContractTest + evidence (61497 chars) + the created-then-deleted probe (27860 chars)
  tasks: 3
  commits: 3
plan_head_before: 9bd5ed8aa92f038625a4327eb95b2bc6dce823b7
plan_head_after: ba0c9f3ffc7053e02ba6c9d898dd0edb728b3795

tech-stack:
  added: []
  patterns:
    - "Wire contract by golden fixture: tree-equal for every published shape, raw-equal for records, and an explicit accepted-ordering-difference set that turns red if it grows or shrinks"
    - "Decided mapper behaviour asserted on the injected bean alone, tied to the REST path by asserting that the MVC converter holds that same bean"

key-files:
  created: []
  modified:
    - core-java/src/test/java/uk/jtoye/core/boot4/Jackson3WireContractTest.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-05-jackson-wire-diff.md
  deleted:
    - core-java/src/test/java/uk/jtoye/core/boot4/Jackson3AcceptanceProbeTest.java

key-decisions:
  - "38-05: the owner chose jackson3-defaults (exact words: \"jackson3-defaults (Recommended)\", 2026-10-05). Boot's JsonMapper keeps Jackson 3's defaults, and application*.yml carries no spring.jackson key"
  - "38-05: two contract changes are accepted. Class-based responses (OrderDto, ProductDto, ShopDto, ProblemDetail) get alphabetical key order, and trailing content after a request body is a 400 errors/unreadable-request. ADR-0006 (38-16) and the PR body (38-18/ship) must name both"
  - "38-05: records keep their exact bytes. That covers all 6 outbox payloads, the webhook envelope (the HMAC input) and every record response; the wire test asserts it"

patterns-established:
  - "ACCEPTED_ORDERING_DIFFERENCES: an exact set. A record whose bytes change, or a class whose order reverts, both turn the contract red"

requirements-completed: []  # plan declares [BOOT4-04]; shared with 38-06..38-10, 38-12, 38-19 - the shared-ID gate (requirements.ready-ids) reports 0/1 ready

coverage:
  - id: D1
    description: "Wire contract: all 15 responses/ and outbox/ fixtures tree-equal to Boot's output, and only the 4 class-based fixtures raw-unequal (alphabetical order). Records are byte-identical"
    requirement: "BOOT4-04"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/Jackson3WireContractTest.java#everyFixtureIsTreeEqualAndOnlyTheAcceptedOrderingDifferencesAreRawUnequal"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/Jackson3WireContractTest.java#recordsKeepTheirBytesAndClassBasedTypesAreAlphabetical"
        status: pass
    human_judgment: false
  - id: D2
    description: "The decided acceptance behaviour, asserted on Boot's mapper. Trailing tokens are rejected, including a 400 errors/unreadable-request on the real HTTP path. Null or absent values into primitives are rejected. Unknown properties are ignored. Enums are read and written by toString. Dates are read as UTC instants. A single value is not accepted as a list. A float into an int is truncated"
    requirement: "BOOT4-04"
    verification:
      - kind: integration
        ref: "./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.boot4.Jackson3WireContractTest' --no-daemon (12/12)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The owner decision is recorded verbatim. No spring.jackson key and no preferred-json-mapper exists under core-java/src. The temporary probe is deleted"
    requirement: "BOOT4-04"
    verification:
      - kind: other
        ref: "git grep -n -e 'jackson:' -e 'spring\\.jackson' -- core-java/src/main/resources (rc=1); git grep -n 'preferred-json-mapper' -- core-java/src (rc=1); git ls-files probe (n=0)"
        status: pass
    human_judgment: false

duration: 27min
completed: 2026-10-05
status: complete
---

# Phase 38 Plan 05: Jackson 3 wire contract and owner decision Summary

**Boot's Jackson-3 `JsonMapper` keeps its defaults by owner decision. A permanent 12-test wire contract locks that decision. Every golden shape stays tree-equal, records stay byte-identical, class-based responses are written alphabetically, and trailing request content is a 400 `errors/unreadable-request`.**

## Performance

- **Duration:** about 27 min of active work: Task 1 took about 14 min (01:31Z to 01:45Z), and Tasks 2 and 3 resumed from 08:22Z to about 08:35Z. The gap between them was the owner's decision.
- **Started:** 2026-10-05T01:31:27Z (PLAN_BASE `9bd5ed8a`)
- **Completed:** 2026-10-05T08:35Z
- **Tasks:** 3 of 3. Task 2 was the owner's decision.
- **Files modified:** 3. One test was rewritten, the evidence file was extended, and the temporary probe was deleted.

## Accomplishments

- **Measured (Task 1, `2d5e1cd9`).** All 15 fixtures are tree-equal. Only 4 are raw-unequal: OrderDto, ProductDto, ShopDto and ProblemDetail-401, which differ in property order only. Eight acceptance probes differ, but only the trailing-token change reaches the API. No request DTO has a primitive property, and none of the 40 enums has a `toString()` that differs from `name()`. VERDICT: VALUE-DIFF (input side only).
- **Decided (Task 2).** The owner chose "jackson3-defaults (Recommended)". The decision and its rationale are recorded in evidence section 10. No configuration key was applied.
- **Locked (Task 3, `6026d4c2` and `ba0c9f3f`).** `Jackson3WireContractTest` now covers four areas:
  - tree equality for every fixture;
  - an exact set of accepted ordering differences;
  - byte identity for records and alphabetical order for class-based types;
  - the REST converter's mapper identity, the 13 decided acceptance outcomes (one includes the real HTTP path), and the diff self-test.
- **Break arms.** Four bracketed arms each turned the test red on the targeted assertion:
  - a value changed in a fixture copy;
  - `sort-properties-alphabetically=false`;
  - `fail-on-trailing-tokens=false`;
  - `use-jackson2-defaults=true`.

  Each restore was verified by blob hash, and the closing clean run passed 12/12.

## Task Commits

1. **Task 1: Tracer, measuring the serialization and acceptance diffs end to end.** `2d5e1cd9` (test)
2. **Task 2: Owner decision.** No commit, because it was a checkpoint. The decision is recorded in evidence section 10, committed in `6026d4c2`.
3. **Task 3: Apply the decision and lock it with the permanent wire contract.** `6026d4c2` (test) holds the contract, the probe deletion and the decision record. `ba0c9f3f` (docs) holds the arms, the closing run and the acceptance criteria in both directions.

**Plan metadata:** the commit that adds this SUMMARY (docs). STATE, ROADMAP and REQUIREMENTS are updated in a separate docs commit after it.

## Files Created/Modified

- `core-java/src/test/java/uk/jtoye/core/boot4/Jackson3WireContractTest.java` is the permanent decided contract, with 12 tests. It still writes `build-local/boot4/jackson-wire-diff.tsv`.
- `core-java/src/test/java/uk/jtoye/core/boot4/Jackson3AcceptanceProbeTest.java` was **deleted** in `6026d4c2`. The deletion is intentional: the plan's prohibition says the probe must not survive this plan, because it depends on the Jackson-2 bean that 38-12 removes.
- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-05-jackson-wire-diff.md` contains:
  - sections 1-9: the measurement and verdict (Task 1);
  - section 10: the decision, the owner's exact words, the applied keys (none), the two contract changes, and where they must be documented;
  - section 11: the arms, the closing run and the acceptance criteria in both directions.

## Decisions Made

- **jackson3-defaults** (owner, 2026-10-05, exact words "jackson3-defaults (Recommended)"). No `spring.jackson.*` key was added, and `preferred-json-mapper` was not set.
- **Accepted contract change 1: alphabetical key order on class-based responses.** This covers OrderDto, ProductDto, ShopDto, and every Spring ProblemDetail that this mapper writes. Values, key sets and byte lengths are unchanged.
- **Accepted contract change 2: a trailing token after a request body returns 400.** The response is `https://jtoye.uk/errors/unreadable-request` without the body being read. Boot 3.5 ignored the trailing content.
- **Persisted-hash paths are decoupled from this decision.** Idempotency (38-10) uses its own frozen mapper. AMQP (38-08) and Redis (38-09) build their own mappers.

## Carried obligations (owned by later plans, not done here)

- **ADR-0006 is written in 38-16, Task 2.** It must name both contract changes above and cite `evidence/38-05-jackson-wire-diff.md` section 10. The 38-16 must-have already requires it to record "the 38-05 Jackson-defaults decision".
- **The PR description is written by 38-18's ship checklist, then `/gsd-ship`.** It must name the same two contract changes: alphabetical key order on class-based responses, and the 400 for a trailing token.

## Findings for later plans (measured, not assumed)

- **38-07: Boot's mapper rejects trailing content.** `ImageAnalysisService`'s must-have is to tolerate trailing text after the model's JSON. That tolerance therefore cannot come from Boot's `JsonMapper`. It needs its own reader configuration, and it must be proven by test whatever the default is.
- **38-11: there is no `spring.jackson.*` key to put under the key gate.** If any `spring.jackson` key appears later, `Jackson3WireContractTest` turns red, as arms B-D show. <!-- gitleaks:allow (a test class name, not a secret; 38-18) -->
- **38-12 and 38-19:** a migrated test that now sees alphabetical key order on OrderDto, ProductDto, ShopDto or ProblemDetail is explained by this decision. A changed VALUE is not explained by it.
- **38-12:** `Jackson3WireContractTest` does not depend on the Jackson-2 bean, so it survives the bridge removal unchanged.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The wire test's web environment changed from NONE to the default MOCK, with `@AutoConfigureMockMvc`.**
- **Found during:** Task 3.
- **Issue:** Task 1 specified `webEnvironment NONE`. Task 3 moves the decided acceptance outcomes into this test, and one of them is the trailing-token 400 `errors/unreadable-request` on the real HTTP path. That outcome needs MockMvc.
- **Fix:** the test now uses `@SpringBootTest` with the default MOCK environment and `@AutoConfigureMockMvc`. It is still untagged and on H2, so the fast `test` task runs it.
- **Files modified:** `core-java/src/test/java/uk/jtoye/core/boot4/Jackson3WireContractTest.java`
- **Verification:** 12/12 green, and arm C turned the HTTP test red.
- **Committed in:** `6026d4c2`

### Additions beyond the plan's minimum (not scope changes)

- **Arm A is stronger than specified.** The plan asked for "a one-off assertion" on a fixture copy. Instead, the real contract test was repointed at the copy, and its tree check reported the changed pointer.
- **Arms B, C and D were added.** The plan required only arm A for jackson3-defaults. These arms show that each decided default is guarded by the test. Without them, the four alphabetical and trailing assertions would have been observed only passing.
- **`restBodiesUseThisMapper` was added.** It ties the mapper-level assertions to the REST path, measured as `true` in Task 1.

---

**Total deviations:** 1 auto-fixed (Rule 3).
**Impact on plan:** the deviation was needed to assert the decided HTTP outcome permanently. There was no production or configuration change.

## TDD Gate Compliance

- **RED commits:** `test(38-05)` at `2d5e1cd9` and `6026d4c2`.
- **No `feat(38-05)` commit, by design.** The decision is "no configuration", so there is no production change to make green.
- **The rewritten test was green on the real tree when first run.** This is expected: the decided behaviour is the framework's existing default, and that green run is not a pre-implementation RED.
- **RED evidence instead:** each break arm puts the tree into a state that violates the decision. `gsd_run check tdd-red-evidence` classified each arm's JUnit XML (target `Jackson3WireContractTest`) as **RED_EVIDENCE_OK / target_test_failed** for A, B, C and D. As the classifier's fail direction, the closing green XML classified as **INVALID_RED / unexpected_green**.
- **Task 1's own RED** is quoted in evidence section 2a: the raw-equality-for-all run failed on the 4 class-based fixtures.

## Verification (plan `<verification>`, both directions)

1. **The wire diff and acceptance differential are recorded with one VERDICT line.** `awk '/^VERDICT: (ORDER-ONLY|VALUE-DIFF)$/…'` printed `verdicts=1`. Task 1 recorded the fail directions: a second verdict line gave 2, `/dev/null` gave 0, and a malformed line gave 0.
2. **The owner decision is recorded verbatim** in evidence section 10: "jackson3-defaults (Recommended)".
3. **`Jackson3WireContractTest` is green with the decided assertions.** The plan's verify command (`./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.boot4.Jackson3WireContractTest' --no-daemon`) returned rc=0. Both `cleanTest` and `test` executed, and the XML reads `tests="12" failures="0" errors="0"`, fresh from the run. Each arm is red, as listed above, and the probe is deleted.
4. **No deprecated converter-switch property anywhere.** `git grep -n 'preferred-json-mapper' -- core-java/src` returned only `rc=1`. As a positive control, the same grep found the phrase in `38-CONTEXT.md`.

The acceptance criteria were checked in both directions (evidence section 11):

| Criterion | Real tree | Fail direction |
|---|---|---|
| Probe file tracked | `rc=0 n=0` | The pre-delete HEAD gave `n=1` |
| `spring.jackson` keys | only `rc=1` | A scratch YAML with `jackson:` matched with `rc=0`; arms B-D turned the test red |

## Issues Encountered

None.

## User Setup Required

None. No external service configuration is required.

## Next Phase Readiness

- 38-06 (wave 3, sequential) is next. The wire contract is permanent and survives 38-12.
- BOOT4-04 stays open. It is shared with 38-06..38-10, 38-12 and 38-19, and `requirements.ready-ids` reports 0/1 ready.
- **Blocker:** none.

## Self-Check: PASSED

- FOUND: `core-java/src/test/java/uk/jtoye/core/boot4/Jackson3WireContractTest.java`
- FOUND: `.planning/phases/38-spring-boot-4-1-migration/evidence/38-05-jackson-wire-diff.md`
- ABSENT, as required: `core-java/src/test/java/uk/jtoye/core/boot4/Jackson3AcceptanceProbeTest.java`
- FOUND commits: `2d5e1cd9`, `6026d4c2`, `ba0c9f3f`. That count is measured as `git rev-list --count 9bd5ed8a..HEAD` = 3, taken before this SUMMARY's commit.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

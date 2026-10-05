---
phase: 38-spring-boot-4-1-migration
plan: 07
subsystem: api
tags: [spring-boot-4, jackson-3, keycloak, restclient, problem-detail, rate-limit, ai, webhook, tdd]
status: complete

requires:
  - phase: 38-01
    provides: "jackson2-golden/responses/WebhookEventEnvelope.json and GoldenSamples (the vendor-envelope oracle)"
  - phase: 38-03
    provides: "the Boot 4.1.1 tree with Boot's Jackson-3 JsonMapper bean and the Jackson-3 RestClient converter"
  - phase: 38-05
    provides: "the jackson3-defaults decision: Boot's mapper rejects trailing tokens, so ImageAnalysisService needs its own reader"
provides:
  - "KeycloakAdminClient on tools.jackson (JsonMapper injected; searchUsersByTenant returns List<tools.jackson ObjectNode>; setUserEnabled takes one): the disable PUT carries the searched rep with only enabled=false, asserted by content"
  - "RateLimitInterceptor, ImageAnalysisService and WebhookFanoutListener inject Boot's Jackson-3 JsonMapper; DemoImageManifest builds a local Jackson-3 JsonMapper"
  - "ImageAnalysisService reads model text with its own ObjectReader without FAIL_ON_TRAILING_TOKENS and FAIL_ON_UNKNOWN_PROPERTIES"
  - "WebhookFanoutListenerEnvelopeTest (new): the real listener's delivered envelope is tree- and byte-equal (bar the event id) to the 38-01 Jackson-2 fixture"
  - "BootJsonMapper test helper: Boot's auto-configured Jackson-3 JsonMapper for the fast unit slice"
affects: [38-08, 38-09, 38-10, 38-12, 38-16, 38-17, 38-18, 38-19]

actuals:
  tokens: 14696    # chars/4 over the added lines of c5aac555..d8844e34 (58784 chars, 17 files)
  tasks: 2
  commits: 6       # MEASURED: git rev-list --count c5aac555..HEAD at SUMMARY write
plan_head_before: c5aac555f72b09dda74963ffbb802dbc9b69419f
plan_head_after: d8844e34012f5c29bd7966d7547f126795095bda

tech-stack:
  added: []
  patterns:
    - "Untrusted-input leniency lives in a dedicated ObjectReader on the consumer, never in the shared Boot mapper"
    - "A catch the compiler no longer demands (checked -> unchecked JacksonException) is kept on purpose and pinned by a test that the import-only migration turns red"
    - "Unit tests that must serialize as the app does use Boot's mapper from JacksonAutoConfiguration (BootJsonMapper), not a hand-built mapper with the expected mixin added"
    - "Each by-content or shape assertion carries a permanent negative control in the same test class"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookFanoutListenerEnvelopeTest.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/BootJsonMapper.java
    - core-java/src/test/resources/dev-manifest-38-07/truncated-manifest.json
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-07-jackson-request-response.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java
    - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
    - core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java
    - core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java
    - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java
    - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/tenant/TenantOffboardKeycloakHookIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorFailOpenTest.java
    - core-java/src/test/java/uk/jtoye/core/ai/ImageAnalysisServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java

key-decisions:
  - "38-07: the Keycloak disable PUT is asserted as the whole searched representation with only enabled flipped, with the JsonNode-as-bean keys checked first so a regression names nodeType. The Jackson-2-node arm through the same Jackson-3 RestClient turns it red with the spike's exact garbage body"
  - "38-07: ImageAnalysisService tolerance comes from its own ObjectReader (FAIL_ON_TRAILING_TOKENS and FAIL_ON_UNKNOWN_PROPERTIES off), proven against both Boot's mapper and a strict mapper. Boot's JsonMapper keeps the 38-05 defaults. FAIL_ON_NULL_FOR_PRIMITIVES is not overridden: the only number is a boxed Double, so no test can need it"
  - "38-07: the WebhookFanoutListener serialize catch and the DemoImageManifest parse catch are kept as JacksonException catches. The import-only migration drops or misses them silently, and RED showed both behaviour changes (an exception out of the @RabbitListener; a raw StreamReadException instead of the loader's IllegalStateException)"
  - "38-07: Boot's JacksonAutoConfiguration registers the ProblemDetail mixin on the JsonMapper (JsonProblemDetailsConfiguration), so the 429 stays flat with no change to its ProblemDetail construction. The plan's STOP condition did not arise"

patterns-established:
  - "BootJsonMapper: JacksonAutoConfiguration in an ApplicationContextRunner is the app's mapper while core-java/src/main declares no Jackson customizer, module bean or spring.jackson key"

requirements-completed: []  # plan declares [BOOT4-05, BOOT4-04]; both are shared with plans not yet summarised (BOOT4-05: 38-17 live offboard; BOOT4-04: 38-08..38-10, 38-12, 38-19). The shared-ID gate decides; see the state step.

coverage:
  - id: D1
    description: "The Keycloak disable PUT body, parsed, has enabled=false, the searched id/username/attributes.tenant_id, nothing else changed, and none of nodeType/array/bigDecimal/containerNode/missingNode/valueNode; Content-Type application/json, URI /admin/realms/{realm}/users/{id}"
    requirement: "BOOT4-05"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java#setUserEnabled_putsTheSearchedRepBack_withOnlyEnabledFlipped_byContent"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/tenant/TenantOffboardKeycloakHookIntegrationTest.java (2/2), TenantLifecycleAdminIntegrationTest.java (10/10)"
        status: pass
      - kind: other
        ref: "evidence/38-07-jackson-request-response.txt Task 1: RED garbage body, Jackson-2-node arm red on nodeType, closing green body"
        status: pass
    human_judgment: false
  - id: D2
    description: "A 429 problem body written by Boot's Jackson-3 JsonMapper keeps the GlobalExceptionHandler structure: exactly {type,title,status,detail,retryAfterSeconds[,tenantId]} at the top level, no properties wrapper"
    requirement: "BOOT4-04"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java (18/18, incl. negativeControl_aMapperWithoutBootsProblemDetailMixin_nestsTheExtensions)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java (1/1)"
        status: pass
    human_judgment: false
  - id: D3
    description: "ImageAnalysisService still tolerates the Jackson-2 model-output shapes (trailing prose with or without braces, unknown fields, null confidence) with Boot's mapper and with a strict mapper, and still rejects text with no object or a truncated object"
    requirement: "BOOT4-04"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/ai/ImageAnalysisServiceTest.java (20/20); arms T and U red"
        status: pass
    human_judgment: false
  - id: D4
    description: "DemoImageManifest loads the same 21 entries (first and last equal to the Jackson-2 load); an unparseable manifest is the loader's IllegalStateException"
    requirement: "BOOT4-04"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java (8/8)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The webhook envelope vendors receive is tree-equal (and byte-equal bar the event id) to the 38-01 Jackson-2 fixture; a serialize failure skips the fan-out without throwing"
    requirement: "BOOT4-04"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/webhook/WebhookFanoutListenerEnvelopeTest.java (4/4)"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/webhook/WebhookDeliveryWorkerIntegrationTest.java (4/4)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Live offboard against the compose Keycloak: the user reads back enabled=false by content"
    requirement: "BOOT4-05"
    verification: []
    human_judgment: true
    rationale: "Out of scope here: the plan prohibits starting, stopping or rebuilding compose services. 38-17 owns the live read-back"

duration: 27min
completed: 2026-10-05
---

# Phase 38 Plan 07: Request/response Jackson users on Jackson 3 Summary

**The spike's Keycloak defect is closed and guarded by content. KeycloakAdminClient now sends Jackson-3 nodes, so tenant offboarding PUTs the user's real representation with `enabled:false` instead of `{"array":false,…,"nodeType":"OBJECT",…}`. The 429 body, model-output tolerance, demo manifest and vendor webhook envelope are on Jackson 3, and their Jackson-2 behaviour is pinned by tests.**

## Performance

- **Duration:** about 27 min
- **Started:** 2026-10-05T10:18:08Z
- **Completed:** 2026-10-05T10:45Z
- **Tasks:** 2 of 2
- **Files:** 17 (6 main, 9 test incl. 2 new, 1 test resource, 1 evidence)

## Accomplishments

- **Keycloak (Task 1, tracer).** The by-content test takes the user from the client's own search, captures the PUT verbatim and parses it. The body must equal the searched representation with only `enabled` flipped, and must contain none of the JsonNode-as-bean keys. On the interim tree it was red with the exact garbage body the spike measured. After the move to `tools.jackson` it is green. Sending a Jackson-2 node through the same Jackson-3 RestClient turns it red again, naming `nodeType`.
- **429 problem body.** It is written by Boot's real JsonMapper, built from `JacksonAutoConfiguration` and not hand-assembled. Both paths have an exact top-level member set. A permanent negative control shows that a mixin-less mapper nests the members under `properties`. The ProblemDetail construction is unchanged.
- **Model output.** ImageAnalysisService reads the model text with its own `ObjectReader`, which ignores trailing tokens and unknown properties. Trailing prose (with and without braces), unknown fields and a null confidence parse to the measured Jackson-2 values. Each case runs with Boot's mapper and with a strict one. Text with no object, or a truncated object, still gives no result.
- **Demo manifest.** The first and last entries equal the Jackson-2 load, and an unparseable manifest is again the loader's `IllegalStateException`.
- **Webhook envelope (new test).** The real listener's delivered payload is tree-equal to the 38-01 fixture, and byte-equal to it apart from the event id. A timestamps mapper is caught. A serialize failure skips the fan-out without throwing out of the `@RabbitListener`.
- **Full unit suite:** 1403 tests, 0 failures, 1 skipped. That is 1385 plus 18. The 38-04 ledger's 38-07 red (KeycloakAdminClientTest) is closed.

## Task Commits

1. **Task 1: Tracer: the Keycloak disable PUT carries a correct body, asserted by content**
   - `078029ee` test(38-07): RED
   - `d4e24605` feat(38-07): GREEN
   - `306a7c33` docs(38-07): evidence (RED, GREEN, the Jackson-2-node arm, closing run)
   - Tracer gate (end-of-phase, automated-only verify): the closing clean run re-ran both verify commands green, then expanded to Task 2.
2. **Task 2: Rate-limit problem body, model-output tolerance, demo manifest and webhook envelope on Jackson 3**
   - `e7679332` test(38-07): RED (tests plus the import-only migration)
   - `fee432af` feat(38-07): GREEN
   - `d8844e34` docs(38-07): evidence (baseline, catch review, arms T/U/R, closing run)

**Plan metadata:** the docs commit that adds this SUMMARY.

## Files Created/Modified

- `KeycloakAdminClient.java` / `KeycloakDeprovisionService.java`: `tools.jackson` JsonMapper, JsonNode and ObjectNode; `asString()`; `page.values()`.
- `RateLimitInterceptor.java`: injects Boot's Jackson-3 `JsonMapper`. The ProblemDetail construction is unchanged.
- `ImageAnalysisService.java`: Jackson-3 `JsonMapper` plus the tolerant `analysisReader`.
- `DemoImageManifest.java`: a local Jackson-3 `JsonMapper`, `catch (IOException | JacksonException)`, and a package-private `load(String)` seam.
- `WebhookFanoutListener.java`: injects Boot's Jackson-3 `JsonMapper`, with `catch (JacksonException)` around the single envelope serialization.
- `WebhookFanoutListenerEnvelopeTest.java` (new), `BootJsonMapper.java` (new test helper), `truncated-manifest.json` (new test resource).
- Tests migrated or extended: KeycloakAdminClientTest, KeycloakDeprovisionServiceTest, TenantOffboardKeycloakHookIntegrationTest, RateLimitInterceptorTest, RateLimitInterceptorFailOpenTest, ImageAnalysisServiceTest, DemoImageManifestTest.
- `evidence/38-07-jackson-request-response.txt`: every run, body, arm and criterion in both directions.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| Keycloak PUT by content | 5/5 green; body `{"id":…,"enabled":false,…,"attributes":{"tenant_id":[…]}}` | RED: garbage body, keys found `[nodeType, bigDecimal, …]`; Jackson-2-node arm: the same |
| AC1 `nodeType` in the test (awk) | 4 | 0 on the pre-plan blob |
| AC2 no Jackson-2 databind in tenant/keycloak main | rc=1 | 4 import lines at c5aac555 |
| RED before GREEN (Task 1) | `merge-base --is-ancestor 078029ee d4e24605` rc=0 | reversed rc=1 |
| Trailing-text tolerance | 20/20 | RED 3 red; arm T (override removed) 2 red on the trailing case |
| Unknown-field tolerance (strict mapper) | green | RED red; arm U red |
| 429 flat, exact member set | 18/18 | arm R (mixin-less mapper in main) 3 red; permanent negative control nests |
| Manifest parse failure is IllegalStateException | 8/8 | RED: raw `StreamReadException` |
| Envelope tree/bytes vs fixture | 4/4 | permanent timestamps control is not tree-equal |
| Serialize failure skips fan-out | green | RED: `InvalidDefinitionException` escaped |
| Task 2 AC1: no Jackson-2 databind/core in the four files | rc=1 | 7 lines at c5aac555 |
| Task 2 AC2: `FAIL_ON_TRAILING_TOKENS` in ImageAnalysisService | 2 lines | rc=1 at c5aac555; arm T shows the override itself is load-bearing |
| Plan verification 3: the six main files | rc=1 | 11 lines at c5aac555; positive control: annotation imports still match |
| RED classifier | RED_EVIDENCE_OK for 4 records (Task 1, ImageAnalysis, DemoImageManifest, Envelope) | green XML -> INVALID_RED (unexpected_green) |

Every arm ran on a committed tree. Each restore used `git checkout` and was verified by sha256 and blob against HEAD. Each arm set ended with a closing clean run of the full `<verify>`.

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- The Keycloak assertion requires the whole searched representation, not only `enabled`.
- Tolerance lives in the service's own reader, and is proven against a strict mapper as well as Boot's.
- Both catches the compiler stopped demanding are kept, and are pinned.
- Boot's mapper carries the ProblemDetail mixin, so the 429 needed no change.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] RateLimitInterceptorFailOpenTest constructs the interceptor**
- **Found during:** Task 2 (constructor change to `JsonMapper`).
- **Issue:** this file is not in the plan's file list, but it calls `new RateLimitInterceptor(provider, Jackson2ObjectMapperBuilder…)`, which no longer compiles.
- **Fix:** it now passes `BootJsonMapper.get()`, and its comment is updated. The test's behaviour is unchanged (fail-open path).
- **Committed in:** `e7679332`.

**2. [Rule 2 - Strengthened check] BootJsonMapper test helper (new file, not in the plan's list)**
- **Issue:** the plan requires the 429 shape and the envelope to be checked against Boot's Jackson-3 JsonMapper. A unit test has no Boot bean. Hand-adding the ProblemDetail mixin (as the 38-06 test does) would prove the mixin works, not that Boot's bean carries it.
- **Fix:** `uk.jtoye.core.testsupport.BootJsonMapper` builds the bean with `JacksonAutoConfiguration` in an `ApplicationContextRunner`. Three test classes share it.
- **Committed in:** `e7679332`.

**3. [Rule 2 - Strengthened check] Test seams and resources**
- `DemoImageManifest.load(String)` is package-private, and `public load()` delegates to it. This lets a truncated manifest (a new test resource) exercise the real parse path and its catch.
- The ImageAnalysis cases run through `analyze()` against a JDK `HttpServer` stand-in for Ollama, so `parseAnalysisJson` stays private and the real envelope parse is exercised too.
- **Committed in:** `e7679332`.

**4. [Rule 2 - Missing critical] Catch behaviour pinned, not only reviewed**
- **Issue:** the plan asks every former checked catch to keep its fallback. Two of them (the DemoImageManifest parse catch and the WebhookFanoutListener serialize catch) change silently under an import-only migration.
- **Fix:** a RED test for each, and the catches are restored as `JacksonException` catches. The RED tree for the listener is the compiler-minimal migration, with the catch dropped because nothing forces it any more.
- **Committed in:** `e7679332` (tests), `fee432af` (catches).

**5. [Rule 1 - Measured vs predicted] Three predicted reds did not occur**
- The null-confidence case was not red, because `confidence` is a boxed `Double`.
- The envelope tree test was not red, because records are written in declaration order (as 38-05 measured).
- The 429 flattening was not red, because Boot registers the mixin.
- Each was recorded rather than forced red. Their falsifiability comes from permanent negative controls (429, envelope) and arms T/U/R.

### Additions beyond the plan's minimum

- Arms U and R (the plan asked only for arm T).
- An exact member-set assertion on both 429 paths.
- A byte-equality test for the envelope (stronger than tree equality, because vendors' HMAC covers bytes).
- A "tolerance has a limit" case.
- A whole-body equality check on the Keycloak PUT.

### Not changed, recorded

- **TenantLifecycleAdminIntegrationTest** builds no user representation and is untouched. The plan made that conditional. It was run in both the GREEN and the closing integration runs.

---

**Total deviations:** 5 (1 blocking, 3 strengthened checks or missing-critical, 1 measured-vs-predicted record).
**Impact on plan:** none widens scope. The only production-visible change beyond the planned migration is safer: an `{"access_token":null}` token response is now rejected instead of returning the literal token "null" (Jackson 3 `asString()` on a null node is ""), which is recorded in the evidence.

## TDD Gate Compliance

- RED before GREEN in both tasks: `078029ee` → `d4e24605` and `e7679332` → `fee432af`. The gate greps (`^test\((0*38)-(0*7)\):` and `^feat\(…\):`) find both pairs. No refactor commit was needed.
- `gsd check tdd-red-evidence` returned RED_EVIDENCE_OK (target_test_failed) for KeycloakAdminClientTest, ImageAnalysisServiceTest, DemoImageManifestTest and WebhookFanoutListenerEnvelopeTest, and for the Jackson-2-node arm. In the fail direction, the green XML returned INVALID_RED (unexpected_green).
- Task 2's RED commit includes the import-only main migration. The plan prescribes this, because the assertion RED needs the class on the Jackson-3 mapper first.

## Issues Encountered

- The machine's base-python guard refused a `/usr/bin/python3` edit helper. The edits were made with the Edit tool and sed instead.
- One arm attempt (U) was an invalid edit that failed compilation. It is recorded as VOID and was redone; its restore was verified like the rest.

## Known Stubs

None. A scan for TODO/FIXME/placeholder over the 16 changed core-java files returned rc=1, and a positive control matched (rc=0).

## Threat Flags

None. No endpoint, auth path or schema is added; `BootJsonMapper` is test-only.
- T-38-17 (garbage PUT leaves users enabled): mitigated by the by-content test and the Jackson-2-node arm. The live read-back is 38-17's.
- T-38-18 (model text): mitigated by the tolerance tests with a strict mapper and the "still rejects" case. No polymorphic typing is involved.
- T-38-19 (envelope): mitigated by tree and byte equality with the Jackson-2 fixture.
- T-38-SC: no package was installed.

## User Setup Required

None. No external service configuration is required, and no compose service was started, stopped or rebuilt.

## Next Phase Readiness

- **38-08 / 38-09 / 38-10:** `BootJsonMapper` is available for unit tests that need Boot's Jackson-3 mapper. It is not the full-context bean; `Jackson3WireContractTest` covers that.
- **38-12 / 38-19:** the six main files of this plan carry no Jackson-2 databind/core import. Their tests no longer inject the Jackson-2 bean, except TenantLifecycleAdminIntegrationTest, which still autowires the Jackson-2 `ObjectMapper` and so belongs to 38-19's test sweep.
- **38-17:** the live offboard read-back from the compose Keycloak is still owed (coverage D6).
- **38-16 / 38-18:** nothing new to name in ADR-0006 or the PR body from this plan. The trailing-token 400 stays as 38-05 decided, and ImageAnalysisService's leniency covers only the model's text, not request bodies.
- The unit suite has no red left. 38-14 owns the remaining integration red (OpenApiSnapshotTest).

## Self-Check: PASSED

- FOUND: all 4 created files and all 13 modified files (checked with `[ -f ]` before writing)
- FOUND commits: 078029ee, d4e24605, 306a7c33, e7679332, fee432af, d8844e34. `git rev-list --count c5aac555..HEAD` = 6.
- The acceptance criteria and plan verification were re-run in both directions (table above, and the evidence file).

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

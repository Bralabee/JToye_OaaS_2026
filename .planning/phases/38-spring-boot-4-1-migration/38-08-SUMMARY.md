---
phase: 38-spring-boot-4-1-migration
plan: 08
subsystem: messaging
tags: [spring-boot-4, jackson-3, spring-amqp-4, rabbitmq, transactional-outbox, rolling-deploy, testcontainers, tdd]
status: complete

requires:
  - phase: 38-01
    provides: "jackson2-golden/amqp (6 bodies + 6 header tables) and jackson2-golden/outbox (6 rows) written by the Boot 3.5.16 serializers, and the GoldenSamples factory"
  - phase: 38-03
    provides: "the Boot 4.1.1 tree with Spring AMQP 4.1.1 and Boot's Jackson-3 JsonMapper bean"
  - phase: 38-07
    provides: "BootJsonMapper test helper (Boot's auto-configured Jackson-3 JsonMapper for unit tests)"
provides:
  - "RabbitMQConfig.jsonMessageConverter() returns new JacksonJsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES); the trusted block is byte-identical to the plan base"
  - "OrderEventPublisher, PaymentEventPublisher, RefundEventPublisher, OnboardingEventPublisher, MediaAssetService, PaymentEventOutboxFlusher and MediaEventOutboxFlusher inject tools.jackson.databind.json.JsonMapper; every former JsonProcessingException fallback is kept as a JacksonException catch"
  - "AmqpJackson2CompatibilityTest and OutboxPayloadCompatibilityTest (both DELIBERATE-JACKSON2): six payloads x both persisted forms x both deploy directions (24 cases), plus a truncated-row poison case per payload"
  - "AmqpTypeIdDispatchIntegrationTest: a raw Boot-3.5 PaymentEvent message on a real RabbitMQ 4.3.4 broker is dispatched by __TypeId__ to the matching class-level @RabbitHandler"
  - "InFlightFixtures: Jackson-free loader and instant-equality comparison for the in-flight fixtures"
  - "RESEARCH A2 answered: the default Jackson-3 converter mapper reads the Boot-3.5 epoch-decimal dates to the nanosecond; no drain or flush step is needed"
affects: [38-09, 38-10, 38-12, 38-16, 38-18, 38-19]

actuals:
  tokens: 18690    # chars/4 over the added lines of f97a8161..ee1cd76e (74762 chars, 20 files)
  tasks: 3
  commits: 9       # MEASURED: git rev-list --count f97a8161..HEAD at SUMMARY write
plan_head_before: f97a8161b160d3557c403911de0731ee81a2acba
plan_head_after: ee1cd76ea1d58e94ecfacd79d8ded2313a2b97b4

tech-stack:
  added: []
  patterns:
    - "In-flight compatibility is proven against captured Boot-3.5 bytes in BOTH deploy directions, with a lossy-writer negative control per direction so the reverse check can fail"
    - "A payload whose package is deliberately untrusted (media) is converted the way its only consumer converts it (inferred argument type), with a separate case proving its __TypeId__ alone is still refused"
    - "RED for a type-swap migration is the compiler-minimal swap: the catches the compiler stops demanding are dropped, and the tests that pin each fallback go red on behaviour"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/boot4/AmqpJackson2CompatibilityTest.java
    - core-java/src/test/java/uk/jtoye/core/boot4/OutboxPayloadCompatibilityTest.java
    - core-java/src/test/java/uk/jtoye/core/boot4/AmqpTypeIdDispatchIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/boot4/InFlightFixtures.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-08-amqp-outbox.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventPublisher.java
    - core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEventPublisher.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxFlusher.java
    - core-java/src/test/java/uk/jtoye/core/config/RabbitMQConfigMessageConverterTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderEventPublisherTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/OutboxTenantListingFailureTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventPublisherTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/RefundEventPublisherTest.java
    - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingEventPublisherTest.java

key-decisions:
  - "38-08: the AMQP converter uses Spring AMQP 4's own default mapper (new JacksonJsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES)). It reads the Boot-3.5 epoch-decimal dates to the nanosecond (RESEARCH A2 = yes), so the (JsonMapper, String...) fallback and any drain step are unnecessary. No owner decision was needed"
  - "38-08: media's in-flight messages are proven through the inferred argument type that MediaProcessingWorker's typed listener sets; uk.jtoye.core.media stays untrusted, and a permanent case pins that its __TypeId__ alone is refused by both the Boot-4 and the Boot-3.5 converter"
  - "38-08: each outbox flusher's poison catch (now JacksonException) stays ahead of catch (Exception). Spring AMQP 4 wraps its own Jackson failures in MessageConversionException, so only readValue reaches it and broker failures still take the backoff path"
  - "38-08: every publisher fallback is restored verbatim from the plan base with only the exception type changed, and each is pinned by a test that the compiler-minimal swap turned red"

patterns-established:
  - "InFlightFixtures.assertSameEvent: record components equal, OffsetDateTime by instant; negative controls show it fails on a 1 ns shift and on a lost component"
  - "Coverage of a fixture family is asserted against the 38-01 MANIFEST (exact name set), so a new fixture cannot sit unchecked"

requirements-completed: [BOOT4-06]  # plan declares [BOOT4-06, BOOT4-04]; requirements.ready-ids: BOOT4-06 ready (38-01 done), BOOT4-04 blocked (38-09, 38-10, 38-12, 38-19 not summarised)

coverage:
  - id: D1
    description: "The AMQP converter is JacksonJsonMessageConverter with the byte-identical exact trusted-package list; the three trust tests stay green"
    requirement: "BOOT4-06"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/config/RabbitMQConfigMessageConverterTest.java (7/7, incl. converterIsTheJackson3Converter, rejectsClassOutsideAllowlist, allowlistIsNotTrustAll, everyMultiHandlerPayloadTypeIsTrusted)"
        status: pass
      - kind: other
        ref: "evidence/38-08-amqp-outbox.txt: TRUSTED block sha256 d849ea5a... at base and HEAD; arm A red"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every AMQP message the Boot-3.5 converter wrote (six fixtures) converts to its golden event on Boot 4, and every message the Boot-4 converter writes is read by the Boot-3.5 converter"
    requirement: "BOOT4-06"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/AmqpJackson2CompatibilityTest.java (18/18: 6 forward, 6 reverse, coverage, boundary, 3 negative controls)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every PENDING outbox row a Boot-3.5 publisher wrote (six fixtures) is read by its owning Boot-4 flusher into the golden event and marked SENT; Boot-4 publisher output is read by a Boot-3.5-built ObjectMapper; a truncated row is poisoned, never retried"
    requirement: "BOOT4-06"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/OutboxPayloadCompatibilityTest.java (21/21: 6 forward, 6 reverse, 6 truncated, coverage, reader config, negative control)"
        status: pass
      - kind: integration
        ref: "PaymentEventOutboxReliabilityIntegrationTest 6/6, OnboardingStallOutboxIntegrationTest 1/1, MediaDedupAttachIntegrationTest 4/4, MediaDurabilityIntegrationTest 11/11, MediaPipelineAzuriteIntegrationTest 3/3"
        status: pass
    human_judgment: false
  - id: D4
    description: "On a real RabbitMQ 4.3.4 broker, a raw Boot-3.5 PaymentEvent message is dispatched by __TypeId__ to the PaymentEvent @RabbitHandler (equal to the golden event); the isDefault handler receives nothing"
    requirement: "BOOT4-06"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/AmqpTypeIdDispatchIntegrationTest.java (1/1); OrderEventFanoutTopologyIntegrationTest (1/1)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The eight messaging main files carry no Jackson-2 databind/core import, and each former checked-exception fallback is kept and pinned"
    requirement: "BOOT4-04"
    verification:
      - kind: other
        ref: "git grep over RabbitMQConfig, OrderEventPublisher, core/payment, OnboardingEventPublisher, core/media: rc=1 (14 lines at the plan base)"
        status: pass
      - kind: unit
        ref: "OrderEventPublisherTest 3/3, PaymentEventPublisherTest 4/4, RefundEventPublisherTest 4/4, OnboardingEventPublisherTest 2/2, PaymentEventOutboxFlusherTest 15/15, OutboxPayloadCompatibilityTest truncated media case"
        status: pass
    human_judgment: false
  - id: D6
    description: "The trust boundary bites: arm A (payment package removed), arm B (__TypeId__ java.net.URI) and arm C (truncated RefundEvent row) each went red; restores verified by sha256; closing runs green"
    requirement: "BOOT4-06"
    verification:
      - kind: other
        ref: "evidence/38-08-amqp-outbox.txt Task 3 arms section"
        status: pass
    human_judgment: false

duration: 30min
completed: 2026-10-05
---

# Phase 38 Plan 08: Messaging path on Jackson 3, in-flight state proven Summary

**The AMQP converter and the eight messaging classes are on Jackson 3, with the exact trusted-package list unchanged. Boot-3.5 messages on the broker and PENDING outbox rows are proven readable by Boot 4, and Boot-4 output is proven readable by Boot-3.5 pods, for all six payloads. A raw Boot-3.5 message is dispatched by `__TypeId__` on a real RabbitMQ broker. D-01's in-flight hazard is closed in code, so no drain or flush step is needed at deploy.**

## Performance

- **Duration:** about 30 min
- **Started:** 2026-10-05T10:47:56Z
- **Completed:** 2026-10-05T11:18:04Z
- **Tasks:** 3 of 3
- **Files:** 20 (8 main, 11 test including 4 new, 1 evidence)

## Accomplishments

- **Converter.** `RabbitMQConfig.jsonMessageConverter()` returns `new JacksonJsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES)`. The trusted block is byte-identical to the plan base (sha256 `d849ea5a…`). The Javadoc now names `DefaultJacksonJavaTypeMapper`, which still matches by exact equality.
- **RESEARCH A2 answered: yes.** Spring AMQP 4's default converter mapper reads `"timestamp":1791117296.123456789` to the nanosecond. The `-offset` message, written at +01:00 with the offset dropped, reads to the same instant. The fallback constructor was not needed, and no owner decision or drain step was either.
- **Both persisted forms, both directions, all six payloads.** That is 24 cases: AMQP forward and reverse, outbox forward and reverse.
  - Forward outbox rows go through their owning flusher, `PaymentEventOutboxFlusher` or `MediaEventOutboxFlusher`.
  - The reverse readers are what a Boot-3.5 pod holds: `Jackson2JsonMessageConverter` with the same trusted packages, and a `Jackson2ObjectMapperBuilder` mapper with Boot 3.5's date defaults.
  - Each direction has a lossy-writer negative control, so the reverse cases can fail.
- **Media handled honestly.** Its package is not trusted, on 3.5 or now. Its cases carry the inferred argument type that its only consumer (a typed listener) sets. A permanent case shows its `__TypeId__` alone is refused by both converters.
- **Fallbacks kept.** All seven classes inject Boot's `JsonMapper`, and every `JsonProcessingException` catch is restored verbatim as a `JacksonException` catch. Each one was shown necessary by a RED in which the compiler-minimal swap dropped it:
  - a corrupt order or media row was retried as PENDING forever;
  - the order, refund and onboarding placeholders were lost;
  - the payment publisher threw a raw `JacksonException`.
- **Real broker.** `AmqpTypeIdDispatchIntegrationTest` publishes the raw `PaymentEvent` fixture to RabbitMQ 4.3.4. It is consumed through the application's `rabbitListenerContainerFactory` by a listener of `WebhookFanoutListener`'s shape, which dispatches by `__TypeId__`. The event is equal to the golden one, and the catch-all stays empty.
- **Full unit suite:** 1444 tests, 0 failures, 1 skipped. That is 38-07's 1403 plus the 41 added here.

## Task Commits

1. **Task 1 (tracer): one order event survives both persisted forms**
   - `62abed7b` test(38-08): RED (3 of 34 red on target)
   - `e693b554` feat(38-08): GREEN (34/34)
   - `8ff4114d` docs(38-08): evidence
   - Tracer gate: interactive, end-of-phase mode, automated-only verify. It was re-run green (34/34), then expanded.
2. **Task 2: every payload, both forms, both directions; remaining publishers and the media flusher**
   - `dde52b59` test(38-08): RED (4 of 49 red on target)
   - `6882de7b` feat(38-08): GREEN (unit 49/49, integration 25/25)
   - `5e7f6a75` docs(38-08): evidence
3. **Task 3: real-broker `__TypeId__` dispatch, and arms A/B/C**
   - `69e17fed` test(38-08): RED (the PaymentEvent handler received nothing; the catch-all got the converted event)
   - `9fc97c09` feat(38-08): GREEN (1/1, analog 1/1)
   - `ee1cd76e` docs(38-08): evidence, with arms and closing runs

**Plan metadata:** the docs commit that adds this SUMMARY.

## Files Created/Modified

- `RabbitMQConfig.java`: the Jackson-3 converter, with Javadoc on why it reads Boot-3.5 messages.
- `OrderEventPublisher`, `PaymentEventPublisher`, `RefundEventPublisher`, `OnboardingEventPublisher`: inject `JsonMapper`, with fallbacks as `JacksonException` catches.
- `PaymentEventOutboxFlusher`, `MediaEventOutboxFlusher`: inject `JsonMapper`. The poison catch sits ahead of `catch (Exception)`.
- `MediaAssetService`: only the event-payload serialization changed (`JsonMapper`, `IllegalStateException` kept).
- `AmqpJackson2CompatibilityTest`, `OutboxPayloadCompatibilityTest` (new, DELIBERATE-JACKSON2), `AmqpTypeIdDispatchIntegrationTest` (new, `@Tag("testcontainers")`), `InFlightFixtures` (new, Jackson-free helper).
- Tests migrated to Boot's `JsonMapper`: `RabbitMQConfigMessageConverterTest` (plus the instance test), `OrderEventPublisherTest`, `PaymentEventOutboxFlusherTest`, `OutboxTenantListingFailureTest`, `PaymentEventPublisherTest` (plus the failure test), `RefundEventPublisherTest`, `OnboardingEventPublisherTest`.
- `evidence/38-08-amqp-outbox.txt`: every run, RED record, arm, restore and criterion, in both directions.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| T1 AC1 converter line in RabbitMQConfig | 1 line, rc=0 | 0 lines at the RED commit, rc=1 |
| T1 AC2 three trust tests passed | present, 0 failure/error/skip elements | arm A turns the D-03 guard red |
| T1/T2/T3 RED before GREEN | `merge-base --is-ancestor` rc=0 for each pair | reversed rc=1 |
| Converter is Jackson 3 | green | RED: `Jackson2JsonMessageConverter` |
| Order and payment-family flusher poison branch | green | RED: corrupt row stayed PENDING |
| Media flusher poison branch | green | RED: truncated media row retried |
| Publisher placeholders and IllegalStateException | green | RED: exception escaped, or raw `JacksonException` |
| Forward/reverse compatibility | 24/24 | negative controls: 1 ns shift, lost component, ms-timestamp writer |
| T2 AC1 no Jackson-2 databind/core in the 8 files | rc=1 | 14 lines at the plan base |
| T2 AC2 DELIBERATE-JACKSON2 | 2 (1 per file) | 0 in the Task 1 versions |
| Trust boundary | dispatch 1/1 | arm A: "PaymentEvent is not in the trusted packages" (unit and broker); arm B: "java.net.URI is not in the trusted packages" |
| Unreadable outbox row is poisoned | 6 truncated cases green | arm C: forward RefundEvent case red with `lastError=payload deserialization failed…` |
| T3 AC2 fixtures untouched | `git diff f97a8161..HEAD -- jackson2-golden` empty, rc=0 | 44 paths from 767f5658 |
| RED classifier | RED_EVIDENCE_OK for 8 records (3 + 4 + 1) | a passing test named as target gives INVALID_RED |

Every arm ran on a committed tree. Each restore was a `cp` from a scratch backup, verified by sha256 against the HEAD blob. A closing clean run followed: integration 2/2 and unit 165/165. `rg -uu` found no arm marker left, and its positive control matched.

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- The converter uses its own default mapper, because A2 measured yes.
- Media is proven through its consumer's real conversion path, without widening the trust list.
- The poison catches stay ahead of `catch (Exception)`.
- Every fallback is restored verbatim and pinned.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The RED trees carry the compiler-minimal swap of the main files (Tasks 1 and 2)**
- **Issue:** the plan wanted Task 1's RED to be the instance assertion, and Task 2's RED to be "the publisher-side reverse cases". A test that hands the flushers or publishers Boot's `JsonMapper` does not compile while they still take a Jackson-2 `ObjectMapper`, and a compile error is INVALID_RED.
- **Fix:** each RED commit includes the type swap that an import-only migration produces. That swap drops the `JsonProcessingException` catches, because they no longer compile. This is the 38-07 precedent. The RED targets are the behaviours that swap loses, each confirmed `RED_EVIDENCE_OK`. GREEN restores the catches. The reverse cases were green on RED, as they must be on any compiling tree; their falsifiability comes from the permanent lossy-writer controls.
- **Committed in:** `62abed7b`, `dde52b59`.

**2. [Rule 2 - Missing critical] Fallbacks pinned that no test covered**
- **Issue:** under Jackson 3 nothing forces the catches. `MediaEventOutboxFlusher`'s poison branch and `PaymentEventPublisher`'s `IllegalStateException` had no test.
- **Fix:** a truncated-row poison case per payload in `OutboxPayloadCompatibilityTest`, which also makes arm C permanent, and a serialization-failure case in `PaymentEventPublisherTest`. Both were red on the RED tree.
- **Committed in:** `dde52b59`.

**3. [Rule 2 - Strengthened check] Media proven through its real consumer path; coverage against the MANIFEST**
- **Issue:** `uk.jtoye.core.media` is not trusted, so a header-only conversion of the media fixture is refused, in Boot 3.5 as much as now. Forcing it green would have meant widening the trust list, which is prohibited.
- **Fix:** the media cases set the inferred argument type that the typed `MediaProcessingWorker` listener sets. `mediaTypeIdAloneIsRefused` pins the refusal. `everyAmqpFixtureIsCovered` and `everyOutboxFixtureIsCovered` assert the six names against the 38-01 MANIFEST.
- **Committed in:** `dde52b59`.

**4. [Rule 2 - Strengthened check] Shared helper `InFlightFixtures` (new file, not in the plan's list)**
- It is a Jackson-free loader with an instant-equality comparison, used by all three compatibility tests. It keeps Jackson-2 imports confined to the two DELIBERATE-JACKSON2 classes, which is what 38-12's allowlist needs. Its negative controls live in `AmqpJackson2CompatibilityTest`.
- **Committed in:** `62abed7b`, `dde52b59`.

**5. [Rule 1 - Bug, own test] Jackson 2's JavaTime module id**
- My first `boot35ReaderIsConfiguredLikeBoot35` looked for "JavaTime". The registered id is `jackson-datatype-jsr310`. It was fixed before the RED commit and re-run, so Task 2's RED holds only target failures.
- **Committed in:** `dde52b59`.

**6. [Rule 1 - Cosmetic] A parameterised display name lost an apostrophe to JUnit's MessageFormat**
- It was reworded.
- **Committed in:** `6882de7b`.

### Not changed, recorded

- **The three media integration tests** construct nothing with a mapper (they autowire), so they needed no edit. They ran green in the integration verify.
- **`OrderEventFanoutTopologyIntegrationTest`, `PaymentEventOutboxReliabilityIntegrationTest` and `OnboardingStallOutboxIntegrationTest`** still use Jackson-2 readers: a `Jackson2JsonMessageConverter`, and the interim `ObjectMapper` bean. They are not in this plan's file list, and they belong to 38-19's test sweep. Each now reads Boot-4 output through a Jackson-2 reader in a real context, which is the reverse direction again, and each is green.
- **`MediaAssetService.serialize`'s `IllegalStateException`** is kept but not unit-pinned. The service reaches it only through its large accept paths, and the caller's transaction rolls back either way.

---

**Total deviations:** 6 (1 blocking, 3 strengthened or missing-critical, 2 small bugs in my own tests).
**Impact on plan:** none widens scope or changes production behaviour beyond the planned migration. The trust list, the fixtures and the RefundEventPublisher no-mapper branch are unchanged.

## TDD Gate Compliance

- RED before GREEN in all three tasks: `62abed7b` → `e693b554`, `dde52b59` → `6882de7b`, `69e17fed` → `9fc97c09`. The gate greps (`^test\((0*38)-(0*8)\):` and `^feat\(…\):`) find all three pairs. No refactor commit was needed.
- `gsd check tdd-red-evidence` gave RED_EVIDENCE_OK (target_test_failed) for 8 records:
  - Task 1: the converter instance, the order/payment flusher poison and the order publisher placeholder.
  - Task 2: the onboarding and refund placeholders, the payment `IllegalStateException` and the media truncated row.
  - Task 3: the dispatch.
- Controls naming a passing test gave INVALID_RED (no_target_test_failure).
- Two classifier facts were learned and recorded: it matches the XML's display name, not the method name, and it keeps XML entities (`&quot;`) in names.
- Task 3's GREEN is test-scope only (the probe's handler). The production path under test landed in Task 1's GREEN.

## Issues Encountered

- The machine's base-python guard refuses Python script files. The edit scripts ran in the `engineering-doctrine` conda env, as the contract prescribes.
- The blind-search guard refused one compound command that used a `.gitignore`-honouring grep for an absence check, so nothing in it ran (the arm C restore included). It was re-issued with `rg -uu` and a positive control.
- A slice in my first Task 1 edit script matched the first `catch (Exception e)` in the file, which duplicated text. It was caught by `git diff --stat` before any build. The file was restored from the scratch backup (byte-equal to HEAD) and the edit redone, anchored after the target.

## Known Stubs

None. The scan for TODO/FIXME/placeholder/coming soon/not available over the 19 changed code files matched only the domain term "poisoned placeholder" (the outbox fallback row) and log or test strings.

## Threat Flags

None. No endpoint, auth path or schema was added. The new listener and queue are test-scope.
- T-38-20 (`__TypeId__` gadget): mitigated. The exact list is unchanged (block hash), the trust tests are green, and arm B refuses `java.net.URI`. Media's untrusted `__TypeId__` is still refused.
- T-38-21 (in-flight state unreadable after deploy): mitigated. All six payloads are proven in both forms and both directions, and on a real broker. No drain step is needed.
- T-38-22 (malformed outbox payload): mitigated. Six permanent truncated-row cases cover it, and arm C.
- T-38-SC: no package was installed.

## User Setup Required

None. No compose service was started, stopped or rebuilt; only Testcontainers were used.

## Next Phase Readiness

- **38-09 / 38-10:** `InFlightFixtures.assertSameEvent` and the MANIFEST-coverage pattern are available.
- **38-12:** the two test classes that may keep Jackson-2 imports are marked `DELIBERATE-JACKSON2: emulates Boot-3.5 pods during a rolling deploy`. In `core-java/src/test/java/uk/jtoye/core/boot4`, only `OutboxPayloadCompatibilityTest` imports `com.fasterxml.jackson`. `AmqpJackson2CompatibilityTest` uses Spring's deprecated `Jackson2JsonMessageConverter`.
- **38-19:** the sweep must cover `OrderEventFanoutTopologyIntegrationTest` (consumes with `Jackson2JsonMessageConverter`), `PaymentEventOutboxReliabilityIntegrationTest` and `OnboardingStallOutboxIntegrationTest` (both autowire the Jackson-2 `ObjectMapper`).
- **38-16 / 38-18:** nothing new for ADR-0006 or the PR body beyond this: in-flight AMQP messages and outbox rows need no deploy step, both directions are proven, and Boot-4 AMQP bodies now carry ISO-8601 dates where Boot 3.5 wrote epoch decimals (Boot-3.5 readers accept both).
- BOOT4-06 is ready to mark complete. BOOT4-04 stays open (38-09, 38-10, 38-12, 38-19).

## Self-Check: PASSED

- FOUND: the 5 created files and the 15 modified files (`[ -f ]` before writing).
- FOUND commits: 62abed7b, e693b554, 8ff4114d, dde52b59, 6882de7b, 5e7f6a75, 69e17fed, 9fc97c09, ee1cd76e. `git rev-list --count f97a8161..HEAD` = 9.
- All acceptance criteria and the plan verification were re-run in both directions (table above, and the evidence file).

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

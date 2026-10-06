---
phase: 38-spring-boot-4-1-migration
plan: 01
subsystem: testing
tags: [jackson2, golden-fixtures, idempotency, amqp, redis, outbox, spring-boot-3.5, boot4-migration]

requires: []
provides:
  - "Jackson-free deterministic sample factory uk.jtoye.core.boot4.GoldenSamples (23 factories)"
  - "38 Jackson-2 golden fixtures under core-java/src/test/resources/jackson2-golden, each written by a production Boot 3.5.16 serializer"
  - "idempotency/request-hashes.tsv: 7 endpoint ids mapped to the request_hash IdempotencyService stored under Jackson 2"
  - "Permanent Jackson-free GoldenFixturesIntegrityTest (sha256 and length per MANIFEST row, exact family sets, hash proof, factory determinism)"
  - "README naming CAPTURE_SHA e12177e1aac382bfa3c50f89a32d55091068d203, with regeneration from history only"
affects: [38-05, 38-07, 38-08, 38-09, 38-10, 38-12]

actuals:
  tokens: 19666
  tasks: 3
  commits: 5
plan_head_before: 767f5658ea85979a0f97be40259bd1e149fc0c7e
plan_head_after: 93b913ce72b02c3f00cb90b5071641703b726f47

tech-stack:
  added: []
  patterns:
    - "Golden capture: an env-gated one-shot generator runs on the old tree, its output is committed, then the generator is deleted and kept reachable only through a named commit"
    - "Production-serializer proof: the generator asserts by identity that it holds the same ObjectMapper instance as every writer it imitates"
    - "Version-neutral integrity guard: a test with no import from either Jackson line checks sha256 and length per MANIFEST row"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/boot4/GoldenSamples.java
    - core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java
    - core-java/src/test/resources/jackson2-golden/MANIFEST.tsv
    - core-java/src/test/resources/jackson2-golden/README.md
    - core-java/src/test/resources/jackson2-golden/idempotency/request-hashes.tsv
    - core-java/src/test/resources/jackson2-golden/ (idempotency 7, responses 9, outbox 6, amqp 12, cache 3)
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-01-golden-capture.txt
  modified: []
  deleted:
    - core-java/src/test/java/uk/jtoye/core/boot4/Jackson2GoldenCaptureTest.java (intentional; preserved at e12177e1)

key-decisions:
  - "CAPTURE_SHA is e12177e1aac382bfa3c50f89a32d55091068d203 (Boot 3.5.16, jackson-databind 2.21.7). The generator exists only at that commit."
  - "Family completeness is asserted as EXACT file names per family, not bare counts, so a misnamed capture also fails. This is strictly stronger than the plan's counts, which it implies."
  - "membership() carries ONE shop grant. Map.copyOf with two or more entries iterates in a per-JVM randomised order, so its cache bytes would not be reproducible."
  - "Collections in cached samples use production runtime types (MapStruct ArrayList and LinkedHashMap, Stream.toList, Map.copyOf), because cache type ids record the runtime class."
  - "request-hashes.tsv is rewritten whole on each capture run, not appended, so a second run is byte-identical."

patterns-established:
  - "jackson2-golden fixtures are an oracle: never re-capture them on a Boot-4 tree, and compare against them instead"

requirements-completed: [BOOT4-06, BOOT4-07, BOOT4-08]

coverage:
  - id: D1
    description: "Seven idempotency request fingerprints and their SHA-256 hashes, captured through IdempotencyService's own serialize and sha256Hex on the Boot 3.5 ObjectMapper bean"
    requirement: BOOT4-08
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java#requestHashesAreTheSha256OfTheirFixtures"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java#requestHashesHaveOneRowPerEndpoint"
        status: pass
    human_judgment: false
  - id: D2
    description: "Stored-response and public wire bodies (9), outbox rows (6), AMQP bodies and headers (6+6), and Redis cache values (3), each from its production serializer"
    requirement: BOOT4-07
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java#everyFamilyHoldsExactlyItsExpectedFixtures"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java#manifestsCoverEveryFixtureByteForByte"
        status: pass
    human_judgment: false
  - id: D3
    description: "Tamper-evident fixtures: byte-flip, unlisted-file, deleted-file and tampered-hash arms each turned the integrity test red, naming the file"
    requirement: BOOT4-06
    verification:
      - kind: other
        ref: ".planning/phases/38-spring-boot-4-1-migration/evidence/38-01-golden-capture.txt (byte-flip arm; extra integrity arms)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Deterministic GoldenSamples factory: two capture runs gave byte-identical trees, and every factory returns an equal, fresh object"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java#goldenSamplesFactoriesAreDeterministic"
        status: pass
      - kind: other
        ref: "two JTOYE_GOLDEN_CAPTURE runs, MANIFEST sha256 a46ac5de...405f both times; diff -r empty"
        status: pass
    human_judgment: false
  - id: D5
    description: "Generator retired: absent at HEAD, present at CAPTURE_SHA, MANIFEST unchanged; the README pins the commit and versions"
    verification:
      - kind: other
        ref: "git ls-files core-java/src/test/java/uk/jtoye/core/boot4/Jackson2GoldenCaptureTest.java (empty at HEAD; 1 at e12177e1)"
        status: pass
      - kind: other
        ref: "./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.boot4.*' (no generator XML)"
        status: pass
    human_judgment: false

duration: 18min
completed: 2026-10-05
status: complete
---

# Phase 38 Plan 01: Jackson 2 Golden Capture Summary

**The Boot 3.5.16 production serializers wrote 38 byte-exact Jackson-2 fixtures, so Jackson-3 compatibility can be proven against them later.** They cover 7 idempotency fingerprints with their stored SHA-256 hashes, 9 response and wire bodies, 6 outbox rows, 6 AMQP messages with their headers, and 3 Redis cache values. A permanent test that imports nothing from Jackson guards them by sha256 and length. The generator was deleted after its capture commit, `e12177e1`.

## Performance

- **Duration:** 18 min
- **Started:** 2026-10-04T23:49:30Z
- **Completed:** 2026-10-05T00:07:59Z
- **Tasks:** 3 of 3
- **Files:** 43 paths changed (2 test classes, 39 fixture-tree files, 1 evidence file, 1 deleted generator)

## Accomplishments

- **Every byte comes from a production serializer.** The generator checked this by object identity: all 9 JSON writers hold the same `ObjectMapper` instance it used. Those writers are IdempotencyService, the 4 outbox publishers, MediaAssetService, WebhookFanoutListener, DsarIntakeService and ProblemDetailAuthenticationEntryPoint.
  - Idempotency hashes came from IdempotencyService's own private `serialize` and `sha256Hex`.
  - The context `RabbitTemplate` converter wrote the same AMQP bodies as `RabbitMQConfig.jsonMessageConverter()`.
  - Redis values came from `CacheConfig.jsonRedisSerializer()`.
- `request-hashes.tsv` holds 7 rows. The integrity test proves each one is the sha256 of its fixture. The `orders.create` hash is `7c58d685…` and also equals `sha256sum` of the file.
- `GoldenFixturesIntegrityTest` checks the following, and fails closed when the tree is missing, holds zero manifests or has zero rows:
  - sha256 and length for every MANIFEST row;
  - no unlisted file;
  - the exact fixture set per family;
  - each hash row;
  - factory determinism.
- Two capture runs produced byte-identical trees (MANIFEST sha256 `a46ac5de…405f` both times).
- The README records CAPTURE_SHA, the versions involved (Boot 3.5.16, jackson-databind 2.21.7, spring-amqp 3.2.12, spring-data-redis 3.5.13, spring-web 6.2.19) and which plan consumes each family. It also says regeneration is from history only.

## Findings for later plans (measured, not assumed)

- **38-08: AMQP bodies carry dates as epoch decimals and lose the offset.** For example `"timestamp":1791117296.123456789`. The outbox row for the same event carries `"2026-10-04T13:34:56.123456789+01:00"`. `Jackson2JsonMessageConverter` builds its own mapper, which is not the Boot bean, so a Jackson-3 converter has to read this numeric form.
- **38-09: cache values carry the runtime collection class as a type id.** Examples are `java.util.ImmutableCollections$ListN`, `$Map1`, `java.util.ArrayList` and `java.util.LinkedHashMap`, alongside `["java.lang.Long", 899]` wrappers.
- **38-10: every JSON fixture is in declaration order.** Jackson 3's alphabetical default would change every hash, which is Pitfall 4. The 7 fingerprints are now the literals a frozen fingerprint mapper must reproduce.

## Task Commits

1. **Task 1, tracer (orders.create fingerprint + integrity guard):** `8964ad1b` (test). Javadoc fix `0d3b5a2b` (fix).
2. **Task 2, all families (TDD):** RED `cf27ccfc` (test), GREEN `e12177e1` (feat). This is **CAPTURE_SHA**.
3. **Task 3, retire the generator, write README and evidence:** `93b913ce` (chore).

Plan metadata: the docs commit that follows this SUMMARY.

## TDD Gate Compliance

- **Task 2 RED (`cf27ccfc`):** 3 of 5 tests failed on their target assertions: the family set, hash rows and factories. `gsd check tdd-red-evidence` returned `RED_EVIDENCE_OK`. Factories are discovered reflectively, so RED was an assertion failure and not a compile error.
- **Task 2 GREEN (`e12177e1`):** 5 of 5 pass. No refactor commit was needed.
- **Task 1 (tracer):** also run RED-first. Before any capture, 2 of 2 tests failed on the missing-manifest and missing-hashes assertions, again `RED_EVIDENCE_OK`. The tracer gate then ran in end-of-phase mode with an automated-only verify: re-run green, then expanded to Task 2.

## Verification (both directions, full output in the evidence file)

| Check | Pass direction | Fail direction |
|---|---|---|
| Integrity test | 5/5 green after `cleanTest` | RED-first 2/2 failed; byte-flip failed naming `orders.create.request.json`; unlisted, deleted and tampered-hash arms failed naming each file |
| Restore after arms | `sha256sum -c` over all 38 rows verifies; status clean; re-run green | n/a (closing clean arm) |
| request-hashes rows | 7 | the same awk over the 3-column MANIFEST gives 0 |
| MANIFEST rows | 38 | the tracer-era MANIFEST gives 2 |
| `__TypeId__` | 1 row, `uk.jtoye.core.order.OrderStateChangeEvent` | the same awk over the `.body` gives 0 |
| Determinism | run A = run B = `a46ac5de…` | a one-byte change shows in `diff -rq`, rc=1 |
| No Jackson import in the two Jackson-free classes | `git grep` rc=1 at HEAD | the generator at CAPTURE_SHA matches (rc=0) |
| No reflection in main | `git grep` over `core-java/src/main` rc=1 | GoldenSamples matches |
| No main or build change in the plan | `git diff 767f5658..HEAD` over main and the build files is empty | commit `bc3341d4` lists `core-java/build.gradle.kts` |
| Generator retired | `ls-files` empty at HEAD; no generator XML after `cleanTest`; no stale `.class` | present at CAPTURE_SHA; XML existed while the class was present |
| MANIFEST frozen | HEAD = CAPTURE_SHA = `a46ac5de…` | tracer-era `440889…` differs |

## Decisions Made

All decisions are listed under key-decisions in the frontmatter. The most consequential ones:

- Families are checked by exact file name, which is stronger than the plan's counts and implies them.
- The membership sample carries a single grant, because larger `Map.copyOf` maps iterate in a per-JVM random order.
- Cached samples use production collection runtime types.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The Javadoc of the Jackson-free classes named the forbidden package prefixes**
- **Found during:** Task 1, acceptance criterion 4.
- **Issue:** `GoldenFixturesIntegrityTest` and `GoldenSamples` said "no `com.fasterxml` and no `tools.jackson` import". The criterion's `git grep` therefore matched that sentence (rc=0), which is the known "doc rule fires on its own definition" shape.
- **Fix:** Reworded the Javadoc to "no import from either the Jackson 2 or the Jackson 3 package line". No code change.
- **Files modified:** GoldenFixturesIntegrityTest.java, GoldenSamples.java.
- **Verification:** `git grep` rc=1 at HEAD; positive control on the generator rc=0.
- **Committed in:** `0d3b5a2b`.

**2. [Rule 2 - Strengthened check] Exact per-family file sets instead of bare counts**
- **Found during:** Task 2.
- **Issue:** A count-only check passes when a capture is written under the wrong name.
- **Fix:** `everyFamilyHoldsExactlyItsExpectedFixtures` asserts the exact 38 relative paths. That implies the plan's counts (7+1, 9, 6, 6+6, 3).
- **Committed in:** `cf27ccfc`.

**3. [Rule 2 - Strengthened check] Production identity asserted, not assumed**
- **Found during:** Tasks 1 and 2.
- **Issue:** The plan required production serializers but gave no check that the injected bean is the one the writers use.
- **Fix:** The generator asserts identity against 9 writer beans, unwrapping CGLIB proxies through `AopTestUtils`, and checks the live `RabbitTemplate` converter's bytes.
- **Committed in:** `8964ad1b`, `e12177e1`.

**4. [Rule 1 - Determinism] request-hashes.tsv is rewritten, not appended**
- **Found during:** Task 1 design.
- **Issue:** The plan said "append". Appending would duplicate rows on the second capture run that Task 2 requires.
- **Fix:** Each run rewrites the table whole from a sorted map.
- **Committed in:** `8964ad1b`.

**Total deviations:** 4 auto-fixed (2 Rule 1, 2 Rule 2).
**Impact on plan:** Each one keeps a criterion falsifiable or the capture reproducible. There was no scope creep, and nothing outside the test tree, the fixture tree and the evidence file changed.

## Issues Encountered

- The machine guard (`block-base-python`) refused a `/usr/bin/python3` heredoc used for an edit. The edit was redone with the Edit tool. One-line `/usr/bin/python3 -c` calls, used only to build the RED-evidence JSON, were allowed.
- The host JDK is Ubuntu OpenJDK 25.0.4.1, not Temurin. This is the same caveat 38-SPIKE recorded. The fixtures depend on library versions, which are recorded, and not on the JDK vendor.

## User Setup Required

None. No external service configuration is required, and no compose service was started, stopped or rebuilt.

## Next Phase Readiness

- 38-05, 38-07, 38-08, 38-09 and 38-10 can compare against `jackson2-golden/` and rebuild inputs with `GoldenSamples`, which compiles on both Boot lines.
- BOOT4-06, BOOT4-07 and BOOT4-08 are shared with later plans. This plan supplies their oracle, not their proof. The shared-ID gate decides when they are marked complete.
- **Blocker for later plans:** none. Every later plan must compare against these fixtures and never re-capture them on a Boot-4 tree.

## Self-Check: PASSED

- FOUND: GoldenSamples.java, GoldenFixturesIntegrityTest.java, MANIFEST.tsv, README.md, request-hashes.tsv and the evidence file.
- ABSENT, as expected: Jackson2GoldenCaptureTest.java at HEAD.
- FOUND commits: 8964ad1b, 0d3b5a2b, cf27ccfc, e12177e1 and 93b913ce. The control hash `deadbeef` was reported MISSING, so the check can fail.

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

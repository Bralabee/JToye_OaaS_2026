---
phase: 38-spring-boot-4-1-migration
plan: 09
subsystem: cache
tags: [spring-boot-4, jackson-3, spring-data-redis-4, redis, sec-4, polymorphic-typing, cache-keys, rolling-deploy, testcontainers, tdd]
status: complete

requires:
  - phase: 38-01
    provides: "jackson2-golden/cache (products-ProductDto, shops-ShopDto, shopMembership-Membership) written by the Boot 3.5.16 cache serializer, and the GoldenSamples factory"
  - phase: 38-03
    provides: "the Boot 4.1.1 tree with spring-data-redis 4.1.1 and tools.jackson databind 3.1.7"
provides:
  - "CacheConfig.jsonRedisSerializer() returns GenericJacksonJsonRedisSerializer.builder().enableDefaultTyping(cacheTypeValidator()).build()"
  - "CacheConfig.cacheTypeValidator() returns a tools.jackson BasicPolymorphicTypeValidator, subtype matchers only"
  - "CACHE_TYPE_ID_PREFIXES re-derived from live Jackson-3 bytes: uk.jtoye., java.util., java.math. (java.lang. and java.time. dropped)"
  - "CacheConfig.CACHE_KEY_FORMAT_VERSION = \"v4\"; every region keyed v4:{region}:: through computePrefixWith on the default configuration"
  - "CacheFormatIsolationIntegrationTest: a Boot-3.5 cache entry is never read on a real Redis; jtoye.cache.errors stays 0"
  - "Per-fixture measurement: all three Boot-3.5 cache values are unreadable by the Jackson-3 serializer"
affects: [38-12, 38-16, 38-17, 38-18, 38-19]

actuals:
  tokens: 15329    # chars/4 over the added lines of 9ae069f8..c770b974 (61317 chars, 5 files)
  tasks: 2
  commits: 6       # MEASURED: git rev-list --count 9ae069f8..HEAD at SUMMARY write
plan_head_before: 9ae069f8f540931fb6aa6d8d819ecfff0b3bf388
plan_head_after: c770b974e158de664b9c064f4be9a41a1c4bb5db

tech-stack:
  added: []
  patterns:
    - "A polymorphic-typing allowlist is tied to the observed ids in BOTH directions by a test: every id written is covered, and every prefix covers an observed id"
    - "A cache value format change is isolated by a version segment in the key prefix, set on the default configuration so every region inherits it"
    - "A real-Redis proof plants the captured old-format bytes under the old key and renames the row behind the cache, so the source of each answer is visible"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/boot4/CacheFormatIsolationIntegrationTest.java
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-09-cache.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
    - core-java/src/test/java/uk/jtoye/core/config/CacheSerializerTypeAllowlistTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/MembershipSerializerRoundTripTest.java

key-decisions:
  - "38-09: Spring Data Redis 4's builder types by its own NON_FINAL rule (javap of the 4.1.1 jar), so Long, UUID, OffsetDateTime and enums are written bare. java.lang. and java.time. carried no observed id and were dropped; an id under either is now refused"
  - "38-09: java.math. is kept. No cached DTO holds a BigDecimal today, but the plan's BigDecimal sample and a BigDecimal member both write [\"java.math.BigDecimal\", ...], and dropping it would undo the PR #726 guard against a silent permanent cache miss"
  - "38-09: no customize(...). Jackson 3 writes ISO-8601 dates natively (measured); the Jackson-2 time module and timestamp switch had no Jackson-3 counterpart to carry"
  - "38-09: the versioned key prefix is needed, not belt-and-braces. All three Boot-3.5 cache values fail on the Jackson-3 serializer (MismatchedInputException), so without it each would be a counted GET error on every request until its TTL ran out. No deploy-time flush is needed"
  - "38-09: the Jackson-2 cacheObjectMapper() is removed (its only callers were the two tests, which call jsonRedisSerializer())"

patterns-established:
  - "Bump CacheConfig.CACHE_KEY_FORMAT_VERSION in the same change as any cache value format change"

requirements-completed: []  # plan declares [BOOT4-07, BOOT4-04]; requirements.ready-ids: 0/2 ready (both shared with plans not yet summarised, e.g. 38-17 owns the BOOT4-07 runtime check)

coverage:
  - id: D1
    description: "The cache value serializer is Spring Data Redis 4's Jackson-3 GenericJacksonJsonRedisSerializer, and ProductDto, ShopDto, Membership and BigDecimal values round-trip through the production factory (OffsetDateTime compared by instant)"
    requirement: "BOOT4-07"
    verification:
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/config/CacheSerializerTypeAllowlistTest.java#theProductionSerializerIsTheJackson3GenericSerializer, productDto/shopDto/golden-sample/BigDecimal round-trips"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/security/access/MembershipSerializerRoundTripTest.java (3/3)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The allowlist is re-derived from the live Jackson-3 bytes: the exact id set is pinned, every id is covered and every prefix covers an observed id; dropping java.math. or java.util. turns the round-trips red"
    requirement: "BOOT4-07"
    verification:
      - kind: unit
        ref: "CacheSerializerTypeAllowlistTest#theTypeIdsInTheBytesAreExactlyTheReDerivedSet, #everyAllowlistPrefixCoversAnObservedTypeIdAndEveryIdIsCovered"
        status: pass
      - kind: other
        ref: "evidence/38-09-cache.txt section 6: arm M (java.math.) 2 red, arm U (java.util.) 8 red; restores sha256-verified; closing run green"
        status: pass
    human_judgment: false
  - id: D3
    description: "A type outside the allowlist (java.net.URI), a gadget base (PropertyPathFactoryBean) and the dropped Jackson-2 ids (java.lang.Long, java.time.OffsetDateTime) are refused with tools.jackson InvalidTypeIdException wrapped in SerializationException"
    requirement: "BOOT4-07"
    verification:
      - kind: unit
        ref: "CacheSerializerTypeAllowlistTest#aTypeOutsideTheAllowlistIsRefusedEvenUnderTheObjectBase, #aKnownGadgetBaseStaysRefused, #theDroppedJackson2PrefixesAreNowRefused"
        status: pass
    human_judgment: false
  - id: D4
    description: "Every region reads and writes under v4:{region}:: (a region added later inherits it) with TTLs 10/15/5 min; each Boot-3.5 cache fixture is measured unreadable by the new serializer"
    requirement: "BOOT4-07"
    verification:
      - kind: unit
        ref: "CacheSerializerTypeAllowlistTest#everyRegionReadsAndWritesUnderTheVersionedKeyPrefixAndANewRegionInheritsIt, #aJackson2EraCacheValueIsUnreadableByTheJackson3Serializer [1..3], #theCacheFixturesBelowAreExactlyTheManifestCacheFamily"
        status: pass
    human_judgment: false
  - id: D5
    description: "On a real Redis, the Boot-3.5 ShopDto bytes planted under the old key are not read: the DB name is served, the v4 key serves the read-after-write, the old entry is untouched, jtoye.cache.errors stays 0; removing the prefix turns it red"
    requirement: "BOOT4-07"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/boot4/CacheFormatIsolationIntegrationTest.java#aBoot35CacheEntryIsNeverReadAndTheBoot4EntryServesTheReadAfterWrite"
        status: pass
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java (1/1)"
        status: pass
      - kind: other
        ref: "evidence/38-09-cache.txt section 12: arm P red (jtoye.cache.errors 0 -> 1); restore sha256-verified; closing runs green"
        status: pass
    human_judgment: false
  - id: D6
    description: "CacheConfig carries no Jackson-2 databind import, no Jackson-2 serializer, no unsafe or permissive typing and no base-type matcher"
    requirement: "BOOT4-04"
    verification:
      - kind: other
        ref: "git grep over CacheConfig.java for the five forbidden tokens: rc=1 on the tree; rc=0 with 13 lines at the plan base"
        status: pass
    human_judgment: false

duration: 21min
completed: 2026-10-05
---

# Phase 38 Plan 09: Redis cache on Jackson 3, SEC-4 re-derived, versioned keys Summary

**The Redis cache serializer is now Spring Data Redis 4's Jackson-3 `GenericJacksonJsonRedisSerializer`. Its SEC-4 allowlist was re-derived from the bytes it actually writes and narrowed to `uk.jtoye.`, `java.util.` and `java.math.`. Every cache key now carries a `v4:` format version, so a Boot-3.5 entry is never read: a real-Redis test serves the database value, hits the new entry on the next call, and keeps `jtoye.cache.errors` at 0, with no deploy-time flush.**

## Performance

- **Duration:** about 21 min
- **Started:** 2026-10-05T11:23:06Z
- **Completed:** 2026-10-05T11:44:00Z
- **Tasks:** 2 of 2
- **Files:** 5 (1 main, 3 test including 1 new, 1 evidence)

## Accomplishments

- **What the new serializer writes, measured.** `javap` of the 4.1.1 jar shows that the builder's `enableDefaultTyping(ptv)` installs Spring's own `TypeResolverBuilder` with `NON_FINAL` / `PROPERTY` / `CLASS`. A probe serialized the 38-01 golden samples and recorded every id:
  - **Written:** the DTO classes and records (`@class`), `ArrayList`, `ImmutableCollections$ListN`, `LinkedHashMap`, `ImmutableCollections$Map1` / `$MapN`, and `BigDecimal`.
  - **No longer written:** `java.lang.Long`, `java.time.OffsetDateTime`, `java.util.UUID` and the enum ids.
- **The allowlist is re-derived and pinned in both directions.**
  - The prefixes are `uk.jtoye.`, `java.util.` and `java.math.`; `java.lang.` and `java.time.` are dropped.
  - One test pins the exact id set. Another fails if an id is uncovered or if a prefix has no observed id.
  - Dropping `java.math.` (arm M) or `java.util.` (arm U) turned the round-trips red.
- **SEC-4 is intact.** Validation still uses subtype matchers only. `java.net.URI`, the `PropertyPathFactoryBean` gadget and the dropped Jackson-2 wrappers are each refused with a `tools.jackson` `InvalidTypeIdException`. The `TreeMap` residual is still documented.
- **Old entries, measured:** all three Boot-3.5 cache values fail on the new serializer with `MismatchedInputException`. The prefix is therefore necessary, not belt-and-braces.
- **Versioned keys.**
  - `CACHE_KEY_FORMAT_VERSION = "v4"` is applied through `computePrefixWith` on the default configuration, so products, shops, shopMembership and any later region are keyed `v4:{region}::`.
  - The TTLs (10, 15 and 5 min) are pinned, because they bound how long an old entry survives.
- **Real Redis.** `CacheFormatIsolationIntegrationTest` plants the golden Boot-3.5 `ShopDto` bytes under the old key. The shop exists in Postgres under the same id with a different name. The test asserts:
  - the database name is served and `jtoye.cache.errors` does not move;
  - SCAN lists `v4:shops::tenant:…:getShopById:…` beside the untouched old key, and the TTL is 900 s;
  - after the row is renamed behind the cache, the next call still returns the cached name.

  Removing the prefix turns this test red: the counter goes from 0 to 1, and the application logs "Cache GET failed … Cannot deserialize value of type `java.util.UUID`".
- **Full unit suite:** 1453 tests, 0 failures, 1 skipped (38-08's 1444 plus 9 cases added here). The three other live-cache Testcontainers classes are green.

## Task Commits

1. **Task 1 (tracer): Jackson-3 cache serializer with an allowlist re-derived from its own bytes**
   - `ca4c50b8` test(38-09): RED, 5 of 11 failing on target
   - `41b2f6d3` feat(38-09): GREEN, 11/11 and 3/3
   - `ebb64790` docs(38-09): arms M and U, sha256 restores, tracer re-run
   - Tracer gate: interactive run, end-of-phase mode, automated-only verify. It was re-run green on the committed tree, then expanded to Task 2.
2. **Task 2: Versioned cache keys, proven on a real Redis**
   - `fd7368e7` test(38-09): RED. The unit prefix was `shopMembership::`; on the real Redis, `jtoye.cache.errors` went from 0 to 1.
   - `7f177388` feat(38-09): GREEN, unit 16/16 and integration 1/1 + 1/1
   - `c770b974` docs(38-09): arm P, sha256 restore, closing runs, regression checks

**Plan metadata:** the docs commit that adds this SUMMARY.

## Files Created/Modified

- `CacheConfig.java`:
  - the Jackson-3 serializer, the `tools.jackson` validator and the re-derived prefixes;
  - Javadoc rewritten from the measured ids, describing the forbidden constructs by concept, not by name;
  - `CACHE_KEY_FORMAT_VERSION` and its procedure Javadoc, and `computePrefixWith`;
  - the Jackson-2 `cacheObjectMapper()` removed.
- `CacheSerializerTypeAllowlistTest.java`: 16 cases, covering:
  - the instance check, round-trips and golden-sample round-trips, and the BigDecimal value and member;
  - the exact id set and the coverage check in both directions;
  - three refusals plus the residual;
  - the prefix and TTL check, the MANIFEST cache family, and per-fixture unreadability.
- `MembershipSerializerRoundTripTest.java`: retyped to `RedisSerializer<Object>`.
- `CacheFormatIsolationIntegrationTest.java` (new, `@Tag("testcontainers")`): the real-Redis isolation proof.
- `evidence/38-09-cache.txt`: the probe output, each RED, GREEN, arm, restore and closing run, the SCAN listing and the regression checks.

## Fail-direction record (each criterion was shown to fail)

| Criterion | Real tree | Fail direction |
|---|---|---|
| T1 AC1: no Jackson-2 databind import or serializer in CacheConfig | `git grep` rc=1 | plan base: rc=0, 8 lines |
| T1 AC2: no permissive-typing call, base-type matcher or permissive validator | rc=1 | plan base: rc=0, 5 lines |
| T1 AC3: allowlist re-derived from observed ids | exact-set and coverage tests green | RED: set held `java.lang.Long`, `java.time.OffsetDateTime` and `java.util.UUID`; arm M: 2 red; arm U: 8 red |
| T1 AC4: refusal tests pass as named testcases | self-closing testcases in the GREEN xml | `<failure>` children in the RED xml (root cause was the `com.fasterxml` exception) |
| T2 AC1: `CACHE_KEY_FORMAT_VERSION` declared and used | 5 lines; stronger form gives 1 declaration + 1 code use | rc=1 at the RED commit; under arm P the use count is 0 |
| T2 AC2: prefix-removed arm | integration 1/1 green | arm P: `jtoye.cache.errors` expected 0.0 but was 1.0; unit expected `v4:products::` |
| T2 AC3: per-fixture outcome and SCAN listing | 3/3 fixtures unreadable (recorded); SCAN shows both keys | the RED SCAN path never ran: the counter assertion failed first, because the old key was read |
| Error counter can move | 0 on GREEN | 1 on RED and under arm P |
| "Cache GET failed" log lines | none in the GREEN xml (rc=1) | 1 in the RED xml (rc=0) |
| RED classifier | RED_EVIDENCE_OK for 5 records | a passing test named as target gives INVALID_RED (twice) |

Every arm ran on a committed tree, and every restore was a `cp` from a scratch backup verified by sha256 against the HEAD blob. Each arm was followed by a closing clean run.

## Decisions Made

See `key-decisions` in the frontmatter. In brief:
- Prefixes follow the measured ids. `java.math.` is kept on the measured BigDecimal sample and member.
- No `customize(...)` is needed.
- The prefix is required, not optional.
- The Jackson-2 mapper factory is removed.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Criterion] Task 2 AC1 counts comments**
- **Found during:** Task 2 acceptance.
- **Issue:** `git grep -n 'CACHE_KEY_FORMAT_VERSION'` reaching 2 or more lines can be satisfied by Javadoc mentions alone. On this tree, 3 of the 5 lines are comments.
- **Fix:** I kept the plan's form and reported it (5 lines; 0 at the RED commit). Beside it I added a stronger form: exactly one `static final String CACHE_KEY_FORMAT_VERSION = "v4";` declaration and one `computePrefixWith(…CACHE_KEY_FORMAT_VERSION…)` code use. Arm P shows the stronger form failing (use count 0).
- **Committed in:** `7f177388` (evidence) and `c770b974`.

**2. [Rule 2 - Strengthened check] The allowlist is tied to the observed ids in both directions**
- **Issue:** The prohibition "no prefix without an observed type id" had no executable check, and a re-derivation recorded only as prose can drift.
- **Fix:** Added `theTypeIdsInTheBytesAreExactlyTheReDerivedSet` and `everyAllowlistPrefixCoversAnObservedTypeIdAndEveryIdIsCovered`, which also catches an unobserved prefix being re-added. Added `theDroppedJackson2PrefixesAreNowRefused`, which pins the narrowing.
- **Committed in:** `ca4c50b8`.

**3. [Rule 1 - Plan premise] No cached DTO carries a BigDecimal**
- **Issue:** The plan's behaviour line lists BigDecimal among the ProductDto/ShopDto/Membership members, and its arm drops "the prefix covering the Long/BigDecimal member". No such member exists: `pricePennies` and `minimumOrderPennies` are `Long`, and under Jackson 3 they are written bare.
- **Fix:** I treated the plan's BigDecimal sample as a derivation input. A test-local record `MoneyField(BigDecimal)` shows that a BigDecimal member carries the id. `java.math.` is kept on that evidence. Arm M (drop `java.math.`) is the plan's arm. I also added arm U (drop `java.util.`), so a prefix the DTO round-trips depend on was also shown to bite.
- **Committed in:** `ca4c50b8`, `41b2f6d3` and `ebb64790`.

**4. [Rule 3 - Blocking] The tests compile on both trees**
- **Issue:** Typing the test field as `GenericJacksonJsonRedisSerializer` would not compile against the interim factory, and a compile error is INVALID_RED.
- **Fix:** Both tests hold a `RedisSerializer<Object>`. The instance assertion carries the type check.
- **Committed in:** `ca4c50b8`.

**5. [Rule 3 - Blocking] The integration test's wiring and seed**
- **Issue:** The plan said "Postgres via IntegrationTestSupport". That class's `test` profile disables `CacheConfig` (`@Profile("!test")`), which would leave no cache to test.
- **Fix:** I followed the analog: `dev` profile, a Redis container, the superuser validator mocked, and `IntegrationTestSupport.registerPostgresTestProperties` for the Postgres properties. The shop is inserted by JDBC under the golden id, so the planted bytes are exactly what Boot 3.5 cached for it.
- **Follow-up:** The first RED attempt failed in the seed, because V26 dropped the default on `delivery_fee_pennies`. That was a fixture error, so it did not count as RED. The column was added and the run repeated.
- **Committed in:** `fd7368e7`.

---

**Total deviations:** 5 (2 criterion or premise corrections, 1 strengthened check, 2 blocking).
**Impact on plan:** None of them widens scope or changes production behaviour beyond the planned migration. Narrowing the allowlist is the plan's own rule ("any prefix NOT observed is dropped").

## TDD Gate Compliance

- RED came before GREEN in both tasks: `ca4c50b8` → `41b2f6d3`, and `fd7368e7` → `7f177388`. The gate greps `^test\((0*38)-(0*9)\):` and `^feat\(…\):` find 2 of each. No refactor commit was needed.
- `gsd check tdd-red-evidence` gave RED_EVIDENCE_OK (target_test_failed) for 5 records:
  - Task 1: the instance check, the exact id set, and the out-of-allowlist refusal.
  - Task 2: the unit prefix check and the real-Redis isolation test.
- Each control naming a passing test gave INVALID_RED (no_target_test_failure).

## Issues Encountered

- The machine's blind-search guard refused one compound command that counted matches in a gitignored build directory with a plain `rg -c`, so nothing in that command ran. It was re-issued with `rg -uu --count-matches`, which needed a positive control because zero matches print nothing.
- The measurement probe was a throwaway test file. It was deleted before the first commit, and `rg -uu` for its marker over `core-java/src` gives rc=1.

## Known Stubs

None. The stub scan over the four changed code files matched only `mapToDouble` (the substring "toDo").

## Threat Flags

None beyond the plan's threat model. No endpoint, auth path or schema was added.
- **T-38-23 (gadget type ids):** mitigated. The validator uses subtype matchers only, and the allowlist is narrowed and pinned in both directions. URI, the gadget and the dropped ids are refused with `tools.jackson` `InvalidTypeIdException`. No `enableUnsafeDefaultTyping` (`git grep` rc=1; positive control `enableDefaultTyping` rc=0).
- **T-38-24 (old entries after deploy):** mitigated by the `v4:` prefix. The real-Redis test serves the DB value with the counter at 0, and arm P turns it red.
- **T-38-25 (cross-tenant keys):** mitigated. The SCAN listing shows `v4:shops::tenant:{tid}:getShopById:{id}`: the prefix is prepended and the tenant segment is intact, and an exact-key assertion pins it.
- **T-38-SC:** no package was installed.

## User Setup Required

None. No compose service was started, stopped or rebuilt; only Testcontainers were used.

## Findings for later plans

- **38-17 (runtime):** on the rebuilt compose runtime, read-after-write the products, shops and shopMembership regions and confirm `jtoye.cache.errors` is 0. Keys there must read `v4:{region}::tenant:…`. Pre-deploy `{region}::…` keys may still be present until their TTL ends; that is expected, and nothing reads them.
- **38-16 / 38-18 (ADR-0006, PR body):** for the cache, the deploy needs no flush step. Old entries are unreachable by key and expire within 15 minutes. `CACHE_KEY_FORMAT_VERSION` is the standing procedure for any future cache-format change. The allowlist narrowed to `uk.jtoye.`, `java.util.` and `java.math.`.
- **38-12:** `CacheConfig` now holds no Jackson-2 import. The cache tests import only `tools.jackson`.
- **Stale doc (out of scope here):** `docs/AI_CONTEXT.md:256` describes the cache key format as `products::{tenantId}::{productId}`. That was already wrong before this plan (the real format is `{region}::tenant:{tid}:{method}:{params}`), and it now also lacks `v4:`. This fits the docs pass of 38-16.

## Next Phase Readiness

- BOOT4-07's code and Testcontainers proof are complete. Its runtime check (`jtoye.cache.errors == 0` on the rebuilt stack) belongs to 38-17.
- BOOT4-04 stays open (38-10, 38-12, 38-19).
- No blocker for 38-10.

## Self-Check: PASSED

- FOUND: CacheConfig.java, CacheSerializerTypeAllowlistTest.java, MembershipSerializerRoundTripTest.java, CacheFormatIsolationIntegrationTest.java, evidence/38-09-cache.txt (checked with `[ -f ]` before writing).
- FOUND commits: ca4c50b8, 41b2f6d3, ebb64790, fd7368e7, 7f177388, c770b974. `git rev-list --count 9ae069f8..HEAD` = 6.
- All acceptance criteria and both plan verify commands were re-run on the final tree, in both directions (table above and the evidence file).

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*

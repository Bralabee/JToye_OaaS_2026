---
phase: 38-spring-boot-4-1-migration
fixed_at: 2026-10-05T21:02:43Z
review_path: .planning/phases/38-spring-boot-4-1-migration/38-REVIEW.md
iteration: 1
findings_in_scope: 2
fixed: 2
skipped: 0
status: all_fixed
---

# Phase 38: Code Review Fix Report

**Fixed at:** 2026-10-05T21:02:43Z
**Source review:** .planning/phases/38-spring-boot-4-1-migration/38-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 2 (WR-01, WR-02; fix_scope critical_warning, so IN-01..IN-04 were not attempted)
- Fixed: 2
- Skipped: 0

**Where it ran:** every edit, commit and gate ran in the main checkout of the
`phase-37-spring-boot-4-1` worktree (`/home/sanmi/IdeaProjects/JToye_OaaS_2026-phase37`). The
orchestrator asked for that ("commit here, do not switch branches"), so no nested review-fix
worktree, temp branch or recovery sentinel was created. All counts below come from that tree's
`core-java/build-local/test-results/` XML (JDK 25, Gradle wrapper 9.7.1, Docker for Testcontainers),
so they can be reproduced from it.

## Fixed Issues

### WR-01: The versioned cache prefix splits evictions across Boot versions during a rolling deploy and a rollback

**Files modified:** `core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java`,
`core-java/src/main/java/uk/jtoye/core/config/TenantCacheEvictor.java`,
`core-java/src/test/java/uk/jtoye/core/boot4/CacheFormatIsolationIntegrationTest.java`,
`core-java/src/test/java/uk/jtoye/core/config/TenantCacheEvictorTest.java`,
`docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md`, `docs/metrics.json`, `README.md`,
`CLAUDE.md`, `AGENTS.md`
**Commit:** c327360d
**Status:** fixed: requires human verification (this is a cache-state/rollout logic fix, and the
operator half is a procedure, not code)

**Applied fix:**
- **Dual eviction (code, transitional).** `TenantCacheEvictor.evictEntity(UUID, …)` is the private
  funnel every eviction in main code goes through. It now also deletes the Boot-3.5 key. The key is
  computed by the new `CacheConfig.legacyBoot35CacheKey(cacheName, key)`, which calls Spring Data
  Redis's own `CacheKeyPrefix.simple()`. That is what Boot 3.5's `defaultCacheConfig()` used (verified
  in the bytecode of spring-data-redis 3.5.13 and on origin/main's `CacheConfig`), so it is not a
  second hand-written key format. Properties of the delete:
  - It runs only when `cache.getNativeCache()` is a `RedisCacheWriter`. A `ConcurrentMapCache` is
    skipped, and a decorator around a Redis cache is still reached.
  - It is best-effort: a failure is caught and WARN-logged with region and key, and never thrown.
  - Both javadocs mark it TRANSITIONAL, with the removal condition: the first release after the rollout
    is complete, once no 3.5 pod can exist, no rollback to 3.5 is still possible, and the 15-minute
    longest TTL has elapsed.
- **Synchronous delete (found while fixing).** The first implementation used
  `RedisCacheWriter.evict(name, key)`. The real-Redis arm then failed in 3 of 8 runs, with the legacy
  key still present after the debug log showed the delete was issued. The cause is in the 4.1.1
  bytecode: `DefaultRedisCacheWriter.evict` is fire-and-forget when `writeAsynchronously()` is true,
  which is the default. A failure in that path also never reaches the catch. The legacy delete now
  uses `evictIfPresent`, a synchronous `DEL` and what Boot 3.5's `evict` did. It was green in 8 of 8
  runs after the change, plus the closing run.
- **Other direction (operator).** A Boot-3.5 pod cannot evict `v4:` keys. ADR-0006 "Deploy notes"
  now has an "Evictions during the rolling deploy" entry with:
  - the measure above;
  - the residual, with its bound stated explicitly: a shop-grant revoke made on a 3.5 pod during the
    rollout can take up to 5 minutes to apply on Boot-4 pods, a product edit up to 10, a shop edit
    up to 15;
  - the post-rollout step: once every pod runs Boot 4, delete `v4:*` with SCAN + UNLINK, never KEYS
    or FLUSHALL (example `redis-cli --scan --pattern 'v4:*' | xargs -r -n 500 redis-cli UNLINK`).
- **Rollback.** "Rollback notes" was rewritten:
  - The 3.5 pods' un-prefixed entries are no longer left stale by Boot-4 writes.
  - `v4:` entries outlive a rollback, and a re-roll-forward within the TTL can read stale ones. The
    same `v4:*` step closes that.
  - Keep the dual eviction while a rollback to 3.5 is possible.
- **Text corrected.** The `CACHE_KEY_FORMAT_VERSION` javadoc ("No flush at deploy" became "No flush
  for FORMAT reasons", plus a paragraph on why evictions do not cross generations), ADR §D-01's "nothing
  needs flushing or draining", and ADR "No Redis flush" now say "for format" and point to the new
  entry.

**Verification (both directions recorded):**
- `TenantCacheEvictorTest` (unit, 11 tests, 3 new):
  - both keys are deleted on a real `RedisCache` over a mocked writer (v4 via `evict`, legacy via
    `evictIfPresent`, `verifyNoMoreInteractions`);
  - a failing legacy delete (`RedisConnectionFailureException`) does not propagate, and the v4
    eviction stands;
  - a `ConcurrentMapCache` evicts normally, and an entry under the legacy-form string is untouched.
- `CacheFormatIsolationIntegrationTest` (real Redis 7 + Postgres 15, Testcontainers): the new arm
  `anEvictionOnABoot4PodAlsoRemovesTheBoot35EntryForThatTenantOnly` plants another tenant's legacy
  and v4 entries for the same shop id. It populates this tenant's v4 entry through the real
  `@Cacheable` path and evicts through the `TenantCacheEvictor` bean. It then asserts:
  - the legacy key is gone immediately (no wait);
  - the v4 key is gone after a bounded 5-second poll, because the primary `evict` is asynchronous
    under Spring Data Redis 4 (see the note below);
  - exactly the other tenant's two entries remain, and its legacy entry is byte-identical (SHA-256).
- **Clean run:** unit `tests="11" failures="0"`. Integration `tests="2" failures="0"`, green 8 of 8
  times in a row and again after the break arms. Real SCAN output:
  - immediately after the eviction:
    `[shops::tenant:0ddba11d-…038:getShopById:…a002, v4:shops::tenant:0ddba11d-…038:getShopById:…a002]`
  - before the eviction, the same two entries plus
    `shops::tenant:38010000-…a001:getShopById:…a002` and `v4:shops::tenant:38010000-…a001:…`.
- **Break arm A** (the `evictLegacyBoot35Key(cache, key);` call commented out, after the commit):
  - Integration: `rc=1 tests="2" failures="1"`. The failure was "the Boot-3.5 entry for this tenant's
    shop is gone as soon as evictEntity returns". The immediate SCAN still held
    `shops::tenant:38010000-…a001:getShopById:…a002`.
  - Unit: `rc=1 tests="11" failures="2"` (the Redis dual-delete case and the failure-isolation case).
- **Break arm B** (the catch rethrows): unit `rc=1 failures="1"` on "the legacy delete is best-effort:
  it must never fail the caller's write".
- **Restores:** after each arm, `git checkout --` brought the file back. That was confirmed by
  content: `git hash-object` gave `35846a2e…`, equal to `HEAD:TenantCacheEvictor.java`. The closing
  clean run was green, as recorded above.
- **Gates:**
  - `scripts/docs-freshness.sh`: rc=1 before reconciliation (+4 `@Test`), rc=0 after `--write`.
  - `scripts/check-doc-metrics.sh`: rc=1 with 7 FAIL lines on README/CLAUDE/AGENTS before the prose
    update, rc=0 (37/37) after.
  - `check-doc-citations`, `check-doc-versions`, `check-claims`, `check-no-measured-placeholders` and
    `check-no-object-store-residue` were all rc=0 (pass direction only; these were run as a
    regression check, not as evidence for the fix).

### WR-02: `IdempotencyJson` does not reproduce Boot 3.5's parameter-name detection

**Files modified:** `core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyJson.java`,
`core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java`,
`docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`
**Commit:** 01a67384
**Status:** fixed

**Applied fix:**
- The mapper now has `.enable(MapperFeature.DETECT_PARAMETER_NAMES)`. The constant was verified to
  exist in `tools.jackson.core:jackson-databind:3.1.7`, the version that resolves on
  `runtimeClasspath`.
- The premise was verified too:
  - Boot 3.5.16's `spring-boot-starter-json` POM depends on `jackson-module-parameter-names` (compile
    scope).
  - spring-web 6.2.19's `Jackson2ObjectMapperBuilder` registers
    `com.fasterxml.jackson.module.paramnames.ParameterNamesModule` by name.
- The javadoc's "What it reproduces" list now names the setting. A new paragraph says why this one
  in-place edit was allowed: the mapper has not shipped, no Boot-4 pod has stored a hash or body with
  it, and the golden test proves no stored byte moved. It also says the never-edit rule applies from
  the first Boot-4 release onward.

**Verification (both directions recorded):**
- The 2 new golden-test cases use a constructor-only `ConstructorOnlyDto`:
  - no setters, no default constructor, no `@JsonCreator` or `@JsonProperty`;
  - constructor order `(alpha, zeta)`, field order `(zeta, alpha)`;
  - test classes are compiled with `-parameters` (`MethodParameters` is present in the compiled test
    classes).
- **Unfixed mapper** (test written first): `rc=1 tests="15" failures="2"`.
  - The read failed with `InvalidDefinitionException: Cannot construct instance of
    …ConstructorOnlyDto (no Creators, like default constructor…)`.
  - The write produced `{"zeta":"z","alpha":1}` where `{"alpha":1,"zeta":"z"}` was expected.
- **Fixed mapper:** `rc=0 tests="15" failures="0"`. All 7 `request hash` rows and all 4
  `stored response` rows are green. `git diff --quiet HEAD -- jackson2-golden/idempotency
  jackson2-golden/responses` returned rc=0, so no fixture was touched.
- **What "same bytes as Boot 3.5" is anchored to.** No Boot-3.5 fixture exists for a constructor-only
  type: the 38-01 capture has none. None was invented. The expected string is anchored to:
  - Boot 3.5.16 shipping and registering `ParameterNamesModule` (above);
  - an out-of-tree probe: a Jackson 2.21.7 `JsonMapper` with Boot 3.5's other settings, compiled with
    `-parameters` against the Gradle-cache jars, on the same DTO shape. It printed
    `ParameterNamesModule=true write={"alpha":1,"zeta":"z"} read=alpha=1 zeta=z` and
    `ParameterNamesModule=false write={"zeta":"z","alpha":1} read=InvalidDefinitionException`.

  That is a reconstruction, not a capture from a running Boot-3.5 pod. The test's javadoc says so.
  Jackson 2.21.7 is the version origin/main's build floors `jackson-bom.version` to (main's own
  comment says it moved to 2.21.7 on 2026-10-04).
- **Integration** (real Postgres, Testcontainers):
  - `IdempotencyLegacyHashReplayIntegrationTest`: `tests="2" failures="0"`
  - `OrderIdempotencyIntegrationTest`: `tests="4" failures="0"`
  - `CustomerIdempotencyIntegrationTest`: `tests="1" failures="0"`
- **Gates:** `docs-freshness.sh` was rc=1 before `--write` (+2 `@Test`) and rc=0 after
  (total 4292). `check-doc-metrics.sh` was rc=0 (37/37).

## Full unit suite (after both commits)

`./gradlew :core-java:test --rerun` returned rc=0. Tallied from the 181 result XMLs:
`tests=1495 failures=0 errors=0 skipped=1`. (That is the JUnit invocation count of the unit task,
with Testcontainers classes excluded. It is not the `@Test`-method count in `docs/metrics.json`,
which is 2114 after these fixes.) The full integration suite was not run, only the 5 classes named
above.

## Observation outside this review's findings (not fixed, needs a decision)

**Spring Data Redis 4 makes `Cache.evict` (and cache puts) asynchronous by default. Boot 3.5's were
synchronous.**
- **Bytecode evidence.**
  - spring-data-redis 3.5.13: `RedisCache.evict` called `RedisCacheWriter.remove`, which ran `DEL`
    inline.
  - spring-data-redis 4.1.1: `DefaultRedisCacheWriter.evict` hands the `DEL` to an async writer
    whenever `writeAsynchronously()` is true. That is the default. `immediateWrites()` is the opt-out
    on `RedisCacheWriter.create(…)`.
  - Observed directly: in 3 of 8 runs the legacy key was still present right after an `evict` that
    had already logged success.
- **Why it matters.** It affects the PRIMARY eviction in `TenantCacheEvictor` as well, which this fix
  did not change (out of scope). Javadoc such as `ShopAccessService.evictMembership`'s "the next
  request re-resolves from shop_staff with no stale-allow window" assumes a synchronous delete. Under
  Boot 4 there is a short window after commit in which the `DEL` may not have landed yet.
- **Options**, if the owner rules it admissible:
  - use `cache.evictIfPresent(key)` in `TenantCacheEvictor`, which Spring's contract defines as
    immediate;
  - or build the `RedisCacheManager` from `RedisCacheWriter.create(cf, c -> c.immediateWrites())`.

  Either needs its own test.

---

_Fixed: 2026-10-05T21:02:43Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_

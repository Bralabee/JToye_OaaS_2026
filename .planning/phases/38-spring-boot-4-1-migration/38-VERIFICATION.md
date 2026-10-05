---
phase: 38-spring-boot-4-1-migration
verified: 2026-10-05T22:52:00Z
status: passed
score: 14/14 must-haves verified
covered_files:
  - .github/workflows/ci-cd.yaml
  - .gitleaks.toml
  - .gitleaksignore
  - AGENTS.md
  - CLAUDE.md
  - HANDOFF.md
  - README.md
  - build.gradle.kts
  - core-java/Dockerfile
  - core-java/build.gradle.kts
  - core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java
  - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
  - core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyJson.java
  - core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyService.java
  - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
  - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  - core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java
  - core-java/src/main/java/uk/jtoye/core/config/TenantCacheEvictor.java
  - core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeService.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxFlusher.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/security/CustomerJwtVerifier.java
  - core-java/src/main/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPoint.java
  - core-java/src/main/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilter.java
  - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
  - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java
  - core-java/src/main/resources/application-prod.yml
  - core-java/src/main/resources/application-staging.yml
  - core-java/src/main/resources/application-test.yml
  - core-java/src/main/resources/application.yml
  - core-java/src/test/java/uk/jtoye/core/boot4/CacheFormatIsolationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java
  - core-java/src/test/java/uk/jtoye/core/config/TenantCacheEvictorTest.java
  - docs/AI_CONTEXT.md
  - docs/api/openapi-snapshot.json
  - docs/architecture/ESSENTIAL_ARCHITECTURE.md
  - docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md
  - docs/guides/DEPLOYMENT_GUIDE.md
  - docs/guides/USER_GUIDE.md
  - docs/metrics.json
  - infra/dependency-horizons.yaml
  - k8s/LOCAL.md
  - mcp-server/src/tools/create-customer.ts
  - mcp-server/src/tools/create-order.ts
  - scripts/check-boot-config-keys.sh
  - scripts/check-doc-versions.sh
covered_digest: "v2:sha256:b88aa7a6d5d3e73209353d1017bd851de1bb553878310220f5c717e5257a22ba"
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: passed
  previous_score: 14/14
  gaps_closed: []
  gaps_remaining: []
  regressions: []
---

# Phase 38: Spring Boot 4.1 Migration Verification Report

**Phase Goal:** Move core-java from Spring Boot 3.5.16 to 4.1.x and prove the result green by test, gate and live runtime; close three defects today's tests do not catch (KeycloakAdminClient garbage body under Jackson 3; 18 silently ignored config keys needing an unknown-key gate; netty/Tomcat CVE pins moved to their Boot-4 lines); honour D-01..D-05.
**Verified:** 2026-10-05T22:52:00Z
**Status:** passed
**Re-verification:** Yes. The previous report (2026-10-05T19:48Z, tree `acee00c5`) went stale when the code-review fixes `c327360d` (WR-01) and `01a67384` (WR-02) changed covered source. This pass re-checked every must-have against HEAD `b6e87153` and gave fresh scrutiny to what those two commits touched.

Method: SUMMARY and REVIEW-FIX claims were not used as evidence. Code was read at HEAD, the two fix diffs were read line by line, the Spring Data Redis 4.1.1 bytecode was disassembled, a Jackson 3.1.7 probe was compiled and run, the non-Gradle gates were re-run, and test-result XML was read back. No Gradle was run, because the orchestrator's `test integrationTest --rerun-tasks` was in progress in this worktree; the unit XMLs it has already rewritten (22:46Z) are used below.

## What changed since the last report

`git diff acee00c5..HEAD -- core-java/src/main` touches exactly three files, all inside the two fix commits:

| File | Change | Truths it can touch |
| ---- | ------ | ------------------- |
| `config/CacheConfig.java` | adds `import CacheKeyPrefix`, a javadoc block, and `static String legacyBoot35CacheKey(cacheName, key)` = `CacheKeyPrefix.simple().compute(cacheName) + key`. `jsonRedisSerializer()`, the validator, `CACHE_KEY_FORMAT_VERSION = "v4"` and `computePrefixWith` are byte-unchanged (read in the diff and at `:133-142`). | 7 |
| `config/TenantCacheEvictor.java` | `evictEntity(UUID,...)` now also calls `evictLegacyBoot35Key(cache, key)` after the unchanged `cache.evict(key)` | 7 |
| `common/idempotency/IdempotencyJson.java` | one added builder line `.enable(MapperFeature.DETECT_PARAMETER_NAMES)` (import present, `:5`) plus javadoc | 8 |

Everything else changed since `acee00c5` is under `.planning/` or docs (ADR-0006, README/CLAUDE/AGENTS metric counts, `docs/metrics.json`). No yml, build file, fixture, snapshot or workflow moved.

## Goal Achievement

### Observable Truths

ROADMAP.md carries no separate success-criteria list for Phase 38, so the contract is the goal text plus BOOT4-01..14 (REQUIREMENTS.md), restated by the plans.

| #  | Truth (requirement) | Status | Evidence |
| -- | ------------------- | ------ | -------- |
| 1  | BOOT4-01: Boot 4.1.1; horizons gate; CI on Temurin 25 | VERIFIED | `build.gradle.kts:2` and `core-java/build.gradle.kts:2` both `"4.1.1"` (re-read at HEAD). No build file changed since `acee00c5`. CI run 37348823924 on `acee00c5` was success; see item O-2 for the CI run the final head still needs. |
| 2  | BOOT4-02: explicit per-module starters; liveness and census tests | VERIFIED (carried) | `core-java/build.gradle.kts` unchanged since the last pass, which read the starter block and the 239-lib jar listing (no classic, no jackson2, no grpc). Fix commits touch no dependency line. |
| 3  | BOOT4-03: Flyway liveness | VERIFIED (carried) | Unchanged inputs; permanent liveness test and `RlsContractTest` re-run green in the last pass. |
| 4  | BOOT4-04: Jackson 3 only in main; wire contract | VERIFIED | `git grep` for `com.fasterxml.jackson.(databind\|core\|datatype\|dataformat\|module)` in `core-java/src/main` returned rc=1 (no match) at HEAD, so the WR-02 edit introduced no Jackson-2 import (it uses `tools.jackson.databind.MapperFeature`). `IdempotencyJson` still builds on `builderWithJackson2Defaults()` deliberately. |
| 5  | BOOT4-05: Keycloak disable body by content; live offboard | VERIFIED (carried) | `KeycloakAdminClient` is not in the fix diff. The last pass's independent fail arm and the live offboard evidence (`evidence/38-17-runtime.txt` 6.6-6.10) stand. |
| 6  | BOOT4-06: AMQP converter, in-flight state both directions | VERIFIED (carried) | `RabbitMQConfig.java:433` `new JacksonJsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES)` re-read at HEAD; file not in the fix diff. |
| 7  | BOOT4-07: Redis serializer allowlist, `v4:` prefix, real-Redis proof, `jtoye.cache.errors` 0 | VERIFIED | The serializer, `BasicPolymorphicTypeValidator` allowlist (`CacheConfig.java:226,250-251`) and prefix (`:134`) are unchanged by the fix. The new dual eviction deletes the Boot-3.5 key and never reads it, so the read path and the error counter are untouched. See "Fix-commit scrutiny, WR-01" below: the new behaviour is behaviorally tested, with a can-fail instrument. |
| 8  | BOOT4-08: idempotency key reserved under Jackson 2 replays after deploy (no 422), NOSUPERUSER | VERIFIED | `IdempotencyJson` stays the sole reader/writer (`IdempotencyService:270,274`, the only two `IdempotencyJson.` call sites in main). Fresh XML written by the orchestrator's run at 22:46:34Z: `IdempotencyFingerprintGoldenTest` tests=15 failures=0 errors=0, including all 7 `request hash` rows and all 4 `stored response` rows against the Boot-3.5-written fixtures. See "Fix-commit scrutiny, WR-02" below. |
| 9  | BOOT4-09 (+D-04, D-05): plain-Bearer 401; `/.well-known/oauth-protected-resource` answered by the suppression filter | VERIFIED (carried) | `SecurityConfig.java:283` still wires `ProtectedResourceMetadataSuppressionFilter` via `addFilterAfter`; no security file is in the fix diff. Live evidence in `38-17` section 5 stands. |
| 10 | BOOT4-10: spring-statemachine 4.0.2 kept | VERIFIED (carried) | No dependency or statemachine file in the fix diff. |
| 11 | BOOT4-11: 18 keys renamed; CI unknown-key gate | VERIFIED (carried) | No yml and no `scripts/check-boot-config-keys.sh` change since `acee00c5` (`git diff --stat`). The gate itself runs a Gradle test, so it was not re-run here; `check-gate-enforcement.sh` rc=0 (46 gates) re-run. |
| 12 | BOOT4-12: CVE floors on Boot-4 lines | VERIFIED | `core-java/build.gradle.kts:71` `tomcat.version` 11.0.26, `:131` `rabbit-amqp-client.version` 5.34.0, `:222` `jackson-2-bom.version` 2.22.3, `:231` `jackson-bom.version` 3.1.7; no non-comment netty line. File unchanged since the last pass. |
| 13 | BOOT4-13: OpenAPI snapshot | VERIFIED (carried) | No snapshot, controller or DTO change in the fix diff (`ErasureResponse`-style zero-diff holds: only the 3 main files above moved). |
| 14 | BOOT4-14: docs, gates, runtime parity, nightly | VERIFIED, with open closing items O-1..O-3 | Gates re-run at HEAD this session: `docs-freshness` OK (4292), `check-doc-metrics` PASS 37/37, `check-doc-versions` PASS 153 claims, `check-doc-citations` PASS 29 verified / 0 violations (caveat below), `check-gate-enforcement` PASS 46 gates, `check-branch-behind-base` PASS (0 behind `origin/main` 03022f21, fetched fresh). Runtime parity for the current HEAD is item O-1. |

**Score:** 14/14 truths verified (0 present, behavior-unverified; 0 overrides). "Carried" means the inputs for that truth are provably unchanged since the last pass (diff-checked above) and the last pass's observation stands; every truth the fix commits could reach (7, 8, 14) was re-observed.

Decisions honoured: D-01 (Jackson 3, truth 4), D-02 (truth 2), D-03 (truth 10), D-04 and D-05 including the owner's 2026-10-05 anon-401-parity refinement (truth 9).

### Fix-commit scrutiny

**WR-01, `c327360d` (dual eviction in `TenantCacheEvictor`).** Checked against the code, not the report.

- *Single funnel.* `git grep` for `@CacheEvict|.evict(|getCache(` in main finds exactly one active `cache.evict` (`TenantCacheEvictor.java:153`) and one `getCache` (`:147`). The `@CacheEvict(allEntries=...)` hits are comments. So every eviction in main (`ShopAccessService:539` for `shopMembership`, `ProductService`, `ShopService`, `SyncService`) goes through the method that now also deletes the legacy key. The claim "every eviction in main code goes through evictEntity" holds.
- *Key shape.* Boot 3.5 key = `CacheKeyPrefix.simple()` (`{region}::`) + the `tenant:{tenantId}:{method}:{id}` suffix; the v4 key is `v4:{region}::` + the same suffix (`CacheConfig.java:134`, `TenantCacheEvictor.java:152`). The legacy key keeps the tenant segment, so the delete cannot widen tenant scope. The integration test anchors this to a literal `BOOT35_KEY` independent of `legacyBoot35CacheKey`, and asserts the other tenant's legacy entry survives byte-identical (SHA-256).
- *Can fail.* The integration arm first asserts all four keys exist before the eviction (`containsExactlyInAnyOrder(BOOT4_KEY, BOOT35_KEY, otherBoot4Key, otherBoot35Key)`), then asserts the legacy key is absent immediately and the other tenant's two remain. The unit test verifies `writer.evictIfPresent(eq("shops"), aryEq("shops::"+suffix))` and `verifyNoMoreInteractions`, so removing the call, changing the key or widening the scope turns it red. The break arms were recorded by the fixer (REVIEW-FIX: arm A `rc=1 failures="1"` integration, `failures="2"` unit; arm B rethrow `failures="1"`); I did not re-run them (no Gradle).
- *Best-effort.* The try/catch is on `RuntimeException`, WARN-logs region and key, and cannot fail the caller's write; the unit test `A failing legacy delete is logged, not propagated` passed (WARN line visible in the fresh XML `system-out`).
- *Fresh results.* `TenantCacheEvictorTest` tests=11 failures=0 (orchestrator run, 22:46:37Z). `CacheFormatIsolationIntegrationTest` tests=2 failures=0 (fixer's real-Redis run, 21:04:40Z, the XML currently on disk; the orchestrator's integration run will rewrite it).
- *Synchronous legacy delete.* Verified in 4.1.1 bytecode: `DefaultRedisCacheWriter.evict(String,byte[])` branches on `writeAsynchronously()` and, when true, hands the DEL to the async writer and returns; `writeAsynchronously()` is `supportsAsyncRetrieve() && asynchronousWrites`, and the 3-arg constructor passes `asynchronousWrites = true` (`iconst_1`). The fixer's choice of `evictIfPresent` for the legacy key is therefore correct, and its explanation is accurate.
- *Residual (other direction).* A write on a Boot-3.5 pod still cannot reach `v4:` keys. This is now recorded with bounds (shop-grant revoke 5 min, product 10, shop 15) and the SCAN+UNLINK post-rollout step in ADR-0006 "Deploy notes" and "Rollback notes" (read in the diff). That discharges review fix (b) from the previous W-1 and the code half is fix (a); the owner's original request for a ruling is met by having both.

**WR-02, `01a67384` (`DETECT_PARAMETER_NAMES`).**

- *The premise is real and I reproduced both directions.* A throwaway probe (scratchpad, not in the repo) compiled with `-parameters` against `tools.jackson` 3.1.7 and the same `builderWithJackson2Defaults()`: without the feature, `write={"zeta":"z","alpha":1}` and read throws `InvalidDefinitionException`; with `.enable(MapperFeature.DETECT_PARAMETER_NAMES)`, `write={"alpha":1,"zeta":"z"}` and the read round-trips. So the one-line edit is the thing that changes behaviour, and the two new golden cases can go red.
- *No current adopter moves.* The only stored response types are `CustomerDto`, `MediaAcceptDto` (two controllers), `OrderDto`, `WebhookDeliveryView` (from the four `IdempotencyOutcome<...>` call sites; the storefront call uses `executeWithoutStoringResponse`), and the 7 request fingerprints cover those call sites. All 7 hashes and 4 bodies are green against the unchanged Boot-3.5 fixtures (fresh XML 15/0), and the fixer's `git diff --quiet` shows no fixture was touched.
- *Honesty of the anchor.* The expected bytes for the new constructor-only DTO are a reconstruction (Jackson 2.21.7 plus `ParameterNamesModule`, out of tree), not a Boot-3.5 pod capture. The test javadoc says so. I did not re-measure Jackson 2 here; the Jackson-3 side is reproduced above. This is acceptable because no current adopter has such a type; it matters only to a future adopter.
- *The "frozen" rule.* The javadoc records that this one in-place edit was made before the mapper shipped, and that the never-edit rule applies from the first Boot-4 release.

### New observation (not a must-have; owner decision, not a gap)

**W-3. Spring Data Redis 4 makes the PRIMARY `cache.evict` fire-and-forget.** Confirmed in the 4.1.1 bytecode above: `TenantCacheEvictor.java:153` `cache.evict(key)` on a `RedisCache` built from `RedisCacheManager.builder(connectionFactory)` (`CacheConfig.java:162`, no `immediateWrites()`) issues the DEL asynchronously, and an async failure never reaches `RedisCacheErrorHandler`. Boot 3.5's evict was synchronous. Consequence: after a `shopMembership` revoke commits there is a short window (typically sub-millisecond to a few ms) before the DEL lands, whereas `ShopAccessService.evictMembership`'s javadoc assumes none. The integration test itself polls up to 5 s for the v4 key to disappear (`CacheFormatIsolationIntegrationTest` eviction arm), which encodes the asynchrony rather than closing it.

Why it is not a gap: BOOT4-07 asks for the serializer, prefix, isolation and a zero error counter, all proven; no must-have or requirement asserts synchronous eviction, and the window is small and TTL-backstopped. It is a real behaviour change from Boot 3.5 on an authorization cache, so it should not be left unruled. Both the review fixer and the security audit (`38-SECURITY.md` item 1) record it with the same two options: `cache.evictIfPresent(key)` in `TenantCacheEvictor` (the legacy path already does this) or `RedisCacheWriter.create(cf, c -> c.immediateWrites())`, each needing its own test. **Owner decision requested before the first production rollout.**

### Items the orchestrator closes (not gaps)

- **O-1. Runtime parity for HEAD.** The running container `jtoye_oaas_2026-core-java-1` (up 6 h, healthy) is built from an image tagged 2026-10-05 17:03:46 UTC. Commit `01a67384` is 21:00:56 UTC and `c327360d` 20:54:23 UTC, so the local compose runtime predates WR-01 and WR-02 and `check-runtime-freshness.sh` reports DRIFT for core-java. The orchestrator is rebuilding after the regression suite. I checked whether the live-runtime must-haves depend on the fixed paths: they do not. The live evidence (truths 5, 7, 9, 14: Keycloak offboard, `v4:` keys with `jtoye_cache_errors_total` 0 and the corrupted-entry arm, 401/404, tracing, Prometheus, nightly) exercises the serializer, prefix, Keycloak client and security chain, none of which changed; the dual eviction cannot be exercised on a compose stack with no Boot-3.5 pod; and the WR-02 mapper change moves no byte for any current adopter. So the pre-fix runtime is valid evidence for those truths. After the rebuild, re-run `scripts/check-runtime-freshness.sh` (needs the compose env file this shell lacks) and read `application.yml` and the `IdempotencyJson` class out of the running jar, to close the parity half of BOOT4-14 for the final head. A cheap live spot-check that adds value once rebuilt: a shop edit still serves fresh data and `jtoye_cache_errors_total` stays 0.
- **O-2. CI on the final head.** `origin/phase-37-spring-boot-4-1..HEAD` is 13 commits, including both code fixes; CI run 37348823924 and nightly 37348829067 are on `acee00c5`, which does not contain them. Push and let the required jobs run green on the final head. The nightly need not be re-run for these two changes (they touch cache eviction and one mapper setting), but the standard CI jobs should.
- **O-3. Full regression result.** The orchestrator's `test integrationTest --rerun-tasks` result is reported separately. Until it lands, the evidence for the two fix areas is: unit tests fresh at 22:46Z (golden 15/0, evictor 11/0), and the real-Redis integration at 21:04Z from the fixer's run.

## Required Artifacts

| Artifact | Expected | Status | Details |
| -------- | -------- | ------ | ------- |
| `core-java/.../TenantCacheEvictor.java` | single eviction funnel + transitional legacy delete | VERIFIED | read in full at HEAD; one caller path for every eviction |
| `core-java/.../CacheConfig.java` | `v4` prefix, allowlist, `legacyBoot35CacheKey` | VERIFIED | helper has exactly one caller (`TenantCacheEvictor:173`) |
| `core-java/.../IdempotencyJson.java` | frozen fingerprint mapper incl. parameter names | VERIFIED | wired at `IdempotencyService:270,274`; golden-tested |
| `core-java/.../RabbitMQConfig.java` | `JacksonJsonMessageConverter` | VERIFIED | `:433` |
| `core-java/.../ProtectedResourceMetadataSuppressionFilter.java` | D-05 filter | VERIFIED | `SecurityConfig:283` |
| `core-java/.../KeycloakAdminClient.java` | Jackson-3 body | VERIFIED | not in fix diff |
| `scripts/check-boot-config-keys.sh` | unknown-key gate | VERIFIED | unchanged; wired in CI (`check-gate-enforcement` PASS) |
| `docs/api/openapi-snapshot.json` | regenerated snapshot | VERIFIED | unchanged |
| `ADR-0006-spring-boot-4-migration.md` | migration record + deploy/rollback notes | VERIFIED | WR-01 deploy and rollback entries present and consistent with the code (the ADR says the legacy delete is synchronous, which matches `evictIfPresent`) |

## Key Link Verification

| From | To | Via | Status | Details |
| ---- | -- | --- | ------ | ------- |
| `ShopAccessService.evictMembership` | `TenantCacheEvictor.evictEntity` | `cacheEvictor.evictEntity("shopMembership","resolveMembership",userId)` `:539` | WIRED | reaches both key generations |
| `TenantCacheEvictor.evictEntity` | `CacheConfig.legacyBoot35CacheKey` | `evictLegacyBoot35Key` `:155,173` | WIRED | real-Redis test proves the key is removed |
| `CacheConfig.cacheManager` | `v4:` prefix + `jsonRedisSerializer()` | `computePrefixWith` `:134`, `serializeValuesWith` `:139` | WIRED | unchanged |
| `IdempotencyService` | `IdempotencyJson` | `:270,274` | WIRED | no injected mapper remains |
| `SecurityConfig` | suppression filter | `addFilterAfter` `:283` | WIRED | unchanged |
| `ci-cd.yaml` | `check-boot-config-keys.sh` | JDK-job step | WIRED | `check-gate-enforcement` PASS |

## Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
| -------- | ------------- | ------ | ------------------ | ------ |
| Legacy cache delete | legacy key bytes | `CacheKeyPrefix.simple()` + `tenant:..` suffix, UTF-8 | Yes: SCAN output in the integration test shows the key removed and the other tenant's survives | FLOWING |
| Idempotency fingerprint | request hash | `IdempotencyJson.write` of the real adopter request records | Yes: 7 hashes equal the Boot-3.5 fixtures | FLOWING |
| Cache regions | cached DTOs | Redis via the unchanged serializer | Yes (last pass, live `v4:` keys) | FLOWING |

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| Idempotency golden incl. constructor-only DTO | read `TEST-...IdempotencyFingerprintGoldenTest.xml` (orchestrator run 22:46:34Z) | tests=15 failures=0 errors=0 | PASS |
| Evictor unit incl. dual delete, failure isolation, non-Redis | read `TEST-...TenantCacheEvictorTest.xml` (22:46:37Z) | tests=11 failures=0 errors=0 | PASS |
| Real-Redis dual eviction + cache isolation | read `TEST-...CacheFormatIsolationIntegrationTest.xml` (fixer run 21:04:40Z) | tests=2 failures=0 errors=0 | PASS (to be re-confirmed by the orchestrator's integration run) |
| `DETECT_PARAMETER_NAMES` is load-bearing on Jackson 3.1.7 | scratchpad probe, same builder, with and without the feature | without: wrong order + `InvalidDefinitionException`; with: creator order + round-trip | PASS (can fail) |
| Primary evict is async in 4.1.1 | `javap -c` of `DefaultRedisCacheWriter.evict` | `writeAsynchronously()` branch to `AsyncCacheWriter.remove`; default `asynchronousWrites=true` | CONFIRMED (W-3) |
| docs / metrics / versions / citations / gate-enforcement / branch-behind-base | `scripts/*.sh` | docs-freshness OK (4292); metrics 37/37; versions 153 drift=0; citations 29 verified 0 violations; gates 46 PASS; 0 behind origin/main | PASS |
| No Jackson-2 imports in main | `git grep` for `com.fasterxml.jackson.(databind\|core\|...)` in `core-java/src/main` | rc=1, no match | PASS |
| No debt markers in the three fix files | `git grep -E "TBD\|FIXME\|XXX"` on them | none | PASS |
| `check-boot-config-keys.sh`, Gradle suites, Trivy, Playwright | not run | n/a | SKIPPED (Gradle collision; inputs unchanged; covered by CI on `acee00c5` and O-2/O-3) |

Caveat on the citations gate: it printed `PASS` but also a `VOID` for `docs/ops/terminal-states.yaml` because the machine python shim blocked a base-env `python3` call (an environment fact, not a repo defect; that doc carries 0 citations). The other eight docs verified.

## Probe Execution

Step 7c: SKIPPED. No `scripts/*/tests/probe-*.sh` is declared by the phase plans.

## Requirements Coverage

| Requirement | Source Plans | Status | Evidence |
| ----------- | ------------ | ------ | -------- |
| BOOT4-01 | 38-03, 38-16, 38-18 | SATISFIED | Truth 1 |
| BOOT4-02 | 38-03, 38-04, 38-12, 38-13, 38-19 | SATISFIED | Truth 2 |
| BOOT4-03 | 38-03, 38-04 | SATISFIED | Truth 3 |
| BOOT4-04 | 38-05..10, 38-12, 38-19 | SATISFIED | Truth 4 |
| BOOT4-05 | 38-07, 38-17 | SATISFIED | Truth 5 |
| BOOT4-06 | 38-01, 38-08 | SATISFIED | Truth 6 |
| BOOT4-07 | 38-01, 38-09, 38-17 | SATISFIED | Truth 7 (plus WR-01 dual eviction) |
| BOOT4-08 | 38-01, 38-02, 38-10 | SATISFIED | Truth 8 (plus WR-02) |
| BOOT4-09 | 38-02, 38-06, 38-17 | SATISFIED | Truth 9 |
| BOOT4-10 | 38-03, 38-04, 38-16 | SATISFIED | Truth 10 |
| BOOT4-11 | 38-11 | SATISFIED | Truth 11 |
| BOOT4-12 | 38-03, 38-15, 38-18 | SATISFIED | Truth 12 |
| BOOT4-13 | 38-02, 38-14 | SATISFIED | Truth 13 |
| BOOT4-14 | 38-16, 38-17, 38-18 | SATISFIED (closing items O-1..O-3) | Truth 14 |

Every ID BOOT4-01..14 appears in at least one PLAN's `requirements:` frontmatter and in REQUIREMENTS.md; no other ID maps to Phase 38. **Orphaned requirements: none.**

## Anti-Patterns Found

No `TBD`, `FIXME` or `XXX` in the three modified main files (grep above). The previous pass's scan of the 45 phase production files stands for everything the fix commits did not touch.

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| `core-java/.../TenantCacheEvictor.java` | 153 | `cache.evict` is fire-and-forget under Spring Data Redis 4 (W-3) | Warning | short post-commit stale window on an auth cache; owner decision requested |
| `.planning/REQUIREMENTS.md` | 321-334 | Traceability Status column still reads "Planned" for the 14 BOOT4 rows | Info | bookkeeping |
| `.planning/ROADMAP.md` | 58 | Phase 38 index line still `[ ]` | Info | flips at phase completion |
| `HANDOFF.md` | live block | still says Phase 38 is "planned, ready to execute" (review IN-04) | Info | stale handoff text |

Review dispositions: WR-01 and WR-02 `fixed` (`38-REVIEW-DISPOSITION.md`); IN-01..IN-04 `open`, informational, no must-have affected.

## Human Verification Required

None for the must-haves. Two owner items, neither a verification of a truth: the W-3 ruling above, and the operator post-rollout `v4:*` deletion, which is a deploy procedure recorded in ADR-0006 and cannot be exercised before a real rolling deploy.

## Gaps Summary

No gaps. The two fix commits did what the review asked: WR-01 closes the Boot-4-write-reaches-Boot-3.5-pods direction in code, tested on a real Redis with a tenant-scope control, and records the other direction's TTL bound and cleanup step; WR-02 makes `IdempotencyJson` reproduce Boot 3.5's parameter-name detection, with a fail direction reproduced independently on Jackson 3.1.7 and every stored fingerprint byte-identical. Nothing the fix commits changed alters a Phase 38 must-have. The phase goal stands achieved: core-java builds, tests and runs on Spring Boot 4.1.1; the Keycloak body, the 18 keys and the CVE floors are closed; D-01..D-05 are honoured. The orchestrator should close O-1 (rebuild and re-run the freshness gate), O-2 (push and CI on the final head) and O-3 (full regression), and the owner should rule on W-3 before the first production rollout.

---

_Verified: 2026-10-05T22:52:00Z_
_Verifier: Claude (gsd-verifier)_

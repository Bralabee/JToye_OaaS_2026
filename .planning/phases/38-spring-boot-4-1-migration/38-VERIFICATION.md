---
phase: 38-spring-boot-4-1-migration
verified: 2026-10-05T19:48:00Z
status: passed
score: 14/14 must-haves verified
covered_files:
  - .github/workflows/ci-cd.yaml
  - .gitleaks.toml
  - .gitleaksignore
  - .planning/phases/38-spring-boot-4-1-migration/38-01-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-01-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-02-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-02-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-03-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-03-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-04-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-04-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-05-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-05-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-06-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-06-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-07-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-07-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-08-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-08-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-09-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-09-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-10-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-10-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-11-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-11-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-12-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-12-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-13-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-13-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-14-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-14-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-15-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-15-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-16-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-16-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-17-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-17-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-18-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-18-SUMMARY.md
  - .planning/phases/38-spring-boot-4-1-migration/38-19-PLAN.md
  - .planning/phases/38-spring-boot-4-1-migration/38-19-SUMMARY.md
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
covered_digest: "v2:sha256:ce936380db7f0a1bf25588d18c3a1883f486b093b67e97dfdf5bd2048a081a5e"
behavior_unverified: 0
overrides_applied: 0
---

# Phase 38: Spring Boot 4.1 Migration Verification Report

**Phase Goal:** Move core-java from Spring Boot 3.5.16 to 4.1.x and prove the result green by test, gate and live runtime; close three defects today's tests do not catch (KeycloakAdminClient garbage body under Jackson 3; 18 silently ignored config keys needing an unknown-key gate; netty/Tomcat CVE pins moved to their Boot-4 lines); honour D-01..D-05.
**Verified:** 2026-10-05T19:48:00Z
**Status:** passed
**Re-verification:** No, initial verification

Method: SUMMARY claims were not used as evidence. Every row below was re-observed this session from the
worktree source, the running container, GitHub run metadata, or a fresh local Gradle run (`--rerun`, XML read
back). The 2 open review warnings (WR-01, WR-02) were judged against the must-haves and do not break one;
they are carried as non-gating warnings below.

## Goal Achievement

### Observable Truths

ROADMAP.md carries no separate success-criteria list for Phase 38, so the roadmap contract is the goal text plus
BOOT4-01..14 (REQUIREMENTS.md lines 210-223), which the plans restate. All 14 are verified below.

| #  | Truth (requirement) | Status | Evidence (observed this session) |
| -- | ------------------- | ------ | -------------------------------- |
| 1  | BOOT4-01: Boot 4.1.1 in both build files; horizons gate; CI on Temurin 25 | VERIFIED | `build.gradle.kts:2` and `core-java/build.gradle.kts:2` both `"4.1.1"`. `check-dependency-horizons.sh` rc=0 with row `spring-boot` sites `build.gradle.kts:2` + `core-java/build.gradle.kts:2`, eol_cycle 4.1, 2027-07-31, no exemption. Running jar holds `spring-boot-4.1.1.jar`. CI run 37348823924 (success) on `acee00c5`; `git diff --name-only acee00c5..HEAD` = 7 files, 0 outside `.planning/`. |
| 2  | BOOT4-02: explicit per-module starters; no classic, no Jackson-2 autoconfig module, no gRPC module on the production classpath; census; permanent liveness tests | VERIFIED | `core-java/build.gradle.kts:240-370` declares webmvc, data-jpa, flyway, aspectj, security, oauth2-resource-server, validation, actuator, restclient, data-redis, cache, amqp, websocket, mail, webflux, webclient, zipkin and per-module test starters; no non-comment `classic`. Jar listing (239 libs): no `*classic*` starter (only `logback-classic`), no `spring-boot-jackson2`, no `*grpc*`. `Boot4ModuleLivenessIntegrationTest` 6/0 and `AutoConfigurationCensusIntegrationTest` 2/0 re-run green. |
| 3  | BOOT4-03: Flyway liveness | VERIFIED | `spring-boot-starter-flyway` declared (l.244); `spring-boot-flyway-4.1.1.jar` in the running jar; `RlsContractTest` 7/0 and `Boot4ModuleLivenessIntegrationTest` re-run green on real Postgres. The "0 migrations when the module is removed, RlsContractTest red" arm is recorded in `evidence/38-04-suite-and-liveness.txt` and the liveness test is permanent; not re-broken this session (see Behavioral Spot-Checks). |
| 4  | BOOT4-04: Jackson 3 only in main; deprecated converter switch never set; Boot defaults locked by a wire contract | VERIFIED | `rg -uu "com\.fasterxml\.jackson\.(databind\|core\|datatype\|dataformat\|module)" core-java/src/main` returned nothing (rc=1; control: same pattern finds the test-side imports). No `preferred-json-mapper` outside the contract test's own control fixture. `JacksonLineContractTest` 5/0 (includes the closed test-side DELIBERATE-JACKSON2 list) and `Jackson3WireContractTest` 12/0 re-run green. `IdempotencyJson` uses `builderWithJackson2Defaults()` deliberately (frozen format, BOOT4-08). |
| 5  | BOOT4-05: KeycloakAdminClient disable body by content; unit test fails on the pre-fix shape; live offboard with control user | VERIFIED | `KeycloakAdminClient.setUserEnabled` takes/emits `tools.jackson` `ObjectNode`. `KeycloakAdminClientTest` 5/0. Independent fail arm (throwaway test, deleted, `git status` clean): `assertDisableBodyByContent` REJECTS the spike's garbage body (`{"array":false,...,"nodeType":"OBJECT"}`) and REJECTS an unflipped `enabled:true` body, ACCEPTS the correct body (3/3). Live: `evidence/38-17-runtime.txt` §6.6-6.10, offboard HTTP 200, Keycloak read-back `enabled=false` with id, username, `attributes.tenant_id` intact, control user still `enabled=true`, `keycloak_deprovisioned_at` set, and three jq arms (pre-offboard, garbage keys, changed tenant_id) each false. |
| 6  | BOOT4-06: AMQP `JacksonJsonMessageConverter`, same trusted packages; Boot-3.5 broker messages and PENDING outbox rows readable; Jackson-3 output readable by Jackson 2 | VERIFIED | `RabbitMQConfig.java:433` `new JacksonJsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES)`. Re-run green: `AmqpJackson2CompatibilityTest` 18/0, `OutboxPayloadCompatibilityTest` 21/0, `AmqpTypeIdDispatchIntegrationTest` 1/0, `MediaEventOutboxRepositoryTest` 2/0, `PaymentEventOutbox*` integration 8/0. Fixtures are the Boot-3.5-captured goldens (`GoldenFixturesIntegrityTest` 5/0). |
| 7  | BOOT4-07: `GenericJacksonJsonRedisSerializer` with `BasicPolymorphicTypeValidator` allowlist; `v4:` key prefix; real-Redis proof; `jtoye.cache.errors` 0 | VERIFIED | `CacheConfig.java:97` `computePrefixWith(... "v4:" + ...)`, `:189-214` validator + serializer. `CacheSerializerTypeAllowlistTest` 16/0 and `CacheFormatIsolationIntegrationTest` 1/0 re-run green. Live (`38-17` §6.11, §7): keys `v4:products::tenant:...`, `v4:shops::...`; `jtoye_cache_errors_total` 0 on the final container; a deliberately corrupted `v4:` entry moved it to 1 (can fail) and the request degraded to the DB, not a 500. |
| 8  | BOOT4-08: idempotency key reserved under Jackson 2 replays after deploy (no 422), under NOSUPERUSER; response bodies, DSAR ack, jsonb read back unchanged | VERIFIED | `IdempotencyJson` is a dedicated frozen mapper; `IdempotencyService:270,274` route through it. Re-run green: `IdempotencyFingerprintGoldenTest` 13/0 (7 request hashes + 4 stored response types against Boot-3.5-written fixtures), `IdempotencyLegacyHashReplayIntegrationTest` 2/0 (rls_test_role, i.e. NOSUPERUSER), `Customer/OrderIdempotencyIntegrationTest` 5/0, `DsarAckCompatibilityTest` 2/0, `JsonbColumnsReadBackIntegrationTest` 3/0. The 6 production call sites of `IdempotencyService.execute*` map onto those 7 fingerprints. WR-02 is a latent gap for a FUTURE adopter only (see Warnings). |
| 9  | BOOT4-09 (+D-04, D-05): 401 plain `Bearer`, no `resource_metadata`; `/.well-known/oauth-protected-resource` answered by a suppression filter ahead of the framework's | VERIFIED | `ProtectedResourceMetadataSuppressionFilter` wired in `SecurityConfig.java:275-283` (anchored after the framework filter's predecessor, since `addFilterBefore` on that class is refused; comment records it). `ProtectedResourceMetadataSuppressionFilterTest` 18/0, `ProblemDetailAuthenticationEntryPointTest` 18/0, `UnauthenticatedProblemDetailIntegrationTest` 14/0 re-run green. Live on the rebuilt runtime (`38-17` §5): anonymous `/.well-known/...` -> 401 `WWW-Authenticate: Bearer` + problem document; credentialed -> 404 not-found problem document; garbage bearer on `/api/v1/shops` -> `Bearer error="invalid_token"...` with no `resource_metadata`; doctored-input arms FAIL (4 doctored FAIL lines). Owner "anon-401-parity" refinement (2026-10-05) is honoured; the recorded residual (well-formed invalid bearer on the metadata path gets 404) is the accepted one, not a gap. |
| 10 | BOOT4-10: spring-statemachine 4.0.2 kept with `spring-security-access`; tests green; Framework-7 risk recorded | VERIFIED | `core-java/build.gradle.kts:255,259`; `spring-statemachine-core-4.0.2.jar` and `spring-security-access-7.1.1.jar` in the running jar. `StatemachineSecurityAccessTest` 2/0, `OrderStateMachineServiceTest` 11/0, `OrderStateMachineGuardVetoTest` 4/0, `VendorOnboardingStateMachineServiceTest` 13/0 re-run green. Risk recorded: `.planning/codebase/CONCERNS.md:195` and ADR-0006. |
| 11 | BOOT4-11: 18 keys renamed and bound; Boot-3 excludes renamed; CI gate fails on unknown/deprecated key and invalid exclude, VOIDs on empty input, wired into CI | VERIFIED | Source: `spring.web.error.*` (application.yml:499), `management.tracing.export.zipkin.endpoint` (:563), `logging.logback.rollingpolicy.*` in prod and staging, dead staging prometheus key deleted. `RenamedConfigKeysBindingTest` 8/0 re-run green. Gate run fresh: `check-boot-config-keys.sh` rc=0, `tests=9 failures=0`, "218 key(s) in 7 yml file(s) and 36 autoconfigure exclude(s)". Fail arm: pointing `JTOYE_CONFIG_KEY_DIRS` at a scratch yml with a Boot-3 zipkin key, a `server.error.*` key and a bogus `spring.not-a-real-key` -> rc=1, 3 rows named (DEPRECATED, DEPRECATED, UNKNOWN); then a closing clean run rc=0. Wired: `.github/workflows/ci-cd.yaml:269-270`; `check-gate-enforcement.sh` rc=0 (46 gates). VOID (rc=2) arms are documented in the script and in `evidence/38-11-config-keys.txt`; not re-run. |
| 12 | BOOT4-12: CVE floors on Boot-4 lines | VERIFIED | `core-java/build.gradle.kts`: no non-comment netty line; `extra["tomcat.version"]="11.0.26"` (:71), `rabbit-amqp-client.version` 5.34.0 (:131), `jackson-2-bom.version` 2.22.3 (:222), `jackson-bom.version` 3.1.7 (:231). Running jar read-back: `netty-*-4.2.17.Final` throughout (no 4.1.x), `tomcat-embed-core-11.0.26`, `amqp-client-5.34.0`, `jackson-core/databind-3.1.7` and `-2.22.3`. CI "Trivy image gate" step success on the branch image (`evidence/38-18` §4.6); local 0.70.0 scan and near-miss arms are in `evidence/38-15-cve-floors.txt`. |
| 13 | BOOT4-13: OpenAPI snapshot regenerated only after every newly required field proven enforced on Boot 3.5; gate and consumers green | VERIFIED | Commit `f5d537df` "regenerate ... with updateOpenApiSnapshot". `evidence/38-02-request-body-constraints-boot35.tsv` (55 rows, measured on 3.5.16). `RequestBodyConstraintEnforcementTest` 2/0 and `OpenApiSnapshotTest` (check mode) 1/0 re-run green against the committed snapshot. CI jobs "OpenAPI Breaking-Change Gate" and "MCP Server Tests" success on `acee00c5`. |
| 14 | BOOT4-14: docs, gates, runtime parity, images rebuilt, jar read-back, freshness/branch gates, tracing + Prometheus live, nightly E2E on the branch runtime | VERIFIED | Gates run this session: `check-doc-versions` rc=0 (153 claims), `check-doc-citations` rc=0 (29 verified, 0 violations), `check-doc-metrics` rc=0 (37), `docs-freshness` OK (4286 invocations, matches CLAUDE.md), `check-branch-behind-base` rc=0 (0 behind `origin/main` 03022f21, fetched fresh), `check-dependency-horizons` rc=0. Runtime: container `jtoye_oaas_2026-core-java-1` (created 2026-10-05T17:11:27Z, after the newest core-java commit at 16:11:29Z) holds Boot 4.1.1; `application.yml` inside `/app/app.jar` md5 `673618e0...` equals source. Live tracing (32-hex traceId in order-request log lines), Prometheus scrape and alert-metric gate PASS in `38-17` §7. Nightly run 37348829067 (workflow_dispatch, `acee00c5`, success): 325 executed / 319 passed / 0 failed / 6 skipped, read from the report artifact. |

**Score:** 14/14 truths verified (0 present, behavior-unverified; 0 overrides).

Decisions honoured: D-01 (Jackson 3, row 4), D-02 (explicit starters, row 2), D-03 (statemachine kept, row 10), D-04
and D-05 (rows 9), including the owner's 2026-10-05 anon-401-parity refinement.

### Deferred Items

None. No gap was deferred to a later phase.

### Required Artifacts

| Artifact | Expected | Status | Details |
| -------- | -------- | ------ | ------- |
| `core-java/build.gradle.kts` | Boot 4.1.1, starters, CVE keys | VERIFIED | read in full for the dependency/extra blocks |
| `core-java/.../IdempotencyJson.java` | frozen fingerprint mapper | VERIFIED | wired at `IdempotencyService:270,274`; golden-tested |
| `core-java/.../CacheConfig.java` | v4 prefix, allowlist, Jackson-3 serializer | VERIFIED | wired as the CacheManager default config |
| `core-java/.../RabbitMQConfig.java` | `JacksonJsonMessageConverter` | VERIFIED | `:433` |
| `core-java/.../ProtectedResourceMetadataSuppressionFilter.java` | D-05 filter | VERIFIED | registered in `SecurityConfig:283`; live-proven |
| `core-java/.../KeycloakAdminClient.java` | Jackson-3 body | VERIFIED | live-proven |
| `scripts/check-boot-config-keys.sh` | unknown-key gate | VERIFIED | run, fail-armed, wired in CI |
| `docs/api/openapi-snapshot.json` | regenerated snapshot | VERIFIED | check mode green |
| `docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md` | migration record | VERIFIED | exists, cited by CONCERNS.md and the horizons gate |
| `infra/dependency-horizons.yaml` spring-boot row | 4.1, no exemption | VERIFIED | gate rc=0 |

### Key Link Verification

| From | To | Via | Status | Details |
| ---- | -- | --- | ------ | ------- |
| `SecurityConfig` | suppression filter | `addFilterAfter(new ProtectedResourceMetadataSuppressionFilter(...))` | WIRED | `:283`; live 404/401 behaviour confirms it is ahead of the framework filter |
| `CacheConfig.cacheManager` | `jsonRedisSerializer()` + `v4:` prefix | `serializeValuesWith` / `computePrefixWith` | WIRED | live Redis keys carry `v4:` |
| `IdempotencyService` | `IdempotencyJson` | `serialize`/`deserialize` | WIRED | no injected mapper remains |
| `RabbitMQConfig` | trusted-package list | converter constructor | WIRED | |
| `ci-cd.yaml` | `check-boot-config-keys.sh` | JDK-job step | WIRED | `:269-270`; `check-gate-enforcement.sh` rc=0 |
| `KeycloakDeprovisionService` | `KeycloakAdminClient.setUserEnabled` | offboard path | WIRED | live offboard disabled the user |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
| -------- | ------------- | ------ | ------------------ | ------ |
| Cache regions | cached ProductDto/ShopDto | Redis via serializer, DB on miss | Yes: live `v4:` keys read back, corrupted entry degrades to DB | FLOWING |
| Keycloak disable PUT | request body | searched user rep + `enabled` flip | Yes: Keycloak read-back shows `enabled=false` | FLOWING |
| Config keys | zipkin endpoint, error detail, rollout policy | `application*.yml` -> Boot binding | Yes: `RenamedConfigKeysBindingTest` asserts bound values per profile | FLOWING |
| Idempotency replay | stored `response_body` | `idempotency_keys` row written by Boot-3.5 fixtures | Yes: legacy-hash replay test under `rls_test_role` | FLOWING |

### Behavioral Spot-Checks

All runs from `/home/sanmi/IdeaProjects/JToye_OaaS_2026-phase37` with `--rerun`; XML read back (tests/failures/errors).

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| Unit group (12 classes: Jackson line + wire contract, Keycloak, 401/404 filter and entry point, idempotency golden, golden integrity, cache allowlist, outbox, AMQP, renamed keys, statemachine security) | `./gradlew :core-java:test --tests ... --rerun` | 141 tests, 0 failures, 0 errors | PASS |
| Config-key gate on the real tree | `bash scripts/check-boot-config-keys.sh` | rc=0, tests=9 failures=0 | PASS |
| Config-key gate on a bad yml (fail arm) | `JTOYE_CONFIG_KEY_DIRS=<scratch> bash scripts/check-boot-config-keys.sh` | rc=1, 3 keys named; then clean re-run rc=0 | PASS (can fail) |
| Keycloak by-content assertion accepts/rejects | throwaway test calling `assertDisableBodyByContent` (deleted; `git status` clean) | garbage rejected, unflipped rejected, correct accepted | PASS (can fail) |
| Integration group on real Testcontainers (liveness, census, cache isolation, RLS contract, jsonb read-back, 401 problem document, AMQP dispatch, legacy-hash replay under NOSUPERUSER, outbox, order/customer idempotency, OpenAPI snapshot) | `./gradlew :core-java:integrationTest --tests ... --rerun` | all classes 0 failures, 0 errors | PASS |
| Statemachine services | `./gradlew :core-java:test --tests '*StateMachine*' --rerun` | 28 tests (13+4+11), 0 failures | PASS |
| Request-body enforcement oracle | `:core-java:test --tests '*RequestBodyConstraintEnforcementTest'` | 2/0 | PASS |
| Running jar contents | `docker exec jtoye_oaas_2026-core-java-1 unzip -l /app/app.jar` | boot 4.1.1, jackson 3.1.7 + 2.22.3, netty 4.2.17, tomcat 11.0.26, amqp-client 5.34.0, no classic/jackson2/grpc starters | PASS |
| Running `application.yml` vs source | md5 of jar entry vs `core-java/src/main/resources/application.yml` | identical (`673618e0d6c805bb9505df06db8bc9a4`) | PASS |
| CI + nightly metadata | `gh run view 37348823924`, `gh run view 37348829067` | both success, `headSha=acee00c5`; all required CI jobs success (3 deploy-type jobs skipped by design) | PASS |
| Doc/version/metrics/citation/gate-enforcement/horizons/branch-behind gates | `scripts/check-*.sh` (horizons with `/usr/bin` first on PATH) | all rc=0 | PASS |
| `check-runtime-freshness.sh` | `bash scripts/check-runtime-freshness.sh` | rc=2 PARSE ERROR: `docker compose config` needs the env file this shell lacks | SKIP: the recorded run is `38-17` §6.13 (rc=0, 4 services FRESH); the parity it asserts was re-established directly by the jar and `application.yml` read-back above |

Not re-run (cost, covered by CI run 37348823924 and the committed evidence): the full unit (1490/0/1) and integration (771/0/6)
suites; the break arms that remove the Flyway starter or the suppression filter; Trivy; the Playwright nightly.
CI evidence for the first two is the same code (`git diff acee00c5..HEAD` touches only `.planning/`).

### Probe Execution

Step 7c: SKIPPED. No `scripts/*/tests/probe-*.sh` is declared by the phase plans (`rg` over the PLAN/SUMMARY files finds none).

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
| ----------- | ------------ | ----------- | ------ | -------- |
| BOOT4-01 | 38-03, 38-16, 38-18 | Boot 4.1.1, horizons gate, Temurin CI | SATISFIED | Truth 1 |
| BOOT4-02 | 38-03, 38-04, 38-12, 38-13, 38-19 | explicit starters, census, liveness | SATISFIED | Truth 2 |
| BOOT4-03 | 38-03, 38-04 | Flyway liveness | SATISFIED | Truth 3 |
| BOOT4-04 | 38-05..10, 38-12, 38-19 | Jackson 3 only in main | SATISFIED | Truth 4 |
| BOOT4-05 | 38-07, 38-17 | Keycloak body + live offboard | SATISFIED | Truth 5 |
| BOOT4-06 | 38-01, 38-08 | AMQP converter + in-flight state | SATISFIED | Truth 6 |
| BOOT4-07 | 38-01, 38-09, 38-17 | Redis serializer + v4 prefix | SATISFIED | Truth 7 |
| BOOT4-08 | 38-01, 38-02, 38-10 | persisted-JSON continuity | SATISFIED | Truth 8 |
| BOOT4-09 | 38-02, 38-06, 38-17 | Bearer 401 + D-05 filter | SATISFIED | Truth 9 |
| BOOT4-10 | 38-03, 38-04, 38-16 | statemachine kept | SATISFIED | Truth 10 |
| BOOT4-11 | 38-11 | 18 keys + gate | SATISFIED | Truth 11 |
| BOOT4-12 | 38-03, 38-15, 38-18 | CVE floors | SATISFIED | Truth 12 |
| BOOT4-13 | 38-02, 38-14 | OpenAPI snapshot | SATISFIED | Truth 13 |
| BOOT4-14 | 38-16, 38-17, 38-18 | docs, parity, nightly | SATISFIED | Truth 14 |

Cross-reference: every ID BOOT4-01..14 appears in at least one PLAN's `requirements:` frontmatter (counts 1-8 plans each;
BOOT4-11 only in 38-11) and in REQUIREMENTS.md lines 210-223 (all `[x]`). REQUIREMENTS.md maps no other ID to Phase 38.
**Orphaned requirements: none.**

### Anti-Patterns Found

Scanned the 45 production/build/config/script files changed by the phase (diff `origin/main...HEAD`, excluding tests, docs, planning):
no added line carries `TBD`, `FIXME` or `XXX` (positive control: the same pattern matches a seeded `// FIXME`); no `TODO`/`HACK`/`PLACEHOLDER` added in `core-java/src/main`.

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| `.planning/REQUIREMENTS.md` | 321-334 | Traceability Status column still reads "Planned 2026-10-04" for all 14 BOOT4 rows while the checkboxes above are `[x]` | Info | bookkeeping drift only; update at phase close |
| `.planning/ROADMAP.md` | 58 | Phase 38 index line still `[ ]` (detail section shows 19/19 plans executed) | Info | flips at phase completion |
| `HANDOFF.md` | live block | still says Phase 38 is "planned, ready to execute" (review IN-04) | Info | stale handoff text |
| `.planning/STATE.md` | ~52 | "Carried red: docs-freshness.sh" is stale; `docs-freshness.sh` passes now (4286) | Info | stale note |

### Warnings (non-gating; no must-have is broken)

**W-1. WR-01 (review, open): the `v4:` cache prefix splits evictions across Boot generations during a rolling deploy or a rollback.**
Observed in code: `CacheConfig.java:97` prefixes only Boot-4 pods; `TenantCacheEvictor` evicts through `Cache.evict`, so each
pod evicts only its own generation's key; `k8s/base/core-java-deployment.yaml` is `RollingUpdate`, replicas 3, maxSurge 1. A
shop-grant revoke handled by one generation therefore does not evict the other generation's `shopMembership` entry, which
stays stale until its 5-minute TTL (products 10 min, shops 15 min). ADR-0006 says "No flush is needed" and does not state this
bounded window. This does NOT break BOOT4-07 (its truth is that Jackson-2-era entries are never read and the counter stays 0,
both proven), and the exposure is transient and TTL-bounded, so it is not a gap. It is, however, a stale-authorization window on
an auth cache for the first production rollout and rollback of this release. Decision requested from the owner before the
first prod/staging rollout: record the window in the ADR deploy notes (review fix b), or add the transitional dual-key evict
(fix a). Disposition in `38-REVIEW-DISPOSITION.md` is `open`.

**W-2. WR-02 (review, open): `IdempotencyJson` does not enable `DETECT_PARAMETER_NAMES`.**
`builderWithJackson2Defaults()` disables it, whereas Boot 3.5's auto-configured mapper registered `ParameterNamesModule`. Checked
against the must-have ("reproducing the Jackson-2 bytes for every adopter"): the 6 production call sites of
`IdempotencyService.execute*` and the 7 fingerprints / 4 stored response types are covered byte-for-byte by
`IdempotencyFingerprintGoldenTest` (13/0, fixtures written by a real Boot-3.5 pod), and `IdempotencyLegacyHashReplayIntegrationTest`
(2/0) replays a Boot-3.5 reservation. So no CURRENT adopter diverges and BOOT4-08 holds. The class's own rule forbids editing the
mapper after ship, so closing it before this lands is cheaper than after; recommended, not required for this phase.

Info findings IN-01..IN-04 (CI gate step sets unneeded DB env vars; hand-pinned `spring-retry`; 404 `instance` uses the raw URI;
stale HANDOFF.md) change no must-have.

### Human Verification Required

None. Every truth has either a test that I re-ran green or live-runtime evidence whose method was checked against the committed
files and the running container; the one owner-accepted residual (404 for a well-formed invalid bearer on the metadata path) is a
recorded decision, not an open item.

### Gaps Summary

No gaps. Phase goal achieved: core-java builds, tests and runs on Spring Boot 4.1.1; the three named defects are closed (Keycloak
body proven by content, by a break arm and live; 18 keys renamed with a CI gate proven failing; netty/Tomcat/amqp/Jackson floors on
Boot-4 lines, confirmed inside the running jar); D-01..D-05 are honoured. The phase may proceed. Before the first production
rollout, the owner should rule on W-1.

---

_Verified: 2026-10-05T19:48:00Z_
_Verifier: Claude (gsd-verifier)_

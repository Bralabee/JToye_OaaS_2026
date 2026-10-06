---
phase: "38"
slug: "spring-boot-4-1-migration"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-10-04"
---

# Phase 38 — Validation Strategy

> This is the validation contract for the phase: what gets tested, how often, and with which command. Source: `38-RESEARCH.md` § Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Jupiter 6.0.3 (managed by Boot 4.1.1), Spring Boot Test 4.1.1 and Testcontainers 1.21.4 (explicit) |
| **Config file** | `core-java/build.gradle.kts`. `tasks.test` excludes the `testcontainers` tag and `integrationTest` includes it, with forkEvery 4. |
| **Quick run command** | `./gradlew :core-java:test --tests '<Class>'` |
| **Full suite command** | `./gradlew :core-java:test :core-java:integrationTest --rerun`. Read counts from `core-java/build-local/test-results/**.xml`, never from `build/`. Baseline: 1330 unit, 745 integration. |
| **Estimated runtime** | ~2,700 seconds for the full suite. The integration tests (Testcontainers, forkEvery 4) dominate. |

---

## Sampling Rate

- **After every task commit:** run the unit tests of the classes the task touched, plus a compile of main and test.
- **After every plan wave:** run the full `test` plus `integrationTest --rerun`. Compare counts from the XML against the 1330 / 745 baseline plus any new tests.
- **Before `/gsd-verify-work`:**
  - the full suite is green;
  - every BOOT4 arm is recorded in both directions;
  - the runtime gates pass on a rebuilt runtime;
  - the nightly E2E is green on the branch's runtime.
- **Max feedback latency:** about 300 seconds per task (targeted unit tests).
- **Before every fail-direction arm:** delete the stale test-result XML. In the spike's first Flyway arm, compilation failed and the old XML was read, so the arm proved nothing.

---

## Per-Task Verification Map

Task IDs are assigned by the planner. Until then, each row is keyed by requirement. Each "Fail-direction arm" is the deliberate break that must turn the check red before a pass is trusted.

| Requirement | Behavior | Test Type | Automated Command | Fail-direction arm | File Exists | Status |
|-------------|----------|-----------|-------------------|--------------------|-------------|--------|
| BOOT4-01 | Resolves to Boot 4.1.1 | build | `./gradlew :core-java:dependencyInsight --dependency spring-boot --configuration runtimeClasspath` | Plugin left at 3.5.16 in one of the two files → mismatch reported | ❌ W0 | ⬜ pending |
| BOOT4-02 | No classic, jackson2 or grpc modules on the classpath; the census diff has no unintended items | integration | census test, plus `dependencies --configuration runtimeClasspath` piped through grep | Drop `spring-boot-starter-zipkin` → census names the tracing auto-config | ❌ W0 | ⬜ pending |
| BOOT4-03 | Migrations applied > 0 | integration | `integrationTest --tests '*RlsContractTest' '*FreshChainMigrationIntegrationTest'` plus the applied count from the log | Runtime-exclude `spring-boot-flyway` → 0 applied, RlsContractTest red | ✅ (new arm) | ⬜ pending |
| BOOT4-04 | `main` has no Jackson-2 databind/core imports | static | `rg -uu -n 'com\.fasterxml\.jackson\.(databind|core)' core-java/src/main` returns nothing (print rc) | Re-add one import → non-empty | ❌ W0 | ⬜ pending |
| BOOT4-05 | Keycloak request body is correct, checked by content | unit + live | `test --tests '*KeycloakAdminClientTest'`; live: offboard, then admin GET | Current Jackson-2 ObjectNode → garbage keys present | ✅ (strengthen assertion) | ⬜ pending |
| BOOT4-06 | A message written by Jackson 2 is consumed | unit | new `RabbitMessageCompatibilityTest` | Remove a trusted package → conversion exception | ❌ W0 | ⬜ pending |
| BOOT4-07 | Cache entries round-trip and refusal still works; old entries are not read | unit + runtime | `test --tests '*CacheSerializerTypeAllowlistTest' '*MembershipSerializerRoundTripTest'`; at runtime `jtoye.cache.errors == 0` | Re-derived allowlist minus a required entry → read fails | ✅ (re-derive) | ⬜ pending |
| BOOT4-08 | An idempotency key reserved before the deploy still replays | unit + integration | Golden-hash unit test (literals captured on `main` under Jackson 2), plus a NOSUPERUSER integration replay of a Jackson-2-era reservation → original response, not 422 | Fingerprint through Boot's default `JsonMapper` → hash mismatch / 422 | ❌ W0 | ⬜ pending |
| BOOT4-09 | 401 header is exactly `Bearer` or the RFC 6750 error form | unit + runtime | `test --tests '*ProblemDetailAuthenticationEntryPointTest'`; live `curl -i` | Remove the wrapper → `resource_metadata` present | ✅ (change assertion) | ⬜ pending |
| BOOT4-10 | State-machine transitions behave as before | unit + integration | `test --tests '*StateMachine*'`, plus OrderControllerIntegrationTest | Spike arms A/B: retarget a transition; make a guard always pass | ✅ | ⬜ pending |
| BOOT4-11 | An unknown or removed config key fails CI | unit + script | `scripts/check-boot-config-keys.sh` and `ConfigKeyContractTest` | Inject `spring.bogus.key` and a Boot-3 key → exit 1; empty input → exit 2 | ❌ W0 | ⬜ pending |
| BOOT4-12 | CVE floors resolve and the image scans clean | build + image | `dependencyInsight` per artifact; Trivy 0.70.0 image scan (`aquasec/trivy:0.70.0`, gate flags) | `tomcat.version=11.0.24` → Trivy exit 1; near-miss key → managed version | ❌ | ⬜ pending |
| BOOT4-13 | OpenAPI snapshot is fresh and newly required fields are enforced | integration + gate | `integrationTest --tests '*OpenApiSnapshotTest'`; a 400 request per field; `openapi-gate.sh` | Skip the snapshot regeneration → gate fails | ✅ | ⬜ pending |
| BOOT4-14 | Runtime parity and docs gates | script | `check-runtime-freshness.sh`, `check-branch-behind-base.sh`, `check-doc-versions.sh`, `check-doc-citations.sh`, `check-dependency-horizons.sh`, `docs-freshness.sh`, `check-doc-metrics.sh`; `unzip -l /app/app.jar` names spring-boot 4.1.1; `unzip -p /app/app.jar BOOT-INF/classes/application.yml` | Stop one service → freshness VOID (exit 2); stale doc claim → exit 1 | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Census harness comparing auto-configurations that activate under explicit starters vs classic (BOOT4-02)
- [ ] `ConfigKeyContractTest` and `scripts/check-boot-config-keys.sh`, wired into CI and enforced by `check-gate-enforcement.sh` (BOOT4-11)
- [ ] `RabbitMessageCompatibilityTest` (BOOT4-06)
- [ ] Idempotency golden-hash test, with literals captured on `main` under Jackson 2 BEFORE any Jackson-3 change (BOOT4-08)
- [ ] Static Jackson-import check (BOOT4-04)
- [ ] A step that deletes stale test-result XML before every arm run

---

## Plan Mapping (planner, 2026-10-04; revised 2026-10-04: the 38-12 test sweep split out as 38-19, and 38-03's doc and doc-gate edits moved to 38-16)

| Requirement | Plan / task carrying the automated check | Fail-direction arm lives in |
|-------------|------------------------------------------|-----------------------------|
| BOOT4-01 | 38-03 T1 (dependencyInsight), 38-03 T2 (both version gates recorded red on the half-done tree, expected interim), 38-16 T1 (check-dependency-horizons and check-doc-versions green), 38-18 T2 (CI on Temurin) | 38-16 T1 arm C (build file left at 3.5.16 -> H-5 VOID, rc 2) and arm D (stale claim -> rc 1) |
| BOOT4-02 | 38-04 T2 (Boot4ModuleLivenessIntegrationTest), 38-19 T1-T3 (every test injector green with the bridge line removed), 38-12 T1 (JacksonLineContractTest classpath), 38-13 T1-T2 (census) | 38-04 T2 arms A-C, 38-19 T1 unmigrated-class bridge-off arm, 38-12 T1 arm A, 38-13 T2 zipkin arm |
| BOOT4-03 | 38-03 T1 (applied67), 38-04 T2 | 38-04 T2 arm A |
| BOOT4-04 | 38-12 T1 (JacksonLineContractTest main scan), 38-05 T3 (Jackson3WireContractTest), 38-19 T3 (closed test-side DELIBERATE-JACKSON2 list) | 38-12 T1 arms B-C, 38-05 T3 arm |
| BOOT4-05 | 38-07 T1 (KeycloakAdminClientTest by content), 38-17 T3 (live) | 38-07 T1 Jackson-2-node arm |
| BOOT4-06 | 38-08 T1-T3 (AmqpJackson2CompatibilityTest, OutboxPayloadCompatibilityTest, AmqpTypeIdDispatchIntegrationTest) | 38-08 T3 arms A-C |
| BOOT4-07 | 38-09 T1-T2 (CacheSerializerTypeAllowlistTest, CacheFormatIsolationIntegrationTest), 38-17 T3 (runtime) | 38-09 T1 dropped-prefix arm, T2 prefix-removed arm |
| BOOT4-08 | 38-10 T1-T2 (IdempotencyFingerprintGoldenTest, IdempotencyLegacyHashReplayIntegrationTest), 38-02 T2 (JsonbColumnsReadBackIntegrationTest) | 38-10 T2 plain-mapper arm, 38-02 T2 corrupted-span arm |
| BOOT4-09 | 38-06 T1-T2, 38-17 T2 (live curl) | 38-06 T1 pass-through arm, T2 registration-removed arm |
| BOOT4-10 | 38-04 T3 | 38-04 T3 arms A, B and the spring-security-access exclusion |
| BOOT4-11 | 38-11 T1-T3 (ConfigKeyContractTest, RenamedConfigKeysBindingTest, scripts/check-boot-config-keys.sh) | 38-11 T3 arms A-F |
| BOOT4-12 | 38-15 T1-T2, 38-18 T2 (CI image gate) | 38-15 T2 arms A-D |
| BOOT4-13 | 38-02 T1 (inventory), 38-14 T1-T2 | 38-14 T1 doctored-pair arm, T2 old-snapshot gate arm |
| BOOT4-14 | 38-16 T1-T3 (doc gates, incl. the horizons row and doc-versions row moved from 38-03), 38-17 T2-T3 (runtime parity), 38-18 T2 (nightly) | 38-16 T1 arms A-E, 38-17 T2 stopped-service VOID arm |

Wave 0 gaps, as planned: census harness -> 38-13 (with 38-04's liveness probes earlier); ConfigKeyContractTest + gate + CI -> 38-11;
RabbitMessageCompatibilityTest -> 38-08 AmqpJackson2CompatibilityTest; idempotency golden hashes captured on Boot 3.5 -> 38-01, proven
-> 38-10; static Jackson-import check -> 38-12 JacksonLineContractTest; stale-XML deletion -> every arm task (cleanTest /
cleanIntegrationTest or an explicit rm of the results directory before the arm run).

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| A live Keycloak offboard sends the correct body | BOOT4-05 | Needs the running compose Keycloak and an admin read-back | Rebuild the runtime, offboard a test tenant, then GET the user from the Keycloak admin API and assert enabled=false with no stray keys |
| The rebuilt runtime matches the branch | BOOT4-14 | A CI runner has no running stack | `scripts/sync-runtime.sh`, then the freshness gate, then read the Boot version and `application.yml` from inside `/app/app.jar` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending

# Boot 3.5.16 -> 4.1.1 feasibility spike (core-java, issue #706)

Throwaway spike. It ran in a detached worktree off origin/main 43ed6bbf, on local branch `spike/boot4` with 4 local commits. Nothing was pushed and no PR was opened. The full diff is saved at `scratchpad/boot4-spike.patch`.
JDK: OpenJDK 25.0.4.1 (Ubuntu build). No Temurin JDK is installed on this machine; the only other JDK 25 builds here are Oracle 25.0.1 and 25.0.2. Gradle 9.7.1.

## Bottom line

- **Compiles:** yes, on the least invasive route.
- **Unit suite:** 1330 tests, 2 failures (both real behaviour changes).
- **Integration suite (Testcontainers):** 745 tests, 1 failure (the OpenAPI snapshot).
- **spring-statemachine 4.0.2:** works on Boot 4.1.1 / Framework 7.0.9.
- **The real migration costs** are the Jackson 3 default, 18 silently-dropped config properties, and the OpenAPI snapshot plus breaking-change gate. The state machine is not one of them.

## 1. Changes made (105 files, +200/-184; spike probe files excluded)

**Build scripts (2 files)**

- Boot plugin `4.1.1` in both `build.gradle.kts` (root `apply false`) and `core-java/build.gradle.kts`. dependency-management 1.1.7 kept; it works.
- `spring-boot-starter-classic` added; `spring-boot-starter-test` changed to `spring-boot-starter-test-classic`.
- `spring-boot-starter-aop` is gone in Boot 4, replaced by `spring-boot-starter-aspectj`.
- `resilience4j-spring-boot3` changed to `resilience4j-spring-boot4:2.4.0`; springdoc `3.1.1`.
- `spring-retry` is no longer managed by the BOM, so it is pinned to `2.0.13`.
- `jackson-bom.version` re-keyed to `jackson-2-bom.version = 2.21.6`. The Boot 4 key `jackson-bom.version` is Jackson 3 (3.1.5).
- `tomcat.version` changed from `10.1.59` to `11.0.25` (see section 5). The netty pin was removed (see section 5).
- NEW: `configurations.all { exclude spring-boot-grpc-server, spring-boot-grpc-client }`.
  - Classic ships these autoconfigure modules but not spring-grpc itself.
  - `spring-boot-grpc-server` registers `GrpcDisableCsrfHttpConfigurer` as a default `AbstractHttpConfigurer` in `spring.factories`. Its `init()` resolves `org.springframework.grpc.server.service.GrpcServiceDiscoverer` whenever a filter chain keeps CSRF on, which throws `NoClassDefFoundError`.
  - This broke 30 `@WebMvcTest` contexts (Boot's default `jwtSecurityFilterChain`).
  - Production is not hit today only because `SecurityConfig` calls `csrf.disable()` before build. It is a latent startup failure for any future chain that keeps CSRF on.
- Flyway: nothing extra needed with classic, because classic bundles the `spring-boot-flyway` module. Without classic you need `spring-boot-starter-flyway`; this is proven by the arm in section 2.

**Package moves in main (6 files)**

- `boot.web.client.RestTemplateBuilder` -> `boot.restclient.RestTemplateBuilder` (2)
- `boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer` -> `boot.amqp.autoconfigure.*` (1)
- `data.mapping.PropertyReferenceException` -> `data.core.*` (1)
- `amqp.rabbit.listener.MessageListenerContainer` -> `amqp.core.*` (1)

**API removal in main (1 file)**

- Lettuce 7 removed `RedisClient.setDefaultTimeout`, in `RateLimitConfig`. The line was deleted because `RedisURI.withTimeout` already carries the same timeout.

**Tests (97 files, all mechanical except 2)**

- `@MockBean`/`@SpyBean` -> `@MockitoBean`/`@MockitoSpyBean`: 32 files, 63 annotations plus 1 fully-qualified usage in `MediaDurabilityIntegrationTest`.
  - Every one is a plain field with no attributes and none sits in an `@Configuration` class, so **a sed suffices** (import line plus `^\s*@MockBean\b`).
- `boot.test.autoconfigure.web.servlet.*` -> `boot.webmvc.test.autoconfigure.*`: 66 files.
- `TestRestTemplate` -> `boot.resttestclient.*`, plus `@AutoConfigureTestRestTemplate` (Boot 4 no longer auto-registers it): 1 file.
- `DataJpaTest` -> `boot.data.jpa.test.autoconfigure.*`: 1 file.
- `FlywayAutoConfiguration`/`DataSourceAutoConfiguration` package moves: 1 file.
- `PropertyPath`/`TypeInformation` -> `data.core.*`: 1 file.
- `NoResourceFoundException` gained a third constructor argument (`resourcePath`): 1 file.

**Deprecated for removal, still working:** `EnvironmentPostProcessor` in `ActiveProfileValidator`, `Jackson2JsonMessageConverter` in `RabbitMQConfig`, `GenericJackson2JsonRedisSerializer` in `CacheConfig`. Boot 4.1.1 still loads the old `org.springframework.boot.env.EnvironmentPostProcessor` key in `spring.factories` through `SpringFactoriesEnvironmentPostProcessorsFactory$Adapter` (checked with javap), so the P0-2 profile guard still fires.

## 2. Results (counted from build-local XML, `--rerun`)

| Suite | Baseline (CI run 36776735636 on be345ea1) | Boot 4.1.1 spike |
|---|---|---|
| compileJava / compileTestJava | pass | pass |
| unit `test` | 1330 tests / 0 fail / 1 skip | 1330 / **2 fail** / 1 skip (plus 3 spike probe tests, all pass) |
| `integrationTest` | 745 / 0 / 6 skip | 745 / **1 fail** / 0 err / 1 skip |

- The baseline core-java differs from HEAD only by dependabot bumps in build.gradle.kts.
- Before fixes the unit suite stood at 1330 / 72 failures: 30 gRPC `NoClassDefFoundError` contexts, 1 TestRestTemplate class, and 2 real failures.
- CI's 5 extra skips are `Assumptions.assumeTrue(mailhog.isReachable())`. Locally Mailhog was reachable, so those 5 ran and passed. That is an environment difference, not Boot 4.
- **Flyway proven to run:** 143 integration classes log `Successfully applied 67 migrations to schema "public", now at version v67` (Flyway 12.4.0). FreshChainMigrationIntegrationTest passes 5/5 and RlsContractTest 7/7.
  - Fail-direction arm: `spring-boot-flyway` excluded from the test runtime classpath gave 0 migrations applied and RlsContractTest 4/7 red, with its non-vacuity controls firing. Restored: 7/7 with migrations applied.
  - My first version of this arm was vacuous: compile failed, so the XML was stale. It was redone with the runtime-only exclusion and the XML deleted before the run.
- `@Retryable` (spring-retry 2.0.13) is exercised by ConcurrentStockDecrementIntegrationTest, which passed.

## 3. Remaining failures, root causes

1. **KeycloakAdminClientTest (a real production bug under the default config).**
   - Boot 4 wires the Jackson 3 `JacksonJsonHttpMessageConverter` into RestClient. `KeycloakAdminClient.setUserEnabled` sends a Jackson 2 `ObjectNode`, which Jackson 3 serializes as a bean.
   - Measured wire body: `{"array":false,"bigDecimal":false,...,"nodeType":"OBJECT",...}`, with no `enabled` field. Tenant-offboard deprovisioning would PUT garbage to Keycloak.
   - It is the only affected call site. The other client bodies are `byte[]` (webhooks) or `Map` (AI), and responses are read as String and then `readTree`.
2. **ProblemDetailAuthenticationEntryPointTest.**
   - Spring Security 7's `BearerTokenAuthenticationEntryPoint` now emits `WWW-Authenticate: Bearer resource_metadata="http://localhost/.well-known/oauth-protected-resource"` (RFC 9728) instead of bare `Bearer`.
   - This is a contract change on every 401. I did not check whether that metadata URL is served.
3. **OpenApiSnapshotTest (springdoc 3.1.1).** Same 109 paths and 108 schema names; 19 schemas changed:
   - Bean Validation is now reflected into `required` on 11 request DTOs (e.g. `GuestOrderRequest` [] -> [customerEmail, customerName, customerPhone, fulfilmentType, items]; `CreateCustomerRequest` [] -> [email, name]), and 5 `format: email` were added. The server already enforced these, so the docs are catching up with behaviour.
   - The `HttpStatus` enum lost its deprecated constants (e.g. `413 REQUEST_ENTITY_TOO_LARGE` -> `413 CONTENT_TOO_LARGE`).
   - `MyAccessDto.grantedShopIds` became `["array","null"]`.
   - The pre-existing `RedirectView`/`ApplicationContext` introspection schemas expanded.
   - Newly required request fields will very likely trip the CI OpenAPI breaking-change gate, which I did not run.

**Silent config breakage (no test catches it).**
- Audited all 211 `spring|management|server|logging.*` keys in `application*.yml` against the Boot 4.1.1 configuration metadata (103 files, 2823 properties).
- Fail-direction arm: an injected bogus key was reported UNKNOWN, and the clean tree reports none.
- 18 keys are `level=ERROR`, meaning they are **no longer bound and are silently ignored**:
  - `management.zipkin.tracing.endpoint` (application.yml) -> `management.tracing.export.zipkin.endpoint`. **`ZIPKIN_ENDPOINT` would be ignored in every deployment.**
  - `server.error.include-*` -> `spring.web.error.include-*` (application, prod, staging). The base `always` and staging's `on_param` would revert to Boot's defaults. Prod's `never` equals the default, so prod is unchanged.
  - `logging.file.max-size/max-history/total-size-cap` -> `logging.logback.rollingpolicy.*` (prod, staging). Retention reverts to defaults (prod was 30 days / 1GB).
  - `management.metrics.export.prometheus.enabled` -> `management.prometheus.metrics.export.enabled` (staging).

## 4. Statemachine verdict: WORKS on 4.1.1 / Framework 7.0.9

- **Unit tests against the real library:**
  - OrderStateMachineServiceTest 11/11 and VendorOnboardingStateMachineServiceTest 13/13, both `@SpringBootTest` on the real `@EnableStateMachineFactory` configs.
  - OrderStateMachineGuardVetoTest 4/4, which builds a guarded machine with the library's own `StateMachineBuilder`.
  - Resolved transitive versions: statemachine-core 4.0.2 on spring-context/tx/messaging 7.0.9 and reactor 3.8.7.
- **Fail-direction bracket (clean -> arms -> clean again):**
  - Arm A retargeted READY+COMPLETE to CANCELLED: "happy path" went red.
  - Arm B made the APPROVE guard always pass: "APPROVE guard rejects when a mandatory gate is still PENDING" went red.
  - Restore verified by sha256; clean again 28/28.
- **Full-context integration:** 14 classes drove real transitions through the library, all green. OrderControllerIntegrationTest, MoneyPathExecutionIntegrationTest, RefundWebhookHandlingIntegrationTest and the Onboarding* suites are among them. Logged transitions:
  - Order: PENDING->CONFIRMED x10, DRAFT->PENDING x5, CONFIRMED->PREPARING x4, PREPARING->READY x3, READY->COMPLETED x3, CONFIRMED->REFUNDED x1.
  - Onboarding: the guarded PENDING_APPROVAL->APPROVED x2 and APPROVED->LIVE x2, plus REJECT and WITHDRAW paths.
- **Linkage errors:** zero `NoSuchMethodError`, `NoClassDefFoundError`, `ClassNotFoundException` or `AbstractMethodError` in any statemachine test output.
- **Latent risk (static jdeps check against the 4.1.1 runtime classpath):**
  - statemachine-core references 24 classes from `org.springframework.security.access.*` (AccessDecisionManager, voters) that Security 7 moved out to a separate `spring-security-access` artifact. The references are in `AbstractStateMachineFactory`, `StateMachineConfigurationBuilder` and `ConfigurationData`.
  - They load lazily, so our path is fine. `withSecurity()` or statemachine event security would throw `NoClassDefFoundError`; adding `spring-security-access:7.1.1` would close that.
  - The autoconfigure module's references to the Boot 3 `EntityScan` package are only in the JPA/Redis/Mongo repository autoconfigs. Those are `@ConditionalOnClass`-gated on `spring-statemachine-data-*`, which we don't ship, so they are skipped.
  - The library itself is unmaintained for Framework 7; it is not a blocker today.
- **Size, for a hand-rolled replacement:**
  - OrderStateMachineConfig 155 LOC / Service 145 LOC: 8 states, 7 events, 15 transitions, 0 guards, log-only actions, 2 terminal states.
  - VendorOnboardingStateMachineConfig 227 LOC / Service 101 LOC: 9 states, 10 events, 16 transitions, 2 guard lambdas on 3 transitions (both read `VendorOnboardingGateRepository`), log-only actions.
  - 3 call sites: OrderService, RefundService, VendorOnboardingService.
  - Both services already emulate a transition table: they build a fresh machine per call, reset its state, send one event and compare states.
  - An `EnumMap<State, Map<Event, (target, guard)>>` would be roughly 60-80 LOC per machine. The existing tests would carry over as the spec.

## 5. Jackson 2 vs 3; CVE-floor pins

**Jackson verdict.** Measured with a full-context probe test, default config vs `spring.http.converters.preferred-json-mapper=jackson2`:
- **Both ObjectMapper beans exist either way:** `jackson2ObjectMapper` (com.fasterxml) and `jacksonJsonMapper` (tools.jackson 3.1.5).
- **MVC and RestClient use Jackson 3 by default** (`JacksonJsonHttpMessageConverter`).
- With `preferred-json-mapper=jackson2` they switch to `MappingJackson2HttpMessageConverter`, and the `ObjectNode` body is correct (`{"enabled":false}`). Instant serialization is identical in both modes.
- **Keeping Jackson 2 is viable in 4.1.1 but deprecated.** The `jackson2` value and every `spring.jackson2.*` property are marked "Deprecated in favor of Jackson 3, since 4.0.0".
- Exposure:
  - 14 main classes inject a Jackson 2 `ObjectMapper` (17 import databind).
  - There are 0 uses of `com.fasterxml.jackson.databind.annotation`, so nothing would be silently ignored on DTOs. Jackson 3 still reads `com.fasterxml.jackson.annotation`.
  - Test files importing Jackson 2: 47.
- Jackson 2 stays on the classpath regardless, via swagger-core, azure-core and others.
- swagger-core 2.2.55 (springdoc 3.1.1) requests jackson-databind 2.22.1. Our 2.21.6 pin DOWNGRADES it.

**CVE floors.** Resolved with dependencyInsight; the fail-direction was run for both pins:
- **netty:** Boot 4.1.1 manages 4.2.17.Final. GitHub advisories list 4.2.17 as patched for CVE-2026-75595/75596 (paired with 4.1.137) and 4.2.16 for CVE-2026-55831/55833/56745/59901 (paired with 4.1.136). **The 4.1.137 pin must be REMOVED:** it would force a 4.1 netty under reactor-netty on the 4.2 line. The floor is met without a pin.
- **Tomcat:** Boot 4.1.1 manages 11.0.24, and **11.0.24 is below our floor**. Tomcat's security page lists the same 12 CVEs fixed in 10.1.59 and 11.0.25. Pinned to 11.0.25. Removing the pin gives `11.0.24 (selected by rule)`. 11.0.26 (2026-09-09) fixes 14 more.
- **amqp-client:** the 5.34.0 pin still resolves. Removing it gives `5.30.0 (selected by rule)`, which is below the floor and below spring-rabbit 4.1.1's own 5.31.0, so the pin stays.
- **Testcontainers:** stays at 1.21.4 (all artifacts; explicit versions win over the BOM's 2.0.5) and works.

## 6. Needs an owner decision

1. **Jackson line:** migrate to Jackson 3 (at least `KeycloakAdminClient`; the Rabbit/Redis serializers are deprecated for removal), or set the deprecated `preferred-json-mapper=jackson2` as a stopgap. The Keycloak bug ships either way unless one of these is chosen.
2. **OpenAPI:** accept the regenerated snapshot. The newly `required` request fields will probably need a breaking-change-gate exception, even though the server already enforced them.
3. **Security 7 `WWW-Authenticate: Bearer resource_metadata=...`:** accept it (and serve the metadata) or customise the entry point.
4. **Classic starters vs explicit module starters:** classic drags in ~90 autoconfigure modules, gRPC included, which needs the exclusion above. Moving to explicit starters (`starter-webmvc`, `starter-flyway`, `starter-security-oauth2-resource-server`, ...) is the cleaner end state.
5. **Rename the 18 silently-dropped properties.** Tracing endpoint, error detail and log retention are affected at runtime.
6. **Keep spring-statemachine** (add `spring-security-access` as a guard?) **or replace it** with the ~150 LOC transition tables. It is not blocking.
7. **Major bumps that came in with Boot 4:** Hibernate 7.4.5, Flyway 12.4.0, Lettuce 7.5.2, JUnit 6.0.3, Spring Data 2025.1, Spring AMQP 4.1.1. All suites pass, but each is a major-version move per the escalation rule.
8. **Pins:** bump Tomcat to 11.0.26, and move the Jackson 2 pin to 2.22.1 to stop downgrading swagger-core.

**Not done:** no `bootJar` start against the compose stack (the integration suite boots full contexts on real Postgres, Redis and Rabbit); no CI OpenAPI breaking-change gate run; no Temurin JDK.

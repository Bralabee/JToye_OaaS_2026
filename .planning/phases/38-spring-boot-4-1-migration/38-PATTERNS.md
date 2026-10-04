# Phase 38: Spring Boot 4.1 Migration - Pattern Map

**Mapped:** 2026-10-04
**Files analyzed:** 18 (new + modified)
**Analogs found:** 17 / 18

All paths are relative to the repo root and are git-tracked (checked with `git ls-files`). Line numbers are from branch `phase-37-spring-boot-4-1` @ 6a219c9b.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `scripts/check-boot-config-keys.sh` (NEW, BOOT4-11) | CI gate script | batch / file-I/O | `scripts/check-no-create-extension.sh` (+ `scripts/check-jacoco-coverage.sh` for reading `build-local` outputs) | exact (gate shape) |
| `.github/workflows/ci-cd.yaml` (wire the new gate) | config | batch | `ci-cd.yaml` lines 945-958 (no-CREATE-EXTENSION step) | exact |
| `core-java/src/test/java/.../config/ConfigKeyContractTest.java` (NEW, BOOT4-11 engine) | test | file-I/O | `core-java/src/test/java/uk/jtoye/core/storage/WorkloadIdentityCredentialBuildTest.java` (production-classpath loader); spike `BootSpikePropertyAuditTest` in `38-SPIKE.patch` l.1695-1760 | role-match |
| `core-java/src/main/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilter.java` (NEW, D-05; name at planner's discretion) | middleware (servlet filter) | request-response | `core-java/src/main/java/uk/jtoye/core/security/TenantContextCleanupFilter.java` | exact |
| `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` (register the D-05 filter) | config | request-response | itself, lines 255-266 (`addFilterBefore` / `addFilterAfter`) | exact |
| `core-java/src/main/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPoint.java` (D-04 wrapper + Jackson 3) | middleware | request-response | itself (lines 47-86) | exact |
| `core-java/src/test/java/.../security/ProblemDetailAuthenticationEntryPointTest.java` + `UnauthenticatedProblemDetailIntegrationTest.java` (strengthen; add D-05 404 test) | test | request-response | `core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java` | exact |
| `core-java/src/test/java/.../common/idempotency/IdempotencyLegacyHashReplayIntegrationTest.java` (NEW, BOOT4-08) | test (NOSUPERUSER Testcontainers) | CRUD | `core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java` + `IdempotencyServiceUnstoredResponseIntegrationTest.java` | exact |
| `core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyService.java` (Jackson 3; possibly legacy-hash acceptance) | service | CRUD | itself — existing `legacyRequestBody` second-hash path, lines 155-172, 237-242 | exact |
| `core-java/src/test/java/.../config/RabbitMessageCompatibilityTest.java` (NEW, BOOT4-06) | test | event-driven | `core-java/src/test/java/uk/jtoye/core/config/RabbitMQConfigMessageConverterTest.java` | exact |
| `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java` (converter swap, l.404-426) | config | event-driven | itself | exact |
| `core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java` (Redis serializer, l.109-190) | config | CRUD (cache) | itself | exact |
| `core-java/src/test/java/.../config/CacheSerializerTypeAllowlistTest.java` + `security/access/MembershipSerializerRoundTripTest.java` (re-derive) | test | transform | themselves | exact |
| `core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java` (l.144-158) + `KeycloakDeprovisionService.java` | service | request-response | itself | exact |
| `core-java/src/test/java/.../tenant/keycloak/KeycloakAdminClientTest.java` (by-content assertion) | test | request-response | itself, lines 120-141 | exact |
| `core-java/build.gradle.kts` + root `build.gradle.kts` (plugin, starters, pins, `spring-security-access`) | config | — | itself, lines 285-297 (`jvmArgumentProviders` for production classpath) | exact |
| `core-java/src/main/resources/application*.yml` (18 key renames) | config | — | RESEARCH §"Config keys — the 18" | n/a (mechanical) |
| Census harness (BOOT4-02, explicit vs classic `ConditionEvaluationReport`) | test | batch | none | **no analog** |
| Static Jackson-import check (BOOT4-04) | gate | batch | `scripts/check-no-create-extension.sh` (here-string + VOID shape) | role-match |
| Docs / horizons / metrics (`CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`, `infra/dependency-horizons.yaml`, `scripts/check-doc-versions.sh`, `docs/metrics.json`) | config/docs | — | existing gates per RESEARCH §BOOT4-14 | n/a |

## Pattern Assignments

### `scripts/check-boot-config-keys.sh` (CI gate, batch)

**Analog:** `scripts/check-no-create-extension.sh`

**Header contract** (lines 1-46): a block comment stating WHY, then an explicit exit-code table — copy verbatim in shape:
```bash
# Exit codes:
#   0  no migration creates an extension
#   1  at least one does — named, with its line
#   2  VOID — the migration directory is missing, or the scan found NO FILES to check
#
# 2 is load-bearing. A zero-file scan reporting "clean" is the vacuous shape this repo has been
# bitten by repeatedly: "I found nothing" must never render as "there is nothing".
```
plus the two documented hazards (gate must not fire on its own definition; `| grep -q` under pipefail inverts — here-strings only, `grep -c ... || true`).

**Prologue / VOID helpers / overridable input** (lines 48-64):
```bash
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# Overridable so the VOID direction is testable against an empty directory without inventing a
# second code path. Defaults to the real migration directory.
MIGRATION_DIR="${MIGRATION_DIR:-$REPO_ROOT/core-java/src/main/resources/db/migration}"

fail() { echo "FAIL: $*" >&2; exit 1; }
void() { echo "VOID: $*" >&2; exit 2; }
...
[ -d "$MIGRATION_DIR" ] || void "migration directory not found: $MIGRATION_DIR"
mapfile -t MIGRATIONS < <(find "$MIGRATION_DIR" -maxdepth 1 -type f -name '*.sql' | sort)
COUNT="${#MIGRATIONS[@]}"
[ "$COUNT" -gt 0 ] || void "no .sql files found ... — refusing to report clean over an empty scan"
```
Apply to the new gate: overridable `RESULTS_DIR` defaulting to `core-java/build-local/test-results/test` (NEVER `core-java/build/` — stale; see `check-jacoco-coverage.sh` lines 176-206 `BUILD_DIR="${JACOCO_BUILD_DIR:-$REPO_ROOT/core-java/build-local}"`). Delete the stale `TEST-*ConfigKeyContractTest.xml` before invoking `./gradlew :core-java:test --tests '*ConfigKeyContractTest' --rerun`; missing XML or `tests="0"` = `void`; `failures`/`errors` > 0 = `fail`. Capture `rc` on the same line as the gradle call.

**Wiring** (`.github/workflows/ci-cd.yaml` lines 945-958) — copy the step shape, including the "why this is static, belongs here" comment:
```yaml
      - name: Assert no migration creates a PostgreSQL extension (33-02)
        run: |
          chmod +x ./scripts/check-no-create-extension.sh
          ./scripts/check-no-create-extension.sh
```
Put the new step in the core-java unit job (it needs Gradle + JDK). `check-gate-enforcement.sh` passes once a workflow references the script; do NOT add it to `scripts/gates/gate-enforcement.conf` (that file is only for gates that cannot run on a hosted runner — header lines 1-17).

---

### `ConfigKeyContractTest.java` (engine for BOOT4-11)

**Analog:** `core-java/src/test/java/uk/jtoye/core/storage/WorkloadIdentityCredentialBuildTest.java`

**Fail-closed production-classpath loader** (lines 49-75):
```java
private static final String PRODUCTION_CLASSPATH_PROPERTY = "jtoye.productionRuntimeClasspath";
...
@BeforeAll
static void productionRuntimeLoader() throws MalformedURLException {
    String classpath = System.getProperty(PRODUCTION_CLASSPATH_PROPERTY);
    // Fail closed: without the property this class could only test the test classpath.
    assertThat(classpath).as("-D" + PRODUCTION_CLASSPATH_PROPERTY
            + " is set by core-java/build.gradle.kts tasks.test; run this class through Gradle").isNotBlank();
    List<URL> urls = new ArrayList<>();
    for (String entry : classpath.split(File.pathSeparator)) {
        urls.add(Path.of(entry).toUri().toURL());
    }
    production = new URLClassLoader("production-runtime", urls.toArray(URL[]::new),
            ClassLoader.getPlatformClassLoader());
}
```
Load `META-INF/spring-configuration-metadata.json` + `additional-spring-configuration-metadata.json` via `production.getResources(...)` into `ConfigurationMetadataRepositoryJsonBuilder`. Include a positive-control test (analog: `jnaIsNotOnTheProductionRuntimeClasspath` asserts the loader holds the production jars before trusting an absence) — e.g. assert `spring.datasource.url` is known, and that `spring.bogus.key` and a Boot-3 key (`server.error.include-message`) are reported. Zero keys checked = fail.

**Build wiring already exists** — `core-java/build.gradle.kts` lines 285-297:
```kotlin
val productionRuntimeClasspath = configurations.runtimeClasspath.get()
inputs.files(productionRuntimeClasspath).withPropertyName("productionRuntimeClasspath")
jvmArgumentProviders.add(CommandLineArgumentProvider {
    listOf("-Djtoye.productionRuntimeClasspath=" + productionRuntimeClasspath.asPath)
})
```
Reuse; do not add a second property. `spring-boot-configuration-metadata` goes on `testImplementation` only.

---

### D-05 suppression filter (middleware, request-response)

**Analog:** `core-java/src/main/java/uk/jtoye/core/security/TenantContextCleanupFilter.java` (whole file, 40 lines)
```java
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
...
public class TenantContextCleanupFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
```
Differences to apply:
- Use `shouldNotFilter(request)` to return true for every path other than `/.well-known/oauth-protected-resource` (and its `/**` suffix form, which Security 7.1 also matches); for the match, `response.sendError(404)` or write a 404 RFC 7807 body consistent with `GlobalExceptionHandler`'s 404 (Boot 3.5 today returns the 404 problem document; match by content on the current runtime before choosing).
- Register IN the security chain, not as a `@Component` servlet filter: `TenantContextCleanupFilter`/`JwtTenantFilter` are `@Component` (and therefore also auto-registered by Boot as servlet filters). For D-05 either make it a plain class instantiated in `SecurityConfig`, or keep `@Component` and add a `FilterRegistrationBean` with `setEnabled(false)` (no existing analog in the repo — grep found zero `FilterRegistrationBean`).

**Registration analog** — `SecurityConfig.java` lines 259-266:
```java
TenantFilter tenantFilter = tenantFilterProvider.getIfAvailable();
if (tenantFilter != null) {
    http.addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class);
}
http.addFilterAfter(jwtTenantFilter, BearerTokenAuthenticationFilter.class);
return http.build();
```
New line: `http.addFilterBefore(suppressionFilter, OAuth2ProtectedResourceMetadataFilter.class);` (Security 7.1 class). Fail arm per D-05: remove that line → path returns 200 with `tls_client_certificate_bound_access_tokens: true`.

---

### `ProblemDetailAuthenticationEntryPoint.java` (D-04)

**Analog:** itself. Keep the wrap-the-delegate structure (lines 53-86):
```java
private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
...
delegate.commence(request, response, authException);
if (response.isCommitted()) { return; }
HttpStatus status = HttpStatus.resolve(response.getStatus());
...
response.getWriter().write(objectMapper.writeValueAsString(problem));
```
Changes: pass `delegate.commence(request, new HttpServletResponseWrapper(response){ setHeader/addHeader rewrite WWW-Authenticate dropping resource_metadata }, authException)`; switch `com.fasterxml.jackson.databind.ObjectMapper` (line 3) to Boot's Jackson-3 `tools.jackson.databind.json.JsonMapper`. Keep the class Javadoc style (API-10 rationale) and add a D-04/D-05 paragraph.

### `UnauthenticatedProblemDetailIntegrationTest.java` (strengthen + D-05 test)

**Analog:** itself (lines 36-74). Testcontainers + MockMvc scaffold to copy for a new D-05 test:
```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class UnauthenticatedProblemDetailIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test").withUsername("test").withPassword("test");
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }
```
Replace `header().exists("WWW-Authenticate")` (lines 61, 72 — currently cannot fail on the D-04 defect) with exact-value assertions: missing token → `"Bearer"`; garbage token → starts with `Bearer error="invalid_token"` and `doesNotContain("resource_metadata")`. Add `get("/.well-known/oauth-protected-resource")` → `status().isNotFound()` plus the existing "permitted route still reachable" control pattern (lines 76-80). Note: `AutoConfigureMockMvc` package moves under Boot 4 (`org.springframework.boot.webmvc.test.autoconfigure`) — see spike patch.

---

### Idempotency legacy-hash replay (NOSUPERUSER Testcontainers, BOOT4-08)

**Analog A (NOSUPERUSER role downgrade):** `core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java` lines 84-128:
```java
jdbc.execute("DO $$ BEGIN " +
        "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
        "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
        "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
        "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
        "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
        "  END IF; " +
        "END $$");
...
// Seed a COMPLETED idempotency_keys row for tenant A (as superuser ...)
TenantContext.set(tenantA);
jdbc.update("INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash, response_status, response_body) "
        + "VALUES (?, ?, ?, ?, ?, ?)", tenantA, ENDPOINT, SHARED_KEY, "hash-a", 201, "{...}");
...
private void dropSuperuserForTransaction() {
    jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
}
```
For BOOT4-08: seed `request_hash` with a GOLDEN literal captured on `main` under Jackson 2 (sha256 of the Jackson-2 bytes for a fixed request DTO), then call `IdempotencyService` under `@Transactional` + `SET LOCAL ROLE` → assert replay status 201 and the stored body, not `IdempotencyPayloadMismatchException`. Fail arm: fingerprint with Boot's default `JsonMapper` (alphabetical sort on) → 422.

**Analog B (service-level call + Javadoc falsifiability note):** `IdempotencyServiceUnstoredResponseIntegrationTest.java` lines 52-135 — `@Autowired IdempotencyService`, `TenantContext.set` in `@BeforeEach`, `reservationRow(key)` read-back, `assertThatThrownBy(...).isInstanceOf(IdempotencyPayloadMismatchException.class)`.

**Production seam already exists** — `IdempotencyService.java` lines 155-172 and 237-242: a second accepted hash via `legacyRequestBody`:
```java
String storedHash = (String) row.get("request_hash");
if (storedHash != null && !storedHash.equals(requestHash)
        && (legacyRequestBody == null || !storedHash.equals(sha256Hex(serialize(legacyRequestBody))))) {
    throw new IdempotencyPayloadMismatchException(...);
}
```
If BOOT4-08 is solved in code (not as a recorded deploy step), extend THIS seam (e.g. a legacy-serializer hash that reproduces Jackson-2 bytes) rather than adding a parallel path. `serialize`/`deserialize` (lines 267-281) catch `JsonProcessingException` — under Jackson 3 that becomes unchecked `JacksonException`; keep the `IllegalStateException` wrapping.

---

### `RabbitMessageCompatibilityTest.java` (BOOT4-06)

**Analog:** `core-java/src/test/java/uk/jtoye/core/config/RabbitMQConfigMessageConverterTest.java`

**Message construction with `__TypeId__`** (lines 68-84):
```java
MessageProperties props = new MessageProperties();
props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
props.setHeader("__TypeId__", className);
Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), props);
converter.fromMessage(message);
```
**Payload discovery by classpath scan** (lines 87-121, `discoverMultiHandlerPayloadPackages` / `handlerPayloadPackagesOf`) — reuse to enumerate every `@RabbitHandler` payload type, so the compatibility test cannot silently skip a new event. For each: serialize a populated instance with `new Jackson2JsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES)` (test scope), `fromMessage` with `RabbitMQConfig`'s new `JacksonJsonMessageConverter`, assert equality. Converter under test comes from `RabbitMQConfig.java` lines 404-426 (`TRUSTED_PAYLOAD_PACKAGES`, `jsonMessageConverter()`); keep that exact-list contract and the existing `rejectsClassOutsideAllowlist` / `allowlistIsNotTrustAll` tests green.

---

### `CacheConfig.java` + `CacheSerializerTypeAllowlistTest.java` (BOOT4-07)

**Analog:** themselves. `CacheConfig.java` lines 109-190: `CACHE_TYPE_ID_PREFIXES` (l.140) folded into `BasicPolymorphicTypeValidator.builder().allowIfSubType(prefix)` (l.152-153), `activateDefaultTyping(...)` (l.182), `jsonRedisSerializer()` (l.187). Port to `GenericJacksonJsonRedisSerializer.builder().enableDefaultTyping(validator)`; keep the static factory method so the test keeps calling the production serializer. Add a versioned key prefix (`RedisCacheConfiguration.prefixCacheNameWith("v4:")` or `computePrefixWith`) per RESEARCH Pattern 5.

Test structure to preserve (`CacheSerializerTypeAllowlistTest.java`): round-trip tests (l.112-130), explicit type-id-in-bytes test (l.143-160 — re-derive the expected id from LIVE Jackson-3 bytes), refusal arms (l.186-218: outside-allowlist and gadget base stay refused). Imports `com.fasterxml.jackson.databind.exc.InvalidTypeIdException` (l.1-6) move to `tools.jackson.databind.exc`.

---

### `KeycloakAdminClient.java` / `KeycloakAdminClientTest.java` (BOOT4-05)

**Analog:** themselves. Production `setUserEnabled` lines 144-158 (`ObjectNode userRep.deepCopy(); payload.put("enabled", enabled); restClient.put()...body(payload)`) → `tools.jackson.databind.node.ObjectNode`. Test lines 53-57 bind `MockRestServiceServer` to the `RestClient.Builder`; lines 120-141 assert via `jsonPath("$.enabled").value(false)`, `$.username`, `$.firstName`. Strengthen: capture the body, parse, assert `enabled == false`, `id`/`username`/`attributes.tenant_id` preserved, and `doesNotContainKeys("nodeType","array","bigDecimal","containerNode")` — the measured garbage signature. NOTE: the existing jsonPath assertions passed only because they never looked for the garbage keys; make sure the new assertion turns red against a Jackson-2 `ObjectNode` (fail arm).

## Shared Patterns

### Exit-code contract for every new gate script
**Source:** `scripts/check-no-create-extension.sh` lines 26-64. 0 pass / 1 fail (named) / 2 VOID on missing input or empty scan; here-strings, never `| grep -q`; `rc` captured on the same line.

### Testcontainers integration test scaffold
**Source:** `UnauthenticatedProblemDetailIntegrationTest.java` lines 36-51 (`@Tag("testcontainers")` routes it to `integrationTest`, excluded from `test`). Always `IntegrationTestSupport.registerPostgresTestProperties`.

### NOSUPERUSER RLS proof
**Source:** `IdempotencyKeysRlsPolicyIntegrationTest.java` lines 84-128. Seed as superuser, then `SET LOCAL ROLE rls_test_role` inside the test transaction; any tenant-data proof must run this way (CLAUDE.md V67 history: SUPERUSER bypasses FORCE RLS).

### Class Javadoc as evidence record
**Source:** `ProblemDetailAuthenticationEntryPoint.java` lines 18-46, `IdempotencyServiceUnstoredResponseIntegrationTest.java` lines 30-51. Every new class states the defect, what is preserved, what is deliberately not done, and which arm proves falsifiability.

### Jackson 3 import rule (BOOT4-04)
`com.fasterxml.jackson.annotation.*` stays; `com.fasterxml.jackson.databind|core` → `tools.jackson.*`. Do not sed-replace wholesale (RESEARCH anti-patterns).

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| Auto-configuration census harness (BOOT4-02) | test | batch | No existing test compares `ConditionEvaluationReport` positive matches across two builds; use RESEARCH Pattern 1. |
| `FilterRegistrationBean` disabling auto-registration of a security-chain filter (only if the D-05 filter is a `@Component`) | config | — | Zero `FilterRegistrationBean` in `core-java/src/main` today. Prefer a non-`@Component` filter constructed in `SecurityConfig`. |

## Metadata

**Analog search scope:** `scripts/`, `scripts/gates/`, `.github/workflows/ci-cd.yaml`, `core-java/src/main/java/uk/jtoye/core/{security,config,common/idempotency,tenant/keycloak}`, `core-java/src/test/java/uk/jtoye/core/{security,config,common/idempotency,tenant/keycloak,storage}`, `core-java/build.gradle.kts`
**Files scanned:** ~25
**Pattern extraction date:** 2026-10-04

# Phase 1: API Versioning -- Backend - Research

**Researched:** 2026-04-07
**Domain:** Spring Boot 3.4.2 URL path prefix versioning via WebMvcConfigurer
**Confidence:** HIGH

## Summary

This phase adds `/api/v1/` prefix to all Spring Boot REST endpoints using `PathMatchConfigurer.addPathPrefix()` within an existing `WebMvcConfigurer`. The approach is well-supported in Spring Framework 6.1.x (used by Spring Boot 3.4.2) and requires no new dependencies. The key challenge is building a correct predicate that includes the 7 versioned controllers while excluding the 4 exempt controllers and the `CoreApplication` root controller.

The existing `WebConfig.java` already implements `WebMvcConfigurer` for rate limiting. The `configurePathMatch()` override should be added to this same class (or a new config class -- implementer's discretion). SpringDoc 2.8.6 automatically picks up path prefixes applied via `addPathPrefix`, so Swagger UI will show `/api/v1/` paths without manual configuration. Security patterns in `SecurityConfig.java` must be updated to permit `/api/v1/` prefixed paths for authenticated requests.

**Primary recommendation:** Use `HandlerTypePredicate.forBasePackage()` targeting the 7 packages that contain versioned controllers. This is simpler and more maintainable than a negation-based approach because it explicitly lists what IS versioned rather than trying to exclude what is not.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Use `WebMvcConfigurer.configurePathMatch()` with a package-based predicate targeting `@RestController` classes to apply `/api/v1/` prefix automatically. Do NOT rewrite individual `@RequestMapping` annotations.
- **D-02:** The following paths remain unversioned (no /api/v1/ prefix):
  - `/public/payments` -- Stripe webhook (cannot change atomically with deployment)
  - `/public/**` -- PublicStorefrontController (unauthenticated storefront endpoints)
  - `/health`, `/actuator/**` -- Infrastructure health checks
  - `/swagger-ui/**`, `/v3/api-docs/**` -- API documentation
  - `/dev/tenants` -- DevTenantController (dev-only, not production API)
- **D-03:** The WebMvcConfigurer predicate must exclude controllers for exempt paths. Controllers in the `storefront`, `payment`, `tenant` (dev), and `controller` (SecurityHealthController) packages should be excluded from the prefix.
- **D-04:** Update ALL existing MockMvc test paths to use `/api/v1/` prefix. Clean break -- no backward compatibility redirects.
- **D-05:** Big-bang rollout. WebMvcConfigurer applies to all non-exempt controllers at once. All test updates in the same PR. SecurityConfig `permitAll()` patterns updated to match new paths.

### Claude's Discretion
- Implementation details of the `WebMvcConfigurer` predicate (package-based vs annotation-based filtering)
- Whether to use a custom annotation to mark exempt controllers or rely on package structure
- SecurityConfig pattern updates for the new /api/v1/ prefix paths

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| APIV-01 | All REST endpoints prefixed with /api/v1/ via WebMvcConfigurer path matching | `PathMatchConfigurer.addPathPrefix("/api/v1", predicate)` in `configurePathMatch()` -- verified in Spring Framework 6.1.x docs |
| APIV-04 | Stripe webhook and WhatsApp webhook paths exempted from versioning | Predicate excludes `payment`, `storefront`, `tenant`, `controller` packages; `/public/**` stays unversioned |
| APIV-05 | OpenAPI/Swagger docs reflect /api/v1/ paths | SpringDoc 2.8.6 automatically respects `addPathPrefix` -- Swagger UI shows prefixed paths with no extra config |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Framework (spring-webmvc) | 6.1.x (managed by Boot 3.4.2) | `PathMatchConfigurer.addPathPrefix()` API | Built-in Spring MVC feature, no dependencies needed |
| SpringDoc OpenAPI | 2.8.6 (existing) | Auto-discovers path prefix from WebMvcConfigurer | Already installed, automatically reflects prefix changes |

### Supporting
No new dependencies required. This phase uses only existing Spring Framework APIs.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `addPathPrefix()` | `server.servlet.context-path=/api/v1` | Moves ALL endpoints including /health, /actuator, /swagger-ui -- breaks Docker healthchecks and monitoring |
| Package predicate | Custom `@Versioned` annotation | Extra ceremony, every new controller must remember the annotation |
| Package predicate | `forAnnotation(RestController.class)` + negate exempts | Fragile: any new @RestController auto-gets prefix, even if exempt |

## Architecture Patterns

### Controller Package Layout (existing)
```
uk.jtoye.core/
  CoreApplication.java          # @RestController: /, /health -- EXEMPT (root package)
  shop/ShopController.java      # /shops -- VERSION
  product/ProductController.java # /products -- VERSION
  order/OrderController.java    # /orders -- VERSION
  customer/CustomerController.java # /customers -- VERSION
  finance/FinancialTransactionController.java # /financial-transactions -- VERSION
  gdpr/GdprController.java      # /gdpr/customers -- VERSION
  sync/SyncController.java      # /sync -- VERSION
  payment/PaymentController.java # /public/payments -- EXEMPT
  storefront/PublicStorefrontController.java # /public/** -- EXEMPT
  tenant/DevTenantController.java # /dev/tenants -- EXEMPT
  controller/SecurityHealthController.java # /health/security -- EXEMPT
```

### Pattern: Package-Based addPathPrefix Predicate

**What:** Use `HandlerTypePredicate.forBasePackage()` listing the 7 packages that contain versioned controllers.

**Why this over exclusion-based:** The positive-list approach is safer. If a new controller is added in a new package, it does NOT automatically get the prefix -- the developer must explicitly add the package. This is better than a catch-all that might accidentally version a webhook endpoint.

**Example:**
```java
// Source: Spring Framework PathMatchConfigurer docs + codebase analysis
@Override
public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix("/api/v1",
        HandlerTypePredicate.forBasePackage(
            "uk.jtoye.core.shop",
            "uk.jtoye.core.product",
            "uk.jtoye.core.order",
            "uk.jtoye.core.customer",
            "uk.jtoye.core.finance",
            "uk.jtoye.core.gdpr",
            "uk.jtoye.core.sync"
        )
    );
}
```

### Pattern: SecurityConfig Update

**What:** Add `/api/v1/**` authenticated path alongside existing patterns. The existing `anyRequest().authenticated()` already catches versioned paths, but if any explicit matchers reference old paths (e.g., specific role checks added later), they need updating.

**Current SecurityConfig analysis:**
```java
.requestMatchers("/", "/health", "/actuator/health", "/actuator/info").permitAll()
.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
.requestMatchers("/public/**").permitAll()
.anyRequest().authenticated()
```

The `.anyRequest().authenticated()` wildcard means versioned paths like `/api/v1/shops` are already authenticated by default. No explicit SecurityConfig changes are needed for authentication. However, the rate limiter in `WebConfig.java` excludes certain paths -- it must also exclude infrastructure paths that remain unversioned.

### Pattern: WebConfig Rate Limiter Update

**What:** The rate limiter `addPathPatterns("/**")` already catches everything. But its `excludePathPatterns` may need adjustment. Currently it excludes `/actuator/**`, `/health`, `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`. These are all unversioned, so no change needed. Versioned paths will be rate-limited as expected.

### Anti-Patterns to Avoid
- **Rewriting individual @RequestMapping annotations:** Violates D-01. The whole point of `addPathPrefix` is to avoid touching every controller.
- **Using server.servlet.context-path:** Moves ALL paths including health, actuator, swagger. Breaks Docker/K8s health probes.
- **Negative predicate (version everything, then exclude):** A new controller added in a new package auto-gets the prefix, which may be wrong for webhooks or public endpoints.
- **Adding backward-compatibility redirects:** Violates D-04. Clean break, update tests.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Path prefix for controllers | Custom filter that rewrites URLs | `PathMatchConfigurer.addPathPrefix()` | Spring-native, works with Swagger, security, tests automatically |
| Swagger path updates | Manual OpenAPI path rewriting | SpringDoc auto-discovery | SpringDoc reads prefix from HandlerMapping, zero config |
| Test path constant management | Shared constant for "/api/v1" prefix | Direct string in test `mockMvc.perform(get("/api/v1/shops"))` | Constants add indirection; tests should show the actual URL being called |

## Common Pitfalls

### Pitfall 1: CoreApplication Has @RestController
**What goes wrong:** `CoreApplication.java` is annotated with `@RestController` and defines `/` (redirect to Swagger) and `/health`. If the predicate accidentally matches the root package `uk.jtoye.core`, these endpoints move to `/api/v1/` and `/api/v1/health`, breaking Docker healthchecks and the Swagger redirect.
**Why it happens:** `CoreApplication` lives in `uk.jtoye.core` (the root package). A predicate using `forBasePackage("uk.jtoye.core")` would match ALL controllers including exempt ones.
**How to avoid:** Use specific sub-packages in the predicate (`uk.jtoye.core.shop`, `uk.jtoye.core.order`, etc.) -- NOT the root package.
**Warning signs:** `/health` returns 404, Docker container marked unhealthy.

### Pitfall 2: Tests Use Direct Service Calls, Not MockMvc
**What goes wrong:** Some integration tests (e.g., `OrderControllerIntegrationTest`) call `orderService.createOrder()` directly -- not `mockMvc.perform()`. These tests pass regardless of path changes because they bypass HTTP routing entirely. The developer thinks "all tests pass" but the endpoint paths are never actually verified.
**Why it happens:** Testcontainers-based integration tests often test service logic, not HTTP routing.
**How to avoid:** Identify which tests use MockMvc (path-sensitive) vs direct service calls (path-insensitive). Only MockMvc tests need path updates. There are 27 `mockMvc.perform()` calls across 4 active test files (Customer, FinancialTransaction, Shop, Sync integration tests).
**Warning signs:** All tests pass but `/api/v1/shops` returns 404 in the running app.

### Pitfall 3: SpringDoc Server URL Mismatch
**What goes wrong:** `OpenApiConfig.java` hardcodes `Server.url("http://localhost:8080")`. With `addPathPrefix`, the API paths in Swagger become `/api/v1/shops` but the "Try it out" button sends to `http://localhost:8080/api/v1/shops` which is correct. However, if someone later changes to `context-path` approach, the server URL would need updating.
**Why it happens:** Confusion between `addPathPrefix` (controller-level) and `context-path` (servlet-level).
**How to avoid:** Verify Swagger "Try it out" works after the change. The `addPathPrefix` approach does NOT require changing Server URLs.

### Pitfall 4: Disabled Test File Contains Old Paths
**What goes wrong:** `RateLimitIntegrationTest.java.disabled` contains 12 `mockMvc.perform()` calls with paths like `/api/health-test` and `/api/test-endpoint`. If re-enabled later, these paths will be wrong.
**Why it happens:** Disabled test files are invisible to test runners and easy to forget.
**How to avoid:** Update paths in the disabled test file too, or add a comment noting it needs path updates if re-enabled.

## Code Examples

### Adding Path Prefix to Existing WebConfig
```java
// Source: Spring Framework 6.1.x PathMatchConfigurer docs
// File: core-java/src/main/java/uk/jtoye/core/config/WebConfig.java

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1",
            HandlerTypePredicate.forBasePackage(
                "uk.jtoye.core.shop",
                "uk.jtoye.core.product",
                "uk.jtoye.core.order",
                "uk.jtoye.core.customer",
                "uk.jtoye.core.finance",
                "uk.jtoye.core.gdpr",
                "uk.jtoye.core.sync"
            )
        );
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/actuator/**",
                    "/health",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
                );
    }
}
```

### MockMvc Test Path Update Pattern
```java
// BEFORE:
mockMvc.perform(post("/customers")
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json))
    .andExpect(status().isCreated());

// AFTER:
mockMvc.perform(post("/api/v1/customers")
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json))
    .andExpect(status().isCreated());
```

### Verifying Exempt Paths Still Work
```java
// Health endpoint stays at /health (no prefix)
mockMvc.perform(get("/health"))
    .andExpect(status().isOk())
    .andExpect(content().string("OK"));

// Public storefront stays at /public/** (no prefix)
mockMvc.perform(get("/public/shops/some-slug"))
    .andExpect(status().isOk());
```

## Test File Inventory -- MockMvc Paths to Update

| Test File | mockMvc Calls | Paths Used |
|-----------|---------------|------------|
| `ShopControllerIntegrationTest.java` | 7 | `/health`, `/shops`, `/shops` (POST/GET) |
| `CustomerControllerIntegrationTest.java` | 11 | `/customers` (POST/GET/PUT/DELETE) |
| `FinancialTransactionControllerIntegrationTest.java` | 8 | `/financial-transactions` (POST/GET) |
| `SyncControllerIntegrationTest.java` | 1 | `/sync/batch` (POST) |
| `RateLimitIntegrationTest.java.disabled` | 12 | `/api/health-test`, `/api/test-endpoint`, `/health`, `/actuator/health` |

**Total active mockMvc calls to update:** 27 across 4 files. The `/health` call in ShopControllerIntegrationTest should NOT be prefixed (it tests the exempt health endpoint).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual `@RequestMapping("/api/v1/...")` | `PathMatchConfigurer.addPathPrefix()` | Spring Framework 5.1 (2018) | No need to touch individual controllers |
| `server.servlet.context-path` | `addPathPrefix` with predicate | Same era, but predicate support evolved | Selective versioning possible |
| Spring Boot 4 `spring.mvc.api-version` | NOT available in Boot 3.4.2 | Spring Boot 4 / Framework 7 (2025) | Do NOT use -- incompatible with current stack |

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers 1.21.3 |
| Config file | `core-java/build.gradle.kts` (test dependencies), `application-test.yml` |
| Quick run command | `./gradlew :core-java:test --tests "uk.jtoye.core.integration.*"` |
| Full suite command | `./gradlew :core-java:test` |

### Phase Requirements -> Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| APIV-01 | Versioned endpoints respond under /api/v1/ | integration | `./gradlew :core-java:test --tests "uk.jtoye.core.integration.ShopControllerIntegrationTest"` | Yes (needs path update) |
| APIV-04 | Exempt paths respond at original URLs | integration | `./gradlew :core-java:test --tests "uk.jtoye.core.integration.ShopControllerIntegrationTest.healthEndpointReturns200"` | Partial (health test exists in ShopControllerIntegrationTest) |
| APIV-05 | Swagger shows /api/v1/ paths | smoke | Manual: visit `/swagger-ui.html`, verify paths show `/api/v1/` prefix | No automated test |

### Sampling Rate
- **Per task commit:** `./gradlew :core-java:test --tests "uk.jtoye.core.integration.*"`
- **Per wave merge:** `./gradlew :core-java:test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] Add test asserting `/api/v1/shops` returns 200 (versioned path works)
- [ ] Add test asserting `/shops` returns 404 (old path no longer works -- verifies clean break)
- [ ] Add test asserting `/health` returns 200 (exempt path still works)
- [ ] Add test asserting `/public/shops/{slug}` returns 200 (exempt storefront still works)

## Open Questions

1. **Where to place configurePathMatch override?**
   - What we know: `WebConfig.java` already implements `WebMvcConfigurer`. Adding `configurePathMatch()` there is natural.
   - What's unclear: Whether to keep it in `WebConfig` or create a new `ApiVersioningConfig` class for separation of concerns.
   - Recommendation: Add to existing `WebConfig.java` -- it is already the MVC configuration class. A separate class adds no value for a single method.

2. **Should disabled RateLimitIntegrationTest paths be updated?**
   - What we know: The file is `.disabled` and not executed.
   - What's unclear: Whether it will be re-enabled soon.
   - Recommendation: Update paths to maintain consistency. Low effort, prevents future confusion.

## Sources

### Primary (HIGH confidence)
- [Spring Framework PathMatchConfigurer docs](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/path-matching.html) -- `addPathPrefix()` API and predicate usage
- [Spring Framework HandlerTypePredicate javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/method/HandlerTypePredicate.html) -- `forBasePackage()`, `forAnnotation()`, `forAssignableType()` factory methods
- Codebase analysis -- all 11 controllers, SecurityConfig, WebConfig, OpenApiConfig, 4 integration test files verified directly

### Secondary (MEDIUM confidence)
- [Piotr Minkowski: Spring Boot Built-in API Versioning](https://piotrminkowski.com/2025/12/01/spring-boot-built-in-api-versioning/) -- confirms `spring.mvc.api-version` is Boot 4 only
- [Dan Vega: API Versioning in Spring Boot 4](https://www.danvega.dev/blog/spring-boot-4-api-versioning) -- confirms Framework 7 requirement

## Project Constraints (from CLAUDE.md)

- **Feature branches only:** Never commit to main -- use `feature/<name>` branch
- **No Co-Authored-By trailers** on commits
- **Always rebuild ALL Docker containers** after code changes before E2E testing
- **All new code requires tests** -- project standard is 310+ tests passing
- **E2E verification mandatory** -- must verify with browser-level testing, not just health checks

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- `addPathPrefix()` is a stable Spring Framework API since 5.1, verified in current docs
- Architecture: HIGH -- all controllers, packages, and test files verified directly from codebase
- Pitfalls: HIGH -- CoreApplication @RestController trap and test coverage gap verified from source

**Research date:** 2026-04-07
**Valid until:** 2026-05-07 (stable Spring Framework API, unlikely to change)

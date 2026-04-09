# Phase 1: API Versioning — Backend - Context

**Gathered:** 2026-04-07
**Status:** Ready for planning

<domain>
## Phase Boundary

Add /api/v1/ prefix to all Spring Boot REST endpoints using WebMvcConfigurer path matching. Exempt webhook, public storefront, infrastructure, and dev paths. Update all existing tests to use new paths. Big-bang rollout in a single PR.

</domain>

<decisions>
## Implementation Decisions

### Versioning Strategy
- **D-01:** Use `WebMvcConfigurer.configurePathMatch()` with a package-based predicate targeting `@RestController` classes to apply `/api/v1/` prefix automatically. Do NOT rewrite individual `@RequestMapping` annotations.

### Exemption Policy
- **D-02:** The following paths remain unversioned (no /api/v1/ prefix):
  - `/public/payments` — Stripe webhook (cannot change atomically with deployment)
  - `/public/**` — PublicStorefrontController (unauthenticated storefront endpoints)
  - `/health`, `/actuator/**` — Infrastructure health checks
  - `/swagger-ui/**`, `/v3/api-docs/**` — API documentation
  - `/dev/tenants` — DevTenantController (dev-only, not production API)
- **D-03:** The WebMvcConfigurer predicate must exclude controllers for exempt paths. Controllers in the `storefront`, `payment`, `tenant` (dev), and `controller` (SecurityHealthController) packages should be excluded from the prefix.

### Test Migration
- **D-04:** Update ALL existing MockMvc test paths to use `/api/v1/` prefix. Clean break — no backward compatibility redirects.

### Rollout Approach
- **D-05:** Big-bang rollout. WebMvcConfigurer applies to all non-exempt controllers at once. All test updates in the same PR. SecurityConfig `permitAll()` patterns updated to match new paths.

### Claude's Discretion
- Implementation details of the `WebMvcConfigurer` predicate (package-based vs annotation-based filtering)
- Whether to use a custom annotation to mark exempt controllers or rely on package structure
- SecurityConfig pattern updates for the new /api/v1/ prefix paths

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Security Configuration
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` — SecurityFilterChain with permitAll patterns that must be updated for /api/v1/ prefix

### Controllers to Version (apply /api/v1/ prefix)
- `core-java/src/main/java/uk/jtoye/core/shop/ShopController.java` — /shops
- `core-java/src/main/java/uk/jtoye/core/product/ProductController.java` — /products
- `core-java/src/main/java/uk/jtoye/core/order/OrderController.java` — /orders
- `core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java` — /customers
- `core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java` — /financial-transactions
- `core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java` — /gdpr/customers
- `core-java/src/main/java/uk/jtoye/core/sync/SyncController.java` — /sync
- `core-java/src/main/java/uk/jtoye/core/review/ReviewController.java` — /reviews (if exists)

### Controllers to Exempt (NO /api/v1/ prefix)
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentController.java` — /public/payments (Stripe webhook)
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` — /public/** (storefront)
- `core-java/src/main/java/uk/jtoye/core/tenant/DevTenantController.java` — /dev/tenants
- `core-java/src/main/java/uk/jtoye/core/controller/SecurityHealthController.java` — /health/security

### API Documentation
- `core-java/src/main/java/uk/jtoye/core/CoreApplication.java` — Has @RestController annotation (check if it needs exemption)

### Research
- `.planning/research/STACK.md` — API versioning approach rationale
- `.planning/research/PITFALLS.md` — Pitfalls #3 (coordinated 3-service change), #5 (Spring Boot 3.4.2 has no built-in API versioning)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `SecurityConfig.java` — already has pattern-based path matching with `requestMatchers()` — extend for /api/v1/ patterns
- `WebMvcConfigurer` pattern — research confirms `configurePathMatch()` is the correct approach for Spring Boot 3.4.2

### Established Patterns
- All controllers use `@RequestMapping("/path")` at class level with `@GetMapping`, `@PostMapping` etc at method level
- Security uses path-based matchers: `.requestMatchers("/public/**").permitAll()` and `.anyRequest().authenticated()`
- OpenAPI/Swagger configured via SpringDoc 2.8.6

### Integration Points
- `SecurityConfig.securityFilterChain()` — must update `requestMatchers` patterns to include `/api/v1/` prefixed paths
- SpringDoc/OpenAPI — should automatically pick up new paths if `WebMvcConfigurer` is used
- Frontend `NEXT_PUBLIC_API_URL` — currently points to `http://localhost:8080/api` (jest.setup.js) — Phase 2 handles frontend updates
- Go edge gateway — Phase 2 handles edge updates

</code_context>

<specifics>
## Specific Ideas

No specific requirements — standard WebMvcConfigurer approach as recommended by research.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-api-versioning-backend*
*Context gathered: 2026-04-07*

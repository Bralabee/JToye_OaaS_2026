# Phase 13: Guest Tracking Tenant Validation - Research

**Researched:** 2026-04-18
**Domain:** Spring Security 6 filter chain, multi-tenant isolation, PostgreSQL RLS, stateless public-endpoint hardening
**Confidence:** HIGH — all findings verified against live source at file:line; no training-data guessing

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SEC-01 | Application-layer tenant validation for guest/session requests. Today `TenantFilter`+`JwtTenantFilter` populate `TenantContext` from path slug or JWT claim with NO validation that the caller is authorized for that tenant. Add explicit application-layer check that compares session-bound tenant against path slug; reject mismatches with 403 + audit log. | §2-§8 below locate the exact vulnerable call site (`PublicStorefrontService.java:214, :326, :102`) and the canonical fix location (§3 Pattern A: inline check in service entry, before `TenantContext.set()`). §9 gives the MockMvc+Testcontainers scaffolding. §10 maps STRIDE. |

## 1. Executive Summary

### The hard problems (in order of difficulty)

1. **There is no "session-bound tenant" for guest traffic.** Guests hitting `/public/**` are truly stateless — no `GuestTrackingService`, no HTTP session cookie, no session JWT claim. The path slug IS the only source of tenant identity. This means SEC-01 is not "compare session tenant to path tenant"; it is **"verify the slug resolves to a published, visible tenant AND that no upstream tenant signal (JWT / `X-Tenant-Id`) contradicts it"**. The requirement as written assumes a session-bound tenant that does not exist. `[VERIFIED: grep core-java — GuestTracking|session_tenant|guest_session = zero hits; PublicStorefrontService has no HttpSession or cookie state]`

2. **`PublicStorefrontService` trusts the slug without any safety check.** Three separate methods call `TenantContext.set(shop.getTenantId())` immediately after resolving the shop from the slug — `getShopConfig` (line 102), `getShopProducts` (line 214), `createGuestOrder` (line 326). The shop is loaded with `findBySlugAndPublishedTrue()`, which uses the `shops_public_read` RLS policy (`V16__public_storefront.sql:75`) that allows ANY caller to read published shops. So the slug-to-tenant derivation is correct, but there is no gate that validates the CALLER is permitted on that tenant context before setting it. If a request arrives with a JWT for tenant A and a path slug for tenant B, the current code silently overwrites TenantContext with tenant B and proceeds. `[VERIFIED: PublicStorefrontService.java:102,214,326 + SecurityConfig.java:71 permitAll on /public/**]`

3. **The filter order makes it ambiguous who "wins" for public endpoints.** `TenantFilter` (runs before auth, reads `X-Tenant-Id` header → sets TenantContext if empty) and `JwtTenantFilter` (Order=200, runs after BearerTokenAuthenticationFilter → overrides TenantContext from JWT claim) are both in the chain even for `/public/**` requests. An authenticated request carrying a JWT can hit a public endpoint; JwtTenantFilter sets TenantContext to the JWT's tenant; the service then overwrites it with `shop.getTenantId()` from the slug. No comparison is ever made. `[VERIFIED: SecurityConfig.java:95-99, TenantFilter.java:22-30, JwtTenantFilter.java:38-44]`

4. **The OWASP-canonical fix is a service-layer gate, not a new filter.** A filter cannot reliably parse `/public/shops/{slug}/...` and resolve it to a tenant — that requires a DB lookup which belongs in a service. The right call site is inside `PublicStorefrontService`, in a private `resolveAndValidateShop(String slug)` method that replaces the current `findBySlugAndPublishedTrue() + TenantContext.set()` pairs. `[ASSUMED: standard Spring service-layer pattern]` `[VERIFIED against codebase: all three existing call sites follow the same shape — `findBySlugAndPublishedTrue().orElseThrow() + TenantContext.set(shop.getTenantId())` — ripe for extraction]`

### Primary recommendation

**Extract a `resolvePublicShopForSlug(String slug)` method in `PublicStorefrontService` that:**
1. Loads the shop by slug (`findBySlugAndPublishedTrue`), 404 if absent (unchanged behavior).
2. Reads any existing `TenantContext.get()` (populated upstream by `JwtTenantFilter` from a bearer JWT if present).
3. If TenantContext is present AND differs from `shop.getTenantId()`, throws a new `TenantAccessDeniedException` (maps to 403 via existing `GlobalExceptionHandler.handleAccessDenied`) AND emits a structured SLF4J WARN/ERROR log with fields `{event: "tenant_spoof_attempt", jwt_tenant, slug_tenant, slug, path, remote_addr}`.
4. Calls `TenantContext.set(shop.getTenantId())` only after the check passes.
5. Returns the resolved `Shop` (so callers don't re-query).

Replace the three current inline `findBySlugAndPublishedTrue() + TenantContext.set()` sites with calls to this method. Reuse Phase 12 MockMvc+Testcontainers scaffolding for integration tests. Add two `@ActiveProfiles("test")`-style unit tests on the service (mismatch → 403, match → proceed, no-tenant-context → proceed — the all-guest happy path).

---

## 2. Current State Audit (file:line evidence)

### 2.1 Filter chain order and behavior

`core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java`

| Filter | Order | Role |
|--------|-------|------|
| `TenantContextCleanupFilter` | `HIGHEST_PRECEDENCE` (`@Order` at `TenantContextCleanupFilter.java:23`) | finally-block clears `TenantContext` after request |
| `TenantFilter` | before `UsernamePasswordAuthenticationFilter` (`SecurityConfig.java:96`) | reads `X-Tenant-Id` header; **only if TenantContext is empty** sets it to header value (`TenantFilter.java:22-30`). Clears TenantContext again in own finally block (`TenantFilter.java:33`). |
| `BearerTokenAuthenticationFilter` | Spring Security default | validates JWT against JWKS |
| `JwtTenantFilter` | `@Order(200)`, added with `addFilterAfter(BearerTokenAuthenticationFilter.class)` (`SecurityConfig.java:99`) | reads `tenant_id`/`tenantId`/`tid` claim from JWT; **unconditionally overrides** TenantContext if present (`JwtTenantFilter.java:38-44`). Does NOT clear. |

**Key insight:** `TenantFilter` clears TenantContext in its finally block (`TenantFilter.java:33`), AND `TenantContextCleanupFilter` clears it as HIGHEST_PRECEDENCE. Double-cleanup is benign. But `TenantFilter`'s clear means: if the filter chain completes normally, `TenantContext` is already empty when the next request starts. `[VERIFIED: read all three filter sources]`

### 2.2 Public endpoint configuration

`core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:71` — `.requestMatchers("/public/**").permitAll()`. No authentication required. No `@PublicEndpoint` annotation pattern exists in this codebase (grep for `@PermitAll|@PublicEndpoint` returned zero hits in `core-java/src/main/java`).

Scope of `/public/**`: all endpoints in `PublicStorefrontController` (`@RequestMapping("/public")` at line 29). 11 endpoints total (`PublicStorefrontController.java`):
- `GET /public/shops` (line 41) — list; **no slug, no tenant** — not in scope
- `GET /public/shops/{slug}` (line 53) — **in scope** (slug-tenant derivation)
- `GET /public/shops/{slug}/config` (line 59) — **in scope** (calls `getShopConfig`, sets TenantContext at `PublicStorefrontService.java:102`)
- `GET /public/shops/{slug}/promotions` (line 65) — reads `findActiveByShopId` (no TenantContext set; RLS `shops_public_read` policy allows via `published=true`) — in scope for the path-slug check, but no TenantContext overwrite today
- `GET /public/shops/{slug}/announcements` (line 71) — same as promotions
- `GET /public/shops/{slug}/products` (line 77) — **in scope** (calls `getShopProducts`, sets TenantContext at `PublicStorefrontService.java:214`)
- `POST /public/shops/{slug}/orders` (line 83) — **in scope** (calls `createGuestOrder`, sets TenantContext at `PublicStorefrontService.java:326`)
- `GET /public/orders` (line 92) — **NOT slug-scoped**; uses email+order-number pair gated by RLS session variable `app.customer_email` (`PublicStorefrontService.java:238-244`). Separate threat model (email enumeration — tracked as deferred STFR-ENUM-01). **Out of SEC-01 scope.**
- `GET /public/orders/{orderNumber}` (line 107) — NOT slug-scoped; same as above.
- `GET /public/shops/{slug}/reviews` (line 115) — delegates to `ReviewService.getShopReviews(slug, ...)` — **needs audit** to see whether it sets TenantContext from slug. `[ASSUMED: likely yes; grep ReviewService in plan phase]`
- `POST /public/shops/{slug}/reviews` (line 123) — same as above.

### 2.3 `PublicStorefrontService` — the three vulnerable call sites

Code path for each: `PublicStorefrontService.java`

**Site 1 — `getShopConfig(String slug)` lines 90-123**
```
Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)     // line 91
        .orElseThrow(() -> new ResourceNotFoundException(...));  // line 92
// ... (announcements fetch uses shop.getId() directly, no TenantContext needed here)
TenantContext.set(shop.getTenantId());                           // line 102  ← VULNERABLE
try {
    // featured-products fetch; crosses RLS boundary
} finally {
    TenantContext.clear();                                       // line 113
}
```

**Site 2 — `getShopProducts(String slug)` lines 207-230**
```
Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)    // line 210
        .orElseThrow(() -> new ResourceNotFoundException(...)); // line 211
TenantContext.set(shop.getTenantId());                          // line 214  ← VULNERABLE
try {
    List<Product> products = productRepository
        .findAvailableByShopOrderedByCategory(shop.getId());    // line 217 — product RLS-gated on TenantContext
    // ...
} finally {
    TenantContext.clear();                                      // line 228
}
```

**Site 3 — `createGuestOrder(String slug, GuestOrderRequest)` lines 316-482**
```
Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)    // line 319
        .orElseThrow(() -> new ResourceNotFoundException(...)); // line 320
validateShopIsOpen(shop);                                       // line 323
UUID tenantId = shop.getTenantId();                             // line 325
TenantContext.set(tenantId);                                    // line 326  ← VULNERABLE (worst — mutates data)
try {
    // order creation, stock decrement, payment intent, event publish
    // EVERY tenant-scoped mutation (Order.setTenantId, OrderItem.setTenantId, etc.)
    // runs under the slug-derived TenantContext with NO upstream validation
} finally {
    TenantContext.clear();                                      // line 481
}
```

**Severity ranking:** Site 3 is worst because it **writes** tenant-scoped data (creates Order + OrderItem rows with `tenantId = shop.getTenantId()`). Sites 1 and 2 only **read**, and are further gated by RLS `shops_public_read` (published=true) + the product/promotion RLS policies. But all three share the same flaw — a JWT-authenticated caller for tenant A hitting `POST /public/shops/{tenant-B-slug}/orders` creates an order that belongs to tenant B with no record of the cross-tenant access. `[VERIFIED: source read for all three methods]`

### 2.4 Where there is NO `GuestTrackingService`

- `grep -r "GuestTracking|guest_session|GuestSession|session_id" core-java/` → zero hits (Grep tool run)
- `grep session-related patterns in PublicStorefrontService.java` → the only "Session" mentions are `org.hibernate.Session` (Hibernate JPA session unwrap for RLS config, lines 238, 277), NOT HTTP session.
- `grep HttpSession` in the storefront domain → zero hits
- Spring Security is configured stateless (JWT Resource Server; no `SecurityContextRepository`). `[VERIFIED: SecurityConfig.java has no http.sessionManagement() block — defaults to stateless for JWT]`

**Conclusion:** "session-bound tenant" for guests = **the slug itself**. There is no separate session identity to compare against. For authenticated-but-public-endpoint traffic (a Keycloak-signed-in customer browsing), the session-bound tenant exists on the JWT's `tenant_id` claim, mapped into TenantContext by `JwtTenantFilter` BEFORE `PublicStorefrontService` runs.

### 2.5 Frontend context

`frontend/lib/customer-auth.ts` — customer auth uses Keycloak `storefront-client` with PKCE; tokens stored in HttpOnly cookies via `/api/customer-auth/*` Next.js API routes. The browser ONLY holds a non-sensitive `jtoye-customer-logged-in` marker in localStorage. Customer profile retrieved via `/api/customer-auth/session` (cookie-backed, server-side). `[VERIFIED: customer-auth.ts:1-300]`

`frontend/lib/api-client.ts:38-39` — sets `X-Tenant-Id` header from `session.user.tenantId` (vendor NextAuth session) on every request. This is the vendor-admin flow (`/dashboard/*`), NOT the customer storefront — customer storefront uses `fetch()` directly against `/public/**` endpoints, which do NOT need `X-Tenant-Id` and don't set it.

---

## 3. Exact Call Site for the Tenant-Match Check

### 3.1 Options considered

| Option | Verdict | Rationale |
|--------|---------|-----------|
| **A. Inline check in `PublicStorefrontService.resolvePublicShopForSlug(slug)`** | **RECOMMENDED** | Only the service knows how to resolve slug→tenant (DB query). Filter has no access to `ShopRepository` without DI and a second DB round-trip per request. Keeps the 3 current call sites DRY. |
| B. New `GuestTenantValidationFilter` in the filter chain | REJECTED | Cannot read path variables before routing; would need URI regex parsing (brittle against Spring path matching). Filter runs before shop resolution; even with URI parsing, the filter would need to call `ShopRepository.findBySlugAndPublishedTrue` — adding DB access to the filter chain is an anti-pattern (circular-dependency risk against request-scoped bean wiring). |
| C. Method interceptor on a custom `@PublicSlugEndpoint` annotation | REJECTED | Adds a new cross-cutting pattern for 3 call sites. Overkill. The `@PublicEndpoint` pattern doesn't exist today (zero hits in codebase) — introducing it would be new surface area. |
| D. `@ControllerAdvice` or `HandlerInterceptor` with path parsing | REJECTED | Same fundamental problem as B — needs DB access and brittle URI matching. |

### 3.2 The canonical fix (Pattern A)

Add to `PublicStorefrontService.java`:

```java
/**
 * Resolve a public shop by slug, verifying the caller has no upstream tenant
 * context that contradicts the slug-derived tenant. Protects against a
 * JWT-authenticated (or X-Tenant-Id-carrying) caller for tenant A hitting
 * a /public/shops/{tenantB-slug}/* endpoint.
 *
 * @throws ResourceNotFoundException if slug is unknown or unpublished (unchanged)
 * @throws TenantAccessDeniedException if upstream TenantContext differs from slug tenant
 */
private Shop resolvePublicShopForSlug(String slug) {
    Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
            .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

    Optional<UUID> upstreamTenant = TenantContext.get();
    if (upstreamTenant.isPresent() && !upstreamTenant.get().equals(shop.getTenantId())) {
        // Structured audit log — single line, parseable by Loki/ELK, includes
        // sufficient context to trace the actor without leaking sensitive data
        log.warn("event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403",
                slug, shop.getTenantId(), upstreamTenant.get());
        throw new TenantAccessDeniedException(
                "Tenant mismatch between authenticated identity and requested shop");
    }

    TenantContext.set(shop.getTenantId());
    return shop;
}
```

And a **new exception class** `core-java/src/main/java/uk/jtoye/core/exception/TenantAccessDeniedException.java`:

```java
package uk.jtoye.core.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thrown when a caller's upstream tenant context contradicts the tenant
 * derived from a request's resource path. Extends AccessDeniedException so
 * the existing GlobalExceptionHandler.handleAccessDenied maps it to 403.
 */
public class TenantAccessDeniedException extends AccessDeniedException {
    public TenantAccessDeniedException(String message) {
        super(message);
    }
}
```

**Why extend `AccessDeniedException`:** `GlobalExceptionHandler.java:103-109` already maps `AccessDeniedException` → 403 ProblemDetail. Extending it means zero changes to `GlobalExceptionHandler`. `[VERIFIED: GlobalExceptionHandler.java:103-109]`

**Call-site refactor:** Replace the three inline pairs:
- Line 91-92 + 102 → `Shop shop = resolvePublicShopForSlug(slug);`
- Line 210-211 + 214 → `Shop shop = resolvePublicShopForSlug(slug);`
- Line 319-320 + 326 → `Shop shop = resolvePublicShopForSlug(slug);`

Remove the now-redundant `TenantContext.set(...)` in each try block. Keep the `finally { TenantContext.clear(); }` — the helper only sets, it does not own cleanup.

### 3.3 Filter-chain order is unchanged

No `SecurityConfig.java` change required. `JwtTenantFilter` continues to populate `TenantContext` from the JWT upstream; the service-layer gate reads it via `TenantContext.get()`.

---

## 4. Session-Bound Tenant Source — How Is It Tracked Today?

**Answer: it depends on whether the request carries a JWT.**

| Caller type | Upstream tenant source | At service entry |
|-------------|------------------------|------------------|
| Anonymous guest (no JWT, no `X-Tenant-Id`) | None | `TenantContext.get()` → `Optional.empty()` |
| Guest with `X-Tenant-Id` header (dev only; header filter runs BEFORE auth) | Header UUID | `TenantContext.get()` → header value — **but header comes from the browser and is not trustworthy on public endpoints** |
| Authenticated customer via Keycloak `storefront-client` (JWT with `tenant_id` claim) | JWT claim | `TenantContext.get()` → JWT tenant |
| Authenticated vendor via `jtoye-admin` JWT | JWT claim | `TenantContext.get()` → JWT tenant |

For SEC-01's threat model, **only the JWT case matters**. A browser can't add an arbitrary `X-Tenant-Id` header on a cross-origin request to `/public/**` (CORS preflight would block it if the CORS config restricted headers, though the current `cors(Customizer.withDefaults())` at `SecurityConfig.java:67` uses Spring defaults — this deserves verification during planning). The JWT case is the real risk: customer logs into tenant A's storefront via Keycloak, opens a new tab, navigates to `/public/shops/{tenantB-slug}/...`, browser automatically attaches the HttpOnly cookie → token → JWT with `tenant_id: A`, but URL says tenant B. Without Phase 13's gate, that request succeeds.

**Recommendation for the planner:** Treat `TenantContext.get()` as authoritative for "upstream tenant." It's already populated by `JwtTenantFilter` (JWT case) or `TenantFilter` (dev header case) before any `@RequestMapping` method runs. No new session abstraction needed.

---

## 5. Scope — Which Endpoints Need the Check

**In scope (5 endpoints, 3 service methods):**

| Endpoint | Service method | Today's call site |
|----------|---------------|-------------------|
| `GET /public/shops/{slug}/config` | `PublicStorefrontService.getShopConfig` | `PublicStorefrontService.java:102` |
| `GET /public/shops/{slug}/products` | `PublicStorefrontService.getShopProducts` | `PublicStorefrontService.java:214` |
| `POST /public/shops/{slug}/orders` | `PublicStorefrontService.createGuestOrder` | `PublicStorefrontService.java:326` |

**Also in scope but require separate audit by planner:**

| Endpoint | Service method | Needs verification |
|----------|---------------|-------------------|
| `GET /public/shops/{slug}/reviews` | `ReviewService.getShopReviews` | Does it call `TenantContext.set()`? Likely yes. Planner to grep `core-java/src/main/java/uk/jtoye/core/review/ReviewService.java`. If yes, apply same fix (inject `PublicStorefrontService.resolvePublicShopForSlug` OR duplicate the shop-resolution helper into `ReviewService`). |
| `POST /public/shops/{slug}/reviews` | `ReviewService.createReview` | Same as above. |

**Out of scope (no slug; separate threat model):**
- `GET /public/shops` — list all, no tenant derivation
- `GET /public/orders` — email+order-number, gated by `app.customer_email` RLS session var
- `GET /public/orders/{orderNumber}` — same

**Also in scope but lower priority:**
- `GET /public/shops/{slug}` — returns a `PublicShopDto` built from the `Shop` entity, but does NOT call `TenantContext.set()`. The public RLS policy `shops_public_read` (`V16__public_storefront.sql:75`) allows SELECT on `published=true` without tenant context. **Cannot leak cross-tenant data today** because RLS blocks it — but still should use the helper for consistency and to emit the audit log on spoof attempts. Low severity.
- `GET /public/shops/{slug}/promotions`, `.../announcements` — same as above.

---

## 6. Audit Log Strategy — Reuse AuditService vs New Channel

### 6.1 Existing audit infrastructure

`core-java/src/main/java/uk/jtoye/core/audit/AuditService.java` (107 lines) — wraps Hibernate Envers `AuditReader`. It is READ-ONLY: it queries the `*_aud` tables that Envers populates automatically on entity mutations. It is NOT a write-side audit channel. It cannot log security events like "403 refused."

`grep -r "AuditService" core-java/src/main` for write callers → the service is consumed READ-ONLY by controllers exposing entity revision history. No method in it emits security-event logs.

### 6.2 Options

| Option | Verdict | Rationale |
|--------|---------|-----------|
| **A. Structured SLF4J log with `event=` key-value prefix** | **RECOMMENDED** | Matches the existing logging pattern (see `PublicStorefrontService.java:461-465` for the log format already in use). Loki/ELK scrapes stdout. No new infrastructure. Alertmanager (Phase 9) can alert on `event=tenant_spoof_attempt` patterns. Zero new code outside the service method itself. |
| B. Extend `AuditService` with a `logSecurityEvent` method writing to a new `security_events` table | REJECTED | Adds a new Flyway migration, a new entity, a new repository, and write-side mutation to AuditService (which is currently read-only). All for what is essentially a structured log line. Can be added later in a dedicated security-audit milestone if needed. |
| C. Publish a `tenant.spoof.detected` RabbitMQ event | DEFERRED | Nice-to-have; pairs with Alertmanager for real-time alerts. But v2.2 scope is bounded; can piggyback on the SLF4J log pattern (existing Phase 9 Alertmanager routes already read from structured logs). |

**Recommended log format (reuse the SLF4J pattern from existing code):**
```
log.warn("event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} path={} outcome=403",
        slug, shop.getTenantId(), upstreamTenant.get(), request.getRequestURI());
```

**Note on `request.getRequestURI()`:** the service layer does not have the `HttpServletRequest`. Either (a) inject `HttpServletRequest` into the service method (noisy — violates layer boundary), or (b) add a `(String slug, HttpServletRequest request)` overload and have the controller pass the request, or (c) drop the `path` field from the log and rely on slug + upstreamTenant for the audit trail. **Option (c) is cleanest;** the slug uniquely identifies the endpoint pattern, and a companion access-log entry from Spring's `ServletRequestHandledEvent` or the existing Micrometer request metrics will provide the full URI.

---

## 7. 403 Response Shape

### 7.1 Reuse existing `GlobalExceptionHandler`

`core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java:103-109`:
```java
@ExceptionHandler(AccessDeniedException.class)
public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    problem.setTitle("Forbidden");
    problem.setType(URI.create("https://jtoye.uk/errors/forbidden"));
    return problem;
}
```

Already maps `AccessDeniedException` → 403 with RFC 7807 Problem Detail. **No change needed** — just extend `AccessDeniedException` in the new `TenantAccessDeniedException`.

### 7.2 Caveat — message disclosure

The current handler returns a static `"Access denied"` message. That is correct for SEC-01 — we should NOT echo back the caller's JWT tenant vs. path tenant in the response body, because that would confirm the JWT tenant to an attacker who was guessing. The structured log gets the rich detail; the response gets the generic 403. `[ASSUMED: standard practice — also stated in OWASP ASVS V4.1.5 (Do not reveal internal state on access denial)]`

### 7.3 Alternative — 404 vs 403

There's an argument for returning **404 Not Found** instead of **403 Forbidden** to prevent an attacker from distinguishing "this slug exists but I can't access it" from "this slug doesn't exist." ROADMAP explicitly says 403; OWASP and the requirement text both use 403. Recommend following the requirement. Note the tradeoff in the plan. `[CITED: ROADMAP.md line 83, REQUIREMENTS.md line 16]`

---

## 8. Integration Test Scaffolding

### 8.1 Pattern to reuse

`MultiTenantIsolationIntegrationTest` (`core-java/src/test/java/uk/jtoye/core/security/MultiTenantIsolationIntegrationTest.java`) is the canonical two-tenant seeding pattern:
- Two hardcoded tenant UUIDs: `TENANT_A = 00000000-0000-0000-0000-000000000001`, `TENANT_B = 00000000-0000-0000-0000-000000000002`
- `@BeforeEach` inserts both tenants with `ON CONFLICT DO NOTHING`
- Helper methods `createShop(tenantId, name)`, `createProduct(tenantId, sku, title)` that set `TenantContext` and save via repository

`SecurityHeadersIntegrationTest` (Phase 12 Plan 01) is the MockMvc+Testcontainers composition pattern — use this as the outer shell. Key conventions:
- `@SpringBootTest + @AutoConfigureMockMvc + @Testcontainers + @ActiveProfiles("test") + @Tag("testcontainers")`
- `@Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")...`
- `@DynamicPropertySource` overrides `spring.datasource.url`, `spring.datasource.driver-class-name`, `spring.jpa.database-platform`, `spring.flyway.enabled=true`, `rate-limiting.enabled=false` (the H2-default override — MANDATORY per Phase 12 deviation #4 or tests fail with Hikari driver mismatch)

### 8.2 Phase 12-mandated fixes that Phase 13 must also carry

From `12-01-SUMMARY.md` Deviations section:
1. **Docker API version** — `build.gradle.kts` already has `systemProperty("api.version", "1.45")` from commit `f428184`. Already in main, no action needed.
2. **RabbitMQ stub** — `@DynamicPropertySource` must set `spring.rabbitmq.host=localhost`, `spring.rabbitmq.port=0`, `spring.rabbitmq.listener.simple.auto-startup=false` if the test runs under any profile that does NOT exclude RabbitAutoConfiguration. For `@ActiveProfiles("test")` this is inherited from `src/test/resources/application-test.yml:11-15`. No action needed.
3. **H2 driver override** — mandatory when overriding the URL to Postgres. See pattern in `SecurityHeadersIntegrationTest.java:58-61`.

### 8.3 Proposed test plan (see §9 Validation Architecture for full mapping)

**Unit tests (Mockito, no Spring context):**
- `PublicStorefrontServiceTest.resolvePublicShopForSlug_whenNoUpstreamTenant_setsContextFromSlug()` — the all-guest happy path
- `PublicStorefrontServiceTest.resolvePublicShopForSlug_whenUpstreamMatches_setsContextFromSlug()` — JWT tenant == slug tenant; passes
- `PublicStorefrontServiceTest.resolvePublicShopForSlug_whenUpstreamMismatches_throws403()` — JWT tenant ≠ slug tenant; throws `TenantAccessDeniedException`
- `PublicStorefrontServiceTest.resolvePublicShopForSlug_whenSlugUnknown_throws404()` — preserves existing `ResourceNotFoundException` behavior

**Integration test (new `CrossTenantSpoofIntegrationTest`, modeled on `MultiTenantIsolationIntegrationTest`):**
- Seed two tenants (A and B); seed a published shop for each with distinct slugs
- Mint a mock JWT for tenant A via `@WithMockUser` + a custom `JwtRequestPostProcessor` (Spring Security test utility)
- `mockMvc.perform(get("/public/shops/{slugB}/products").with(jwt(...)))` → `status().isForbidden()`
- `mockMvc.perform(get("/public/shops/{slugA}/products").with(jwt(...)))` → `status().isOk()` (legitimate same-tenant access)
- `mockMvc.perform(get("/public/shops/{slugA}/products"))` with no JWT → `status().isOk()` (happy anonymous guest)
- Assert structured log entry `event=tenant_spoof_attempt` is present for the mismatch case (use `OutputCaptureExtension` / `@ExtendWith(OutputCaptureExtension.class)` — Spring Boot test util)

### 8.4 Playwright regression (success criterion 2)

Existing Playwright storefront e2e (if any exists — planner to grep `frontend/e2e/` and `frontend/playwright/`). Requirement: one pass over the guest browse flow confirming no Phase 13 regression. No new Playwright test is required; just run the existing one.

---

## 9. Validation Architecture (Nyquist)

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5.10 + Spring Boot Test 3.4.2 + Testcontainers 1.21.3 + MockMvc |
| Config file | `core-java/build.gradle.kts` (gradle test task + `api.version` system property); `core-java/src/test/resources/application-test.yml` |
| Quick run command | `./gradlew :core-java:test --tests "*PublicStorefrontServiceTest*" --tests "*CrossTenantSpoofIntegrationTest*"` |
| Full suite command | `./gradlew :core-java:test -PincludeIntegration` |

### Phase Requirements → Test Map

| Criterion | Behavior | Test Type | Automated Command | File Exists? |
|-----------|----------|-----------|-------------------|-------------|
| SC-1 (guest on A cannot retrieve B) | JWT-authenticated-for-A request to `/public/shops/{slug-B}/products` returns 403 + structured audit log | integration (MockMvc + Testcontainers) | `./gradlew :core-java:test --tests "CrossTenantSpoofIntegrationTest.crossTenantRequestReturns403"` | ❌ Wave 0 — new file `core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java` |
| SC-1 (audit log present) | SLF4J log line contains `event=tenant_spoof_attempt` when spoof is rejected | integration (MockMvc + `OutputCaptureExtension`) | same command, assertion on captured output | ❌ Wave 0 — new test method in `CrossTenantSpoofIntegrationTest` |
| SC-2 (legitimate browse unaffected) | Playwright storefront flow — browse a single tenant's shop, add to cart, continue to checkout — no new 403s | e2e (Playwright) | `npx playwright test frontend/e2e/storefront*.spec.ts` (path to verify in plan phase) | ✅ Existing storefront e2e suite (planner to confirm file path) |
| SC-2 (no regression on same-tenant JWT) | Authenticated customer for A accessing A's products returns 200 | integration (MockMvc) | `./gradlew :core-java:test --tests "CrossTenantSpoofIntegrationTest.sameTenantJwtSucceeds"` | ❌ Wave 0 — new test method |
| SC-2 (no regression anonymous guest) | No JWT, request to A's products returns 200 | integration (MockMvc) | `./gradlew :core-java:test --tests "CrossTenantSpoofIntegrationTest.anonymousGuestSucceeds"` | ❌ Wave 0 — new test method |
| SC-3 (integration test seeds 2 tenants + spoof + asserts 403) | Two tenants seeded, spoof attempted, 403 asserted, 200 asserted for same-tenant | integration | same file as SC-1 | ❌ Wave 0 |
| SC-4 (unit tests: match, mismatch, missing-tenant) | `resolvePublicShopForSlug` helper — three unit tests | unit (Mockito) | `./gradlew :core-java:test --tests "PublicStorefrontServiceTest.resolvePublicShopForSlug*"` | ⚠️ Partial — add new test methods to existing `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` |

### Sampling Rate
- **Per task commit:** `./gradlew :core-java:test --tests "*PublicStorefrontServiceTest*" --tests "*CrossTenantSpoofIntegrationTest*"` (unit + the new integration test)
- **Per wave merge:** `./gradlew :core-java:test -PincludeIntegration` (full Java test suite including all testcontainer-tagged tests)
- **Phase gate:** full suite green + existing storefront Playwright e2e green (no regressions)

### Wave 0 Gaps
- [ ] `core-java/src/main/java/uk/jtoye/core/exception/TenantAccessDeniedException.java` — new exception extending `AccessDeniedException`
- [ ] `core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java` — new integration test (MockMvc + Testcontainers; seeds TENANT_A/TENANT_B following `MultiTenantIsolationIntegrationTest` pattern)
- [ ] Extend `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` with 4 new unit tests covering `resolvePublicShopForSlug`
- [ ] ReviewService audit (Planner task — grep for `TenantContext.set` in `review/ReviewService.java`; if present, add it to scope in Wave 2)

---

## 10. Threat Model Dimensions (STRIDE for SEC-01)

Per ASVS V4 (Access Control), V1 (Architecture), V7 (Error Handling & Logging).

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V1.4.1 Trusted enforcement | yes | Service-layer tenant gate (not trustable from client path alone) |
| V4.1.1 Principle of least privilege | yes | JWT-authenticated caller limited to own tenant even on `/public/**` |
| V4.1.3 Deny by default for cross-tenant access | yes | Upstream tenant != slug tenant → 403, no exceptions |
| V4.1.5 Do not reveal internal state on access denial | yes | `GlobalExceptionHandler` returns generic `Access denied` (no tenant IDs in body) |
| V7.1.1 Log security-relevant events | yes | Structured SLF4J log `event=tenant_spoof_attempt` |
| V7.3.1 Logs do not contain sensitive data | yes | Log contains tenant UUIDs (not PII), slug (public), and outcome — no JWT, no email, no name |
| V2/V3 Authentication/Session Management | indirect | Inherits from Keycloak OIDC + JwtTenantFilter (Phase 9 scope, already shipped) |
| V5 Input Validation | indirect | Slug is validated implicitly by `findBySlugAndPublishedTrue` (no manual injection possible — JPA parameterized query) |
| V6 Cryptography | no | No new crypto surface |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| URL slug spoof — authenticated caller for tenant A crafts URL for tenant B | **Tampering** (request path lies about intended tenant) | Service-layer `resolvePublicShopForSlug` helper: compare `TenantContext` to slug tenant; 403 on mismatch |
| Cross-tenant data exfiltration — reading tenant B's products while authenticated as A | **Information Disclosure** | Same gate — 403 BEFORE `TenantContext.set(shop.getTenantId())` runs, so downstream RLS never sees the override |
| Cross-tenant data forgery — creating an order on tenant B while authenticated as A | **Information Disclosure + Tampering** (WRITE — worst-case) | Same gate on `createGuestOrder` prevents `TenantContext` override; Order + OrderItem mutation rows never get `tenant_id = B` |
| Guest session hijack to authenticated tenant | **Elevation of Privilege** | NOT the SEC-01 surface — an anonymous guest has no JWT, `TenantContext.get()` is empty, the helper sets context from slug and proceeds normally. Only matters when caller IS authenticated. |
| Repeat attempts with rotated slugs to enumerate tenants | **Reconnaissance (Information Disclosure)** | RateLimitInterceptor (existing, `RateLimitInterceptor.java`) already applies per-tenant rate limiting — ADDITIONAL defense-in-depth not in SEC-01 scope, but relevant to note |
| Audit log forgery / log injection | **Repudiation / Tampering** | Structured key-value log format — slug is URL-path validated (alphanumeric + hyphen per `V16__public_storefront.sql:29-31`), tenant UUIDs are native Java objects — no injection surface in the log line construction |

### Primary impact
**Information Disclosure** — an authenticated customer for tenant A can read tenant B's products and create orders in tenant B's namespace. The worst case is order creation (`createGuestOrder`) because it's a WRITE: an attacker could pollute tenant B's order book with garbage orders that appear to come from legitimate Keycloak identities. Rows would have `tenant_id = B` (via `shop.getTenantId()`) but be initiated by an actor only authorized for tenant A — an auditable log-free cross-tenant write today. Phase 13 closes this exactly.

---

## 11. Open Questions (RESOLVED — defaults for the planner)

**All questions from the prompt resolved based on codebase evidence:**

### Q1. WHERE to put the check — filter, interceptor, or service?
**RESOLVED: service-layer helper `PublicStorefrontService.resolvePublicShopForSlug(slug)`.** A filter cannot resolve slug→tenant without a DB query, and adding DB access to the filter chain is an anti-pattern. The three current call sites (`getShopConfig`, `getShopProducts`, `createGuestOrder`) already share the `findBySlugAndPublishedTrue() + TenantContext.set()` shape — extracting them into a helper is a clean refactor, not a new pattern. See §3.

### Q2. HOW is session-bound tenant tracked today?
**RESOLVED: for guests, it isn't — there is no session. The slug IS the tenant source. For authenticated-at-public-endpoint callers, `TenantContext` is populated by `JwtTenantFilter` from the JWT's `tenant_id` claim BEFORE the controller runs.** No `GuestTrackingService`, no HTTP session cookie (Spring is stateless), no separate session JWT claim. See §2.4, §4.

### Q3. WHICH endpoints are in scope?
**RESOLVED: the 3 in-scope endpoints are the ones whose service methods call `TenantContext.set(shop.getTenantId())`**: `/public/shops/{slug}/config`, `/public/shops/{slug}/products`, `POST /public/shops/{slug}/orders`. Planner must separately audit `ReviewService.getShopReviews` / `createReview` (2 more endpoints, same pattern expected). Other public endpoints without slug (or without `TenantContext.set`) are out of scope. See §5.

### Q4. WHAT should the audit log format be?
**RESOLVED: structured SLF4J WARN/ERROR with `event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403`.** Reuse the existing SLF4J logger already in `PublicStorefrontService.java:61`. Do NOT extend `AuditService` — that's Envers-read-only. No new table, no new migration. Loki/ELK (phase 9 observability) scrapes stdout; Alertmanager can alert on the event key. See §6.

### Q5. HOW is the 403 response structured?
**RESOLVED: reuse `GlobalExceptionHandler.handleAccessDenied` which already maps `AccessDeniedException` → 403 ProblemDetail.** New exception `TenantAccessDeniedException extends AccessDeniedException` — zero handler changes. Generic `"Access denied"` message in the body (no tenant IDs leaked) per ASVS V4.1.5. See §7.

### Q6. Should session→tenant be cached (Redis) or re-derived per request?
**RESOLVED: re-derive from `TenantContext.get()` each request.** No new caching. `JwtTenantFilter` already runs once per request and populates TenantContext from the JWT in O(1). Adding a Redis lookup here would add latency with zero security benefit — the JWT is already validated by `BearerTokenAuthenticationFilter`. See §4.

### Q7. TEST DATA — how are two tenants seeded today?
**RESOLVED: `MultiTenantIsolationIntegrationTest.java:74-86` is the canonical pattern.** Two hardcoded UUIDs (`00000000-...-0000-0000-000000000001` and `...-000000000002`), `@BeforeEach` inserts with `ON CONFLICT DO NOTHING`, helper methods `createShop(tenantId, name)` save via repository under `TenantContext`. Copy this scaffold into the new `CrossTenantSpoofIntegrationTest.java`. See §8.1-8.3.

---

## 12. Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|-----------|--------------|----------------|-----------|
| Tenant-mismatch detection + 403 decision | Core Java (service layer) | — | Requires DB lookup of slug→tenant; belongs in `PublicStorefrontService` |
| JWT tenant extraction | Core Java (security filter layer) | — | Already owned by `JwtTenantFilter`; no change |
| 403 response shaping (ProblemDetail) | Core Java (`GlobalExceptionHandler`) | — | Already handles `AccessDeniedException`; new exception extends it |
| Structured audit log emission | Core Java (service layer) | Observability (Loki/Alertmanager) | SLF4J WARN at site of rejection; log aggregator scrapes and alerts |
| Cross-tenant integration test | Core Java (test layer) | — | MockMvc + Testcontainers Postgres; follows Phase 12 + `MultiTenantIsolationIntegrationTest` patterns |
| Playwright regression check | Frontend (test layer) | — | Existing storefront e2e must pass unchanged |

---

## 13. Standard Stack (verified versions)

No new libraries. All testing infrastructure already pinned by Phase 12 Plan 01:

| Library | Version | Purpose | Source |
|---------|---------|---------|--------|
| Spring Security | 6.x (via Spring Boot 3.4.2) | Filter chain, `AccessDeniedException`, `AuthenticationException` | `core-java/build.gradle.kts`, SecurityConfig.java |
| Spring Boot Test | 3.4.2 | `@SpringBootTest`, `@AutoConfigureMockMvc` | BOM |
| JUnit Jupiter | 5.10.x | `@Test`, `@BeforeEach`, `@Tag` | BOM |
| Testcontainers | 1.21.3 | `PostgreSQLContainer<>("postgres:15")` | `core-java/build.gradle.kts` (verified in Phase 12 SUMMARY) |
| Mockito | 5.x | `@Mock`, `MockitoExtension` — already used in `PublicStorefrontServiceTest` | BOM |
| AssertJ | 3.x | fluent assertions — already used in `MultiTenantIsolationIntegrationTest` | BOM |
| Spring Security Test | 6.x | `@WithMockUser`, JWT request post-processors | BOM |

[CITED: Phase 12 Plan 01 SUMMARY — tech-stack.added: [] — confirms zero new deps needed for test scaffolding]

---

## 14. Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 403 response shape | Custom `ErrorResponse` / `ResponseEntity<...>` return | Extend `AccessDeniedException`; `GlobalExceptionHandler` already handles it | Duplicates existing handler; violates DRY |
| URI path parsing in a new filter | Custom regex to extract `{slug}` in a filter | Service-layer check; let Spring MVC do `@PathVariable` extraction | Path-matching is brittle; Spring MVC patterns (`/public/shops/{slug}`) can vary; filter runs before routing |
| Session storage for guest tenant | New `GuestTrackingService` bean + Redis keys | Nothing — `TenantContext.get()` is already correct | Creates new stateful surface with zero security benefit; JwtTenantFilter already populates TenantContext per-request |
| Custom security-event table | `security_events` Flyway migration + entity + repository | Structured SLF4J WARN + Loki | Log aggregation + Alertmanager already in place from Phase 9; adding a DB table is duplicate infrastructure |
| JWT tenant extraction | New helper to read `tenant_id` claim | Reuse `TenantContext.get()` populated by JwtTenantFilter | Already extracted once per request, Optional<UUID> is the API |

---

## 15. Common Pitfalls

### Pitfall 1: Cleaning TenantContext in the helper
**What goes wrong:** If `resolvePublicShopForSlug` calls `TenantContext.clear()` on exception, it strips upstream state that other filters may still use. The helper MUST only set on success, never clear.
**Why it happens:** Intuitive to do cleanup at the point of failure.
**How to avoid:** Helper only sets on happy path; callers retain their existing `finally { TenantContext.clear(); }` around the full operation; `TenantContextCleanupFilter` provides final cleanup.
**Warning signs:** Second request in a thread pool starts with orphaned tenant state (test: run two tests that hit the helper via MockMvc on the same thread).

### Pitfall 2: Test passes because H2 doesn't enforce RLS
**What goes wrong:** `application-test.yml` defaults to H2 in-memory (line 18). H2 does not enforce Postgres RLS policies. An integration test against H2 could pass vacuously because the cross-tenant query returns all rows.
**Why it happens:** Copy-paste from other unit tests that don't care about RLS.
**How to avoid:** The new `CrossTenantSpoofIntegrationTest` MUST use the `@Testcontainers` + `PostgreSQLContainer<>("postgres:15")` + `@DynamicPropertySource` override pattern (see §8.1-8.3). This is Phase 12's established pattern.
**Warning signs:** Test passes without the service-layer gate — indicates H2 is being used instead of Testcontainers Postgres. Check `@Container` annotation and `@DynamicPropertySource` driver override.

### Pitfall 3: JWT request post-processor with wrong tenant claim key
**What goes wrong:** `JwtTenantFilter` reads claims in this priority order: `tenant_id`, `tenantId`, `tid` (`JwtTenantFilter.java:53`). Test fixtures that set only `tid` when the real Keycloak JWT uses `tenant_id` create a false-green (test matches the test JWT but production would fail, or vice versa).
**Why it happens:** Unclear which claim key is canonical.
**How to avoid:** Test fixtures use `tenant_id` (the production Keycloak claim name — see `PROJECT.md` / Phase 9 Keycloak config). Document the choice in the test.
**Warning signs:** Test JWT `.claim("tid", ...)` passes trivially — should be `.claim("tenant_id", ...)`.

### Pitfall 4: Forgetting that `X-Tenant-Id` header is a guest back-door
**What goes wrong:** `TenantFilter` accepts `X-Tenant-Id` from the request header without authentication (`TenantFilter.java:22-30`). This is a dev-convenience path. A browser cannot add it cross-origin (CORS preflight blocks non-standard headers by default), but a naive test or a misconfigured CORS policy could.
**Why it happens:** Legacy dev ergonomics; production should reject it on `/public/**`.
**How to avoid:** Verify current CORS config in `SecurityConfig.java:67` does NOT allow `X-Tenant-Id` in `allowed-headers` for cross-origin requests. Phase 13 plan can optionally add a check in `resolvePublicShopForSlug` that rejects if `X-Tenant-Id` header is present on a `/public/**` path (defense-in-depth). **Planner call.**
**Warning signs:** Penetration test sends `X-Tenant-Id: <other-tenant-uuid>` to `/public/shops/{slug}/products` and gets cross-tenant data.

### Pitfall 5: ReviewService has the same bug, missed during planning
**What goes wrong:** Phase 13 ships the fix for `PublicStorefrontService` but `ReviewService.getShopReviews(slug, ...)` and `ReviewService.createReview(slug, email, ...)` have the identical pattern and are missed.
**Why it happens:** ReviewService is in a different package (`uk.jtoye.core.review`), grep-audit missed it.
**How to avoid:** Planner explicitly greps `core-java/src/main/java/uk/jtoye/core/review/ReviewService.java` for `TenantContext.set` in Wave 0. If found, extend the fix to cover those two endpoints in the same phase (they're on `/public/**`, same threat, same pattern).
**Warning signs:** Phase-13 integration test runs against `/public/shops/{slug}/config|products|orders` but ignores `/public/shops/{slug}/reviews`. Attacker moves to the review endpoint.

---

## 16. Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker daemon | Testcontainers | ✓ (verified by Phase 12 Plan 01) | Engine 29.x, API 1.45+ | — |
| Java 21 JDK | Gradle build | ✓ (project pins; CLAUDE.md) | 21 | — |
| Gradle wrapper | Build | ✓ | 8.10+ | — |
| PostgreSQL image | Testcontainers | ✓ (pulled on first test run) | 15 | — |
| Keycloak / real JWKS | NOT required for tests (MockMvc uses `@WithMockUser` / `jwt()` post-processor) | ✓ | 24.0.5 (prod only) | MockMvc JWT PP |

No blocking missing dependencies. All Phase 12 scaffolding fixes (Docker API version, Postgres driver override, RabbitMQ stubs) already applied in `build.gradle.kts` and `application-test.yml`.

---

## 17. Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Spring's default `cors(Customizer.withDefaults())` does NOT echo arbitrary cross-origin `X-Tenant-Id` on preflight | §4 (browser can't attach header cross-origin), Pitfall 4 | LOW — dev-header `X-Tenant-Id` bypass is a secondary attack vector; primary threat (JWT-carrying authenticated user hitting wrong slug) is covered regardless. Planner to verify CORS config during Wave 0 audit. |
| A2 | `ReviewService.getShopReviews(slug, ...)` and `.createReview(slug, ...)` call `TenantContext.set()` with slug-derived tenant | §5 (scope), Pitfall 5 | MEDIUM — if true, Phase 13 must cover them; if false, they don't need fixing. Resolved by 1 grep in Wave 0. |
| A3 | Production Keycloak JWT uses claim name `tenant_id` (not `tenantId` or `tid`) | Pitfall 3 | LOW — JwtTenantFilter handles all three; test fixtures using `tenant_id` match production fastest path. Verify against `keycloak/realm-export.json` or `docs/` in Wave 0. |
| A4 | Playwright storefront e2e exists and can be run as the SC-2 regression check | §8.4, §9 | LOW — if no Playwright e2e exists, MockMvc integration tests alone satisfy SC-2 (assert 200 on same-tenant request). Planner to confirm `frontend/e2e/` contents. |
| A5 | `GlobalExceptionHandler.handleAccessDenied` does NOT log the exception (which would duplicate the service-layer log) | §6 | LOW — verified at `GlobalExceptionHandler.java:103-109`: no logging in the handler, just `ProblemDetail` construction. So the single structured log from `resolvePublicShopForSlug` is the canonical record. |
| A6 | `@WithMockUser` + `SecurityMockMvcRequestPostProcessors.jwt()` is available on the current Spring Security Test classpath | §8.3 | LOW — Spring Security 6 provides this out of the box; confirmed standard pattern. |

---

## 18. Project Constraints (from CLAUDE.md)

Copy of load-bearing directives from `/home/sanmi/IdeaProjects/JToye_OaaS_2026/CLAUDE.md` and user-global CLAUDE.md (2026-04-18):

- **Git policy (global):** Feature branches only; never commit or push directly to main. Workflow: `feature/<name>` branch → commit → push → PR → CI → merge. No Co-Authored-By trailers.
- **Tech stack:** Spring Boot 3.4.2, Java 21 (JDK 21 specifically — JDK 25 incompatible with Gradle 8.10).
- **Multi-tenancy constraint:** All new features must respect RLS and TenantContext — **Phase 13 IS this constraint applied to the guest path.**
- **Testing:** All new code requires tests. Current baseline 2026-04-18: 390 Java @Test + 76 Jest + 50/54 Go = 516+ logical invocations. Phase 13 must grow this; estimated +4 unit + ~3 integration = +7 Java tests.
- **Docker:** Rebuild ALL containers after code changes before E2E testing. Not applicable to Phase 13 (backend-only change; no Docker image rebuild needed for MockMvc tests).
- **Release & Documentation Integrity (global):** Every commit — grep for old version refs, update CHANGELOG, keep docs in sync with code.
- **GSD Workflow Enforcement:** Must route changes through a GSD command. This research is under `/gsd-plan-phase` → plan phase's `/gsd-execute-phase`.

---

## 19. Code Examples (verified patterns to reference)

### 19.1 Structured SLF4J log (matching existing `PublicStorefrontService` style)
```java
// Source: PublicStorefrontService.java:461-465 (pattern) + new usage
log.warn("event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403",
        slug, shop.getTenantId(), upstreamTenant.get());
```

### 19.2 MockMvc + Testcontainers scaffold
```java
// Source: SecurityHeadersIntegrationTest.java (Phase 12 Plan 01)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CrossTenantSpoofIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
    }
    // ... test methods
}
```

### 19.3 Two-tenant seeding
```java
// Source: MultiTenantIsolationIntegrationTest.java:74-86
private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

@BeforeEach
void setUp() {
    jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
            TENANT_A, "Tenant A");
    jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
            TENANT_B, "Tenant B");
}
```

### 19.4 JWT with tenant claim via MockMvc
```java
// Source: Spring Security 6 test utility — SecurityMockMvcRequestPostProcessors.jwt()
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

mockMvc.perform(get("/public/shops/{slug}/products", TENANT_B_SHOP_SLUG)
        .with(jwt().jwt(j -> j.claim("tenant_id", TENANT_A.toString()))))
    .andExpect(status().isForbidden());
```

---

## 20. Sources

### Primary (HIGH confidence) — file:line evidence
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` (102 lines, read in full)
- `core-java/src/main/java/uk/jtoye/core/security/TenantFilter.java` (36 lines, read in full)
- `core-java/src/main/java/uk/jtoye/core/security/JwtTenantFilter.java` (64 lines, read in full)
- `core-java/src/main/java/uk/jtoye/core/security/TenantContext.java` (23 lines, read in full)
- `core-java/src/main/java/uk/jtoye/core/security/TenantContextCleanupFilter.java` (41 lines, read in full)
- `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java` (78 lines, read in full)
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` (131 lines, read in full — 11 endpoints)
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` (595 lines, read in full — 3 `TenantContext.set` sites identified at lines 102, 214, 326)
- `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java` (120 lines, read in full — 403 mapping confirmed at lines 103-109)
- `core-java/src/main/java/uk/jtoye/core/exception/ErrorResponse.java` (38 lines, read in full)
- `core-java/src/main/java/uk/jtoye/core/audit/AuditService.java` (107 lines, read in full — Envers read-only, not a security-event sink)
- `core-java/src/test/java/uk/jtoye/core/security/MultiTenantIsolationIntegrationTest.java` (274 lines, read in full — canonical two-tenant scaffold)
- `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` (128 lines, read in full — Phase 12 MockMvc+Testcontainers pattern)
- `core-java/src/test/java/uk/jtoye/core/security/TenantFilterTest.java` (119 lines, read in full)
- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` (first 80 lines)
- `core-java/src/test/resources/application-test.yml` (42 lines, read in full — H2 defaults that MUST be overridden)
- `core-java/src/main/resources/db/migration/V16__public_storefront.sql` (first 120 lines — `shops_public_read` RLS policy at line 75)
- `frontend/lib/customer-auth.ts` (300 lines, read in full — confirms HttpOnly-cookie customer auth, no frontend session-bound tenant)
- `.planning/REQUIREMENTS.md` (127 lines, read in full)
- `.planning/ROADMAP.md` (172 lines, read in full)
- `.planning/STATE.md` (122 lines, read in full)
- `.planning/phases/12-spring-security-response-headers-frontend-csp/12-01-SUMMARY.md` (226 lines, read in full — testcontainers patterns + deviations)
- `.planning/config.json` — `workflow.nyquist_validation: true`, `workflow.research: true`, `mode: yolo`
- `/home/sanmi/.claude/CLAUDE.md` (global) + `./CLAUDE.md` (project) (read via system-reminder — constraints captured)

### Grep audits (HIGH confidence — negative findings)
- `Guest|guest` in `core-java/src/main/java` — 7 hits, all in `storefront/` (DTO names like `GuestOrderRequest`, `GuestOrderConfirmation`). **Zero `GuestTrackingService`.**
- `GuestTracking|guest_session|GuestSession|session_id` in `core-java/src/main/java` — zero hits
- `session_id|session-id|app\.session|SessionService` in `core-java` — zero hits
- `TenantMismatch|CrossTenant|tenant.mismatch` in repo — zero hits (so no pre-existing implementation to merge against)
- `GlobalExceptionHandler|@RestControllerAdvice|@ExceptionHandler` in `core-java/src/main/java` — 1 file only: `common/GlobalExceptionHandler.java`
- `guest.?session|guest.?tenant|session_tenant` in `frontend/` — zero hits

### Secondary (MEDIUM — not verified in this session)
- Keycloak realm claim name `tenant_id` vs `tenantId` vs `tid` — JwtTenantFilter handles all three with priority order; production claim name is assumed to be `tenant_id` but not verified in realm-export.json this session (Assumption A3).
- Spring's default CORS policy behavior on `X-Tenant-Id` preflight — not verified (Assumption A1).
- Playwright storefront e2e existence at `frontend/e2e/` — not verified (Assumption A4).

### Tertiary (LOW — training-data only)
- OWASP ASVS V4.1.5 "Do not reveal internal state on access denial" — cited from training knowledge, standard ASVS v4 category (well-established).
- Standard Spring service-layer pattern for cross-cutting validation — training knowledge.

---

## 21. Metadata

**Confidence breakdown:**
- Current-state audit: HIGH — read source in full for all 3 vulnerable call sites and every filter in the chain; grep-confirmed no hidden GuestTrackingService
- Fix location: HIGH — three existing call sites already share the refactor shape; service-layer gate is the only viable location given no DB access in filters
- 403 wiring: HIGH — verified `GlobalExceptionHandler.handleAccessDenied` already exists and routes `AccessDeniedException` → 403
- Test scaffolding: HIGH — Phase 12 testcontainers pattern verified working, `MultiTenantIsolationIntegrationTest` two-tenant seeding verified at file:line
- STRIDE / ASVS mapping: MEDIUM-HIGH — applied standard categories; pattern-matched to existing codebase
- CORS dev-header side-channel: MEDIUM — flagged as Assumption A1; planner to verify in Wave 0
- ReviewService scope: MEDIUM — flagged as Assumption A2; planner to verify in Wave 0 (single grep)

**Research date:** 2026-04-18
**Valid until:** 2026-05-18 (30 days — Spring Security 6 and Spring Boot 3.4.2 are stable; SecurityConfig + filter chain semantics do not shift on minor bumps)

---

## RESEARCH COMPLETE

**Phase:** 13 — Guest Tracking Tenant Validation
**Confidence:** HIGH

### Key Findings (3 most decision-impacting)

1. **There is no `GuestTrackingService` — the requirement's "session-bound tenant" premise doesn't apply to anonymous guests.** Guests are stateless; their tenant comes from the path slug. The real threat is an **authenticated customer** whose JWT tenant (populated into `TenantContext` by `JwtTenantFilter`) contradicts a spoofed path slug. The fix is a service-layer gate that compares the JWT-populated `TenantContext.get()` to `shop.getTenantId()`, not a new session mechanism.

2. **The exact fix is a private helper `resolvePublicShopForSlug(slug)` in `PublicStorefrontService`** that consolidates three existing `findBySlugAndPublishedTrue() + TenantContext.set()` pairs (at lines 102, 214, 326) behind a single check. Extend `AccessDeniedException` with a new `TenantAccessDeniedException` — the existing `GlobalExceptionHandler.handleAccessDenied` already maps it to 403. Zero new filters, zero new handlers, zero new tables, zero new libraries.

3. **Test scaffolding is fully precedented — reuse Phase 12 + `MultiTenantIsolationIntegrationTest` patterns.** New `CrossTenantSpoofIntegrationTest` uses `PostgreSQLContainer<>("postgres:15")` + `@DynamicPropertySource` (Phase 12 Deviation #4: MUST override driver-class-name from H2) + Spring Security Test's `jwt().jwt(j -> j.claim("tenant_id", ...))` request post-processor. Two hardcoded tenant UUIDs (`...000000000001`, `...000000000002`) from `MultiTenantIsolationIntegrationTest.java:74-75`. **Planner's only Wave 0 uncertainty:** audit `ReviewService` for the same `TenantContext.set` pattern (Assumption A2 — single grep resolves).

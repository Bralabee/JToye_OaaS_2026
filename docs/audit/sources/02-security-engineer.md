# Security Audit
**Auditor persona**: AppSec engineer / pentester, multi-tenant SaaS specialty
**Date**: 2026-04-27
**Overall risk posture**: **High** — RLS scaffolding is largely correct and JWT/Stripe paths show real defensive design, but two confirmed cross-tenant data leaks (SSE broadcast, IDOR on customer order history), missing webhook replay protection, broken review-RLS clause, and absent role-based authorisation collectively make production rollout unsafe without remediation.

---

## Critical findings

1. **[CRITICAL] Cross-tenant SSE broadcast — every authenticated user receives every other tenant's order state changes** — `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java:29-40`
   - Evidence: `broadcast()` iterates `emitters` (a process-wide `CopyOnWriteArrayList<SseEmitter>`) and sends each `OrderStateChangeEvent` to all subscribers with no tenant key check. `subscribe()` adds the emitter without storing the caller's tenant. `OrderStateChangeListener.handleOrderStateChange()` calls `sseService.broadcast(event)` for every order in every tenant. The WebSocket path correctly scopes by `/topic/kitchen/{tenantId}/{shopId}` and is enforced by `TenantChannelInterceptor`, but the SSE path is parallel and unscoped.
   - Attack scenario: Vendor A's dashboard user opens `GET /orders/stream` with their valid JWT. Vendor B places an order. Vendor A's browser receives an SSE event containing Vendor B's order id, tenant id, order number, customer email, total, and status transitions. Sustained subscription = full order-stream eavesdrop on the entire platform.
   - Fix: Capture `TenantContext.get()` (or the JWT principal's tenant claim) at `subscribe()` time, store it alongside the emitter (e.g. `record EmitterEntry(UUID tenantId, SseEmitter emitter)`), and filter in `broadcast()` by `event.tenantId().equals(entry.tenantId())`. Add an integration test that asserts a Tenant-A subscriber does not see a Tenant-B event.

2. **[CRITICAL] IDOR — anyone with a customer email can list that customer's full order history** — `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java:91-104` + `PublicStorefrontService.getCustomerOrders` (line 233-265) + `db/migration/V18__order_history_by_email.sql:9-19`
   - Evidence: The controller has a permissive guard — `if (verifyOrderNumber != null && !verifyOrderNumber.isBlank())` — meaning when the caller omits the `verify` query param, no ownership proof is required. The service then does `set_config('app.customer_email', :email)`; the V18 RLS policy `orders_customer_history` returns rows where `customer_email = current_setting('app.customer_email', true)`. The list endpoint is mounted under `/public/**` (SecurityConfig.java:71 `permitAll`).
   - Attack scenario: `curl 'https://api.jtoye.uk/public/orders?email=victim@example.com'` → returns full PII (order number, shop name, totals, status, line items via subsequent tracking) for every order the victim has ever placed across every published shop. Combined with the predictable seeded tenant UUIDs (`00000000-0000-0000-0000-000000000001/2` in V13) and known customer emails (e.g. from a breach), this enables mass-scrape of the platform's customer database.
   - Fix: Make `verify` mandatory. Reject (400) when missing; only after `trackOrder(verify, email)` succeeds may the history be returned. Better: issue a short-lived signed token in the order-confirmation email and require it on history lookups. Rate-limit per IP and per email separately.

3. **[CRITICAL] Stripe webhook has no replay or idempotency guard** — `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:112-199`
   - Evidence: `Webhook.constructEvent` validates the signature, but the handler never persists `event.getId()` and never checks whether that event id has already been processed. `handlePaymentIntentSucceeded` flips the order to PENDING, writes a financial transaction (`createTransaction(... STANDARD)`), and publishes a payment-succeeded event each time it runs. Stripe explicitly retries any 2xx-but-network-flaky delivery, and a leaked webhook secret or even a benign double-delivery causes duplicate revenue lines.
   - Attack scenario: Attacker who can capture one valid `payment_intent.succeeded` payload + `Stripe-Signature` header (e.g. via misconfigured proxy log, or by being on a shared dev environment that shares the webhook secret) can replay the request indefinitely. Each replay creates a new `financial_transactions` row, double-counting revenue and corrupting VAT submissions. Even without an attacker, a Stripe network hiccup causes the same effect on legitimate retries.
   - Fix: Persist `event.getId()` to a `processed_stripe_events` table with a unique constraint, inside the same `@Transactional` block. On duplicate, return 200 OK without re-processing. Also enforce a max-age on the event timestamp (`event.getCreated()`) to bound replay window to ~5 minutes.

---

## High findings

4. **[HIGH] Review insert RLS uses a session GUC the application never sets** — `db/migration/V27__customer_reviews.sql:31-36`
   - Evidence: `reviews_tenant_write` checks `tenant_id::text = current_setting('app.tenant_id', true)::UUID` — note the GUC is `app.tenant_id`, not `app.current_tenant_id` which `TenantSetLocalAspect` actually sets. The OR branch — `current_setting('app.customer_email', true) = customer_email` — accepts any review whose `customer_email` matches the session-set email. Any storefront caller can hit `POST /public/shops/{slug}/reviews?email=anything` and write a review for any shop with arbitrary `tenant_id`/`shop_id`/`order_id` because the row's `customer_email` matches the session email.
   - Attack scenario: Spam review injection at scale; review-bombing competitor vendors; setting `food_rating=1` for every shop in a region from a single email.
   - Fix: Re-issue policy with the correct GUC (`app.current_tenant_id`) and stop relying on `customer_email` as proof of ownership. Verify the email owns the cited `order_id` (server-side join to `orders.customer_email`) before the row is allowed.

5. **[HIGH] No method-level / role-based authorisation anywhere — every authenticated tenant user is effectively an admin** — `SecurityConfig.java:68-74`, `grep -l "@PreAuthorize\|@RolesAllowed\|@Secured"` returns zero matches across `core-java/src/main/java`.
   - Evidence: Filter chain ends with `.anyRequest().authenticated()` — it never inspects roles. `GdprController` (`/gdpr/customers/{id}/export`, `/erase`) is reachable by any holder of a tenant JWT. `FinancialTransactionController`, `OrderController.deleteOrder`, `ProductController`, `ShopController`, `PromotionController`, `AnnouncementController`, `CustomerController` — all wide-open within tenant boundary.
   - Attack scenario: Compromised low-privilege tenant employee (kitchen-display kiosk, marketing intern) erases the entire customer database via `DELETE /gdpr/customers/{id}/erase`, deletes orders, mints fake transactions, exports PII. There's no separation between owner / manager / staff / kiosk roles even though Keycloak issues role claims.
   - Fix: Define role taxonomy (OWNER, MANAGER, STAFF, KITCHEN, READONLY), wire `JwtGrantedAuthoritiesConverter` to lift `realm_access.roles` into Spring authorities, annotate destructive endpoints with `@PreAuthorize("hasRole('OWNER')")` etc.

6. **[HIGH] NextAuth session leaks the raw Keycloak access token to the browser** — `frontend/auth.ts:87-92`
   - Evidence: `session.accessToken = token.accessToken as string`. NextAuth's session handler is reachable from client components via `useSession()`, and `/api/auth/session` returns the JWT in JSON. Any XSS in the dashboard immediately exfiltrates a tenant-scoped bearer token usable directly against the Core API. The HttpOnly NextAuth cookie protection is voided.
   - Attack scenario: A single XSS payload (e.g. an unsanitised vendor description rendered without escaping, or a product name with HTML in it) reads `fetch('/api/auth/session').then(r=>r.json())`, exfils `accessToken`, and the attacker calls `/orders`, `/products`, `/customers` from anywhere as that tenant.
   - Fix: Keep tokens server-side only. Strip `accessToken`/`refreshToken` from `session()` callback. Have BFF routes in Next.js read the token from the JWT cookie (server-side) and proxy to Core. If you must hand the token to the SPA, use a short-lived (60-second) access proxy token that's scoped per page load.

7. **[HIGH] Edge gateway rate limiter is global, not per-tenant — single tenant can DoS the platform** — `edge-go/cmd/edge/main.go:71-102, 149-153`
   - Evidence: `rateLimiter()` returns a single Gin middleware backed by one `chan struct{}` token bucket sized at `RATE_LIMIT_BURST=40` and refilled at `RATE_LIMIT_RPS=20` per second for the whole process. The Gin handler does not key on tenant id, IP, or even bearer token.
   - Attack scenario: A single noisy tenant (or a single attacker with one valid JWT) consumes all 20 rps, every other tenant gets `429 rate limit exceeded`. Effective DoS.
   - Fix: Replace the channel with a `golang.org/x/time/rate.Limiter` map keyed by extracted JWT `tenant_id` (validated by middleware), or by client IP for unauthenticated paths. The Spring side already uses Bucket4j+Redis per-tenant correctly (`RateLimitInterceptor`); mirror that.

8. **[HIGH] Edge JWT middleware: race-condition writes to publicKeys map + no `aud` claim verification** — `edge-go/internal/middleware/jwt.go:118-132, 199-227`
   - Evidence: `m.publicKeys = newKeys` is assigned without any `sync.RWMutex`. Concurrent requests during a refresh cause a Go data race (visible under `-race`); read-while-write can panic or, worse, silently return a stale/zero key. There's also no check of `aud` (audience) — any JWT issued by the same Keycloak realm for any client (e.g. a public storefront client) will pass validation against the protected sync endpoint.
   - Attack scenario: A token issued for the customer-facing storefront client is accepted by `/api/v1/sync/batch` and forwarded into Core's batch sync, which writes to the tenant's data with no further audience check.
   - Fix: Wrap `publicKeys` in `sync.RWMutex`. Add `jwt.WithAudience("core-api")` to `jwt.Parse`, or explicitly check `claims["aud"]` against an allow-list. Lock JWT signing to RS256 via `jwt.WithValidMethods([]string{"RS256"})` rather than asserting `*jwt.SigningMethodRSA` post-hoc.

9. **[HIGH] Public storefront RLS for `shop_announcements` lacks FORCE and uses bare `USING(true)` for SELECT** — `db/migration/V29__vendor_marketing.sql:25-31` (V33 partially fixes shop_announcements but does not add FORCE)
   - Evidence: V29 originally `CREATE POLICY shop_announcements_read ... USING (true)`. V33 replaced this with a published-shop-or-tenant filter, good. However neither V29 nor V33 issues `ALTER TABLE shop_announcements FORCE ROW LEVEL SECURITY`. `shop_promotions` and `reviews` are in the same boat. If `DB_USER` becomes the table owner via a future migration or admin connection (or in dev where Flyway runs as the bootstrap user), RLS is silently bypassed.
   - Fix: Audit-add `FORCE ROW LEVEL SECURITY` for `shop_announcements`, `shop_promotions`, `reviews`, `shop_promotions`. Verify in CI that `pg_class.relforcerowsecurity = true` for every tenant-scoped table (Phase 13 already started this list — finish it).

---

## Medium findings

10. **[MEDIUM] WebSocket JWT passed via URL query parameter — token leaks to access logs / browser history** — `frontend → /ws?token=<jwt>` per `JwtHandshakeInterceptor.java:24-37`. STOMP CONNECT-frame token is supported as fallback; switch the dashboard to send only via CONNECT frame headers, then remove the query-param branch.

11. **[MEDIUM] Missing CSP header** — `SecurityConfig.java:81-93` sets `frame-options DENY`, `X-Content-Type-Options`, `Referrer-Policy`, conditional HSTS in prod, but never adds `Content-Security-Policy`. Combined with the accessToken-in-session issue (#6), this means a single XSS sink is unmitigated.

12. **[MEDIUM] `error.include-message: always` in base config + raw exception messages echoed to clients** — `application.yml:97-99` plus `GlobalExceptionHandler.handleIllegalArgument/handleIllegalState` returning `ex.getMessage()`. Stack traces aren't included, but messages can leak internal paths, SQL fragments, or tenant ids depending on the exception (`PublicStorefrontService` throws `RuntimeException("Payment processing unavailable. Please try again later.")` — fine — but `IllegalArgumentException` from `productRepository` lookups leak product UUIDs). Production profile overrides — but staging/dev share the verbose default.

13. **[MEDIUM] `.env` in working tree contains live credentials with weak passwords** — `/.env` is gitignored (verified via `.gitignore`), but the values committed earlier history may persist (check `git log -p -- .env` retroactively). Passwords are `secret`, `admin123`, `<rotated-2026-08-29-see-.env>`, `<rotated-2026-08-29-see-.env>`, `jtoye-dev`-realm client secret `core-api-secret-2026`. The `NEXTAUTH_SECRET` is at least 32-byte base64. Replace dev credentials before any staging deploy; rotate `NEXTAUTH_SECRET` and Keycloak client secret as part of go-live.

14. **[MEDIUM] CORS allows credentials with a configurable origin list — single misconfigured `CORS_ALLOWED_ORIGINS=*` would void same-origin protection** — `CorsConfig.java:24-25`. `setAllowCredentials(true)` + `setAllowedOrigins(allowedOrigins)`: if anyone ever sets `CORS_ALLOWED_ORIGINS=*` the Spring Cors filter will reject (good) — but `setAllowedOriginPatterns` would silently allow it. Also `addAllowedHeader("*")` is overly broad; allowlist `Authorization, Content-Type, X-Tenant-Id` instead.

15. **[MEDIUM] Image upload trusts client-supplied `Content-Type` for the S3 object** — `StorageService.java:88` stores `file.getContentType()` even though magic-byte detection has already produced an authoritative `detectedType`. Set the S3 object's content-type from `detectedType`, not the client header. Otherwise an attacker can upload a valid JPEG with `Content-Type: text/html` and have MinIO serve it as HTML to other browsers (XSS via image storage).

16. **[MEDIUM] `getExtension()` honours user-supplied filenames including double-dots, no allow-list** — `StorageService.java:245-249`. Path traversal is mitigated because the key prefix is server-built (`tenantId + pathPrefix + entityId + UUID`), but extensions like `.svg` (which `detectContentType` rejects, good) or `.html..jpg` could end up in S3 keys. Map `detectedType → ".jpg"/".png"/".webp"/".gif"` and ignore the client filename entirely.

17. **[MEDIUM] Scheduled jobs enumerate ALL tenants via raw `SELECT id FROM tenants` (no RLS on `tenants`)** — `PaymentEventOutboxFlusher.java:73-75`, `ScheduledCleanupService.java:53-55`. The `tenants` table intentionally has no RLS (V2 comment). If an attacker ever achieves SQL injection or RCE, they can enumerate every tenant id. Restrict the table to a dedicated admin role; the runtime app role does not need `SELECT` on `tenants` once tenant ids are known via JWT.

18. **[MEDIUM] No request body / payload size cap on Stripe webhook** — `PaymentController.java:32-43`. Spring's default multipart cap (`spring.servlet.multipart.max-request-size: 5MB`) doesn't apply to JSON bodies. A 100MB POST to `/public/payments/webhook` will be parsed before signature verification, enabling resource exhaustion on a public endpoint.

---

## Low findings / hardening opportunities

- `JwtHandshakeInterceptor.beforeHandshake` returns `true` regardless of token presence — handshake always succeeds, error surfaces only at STOMP CONNECT. This bloats logs and allows handshake-flood DoS. Reject at handshake when no token is present.
- `TenantContext.set()` from `JwtTenantFilter` does not run for `/public/**` (no JWT). The aspect then issues `SET LOCAL app.current_tenant_id TO DEFAULT` per `TenantSetLocalAspect.resetTenant()`. RLS policies fail-closed because `current_tenant_id() RETURNS NULL`, but defence in depth would be: an explicit deny when an authenticated controller is hit with no tenant context.
- Stripe `customMetadata` includes `tenant_id` as a UUID string — fine, but log-redact it. Currently `log.info("Created PaymentIntent ... order ...")` would expose if logger format ever changes.
- `RateLimitInterceptor.preHandle` returns `true` (allow) when `TenantContext.get().isEmpty()` — see `RateLimitInterceptor.java:74-77`. For unauthenticated `/public/**` routes this means no rate limit at all on the most exposed endpoints (storefront listing, guest order creation). Add an IP-keyed bucket as fallback.
- `SecurityConfig` permits `/swagger-ui/**` and `/v3/api-docs/**` unauthenticated. Production overrides `springdoc.api-docs.enabled=false`, but the static Swagger UI assets still resolve. Disable both, in `application-prod.yml`, or guard with a separate filter.
- Hibernate Envers audit tables (`*_aud`) have RLS, but the listed audit policies (V4, V11) only cover `shops_aud`, `products_aud`, `financial_transactions_aud`, `orders_aud`, `order_items_aud`, `customers_aud` — and rely on `current_tenant_id()` being set during async DB writes. `BusinessMetricsService`, `PaymentEventOutboxFlusher`, RabbitMQ listener (`OrderStateChangeListener` — does set it, good) all need the same pattern. Audit each `@Scheduled` and `@RabbitListener` for tenant-context establishment.
- `TenantContextCleanupFilter` is `Ordered.HIGHEST_PRECEDENCE`, but `@Scheduled` and `@RabbitListener` paths run on container threads pulled from a pool. If a listener throws before the `try { } finally { TenantContext.clear(); }` block, the next message handled on that thread starts with a stale tenant. Wrap every async entry point in a try/finally.
- HikariCP `leak-detection-threshold: 60000` in dev / `30000` in prod is good, but no Spring Security session timeout configured (stateless JWT, OK) — confirm Keycloak access-token lifetime is short (<10 min) since the bearer is exposed to JS (#6).
- `frontend/.env*` patterns are gitignored, but verify no secrets ever land in `frontend/next.config.mjs` or `.env.production` accidentally.

---

## Cross-tenant isolation analysis (deep dive — this is the existential risk)

The design intent is layered: JWT extraction (`JwtTenantFilter`) populates a `ThreadLocal<UUID>` (`TenantContext`); a Hibernate `@Before` aspect (`TenantSetLocalAspect`) translates that to a Postgres session GUC `app.current_tenant_id` on every transactional repository or `JdbcTemplate` call; RLS policies on every multi-tenant table compare `tenant_id = current_tenant_id()`. When the GUC is unset, `current_tenant_id()` returns NULL and `WHERE tenant_id = NULL` evaluates UNKNOWN → row is excluded. This is the right fail-closed posture. `TenantContextCleanupFilter` clears the ThreadLocal after every HTTP request to prevent context-bleed across pooled threads.

**Where it works.** On the synchronous request path for authenticated controllers, this design is sound. The JWT is signed by Keycloak (RS256, JWKS verified by NimbusJwtDecoder); the `tenant_id` claim takes priority over the `X-Tenant-Id` header (`JwtTenantFilter.java:35-44`); the aspect sets the GUC inside the same DB connection used by the JPA query; RLS is forced (`FORCE ROW LEVEL SECURITY`) on `shops`, `products`, `financial_transactions`, `customers`, `orders`, `order_items`, `payment_event_outbox`. Cross-tenant SELECT/INSERT/UPDATE/DELETE on these tables is blocked even for the table owner.

**Where it leaks today.** Three concrete failures:

1. **SSE broadcast bypasses every layer above.** `OrderSseService` keeps a single process-wide list of `SseEmitter`s with no tenant key. The RabbitMQ listener (`OrderStateChangeListener.handleOrderStateChange`) — which DOES set `TenantContext` and the GUC for its DB work — calls `sseService.broadcast(event)` BEFORE the tenant context is restricted. Every connected client across every tenant receives every order's metadata. The WebSocket sibling path (`SimpMessagingTemplate.convertAndSend("/topic/kitchen/" + tenantId + "/" + shopId, event)`) is correctly tenant-scoped because `TenantChannelInterceptor` validates SUBSCRIBE destinations against the session's authenticated tenant. SSE was never updated to match.

2. **Storefront IDOR routes around RLS by setting `app.customer_email` from request input.** RLS policy `orders_customer_history` (V18) was written to allow customers to look up their own orders without authentication, scoped by an email session var. The storefront service obediently writes whatever email the caller sends into that GUC, so RLS is bypassed not because RLS is broken but because the application controls the comparison value. The "verification" check in the controller is conditional on the caller including a `verify` parameter — i.e. opt-in. This is "RLS is not access control" 101.

3. **Reviews policy is structurally flawed.** Even after V33 fixed the SELECT clause, `reviews_tenant_write` (V27) uses the wrong GUC name (`app.tenant_id` vs `app.current_tenant_id`) AND adds an OR fallback on customer_email. The OR makes the tenant_id check moot — any storefront request that has set `app.customer_email` (which every public order endpoint does) can insert a review row with arbitrary `tenant_id` and `shop_id`.

**Where it is at-risk but not currently exploited.** Background jobs (`PaymentEventOutboxFlusher`, `ScheduledCleanupService`) deliberately enumerate all tenants and set `TenantContext` per tenant — this is the right pattern, but it relies on every iteration's `TenantContext.set` completing before the repository call AND on every exception path clearing context. The `TenantSetLocalAspect` re-asserts the GUC just-in-time. A regression that adds a non-transactional read inside one of these loops would silently leak. The `tenants` table itself has no RLS (intentional), so any future SQL injection or raw-JDBC bug provides full tenant enumeration.

**Net assessment.** The RLS layer is doing its job for direct CRUD; the failures are above it (SSE service, application-controlled session vars, missing FORCE on three newer tables). The authentication is solid (RS256, JWKS, audience-needed-but-missing on edge). The authorisation layer is missing entirely — RLS only enforces "same tenant", never "right person within tenant".

---

## OWASP Top 10 status table

| OWASP                              | Status        | Evidence                                                                                                                                              |
|------------------------------------|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| A01 Broken Access Control          | **FAIL**      | SSE cross-tenant broadcast (#1), customer-orders IDOR (#2), no role checks (#5), reviews RLS bypass (#4), `/gdpr/.../erase` reachable by any user     |
| A02 Cryptographic Failures         | PARTIAL       | RS256 JWT, JWKS validated, HSTS in prod. Access token leaked to browser JS (#6). Dev secrets are weak placeholders (#13)                              |
| A03 Injection                       | PASS          | All SQL via JPA / parameterised native queries / `set_config(... ?, true)` PreparedStatements. No `Runtime.exec`, no `eval`, no string concatenation found in repositories                                                                                                       |
| A04 Insecure Design                | **FAIL**      | RLS used as access control (#2 #4); broadcast service forgets tenant (#1); webhook handler has no idempotency (#3); SSE auth = JWT-required but no per-subscriber filter |
| A05 Security Misconfiguration      | PARTIAL       | CSRF disabled (justified for stateless JWT), CORS over-permissive headers (#14), missing CSP (#11), Swagger publicly served in non-prod, `error.include-message: always` (#12)                                                              |
| A06 Vulnerable & Outdated Components | NOT AUDITED | Out of scope here — recommend `gradle dependencyCheck` and `npm audit` runs                                                                            |
| A07 Identification & AuthN Failures | PARTIAL      | JWT validation correct in Spring; edge-go has data race on JWKS map (#8) and missing audience check (#8); WebSocket token in URL query (#10)          |
| A08 Software & Data Integrity      | PARTIAL       | Stripe sig verified (good). Stripe replay protection absent (#3). WhatsApp HMAC verified, fail-closed on missing secret (good).                       |
| A09 Logging & Monitoring Failures  | PASS-ish      | Structured JSON logs in prod, traceId/spanId, audit log on tenant-spoof attempts. Improve: rate-limit-bypass alerts, SSE subscriber metrics            |
| A10 SSRF                            | PASS          | No outbound HTTP from user input found in image upload or AI integration. `S3_ENDPOINT` is server-configured                                          |

---

## What I would patch in the next 48 hours

1. **Tenant-scope `OrderSseService`** — store tenant id alongside each `SseEmitter`, filter `broadcast()` by event tenant. Add an integration test (`OrderSseServiceTenantIsolationTest`) that creates two emitters for two tenants and asserts the wrong-tenant emitter receives nothing.
2. **Make `verify` mandatory on `GET /public/orders`** — return 400 when missing; this stops the IDOR immediately. Follow up with magic-link tokens.
3. **Add Stripe event idempotency** — `processed_stripe_events(event_id PRIMARY KEY, processed_at)` table, insert inside the same transaction as the order/financial mutation. Reject duplicates with 200 OK + log.
4. **Fix `reviews_tenant_write` policy** — drop the `app.tenant_id`/`customer_email` OR clause, require both `tenant_id = current_tenant_id()` AND `EXISTS (SELECT 1 FROM orders WHERE id = order_id AND customer_email = app.customer_email)`.
5. **Strip access/refresh tokens from NextAuth `session()` callback** — keep them in the encrypted JWT cookie only. Update dashboard fetch helpers to call BFF routes that proxy to Core.
6. **Add `sync.RWMutex` around `JWTMiddleware.publicKeys`** in edge-go and add `aud` claim verification.
7. **Per-tenant rate limiter in edge-go** — switch global channel to keyed `golang.org/x/time/rate.Limiter` map; key by JWT tenant.

## What I would NOT ship to production until fixed

1. **The SSE cross-tenant broadcast bug** — this is a confirmed multi-tenant data leak. Production deployment of this version exposes every order to every authenticated user across every tenant.
2. **The customer-orders IDOR** — this is a regulated PII leak. UK GDPR Article 32 requires "appropriate technical measures"; an opt-in verification check does not qualify.
3. **The Stripe webhook with no replay protection** — financial integrity bug. One Stripe retry storm corrupts revenue + VAT submissions across the platform.
4. **Frontend access-token exposure** — combined with the lack of CSP and any user-generated content that could host XSS, this is a single-XSS-to-full-tenant-takeover bug.
5. **Edge gateway shared global rate limiter** — single-tenant DoS is trivial; SLA-breach risk on day one.
6. **No role-based authorisation** — until every staff/kitchen/intern user can be prevented from calling `/gdpr/customers/{id}/erase` and `DELETE /orders/{id}`, the platform is one disgruntled employee away from a data-loss incident.

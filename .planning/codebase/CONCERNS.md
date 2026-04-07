# Codebase Concerns

**Analysis Date:** 2026-04-07

## Tech Debt

**Broad Exception Handling:**
- Issue: Multiple services catch `catch (Exception e)` broadly instead of specific exception types
- Files: `core-java/src/main/java/uk/jtoye/core/storage/StorageService.java:143`, `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:101`, `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java:144,186,227,257`, `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java:92,170,197,250`, `core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java:38`
- Impact: Masks specific errors, makes debugging harder, hides transient vs permanent failures, prevents proper circuit breaker behavior
- Fix approach: Replace broad `Exception` catches with specific types (IOError, StripeException, etc.). Log full stack traces for unexpected errors.

**Rate Limiter Hardcoded in Edge Service:**
- Issue: Rate limiting values (20 RPS, 40 burst) hardcoded in Go code at `edge-go/cmd/edge/main.go:82`
- Files: `edge-go/cmd/edge/main.go:82`, environment config not wired (`WHATSAPP_RATE_LIMIT_RPS`, `WHATSAPP_RATE_LIMIT_BURST`)
- Impact: Cannot adjust rate limits without recompiling/redeploying edge service
- Fix approach: Extract `20` and `40` to environment variables with fallback defaults, parse and validate at startup

**Email Notification Infrastructure Missing:**
- Issue: Email service infrastructure in place (`core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java`) but no SMTP provider configured
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java` calls email service; service has extension points but no transport
- Impact: Order notifications will silently fail to send. Customers won't receive order status updates.
- Fix approach: Choose SMTP provider (SendGrid, SES, Mailhog), configure `spring.mail.*` properties, add provider decision to onboarding docs

**WhatsApp Order Creation Not Wired:**
- Issue: Parser exists in `edge-go/internal/whatsapp` but POST /orders endpoint doesn't call it. No shop assignment strategy.
- Files: `edge-go/cmd/edge/main.go`, `edge-go/internal/whatsapp` (parser only)
- Impact: WhatsApp orders cannot be created. Feature advertised but non-functional.
- Fix approach: Wire WhatsApp parser to POST /orders, implement shop assignment (geography, tags, manual mapping), add idempotency key

## Known Bugs

**Broad Exception Catches in Order Event Publishing:**
- Symptoms: Order state changes may fail silently if `RabbitTemplate.convertAndSend()` throws unexpected exception
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java:38`
- Trigger: Network issue, RabbitMQ misconfiguration, or serialization error while publishing order state change event
- Workaround: Check RabbitMQ logs and Core API logs separately; may not correlate

**Image Analysis Service Fails Silently on Parse Error:**
- Symptoms: Product image analysis returns confidence 0 instead of error, falls back to user description
- Files: `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java:144,186,227,257`
- Trigger: LLM returns malformed JSON, network timeout, or model unavailable
- Workaround: Check logs; currently only logged, not surfaced to UI

**BulkImportService Error Handling in Batch Processing:**
- Symptoms: Partial batch imports may succeed even if some rows fail silently
- Files: `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java:92,170,197,250`
- Trigger: CSV parsing fails on row N; rows 1 to N-1 imported but N onwards silently dropped
- Workaround: None; reimport with corrected CSV

## Security Considerations

**Stripe Webhook Signature Verification:**
- Risk: Incorrect signature handling could accept forged Stripe webhook events
- Files: `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:94-104`
- Current mitigation: Uses `Webhook.constructEvent()` from official Stripe SDK; signature verified against `stripe.webhook.secret`
- Recommendations: 
  - Ensure `stripe.webhook.secret` is NOT logged or exposed in error messages
  - Add replay attack protection: reject events with `created` timestamp > 5 minutes old
  - Monitor webhook processing latency to detect DDoS (high volume, low-latency, repeated event IDs)

**Tenant Context Isolation in Async Email Processing:**
- Risk: Email notifications run async; if tenant context lost, notifications could leak between tenants
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java:47-54,56-60`
- Current mitigation: `TenantContext.set()` before email send, `.clear()` in finally block; explicit session-level config set
- Recommendations:
  - Unit test `TenantContext` clearing in exception scenarios (e.g., email service throws)
  - Consider passing tenantId as method parameter to `EmailNotificationService` to avoid ThreadLocal dependency
  - Add audit logging of tenant context switches in security-sensitive paths

**JWT Token Validation:**
- Risk: Missing or invalid tenant claim in JWT allows default/null tenant assignment
- Files: `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java`, `core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java:79`
- Current mitigation: JWT token required on all endpoints except `/health`; tenant claim extraction in aspect
- Recommendations:
  - Add explicit validation: reject requests with missing/empty tenant claim
  - Log failed token extractions (malformed, missing claims) for security audits
  - Consider implementing rate limiting per tenant, not globally

**RLS Policy Edge Cases:**
- Risk: RLS policies bypass if Keycloak tenant claim name changes or token parsing fails silently
- Files: All repository queries depend on RLS; `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java` sets context
- Current mitigation: Aspect sets tenant context before each request; SQL `SET app.current_tenant_id` at session level
- Recommendations:
  - Add integration test that verifies queries fail if tenant context NOT set (break the safety net to ensure it works)
  - Monitor slow queries that bypass RLS (would indicate missing tenant filter)
  - Document RLS policy schema in README for future maintainers

## Performance Bottlenecks

**N+1 Query Risk in Order with Items:**
- Problem: `Order.java:102` has `@OneToMany` for items; if service loads orders in list without JOIN FETCH, will trigger N queries
- Files: `core-java/src/main/java/uk/jtoye/core/order/Order.java:102`, repository queries in `core-java/src/main/java/uk/jtoye/core/order/OrderService.java`
- Cause: Lazy loading default; if pagination or filtering loops over orders, each `.getItems()` triggers DB hit
- Improvement path:
  - Add custom query with `LEFT JOIN FETCH orderItems` for list endpoints
  - Use `@EntityGraph` on repository methods that return paginated results
  - Profile with Spring Boot actuator + Micrometer to detect slow pages

**Image Analysis with Ollama Over Network:**
- Problem: Every product image upload triggers HTTP call to Ollama LLM; if model runs locally but on different container, network latency adds 2-5s per image
- Files: `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java`
- Cause: Synchronous wait for LLM response during upload; no caching of similar images
- Improvement path:
  - Make analysis async: return early, process in background, notify via SSE
  - Cache analysis results by image hash or filename pattern
  - Add timeout to Ollama calls (currently `WebClient` default may be infinite)

**Storage Service S3 Operations Without Connection Pooling Limits:**
- Problem: Each upload/delete hits S3 synchronously; if workload spikes, can exhaust thread pool
- Files: `core-java/src/main/java/uk/jtoye/core/storage/StorageService.java:84-92,138-141`
- Cause: `S3Client.putObject()` blocks; default `S3AsyncClient` not used
- Improvement path:
  - Switch to `S3AsyncClient` for non-blocking S3 calls
  - Add circuit breaker to S3 operations (already done for Stripe, missing here)
  - Set connection pool limits in S3Client builder

**Large File Bulk Import Processing:**
- Problem: `BulkImportService.java` loads entire CSV into memory before parsing; no streaming approach
- Files: `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java`
- Cause: File read entirely before processing; multiple passes over data
- Improvement path:
  - Stream CSV line-by-line instead of loading all into memory
  - Process in batches (100-row chunks) to allow transaction commits and progress checkpoints
  - Add cancel/resume for long-running imports

## Fragile Areas

**ImageAnalysisService LLM Prompt Reliability:**
- Files: `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java:37-82`
- Why fragile: Hard-coded prompt with cultural examples and JSON schema. If Ollama/Claude model updates, behavior may change. No schema versioning.
- Safe modification: 
  - Externalize prompt to config file with version tracking
  - Add schema validation with `@JsonSchema` after parsing
  - Add tests with real food images (Nigerian dishes, etc.) to detect model drift
  - Create separate test suite for image analysis using fixed test images
  
**RabbitMQ Event Publishing in Transaction Boundaries:**
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java`, `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java`
- Why fragile: Order state updated in DB, then event published to RabbitMQ. If event fails to send, DB transaction already committed—event lost.
- Safe modification:
  - Use RabbitMQ publisher confirms (add `spring.rabbitmq.publisher-confirms=true`)
  - Wrap event publish in retry logic with exponential backoff
  - Consider outbox pattern: save event to DB in same transaction, publish from separate poller
  - Add integration test: verify order state + event both succeed or both fail

**Stripe Webhook Handler Idempotency:**
- Files: `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:108-113`
- Why fragile: No idempotency key tracking; if webhook delivered twice (Stripe retries), order may be double-processed
- Safe modification:
  - Store received Stripe event IDs in DB with timestamp
  - Check `event.id` before processing; if seen before, skip and return 200 OK
  - Add test case: send same webhook twice, verify payment marked CAPTURED only once

**Order Status State Machine Transitions:**
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineService.java`
- Why fragile: Complex state transitions (DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED) with multiple entry points (UI, Stripe webhook, admin)
- Safe modification:
  - Document all valid transitions in README or code
  - Add guard clauses: reject invalid transitions with descriptive error
  - Unit test every transition pair (matrix of 6×6 = 36 cases)
  - Add @CircuitBreaker around state changes to prevent cascading failures

## Scaling Limits

**RabbitMQ Dead Letter Queue Management:**
- Current capacity: DLQ created but no cleanup policy; messages accumulate indefinitely
- Limit: After extended outage, DLQ may grow to GBs; no alerts configured
- Scaling path:
  - Configure message TTL on DLQ (e.g., 7 days)
  - Add metrics to count DLQ depth
  - Create admin endpoint to review/replay/discard DLQ messages
  - Set up alerts when DLQ depth > 1000

**Rate Limiting Per Tenant vs Global:**
- Current capacity: Edge service uses global rate limiter (20 RPS across all tenants)
- Limit: Single high-volume tenant exhausts limit for others; no tenant isolation
- Scaling path:
  - Implement token bucket per tenant-ID extracted from JWT
  - Set soft limit (warn at 80%, block at 100%)
  - Add metrics per tenant to detect abuse patterns
  - Allow tier-based rate limits (paid tiers get higher limits)

**Redis Caching Eviction:**
- Current capacity: Default Redis eviction policy may be `noeviction` (blocking inserts on full)
- Limit: If cache fills without eviction, new caching attempts block the app
- Scaling path:
  - Set explicit `maxmemory-policy=allkeys-lru` in Redis config
  - Monitor cache hit/miss ratios with Micrometer
  - Adjust TTLs for high-churn data (orders, customer sessions)

**Frontend SSE Connection Limits:**
- Current capacity: Each browser holds open SSE connection; Jetty has max threads per request
- Limit: If >500 concurrent users open storefront, SSE connection pool exhausted
- Scaling path:
  - Implement WebSocket instead of SSE for multiplexed connections
  - Add connection rate limiting per IP
  - Graceful degradation: if SSE unavailable, fall back to polling with backoff

## Dependencies at Risk

**Stripe SDK Version (28.2.0):**
- Risk: If Stripe API changes, old SDK version may break
- Impact: Payment creation and webhook handling fail; orders cannot be paid
- Migration plan: Monitor Stripe API changelog; upgrade SDK quarterly; test webhook event parsing with new event types

**Spring Boot 3.4.2 with Java 21 Toolchain:**
- Risk: Java 21 language features (records, sealed classes) not compatible with older Spring Boot versions if downgrade needed
- Impact: Lock-in to Java 21; cannot use lower versions
- Migration plan: None needed if staying on Spring Boot 3.x; Java 21 is LTS. Monitor deprecations in Spring Boot release notes.

**Resilience4j Circuit Breaker (2.2.0):**
- Risk: Custom timeout handling in circuit breaker may differ from Spring Boot defaults
- Impact: Unexpected circuit breaker state (open/closed) during deployment
- Migration plan: Document all circuit breakers in README; verify timeout values match SLA for each external service

**Hibernate Envers for Auditing:**
- Risk: Complex schema with audit tables; migration/backup complexity scales with data
- Impact: Schema migrations become slow; audit data not easily queryable without special tools
- Migration plan: Consider replacing with simple audit log table if audit queries become bottleneck; Envers good for now at current scale

## Missing Critical Features

**No Email Provider Implementation:**
- Problem: Email notification service exists but has no SMTP backend; customers won't be notified of orders
- Blocks: Customer engagement, order tracking, payment confirmations
- Decision needed: SendGrid (pay-per-send, fast setup), AWS SES (cheap, requires verification), Mailhog (dev-only, free)

**No Delivery Management System:**
- Problem: Orders have no delivery address, time slots, or courier integration
- Blocks: Delivery-based businesses cannot use platform
- Decision needed: Build in-house, integrate Deliveroo API, or MVP with simple postcode radius

**No Self-Service Tenant Signup:**
- Problem: All tenants created manually via SQL + Keycloak admin panel
- Blocks: Cannot scale to 100s of shops
- Decision needed: Build signup flow, Stripe billing integration, automated tenant provisioning

**No Payment Processing UI:**
- Problem: Stripe integration in backend; frontend has no payment form
- Blocks: Customers cannot pay; only "cash on delivery" works
- Decision needed: Use Stripe hosted checkout vs embedded form; PCI compliance

## Test Coverage Gaps

**ImageAnalysisService with Multiple LLM Providers:**
- What's not tested: Fallback from Anthropic to Ollama on API key missing; different response formats between providers
- Files: `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java`
- Risk: Provider switch in prod may fail silently with confusing error messages
- Priority: High — image analysis is customer-facing

**BulkImportService Error Recovery:**
- What's not tested: CSV with 10,000 rows where row 5,000 is malformed; does import stop or continue?
- Files: `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java`
- Risk: Unpredictable behavior with large imports; data loss without visibility
- Priority: High — bulk import used for initial product onboarding

**RabbitMQ Event Publishing Failure Scenarios:**
- What's not tested: RabbitMQ unavailable during order state change; does order save anyway? Is event lost?
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java`
- Risk: Notifications never sent; no retry mechanism; customer left in limbo
- Priority: High — core business flow

**Stripe Webhook Signature Verification:**
- What's not tested: Malformed webhook payload; wrong signature; replay attack (duplicate event_id)
- Files: `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java`
- Risk: Forged payments accepted; order paid twice
- Priority: Critical — payment system

**RLS Policy Enforcement:**
- What's not tested: Can tenant-A query tenant-B's orders if they somehow obtain the UUID? RLS block it?
- Files: All repositories; depends on database RLS policies
- Risk: Data leakage between tenants
- Priority: Critical — data isolation

**Frontend Complex Page Component Logic:**
- What's not tested: Dashboard orders page (935 lines, `frontend/app/dashboard/orders/page.tsx`) with filtering, sorting, pagination all together
- Files: `frontend/app/dashboard/orders/page.tsx`, `frontend/app/dashboard/products/page.tsx` (889 lines), `frontend/app/dashboard/shops/page.tsx` (600 lines)
- Risk: UI state bugs under edge cases (empty list + filter + sort all active at once)
- Priority: Medium — impacts UX but not data integrity

---

*Concerns audit: 2026-04-07*

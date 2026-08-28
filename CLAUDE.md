<!-- GSD:project-start source:PROJECT.md -->
## Project

**J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved**

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. This milestone turns to vendor operational control: unblocking stuck onboarding, scoping access per shop within a tenant, hardening image handling (copy-on-write media_asset model + safe async upload pipeline), and fixing dashboard mobile — plus extending the AI/automation surface (outbound webhooks + mutating MCP tools) on a committed local-k8s overlay.

**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility.

### Constraints

- **Tech stack**: Must use existing stack — Spring Boot 3.5.16, Next.js 16, Go 1.26, PostgreSQL 15
- **Java version**: JDK 21 (JDK 25 incompatible with Gradle 8.10)
- **Multi-tenancy**: All new features must respect RLS and TenantContext
- **Testing**: All new code requires tests — project standard is 3188 logical invocations passing (1716 Java `@Test` methods across 271 files + 1230 Jest `it/test` blocks across 120 files + 81 top-level Go `Test*` funcs across 11 files + 113 Playwright `test()` blocks across 22 specs + 48 MCP-server vitest `it/test` blocks across 8 files under `mcp-server/`). Multiple Java files use Testcontainers (real Postgres + RLS). Counts are the single source of truth in `docs/metrics.json`, enforced by **two** gates in `.github/workflows/docs-freshness.yml`, one per half of the loop: `scripts/docs-freshness.sh` (source tree → `docs/metrics.json`) and `scripts/check-doc-metrics.sh` (the numbers quoted in prose here, in `AGENTS.md` and in `README.md` → `docs/metrics.json`). Both fail the build on drift. The second gate exists because the first never opened a doc: README sat at `921` for months while the tree was at `1895`, and `docs-freshness.sh` was green on every one of those commits.
- **Docker**: Always rebuild ALL containers after code changes before E2E testing
- **Runtime & deploy topology (compose and k8s are two layers, both kept — not redundant)**: Docker **Compose** (`docker-compose.full-stack.yml`, incl. Mailhog) is the **canonical local dev + E2E runtime** — driven by `scripts/start-dev.sh` + Playwright/`webapp-testing`; this is where you develop and test. **Kubernetes** kustomize (`k8s/base` + `k8s/staging|production` overlays) is the **staging/prod deploy target** — driven by the `ci-cd.yaml` deploy job + `scripts/deploy.sh` (sealed secrets, networkpolicies); this is where you ship. Neither is retired. **XOR applies only at *local* runtime**: run Compose **or** a local minikube, never both at once (they share the dev DB) — local dev defaults to Compose. (Decided 2026-07-15; supersedes any "we only need one" reading.)
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Java 21 - Core API (Spring Boot 3.5.16)
- TypeScript 5 - Frontend (Next.js 16.3.2, React 19)
- Go 1.26 - Edge API gateway (Gin)
- SQL (PostgreSQL) - Database migrations via Flyway
- YAML - Configuration management
## Runtime
- JVM (Java 21) - Core API execution
- Node.js 24+ - Frontend build and runtime
- Go 1.26 runtime - Edge gateway
- PostgreSQL 15 - Database
- Gradle 8.10+ (Kotlin DSL) - Java/Spring Boot build
- npm - Node.js dependencies
- go mod - Go dependencies
- Gradle: Present (`gradle-wrapper` properties)
- npm: package-lock.json (implicit)
- Go: go.sum
## Frameworks
- Spring Boot 3.5.16 - Web framework, dependency injection, auto-configuration
- Spring Data JPA - ORM and database abstraction
- Spring Security - Authentication and authorization
- Spring OAuth2 Resource Server - JWT/OIDC token validation
- Spring AOP - Aspect-oriented programming
- Spring Cache - Distributed caching with Redis
- Spring AMQP - RabbitMQ message queue integration
- Spring Actuator - Metrics and health endpoints
- SpringDoc OpenAPI 2.8.6 - Swagger/OpenAPI documentation
- Micrometer Prometheus - Metrics export
- Micrometer Tracing (Brave/Zipkin) - Distributed tracing
- Next.js 16.3.2 - React framework with file-based routing
- React 19 - UI component library
- React Hook Form 7.85.0 - Form state management
- Next-Auth 5.0.0-beta.32 - Authentication middleware
- TailwindCSS 3.4.1 - Utility-first CSS framework
- Radix UI - Headless component library
- Zod 4.4.3 - Schema validation
- Gin v1.12.0 - HTTP routing and middleware
- golang-jwt/jwt v5 - JWT validation
- uber/zap - Structured logging
- sony/gobreaker - Circuit breaker pattern
- JUnit 5 - Java test framework
- Testcontainers 1.21.4 - Docker-based integration testing
- Spring Boot Test - Testing utilities and test containers
- Jest 29.7.0 - JavaScript test runner
- @testing-library/react - React component testing
- @playwright/test 1.62.1 - E2E browser automation
- Spring Boot Gradle Plugin 3.5.16 - JAR packaging
- Flyway - Database migration management
- Lombok - Boilerplate reduction (code generation)
- MapStruct 1.6.3 - Type-safe DTO mapping
## Key Dependencies
- PostgreSQL JDBC Driver 42.7.13 - Database connectivity
- Hibernate ORM (via Spring Boot 3.5.16) - JPA implementation
- Hibernate Envers - Audit history tracking
- AWS SDK v2 (2.53.2) - S3 API for image storage
- Stripe React/JS 6.8.2, 9.14.0 - Payment processing UI integration
- Axios 1.19.0 - HTTP client for API calls
- Framer Motion 12.43.0 - Animation library
- Recharts 3.10.1 - Charts and data visualization
- Redis 7 - Session and cache store
- RabbitMQ 4.3.4 - Message queue (AMQP)
- Keycloak 24.0.5 - Identity provider (OIDC/OAuth2)
- MinIO (latest) - S3-compatible object storage for images
- Ollama (latest) - Local LLM for image analysis
- Mailhog v1.0.1 - Local SMTP for email testing
- Resilience4j 2.4.0 - Circuit breakers and retry logic
- Bucket4j 8.10.1 - Token bucket rate limiting
- Stripe Java SDK 33.3.0 - Payment intent creation and webhook handling
- OpenPDF 2.0.3 - PDF generation for allergen labels
- Spring Data Redis (Lettuce) - Redis connection pooling
## Configuration
- `.env` file (required for docker-compose)
- Environment variable precedence: Spring profiles (dev, test, staging, prod)
- Config location: `core-java/src/main/resources/application*.yml`
- `application.yml` - Base configuration (all profiles)
- `application-dev.yml` - Development profile (localhost defaults)
- `application-test.yml` - Test profile (H2 in-memory, testcontainers)
- `application-staging.yml` - Staging production-like settings
- `application-prod.yml` - Production hardened settings (no SQL logging, higher pool sizes)
- `dev` (default in docker-compose)
- `test` (for unit/integration tests)
- `staging` (pre-production validation)
- `prod` (hardened security and performance)
- Flyway migrations: `core-java/src/main/resources/db/migration/`
- Migration strategy: Versioned SQL files (V1__, V2__, etc.)
- Current schema version: V64 (V64 [#647 — the grant that a fresh deployment could not make for itself]: `GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime`, guarded on the role existing. Since the SEC-04/#552 runtime-migrator split the app connects as the DML-only `jtoye_runtime`, and TRUNCATE is a DISTINCT privilege NOT implied by DELETE. `infra/db/init/00-create-db.sql` grants that role DML but CANNOT name `postcode_centroid` — V61 creates it later, so at cluster-init time there is nothing to grant on — and its comment therefore told an operator to run `infra/db/create-runtime-role.sql` by hand after the first migration. `e2e-nightly.yml` tears down with `down -v`, so every night began on a fresh volume where nobody had, and `PostcodeCentroidImporter` crash-looped on `permission denied for table postcode_centroid` every ~27s for **14 consecutive nights** (2026-08-11 to 2026-08-24) without executing one Playwright test. A provisioning step only a human can perform is not provisioning. The grant is deliberately TABLE-SCOPED and not added to `ALTER DEFAULT PRIVILEGES`: that would hand the DML-only application TRUNCATE on every tenant table, which is the whole thing the split prevents. `postcode_centroid` is public reference data — no `tenant_id`, no RLS — so a TRUNCATE on it alone carries no cross-tenant risk. The role guard is load-bearing: Testcontainers migrates against a bare Postgres where `jtoye_runtime` does not exist, and an unguarded GRANT fails "role does not exist" and reds every integration test. V63 V63 [Phase 31-10 Consumer-Safety — the order line records the allergen picture that was true WHEN THE ORDER WAS PLACED, LGL-03]: adds `order_items.allergen_mask` and `order_items.allergen_flag_mask`, both nullable INT. `order_items` carried NO allergen data at all, so the checkout panel and the kitchen display could only be fed by joining back to `products.allergen_mask` at READ time — and under a live join a vendor who edits a product's mask AFTER an order is placed silently changes what the customer is recorded as having acknowledged and what the kitchen ticket shows: the customer acknowledged set A, the kitchen sees set B, and **no record of A exists anywhere**. Measured: replacing the write-time snapshot with a live join makes the immutability test read `expected: 65 / but was: 512` after a vendor edit, and — the finding the plan did not anticipate — a live join **destroys the "not recorded" state entirely**, so a historic order silently claims a set it never had. `order_items` already snapshots `product_name` (V30) for exactly this class of reason; the mask is snapshotted beside it, at the same moment. **`allergen_mask`** is the vendor's DECLARED mask copied at write time, the same 14-bit UK FSA layout as `products.allergen_mask` (`uk.jtoye.core.product.AllergenCatalog`, bits 0..13) — the legally operative statement. **`allergen_flag_mask`** is the ADVISORY reconciliation result from 31-04's `OrderAllergenAggregator`: the bits this product's EMPHASISED ingredients text names but its declared mask omits. The two are structurally separate and the flag is **never OR-ed into the declaration** — a text heuristic must not rewrite a vendor's statement, because that would make the platform the author of an allergen claim it cannot stand behind AND would mask the vendor's underlying data error instead of surfacing it. Two integers rather than JSONB/TEXT[]: per LINE a reconciliation flag is exactly "an allergen bit" and the product is the row itself, so a per-row bitmask preserves "which product, which allergen" with no encoding; the human-readable NAME is deliberately NOT stored, because persisting it as prose would create a second, ungated copy of the catalogue frozen at write time — one row per order line — which is the exact drift the cross-language parity test (`AllergenCatalog` against `frontend/types/api.ts`) exists to prevent. The BIT is the fact; the name is a label. **NO BACKFILL, deliberately**: both columns are nullable and historic rows stay NULL, which reads as "not recorded". Inventing a mask for a past order from today's product rows would fabricate a record of what a past customer was shown — a worse defect than the one being fixed, because a fabricated record is indistinguishable from a real one. **NULL ("not recorded") and 0 ("the vendor declared none of the 14 regulated allergens") are DIFFERENT** and downstream must not conflate them. The snapshot is captured on BOTH write paths — the storefront and `OrderService.createOrder` — because the latter feeds the same kitchen display, and omitting it would have shipped every vendor/API/MCP order with no allergen data. The order-level aggregate is exposed on **`OrderDetailDto`, NOT `OrderDto`**, measured with a Hibernate-statistics probe (`itemsCollectionInitialisedAfterFindAll=false`, 7 orders → 7 extra prepared statements): putting it on the list DTO is an N+1, so the MCP `read-orders` tool gains the fields on its `orderId` DETAIL call and deliberately not on its `shopId` LIST call. V62 V62 [Phase 31-05 Consumer-Safety — the DSAR intake behind the published single point of contact, decisions D-16/D-17]: creates `dsar_request`, the platform-level UK-GDPR data-subject-request intake queue (ACCESS/ERASURE), plus a partial unique index on `idempotency_key` and the partial `idx_dsar_request_outstanding (received_at) WHERE completed_at IS NULL` that backs 31-09's claim query. D-17 is the whole design and is not negotiable: **intake is a request, execution is background** — a request thread lodges a row and stops, and 31-09's scheduled worker reads it, iterates tenants and does the work, so **no human ever holds cross-tenant read**. That is how a single cross-tenant DSAR desk is reconciled with a project that has refused a cross-tenant operator identity twice. **`dsar_request` is deliberately NOT tenant-scoped, and RLS here would be WORSE rather than safer**: an anonymous subject lodges from the public internet before any tenant is known (no JWT, no TenantContext, no `app.current_tenant_id` GUC), the request must be actioned across every tenant because UK GDPR Articles 15 and 17 give the subject one right against the controller and not one right per vendor, and with no `tenant_id` there is no predicate to write — a FORCED policy would return zero rows to the very worker that must read them, so the intake would keep returning 202, the queue would keep filling, nothing would ever be actioned, and every test would stay green because **a dead table is indistinguishable from an empty one** (the same liveness failure `RlsContractTest.everyRlsEnabledTableHasAtLeastOnePolicy` was added to catch, and the `postcode_centroid` argument from the other direction). It is exempted BY ADDITION in `RlsContractTest.EXEMPT_TABLES` with a written justification, so the schema-walk sweep itself is never weakened, and the exemption was proven load-bearing by removing it and watching the sweep name the table. **Only a hash is ever stored**: the subject is `subject_email_sha256`, a one-way digest over the lower-cased, trimmed, UTF-8 encoded address — the V42 `erasure_records` rule verbatim — with normalisation owned solely by `DsarIntakeService` so 31-09's worker can reproduce it exactly; the verification token is likewise held only as a digest with an expiry, because a readable token at rest is a bearer credential. `response_body` is a CONSTANT opaque acknowledgement carrying no request identifier and nothing derived from whether any tenant holds a match, so the endpoint cannot be used to enumerate which vendors hold an address. No Envers `_aud` mirror, deliberately: `erasure_records` is already the Article-17 proof row, and mirroring an operational queue would create a SECOND long-lived store keyed by a data subject. Idempotency is enforced on this table rather than in the shared V50 `idempotency_keys` store, MEASURED and not assumed: `IdempotencyService.execute` opens with `TenantContext.get().orElseThrow(MissingTenantContextException)` and `idempotency_keys` is keyed `(tenant_id, endpoint, idempotency_key)` under FORCE RLS, so a tenant-less caller cannot be served by it at all and would 500 before reaching storage; the unique index is on `idempotency_key` ALONE and not `(subject_email_sha256, idempotency_key)`, because the composite would let the same key carry a DIFFERENT address and insert a silent second erasure. Rows start `PENDING_VERIFICATION` and 31-09 owns the transition to `VERIFIED` — defaulting to VERIFIED was considered and rejected as arming an unverified erasure request, which is threat T-31-05-02 itself. V62 is sequential (head was V61) so the project-wide `spring.flyway.out-of-order=true` does not interact; plan 31-10 owns V63. V61 [Phase 33-02 Locality — the offline postcode substrate for CUST-01]: DDL ONLY, creating `postcode_centroid` (postcode TEXT PRIMARY KEY, latitude/longitude DOUBLE PRECISION NOT NULL) plus the partial composite index `idx_shops_lat_lon ON shops (latitude, longitude) WHERE latitude IS NOT NULL AND longitude IS NOT NULL`. `shops.latitude`/`longitude` themselves date from V16; only the index is new, and it is what makes 33-06's LEAKPROOF bounding-box prefilter cheap — PostgreSQL will only push a predicate below an RLS security barrier when the operator is leakproof, which plain float8 comparison is and a distance function is not. **`postcode_centroid` is deliberately NOT tenant-scoped**: no `tenant_id`, no RLS policy, no `_aud` mirror, because a public address's postcode is not tenant information and no customer data is held. It is exempted BY ADDITION in `RlsContractTest.EXEMPT_TABLES` with a written justification, so the schema-walk sweep itself is never weakened. **No data ships in the migration**: the 1,748,230-row OS Code-Point Open dataset is a gzipped classpath resource loaded at startup by `PostcodeCentroidImporter`, because ~46 MB of INSERTs would re-execute on every Testcontainers integration test that spins a fresh Postgres. **NO EXTENSION IS CREATED, and that is not a compromise** — measured on the live stack: `cube` is trusted-but-superuser, `earthdistance` is neither, PostGIS is absent entirely, and Flyway runs as `jtoye_app` (rolsuper=f, rolbypassrls=f, no CREATE on the database), so even the trusted extension fails; the available "fix" is a privilege escalation on the exact role the whole RLS wall is built around and is explicitly rejected. The invariant is enforced across the whole migration directory by `scripts/check-no-create-extension.sh`, wired into `ci-cd.yaml`. Accuracy is ~100 m postcode-unit centroids and coverage is **Great Britain only** (Code-Point Open excludes Northern Ireland) — recorded in `core-java/src/main/resources/geo/SOURCE.md` under OGL v3. V60 [Phase 27-01 Media Durability — a broker outage no longer destroys vendor uploads]: adds `media_asset.process_attempts` (INT NOT NULL DEFAULT 0) + `quarantine_expires_at`/`quarantine_reclaimed_at` (TIMESTAMPTZ), the nullable `media_asset_aud` mirrors, and two partial indexes (quarantine sweep + `media_event_outbox` by asset). The transactional outbox protected the *event*; nothing protected the *object* — `MediaPendingReaper` selected on status ALONE (`findStalePending`: PENDING AND created_at < now() - 15 min) and permanently deleted the quarantined source bytes, while during a RabbitMQ outage `MediaEventOutboxFlusher` is still backing off (5+10+20+40+80+160+300+300+300s ≈ 20 min) and the outbox row is provably still PENDING at ~7–8 attempts when the 15-minute cutoff fires. These columns let the sweep tell "never dispatched" from "dispatched and stalled", and let the bytes be retained on a declared horizon instead of destroyed as a 15-minute accident. V59/V58/V53 [Phase 24 Image Architecture — CoW Assets + Safe Upload Pipeline]: V59 adds media_asset.version (BIGINT NOT NULL DEFAULT 0) — a JPA @Version optimistic lock closing the MediaPendingReaper↔MediaProcessingWorker race so a stale reaper sweep can never flip an asset the worker already moved to ACTIVE back to FAILED (code-review WR-02). V53 ships the copy-on-write media_asset model — media_asset (+ media_asset_aud Envers mirror) + the product_media join (product_id/asset_id/is_primary/sort_order), all ENABLE+FORCE RLS tenant-scoped via the safe current_tenant_id() helper, (tenant_id, sha256) unique dedup index, ref-counted physical MinIO delete only at COUNT(*)=0, and a per-tenant set_config backfill loop wrapping existing products.image_url/additional_image_urls[] as status=ACTIVE assets as-is (no re-pipeline, dual-read D-03a keeps the flat columns this phase). V58 adds a DEDICATED media_event_outbox (cloned from payment_event_outbox: SKIP LOCKED claim + exponential backoff + resurrect, its own media.events exchange — sidesteps the outbox_flusher_dispatch_trap, no PaymentEventOutboxFlusher edit). Every upload passes a safe async pipeline (reject-early Content-Length 413 → quarantine + PENDING row → outbox → @RabbitListener worker that pins the tenant GUC, magic-byte-sniffs jpeg/png/webp, header-read decompression-bomb guard, decode-verifies, strips EXIF, transcodes to a WebP derivative + 400px thumbnail under the jtoye.media.* config budget) storing ONLY the validated normalized derivative, never raw bytes; the 202 accept carries an Idempotency-Key contract + RFC 7807 typed errors (D-06). The vendor UI (IMG-04) renders PENDING→processing / ACTIVE→WebP w/ width+height (CWV, D-07) / FAILED→reason+Re-upload / flagged-ACTIVE→Keep-or-Replace review queue (GET /api/v1/media/review-queue + POST /{assetId}/keep). V57/V52 [Phase 23 Vendor-Scoped Access]: V52 ships shop_staff + shop_staff_aud + user_directory — the vendor→shop application-layer access boundary layered under the RLS tenant wall, all ENABLE+FORCE RLS via the safe current_tenant_id() helper (never the raw ::uuid cast), functional unique index over (tenant_id, user_id, COALESCE(shop_id, zero-uuid)); user_directory is a login-populated grant-target picker (RLS, no _aud — high-churn derived cache). V57 adds shop_staff.grant_source (JIT|OPERATOR) + aud mirror (backfill created_by IS NULL→JIT, NOT NULL DEFAULT 'JIT', NO RLS policy → RlsContractTest green): under the config-injected strict-scoping switch ON, a JIT-sourced tenant-wide GROUP_ADMIN is de-honoured (a day-one auto-provisioned user genuinely becomes scoped) while OPERATOR grants + realm admins stay honoured, with the oldest JIT admin retained as a WARN-logged bootstrap when no OPERATOR admin exists (no tenant can lock itself out on the flip); strict-scoping defaults OFF (day-one JIT auto-provision preserved). V56/V55/V54 [Phase 22 Notifications & Comms]: added the notifications/webhooks tables — notification_consent (V54), webhook_subscription (V55), webhook_delivery (V56) — all ENABLE+FORCE RLS tenant-scoped; V53 remains RESERVED for Phase 24 (media_asset), so spring.flyway.out-of-order=true stays required. V51 RLS uuid-cast safety [Issue #113 / P3-11]: removes the raw `current_setting('app.current_tenant_id', true)::uuid` cast — the latent 22P02 bug class V39 fixed for the three storefront SELECT policies — from all 10 remaining raw-cast policies (payment_event_outbox_tenant, reviews_tenant_write, refunds_tenant_policy + refunds_aud, vendor_onboarding x4, processed_order_events_tenant, idempotency_keys_tenant), routing each through the safe `current_tenant_id()` helper; ALSO hardens `current_tenant_id()` itself by guarding its final `RETURN v::uuid` so a non-UUID GUC fails filtered (NULL → no rows) not errored (22P02). No data change, tenant semantics identical under a valid GUC; the `tenant_id::text = current_setting(...)` TEXT-comparison policies are deliberately untouched (no cast, no 22P02 risk). Permanent RlsContractTest.noPolicyUsesRawTenantGucCast pg_policy sweep guards against reintroduction; DEFERRED: the guest-tracking app.customer_email GUC DB-guard (#113 third item — TEXT comparison, new mechanism). Also removes the /ws?token= handshake query-param JWT path (JwtHandshakeInterceptor deleted; STOMP CONNECT Authorization header is the sole token source). V50 idempotency_keys [Issue #204 / AI-2]: tenant-scoped ENABLE+FORCE RLS dedup store keyed (tenant_id, endpoint, idempotency_key), request_hash + response_status/body columns, no _aud — mirrors V47; backs the uniform Idempotency-Key header contract adopted by orders.create + customers.create via a generic @Transactional IdempotencyService.execute (reserve-first INSERT ON CONFLICT DO NOTHING + defensive set_config GUC pin); same-key replay returns the original response, in-flight race → 409, same-key/different-body → 422; response_body carries customer PII so FORCE RLS is load-bearing, proven under the NOSUPERUSER role-downgrade. V49 Keycloak deprovisioning on offboard [Issue #102 remainder]: tenants.keycloak_deprovisioned_at nullable TIMESTAMPTZ — stamped only when ALL of an offboarded tenant's Keycloak users have been disabled + logged out across the configured realms; the identity-layer complement to TenantStatusInterceptor's request rejection so a stolen/cached token can no longer mint at the IdP. Deprovisioning runs best-effort AFTER the offboard tx commits (TransactionSynchronization.afterCommit → REQUIRES_NEW), so a Keycloak outage never rolls back the offboard (marker stays NULL, ERROR logged); an admin re-trigger endpoint POST /api/v1/admin/tenants/{id}/keycloak/deprovision recovers OFFBOARDED tenants (idempotent). Fully INERT by default: jtoye.keycloak.admin.enabled=false + empty base-url → one WARN no-op + RFC 7807 400 "not configured"; tenants stays RLS-free (no policy change). V48 tenant lifecycle + Stripe Connect [Issue #102]: tenants.status/plan/contact fields + suspended_at/offboarded_at + stripe_account_id/stripe_connect_status — the tenants registry stays deliberately RLS-free (role-gated admin API is the lifecycle writer; TenantStatusInterceptor rejects SUSPENDED/OFFBOARDED traffic), and MARKETPLACE orders route as Stripe destination charges to the linked ENABLED connected account per ADR-0001 Decision 2; V47 processed_order_events [QA-council disc-20260712-010550 FIX-2/H1]: semantic-key (tenant_id, order_id, new_status) dedup table for the at-least-once ORDER_STATE_CHANGED consumer, ENABLE+FORCE RLS tenant-scoped, mirrors the processed_stripe_events idempotency precedent; V46 outbox reliability [Issue #93]; V45 Phase 19 full-frontend overhaul [UIX-04]: orders.fulfilment_type + UK delivery-address columns + orders_aud mirror — enables checkout delivery-address capture + fee-before-payment and GDPR address scrub; V44 FTS tail [Issue #96 — filled reserved slot AFTER V45/V46 shipped, so spring.flyway.out-of-order=true is required and set in all profiles]: pg_catalog.ts_match_vq LEAKPROOF (superuser-only; graceful WARNING + documented manual step when the migration role lacks superuser) + idempotent tenant-looped backfill of NULL search_vector on products/shops; V43 vendor onboarding first slice [Phase 18]: vendor_onboarding + vendor_onboarding_gate + both Envers _aud mirrors, all ENABLE+FORCE RLS tenant-scoped; the onboarding state machine is the sole writer of Shop.published, gated by automatic BUSINESS_VERIFIED/FOOD_HYGIENE_RATING/ALLERGEN_DATA_COMPLETE checks; V42 GDPR erasure completeness [Issue #84]: erasure_records table — tenant-scoped, FORCE RLS, PII-free SHA-256 email hash — plus tenant-scoped UPDATE policies on orders_aud/customers_aud enabling the deliberate Article-17 PII scrub of append-only audit history; V41 PPDS/Natasha's Law label compliance [Issue #82]: products.allergen_spans/shelf_life_days/durability_type + products_aud mirrors, all nullable; V40 VAT ledger correctness [Issue #81]: products.vat_rate + financial_transactions.order_id + _aud mirrors + partial unique index uq_fin_tx_tenant_order + historical duplicate collapse)
- Next.js config: `frontend/next.config.mjs` (standalone output, image remotePatterns)
- TypeScript config: `frontend/tsconfig.json`
- ESLint: `frontend/eslint.config.mjs` (ESLint 9 flat config)
- Dockerfile: `edge-go/Dockerfile` (multi-stage, scratch-based runtime)
- Binary output: `/edge` executable
- Port: 8080 (customizable via PORT env var)
## Platform Requirements
- Docker & Docker Compose v2+ — the `docker compose` subcommand (for local stack)
- Java 21 JDK
- Node.js 24+
- Go 1.26+
- Git
- Gradle 8.10+ (included via wrapper)
- npm (included in Node.js)
- Docker (for building multi-stage images)
- Kubernetes (recommended) - See `k8s/` directory for manifests
- Docker container runtime
- PostgreSQL 15+ database
- Redis 7+ (external or managed service)
- RabbitMQ **3.13+** minimum (4.3 recommended — the dev/compose stack pins 4.3.4). The deployed staging/production broker's version is **unverified from this repository** — see `docs/runbooks/rabbitmq-broker-upgrade.md` and ADR-0002.
- Keycloak 24.0+ (external identity provider)
- AWS S3 (or S3-compatible storage like MinIO)
- SMTP server (SendGrid, AWS SES, etc.)
- Spring Boot: 3.5.16 (Java 21)
- PostgreSQL: 15-alpine
- Keycloak: 24.0.5
- Redis: 7-alpine
- RabbitMQ: 4.3.4-management-alpine
- MinIO: latest
- Go: 1.26-alpine
- Node.js: 24+
- Next.js: 16.3.2
## Performance Tuning
- Connection pooling: HikariCP
- Batch insert/update: Hibernate batch_size=20 (prod: 50)
- Query timeout: 30s
- Idle timeout: 10m
- Redis timeout: 2s (dev), 3s (prod)
- Lettuce pool: 8 active, 8 idle (dev), 20 active, 10 idle (prod)
- Default: 100 requests per minute per tenant
- Burst capacity: 20 requests
- Enabled by default (RATE_LIMIT_ENABLED=true)
- Sampling probability: 10% default (increase in dev)
- Zipkin endpoint: http://localhost:9411/api/v2/spans
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Naming Patterns
- Page routes: `page.tsx` (Next.js convention)
- Components: PascalCase (e.g., `CartProvider.tsx`, `SafeImage.tsx`)
- Utilities/helpers: camelCase (e.g., `api-client.ts`, `use-toast.ts`)
- Test files: co-located with `__tests__` directory or `*.test.tsx` suffix
- Hooks: `use<Name>` pattern (e.g., `useToast()`, `useCart()`)
- Entity classes: PascalCase (e.g., `Shop.java`, `Product.java`, `Order.java`)
- Service classes: `<Entity>Service.java` (e.g., `ShopService.java`)
- Controller classes: `<Entity>Controller.java` (e.g., `ShopController.java`)
- Repository interfaces: `<Entity>Repository.java` (e.g., `ShopRepository.java`)
- DTO classes: `<EntityName>Dto.java` or request `<Action><Entity>Request.java`
- Mapper interfaces: `<Entity>Mapper.java` (MapStruct convention)
- Exception classes: `<Reason>Exception.java` in `exception/` package
- Package structure: `internal/<domain>` layout
- Test files: `*_test.go` suffix (standard Go convention)
- Functions: camelCase (e.g., `SearchProducts()`, `CreateOrder()`)
- Types: PascalCase (e.g., `CreateOrderRequest`, `ProductSearchResult`)
- JavaScript/TypeScript: camelCase (e.g., `addItem()`, `removeItem()`, `updateQuantity()`)
- Java: camelCase (e.g., `getShopById()`, `createShop()`, `updateShop()`)
- Go: camelCase exported, lowercase unexported (e.g., `SearchProducts()`, `createRequest()`)
- TypeScript: camelCase (e.g., `itemCount`, `totalPennies`, `shopSlug`)
- Java: camelCase (e.g., `tenantId`, `productId`, `isPublished`)
- Database columns: snake_case (e.g., `created_at`, `delivery_fee_pennies`, `opening_hours`)
- TypeScript: PascalCase (e.g., `CartItem`, `CartContextValue`, `SafeImageProps`)
- Java: PascalCase for classes/records
- Java DTOs: `<Entity>Dto` (e.g., `ShopDto`, `OrderDto`)
- TypeScript: UPPER_SNAKE_CASE (e.g., `TOAST_LIMIT = 1`, `TOAST_REMOVE_DELAY = 1000000`)
- Java: UPPER_SNAKE_CASE for static finals
- Cache keys: use annotation values (e.g., `@Cacheable(value = "shops")`)
## Code Style
- Frontend: ESLint 9 **flat config** at `frontend/eslint.config.mjs`, run as `eslint .` (`npm run lint`). Next 16 removed `next lint`, so there is no Next-managed linting step and no legacy RC-style config file — the flat config is the only one. ⚠ Do NOT wrap the Next configs with `FlatCompat`: `eslint-config-next@16` ships native flat-config arrays at the `/core-web-vitals` and `/typescript` subpaths and they are spread directly; wrapping them crashes with a circular-structure error (recorded in that file's own header).
- Backend: Gradle/Spring Boot standard formatting (4-space indentation)
- Configuration: `eslint.config.mjs` spreads `eslint-config-next/core-web-vitals` and `eslint-config-next/typescript`, then layers the `jsx-a11y` accessibility rules (31-02 / LGL-02) on top — every one at `error`, none downgraded
- Frontend: ESLint with Next.js and TypeScript rules
- Backend: Gradle tasks enforce Spring Boot patterns and conventions
- TypeScript/JavaScript: 2 spaces (Next.js default)
- Java: 4 spaces
- Go: tabs (Go standard)
## Import Organization
- `@/` points to frontend root directory
- Used throughout: `@/components/`, `@/lib/`, `@/hooks/`, `@/types/`
## Error Handling
- Try-catch blocks in async operations
- Axios interceptors for global error handling (see `api-client.ts`)
- 401 responses trigger redirect to `/auth/signin`
- Errors passed to error boundary or logged to console
- Toast notifications for user-facing errors (not yet implemented pattern, but `useToast` hook available)
- Custom exception hierarchy: `ResourceNotFoundException`, `InvalidStateTransitionException` in `uk.jtoye.core.exception`
- Global exception handler: `GlobalExceptionHandler` annotated with `@RestControllerAdvice`
- Returns RFC 7807 Problem Detail responses with:
- Specific handlers for:
- Error wrapping with `fmt.Errorf("context: %w", err)` for error chain preservation
- Status code checks: `if httpResp.StatusCode >= 400`
- Circuit breaker integration: errors passed through `c.breaker.Execute()` wrapper
- Error logging: typically returned to caller, let client decide logging
## Logging
- Frontend: `console.log()`, `console.error()` (browser console)
- Backend Java: SLF4J with LoggerFactory (configured in Spring Boot)
- Go: `go.uber.org/zap` for structured logging
- Service layer: entry point of significant operations
- Condition checks: `log.debug("Checking X condition")`
- State changes: `log.info("Created shop {} with ID {} for tenant {}")`
- Errors: caught exceptions before rethrowing or handling
- DEBUG: method entry, intermediate calculations, detailed flow
- INFO: business-significant operations (create, update, delete)
- WARN: recoverable issues, deprecated usage
- ERROR: exceptions, failures that need attention
## Comments
- Complex algorithm logic: explain the "why", not the "what"
- Non-obvious business rules: e.g., slug generation, UUID handling
- Workarounds and known limitations: why a shortcut exists
- Integration points with external systems
- Used sparingly but consistently
- Function-level comments for public exports in utilities
- Example from `safe-image.tsx`:
- Controller methods: OpenAPI annotations (`@Operation`, `@ApiResponse`) preferred over Javadoc
- Service methods: Brief Javadoc comment explaining purpose
- Exception classes: Single-line Javadoc explaining when thrown and resulting HTTP status
## Function Design
- Target: < 50 lines for complex business logic
- Small utility functions: < 10 lines acceptable
- Controllers: typically 5-15 lines (delegation to service)
- Frontend: use destructuring for objects (e.g., `{ shopSlug, children }`)
- Backend: individual parameters for JPA/Spring (entities, DTOs)
- Go: explicit parameters, error as last return value
- Frontend: React components return JSX, hooks return state + methods
- Backend: Services return DTOs or Optional<DTO>
- Go: multiple returns with `(result, error)` convention
## Module Design
- Frontend: Named exports for components, default export for pages
- Backend: Public classes are exported, package-private for internal classes
- Go: Capitalized identifiers are exported, lowercase unexported
- Not heavily used in this codebase
- React component groups exported individually
- Frontend: `app/` (pages), `components/`, `lib/`, `hooks/`, `types/`
- Backend: `src/main/java/uk/jtoye/core/<domain>/` (feature modules)
- Go: `internal/<domain>/` (isolated by feature)
## Specific Patterns
- Frontend: TypeScript strict mode, interface/type definitions required
- Backend: Gradle type checking, POJO/DTO validation with `@Valid`
- Go: Explicit type declarations, error type checking
- Frontend TypeScript: Optional chaining (`?.`), nullish coalescing (`??`)
- Backend Java: `Optional<T>`, null checks with guard clauses
- Go: Error-checking pattern, nil checks before dereferencing
- Frontend: React uses immutable state updates (spread operator, map/filter)
- Backend: Entity setters used in service layer, DTOs are mutable POJOs
- Functional style preferred in logic implementations (map, filter, reduce)
- Frontend: React Context and hooks for shared state
- Backend: Spring dependency injection via constructor injection
- Go: Manual injection, passing dependencies as function arguments
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## Pattern Overview
- Multi-tenant isolation enforced at database (RLS policies), middleware (JWT extraction), and application layers
- Service-Repository pattern for business logic layering with clean separation
- Event-driven state machine for order workflows with Spring State Machine
- Tenant-aware caching with Redis, scoped by TenantContext
- Edge-to-Core data synchronization via high-volume batch API
- JWT-first authentication with Keycloak OAuth2/OIDC
## Layers
- Purpose: Customer-facing UI and admin dashboards for multi-tenant operations
- Location: `frontend/app/`, `frontend/components/`
- Contains: Next.js 16 page routes, React components, form validation, API client integration
- Depends on: Backend Core API (`NEXT_PUBLIC_API_URL`), NextAuth.js for session management
- Used by: Browser clients (B2B admin dashboard, B2C customer storefronts)
- Purpose: Rate limiting, JWT validation, circuit breaker protection, request routing
- Location: `edge-go/cmd/edge/main.go`, `edge-go/internal/`
- Contains: Token bucket rate limiter, JWT middleware, Core API client with circuit breaker, WhatsApp webhook handler
- Depends on: Core Java API, Keycloak for JWKS, RabbitMQ for async messaging
- Used by: Storefront pages, mobile clients, external webhook integrations
- Purpose: Full REST API surface with CRUD operations, state management, tenant isolation
- Location: `core-java/src/main/java/uk/jtoye/core/`
- Contains: REST controllers, service layer, repository layer, domain entities, mappers, configurations
- Depends on: PostgreSQL database (RLS-enabled), Redis cache, RabbitMQ, Stripe API, S3/MinIO storage, Keycloak
- Used by: Frontend, Edge gateway, batch sync operations, webhook processors
- Purpose: ORM abstraction for tenant-scoped database queries
- Location: `core-java/src/main/java/uk/jtoye/core/*/` (repository interfaces in each domain folder)
- Contains: JpaRepository extensions with custom queries, named queries for full-text search
- Depends on: PostgreSQL JDBC driver, Flyway for schema migration
- Used by: Service layer exclusively
- Purpose: Multi-tenant data storage with RLS enforcement and audit trails
- Location: Schema defined in `core-java/src/main/resources/db/migration/` (64 Flyway migrations, V1 through V64)
- Contains: Tables (shops, products, orders, customers, financial_transactions, reviews, etc.), RLS policies per table, audit tables via Envers
- Depends on: JDBC driver, Java code for policy setup
- Used by: Core Java service layer via JPA, trigger functions for audit events
## Data Flow
- Order state: Stored in Order.status field, validated by state machine, sourced from database
- Cache state: TenantContext.CURRENT (ThreadLocal), populated by JwtTenantFilter per request
- Session state: NextAuth.js session in browser cookie, refreshable from Keycloak token endpoint
- Business metrics: Captured by BusinessMetricsService (scheduled task) and published to Micrometer metrics for Prometheus scrape
## Key Abstractions
- Purpose: Thread-local holder of current tenant_id for request scope
- Examples: `uk.jtoye.core.security.TenantContext`
- Pattern: ThreadLocal<UUID> with static get()/set()/clear() methods. JwtTenantFilter populates on each request, cleared after response.
- Purpose: Separate business logic (Service) from data access (Repository)
- Examples: `ShopService` / `ShopRepository`, `OrderService` / `OrderRepository`, `CustomerService` / `CustomerRepository`
- Pattern: Service is @Transactional, handles caching, validation, state transitions. Repository is JpaRepository extension with @Query methods.
- Purpose: Compile-time safe DTO ↔ Entity conversion
- Examples: `ShopMapper`, `OrderMapper`, `ProductMapper` (located alongside entities in each domain)
- Pattern: Interfaces with @Mapper(componentModel="spring") and abstract mapping methods, processor generates implementations at compile time.
- Purpose: Enforce valid order lifecycle transitions
- Examples: `OrderStateMachineConfig`, `OrderStateMachineService`
- Pattern: States (OrderStatus enum: DRAFT, PENDING, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED), Events (OrderEvent enum), Transitions defined in StateMachineConfigurerAdapter.
- Purpose: Prevent cross-tenant cache key collisions
- Examples: `TenantAwareCacheKeyGenerator` (`core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java`, wired in `CacheConfig`)
- Pattern: Bean implementing KeyGenerator, reads TenantContext.get() and appends to cache key.
- Purpose: Protect edge from Core outages with fallback degradation
- Examples: `edge-go/internal/core/client.go` with Resilience4j fallback (Spring side) or Gin middleware (Go side)
- Pattern: HTTP client with timeout + retry logic. On the edge (Go) the breaker has NO fallback — breaker-open or a transport error returns 502 (the frontend/mcp bypass the edge entirely). On the Spring side, Resilience4j guards the outbound Stripe call.
## Entry Points
- Location: `core-java/src/main/java/uk/jtoye/core/CoreApplication.java`
- Triggers: Spring Boot application start (`SpringApplication.run()`)
- Responsibilities: Enable async execution, enable scheduling (for cleanup jobs), redirect root to Swagger UI, serve /health endpoint
- Location: `frontend/app/page.tsx`
- Triggers: Browser navigates to /
- Responsibilities: Redirect authenticated users to /dashboard, redirect unauthenticated to /auth/signin
- Location: `edge-go/cmd/edge/main.go`
- Triggers: Docker container startup or direct binary execution
- Responsibilities: Initialize Gin router, attach JWT middleware, rate limiter, serve /health, /ready, /openapi.json + /docs, an HMAC-signed WhatsApp webhook, and the ONE JWT-proxied business route POST /api/v1/sync/batch, to Core API with a sony/gobreaker circuit breaker (no fallback; breaker-open returns 502). The frontend and mcp-server call Core directly and do NOT traverse the edge
- `ShopController` (`/shops`): GET, POST, PUT, DELETE, search, image upload
- `ProductController` (`/products`): CRUD with filtering, full-text search, image gallery
- `OrderController` (`/orders`): CRUD, state transitions, SSE for real-time updates
- `CustomerController` (`/customers`): CRUD with email lookup
- `PaymentController` (`/payments`): Stripe integration, webhook handling
- `FinancialTransactionController` (`/financial-transactions`): VAT tracking, transaction ledger
- `SyncController` (`/sync/batch`): High-volume batch sync from edge
- `DevTenantController` (`/dev/tenants`): Development-only tenant CRUD (disabled in production)
## Error Handling
- `ResourceNotFoundException` (404): Thrown when entity not found by ID or unique constraint
- `InvalidStateTransitionException` (400): Thrown when state machine rejects a transition
- `IllegalStateException` (500): Thrown when TenantContext is not set (indicates security configuration error)
- `ConstraintViolationException` (400): From @Valid on @RequestBody, automatic Spring conversion
- `ValidationException` (400): From Jakarta Validation annotations
- All exceptions caught by @ExceptionHandler methods, converted by the @RestControllerAdvice GlobalExceptionHandler to RFC 7807 ProblemDetail responses, returned as application/problem+json with appropriate HTTP status
```json
```
## Cross-Cutting Concerns
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, or `.github/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

## Incremental Betterment Doctrine

Improvements must *better* what is already good — never trade away a working good to add a new one.

- Any plan that reworks an existing user-visible surface MUST enumerate the goods it displaces and account for each one (preserve it, or replace it with something strictly better and say why).
- **Regression by omission is a defect** even when every test is green: shipping an empty demo catalog, a blank screen, or a silently-dropped capability is a failure regardless of a passing suite. Tests prove code does what it claims; they do not prove the product still does what users need.
- When in doubt, make the change *additive*: extend the good path rather than removing it, and leave the existing invariants intact.

## Cross-Cutting Quality Contracts (design-time)

Five quality dimensions are **standing acceptance criteria** — plans and executors treat them as build-time requirements on the relevant surfaces, not as things a later audit will catch. Each has an **audit-time counterpart** in the QA council (`/qa-discover` Phase 1 API / Phase 2 browser); the two must agree. **Security is the model** — it already gates at plan time (the `<threat_model>` block) and audits in QA Phase 1/5; the other three were brought to the same bar on 2026-07-14, and the fifth (falsifiable evidence + runtime parity) on 2026-07-26.

The fifth dimension differs from the other four in an important way: it is not scoped to a surface. Web-perf applies to pages, SEO to public surfaces, agent-readiness to APIs, security to everything with a threat model — but falsifiability applies to *every claim any of the other four makes about itself*. It is the dimension that keeps the rest honest, which is why it was added only after a phase demonstrated that four green gates can coexist with a runtime that does not match its own branch.

- **Web performance (mobile-first)** — any phase touching a user-facing page owns its Core Web Vitals. For such phases, acceptance criteria include: no route regresses LCP/CLS/INP at a **throttled mobile profile**; no unbounded/duplicate bundle growth or unoptimised images shipped; measured against a **config-declared budget** where one exists (introduce one rather than inventing an ad-hoc number). "Builds clean" ≠ "loads fast" — verify on a throttled profile, never localhost-unthrottled.
- **SEO / discoverability** — any phase building or reworking a **public/unauthenticated** surface (storefront, marketing, shop pages, docs) owns its discoverability: unique title + meta description + canonical + Open Graph per page; schema.org JSON-LD on products/shops (Product/Offer/LocalBusiness); valid `sitemap.xml` + `robots.txt`; crawlable `<a href>` nav (not JS-only); no stray `noindex` on public pages. For J'Toye this is storefront reach → vendor revenue, not polish. Internal/authenticated dashboards are exempt (record N/A).
- **AI agent-readiness / machine-consumability** — any phase adding or changing an **API surface** owns its agent-operability: mutating endpoints carry an Idempotency-Key contract (or are provably idempotent); errors are typed/machine-parseable (RFC 7807, stable codes) not prose-only; credentials are scoped/least-privilege for the action; the OpenAPI/machine-readable contract matches live responses; and — where the MCP server exists — a core new capability gets a corresponding MCP tool (or a recorded reason it's out of scope). This is the standing form of the AI Readiness track (idempotency #204, scoped creds #206, MCP tools #203).
- **Security** — already contracted: every plan carries a `<threat_model>` block (ASVS L1), routed through `/gsd-secure-phase` + `/gsd-code-review` + CI scanners (trivy/gitleaks/dependabot). Listed here so the dimensions read as one set; no change to the existing gate.

- **Falsifiable evidence + runtime parity** — added 2026-07-26 after Phase 26. Two halves, both standing acceptance criteria on every phase:

  **(a) Every acceptance criterion must be shown to FAIL before it is trusted.** Run it against a deliberately broken input, confirm it fails there and passes on the real tree, and record BOTH directions' real output. A criterion observed only passing is not evidence — it may be incapable of failing. This is not a hypothetical risk: Phase 26 found **~22** unfalsifiable criteria across its nine plans, plus three fail-open guards, and **two criteria whose satisfaction would have caused an outage** (one renamed the live AMQP broker user; one deleted the external-IdP issuer config). Every one was caught by running the fail direction; none by the criterion passing. If a criterion cannot fail, say so explicitly, replace it with a strictly stronger form, and record both — never silently substitute, and never report the vacuous pass as satisfied. Known vacuous shapes: an already-0 grep; a diff that compares a file to itself when its baseline lookup fails; a scan direction defeated by output ordering (`kubectl kustomize` sorts map keys alphabetically); an expected-0 that is 1 on the *correct* tree; a doc rule that must name the token it forbids (`grep -v '^\s*#'` filters only full-line comments); a build reporting success while executing nothing (`UP-TO-DATE`, cached, skipped); reading a stale artifact dir (`core-java/build/` is stale — the live one is `build-local`); and a guard that fails OPEN — `cmd | grep -q X` under `set -o pipefail` **inverts** on match via SIGPIPE→141, so use here-strings, and missing tooling / unparseable / EMPTY output must exit non-zero (VOID), never 0.

  **(b) A phase is not done until the DELIVERED RUNTIME matches the branch.** HTTP 200, a rendered page title, "builds clean", and a green suite are identical whether the running code is current or months stale — Phase 26 shipped with a runtime missing its own `application.yml` change and three merged UI PRs, past four green gates, and the user caught it by eye. So: any step that restores or hands back a runtime after source changed **must rebuild** — `docker compose start` starts existing containers and does not rebuild — and `git log HEAD..origin/main` must be empty (or a merge recorded) before a PR, because a branch behind its base ships missing work that no rebuild can fix. Prove parity by content and identity, not by status code: compare each image's **`.Metadata.LastTagTime`** (NOT `.Created`, which Docker preserves across a fully-cached rebuild) against the newest commit touching that image's build paths, and read the value out of the running artifact — for a Spring Boot fat jar, `unzip -p /app/app.jar BOOT-INF/classes/application.yml`, since a filesystem `find` returns a misleading `0`. An old image is **not** automatically stale: if nothing it builds from changed, it is correct. Enforced by two executable gates, one per half: **`scripts/check-runtime-freshness.sh`** (runtime vs tree — per-service `.Metadata.LastTagTime` vs the newest commit touching that service's build paths, plus the running container's image ID vs the tag's, which catches a rebuild that was only `start`ed) and **`scripts/check-branch-behind-base.sh`** (tree vs base — `HEAD..origin/<default>` must be empty, base resolved from the remote and never hardcoded). Both fail closed at exit **2** (VOID) on missing tooling, an empty discovery result, or a stopped stack — "found nothing" is never "clean". For the runtime half this is enforced **per service** (tightened 2026-07-27, plan 27-00 Task 6): **any** built service that is missing or not `running` VOIDs the whole run. It previously VOIDed only when *every* built service was unverifiable, so stopping one of four printed `PASS: 3 … (1 unverified)` and exited 0 — an unproven service reported inside a pass. Documented in `k8s/DEPLOYMENT.md` ("Runtime-parity gates"); the branch half also runs in CI, the runtime half deliberately does not (a CI runner has no running containers, so it could only ever be VOID there).

Accessibility stays contracted via the existing UI standards + QA Phase 4. When a dimension genuinely doesn't apply to a phase, record it **N/A** — never silently drop it (same rule as the QA council roster).

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->

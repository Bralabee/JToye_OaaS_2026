<!-- GSD:project-start source:PROJECT.md -->
## Project

**J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved**

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. This milestone turns to vendor operational control: unblocking stuck onboarding, scoping access per shop within a tenant, hardening image handling (copy-on-write media_asset model + safe async upload pipeline), and fixing dashboard mobile — plus extending the AI/automation surface (outbound webhooks + mutating MCP tools) on a committed local-k8s overlay.

**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility.

### Constraints

- **Tech stack**: Must use existing stack — Spring Boot 4.1.1 (migrated from Spring Boot 3.5.16, 2026-10-05, Phase 38), Next.js 16, Go 1.27, PostgreSQL 15
- **Java version**: JDK 25 (Temurin) on Gradle 9.7.1 — JDK 25 requires Gradle ≥ 9.1 (migrated from JDK 21/Gradle 8.10.2, 2026-08-31)
- **Multi-tenancy**: All new features must respect RLS and TenantContext
- **Testing**: All new code requires tests — project standard is 4863 logical invocations passing (2319 Java `@Test` methods across 346 files + 2237 Jest `it/test` blocks across 195 files + 98 top-level Go `Test*` funcs across 13 files + 148 Playwright `test()` blocks across 36 specs + 61 MCP-server vitest `it/test` blocks across 8 files under `mcp-server/`). Multiple Java files use Testcontainers (real Postgres + RLS). Counts are the single source of truth in `docs/metrics.json`, enforced by **two** gates in `.github/workflows/docs-freshness.yml`, one per half of the loop: `scripts/docs-freshness.sh` (source tree → `docs/metrics.json`) and `scripts/check-doc-metrics.sh` (the numbers quoted in prose here, in `AGENTS.md` and in `README.md` → `docs/metrics.json`). Both fail the build on drift. The second gate exists because the first never opened a doc: README sat at `921` for months while the tree was at `1895`, and `docs-freshness.sh` was green on every one of those commits.
- **Docker**: Always rebuild ALL containers after code changes before E2E testing
- **Runtime & deploy topology (compose and k8s are two layers, both kept — not redundant)**: Docker **Compose** (`docker-compose.full-stack.yml`, incl. Mailhog) is the **canonical local dev + E2E runtime** — driven by `docker compose -f docker-compose.full-stack.yml` + Playwright/`webapp-testing`; this is where you develop and test. (`scripts/start-dev.sh` is NOT this: it drives a separate hybrid runtime — `infra/` compose, which reads `infra/.env`, plus backend and frontend as host processes — paired with `scripts/stop-dev.sh`. See README.md "Option 3: Hybrid".) **Kubernetes** kustomize (`k8s/base` + `k8s/staging|production` overlays) is the **staging/prod deploy target** — driven by the `ci-cd.yaml` deploy job + `scripts/deploy.sh` (sealed secrets, networkpolicies); this is where you ship. Neither is retired. **XOR applies only at *local* runtime**: run Compose **or** a local minikube, never both at once (they share the dev DB) — local dev defaults to Compose. (Decided 2026-07-15; supersedes any "we only need one" reading.)
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Java 25 - Core API (Spring Boot 4.1.1)
- TypeScript 5 - Frontend (Next.js 16.3.7, React 19)
- Go 1.27 - Edge API gateway (Gin)
- SQL (PostgreSQL) - Database migrations via Flyway
- YAML - Configuration management
## Runtime
- JVM (Java 25) - Core API execution
- Node.js 24+ - Frontend build and runtime
- Go 1.27 runtime - Edge gateway
- PostgreSQL 15 - Database
- Gradle 9.7+ (Kotlin DSL) - Java/Spring Boot build
- npm - Node.js dependencies
- go mod - Go dependencies
- Gradle: Present (`gradle-wrapper` properties)
- npm: package-lock.json (implicit)
- Go: go.sum
## Frameworks
- Spring Boot 4.1.1 - Web framework, dependency injection, auto-configuration (Spring Framework 7.0.9; explicit per-module starters incl. restclient, webclient and zipkin, no classic starter)
- Spring Data JPA (Spring Data 2026.0) - ORM and database abstraction
- Spring Security 7.1 - Authentication and authorization
- Spring OAuth2 Resource Server - JWT/OIDC token validation
- Spring AOP - Aspect-oriented programming
- Spring Cache - Distributed caching with Redis
- Spring AMQP 4.1 - RabbitMQ message queue integration
- Spring Actuator - Metrics and health endpoints
- SpringDoc OpenAPI 3.1.1 - Swagger/OpenAPI documentation
- Micrometer Prometheus - Metrics export
- Micrometer Tracing (Brave/Zipkin, via spring-boot-starter-zipkin) - Distributed tracing
- Jackson 3 (tools.jackson) - Application JSON line: Boot's JsonMapper with Jackson 3 defaults; Jackson 2 is transitive-only, floored via jackson-2-bom.version
- Next.js 16.3.7 - React framework with file-based routing
- React 19 - UI component library
- React Hook Form 7.89.0 - Form state management
- Next-Auth 5.0.0-beta.32 - Authentication middleware
- TailwindCSS 3.4.1 - Utility-first CSS framework
- Radix UI - Headless component library
- Zod 4.6.5 - Schema validation
- Gin v1.12.0 - HTTP routing and middleware
- golang-jwt/jwt v5 - JWT validation
- uber/zap - Structured logging
- sony/gobreaker - Circuit breaker pattern
- JUnit Jupiter 6 - Java test framework
- Testcontainers 1.21.4 - Docker-based integration testing
- Spring Boot Test - Testing utilities and test containers
- Jest 30.5.2 - JavaScript test runner
- @testing-library/react - React component testing
- @playwright/test 1.63.0 - E2E browser automation
- Spring Boot Gradle Plugin 4.1.1 - JAR packaging
- Flyway 12 - Database migration management
- Lombok - Boilerplate reduction (code generation)
- MapStruct 1.6.3 - Type-safe DTO mapping
## Key Dependencies
- PostgreSQL JDBC Driver 42.7.13 - Database connectivity
- Hibernate ORM 7 (via Spring Boot 4.1.1) - JPA implementation
- Hibernate Envers - Audit history tracking
- Azure Storage Blob SDK (12.35.1) - Blob API for image storage
- Azure Identity (1.18.6) - Workload Identity credential for Blob in AKS
- Stripe React/JS 6.12.0, 9.17.0 - Payment processing UI integration
- Axios 1.19.0 - HTTP client for API calls
- Framer Motion 13.4.6 - Animation library
- Recharts 3.10.1 - Charts and data visualization
- Redis 7 - Session and cache store
- RabbitMQ 4.3.4 - Message queue (AMQP)
- Keycloak 24.0.5 - Identity provider (OIDC/OAuth2)
- Azurite 3.37.0 - Azure Blob emulator (local, hybrid, nightly); Azure Blob Storage in staging/production
- Ollama (latest) - Local LLM for image analysis
- Mailhog v1.0.1 - Local SMTP for email testing
- Resilience4j 2.4.0 - Circuit breakers and retry logic
- Bucket4j 8.10.1 - Token bucket rate limiting
- Stripe Java SDK 33.4.2 - Payment intent creation and webhook handling
- OpenPDF 2.0.3 - PDF generation for allergen labels
- Spring Data Redis (Lettuce 7) - Redis connection pooling
- Embedded Tomcat 11.0.26 - Servlet container (security floor over Boot's managed 11.0.24)
- Netty 4.2.17.Final - Boot-managed, no pin (transitive via reactor-netty and the Azure SDK)
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
- Current schema version: V75 (V75 [Phase 31.1-16 / #778 P0, decision D-01 — a verified Article 15 request is finally ANSWERED]: creates `dsar_access_export`, the single-use, expiring, encrypted store behind the link an access request is answered with. Until V75 every verified ACCESS request sat in `dsar_request` for ever: the fan-out worker logged "ACCESS delivery is not implemented" each sweep while the statutory one-month clock ran out for every requester. The worker now assembles ONE document across every tenant (each tenant reached by pinning the GUC under FORCE RLS, NOSUPERUSER-proven), stores it here, and emails the verified address a fragment link carrying a single-use token; the personal data is never emailed and never attached. The token is held ONLY as its SHA-256 (`token_sha256`, the V62 rule: a readable token at rest is a bearer credential); `payload_ciphertext` is AES-256-GCM by `DsarCipher` under purpose ACCESS_EXPORT with the request id as associated data and the key outside the database (V70); `ck_dsar_access_export_consumed_payload` makes "consumed but still holding the data" unstorable; `expires_at` (default 168 h, `jtoye.gdpr.dsar.export-link-ttl-hours`) is indexed for 31.1-17's purge, which NULLs every unconsumed payload past it; `dsar_request_id` is UNIQUE, so a retry REPLACES token, payload and expiry on the same row and an old token dies the moment a new one is issued; ON DELETE CASCADE, so an export cannot outlive its request. **Deliberately NOT tenant-scoped, and RLS here would be WORSE** — the V62 argument from the read side: one row is one cross-tenant document for one subject (Article 15 is one right against the controller, and 15(1)(c) requires the recipients to be named), read by an anonymous token holder with no JWT, no TenantContext and no GUC, so a FORCE'd policy with no predicate to write would return nothing, every emailed link would be dead, and every test would stay green because a dead table is indistinguishable from an empty one. Exempted BY ADDITION in `RlsContractTest.EXEMPT_TABLES` with a written justification, proven load-bearing by removing it and watching the sweep name the table; the tenant wall is not weakened, because only the background worker reads tenant data into it, one pinned tenant at a time (Phase 31 D-17). No `_aud` mirror, deliberately: a long-lived second store keyed by a data subject would defeat a table designed to hold the data briefly and then not at all. V74 [Phase 31.1-14 / #861, decision D-16 — "may contain" is its own statement, never a declaration]: adds nullable `products.may_contain_mask` INT (`ck_products_may_contain_mask_range`: NULL or 0..16383, matching `@Min(0) @Max(16383)` on `CreateProductRequest.mayContainMask`) plus the `products_aud` mirror in the SAME migration (Product is `@Audited`). The only allergen field on a product was `allergen_mask`, the vendor's DECLARATION of what the food contains, so a vendor whose kitchen also handles sesame could either print sesame as an ingredient on the PPDS label and in every storefront and checkout allergen panel — a false statement about the recipe — or say nothing about the cross-contact risk at all. Same 14-bit `AllergenCatalog` layout; printed as its own "May contain: …" label line and storefront line, through the one rule both use (`MayContainAllergens.undeclaredNames`: a bit that is also declared shows only under the declaration). **NEVER MERGED** — the V63 `allergen_flag_mask` rule applied to the product: it is never OR-ed into `allergen_mask`, the declared names, the V63 order-line snapshot or the V69 acknowledgement, because a precautionary statement is not a declaration and folding one into the other would make the platform the author of an ingredient claim the vendor never made (the IT proves the separation by SQL, and a merge break arm reds it). **NULL ("the vendor has not said") is DIFFERENT from 0 ("the vendor declared no cross-contact risk")**: no backfill, no DEFAULT, and no later migration may add one — a 0 on every existing product would record a statement no vendor made (the V63/V66 rule). No UPDATE, so no tenant loop and no RLS-backfill trap; no new table, no policy change. V73 [Phase 31.1-13 / #812, decision D-15 — a customer's allergy request is its own field, and the shop's reading of it is recorded]: adds nullable `orders.allergy_note` VARCHAR(500), `allergy_note_ack_at` TIMESTAMPTZ and `allergy_note_ack_by` VARCHAR(255) plus their `orders_aud` mirrors in the SAME migration (Order is `@Audited`). The persona run sent "My child is allergic to peanuts and sesame - please confirm" in the delivery NOTES; the order went all the way to COMPLETED and nothing recorded that anyone in the shop had read it. The note is trimmed (blank stored as NULL) and capped at 500 to match `@Size(max = 500)` on `GuestOrderRequest`; it may be special-category (health) data whose content the vendor controls, the platform derives nothing from it, and an Article 17 erasure nulls it on `orders` AND in both `orders_aud` scrubs while KEEPING who and when (a staff record, not subject data). `ck_orders_allergy_note_ack_pair` makes a half-written acknowledgement impossible (who and when together, never one without the other); it deliberately does NOT say "an acknowledgement implies a note", because an erasure legitimately leaves exactly that state — "only an order with a note can be acknowledged" is enforced where it is decided, in `OrderService.acknowledgeAllergyNote` (STAFF of the order's own shop, typed 400 without a note, first write wins). **No backfill, no DEFAULT, and none may be added**: a pre-V73 request stays inside its delivery notes (moving free text between columns would rewrite what the customer submitted), and a default acknowledgement would claim somebody read something nobody read. No UPDATE, so no tenant loop; no new table, no policy change (the V42 `orders_aud` UPDATE policy already admits the scrub). V72 [Phase 31.1-11 / #777, decision D-03 — erasure deletes the sign-in account, and "complete" waits for proof]: adds `dsar_request.account_deletion_status` VARCHAR(16), nullable, `ck_dsar_request_account_deletion_status` DELETED | NONE_FOUND | OUTSTANDING | NOT_CONFIGURED, and `account_deletion_attempts` INT NOT NULL DEFAULT 0. After V68 an erased customer could still sign in with their `jtoye-customers` Keycloak account and see their now-anonymised orders. The worker deletes that account AFTER every tenant's erasure has committed, best-effort, in the #102/V49 shape (a Keycloak outage must never roll back an erasure); only DELETED (a 404 counts: already gone) or NONE_FOUND accompany COMPLETED, while OUTSTANDING and NOT_CONFIGURED (admin seam off) release the request for retry, are NEVER complete, and park it FAILED after the configured maximum attempts. The pairing with status is the worker's, proven by `DsarAccountDeletionIntegrationTest`. Nothing here identifies the subject: the account is found through the V70 ciphertext address, which is still dropped at the terminal state. No backfill — historic rows stay NULL ("not attempted"), and 0 attempts is true of every one of them; `dsar_request` is not tenant-scoped (V62), so no RLS change and no `_aud` to extend. V71 [Phase 31.1-10 / #789 P0, decisions D-10/D-11 — the legal entity customers buy FROM]: creates `trader_identity`, one row per tenant (`UNIQUE(tenant_id)`): legal name, entity type COMPANY | SOLE_TRADER | PARTNERSHIP, a GEOGRAPHIC address, and a VAT number (NULL = no registration declared; stored as `GB` + 9 or 12 digits) — the pre-contract trader details CCR 2013 Sch 2 (b)-(c) and the E-Commerce Regulations 2002 reg 6 require, where until then the platform held no legal-entity field at all, only a shop's trading name and premises. Tenant-level, not per shop (D-11): the legal entity is the business, so a multi-site owner enters it once and every shop resolves to it; contact details stay on `shops.phone`/`shops.email`. **No company-number column**: `vendor_onboarding.company_number` (V43) already holds it, gate-checked against Companies House, and a second copy could disagree with the one the gate verified. **Ships EMPTY, no backfill**: an invented legal name or address written by Flyway would land in every environment as a statutory statement nobody made (the V63 rule); demo and e2e tenants are filled by the dev seeder and the e2e fixtures (31.1-12). `trader_identity` and `trader_identity_aud` are ENABLE + FORCE RLS through the safe `current_tenant_id()`, swept by `RlsContractTest` with no exemption, and proven under a NOSUPERUSER role (cross-tenant read empty, cross-tenant UPDATE 0 rows, foreign-tenant INSERT and forged audit INSERT refused 42501, each beside its own-tenant control); the `_aud` has the V65 shape (tenant SELECT; INSERT `(tenant_id IS NULL) OR (tenant_id = current_tenant_id())`) and NO UPDATE or DELETE policy, so the record of who published which legal identity is append-only for the application role. It also drops and re-adds `vendor_onboarding_gate_gate_type_check` with the eight V43 values plus `TRADER_IDENTITY` — the constraint rewrite V43 anticipated; no row changes, and the `_aud` column has no CHECK. V70 [Phase 31.1-07 / decision D-19, owner ruling 2026-10-05 — the worker can REACH the subject, and only while the request is open]: adds `dsar_request.subject_email_ciphertext` BYTEA plus `ck_dsar_request_ciphertext_terminal CHECK (status NOT IN ('COMPLETED','FAILED','EXPIRED') OR subject_email_ciphertext IS NULL)`. It AMENDS, narrowly, V62's "no readable-address column, and there must never be one": the digest is untouched and matching is still digest against digest, but a one-way digest cannot be reversed into somewhere to send a message, and four things need one — the D-01 export link, the D-03 account lookup, the #777 completion email, and the Article 15 "we hold nothing about you" reply to a subject no vendor holds. The address (as typed, trimmed) is AES-256-GCM by `DsarCipher` (JDK `javax.crypto`, a fresh 12-byte IV, a 128-bit tag, associated data binding purpose and row id so a ciphertext copied onto another row does not decrypt; the column holds IV || ciphertext-with-tag). The key lives ONLY in `jtoye.gdpr.dsar.encryption-key` (env `DSAR_ENCRYPTION_KEY`, 64 hex characters) and **core-java refuses to start without a valid one** — 31.1-08 wired it into compose, `.env.example`, `verify-env.sh`, the nightly and a non-optional k8s `dsar-credentials` secretKeyRef, and staging/production need a sealed secret per environment before rollout. The column is NULLed in the SAME statement that moves the row to COMPLETED, FAILED or EXPIRED (`DsarRequestExpirySweep`, the first EXPIRED writer anywhere; retention R-14, 168 h for an unconfirmed request), and the CHECK makes a terminal row still holding it unstorable. Both rejected alternatives are recorded in the header: derive the address from matched rows (cannot reply when nothing matches, and the reply would disclose whether a vendor holds data) and make the verification link the download link (contradicts D-01; a bearer credential to the export from the first minute). No backfill (old rows are NULL, which the CHECK admits in every status); no extension. V69 [Phase 31.1-03 / #784 + #785 P0, decisions D-05/D-06/D-07 — the SERVER refuses an order whose allergen acknowledgement is missing or stale, and records the one it accepted]: adds nullable `orders.allergen_ack_mask` INT, `allergen_ack_at` TIMESTAMPTZ and `placed_via` VARCHAR(16) plus the three `orders_aud` mirrors in the SAME migration (Order is `@Audited`), with `ck_orders_placed_via` (STOREFRONT | VENDOR) and `ck_orders_allergen_ack_mask_range` (0..16383, matching `@Min(0) @Max(16383)` on `GuestOrderRequest.acknowledgedAllergenMask`) on `orders` only. The checkout checkbox gated the browser only: measured RED, a guest-order POST with no acknowledgement returned 201, and a vendor edit landing between render and submit was recorded as if the customer had seen it (the persona acknowledged Gluten/Fish/Peanuts; the V63 snapshot recorded Gluten/Eggs/Fish/Peanuts/Milk). Now a missing acknowledgement is 422 `allergen-acknowledgement-required` and a stale one 409 `allergen-acknowledgement-stale` carrying the current set and its per-line attribution, both rolling the idempotency reservation back; the accepted mask must EQUAL the union read in the same transaction that writes the V63 snapshot, so `allergen_ack_mask = bit_or(order_items.allergen_mask)` by construction (asserted by SQL on `orders` and `orders_aud`). Vendor, REST API and MCP orders (`OrderService.createOrder`) record `placed_via = VENDOR` with a NULL acknowledgement (D-07). **NULL ("not recorded") is DIFFERENT from 0 ("acknowledged a basket declaring none of the 14")**: no backfill, no DEFAULT, and none may be added — a DEFAULT 0 would claim every pre-V69 customer acknowledged "no allergens", and a default channel would guess one nobody recorded (the V63/V66 rule). No UPDATE, so no tenant loop and no RLS-backfill trap; no new table, no policy change. The request field is `@JsonInclude(NON_NULL)`, so the D-21 idempotency-fingerprint golden stays green (un-annotated, exactly the two `storefront.guest-order*` rows red). V68 [Phase 31.1-02 / #777 P0, decision D-02 — a verified Article 17 request erases storefront GUESTS]: makes `erasure_records.subject_customer_id` nullable. Guest checkout never creates a `customers` row — the subject's name, address and phone live only on orders and reviews — and the DSAR fan-out matched the digest against `customers` alone, so for every storefront subject it erased nothing and still marked the request COMPLETED: measured RED under NOSUPERUSER + FORCE RLS as `tenantsErased=0 tenantsScanned=4` with all 28 PII assertions failing. `GdprService` now matches the digest against every stored spelling on orders and reviews too, in each pinned tenant with an explicit tenant predicate (the unpredicated unpaged finder is deleted), and anonymises through the one routine the admin erasure uses — and that erasure must leave its PII-free evidence row, which V42's NOT NULL made impossible (the insert would throw, roll back the tenant's erasure and be retried until FAILED). NULL means "the subject had no customers row in this tenant"; such a record carries the DSAR subject digest in `subject_email_sha256`. Order amounts, items, VAT and `financial_transactions` stay byte-identical (asserted by a per-tenant fingerprint). No backfill and no DEFAULT (a record never gains or loses a customer id), no `_aud` (`erasure_records` is itself the audit artifact), no RLS change (the V42 and V67 policies key on `tenant_id` and `photos_deleted`, never on this column). V67 [#764 — GDPR erasure works for a customer who left a review, and a failed erasure destroys nothing]: `reviews` has FORCE RLS (V35) and until now carried only `reviews_tenant_read` FOR SELECT and `reviews_tenant_write` FOR INSERT — NO UPDATE policy in any migration — so for the NOSUPERUSER application role the Article-17 anonymising UPDATE matched ZERO rows, Hibernate raised `ObjectOptimisticLockingFailureException` (`StaleStateException`: "actual row count: 0; expected: 1") and the whole erasure rolled back, for EVERY customer who had ever left a review. Worse, the review photos had already been deleted from Blob INSIDE the transaction, and object storage does not roll back. Measured RED on the unfixed tree by `GdprErasureReviewRlsIntegrationTest` (NOSUPERUSER + real Azurite, three arms); the existing suites were green because `GdprErasureIntegrationTest` runs as the Testcontainers SUPERUSER, which bypasses FORCE RLS, and no erasure test ever inserted a review. V67 adds `reviews_tenant_update` FOR UPDATE with USING AND WITH CHECK both `tenant_id = current_tenant_id()`: USING is what excludes the PUBLISHED-shop rows `reviews_tenant_read` exposes across tenants, so the database refuses a cross-tenant anonymisation even if the Java predicate regresses, and WITH CHECK stops a row being re-stamped into another tenant. FOR UPDATE, not FOR ALL — no code path deletes a review, and the only other writer (`ReviewService`) INSERTs. No `_aud` policy: `Review` is not `@Audited` and `reviews_aud` does not exist. It also adds `erasure_records_photo_count_update` FOR UPDATE, USING `tenant_id = current_tenant_id() AND photos_deleted = 0`, WITH CHECK `tenant_id = current_tenant_id()`: photo deletion moved AFTER commit (a `TransactionSynchronization.afterCommit` hook with the erasing tenant pinned for the D-09 guard), so the WR-02 count is only known then — the record is still written INSIDE the erasure transaction with `photos_deleted = 0` (true at commit) and the real count is written ONCE afterwards in a `REQUIRES_NEW` transaction (a default-propagation write inside `afterCommit` joins the committed transaction and is silently lost); the `= 0` USING clause makes that write-once at the database. Accepted residual: a record still at 0 stays tenant-updatable, by a role that can already INSERT records for its own tenant. The review lookup on BOTH the erasure and the Article-20 export now carries an explicit tenant predicate (`ReviewRepository.findByTenantIdAndCustomerEmail`) because the SELECT policy is cross-tenant for PUBLISHED shops — the email-only finder pulled another tenant's review into the erasure and the export, and is deleted. `ErasureResponse` is unchanged (no OpenAPI diff). DDL only: no backfill, no data change, no role, no extension. Four break arms each turned their named arm RED.) V66 [QA-council 20260902-134741 / COR-4, adjudication A9 — the order records how many THINGS the customer bought, not just how many LINES the basket had]: adds nullable `orders.unit_count` INT plus the `orders_aud` mirror in the SAME migration (Order is `@Audited`, so a missing mirror throws at RUNTIME on the next audited write — the failure V38 had to repair after V30). `orders.item_count` means LINES (`Order.calculateTotal()` sets it to `items.size()`); the browser basket means UNITS (`cart-provider.tsx` reduces over `quantity`). Both render the identical English string "{n} item(s)", so a customer who buys 6 Zobos was shown "6 items" on the basket, the checkout header and the cart drawer, then "1 item" on the tracking page, the per-shop order page and My Orders — same order, minutes apart. Measured on the dev runtime: 24 of 60 orders (40%) have SUM(quantity) <> COUNT(*), so this was a live defect on two fifths of all orders, not a theoretical one. Neither definition was ever CHOSEN over the other: V21's own header records that `item_count` was added to fix a "0 items" bug on tracking pages, four days after the client had already shipped the units reduce. **`item_count` is left ENTIRELY untouched**: redefining it would rewrite the MEANING of a persisted column for every existing row with nothing to distinguish migrated rows from unmigrated ones (the V63 "a fabricated value is indistinguishable from a real one" rule), would silently change OrderDto, PublicOrderStatus, GuestOrderConfirmation and the MCP `read_orders` payload with ZERO OpenAPI diff so `check-openapi-snapshot-fresh.sh` would stay green across a semantic break, and would red the shared money-conservation invariant I5 (`item_count == COUNT(order_items)`) on all 60 rows. Two columns, two facts. **NO BACKFILL, deliberately** — the V63 shape for the V63 reason: the column is nullable, historic rows stay NULL ("not recorded"), and NULL is DIFFERENT from 0 ("an order with no units"); customer surfaces render the count only when present and the price alone when not, rather than inventing one. Do NOT add a DEFAULT 0 in a later migration — that would destroy the distinction silently and retroactively. Because there is no backfill there is deliberately NO UPDATE and NO tenant loop, so the recurring RLS-backfill trap (V25 → V44 → V57: a bare UPDATE against a FORCE-RLS table matches ZERO rows under the migration role and reports success) genuinely does not apply rather than merely going unmentioned. No new table, so the RLS posture is inherited unchanged and RlsContractTest's schema walk is unaffected; no index, because `unit_count` is read as part of an already-keyed order fetch and is never a predicate. V65 [QA-council 20260902-134741 SEC-6, adjudication A5 — the six legacy Envers `_aud` INSERT policies stop accepting a FOREIGN tenant_id]: V4, V5, V9 and V11 created the INSERT policies on `shops_aud`, `products_aud`, `financial_transactions_aud`, `orders_aud`, `order_items_aud` and `customers_aud` as `FOR INSERT WITH CHECK (true)`. The paired `*_aud_select_policy` on each table is correctly `tenant_id = current_tenant_id()`, so nothing was ever READABLE across tenants — the gap was write-side only: a session pinned to tenant A could INSERT an audit row stamped tenant B, into what is the Article-17 erasure evidence chain (V42). Measured RED before this migration: `AuditTableInsertPolicyIntegrationTest`'s armed foreign-tenant INSERT succeeded on all six tables under a NOSUPERUSER role with the GUC pinned. The predicate is `WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()))` and **the IS NULL arm is load-bearing**: Hibernate Envers writes a DELETE revision (revtype = 2) carrying only the identifier unless `store_data_at_delete` is on, so `tenant_id` is NULL BY CONSTRUCTION on every such row — measured live with exact correlation (orders_aud 1 NULL-tenant row / 1 DELETE revision, products_aud 5/5, customers_aud 1/1, and 0/0 on revtypes 0 and 1). The naive `WITH CHECK (tenant_id = current_tenant_id())` would therefore turn every product, order and customer DELETE into "new row violates row-level security policy" — a data-modification outage on three core entities that no test which never deletes would see. V11 fixed exactly this once already by widening to `true`; V65 narrows to the foreign-tenant case without re-breaking deletes. The companion N-3 fix in `application.yml` (the `org.hibernate.envers.*` prefix, so `store_data_at_delete=true` finally reaches Envers) means NEW delete revisions carry their tenant_id and pass the second arm; the IS NULL arm stays per A5 as what keeps a DELETE auditable if that setting is ever reverted. V64 [#647 — the grant that a fresh deployment could not make for itself]: `GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime`, guarded on the role existing. Since the SEC-04/#552 runtime-migrator split the app connects as the DML-only `jtoye_runtime`, and TRUNCATE is a DISTINCT privilege NOT implied by DELETE. `infra/db/init/00-create-db.sql` grants that role DML but CANNOT name `postcode_centroid` — V61 creates it later, so at cluster-init time there is nothing to grant on — and its comment therefore told an operator to run `infra/db/create-runtime-role.sql` by hand after the first migration. `e2e-nightly.yml` tears down with `down -v`, so every night began on a fresh volume where nobody had, and `PostcodeCentroidImporter` crash-looped on `permission denied for table postcode_centroid` every ~27s for **14 consecutive nights** (2026-08-11 to 2026-08-24) without executing one Playwright test. A provisioning step only a human can perform is not provisioning. The grant is deliberately TABLE-SCOPED and not added to `ALTER DEFAULT PRIVILEGES`: that would hand the DML-only application TRUNCATE on every tenant table, which is the whole thing the split prevents. `postcode_centroid` is public reference data — no `tenant_id`, no RLS — so a TRUNCATE on it alone carries no cross-tenant risk. The role guard is load-bearing: Testcontainers migrates against a bare Postgres where `jtoye_runtime` does not exist, and an unguarded GRANT fails "role does not exist" and reds every integration test. V63 V63 [Phase 31-10 Consumer-Safety — the order line records the allergen picture that was true WHEN THE ORDER WAS PLACED, LGL-03]: adds `order_items.allergen_mask` and `order_items.allergen_flag_mask`, both nullable INT. `order_items` carried NO allergen data at all, so the checkout panel and the kitchen display could only be fed by joining back to `products.allergen_mask` at READ time — and under a live join a vendor who edits a product's mask AFTER an order is placed silently changes what the customer is recorded as having acknowledged and what the kitchen ticket shows: the customer acknowledged set A, the kitchen sees set B, and **no record of A exists anywhere**. Measured: replacing the write-time snapshot with a live join makes the immutability test read `expected: 65 / but was: 512` after a vendor edit, and — the finding the plan did not anticipate — a live join **destroys the "not recorded" state entirely**, so a historic order silently claims a set it never had. `order_items` already snapshots `product_name` (V30) for exactly this class of reason; the mask is snapshotted beside it, at the same moment. **`allergen_mask`** is the vendor's DECLARED mask copied at write time, the same 14-bit UK FSA layout as `products.allergen_mask` (`uk.jtoye.core.product.AllergenCatalog`, bits 0..13) — the legally operative statement. **`allergen_flag_mask`** is the ADVISORY reconciliation result from 31-04's `OrderAllergenAggregator`: the bits this product's EMPHASISED ingredients text names but its declared mask omits. The two are structurally separate and the flag is **never OR-ed into the declaration** — a text heuristic must not rewrite a vendor's statement, because that would make the platform the author of an allergen claim it cannot stand behind AND would mask the vendor's underlying data error instead of surfacing it. Two integers rather than JSONB/TEXT[]: per LINE a reconciliation flag is exactly "an allergen bit" and the product is the row itself, so a per-row bitmask preserves "which product, which allergen" with no encoding; the human-readable NAME is deliberately NOT stored, because persisting it as prose would create a second, ungated copy of the catalogue frozen at write time — one row per order line — which is the exact drift the cross-language parity test (`AllergenCatalog` against `frontend/types/api.ts`) exists to prevent. The BIT is the fact; the name is a label. **NO BACKFILL, deliberately**: both columns are nullable and historic rows stay NULL, which reads as "not recorded". Inventing a mask for a past order from today's product rows would fabricate a record of what a past customer was shown — a worse defect than the one being fixed, because a fabricated record is indistinguishable from a real one. **NULL ("not recorded") and 0 ("the vendor declared none of the 14 regulated allergens") are DIFFERENT** and downstream must not conflate them. The snapshot is captured on BOTH write paths — the storefront and `OrderService.createOrder` — because the latter feeds the same kitchen display, and omitting it would have shipped every vendor/API/MCP order with no allergen data. The order-level aggregate is exposed on **`OrderDetailDto`, NOT `OrderDto`**, measured with a Hibernate-statistics probe (`itemsCollectionInitialisedAfterFindAll=false`, 7 orders → 7 extra prepared statements): putting it on the list DTO is an N+1, so the MCP `read-orders` tool gains the fields on its `orderId` DETAIL call and deliberately not on its `shopId` LIST call. V62 V62 [Phase 31-05 Consumer-Safety — the DSAR intake behind the published single point of contact, decisions D-16/D-17]: creates `dsar_request`, the platform-level UK-GDPR data-subject-request intake queue (ACCESS/ERASURE), plus a partial unique index on `idempotency_key` and the partial `idx_dsar_request_outstanding (received_at) WHERE completed_at IS NULL` that backs 31-09's claim query. D-17 is the whole design and is not negotiable: **intake is a request, execution is background** — a request thread lodges a row and stops, and 31-09's scheduled worker reads it, iterates tenants and does the work, so **no human ever holds cross-tenant read**. That is how a single cross-tenant DSAR desk is reconciled with a project that has refused a cross-tenant operator identity twice. **`dsar_request` is deliberately NOT tenant-scoped, and RLS here would be WORSE rather than safer**: an anonymous subject lodges from the public internet before any tenant is known (no JWT, no TenantContext, no `app.current_tenant_id` GUC), the request must be actioned across every tenant because UK GDPR Articles 15 and 17 give the subject one right against the controller and not one right per vendor, and with no `tenant_id` there is no predicate to write — a FORCED policy would return zero rows to the very worker that must read them, so the intake would keep returning 202, the queue would keep filling, nothing would ever be actioned, and every test would stay green because **a dead table is indistinguishable from an empty one** (the same liveness failure `RlsContractTest.everyRlsEnabledTableHasAtLeastOnePolicy` was added to catch, and the `postcode_centroid` argument from the other direction). It is exempted BY ADDITION in `RlsContractTest.EXEMPT_TABLES` with a written justification, so the schema-walk sweep itself is never weakened, and the exemption was proven load-bearing by removing it and watching the sweep name the table. **Only a hash is ever stored**: the subject is `subject_email_sha256`, a one-way digest over the lower-cased, trimmed, UTF-8 encoded address — the V42 `erasure_records` rule verbatim — with normalisation owned solely by `DsarIntakeService` so 31-09's worker can reproduce it exactly; the verification token is likewise held only as a digest with an expiry, because a readable token at rest is a bearer credential. `response_body` is a CONSTANT opaque acknowledgement carrying no request identifier and nothing derived from whether any tenant holds a match, so the endpoint cannot be used to enumerate which vendors hold an address. No Envers `_aud` mirror, deliberately: `erasure_records` is already the Article-17 proof row, and mirroring an operational queue would create a SECOND long-lived store keyed by a data subject. Idempotency is enforced on this table rather than in the shared V50 `idempotency_keys` store, MEASURED and not assumed: `IdempotencyService.execute` opens with `TenantContext.get().orElseThrow(MissingTenantContextException)` and `idempotency_keys` is keyed `(tenant_id, endpoint, idempotency_key)` under FORCE RLS, so a tenant-less caller cannot be served by it at all and would 500 before reaching storage; the unique index is on `idempotency_key` ALONE and not `(subject_email_sha256, idempotency_key)`, because the composite would let the same key carry a DIFFERENT address and insert a silent second erasure. Rows start `PENDING_VERIFICATION` and 31-09 owns the transition to `VERIFIED` — defaulting to VERIFIED was considered and rejected as arming an unverified erasure request, which is threat T-31-05-02 itself. V62 is sequential (head was V61) so the project-wide `spring.flyway.out-of-order=true` does not interact; plan 31-10 owns V63. V61 [Phase 33-02 Locality — the offline postcode substrate for CUST-01]: DDL ONLY, creating `postcode_centroid` (postcode TEXT PRIMARY KEY, latitude/longitude DOUBLE PRECISION NOT NULL) plus the partial composite index `idx_shops_lat_lon ON shops (latitude, longitude) WHERE latitude IS NOT NULL AND longitude IS NOT NULL`. `shops.latitude`/`longitude` themselves date from V16; only the index is new, and it is what makes 33-06's LEAKPROOF bounding-box prefilter cheap — PostgreSQL will only push a predicate below an RLS security barrier when the operator is leakproof, which plain float8 comparison is and a distance function is not. **`postcode_centroid` is deliberately NOT tenant-scoped**: no `tenant_id`, no RLS policy, no `_aud` mirror, because a public address's postcode is not tenant information and no customer data is held. It is exempted BY ADDITION in `RlsContractTest.EXEMPT_TABLES` with a written justification, so the schema-walk sweep itself is never weakened. **No data ships in the migration**: the 1,748,230-row OS Code-Point Open dataset is a gzipped classpath resource loaded at startup by `PostcodeCentroidImporter`, because ~46 MB of INSERTs would re-execute on every Testcontainers integration test that spins a fresh Postgres. **NO EXTENSION IS CREATED, and that is not a compromise** — measured on the live stack: `cube` is trusted-but-superuser, `earthdistance` is neither, PostGIS is absent entirely, and Flyway runs as `jtoye_app` (rolsuper=f, rolbypassrls=f, no CREATE on the database), so even the trusted extension fails; the available "fix" is a privilege escalation on the exact role the whole RLS wall is built around and is explicitly rejected. The invariant is enforced across the whole migration directory by `scripts/check-no-create-extension.sh`, wired into `ci-cd.yaml`. Accuracy is ~100 m postcode-unit centroids and coverage is **Great Britain only** (Code-Point Open excludes Northern Ireland) — recorded in `core-java/src/main/resources/geo/SOURCE.md` under OGL v3. V60 [Phase 27-01 Media Durability — a broker outage no longer destroys vendor uploads]: adds `media_asset.process_attempts` (INT NOT NULL DEFAULT 0) + `quarantine_expires_at`/`quarantine_reclaimed_at` (TIMESTAMPTZ), the nullable `media_asset_aud` mirrors, and two partial indexes (quarantine sweep + `media_event_outbox` by asset). The transactional outbox protected the *event*; nothing protected the *object* — `MediaPendingReaper` selected on status ALONE (`findStalePending`: PENDING AND created_at < now() - 15 min) and permanently deleted the quarantined source bytes, while during a RabbitMQ outage `MediaEventOutboxFlusher` is still backing off (5+10+20+40+80+160+300+300+300s ≈ 20 min) and the outbox row is provably still PENDING at ~7–8 attempts when the 15-minute cutoff fires. These columns let the sweep tell "never dispatched" from "dispatched and stalled", and let the bytes be retained on a declared horizon instead of destroyed as a 15-minute accident. V59/V58/V53 [Phase 24 Image Architecture — CoW Assets + Safe Upload Pipeline]: V59 adds media_asset.version (BIGINT NOT NULL DEFAULT 0) — a JPA @Version optimistic lock closing the MediaPendingReaper↔MediaProcessingWorker race so a stale reaper sweep can never flip an asset the worker already moved to ACTIVE back to FAILED (code-review WR-02). V53 ships the copy-on-write media_asset model — media_asset (+ media_asset_aud Envers mirror) + the product_media join (product_id/asset_id/is_primary/sort_order), all ENABLE+FORCE RLS tenant-scoped via the safe current_tenant_id() helper, (tenant_id, sha256) unique dedup index, ref-counted physical object delete only at COUNT(*)=0, and a per-tenant set_config backfill loop wrapping existing products.image_url/additional_image_urls[] as status=ACTIVE assets as-is (no re-pipeline, dual-read D-03a keeps the flat columns this phase). V58 adds a DEDICATED media_event_outbox (cloned from payment_event_outbox: SKIP LOCKED claim + exponential backoff + resurrect, its own media.events exchange — sidesteps the outbox_flusher_dispatch_trap, no PaymentEventOutboxFlusher edit). Every upload passes a safe async pipeline (reject-early Content-Length 413 → quarantine + PENDING row → outbox → @RabbitListener worker that pins the tenant GUC, magic-byte-sniffs jpeg/png/webp, header-read decompression-bomb guard, decode-verifies, strips EXIF, transcodes to a WebP derivative + 400px thumbnail under the jtoye.media.* config budget) storing ONLY the validated normalized derivative, never raw bytes; the 202 accept carries an Idempotency-Key contract + RFC 7807 typed errors (D-06). The vendor UI (IMG-04) renders PENDING→processing / ACTIVE→WebP w/ width+height (CWV, D-07) / FAILED→reason+Re-upload / flagged-ACTIVE→Keep-or-Replace review queue (GET /api/v1/media/review-queue + POST /{assetId}/keep). V57/V52 [Phase 23 Vendor-Scoped Access]: V52 ships shop_staff + shop_staff_aud + user_directory — the vendor→shop application-layer access boundary layered under the RLS tenant wall, all ENABLE+FORCE RLS via the safe current_tenant_id() helper (never the raw ::uuid cast), functional unique index over (tenant_id, user_id, COALESCE(shop_id, zero-uuid)); user_directory is a login-populated grant-target picker (RLS, no _aud — high-churn derived cache). V57 adds shop_staff.grant_source (JIT|OPERATOR) + aud mirror (backfill created_by IS NULL→JIT, NOT NULL DEFAULT 'JIT', NO RLS policy → RlsContractTest green): under the config-injected strict-scoping switch ON, a JIT-sourced tenant-wide GROUP_ADMIN is de-honoured (a day-one auto-provisioned user genuinely becomes scoped) while OPERATOR grants + realm admins stay honoured, with the oldest JIT admin retained as a WARN-logged bootstrap when no OPERATOR admin exists (no tenant can lock itself out on the flip); strict-scoping defaults OFF (day-one JIT auto-provision preserved). V56/V55/V54 [Phase 22 Notifications & Comms]: added the notifications/webhooks tables — notification_consent (V54), webhook_subscription (V55), webhook_delivery (V56) — all ENABLE+FORCE RLS tenant-scoped; V53 remains RESERVED for Phase 24 (media_asset), so spring.flyway.out-of-order=true stays required. V51 RLS uuid-cast safety [Issue #113 / P3-11]: removes the raw `current_setting('app.current_tenant_id', true)::uuid` cast — the latent 22P02 bug class V39 fixed for the three storefront SELECT policies — from all 10 remaining raw-cast policies (payment_event_outbox_tenant, reviews_tenant_write, refunds_tenant_policy + refunds_aud, vendor_onboarding x4, processed_order_events_tenant, idempotency_keys_tenant), routing each through the safe `current_tenant_id()` helper; ALSO hardens `current_tenant_id()` itself by guarding its final `RETURN v::uuid` so a non-UUID GUC fails filtered (NULL → no rows) not errored (22P02). No data change, tenant semantics identical under a valid GUC; the `tenant_id::text = current_setting(...)` TEXT-comparison policies are deliberately untouched (no cast, no 22P02 risk). Permanent RlsContractTest.noPolicyUsesRawTenantGucCast pg_policy sweep guards against reintroduction; DEFERRED: the guest-tracking app.customer_email GUC DB-guard (#113 third item — TEXT comparison, new mechanism). Also removes the /ws?token= handshake query-param JWT path (JwtHandshakeInterceptor deleted; STOMP CONNECT Authorization header is the sole token source). V50 idempotency_keys [Issue #204 / AI-2]: tenant-scoped ENABLE+FORCE RLS dedup store keyed (tenant_id, endpoint, idempotency_key), request_hash + response_status/body columns, no _aud — mirrors V47; backs the uniform Idempotency-Key header contract adopted by orders.create + customers.create via a generic @Transactional IdempotencyService.execute (reserve-first INSERT ON CONFLICT DO NOTHING + defensive set_config GUC pin); same-key replay returns the original response, in-flight race → 409, same-key/different-body → 422; response_body carries customer PII so FORCE RLS is load-bearing, proven under the NOSUPERUSER role-downgrade. V49 Keycloak deprovisioning on offboard [Issue #102 remainder]: tenants.keycloak_deprovisioned_at nullable TIMESTAMPTZ — stamped only when ALL of an offboarded tenant's Keycloak users have been disabled + logged out across the configured realms; the identity-layer complement to TenantStatusInterceptor's request rejection so a stolen/cached token can no longer mint at the IdP. Deprovisioning runs best-effort AFTER the offboard tx commits (TransactionSynchronization.afterCommit → REQUIRES_NEW), so a Keycloak outage never rolls back the offboard (marker stays NULL, ERROR logged); an admin re-trigger endpoint POST /api/v1/admin/tenants/{id}/keycloak/deprovision recovers OFFBOARDED tenants (idempotent). Fully INERT by default: jtoye.keycloak.admin.enabled=false + empty base-url → one WARN no-op + RFC 7807 400 "not configured"; tenants stays RLS-free (no policy change). V48 tenant lifecycle + Stripe Connect [Issue #102]: tenants.status/plan/contact fields + suspended_at/offboarded_at + stripe_account_id/stripe_connect_status — the tenants registry stays deliberately RLS-free (role-gated admin API is the lifecycle writer; TenantStatusInterceptor rejects SUSPENDED/OFFBOARDED traffic), and MARKETPLACE orders route as Stripe destination charges to the linked ENABLED connected account per ADR-0001 Decision 2; V47 processed_order_events [QA-council disc-20260712-010550 FIX-2/H1]: semantic-key (tenant_id, order_id, new_status) dedup table for the at-least-once ORDER_STATE_CHANGED consumer, ENABLE+FORCE RLS tenant-scoped, mirrors the processed_stripe_events idempotency precedent; V46 outbox reliability [Issue #93]; V45 Phase 19 full-frontend overhaul [UIX-04]: orders.fulfilment_type + UK delivery-address columns + orders_aud mirror — enables checkout delivery-address capture + fee-before-payment and GDPR address scrub; V44 FTS tail [Issue #96 — filled reserved slot AFTER V45/V46 shipped, so spring.flyway.out-of-order=true is required and set in all profiles]: pg_catalog.ts_match_vq LEAKPROOF (superuser-only; graceful WARNING + documented manual step when the migration role lacks superuser) + idempotent tenant-looped backfill of NULL search_vector on products/shops; V43 vendor onboarding first slice [Phase 18]: vendor_onboarding + vendor_onboarding_gate + both Envers _aud mirrors, all ENABLE+FORCE RLS tenant-scoped; the onboarding state machine is the sole writer of Shop.published, gated by automatic BUSINESS_VERIFIED/FOOD_HYGIENE_RATING/ALLERGEN_DATA_COMPLETE checks; V42 GDPR erasure completeness [Issue #84]: erasure_records table — tenant-scoped, FORCE RLS, PII-free SHA-256 email hash — plus tenant-scoped UPDATE policies on orders_aud/customers_aud enabling the deliberate Article-17 PII scrub of append-only audit history; V41 PPDS/Natasha's Law label compliance [Issue #82]: products.allergen_spans/shelf_life_days/durability_type + products_aud mirrors, all nullable; V40 VAT ledger correctness [Issue #81]: products.vat_rate + financial_transactions.order_id + _aud mirrors + partial unique index uq_fin_tx_tenant_order + historical duplicate collapse)
- Next.js config: `frontend/next.config.mjs` (standalone output, image remotePatterns)
- TypeScript config: `frontend/tsconfig.json`
- ESLint: `frontend/eslint.config.mjs` (ESLint 9 flat config)
- Dockerfile: `edge-go/Dockerfile` (multi-stage, scratch-based runtime)
- Binary output: `/edge` executable
- Port: 8080 (customizable via PORT env var)
## Platform Requirements
- Docker & Docker Compose v2+ — the `docker compose` subcommand (for local stack)
- Java 25 JDK
- Node.js 24+
- Go 1.27+
- Git
- Gradle 9.7+ (included via wrapper)
- npm (included in Node.js)
- Docker (for building multi-stage images)
- Kubernetes (recommended) - See `k8s/` directory for manifests
- Docker container runtime
- PostgreSQL 15+ database
- Redis 7+ (external or managed service)
- RabbitMQ **3.13+** minimum (4.3 recommended — the dev/compose stack pins 4.3.4). The deployed staging/production broker's version is **unverified from this repository** — see `docs/runbooks/rabbitmq-broker-upgrade.md` and ADR-0002.
- Keycloak 24.0+ (external identity provider)
- Azure Blob Storage (Azurite locally)
- SMTP server (SendGrid, AWS SES, etc.)
- Spring Boot: 4.1.1 (Java 25)
- PostgreSQL: 15-alpine
- Keycloak: 24.0.5
- Redis: 7-alpine
- RabbitMQ: 4.3.4-management-alpine
- Azurite: 3.37.0
- Go: 1.27-alpine
- Node.js: 24+
- Next.js: 16.3.7
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
- Depends on: PostgreSQL database (RLS-enabled), Redis cache, RabbitMQ, Stripe API, Azure Blob Storage, Keycloak
- Used by: Frontend, Edge gateway, batch sync operations, webhook processors
- Purpose: ORM abstraction for tenant-scoped database queries
- Location: `core-java/src/main/java/uk/jtoye/core/*/` (repository interfaces in each domain folder)
- Contains: JpaRepository extensions with custom queries, named queries for full-text search
- Depends on: PostgreSQL JDBC driver, Flyway for schema migration
- Used by: Service layer exclusively
- Purpose: Multi-tenant data storage with RLS enforcement and audit trails
- Location: Schema defined in `core-java/src/main/resources/db/migration/` (75 Flyway migrations, V1 through V75)
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

Six quality dimensions are **standing acceptance criteria** — plans and executors treat them as build-time requirements on the relevant surfaces, not as things a later audit will catch. Each has an **audit-time counterpart** in the QA council (`/qa-discover` Phase 1 API / Phase 2 browser); the two must agree. **Security is the model** — it already gates at plan time (the `<threat_model>` block) and audits in QA Phase 1/5; the other three were brought to the same bar on 2026-07-14, the fifth (falsifiable evidence + runtime parity) on 2026-07-26, and the sixth (client-persisted identity lifecycle) on 2026-08-31.

The falsifiability dimension differs from the surface-scoped ones in an important way: it is not scoped to a surface at all. Web-perf applies to pages, SEO to public surfaces, agent-readiness to APIs, client-persisted identity lifecycle to storage-backed user state, security to everything with a threat model — but falsifiability applies to *every claim any of the others makes about itself*. It is the dimension that keeps the rest honest, which is why it was added only after a phase demonstrated that four green gates can coexist with a runtime that does not match its own branch.

- **Web performance (mobile-first)** — any phase touching a user-facing page owns its Core Web Vitals. For such phases, acceptance criteria include: no route regresses LCP/CLS/INP at a **throttled mobile profile**; no unbounded/duplicate bundle growth or unoptimised images shipped; measured against a **config-declared budget** where one exists (introduce one rather than inventing an ad-hoc number). "Builds clean" ≠ "loads fast" — verify on a throttled profile, never localhost-unthrottled.
- **SEO / discoverability** — any phase building or reworking a **public/unauthenticated** surface (storefront, marketing, shop pages, docs) owns its discoverability: unique title + meta description + canonical + Open Graph per page; schema.org JSON-LD on products/shops (Product/Offer/LocalBusiness); valid `sitemap.xml` + `robots.txt`; crawlable `<a href>` nav (not JS-only); no stray `noindex` on public pages. For J'Toye this is storefront reach → vendor revenue, not polish. Internal/authenticated dashboards are exempt (record N/A).
- **AI agent-readiness / machine-consumability** — any phase adding or changing an **API surface** owns its agent-operability: mutating endpoints carry an Idempotency-Key contract (or are provably idempotent); errors are typed/machine-parseable (RFC 7807, stable codes) not prose-only; credentials are scoped/least-privilege for the action; the OpenAPI/machine-readable contract matches live responses; and — where the MCP server exists — a core new capability gets a corresponding MCP tool (or a recorded reason it's out of scope). This is the standing form of the AI Readiness track (idempotency #204, scoped creds #206, MCP tools #203).
- **Security** — already contracted: every plan carries a `<threat_model>` block (ASVS L1), routed through `/gsd-secure-phase` + `/gsd-code-review` + CI scanners (trivy/gitleaks/dependabot). Listed here so the dimensions read as one set; no change to the existing gate.
- **Client-persisted identity lifecycle** — any surface that persists USER-SCOPED state client-side (localStorage/sessionStorage/IndexedDB: baskets, drafts, preferences) owns its identity-lifecycle TRANSITIONS — sign-in, sign-out, session lapse, account switch, new registration — tested THROUGH the transition, never as two steady states either side of it. Assert the stored ownership stamp BY CONTENT after the transition, not the rendered view: R-16 shipped because a test checked what the page showed while the same render silently erased the stamp on disk. Ownership markers may be ADDED or CONFIRMED by a write and REMOVED only by an explicit sign-out; a lapsed session is not a new person. N/A for state with no user scope (theme, cookie banner) — record it.

- **Falsifiable evidence + runtime parity** — added 2026-07-26 after Phase 26. Two halves, both standing acceptance criteria on every phase:

  **(a) Every acceptance criterion must be shown to FAIL before it is trusted.** Run it against a deliberately broken input, confirm it fails there and passes on the real tree, and record BOTH directions' real output. A criterion observed only passing is not evidence — it may be incapable of failing. This is not a hypothetical risk: Phase 26 found **~22** unfalsifiable criteria across its nine plans, plus three fail-open guards, and **two criteria whose satisfaction would have caused an outage** (one renamed the live AMQP broker user; one deleted the external-IdP issuer config). Every one was caught by running the fail direction; none by the criterion passing. If a criterion cannot fail, say so explicitly, replace it with a strictly stronger form, and record both — never silently substitute, and never report the vacuous pass as satisfied. Known vacuous shapes: an already-0 grep; a diff that compares a file to itself when its baseline lookup fails; a scan direction defeated by output ordering (`kubectl kustomize` sorts map keys alphabetically); an expected-0 that is 1 on the *correct* tree; a doc rule that must name the token it forbids (`grep -v '^\s*#'` filters only full-line comments); a build reporting success while executing nothing (`UP-TO-DATE`, cached, skipped); reading a stale artifact dir (`core-java/build/` is stale — the live one is `build-local`); and a guard that fails OPEN — `cmd | grep -q X` under `set -o pipefail` **inverts** on match via SIGPIPE→141, so use here-strings, and missing tooling / unparseable / EMPTY output must exit non-zero (VOID), never 0.

  **(b) A phase is not done until the DELIVERED RUNTIME matches the branch.** HTTP 200, a rendered page title, "builds clean", and a green suite are identical whether the running code is current or months stale — Phase 26 shipped with a runtime missing its own `application.yml` change and three merged UI PRs, past four green gates, and the user caught it by eye. So: any step that restores or hands back a runtime after source changed **must rebuild** — `docker compose start` starts existing containers and does not rebuild — and `git log HEAD..origin/main` must be empty (or a merge recorded) before a PR, because a branch behind its base ships missing work that no rebuild can fix. Prove parity by content and identity, not by status code: compare each image's **`.Metadata.LastTagTime`** (NOT `.Created`, which Docker preserves across a fully-cached rebuild) against the newest commit touching that image's build paths, and read the value out of the running artifact — for a Spring Boot fat jar, `unzip -p /app/app.jar BOOT-INF/classes/application.yml`, since a filesystem `find` returns a misleading `0`. An old image is **not** automatically stale: if nothing it builds from changed, it is correct. Enforced by two executable gates, one per half: **`scripts/check-runtime-freshness.sh`** (runtime vs tree — per-service `.Metadata.LastTagTime` vs the newest commit touching that service's build paths, plus the running container's image ID vs the tag's, which catches a rebuild that was only `start`ed) and **`scripts/check-branch-behind-base.sh`** (tree vs base — `HEAD..origin/<default>` must be empty, base resolved from the remote and never hardcoded). Both fail closed at exit **2** (VOID) on missing tooling, an empty discovery result, or a stopped stack — "found nothing" is never "clean". For the runtime half this is enforced **per service** (tightened 2026-07-27, plan 27-00 Task 6): **any** built service that is missing or not `running` VOIDs the whole run. It previously VOIDed only when *every* built service was unverifiable, so stopping one of four printed `PASS: 3 … (1 unverified)` and exited 0 — an unproven service reported inside a pass. Documented in `k8s/DEPLOYMENT.md` ("Runtime-parity gates"); the branch half also runs in CI, the runtime half deliberately does not (a CI runner has no running containers, so it could only ever be VOID there).

Accessibility stays contracted via the existing UI standards + QA Phase 4. When a dimension genuinely doesn't apply to a phase, record it **N/A** — never silently drop it (same rule as the QA council roster).

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->

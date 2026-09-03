# External Integrations

**Analysis Date:** 2026-09-03

This document distinguishes **LOCAL-DEV** integrations (run as compose containers, swappable/fake) from **STAGING/PROD** integrations (external services the deployed cluster talks to). Every value below is traced to `docker-compose.full-stack.yml`, `core-java/src/main/resources/application*.yml`, `k8s/base` + overlays, or actual client code — nothing aspirational.

## APIs & External Services

**Payments:**
- Stripe — PaymentIntent creation, Stripe Connect (Express accounts, destination charges for MARKETPLACE tenants per ADR-0001 Decision 2), refunds, webhook signature verification.
  - SDK/Client: `com.stripe:stripe-java:33.3.0` (server); `@stripe/react-stripe-js` 6.8.2 + `@stripe/stripe-js` 9.14.0 (browser Elements)
  - Client classes: `core-java/src/main/java/uk/jtoye/core/payment/StripeConnectService.java`, `StripeRefundClient.java`, `StripeProperties.java`
  - Auth: `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` (both default empty — feature is inert without them)
  - Circuit breaker: `resilience4j.circuitbreaker.instances.stripe` (`application.yml:727-732`)
  - Config: `stripe.currency` (default `gbp`), `stripe.platform-fee-bps` (basis points, default 0), `stripe.connect.country` (default `GB`)
  - **LOCAL-DEV and STAGING/PROD identical mechanism** — Stripe is a real external API in every environment; only the key differs (test vs live).

**Vendor Onboarding Gates (Phase 18):**
- Food Hygiene Rating Scheme (FHRS) — `uk.jtoye.core.onboarding.client.FhrsClient` / `FhrsEstablishment.java`. Free API, no key, requires `x-api-version: 2` header.
  - Base URL: `onboarding.fhrs.base-url` (default `https://api.ratings.food.gov.uk`)
  - Min rating gate: `onboarding.fhrs.min-rating` (default 2 — "Deliveroo/Uber parity")
  - Circuit breaker: `resilience4j.circuitbreaker.instances.fhrs`
  - **Real external API in every environment** — no local mock; dev talks to the live FHRS API.
- Companies House — `uk.jtoye.core.onboarding.client.CompaniesHouseClient` / `CompanyProfile.java`. Free, API-key auth (HTTP Basic, key as username).
  - Base URL: `onboarding.companies-house.base-url` (default `https://api.company-information.service.gov.uk`)
  - Auth: `COMPANIES_HOUSE_API_KEY` (default empty)
  - Circuit breaker: `resilience4j.circuitbreaker.instances.companies-house`
  - **Real external API in every environment.**

**AI / Image Analysis:**
- Ollama (LOCAL-DEV) — local vision LLM, `gemma3:12b` model, GPU-accelerated. `ollama/ollama:${OLLAMA_IMAGE_TAG:-latest}` container in `docker-compose.full-stack.yml`; `OLLAMA_KEEP_ALIVE=-1` keeps the model resident in VRAM (cold load measured at ~72s, warm ~400ms). Compose exposes it on host port `11435` for developer curl access only — nothing in the stack uses the published port; core-java reaches it as `http://ollama:11434` over the bridge network.
  - Config: `ai.provider` (`ollama` default), `ai.ollama.url`, `ai.ollama.model`
- Anthropic Claude (STAGING/PROD alternative provider) — `ai.provider=anthropic` switch. `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java:91` — model `claude-sonnet-4-20250514`, called via the WebFlux `WebClient` (this is what the `spring-boot-starter-webflux` dependency comment "Claude API calls" refers to).
  - Auth: `ANTHROPIC_API_KEY` (default empty — disabled with a WARN log if provider=anthropic and key unset)
  - Circuit breaker: `resilience4j.circuitbreaker.instances.ai`
  - Feature flag: `ai.enabled` (default true), plus the separate advisory vision-relevance stage `jtoye.media.vision.enabled` (default **false** — "Ollama unreliable")
  - **Neither is wired in staging/prod compose/k8s manifests examined** — `ai.provider` defaults to `ollama`, which has no equivalent staging/prod deployment in this repo; Anthropic is the documented failover path but its production wiring (secret, k8s env) was not found in `k8s/base`.

**WhatsApp/SMS (scaffolded, not live):**
- `uk.jtoye.core.notification` WhatsApp channel — Phase 22 COMMS-07 seam. Fully inert by default: `WhatsAppSmsChannel` logs one WARN and no-ops unless `whatsapp.enabled=true` AND all provider credentials present. Live send is explicitly out of scope (#208 scaffold-only).
  - Config: `WHATSAPP_ENABLED` (default false), `WHATSAPP_PROVIDER`, `WHATSAPP_ACCOUNT_SID`, `WHATSAPP_AUTH_TOKEN`, `WHATSAPP_FROM_NUMBER` (all default empty)
  - Edge gateway also has a WhatsApp webhook handler (HMAC-signed inbound) at `edge-go/internal/` per its main.go responsibilities — inbound only.

## Data Storage

**Databases:**
- PostgreSQL 15
  - LOCAL-DEV: `postgres:15-alpine` container, `docker-compose.full-stack.yml`, published on `127.0.0.1:5433:5432` (loopback-only by default, `JTOYE_BIND_HOST`), `max_connections=200` explicit
  - STAGING/PROD: external/managed Postgres, connection budget math enforced by `k8s/scripts/check-connection-math.sh` (HikariCP pool sizes: 12 per pod in staging/prod vs 20 in dev, sized against HPA replica ceiling)
  - Runtime/migrator role split (SEC-04 / #552): app connects as `jtoye_runtime` (DML-only), Flyway migrates as `jtoye_app`/`DB_MIGRATION_USER` (schema owner) — two separate credential pairs (`DB_USER`/`DB_PASSWORD` vs `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD`)
  - Client: Spring Data JPA / Hibernate, JDBC driver `org.postgresql:postgresql:42.7.13`
  - RLS: enabled + FORCE RLS on every tenant-scoped table; enforced by `RlsContractTest` with an explicit `EXEMPT_TABLES` allowlist (currently: `tenants`, `dsar_request`, `postcode_centroid`, plus others documented per-table in migration headers)
  - Keycloak also persists to the SAME Postgres instance (`keycloak` DB) — `KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak`

**File Storage:**
- S3-compatible object storage — copy-on-write `media_asset` model (Phase 24), safe async upload pipeline (quarantine → validate → transcode → WebP derivative + thumbnail).
  - LOCAL-DEV: MinIO (`minio/minio:${MINIO_IMAGE_TAG:-latest}`), console on `9001`, S3 API on `9000` (both loopback-only). Bucket `jtoye-images` bootstrapped by a digest-pinned `minio-init` job (`minio/mc:${MINIO_MC_IMAGE_TAG}`) with an anonymous `s3:GetObject`-only policy (no `s3:ListBucket` — objects are readable by URL but not enumerable).
  - STAGING/PROD: real AWS S3 (or equivalent), via `software.amazon.awssdk:s3` (BOM 2.54.3). `frontend/next.config.mjs` has a commented-out placeholder for production S3/CloudFront `remotePatterns` — not yet activated.
  - Config: `storage.s3.endpoint/region/bucket/access-key/secret-key/public-url`, all `${ENV:default}`, defaults pointing at local MinIO.

**Caching:**
- Redis 7
  - LOCAL-DEV: `redis:7-alpine`, `--appendonly yes --requirepass`, loopback-only port `6379`
  - STAGING/PROD: external/managed Redis; `application-staging.yml` and `application-prod.yml` both make `REDIS_PASSWORD` **required with no default** (fails startup via `PlaceholderResolutionException` rather than starting unauthenticated — closed 2026-09-02 per QA-council SEC-7/A7, staging was previously the one deployed profile that failed open)
  - Client: Spring Data Redis (Lettuce), pool tuned per profile (dev: 8 active/8 idle; staging: 15/8; prod: 20/10)
  - Used for: distributed cache (`@Cacheable`, tenant-aware key generator), Bucket4j rate-limit buckets, tenant-lifecycle status TTL cache

## Message Queue

- RabbitMQ 4.3.4-management-alpine (LOCAL-DEV compose pin) — AMQP `5672`, management UI `15672`, STOMP `61613`, all loopback-only by default.
  - Client: Spring AMQP, `com.rabbitmq:amqp-client` pinned to 5.33.1 via `rabbit-amqp-client.version` Gradle property.
  - Transactional outbox pattern for at-least-once delivery: `payment_event_outbox` (existing) and `media_event_outbox` (V58, Phase 24) — each with its own flusher (`PaymentEventOutboxFlusher`, dedicated media flusher), exponential backoff + resurrect.
  - STOMP relay mode (`stomp.broker.mode`) — `in-memory` in compose (default), RabbitMQ STOMP relay in k8s for multi-replica SSE/KDS fan-out.
  - **STAGING/PROD broker version is unverified from this repository** — minimum supported 3.13+, RabbitMQ 4.3 community support ends 2026-11-30 (tracked `infra/dependency-horizons.yaml`, deferred to 2026-11-30 under issue #724/PR #725).

## Authentication & Identity

**Auth Provider: Keycloak 24.0.5** (two realms, dual-issuer split-horizon pattern throughout)
- `jtoye-dev` realm — staff/vendor identity (dashboard admin users). Frontend NextAuth.js (`next-auth` 5.0.0-beta.32, `frontend/auth.ts`) uses the `next-auth/providers/keycloak` provider.
- `jtoye-customers` realm — B2C customer identity (Phase 18 realm split), storefront sign-in / order history. Separate `CustomerJwtVerifier` on the backend, separate `customer-auth.ts` on the frontend.
- Google identity provider — federated into the `jtoye-customers` realm (ADR-0005), **disabled by default** (`GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` empty by default in the realm-render template).
- Split-horizon issuer pattern (issue #87): JWKS fetched from the INTERNAL Keycloak host (`http://keycloak:8080/realms/...`, reachable in-container), but the token's `iss` claim is validated against the PUBLIC issuer (`http://localhost:8085/realms/...` in dev). Both `KC_ISSUER_URI`/`JWT_EXPECTED_ISSUER` (staff) and `CUSTOMER_KC_ISSUER_URI`/`CUSTOMER_JWT_EXPECTED_ISSUER` (customer) follow this pattern.
- Implementation:
  - Core API: Spring OAuth2 Resource Server with a custom `JwtDecoder` bean (NOT the auto-configured one — `spring.security.oauth2.resourceserver.jwt.audiences` is deliberately not used).
  - Edge gateway: `golang-jwt/jwt/v5` validates against Keycloak JWKS fetched over the internal host; expected audience `core-api` (fail-closed, explicit).
  - MCP server: Bearer pass-through only — forwards the caller's own token verbatim to core-java, validates nothing itself (RLS in core is the sole isolation boundary; T-20-04 SSRF guard keeps `CORE_BASE_URL` a fixed internal service name).
- Keycloak admin API (deprovisioning) — `jtoye.keycloak.admin.enabled` (default false), disables an offboarded tenant's Keycloak users post-commit (best-effort, `REQUIRES_NEW` after the offboard transaction).
- LOCAL-DEV: Keycloak container backed by the shared Postgres, realm JSON rendered from a template at container start (`keycloak-realm-render` service, `envsubst`) so no secret is committed.
- STAGING/PROD: external Keycloak 24.0+ instance (deployment target unverified from this repo beyond the version floor).

## Vendor-Scoped Access / Machine Clients

- `ACCESS_MACHINE_CLIENT_IDS` — allowlist of client-credentials service accounts (non-UUID JWT `sub`) permitted to bypass shop-scoping JIT provisioning. Currently includes `integration-orders-rw` (the MCP write service account).
- `ACCESS_STRICT_SCOPING` — global feature flag (default `false`), governs whether an ungranted tenant user is implicitly a tenant-wide `GROUP_ADMIN`.

## Monitoring & Observability

**Error Tracking:** None — no Sentry, Rollbar, or equivalent found in `core-java/build.gradle.kts` or `frontend/package.json`.

**Metrics:**
- Micrometer + Prometheus registry (`io.micrometer:micrometer-registry-prometheus`) on core-java, exposed at `/actuator/prometheus` (base profile: disabled by default, opt-in per non-prod profile; prod serves it on a **separate internal management port 9091**, never the public app port).
- Edge gateway: `prometheus/client_golang` v1.24.1, served on a dedicated metrics port (`EDGE_MANAGEMENT_PORT`, default `9101`) — deliberately not published under compose `ports:`, reached over the bridge network by container name only.
- Self-hosted scrape/dashboard/alert stack (`infra/monitoring/docker-compose.monitoring.yml`): Prometheus v2.48.0, Grafana 10.2.2, Alertmanager v0.27.0, plus `redis_exporter` v1.58.0 and `postgres_exporter` v0.15.0.

**Tracing:**
- Micrometer Tracing with the Brave bridge (`micrometer-tracing-bridge-brave`) + `zipkin-reporter-brave`.
- Endpoint: `management.zipkin.tracing.endpoint` (default `http://localhost:9411/api/v2/spans` — no Zipkin container found wired into `docker-compose.full-stack.yml`, so tracing export is a dev/optional capability, not part of the standard local stack).
- Sampling: `management.tracing.sampling.probability` (default 0.1 = 10%, override via `TRACING_PROBABILITY`).

**Logs:**
- Backend: SLF4J via Spring Boot logging; JSON-structured console pattern in staging/prod for log aggregation (ELK/Splunk-style ingestion, no specific aggregator wired in this repo); plain pattern in dev.
- Edge gateway: `go.uber.org/zap` v1.28.0 structured logging.
- Frontend: `console.log`/`console.error` only.
- MCP server: `pino` ^10 structured logging.

## CI/CD & Deployment

**Hosting:**
- Container registry: GitHub Container Registry, `ghcr.io/bralabee/jtoye-{core-java,edge-go,frontend,pg-backup}`.
- Deploy target: Kubernetes via Kustomize (`k8s/base` + `k8s/staging`/`k8s/production` overlays), driven by the `ci-cd.yaml` deploy job + `scripts/deploy.sh` (sealed secrets, NetworkPolicies).
- Local dev/E2E runtime: Docker Compose (`docker-compose.full-stack.yml`) — canonical, XOR with local minikube (`k8s/local` overlay) at *local* runtime only; never both simultaneously (shared dev DB).

**CI Pipeline:**
- GitHub Actions (`.github/workflows/ci-cd.yaml` + 6 sibling workflows: `docs-freshness.yml`, `e2e-nightly.yml`, `gitleaks.yml`, `pii-guard.yml`, `review-record.yml`, `base-image-freshness.yml`).
- Jobs pin: `java-version: '25'` (temurin), `go-version: '1.27'`, `node-version: '24'` — consistent across every job that declares a toolchain.
- Security scanning: Trivy (image + filesystem, SARIF → GitHub code scanning), gitleaks (secret scanning), pii-guard (rejects committed DB dumps), Dependabot (weekly, per-ecosystem).
- Nightly E2E: `e2e-nightly.yml` tears the stack down with `down -v` each run (fresh volume every night).

## Environment Configuration

**Required env vars (fail closed with `:?` in compose, or no default in a deployed Spring profile):**
- `POSTGRES_PASSWORD`, `DB_PASSWORD`, `DB_MIGRATION_PASSWORD` (falls back to `DB_PASSWORD` if empty — deliberate single-credential support)
- `KEYCLOAK_ADMIN_PASSWORD`, `KC_DB_PASSWORD`
- `REDIS_PASSWORD` (staging/prod: no default at all, must be set)
- `RABBITMQ_DEFAULT_PASS`
- `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`
- `NEXTAUTH_SECRET`, `KEYCLOAK_CLIENT_SECRET`, `EDGE_API_CLIENT_SECRET`
- `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` (required build-arg, inlined into the frontend bundle)
- `KC_SEED_USER_PASSWORD`, `INTEGRATION_CATALOG_RO_SECRET`, `INTEGRATION_ORDERS_RW_SECRET`, `CUSTOMER_STOREFRONT_REDIRECT_URIS`, `CUSTOMER_STOREFRONT_WEB_ORIGINS` (Keycloak realm render inputs)

**Optional / inert-by-default (empty-string default = feature off):**
- `ANTHROPIC_API_KEY`, `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `COMPANIES_HOUSE_API_KEY`
- `WHATSAPP_*` (all four)
- `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` (customer realm Google IdP)
- `NOTIFICATION_UNSUBSCRIBE_SECRET`, `NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL`
- `KC_ADMIN_ENABLED` (Keycloak deprovisioning on tenant offboard)

**Secrets location:**
- Local dev: `.env` (git-ignored, present at repo root; not read for this document per secret-handling policy) + `.env.example` template (present, also not read).
- Staging/production: Kubernetes Sealed Secrets (`k8s/base/secrets-template.yaml.example` is the template; actual secrets are never committed).

## Webhooks & Callbacks

**Incoming:**
- Stripe webhook — signature-verified via `STRIPE_WEBHOOK_SECRET`, handled in the payment package.
- WhatsApp webhook — HMAC-signed, handled at the edge gateway (`edge-go`), inbound-only; parser noted elsewhere as incomplete.
- DSAR verification link — `POST/GET` public endpoint, `dsar.verify-base-url` (default `http://localhost:8080/api/v1/public/gdpr/dsar/verify`), single-use bearer token with a 168h (7-day) TTL, config-injected base URL (never derived from the request `Host` header — deliberate anti-hijack measure).
- One-click unsubscribe (RFC 8058) — `notification.unsubscribe.one-click-base-url`, empty by default (fail-safe: absent → advertises a working page link instead of promising a POST target that can't honour it).

**Outgoing:**
- Platform webhook delivery engine (Phase 22 COMMS-05) — `uk.jtoye.core.webhook` package: `WebhookSubscriptionController`/`Service` (tenant-managed subscriptions), `WebhookDeliveryWorker` (delivery loop, exponential backoff, auto-pause after `webhook.delivery.auto-pause-threshold` consecutive failures, default 10), `WebhookSigner` (HMAC request signing), `WebhookUrlValidator` + `SsrfGuardAddressResolverGroup` (blocks private IP ranges by default, `webhook.target.block-private-ranges=true`, re-guards against DNS rebinding at delivery time), `WebhookRetentionCleanup` (30-day default retention on `webhook_delivery` rows).
- Config: `webhook.delivery.max-attempts` (8), `backoff-base-ms`/`backoff-cap-ms` (1s/1h), `timeout-seconds` (10), `signature-tolerance-seconds` (300).
- Backed by `webhook_subscription` (V55) and `webhook_delivery` (V56) tables, plus `webhook.envelope.version` for the payload envelope schema.

## MCP Server (AI Agent Surface)

Read-mostly + narrow-write Model Context Protocol server (`mcp-server/`, Phase 20/25), Bearer pass-through over the core REST API — RLS is the sole tenant-isolation boundary, the container holds no client secret and no DB credentials.
- Tools (`mcp-server/src/tools/`): `list-shops.ts`, `list-products.ts`, `read-orders.ts` (all read), `create-customer.ts`, `create-order.ts` (mutating — Phase 25, Idempotency-Key contract, NOSUPERUSER-proven RLS).
- Transport: `@modelcontextprotocol/sdk` ^1.29.0, hosted over `express` ^5 (Streamable HTTP).
- LOCAL-DEV: `jtoye-mcp-server` container, port `9100`, `CORE_BASE_URL=http://core-java:9090` (fixed internal name, SSRF guard T-20-04).

---

*Integration audit: 2026-09-03*

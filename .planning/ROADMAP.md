# Roadmap: J'Toye OaaS — Milestone 3 (v2.1 Post-Audit Hardening + Storefront Completion)

## Overview

Milestone 3 closes the three highest-priority gaps from the 2026-04-14 state-of-codebase audit (Work Orders A+B+C). Phase 9 ships first as a standalone safety net — it rotates committed credentials and wires the 13 existing Prometheus alert rules into Alertmanager so the platform stops firing alerts into the void. Phase 10 closes the vendor marketing loop by rendering promotions and announcements on the storefront plus adding the two missing customer routes (cart page, order history). Phase 11 swaps the in-memory STOMP broker for a RabbitMQ relay so `core-java` can scale past one replica without losing kitchen broadcasts — it depends on Phase 9 because STMP-05 reuses the Alertmanager route SECR-04 installs.

Phase numbering continues from Milestone 2 (phases 1–8 complete). New work starts at Phase 9.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (9.1, 10.1): Urgent insertions (marked with INSERTED)

Milestone 2 phases 1–8 are complete. Milestone 3 adds phases 9–11.

- [x] **Phase 1: API Versioning — Backend** - Add /api/v1/ prefix to all Spring Boot endpoints with webhook exemptions and updated Swagger docs (completed 2026-04-07)
- [x] **Phase 2: API Versioning — Edge & Frontend** - Update Go edge gateway routes and Next.js API client for /api/v1/ paths (completed 2026-04-08)
- [x] **Phase 3: Vendor Marketing Backend** - Promotion CRUD with scheduling, announcement entity extraction and CRUD (completed 2026-04-08)
- [x] **Phase 4: Vendor Dashboard UI** - Dashboard page for vendors to manage promotions and announcements (completed 2026-04-08)
- [x] **Phase 5: KDS Security & WebSocket Foundation** - Spring WebSocket/STOMP config with tenant-aware channel security (completed 2026-04-08)
- [x] **Phase 6: KDS Event Pipeline** - Route WebSocket events through RabbitMQ consumer for single event pipeline (completed 2026-04-08)
- [x] **Phase 7: Kitchen Display UI** - Real-time order card feed with status bumping, age indicators, and audio alerts (completed 2026-04-09)
- [x] **Phase 8: Test Coverage Closure** - Tests for PaymentController, PublicStorefrontController, security filters, and GdprController (completed 2026-04-09)
- [ ] **Phase 9: Repository Secrets + Alerting** - Remove committed .env, rotate 5 credentials, deploy Alertmanager with Slack routing for 13 existing alert rules
- [ ] **Phase 10: Storefront Marketing Render + Missing Customer Routes** - Public promotions/announcements endpoints, storefront render, standalone cart page, customer order history, full-flow Playwright e2e
- [ ] **Phase 11: STOMP Broker Relay for Horizontal Scale** - Swap SimpleBroker for StompBrokerRelay behind stomp.broker.mode flag, enable RabbitMQ STOMP plugin, two-replica broadcast verification

## Phase Details

### Phase 1: API Versioning — Backend
**Goal**: All Spring Boot REST endpoints are accessible under /api/v1/ with webhook paths exempted and docs updated
**Depends on**: Nothing (first phase)
**Requirements**: APIV-01, APIV-04, APIV-05
**Success Criteria** (what must be TRUE):
  1. Every REST endpoint responds under /api/v1/ prefix (e.g., GET /api/v1/shops returns shops)
  2. Stripe webhook and WhatsApp webhook paths remain accessible at their original paths without /api/v1/ prefix
  3. Swagger UI at /swagger-ui.html shows all endpoints with /api/v1/ paths
  4. Existing tests pass with the new path structure
**Plans**: 1 plan

Plans:
- [x] 01-01-PLAN.md — Add /api/v1/ prefix via WebMvcConfigurer and update all tests

### Phase 2: API Versioning — Edge & Frontend
**Goal**: The full request path from browser through Go edge to Spring Boot uses /api/v1/ consistently
**Depends on**: Phase 1
**Requirements**: APIV-02, APIV-03
**Success Criteria** (what must be TRUE):
  1. Go edge gateway proxies requests to /api/v1/ backend paths correctly
  2. Next.js storefront loads and functions with all API calls using /api/v1/ paths
  3. No 404s or routing errors in browser console when navigating the storefront
**Plans**: 1 plan

Plans:
- [x] 02-01-PLAN.md — Update Go edge routes and Next.js dashboard API calls for /api/v1/ paths

### Phase 3: Vendor Marketing Backend
**Goal**: Vendors have full API access to create and schedule promotions and announcements for their shops
**Depends on**: Phase 2
**Requirements**: VMKT-01, VMKT-02, VMKT-03, VMKT-04
**Success Criteria** (what must be TRUE):
  1. Vendor can create a promotion with discount type (PERCENTAGE or FLAT_AMOUNT) and amount, via new PromotionController. Flyway migration extends existing ShopPromotion entity with discountType + discountAmountPennies.
  2. Promotion with validFrom/validUntil dates only applies within the scheduled window (timezone-aware)
  3. Announcement entity exists as its own table (migrated from Shop.announcements TEXT[]) with Flyway migration
  4. Vendor can create, edit, and delete announcements with scheduling via API
  5. All marketing endpoints enforce RLS -- vendor A cannot see or modify vendor B's promotions or announcements
**Plans**: 2 plans

Plans:
- [x] 03-01-PLAN.md — Flyway V29 migration, ShopPromotion extension with discount types, PromotionController CRUD
- [x] 03-02-PLAN.md — ShopAnnouncement entity and CRUD, public storefront endpoints, ShopConfigDto update

### Phase 4: Vendor Dashboard UI
**Goal**: Vendors can manage their promotions and announcements through a dedicated dashboard page
**Depends on**: Phase 3
**Requirements**: VMKT-05
**Success Criteria** (what must be TRUE):
  1. Vendor sees a dashboard page listing all their promotions and announcements
  2. Vendor can create, edit, and delete promotions from the dashboard UI
  3. Vendor can create, edit, and delete announcements from the dashboard UI
  4. Dashboard displays scheduling information (active, upcoming, expired) for promotions and announcements
**Plans**: 1 plan
**UI hint**: yes

Plans:
- [x] 04-01-PLAN.md — Marketing dashboard page with Promotions and Announcements CRUD tabs

### Phase 5: KDS Security & WebSocket Foundation
**Goal**: WebSocket connections are tenant-isolated and JWT-authenticated, preventing cross-tenant data leakage
**Depends on**: Phase 2
**Requirements**: KDS-01, KDS-02, KDS-03
**Success Criteria** (what must be TRUE):
  1. WebSocket STOMP endpoint accepts connections at a known path (e.g., /ws). Existing SSE (OrderSseService) kept for dashboard; WebSocket is additive for KDS only.
  2. Connections without a valid JWT are rejected before subscribing to any topic
  3. A tenant's WebSocket subscription only receives events for their own shops (not broadcasts to all tenants)
  4. TenantContext is available inside WebSocket message handlers, matching the authenticated user's tenant
**Plans**: 1 plan

Plans:
- [x] 05-01-PLAN.md — WebSocket/STOMP config, JwtHandshakeInterceptor, TenantChannelInterceptor with tenant-isolated security

### Phase 6: KDS Event Pipeline
**Goal**: Order state changes flow through RabbitMQ into WebSocket broadcasts as a single unified event pipeline
**Depends on**: Phase 5
**Requirements**: KDS-08
**Success Criteria** (what must be TRUE):
  1. When an order status changes, a RabbitMQ message is published
  2. A consumer picks up the message and broadcasts it to the correct tenant's WebSocket topic
  3. The existing SSE broadcast-to-all-tenants pattern is not used -- events are scoped to the correct tenant
**Plans**: 1 plan

Plans:
- [x] 06-01-PLAN.md — Wire SimpMessagingTemplate WebSocket broadcast into OrderStateChangeListener

### Phase 7: Kitchen Display UI
**Goal**: Kitchen staff see a live order feed and can manage order progression in real time
**Depends on**: Phase 6
**Requirements**: KDS-04, KDS-05, KDS-06, KDS-07
**Success Criteria** (what must be TRUE):
  1. Kitchen display page shows incoming orders as cards that appear in real time (no page refresh needed)
  2. Kitchen staff can tap/click a button to bump an order from PREPARING to READY and the change reflects immediately
  3. Order cards change colour based on age: green (fresh), yellow (aging), red (overdue)
  4. An audio alert plays when a new order arrives on the kitchen display
**Plans**: 1 plan
**UI hint**: yes

Plans:
- [x] 07-01-PLAN.md — Kitchen display page with STOMP WebSocket, order cards, status bumping, age indicators, audio alerts

### Phase 8: Test Coverage Closure
**Goal**: Previously untested critical paths have test coverage, bringing the test suite above the milestone baseline
**Depends on**: Nothing (can run in parallel with any phase)
**Requirements**: TEST-01, TEST-02, TEST-03, TEST-04
**Success Criteria** (what must be TRUE):
  1. PaymentController webhook endpoint has tests covering successful payment, failed payment, and invalid signature scenarios
  2. PublicStorefrontController has tests covering shop listing, product listing, and search
  3. JwtTenantFilter and TenantFilter have tests verifying tenant extraction, missing header rejection, and cross-tenant blocking
  4. GdprController has integration tests covering export and erasure endpoints with tenant isolation verification
  5. Total test count exceeds 310 (the pre-milestone baseline)
**Plans**: 2 plans

Plans:
- [x] 08-01-PLAN.md — PaymentController webhook tests + PublicStorefrontController endpoint tests
- [x] 08-02-PLAN.md — JwtTenantFilter/TenantFilter security filter tests + GdprController integration tests

### Phase 9: Repository Secrets + Alerting
**Goal**: Committed credentials are purged and rotated, and every existing Prometheus alert rule reaches a human via Slack within 60 seconds
**Depends on**: Nothing (standalone safety net; ships first in milestone 3)
**Requirements**: SECR-01, SECR-02, SECR-03, SECR-04, SECR-05, SECR-06
**Success Criteria** (what must be TRUE):
  1. `.env` is no longer tracked by git (`git check-ignore .env` succeeds) and a gitignore entry prevents re-adds
  2. All 5 previously committed credentials (Postgres jtoye/keycloak roles, Keycloak admin, Redis, RabbitMQ, Keycloak client secret) have been rotated in running services and the rotated values exist only in GitHub Secrets + k8s Secret manifests
  3. Prometheus + Alertmanager run side-by-side in `infra/monitoring/docker-compose.monitoring.yml` with Prometheus's `alerting.alertmanagers` block pointing at the Alertmanager container
  4. Force-killing `core-java` produces a Slack message on the configured webhook channel within 60 seconds (end-to-end alert roundtrip verified, runbook entry captured)
  5. All 13 existing Prometheus alert rules carry `severity` and `service` labels and route to the Slack receiver without warnings in Alertmanager config-check
**Plans**: TBD

### Phase 10: Storefront Marketing Render + Missing Customer Routes
**Goal**: Customers can see the promotions and announcements vendors publish, land on the two previously-missing customer routes without 404s, and complete a full browse→cart→checkout flow end-to-end
**Depends on**: Nothing from milestone 3 (independent of Phase 9 and 11; can run in parallel with either)
**Requirements**: STFR-01, STFR-02, STFR-03, STFR-04, STFR-05, STFR-06
**Success Criteria** (what must be TRUE):
  1. Hitting `GET /public/shops/{slug}/promotions` and `GET /public/shops/{slug}/announcements` returns only active (validFrom ≤ now ≤ validUntil) records scoped to the tenant that owns `{slug}` — cross-tenant probes return empty, controller-level integration tests cover both positive and negative paths
  2. Opening `/shop/[slug]` as an unauthenticated visitor shows the announcement banner above the menu and renders discount badges on the product cards that match an active promotion (verified with Playwright against the full stack)
  3. Navigating directly to `/shop/[slug]/cart` renders the standalone cart page — populated from the same localStorage key as the modal cart, supporting quantity edit, checkout link, and graceful empty-cart + missing-shop states (Jest covers both states)
  4. Navigating to `/shop/orders` as a logged-in customer lists all of that customer's orders across every shop, with status filter, date filter, and pagination; unauthenticated visitors are redirected by `RequireCustomerAuth`
  5. A Playwright e2e walks shop discovery → shop detail → add to cart → cart page → Stripe test-mode checkout → confirmation screen in a single run against the full docker-compose stack and passes in CI
**Plans**: TBD
**UI hint**: yes

### Phase 11: STOMP Broker Relay for Horizontal Scale
**Goal**: `core-java` can run with two or more replicas behind a load balancer without losing kitchen WebSocket broadcasts, and operators see STOMP broker lag in Prometheus/Grafana with alerting wired through the Phase 9 Alertmanager
**Depends on**: Phase 9 (STMP-05 reuses the Alertmanager + Slack route installed in SECR-04/SECR-05)
**Requirements**: STMP-01, STMP-02, STMP-03, STMP-04, STMP-05
**Success Criteria** (what must be TRUE):
  1. `WebSocketConfig.java` reads a `stomp.broker.mode` property — `in-memory` mode preserves today's `enableSimpleBroker` behaviour for local dev; `relay` mode calls `enableStompBrokerRelay("/topic","/queue")` with host/port/login wired from env
  2. RabbitMQ has the `rabbitmq_stomp` plugin enabled in both `docker-compose.full-stack.yml` and the `k8s/` manifests, port 61613 is exposed, and relay credentials live as k8s Secret entries referenced via env vars
  3. Running `docker compose up --scale core-java=2` and publishing an order state change to replica B causes a kitchen client connected to replica A to receive the message within 2 seconds (smoke-test log captured)
  4. A Playwright e2e running against the two-replica stack in `relay` mode opens `/dashboard/kitchen`, triggers an order state change via REST on a different replica, and asserts the WebSocket message arrives within 2 seconds — green in CI
  5. A Prometheus alert rule on RabbitMQ STOMP exchange lag > 5 seconds fires into the Phase 9 Alertmanager Slack route, and a Grafana dashboard tile displays live STOMP connection count
**Plans**: TBD

## Progress

**Execution Order:**
Milestone 2: phases 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 (all complete)
Milestone 3: phase 9 first (standalone safety net, 2 days), then phases 10 and 11 in parallel where possible. Phase 11 must not start STMP-05 until Phase 9 SECR-04/SECR-05 are complete (shared Alertmanager route).

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. API Versioning -- Backend | 1/1 | Complete    | 2026-04-07 |
| 2. API Versioning -- Edge & Frontend | 1/1 | Complete    | 2026-04-08 |
| 3. Vendor Marketing Backend | 2/2 | Complete    | 2026-04-08 |
| 4. Vendor Dashboard UI | 1/1 | Complete    | 2026-04-08 |
| 5. KDS Security & WebSocket Foundation | 1/1 | Complete    | 2026-04-08 |
| 6. KDS Event Pipeline | 1/1 | Complete    | 2026-04-08 |
| 7. Kitchen Display UI | 1/1 | Complete    | 2026-04-09 |
| 8. Test Coverage Closure | 2/2 | Complete    | 2026-04-09 |
| 9. Repository Secrets + Alerting | 0/? | Not started | - |
| 10. Storefront Marketing Render + Missing Customer Routes | 0/? | Not started | - |
| 11. STOMP Broker Relay for Horizontal Scale | 0/? | Not started | - |

# Roadmap: J'Toye OaaS — Milestone 2 (Tier 3 Enhancements)

## Overview

This milestone transforms J'Toye OaaS from a transactional platform into a real-time operational tool. API versioning lands first because it changes every URL in the system -- doing it later means double rework. With versioned endpoints stable, vendor marketing features build on existing backend entities (low risk, high value). The Kitchen Display System is the highest-complexity work: a tenant-aware WebSocket security layer must be proven before any real-time UI ships. Test gap closure runs as the final phase, ensuring the milestone ends with a stronger test suite than it started.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: API Versioning — Backend** - Add /api/v1/ prefix to all Spring Boot endpoints with webhook exemptions and updated Swagger docs (completed 2026-04-07)
- [ ] **Phase 2: API Versioning — Edge & Frontend** - Update Go edge gateway routes and Next.js API client for /api/v1/ paths
- [ ] **Phase 3: Vendor Marketing Backend** - Promotion CRUD with scheduling, announcement entity extraction and CRUD
- [ ] **Phase 4: Vendor Dashboard UI** - Dashboard page for vendors to manage promotions and announcements
- [ ] **Phase 5: KDS Security & WebSocket Foundation** - Spring WebSocket/STOMP config with tenant-aware channel security
- [ ] **Phase 6: KDS Event Pipeline** - Route WebSocket events through RabbitMQ consumer for single event pipeline
- [ ] **Phase 7: Kitchen Display UI** - Real-time order card feed with status bumping, age indicators, and audio alerts
- [ ] **Phase 8: Test Coverage Closure** - Tests for PaymentController, PublicStorefrontController, security filters, and ReviewService

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
- [ ] 02-01-PLAN.md — Update Go edge routes and Next.js dashboard API calls for /api/v1/ paths

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
**Plans**: 1 plan

Plans:
- [ ] 03-01: TBD

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
- [ ] 04-01: TBD

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
- [ ] 05-01: TBD

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
- [ ] 06-01: TBD

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
- [ ] 07-01: TBD

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
**Plans**: 1 plan

Plans:
- [ ] 08-01: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8
Note: Phase 8 (Test Coverage) has no dependencies and can execute in parallel with other phases.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. API Versioning -- Backend | 1/1 | Complete    | 2026-04-07 |
| 2. API Versioning -- Edge & Frontend | 0/0 | Not started | - |
| 3. Vendor Marketing Backend | 0/0 | Not started | - |
| 4. Vendor Dashboard UI | 0/0 | Not started | - |
| 5. KDS Security & WebSocket Foundation | 0/0 | Not started | - |
| 6. KDS Event Pipeline | 0/0 | Not started | - |
| 7. Kitchen Display UI | 0/0 | Not started | - |
| 8. Test Coverage Closure | 0/0 | Not started | - |

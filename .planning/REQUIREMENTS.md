# Requirements: J'Toye OaaS — Milestone 2 (Tier 3 Enhancements)

**Defined:** 2026-04-07
**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility.

## v1 Requirements

Requirements for Milestone 2. Each maps to roadmap phases.

### API Versioning

- [ ] **APIV-01**: All REST endpoints prefixed with /api/v1/ via WebMvcConfigurer path matching
- [ ] **APIV-02**: Go edge gateway routes updated for /api/v1/ prefix
- [ ] **APIV-03**: Next.js frontend API client updated to use /api/v1/ paths
- [ ] **APIV-04**: Stripe webhook and WhatsApp webhook paths exempted from versioning
- [ ] **APIV-05**: OpenAPI/Swagger docs reflect /api/v1/ paths

### Vendor Marketing Dashboard

- [ ] **VMKT-01**: Promotion CRUD — vendor can create, edit, delete promotions with discount type/amount
- [ ] **VMKT-02**: Promotion scheduling — validFrom/validUntil with timezone-aware date handling
- [ ] **VMKT-03**: Announcement entity extracted from Shop.announcements TEXT[] with Flyway migration
- [ ] **VMKT-04**: Announcement CRUD — vendor can create, edit, delete announcements with scheduling
- [ ] **VMKT-05**: Vendor dashboard UI page with promotion and announcement management

### Kitchen Display System

- [ ] **KDS-01**: Spring WebSocket/STOMP configuration with in-memory broker
- [ ] **KDS-02**: TenantChannelInterceptor validates JWT and scopes subscriptions to tenant's shops
- [ ] **KDS-03**: TenantContext propagation from WebSocket session attributes to message handlers
- [ ] **KDS-04**: Real-time order card feed on kitchen display page
- [ ] **KDS-05**: Kitchen staff can bump order status (PREPARING → READY) via WebSocket
- [ ] **KDS-06**: Colour-coded order age indicators (green/yellow/red)
- [ ] **KDS-07**: Audio alert on new order arrival
- [ ] **KDS-08**: WebSocket events routed through RabbitMQ consumer (single event pipeline)

### Test Coverage

- [ ] **TEST-01**: PaymentController webhook endpoint tests
- [ ] **TEST-02**: PublicStorefrontController tests
- [ ] **TEST-03**: JwtTenantFilter and TenantFilter security filter tests
- [ ] **TEST-04**: ReviewService unit tests

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Kitchen Display Enhancements

- **KDS-09**: Station routing — assign orders to specific prep stations (grill, fryer, drinks)
- **KDS-10**: Course pacing — hold courses until prior course is served
- **KDS-11**: Order recall/history on kitchen display

### Marketing Enhancements

- **VMKT-06**: Promotion analytics — view counts, redemption tracking
- **VMKT-07**: Campaign templates — reusable promotion templates
- **VMKT-08**: A/B testing for promotions

### Infrastructure

- **INFRA-01**: RabbitMQ STOMP relay — config switch when scaling beyond single Core replica
- **INFRA-02**: Mobile native app — native iOS/Android client

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Station routing (grill, fryer, drinks) | Complexity, defer to v2 |
| Course pacing | Not needed for initial KDS |
| Order recall/history on KDS | Standard order history page suffices |
| Promotion analytics | Needs event tracking infrastructure, defer |
| Campaign templates | Nice-to-have, not table stakes |
| RabbitMQ STOMP relay | Config-only upgrade when scaling beyond 1 replica |
| Mobile native app | Web-first strategy |
| Real-time chat | High complexity, not core |
| Video product content | Storage/bandwidth cost |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| APIV-01 | Pending | Pending |
| APIV-02 | Pending | Pending |
| APIV-03 | Pending | Pending |
| APIV-04 | Pending | Pending |
| APIV-05 | Pending | Pending |
| VMKT-01 | Pending | Pending |
| VMKT-02 | Pending | Pending |
| VMKT-03 | Pending | Pending |
| VMKT-04 | Pending | Pending |
| VMKT-05 | Pending | Pending |
| KDS-01 | Pending | Pending |
| KDS-02 | Pending | Pending |
| KDS-03 | Pending | Pending |
| KDS-04 | Pending | Pending |
| KDS-05 | Pending | Pending |
| KDS-06 | Pending | Pending |
| KDS-07 | Pending | Pending |
| KDS-08 | Pending | Pending |
| TEST-01 | Pending | Pending |
| TEST-02 | Pending | Pending |
| TEST-03 | Pending | Pending |
| TEST-04 | Pending | Pending |

**Coverage:**
- v1 requirements: 22 total
- Mapped to phases: 0
- Unmapped: 22

---
*Requirements defined: 2026-04-07*
*Last updated: 2026-04-07 after initial definition*

# Feature Landscape

**Domain:** Food vendor SaaS -- vendor marketing dashboard, kitchen display system, API versioning
**Researched:** 2026-04-07

## Table Stakes

Features users expect. Missing = product feels incomplete.

### Vendor Dashboard: Announcements & Promotions

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Promotion CRUD (create, edit, delete, toggle active) | Every POS/ordering platform has this (Toast, Square, Clover). Vendors need to run discounts. | Low | Backend entity `ShopPromotion` already exists with label, discountPercent, category, validFrom/validUntil, active. Needs a UI and a dedicated controller (currently no REST endpoint for promotions). |
| Announcement CRUD (create, edit, delete) | Vendors communicate closures, new items, specials. Toast and competitors all offer this. | Low | `Shop.announcements` already stored as TEXT[]. Need to expose as first-class CRUD rather than editing the Shop entity directly. |
| Promotion scheduling (start/end dates) | Vendors plan ahead -- "Friday happy hour discount" needs to activate/deactivate automatically. | Low | `validFrom`/`validUntil` fields exist on ShopPromotion. Need scheduler or query-time filtering (query-time filtering already implemented in `findActiveByShopId`). |
| Promotion visibility on storefront | Customers must see active promotions when browsing a shop. No point creating promotions nobody sees. | Low | PublicStorefrontService likely already exposes shop config. Wire active promotions into the storefront shop page. |
| Dashboard list/table view with status indicators | Vendors need to see all promotions and announcements at a glance with active/scheduled/expired status. | Low | Standard data table component. |
| Form validation (date ranges, discount bounds 1-100%) | Bad data causes customer-facing errors. Every SaaS form validates. | Low | Jakarta Validation on backend, Zod/React Hook Form on frontend. |

### Kitchen Display System (KDS)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Real-time order feed (new orders appear without refresh) | Core KDS requirement. Every KDS from Toast to Oracle to Square shows orders in real-time. Paper tickets are the alternative. | Medium | SSE exists (`OrderSseService`) but is not tenant-scoped and broadcasts to all subscribers. WebSocket via STOMP is the target. |
| Order card with items, quantities, special instructions | Kitchen staff need to see exactly what to prepare. Universal KDS feature. | Low | OrderDetailDto already contains line items. Render as cards. |
| Order status bump (mark as PREPARING, READY) | Kitchen staff advance orders through the pipeline. "Bump bar" is the industry term. The state machine already supports these transitions. | Medium | Requires bidirectional communication: kitchen bumps status, server persists transition, broadcasts update to all KDS screens and customer-facing views. |
| Visual priority/age indicators (colour coding by wait time) | Orders waiting too long need attention. Every commercial KDS colour-codes by age (green < 5min, yellow 5-10min, red > 10min). | Low | Frontend-only calculation based on order timestamp. |
| Audio/visual alert for new orders | Kitchen is noisy. Staff need audible notification when new orders arrive. | Low | Browser `Notification` API or simple audio ping on WebSocket message. |
| Filter by shop (multi-shop tenant) | A tenant with multiple shops needs per-shop KDS views. | Low | Already have shopId on orders. Filter WebSocket subscription by shop. |

### API Versioning

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| URL prefix `/api/v1/` on all endpoints | Industry standard. Stripe, Twilio, DoorDash, Uber Eats all use URL-based versioning. Clients need stable contracts. | Medium | Currently 8+ controllers with paths like `/shops`, `/products`, `/orders`. All need prefix migration. Edge Go proxy routes need updating too. Frontend API client base URL needs updating. |
| Backward-compatible response format | Existing integrations (edge gateway, frontend) must not break during migration. | Low | Keep response DTOs identical -- this is a path-only change for v1. |
| API documentation reflecting versioned paths | Swagger/OpenAPI must show `/api/v1/` paths. | Low | Swagger picks up `@RequestMapping` automatically. |

## Differentiators

Features that set product apart. Not expected, but valued.

### Vendor Dashboard: Promotions & Announcements

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Promotion analytics (views, redemptions, revenue impact) | Most small-vendor platforms lack this. Knowing "10% off drove 40 extra orders" retains vendors on the platform. | High | Requires tracking promotion application at checkout, linking orders to promotions. Defer to future milestone. |
| Promotion templates (e.g., "Happy Hour", "Buy One Get One", "New Customer Discount") | Saves vendors time. Pre-built templates with sensible defaults reduce friction. | Low | Frontend-only: pre-fill form fields based on template selection. |
| Announcement rich text / images | Plain text announcements look basic. Rich content (images, formatting) feels professional. | Medium | Requires rich text editor (Tiptap/Slate) and image upload to S3/MinIO. |
| Scheduled announcement auto-publish/expire | Announcements that auto-appear and auto-disappear on dates -- like promotions but for informational content. | Low | Add validFrom/validUntil to announcements (currently just a string array on Shop). Would need to extract announcements into their own entity. |
| Bulk promotion creation (apply to multiple shops) | Multi-location vendors create one promotion across all shops at once. | Medium | Need batch endpoint and UI multi-select. |

### Kitchen Display System

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Station routing (send drink orders to bar, food to kitchen) | Splits workload across stations. Commercial KDS systems like Oracle and Fresh KDS offer this. | High | Requires product categorisation into stations, multiple KDS views per shop, routing logic. Defer. |
| Order grouping / course pacing | Fine dining needs starters before mains. Group items by course and pace kitchen output. | High | Requires course metadata on order items, timing logic. Defer. |
| Prep time estimates per item | Show estimated completion time. Kitchen and customers both benefit. | Medium | Requires historical data collection and calculation. Defer. |
| Recall/undo bump | Accidentally bumped? Bring it back. Fresh KDS and Toast both offer this. | Low | Reverse the state transition (PREPARING back to CONFIRMED). State machine may need to allow backward transitions or a dedicated "recall" event. |
| KDS performance metrics (avg ticket time, items/hour) | Operational insight for the vendor. | Medium | Aggregate WebSocket event timestamps. Can be computed from order state change audit trail (Envers). |

### API Versioning

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Deprecation headers on old paths | `Sunset` and `Deprecation` HTTP headers warn clients to migrate. Professional API practice. | Low | Add response filter/interceptor. |
| API changelog endpoint | `/api/changelog` showing version history. Developer-friendly. | Low | Static JSON or markdown served as endpoint. |

## Anti-Features

Features to deliberately NOT build.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Coupon codes / voucher system | Significant complexity (generation, validation, fraud prevention, single-use tracking). Not needed for vendor announcements/promotions MVP. | Stick with shop-level percentage discounts applied automatically during promotion period. Coupon codes are a separate milestone. |
| AI-powered promotion suggestions | Overhyped, needs significant order history data to be useful, adds ML dependency. | Let vendors create their own promotions. Add templates for guidance. |
| Bump bar hardware integration | Physical bump bars need USB HID or serial protocol support. Web-based KDS should use touchscreen or keyboard shortcuts. | Keyboard shortcuts (spacebar to bump, arrow keys to navigate) give the same speed without hardware dependency. |
| KDS printer fallback | Some KDS systems fall back to paper tickets on failure. Adds complexity for an edge case. | If WebSocket disconnects, show a "connection lost" banner and auto-reconnect. Orders persist in database regardless. |
| GraphQL API alongside REST | Adding a second API paradigm increases surface area, testing burden, and security audit scope. | REST with well-designed DTOs covers all use cases. Add GraphQL only if third-party integrators demand it. |
| Header-based or query-parameter API versioning | Multiple versioning strategies create confusion. URL prefix is the clearest and most widely adopted pattern (Stripe, Twilio, DoorDash). | Use `/api/v1/` URL prefix exclusively. |
| Simultaneous support for multiple API versions | v1 and v2 running simultaneously doubles maintenance. Not needed for a platform with controlled clients (own frontend + edge). | Migrate all clients to v1 in this milestone. When v2 is needed, deprecate v1 with a sunset period. |

## Feature Dependencies

```
Promotion CRUD UI ──→ ShopPromotion REST controller (backend)
                  ──→ Storefront promotion display (frontend)

Announcement CRUD UI ──→ Announcement entity extraction (if adding scheduling)
                     ──→ Storefront announcement display (already partially wired)

KDS Order Feed ──→ WebSocket infrastructure (Spring WebSocket + STOMP config)
               ──→ Tenant-scoped WebSocket channels (/topic/orders/{shopId})
               ──→ Order state machine (already exists)

KDS Status Bump ──→ WebSocket infrastructure
                ──→ OrderController status update endpoint (already exists)
                ──→ Broadcast state change via WebSocket (replaces/augments SSE)

API Versioning ──→ All controller @RequestMapping paths updated
               ──→ Edge Go proxy routes updated
               ──→ Frontend apiClient base URL updated
               ──→ Swagger/OpenAPI path reflection (automatic)

WebSocket infra ──→ Spring WebSocket + STOMP dependency added
                ──→ JWT authentication on WebSocket handshake
                ──→ TenantContext extraction for WebSocket sessions
```

## MVP Recommendation

**Priority order based on dependencies and value:**

1. **API Versioning** -- do first because it changes every endpoint path. Doing it later means more rework. Low feature risk, medium migration effort.

2. **Promotion/Announcement REST endpoints + Dashboard UI** -- backend entities exist, need controller + frontend. Straightforward CRUD. Gives vendors immediate marketing capability.

3. **WebSocket infrastructure** -- foundational for KDS. Set up Spring WebSocket + STOMP, JWT auth on handshake, tenant-scoped topics. This is the riskiest piece technically.

4. **KDS Order Feed + Bump** -- depends on WebSocket infra. Once WebSocket works, the KDS is rendering order cards and sending bump commands.

**Defer to future milestones:**
- Station routing: needs product categorisation metadata that does not exist yet
- Promotion analytics: needs checkout-to-promotion linking
- Course pacing: fine dining feature, not core for food vendor SaaS
- Rich text announcements: nice-to-have, plain text works for MVP
- Bulk multi-shop promotions: can be added incrementally

## Sources

- [Kitchen Display System Guide 2026 - Techryde](https://www.techryde.com/blog/kitchen-display-system-guide-2026/)
- [What is KDS? - Quantic](https://getquantic.com/what-is-a-kds/)
- [KDS Why Restaurants Need It 2026 - Novatab](https://www.novatab.com/blog/kitchen-display-system-kds-why-every-modern-restaurant-needs-one)
- [Kitchen Display Systems - Toast POS](https://pos.toasttab.com/hardware/kitchen-display-system)
- [Kitchen Display Systems - Oracle](https://www.oracle.com/food-beverage/restaurant-pos-systems/kds-kitchen-display-systems/)
- [Fresh KDS Features](https://www.fresh.technology/kitchen-display-system)
- [Bump Bar Support - Fresh KDS](https://www.fresh.technology/kds-features/bump-bar-support)
- [Best Commission-Free SaaS Ordering Platforms 2026](https://acuitysoftwareservices.com/blog/best-commission-free-saas-online-ordering-platforms-in-2026/)
- [Must-Have Features for Food Ordering 2025 - Integrass](https://integrass.com/media/must-have-features-for-online-food-ordering-system-in-2025/)
- [API Versioning Best Practices - Gravitee](https://www.gravitee.io/blog/api-versioning-best-practices)
- [Top 5 API Versioning Strategies 2025 - DreamFactory](https://blog.dreamfactory.com/top-5-api-versioning-strategies-2025-dreamfactory)
- [Web API Design Best Practices - Microsoft Azure](https://learn.microsoft.com/en-us/azure/architecture/best-practices/api-design)
- [Toast vs Square POS 2026](https://www.posusa.com/toast-vs-square/)

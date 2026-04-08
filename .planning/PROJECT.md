# J'Toye OaaS — Milestone 2: Tier 3 Enhancements

## What This Is

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. This milestone adds vendor marketing tools, real-time kitchen displays, API versioning, and closes remaining test gaps from the previous milestone.

## Core Value

Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility.

## Requirements

### Validated

- ✓ Multi-tenant shop management with PostgreSQL RLS — existing
- ✓ Product CRUD with image analysis (Ollama/Claude) — existing
- ✓ Order state machine (DRAFT → CONFIRMED → PREPARING → READY → DELIVERED) — existing
- ✓ Stripe payments with COD fallback — existing
- ✓ Keycloak OAuth2/OIDC authentication — existing
- ✓ Go edge gateway with rate limiting and circuit breakers — existing
- ✓ Next.js storefront with NextAuth — existing
- ✓ Full-text search, delivery fees, reviews, allergens, VAT, opening hours — existing
- ✓ GDPR export/erasure endpoints — existing
- ✓ Resilience4j circuit breakers, RabbitMQ DLQ, business metrics, cleanup jobs — existing
- ✓ CORS env vars, K8s backup CronJob — existing

### Active

- [ ] Vendor dashboard UI for announcements, promotions/discounts, and scheduling
- [ ] Real-time WebSocket kitchen display (live order feed + kitchen status updates)
- [x] API versioning — /api/v1/ prefix across backend, Go edge, and frontend (Phase 1+2)
- [ ] Test coverage for PaymentController webhook endpoint
- [ ] Test coverage for PublicStorefrontController
- [ ] Test coverage for security filters (JwtTenantFilter, TenantFilter)
- [ ] Test coverage for ReviewService

### Out of Scope

- Mobile native app — web-first, defer to future milestone
- Real-time chat between vendor and customer — high complexity, not core
- Video content for products — storage/bandwidth cost, defer
- Email notification provider setup (SMTP) — infrastructure decision, separate milestone
- WhatsApp order creation wiring — parser exists but needs shop assignment strategy, separate effort

## Context

- **Existing codebase:** 3-tier architecture (Next.js frontend, Go edge, Spring Boot core) with 28 Flyway migrations, 310 passing tests
- **Previous milestones:** Batches 3-5 (features), Tier 2 (reliability) all merged to main
- **Announcement/promotion APIs:** Backend API exists for announcements but has no UI — vendor dashboard is the missing piece
- **WebSocket:** No WebSocket infrastructure exists yet — needs Spring WebSocket + STOMP on backend, Socket.io or native WS on frontend
- **Known concerns:** Broad exception handling in several services, hardcoded rate limiter values in edge, email notifications not wired

## Constraints

- **Tech stack**: Must use existing stack — Spring Boot 3.4.2, Next.js 16, Go 1.22, PostgreSQL 15
- **Java version**: JDK 21 (JDK 25 incompatible with Gradle 8.10)
- **Multi-tenancy**: All new features must respect RLS and TenantContext
- **Testing**: All new code requires tests — project standard is 310+ tests passing
- **Docker**: Always rebuild ALL containers after code changes before E2E testing

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| WebSocket via Spring WebSocket + STOMP | Native Spring integration, tenant-aware channels | — Pending |
| API versioning as URL prefix /api/v1/ | Simplest approach, clear routing, no header negotiation | — Pending |
| Include test gaps in this milestone | Reduces tech debt alongside feature work | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-08 after Phase 1 completion*

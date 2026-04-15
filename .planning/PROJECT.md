# J'Toye OaaS — Milestone 3: Post-Audit Hardening + Storefront Completion

## What This Is

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. This milestone closes the three highest-priority gaps identified by the 2026-04-14 state-of-codebase audit: repository secret exposure + missing alerting, the half-dead vendor marketing path (backend ships promotions that the storefront never renders, plus two missing customer routes), and the single-replica STOMP broker that silently breaks kitchen broadcasts the moment a second `core-java` replica starts.

## Core Value

Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.

## Current Milestone: v2.1 Post-Audit Hardening + Storefront Completion

**Goal:** Ship Work Orders A, B, C from `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md §11` — close the 3 immediate production blockers without adding new major surface area.

**Target features:**
- Repository secret hygiene + Prometheus Alertmanager deployment with Slack routing
- Storefront rendering of vendor promotions + announcements, plus the missing `/shop/[slug]/cart` and `/shop/orders` customer routes
- `StompBrokerRelay` over RabbitMQ with a config flag (`stomp.broker.mode`) so `core-java` can scale horizontally without losing kitchen broadcasts

**Key context:**
- All 34 audit findings are on `main` via PRs #30–#36; this milestone starts from a verified-green baseline (335 java / 69 frontend / 28 go tests, 0 npm vulns)
- Source of truth is `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md` — every requirement below has a file:line in that document
- Tier-2 backlog (Work Orders D–O) is explicitly deferred to milestone 4+

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
- ✓ **[M2]** API versioning (/api/v1/) across backend, Go edge, and frontend — milestone 2, phases 1–2
- ✓ **[M2]** Vendor marketing backend + dashboard UI (promotions + announcements CRUD) — milestone 2, phases 3–4
- ✓ **[M2]** Real-time Kitchen Display System — STOMP WebSocket, tenant-scoped channels, audio alerts, age colouring — milestone 2, phases 5–7
- ✓ **[M2]** Test coverage closure — PaymentController webhook, PublicStorefrontController, security filters, GDPR — milestone 2, phase 8
- ✓ **[Post-audit]** edge-go security hardening, java data integrity, frontend HttpOnly cookies, optimistic locking V32, payment transactional outbox V31, Flyway V32 doc sync — PRs #30–#36

### Active

**Work Order A — Repository secrets + alerting (SECR):**
- [ ] SECR-01: `.env` removed from git tracking with gitignore entry
- [ ] SECR-02: All 5 committed credentials rotated (Postgres, Keycloak admin, Redis, RabbitMQ, Keycloak client secret)
- [ ] SECR-03: Rotated credentials pushed to GitHub Secrets + k8s secrets for staging/prod
- [ ] SECR-04: Prometheus Alertmanager deployed in `infra/monitoring/docker-compose.monitoring.yml`
- [ ] SECR-05: Alertmanager Slack webhook routing bound to the existing 13 Prometheus alert rules
- [ ] SECR-06: End-to-end alert roundtrip verified (force `ServiceDown`, confirm Slack message)

**Work Order B — Storefront marketing + missing customer routes (STFR):**
- [ ] STFR-01: `GET /public/shops/{slug}/promotions` endpoint returning only active+unexpired tenant-scoped promotions
- [ ] STFR-02: `GET /public/shops/{slug}/announcements` endpoint with the same active-only filter
- [ ] STFR-03: `frontend/app/shop/[slug]/page.tsx` renders the announcement banner above the menu and discount badges on product cards
- [ ] STFR-04: `frontend/app/shop/[slug]/cart/page.tsx` standalone cart route with empty-cart + missing-shop handling
- [ ] STFR-05: `frontend/app/shop/orders/page.tsx` authenticated customer order-history route with filter + pagination
- [ ] STFR-06: Playwright e2e covering browse → add → cart page → Stripe checkout → confirmation on the full customer flow

**Work Order C — STOMP broker relay for horizontal scale (STMP):**
- [ ] STMP-01: `WebSocketConfig.java` swaps `SimpleBroker` for `StompBrokerRelay` behind a `stomp.broker.mode` property (`in-memory` | `relay`)
- [ ] STMP-02: RabbitMQ STOMP plugin enabled in docker-compose + k8s; port 61613 exposed; relay credentials stored as k8s secrets
- [ ] STMP-03: Two-replica `core-java` broadcast verified — client on replica A receives order state change published on replica B
- [ ] STMP-04: Playwright e2e asserts real-time kitchen update within 2s in relay mode
- [ ] STMP-05: Prometheus alert on RabbitMQ STOMP exchange lag > 5s + Grafana tile for STOMP connection count

### Out of Scope

- Tenant self-serve onboarding flow (Work Order D) — deferred to milestone 4
- Vendor order detail view + refund flow (Work Order E) — deferred
- Vendor finance + settings pages (Work Order F) — deferred
- Log aggregation + Grafana dashboards + runbooks (Work Order G) — deferred
- K8s sealed-secrets / external-secrets-operator (Work Order H) — deferred (SECR uses GitHub Secrets + k8s Secret resources as an interim)
- Postgres PITR via WAL archiving (Work Order I) — deferred
- Review module controller + moderation (Work Order J) — deferred
- Edge OpenTelemetry + distributed rate limiter (Work Order K) — deferred
- Full-text product search perf verification (Work Order L) — deferred
- Bulk product import endpoint + UI (Work Order M) — deferred
- Billing subscription management (Work Order N) — deferred
- WhatsApp idempotency key migration (Work Order O) — deferred
- Mobile native app — web-first, no change from milestone 2
- Real-time vendor-customer chat — high complexity, not core

## Context

- **Existing codebase:** 3-tier architecture (Next.js 16 frontend, Go 1.22 edge, Spring Boot 3.4.2 core) with Flyway V1–V32, 335 Java + 69 frontend + 28 Go tests
- **Previous milestones:** Milestone 1 (batches 3–5 + Tier 2) shipped reliability + core features; Milestone 2 (v2.0 Tier 3) shipped API versioning, vendor marketing, KDS, test coverage closure
- **Source-of-truth doc:** `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md` (676 lines, 12 sections, every finding file:line-backed)
- **Known concerns:**
  - `.env` committed at repo root (SECR addresses)
  - Alertmanager not deployed — 13 Prometheus rules fire into the void (SECR addresses)
  - Storefront never calls `/promotions` or `/announcements` — vendor marketing is half-dead (STFR addresses)
  - `WebSocketConfig.java` uses `SimpleBroker` — kitchen broadcasts break silently on a second replica (STMP addresses)

## Constraints

- **Tech stack**: Must use existing stack — Spring Boot 3.4.2, Next.js 16, Go 1.22, PostgreSQL 15
- **Java version**: JDK 21 (JDK 25 incompatible with Gradle 8.10); always set `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Multi-tenancy**: All new features must respect RLS and TenantContext; new public endpoints must be tenant-scoped by slug
- **Testing**: Every requirement ships with tests; baseline is 335 Java + 69 frontend + 28 Go and the milestone must not regress that count
- **Docker**: Always rebuild ALL containers after code changes before E2E testing (stale images cause subtle failures)
- **Credentials**: SECR work must not leave any secret in git history going forward; prefer rotation over redaction

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Scope milestone 3 to Work Orders A+B+C only | A is 2 days + standalone safety net, B is 1 week + closes marketing loop, C is 1 week + unblocks horizontal scale. Bundling A in hides its urgency; bundling D (tenant onboarding) in blows the milestone past 5 weeks. | — Pending |
| Version as v2.1 (not v3.0) | Hardening + completion, no net-new major surface. v3.0 is reserved for tenant onboarding (Work Order D) which genuinely signals SaaS v1 self-serve. | — Pending |
| Skip domain research for this milestone | State-of-codebase doc is already research-grade (5 specialist agents, 676 lines, file:line evidence). Re-researching would duplicate. Framework-specific pitfalls (StompBrokerRelay, Alertmanager) will be covered in phase-level research. | — Pending |
| Continue phase numbering from 9 | Preserves M2 phase history (1–8) and matches `.planning/phases/` directory convention. Reset would require archiving with no archive path available. | — Pending |
| SECR credential rotation via rotation + GitHub/k8s Secrets, not sealed-secrets | Work Order H (sealed-secrets or external-secrets-operator) is the long-term answer. This milestone uses plain GitHub + k8s Secrets to close the hole within 2 days. | — Pending |
| STOMP broker behind config flag | `stomp.broker.mode` lets dev keep in-memory broker (zero RabbitMQ dependency for local) while staging/prod switch to relay. Prevents a hard cutover from regressing local dev loops. | — Pending |

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
*Last updated: 2026-04-14 at start of milestone 3 (v2.1) from state-of-codebase audit input*

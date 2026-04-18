# J'Toye OaaS — Post-v2.1 Shipped

## What This Is

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. As of v2.1 (shipped 2026-04-16), the platform has secret-hygiene CI enforcement, deployed Alertmanager routing 15 Prometheus rules to email, a customer-facing storefront that renders vendor promotions/announcements with working cart + order-history routes, and a horizontally-scalable kitchen display system backed by RabbitMQ STOMP relay.

## Core Value

Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.

## Current State

**Last shipped:** v2.1 Post-Audit Hardening + Storefront Completion (2026-04-16, archived 2026-04-18)
**See:** `MILESTONES.md` and `milestones/v2.1-*` for full archive.

**Current focus:** v2.2 not yet scoped. Known candidates from v2.1 deferred list:
- 14 P2 items from deep-audit HANDOFF.md (stock race, K8s NetworkPolicies, K8s Sealed Secrets, CSP headers, Grafana JVM/DB/business dashboards, Alertmanager inhibition rules, etc.)
- SECR-08 — Keycloak realm-export hardcoded dev secrets
- `/public/orders?email=` enumeration risk
- Alert runbook completion (9 stubs in `docs/runbooks/alerts.md`)
- Phase 11 VALIDATION.md closure (nyquist_compliant: false)

## Next Milestone Goals

Run `/gsd-new-milestone` to scope. Likely frame: "v2.2 Production Hardening" — close the P2 items from the deep-audit HANDOFF that didn't fit into v2.1's bounded scope.

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
- ✓ **[v2.1 SECR]** Alertmanager deployed with email receiver routing 15 Prometheus alert rules; gitleaks CI + allowlist; `.env` verified untracked (audit-doc premise was false) — phase 9, PR #37
- ✓ **[v2.1 STFR]** Storefront renders vendor promotions + announcements; `/shop/[slug]/cart` + `/shop/orders` routes shipped; full browse→cart→Stripe checkout Playwright e2e — phase 10, PR #38
- ✓ **[v2.1 STMP]** `StompBrokerRelay` behind `stomp.broker.mode` flag; RabbitMQ STOMP plugin; two-replica smoke test 6/6; StompBrokerLag alert + Grafana dashboard — phase 11, PR #39
- ✓ **[v2.1 Deep audit P1]** 4 new Prometheus alerts, redis-exporter, error boundaries, STOMP tenant validation on ALL /topic/, JWT in CONNECT headers, Go edge tests (21→57) — PR #40

### Active

No active requirements — v2.1 shipped. Run `/gsd-new-milestone` to scope v2.2.

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

- **Existing codebase:** 3-tier architecture (Next.js 16 frontend, Go 1.22 edge, Spring Boot 3.4.2 core) with Flyway V1–V33, 474+ tests passing (341 Java + 76 Jest + 57 Go)
- **Previous milestones:** Milestone 1 (batches 3–5 + Tier 2) shipped reliability + core features; Milestone 2 (v2.0 Tier 3) shipped API versioning, vendor marketing, KDS, test coverage closure; Milestone 3 (v2.1) closed the 3 highest-priority audit Work Orders + a deep-audit P1 pass
- **v2.1 outcome:** 18/18 requirements complete, 3/3 phases verified after audit remediation. Alertmanager routes emails (not Slack — rescoped during phase 9). Secret-hygiene CI prevents future drift. Storefront is no longer half-dead — customers see promotions and have cart/orders routes. STOMP broker scales horizontally behind a config flag.
- **Known concerns for v2.2 candidates:**
  - 14 P2 deep-audit items in HANDOFF.md (stock race at confirmation vs creation, DB aggregation for getSummary, K8s NetworkPolicies, K8s Sealed Secrets, CSP headers, CSP-compatible frontend error logging, Grafana JVM/DB/business dashboards, Alertmanager inhibition rules, runbook completion, blocking reactive calls in state machine)
  - SECR-08: Keycloak realm-export hardcoded dev secrets (allowlisted for now)
  - `/public/orders?email=` enumeration risk
  - Phase 11 VALIDATION.md draft (nyquist_compliant: false)

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
| Scope milestone 3 to Work Orders A+B+C only | A is 2 days + standalone safety net, B is 1 week + closes marketing loop, C is 1 week + unblocks horizontal scale. Bundling A in hides its urgency; bundling D (tenant onboarding) in blows the milestone past 5 weeks. | ✓ Good (v2.1 shipped as planned) |
| Version as v2.1 (not v3.0) | Hardening + completion, no net-new major surface. v3.0 is reserved for tenant onboarding (Work Order D) which genuinely signals SaaS v1 self-serve. | ✓ Good (v2.1 shipped as planned) |
| Skip domain research for this milestone | State-of-codebase doc is already research-grade (5 specialist agents, 676 lines, file:line evidence). Re-researching would duplicate. Framework-specific pitfalls (StompBrokerRelay, Alertmanager) will be covered in phase-level research. | ✓ Good (v2.1 shipped as planned) |
| Continue phase numbering from 9 | Preserves M2 phase history (1–8) and matches `.planning/phases/` directory convention. Reset would require archiving with no archive path available. | ✓ Good (v2.1 shipped as planned) |
| SECR credential rotation via rotation + GitHub/k8s Secrets, not sealed-secrets | Work Order H (sealed-secrets or external-secrets-operator) is the long-term answer. This milestone uses plain GitHub + k8s Secrets to close the hole within 2 days. | ✓ Good (v2.1 shipped as planned) |
| STOMP broker behind config flag | `stomp.broker.mode` lets dev keep in-memory broker (zero RabbitMQ dependency for local) while staging/prod switch to relay. Prevents a hard cutover from regressing local dev loops. | ✓ Good (v2.1 shipped as planned) |
| Phase 9 rescope mid-flight (Slack→email, rotation→verification) | User challenge 2026-04-15: no committed Slack dependency; `git ls-files --error-unmatch .env` confirmed original audit-doc claim was false. Converted SECR-01..03 from rotation to verification; added SECR-07 for gitleaks CI so future drift is caught at PR time. | ✓ Good (right-sized scope without losing safety) |
| ALL /topic/ STOMP subscriptions require tenant segment | Future-proofs against new broadcast channels bypassing isolation. Originally only /topic/kitchen/ was guarded. | ✓ Good (P1 deep-audit, PR #40) |
| JWT in STOMP CONNECT headers with session fallback | Backwards-compatible during rolling deploys — old clients still connect via session JWT until updated. | ✓ Good (P1 deep-audit, PR #40) |

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
*Last updated: 2026-04-18 after v2.1 milestone close*

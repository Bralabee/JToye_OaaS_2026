# Requirements: J'Toye OaaS — Milestone 3 (Post-Audit Hardening + Storefront Completion)

**Defined:** 2026-04-14
**Milestone:** v2.1
**Source:** `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md §9, §11 Work Orders A+B+C`
**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.

## v1 Requirements

Requirements for Milestone 3. Three work orders = three requirement categories. Each maps to roadmap phases (continuing numbering from M2 phase 8 → M3 starts at phase 9).

### Work Order A — Repository secrets + alerting (SECR)

Closes the credential-exposure hole and wires the 13 Prometheus alert rules into an Alertmanager + Slack route. Highest urgency, shortest effort (~2 days).

- [ ] **SECR-01**: `.env` removed from git tracking (`git rm --cached .env`) with a matching `.gitignore` entry, verified by `git check-ignore`
- [ ] **SECR-02**: All 5 committed credentials rotated in running services — Postgres `jtoye`/`keycloak` roles via `ALTER USER`, Keycloak admin via Admin CLI, Redis password in `redis.conf`, RabbitMQ via `rabbitmqctl change_password`, Keycloak client secret regenerated
- [ ] **SECR-03**: Rotated values distributed to GitHub Actions Secrets (for CI/CD) and `k8s/` Secret manifests (for staging/prod); no plaintext committed
- [ ] **SECR-04**: `prom/alertmanager:v0.27` container deployed in `infra/monitoring/docker-compose.monitoring.yml`; exposed on a stable port; Prometheus `alerting.alertmanagers` block bound to it
- [ ] **SECR-05**: `alertmanager.yml` routes the 13 existing Prometheus alert rules to a Slack webhook (single channel); labels include `severity` and `service`
- [ ] **SECR-06**: End-to-end alert roundtrip verified — force `ServiceDown` (e.g. kill `core-java`) and confirm a Slack message arrives within 60 s; runbook entry added describing the test

### Work Order B — Storefront marketing + missing customer routes (STFR)

The tier-3 vendor marketing flagship works end-to-end on the vendor side but is never rendered on the storefront — customers literally cannot see the promotions vendors are paying for. Plus two missing customer routes (`/shop/[slug]/cart` and `/shop/orders`) cause direct-link 404s and loyalty friction. ~1 week.

- [ ] **STFR-01**: `PublicStorefrontController.getPromotions(slug)` — `GET /public/shops/{slug}/promotions` returns only active (validFrom ≤ now ≤ validUntil) promotions for the tenant owning `slug`; DTO + mapper + RLS-compatible query; controller-level integration test
- [ ] **STFR-02**: `PublicStorefrontController.getAnnouncements(slug)` — `GET /public/shops/{slug}/announcements` returns only active+unexpired announcements with the same tenant scoping; controller-level integration test
- [ ] **STFR-03**: `frontend/app/shop/[slug]/page.tsx` fetches promotions + announcements in parallel with shop, renders the announcement banner above the menu, and overlays discount badges on the product cards that match an active promotion; active-promotion-per-product lookup memoised
- [ ] **STFR-04**: `frontend/app/shop/[slug]/cart/page.tsx` standalone cart view — reads the same localStorage key as the modal cart, renders items, supports quantity edit, links to checkout, and handles empty-cart + missing-shop gracefully; Jest test for empty and populated states
- [ ] **STFR-05**: `frontend/app/shop/orders/page.tsx` authenticated customer order-history route — `RequireCustomerAuth` guard, lists all orders for the logged-in customer across all shops with status filter + date filter + pagination; backend `GET /public/customers/{id}/orders` endpoint (or equivalent) if not already exposed
- [ ] **STFR-06**: Playwright e2e validates the full customer flow — shop discovery → shop detail → add to cart → cart page → checkout → Stripe test mode payment → confirmation screen; runs against the full docker-compose stack

### Work Order C — STOMP broker relay for horizontal scale (STMP)

A second `core-java` replica will break kitchen broadcasts silently today because `WebSocketConfig.java` uses `SimpleBroker`. Swap to `StompBrokerRelay` behind a config flag so dev keeps the zero-dependency path while staging/prod gain multi-replica safety. ~1 week.

- [ ] **STMP-01**: `WebSocketConfig.java` reads a `stomp.broker.mode` property (`in-memory` | `relay`); in `in-memory` mode it retains today's `enableSimpleBroker`, in `relay` mode it calls `enableStompBrokerRelay("/topic", "/queue").setRelayHost(...).setRelayPort(61613).setClientLogin(...).setSystemLogin(...)`
- [ ] **STMP-02**: RabbitMQ STOMP plugin enabled in `docker-compose.full-stack.yml` and `k8s/` manifests via `rabbitmq-plugins enable rabbitmq_stomp`; port `61613` exposed; relay credentials stored as k8s Secret entries and referenced via env vars
- [ ] **STMP-03**: Two-replica broadcast verified in relay mode — `docker compose up --scale core-java=2`, kitchen client on replica A receives an order state change published via API to replica B within 2 seconds; test result captured in a smoke-test log
- [ ] **STMP-04**: Playwright e2e in relay mode — open `/dashboard/kitchen`, POST an order via the REST API to a different replica (or via the edge gateway load-balancing across replicas), assert the WebSocket message arrives within 2 seconds; green in CI
- [ ] **STMP-05**: Prometheus alert rule on RabbitMQ STOMP exchange lag > 5 s + Grafana dashboard tile for STOMP connection count; wired through the Alertmanager deployed in SECR-04

## v2 Requirements

Deferred to milestone 4+. Tracked but not in the current roadmap. These map to Work Orders D–O in the state-of-codebase doc.

### Tenant SaaS v1 (signals v3.0)

- **TNT-01**: Production `TenantService.create(tenant, adminUser)` that provisions Keycloak user + role + shop shell (Work Order D)
- **TNT-02**: `/api/v1/tenants` POST endpoint with Keycloak admin API integration (Work Order D)
- **TNT-03**: `frontend/app/(auth)/register/page.tsx` self-serve signup page (Work Order D)
- **TNT-04**: Stripe Customer creation hook + welcome email template (Work Order D)

### Vendor operational completion

- **VOPS-01**: `/dashboard/orders/[id]` detail view with refund flow (Work Order E)
- **VOPS-02**: `/dashboard/finance` page wired to transaction ledger + VAT (Work Order F)
- **VOPS-03**: `/dashboard/settings` page wired to shop + notification preferences (Work Order F)
- **VOPS-04**: Bulk product import endpoint + UI integration (Work Order M)

### Platform hardening

- **PLAT-01**: Log aggregation (Loki or ELK) + Grafana dashboards + runbooks (Work Order G)
- **PLAT-02**: K8s sealed-secrets or external-secrets-operator (Work Order H) — replaces SECR's interim GitHub+k8s-Secret approach
- **PLAT-03**: Postgres PITR via WAL archiving to S3 (Work Order I)
- **PLAT-04**: Edge OpenTelemetry + distributed rate limiter (Redis) (Work Order K)
- **PLAT-05**: Full-text product search perf verification + caching (Work Order L)

### Other

- **REV-01**: Review module controller + storefront display + moderation (Work Order J)
- **BILL-01**: Vendor billing subscription mgmt (Work Order N)
- **WHAT-01**: Migrate WhatsApp handler to order idempotency key (Work Order O)

## Out of Scope

Explicitly excluded from milestone 3. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Tenant self-serve onboarding (D) | Different milestone size/risk; saves for v3.0 major |
| Vendor order detail + refund (E) | Operational polish, not an audit blocker |
| Finance + settings pages (F) | Placeholder shells today, out-of-scope until billing architecture is decided |
| Log aggregation / Loki (G) | Alertmanager addresses the immediate blind spot; full log pipeline is milestone 4 |
| Sealed-secrets / external-secrets-operator (H) | SECR uses GitHub Secrets + k8s Secrets as interim; full GitOps secret management is milestone 4+ |
| Postgres PITR (I) | Backup CronJob already exists; PITR is a separate effort |
| Review controller + moderation (J) | Service exists, no audit-blocking urgency |
| Edge OpenTelemetry + distributed rate limiter (K) | Edge hardening shipped in PR #30; OTel is a separate observability milestone |
| Full-text search perf (L) | V25 tsvector indexes exist; perf not proven blocking |
| Bulk product import UI (M) | Endpoint unclear, low priority |
| Billing subscription mgmt (N) | Depends on D (tenant onboarding) |
| WhatsApp idempotency key (O) | Handler gated by `WHATSAPP_ENABLED`; low priority until SMS/WhatsApp rollout |
| Station routing / course pacing / KDS recall (from M2 v2) | Still deferred from milestone 2 v2 list |
| Mobile native app | Web-first strategy unchanged |
| Real-time vendor-customer chat | High complexity, not core |

## Traceability

Which phases cover which requirements. **Filled by roadmap creation** — currently empty because the roadmap has not yet been written for milestone 3.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SECR-01 | — | Pending |
| SECR-02 | — | Pending |
| SECR-03 | — | Pending |
| SECR-04 | — | Pending |
| SECR-05 | — | Pending |
| SECR-06 | — | Pending |
| STFR-01 | — | Pending |
| STFR-02 | — | Pending |
| STFR-03 | — | Pending |
| STFR-04 | — | Pending |
| STFR-05 | — | Pending |
| STFR-06 | — | Pending |
| STMP-01 | — | Pending |
| STMP-02 | — | Pending |
| STMP-03 | — | Pending |
| STMP-04 | — | Pending |
| STMP-05 | — | Pending |

**Coverage:**
- v1 requirements: 17 total (SECR ×6 + STFR ×6 + STMP ×5)
- Mapped to phases: 0 (pending roadmap)
- Unmapped: 17 (pending roadmap)

---
*Requirements defined: 2026-04-14*
*Last updated: 2026-04-14 on milestone 3 initialization*

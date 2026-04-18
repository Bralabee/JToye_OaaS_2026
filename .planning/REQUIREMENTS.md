# Requirements: J'Toye OaaS — Milestone 3 (Post-Audit Hardening + Storefront Completion)

**Defined:** 2026-04-14
**Milestone:** v2.1
**Source:** `.planning/state-of-codebase/STATE-OF-CODEBASE-2026-04-14.md §9, §11 Work Orders A+B+C`
**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.

## v1 Requirements

Requirements for Milestone 3. Three work orders = three requirement categories. Each maps to roadmap phases (continuing numbering from M2 phase 8 → M3 starts at phase 9).

### Work Order A — Repository secrets + alerting (SECR)

**RESCOPED 2026-04-15 during phase 9 discuss + execution.** Two material corrections:

1. **The `.env`-committed claim in the audit doc is false.** Verified via `git log --all --full-history -- .env` (empty), `git ls-files --error-unmatch .env` ("did not match any file"), and `git check-ignore -v .env` (matched by `.gitignore:64`). `.env.example` and `k8s/base/secrets-template.yaml` use `CHANGE_ME` / `REPLACE_WITH_*` placeholders only. SECR-01..03 are converted from rotation-style to verification + enforcement-style, and a new SECR-07 adds `gitleaks` CI to prevent any future drift from making the original finding real.
2. **Alert destination rescoped from Slack to email via Mailhog.** The project has no committed Slack dependency beyond one CI notification workflow (`.github/workflows/ci-cd.yaml`); `docs/reports/PRODUCTION_READINESS_REPORT.md` lists `"email/Slack"` as interchangeable. Mailhog is already in `docker-compose.full-stack.yml`, so email needs no external accounts. Prod can override `ALERTMANAGER_SMTP_*` env vars to point at a real SMTP relay.
3. **Alert rule count is 10, not 13** (verified by `grep -c "^\s*- alert:" infra/monitoring/prometheus/alerts.yml`). Audit doc figure was incorrect.

Effort: ~1 day (down from 2 days because the credential rotation work is dropped).

- [x] **SECR-01**: `.env` verified absent from git tracking via `git ls-files --error-unmatch .env` ("did not match") and `git check-ignore -v .env` (matched by `.gitignore:64`). Re-scoped from "remove from tracking" — already absent. Enforcement going forward via SECR-07.
- [x] **SECR-02**: Credential rotation **dropped** — no credentials were ever committed. Enforcement via SECR-07 ensures future drift is caught at PR time.
- [x] **SECR-03**: GitHub / k8s Secret distribution **dropped** — nothing to distribute. Alertmanager SMTP env vars (phase 9 additions) ship via the standard `.env.example` → `.env` pattern already used across the project.
- [x] **SECR-04**: `prom/alertmanager:v0.27.0` container deployed in `infra/monitoring/docker-compose.monitoring.yml`, joined to `jtoye-network` so it can reach Mailhog at `mailhog:1025`. `prometheus.yml` `alerting.alertmanagers` block bound to `alertmanager:9093`. Verified via containerised `amtool check-config` + `promtool check config` — both PASS. Phase 9 plan 09-01, commit `295ea56` + `47ea7b4`.
- [x] **SECR-05**: `alertmanager.yml` (rendered from `.tmpl` at container start via `entrypoint.sh` sed wrapper) routes the 10 existing Prometheus alert rules to an `email-default` receiver (Mailhog in dev, real SMTP in prod via `ALERTMANAGER_SMTP_*` env overrides). All 10 rules now carry `severity` + `service` literal-string labels driving the `group_by: [alertname, service]` tree and the email subject template. Phase 9 plan 09-01, commit `295ea56` + `47ea7b4`.
- [x] **SECR-06**: End-to-end alert roundtrip **VERIFIED 2026-04-15**. Ran `./infra/monitoring/scripts/smoke-test-alertmanager.sh` against a live `docker-compose.full-stack.yml` + `docker-compose.monitoring.yml` after stopping the unrelated `dealflow_*` containers. Both tests PASS:
  1. **Synthetic alert** posted via Alertmanager `/api/v2/alerts` → Mailhog received email `[FIRING:1] SmokeTestSynthetic (smoke-test/critical)` from `alerts@jtoye.local` to `ops@jtoye.local`
  2. **Real `ServiceDown`** triggered by `docker stop jtoye-core-java` → Prometheus detected `up==0` for 2m → fired → Alertmanager routed → Mailhog received email `[FIRING:3] ServiceDown (platform/critical)` (3 because the scrape cascade caught core-java itself plus its dependents at scrape time)
  3. Cleanup — `jtoye-core-java` restarted successfully
  Runbook entry at `docs/runbooks/alerts.md` — ServiceDown section filled as the worked example; other 9 alerts are TODO skeletons for future oncall PRs. Subject template + label interpolation + route tree grouping all verified end-to-end. **Discovered during execution:** monitoring compose's `jtoye-network` external reference needed `name: jtoye_oaas_2026_jtoye-network` override because the full-stack compose auto-prefixes the network — committed as a fix.
- [x] **SECR-07** (new 2026-04-15): Gitleaks CI enforcement — `.github/workflows/gitleaks.yml` runs `gitleaks-action@v2` on every PR + push to `main` using a tight `.gitleaks.toml` allowlist (4 paths, plus a content-based placeholder allowlist for defence in depth). Opt-in local pre-commit hook at `scripts/pre-commit-gitleaks.sh`. **Deferred finding surfaced:** `infra/keycloak/realm-export.json` contains dev-only OIDC client secrets and PBKDF2-hashed user passwords — allowlisted with an explicit comment pointing at `.planning/phases/09-repository-secrets-alerting/deferred-items.md` D-1 (proposed `SECR-08` for milestone 4+). Phase 9 plan 09-02, commit `165a7a7`. **Validation on first CI run** — gitleaks CLI not available locally; CI runner is the first validator.

### Work Order B — Storefront marketing + missing customer routes (STFR)

The tier-3 vendor marketing flagship works end-to-end on the vendor side but is never rendered on the storefront — customers literally cannot see the promotions vendors are paying for. Plus two missing customer routes (`/shop/[slug]/cart` and `/shop/orders`) cause direct-link 404s and loyalty friction. ~1 week.

- [x] **STFR-01**: `PublicStorefrontController.getPromotions(slug)` — `GET /public/shops/{slug}/promotions` returns only active (validFrom ≤ now ≤ validUntil) promotions for the tenant owning `slug`; DTO + mapper + RLS-compatible query; controller-level integration test. Done: plan 10-01 commit `168582a`.
- [x] **STFR-02**: `PublicStorefrontController.getAnnouncements(slug)` — `GET /public/shops/{slug}/announcements` returns only active+unexpired announcements with the same tenant scoping; controller-level integration test. Done: plan 10-01 commit `168582a`.
- [x] **STFR-03**: `frontend/app/shop/[slug]/page.tsx` fetches promotions + announcements in parallel with shop, renders the announcement banner above the menu, and overlays discount badges on the product cards that match an active promotion; active-promotion-per-product lookup memoised. Done: plan 10-02 commit `cbbd609`.
- [x] **STFR-04**: `frontend/app/shop/[slug]/cart/page.tsx` standalone cart view — reads the same localStorage key as the modal cart, renders items, supports quantity edit, links to checkout, and handles empty-cart + missing-shop gracefully; Jest test for empty and populated states. Done: plan 10-02 commit `bca8545`.
- [x] **STFR-05**: `frontend/app/shop/orders/page.tsx` authenticated customer order-history route — `RequireCustomerAuth` guard, lists all orders for the logged-in customer across all shops with status filter + date filter + pagination; backend `GET /public/customers/{id}/orders` endpoint (or equivalent) if not already exposed. Done: plan 10-03 commits `926717c` + `7658e35`.
- [x] **STFR-06**: Playwright e2e validates the full customer flow — shop discovery → shop detail → add to cart → cart page → checkout → Stripe test mode payment → confirmation screen; runs against the full docker-compose stack. Done: plan 10-03 commit `b11a3f4`.

### Work Order C — STOMP broker relay for horizontal scale (STMP)

A second `core-java` replica will break kitchen broadcasts silently today because `WebSocketConfig.java` uses `SimpleBroker`. Swap to `StompBrokerRelay` behind a config flag so dev keeps the zero-dependency path while staging/prod gain multi-replica safety. ~1 week.

- [x] **STMP-01**: `WebSocketConfig.java` reads a `stomp.broker.mode` property (`in-memory` | `relay`); in `in-memory` mode it retains today's `enableSimpleBroker`, in `relay` mode it calls `enableStompBrokerRelay("/topic", "/queue").setRelayHost(...).setRelayPort(61613).setClientLogin(...).setSystemLogin(...)`. Done: phase 11 plan 11-01.
- [x] **STMP-02**: RabbitMQ STOMP plugin enabled in `docker-compose.full-stack.yml` and `k8s/` manifests via `rabbitmq-plugins enable rabbitmq_stomp`; port `61613` exposed; relay credentials stored as k8s Secret entries and referenced via env vars. Done: phase 11 plan 11-01.
- [x] **STMP-03**: Two-replica broadcast verified in relay mode — `docker compose up --scale core-java=2`, kitchen client on replica A receives an order state change published via API to replica B within 2 seconds; test result captured in a smoke-test log. Done: `scripts/smoke-test-stomp-relay.sh` (phase 11 plan 11-02, human-confirmed 2026-04-16).
- [x] **STMP-04**: Playwright e2e in relay mode — open `/dashboard/kitchen`, POST an order via the REST API to a different replica (or via the edge gateway load-balancing across replicas), assert the WebSocket message arrives within 2 seconds; green in CI. Done: `frontend/e2e/stomp-relay.spec.ts` (phase 11 plan 11-02, gated behind `RELAY_E2E=true`).
- [x] **STMP-05**: Prometheus alert rule on RabbitMQ STOMP exchange lag > 5 s + Grafana dashboard tile for STOMP connection count; wired through the Alertmanager deployed in SECR-04. Done: `infra/monitoring/prometheus/alerts.yml` StompBrokerLag rule + `infra/monitoring/grafana/dashboards/stomp-dashboard.json` (phase 11 plan 11-03).

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

Which phases cover which requirements. Filled by roadmap creation 2026-04-14.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SECR-01 | Phase 9 | Verified (rescoped — already absent) |
| SECR-02 | Phase 9 | Dropped (rescoped — no committed creds) |
| SECR-03 | Phase 9 | Dropped (rescoped — no distribution needed) |
| SECR-04 | Phase 9 | Done (commits 295ea56 + 47ea7b4) |
| SECR-05 | Phase 9 | Done (email receiver, commit 295ea56 + 47ea7b4) |
| SECR-06 | Phase 9 | Done (smoke test PASSED 2026-04-15 — both synthetic + real ServiceDown delivered to Mailhog) |
| SECR-07 | Phase 9 | Done (commit 165a7a7) |
| STFR-01 | Phase 10 | Done (plan 10-01, commit 168582a) |
| STFR-02 | Phase 10 | Done (plan 10-01, commit 168582a) |
| STFR-03 | Phase 10 | Done (plan 10-02, commit cbbd609) |
| STFR-04 | Phase 10 | Done (plan 10-02, commit bca8545) |
| STFR-05 | Phase 10 | Done (plan 10-03, commits 926717c + 7658e35) |
| STFR-06 | Phase 10 | Done (plan 10-03, commit b11a3f4) |
| STMP-01 | Phase 11 | Pending |
| STMP-02 | Phase 11 | Pending |
| STMP-03 | Phase 11 | Pending |
| STMP-04 | Phase 11 | Pending |
| STMP-05 | Phase 11 | Pending |

**Coverage:**
- v1 requirements: 18 total (SECR ×7 + STFR ×6 + STMP ×5) — SECR-07 added 2026-04-15 during phase 9 rescope
- Mapped to phases: 18 (Phase 9 ×7, Phase 10 ×6, Phase 11 ×5)
- Unmapped: 0 ✓
- Done: SECR-01 (verified), SECR-04, SECR-05, SECR-06 (live tested), SECR-07 (5 of 7 in Phase 9)
- Done: STFR-01, STFR-02 (plan 10-01), STFR-03, STFR-04 (plan 10-02), STFR-05, STFR-06 (plan 10-03) (6 of 6 in Phase 10)
- Dropped: SECR-02, SECR-03 (rescope — no committed creds to rotate/distribute)
- Pending: STMP ×5 (Phase 11)

**Phase 9 is COMPLETE.**
**Phase 10 is COMPLETE.**

---
*Requirements defined: 2026-04-14*
*Last updated: 2026-04-16 — Phase 10 STFR-01..06 marked Done after plans 10-01, 10-02, 10-03 execution. Phase 11 (STMP) pending.*

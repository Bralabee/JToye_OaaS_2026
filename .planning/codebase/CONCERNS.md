# Codebase Concerns

**Analysis Date:** 2026-04-18

**Scope:** Post-v2.1 (tag `v2.1`, shipped 2026-04-16, archived 2026-04-18). Milestone v2.1 closed all 18 v1 Work Order requirements (SECR×7, STFR×6, STMP×5) plus the P0/P1 deep-audit items from `.planning/DEEP-AUDIT-2026-04-16.md`. Remaining concerns are the v2.2+ backlog: the 14 P2 audit items, the 5 deferred items called out in `MILESTONES.md:36-41`, and Work Orders D–O from the 2026-04-14 state-of-codebase.

**Status legend:** Every claim cites `file:line` or references a planning artifact. Items marked `(unverified)` are inferred from planning docs without spot-checking source; caller should verify before acting.

---

## Blockers (open)

None. The 5 production blockers from `STATE-OF-CODEBASE-2026-04-14.md:451-504` were all closed by v2.1:

- Blocker 1 (tenant onboarding) — deferred to v2.2+ as Work Order D, not a blocker for next production push (manual provisioning still works) (`STATE-OF-CODEBASE-2026-04-14.md:455-461`).
- Blocker 2 (marketing on storefront) — closed by STFR-01/02 in phase 10 (`MILESTONES.md:22`, `v2.1-MILESTONE-AUDIT.md:82-88`).
- Blocker 3 (cart + order-history routes) — closed by STFR-03/04/05/06 in phase 10 (`MILESTONES.md:22`, `v2.1-MILESTONE-AUDIT.md:84-89`).
- Blocker 4 (STOMP in-memory) — closed by STMP-01..05 in phase 11 (`MILESTONES.md:23`, `v2.1-MILESTONE-AUDIT.md:93-99`).
- Blocker 5 (`.env` committed + Alertmanager missing) — footnote in `STATE-OF-CODEBASE-2026-04-14.md:490-502` proved `.env` was never committed; Alertmanager delivered by SECR-04 in phase 9 (`v2.1-MILESTONE-AUDIT.md:68-80`).

The v2.1 milestone audit initially flagged 2 missing VERIFICATION.md files as blockers (`v2.1-MILESTONE-AUDIT.md:103-119`); both remediated 2026-04-18 (`v2.1-MILESTONE-AUDIT.md:170-188`).

---

## P1/P2 Tech Debt (tracked)

All 14 items from `HANDOFF.md:29-42`, evidence cross-referenced against `DEEP-AUDIT-2026-04-16.md`.

### CQ-01: Stock race at confirmation vs creation
- Issue: Stock validated at order creation but decremented at confirmation — race window allows two orders to pass validation then exceed available stock (`DEEP-AUDIT-2026-04-16.md:240`).
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderService.java:117-128`
- Impact: Overselling when two concurrent confirmations hit the same low-stock SKU. No data corruption (negative stock caught elsewhere) but customer-visible "out of stock after payment" failure.
- Fix approach: Re-validate stock inside the `confirmOrder` transaction with `SELECT … FOR UPDATE` on `products.stock_quantity`, or move stock decrement into creation (reservation model). Covered by `adjustStockInBatch` at `OrderService.java:350-400` — wire it into confirmation path.

### CQ-02: FinancialTransactionService.getSummary() loads all rows into memory
- Issue: `getSummary()` calls `findAll()` and aggregates in memory; OOM risk on high-volume tenants (`DEEP-AUDIT-2026-04-16.md:67,242`).
- Files: `core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionService.java:111-149`
- Impact: API `/api/v1/financial-transactions/summary` unbounded memory growth. Dashboard home card breaks for long-lived tenants.
- Fix approach: Push aggregation into PostgreSQL via JPQL `SELECT new SummaryDto(SUM(...), COUNT(...), ...)`. Add index on `(tenant_id, created_at)` if not present.

### INFRA-17: K8s NetworkPolicies missing
- Issue: No NetworkPolicies — lateral movement between pods unrestricted (`DEEP-AUDIT-2026-04-16.md:140,255`).
- Files: `k8s/base/` (no `*.yaml` with `kind: NetworkPolicy`) (unverified — inferred from audit)
- Impact: Compromised frontend pod can reach postgres/keycloak/redis directly. Defense-in-depth gap.
- Fix approach: Write per-deployment NetworkPolicy manifests. Default-deny ingress + allowlist specific pod labels. Test with `kubectl exec` cross-pod connectivity attempts.

### INFRA-11a: K8s Sealed Secrets not deployed
- Issue: K8s secrets use `stringData` (plain base64), not sealed (`DEEP-AUDIT-2026-04-16.md:141`, `STATE-OF-CODEBASE-2026-04-14.md:310-315`).
- Files: `k8s/base/secrets-template.yaml` (unverified path)
- Impact: Secrets committable in plain text after base64 decode. Git-tracked secret rotation is manual and error-prone.
- Fix approach: Install `sealed-secrets-controller` in cluster, re-encrypt existing secrets with `kubeseal`, update Kustomize overlays. Documented as "recommended but not deployed" in `STATE-OF-CODEBASE-2026-04-14.md:313`. See also Work Order H.

### AUTH/TENANT: Application-layer tenant validation for guest tracking
- Issue: Guest order tracking relies entirely on RLS — no app-layer validation (`DEEP-AUDIT-2026-04-16.md:123-126`).
- Files: `PublicStorefrontController` (guest `/public/orders/{orderNumber}` path, unverified exact line)
- Impact: If RLS bypassed (e.g., connection pool uses superuser per AUTH-02), guest can read arbitrary orders by guessing order number.
- Fix approach: Add explicit `tenant_id` match check in service layer before returning order. Also requires email-match check to raise enumeration difficulty (see deferred `/public/orders?email=` below).

### SEC: No security headers on Spring responses
- Issue: Spring responses lack security headers (`DEEP-AUDIT-2026-04-16.md:212`, `HANDOFF.md:34`).
- Files: `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` (unverified — needs header configuration added)
- Impact: No `Strict-Transport-Security`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`. Clickjacking / MIME-sniffing exposure.
- Fix approach: Configure Spring Security `HeadersConfigurer` in `SecurityConfig`. HSTS 31536000, nosniff, strict-origin-when-cross-origin, strict permissions.

### DTO: Remove `tenantId` from response DTOs
- Issue: `tenantId` field exposed in OrderDto/CustomerDto responses (`DEEP-AUDIT-2026-04-16.md:68`).
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderDto.java`, `core-java/src/main/java/uk/jtoye/core/customer/CustomerDto.java` (lines unspecified in audit)
- Impact: Info leak — tenant UUID visible to authenticated users. Not immediately exploitable (all API calls already require tenant match) but violates least-exposure principle.
- Fix approach: Remove `tenantId` from DTOs; update MapStruct mappers; update tests. Keep internal Entity field.

### Reactive: Blocking `.block()` calls in state machine
- Issue: `.block()` used on reactive streams in state machine (`DEEP-AUDIT-2026-04-16.md:70`).
- Files: `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineService.java:52-116`
- Impact: Reactor thread pool blocked → throughput degradation under load. Thread starvation possible if state transitions spike.
- Fix approach: Convert state machine calls to imperative path (remove reactive dependency), or make entire transition chain `Mono<OrderDto>` and propagate to controller. Prefer imperative — state machine is not a hot path.

### CSP: No Content-Security-Policy headers
- Issue: No CSP headers on any response (`HANDOFF.md:37`, `DEEP-AUDIT-2026-04-16.md:221`).
- Files: `frontend/next.config.mjs` (no `headers()` config for CSP, unverified), `core-java/.../SecurityConfig.java` (no CSP header)
- Impact: XSS mitigation missing. Audit confirms no `dangerouslySetInnerHTML` usage (`DEEP-AUDIT-2026-04-16.md:82`) but defense-in-depth still wanted.
- Fix approach: Add Next.js `headers()` entry in `next.config.mjs` with CSP `default-src 'self'; script-src 'self' 'unsafe-inline' https://js.stripe.com; frame-src https://js.stripe.com; img-src 'self' data: <minio-host>;`. Parallel header in Spring for API responses.

### ERR: Frontend API error logging
- Issue: Frontend silently swallows API errors in 4 catch blocks returning empty (`DEEP-AUDIT-2026-04-16.md:71`).
- Files: `frontend/app/shop/[slug]/page.tsx` (lines unspecified)
- Impact: Silent failures — customer sees empty storefront, vendor sees nothing in logs. Debugging productivity loss.
- Fix approach: Log errors before returning empty (`console.error` minimum; wire to frontend error tracking — Sentry or similar). Also add a `window.onunhandledrejection` handler (`DEEP-AUDIT-2026-04-16.md:72`).

### Edge: OpenAPI spec generation for Go gateway
- Issue: Go gateway has no OpenAPI specification (`HANDOFF.md:39`).
- Files: `edge-go/` (no `openapi.yaml` or swagger annotations, unverified)
- Impact: No machine-readable contract for the edge surface. External consumers (storefront, mobile) rely on tribal knowledge. Contract drift risk vs Core Java Swagger.
- Fix approach: Use `swaggo/swag` to annotate Gin handlers and generate `docs/openapi.yaml` at build. Or hand-author openapi.yaml and validate with `oasdiff` in CI.

### INFRA-10: Grafana dashboards missing (JVM, DB, business)
- Issue: Only STOMP dashboard exists; no dashboards for JVM health, database, business metrics, application overview (`DEEP-AUDIT-2026-04-16.md:271`).
- Files: `infra/monitoring/grafana/dashboards/` — only STOMP dashboard auto-provisioned (`DEEP-AUDIT-2026-04-16.md:148`)
- Impact: On-call engineer has no single-pane-of-glass for service health. Incident response slower.
- Fix approach: Import community dashboards (JVM Micrometer 4701, Postgres 9628) into `infra/monitoring/grafana/provisioning/dashboards/`. Custom business dashboard keyed off `BusinessMetricsService` metrics.

### INFRA-11b: Alertmanager inhibition rules missing
- Issue: No inhibition rules — database-down cascade triggers noisy pool/connection alerts (`DEEP-AUDIT-2026-04-16.md:272`).
- Files: `infra/monitoring/alertmanager/alertmanager.yml` (unverified — needs `inhibit_rules:` block)
- Impact: Alert fatigue during outages. Operators see 5 alerts for one root cause (DB down → connection pool exhausted → high error rate → service down → no orders).
- Fix approach: Add `inhibit_rules` that silence downstream alerts when `DatabaseDown` or `ServiceDown` fires. Match on shared labels (`service`, `tenant_id`).

### Alert runbook documentation incomplete
- Issue: `docs/runbooks/alerts.md` has 9 TODO stubs, only `ServiceDown` filled (`v2.1-MILESTONE-AUDIT.md:140`).
- Files: `docs/runbooks/alerts.md:81-121` (verified — file is 121 lines; `HighErrorRate`, `HighResponseTime`, `DatabaseConnectionPoolExhausted`, `DatabaseDown`, `TooManyDatabaseConnections`, `HighMemoryUsage`, `FrequentGarbageCollection`, `NoOrdersCreated`, `TenantIsolationFailure` all contain `<!-- TODO: fill in -->`)
- Impact: Alerts route to email but operator has no first-response guidance. Triage time extended.
- Fix approach: Fill in each TODO with: symptom description, immediate checks (kubectl commands, log queries), common causes, rollback decision tree. Populate incrementally as first incidents surface.

---

## Deferred Requirements from v2.1

### SECR-08: Keycloak realm-export dev secrets
- Issue: `infra/keycloak/realm-export.json` contains PBKDF2 password hashes for `demo-admin`/`demo-customer`/`demo-vendor` and plaintext OIDC client secrets for `core-api` and `frontend` clients (`v2.1-phases/09-repository-secrets-alerting/deferred-items.md:7-13`).
- Files: `infra/keycloak/realm-export.json` (entire file)
- Impact: Dev-only today, but nothing prevents staging/prod import. If imported outside dev, dev secrets become prod secrets — instant incident.
- Fix approach: Rewrite realm export to use `${VAR}` substitution (Keycloak 24 supports via `--spi-...` or pre-process at container start). Add required env vars to `.env.example`. Rotate the in-git dev secrets. Smoke-test local dev bootstrap. Tracked as proposed SECR-08 for milestone v2.2+ (`deferred-items.md:26-28`). Currently allowlisted in `.gitleaks.toml`.

### `/public/orders?email=` enumeration risk
- Issue: Customer order tracking endpoint allows email-based lookup; enumeration possible if no rate limiting / validation (`MILESTONES.md:37`, `v2.1-MILESTONE-AUDIT.md:141`).
- Files: `PublicStorefrontController` — `/public/orders?email=` endpoint (unverified line) — deferred per Pitfall 5 of plan 10-03
- Impact: Attacker can enumerate customer emails by guessing and observing 200 vs 404 responses. Confirms existence of customer in the system per tenant.
- Fix approach: Return uniform response regardless of hit/miss (same 200 with empty list). Add per-IP rate limit on endpoint. Require order number + email pair (AND, not OR). Related to app-layer tenant validation gap above.

### 9 alert runbook stubs
- See "Alert runbook documentation incomplete" under P2 tech debt above — same item, tracked from deferred-items angle.

### Phase 11 VALIDATION.md is draft
- Issue: `nyquist_compliant: false`, `wave_0_complete: false` in frontmatter (`v2.1-MILESTONE-AUDIT.md:144`).
- Files: `.planning/milestones/v2.1-phases/11-stomp-broker-relay-for-horizontal-scale/11-VALIDATION.md:4-6` (verified: `status: draft`, `nyquist_compliant: false`, `wave_0_complete: false`)
- Impact: Nyquist workflow incomplete for Phase 11. Not a code defect — a process gap.
- Fix approach: Run `/gsd-validate-phase 11` to close the gap; set frontmatter to compliant after validation waves complete. Also — `stomp-relay.spec.ts` Playwright e2e gated behind `RELAY_E2E=true` env flag, not running in default CI (`v2.1-MILESTONE-AUDIT.md:142`) — enable in CI before v2.2 close.

### 5 quick-task metadata entries
- Issue: Work shipped via PR #40 but the 5 `.planning/quick/260414-*` SUMMARY.md files lack `status: complete` frontmatter (`MILESTONES.md:41`).
- Files (verified exist, no `status:` frontmatter):
  - `.planning/quick/260414-fe3-frontend-security-and-tests/SUMMARY.md`
  - `.planning/quick/260414-inf-infrastructure-hardening/SUMMARY.md`
  - `.planning/quick/260414-j9c-edge-go-security-hardening-batch-phase-1/SUMMARY.md`
  - `.planning/quick/260414-jkp-java-core-data-integrity-batch-phase-2-o/SUMMARY.md`
  - `.planning/quick/260414-ltc-low-touch-cleanup/SUMMARY.md`
- Impact: Planning-metadata drift only. GSD workflow queries for incomplete work will re-surface these.
- Fix approach: Add `status: complete` + `completed_at: 2026-04-16` frontmatter to each SUMMARY.md. Pure doc sync task.

---

## Out-of-Scope Items (v2.2+)

Work Orders D–O from `STATE-OF-CODEBASE-2026-04-14.md:634-647`. Sorted roughly by business priority.

### Work Order D: Tenant onboarding flow
- Problem: No production tenant provisioning; `DevTenantService` is dev-only. Every new vendor requires manual DB insert + Keycloak admin + shop provisioning (`STATE-OF-CODEBASE-2026-04-14.md:151-152,455-461`).
- Blocks: Self-serve SaaS signup. Cannot scale past hand-provisioned pilot.
- Scope: `TenantService` (create → Keycloak admin API → user + role → shop shell), `POST /api/v1/tenants`, `frontend/app/(auth)/register/page.tsx`, Stripe Customer billing hook, welcome email template.
- Effort: 1–2 weeks.

### Work Order E: Order detail view + refund flow
- Problem: Vendor dashboard has no order detail view; cannot refund or edit individual orders (`STATE-OF-CODEBASE-2026-04-14.md:78,186,214`).
- Blocks: Support staff cannot handle customer queries. Refunds impossible via UI.
- Files (affected): `frontend/app/dashboard/orders/page.tsx` (935 lines — add detail view; CONCERNS.md:277-280 prior version flagged this page's complexity)
- Effort: 1 week.

### Work Order F: Finance + settings pages
- Problem: `/dashboard/finance` and `/dashboard/settings` are placeholder shells (`STATE-OF-CODEBASE-2026-04-14.md:81-82,189,215-216`).
- Blocks: Vendors cannot see P&L, payout status, transaction list; cannot change VAT, display name, payment methods, webhooks from UI.
- Effort: 1–2 weeks.

### Work Order G: Log aggregation
- Problem: No log aggregation — container stdout only (`STATE-OF-CODEBASE-2026-04-14.md:128,306,338`).
- Blocks: Cannot trace cross-service requests; no incident forensics; no alerting on log patterns.
- Scope: Deploy Loki (lightweight) or ELK (heavier, more features) in `infra/monitoring/`. Add Promtail/Fluentbit sidecars.
- Effort: 1 week.

### Work Order H: K8s Sealed-Secrets
- See INFRA-11a under P2 tech debt above — same item from backlog view. Effort: 3–5 days.

### Work Order I: Postgres PITR
- Problem: Backup is daily `pg_dump` only — no Point-In-Time Recovery, no tested restore (`STATE-OF-CODEBASE-2026-04-14.md:130,318-322`).
- Blocks: Data loss window up to 24h. No ability to recover to arbitrary timestamp.
- Scope: WAL archiving to S3. Restore-test procedure in runbook. Add backup verification via `pg_restore -l` integrity check (addresses INFRA-12, `DEEP-AUDIT-2026-04-16.md:273`).
- Effort: 3–5 days.

### Work Order J: Review module
- Problem: `ReviewService` exists, no controller exposed; no storefront display; no moderation (`STATE-OF-CODEBASE-2026-04-14.md:67,167`).
- Blocks: Customer review feature shipped in DB (V27 migration) but user-invisible.
- Scope: `ReviewController` + storefront integration + moderation UI. Also fixes TENANT-04 RLS (already closed in P0 wave, but re-verify).
- Effort: 1 week.

### Work Order K: Edge OpenTelemetry + distributed rate limiter
- Problem: Edge gateway has no metrics/observability hook; rate limiter is per-instance (in-memory token bucket — 10x effective limit at 10 pods) (`STATE-OF-CODEBASE-2026-04-14.md:260-262`).
- Blocks: Horizontal scaling of edge-go breaks rate limiting correctness. No cross-service tracing into edge.
- Scope: OpenTelemetry SDK + exporter to Zipkin/Tempo. Redis-backed distributed rate limiter.
- Effort: 1 week.

### Work Order L: Search performance verification
- Problem: Full-text product search uses PG `tsvector` (V25) but not perf-verified; could N-query at scale (`STATE-OF-CODEBASE-2026-04-14.md:56,165`).
- Blocks: Nothing today, but unknown scaling cliff.
- Scope: Load test with 10k products per tenant × 100 tenants; profile query plans; add missing indexes.
- Effort: 3 days.

### Work Order M: Bulk product import integration
- Problem: Upload UI exists; endpoint integration unclear (`STATE-OF-CODEBASE-2026-04-14.md:77,218`).
- Files: `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java` (prior concerns also flagged silent partial-failure and OOM-on-large-file at lines 92,170,197,250)
- Blocks: Vendor onboarding requires manual product entry.
- Effort: 3 days.

### Work Order N: Billing subscription management
- Problem: No Stripe Customer / Subscription management for vendors. Coupled with Work Order D.
- Blocks: Cannot monetize; cannot enforce tier limits.
- Effort: 1 week.

### Work Order O: WhatsApp order idempotency
- Problem: No order idempotency key from WhatsApp webhook id → retry after partial failure could double-book (`STATE-OF-CODEBASE-2026-04-14.md:235,259`).
- Files: `edge-go/cmd/edge/main.go:336-355`
- Blocks: WhatsApp flow MVP-ready but not production-safe under retry.
- Effort: 2 days.

---

## Architectural Anxieties

### Dev environment port conflicts (already workarounded)
- Issue: Port 3000 held by MCP server — frontend runs on 3100. Port 5432 held by unrelated `dealflow_postgres` container — full-stack bringup blocked by port collision (`STATE-OF-CODEBASE-2026-04-14.md:411,486-494`).
- Files: `frontend/.env.local.example` (NEXT_PUBLIC_API_URL and related configured for 3100), `HANDOFF.md:55` (confirms frontend port 3100)
- Impact: Every new developer hits this if they run MCP server or dealflow. Keycloak redirect URIs + CORS allow-list must include `http://localhost:3100` (per memory `feedback_port3100.md`).
- Fix approach: Document port-conflict mitigation in README setup. Longer-term: dev-compose on a non-default subnet with exposed ports in 3100+/5532+ range. Low priority — workaround is known.

### Blocking reactive calls in state machine
- Listed as separate P2 item above (`OrderStateMachineService.java:52-116`). Architectural anxiety: if reactive framework is not used anywhere else as a hot path, consider removing reactor dependency entirely rather than patching blocking calls.

### STOMP broker mode flag surface area
- Issue: `stomp.broker.mode` flag (`in-memory` | `relay`) introduced in Phase 11. Local dev defaults differ from staging/prod (`MILESTONES.md:23`, `v2.1-MILESTONE-AUDIT.md:91-99`).
- Files: `core-java/src/main/java/uk/jtoye/core/ws/WebSocketConfig.java` (unverified)
- Impact: Two code paths means two failure modes. Relay mode adds RabbitMQ STOMP plugin as a dependency at port 61613; if plugin disabled in a prod cluster, kitchen silently degrades.
- Fix approach: Add Prometheus alert on relay-mode misconfiguration (probe port 61613 from core-java startup, fail fast). Document the flag contract in the CLAUDE.md architecture section.

### `payment_event_outbox` flusher is single-instance
- Issue: `PaymentEventOutboxFlusher` is method-level `@Scheduled`, not DB-recovery-driven. If the flushing instance dies mid-flush, PENDING rows stuck until restart (`STATE-OF-CODEBASE-2026-04-14.md:165`).
- Files: `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java:55-105`
- Impact: Payment events delayed during core-java restart. Outbox is a transactional guarantee that still relies on availability.
- Fix approach: Add Shedlock or similar distributed lock so multiple replicas can coordinate flush. Or migrate to Debezium CDC for DB-driven delivery.

### Go edge rate limiter in-memory
- Issue: Same as Work Order K (distributed rate limiter). Callout here for architectural view: horizontal scaling of edge-go breaks correctness, not just observability (`STATE-OF-CODEBASE-2026-04-14.md:261`).

### Merge freeze nuances
- No open merge freeze. `main` is at `9e491d5` (PR #40 merged 2026-04-16). Branch `feature/deep-audit-p1-fixes` from HANDOFF.md has been merged (`HANDOFF.md:3-4`). `.planning/MILESTONES.md` records clean close at `9008b3a..9e491d5`.

---

*Concerns audit: 2026-04-18*

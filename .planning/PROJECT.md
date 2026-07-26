# J'Toye OaaS — Milestone v2.3: Vendor Ops + AI Interleaved

## What This Is

J'Toye OaaS is a multi-tenant UK retail SaaS platform enabling food vendors to manage shops, products, orders, and customers through a shared infrastructure. Through v2.2 (production hardening + vendor order operations, 2026-07) the platform gained the full P1/P2 security backlog (RBAC, GDPR erasure, JWT audience, CSP enforcement, rate limiting, Redis resilience), vendor onboarding with a compliance gate state machine, a full-frontend overhaul, a read-only MCP server, and a uniform Idempotency-Key contract — running on a live-verified minikube deployment. Milestone v2.3 turns to vendor operational control: unblocking stuck onboarding, scoping access per shop within a tenant, hardening image handling with a copy-on-write asset model + safe async upload pipeline, fixing dashboard mobile, and extending the AI/automation surface (outbound webhooks + mutating MCP tools) — with a committed local-k8s overlay replacing the imperative deploy patches.

## Core Value

Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.

## Current Milestone: v2.3 Vendor Ops + AI Interleaved

**Goal:** Give vendors real operational control — unblock stuck onboarding, scope access per shop, harden image handling — and extend the AI/automation surface, all on verified local-k8s-capable infrastructure. Scope locked by user 2026-07-14 (do not re-litigate); three phase-ready specs drive it (`.planning/specs/`).

**Target features (phase order — thinnest/highest-pain first):**

Vendor operations:
- **Onboarding blocker UX** — visible per-gate blockers with remediation, correctable onboarding data (company number / sole-trader / FHRS override update endpoint), reachable WITHDRAW exit, manual-review made visible (DTO-derived "in review" + admin gate-resolve endpoint), rejection reason surfaced to vendor. Zero migrations.
- **Vendor-scoped access (RBAC)** — the vendor's internal access model (hierarchy Vendor/tenant → Shop; one vendor owns many shops). `shop_staff` mapping table (V52), roles GROUP_ADMIN (vendor-wide) / SHOP_MANAGER / STAFF, application-layer second gate (RLS stays the tenant wall), dashboard shop-context switcher with explicit "apply to all shops", GROUP_ADMIN backfill (no day-one regression). Shop is the finest grain; a department tier is a future layer.
- **Image architecture** — `media_asset` copy-on-write model (V53) with reference counting, safe async RabbitMQ upload/normalize pipeline (magic-byte sniff, decode-to-verify, EXIF strip, resize/re-encode), compress = hard veto, content-relevance = review queue.
- **Dashboard mobile (#104)** — fixed `w-64` sidebar overlays content at 375px; responsive nav pairing with the shop switcher.

AI / automation track:
- **Outbound webhooks (#205)** — vendor-registered webhook subscriptions delivered from the V46 transactional outbox (onboarding/order/refund state changes).
- **Mutating MCP tools (#204 wiring)** — extend the read-only MCP server (Phase 20) with write tools riding the uniform Idempotency-Key contract.

Infrastructure:
- **Local-k8s overlay** — committed `k8s/local` overlay (endpoint shims to `host.minikube.internal`, minReplicas=1, backup→MinIO) replacing imperative patches; fixes the verified breakage list (DB_PORT hardcode repo defect, NOSUPERUSER role secrets, HPA minReplicas, pg-backup→S3).

**Key context:**
- Direct predecessor is v2.2 (production hardening + vendor order ops), fully merged to main; at v2.2 close schema was V51 with 1257 test invocations, docs-freshness green. **As of v2.3 planning (2026-07-15)** main was schema V56 / 1456 test invocations (Phase 22 #231 + landing brand refresh + logo-nav fix #232 both merged). That is a dated snapshot, not a live figure — see `docs/metrics.json` for the current count.
- Migration numbering: shop_staff = **V52**, media_asset = **V53** (shop_staff first per HANDOFF ordering).
- No milestone-level research — all three specs carry locked decisions with file:line evidence; the only genuinely-new surfaces (outbound webhooks, mutating MCP, async image pipeline) have prescribed approaches (V46 outbox, #204 idempotency, RabbitMQ worker). Framework pitfalls covered at phase-level research.
- Deferred within-track items (per specs): platform-wide stock image library (cross-tenant), reviewer SLA/multi-reviewer, reapply-after-REJECTED, self-serve user invitation, per-capability permissions beyond the three roles.

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
- ✓ **[v2.2 VOPS-02]** `POST /api/v1/orders/{id}/refund` wired to Stripe `Refund.create` with stored-first idempotency; refund.created/refund.updated/refund.failed webhook lifecycle (after Phase 16.1 dedup); V36 refunds + refunds_aud + RLS migration; `RefundEventPublisher` writes `order.refunded` to outbox — Phase 17
- ✓ **[v2.2 VOPS-03]** Order state machine extended with `REFUND_REQUESTED` event + `REFUNDED` state + 4 transitions (`CONFIRMED|PREPARING|READY|COMPLETED → REFUNDED`); idempotent (service-level short-circuit on already-refunded); audited via Hibernate Envers — Phase 17
- ⚠ **[v2.2 VOPS-01]** `/dashboard/orders/[id]` route + `OrderDetailPanel` (header, customer, items, payment block, refund history) — header-level **state-transition timeline subcomponent NOT implemented**; tracked in `17-VERIFICATION.md` gaps + `17-HUMAN-UAT.md` — Phase 17
- ✓ **[v2.2 SEC/CQ/INF/DOC]** Guest-tracking tenant validation (Phase 13), Spring security headers + Next.js CSP enforce (Phase 12), stock-race fix + getSummary aggregation (Phase 14), K8s NetworkPolicies + Sealed Secrets manifests (Phase 15), Go edge OpenAPI (Phase 16), pre-prod hardening council fixes (Phase 16.1) — all shipped v2.2
- ✓ **[v2.2 Vendor onboarding]** Onboarding state machine as sole writer of `Shop.published`, CH/FHRS/allergen compliance gates, hybrid auto-approve by model (Phase 18)
- ✓ **[v2.2 Frontend]** Full-frontend experience overhaul — mobile nav, loading states, demo catalog images, storefront theme groundwork (Phase 19)
- ✓ **[v2.2 AI]** Read-only MCP server slice with live RLS proof (Phase 20); uniform Idempotency-Key contract (#204, V50); scoped machine credentials (#206)
- ✓ **[v2.2 P1/P2 security backlog]** RBAC method security (#83), GDPR erasure completeness (#84), guest-checkout stock convergence (#85), Redis resilience (#86), JWT audience validation (#87), public-path rate limiting (#88), supply-chain CI gate (#91), Keycloak deprovisioning on offboard (#102, V49), RLS uuid-cast safety (#113, V51)
- ✓ **[v2.3 COMMS]** Notifications & Comms — first delivery consumer of the V46 outbox: multipart branded email + INERT WhatsApp/SMS seam (COMMS-02/07), GDPR consent/suppression + one-click unsubscribe (COMMS-03, V54), vendor-registered HMAC-signed outbound webhooks with retry/auto-pause/replay + delivery-log UI (COMMS-04/05/06, V55/V56, absorbs AI-01/#205 + #208), the previously-dead onboarding.events exchange now bound to a vendor email, all new tables ENABLE+FORCE RLS — Phase 22 (7 plans, verified 34/34; 5 code-review fixes; CR-01 SSRF DNS-rebinding hardening deferred to a tracked security follow-up)
- ✓ **[v2.3 VSA/MOBL]** Vendor-Scoped Access + Responsive Dashboard Nav — the vendor→shop application-layer authorization boundary UNDER the RLS tenant wall: `shop_staff`/`user_directory` + `_aud` ENABLE+FORCE RLS via `current_tenant_id()` (V52), JIT day-one GROUP_ADMIN auto-provision with realm-admin bridge, `ShopAccessService` single decision funnel (HTTP + STOMP share it) with deny-by-default writes + query-level read-scope + typed shop-403≠RLS-404, staff list/grant/revoke API + screen (last-admin 409 lock, idempotent grant, Envers audit, email-masked directory, GDPR sweep), persisted shop-context switcher + all shop-scoped screens narrow live, config-gated strict-scoping that de-honours JIT admins with a bootstrap-safe flip (V57 `grant_source`), and MOBL-01 375px responsive nav carrying the switcher (VSA-01..04, MOBL-01) — Phase 23 (17 plans incl. a 10-plan gap-closure wave; security 97/97 threats closed; validation 0 gaps; UAT 2/2 live-verified incl. real 375px Playwright). Conscious deferrals (AR-23-01..08) tracked in `deferred-items.md`.
- ✓ **[v2.3 IMG]** Image Architecture — CoW assets + safe async upload pipeline: reference-counted `media_asset` (V53) with sha256 per-tenant dedup + `product_media` join, ENABLE+FORCE RLS via `current_tenant_id()` proven under NOSUPERUSER, per-tenant `set_config` backfill of existing `image_url` behind a dual-read resolver, copy-on-write repoint + physical MinIO delete only at ref-count 0 (IMG-01); reject-early oversize guard + quarantine + PENDING + idempotent 202 accept + a **dedicated** `media_event_outbox`→`media.events` path (V58, sidesteps the shared-flusher dispatch trap) + a GUC-pinned `@RabbitListener` worker that magic-byte-sniffs / bomb-guards / decode-verifies / EXIF-strips / stores ONLY the WebP derivative / deletes the raw + PENDING reaper + BulkImportService unified onto the one path (IMG-02); FAILED→vendor-visible reason, below-relevance→ACTIVE+flagged review queue, advisory-default-OFF vision gate (IMG-03); status-aware `AssetImage` + `/dashboard/media/review` queue screen + 202 uploader handling (IMG-04) — Phase 24 (6 plans, 5 waves; verifier 4/4 must-haves + code-review-caught **CR-01 dedup-attach blocker** + WR-01..05 all fixed under TDD; schema V59, 1648 test invocations, docs-freshness green). Live browser E2E deferred to `/gsd:verify-work` (needs REBUILD-ALL for the 24-01 `libwebp-tools` Dockerfile change).

### Active

**Onboarding UX (ONBD):**
- [ ] ONBD-01: Vendor can withdraw an in-progress application (WITHDRAW event + endpoint, terminal, restart = new application)
- [ ] ONBD-02: Vendor can correct onboarding data (company number / sole-trader flag / FHRS establishment override) via an update endpoint valid in DRAFT/ACTION_REQUIRED, re-validated like create
- [ ] ONBD-03: Manual-review applications are visible — vendor sees "in review" (DTO-derived), admin sees a review-queue entry, with a human gate-resolve mechanism that recomputes and advances the state machine
- [ ] ONBD-04: Per-gate remediation blocks (why → what to do → deep link to the fix surface) for FAILED/MANUAL_REVIEW gates
- [ ] ONBD-05: Rejection reason surfaced on the vendor DTO + a configurable real support channel on terminal states

**Image architecture (IMG):**
- [ ] IMG-01: `media_asset` model (tenant-scoped RLS, sha256 dedup) — products reference assets, copy-on-write on edit, reference-counted physical delete (V53)
- [ ] IMG-02: Safe async upload pipeline — early size/streaming reject, quarantine + PENDING row + AMQP publish; worker does magic-byte sniff, decode-verify, EXIF strip, normalize/resize/re-encode (stored artifact is always the normalized derivative)
- [ ] IMG-03: Gate strictness — compress failure = hard veto (FAILED), content-relevance below threshold = review queue (asset stays ACTIVE), vision stage behind advisory flag
- [ ] IMG-04: Product UI "processing" state while asset PENDING; vendor-visible review/rejection queue

**AI / automation (AI):**
- [x] AI-01: Vendor-registered outbound webhook subscriptions delivered from the V46 transactional outbox with retry/signing (#205) — ✓ delivered as Phase 22 COMMS-04/05/06 (V55/V56)
- [ ] AI-02: Mutating MCP tools extending the Phase 20 read-only server, riding the uniform Idempotency-Key contract (#204)

**Infrastructure (INFRA):**
- [ ] INFRA-01: Committed `k8s/local` overlay (host.minikube.internal shims, minReplicas=1, backup→MinIO) replacing imperative patches
- [ ] INFRA-02: Fix verified k8s breakage — DB_PORT hardcode (secretKeyRef), NOSUPERUSER role secrets (DB_USER/DB_PASSWORD not POSTGRES_USER), HPA minReplicas, pg-backup target

### Out of Scope

- Tenant self-serve onboarding flow (Work Order D) — deferred to milestone 4
- Vendor finance + settings pages (Work Order F) — deferred
- Log aggregation + Grafana dashboards + runbooks (Work Order G) — deferred
- ~~K8s sealed-secrets / external-secrets-operator (Work Order H)~~ — now in v2.2 scope as INF-02
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

- **Existing codebase:** 3-tier architecture (Next.js 16 frontend, Go 1.22 edge, Spring Boot 3.4.2 core) with Flyway V1–V33, 516+ logical test invocations across 66 test files (390 Java `@Test` methods + 76 Jest `it/test` blocks + 50 top-level Go `Test*` funcs / 54 with `t.Run` subtests). Verified 2026-04-18 post-v2.1.
- **Previous milestones:** Milestone 1 (batches 3–5 + Tier 2) shipped reliability + core features; Milestone 2 (v2.0 Tier 3) shipped API versioning, vendor marketing, KDS, test coverage closure; Milestone 3 (v2.1) closed the 3 highest-priority audit Work Orders + a deep-audit P1 pass
- **v2.1 outcome:** 18/18 requirements complete, 3/3 phases verified after audit remediation. Alertmanager routes emails (not Slack — rescoped during phase 9). Secret-hygiene CI prevents future drift. Storefront is no longer half-dead — customers see promotions and have cart/orders routes. STOMP broker scales horizontally behind a config flag.
- **Known concerns for v2.2 candidates:**
  - 14 P2 deep-audit items in HANDOFF.md (stock race at confirmation vs creation, DB aggregation for getSummary, K8s NetworkPolicies, K8s Sealed Secrets, CSP headers, CSP-compatible frontend error logging, Grafana JVM/DB/business dashboards, Alertmanager inhibition rules, runbook completion, blocking reactive calls in state machine)
  - SECR-08: Keycloak realm-export hardcoded dev secrets (allowlisted for now)
  - `/public/orders?email=` enumeration risk
  - Phase 11 VALIDATION.md draft (nyquist_compliant: false)

## Constraints

- **Tech stack**: Must use existing stack — Spring Boot 3.5.16, Next.js 16.2.2, Go 1.25, PostgreSQL 15
- **Java version**: JDK 21 (JDK 25 incompatible with Gradle 8.10)
- **Multi-tenancy**: All new features must respect RLS and TenantContext; new tables ENABLE+FORCE RLS tenant-scoped, proven under the NOSUPERUSER role-downgrade (RlsContractTest pattern); new public endpoints tenant-scoped by slug
- **Migration numbering**: shop_staff = V52, media_asset = V53 (shop_staff first); onboarding-blocker path is zero-migration
- **Testing**: Every requirement ships with tests; the invocation baseline is whatever `docs/metrics.json` currently records — that file is the single source of truth and the `docs-freshness` CI gate enforces it. The number is deliberately **not** restated here: the gate does not read `PROJECT.md`, so a count quoted in this prose can only drift (it sat at a stale "1257" for several phases while the real figure reached 1736). Reconcile via `scripts/docs-freshness.sh --write`. Total must grow, not regress.
- **Docker**: Always rebuild ALL containers after code changes before E2E testing (stale images cause subtle failures)
- **Runtime**: compose XOR k8s on local (shared dev Postgres) — never both writers; compose is canonical
- **Incremental Betterment**: no capability regression on day one (RBAC backfills GROUP_ADMIN; image backfill has a dual-read window)

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
*Last updated: 2026-07-24 — Phase 25 (Mutating MCP Tools, AI-02) complete: `create_order`/`create_customer` MCP write tools (mandatory Idempotency-Key split-to-header, SSRF-safe `corePost`) over `orders:write`/`customers:write` `@PreAuthorize` gates; template-seeded `integration-orders-rw` RW credential + `ACCESS_MACHINE_CLIENT_IDS` VSA-02 allowlist; cross-tenant write RLS proof under NOSUPERUSER `rls_test_role`; human-approved live E2E (create 200 / idempotent-replay-no-dup / no-scope 403 / cross-tenant 404-RLS / no rogue shop_staff row). Code-review CR-01 remediated → ALL order/customer mutations now write-scope-gated (10 latent integrationTest regressions from the create gates repaired); WR-01/WR-02 fixed. No schema change; 1684 logical invocations; suite green (1151 Java, 48 MCP). Milestone v2.3 83%/6 phases (21-25 done). Next: Phase 26 (Local-K8s Overlay + Verified Breakage Fixes).*

*2026-07-26 — Phase 26 (Local-K8s Overlay + Verified Breakage Fixes) complete and merged (`a67f50d`, PR #267): committed `k8s/local` overlay, 5 CI gates, live minikube rehearsal, 4 real production defects fixed. **Milestone v2.3's build is 6/6 phases.** Schema V59; 1736 logical invocations; `docs-freshness` green. Two follow-on CI fixes merged (`5cd1ddf` gitleaks allowlist, `53f0444` frontend Trivy base image).*

*2026-07-26 — Milestone backlog review (`/gsd-review-backlog`). The GSD-native backlog was empty (no `999.x` phases, no `## Backlog` section); the real v2.3 backlog was 31 open entries across the phase `deferred-items.md` files, of which only one carried an issue link. **28 were filed as GitHub issues #278–#305** so they survive the phase-dir archive; one (`PROJECT.md` stale baseline) named this pass as its owner and is fixed in this commit; one (AGENTS.md stale at V37) was found already resolved and is marked obsolete. Open at review: `main`'s CI/CD Pipeline is red at the frontend build-arg gate, blocked on the production-domain decision — `jtoye.co.uk` is not registered.*

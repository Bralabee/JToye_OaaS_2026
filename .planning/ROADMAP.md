# Roadmap: J'Toye OaaS — Milestone v2.3 (Vendor Ops + AI Interleaved)

Multi-tenant UK retail SaaS for food vendors — shops, products, orders, customers, marketing, kitchen fulfilment.

## Overview

Milestone v2.3 turns from platform hardening to **vendor operational control**. It unblocks stuck onboarding (visible per-gate blockers, correctable data, reachable exits — zero migrations), stands up the platform's first **delivery consumer** of the V46 transactional outbox (email-first notifications + the outbound-webhook machine channel + a WhatsApp/SMS seam), adds a finer authorization boundary *inside* the tenant (`shop_staff` mapping, roles, an application-layer gate, a shop-context switcher — with a GROUP_ADMIN backfill so day one has no regression), and hardens image handling ahead of real vendor uploads (a copy-on-write `media_asset` model + a safe async upload pipeline that stores only the validated, normalized derivative). It also extends the AI/automation surface (mutating MCP tools riding the #204 Idempotency-Key contract) and replaces the imperative k8s deploy patches with a committed `k8s/local` overlay plus the verified breakage fixes.

**6 phases (21–26)**, continuing phase numbering from v2.2's Phase 20. Original scope locked by user 2026-07-14 (three phase-ready specs in `.planning/specs/` carry file:line evidence and locked decisions); the **Notifications & Comms phase was inserted at 22 on 2026-07-14**, ahead of the original order, absorbing the standalone Outbound Webhooks phase (#205) + WhatsApp (#208) — a dedicated delivery consumer had to precede the surfaces that depend on it. Phase order is thinnest/highest-pain first. Granularity: `fine`.

## Milestones

- ✅ **v2.0 Tier 3 Enhancements** — Phases 1–8 (shipped 2026-04-10, PR #27)
- ✅ **v2.1 Post-Audit Hardening + Storefront Completion** — Phases 9–11 (shipped 2026-04-16; archived `milestones/v2.1-*`)
- ✅ **v2.2 Production Hardening + Vendor Order Ops + Onboarding + MCP** — Phases 12–20 (shipped 2026-07-13; archived `milestones/v2.2-*`)
- 🚧 **v2.3 Vendor Ops + AI Interleaved** — Phases 21–26 (in progress)

## Phases

**Phase Numbering:**

- Integer phases (21, 22, …): planned milestone work
- Decimal phases (22.1, …): urgent insertions (marked INSERTED)

<details>
<summary>✅ Shipped milestones — Phases 1–20 (v2.0 → v2.2)</summary>

Full phase detail lives in the milestone archives:

- **v2.0** (Phases 1–8) — API versioning, vendor marketing, KDS WebSocket, test coverage closure. Source: PR #27 (commit `955e641`); no archive file (pre-`/gsd-complete-milestone`).
- **v2.1** (Phases 9–11) — repo secrets + Alertmanager, storefront marketing render + customer routes, STOMP broker relay. Archives: `milestones/v2.1-ROADMAP.md`, `milestones/v2.1-REQUIREMENTS.md`, `milestones/v2.1-MILESTONE-AUDIT.md`.
- **v2.2** (Phases 12–20) — Spring/Next.js security headers + CSP, guest-tracking tenant validation, stock-race + summary aggregation, K8s NetworkPolicies + Sealed Secrets, Go edge OpenAPI, pre-prod hardening (16.1), vendor order detail + Stripe refund flow, vendor onboarding first slice, full-frontend overhaul (19), read-only MCP server (20). Archives: `milestones/v2.2-ROADMAP.md`, `milestones/v2.2-REQUIREMENTS.md`.

Schema at close: **V51**. Test baseline: **1257 logical invocations**. docs-freshness green. Running on a live-verified minikube deployment.

</details>

### 🚧 v2.3 Vendor Ops + AI Interleaved (Phases 21–26)

- [x] **Phase 21: Onboarding Blocker UX** — Visible per-gate blockers, correctable onboarding data, reachable withdraw/support exits, manual-review made visible to vendor + admin (zero migrations) (completed 2026-07-14)
- [ ] **Phase 22: Notifications & Comms** — First delivery consumer of the V46 transactional outbox: email-first transactional notifications (Mailhog dev → SES prod) + vendor-registered outbound webhooks (HMAC-signed, retried; absorbs #205) + a WhatsApp/SMS seam behind a provider flag (absorbs #208), with GDPR consent + unsubscribe
- [ ] **Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav** — `shop_staff` (V52) + app-layer role gate + shop-context switcher + staff management, with a GROUP_ADMIN backfill; dashboard nav no longer overlays at 375px
- [ ] **Phase 24: Image Architecture — CoW Assets + Safe Upload Pipeline** — `media_asset` (V53) copy-on-write + reference counting + safe async RabbitMQ upload/normalize pipeline storing only the validated derivative
- [ ] **Phase 25: Mutating MCP Tools** — Write tools on the Phase 20 MCP server riding the uniform Idempotency-Key contract, RLS-proven under the MCP credential
- [ ] **Phase 26: Local-K8s Overlay + Verified Breakage Fixes** — Committed `k8s/local` overlay replacing imperative patches + the verified deploy breakage list fixed

## Phase Details

### Phase 21: Onboarding Blocker UX

**Goal**: A vendor who hits an onboarding blocker can see exactly what is wrong, fix bad data in place, withdraw if they want out, and reach a real human — no more silent black holes. The state machine stays the sole writer of `Shop.published`; every transition goes through events.
**Depends on**: Nothing (v2.2 shipped the onboarding state machine + gate chain). Zero Flyway migrations (`WITHDRAWN` is already in the V43 status CHECK).
**Requirements**: ONBD-01, ONBD-02, ONBD-03, ONBD-04, ONBD-05
**Success Criteria** (what must be TRUE):

  1. A vendor whose application hit a manual-review gate (e.g. a fuzzy FHRS name/address mismatch) sees an honest "In review — a reviewer checks these within N business days" state (DTO-derived from `status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING`), and an admin sees that same application in a review queue with a gate-resolve control that recomputes and advances the state machine — neither is a dead end. (ONBD-03)
  2. A vendor can correct a typo'd company number, toggle the sole-trader flag, or override the FHRS establishment via an update endpoint valid only in DRAFT/ACTION_REQUIRED (RFC 7807 rejection outside those states), then resubmit and have the gates re-run against the corrected data. (ONBD-02)
  3. Each FAILED / MANUAL_REVIEW gate renders *why → what to do → a button that goes there* — inline company-number edit, a "fix these N products" allergen deep link, and an FHRS address-confirm / establishment picker. (ONBD-04)
  4. A vendor can withdraw an in-progress application from a confirm dialog; the application reaches `WITHDRAWN` (terminal, valid from DRAFT/VERIFYING/ACTION_REQUIRED) and restarting begins a fresh application. (ONBD-01)
  5. On a rejected application the vendor sees the actual `rejectionReason` (now on the vendor-facing DTO) plus a real, configured support channel (mailto/link) — not a bare "contact support"; and one blocked-onboarding journey (bad company number → fix inline → resubmit → live) passes end-to-end in Playwright. (ONBD-05)

**Plans**: 5 plans (4 waves)

Plans:
**Wave 1**

- [x] 21-01-PLAN.md (Wave 1) — Backend vendor endpoints: `POST /onboarding/withdraw` (reuses the already-wired WITHDRAW transitions) + `POST /onboarding/company-number` update (blank=sole trader, DRAFT/ACTION_REQUIRED guard, create-identical validation, RFC 7807) + Testcontainers proofs
- [x] 21-02-PLAN.md (Wave 1, parallel) — Backend outbox stall-event seam (Pitfall-1 atomic unit): `onboarding.events` TopicExchange + `OnboardingStateChangeEvent` + `OnboardingEventPublisher` + flusher dispatch branch + `GateChainRunner` emission from the MANUAL_REVIEW park branch

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 21-03-PLAN.md (Wave 2) — Backend manual-review visibility: DTO-derived `reviewPending` + `rejectionReason` on the vendor `OnboardingDto` + admin `GET /onboarding/admin/reviews` queue + `POST /onboarding/admin/{id}/gates/{gateType}/resolve` (writes gate row via V43 `_aud`, recompute-after-commit → SM advances) + RLS/Envers/403/404 proofs

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 21-04-PLAN.md (Wave 3) — Frontend: per-gate remediation blocks (why → what → deep link) + honest in-review copy with polling back-off + withdraw confirm dialog + inline company-number edit + rejection reason + config-injected support channel (NEXT_PUBLIC_*) + admin gate-resolve UI + Jest/tsc

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 21-05-PLAN.md (Wave 4) — Playwright blocked-onboarding journey (bad company number → fix inline → resubmit → live) + human-verify FHRS manual-review path + `docs/metrics.json` reconcile + closure

**UI hint**: yes

### Phase 22: Notifications & Comms

**Goal**: The platform gains its first **delivery consumer** of the V46 transactional outbox. Order/payment/refund and Phase 21 onboarding state-change events — today emitted to the outbox but delivered nowhere — reach the people and systems that need them. Email first (Mailhog dev → SES prod), with the outbound-webhook machine channel (the delivery seam ONBD-05 / AI-01 left open) and a WhatsApp/SMS seam as further channels on the same consumer.
**Depends on**: V46 transactional outbox + Phase 21 `onboarding.events` (both shipped). Reuses the RabbitMQ/AMQP infra. **Respects the outbox-flusher dispatch trap**: this phase adds consumers (not producers) — all four `publishRow` dispatch branches already exist, so the shared flusher is untouched. Migrations take **V54/V55/V56** under `out-of-order=true` — the V52 `shop_staff` (Phase 23) / V53 `media_asset` (Phase 24) ordering is preserved.
**Requirements**: COMMS-01, COMMS-02, COMMS-03, COMMS-04, COMMS-05, COMMS-06, COMMS-07 (COMMS-04/05/06 absorb AI-01 outbound webhooks #205; COMMS-07 absorbs #208 WhatsApp) — *finalized by 22-SPEC.md*
**Success Criteria** (what must be TRUE) — *locked by `22-SPEC.md`*:

  1. A single set of outbox consumers fans each matching event to its channels; a channel/subscription failure is retried with bounded backoff and does not block delivery to other channels or subscriptions (no head-of-line block). The pre-existing order-confirmation email path stays intact (no regression) and no event type poison-dead-letters. (COMMS-01, COMMS-05)
  2. Transactional email is delivered to the correct audiences (customer and/or vendor) across order/onboarding/payment/refund through a provider-abstracted multipart renderer (Mailhog dev, SES-over-SMTP prod), tenant-scoped, with a working one-click unsubscribe + recorded consent (GDPR/PECR) — no email to a suppressed recipient; marketing requires explicit opt-in. (COMMS-02, COMMS-03)
  3. A vendor can register tenant-scoped webhook subscriptions (ENABLE+FORCE RLS, isolation-proven) that receive HMAC-SHA256-signed, retried, observable deliveries of chosen event types, self-served through a mobile-first management + delivery-log/replay UI (375px). (COMMS-04, COMMS-05, COMMS-06)
  4. The WhatsApp/SMS channel is scaffolded behind a provider flag defaulting off until credentials are configured (#208); its absence never blocks email or webhook delivery. (COMMS-07)

**Plans**: 7 plans (5 waves)

Plans:
**Wave 1** *(parallel foundations — no shared-file overlap)*

- [x] 22-01-PLAN.md (Wave 1) — Notification channel seam: `NotificationChannel` abstraction + `MimeMessageHelper` multipart `EmailChannel` + `EmailTemplateRenderer` (D-01) + INERT-by-default `WhatsAppSmsChannel` stub (COMMS-07) + notification/whatsapp config keys (GLOBAL_RULE_6). Order-email path untouched (Pitfall 5 path A). [COMMS-02, COMMS-07]
- [x] 22-02-PLAN.md (Wave 1, parallel) — Consent backend: V54 `notification_suppression` + `marketing_opt_in` (FORCE RLS helper form) + `NotificationCategory` + `ConsentGate` + stateless HMAC `UnsubscribeTokenService` + no-auth `PublicUnsubscribeController` + NOSUPERUSER RLS proof (COMMS-03)
- [x] 22-03-PLAN.md (Wave 1, parallel) — Webhook subscription data + CRUD API: V55 `webhook_subscription` (FORCE RLS, plaintext secret) + `WebhookUrlValidator` (HTTPS + SSRF block) + create/list/rotate/pause/resume/revoke REST (RFC 7807, secret-once) + RLS proof (COMMS-04)

**Wave 2** *(blocked on 22-01 + 22-02)*

- [x] 22-04-PLAN.md (Wave 2) — Email dispatch + all RabbitMQ topology: bind `onboarding.events` + add payment/refund/webhook-fanout durable queues + `RecipientResolver` (D-04) + `NotificationDispatchService` (consent-gated fan-out) + onboarding/financial `@RabbitListener`s → Mailhog landing proofs; order path un-regressed, no poison (COMMS-01, COMMS-02)

**Wave 3** *(blocked on 22-03 + 22-04)*

- [ ] 22-05-PLAN.md (Wave 3) — Webhook delivery engine: V56 `webhook_delivery` (FORCE RLS) + `WebhookSigner` (HMAC-SHA256 t=,v1=) + versioned envelope + fanout listener + `@Scheduled` SKIP-LOCKED worker (backoff + auto-pause + no head-of-line block) + retention prune + delivery-log/replay API + config tunables (COMMS-05)

**Wave 4** *(blocked on 22-03 + 22-05)*

- [ ] 22-06-PLAN.md (Wave 4) — Webhook management + delivery-log UI (mobile-first 375px): subscriptions list + create/pause/resume/revoke + once-only secret reveal + rotate + delivery-log filter + Idempotency-Key replay + status-badge taxonomy + sidebar nav + Jest 375px coverage (COMMS-06)

**Wave 5** *(blocked on 22-02 + 22-06)*

- [ ] 22-07-PLAN.md (Wave 5) — Public unsubscribe page (noindex, sitemap-excluded, PII-safe) + Playwright E2E (webhook create→list→filter→replay→375px + unsubscribe flow) + `docs/metrics.json` reconcile (schema 51→56) + docs-freshness green + closure (COMMS-03, COMMS-06)

**UI hint**: yes (vendor webhook management + delivery-log/replay + public unsubscribe)

### Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav

**Goal**: A vendor group can scope staff to individual shops — a shop manager only touches their shop while RLS stays the tenant wall — and the dashboard nav (carrying the shop-context switcher) works on a phone. Incremental Betterment: every existing tenant user is backfilled to GROUP_ADMIN so day-one behaviour is identical.
**Depends on**: Nothing structural (RLS/onboarding untouched). Sequenced after Phase 22 per the locked order. Ships Flyway **V52** (must precede V53 in Phase 24).
**Requirements**: VSA-01, VSA-02, VSA-03, VSA-04, MOBL-01
**Success Criteria** (what must be TRUE):

  1. After migration, the tenant behaves exactly as today: every existing user has a GROUP_ADMIN `shop_staff` row and the realm `admin` role acts as implicit GROUP_ADMIN — zero day-one regression, proven under the NOSUPERUSER RLS role-downgrade. (VSA-01)
  2. A SHOP_MANAGER granted one shop can CRUD that shop's products/orders/marketing/KDS but receives a 403 (RFC 7807, distinct from the RLS 404) on any other shop in the tenant; STAFF is operational-read + order-state-only; shop-scoped writes without a grant are denied by default. (VSA-02)
  3. The dashboard carries a persisted shop-context switcher; all shop-scoped screens operate on the selected shop, and a group-wide "apply to all shops" action is visible only to GROUP_ADMIN. (VSA-03)
  4. A GROUP_ADMIN can list, grant, and revoke staff roles per shop from a staff-management screen; a grant immediately unlocks access and a revoke immediately produces a 403. (VSA-04)
  5. The dashboard sidebar no longer overlays content at 375px — the nav collapses to a drawer/bottom-nav that pairs with the shop switcher, verified by a 375px Jest/Playwright viewport spec. (MOBL-01)

**Plans**: TBD (est. 3)

Plans:

- [ ] 23-01: V52 `shop_staff` (+`_aud`, ENABLE+FORCE RLS, unique `(tenant_id, user_id, COALESCE(shop_id, zero-uuid))`) + GROUP_ADMIN backfill (idempotent) + realm-admin implicit GROUP_ADMIN + RLS-under-NOSUPERUSER proof
- [ ] 23-02: `ShopAccessService.require(shopId, minRole)` enforcement sweep across shop-scoped endpoints (shops/products/orders/KDS/marketing) — deny-by-default writes, 403 RFC 7807 vs RLS 404, tenant-aware membership cache, Testcontainers cross-shop 403 proofs (seed inventory from `qa/surface-ledger.json`)
- [ ] 23-03: Dashboard shop-context switcher (persisted) + GROUP_ADMIN-only "apply to all shops" + staff-management screen (list/grant/revoke) + responsive drawer/bottom-nav at 375px (MOBL-01) + Jest/Playwright

**UI hint**: yes

### Phase 24: Image Architecture — CoW Assets + Safe Upload Pipeline

**Goal**: Vendor image uploads are backed by a shared copy-on-write asset model with reference counting, and every upload passes through a safe async pipeline that stores only a validated, normalized derivative — never the raw bytes.
**Depends on**: Phase 23 (migration ordering — `media_asset` is **V53**, which must land after `shop_staff` V52). Reuses the V46 outbox/AMQP infra.
**Requirements**: IMG-01, IMG-02, IMG-03, IMG-04
**Success Criteria** (what must be TRUE):

  1. Products reference `media_asset` rows (never own bytes); editing a shared asset mints a new asset and repoints only that product (copy-on-write), and a physical MinIO delete happens only at reference-count 0 — with sha256 per-tenant dedup, proven under the NOSUPERUSER RLS role-downgrade; existing `image_url` values are backfilled behind a dual-read window. (IMG-01)
  2. An oversize upload is refused *before* it is buffered; a valid upload stores a raw quarantine object + a PENDING `media_asset` and returns immediately, then an async worker magic-byte-sniffs, enforces the jpeg/png/webp allowlist, decodes-to-verify, strips EXIF, and stores the normalized/re-encoded derivative (never the raw upload), pinning the tenant GUC before any DB write; single uploads and BulkImportService share the one path. (IMG-02)
  3. A file that fails normalization/decode/allowlist is marked FAILED and rejected with a vendor-visible reason; an image below the content-relevance threshold still goes ACTIVE but lands in a vendor-visible review queue; the vision stage sits behind a flag defaulting to advisory until the provider is reliably up. (IMG-03)
  4. The product UI shows a "processing" state while an asset is PENDING and surfaces FAILED (with reason) and content-flagged (ACTIVE) assets in a vendor-visible review/rejection queue. (IMG-04)

**Plans**: TBD (est. 3)

Plans:

- [ ] 24-01: V53 `media_asset` (+`_aud` if audited, ENABLE+FORCE RLS, sha256 tenant-unique) + product↔asset reference (FK/join) + copy-on-write repoint + reference-counted physical delete + `image_url` backfill dual-read + RLS-under-NOSUPERUSER proof
- [ ] 24-02: Safe async upload pipeline — reject-early Content-Length/streaming size guard + quarantine store + PENDING row + AMQP outbox publish (202-style) + queue worker (magic-byte sniff, decode-verify, EXIF strip, normalize/resize/re-encode/thumbnail, raw-delete-on-success, tenant-GUC pin) + BulkImportService unification
- [ ] 24-03: Gate strictness (compress-fail → FAILED reject; low-relevance → ACTIVE + review queue; vision advisory flag) + product UI processing/failed/flagged states + vendor review/rejection queue + `docs/metrics.json` reconcile

**UI hint**: yes

### Phase 25: Mutating MCP Tools

**Goal**: An external AI agent holding a tenant-scoped credential can safely create orders/customers through MCP write tools that ride the uniform Idempotency-Key contract, with RLS as the boundary — extending the Phase 20 read-only server.
**Depends on**: Phase 20 read-only MCP server + #204 Idempotency-Key contract (V50) + #206 scoped machine credentials (all shipped v2.2). Structurally independent of other v2.3 phases.
**Requirements**: AI-02
**Success Criteria** (what must be TRUE):

  1. The MCP server exposes write tools (e.g. `orders.create`, `customers.create`) mapped to the appropriate write scopes; each rides the uniform Idempotency-Key contract so a replayed call returns the original result, not a duplicate. (AI-02)
  2. A write attempt targeting another tenant returns empty/403 under the MCP credential — RLS-proven, test included. (AI-02)
  3. Tool errors surface as RFC 7807 problem-detail (consistent with the read-only slice), not raw stack traces, and the flow is proven live against the dev stack. (AI-02)

**Plans**: TBD (est. 2)

Plans:

- [ ] 25-01: MCP write tools (`orders.create` / `customers.create`) over the core REST API + `Idempotency-Key` header wiring (#204) + write-scope mapping
- [ ] 25-02: cross-tenant RLS proof under the MCP credential + RFC 7807 tool errors + idempotent-replay integration test + live E2E + `docs/metrics.json` reconcile

**UI hint**: no

### Phase 26: Local-K8s Overlay + Verified Breakage Fixes

**Goal**: The imperative deploy patches from the 2026-07-14 live-deploy rehearsal are replaced by a committed, buildable `k8s/local` overlay, and the verified k8s breakage list is fixed so core boots as the NOSUPERUSER app role on a single replica.
**Depends on**: Nothing structural (infra/deploy config). Best sequenced last so the overlay ships all v2.3 schema (V52 `shop_staff`, V53 `media_asset`, Comms migrations) and services. `compose XOR k8s` on local (RULE 0) still applies.
**Requirements**: INFRA-01, INFRA-02
**Success Criteria** (what must be TRUE):

  1. `kubectl kustomize k8s/local` builds and a server dry-run apply resolves every reference — no dangling secret/configmap/label refs. (INFRA-01)
  2. The `k8s/local` overlay shims endpoints to `host.minikube.internal`, sets `minReplicas=1`, and repoints the backup CronJob to host MinIO — committed, replacing the imperative secret/configmap patches. (INFRA-01)
  3. `DB_PORT` is injected via `valueFrom.secretKeyRef` (no hardcoded `5432`), and secrets use `DB_USER`/`DB_PASSWORD` (the `jtoye_app` NOSUPERUSER role) so core boots without `DatabaseConfigurationValidator` refusing a DB superuser. (INFRA-02)
  4. The pg-backup CronJob targets host MinIO and the STOMP relay stomp-login/passcode wiring reaches the spring config (no boot-time `Access refused for user 'guest'`). (INFRA-02)

**Plans**: TBD (est. 2)

Plans:

- [ ] 26-01: Committed `k8s/local` overlay (host.minikube.internal endpoint shims, `minReplicas=1`, backup→MinIO) replacing imperative patches + `kubectl kustomize` build + server dry-run
- [ ] 26-02: Verified breakage fixes — `DB_PORT` via `secretKeyRef`, `DB_USER`/`DB_PASSWORD` NOSUPERUSER role, pg-backup→host MinIO, STOMP relay login wiring + config-injection (no-hardcoded-port) assertion + boot-as-app-role smoke

**UI hint**: no

## Progress

**Execution Order:**
Phases run in the user-locked, thinnest/highest-pain-first order: **21 → 22 → 23 → 24 → 25 → 26**. Phase 21 is independent (zero migrations) and led because it was the cheapest fix for the highest user pain. **Phase 22 (Notifications & Comms) was inserted ahead of Vendor-Scoped Access** because a delivery consumer of the V46 outbox is the seam onboarding (ONBD-05) and future surfaces depend on, and it folds in the previously-standalone Outbound Webhooks (#205) + WhatsApp (#208). The one hard migration dependency is Phase 23 before Phase 24 (Flyway V52 `shop_staff` must precede V53 `media_asset`); Comms migrations take V54/V55/V56 under `out-of-order=true` so that ordering is undisturbed. Phase 25 (mutating MCP) builds only on shipped infra (#204 idempotency, Phase 20 MCP) and is structurally independent. Phase 26 (infra) lands last so the committed overlay ships all v2.3 schema and services.

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 21. Onboarding Blocker UX | v2.3 | 5/5 | Complete    | 2026-07-14 |
| 22. Notifications & Comms | v2.3 | 4/7 | In Progress|  |
| 23. Vendor-Scoped Access + Responsive Dashboard Nav | v2.3 | 0/3 | Not started | - |
| 24. Image Architecture — CoW Assets + Safe Upload Pipeline | v2.3 | 0/3 | Not started | - |
| 25. Mutating MCP Tools | v2.3 | 0/2 | Not started | - |
| 26. Local-K8s Overlay + Verified Breakage Fixes | v2.3 | 0/2 | Not started | - |

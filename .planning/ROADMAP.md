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
- [x] **Phase 22: Notifications & Comms** — First delivery consumer of the V46 transactional outbox: email-first transactional notifications (Mailhog dev → SES prod) + vendor-registered outbound webhooks (HMAC-signed, retried; absorbs #205) + a WhatsApp/SMS seam behind a provider flag (absorbs #208), with GDPR consent + unsubscribe (completed 2026-07-15)
- [x] **Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav** — `shop_staff` (V52) + app-layer role gate + shop-context switcher + staff management, with a GROUP_ADMIN backfill; dashboard nav no longer overlays at 375px (code-complete 2026-07-20; **gap-closure wave 23-08..23-17 CLOSED 2026-07-21** — the 3 confirmed authZ bypasses fixed [CR-01 cache-bypass 23-10, CR-02 STOMP gate 23-11, CR-03/CR-04 fail-closed 23-08] + CR-05..CR-08 staff/frontend + CR-07 strict-scoping 23-14 + the V57 grant_source-backfill deploy blocker fixed [23-17: bare no-GUC UPDATE → V44 per-tenant `set_config` loop, so `SET NOT NULL` no longer bricks boot on a non-fresh DB]; VSA-02/VSA-04 Complete with named green proofs over a green full `integrationTest` (332/0); both known-red CI gates green [OpenAPI snapshot + docs-freshness]. Checkbox stays open pending final `/gsd:secure-phase 23` + `/gsd:verify-work` sign-off; live vendor-auth Playwright deferred to the phase PR) (completed 2026-07-22)
- [x] **Phase 24: Image Architecture — CoW Assets + Safe Upload Pipeline** — `media_asset` (V53) copy-on-write + reference counting + safe async RabbitMQ upload/normalize pipeline storing only the validated derivative (completed 2026-07-23)
- [x] **Phase 25: Mutating MCP Tools** — Write tools on the Phase 20 MCP server riding the uniform Idempotency-Key contract, RLS-proven under the MCP credential (completed 2026-07-24)
- [x] **Phase 26: Local-K8s Overlay + Verified Breakage Fixes** — Committed `k8s/local` overlay replacing imperative patches + the verified deploy breakage list fixed, proven on a live minikube rehearsal (verbatim server dry-run, 3/3 rollout, NOSUPERUSER boot corroborated from the database side, two-arm backup falsification, broker-side STOMP identity, real Keycloak login through the ingress). The rehearsal also **falsified** the KDS relay path — a RabbitMQ `/topic` destination cannot contain `/`, and `k8s/base` sets `relay`, so staging and production both inherit it: a confirmed production defect found only because D-06 insisted the relay be proven on a cluster rather than in compose (tracked as [#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266), fixed in its own scoped work — **#266 CLOSED 2026-07-26 by PR #269, `d964a85`: the destination is now a single dot-separated segment built in one place. The live L6 proof — a KDS client receiving a relayed event — is still uncaptured, so this is a fixed defect plus an open evidence gap, not a proven realtime path**) (completed 2026-07-26)

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

- [x] 22-05-PLAN.md (Wave 3) — Webhook delivery engine: V56 `webhook_delivery` (FORCE RLS) + `WebhookSigner` (HMAC-SHA256 t=,v1=) + versioned envelope + fanout listener + `@Scheduled` SKIP-LOCKED worker (backoff + auto-pause + no head-of-line block) + retention prune + delivery-log/replay API + config tunables (COMMS-05)

**Wave 4** *(blocked on 22-03 + 22-05)*

- [x] 22-06-PLAN.md (Wave 4) — Webhook management + delivery-log UI (mobile-first 375px): subscriptions list + create/pause/resume/revoke + once-only secret reveal + rotate + delivery-log filter + Idempotency-Key replay + status-badge taxonomy + sidebar nav + Jest 375px coverage (COMMS-06)

**Wave 5** *(blocked on 22-02 + 22-06)*

- [x] 22-07-PLAN.md (Wave 5) — Public unsubscribe page (noindex, sitemap-excluded, PII-safe) + Playwright E2E (webhook create→list→filter→replay→375px + unsubscribe flow) + `docs/metrics.json` reconcile (schema 51→56) + docs-freshness green + closure (COMMS-03, COMMS-06)

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

**Plans**: 15 plans (6 waves shipped + 5 gap-closure waves)

Plans:
**Wave 1**

- [x] 23-01-PLAN.md (Wave 1) — V52 schema: `shop_staff` (+`_aud`, ENABLE+FORCE RLS via `current_tenant_id()`, functional unique `(tenant_id, user_id, COALESCE(shop_id, zero-uuid))`) + login-populated `user_directory` (D-09, no `_aud`) + entities/repos (native race-safe JIT-insert + throttled directory upsert) + `ShopStaffRlsPolicyIntegrationTest` (cross-tenant + PII under NOSUPERUSER). No migrate-time backfill (JIT is 23-02). [VSA-01]

**Wave 2** *(blocked on 23-01)*

- [x] 23-02-PLAN.md (Wave 2) — `ShopAccessService` core: per-user membership cache + realm-admin⇒implicit-GROUP_ADMIN bridge + D-04 JIT lazy-provision + D-09 throttled directory upsert + D-12 strict-scoping switch (default OFF) + typed `ShopAccessDeniedException` (distinct 403) / `LastGroupAdminException` (409) + JIT-idempotency + 403≠404 tests. [VSA-01, VSA-02]

**Wave 3** *(both blocked on 23-02; parallel — no file overlap)*

- [x] 23-03-PLAN.md (Wave 3) — Enforcement sweep (RESEARCH §3 inventory): `require(shopId, minRole)` + read-scoping across Shop/Product/Order/Promotion/Announcement services; §3-FLAG mitigations (bulk-import per-row, KDS SSE fan-out grant-set filter); `ShopAccessEnforcementIntegrationTest`. [VSA-02]
- [x] 23-04-PLAN.md (Wave 3, parallel) — Staff-management backend: GROUP_ADMIN-gated `/api/v1/staff` list/grant/revoke + last-GROUP_ADMIN 409 guard (D-11) + evict-on-write (D-05) + `StaffManagementIntegrationTest`. [VSA-04]

**Wave 4** *(blocked on 23-03)*

- [x] 23-05-PLAN.md (Wave 4) — Frontend switcher (VSA-03): persisted shop-context dropdown (localStorage, GA⇒All-shops, apply-to-all GA-only) mounted in sidebar header + mobile top bar; MOBL-01 verify-first (375px Jest/Playwright regression + surface-ledger proof — no new drawer); D-13 stale-selection access-required. [VSA-03, MOBL-01]

**Wave 5** *(blocked on 23-05)*

- [x] 23-07-PLAN.md (Wave 5) — Shop-context wiring (VSA-03 consumption): a `useShopContext` hook over `getShopContext()`/`subscribeShopContext` (23-05) threaded into Products/Orders/Marketing/Kitchen — list narrowing (Orders `?shopId=` server param, others client-side over the 23-03 grant-scoped result) + create-form default/constrain to the selected shop (D-08) + Kitchen local-selector reconcile; Jest behaviour proofs. Closes VSA-03's “all shop-scoped screens operate on the selected shop” clause. [VSA-03]

**Wave 6** *(blocked on 23-04 + 23-05 + 23-07)*

- [x] 23-06-PLAN.md (Wave 6) — Staff-management screen (VSA-04): `/dashboard/staff` list/grant/revoke (403→access-required, 409→clear msg) + GROUP_ADMIN-only Staff nav item (D-10) + phase-gate `docs/metrics.json` + CLAUDE.md count reconcile (all of 23-01..23-07). [VSA-04, MOBL-01]

**Gap closure** *(`/gsd:execute-phase 23 --gaps-only`; VSA-01/03 + MOBL-01 already PASS and are NOT re-planned)*

Source: `23-VERIFICATION.md` (status `gaps_found` — 3 confirmed authorization bypasses) + `23-REVIEW.md` (8 Critical / 12 Warning / 3 Info). Waves below are scoped to the gap-closure run.

**Gap Wave 1**

- [x] 23-08-PLAN.md (Gap Wave 1) — CR-03 fail-closed system principal (a non-UUID-subject JWT was an unrestricted GROUP_ADMIN on `/api/v1/staff`) via an explicit empty-by-default machine-client allowlist, + CR-04 `require(null, role)` NPE→typed 403 and the null-shop write policy. [VSA-02, VSA-04]
- [x] 23-09-PLAN.md (Gap Wave 1, parallel) — CR-05 role changes silently no-op while reporting success (grant reshaped to an audited session-based write, closing WR-02) + CR-06 last-GROUP_ADMIN check-then-act race (`PESSIMISTIC_WRITE` lock) + IN-03. [VSA-04] (VSA-04 stays PENDING — 23-12/23-13/23-14/23-15 still contribute)

**Gap Wave 2** *(23-10/23-11 blocked on 23-08; 23-12 on 23-09; all three parallel — no file overlap)*

- [x] 23-10-PLAN.md (Gap Wave 2) — CR-01 `@Cacheable` short-circuits the shop gate on a warm cache, proven by a caching-ENABLED two-scoped-user test that defeats the `@Profile("!test")` blindness; + WR-08 null-shop read policy and WR-07 malformed-CSV 403→400. [VSA-02] — ✓ gate moved onto dedicated cached-loader beans (require() runs on every call, cache key + evictions unchanged); RED demonstrated pre-fix on the two-user cache cases; 4/4 cache-bypass + 6/6 enforcement + full :core-java:test green. VSA-02 stays PENDING (23-11 KDS transport still contributes).
- [x] 23-11-PLAN.md (Gap Wave 2, parallel) — CR-02 the real KDS transport (STOMP `/topic/kitchen/{tid}/{shopId}`) is not shop-gated; explicit-identity grant check at SUBSCRIBE, with the day-one ungranted user preserved. [VSA-02]
- [x] 23-12-PLAN.md (Gap Wave 2, parallel) — WR-05 grant validates neither shop tenancy nor user existence + `GET /api/v1/staff/me` (CR-08 backend half) + WR-10 `user_directory` PII masking and GDPR erasure coverage. [VSA-04]

**Gap Wave 3** *(blocked on 23-12)*

- [x] 23-13-PLAN.md (Gap Wave 3) — CR-08 frontend GROUP_ADMIN detection disagrees with the backend model, silently pinning every non-realm-admin to one shop; server-sourced via `/me`, + WR-06 divergent switcher instances, WR-12 sub-based identity, IN-02 copy. [VSA-03, VSA-04]

**Gap Wave 4** *(blocked on 23-08 + 23-09 + 23-11 + 23-13)*

- [x] 23-14-PLAN.md (Gap Wave 4) — **CR-07 design correction, revises locked D-04/D-12/D-05** (blocking decision checkpoint): enabling `strict-scoping` currently tightens nothing because JIT already wrote permanent GROUP_ADMIN rows for everyone. V57 grant provenance + strict-ON de-honours JIT rows with a deterministic bootstrap admin; + WR-09 machine accounts, WR-01/WR-11 membership cache made real with proven post-commit eviction. [VSA-02, VSA-04]

**Gap Wave 5** *(blocked on all)*

- [x] 23-15-PLAN.md (Gap Wave 5) — Phase gate: OpenAPI snapshot regen (`adc1c58`, 4 staff endpoints incl. /me) + `docs-freshness --write` reconcile (1511→1573 / schema 56→57, CLAUDE.md+AGENTS.md, EXIT 0) over a green suite (integrationTest 331/0, jest 360/360) + 23-VALIDATION/REQUIREMENTS/23-CONTEXT/deferred-items reconcile; VSA-02+VSA-04 → Complete. Both known-red CI gates now green. (completed 2026-07-21) [VSA-02, VSA-04]
- [x] 23-16-PLAN.md (Gap Wave 5, test-only) — migrated 7 legacy integration classes from `@WithMockUser` / non-UUID `.jwt()` to the production UUID-subject JWT auth shape, turning the full `:core-java:integrationTest` from 13 failures (23-08's fail-closed `requireVendorUserId()` denials) to **331/0** with zero main-source change; the fail-closed boundary is preserved, not relaxed. (completed 2026-07-21)
- [x] 23-17-PLAN.md (Gap Wave 6, code-review blocker) — **CONFIRMED V57 deployment blocker fixed**: the grant_source backfill (shipped by 23-14) was a bare no-GUC `UPDATE` invisible under the FORCE-RLS shop_staff table to the RLS-bound migration role (jtoye_app) → 0 rows → `SET NOT NULL` bricks boot on any non-fresh DB. Rewritten as V44's per-tenant `set_config` loop; steps 1/3/4 unchanged, no RLS policy touched (RlsContractTest green). New `V57GrantSourceBackfillIntegrationTest` proves it on a non-fresh two-tenant DB under a NOSUPERUSER `rls_migrator` role (RED against the bare UPDATE — SQLSTATE 23502 at SET NOT NULL; GREEN after). Full `integrationTest` **332/0**. (completed 2026-07-21) [VSA-02]

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

**Plans**: 6 plans (5 waves)

Plans:
**Wave 1** *(parallel foundations — no shared-file overlap)*

- [x] 24-01-PLAN.md (Wave 1) — WebP toolchain + **musl-cwebp Wave-0 smoke spike (A1, the phase's #1 risk — gates the library choice; fallback = glibc base image)** + `MediaNormalizer` (magic-byte sniff + decompression-bomb header guard + decode-verify + Scrimage/cwebp WebP encode + thumbnail) + `jtoye.media.*` config budget + multipart reject-early config [IMG-02]
- [x] 24-02-PLAN.md (Wave 1, parallel) — V53 `media_asset` (+`_aud`) + `product_media` join (D-01) + ENABLE+FORCE RLS via `current_tenant_id()` + sha256 tenant-unique dedup + copy-on-write repoint + reference-counted physical delete + per-tenant `set_config` backfill loop (D-03b) + asset-first dual-read resolver + key-addressed `StorageService` helpers + RLS-under-NOSUPERUSER / CoW / non-fresh-DB-backfill proofs [IMG-01]

**Wave 2** *(blocked on 24-01 + 24-02)*

- [x] 24-03-PLAN.md (Wave 2) — Reject-early accept side: Content-Length 413 (RFC 7807) before buffering + quarantine store + PENDING row + Idempotency-Key contract (D-06) + 202 accept + **dedicated `media_event_outbox` (V58) + `media.events` topology** (sidesteps `outbox_flusher_dispatch_trap`) [IMG-02]

**Wave 3** *(blocked on 24-01 + 24-02 + 24-03)*

- [x] 24-04-PLAN.md (Wave 3) — Async worker: `@RabbitListener` GUC-pinned pipeline (sniff → bomb-guard → decode-verify → EXIF-strip → normalize → store WebP derivative only → delete raw → ACTIVE/FAILED) + gate strictness (compress-fail → FAILED; low-relevance → ACTIVE+flagged review queue; vision advisory flag) + CoW-safety (failed replacement never clobbers, D-04a) + PENDING reaper + BulkImportService one-path unification [IMG-02, IMG-03]

**Wave 4** *(blocked on 24-02 + 24-04)*

- [x] 24-05-PLAN.md (Wave 4) — Review-queue backend: tenant-scoped GET review-queue (FAILED + flagged) + Keep (dismiss-flag) + `MediaAssetDto`/`MediaAssetStatus` on the product DTO (dual-read retained) [IMG-03, IMG-04]

**Wave 5** *(blocked on 24-05)*

- [x] 24-06-PLAN.md (Wave 5) — Frontend (IMG-04): status-aware `AssetImage` (processing/active-webp/failed/flagged, alt+dimensions preserved) + review/rejection queue screen (Keep/Replace, 375px) + uploader 202-accept handling + phase-gate reconcile (`docs/metrics.json` schema→58, docs-freshness green, OpenAPI snapshot regen, CLAUDE/AGENTS counts) [IMG-04]

**UI hint**: yes

### Phase 25: Mutating MCP Tools

**Goal**: An external AI agent holding a tenant-scoped credential can safely create orders/customers through MCP write tools that ride the uniform Idempotency-Key contract, with RLS as the boundary — extending the Phase 20 read-only server.
**Depends on**: Phase 20 read-only MCP server + #204 Idempotency-Key contract (V50) + #206 scoped machine credentials (all shipped v2.2). Structurally independent of other v2.3 phases.
**Requirements**: AI-02
**Success Criteria** (what must be TRUE):

  1. The MCP server exposes write tools (e.g. `orders.create`, `customers.create`) mapped to the appropriate write scopes; each rides the uniform Idempotency-Key contract so a replayed call returns the original result, not a duplicate. (AI-02)
  2. A write attempt targeting another tenant returns empty/403 under the MCP credential — RLS-proven, test included. (AI-02)
  3. Tool errors surface as RFC 7807 problem-detail (consistent with the read-only slice), not raw stack traces, and the flow is proven live against the dev stack. (AI-02)

**Plans**: 4 plans (3 waves)

Plans:

**Wave 1**

- [x] 25-01-PLAN.md (Wave 1) — Core write-scope gates + CI proof: `@PreAuthorize("SCOPE_orders:write")` on `POST /orders` (D-01) + new `@PreAuthorize("SCOPE_customers:write")` on `POST /customers` (D-02) + `OpenApiConfig` taxonomy update + `ScopedWriteAccessIntegrationTest` (converter-through-MockMvc, 403/not-403, valid bodies)
- [x] 25-02-PLAN.md (Wave 1, parallel) — Realm RW credential + secret/config wiring: template-seeded `integration-orders-rw` client (both mappers, `orders:write`+`customers:write`+`catalog:read`, no `catalog:write`, D-09/D-10) + `customers:read/write` scopes + `core-api` default-grant (D-03) + `INTEGRATION_ORDERS_RW_SECRET` across 6 sites (D-11) + `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` (VSA-02 mitigation)

**Wave 2** *(blocked on 25-01)*

- [x] 25-03-PLAN.md (Wave 2) — MCP write tools + proofs: `corePost` sibling + `create_order`/`create_customer` (required `idempotencyKey`→header, D-05/D-07) registered in `buildServer` + vitest (header/body split, PII-never-logged, `toToolError`) + cross-tenant `create_order` RLS proof under the NOSUPERUSER `rls_test_role` (foreign `shopId`→404)

**Wave 3** *(blocked on 25-01/25-02/25-03)*

- [x] 25-04-PLAN.md (Wave 3) — Phase-gate closer: `docs/metrics.json` reconcile via `docs-freshness.sh --write` (total 1648→1675, EXIT 0) + write-surface docs (`security-scopes.md`/`README.md`/`idempotency.md`) + OpenAPI snapshot regenerated GREEN + **human-approved live E2E** (rebuild ALL + `kc.sh import --override true` → create 200 / idempotent-replay-no-dup / cross-tenant 404-RLS / no-scope 403 + no-rogue-`shop_staff`-row, D-12). Rule 1/3 fix: RW-client description trimmed <=255 (kc.sh import 22P01). AI-02 → COMPLETE

**UI hint**: no

### Phase 26: Local-K8s Overlay + Verified Breakage Fixes

**Goal**: The imperative deploy patches from the 2026-07-14 live-deploy rehearsal are replaced by a committed, buildable `k8s/local` overlay, and the verified k8s breakage list is fixed so core boots as the NOSUPERUSER app role on a single replica.
**Depends on**: Nothing structural (infra/deploy config). Best sequenced last so the overlay ships all v2.3 schema (V52 `shop_staff`, V53 `media_asset`, Comms migrations) and services. `compose XOR k8s` on local (RULE 0) still applies.
**Requirements**: INFRA-01, INFRA-02
**Success Criteria** (what must be TRUE):

  1. `kubectl kustomize k8s/local` builds and a server dry-run apply resolves every reference — no dangling secret/configmap/label refs. (INFRA-01) — **MET.** `check-no-plaintext-secrets.sh` exit 0 (`[k8s/local]: build succeeded, 23 resources`) + `k8s/LOCAL.md` §11 **L1**: verbatim server dry-run, exit 0, 23 objects, 0 `denied the request` across 8 run logs.
  2. The `k8s/local` overlay shims endpoints to `host.minikube.internal`, sets `minReplicas=1`, and repoints the backup CronJob to host MinIO — committed, replacing the imperative secret/configmap patches. (INFRA-01) — **MET.** `check-render-invariants.sh` exit 0 asserts LOC-1..LOC-6 on the render: 8 endpoint shims, the D-09 scale triple with `maxReplicas` byte-identical to base (10/20/10), and `s3.backup.endpoint: http://host.minikube.internal:9000`. `render-golden.sh` exit 0 proves staging + production renders were not disturbed (1469 lines each).
  3. `DB_PORT` is injected via `valueFrom.secretKeyRef` (no hardcoded `5432`), and secrets use `DB_USER`/`DB_PASSWORD` (the `jtoye_app` NOSUPERUSER role) so core boots without `DatabaseConfigurationValidator` refusing a DB superuser. (INFRA-02) — **MET.** §11 **L2b**: live env `secretKeyRef present : 1`, `"value" field present : 0`, decoded port 5433 (and the pod is genuinely connected on 5433, so a stale 5432 would have connected to nothing). §11 **L2**: validator counts 1/1/0 with `Database username: jtoye_app`, corroborated independently from the database side by `rolsuper = f`.
  4. The pg-backup CronJob targets host MinIO and the STOMP relay stomp-login/passcode wiring reaches the spring config (no boot-time `Access refused for user 'guest'`). (INFRA-02) — **MET AS WRITTEN, with one scope statement that must not be lost.** CronJob: §11 **L3** `.status.succeeded = 1`, uploaded to host MinIO via `http://host.minikube.internal:9000`, falsified two-arm in **L4** (arm A products 0 / arm B products 47). STOMP wiring: §11 **L5** `grep -c "Access refused for user"` = **0**, plus the broker-side identity — 1 STOMP connection with `auth_login = jtoye`, `guest` = **0**, with a non-vacuity control and a fixture proving the guest predicate can fire. **NOT met, and never claimed by this criterion: the KDS relay does not actually deliver.** The stronger D-06 row — a KDS client receiving a relayed event — is FALSIFIED (§11 **L6**: 14 SUBSCRIBE / 14 `Invalid destination` / **0 MESSAGE**), because a RabbitMQ `/topic` destination may not contain `/`. `k8s/base/configmap.yaml:36` sets `relay` with no staging/production override, so both inherit it. Tracked as **[#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266)** (`bug`/`P1`); see `k8s/LOCAL.md` §7 A3. **Status 2026-07-26: #266 is CLOSED — PR #269 (`d964a85`) made the destination a single dot-separated segment (`StompDestinations`), re-parsed the tenant wall and re-ran its cross-tenant denial. This criterion is unaffected either way: it was MET AS WRITTEN before, and the *functional* row it explicitly never claimed is still not proven — L6 (a KDS client receiving a relayed order event through a real broker) has never been captured and needs a cluster. The remaining item is an evidence gap, not a defect; INFRA-02(d) stays closed on credential wiring only. A fix is not a proof.**

**Plans**: 9 plans (9 waves)

Plans:

**Wave 1**

- [x] 26-01-PLAN.md (Wave 1) — Golden-render baseline harness + the three surgical base fixes with verified mechanics: `DB_PORT` → `secretKeyRef` (DEF-1), `RABBITMQ_USERNAME` → `RABBITMQ_USER` (DEF-4 deploy half), the kustomize `labels` `fields:` fix that un-poisons the kube-dns NetworkPolicy selector (D-17), plus the additive `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` chain (D-05) with a three-case resolution test; the golden harness also ships `--snapshot`/`--diff-since` (fail-closed on a missing baseline) and the rename carries a recorded pre-rollout operator confirmation of the live `rabbitmq-credentials/username` value

**Wave 2** *(blocked on Wave 1)*

- [x] 26-02-PLAN.md (Wave 2) — DEF-6 / D-15 base config-drift closure: 19 new `app-config` keys (media storage, SMTP, CORS, JWT audience, split-horizon issuer D-13, the four `localhost:3000` notification + Stripe Connect URLs D-19, log path, webhook knobs) with prod-identical or repo-derived values; four `optional: true` Secrets; frontend `KEYCLOAK_ISSUER_INTERNAL` + the dead `NEXT_PUBLIC_API_URL` injection removed (D-18); edge-go `JWT_EXPECTED_ISSUER`; DEF-2 `jtoye_app` in the recipe + template

**Wave 3** *(blocked on Wave 2)*

- [x] 26-03-PLAN.md (Wave 3) — Recurrence prevention: `check-env-contract.sh` (two-direction core-java env contract + local-only-default rule + reasoned allowlist, D-07/D-08) and `check-render-invariants.sh` (INV-1..INV-5: no hardcoded 5432, `DB_PORT` exactly-one-of `value`/`valueFrom`, kube-dns selector purity, no-localhost renders, DEF-2 docs), both wired into the `k8s-validate` CI job alongside the golden-render check

**Wave 4** *(blocked on Wave 3)*

- [x] 26-04-PLAN.md (Wave 4) — The committed `k8s/local` overlay (INFRA-01): namespace, eight `host.minikube.internal` endpoint shims, the D-09 scale triple with `maxReplicas` untouched, backup → host MinIO, ingress-nginx-v1.12.2-admissible Ingress patches (PIT-1 snippet + PIT-10 rate limits nulled, TLS removed), prod profile retained (D-10), NetworkPolicies rendered-not-enforced (D-11); plus the `k8s/base` fix removing the dangling `auth.jtoye.co.uk` -> `keycloak` rule and its TLS SAN (a host published with no backend in any render), and LOC-1..LOC-5 + the all-target INV-6 dangling-backend render assertions

**Wave 5** *(blocked on Wave 4)*

- [x] 26-05-PLAN.md (Wave 5) — Bootstrap tooling: `scripts/lib/k8s-local-guards.sh` (refuse-unless-local-context + compose-XOR guard, D-04), `scripts/k8s-local-secrets.sh` (idempotent secrets + BYPASSRLS `jtoye_backup` role + non-public backup bucket, D-01/D-02), 15 new `K8S_LOCAL_*`/`DB_BACKUP_PASSWORD` `.env` keys (D-03), `scripts/k8s-local-up.sh` as the single bring-up entry point with correctly-baked local images (D-14/D-18), `scripts/deploy.sh` phantom-`dev` fix, Playwright cookie-domain parameterisation

**Wave 6** *(blocked on Wave 5)*

- [x] 26-06-PLAN.md (Wave 6) — `k8s/LOCAL.md` runbook + rehearsal-evidence template (what local does and does NOT prove: no TLS/HSTS, no security-header snippet, no NetworkPolicy enforcement with the PIT-7 CIDRs written out), deploy-doc cross-links, an appended dated note on the signed readiness audit, the two-arm backup falsification recipe, and the single `docs/metrics.json` reconcile + CLAUDE.md/AGENTS.md prose sync

**Wave 7** *(blocked on Wave 6 — human-gated)*

- [x] 26-07-PLAN.md (Wave 7) — Live rehearsal, part 1 (D-16): human prerequisites checkpoint (compose app shutdown, `/etc/hosts`), verbatim server dry-run, 3/3 READY rollout, DEF-1/DEF-2/DEF-4 boot proofs with DEF-2 corroborated independently from the database side, and the pg-backup CronJob run with the two-arm non-empty falsification (app-role dump → `products = 0`, backup-role dump → `products > 0`)

**Wave 8** *(blocked on Wave 7 — human-gated)*

- [x] 26-08-PLAN.md (Wave 8) — Live rehearsal, part 2 (DEF-5 + D-06): the two planning-discovered login blockers fixed (additive `app.jtoye.local` realm redirect URI; `KEYCLOAK_CLIENT_ID` config-injected instead of a hardcoded literal absent from the dev realm), broker-side STOMP identity proof (dedicated login, zero `guest` connections), then the human-verified journey — real Keycloak vendor login through the ingress to a dashboard, and a kitchen display receiving a relayed order event

**Wave 9** *(blocked on Wave 8 — ends with a human-gated end-state decision)*

- [x] 26-09-PLAN.md (Wave 9) — Phase-gate closure: full `:core-java:test` (104 classes / 767 tests / 0 fail) + `:core-java:integrationTest` (98 classes / 392 tests / 0 fail, 40m) + frontend build/jest (59 suites / 377 tests) regression sweep, evidence-block completeness audit (7 live rows required, 7 filled; two corrections made — a wrong `arm B` figure in the sign-off and an unsatisfiable literal-value secret sweep replaced with a falsifiable credential-shape form), then INFRA-01 / INFRA-02 marked complete with per-sub-item cited proofs and the falsified D-06 relay row scoped out to issue #266, ROADMAP / STATE / 26-VALIDATION reconciled, and a human-gated end-state decision restoring the canonical compose app containers (cluster stopped first, XOR guard refusing again as the proof)
**UI hint**: no

## Progress

**Execution Order:**
Phases run in the user-locked, thinnest/highest-pain-first order: **21 → 22 → 23 → 24 → 25 → 26**. Phase 21 is independent (zero migrations) and led because it was the cheapest fix for the highest user pain. **Phase 22 (Notifications & Comms) was inserted ahead of Vendor-Scoped Access** because a delivery consumer of the V46 outbox is the seam onboarding (ONBD-05) and future surfaces depend on, and it folds in the previously-standalone Outbound Webhooks (#205) + WhatsApp (#208). The one hard migration dependency is Phase 23 before Phase 24 (Flyway V52 `shop_staff` must precede V53 `media_asset`); Comms migrations take V54/V55/V56 under `out-of-order=true` so that ordering is undisturbed. Phase 25 (mutating MCP) builds only on shipped infra (#204 idempotency, Phase 20 MCP) and is structurally independent. Phase 26 (infra) lands last so the committed overlay ships all v2.3 schema and services.

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 21. Onboarding Blocker UX | v2.3 | 5/5 | Complete    | 2026-07-14 |
| 22. Notifications & Comms | v2.3 | 7/7 | Complete    | 2026-07-15 |
| 23. Vendor-Scoped Access + Responsive Dashboard Nav | v2.3 | 17/17 | Complete    | 2026-07-22 |
| 24. Image Architecture — CoW Assets + Safe Upload Pipeline | v2.3 | 6/6 | Complete    | 2026-07-23 |
| 25. Mutating MCP Tools | v2.3 | 4/4 | Complete    | 2026-07-24 |
| 26. Local-K8s Overlay + Verified Breakage Fixes | v2.3 | 9/9 | Complete    | 2026-07-26 |

### Phase 27: Operational Maturity — messaging as the first instance

**Goal**: Every terminal failure state in the system has a detection path a human is actually told about, every pinned dependency has a support horizon that fails before it lapses, and the capacity claims have a measured baseline. Messaging is the first instance of each — not the scope. Comes out of the ADR-0003 investigation, which found the architecture sound for correctness and unsound for failure visibility and lifecycle.
**Depends on**: Nothing structural. Best sequenced **before** `/qa-council`, which would otherwise audit a runtime whose monitoring is blind (11 of 14 alerts defective at time of planning).
**Requirements**: OPS-01, OPS-02, OPS-03, OPS-04, OPS-05
**Plans:** 7/7 plans complete

| Req | What must become true | Plans |
|---|---|---|
| **OPS-01** | Terminal failure states are declared, detected, and routed to a human — no DLQ, poison row or FAILED asset is silently unowned. Alert rules must be proven capable of firing against live series, not merely `promtool`-valid. | 27-00, 27-03, 27-06 |
| **OPS-02** | Every pinned runtime dependency carries a support horizon that fails the build before it lapses; RabbitMQ moves to a supported 4.x series with a proven rollback. | 27-00, 27-02, 27-06 |
| **OPS-03** | Consumer concurrency and prefetch derive from a measured baseline rather than defaults, and the load harness asserts status codes so a 401 flood cannot read as throughput. | 27-00, 27-04 |
| **OPS-04** | In-flight work survives infrastructure failure: a broker outage no longer destroys uploads, and a broker rebuild no longer orphans undelivered messages. | 27-01, 27-02 |
| **OPS-05** | Outbound webhooks actually deliver. Fixes an outage in which 100% of webhook events have dead-lettered since Phase 22 (untrusted-package deserialization). | 27-05 |

Plans (execute by wave; every `depends_on` resolves to a strictly earlier wave):

- [x] 27-00-PLAN.md (Wave 1) — Spine: terminal-states register, alert-liveness mechanism, dependency-horizon manifest + gate, load-baseline harness, the `core-java` scrape-port fix — **merged PR #314** (`60cb641`)
- [x] 27-01-PLAN.md (Wave 1) — Media durability: the P0 in which a broker outage >15 min deletes quarantined uploads; adds a reclaim sentinel and a claim lock
- [x] 27-05-PLAN.md (Wave 1) — Webhook fan-out: the trusted-packages converter defect; the only plan in the phase that closes a *live* outage — **merged PR #310**; fix verified in the delivered runtime (3 trusted-package literals read from inside the running `app.jar`), DLQ left at 9 for 27-03/27-02
- [x] 27-04-PLAN.md (Wave 2) — Throughput + guards: `spring.rabbitmq.listener.simple.*` is inert via a bean-name collision; media container factory; publish-side destination guard — **merged PR #331** (`9858370`)
- [x] 27-03-PLAN.md (Wave 3) — Failure visibility: all `alerts.yml` rule content, four missing runbook sections, `check-alert-metrics.sh`, DLQ archive — **COMPLETE 9/9**. `DeadLetterQueueNonEmpty` observed **firing on the real batch**, closing the H-1 signal-regression window. Task 8 re-ran the live gate on the replaced broker and found the alert layer survived the major version change **with no edit**: 19 live rules / 24 selectors / 3 dormant, identical to 3.12; `/metrics/detailed` families unchanged; `dlq-inspect --summary` flipped 1 → 0. Evidence in `27-03-EVIDENCE.md` §14 — **PR #336**
- [x] 27-02-PLAN.md (Wave 4) — Broker upgrade: 3.12 → 4.3.4 fresh install (no direct upgrade path exists), volume snapshot + rollback, DLQ purge and disposition — **merged PR #335** (`b51c82f`); rollback proven twice, once by firing for real
- [x] 27-06-PLAN.md (Wave 4) — CI wiring: the `ops-contracts` job — three static gates

**Planning note**: plans were written, audited by five independent passes (correctness ×2, falsifiability ×2, regression-by-omission ×1), and fixed *before* registration. `drafts/REVISION-BRIEF.md` is binding and records where the brief itself was wrong. Registered via `/gsd-phase`; the SDK derived Phase **28** because the phase directory already existed, and was corrected to 27 by hand.

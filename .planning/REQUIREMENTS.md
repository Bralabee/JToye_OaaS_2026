# Requirements: J'Toye OaaS — Milestone v2.3 (Vendor Ops + AI Interleaved)

**Defined:** 2026-07-14
**Milestone:** v2.3
**Source:** Three phase-ready specs in `.planning/specs/` (`onboarding-blocker-ux-SPEC.md`, `shop-scoped-access-SPEC.md`, `image-architecture-SPEC.md`) + `HANDOFF.md` (2026-07-14: dashboard mobile #104, AI track #205/#204, k8s live-deploy breakage list). All carry file:line evidence and locked decisions.
**Core Value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.

## v1 Requirements

v2.3 scopes **24 requirements across 7 categories**. Original scope locked by user 2026-07-14 (do not re-litigate); a **Notifications & Comms** category was inserted as **Phase 22** on 2026-07-14, absorbing the former standalone Outbound Webhooks (#205 → COMMS-04/05/06) + WhatsApp (#208). Phase order is thinnest/highest-pain first: Onboarding UX → **Notifications & Comms** → Vendor-scoped access → Image architecture → AI (mutating MCP) → Infrastructure (Dashboard mobile MOBL-01 folded into Vendor-scoped access).

Migration numbering: shop_staff = **V52**, media_asset = **V53** (shop_staff first); Comms tables take later versions under `out-of-order=true` so that ordering is undisturbed. The onboarding-blocker path is zero-migration.

### Onboarding UX (ONBD) — spec `onboarding-blocker-ux-SPEC.md`

Make onboarding blockers visible, onboarding data correctable, and exits reachable. Highest user pain, cheapest fix (zero migrations). The state machine remains the sole writer of `Shop.published`; every transition goes through events.

- [x] **ONBD-01**: Vendor can withdraw an in-progress application. Add the `WITHDRAW` `OnboardingEvent` + `POST /onboarding/withdraw`, valid from DRAFT / VERIFYING / ACTION_REQUIRED, terminal (restart = new application). `OnboardingState.WITHDRAWN` already exists in the V43 status CHECK — no migration. Tests: state-machine transition tests (valid sources → WITHDRAWN; invalid source rejected), controller test, Jest for the withdraw confirm dialog. Source: `OnboardingEvent` has no WITHDRAW and no endpoint fires one (spec Problem #3).

- [x] **ONBD-02**: Vendor can correct onboarding data. Add an update endpoint (company number / sole-trader flag / FHRS establishment override) valid only in DRAFT / ACTION_REQUIRED, re-validated like create (bounded company-number format), rejected outside those states with RFC 7807. Resubmit re-runs gates against the corrected data. Tests: controller state-guard test, re-validation test, Jest for the inline company-number edit. Source: `companyNumber` captured once at creation, RESUBMIT re-runs same data (spec Problem #2).

- [x] **ONBD-03**: Manual-review applications are visible to everyone. DTO-derive `reviewPending = status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING` (no state migration); vendor UI renders "In review". Admin surface gains a review queue (extend `/pending` or add `/reviews`) listing these applications, plus the missing human-decision mechanism `POST /onboarding/admin/{id}/gates/{gateType}/resolve {decision: PASS|WAIVE|FAIL, reason}` — writes the gate row (audited via V43 `_aud`), triggers recompute so the state machine advances normally. Tests: gate-resolve → recompute → advance integration test, admin-queue listing test, Jest for in-review copy + polling back-off. Source: `GateChainRunner.java:167-199` never advances on MANUAL_REVIEW; `OnboardingAdminController.java:62-72` lists only PENDING_APPROVAL (spec Problem #1).

- [x] **ONBD-04**: Per-gate remediation blocks. Each FAILED / MANUAL_REVIEW gate renders *why → what to do → a button that goes there*: company-number inline edit, "fix these N products" deep link for allergen offenders, address-confirm / establishment-picker for FHRS. Tests: Jest per remediation block type (renders reason + action + deep link). Source: gate reasons render verbatim with no next-step (spec Problem #5).

- [x] **ONBD-05**: Rejection reason reaches the vendor + a real support channel. Expose `rejectionReason` on the vendor-facing `OnboardingDto` (currently admin-only) and render it plus a configurable mailto/link (not a bare "contact support") on terminal states. Tests: DTO serialization test (reason present), Jest for terminal-state copy. Source: `RejectOnboardingRequest.reason` is `@NotBlank` but only `AdminOnboardingDto` exposes it (spec Problem #4). Journey-matrix add: drive one blocked onboarding end-to-end (bad company number → fix inline → resubmit → live) in Playwright.

### Notifications & Comms (COMMS) — spec `phases/22-notifications-comms/22-SPEC.md`

The platform's first governed delivery of the V46 outbox. **Extend** the already-working order-email path (never regress it), bind the dead onboarding exchange, add the missing consent/unsubscribe governance, stand up outbound webhooks (absorbed from #205), and scaffold WhatsApp/SMS (#208) behind an off-by-default flag. Prod email = SES over SMTP config (no SDK). Respects the outbox-flusher dispatch trap.

- [x] **COMMS-01**: Bind the dead channels; preserve the working one. Bind `onboardingEventsExchange` + add payment/refund consumers; each new event type ships exchange bean + producer + `PaymentEventOutboxFlusher.publishRow` dispatch branch atomically; the existing `OrderStateChangeListener → EmailNotificationService` order path is untouched. Tests: per-event dispatch integration test, existing order-email test still green, no poison dead-letter.
- [x] **COMMS-02**: Transactional email to both audiences. Templated emails for order (customer+vendor), onboarding (vendor), payment (customer+vendor), refund (customer+vendor). Tests: per-event correct-recipient assertions; stalled onboarding → vendor email in Mailhog.
- [x] **COMMS-03**: Consent + one-click unsubscribe + suppression (GDPR/PECR). Transactional under legitimate interest + unsubscribe→suppression; marketing requires explicit opt-in; suppression/consent tables ENABLE+FORCE RLS. Tests: unsubscribe suppresses next send; marketing-without-opt-in refused; RLS under NOSUPERUSER.
- [x] **COMMS-04**: Vendor webhook subscriptions (#205). `webhook_subscription` (ENABLE+FORCE RLS) + event-type selection + signing secret + create/list/rotate/pause/revoke API. Tests: CRUD; cross-tenant empty/403; secret-rotation invalidates old signatures.
- [x] **COMMS-05**: Signed, retried, observable delivery (#205). HMAC-SHA256 signed POST + bounded-backoff retry + `webhook_delivery` status rows + no head-of-line block + bounded retention (#107). Tests: signature verify, retry-then-failed, healthy-sibling-still-delivered, retention prune.
- [x] **COMMS-06**: Webhook management + delivery-log UI. Create/list/pause/revoke + rotate secret + delivery-log browser (filter by event/status) + manual replay (tagged attempt). Mobile-first at 375px. Tests: Jest/Playwright for create→list, filter, replay, 375px no-overflow.
- [x] **COMMS-07**: WhatsApp/SMS channel seam (#208, scaffold). `NotificationChannel` abstraction + WhatsApp/SMS stub behind an OFF-by-default flag; no-op when off, never blocks email/webhooks; creds via config. Tests: flag-off delivers email+webhooks with zero WhatsApp errors; no-op unit test; enable-without-creds = WARN no-op not crash.

### Vendor-scoped access (VSA) — spec `shop-scoped-access-SPEC.md`

Add a finer authorization boundary *inside* a vendor. Hierarchy is **Vendor (tenant) → Shop** — one vendor owns many shops, and this is the vendor's internal access model spanning vendor-wide grants (GROUP_ADMIN) down to a single shop (SHOP_MANAGER/STAFF). RLS stays the tenant wall; this is a second, application-layer gate. Shop is the finest grain this milestone; an intermediate **department** tier (Vendor → Department → Shop) is noted as a future organizational layer, not modeled in v2.3. Incremental Betterment: every existing tenant user gets a GROUP_ADMIN row at migration time — zero day-one regression.

- [x] **VSA-01**: `shop_staff` mapping table (V52). Columns `id, tenant_id, user_id (Keycloak sub UUID), shop_id (FK shops, NULLable = tenant-wide grant), role (CHECK GROUP_ADMIN|SHOP_MANAGER|STAFF), created_at, created_by`; ENABLE+FORCE RLS tenant-scoped (mirror V47/V50 policy pattern); unique `(tenant_id, user_id, COALESCE(shop_id, zero-uuid))`; `_aud` mirror per Envers. Backfill: every existing tenant user → GROUP_ADMIN row; realm `admin` role ⇒ implicit GROUP_ADMIN. Tests: RLS proven under NOSUPERUSER role-downgrade (RlsContractTest pattern), backfill idempotency test. Source: no `shop_staff`/membership table exists (spec Problem, verified live).

- [ ] **VSA-02**: Application-layer enforcement. `ShopAccessService.require(shopId, minRole)` at the top of shop-scoped service methods (shops, products, orders, KDS, marketing); deny-by-default for shop-scoped writes without a grant; membership resolved server-side from `shop_staff` per request (tenant-aware cache). 403 with RFC 7807 body distinct from the RLS 404 (do not blur the tenant boundary signal). Enumerate the endpoint inventory during planning (seed from `qa/surface-ledger.json`). Tests: Testcontainers cross-shop 403 proofs, SHOP_MANAGER-scoped-to-one-shop test, STAFF read-only test, JWT-unchanged assertion. Source: ordinary shop/product/order CRUD open to any authenticated tenant user on every shop (spec Problem).

- [ ] **VSA-03**: Dashboard shop-context switcher. Persisted shop selection in the dashboard nav; all shop-scoped screens operate on the selected shop; group-wide mutations require an explicit "apply to all shops" action available only to GROUP_ADMIN. Tests: Jest for the switcher (selection persists, non-GROUP_ADMIN cannot see "apply to all"). Source: spec UI section.

- [ ] **VSA-04**: Staff management screen. Minimal slice: list staff + grant + revoke roles per shop; invitations / user-creation stay in Keycloak (note the KC24 unmanaged-attribute trap). Tests: Jest for list/grant/revoke, integration test for grant→access-gained / revoke→403. Source: spec UI section.

### Image architecture (IMG) — spec `image-architecture-SPEC.md`

Forward-looking hardening before real vendor uploads. Copy-on-write asset model + safe async pipeline. The worker must pin the tenant GUC before any DB write (@Async-tenant landmine).

- [ ] **IMG-01**: `media_asset` model (V53). Table `id, tenant_id, object_key, sha256, content_type, width, height, bytes, status (PENDING|ACTIVE|FAILED), uploaded_by, created_at`; ENABLE+FORCE RLS tenant-scoped; `sha256` unique per tenant for dedup; `_aud` mirror if audited. Products reference assets (FK or join table for the gallery) — never own bytes; copy-on-write on edit mints a new asset + repoints only that product; reference-counted physical MinIO delete only at ref-count 0. Backfill existing `image_url` values with a dual-read window. Tests: RLS under NOSUPERUSER, CoW repoint test, ref-count-0 delete test, dedup test. Source: `products.image_url text` + `additional_image_urls text[]` are flat strings (spec Problem #1).

- [ ] **IMG-02**: Safe async upload pipeline. Request thread (cheap, reject-early): Content-Length + `spring.servlet.multipart.max-file-size` + streaming size guard refuses oversize BEFORE buffering; store raw to a quarantine prefix; insert PENDING `media_asset`; publish AMQP event (outbox per V46); return 202-style with asset id. Queue worker: magic-byte sniff (never trust client content-type), format allowlist (jpeg/png/webp), decode-to-verify, strip EXIF; normalize (resize to max dimension, re-encode at target quality, thumbnail) — **stored artifact is always the normalized derivative, never the raw upload**; delete raw quarantine object on success. Applies to single uploads AND BulkImportService (one path). Tests: oversize reject-before-buffer, magic-byte mismatch veto, normalize-derivative-stored assertion, worker tenant-GUC-pinned test. Source: `StorageService.upload → validateAndRead` trusts client content-type, WARN + "client should compress" (~line 314) (spec Problem #2).

- [ ] **IMG-03**: Gate strictness. Normalization/decode/allowlist failure → status=FAILED, upload rejected (vendor sees rejection + reason). Content-relevance below threshold → asset goes ACTIVE but lands in a vendor-visible review queue (a hard reject would wrongly block legitimate rare dishes the vision model returns 0.0/"Unknown" for). Vision stage behind a flag defaulting to advisory until the provider is reliably up (Ollama :11434 conflict). Tests: compress-fail→FAILED, low-confidence→ACTIVE+queued, flag-off→advisory-only. Source: spec D3 + Q2 stage 6.

- [ ] **IMG-04**: Product UI asset states. "Processing" state while asset PENDING; vendor-visible review/rejection queue surfaces FAILED (reason) and content-flagged (ACTIVE) assets. Tests: Jest for processing/failed/flagged states. Source: spec D2 ("Product UI shows a processing state while PENDING") + D3 review queue.

### Dashboard mobile (MOBL) — HANDOFF #104

- [ ] **MOBL-01**: Dashboard sidebar no longer overlays content at 375px. The fixed `w-64` sidebar currently overlays the dashboard at mobile width; replace with a responsive nav (drawer/collapse) that pairs with the VSA-03 shop-context switcher. Tests: Jest/Playwright at 375px viewport — content not occluded, nav toggles. Source: HANDOFF Step 1 phase 4 (#104).

### AI / automation (AI) — HANDOFF #205 / #204

- [~] **AI-01**: Outbound webhooks (#205). **ABSORBED into Phase 22 Notifications & Comms on 2026-07-14** (COMMS-04 subscriptions + COMMS-05 signed/retried delivery + COMMS-06 management/delivery-log UI). A delivery consumer of the outbox had to be built as one coherent channel alongside email, not as a standalone later phase. No longer a separate deliverable — see the COMMS category.

- [ ] **AI-02**: Mutating MCP tools (#204 wiring). Extend the Phase 20 read-only MCP server with write tools (e.g. orders.create / customers.create) riding the uniform Idempotency-Key contract already wired via `IdempotencyService.execute` (#204, V50). Tests: MCP write-tool integration test with idempotent replay, RLS-scoped proof under the MCP credential. Source: HANDOFF Step 1 phase 5; #204 idempotency wiring exists.

### Infrastructure (INFRA) — HANDOFF k8s live-deploy breakage list

Durable deliverable replacing the imperative deploy patches from the 2026-07-14 live-deploy rehearsal.

- [ ] **INFRA-01**: Committed `k8s/local` overlay. Endpoint shims to `host.minikube.internal` (shared backing services), `minReplicas=1` (no metrics-server locally), backup CronJob repointed to host MinIO. Replaces tonight's imperative secret/configmap patches. Tests: `kubectl kustomize k8s/local` builds; dry-run apply resolves all refs. Source: HANDOFF k8s breakage list #3/#7.

- [ ] **INFRA-02**: Fix verified k8s breakage. (a) `k8s/base/core-java-deployment.yaml` hardcodes `DB_PORT: "5432"` while postgres-credentials has an ignored `port` key — route through `valueFrom.secretKeyRef` (config-injection doctrine, repo defect). (b) Secrets must use `DB_USER`/`DB_PASSWORD` (the `jtoye_app` NOSUPERUSER role), never `POSTGRES_USER` — core refuses to boot as DB superuser (`DatabaseConfigurationValidator`). (c) pg-backup CronJob → host MinIO (the #101 PITR rehearsal). (d) verify STOMP relay stomp-login/passcode wiring reaches spring config. Tests: config-injection assertion (no hardcoded port), boot-as-app-role smoke. Source: HANDOFF k8s breakage list #1/#2/#5/#6.

## Future Requirements (deferred — tracked, not lost)

Per the three specs' "Explicitly deferred" sections and HANDOFF "Parked":

- Platform-wide stock image library / cross-tenant asset sharing (image spec D1 later slice)
- Reviewer SLA tracking / escalation / multi-reviewer onboarding workflow (onboarding spec)
- Reapply-after-REJECTED flow (onboarding stays terminal this slice; support channel is the path)
- Self-serve user invitation flows (Keycloak admin remains the account source)
- Fine-grained per-capability permissions beyond the three shop roles
- Vision-provider hosting decision (Ollama fix vs hosted model) — blocks IMG-03 stage 6 only
- #88 backend public rate-limiter tuning; #61 refund E2E (Stripe test keys); #207 pgvector; #208 WhatsApp
- Storefront theme implementation (sketch winner D — needs ratings/FHRS in shop DTOs); #202 hydration refactor
- Guest-tracking `app.customer_email` GUC DB-guard (#113 tail — TEXT comparison, needs design)
- NetworkPolicy enforcement proof (needs policy-enforcing CNI or AKS — minikube default CNI does not enforce)
- Cluster-blocked P2 tails: #100 sealed-secrets rollout, #101 PITR-to-MinIO, staging-gate rehearsal

## Out of Scope (explicit exclusions)

- **Cross-tenant / platform-operator roles** — SHOP roles are within-tenant only; per-shop Keycloak clients or shop claims in the token are out (spec deferred).
- **Storefront public read path changes** — `/public/*` stays unauthenticated; shop-scoped enforcement must not touch it (spec constraint).
- **Explicit `IN_REVIEW` onboarding state** — rejected for this slice: costs a V5x CHECK migration + transitions for no additional user-visible value over the DTO-derived flag (spec resolution).
- **Raw-upload storage** — the pipeline never stores the client's raw bytes as the canonical artifact (spec Q2 stage 4).
- **QA audit + remediation-backlog re-count** — these are HANDOFF Steps 2 and 3, explicitly *after* v2.3 work, not part of this milestone's build.

## Traceability

| Requirement | Phase | Plan(s) | Status |
|-------------|-------|---------|--------|
| ONBD-01 | Phase 21 | 21-01, 21-04 | Complete |
| ONBD-02 | Phase 21 | 21-01, 21-04 | Complete |
| ONBD-03 | Phase 21 | 21-02, 21-03, 21-04 | Complete |
| ONBD-04 | Phase 21 | 21-04 | Complete |
| ONBD-05 | Phase 21 | 21-03, 21-04, 21-05 | Complete |
| COMMS-01 | Phase 22 | 22-04 | Complete |
| COMMS-02 | Phase 22 | 22-01, 22-04 | Complete |
| COMMS-03 | Phase 22 | 22-02 | Complete |
| COMMS-04 | Phase 22 | 22-03 | Complete |
| COMMS-05 | Phase 22 | 22-05 | Complete |
| COMMS-06 | Phase 22 | 22-06 | Complete |
| COMMS-07 | Phase 22 | 22-01 | Complete |
| VSA-01 | Phase 23 | 23-01, 23-02 | Complete |
| VSA-02 | Phase 23 | 23-02, 23-03 | Pending |
| VSA-03 | Phase 23 | 23-05, 23-07 | Pending |
| VSA-04 | Phase 23 | 23-04, 23-06 | Pending |
| MOBL-01 | Phase 23 | 23-05, 23-06 | Pending |
| IMG-01 | Phase 24 | 24-01 | Pending |
| IMG-02 | Phase 24 | 24-02 | Pending |
| IMG-03 | Phase 24 | 24-03 | Pending |
| IMG-04 | Phase 24 | 24-03 | Pending |
| AI-01 | Phase 22 | absorbed → COMMS-04/05/06 | Absorbed |
| AI-02 | Phase 25 | 25-01, 25-02 | Pending |
| INFRA-01 | Phase 26 | 26-01 | Pending |
| INFRA-02 | Phase 26 | 26-02 | Pending |

**Coverage:** 24 v1 requirements (ONBD×5, COMMS×7, VSA×4, IMG×4, MOBL×1, AI-02, INFRA×2) mapped to exactly one phase; AI-01 absorbed into Phase 22 (COMMS-04/05/06), not double-counted. No orphans, no duplicates. (Plan columns are the roadmap's suggested breakdown — refined during `/gsd-plan-phase`.)

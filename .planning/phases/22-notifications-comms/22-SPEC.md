# Phase 22: Notifications & Comms — Specification

**Created:** 2026-07-14
**Ambiguity score:** 0.16 (gate: ≤ 0.20)
**Requirements:** 7 locked

## Goal

Every order, onboarding, payment, and refund event on the V46 transactional outbox is delivered to the people (customers + vendors, by email) and systems (vendor-registered, HMAC-signed webhooks) that need it — governed by GDPR-compliant consent + unsubscribe — while the already-working order-confirmation email path stays intact and a WhatsApp/SMS channel seam ships behind an off-by-default flag.

## Background

Grounded in the current codebase (scouted 2026-07-14):

- **Order email already works end-to-end.** `EmailNotificationService` (`SimpleMailMessage` over `JavaMailSender`/SMTP, `@Async`, `notification.email.enabled` flag, `notification.email.from`) sends the full order lifecycle (received/confirmed/preparing/ready/completed/cancelled) to the **customer**. It is driven by `OrderStateChangeListener`, a real `@RabbitListener` on `RabbitMQConfig.ORDER_EVENTS_QUEUE`. Mailhog is wired in `docker-compose.full-stack.yml` (`:1025` SMTP, `:8025` UI); `spring.mail.*` reads `SMTP_*` env. So the "no delivery consumer" framing in the phase brief was **only half true** — orders have one.
- **Onboarding notifications are the real dead channel.** `RabbitMQConfig.onboardingEventsExchange()` is a *deliberately unbound* `TopicExchange` (no queue, no binding) — Phase 21 (21-02) writes `onboarding.events` rows to the shared outbox and the flusher publishes them, but with no bound queue RabbitMQ discards them. The comment says "until Phase 24 (#205 webhook delivery) attaches"; this phase is where they get a consumer.
- **Payment/refund events** flow to `PaymentEventAuditListener` (audit only) on `PAYMENT_EVENTS_QUEUE` — no email, no external delivery.
- **No consent, unsubscribe, or suppression anywhere** — `EmailNotificationService.sendNotification` sends whenever `emailEnabled && recipient present`. A GDPR gap.
- **No outbound webhooks** (#205) and **no WhatsApp/SMS** (#208) exist.
- **Prod email is plain SMTP** via `JavaMailSender` — there is no AWS SES SDK wiring (AWS SDK is present for S3 only).
- The shared `PaymentEventOutboxFlusher.publishRow` (line ~238) dispatches per-row by `exchange` column (V36); a new event type without a matching dispatch branch poison-dead-letters (memory `outbox_flusher_dispatch_trap`).

The gap this phase closes: bind + consume the onboarding/payment/refund events, extend email to both audiences, add the consent/unsubscribe governance the platform is missing, stand up the outbound-webhook machine channel (absorbed from the former standalone Phase 24 / #205), and scaffold WhatsApp/SMS (#208) — without regressing the working order emails (Incremental Betterment).

## Requirements

1. **COMMS-01 — Bind the dead channels; preserve the working one**: All outbox event types reach a notification-dispatch path, added alongside (not replacing) the order-email path.
   - Current: only `ORDER_EVENTS` has a consumer→email path; `onboardingEventsExchange` is unbound (discarded); payment/refund are audit-only.
   - Target: the onboarding exchange is bound to a consumer, and consumers exist for payment + refund events; each new event type ships its exchange bean + producer + `PaymentEventOutboxFlusher.publishRow` dispatch branch in the same change; the existing `OrderStateChangeListener → EmailNotificationService` order path is untouched.
   - Acceptance: an onboarding stall event now lands in Mailhog (was discarded); a Testcontainers/integration test asserts each event type (order/onboarding/payment/refund) produces a dispatch record; the existing order-confirmation email test still passes; no event type poison-dead-letters (flusher dispatch covers all types).

2. **COMMS-02 — Transactional email to both audiences across all lifecycle events**: Customers and vendors each receive the emails relevant to them.
   - Current: emails exist only for order lifecycle → customer.
   - Target: templated transactional emails for order (customer + vendor), onboarding (vendor admin), payment (customer + vendor), refund (customer + vendor) — "both audiences where relevant" (onboarding is vendor-only; customers get order/payment/refund).
   - Acceptance: per event type, a test asserts the correct recipient set gets an email with the right subject/body; a stalled onboarding application produces a vendor email in Mailhog; a refund produces both a customer and a vendor email.

3. **COMMS-03 — Consent, one-click unsubscribe, suppression (GDPR/PECR)**: No email is sent to a recipient who has opted out; marketing requires explicit opt-in.
   - Current: `EmailNotificationService` sends unconditionally — no consent, unsubscribe, or suppression.
   - Target: transactional status emails send under legitimate interest but carry a working one-click unsubscribe that writes a tenant-scoped suppression entry; a suppressed recipient receives no further email of that category; any marketing/promotional send requires a recorded explicit opt-in (absent → not sent). Suppression/consent tables ENABLE+FORCE RLS.
   - Acceptance: the unsubscribe link resolves and creates a suppression row; the suppressed recipient's next matching event sends NO email (test); a marketing send with no opt-in is refused (test); suppression is tenant-isolated under the NOSUPERUSER RLS role-downgrade.

4. **COMMS-04 — Vendor webhook subscriptions (tenant-scoped, RLS)**: A vendor can register and manage webhook endpoints.
   - Current: no webhook model or API.
   - Target: `webhook_subscription` (ENABLE+FORCE RLS, tenant-scoped) with target URL, selected event types (onboarding/order/payment/refund), a per-subscription signing secret, and active/paused state; REST API to create / list / rotate-secret / pause / revoke.
   - Acceptance: subscription CRUD API works; a cross-tenant list returns empty/403 (RLS-proven, NOSUPERUSER); rotating the secret invalidates signatures made with the old secret.

5. **COMMS-05 — Signed, retried, observable webhook delivery (no head-of-line block)**: Events reach subscribers reliably and verifiably.
   - Current: none.
   - Target: each outbox event matching a subscription is POSTed with an HMAC-SHA256 signature header a receiver can verify; failed deliveries retry with bounded exponential backoff; each attempt writes a `webhook_delivery` status row (pending/success/failed + HTTP code + attempt count); one permanently-failing endpoint never blocks deliveries to other subscriptions; delivery rows have a bounded retention (pruned — addresses accumulator growth #107).
   - Acceptance: a test receiver verifies the HMAC on a delivered payload; a failing endpoint retries N times with backoff then marks `failed`; a healthy second subscription still receives its delivery while the first is failing; a retention job prunes delivery rows older than the configured window.

6. **COMMS-06 — Webhook management + delivery-log UI (full, mobile-first)**: Vendors self-serve webhooks and can inspect + replay deliveries.
   - Current: no UI.
   - Target: a dashboard screen to create/list/pause/revoke subscriptions + rotate secret; a delivery-log browser filterable by event type and status; a manual **replay** action that re-enqueues a past delivery (marked as a replay attempt; does not corrupt existing status history).
   - Acceptance: creating a subscription in the UI shows it in the list; a delivered event appears in the delivery log; filtering by status narrows it; replay adds a new attempt row tagged "replay" and re-delivers; the screen renders without horizontal overflow at a 375px viewport (Jest/Playwright).

7. **COMMS-07 — WhatsApp/SMS channel seam (scaffold, off by default)**: The third channel exists structurally without live integration.
   - Current: no WhatsApp/SMS.
   - Target: a `NotificationChannel` provider abstraction with a WhatsApp/SMS implementation stub behind a config flag defaulting OFF; when off it is a logged no-op that never blocks email or webhook delivery; provider credentials/flags injected via config (no hardcode).
   - Acceptance: with the flag OFF (default) all lifecycle events still deliver email + webhooks with zero WhatsApp errors; a unit test proves the no-op path; enabling the flag with no credentials fails filtered (documented WARN no-op), not a crash.

## Boundaries

**In scope:**
- Bind `onboardingEventsExchange` + add consumers for onboarding/payment/refund; preserve the order-email path unchanged
- Templated transactional email to customer **and** vendor across order/onboarding/payment/refund
- Consent model: legitimate-interest transactional + explicit marketing opt-in + one-click unsubscribe + tenant-scoped suppression (RLS)
- Outbound webhooks: `webhook_subscription` model + REST API (RLS), HMAC-SHA256 signing, bounded-backoff retry, `webhook_delivery` status, no head-of-line block, bounded retention
- Webhook management + delivery-log UI (filter + manual replay), mobile-first at 375px
- WhatsApp/SMS provider abstraction + stub behind an OFF-by-default flag
- Flyway migration(s) for consent/suppression + `webhook_subscription` + `webhook_delivery` (versions AFTER V52/V53 under `out-of-order=true`, preserving the shop_staff→media_asset ordering)
- `docs/metrics.json` reconcile + docs-freshness green

**Out of scope:**
- **Live WhatsApp/SMS delivery** — no real Twilio/WhatsApp Business credentials; scaffold only (#208 folded, not driven live) — deferred until a provider account exists
- **AWS SES SDK integration + bounce/complaint feedback loop** — prod email uses SES-over-SMTP config only; the SDK sender is a follow-up
- **Marketing campaign engine** (composition, scheduling, segmentation) — only the opt-in gate + suppression are built; sending campaigns is not
- **Rewriting order notifications into a new framework** — the existing `EmailNotificationService` order path stays; new events are added alongside (Incremental Betterment)
- **In-app / web-push notifications** — separate backlog
- **Customer notification-preference UI beyond unsubscribe** — this phase ships the vendor webhook UI + the unsubscribe surface only

## Constraints

- **Outbox-flusher dispatch trap** (memory `outbox_flusher_dispatch_trap`): every new event type ships its exchange bean + producer + `PaymentEventOutboxFlusher.publishRow` dispatch branch atomically, or it poison-dead-letters.
- **Multi-tenancy/RLS**: `webhook_subscription`, `webhook_delivery`, and consent/suppression tables are ENABLE+FORCE RLS, tenant-scoped, proven under the NOSUPERUSER role-downgrade.
- **Prod email transport = SES via SMTP config** (`spring.mail.*` env pointed at the SES SMTP endpoint) — no transport code change; dev = Mailhog.
- **No head-of-line block**: one failing subscription/endpoint must not stall deliveries to others (per-subscription isolation).
- **Bounded accumulators** (#107): `webhook_delivery` + suppression rows must have a retention/prune policy — not unbounded growth.
- **Config-injection** (GLOBAL_RULE_6): provider creds, channel flags, retry tunables, from-address, review SLA — all via the config layer, never hardcoded.
- **Machine-consumability** (CLAUDE.md cross-cutting): webhook payloads are typed + versioned; tool/API errors are RFC 7807 where applicable; replay is idempotent-safe.
- **Migration ordering**: V52 `shop_staff` (Phase 23) and V53 `media_asset` (Phase 24) keep their versions; Comms migrations take later versions with `out-of-order=true` already enabled.

## Acceptance Criteria

- [ ] An onboarding stall event that was previously discarded now produces a vendor email in Mailhog (dead channel bound).
- [ ] The pre-existing order-confirmation email path still passes its test unchanged (no regression).
- [ ] Order/onboarding/payment/refund each notify the correct recipient set (customer and/or vendor) — one test per event type.
- [ ] No outbox event type poison-dead-letters — the flusher dispatches every event type.
- [ ] A one-click unsubscribe link creates a suppression row and the recipient's next matching event sends NO email.
- [ ] A marketing/promotional send with no recorded opt-in is refused.
- [ ] Suppression + webhook tables are tenant-isolated under the NOSUPERUSER RLS role-downgrade (cross-tenant → empty/403).
- [ ] Webhook subscription CRUD API works; secret rotation invalidates old-secret signatures.
- [ ] A delivered webhook payload carries a verifiable HMAC-SHA256 signature.
- [ ] A failing endpoint retries with bounded backoff then marks `failed`, while a healthy subscription still receives its delivery (no head-of-line block).
- [ ] The webhook UI creates a subscription, shows the delivery log, filters by status, and a replay re-delivers as a tagged attempt; renders without overflow at 375px.
- [ ] With the WhatsApp/SMS flag OFF (default), email + webhooks deliver with zero WhatsApp errors; enabling it without creds is a documented WARN no-op, not a crash.
- [ ] `webhook_delivery` + suppression rows are pruned by a bounded-retention job.
- [ ] `docs/metrics.json` reconciled; docs-freshness CI gate green.

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                        |
|--------------------|-------|------|--------|--------------------------------------------------------------|
| Goal Clarity       | 0.88  | 0.75 | ✓      | Deliverable precise: extend + govern + add channels          |
| Boundary Clarity   | 0.85  | 0.70 | ✓      | Explicit out-of-scope (WhatsApp live, SES SDK, campaigns)     |
| Constraint Clarity | 0.80  | 0.65 | ✓      | Flusher trap, RLS, SES-over-SMTP, retention, config-injection |
| Acceptance Criteria| 0.80  | 0.70 | ✓      | 14 pass/fail checks; events + audiences concrete             |
| **Ambiguity**      | 0.16  | ≤0.20| ✓      | Gate passed after 2 rounds                                   |

Status: ✓ = met minimum, ⚠ = below minimum (planner treats as assumption)

## Interview Log

| Round | Perspective          | Question summary                                  | Decision locked                                                             |
|-------|----------------------|---------------------------------------------------|-----------------------------------------------------------------------------|
| 0     | Researcher (scout)   | What exists today?                                | Order email already works; onboarding exchange unbound; no consent/webhooks/WhatsApp; prod email = SMTP not SES SDK |
| 1     | Researcher/Simplifier| Core deliverable given order email works?          | Extend + govern + add channels (keep the working order path)                |
| 1     | Researcher/Simplifier| Consent model?                                    | Transactional = legitimate interest + unsubscribe/suppression; marketing = explicit opt-in |
| 1     | Researcher/Simplifier| Which channels ship live vs scaffold?             | Email + webhooks live; WhatsApp/SMS scaffold behind OFF flag                 |
| 2     | Boundary/Failure     | Which events + recipients?                        | Broad — all lifecycle events (order/onboarding/payment/refund), both audiences where relevant |
| 2     | Boundary/Failure     | Webhook surface depth?                            | Full — registration API + management UI + delivery-log browser + manual replay + HMAC + retry |
| 2     | Boundary/Failure     | Prod email transport?                             | SES via SMTP config (no new code); bounce/complaint deferred                 |

---

*Phase: 22-notifications-comms*
*Spec created: 2026-07-14*
*Next step: /gsd:discuss-phase 22 — implementation decisions (how to build what's specified above)*

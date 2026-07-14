# Phase 22: Notifications & Comms - Context

**Gathered:** 2026-07-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Governed delivery of V46 transactional-outbox events (order / onboarding / payment / refund) to **customers + vendors by email** and to **vendor systems by webhook**, adding the consent/unsubscribe governance the platform lacks, **while the already-working order-confirmation email path stays intact** (Incremental Betterment). WhatsApp/SMS ships as a scaffolded seam behind an off-by-default flag. Requirements COMMS-01..07 are locked by `22-SPEC.md`; this phase decides HOW, not WHAT.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**7 requirements are locked.** See `22-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `22-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- Bind `onboardingEventsExchange` + add consumers for onboarding/payment/refund; preserve the order-email path unchanged
- Templated transactional email to customer **and** vendor across order/onboarding/payment/refund
- Consent: legitimate-interest transactional + explicit marketing opt-in + one-click unsubscribe + tenant-scoped suppression (RLS)
- Outbound webhooks: `webhook_subscription` model + REST API (RLS), HMAC-SHA256 signing, bounded-backoff retry, `webhook_delivery` status, no head-of-line block, bounded retention
- Webhook management + delivery-log UI (filter + manual replay), mobile-first at 375px
- WhatsApp/SMS provider abstraction + stub behind an OFF-by-default flag
- Flyway migration(s) for consent/suppression + `webhook_subscription` + `webhook_delivery` (versions AFTER V52/V53 under `out-of-order=true`)
- `docs/metrics.json` reconcile + docs-freshness green

**Out of scope (from SPEC.md):**
- Live WhatsApp/SMS delivery (no provider creds) — scaffold only (#208 folded, not driven live)
- AWS SES SDK integration + bounce/complaint feedback — prod email is SES-over-SMTP config only
- Marketing campaign engine (composition, scheduling, segmentation)
- Rewriting order notifications into a new framework — extend alongside
- In-app / web-push notifications
- Customer notification-preference UI beyond unsubscribe

</spec_lock>

<decisions>
## Implementation Decisions

### Email delivery & format
- **D-01 — Branded HTML templates now, with a plain-text alternative.** Every event (order/onboarding/payment/refund, both audiences) gets a branded HTML email (brand header/footer, action button, unsubscribe footer) AND a plain-text `multipart/alternative` part for deliverability. Route through a small renderer seam (subject + html + text per event) so the mechanism is uniform. **Extend, don't replace:** the existing `EmailNotificationService` order path keeps working — migrate its order emails onto the new renderer OR leave them and add the new events alongside, planner's call, but the working order-email test must stay green (COMMS-01).
- **D-02 — Prod email = SES over SMTP config; dev = Mailhog.** Point `spring.mail.*` (already env-driven via `SMTP_*`) at the SES SMTP endpoint in prod; no AWS SES SDK, no bounce/complaint loop this phase.

### Preferences & recipient resolution
- **D-03 — Per-category notification preferences.** Categories: **orders / onboarding / financial (payment+refund) / marketing**. Transactional categories default-on under legitimate interest, each with a per-category one-click unsubscribe → suppression; **marketing requires explicit opt-in** (absent → not sent). Preference + suppression tables ENABLE+FORCE RLS, tenant-scoped, proven under the NOSUPERUSER role-downgrade.
- **D-04 — Recipient resolution.** Customer = the order's email (already how `EmailNotificationService` gets the recipient). Vendor = **`tenants.contact_email`** (V48, verified `VARCHAR(320)` on `Tenant.contactEmail`) with the onboarding contact as fallback. Onboarding notifications are **vendor-only** (no J'Toye platform operator — `arch_no_platform_operator`).

### Webhook payload & contract
- **D-05 — Full entity snapshot wrapped in a versioned event envelope.** Payload = `{ id, type (e.g. "order.ready"), tenantId, occurredAt, version, data: { …full existing DTO } }`. Reuse existing DTOs (`OrderDto`, refund/onboarding DTOs) as the `data` body — no bespoke minimal shape. **Not a third-party PII leak:** deliveries go to the *vendor's own registered endpoint* carrying the *vendor's own tenant data*. Still require **HTTPS-only** subscription URLs + **HMAC-SHA256** signing regardless.
- **D-06 — Per-subscription event-type selection** across the onboarding / order / payment / refund families.

### Webhook delivery reliability
- **D-07 — At-least-once + exponential backoff + auto-pause (Stripe-like).** Receivers dedupe on the envelope `id`. Failed deliveries retry with exponential backoff over a bounded attempt count; a subscription that keeps failing **auto-pauses** and surfaces in the UI. **No head-of-line block** — one failing subscription/endpoint never stalls deliveries to others (per-subscription isolation). Attempt count, backoff schedule, auto-pause threshold, and `webhook_delivery` retention window are all **config-injected** (GLOBAL_RULE_6), not literals.

### Claude's Discretion
- **Consumer topology** — extend the existing per-domain `@RabbitListener` pattern (like `OrderStateChangeListener`) vs a unified `NotificationDispatchService`. Either way: any new event type MUST land its exchange bean + producer + `PaymentEventOutboxFlusher.publishRow` dispatch branch **atomically** (the outbox-flusher poison-dead-letter trap).
- **Webhook delivery mechanism** — the reliability-first choice; the transactional-outbox + `@Scheduled` flusher pattern (`PaymentEventOutboxFlusher`) is the in-repo precedent for at-least-once. Resilience4j is already available for backoff.
- **HTML template engine** — Thymeleaf (NOT currently a dependency — would be added) vs a lightweight string-template + CSS-inliner. Planner/researcher picks; deliverability (text alternative) is the hard requirement.
- **Schemas + migration versions** — exact columns for preference/suppression, `webhook_subscription`, `webhook_delivery`; versions land AFTER V52/V53 under `out-of-order=true`.
- **Signature header format** — Stripe-style `t=<ts>,v1=<hmac>` recommended; event-id + dedup-key scheme.
- **Webhook management UI placement** — a dashboard settings/integrations area; reuse the existing dashboard shell + 375px responsive patterns.
- **Exact per-transition notification matrix** — which state changes fire an email per audience; align with `EmailNotificationService`'s existing order set + Phase 21 onboarding events + the new payment/refund events.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements (source of truth)
- `.planning/phases/22-notifications-comms/22-SPEC.md` — the 7 locked requirements, boundaries, acceptance criteria. **MUST read before planning.**

### Messaging / outbox seam (current implementation, scouted 2026-07-14)
- `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java` — exchange/queue/binding beans; **`onboardingEventsExchange()` §157 is a deliberately UNBOUND `TopicExchange`** (Phase 21 seam — this phase binds it); `orderEventsExchange` (bound), `paymentEventsExchange` (bound → audit), DLX exchanges.
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java` §238 `publishRow` — per-row `exchange`-column routing (V36); **a new event type needs a new dispatch branch here or it poison-dead-letters** (memory `outbox_flusher_dispatch_trap`).
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java` §74 — `@RabbitListener(ORDER_EVENTS_QUEUE)` → drives `EmailNotificationService`. The working order-email consumer; the pattern new consumers mirror.
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventAuditListener.java` §19 — `@RabbitListener(PAYMENT_EVENTS_QUEUE)`, audit-only today (payment/refund email is NEW).
- `core-java/src/main/java/uk/jtoye/core/order/OrderSseFanoutListener.java` — anonymous-queue fanout pattern (per-replica delivery), reference for at-least-once vs fanout semantics.
- `core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingStateChangeEvent.java` — Phase 21 event, "kept general so later phases can reuse it for other lifecycle notifications."

### Email (current implementation)
- `core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java` — the WORKING order emails: `SimpleMailMessage`/`JavaMailSender`, `@Async`, `notification.email.enabled/from/tracking-base-url`, inline text templates, `MailException` swallowed. Extend this or route it through the new renderer — do not regress it.
- `core-java/src/main/resources/application.yml` §72-81 — `spring.mail.*` (`SMTP_HOST/PORT/USERNAME/PASSWORD/AUTH/STARTTLS`) — the SES-over-SMTP knob (D-02).
- `docker-compose.full-stack.yml` §461 — Mailhog (`:1025` SMTP, `:8025` UI) for dev email verification.

### Recipient resolution
- `core-java/src/main/java/uk/jtoye/core/tenant/Tenant.java` §57-58 — `contact_email` `VARCHAR(320)` (`contactEmail`) — the vendor recipient (D-04).
- `core-java/src/main/resources/db/migration/V48__tenant_lifecycle_stripe_connect.sql` §27-29 — `tenants.contact_name/contact_email/contact_phone` added.

### Prior context + design
- `.planning/phases/21-onboarding-blocker-ux/21-CONTEXT.md` — Phase 21 D-01 (onboarding stall event written to the outbox; "Phase 24/Comms delivers"), config-injection pattern, `arch_no_platform_operator`.
- `.planning/REQUIREMENTS.md` — COMMS-01..07 + AI-01 absorbed note.
- Memory `outbox_flusher_dispatch_trap`, `project_comms_phase_decision`, `arch_no_platform_operator`.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`EmailNotificationService`** — working `JavaMailSender` email path; extend/route-through, don't rebuild.
- **`RabbitMQConfig` + the `@RabbitListener` per-domain pattern** — how consumers bind to exchanges; the unbound `onboardingEventsExchange` is ready to bind.
- **`PaymentEventOutboxFlusher` (outbox + `@Scheduled` flush)** — the in-repo at-least-once delivery precedent; the model for reliable webhook delivery.
- **`OnboardingStateChangeEvent` + Phase 21 producer** — the onboarding events already flowing to the outbox.
- **`Tenant.contactEmail` (V48)** — vendor recipient.
- **Resilience4j** (already a dependency) — backoff/retry for webhook delivery.
- **Dashboard shell + 375px responsive patterns** (Phase 19/21) — the webhook management + delivery-log UI reuses these.
- **RFC 7807 `GlobalExceptionHandler`** — webhook API + tool errors.

### Established Patterns
- **Transactional outbox → RabbitMQ → per-domain `@RabbitListener`** — the eventing spine; new event types ship exchange bean + producer + flusher dispatch branch **atomically** (poison-dead-letter trap).
- **ENABLE+FORCE RLS, tenant-scoped, proven under NOSUPERUSER** — every new table (preferences, suppression, `webhook_subscription`, `webhook_delivery`).
- **`@Async` tenant-context landmine** — any async delivery worker must re-establish `TenantContext` in try/finally (like `GateChainRunner`) before a tenant-scoped DB write.
- **Config injection (GLOBAL_RULE_6)** — from-address, SLA, provider creds/flags, retry tunables, retention — all config, never literals.
- **`out-of-order=true` Flyway** — Comms migrations take versions after V52/V53 without disturbing them.

### Integration Points
- Bind `onboardingEventsExchange` → new onboarding email consumer; add payment/refund email off `paymentEventsExchange` (today audit-only).
- New `webhook_subscription` / `webhook_delivery` tables + REST API + delivery worker consuming the outbox.
- Recipient resolution: order email (customer) / `tenants.contact_email` (vendor).
- Dashboard: webhook management + delivery-log/replay screen; per-category unsubscribe surface + a public unsubscribe endpoint (token-based, no auth).
- `NotificationChannel` abstraction: Email (live) + Webhook (live) + WhatsApp/SMS (stub, flag OFF).

</code_context>

<specifics>
## Specific Ideas
- **Never regress the working order emails** — they are the one channel that already works; the phase extends around them.
- **A webhook goes to the vendor's OWN endpoint carrying the vendor's OWN tenant data** — so a full-entity snapshot is legitimate; the guardrails are HTTPS-only URLs + HMAC signing + the vendor's own consent, not payload minimization.
- **The onboarding stall event is already sitting in the outbox** (Phase 21) waiting for this exact consumer — binding `onboardingEventsExchange` is the first visible win.
- **Stripe-like webhook semantics** (versioned envelope, `t=,v1=` signature, at-least-once + dedupe, auto-pause) — vendors integrate against a familiar contract.

</specifics>

<deferred>
## Deferred Ideas
- **AWS SES SDK + bounce/complaint feedback loop** — prod uses SES-over-SMTP config this phase; the SDK sender (with suppression sync) is a follow-up.
- **Live WhatsApp/SMS delivery** — needs real Twilio / WhatsApp Business creds, STOP handling, cost; scaffold-only now (#208).
- **Marketing campaign engine** — composition, scheduling, segmentation; only the opt-in gate + suppression are built.
- **In-app / web-push notifications** — separate channel, separate phase.
- **Customer-facing notification-preference dashboard beyond unsubscribe** — this phase ships the vendor webhook UI + the unsubscribe surface only.
- **Delivery-log browser replay across ALL history / bulk replay** — this phase does single-delivery manual replay; bulk/backfill replay is later.

### Reviewed Todos (not folded)
None — no pending todos matched this phase.

</deferred>

---

*Phase: 22-notifications-comms*
*Context gathered: 2026-07-14*

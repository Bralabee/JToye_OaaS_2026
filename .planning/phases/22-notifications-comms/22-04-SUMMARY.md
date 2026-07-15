---
phase: 22-notifications-comms
plan: 04
subsystem: notification/dispatch
tags: [notifications, rabbitmq, email, multi-tenant, rls, consent, mailhog, tdd, comms-01, comms-02]

# Dependency graph
requires:
  - phase: 22-notifications-comms (22-01)
    provides: NotificationChannel / EmailChannel / EmailTemplateRenderer / NotificationMessage / RecipientRole / NotificationProperties
  - phase: 22-notifications-comms (22-02)
    provides: ConsentGate.allows + NotificationCategory + UnsubscribeTokenService
  - phase: 21-onboarding-blocker-ux
    provides: onboarding.events exchange + producer (left deliberately UNBOUND) — this plan binds it
  - phase: prior milestones
    provides: OrderStateChangeListener GUC preamble, PaymentEvent/RefundEvent/OrderStateChangeEvent, Tenant.contactEmail (V48), TenantContext, RLS
provides:
  - RabbitMQ consumer topology — order.notifications / onboarding.notifications / payment.notifications / refund.notifications (email) + webhook.deliveries (fanout, for 22-05)
  - NotificationDispatchService.dispatch(eventType, tenantId, payload) — D-04 recipient resolve → consent gate → render → fan to channels
  - RecipientResolver — LOCKED per-family audiences (order/onboarding = vendor-only; refund/payment = customer+vendor)
  - OrderNotificationListener / OnboardingNotificationListener / FinancialNotificationListener — @RabbitListener consumers with the TenantContext+GUC preamble
  - MailhogAssertions test-support (polls :8025 /api/v2/messages, RFC-2047-decodes subjects)
affects:
  - "22-05 webhook delivery engine (consumes the webhook.deliveries fanout queue this plan declares)"

# Tech tracking
tech-stack:
  added: []  # zero new deps — spring-boot-starter-amqp/mail + jakarta.mail (MimeUtility) already present
  patterns:
    - "Second durable queue per new consumer bound to an EXISTING exchange (never a 2nd listener on an incumbent queue — competing-consumer isolation)"
    - "@RabbitListener TenantContext.set + set_config('app.current_tenant_id', ?, true) GUC preamble before any tenant-scoped read (OrderStateChangeListener §83-90)"
    - "Dispatch orchestration seam: resolve (D-04) → ConsentGate.allows → EmailTemplateRenderer → fan to NotificationChannel list; best-effort per-recipient, channels never throw"
    - "Additive order-audience wiring: new path is VENDOR-ONLY so the untouched legacy customer path is not duplicated (Incremental Betterment / Pitfall 5)"
    - "MimeUtility.decodeText on captured Mailhog subjects so em-dash Q-encoded headers match on multi-word substrings"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/notification/dispatch/RecipientResolver.java
    - core-java/src/main/java/uk/jtoye/core/notification/dispatch/NotificationDispatchService.java
    - core-java/src/main/java/uk/jtoye/core/notification/listener/OrderNotificationListener.java
    - core-java/src/main/java/uk/jtoye/core/notification/listener/OnboardingNotificationListener.java
    - core-java/src/main/java/uk/jtoye/core/notification/listener/FinancialNotificationListener.java
    - core-java/src/test/java/uk/jtoye/core/notification/dispatch/NotificationDispatchServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/listener/OrderNotificationListenerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/listener/OnboardingNotificationListenerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/listener/FinancialNotificationListenerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/MailhogAssertions.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java

key-decisions:
  - "Order family is VENDOR-ONLY on the new path — the customer is served by the untouched legacy OrderStateChangeListener path, so COMMS-02 (order=customer+vendor) is met without double-emailing the customer (Pitfall 5, path A)."
  - "RecipientResolver.forEvent takes tenantId explicitly (plan wrote forEvent(eventType, payload)) — the events carry no common interface exposing tenantId, and the caller already has it; needed for the tenants.contact_email vendor lookup."
  - "Family enum + Recipient record are nested in RecipientResolver (not separate files) to stay within the plan's declared file list; NotificationDispatchService reuses RecipientResolver.Family for category + template mapping."
  - "Refund family maps to a refund.* template key so EmailTemplateRenderer picks the refund copy (its familyOf('order.refunded') would otherwise render the ORDER template)."
  - "First-deploy onboarding-backlog re-delivery ACCEPTED, no cutoff (RESEARCH A5) — previously-discarded stalls are genuine unresolved notifications the vendor never got; the ConsentGate still applies."
  - "PaymentEventOutboxFlusher.publishRow UNTOUCHED — all four dispatch branches already exist; this plan is a pure CONSUMER (Pitfall 3, no poison risk)."

patterns-established:
  - "Consumer-only fan-out to email: bind a dead/discarded channel with its own durable queue + a listener that pins the tenant GUC then delegates to a shared dispatch service."
  - "Mailhog landing proof: @SpringBootTest + Testcontainers Postgres (NOSUPERUSER RLS) invoking the listener directly + MailhogAssertions polling the real dev Mailhog."

requirements-completed: [COMMS-01, COMMS-02]

# Metrics
duration: ~16min
completed: 2026-07-15
---

# Phase 22 Plan 04: Notification Dispatch & Consumer Wiring Summary

**Binds every dead/discarded lifecycle channel to email and fans each event to the correct D-04 audience through a consent-gated dispatch seam — the NEW vendor order email rides alongside the untouched legacy customer path (COMMS-02, no duplicate), the Phase-21 onboarding stall finally lands in Mailhog (COMMS-01), and payment/refund now reach both customer and vendor — with the outbox flusher and the working order-email path left completely frozen.**

## Performance

- **Duration:** ~16 min (Testcontainers + Mailhog integration runs; environment clock skew noted, as in 22-03)
- **Completed:** 2026-07-15
- **Tasks:** 3
- **Files:** 11 (10 created, 1 modified)

## What Was Built

**Task 1 — RabbitMQ consumer topology + Mailhog helper (`7d16733`)**
- `RabbitMQConfig`: four NEW durable email-consumer queues, each on its OWN queue bound to an EXISTING exchange (RESEARCH Pattern 1 — never a 2nd listener on an incumbent queue):
  - `order.notifications` on `order.events` (`order.state.*`) — the VENDOR order-email queue; does NOT reuse the incumbent `order.state-changes`.
  - `onboarding.notifications` — **BINDS the previously-unbound `onboarding.events` exchange** (Phase 21 dead channel).
  - `payment.notifications` on `payment.events` (`payment.*`) — a SECOND queue, does NOT compete with `PaymentEventAuditListener`.
  - `refund.notifications` on `order.events` with routing key exactly `order.refunded` (matched NO binding before — refund was discarded). Did NOT widen `order.state.*` (Pitfall 2).
  - Plus `webhook.deliveries` fanout queue bound to all four families (consumed by 22-05).
- `PaymentEventOutboxFlusher` untouched (Pitfall 3). First-deploy onboarding-backlog re-delivery accepted, no cutoff.
- `MailhogAssertions` test-support: polls `:8025 /api/v2/messages`, filters by recipient + subject substring, clears between tests.

**Task 2 — RecipientResolver + NotificationDispatchService (TDD: RED `48ac892` → GREEN `a192b07`)**
- `RecipientResolver.forEvent` returns the LOCKED per-family audiences (D-04): `order.state.*` → vendor ONLY (customer on the legacy path), `onboarding.state.*` → vendor ONLY, `order.refunded`/`payment.*` → {customer, vendor}. Vendor = `tenants.contact_email` ONLY (omitted when blank — no phantom onboarding-contact fallback). Customer = the order's email.
- `NotificationDispatchService.dispatch`: family → `NotificationCategory` → `ConsentGate.allows` before every send (suppressed ⇒ skipped entirely) → `EmailTemplateRenderer.render` → per-recipient one-click unsubscribe URL (null when the signing secret is unset) → fan to `EmailChannel` + `WhatsAppSmsChannel` (no-op OFF). Best-effort per recipient; the channels never throw, so a dispatch failure cannot poison the listener transaction.
- 8/8 `NotificationDispatchServiceTest`: order/onboarding vendor-only, refund/payment both audiences, suppressed recipient never delivered, transactional message carries a non-null unsubscribe URL, family classification.

**Task 3 — three @RabbitListener consumers + Mailhog landing proofs (`3cc8cc1`)**
- `OrderNotificationListener` (`order.notifications`) → VENDOR order email; `OnboardingNotificationListener` (`onboarding.notifications`) → vendor onboarding email; `FinancialNotificationListener` — two methods on `payment.notifications` (`PaymentEvent`) + `refund.notifications` (`RefundEvent`). Each runs the `OrderStateChangeListener §83-90` `TenantContext.set` + `set_config` GUC preamble in a `try/finally` before delegating to dispatch.
- Integration tests (Testcontainers Postgres, Flyway RLS schema, NOSUPERUSER role-downgrade, real Mailhog): the vendor order email lands ALONGSIDE the legacy customer email with the customer NOT duplicated (COMMS-02); an onboarding stall lands a vendor email (COMMS-01, dead channel bound); a refund lands both a customer and a vendor email; a payment lands both; and no `payment_event_outbox` row is poisoned.

## Verification

- `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.notification.listener.*'` — green: Order 2/2, Onboarding 1/1, Financial 2/2.
- `./gradlew :core-java:test --tests 'uk.jtoye.core.notification.dispatch.*'` — green: 8/8.
- `./gradlew :core-java:test --tests 'EmailNotificationServiceTest'` — green: 10/10 (order-path regression guard, unchanged).
- `EmailNotificationService.java` and `PaymentEventOutboxFlusher.java` are unmodified (verified `git status` clean on both).

## Threat Model Coverage

| Threat ID | Mitigation | Where |
|-----------|-----------|-------|
| T-22-04-01 Cross-tenant recipient resolution | `TenantContext` + transaction-local GUC pinned from `event.tenantId()` before any read; `RecipientResolver` reads only the pinned tenant; proven under NOSUPERUSER | the 3 listeners + integration tests |
| T-22-04-02 2nd listener stealing from an incumbent | each consumer gets its OWN durable queue (order/payment.notifications are separate queues) | `RabbitMQConfig` |
| T-22-04-03 New event type poison-dead-letters | no new producer/outbox type; `PaymentEventOutboxFlusher` untouched; Financial test asserts zero poison rows | Task 1 + Task 3 |
| T-22-04-04 Emailing an opted-out recipient | `ConsentGate.allows` checked before every `EmailChannel.deliver`; suppressed ⇒ skipped | `NotificationDispatchService` + unit test |
| T-22-04-05 Order-email regression / duplicate customer email | legacy `EmailNotificationService` + its test untouched; new order path is VENDOR-ONLY; tests assert exactly one customer order email | Task 3 |
| T-22-04-SC package installs | none installed (N/A) | — |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] MailhogAssertions did not decode RFC 2047 subject headers**
- **Found during:** Task 3 (first integration run — 4/5 failed)
- **Issue:** Mailhog stores the RAW subject header. Subjects contain an em-dash (`—`, non-ASCII), so MimeMessageHelper Q-encodes the whole header and spaces become `_`. Single-word substring matches (e.g. `onboarding`) passed, but multi-word matches (`an update`, `Payment received`, `Refund processed`) missed — even though the emails were genuinely sent (`event=email_sent` logged).
- **Fix:** `firstHeader` now runs `jakarta.mail.internet.MimeUtility.decodeText(raw)` before matching. No new dependency (jakarta.mail ships with spring-boot-starter-mail).
- **Files modified:** `testsupport/MailhogAssertions.java`
- **Verification:** All 5 listener integration tests green after the fix.
- **Committed in:** `3cc8cc1`

**2. [Rule 3 - Blocking] RecipientResolver.forEvent signature includes tenantId**
- **Found during:** Task 2
- **Issue:** The plan wrote `forEvent(eventType, payload)`, but the vendor recipient is `tenants.contact_email` resolved by tenant id, and the four event records share no interface exposing `tenantId()`. Extracting it reflectively would be fragile.
- **Fix:** `forEvent(String eventType, UUID tenantId, Object payload)` — the caller (`NotificationDispatchService`) already holds the pinned `tenantId`. No behavioural change to the LOCKED audiences.
- **Files modified:** `RecipientResolver.java`
- **Committed in:** `a192b07`

**3. [Rule 3 - Blocking] Family enum + Recipient record nested in RecipientResolver**
- **Found during:** Task 2
- **Issue:** The dispatch service needs the same family classification for category + template selection; a shared classifier would need a new file outside the plan's declared list.
- **Fix:** `RecipientResolver.Family` (public, with `classify`) + `RecipientResolver.Recipient` nested types; `NotificationDispatchService` reuses them. Refund family maps to a `refund.*` template key so the renderer picks the refund copy.
- **Files modified:** `RecipientResolver.java`, `NotificationDispatchService.java`
- **Committed in:** `a192b07`

**Total deviations:** 3 (1 Rule-1 test-helper bug, 2 Rule-3 blocking adaptations). No behavioural scope creep — every artifact is within the plan's declared seam and the LOCKED D-04 audiences are unchanged.

## First-Deploy Backlog Decision (recorded, RESEARCH A5)

Binding `onboarding.notifications` will, on first deploy, flush any onboarding-stall events already sitting in the shared outbox that were discarded while the exchange was unbound. This is **ACCEPTED with no cutoff timestamp**: those events are genuine unresolved stalls the vendor was never notified about, at-least-once is the outbox contract, and the `ConsentGate` still applies.

## Known Stubs

None. `WhatsAppSmsChannel` remains an intentional INERT scaffold (COMMS-07, owned by 22-01) — the dispatch fans to it as a real channel that logs a WARN no-op while off; live send is deferred (#208). Not a stub introduced by this plan.

## Issues Encountered

- Java TDD RED for new classes fails to COMPILE rather than assert; handled per the project pattern — minimal `RecipientResolver`/`NotificationDispatchService` stubs shipped in the RED commit (`48ac892`, 7/8 red) then implemented GREEN (`a192b07`).
- The MimeUtility subject-decoding bug (above) — the feature was correct from the first run; only the test helper's subject matching was wrong.

## User Setup Required

None for dev/test (Mailhog via compose). Prod email is SES-over-SMTP config via existing `SMTP_*` env (D-02); the one-click unsubscribe header only appears once `NOTIFICATION_UNSUBSCRIBE_SECRET` is set (inert-safe default — dispatch sends without the header when unset).

## Next Phase Readiness

- **22-05 (webhook delivery engine):** the `webhook.deliveries` fanout queue + its four bindings are declared here; `WebhookFanoutListener` consumes it to INSERT `webhook_delivery` rows.
- The dispatch seam is reusable for any future channel (fan to the `NotificationChannel` list) and for a WhatsApp live-send flip (#208).

## Self-Check: PASSED

- All 10 created files + the 1 modified file verified present on disk.
- All 4 task commits present in `git log`: `7d16733` (Task 1), `48ac892` (RED), `a192b07` (GREEN), `3cc8cc1` (Task 3).
- Green suites: dispatch 8/8, listener integration 5/5 (Order 2, Onboarding 1, Financial 2), EmailNotificationServiceTest 10/10. Frozen files (`EmailNotificationService.java`, `PaymentEventOutboxFlusher.java`) unmodified.

---
*Phase: 22-notifications-comms*
*Completed: 2026-07-15*

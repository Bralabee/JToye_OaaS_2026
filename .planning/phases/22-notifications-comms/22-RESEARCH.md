# Phase 22: Notifications & Comms - Research

**Researched:** 2026-07-14
**Domain:** Event-driven notification infrastructure — transactional email (multipart), GDPR consent/suppression, outbound HMAC-signed webhooks with reliable delivery, channel-provider seam (Spring Boot 3.5.16 / Java 21 / PostgreSQL 15 RLS / RabbitMQ / Next.js 16)
**Confidence:** HIGH (all load-bearing claims verified against live code + official docs)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01 — Branded HTML templates now, with a plain-text alternative.** Every event (order/onboarding/payment/refund, both audiences) gets a branded HTML email (brand header/footer, action button, unsubscribe footer) AND a plain-text `multipart/alternative` part for deliverability. Route through a small renderer seam (subject + html + text per event) so the mechanism is uniform. **Extend, don't replace:** the existing `EmailNotificationService` order path keeps working — migrate its order emails onto the new renderer OR leave them and add the new events alongside, planner's call, but the working order-email test must stay green (COMMS-01).
- **D-02 — Prod email = SES over SMTP config; dev = Mailhog.** Point `spring.mail.*` (already env-driven via `SMTP_*`) at the SES SMTP endpoint in prod; no AWS SES SDK, no bounce/complaint loop this phase.
- **D-03 — Per-category notification preferences.** Categories: **orders / onboarding / financial (payment+refund) / marketing**. Transactional categories default-on under legitimate interest, each with a per-category one-click unsubscribe → suppression; **marketing requires explicit opt-in** (absent → not sent). Preference + suppression tables ENABLE+FORCE RLS, tenant-scoped, proven under the NOSUPERUSER role-downgrade.
- **D-04 — Recipient resolution.** Customer = the order's email (already how `EmailNotificationService` gets the recipient). Vendor = **`tenants.contact_email`** (V48, verified `VARCHAR(320)` on `Tenant.contactEmail`) with the onboarding contact as fallback. Onboarding notifications are **vendor-only** (no J'Toye platform operator — `arch_no_platform_operator`).
- **D-05 — Full entity snapshot wrapped in a versioned event envelope.** Payload = `{ id, type (e.g. "order.ready"), tenantId, occurredAt, version, data: { …full existing DTO } }`. Reuse existing DTOs (`OrderDto`, refund/onboarding DTOs) as the `data` body. Deliveries go to the *vendor's own registered endpoint* carrying the *vendor's own tenant data*. Require **HTTPS-only** subscription URLs + **HMAC-SHA256** signing.
- **D-06 — Per-subscription event-type selection** across the onboarding / order / payment / refund families.
- **D-07 — At-least-once + exponential backoff + auto-pause (Stripe-like).** Receivers dedupe on the envelope `id`. Failed deliveries retry with exponential backoff over a bounded attempt count; a subscription that keeps failing **auto-pauses** and surfaces in the UI. **No head-of-line block** — one failing subscription/endpoint never stalls deliveries to others (per-subscription isolation). Attempt count, backoff schedule, auto-pause threshold, and `webhook_delivery` retention window are all **config-injected** (GLOBAL_RULE_6), not literals.

### Claude's Discretion
- **Consumer topology** — extend per-domain `@RabbitListener` (like `OrderStateChangeListener`) vs a unified `NotificationDispatchService`. Either way: any new event type MUST land its exchange bean + producer + `PaymentEventOutboxFlusher.publishRow` dispatch branch **atomically** (the outbox-flusher poison-dead-letter trap).
- **Webhook delivery mechanism** — reliability-first; the transactional-outbox + `@Scheduled` flusher pattern (`PaymentEventOutboxFlusher`) is the in-repo at-least-once precedent. Resilience4j is available for backoff.
- **HTML template engine** — Thymeleaf (would be added) vs a lightweight string-template + CSS-inliner. Deliverability (text alternative) is the hard requirement.
- **Schemas + migration versions** — exact columns for preference/suppression, `webhook_subscription`, `webhook_delivery`; versions land AFTER V52/V53 under `out-of-order=true`.
- **Signature header format** — Stripe-style `t=<ts>,v1=<hmac>` recommended; event-id + dedup-key scheme.
- **Webhook management UI placement** — a dashboard settings/integrations area; reuse the existing dashboard shell + 375px responsive patterns.
- **Exact per-transition notification matrix** — which state changes fire an email per audience; align with `EmailNotificationService`'s existing order set + Phase 21 onboarding events + the new payment/refund events.

### Deferred Ideas (OUT OF SCOPE)
- AWS SES SDK + bounce/complaint feedback loop — prod uses SES-over-SMTP config this phase.
- Live WhatsApp/SMS delivery — scaffold-only (#208); no provider creds.
- Marketing campaign engine (composition, scheduling, segmentation) — only the opt-in gate + suppression are built.
- Rewriting order notifications into a new framework — extend alongside.
- In-app / web-push notifications — separate phase.
- Customer-facing notification-preference dashboard beyond unsubscribe.
- Delivery-log bulk/backfill replay — single-delivery manual replay only.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| COMMS-01 | Bind dead channels (onboarding/payment/refund); preserve the working order-email path | Topology in Architecture Patterns §Pattern 1; the **discard findings** (onboarding exchange unbound; `order.refunded` matches no binding) in Pitfalls 1 & 2; flusher-trap analysis in Pitfall 3 (spoiler: no new flusher branch needed this phase) |
| COMMS-02 | Templated transactional email to customer + vendor across all lifecycle events | D-01 renderer seam (Code Example 1, `MimeMessageHelper.setText(plain, html)`); recipient resolution D-04 (`Tenant.contactEmail` verified); the "second consumer needs its own queue" topology in Pitfall 2 |
| COMMS-03 | Consent, one-click unsubscribe, suppression (GDPR/PECR) | Schema in Architecture Patterns §Consent schema; stateless HMAC unsubscribe-token scheme (Code Example 3); RFC 8058 `List-Unsubscribe-Post`; public endpoint precedent (`/api/v1/public/**` permitAll + `/track` page) |
| COMMS-04 | Vendor webhook subscriptions (tenant-scoped, RLS) | `webhook_subscription` schema; RLS via `current_tenant_id()` helper (Pitfall 4); secret-rotation contract; RLS test precedent `IdempotencyKeysRlsPolicyIntegrationTest` |
| COMMS-05 | Signed, retried, observable webhook delivery (no head-of-line block) | Dedicated `webhook_delivery` outbox worker (Architecture §Pattern 2); HMAC-SHA256 signing (Code Example 2, Stripe scheme verified); per-subscription isolation via SKIP LOCKED + per-row backoff; auto-pause on `webhook_subscription.status`; prune job (ScheduledCleanupService precedent) |
| COMMS-06 | Webhook management + delivery-log UI (mobile-first 375px) | Dashboard shell + axios `apiClient` (no react-query); nav array in `dashboard-shell.tsx`; new `dashboard/settings/webhooks` route; replay = new tagged attempt row |
| COMMS-07 | WhatsApp/SMS channel seam (scaffold, off by default) | `NotificationChannel` provider abstraction + INERT-by-default pattern mirroring `CompaniesHouseClient` fail-closed / Keycloak-deprovisioning WARN-no-op |
</phase_requirements>

## Summary

Phase 22 is a **consumer-and-governance** phase, not a producer phase. Every event family it needs (order, onboarding, payment, refund) is **already produced through the shared V46 transactional outbox** and flushed by `PaymentEventOutboxFlusher` — the four `publishRow` dispatch branches already exist. The work is to **bind consumers** to the currently-discarded channels, **fan each event to email + webhook**, add the **consent/suppression** governance the platform lacks, stand up the **outbound-webhook delivery machine**, and scaffold a **WhatsApp/SMS seam** — all without regressing the one channel that works (order emails).

Two live-code findings reframe the plan: (1) the onboarding exchange is genuinely unbound (Phase 21 seam, verified), and (2) **refund events are silently discarded today** — `RefundEvent` publishes to `order.events` with key `order.refunded`, which matches no binding (`order.state.*` only). So "payment/refund are audit-only" is only true for payment; refund has no consumer at all. A third finding governs every new table: **V51 removed the raw `current_setting(...)::uuid` cast from all policies and `RlsContractTest.noPolicyUsesRawTenantGucCast` now fails the build if a new policy reintroduces it** — new Comms migrations MUST use the `current_tenant_id()` helper, not the V50 literal form.

The stack needs **zero new external dependencies**: `spring-boot-starter-mail` (MimeMessage/multipart), `spring-boot-starter-webflux` (WebClient), `resilience4j-spring-boot3:2.4.0`, and Jackson are all already present. `javax.crypto.Mac`/HmacSHA256 is JDK-native.

**Primary recommendation:** Use `MimeMessageHelper.setText(plain, html)` for multipart/alternative (no Thymeleaf); a **dedicated `webhook_delivery` outbox table + `@Scheduled` worker** (mirroring `PaymentEventOutboxFlusher`'s SKIP-LOCKED / per-tenant-tx / exponential-backoff shape) for per-subscription-isolated delivery; add **separate durable queues** for each new email consumer (never a second `@RabbitListener` on an existing queue); model all four new tables with **ENABLE+FORCE RLS via `current_tenant_id()`**; and gate WhatsApp/SMS behind an off-by-default flag with a fail-closed/WARN-no-op provider stub.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Event consumption (bind dead channels) | API / Backend (Spring `@RabbitListener`) | — | Events live on RabbitMQ fed by the DB outbox; consumers are backend beans |
| Transactional email render + send | API / Backend (`MimeMessageHelper` + SMTP) | — | SMTP transport is server-side; Mailhog(dev)/SES(prod) |
| Consent / suppression enforcement | Database (RLS) + API (dispatch gate) | — | Suppression is a tenant-scoped DB fact checked before send; RLS is the isolation boundary |
| One-click unsubscribe | API (public no-auth endpoint) | Frontend (confirmation page) | Token-verified public POST writes suppression; a thin public page confirms (like `/track`) |
| Webhook subscription CRUD | API (REST, RLS) + Database | Frontend (management UI) | Tenant-scoped resource; secret is a DB-resident credential (FORCE RLS load-bearing) |
| Webhook delivery (HMAC, retry, isolation) | API / Backend (`@Scheduled` worker + WebClient) | Database (`webhook_delivery` state) | HTTP egress + durable retry state are backend concerns; per-subscription rows give isolation |
| Webhook management + delivery-log + replay | Frontend Server (Next.js dashboard) | API (list/replay endpoints) | Authenticated vendor dashboard screen; reuses dashboard shell |
| WhatsApp/SMS channel seam | API / Backend (provider abstraction) | — | Structural stub; no live egress this phase |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-mail` | 3.5.16 (present) | `MimeMessage`/`MimeMessageHelper` multipart HTML+text email `[VERIFIED: build.gradle]` | Already the transport under `EmailNotificationService`; multipart is a one-method upgrade, no new dep |
| `spring-boot-starter-webflux` | 3.5.16 (present) | `WebClient` for outbound webhook HTTP POST `[VERIFIED: build.gradle]` | Already used by `CompaniesHouseClient`/`FhrsClient` with per-call timeout + circuit breaker |
| `resilience4j-spring-boot3` | 2.4.0 (present) | Circuit breaker / backoff around webhook egress `[VERIFIED: build.gradle]` | Established gate-client pattern (`@CircuitBreaker`) |
| `spring-boot-starter-amqp` | 3.5.16 (present) | `@RabbitListener` consumers + bindings `[VERIFIED: build.gradle]` | The eventing spine |
| `javax.crypto.Mac` (HmacSHA256) | JDK 21 | HMAC-SHA256 webhook + unsubscribe-token signing | JDK-native; no library; constant-time compare via `MessageDigest.isEqual` |
| Jackson `ObjectMapper` | via Spring Boot (present) | Webhook envelope serialization; sign the exact bytes sent | Already the outbox/AMQP converter |
| Flyway | present | `V54+` migrations for the four new tables | House migration mechanism; `out-of-order=true` already set |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Testcontainers `postgres:15` | 1.21.3 (present) | RLS isolation proofs under NOSUPERUSER role-downgrade | Every new RLS table (COMMS-03/04/05) |
| Mailhog | v1.0.1 (compose) | Dev SMTP capture + assertion (`:1025` SMTP, `:8025` UI/API) | Email-landing acceptance tests (COMMS-01/02/03) |
| axios `apiClient` | ^1.15.0 (present) | Frontend data fetching (Bearer + `X-Tenant-Id` interceptors) | Webhook management UI (COMMS-06) — note: **no react-query/SWR in repo** |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `MimeMessageHelper` + inline-styled HTML template | `spring-boot-starter-thymeleaf` | Thymeleaf adds an official starter + template resolver + view-resolution auto-config that overlaps nothing else here (Next.js serves the UI). For a handful of fixed transactional templates it is dependency weight with no payoff, and needs a CSS-inliner anyway (email clients strip `<style>`). Recommend inline-styled Java text-block templates behind an `EmailTemplateRenderer` seam → **zero new deps**, fully unit-testable. Thymeleaf is a fine override if the vendor wants designer-editable templates later. |
| Dedicated `webhook_delivery` table + worker | Reuse `payment_event_outbox` | The payment outbox is a **single ordered drain** — a poison subscription would create head-of-line risk and there is no per-subscription state (auto-pause, consecutive-failures). A dedicated per-`(subscription,event)` delivery table gives independent per-row backoff (no HOL block, COMMS-05) and a natural home for auto-pause + prune. |
| WebClient (reactive, `.block()`) | Java 21 `HttpClient` | Both work; WebClient is the **in-repo precedent** (`CompaniesHouseClient`) with Resilience4j wiring already proven. Prefer consistency. |

**Installation:** No new packages. (Only if the planner overrides D-01 toward Thymeleaf: `implementation("org.springframework.boot:spring-boot-starter-thymeleaf")` — an official Spring Boot starter.)

**Version verification:** All libraries confirmed present in `core-java/build.gradle` at Spring Boot 3.5.16 line-pinned versions `[VERIFIED: build.gradle]`. No registry lookup required because no package is added.

## Package Legitimacy Audit

> This phase installs **no external packages** — the full stack (mail, webflux, resilience4j, amqp, jackson, testcontainers) is already resolved in `core-java/build.gradle` and `frontend/package.json`. slopcheck is therefore N/A (nothing to install).

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| _(none added)_ | — | — | — | — | N/A | No install |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none
**Conditional (only if D-01 overridden to Thymeleaf):** `org.springframework.boot:spring-boot-starter-thymeleaf` — official Spring Boot managed starter, version-aligned to 3.5.16, `[VERIFIED: Spring Boot BOM]`. No slopcheck concern (first-party).

## Architecture Patterns

### System Architecture Diagram

```
                         ┌──────────────────────────────────────────────┐
   State changes         │  Producers (EXIST — unchanged this phase)     │
   (order/onboarding/    │  OrderEventPublisher · OnboardingEventPublisher│
    payment/refund)  ──► │  RefundEventPublisher · PaymentService         │
                         │        │ writes rows to                        │
                         │        ▼  payment_event_outbox (V46, RLS)      │
                         └────────┼───────────────────────────────────────┘
                                  │ @Scheduled flush (SKIP LOCKED, per-tenant tx)
                                  ▼
                    PaymentEventOutboxFlusher.publishRow  ── routes by `exchange` col ──┐
                                  │                                                       │
        ┌─────────────────────────┼───────────────────────────┬──────────────────────┐  │
        ▼ order.events            ▼ onboarding.events          ▼ payment.events        │  │
   (order.state.*)          (onboarding.state.*)          (payment.*)                  │  │
   [bound: KDS/SSE/email]   [UNBOUND today ✗]            [bound: audit only]           │  │
        │  + order.refunded  ────────── matches NO binding → DISCARDED today ✗ ────────┘  │
        │                                                                                  │
   ═════╪══════════════════ NEW IN PHASE 22 (add durable queues, never reuse existing) ═══╪══
        ▼                          ▼                            ▼                          ▼
   order webhook              onboarding.notif q          payment.notif q          (refund binding
   fanout q (order.*)         → email consumer            → email consumer          order.refunded)
        │                          │                            │                          │
        └────────────┬─────────────┴──────────────┬─────────────┴──────────────────────────┘
                     ▼                             ▼
         NotificationDispatchService      WebhookFanoutListener
         (resolve recipients D-04,        (for each ACTIVE subscription
          check consent/suppression,       matching event type → INSERT
          render, send email)              webhook_delivery PENDING row)
                     │                             │
          ┌──────────┼──────────┐                  ▼
          ▼          ▼          ▼        @Scheduled WebhookDeliveryWorker
       Email      Webhook    WhatsApp    (claim PENDING/retry-due per tenant,
       channel    channel    /SMS stub    SKIP LOCKED, HMAC-sign body, WebClient
       (live)     (enqueue)  (OFF flag →   POST, backoff, auto-pause, prune)
                             WARN no-op)              │
                                                      ▼
                                          vendor's HTTPS endpoint
                                          (X-JToye-Signature: t=,v1=)

   Public (no-auth): POST /api/v1/public/unsubscribe?token=<hmac>  → writes suppression row
   Dashboard (authed): /dashboard/settings/webhooks  → CRUD + delivery-log + replay
```

### Recommended Project Structure
```
core-java/src/main/java/uk/jtoye/core/
├── notification/
│   ├── EmailNotificationService.java     # EXISTS — order emails (extend or leave; keep test green)
│   ├── dispatch/
│   │   ├── NotificationDispatchService.java   # NEW — resolve recipient + consent gate + fan to channels
│   │   ├── NotificationChannel.java           # NEW — provider abstraction (email/webhook/whatsapp)
│   │   ├── EmailChannel.java                   # NEW — MimeMessage multipart sender (live)
│   │   └── WhatsAppSmsChannel.java             # NEW — stub, OFF-by-default flag (COMMS-07)
│   ├── template/
│   │   └── EmailTemplateRenderer.java          # NEW — {subject, html, text} per event (D-01 seam)
│   ├── consent/
│   │   ├── NotificationSuppression.java        # NEW entity (RLS)
│   │   ├── SuppressionService.java             # NEW — check + write suppression, verify unsub token
│   │   └── PublicUnsubscribeController.java     # NEW — no-auth token endpoint (/api/v1/public/unsubscribe)
│   └── listener/
│       ├── OnboardingNotificationListener.java # NEW — @RabbitListener on NEW onboarding.notif queue
│       └── FinancialNotificationListener.java  # NEW — @RabbitListener on NEW payment/refund notif queue
├── webhook/
│   ├── WebhookSubscription.java / Repository   # NEW entity (RLS) + REST controller
│   ├── WebhookSubscriptionController.java       # NEW — CRUD/rotate/pause/revoke (RFC 7807)
│   ├── WebhookDelivery.java / Repository        # NEW entity (RLS)
│   ├── WebhookFanoutListener.java               # NEW — @RabbitListener → INSERT delivery rows
│   ├── WebhookDeliveryWorker.java               # NEW — @Scheduled drain + HMAC + WebClient + backoff/pause
│   ├── WebhookSigner.java                        # NEW — HmacSHA256 t=,v1= (JDK Mac)
│   └── WebhookRetentionCleanup.java             # NEW — @Scheduled prune (#107)
└── config/RabbitMQConfig.java                    # EXTEND — new queues + bindings (bind onboarding, add fanout)

core-java/src/main/resources/db/migration/
├── V54__notification_consent.sql   # NEW — notification_suppression (+ marketing opt-in) RLS via current_tenant_id()
├── V55__webhook_subscription.sql   # NEW — webhook_subscription RLS
└── V56__webhook_delivery.sql       # NEW — webhook_delivery RLS + indexes  (versions AFTER reserved V52/V53)

frontend/app/dashboard/settings/webhooks/
├── page.tsx                        # NEW — subscription list + create/pause/revoke/rotate
├── [id]/page.tsx                   # NEW — delivery-log browser + filter + replay
└── __tests__/                      # NEW — Jest render + 375px overflow tests
```

### Pattern 1: Bind a NEW durable queue per new consumer (never a second listener on an existing queue)

**What:** RabbitMQ competing-consumer queues deliver each message to exactly one consumer. Adding a second `@RabbitListener` on `payment.events` would *steal* half the messages from `PaymentEventAuditListener`. Each new consumer gets its **own** durable queue bound to the exchange.

**When to use:** Every new email/webhook consumer in this phase.

**Example:**
```java
// Source: pattern verified against RabbitMQConfig.java (order/payment topology) + OrderSseFanoutListener (second-consumer-needs-own-queue precedent)
// NEW in RabbitMQConfig — bind the previously-unbound onboarding exchange:
@Bean Queue onboardingNotifQueue() {
    return QueueBuilder.durable("onboarding.notifications")
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE).build();   // give it a DLQ
}
@Bean Binding onboardingNotifBinding(Queue onboardingNotifQueue, TopicExchange onboardingEventsExchange) {
    return BindingBuilder.bind(onboardingNotifQueue).to(onboardingEventsExchange).with("onboarding.state.*");
}
// NEW — a SECOND queue on payment.events (does NOT compete with the audit listener):
@Bean Queue paymentNotifQueue() { return QueueBuilder.durable("payment.notifications")... }
@Bean Binding paymentNotifBinding(...) { return ...bind(paymentNotifQueue).to(paymentEventsExchange).with("payment.*"); }
// NEW — refund binding (order.refunded currently matches NOTHING — see Pitfall 2):
@Bean Queue refundNotifQueue() { return QueueBuilder.durable("refund.notifications")... }
@Bean Binding refundNotifBinding(...) { return ...bind(refundNotifQueue).to(orderEventsExchange).with("order.refunded"); }
```

### Pattern 2: Dedicated webhook-delivery outbox + `@Scheduled` worker (per-subscription isolation)

**What:** Mirror `PaymentEventOutboxFlusher`'s proven shape (`FOR UPDATE SKIP LOCKED` claim, **per-tenant transaction**, `computeBackoffMillis` exponential-with-cap, `MAX_ATTEMPTS` → status flip) but keyed per `(subscription, event)` so each subscription's rows back off independently. Fan-out (event→N delivery rows) happens synchronously in a listener; HTTP delivery happens in the scheduled worker.

**When to use:** COMMS-05 (all webhook delivery).

**Why not deliver inline in the `@RabbitListener`:** a synchronous WebClient call + retry blocks the RabbitMQ consumer thread → head-of-line block *within the queue*, and loses durability across restarts. The two-stage (durable enqueue, async deliver) is the transactional-outbox contract and the only shape that satisfies "one failing subscription never stalls others."

**Auto-pause:** track `webhook_subscription.consecutive_failures`; when it crosses the config threshold, set `status = PAUSED`; the worker's claim query skips PAUSED subscriptions. A successful delivery resets the counter.

### Pattern 3: Async worker must re-establish TenantContext (the `@Async`/`@Scheduled` landmine)

**What:** `TenantContext` is a plain `ThreadLocal` with no `TaskDecorator`, so any `@Async`/`@Scheduled` worker doing a tenant-scoped DB write MUST call `TenantContext.set(tenantId)` inside a `try/finally { TenantContext.clear(); }` and iterate **per-tenant, one transaction each** (the RLS GUC is transaction-scoped).

**Example (canonical, verified):**
```java
// Source: GateChainRunner.runAndRecompute (async) + PaymentEventOutboxFlusher.flushTenant + ScheduledCleanupService.cleanupTenant
private void deliverForTenant(UUID tenantId) {
    TenantContext.set(tenantId);
    try {
        transactionTemplate.executeWithoutResult(status -> {
            List<WebhookDelivery> due = repo.claimDueBatch(BATCH_SIZE); // FOR UPDATE SKIP LOCKED
            for (WebhookDelivery d : due) attemptDelivery(d);
        });
    } finally {
        TenantContext.clear();
    }
}
```
Use `TransactionTemplate` (NOT a `@Transactional` private method) — Spring self-invocation would silently start no transaction and run under a NULL tenant (documented trap in `PaymentEventOutboxFlusher` and `ScheduledCleanupService`).

### Anti-Patterns to Avoid
- **Second `@RabbitListener` on an existing queue** → steals messages from the incumbent consumer. Always a new queue (Pitfall 2).
- **Raw `current_setting('app.current_tenant_id', true)::uuid` in a new RLS policy** → fails `RlsContractTest.noPolicyUsesRawTenantGucCast` at build time. Use `current_tenant_id()` (Pitfall 4).
- **Signing a re-serialized body** → the receiver's HMAC won't match. Serialize the envelope once to `byte[]`, sign those exact bytes, POST those exact bytes.
- **Storing the webhook secret hashed** → you must re-sign every delivery, so the plaintext secret is needed; protect it with FORCE RLS (load-bearing, like V50 `response_body`) and return it plaintext only once on create/rotate.
- **Migrating order emails to `MimeMessage` without updating the order test** → `EmailNotificationServiceTest` captures `SimpleMailMessage` and will fail to compile/match (Pitfall 5).
- **Unbounded `webhook_delivery` growth** → #107 accumulator regression. A prune job is mandatory (not optional).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Multipart HTML+text email | Manual MIME assembly / raw `jakarta.mail` | `MimeMessageHelper(msg, true).setText(plain, html)` | One call produces `multipart/alternative` with correct headers/encoding |
| At-least-once delivery + backoff | Ad-hoc retry loop in the listener | The `PaymentEventOutbox` + `@Scheduled` flusher pattern (copy `computeBackoffMillis`, SKIP-LOCKED claim) | Already proven multi-replica-safe (Issue #93); crash-safe |
| HMAC signature | Custom hashing / string concat quirks | `Mac.getInstance("HmacSHA256")` + `MessageDigest.isEqual` (constant-time) + Stripe `t.body` scheme | JDK-native; timing-attack-safe compare; familiar vendor contract |
| HTTP egress with timeout/breaker | New HTTP client | `WebClient` per `CompaniesHouseClient` (timeout + `@CircuitBreaker`, config-injected) | Consistent, tested, config-driven |
| Per-tenant scheduled work under RLS | New tenant-loop | Copy `ScheduledCleanupService`/`flushTenant` (per-tenant tx, `TenantContext` try/finally, `TransactionTemplate`) | Avoids the GUC-scope + self-invocation traps that already bit this codebase |
| RLS isolation proof | Bespoke test harness | Copy `IdempotencyKeysRlsPolicyIntegrationTest` (`rls_test_role` NOSUPERUSER + `SET LOCAL ROLE`) | Superuser bypasses FORCE RLS; the role-downgrade is the only real proof |
| One-click unsubscribe token | A DB token table + expiry sweep | Stateless HMAC token = `hmac(secret, tenantId + "|" + email + "|" + category)` | No storage, no prune, revocation-free; verify on the public endpoint |

**Key insight:** Almost everything this phase needs already has a battle-tested in-repo precedent that survived a QA council. The value is in *wiring proven patterns together correctly*, not inventing delivery/retry/RLS machinery.

## Runtime State Inventory

> Phase 22 extends live runtime (RabbitMQ broker topology, scheduled workers, DB schema). Broker bindings and migration ordering are runtime state a grep won't surface.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `payment_event_outbox` (V46) already carries onboarding/payment/refund/order rows — **producers unchanged**. No new event type needs writing to the outbox this phase. | Code: add **consumers** only. Do NOT add a new outbox event type (avoids the flusher trap entirely — Pitfall 3). |
| Live service config (broker) | RabbitMQ exchanges declared in `RabbitMQConfig`: `onboarding.events` is an **unbound TopicExchange** (no queue); `order.events` binds only `order.state.*` (so `order.refunded` is discarded); `payment.events` has one audit consumer. These bindings are **declared in code** (Spring re-declares on boot) — not UI/DB drift, so they ARE in git. | Code: add durable queues + bindings (Pattern 1). Re-declared idempotently on container restart. |
| OS-registered state | None — no cron/Task Scheduler entries; scheduling is in-JVM `@Scheduled`. | None — verified: all scheduling is `@Scheduled` beans (`PaymentEventOutboxFlusher`, `ScheduledCleanupService`). |
| Secrets/env vars | `SMTP_*` (host/port/user/pass/auth/starttls) drive `spring.mail.*`; dev=Mailhog, prod=SES-over-SMTP (D-02). New config keys needed: from-address (exists: `NOTIFICATION_EMAIL_FROM`), webhook retry/backoff/pause/retention tunables, WhatsApp flag+creds — all via `@Value`/`@ConfigurationProperties` (GLOBAL_RULE_6). | Config only: add keys to `application.yml` with env defaults; no secret rename. |
| Build artifacts / migrations | Current max migration = **V51** (verified). V52 (`shop_staff`, Phase 23) and V53 (`media_asset`, Phase 24) are **reserved** for later phases that build first-in-sequence; Comms migrations take **V54+** with `out-of-order=true` (verified set in `application.yml`, staging, prod). | Migration: number Comms files V54/V55/V56; do NOT consume V52/V53. |

**The canonical question — after every repo file is updated, what runtime still holds old state?** Nothing stale: this phase adds runtime (queues, workers, tables), it does not rename existing runtime state. The one live-topology fact to internalize is that binding a queue to `onboarding.events`/`order.refunded` starts delivering events that were previously discarded — expect a backlog of already-outboxed onboarding-stall events to flush to the new consumer on first deploy (they are re-published at-least-once).

## Common Pitfalls

### Pitfall 1: The onboarding exchange is unbound — and so is refund
**What goes wrong:** Assuming events "flow" because producers exist. `onboarding.events` has no queue (Phase 21 seam); publishing there discards the message at the exchange.
**Why it happens:** Topic exchanges silently drop messages that match no binding (no dead-letter).
**How to avoid:** Add durable queues + bindings (Pattern 1). Verify by watching Mailhog after an onboarding stall.
**Warning signs:** "Persisted onboarding stall event to outbox" logs with no downstream email.
`[VERIFIED: RabbitMQConfig.java lines 156-159 — lone TopicExchange, no @Bean Queue/Binding]`

### Pitfall 2: `order.refunded` matches no binding today (refund has NO consumer)
**What goes wrong:** The SPEC says "payment/refund are audit-only," but `RefundEvent` routes to `order.events` with key `order.refunded`, and the only `order.events` bindings are `order.state.*` (durable queue + SSE fanout). `order.refunded` does not match `order.state.*` → **discarded**. Payment is audit-only via `payment.events`; refund is dropped entirely.
**Why it happens:** Refund reuses the order exchange (V36 per-row routing) but its key falls outside the order-state pattern.
**How to avoid:** The new refund consumer needs an explicit binding for `order.refunded` (Pattern 1). Do NOT widen the existing `order.state.*` binding to `order.*` — that would double-deliver order-state events to the KDS/email path.
**Warning signs:** No refund email/webhook ever fires despite refunds succeeding.
`[VERIFIED: RabbitMQConfig ORDER_EVENTS_ROUTING_PATTERN="order.state.*"; RefundEvent javadoc key="order.refunded"; no other order binding]`

### Pitfall 3: The outbox-flusher poison trap — but no new flusher branch is needed this phase
**What goes wrong:** The `outbox_flusher_dispatch_trap` memory warns that a new event type without a `publishRow` dispatch branch is poison-cast to `PaymentEvent` and dead-lettered. Planners may over-apply this and add unnecessary producer/flusher changes.
**The nuance (verified):** All four families already have dispatch branches — `order.events`→`OrderStateChangeEvent`/`RefundEvent` (by routing-key prefix), `onboarding.events`→`OnboardingStateChangeEvent`, else→`PaymentEvent`. **This phase adds consumers, not producers**, so it does NOT touch `publishRow`. The trap only bites if a task chooses to push a *brand-new* event type through the shared `payment_event_outbox` — which webhook deliveries must NOT do (they use the dedicated `webhook_delivery` table).
**How to avoid:** Do not route webhook/notification internal events through `payment_event_outbox`. If any task ever adds a new outbox exchange, it MUST land the exchange bean + producer + `publishRow` branch atomically.
**Warning signs:** Rows in `payment_event_outbox` flipping `poison=true` with "payload deserialization failed".
`[VERIFIED: PaymentEventOutboxFlusher.publishRow lines 264-275 — the four-way dispatch + poison else]`

### Pitfall 4: New RLS policies must use `current_tenant_id()`, not the raw cast
**What goes wrong:** Copying the V50 `idempotency_keys` policy form (`current_setting('app.current_tenant_id', true)::uuid`) into a new migration → `RlsContractTest.noPolicyUsesRawTenantGucCast` fails the build. V51 deliberately removed the raw cast from all 10 remaining policies (22P02 error-class fix) and installed a permanent `pg_policy` sweep.
**Why it happens:** V50 is the most recent RLS table and its literal is the wrong template now.
**How to avoid:** Model new policies on V51's form: `USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())`. Keep `ENABLE` + `FORCE ROW LEVEL SECURITY`.
**Warning signs:** `RlsContractTest` red with "policies still using the raw current_setting(...) cast".
`[VERIFIED: V51__rls_uuid_cast_safety.sql lines 54,86-87,116-117; RlsContractTest.java line 218 noPolicyUsesRawTenantGucCast]`

### Pitfall 5: The order-email test captures `SimpleMailMessage`
**What goes wrong:** `EmailNotificationServiceTest` uses `ArgumentCaptor<SimpleMailMessage>` and `verify(mailSender).send(messageCaptor.capture())`. Switching `EmailNotificationService` to `MimeMessage` breaks these tests (wrong overload captured), violating COMMS-01's "order-email test stays green."
**Why it happens:** `MimeMessageHelper` sends a `MimeMessage`, not a `SimpleMailMessage`.
**How to avoid — two green paths:**
- **(A) Additive, zero test-touch (lowest risk):** leave `EmailNotificationService` + its test exactly as-is (order emails stay text-only for now); add the new event types on the new `EmailChannel`/`MimeMessageHelper` renderer. Order emails can be upgraded to HTML in a follow-up.
- **(B) Uniform renderer (D-01's "uniform mechanism"):** route order emails through the new multipart renderer too, and **rewrite** `EmailNotificationServiceTest` to assert against `MimeMessage` (`verify(mailSender).send(any(MimeMessage.class))` + parse content) — behavior preserved (same recipient/subject/body-contains), test updated. Defensible under Incremental Betterment (order emails still send, now bettered to HTML+text) but it *does* modify the test file.
**Recommendation:** (A) for the order path to guarantee no regression, with all NEW events on the multipart renderer; optionally schedule (B) as an explicit, separately-verified task.
`[VERIFIED: EmailNotificationServiceTest.java lines 35-36, 78 — ArgumentCaptor<SimpleMailMessage>]`

### Pitfall 6: Signing bytes must equal sent bytes
**What goes wrong:** Serialize envelope → sign object A → re-serialize when POSTing → receiver computes HMAC over B ≠ A → every signature "invalid."
**How to avoid:** `byte[] body = objectMapper.writeValueAsBytes(envelope);` sign `body`, then send `body`. Never serialize twice.

## Code Examples

### Code Example 1: Multipart HTML+text email (no new dependency)
```java
// Source: Context7 /spring-projects/spring-framework email.adoc (MimeMessageHelper) + jakarta.mail (present via spring-boot-starter-mail)
public void send(String to, String from, String subject, String htmlBody, String textBody) {
    MimeMessage mime = mailSender.createMimeMessage();
    try {
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8"); // multipart
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        // TWO-ARG overload => multipart/alternative: plain-text first, HTML second (deliverability, D-01)
        helper.setText(textBody, htmlBody);
        // RFC 8058 one-click unsubscribe (Gmail/Yahoo bulk requirement):
        mime.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
        mime.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        mailSender.send(mime);
    } catch (MessagingException | MailException e) {
        log.error("Email send failed to {}: {}", to, e.getMessage()); // swallow like the order path
    }
}
```

### Code Example 2: HMAC-SHA256 webhook signature (Stripe `t=,v1=` scheme, verified)
```java
// Source: https://docs.stripe.com/webhooks/signatures — signed_payload = timestamp + "." + rawBody
public String sign(byte[] rawBody, String signingSecret, long unixTs) {
    try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(Long.toString(unixTs).getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        mac.update(rawBody);
        String hex = HexFormat.of().formatHex(mac.doFinal());
        return "t=" + unixTs + ",v1=" + hex;  // header: X-JToye-Signature
    } catch (GeneralSecurityException e) {
        throw new IllegalStateException("HMAC failed", e);
    }
}
// Receiver contract (documented for vendors): recompute HMAC over `t + "." + rawBody`,
// compare with MessageDigest.isEqual (constant-time), reject if |now - t| > tolerance (config default 300s).
```
Also send `X-JToye-Event-Id` (= envelope `id`, the dedupe key) and `X-JToye-Event-Type`.

### Code Example 3: Stateless one-click unsubscribe token (no token table)
```java
// token = base64url( hmac_sha256(appSecret, tenantId + "|" + email + "|" + category) )
// GET/POST /api/v1/public/unsubscribe?tenant=..&email=..&category=orders&token=..
// verify: constant-time compare recomputed token; on match INSERT suppression row (tenant-scoped) idempotently.
// No expiry, no storage, no prune — an unsubscribe link never "expires" (PECR-friendly).
```

### Code Example 4: RLS policy for a new table (V51 helper form — build-safe)
```sql
-- Source: V51__rls_uuid_cast_safety.sql (current_tenant_id() helper) — required by RlsContractTest
CREATE TABLE webhook_subscription (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    target_url     TEXT NOT NULL,               -- HTTPS-only enforced in app layer (D-05)
    event_types    TEXT[] NOT NULL,             -- selected families (D-06)
    signing_secret TEXT NOT NULL,               -- credential → FORCE RLS is load-bearing
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/PAUSED/REVOKED
    consecutive_failures INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE webhook_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY webhook_subscription_tenant ON webhook_subscription
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| RLS policy `current_setting('app.current_tenant_id', true)::uuid` | `current_tenant_id()` helper (NULL-safe, filtered not errored) | V51 (2026-07, Issue #113) | New policies MUST use the helper or fail `RlsContractTest` |
| Text-only `SimpleMailMessage` | `MimeMessageHelper` multipart/alternative | This phase (D-01) | New events HTML+text; order path additive-safe |
| Bulk email without one-click unsubscribe | RFC 8058 `List-Unsubscribe-Post: List-Unsubscribe=One-Click` | Gmail/Yahoo 2024 bulk-sender rules | Deliverability requires the header for any non-trivial volume |
| Inline single-attempt retry | Transactional-outbox + `@Scheduled` SKIP-LOCKED worker | Issue #93 (in-repo) | The only shape giving crash-safe, HOL-free delivery |

**Deprecated/outdated:**
- Copying V50's raw-cast RLS policy — superseded by V51's helper.
- Stripe `v0` signature scheme — test-only; production is `v1` (HMAC-SHA256).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Comms migrations should take V54/V55/V56 (V52/V53 reserved for Phases 23/24 that execute first-in-sequence) | Runtime State Inventory | If Phases 23/24 haven't reserved those numbers yet, a plain sequential V52 would work — but the milestone sequencing note (`project_v23_sequencing`) locks the reservation; confirm at plan time. Low risk (out-of-order=true absorbs either choice). |
| A2 | `RefundEvent` is genuinely discarded today (no `order.refunded` binding anywhere) | Pitfall 2 | Verified no binding in `RabbitMQConfig`; a binding could theoretically exist elsewhere via `@RabbitListener(bindings=...)`. Planner should grep for `order.refunded` listeners before finalizing. Medium risk to the "refund audit-only" framing. |
| A3 | Storing the webhook `signing_secret` as plaintext under FORCE RLS is acceptable (mirrors V50 PII-in-`response_body`) | Architecture / Code Example 4 | If the security review requires at-rest encryption of the secret beyond RLS, add a crypto layer. Confirm against the phase threat model. |
| A4 | Header name `X-JToye-Signature` / event-id `X-JToye-Event-Id` | Code Example 2 | Cosmetic; vendor-facing contract — lock the exact names during planning so docs match. Low risk. |
| A5 | On first deploy, previously-outboxed onboarding-stall rows will flush to the newly-bound queue (backlog delivery) | Runtime State Inventory | If undesired (e.g. stale stalls emailing vendors), the plan may need a cutoff timestamp. Medium risk — call out in acceptance. |

**Note:** No package/version claim is `[ASSUMED]` — the stack is fully present and verified. The assumptions above are design/sequencing choices for the planner to confirm, per the discretionary areas in CONTEXT.md.

## Open Questions

1. **Do refund events reach ANY consumer today?**
   - What we know: `order.events` binds only `order.state.*`; `RefundEvent` uses key `order.refunded`.
   - What's unclear: whether a `@RabbitListener(bindings=@QueueBinding(...))` declares a `order.refunded` binding outside `RabbitMQConfig`.
   - Recommendation: grep `order.refunded` and `RefundEvent` listeners before planning; if truly unconsumed, the plan's refund binding is *also* fixing a latent gap (surface it as a win).

2. **Marketing opt-in storage model — unified preference table vs two tables?**
   - What we know: D-03 wants transactional=default-on-with-suppression, marketing=explicit-opt-in.
   - What's unclear: whether to model as one `notification_preference(state)` table or `notification_suppression` (opt-out) + `marketing_consent` (opt-in).
   - Recommendation: `notification_suppression` (per-category opt-out rows) + a `marketing_opt_in` boolean/row keyed on recipient; both RLS. Simpler to reason about than a tri-state preference. Planner's discretion.

3. **First-deploy onboarding-stall backlog** — see Assumption A5. Decide whether to deliver historical stalls or add a cutoff.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| RabbitMQ | Event consumption + fanout | ✓ | 3.12-management-alpine (compose) | — |
| PostgreSQL 15 | New RLS tables | ✓ | 15-alpine | — |
| Mailhog | Dev email-landing tests | ✓ | v1.0.1 (`:1025`/`:8025`) | — |
| SMTP (SES) | Prod email transport | config-only | — (env `SMTP_*`) | Mailhog in dev; no code change (D-02) |
| Testcontainers Docker | RLS isolation tests | ✓ (CI `integrationTest` job) | postgres:15 | — |
| WhatsApp/SMS provider | COMMS-07 live send | ✗ (deferred) | — | Scaffold + OFF flag → WARN no-op (in scope) |

**Missing dependencies with no fallback:** none blocking. WhatsApp/SMS provider is intentionally absent — the scaffold is the deliverable.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework (Java) | JUnit 5 + Mockito + Spring Boot Test + Testcontainers 1.21.3 |
| Framework (frontend) | Jest 29.7.0 + @testing-library/react + Playwright 1.59.1 |
| Config file | `core-java/build.gradle` (`test` + `integrationTest` tasks, `@Tag("testcontainers")`); `frontend/jest.config.*` |
| Quick run command | `cd core-java && ./gradlew test --tests '*Notification*' --tests '*Webhook*'` ; `cd frontend && npm test -- webhooks` |
| Full suite command | `cd core-java && ./gradlew test integrationTest` ; `cd frontend && npm test && npm run build && npx playwright test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| COMMS-01 | Onboarding stall now lands in Mailhog (dead channel bound) | integration | `./gradlew integrationTest --tests '*OnboardingNotification*'` | ❌ Wave 0 |
| COMMS-01 | Existing order-email test still green (no regression) | unit | `./gradlew test --tests 'EmailNotificationServiceTest'` | ✅ (must stay untouched — path A) |
| COMMS-01 | No event type poison-dead-letters (flusher covers all) | integration | `./gradlew integrationTest --tests '*OutboxFlusher*'` | ✅ extend |
| COMMS-02 | Each event type → correct recipient set (customer/vendor) | unit+integration | `./gradlew test --tests '*NotificationDispatch*'` | ❌ Wave 0 |
| COMMS-02 | Refund → both customer + vendor email in Mailhog | integration | `./gradlew integrationTest --tests '*FinancialNotification*'` | ❌ Wave 0 |
| COMMS-03 | Unsubscribe link writes suppression; next event sends NO email | integration | `./gradlew integrationTest --tests '*Suppression*'` | ❌ Wave 0 |
| COMMS-03 | Marketing send with no opt-in refused | unit | `./gradlew test --tests '*ConsentGate*'` | ❌ Wave 0 |
| COMMS-03 | Suppression tenant-isolated under NOSUPERUSER | integration (RLS) | `./gradlew integrationTest --tests '*SuppressionRlsPolicy*'` | ❌ Wave 0 (copy `IdempotencyKeysRlsPolicyIntegrationTest`) |
| COMMS-04 | Subscription CRUD; secret rotation invalidates old sig | unit+integration | `./gradlew test --tests '*WebhookSubscription*'` | ❌ Wave 0 |
| COMMS-04 | Cross-tenant subscription list empty/403 under NOSUPERUSER | integration (RLS) | `./gradlew integrationTest --tests '*WebhookSubscriptionRlsPolicy*'` | ❌ Wave 0 |
| COMMS-05 | Delivered payload carries verifiable HMAC-SHA256 | unit | `./gradlew test --tests 'WebhookSignerTest'` | ❌ Wave 0 |
| COMMS-05 | Failing endpoint retries w/ backoff → `failed`; healthy sub still delivered (no HOL block) | integration | `./gradlew integrationTest --tests '*WebhookDeliveryWorker*'` (2 subs: one 500, one 200) | ❌ Wave 0 |
| COMMS-05 | Retention job prunes rows older than window | integration | `./gradlew integrationTest --tests '*WebhookRetention*'` | ❌ Wave 0 |
| COMMS-06 | Create/list/filter/replay UI; 375px no overflow | jest+playwright | `npm test -- webhooks && npx playwright test webhooks` | ❌ Wave 0 |
| COMMS-07 | Flag OFF → email+webhook deliver, zero WhatsApp errors; flag ON no creds → WARN no-op not crash | unit | `./gradlew test --tests '*WhatsAppSmsChannel*'` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests '<touched>*'` (< 30s) + `npm test -- <touched>`.
- **Per wave merge:** `./gradlew test integrationTest` + `npm test`.
- **Phase gate:** full Java + integration + Jest + Playwright green, containers rebuilt (CLAUDE.md Docker rule), then `scripts/docs-freshness.sh --write` → commit reconciled `docs/metrics.json` (baseline **1300** total invocations, schema **51→56**) → docs-freshness CI green.

### Wave 0 Gaps
- [ ] `OnboardingNotificationListenerIntegrationTest` — COMMS-01 (Mailhog landing)
- [ ] `NotificationDispatchServiceTest` + `FinancialNotificationListenerIntegrationTest` — COMMS-02
- [ ] `SuppressionServiceTest` + `SuppressionRlsPolicyIntegrationTest` + `ConsentGateTest` — COMMS-03 (copy the `rls_test_role` harness)
- [ ] `WebhookSubscriptionControllerTest` + `WebhookSubscriptionRlsPolicyIntegrationTest` — COMMS-04
- [ ] `WebhookSignerTest` + `WebhookDeliveryWorkerIntegrationTest` (HOL-block scenario) + `WebhookRetentionCleanupTest` — COMMS-05
- [ ] `frontend/app/dashboard/settings/webhooks/__tests__/` (Jest render + 375px) + `frontend/e2e/webhooks.spec.ts` (Playwright) — COMMS-06
- [ ] `WhatsAppSmsChannelTest` — COMMS-07
- [ ] Shared: a Mailhog assertion helper (query `http://mailhog:8025/api/v2/messages`) for integration tests
- [ ] `EmailNotificationServiceTest` — leave untouched under path A (or rewrite to `MimeMessage` under path B, separately verified)

## Security Domain

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Webhook CRUD behind existing JWT/OAuth2 resource server; public unsubscribe uses an HMAC token, not a session |
| V3 Session Management | no | Public unsubscribe is stateless-token, not session-based |
| V4 Access Control | yes | Tenant isolation via ENABLE+FORCE RLS on all 4 new tables, proven under NOSUPERUSER (`IdempotencyKeysRlsPolicyIntegrationTest` pattern) |
| V5 Input Validation | yes | `@Valid` request DTOs (Jakarta Validation); HTTPS-only + URL validation on `target_url`; reject non-https subscription URLs |
| V6 Cryptography | yes | HMAC-SHA256 via JDK `Mac`; constant-time compare (`MessageDigest.isEqual`); never hand-roll |
| V7 Error/Logging | yes | RFC 7807 via `GlobalExceptionHandler`; never log `signing_secret` or unsubscribe token (mirror `CompaniesHouseClient` "log status not key") |
| V9 Communications | yes | Outbound webhook URLs HTTPS-only; consider SSRF guard (block internal/link-local targets) on `target_url` |
| V13 API | yes | Versioned webhook envelope (`version` field); typed machine-parseable payloads; idempotent replay (envelope `id` dedupe) — the AI-agent-readiness cross-cutting contract |

### Known Threat Patterns for {Spring/RabbitMQ/webhooks}
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Cross-tenant read of `signing_secret`/PII in delivery rows | Information Disclosure | ENABLE+FORCE RLS via `current_tenant_id()`; NOSUPERUSER-proven tests |
| Webhook payload tampering / forged sender | Tampering / Spoofing | HMAC-SHA256 `t=,v1=` signature; per-subscription secret; rotation |
| Replay of a captured webhook | Repudiation | Signed timestamp + receiver tolerance (config); envelope `id` dedupe |
| SSRF via attacker-set `target_url` (internal metadata endpoints) | Elevation / Info Disclosure | HTTPS-only + block private/link-local/loopback ranges; egress timeout |
| Unsubscribe-link forgery (suppress a competitor's emails) | Tampering | HMAC-signed token binding tenant+email+category; constant-time verify |
| Log leakage of secrets/tokens | Information Disclosure | Redacted `toString`; log identifiers + status only |
| Head-of-line block starving other tenants' deliveries | Denial of Service | Per-`(subscription,event)` rows + SKIP LOCKED + per-tenant tx + auto-pause |

## Sources

### Primary (HIGH confidence)
- Live codebase (verified this session): `EmailNotificationService.java`, `PaymentEventOutboxFlusher.java`, `RabbitMQConfig.java`, `OrderStateChangeListener.java`, `PaymentEventAuditListener.java`, `OnboardingEventPublisher.java`, `GateChainRunner.java`, `ScheduledCleanupService.java`, `CompaniesHouseClient.java`, `Tenant.java`, `OrderDto.java`, `RefundEvent.java`, `PaymentEvent.java`, `V50__idempotency_keys.sql`, `V51__rls_uuid_cast_safety.sql`, `RlsContractTest.java`, `IdempotencyKeysRlsPolicyIntegrationTest.java`, `EmailNotificationServiceTest.java`, `application.yml`, `build.gradle`, `docs/metrics.json`, `docker-compose.full-stack.yml`, `frontend/lib/api-client.ts`, `frontend/components/dashboard/dashboard-shell.tsx`.
- Context7 `/spring-projects/spring-framework` — `MimeMessageHelper` multipart (email.adoc).
- Stripe official docs — https://docs.stripe.com/webhooks/signatures (signed_payload = `timestamp + "." + body`, HMAC-SHA256, `t=,v1=`, tolerance).

### Secondary (MEDIUM confidence)
- CLAUDE.md V51 migration description (schema history) — cross-checked against the V51 SQL file.
- Memory notes: `outbox_flusher_dispatch_trap`, `arch_no_platform_operator`, `project_comms_phase_decision`, `project_v23_sequencing` — cross-checked against live code where load-bearing.

### Tertiary (LOW confidence)
- RFC 8058 `List-Unsubscribe-Post` one-click (industry standard; not verified against a specific J'Toye deliverability requirement — flagged as a deliverability recommendation, not a locked need).

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every library verified present in `build.gradle`; zero new deps.
- Architecture / topology: HIGH — bindings, flusher dispatch, async-tenant pattern, prune pattern all read from source.
- Pitfalls: HIGH — each traced to a specific line (unbound exchange, `order.refunded` miss, V51 helper + RlsContractTest guard, SimpleMailMessage capture).
- HMAC scheme: HIGH — Stripe official docs.
- Email templating recommendation: MEDIUM-HIGH — MimeMessageHelper verified; "avoid Thymeleaf" is a reasoned recommendation the planner may override.
- Schema column choices: MEDIUM — precedent-grounded but discretionary (D-03/D-07 leave columns to the planner).

**Research date:** 2026-07-14
**Valid until:** 2026-08-13 (stable stack; 30 days) — re-verify max migration version and `docs/metrics.json` baseline at plan time, as Phases 23/24 sequencing may shift reserved versions.

# Phase 22: Notifications & Comms - Pattern Map

**Mapped:** 2026-07-15
**Files analyzed:** 34 (28 new + 6 modified) across backend, migrations, tests, frontend
**Analogs found:** 31 / 34 (3 genuinely-novel: `WebhookSigner`, `NotificationChannel` abstraction, HMAC unsubscribe token — each has a near-analog + a verified code recipe in RESEARCH)

> **Source-of-truth note:** RESEARCH.md §Recommended Project Structure is the authoritative file list; where UI-SPEC.md diverges (route placement), UI-SPEC wins for the frontend surface. The one live-code divergence to internalize: **the webhook dashboard route is flat `/dashboard/webhooks` (UI-SPEC DECIDED), NOT `dashboard/settings/webhooks` (RESEARCH draft)** — no `settings/` shell exists.
>
> **Verified this session (grep):** `order.refunded` is emitted by `RefundEventPublisher.java:28` but has **NO binding anywhere** (no `@QueueBinding`, nothing in `RabbitMQConfig`) → refund events are genuinely discarded today (RESEARCH Pitfall 2 / Assumption A2 CONFIRMED — the refund binding is *also* closing a latent gap). Current max migration = **V51** (Comms → V54/V55/V56). Metrics baseline = **1300 invocations / schema 51** (`docs/metrics.json`).

---

## File Classification

### Backend — consumers, dispatch, channels

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|-------------------|------|-----------|----------------|-------|
| `config/RabbitMQConfig.java` **(MODIFY)** | config | pub-sub | itself (order/payment topology §47-145) | exact |
| `notification/listener/OnboardingNotificationListener.java` | listener | event-driven | `order/OrderStateChangeListener.java` | exact |
| `notification/listener/FinancialNotificationListener.java` | listener | event-driven | `order/OrderStateChangeListener.java` + `payment/PaymentEventAuditListener.java` | exact |
| `notification/dispatch/NotificationDispatchService.java` | service | event-driven | `order/OrderStateChangeListener.java` (recipient resolve + gate) | role-match |
| `notification/dispatch/NotificationChannel.java` | interface | — | *(novel abstraction; provider-stub shape from CompaniesHouseClient)* | partial |
| `notification/dispatch/EmailChannel.java` | service | request-response | `notification/EmailNotificationService.java` + RESEARCH Code Ex.1 | exact |
| `notification/dispatch/WhatsAppSmsChannel.java` | service | request-response | `tenant/keycloak/KeycloakDeprovisionService.java` (INERT-by-default) | role-match |
| `notification/template/EmailTemplateRenderer.java` | utility | transform | `notification/EmailNotificationService.java` (inline text-block templates) | role-match |

### Backend — consent / suppression

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|-------------------|------|-----------|----------------|-------|
| `notification/consent/NotificationSuppression.java` (+ Repository) | model | CRUD | `review/Review.java` / idempotency_keys entity | role-match |
| `notification/consent/SuppressionService.java` | service | CRUD | `notification/EmailNotificationService` gate + `WebhookSigner` (HMAC verify) | partial |
| `notification/consent/PublicUnsubscribeController.java` | controller | request-response (no-auth) | `storefront/PublicStorefrontController.java` | exact |

### Backend — webhooks

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|-------------------|------|-----------|----------------|-------|
| `webhook/WebhookSubscription.java` (+ Repository) | model | CRUD | `review/Review.java` (RLS entity) | role-match |
| `webhook/WebhookSubscriptionController.java` | controller | CRUD + actions | `shop/PromotionController.java` + `payment/RefundController.java` (rotate/pause = custom POST + Idempotency-Key) | exact |
| `webhook/WebhookSubscriptionService.java` | service | CRUD | `review/ReviewService.java` (tenant-scoped @Transactional) | role-match |
| `webhook/WebhookDelivery.java` (+ Repository) | model | CRUD | `payment/PaymentEventOutbox` (status/attempts/backoff row) | role-match |
| `webhook/WebhookFanoutListener.java` | listener | event-driven | `payment/PaymentEventAuditListener.java` (2nd consumer, own queue) | exact |
| `webhook/WebhookDeliveryWorker.java` | service | batch / @Scheduled | `payment/PaymentEventOutboxFlusher.java` + `onboarding/client/CompaniesHouseClient.java` (WebClient egress) | exact |
| `webhook/WebhookSigner.java` | utility | transform | *(novel — JDK `Mac`; RESEARCH Code Ex.2 Stripe scheme)* | partial |
| `webhook/WebhookRetentionCleanup.java` | service | batch / @Scheduled | `config/ScheduledCleanupService.java` | exact |

### Backend — config + migrations

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|-------------------|------|-----------|----------------|-------|
| `webhook/WebhookProperties.java` / `notification/NotificationProperties.java` | config | — | `tenant/keycloak/KeycloakAdminProperties.java` (`@ConfigurationProperties`, masked secret) | exact |
| `db/migration/V54__notification_consent.sql` | migration | — | `V51__rls_uuid_cast_safety.sql` (helper-form policy) | exact |
| `db/migration/V55__webhook_subscription.sql` | migration | — | `V50` shape + `V51` policy helper | exact |
| `db/migration/V56__webhook_delivery.sql` | migration | — | `V50` shape + `V51` policy helper | exact |

### Tests

| New/Modified File | Role | Analog | Match |
|-------------------|------|--------|-------|
| `*RlsPolicyIntegrationTest.java` (suppression, webhook_subscription, webhook_delivery) | test | `common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java` | exact |
| `security/RlsContractTest.java` | test (auto-covers) | itself — `noPolicyUsesRawTenantGucCast` sweep already guards new tables | exact |
| `notification/EmailNotificationServiceTest.java` **(LEAVE UNTOUCHED)** | test | itself — `ArgumentCaptor<SimpleMailMessage>` §36 breaks if order path migrates to MimeMessage (Pitfall 5, path A) | exact |
| `WebhookSignerTest`, `NotificationDispatchServiceTest`, `WhatsAppSmsChannelTest`, worker/retention integration tests | test | new (see RESEARCH §Wave 0 Gaps) | — |

### Frontend

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|-------------------|------|-----------|----------------|-------|
| `app/dashboard/webhooks/page.tsx` | component (page) | CRUD/list | `app/dashboard/orders/page.tsx` | exact |
| `app/dashboard/webhooks/[id]/page.tsx` | component (page) | CRUD/list | `app/dashboard/orders/page.tsx` (statusConfig + filter Select + table/cards) | exact |
| `components/dashboard/webhooks/WebhookCreateDialog.tsx` | component | request-response | `components/dashboard/orders/RefundDialog.tsx` | exact |
| `components/dashboard/webhooks/SecretRevealDialog.tsx` | component | — | `RefundDialog.tsx` (Dialog + readOnly Input) | role-match |
| `components/dashboard/webhooks/status-badge.tsx` | utility (component) | transform | `orders/page.tsx` `statusConfig` map §73-112 | exact |
| `app/unsubscribe/page.tsx` | component (public page) | request-response (no-auth) | `app/track/page.tsx` | exact |
| `components/dashboard/sidebar.tsx` **(MODIFY)** | config | — | itself — `navigation` array §25-38 | exact |
| `lib/api-client` webhook methods **(ADD)** | utility | request-response | `lib/api-client.ts` / `lib/public-api-client.ts` | exact |
| `app/sitemap.ts` **(MODIFY — exclude /unsubscribe)** | config | — | itself | exact |

---

## Pattern Assignments

### `config/RabbitMQConfig.java` (MODIFY — config, pub-sub)

**Analog:** itself. The exact bean triplet to add for each new consumer is already exemplified by the payment topology (§122-145). **Anti-pattern (Pitfall 1/2):** never add a 2nd `@RabbitListener` to an existing queue — it steals messages via competing-consumer semantics. Every new consumer gets its **own durable queue + binding**.

**Existing durable queue + DLX pattern to copy** (§57-73, 122-139):
```java
@Bean public Queue paymentEventsQueue() {
    return QueueBuilder.durable(PAYMENT_EVENTS_QUEUE)
            .withArgument("x-dead-letter-exchange", PAYMENT_EVENTS_DLX).build();
}
@Bean public Binding paymentEventsBinding(Queue paymentEventsQueue, TopicExchange paymentEventsExchange) {
    return BindingBuilder.bind(paymentEventsQueue).to(paymentEventsExchange).with("payment.*");
}
```

**Three NEW bindings required** (the unbound exchange + the two discarded channels):
- Bind the **already-declared** `onboardingEventsExchange()` (§156-159, currently a lone `TopicExchange`) → new durable `onboarding.notifications` queue with routing `onboarding.state.*`.
- New durable `payment.notifications` queue on `paymentEventsExchange` (`payment.*`) — a SECOND queue, does NOT compete with `PaymentEventAuditListener`.
- New durable `refund.notifications` queue on `orderEventsExchange` with routing key **`order.refunded`** (verified: this key currently matches nothing — `ORDER_EVENTS_ROUTING_PATTERN = "order.state.*"` §22). Do NOT widen `order.state.*` to `order.*` (would double-deliver to the KDS/email path).

**Do NOT touch `PaymentEventOutboxFlusher.publishRow`** — all four families already have dispatch branches (§264-275). This phase adds consumers, not producers (Pitfall 3 / RESEARCH Runtime State Inventory).

---

### `notification/listener/OnboardingNotificationListener.java` + `FinancialNotificationListener.java` (listener, event-driven)

**Analog:** `order/OrderStateChangeListener.java` (the working consumer) + `payment/PaymentEventAuditListener.java` (the minimal 2nd-consumer shape).

**`@RabbitListener` + per-event method** (`OrderStateChangeListener.java:74-76`):
```java
@RabbitListener(queues = RabbitMQConfig.ORDER_EVENTS_QUEUE)
@Transactional
public void handleOrderStateChange(OrderStateChangeEvent event) { ... }
```
Minimal typed consumer (`PaymentEventAuditListener.java:19-20`):
```java
@RabbitListener(queues = RabbitMQConfig.PAYMENT_EVENTS_QUEUE)
public void onPaymentEvent(PaymentEvent event) { ... }
```

**CRITICAL — TenantContext + DB-session GUC before any tenant-scoped read** (`OrderStateChangeListener.java:83-90`, the N1 fix): a `@RabbitListener` runs on a Rabbit thread with no tenant context. The order listener sets BOTH the ThreadLocal AND the Postgres GUC, then `try/finally { TenantContext.clear(); }`:
```java
TenantContext.set(event.tenantId());
Session session = entityManager.unwrap(Session.class);
session.doWork(connection -> {
    try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
        stmt.setString(1, event.tenantId().toString());
        stmt.execute();
    }
});
// ... work ...  finally { TenantContext.clear(); }
```
The new consumers delegate to `NotificationDispatchService` (recipient resolve + consent gate + fan to channels). The event payloads are `OnboardingStateChangeEvent` (onboarding queue), `PaymentEvent` (payment queue), `RefundEvent` (refund queue) — each already Jackson-deserialized by the shared converter.

---

### `notification/dispatch/EmailChannel.java` (service, request-response)

**Analog:** `notification/EmailNotificationService.java` (the working SMTP path — do NOT regress its test). The NEW multipart sender uses `MimeMessageHelper` (RESEARCH Code Ex.1, no new dependency):

**Config + swallow pattern to preserve** (`EmailNotificationService.java:22-33, 145-152`):
```java
@Value("${notification.email.from:noreply@jtoye.uk}") private String fromAddress;
@Value("${notification.email.enabled:true}")           private boolean emailEnabled;
// send() swallows MailException (log.error, no rethrow) so an SMTP outage
// never reaches the listener transaction — KEEP this contract.
```

**NEW multipart body (two-arg `setText(plain, html)` → `multipart/alternative`)** — RESEARCH Code Ex.1:
```java
MimeMessage mime = mailSender.createMimeMessage();
MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
helper.setFrom(from); helper.setTo(to); helper.setSubject(subject);
helper.setText(textBody, htmlBody);                       // plain first, HTML second
mime.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");     // RFC 8058
mime.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
mailSender.send(mime);
```

**Pitfall 5 (LOCKED as path A):** leave `EmailNotificationService` + `EmailNotificationServiceTest` (`ArgumentCaptor<SimpleMailMessage>` §36) **untouched**; put ALL new events on `EmailChannel`. The order path stays text-only (green test); order-email HTML upgrade is a separate, later, explicitly-verified task.

---

### `notification/template/EmailTemplateRenderer.java` (utility, transform)

**Analog:** the inline Java text-block templates in `EmailNotificationService.java:36-122`. The renderer seam returns `{subject, html, text}` per event type (D-01). Reuse the existing text-block style + `String.formatted(...)` substitution (`EmailNotificationService.java:117-121, 136`). No Thymeleaf (RESEARCH Standard Stack — inline-styled HTML text blocks, zero new deps).

---

### `notification/dispatch/WhatsAppSmsChannel.java` (service — INERT by default, COMMS-07)

**Analog:** `tenant/keycloak/KeycloakDeprovisionService.java` + `KeycloakAdminProperties.java` (the verified INERT-by-default / WARN-no-op pattern) with the fail-closed posture of `CompaniesHouseClient`.

**Off-by-default gate + one-time WARN no-op** (`KeycloakDeprovisionService.java:50-51, 81-87`):
```java
private final AtomicBoolean warnedOnce = new AtomicBoolean(false);
...
if (!properties.configured()) {
    if (warnedOnce.compareAndSet(false, true)) {
        log.warn("event=..._skipped reason=not_configured (feature inert: set ...enabled=true + creds)");
    }
    return ...noop(...);           // never throws, never blocks email/webhook
}
```

**`configured()` = enabled AND creds present** (`KeycloakAdminProperties.java:76-80`) + **masked `toString()`** (§82-97) so a stray log line can't leak provider creds. Model `NotificationProperties`/`WhatsAppProperties` on this exactly (`@ConfigurationProperties(prefix = "jtoye.whatsapp")`, empty-string secret defaults, `configured()` gate).

---

### `notification/consent/PublicUnsubscribeController.java` (controller, no-auth token)

**Analog:** `storefront/PublicStorefrontController.java` — the ONLY established public/no-auth surface. **Mount under `/api/v1/public/**`** to inherit `permitAll` (verified `SecurityConfig.java:141`: `.requestMatchers("/public/**", "/api/v1/public/**").permitAll()`). Dual-mapping convention (`PublicStorefrontController.java:42-44`):
```java
@RestController
@RequestMapping({"/public", "/api/v1/public"})
@Tag(name = "Public Storefront", ...)
```

**Stateless HMAC unsubscribe token** (RESEARCH Code Ex.3 / Don't-Hand-Roll): `token = base64url(hmac_sha256(appSecret, tenantId + "|" + email + "|" + category))`; verify with constant-time compare (`MessageDigest.isEqual`); on match INSERT a suppression row idempotently. No token table, no expiry, no prune. The public POST writes a tenant-scoped row — so it must `TenantContext.set(tenantId)` (from the verified token) in try/finally before the write (see async-tenant pattern below). Never render `email`/`token` into logs or responses (ASVS V7).

---

### `webhook/WebhookSubscriptionController.java` (controller, CRUD + custom actions)

**Analog:** `shop/PromotionController.java` (the clean tenant-scoped CRUD quartet template) + `payment/RefundController.java` (custom action POST + `Idempotency-Key` header).

**Controller shell + auth + RLS annotations** (`PromotionController.java:27-37`):
```java
@RestController
@RequestMapping("/webhooks")               // → /api/v1/webhooks via WebConfig prefix
@Tag(name = "Webhooks", ...)
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class WebhookSubscriptionController {
    private final WebhookSubscriptionService service;   // constructor injection
```

**CRUD verbs + `@Valid` + `ServletUriComponentsBuilder` Location** (`PromotionController.java:39-105`): `list` (Page), `getById` (`.map(ResponseEntity::ok).orElse(notFound())`), `create` (201 + Location via `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")`), `delete` (204). Rotate-secret / pause / resume / revoke are custom `@PostMapping("/{id}/rotate-secret")` etc.

**Replay endpoint carries `Idempotency-Key`** (`RefundController.java:71-80`): the replay POST reads `@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey` — matches the frontend `makeIdempotencyKey()` contract.

**Errors:** RFC 7807 via the existing `common/GlobalExceptionHandler.java` (already `@RestControllerAdvice`) — throw `ResourceNotFoundException` for missing subscriptions; add `@Valid` DTO validation (HTTPS-only `target_url`, ≥1 event type). Secret returned **plaintext only once** on create/rotate (never re-fetchable).

---

### `webhook/WebhookDeliveryWorker.java` (@Scheduled worker — COMMS-05, no head-of-line block)

**Analog:** `payment/PaymentEventOutboxFlusher.java` (the proven at-least-once shape) + `onboarding/client/CompaniesHouseClient.java` (WebClient egress with timeout + circuit breaker).

**Per-tenant loop, own transaction, TenantContext try/finally, TransactionTemplate NOT `@Transactional`** (`PaymentEventOutboxFlusher.java:156-185`) — the canonical async/scheduled RLS shape (also `ScheduledCleanupService.java:94-112`):
```java
@Scheduled(fixedDelayString = "${webhook.delivery.interval-ms:5000}")
public void deliverDue() {
    for (UUID tenantId : listTenantIds()) {
        try { deliverForTenant(tenantId); }
        catch (Exception e) { log.error("... tenant {} — continuing", tenantId, e); }
    }
}
private void deliverForTenant(UUID tenantId) {
    TenantContext.set(tenantId);
    try {
        transactionTemplate.executeWithoutResult(status -> {
            List<WebhookDelivery> due = repo.claimDueBatch(BATCH_SIZE);  // FOR UPDATE SKIP LOCKED
            for (WebhookDelivery d : due) attemptDelivery(d);
        });
    } finally { TenantContext.clear(); }
}
```
> **Trap (documented in the flusher §86-90):** a `@Transactional` *private* method here is a Spring self-invocation no-op → runs under NULL tenant. Use `TransactionTemplate` (constructed from `PlatformTransactionManager`) exactly as the flusher/cleanup do.

**Exponential backoff (pure, unit-testable)** — copy `computeBackoffMillis` verbatim (`PaymentEventOutboxFlusher.java:112-128`): `base * 2^(attempts-1)` capped, with the loop-not-shift overflow guard. Config-inject `backoff-base-ms` / `backoff-cap-ms` / `MAX_ATTEMPTS` via `@Value` (§80-81).

**Per-subscription isolation + auto-pause (COMMS-05):** claim rows keyed per `(subscription,event)` with `SKIP LOCKED` so one failing endpoint never blocks others; track `webhook_subscription.consecutive_failures`, flip `status=PAUSED` at the config threshold, worker's claim query skips PAUSED, a success resets the counter (RESEARCH Pattern 2).

**HTTP egress** (`CompaniesHouseClient.java:38, 78-103`): `WebClient` with explicit `.block(TIMEOUT)` (`Duration.ofSeconds(10)`) under `@CircuitBreaker(name = "...")`; log **status only, never the secret** (§106-108). Sign the exact `byte[]` you POST (see `WebhookSigner`).

---

### `webhook/WebhookSigner.java` (utility — novel, HMAC-SHA256)

**Analog:** none in-repo (JDK-native `javax.crypto.Mac`). Copy RESEARCH Code Ex.2 (Stripe `t=,v1=` scheme, verified against Stripe docs):
```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(signingSecret.getBytes(UTF_8), "HmacSHA256"));
mac.update(Long.toString(unixTs).getBytes(UTF_8));
mac.update((byte) '.');
mac.update(rawBody);                                   // the EXACT bytes POSTed
return "t=" + unixTs + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
```
**Pitfall 6 (LOAD-BEARING):** serialize the envelope once to `byte[] body = objectMapper.writeValueAsBytes(envelope)`, sign `body`, POST `body`. Never re-serialize. Headers: `X-JToye-Signature`, `X-JToye-Event-Id` (= envelope `id`, the dedupe key), `X-JToye-Event-Type` (lock exact names at plan time — Assumption A4).

---

### `webhook/WebhookFanoutListener.java` (listener → INSERT delivery rows)

**Analog:** `PaymentEventAuditListener.java` (own durable queue on `order.*`/`payment.*`/`onboarding.*`). Fan-out is **synchronous INSERT** of a `webhook_delivery` PENDING row per matching ACTIVE subscription; the actual HTTP POST happens in the `@Scheduled` worker (never inline in the listener — RESEARCH Pattern 2 "why not deliver inline"). Same TenantContext-GUC preamble as `OrderStateChangeListener.java:83-90`.

---

### `webhook/WebhookRetentionCleanup.java` (@Scheduled prune — #107)

**Analog:** `config/ScheduledCleanupService.java` (verbatim shape). Cron-scheduled per-tenant prune, own transaction each, `TenantContext` try/finally (§57-58, 94-112). Retention window is `@Value`-injected (§32). Applies to **both** `webhook_delivery` and suppression rows (bounded-accumulator constraint).

---

### Migrations `V54/V55/V56` (RLS tables — helper form, NOT the V50 literal)

**Analog:** `V51__rls_uuid_cast_safety.sql` (the CURRENT correct policy form) — NOT `V50__idempotency_keys.sql` (its raw `::uuid` cast §53-54 now FAILS the build gate).

**Correct policy form** (`V51.sql:84-87`, RESEARCH Code Ex.4):
```sql
ALTER TABLE webhook_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY webhook_subscription_tenant ON webhook_subscription
    FOR ALL
    USING (tenant_id = current_tenant_id())          -- helper, NOT current_setting(...)::uuid
    WITH CHECK (tenant_id = current_tenant_id());
```
**Pitfall 4:** any raw `current_setting('app.current_tenant_id', true)::uuid` fails `RlsContractTest.noPolicyUsesRawTenantGucCast` (verified sweep, `RlsContractTest.java:218-231`). Table/column shape template from `V50.sql:37-49` (ENABLE+FORCE, composite PK, no `_aud` for dedup/log tables). `signing_secret` stored **plaintext** — FORCE RLS is load-bearing (mirrors V50 `response_body` PII rationale, §26-28; Assumption A3 — confirm vs threat model). Versions are V54+ because head is V51 and V52/V53 are reserved for Phases 23/24; `out-of-order: true` is already set (`application.yml:65`).

---

### RLS integration tests (`*RlsPolicyIntegrationTest.java`)

**Analog:** `common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java` — copy WHOLE. The `rls_test_role` NOSUPERUSER downgrade is the ONLY real FORCE-RLS proof (superuser bypasses it).

**Role provisioning + per-tx downgrade** (`IdempotencyKeysRlsPolicyIntegrationTest.java:86-93, 124-126`):
```java
// BeforeEach: CREATE ROLE rls_test_role NOSUPERUSER NOBYPASSRLS LOGIN; GRANT ALL ...
private void dropSuperuserForTransaction() { jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE); }
```
Three canonical assertions to replicate (§133-176): cross-tenant read → 0 rows; same-key-different-tenant → fresh insert; cross-tenant forged write → `DataAccessException` "row-level security". Testcontainers `postgres:15` bootstrap + `@DynamicPropertySource` (§50-70) copied as-is (note it disables the Rabbit listener via `auto-startup=false`).

---

### Frontend `app/dashboard/webhooks/page.tsx` + `[id]/page.tsx` (list + delivery log)

**Analog:** `app/dashboard/orders/page.tsx`.

- **Status taxonomy = a `statusConfig` map** (`orders/page.tsx:73-112`) — a `Record<Status, {label, color, bgColor, icon}>`. Build `components/dashboard/webhooks/status-badge.tsx` from this exact shape using the UI-SPEC §Color badge taxonomy (emerald/slate/amber/red + lucide icon + text label — never color alone).
- **Filter `Select`** (`orders/page.tsx:504-519`): `<Select value=... onValueChange=...><SelectTrigger className="w-[160px]">...` — reuse for event-type + status filters (UI-SPEC: `sm:w-[180px]`, stack `flex-col` on mobile).
- **`font-mono text-xs` for identifiers** (`orders/page.tsx:569`) — endpoint URLs, HTTP codes, delivery IDs, secrets.
- **`<Pagination>`** (`orders/page.tsx:642`) reused when the log paginates.
- **375px guard (UI-SPEC, LOCKED):** do NOT copy `orders/page.tsx:441` `text-4xl` H1 (overflow risk beside a CTA) — use `text-2xl sm:text-3xl` and card-stack below `sm`, `Table` at `sm+`.
- **Data fetching = `useEffect` + `useState` + `apiClient`** (no react-query in repo); errors → destructive `useToast` (never a blank screen).

### `components/dashboard/webhooks/WebhookCreateDialog.tsx` + `SecretRevealDialog.tsx`

**Analog:** `components/dashboard/orders/RefundDialog.tsx`.

- **Dialog + react-hook-form + Zod** (`RefundDialog.tsx:186-195, 104-136`): `<Dialog><DialogContent className="max-w-md">`, `useForm({ resolver: zodResolver(schema) })`, reset-on-open effect (§140-145). HTTPS-only validation: `z.string().url().startsWith("https://")`.
- **`makeIdempotencyKey()` — reuse EXACTLY** (`RefundDialog.tsx:71-88, 160-165`) for the replay POST: `crypto.randomUUID()` → `crypto.getRandomValues` fallback → throw (never `Math.random`). Header `{ "Idempotency-Key": key }`.
- **Server-error extraction from RFC 7807** (`RefundDialog.tsx:169-179`): `e?.response?.data?.detail ?? ...message ?? "…failed"`.
- Secret-reveal reuses the readOnly `Input` + Dialog; UI-SPEC adds `role="alert"` warning + copy button + no-backdrop-dismiss.

### `app/unsubscribe/page.tsx` (public, no-auth)

**Analog:** `app/track/page.tsx` (the public page template).

- **`Suspense`-wrapped client component reading `useSearchParams`** (`track/page.tsx:39-53, 55-57`): outer default export wraps `<Suspense fallback={<Loader2 className="animate-spin text-orange-500"/>}>` around the content component that reads `?tenant=&email=&category=&token=`.
- **`publicApiClient` (no-auth)** (`track/page.tsx:10, 111-115`) POSTs the token.
- **Layout:** `mx-auto max-w-lg px-4 py-8 sm:py-12` + `rounded-xl bg-white border border-slate-100 p-6 shadow-sm` card (`track/page.tsx:142, 202`) — single column, overflow-proof at 375px.
- **Orange spinner + palette** (`track/page.tsx:45, 180`): `text-orange-500`, `bg-orange-500 hover:bg-orange-600` — NOT blue.
- **SEO (UI-SPEC LOCKED):** `export const metadata = { robots: { index: false, follow: false } }`; EXCLUDE from `app/sitemap.ts`; never render `email`/`token` into meta/body.

### `components/dashboard/sidebar.tsx` (MODIFY — nav only)

**Analog:** itself. Add ONE entry to the exported `navigation` array (§25-38) — `mobile-tab-bar.tsx` imports this array (single source of truth, do NOT re-declare): `{ name: "Webhooks", href: "/dashboard/webhooks", icon: Webhook }` (lucide `Webhook`).

---

## Shared Patterns

### Async / Scheduled tenant-context re-establishment (applies to: WebhookDeliveryWorker, WebhookRetentionCleanup, WebhookFanoutListener, both notification listeners, PublicUnsubscribeController write)
**Source:** `config/ScheduledCleanupService.java:94-112` + `payment/PaymentEventOutboxFlusher.java:169-185` + `order/OrderStateChangeListener.java:83-90`
`TenantContext` is a plain `ThreadLocal` with no `TaskDecorator`. Any off-request-thread tenant-scoped DB access MUST: (1) `TenantContext.set(tenantId)` in a `try/finally { TenantContext.clear(); }`; (2) iterate **per-tenant, one transaction each**; (3) use a `TransactionTemplate` (built from injected `PlatformTransactionManager`), NOT a `@Transactional` private method (Spring self-invocation → NULL-tenant no-op). Listeners additionally set the Postgres GUC via `set_config('app.current_tenant_id', ?, true)` on the Hibernate session.

### RLS on every new table (applies to: notification_suppression, marketing_opt_in, webhook_subscription, webhook_delivery)
**Source:** `V51__rls_uuid_cast_safety.sql:84-87`
`ENABLE` + `FORCE ROW LEVEL SECURITY` + `CREATE POLICY … FOR ALL USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())`. Never the raw `current_setting(...)::uuid` cast (build gate). Prove each under the NOSUPERUSER `rls_test_role` (copy `IdempotencyKeysRlsPolicyIntegrationTest`).

### Config injection — no literals (applies to: WhatsApp flag/creds, webhook retry/backoff/pause/retention, from-address, HMAC secret)
**Source:** `tenant/keycloak/KeycloakAdminProperties.java` (`@ConfigurationProperties`, empty-string secret defaults, `configured()` gate, masked `toString()`) + `EmailNotificationService.java:22-29` (`@Value` with `${ENV:default}`). Existing keys reusable: `notification.email.from/enabled/tracking-base-url` (`application.yml:283-287`), `spring.mail.*` (`application.yml:71-81`, the SES-over-SMTP knob).

### RFC 7807 errors (applies to: WebhookSubscriptionController, PublicUnsubscribeController)
**Source:** `common/GlobalExceptionHandler.java` (`@RestControllerAdvice`) — throw domain exceptions (`ResourceNotFoundException` → 404), `@Valid` DTOs for 400. Machine-parseable, stable — the AI-agent-readiness cross-cutting contract.

### INERT-by-default third channel (applies to: WhatsAppSmsChannel)
**Source:** `KeycloakDeprovisionService.java:81-87` (one-time WARN no-op via `AtomicBoolean warnedOnce`) + `KeycloakAdminProperties.configured()`. Off by default; enabling without creds is a documented WARN no-op, never a crash (COMMS-07 acceptance).

---

## No Analog Found (novel work — planner uses RESEARCH code examples)

| File | Role | Data Flow | Reason / Recipe |
|------|------|-----------|-----------------|
| `webhook/WebhookSigner.java` | utility | transform | No HMAC in repo. JDK-native `javax.crypto.Mac` — RESEARCH Code Ex.2 (Stripe `t=,v1=`, verified). Constant-time verify via `MessageDigest.isEqual`. |
| `notification/dispatch/NotificationChannel.java` | interface | — | No multi-channel provider abstraction exists. Novel interface (`send(NotificationEnvelope)`), but its implementations reuse EmailNotificationService (email), the webhook delivery table (webhook), and the Keycloak INERT stub (WhatsApp). |
| HMAC unsubscribe-token scheme (inside `SuppressionService`) | crypto | — | No token-signing in repo. Stateless `hmac(appSecret, tenant\|email\|category)` — RESEARCH Code Ex.3 / Don't-Hand-Roll (no token table, no expiry). Shares `WebhookSigner`'s `Mac` mechanism. |

> All three are thin, well-specified JDK-native recipes — low novelty risk. Everything else in the phase has a battle-tested in-repo analog that survived a QA council (RESEARCH "Key insight").

---

## Metadata

**Analog search scope:** `core-java/src/main/java/uk/jtoye/core/{config,notification,order,payment,onboarding,tenant,storefront,shop,review,common,security}`; `core-java/src/main/resources/db/migration`; `core-java/src/test/java/.../{security,common/idempotency,notification}`; `frontend/{app/dashboard/orders,app/track,app/unsubscribe,components/dashboard,lib}`.
**Files scanned/read:** ~30 source files (all fully read except two targeted large-file reads: `orders/page.tsx` §73-112 + grep-located sections, `RlsContractTest.java` §175-231).
**Verified live facts:** max migration V51; `order.refunded` unbound (refund discarded); no declarative `@QueueBinding`; public permitAll `SecurityConfig:141`; metrics baseline 1300/schema 51.
**Pattern extraction date:** 2026-07-15

---

## PATTERN MAPPING COMPLETE

**Phase:** 22 - Notifications & Comms
**Files classified:** 34 (28 new + 6 modified)
**Analogs found:** 31 / 34

### Coverage
- Files with exact analog: 22
- Files with role-match / partial analog: 9
- Files with no analog (novel, recipe-backed): 3

### Key Patterns Identified
- **Consumers, not producers:** every event family already flows through the V46 outbox + `PaymentEventOutboxFlusher.publishRow` (all four dispatch branches exist) — this phase binds NEW durable queues (`RabbitMQConfig` triplet pattern), never a 2nd listener on an existing queue, and does NOT touch the flusher (Pitfall 3).
- **Two live gaps to close:** `onboardingEventsExchange` is unbound (Phase 21 seam) and `order.refunded` matches no binding (refund discarded today) — both fixed by new bindings.
- **RLS = `current_tenant_id()` helper (V51), never the V50 raw `::uuid` cast** — the `RlsContractTest.noPolicyUsesRawTenantGucCast` sweep fails the build otherwise; prove isolation with the `IdempotencyKeysRlsPolicyIntegrationTest` NOSUPERUSER role-downgrade.
- **Async/Scheduled RLS shape is load-bearing:** per-tenant `TransactionTemplate` + `TenantContext` try/finally (flusher/cleanup), NOT `@Transactional` private methods (self-invocation → NULL tenant).
- **Webhook delivery mirrors the outbox flusher** (SKIP-LOCKED claim, `computeBackoffMillis`, MAX_ATTEMPTS) on a DEDICATED `webhook_delivery` table for per-subscription isolation + auto-pause; egress via `WebClient` + `@CircuitBreaker` (CompaniesHouseClient).
- **Order-email path stays frozen (Pitfall 5, path A):** `EmailNotificationService` + its `ArgumentCaptor<SimpleMailMessage>` test untouched; all new events on the new `MimeMessageHelper` multipart `EmailChannel`.
- **Frontend reuses `orders/page.tsx` (statusConfig/filter/table→cards)**, `RefundDialog.tsx` (`makeIdempotencyKey` + Dialog+Zod), and `track/page.tsx` (public Suspense + `publicApiClient`); route is flat `/dashboard/webhooks` (UI-SPEC), one `navigation`-array entry.

### File Created
`.planning/phases/22-notifications-comms/22-PATTERNS.md`

### Ready for Planning
Pattern mapping complete. Planner can reference each analog + line-numbered excerpt directly in PLAN.md action sections.

---
phase: 22-notifications-comms
reviewed: 2026-07-15T05:29:15Z
depth: standard
files_reviewed: 59
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  - core-java/src/main/java/uk/jtoye/core/notification/NotificationProperties.java
  - core-java/src/main/java/uk/jtoye/core/notification/WhatsAppProperties.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/ConsentGate.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/MarketingOptIn.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/MarketingOptInRepository.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/NotificationCategory.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/NotificationSuppression.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/NotificationSuppressionRepository.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/PublicUnsubscribeController.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/SuppressionService.java
  - core-java/src/main/java/uk/jtoye/core/notification/consent/UnsubscribeTokenService.java
  - core-java/src/main/java/uk/jtoye/core/notification/dispatch/EmailChannel.java
  - core-java/src/main/java/uk/jtoye/core/notification/dispatch/NotificationChannel.java
  - core-java/src/main/java/uk/jtoye/core/notification/dispatch/NotificationDispatchService.java
  - core-java/src/main/java/uk/jtoye/core/notification/dispatch/NotificationMessage.java
  - core-java/src/main/java/uk/jtoye/core/notification/dispatch/RecipientResolver.java
  - core-java/src/main/java/uk/jtoye/core/notification/dispatch/WhatsAppSmsChannel.java
  - core-java/src/main/java/uk/jtoye/core/notification/listener/FinancialNotificationListener.java
  - core-java/src/main/java/uk/jtoye/core/notification/listener/OnboardingNotificationListener.java
  - core-java/src/main/java/uk/jtoye/core/notification/listener/OrderNotificationListener.java
  - core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java
  - core-java/src/main/java/uk/jtoye/core/notification/template/RecipientRole.java
  - core-java/src/main/java/uk/jtoye/core/notification/template/RenderedEmail.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDelivery.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryController.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryRepository.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryWorker.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookEventEnvelope.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookEventType.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookProperties.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookRetentionCleanup.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSigner.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscription.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscriptionController.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscriptionRepository.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscriptionService.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookUrlValidator.java
  - core-java/src/main/java/uk/jtoye/core/webhook/dto/CreateWebhookSubscriptionRequest.java
  - core-java/src/main/java/uk/jtoye/core/webhook/dto/WebhookSubscriptionDto.java
  - core-java/src/main/resources/application.yml
  - core-java/src/main/resources/db/migration/V54__notification_consent.sql
  - core-java/src/main/resources/db/migration/V55__webhook_subscription.sql
  - core-java/src/main/resources/db/migration/V56__webhook_delivery.sql
  - frontend/app/dashboard/webhooks/[id]/page.tsx
  - frontend/app/dashboard/webhooks/page.tsx
  - frontend/app/unsubscribe/page.tsx
  - frontend/app/unsubscribe/unsubscribe-content.tsx
  - frontend/components/dashboard/sidebar.tsx
  - frontend/components/dashboard/webhooks/ConfirmActionDialog.tsx
  - frontend/components/dashboard/webhooks/SecretRevealDialog.tsx
  - frontend/components/dashboard/webhooks/WebhookCreateDialog.tsx
  - frontend/components/dashboard/webhooks/status-badge.tsx
  - frontend/lib/webhooks-api.ts
  - frontend/lib/public-api-client.ts
  - core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java
  - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
  - core-java/src/main/java/uk/jtoye/core/config/WebConfig.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundService.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventPublisher.java
findings:
  critical: 1
  warning: 5
  info: 2
  total: 8
status: partially_remediated
remediation:
  reviewed_batch: 2026-07-15
  branch: feature/v2.3-milestone-init
  fixed: [WR-01, WR-02, WR-03, WR-05, IN-01]
  deferred: [CR-01, WR-04, IN-02]
  note: >-
    5 of 8 findings fixed 2026-07-15 (per-finding outcome lines below). The
    remaining 3 are a SECURITY FOLLOW-UP tracked in deferred-items.md — CR-01
    (SSRF DNS-rebinding IP-pinning) is the priority, WR-04 (unsubscribe POST
    body, touches frontend), IN-02 (general HTML-escape helper; the one live
    injection vector — the onboarding reason — was already closed under WR-03).
---

# Phase 22: Code Review Report

**Reviewed:** 2026-07-15T05:29:15Z
**Depth:** standard (security-critical-surface prioritized per reviewer brief; not a full 15k-line line review)
**Files Reviewed:** 59
**Status:** issues_found

## Summary

Reviewed the Phase 22 (Notifications & Comms) diff (`4cf5c60..HEAD`, 43 commits / 112 files) with focus on the nine security- and correctness-critical surfaces called out in the review brief: RLS on V54/V55/V56, HMAC webhook signing, SSRF defense, the constant-time unsubscribe token, the public no-auth unsubscribe endpoint, webhook-secret exposure, RabbitMQ wiring, delivery-worker isolation, and the frontend webhook/unsubscribe UI.

**What's solid:** all three new tables (`notification_suppression`, `marketing_opt_in`, `webhook_subscription`, `webhook_delivery`) are `ENABLE + FORCE ROW LEVEL SECURITY` via the safe `current_tenant_id()` helper with tenant-scoped `USING`/`WITH CHECK` — no raw-cast or `USING(true)` gaps found. `WebhookSigner` signs the exact posted bytes with no re-serialization and never logs the secret; verification uses `MessageDigest.isEqual`. `UnsubscribeTokenService.verify` is genuinely constant-time. The webhook signing secret is provably never returned outside create/rotate (`WebhookSubscriptionDto` has no secret field; `toDto()` never sets one). RabbitMQ topology is careful: every new consumer gets its own durable queue, none compete with the incumbent `order.state-changes` / `payment.events` consumers. `SecretRevealDialog` correctly blocks backdrop/Esc/X dismissal. `/unsubscribe` carries `robots: {index:false, follow:false}` and never renders PII into the DOM. The public unsubscribe endpoint is already covered by the existing IP-keyed public rate limiter (`/api/v1/public/**`).

**The one CRITICAL:** the SSRF re-validation in `WebhookDeliveryWorker` does not actually close the DNS-rebinding gap it claims to close (T-22-05-03) — `WebhookUrlValidator.validate()` resolves and discards an IP, then the delivery POST performs a completely independent DNS resolution through Reactor Netty's own resolver. An attacker-controlled DNS server with a short/zero TTL can pass validation with a public IP and then resolve to `169.254.169.254` (cloud metadata) or an internal address for the actual connection.

Also found: an established `Idempotency-Key` contract that is accepted but never enforced on webhook replay (concrete double-delivery risk given the frontend's own automatic retry-on-5xx interceptor), a payment-failure email that lies about payment success, and a silently-broken onboarding email (the computed "reason" is never rendered).

## Critical Issues

### CR-01: SSRF/DNS-rebinding TOCTOU — delivery-time re-validation resolves a different IP than the actual connection

**Outcome:** `deferred` (2026-07-15) — SECURITY FOLLOW-UP (priority). Needs Reactor Netty resolver IP-pinning + dedicated tests; tracked in `deferred-items.md`. Not touched by the 2026-07-15 fix batch.

**File:** `core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryWorker.java:157-174` (call site) and `core-java/src/main/java/uk/jtoye/core/webhook/WebhookUrlValidator.java:80-95` (validator)

**Issue:** The worker's javadoc and inline comment explicitly claim delivery-time re-validation closes "T-22-05-03 SSRF/DNS-rebinding":

```java
// Re-guard SSRF at egress (a subscription created before validation
// tightened, or DNS-rebinding since create) — T-22-05-03.
try {
    urlValidator.validate(sub.getTargetUrl());
} catch (IllegalArgumentException e) { ... }
...
Integer statusCode = webClient.post()
        .uri(URI.create(sub.getTargetUrl()))
        ...
```

`WebhookUrlValidator.validate()` calls `InetAddress.getAllByName(resolveHost)` (JVM resolver), checks every returned address, and then **discards the resolved IP** — it only proves the hostname *validated to* a safe address at that instant. The very next call, `webClient.post().uri(...)`, hands the same **hostname** (not a pinned IP) to `WebClient`'s Reactor Netty `HttpClient`, which performs its **own, independent DNS resolution** through a completely separate resolver/cache subsystem (Netty's async DNS resolver, not `InetAddress`).

This is the textbook DNS-rebinding SSRF pattern: an attacker registers a domain whose authoritative DNS server returns a public/benign IP on the validation lookup and a private/internal IP (e.g. `169.254.169.254` — the Azure/AWS/GCP metadata IP, which this platform runs behind in Azure per `k8s_kustomize_deploy`) on the connection lookup a few milliseconds later, using TTL=0 or per-query alternation. Re-running the *same kind* of resolve-then-discard check closer to the connection narrows the race window but does not close it — it is not equivalent to resolving once and connecting to the validated address.

**Concrete failure scenario:** A tenant admin (already authenticated — this requires an authenticated webhook-subscription owner, not an anonymous attacker) registers `https://attacker-rebind.example/hook` as a webhook target. `WebhookSubscriptionService.create()` validates it once at create time (same gap, lower stakes). Every `WebhookDeliveryWorker.deliverDue()` tick thereafter (every 5s by default), `attemptDelivery()` re-validates via `urlValidator.validate(...)`, which resolves `attacker-rebind.example` to a public IP the attacker returns for validator-looking traffic; `webClient.post()` then resolves the same hostname a moment later and the attacker's DNS returns `169.254.169.254`. The platform's own backend now POSTs the signed webhook body to the metadata endpoint from inside the cluster, and (depending on what a follow-up 30x/redirect or response-body echo yields) can potentially exfiltrate the pod identity token. This runs on a `@Scheduled` loop, so the attacker gets unlimited retries to win the race.

**Fix:** Resolve the hostname exactly once, validate the resolved address(es), and connect to a **pinned IP** while preserving the original `Host` header / SNI for TLS — e.g. build a per-request Reactor Netty `HttpClient` with a custom `AddressResolverGroup` (or a manual `InetSocketAddress` override) so the address used for validation is provably the address used for the TCP connection. A minimal version: resolve once in `WebhookDeliveryWorker`, pass the validated `InetAddress` down to a WebClient configured to connect to that literal IP with the original hostname sent via the `Host` header/SNI. Alternatively, disable Netty's own DNS resolution for this specific `WebClient` and force it through the same `InetAddress` you already validated.

## Warnings

### WR-01: Idempotency-Key accepted but never enforced on webhook replay — concrete double-delivery risk

**Outcome:** `fixed` (2026-07-15, commit `688a54c`, RED test `9c96416`). `WebhookDeliveryController.replay` now routes through the existing generic V50 `IdempotencyService.execute` keyed on `(webhooks.replay, key)` when the header is present: a same-key retry returns the original replay and creates no second `webhook_delivery` row/POST; a different key creates a new one. Proven by `WebhookDeliveryWorkerIntegrationTest.replay_sameIdempotencyKey_createsExactlyOneRow_differentKeyCreatesAnother`.

**File:** `core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryController.java:67-99`

**Issue:** `replay()` accepts an `Idempotency-Key` header and its javadoc calls the endpoint "Idempotency-Key safe", but the key is only used for a log line (`idempotencyKey={}` at line 96-97) — there is no lookup against any store before the new `WebhookDelivery` row is inserted. Contrast with the established, working pattern in this exact codebase: `RefundService.createRefund()` (`core-java/src/main/java/uk/jtoye/core/payment/RefundService.java:101-106`) does `findByTenantIdAndIdempotencyKey(...)` and returns the existing row on a replayed key. The webhook replay path has no equivalent check, and the `webhook_delivery` table (V56) has no `idempotency_key` column at all.

The frontend makes this concretely exploitable, not just theoretical: `frontend/lib/api-client.ts` retries automatically on 5xx/network errors (max 2 retries) and — because it retries `error.config` verbatim — resends the **same** `Idempotency-Key` header on retry. A transient 500 on the first replay attempt (DB blip, GC pause, etc.) causes the client to silently resubmit with the identical key, and the backend, having no dedup check, creates a **second** `WebhookDelivery` row and POSTs a second HTTP request to the vendor's endpoint for what the user experienced as one "Replay" click. `makeIdempotencyKey()` in `frontend/lib/webhooks-api.ts:140-156` is also freshly generated per dialog confirm, so even a user-initiated double-submit (if the UI's `pending` guard is ever raced) is not deduped server-side either. This directly contradicts the project's own AI-readiness contract in `CLAUDE.md` ("mutating endpoints carry an Idempotency-Key contract (or are provably idempotent)").

**Fix:** Add an `idempotency_key` column to `webhook_delivery` (or a lightweight lookup table) and check `findByTenantIdAndSubscriptionIdAndIdempotencyKey` before inserting the replay row, returning the existing replay's `WebhookDeliveryView` on a repeat key — mirroring `RefundService`'s pattern exactly.

### WR-02: Payment-failed events render "Payment received... Thank you!" copy — misleading financial email

**Outcome:** `fixed` (2026-07-15, commit `4df36bd`, RED test `e412d9f`). `EmailTemplateRenderer.paymentCopy` now branches on the payment outcome; `NotificationDispatchService.modelFor` plumbs `PaymentEvent.type()` into the model as `paymentType`. A FAILED payment renders "Payment failed — please try again" copy (never "received/thank you") for both audiences; succeeded still renders the success copy.

**File:** `core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java:81-93` (`paymentCopy`), dispatched from `core-java/src/main/java/uk/jtoye/core/notification/listener/FinancialNotificationListener.java:47-56`

**Issue:** `FinancialNotificationListener.handlePaymentNotification` dispatches **both** `payment.succeeded` and `payment.failed` event types (confirmed against `PaymentEventPublisher.publishFailed` at `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventPublisher.java:47-56`, which publishes with routing key `payment.failed`, matched by the `payment.*` binding on `payment.notifications`). `RecipientResolver.Family.classify()` and `NotificationDispatchService.categoryFor()` both collapse `payment.succeeded`/`payment.failed` into the same `PAYMENT` family/`FINANCIAL` category, and `EmailTemplateRenderer.familyOf()` strips to the `"payment"` prefix for both — landing on the single `paymentCopy()` branch, which unconditionally renders:

- Vendor: `"A payment of %s was received for order %s."`
- Customer: `"We've received your payment of %s for order %s. Thank you!"`

There is no branch on `PaymentEvent.PaymentEventType` anywhere in the copy selection, and `NotificationDispatchService.modelFor()` never puts a `type`/`succeeded` flag into the model for the renderer to key off.

**Concrete failure scenario:** A customer's card is declined (`PaymentEventPublisher.publishFailed(...)`). The customer receives an email titled "Payment received — order X" with body "We've received your payment of £42.50 for order X. Thank you!" — the opposite of what happened. The customer reasonably concludes their order is paid and takes no corrective action; the vendor's copy has the same false-positive framing. This is a direct, reproducible business-logic defect, not an edge case — every failed payment produces this email today.

**Fix:** Branch `paymentCopy` (or add a `failureCopy`) on `PaymentEvent.PaymentEventType`, and have `NotificationDispatchService.modelFor` pass the event type/outcome through the model so the renderer can select "Payment failed — please try again" vs "Payment received — thank you" copy.

### WR-03: Onboarding notification email always renders a blank shop name and never surfaces the actual stall reason

**Outcome:** `fixed` (2026-07-15, commit `5fd8a0c`, RED test `a24faab`). `EmailTemplateRenderer.onboardingCopy` now renders the `reason` the model already carries (instead of the never-populated `shopName`), HTML-escaped in the HTML path via `HtmlUtils.htmlEscape` (this closes the one live IN-02 injection vector), raw in the plain-text path; a blank reason falls back to a generic sentence.

**File:** `core-java/src/main/java/uk/jtoye/core/notification/dispatch/NotificationDispatchService.java:157-159` vs. `core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java:72-79`

**Issue:** `modelFor()` for `OnboardingStateChangeEvent` puts only a `"reason"` key into the model, with the comment `// No shop name on the event; the renderer tolerates a missing key.`:

```java
} else if (payload instanceof OnboardingStateChangeEvent onboarding) {
    model.put("reason", onboarding.reason() == null ? "" : onboarding.reason());
}
```

But `EmailTemplateRenderer.onboardingCopy()` reads `s(m, "shopName")` — a key that is **never populated** — and never reads `"reason"` at all:

```java
private Copy onboardingCopy(RecipientRole role, Map<String, Object> m) {
    String shop = s(m, "shopName");
    ...
    "...onboarding for <strong>%s</strong>. Open your dashboard...".formatted(shop),
```

**Concrete failure scenario:** Every onboarding-stall email a vendor receives (the exact feature this phase exists to ship, per the project's own memory note: "the dead channel is the unbound `onboardingEventsExchange`... extend+govern+add-channels") reads "There's an update on the onboarding for **[blank]**. Open your dashboard to see what's needed next." — no shop name, and critically, no mention of *why* onboarding stalled (the manual-review reason/gate failure that `onboarding.reason()` was specifically computed to carry). This materially degrades the notification's usefulness to near-uselessness while shipping green tests (the renderer's "missing key renders empty, never throws" contract silently swallows the mismatch).

**Fix:** Either (a) fetch the shop name from `TenantRepository`/`ShopRepository` in `RecipientResolver`/`NotificationDispatchService` and pass `shopName` in the model, or (b) change `onboardingCopy` to read and render `reason` (`s(m, "reason")`) instead of/in addition to `shopName`. A unit test asserting the rendered HTML contains the reason text would have caught this.

### WR-04: Unsubscribe token/email sent as query-string params on POST — infra logs still capture them despite the "never logged" claim

**Outcome:** `deferred` (2026-07-15) — SECURITY FOLLOW-UP. Touches the frontend (`unsubscribe-content.tsx` + `public-api-client`), out of scope for the backend-only fix batch; tracked in `deferred-items.md`.

**File:** `frontend/app/unsubscribe/unsubscribe-content.tsx:57-62`, `core-java/src/main/java/uk/jtoye/core/notification/consent/PublicUnsubscribeController.java:61-70`

**Issue:** The frontend POSTs to `/api/v1/public/unsubscribe` but places `tenant`/`email`/`category`/`token` in `params` (axios query string), not a request body, because the backend `@PostMapping("/unsubscribe")` only declares `@RequestParam` bindings, not `@RequestBody`. The class javadoc states "the `email` and `token` are never logged or echoed into the response body" and "PII in logs (ASVS V7): the `email` and `token` params are never logged" — true only for the *application's own* log statements. The query string is part of the request URL for both GET and POST, so any infrastructure-level access logging (nginx/ingress access logs, load-balancer logs, APM/tracing spans that capture full URLs, or a future `CommonsRequestLoggingFilter`) will capture the recipient's email address and the HMAC unsubscribe token verbatim in the POST case too — a case where a request body would have kept them out of the URL entirely. This doesn't affect the initial GET link (inherent to click-through unsubscribe links, industry-standard trade-off), but the POST path had a free opportunity to avoid the same exposure and didn't take it.

**Fix:** Change `PublicUnsubscribeController.unsubscribe()` to accept a `@RequestBody UnsubscribeRequest(UUID tenant, String email, NotificationCategory category, String token)` for the POST variant, and have the frontend's `publicApiClient.post` send it as the request body instead of `params`. Keep the GET variant as-is (it's the click-through link and can't avoid the query string).

### WR-05: `webhook.deliveries` fanout queue has no dead-letter exchange — a failing fanout permanently loses the event with no recovery path

**Outcome:** `fixed` (2026-07-15, commit `0831f61`). `webhook.deliveries` now declares `x-dead-letter-exchange` bound to a dedicated `webhook.deliveries.dlx` fanout + `webhook.deliveries.dlq` + binding (mirroring `payment.notifications`), so a transient-failure retry-exhaustion is dead-lettered/observable instead of silently dropped. Consumer logic unchanged.

**File:** `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java:269-271`, `core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java:26-50`

**Issue:** `webhookDeliveriesQueue()` is declared `QueueBuilder.durable(WEBHOOK_DELIVERIES_QUEUE).build()` with no `x-dead-letter-exchange`, unlike every other queue in this file (`order.events`, `order.notifications`, `payment.events`, `payment.notifications`, `refund.notifications` all carry a DLX). The comment justifies this as "consumed by 22-05's WebhookFanoutListener (which owns its own delivery-state table, so no DLX here)" — but that reasoning only holds if the fanout INSERT into `webhook_delivery` always succeeds. If `insertPendingRows()` throws (e.g. a transient DB connection error, not merely "no matching subscriptions"), the global `retryInterceptor` (3 attempts, `AmqpRejectAndDontRequeueException` on exhaustion) causes RabbitMQ to simply **drop** the message — there is no DLQ to inspect, replay, or alert on, and because the fanout never reached `insertPendingRows`, there is also no `webhook_delivery` row for it. The event is permanently and silently lost for **every** subscribed vendor endpoint, with only a WARN log line as a trace, directly contradicting the class's own "durable enqueue" reliability claim.

**Fix:** Bind `webhook.deliveries` to a DLX (mirroring `DLX_EXCHANGE`/`PAYMENT_EVENTS_DLX`) so a processing failure is at least observable/replayable rather than silently discarded.

## Info

### IN-01: `WebhookProperties.Target.blockPrivateRanges` is a dead, duplicate config binding

**Outcome:** `fixed` (2026-07-15, commit `f806493`). Deleted `WebhookProperties.Target` (nested class + getter + field); `WebhookUrlValidator`'s own `@Value("${webhook.target.block-private-ranges:true}")` is the sole, still-active reader. Grep-confirmed zero external references before removal.

**File:** `core-java/src/main/java/uk/jtoye/core/webhook/WebhookProperties.java:157-169`

**Issue:** `WebhookProperties.Target` binds the same `webhook.target.block-private-ranges` key that `WebhookUrlValidator` reads directly via its own `@Value("${webhook.target.block-private-ranges:true}")` constructor injection (`WebhookUrlValidator.java:38-41`). `WebhookProperties.getTarget()` is never called anywhere in the codebase (`grep` confirms zero call sites besides its own getter). This isn't a functional bug — both bindings resolve to the same property key — but it's a maintenance trap: an operator or future engineer reading `WebhookProperties` (the documented "no literals live in code paths" grouped-config seam for this feature) would reasonably believe changing `WebhookProperties.Target` controls SSRF blocking, when it does nothing.

**Fix:** Delete `WebhookProperties.Target`, or wire `WebhookUrlValidator` to consume `WebhookProperties.getTarget().isBlockPrivateRanges()` instead of its own separate `@Value`.

### IN-02: Email template substitution has no HTML-escaping helper — currently safe only because all interpolated values are system-generated

**Outcome:** `deferred` (2026-07-15) — SECURITY FOLLOW-UP. The one live vector (the onboarding `reason`, now rendered under WR-03) IS already HTML-escaped via `HtmlUtils.htmlEscape`. The remaining general hardening (a dedicated `sHtml()` used by all HTML `Copy` builders, so any FUTURE vendor-controlled field is escaped by construction) is deferred; tracked in `deferred-items.md`.

**File:** `core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java:175-178` (`s()` helper) and all `Copy` builders

**Issue:** `s(model, key)` does `String.valueOf(v)` with no HTML-escaping, and every `Copy` builder interpolates model values directly into `<strong>%s</strong>`-style HTML via `String.formatted`. Today this is not exploitable: `orderNumber` is system-generated (`OrderService.generateOrderNumber`), `amount` is numeric/currency-formatted, and the one case that would plausibly carry vendor-supplied free text (`shopName` in `onboardingCopy`, e.g. a vendor's shop display name) is never actually populated (see WR-03) so that branch is currently dead. If WR-03 is fixed by wiring `shopName` from `Shop.getName()` (vendor-controlled free text) without adding escaping here, this becomes a real HTML-injection vector into vendor-received emails.

**Fix:** Add a small `HtmlUtils.htmlEscape(...)`-based escape in `s()` (or a dedicated `sHtml()` used by the HTML builders only, keeping `wrapText`/plain-text unescaped) before this class starts consuming any vendor-controlled field.

---

_Reviewed: 2026-07-15T05:29:15Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

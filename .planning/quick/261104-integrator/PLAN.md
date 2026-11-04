# Quick 261104-integrator: persona close-out, integrator lane

Branch `fix/persona-integrator`. Issues: #808, #587, #864, #879, #841, #875, #460. Skipped by instruction: #452 gap 1, #102.

Method for every item: test first, seen RED on the unfixed code (or, where the test was written after part of the
code, a break arm that turns it RED), then GREEN. Evidence is in SUMMARY.md.

## #808 edge pre-auth valve per client address
- Fix: the pre-auth valve (`edge-go/cmd/edge/main.go rateLimiter`) is a bounded per-address bucket set, keyed by a
  client address resolved by core's `ClientIpResolver` rule (`clientip.go`): X-Forwarded-For only from
  `TRUSTED_PROXY_CIDRS`, walked from the right, literals only, /0 refused. Authenticated per-tenant limits unchanged.
  `TRUSTED_PROXY_CIDRS` and `RATE_LIMIT_IP_MAX` wired into compose and the k8s edge deployment (goldens regenerated).
- Tests: `ratelimit_preauth_test.go` (anonymous flood from one IP, then an authenticated tenant from another is 202;
  forged XFF cannot mint buckets; trusted proxy attributes the forwarded client; bounded map; resolver table).
- Threat notes: spoofed XFF (UXT-006 class) closed by the trust rule; memory bounded; a flood spread over more than
  RATE_LIMIT_IP_MAX addresses shares one overflow bucket (stated residual; belongs at the ingress).

## #587 webhook retry window
- Fix: defaults 15 attempts, 30 s base, 4 h cap = ~24.3 h (`webhook.delivery.*`, application.yml, WebhookProperties,
  k8s app-config `webhook.delivery.max-attempts` which carried the old 8 as an override). Auto-pause semantics kept.
- Tests: `WebhookRetryScheduleTest` (shipped yml through the worker's `computeBackoffMillis`), binding test, auto-pause IT.

## #864 order webhook payload
- Fix: `OrderWebhookDataFactory` builds `data` for `order.*` from the order (items, totals, fulfilment, payment status,
  customer, delivery address), `dataVersion: 2`, original seven members unchanged, thin fallback if the order is
  unreadable. Built at the webhook edge, not on the shared `OrderStateChangeEvent` (SSE/email must not carry PII).
  Article 17 erasure scrubs the stored payload copy. docs/webhooks.md corrected.
- Privacy: subscriptions are GROUP_ADMIN-only, so the recipient is entitled; the STAFF ruling (#856) does not apply.
- Tests: `OrderWebhookPayloadIntegrationTest` (shape, collection, fallback, erasure scrub) with a break arm.

## #879 webhook test event + delete; UXT-123
- Fix: `POST /{id}/test` (202, one attempt, never counts toward auto-pause), `DELETE /{id}` (REVOKED only, 409
  otherwise, log goes too), typed 409 `webhook-subscription-state`; dashboard buttons; docs for redirects (by design)
  and the MCP decision (not exposed, recorded reason). UXT-123: MCP tool errors keep problem type + typed members;
  OpenAPI `tokenUrl` from the public expected issuer; `Idempotent-Replayed` response header (+ CORS exposure).
- Not done (recorded): request/response body inspector (PII, owner call); `*/*` response types, global 429 docs,
  `pageable` parameter shape.
- Tests: `WebhookTestEventAndDeleteIntegrationTest`, `WebhookAuthzIntegrationTest` (STAFF arms), `errors.test.ts`,
  `OpenApiConfigServerUrlTest`, idempotency ITs, `webhooks-test-event-delete.test.tsx`.

## #841 order contact validation
- Fix: `PlausibleContact` on guest and vendor order requests (name: 2 letters; email: dotted domain; phone: UK national
  / +44 / 0044 / other E.164); vendor/API/MCP order needs customerId or one contact, or `walkIn: true`. Typed 400
  (`errors` keyed by field). Checkout inline validation mirrors core; MCP `create_order` gains `walkIn`.
- Tests: `OrderContactValidationTest` (60 cases), `contact-and-radius.test.ts(x)`, MCP tests.

## #875 dashboard polish
- UXT-101 (explainer after the orders below lg; Created column below xl), UXT-115 (SKU optional, generated
  server-side `JTP-XXXXXXXX`), UXT-117 (Shop column under All shops). Staff email masking is a deliberate privacy
  choice (WR-10) and is kept. UXT-098 already fixed (ticked in the issue).

## #460 delivery radius
- Fix: V94 `shops.delivery_radius_km` (+ `shops_aud`), `FulfilmentPolicy.requireWithinDeliveryRadius` on both writers,
  typed 422 `delivery-out-of-range` / `delivery-postcode-unrecognised`, shop API + form field, storefront hint and
  postcode-field refusal. No radius, or radius without a stored coordinate: unchanged.
- Cache key format v5 -> v6 (additive field on cached DTOs).
- Tests: `DeliveryRadiusIntegrationTest` (London shop; Edinburgh, Croydon, near), shop form, checkout.

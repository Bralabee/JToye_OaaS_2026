# Phase 22 — Deferred Items

Out-of-scope discoveries logged during plan execution (SCOPE BOUNDARY rule). These
are NOT fixed by the discovering plan; they are reconciled at the phase gate.

## OpenAPI snapshot (`docs/api/openapi-snapshot.json`) is stale — needs one phase-gate regeneration

**Discovered during:** 22-03 (webhook subscriptions).

**What:** `OpenApiSnapshotTest` asserts byte-equality of `docs/api/openapi-snapshot.json`
against the live `/v3/api-docs`. Running `./gradlew :core-java:updateOpenApiSnapshot`
shows the committed snapshot is missing endpoints from **already-merged** plans, not just
this one:

- Phase 21: `/api/v1/onboarding/withdraw`, `/api/v1/onboarding/company-number`,
  `/api/v1/onboarding/admin/reviews`, `/api/v1/onboarding/admin/{id}/gates/{gateType}/resolve`
- 22-02: `/api/v1/public/unsubscribe` (+ `/public/unsubscribe`)
- 22-03: `/api/v1/webhooks`, `/api/v1/webhooks/{id}`, and the
  `rotate-secret` / `pause` / `resume` / `revoke` actions
- 22-05: `GET /api/v1/webhooks/{subscriptionId}/deliveries` (delivery log) and
  `POST /api/v1/webhooks/{subscriptionId}/deliveries/{deliveryId}/replay`

The snapshot is a whole-spec, byte-stable artifact — it cannot be regenerated for one
plan's endpoints in isolation. Regenerating it here would absorb three plans' worth of
unrelated API surface into the 22-03 commit and violate the plan's "stay within webhook/*
and V55" invariant. So 22-03 deliberately does NOT touch the snapshot.

**Action required (phase gate):** run `./gradlew :core-java:updateOpenApiSnapshot` once, in
the same reconciliation step as `scripts/docs-freshness.sh --write` / `docs/metrics.json`,
and commit the single regenerated snapshot. The regeneration was verified to REMOVE no
existing endpoints (additive only).

**Note:** `OpenApiSnapshotTest` (tag `testcontainers`) is therefore already red on this
branch independent of 22-03 (Phase 21 / 22-02 drift). 22-03's own verification uses the
filtered `uk.jtoye.core.webhook.*` suites, which are all green.

## `docs/metrics.json` invocation counts — phase-gate reconcile

**Discovered during:** 22-05 (webhook delivery engine).

**What:** each Comms plan adds Java `@Test`/integration methods (22-05 adds
`WebhookSignerTest` ×4, `WebhookDeliveryRlsPolicyIntegrationTest` ×2,
`WebhookDeliveryWorkerIntegrationTest` ×3, `WebhookRetentionCleanupTest` ×1). The
`docs-freshness` CI gate (`scripts/docs-freshness.sh`) enforces the invocation
totals in `docs/metrics.json`, which are a whole-repo aggregate. Per the RESEARCH
sampling plan, the count reconcile runs ONCE at the phase gate
(`scripts/docs-freshness.sh --write`), not per-plan, to avoid every plan editing the
same shared aggregate file. 22-05 stays within `webhook/*` + `V56` + `application.yml`
and its own tests; the metrics reconcile is deferred to the phase gate alongside the
OpenAPI snapshot regeneration above.

---

# SECURITY FOLLOW-UP — deferred code-review findings (from 22-REVIEW.md)

Of the three findings from the Phase 22 code review (`22-REVIEW.md`, reviewed 2026-07-15)
that were deferred out of the correctness-bug batch, **CR-01 is now RESOLVED** (2026-07-15,
commit `5d7b88d`). WR-04 + IN-02 remain tracked below.

## CR-01 (CRITICAL) — SSRF / DNS-rebinding TOCTOU in webhook delivery — ✅ RESOLVED 2026-07-15 (`5d7b88d`)

**Was:** delivery-time re-validation resolved + discarded an IP, then `webClient.post()`
performed an INDEPENDENT DNS resolution through Reactor Netty's own resolver — a rebinding
window to `169.254.169.254` (Azure metadata) on the `@Scheduled` loop.

**Fixed by:** `SsrfGuardAddressResolverGroup` (a Netty `AddressResolverGroup` that validates
the exact address it returns for the connection → single resolution, no TOCTOU), wired into a
dedicated webhook-only `WebClient` via `WebhookDeliveryClientConfig` with `followRedirect(false)`;
`WebhookUrlValidator.isAddressAllowed` is the shared block-list (create/delivery/connect can't
diverge). Only the DNS lookup is intercepted, so `Host`/SNI/cert verification still target the
hostname. Scoped to webhook egress only (trusted `keycloak:8080`/Stripe untouched). Proven by
`WebhookSsrfResolverTest` (6 tests, incl. metadata/RFC1918/loopback rejection + exact-validated-
address pinning + multi-record any-private rejection) and independently re-verified CLOSED by
`gsd-security-auditor`. `22-SECURITY.md` → `threats_open: 0, status: verified`.

## WR-04 (WARNING) — unsubscribe token/email sent as query-string params on POST

**Files:** `frontend/app/unsubscribe/unsubscribe-content.tsx:57-62` +
`core-java/.../notification/consent/PublicUnsubscribeController.java:61-70`.

**What:** the POST `/api/v1/public/unsubscribe` places `tenant`/`email`/`category`/`token`
in the axios query string (backend only declares `@RequestParam`), so infra-level access
logs (nginx/ingress/APM full-URL spans) capture the recipient email + HMAC token verbatim
even on the POST path, despite the "never logged" claim. Fix: accept a `@RequestBody`
`UnsubscribeRequest` for the POST variant + send it as the request body from the frontend;
keep the GET click-through variant as-is.

**Why deferred:** touches the frontend (`unsubscribe-content.tsx` + `public-api-client`),
which is out of scope for this backend-only (core-java) fix batch.

## IN-02 (INFO) — no general HTML-escape helper in EmailTemplateRenderer

**File:** `core-java/src/main/java/uk/jtoye/core/notification/template/EmailTemplateRenderer.java:175-178`
(`s()` helper) + all `Copy` builders.

**What:** `s(model, key)` interpolates model values into `<strong>%s</strong>` HTML with no
escaping. **Partially pre-empted:** the WR-03 fix already HTML-escapes the one field now
rendering vendor/system free text (the onboarding `reason`, via `HtmlUtils.htmlEscape`).
The remaining work is the GENERAL hardening: a dedicated `sHtml()` used by all HTML builders
(keeping `wrapText`/plain-text unescaped) so any FUTURE field wired from vendor-controlled
input (e.g. a real `shopName` from `Shop.getName()`) is escaped by construction.

**Why deferred:** the immediate injection vector (the reason field) is closed; the general
helper is defense-in-depth for future fields and belongs with the security follow-up.

---
phase: 22
slug: notifications-comms
status: blocked
threats_open: 1
asvs_level: 1
created: 2026-07-15
---

# Phase 22 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Register authored at plan time (all 7 PLANs carried a `<threat_model>` block); verified in
> **verify-mitigations-exist mode** against real source by `gsd-security-auditor` (2026-07-15).

**GATE: BLOCKED.** 1 CRITICAL threat open (T-22-05-03) — `block_on: high`. Phase advancement/ship is
blocked until `threats_open: 0`. Fix on the CR-01 security follow-up branch, then re-run `/gsd-secure-phase 22`.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| config → app | Provider creds + signing secrets injected via env | SMTP/WhatsApp creds, HMAC secrets (must never be hardcoded/logged) |
| internet → public API | `/api/v1/public/unsubscribe` no-auth; token is the only authz | HMAC unsubscribe token, recipient email |
| vendor client → webhook API | authenticated (Bearer JWT + X-Tenant-Id); vendor supplies arbitrary target_url | subscription config, signing_secret |
| app → vendor-supplied URL | outbound HTTP egress to a vendor URL (validated at create; re-guarded at send) | full-entity event snapshot of the vendor's own tenant data |
| RabbitMQ → consumer thread | events arrive off-request with NO tenant context; GUC pinned before any tenant read | tenant PII (order/onboarding/payment details) |
| tenant ↔ tenant | suppression / marketing_opt_in / webhook_subscription / webhook_delivery RLS-isolated | cross-tenant reads must return 0 rows |
| rendered data → DOM (dashboard + public page) | delivery URLs / HTTP codes / secrets / unsubscribe token | XSS + PII-in-DOM surface |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation (verified) | Status |
|-----------|----------|-----------|-------------|------------------------|--------|
| T-22-01-01 | Information Disclosure | WhatsApp/Notification creds in logs | mitigate | masked `toString()` — `WhatsAppProperties.java:97-111`, `NotificationProperties.java:43-54` | closed |
| T-22-01-02 | Tampering | Hardcoded secrets/flags | mitigate | all keys `${ENV:default}`, secrets default empty — `application.yml:160-164,305,316-329` | closed |
| T-22-01-03 | Denial of Service | WhatsApp stub throwing/blocking | mitigate | fail-closed WARN-no-op via `AtomicBoolean`, never throws — `WhatsAppSmsChannel.java:49-63` | closed |
| T-22-02-01 | Tampering | Unsubscribe-link forgery | mitigate | HMAC-SHA256(tenant\|email\|category) + constant-time `MessageDigest.isEqual` — `UnsubscribeTokenService.java:47-65` | closed |
| T-22-02-02 | Information Disclosure | Email-existence enumeration | mitigate | identical response valid/invalid, no address lookup, PII-free response/logs — `PublicUnsubscribeController.java:84-104` (residual: WR-04) | closed |
| T-22-02-03 | Information Disclosure | Cross-tenant read of suppression/opt-in | mitigate | both tables ENABLE+FORCE RLS via `current_tenant_id()` — `V54:49-55,68-74` (NOSUPERUSER-proven) | closed |
| T-22-02-04 | Repudiation | GDPR opt-out silently expiring | mitigate | suppression never time-pruned (only `webhook_delivery` is) — `V54:15-21`, `WebhookRetentionCleanup.java:24-27` | closed |
| T-22-03-01 | Elevation/Info Disclosure | SSRF via target_url at CREATE | mitigate | HTTPS-only + block loopback/RFC1918/link-local/169.254.169.254/multicast/IPv6-ULA, fail-closed — `WebhookUrlValidator.java:60-114` | closed |
| T-22-03-02 | Information Disclosure | Cross-tenant read of signing_secret | mitigate | FORCE RLS `V55:36-42`; no secret field in `WebhookSubscriptionDto.java:16-24`; `toDto` sets none | closed |
| T-22-03-03 | Information Disclosure | Secret leak via re-fetch/logs | mitigate | plaintext only via `WithSecret` on create/rotate; never in GET DTO; no secret logging — `WebhookSubscriptionService.java:66,69,93,95` | closed |
| T-22-03-04 | Spoofing | Old-secret signatures valid after rotate | mitigate | rotate overwrites stored secret with `SecureRandom` 256-bit — `WebhookSubscriptionService.java:84-96,138-142` | closed |
| T-22-04-01 | Information Disclosure | Cross-tenant recipient resolution | mitigate | TenantContext+GUC pinned from `event.tenantId()` before any read — `OrderNotificationListener.java:60-74` (+Financial/Onboarding) | closed |
| T-22-04-02 | Tampering/DoS | 2nd listener stealing from incumbent | mitigate | each consumer OWN durable queue, incumbent queues untouched — `RabbitMQConfig.java:202-266` | closed |
| T-22-04-03 | Denial of Service | New event type poison-dead-letters | mitigate | consumers only, no new outbox type; unknown handler ignores — `WebhookFanoutListener.java:106-110` | closed |
| T-22-04-04 | Information Disclosure | Emailing an opted-out recipient (GDPR) | mitigate | `consentGate.allows(...)` gate before every `EmailChannel.deliver` — `NotificationDispatchService.java:106-120` | closed |
| T-22-04-05 | Repudiation | Order-email regression / duplicate customer email | mitigate | legacy `EmailNotificationService` untouched; new order path VENDOR-only — `RecipientResolver.java:98-110` | closed |
| T-22-05-01 | Tampering/Spoofing | Forged/tampered webhook payload | mitigate | HMAC-SHA256 `t=,v1=` over exact POSTed bytes — `WebhookSigner.java:45-59`, `WebhookDeliveryWorker.java:168-179` | closed |
| T-22-05-02 | Repudiation | Replay of a captured webhook | mitigate | signed timestamp + 300s tolerance + `X-JToye-Event-Id` — `WebhookSigner.java:53`, `WebhookDeliveryWorker.java:177`, `application.yml:325` | closed |
| **T-22-05-03** | **Elevation/Info Disclosure** | **SSRF at delivery / DNS-rebinding** | **mitigate (INCOMPLETE)** | **validator resolves+DISCARDS the IP, WebClient re-resolves independently (no IP pinning) — TOCTOU reachable to Azure metadata. `WebhookDeliveryWorker.java:157-182` + `WebhookUrlValidator.java:80-95`. Deferred as CR-01.** | **OPEN** |
| T-22-05-04 | Denial of Service | Head-of-line block starving other subs/tenants | mitigate | per-(sub,event) rows + `FOR UPDATE SKIP LOCKED` + per-tenant tx + auto-pause — `WebhookDeliveryRepository.java:42`, `WebhookDeliveryWorker.java:123-144,219-227` | closed |
| T-22-05-05 | Denial of Service | Unbounded webhook_delivery growth (#107) | mitigate | `@Scheduled` per-tenant retention prune — `WebhookRetentionCleanup.java:49-73` | closed |
| T-22-05-06 | Information Disclosure | signing_secret in logs | mitigate | logs status/attempts/exception-class only — `WebhookDeliveryWorker.java:193,208,231` | closed |
| T-22-05-07 | Information Disclosure | Cross-tenant read of delivery payloads | mitigate | `webhook_delivery` ENABLE+FORCE RLS via `current_tenant_id()` — `V56:45-51` | closed |
| T-22-06-01 | Information Disclosure | Signing secret persisted/re-fetchable in UI | mitigate | secret set only from create/rotate response; shown once in `SecretRevealDialog`; GET DTO carries none — `[id]/page.tsx:173-177` | closed |
| T-22-06-02 | Tampering | XSS via rendered URL/error text | mitigate | React auto-escaping; zero `dangerouslySetInnerHTML` in webhook/unsubscribe UI | closed |
| T-22-06-03 | Tampering | Duplicate delivery on replay | mitigate | replay routes through generic V50 `IdempotencyService.execute` (fixed inline `688a54c`) — `WebhookDeliveryController.java:89-100` | closed |
| T-22-06-04 | Denial of Service (UX) | 375px overflow / bundle bloat | mitigate | card-stacking below sm + Jest 375px no-overflow + CWV smoke (22-07) | closed |
| T-22-07-01 | Information Disclosure | Email/token leaked via meta/index/logs | mitigate | noindex,nofollow + sitemap exclusion; token never in DOM — `unsubscribe/page.tsx:20-24`, `unsubscribe-content.tsx` (residual: WR-04) | closed |
| T-22-07-02 | Information Disclosure | Public unsubscribe page indexed | mitigate | `robots:{index:false,follow:false}` + sitemap-excluded — `unsubscribe/page.tsx:20-24` | closed |
| T-22-07-03 | Tampering | Docs/metrics drift hiding an untested file | mitigate | docs-freshness CI gate — `.github/workflows/docs-freshness.yml`, reconcile `3a22471` | closed |
| T-22-07-04 | Denial of Service (UX) | New routes regressing CWV at throttled mobile | mitigate | responsive + Suspense/lazy; CWV smoke over 3 new routes (22-07) | closed |
| T-22-0X-SC (×7) | Tampering (supply-chain) | npm/gradle installs | accept | no packages installed this phase (mail/webflux/resilience4j/jackson/shadcn already vendored) | closed (accepted) |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

### Residual warnings (non-blocking at ASVS L1 / block_on: high)

- **WR-04 (medium)** — partially undercuts the "no PII in logs" sub-claim of T-22-02-02 / T-22-07-01. `POST /api/v1/public/unsubscribe` binds `tenant/email/category/token` as `@RequestParam` and the frontend sends them as axios query `params`, so the recipient email + HMAC token land in the request URL → captured by infra/ingress/APM access logs on the POST path. Application logs are clean; enumeration + meta/index/DOM mitigations remain intact. Fix: accept a `@RequestBody UnsubscribeRequest` for POST. Tracked in `deferred-items.md`.

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| SC-22 | T-22-0X-SC (×7) | No third-party packages installed this phase — all deps (Spring mail/webflux, resilience4j, jackson, vendored shadcn primitives) already present + first-party; slopcheck N/A | plan-time disposition | 2026-07-15 |

*Note: T-22-05-03 was explicitly NOT accepted (user decision 2026-07-15) — it remains OPEN and blocking.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-07-15 | 38 | 37 | 1 | gsd-security-auditor (opus) + code review (22-REVIEW.md) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [ ] `threats_open: 0` confirmed — **BLOCKED: T-22-05-03 (CR-01 SSRF/DNS-rebinding) open**
- [ ] `status: verified` set in frontmatter

**Approval:** pending — blocked on CR-01. Fix (resolve-once + pin the validated IP, preserving Host/SNI; test proving connected-IP == validated-IP), then re-run `/gsd-secure-phase 22`.

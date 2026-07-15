# Phase 22: Notifications & Comms - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-14
**Phase:** 22-notifications-comms
**Areas discussed:** Email format & branding, Preferences & recipients, Webhook payload contract, Webhook reliability & retry

---

## Email format & branding

| Option | Description | Selected |
|--------|-------------|----------|
| Plain-text now, HTML-ready seam | Extend the existing SimpleMailMessage path; renderer seam so HTML drops in later | |
| Branded HTML templates now | HTML templates (brand header/footer/button) per event + plain-text alternative | ✓ |
| You decide | Claude picks on effort vs consistency | |

**User's choice:** Branded HTML templates now (with plain-text alternative for deliverability).
**Notes:** Engine (Thymeleaf vs lightweight) left to research — Thymeleaf is not yet a dependency. Existing order-email path must not regress (COMMS-01).

---

## Preferences & recipients

| Option | Description | Selected |
|--------|-------------|----------|
| Per-category prefs + tenants.contact_email | Opt-out per category (orders/onboarding/financial/marketing); vendor=tenants.contact_email, customer=order email | ✓ |
| Global unsubscribe + tenants.contact_email | Single unsubscribe-all; simplest model | |
| You decide | Claude picks granularity + recipient resolution | |

**User's choice:** Per-category preferences + `tenants.contact_email` (V48, verified) for vendor, order email for customer.
**Notes:** Onboarding notifications are vendor-only (no platform operator). Transactional default-on (legitimate interest); marketing opt-in.

---

## Webhook payload contract

| Option | Description | Selected |
|--------|-------------|----------|
| Thin envelope + version field | {id,type,tenantId,occurredAt,version,data:{minimal}}; receiver callbacks for detail | |
| Full entity snapshot embedded | Full DTO embedded in the payload | ✓ |
| You decide | Claude picks envelope + versioning | |

**User's choice:** Full entity snapshot embedded.
**Notes:** Claude will still wrap it in a versioned event envelope. Reframed as NOT a third-party leak — delivery is to the vendor's own endpoint carrying their own tenant data; HTTPS-only + HMAC signing still required.

---

## Webhook reliability & retry

| Option | Description | Selected |
|--------|-------------|----------|
| At-least-once, exp backoff, auto-pause | Receivers dedupe on event id; exponential backoff; failing subscription auto-pauses | ✓ |
| Best-effort, few retries | Small fixed retry count, no auto-pause | |
| You decide | Claude picks guarantee + thresholds | |

**User's choice:** At-least-once + exponential backoff + auto-pause (Stripe-like).
**Notes:** Concrete attempt/backoff/pause/retention thresholds config-injected. No head-of-line block (per-subscription isolation).

## Claude's Discretion

- Consumer topology (per-domain listener vs unified dispatch service) — must respect the outbox-flusher dispatch trap.
- Webhook delivery mechanism (outbox+flusher poller vs queue consumer vs Resilience4j async).
- HTML template engine choice + template storage.
- Exact schemas + Flyway versions (post-V52/V53, out-of-order) for preferences/suppression, webhook_subscription, webhook_delivery.
- Signature header format (Stripe-style recommended), event-id/dedup scheme, retry/backoff/pause/retention numbers.
- Webhook management UI placement in the dashboard.
- Exact per-transition notification matrix per audience.

## Deferred Ideas

- AWS SES SDK + bounce/complaint feedback loop (prod uses SES-over-SMTP config this phase).
- Live WhatsApp/SMS delivery (scaffold-only, #208).
- Marketing campaign engine (composition/scheduling/segmentation).
- In-app / web-push notifications.
- Customer notification-preference dashboard beyond unsubscribe.
- Bulk/backfill webhook replay (single manual replay only this phase).

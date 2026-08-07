# WhatsApp Business Platform 2026 — Evaluation Against the OaaS Integration

**Date:** 2026-08-07
**Branch at time of investigation:** `docs/issue-triage-disposition`
**Trigger:** WhatsApp shipped a run of 2026 platform changes, several aimed at business
accounts. This document asks whether OaaS should keep building its own WhatsApp
functionality or connect to what Meta now provides — and what, if anything, the 2026
changes break in the integration as it currently stands.
**Scope:** analysis only. No code, config or migration is changed by this document.

---

## Verdict

**Most of what OaaS hand-built for WhatsApp order intake is now off-the-shelf. The one
thing OaaS most needs — multi-tenant routing — Meta still does not provide. And one 2026
change breaks the current code.**

Three findings, in order of how soon they bite:

1. **The webhook cannot be subscribed today.** Only `POST` is registered. Meta's
   subscription handshake is a `GET`, and no `hub.challenge` / `hub.verify_token`
   handling exists anywhere in `edge-go`. The intake path has therefore never been
   connectable to a real WhatsApp Business Account.
2. **BSUID breaks the identity assumption.** The webhook's `from` field is treated as a
   phone number and written straight to a customer phone column. During 2026 that field
   may contain a business-scoped user ID (`CC.alphanumeric`) or be absent entirely.
3. **The free-text parser is now the wrong build.** Catalogs plus the `order` webhook
   deliver structured line items. The regex, and the ambiguity workarounds around it,
   become dead code.

None of this is an argument that the WhatsApp work was misconceived. It is an argument
that the *cheapest* version of it changed underneath the project, and that the remaining
expensive part is not the part currently built.

---

## 1. What is actually built

Two halves, written in different phases, and — this is the load-bearing observation —
**they assume two different integrations.**

### 1.1 Inbound: a live path in `edge-go`

| Property | Value | Where |
|---|---|---|
| Route | `/api/v1/webhooks/whatsapp` — **POST only** | `edge-go/cmd/edge/main.go:299` |
| Handler | `WhatsAppWebhook` | `edge-go/cmd/edge/handlers.go:223` |
| Signature header | `X-Hub-Signature-256` | `edge-go/cmd/edge/handlers.go:226` |
| Signature check | `verifyWhatsAppSignature` over the raw body | `edge-go/cmd/edge/handlers.go:271` |
| Unconfigured secret | fail-closed 503 + Retry-After when `appSecret` is empty | `edge-go/cmd/edge/handlers.go:249` |
| Message parsing | a regex, `itemLinePattern` | `edge-go/internal/whatsapp/parser.go:48` |
| Accepted message types | text only — `msg.Type` gate | `edge-go/internal/whatsapp/parser.go:60` |
| Customer identity | `CustomerPhone` set from the webhook `from` | `edge-go/cmd/edge/handlers.go:369` |
| Tenant | one global value, `h.whatsAppTenantID` | `edge-go/cmd/edge/handlers.go:297` |
| Shop | one global value, `h.defaultShopID` | `edge-go/cmd/edge/handlers.go:361` |
| Failure behaviour | always `http.StatusOK`; real signal only in logs | `edge-go/cmd/edge/handlers.go:281` |

The security posture here is good and was clearly argued: the signature check is
fail-closed, the refusals are distinguished (401 for a bad signature, 503 for an
unconfigured one), and the guard logging `Ambiguous product query` (`edge-go/cmd/edge/handlers.go:342`)
deliberately skips rather than binding to the first
search hit. None of that is in question. What is in question is everything the endpoint
is asked to do *after* the signature verifies.

### 1.2 Outbound: an INERT stub in `core-java`

`WhatsAppSmsChannel` is scaffolding. The configuration block records its own status —
`Live send is out of scope` this phase (`core-java/src/main/resources/application.yml:240`) —
and the roadmap success criterion `COMMS-07` (`.planning/ROADMAP.md:104`, issue #208) says
the channel is scaffolded behind a provider flag defaulting off until credentials are
configured. No first-party call to the Meta Graph API exists anywhere in the repo — the
only `graph.facebook.com` strings are inside `frontend/node_modules`.

### 1.3 The straddle

The two halves presume different vendors:

- **Inbound is Meta Cloud API direct.** `WHATSAPP_APP_SECRET` plus the
  `X-Hub-Signature-256` scheme (`edge-go/cmd/edge/handlers.go:226`) is Meta's own
  app-secret signature scheme.
- **Outbound is BSP-shaped.** The config key is `account-sid` (`core-java/src/main/resources/application.yml:246`),
  alongside `auth-token` and `from-number`. `account-sid` is Twilio's vocabulary. Meta-direct outbound needs a phone
  number ID, a WhatsApp Business Account ID and a system-user access token; it has no
  concept of an account SID.

This is not currently a bug, because outbound never sends. It becomes one the moment
someone implements COMMS-07, because the two routes would be paying two different
platforms to do halves of one conversation — and because the multi-tenancy answer differs
sharply between the two (§4). **The provider decision is upstream of the remaining work
and has not been made.**

---

## 2. The 2026 platform changes that bear on this

### 2.1 BSUID and usernames — this one breaks current code

WhatsApp is moving off the phone number as the customer identifier. Message webhooks
carry a `user_id` field (a business-scoped user ID), and as usernames roll out, `from`
and `wa_id` may contain a BSUID of the form `CC.alphanumeric` — or be omitted from the
payload entirely for users who have hidden their number.

OaaS sets `CustomerPhone` directly from that field (`edge-go/cmd/edge/handlers.go:369`).
The failure is quiet and cumulative: customer rows
acquire values like `GB.1A2B3C4D5E…` in a phone column, and anything downstream that
dedupes by phone, or later tries to reach the customer on it, degrades without erroring.

**Required change**, not an enhancement: read `user_id` as the primary identifier, treat
`from` / `wa_id` as optional, and persist both identities separately.

> **Evidence caveat.** Meta's own changelog page returned **HTTP 500** when fetched on
> 2026-08-07, so the precise rollout dates below could not be confirmed from the primary
> source. They come from Twilio's changelog and several BSP advisories, which agree with
> each other: a `user_id` field on message webhooks from around 2026-03-31, and BSUIDs
> appearing in `from` / `wa_id` from around June 2026. **Treat the dates as approximate
> and the direction as certain.** Re-check against Meta's changelog before scheduling
> work.

### 2.2 Cloud API terms changed 2 April 2026

Continued use constitutes acceptance — there is no accept step. The change introduces a
Meta-side "Contact Book" that stores contact information for users who message the
business, for the duration of the active conversation.

This is a compliance item, not a technical one, and it lands on a project that has taken
data protection seriously elsewhere: `notification_consent` (V54), `erasure_records`
(V42), and the deliberate Article-17 scrub of Envers audit history. A new processor
holding customer contact data needs to appear in the DPIA and ROPA before the channel
carries real traffic.

### 2.3 Pricing — favourable, and it shapes the design

Per-message billing has applied since 1 July 2025. What matters for this integration:

- **Service messages** (free-form replies) are free.
- Inside the **24-hour customer service window** opened by an inbound customer message,
  non-template messages are free, and utility templates are free too.
- **Free entry point** conversations (Click-to-WhatsApp ads, Page buttons) open a
  72-hour free window once the business replies.
- **Marketing templates always cost.**

Order intake is customer-initiated by construction, so the entire confirm/clarify/
"your order is in" exchange sits inside a free window. Only status updates sent *after*
the window closes are billable utility templates. That is a favourable shape for a food
vendor: it argues for replying promptly rather than batching.

> **Unverified claim, deliberately not relied on.** A secondary source states that
> utility templates and free-form messages inside the service window become chargeable
> from 1 October 2026. Meta's own pricing page does **not** say this — it describes the
> 1 October 2026 change as several markets moving to standalone rate cards. The two
> disagree; the primary source wins, and the claim is recorded here as unconfirmed rather
> than smoothed away.

### 2.4 Webhook retries: 10 attempts over 48 hours

Previously 7. Two consequences:

- The handler's godoc still describes a `3-day exponential retry loop` (`edge-go/cmd/edge/handlers.go:206`). That is stale.
- More importantly, the always-`http.StatusOK` design (`edge-go/cmd/edge/handlers.go:281`)
  **declines the retries**. A transient Core outage, an expired service token, or a
  RabbitMQ-adjacent failure drops the customer's order permanently, and the only record
  is a log line. Meta is offering 48 hours of free redelivery and the handler answers
  "received, thanks" to every one of its own failures.

The original reasoning — do not enter a multi-day retry storm — is sound for *business*
outcomes (an unparseable message, an unknown product). It is wrong for *infrastructure*
outcomes. Those two are currently on the same code path, and separating them is a small,
high-value change.

### 2.5 Coexistence

A business can now run the WhatsApp Business **app** and the Cloud **API** on the same
number simultaneously — manual chat in the app, automation via the API. This is a
go-to-market unlock rather than a technical one, and it is worth recording because it
removes a real objection: a vendor no longer has to give up the WhatsApp they already
use to be onboarded onto the platform.

### 2.6 Not constraints here

Tier-1 throughput raised to 100 msg/s, the 100K daily messaging limit for verified
businesses, document size 16→25 MB. Recorded so a later reader does not re-investigate:
none of these binds a per-vendor food-ordering workload.

---

## 3. Connect or build

| Capability | Meta provides it? | OaaS position |
|---|---|---|
| Structured order capture | **Yes** — catalogs; single-product, multi-product (up to 30) and carousel messages; the customer builds a cart and the business receives an `order` webhook with line items | Regex-parsing free text via `itemLinePattern` (`edge-go/internal/whatsapp/parser.go:48`). Replace. |
| Product browsing in-chat | **Yes** — catalog synced from Commerce Manager or the Catalog API | None. OaaS products never reach Meta. |
| Structured data capture (address, slot, allergens) | **Yes** — Flows | None on the WhatsApp path |
| Confirm / cancel affordances | **Yes** — interactive reply buttons and list messages | None; text only, gated on `msg.Type` (`edge-go/internal/whatsapp/parser.go:60`) |
| Delivery observability | **Yes** — sent / delivered / read / failed status webhooks | Built for the *own* webhook channel (V56 `webhook_delivery`), absent for WhatsApp |
| Voice | **Yes** — Calling API | N/A |
| **Multi-tenant routing** | **No** — Meta's unit is one WABA + phone number per business | Single global `h.whatsAppTenantID` (`edge-go/cmd/edge/handlers.go:297`) and `h.defaultShopID` (`edge-go/cmd/edge/handlers.go:361`). Hard ceiling. |
| **In-chat payment (UK)** | **No** — Payments API (`order_details` / `order_status`) is a Brazil/India product | Use the existing Stripe path: order webhook → create order → payment link |
| Order lifecycle, RLS/tenancy, VAT, PPDS & allergen labelling, fulfilment | No | OaaS, correctly |

### 3.1 The one that matters

Everything above is a line item except multi-tenant routing, and that one is
architectural. Meta's model has no notion of a platform operating many businesses' chats
through one number. The SaaS answer is **Embedded Signup under a Tech Provider account**:
each vendor authorises their own WhatsApp Business Account through the OaaS Meta app, and
inbound webhooks are routed to a tenant by `phone_number_id`.

`WHATSAPP_DEFAULT_TENANT_ID` and `WHATSAPP_DEFAULT_SHOP_ID` are exactly the opposite
shape — a single global destination for all WhatsApp traffic. On a platform whose entire
security model is per-tenant RLS, the WhatsApp path currently has no tenant dimension at
all. It is the only ingress in the system with that property.

This is also where the §1.3 provider decision resolves: a BSP (Twilio and peers) sells a
different multi-tenancy story — numbers under the BSP's account, their onboarding, their
per-message margin — versus Meta-direct with Embedded Signup, which is more work up front
and cheaper per message. **That trade is the actual decision, and it is not a detail of
implementing COMMS-07; it precedes it.**

---

## 4. Defects and drift this review surfaced

Ranked by what hurts first. These are observations for triage, not a plan.

| # | Finding | Evidence |
|---|---|---|
| **1** | **The webhook cannot be subscribed.** Meta verifies a webhook by issuing a `GET` with `hub.mode` / `hub.verify_token` / `hub.challenge` and requiring the challenge echoed back. Only the POST route `/api/v1/webhooks/whatsapp` is registered, and no challenge/verify-token handling exists in `edge-go`. The intake path has never been connectable to a live WABA. | `edge-go/cmd/edge/main.go:299` |
| **2** | **The webhook `from` value is assumed to be a phone number** and stored as `CustomerPhone`. Breaks under BSUID (§2.1). | `edge-go/cmd/edge/handlers.go:369` |
| **3** | **No tenant dimension** — one global `h.whatsAppTenantID` for all WhatsApp orders. | `edge-go/cmd/edge/handlers.go:297` |
| **4** | **One global `h.defaultShopID`** — every WhatsApp order lands in the same shop. | `edge-go/cmd/edge/handlers.go:361` |
| **5** | **Infrastructure failures are swallowed as `http.StatusOK`**, declining 48h of free redelivery. Business-outcome 200s are correct; infrastructure-outcome 200s are not, and they share a path. | `edge-go/cmd/edge/handlers.go:281` |
| **6** | **Provider straddle** — Meta-direct inbound, but the outbound config key is `account-sid`. Inert today, contradictory the moment outbound ships. | `core-java/src/main/resources/application.yml:246` |
| **7** | **Text-only intake** — the `msg.Type` gate silently ignores everything else, so a customer sending a voice note or image gets no reply and no order. | `edge-go/internal/whatsapp/parser.go:60` |
| **8** | **Stale doc string** — the godoc still says `3-day exponential retry loop`; it is now 10 retries over 48h. | `edge-go/cmd/edge/handlers.go:206` |
| **9** | **The estimate predates the requirement.** `6.2 WhatsApp Integration` is costed at 10–15 days and ranked 16th of 17. That estimate assumes self-built intake and no multi-tenancy. Catalogs make intake cheaper; Embedded Signup makes the total larger. | `docs/planning/FUTURE_ENHANCEMENTS.md:459` |

---

## 5. Recommendation

Do not extend the free-text parser. Reframe the remaining WhatsApp work in this order —
the first two are small and unblock everything, the fourth is the real phase:

1. **Add the `GET` verification handler.** Small. Without it nothing else can be tested
   against a real WABA, which is presumably why none of this has been.
2. **Make identity BSUID-safe.** Read `user_id` as primary, allow `from` / `wa_id` to be
   absent, persist phone and BSUID separately. Time-boxed by Meta's rollout, so it is
   maintenance rather than a feature.
3. **Decide Meta-direct vs BSP** (§1.3, §3.1) before writing outbound code. This is a
   commercial decision with a technical tail, not the reverse.
4. **Build multi-tenant WABA routing** — Embedded Signup, route by `phone_number_id`.
   This is the phase-sized item and the one that turns a demo into a product.
5. **Then replace the parser with catalog sync plus the `order` webhook.** OaaS already
   has products with images and a media pipeline; pushing a catalog to Meta is a sync
   job. It deletes the `Ambiguous product query` problem (`edge-go/cmd/edge/handlers.go:342`)
   rather than refining it.

Separately and cheaply: split infrastructure failures from business failures in the
handler so Meta's retries are used (defect 5), and correct the stale retry doc string
(defect 8).

**Nothing above is scheduled by this document.** It records what is true as of
2026-08-07 so the decision can be made deliberately.

---

## 6. Method and falsifiability

Every claim about this repository was read from the tree at the branch named above, not
inferred from documentation:

- Route registration and HTTP verbs — read from `edge-go/cmd/edge/main.go`.
- The absence of the `GET` verification handler — searched `edge-go` for `challenge` and
  `verify_token`. Recorded as an absence, which is the weaker kind of evidence: it is a
  true statement about `edge-go` and is **not** a claim about ingress or proxy layers,
  which were not audited. If a challenge responder lives in front of the service, this
  finding is wrong and should be corrected here.
- Identity handling, tenant/shop scoping, and the always-200 behaviour — read from
  `edge-go/cmd/edge/handlers.go`.
- Parser grammar and accepted message types — read from
  `edge-go/internal/whatsapp/parser.go`.
- Outbound inertness — read from `core-java/src/main/resources/application.yml` and
  corroborated against the COMMS-07 success criterion in `.planning/ROADMAP.md:104`.
- No first-party Meta Graph API usage — searched the tree; every `graph.facebook.com`
  hit is inside `frontend/node_modules`.
- Every `file:line` citation in this document was checked with the repo's own gate,
  `scripts/check-doc-citations.sh`, run explicitly over this file via `CITATION_DOCS`.
  **The gate's first run over an earlier draft FAILED with 11 violations**, which is the
  fail-direction evidence: it is demonstrably capable of rejecting a citation that does
  not resolve to what the claim names. It was then re-run against a deliberately
  corrupted copy to confirm the same, and the clean state re-asserted afterwards. A gate
  observed only passing would not be evidence. Note this document is not in the gate's
  default doc set, so it is checked on demand, not in CI.

Claims that could **not** be verified, recorded rather than smoothed over:

- **Meta's changelog was unreachable** (HTTP 500 on 2026-08-07), so BSUID rollout dates
  come from Twilio's changelog and BSP advisories (§2.1). Direction certain, dates
  approximate.
- **The 1 October 2026 utility-pricing claim is contradicted by Meta's own pricing page**
  and is recorded as unconfirmed (§2.3).
- **UK availability of catalogs and the `order` webhook was not positively confirmed**
  from the primary documentation. The docs describe no UK restriction and call out only
  India-specific compliance obligations, but absence of a stated restriction is not
  confirmation of availability. Verify before planning against it.
- **No cost model was built.** The pricing shape in §2.3 is directional; nobody has
  multiplied it by an expected vendor's message volume.

---

## Sources

Meta primary documentation:

- [Pricing on the WhatsApp Business Platform](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing)
- [Catalogs overview](https://developers.facebook.com/documentation/business-messaging/whatsapp/catalogs/catalogs-overview/)
- [Payments API — Brazil](https://developers.facebook.com/documentation/business-messaging/whatsapp/payments/payments-br/overview/)
- [Changelog](https://developers.facebook.com/documentation/business-messaging/whatsapp/changelog) — **returned HTTP 500 on 2026-08-07**

Secondary (used only where the primary source was unreachable, and flagged as such):

- [Twilio — WhatsApp usernames: new BSUID field required starting June 2026](https://www.twilio.com/en-us/changelog/whatsapp-usernames--new-business-scoped-user-id--bsuid--field-re)
- [Meta BSUID is live in WhatsApp Cloud API: what to change in your webhooks and CRM](https://medium.com/@matthias_20536/meta-bsuid-is-live-in-whatsapp-cloud-api-what-to-change-in-your-webhooks-and-crm-9b6dc69058dd)
- [EZContact — WhatsApp Cloud API changes coming April 2026](https://ezcontact.ai/en/blog/whatsapp-cloud-api-changes-april-2026-privacy/)
- [MEF — WhatsApp Business April 2026](https://mobileecosystemforum.com/2026/04/08/whatsapp-business-april-2026/)
- [Woztell — WhatsApp API 2026 updates: pacing, 100K messaging limits, usernames](https://woztell.com/whatsapp-api-2026-updates-pacing-limits-usernames/)
- [Infobip — WhatsApp news and updates 2026](https://www.infobip.com/blog/whatsapp-news-and-updates)

---

## Related

- `docs/analysis/MESSAGING-BROKER-EVALUATION-2026-07-26.md` — same evaluation shape
  (external recommendation re-investigated against the actual topology)
- `docs/planning/FUTURE_ENHANCEMENTS.md` §6.2 — the original WhatsApp scope and estimate
- `.planning/ROADMAP.md` Phase 22 — COMMS-07, the WhatsApp/SMS seam (issue #208)
- `docs/architecture/SYSTEM_DESIGN_V2.md` — canonical comms topology

# Flipdish vs J'Toye OaaS — Competitive Teardown

**Last updated: 2026-07-24**

A detailed, evidence-based feature teardown of **Flipdish** (a mature, VC-backed
restaurant operating system) against **J'Toye OaaS** (this repository — a
multi-tenant UK retail SaaS platform). This is the referenceable `.md` view; the
same dataset drives the interactive page at **[`/competitive`](https://your-host/competitive)**
(radar chart + filterable feature matrix).

Status legend: ✅ full · 🟡 partial · ❌ none. Verdict = who wins that row.

> **Caveat (read before quoting):** For J'Toye, "PRESENT" means the code/wiring
> exists in **this repo**, **not** that every path is proven end-to-end at
> production scale (e.g. the `WHITE_LABEL` own-key payment path is a stub).
> Flipdish's features are live across 5,000+ brands. Every J'Toye row below
> carries a source-tree evidence path, or is explicitly marked
> "Absent — searched, not built."

---

## Executive summary

Flipdish is a **full restaurant OS** sold to individual restaurants. It wins
roughly **14 of ~20 functional categories** outright — native apps, POS/EPOS,
loyalty, courier and aggregator integrations, marketing automation, kiosk, AI
phone ordering — and wins **decisively on maturity, scale and capital** (5,000+
brands, 25 countries, ~$1.25B valuation).

J'Toye wins a **narrow, coherent cluster**: UK-compliance-native (PPDS/Natasha's-Law
labels, VAT ledger, FHRS + Companies House onboarding gating, GDPR erasure),
native marketplace payments (Stripe Connect destination charges), an
agent-operable MCP write API, and a resellable RLS multi-tenant white-label
engine.

**One-line verdict:** *Compete on the compliance + marketplace-engine +
agent-ready wedge; do NOT try to out-feature Flipdish head-on.*

**Headline counts (from the dataset below):** 29 tracked features —
**10 J'Toye leads · 9 Flipdish leads · 6 hard gaps · 4 parity.**

---

## Category-by-category matrix

### A. Customer ordering channels

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| Branded web ordering | ✅ | ✅ | Flipdish | `core-java PublicStorefrontController.java` + `frontend app/shop/[slug]/page.tsx` |
| Native mobile apps (iOS/Android) | ✅ | ❌ | Flipdish | Absent — searched, not built (no RN/Flutter/native; Next.js web only) |
| Self-service kiosk | ✅ | ❌ | Flipdish | Absent — searched, not built |
| QR / at-table ordering | ✅ | ❌ | Flipdish | Absent — `FulfilmentType` has DELIVERY/COLLECTION only; no table/dine-in |
| WhatsApp ordering | ❌ | 🟡 | **J'Toye** | `edge-go/internal/whatsapp/parser.go` + notification `WhatsAppSmsChannel.java` |
| AI phone-order agent | ✅ | ❌ | Flipdish | Absent — searched, not built |

*Rationale:* Both ship branded web ordering, but Flipdish's is AI-optimised and
battle-tested at scale; Flipdish ships branded native apps, in-store kiosk, and an
AI Phone Agent that answers/takes calls, while J'Toye is web-only and DELIVERY/COLLECTION
only. J'Toye's one edge here is an inbound WhatsApp order parser + notify channel.

### B. Payments & monetization

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| Card payments | ✅ | ✅ | Parity | `core-java payment/PaymentService.java` (Stripe) |
| Marketplace / multi-vendor payouts | 🟡 | ✅ | **J'Toye** | `payment/PaymentService.java` (`transfer_data[destination]` + `application_fee_amount`) + `StripeConnectService.java` |
| White-label vendor-own-key payments | 🟡 | 🟡 | Parity | `payment/PaymentService.java` (WHITE_LABEL own-key marked future slice) |

*Rationale:* Flipdish Pay vs Stripe is functional parity on card payments. J'Toye
has native Stripe Connect destination charges + `application_fee`. Neither fully
ships white-label vendor-own-key payments (J'Toye's own-key path is a stubbed
future slice).

### C. Kitchen & operations

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| Kitchen Display System (real-time) | ✅ | ✅ | Flipdish | `core-java OrderController /orders/stream` (SSE) + `websocket/WebSocketConfig.java` + `frontend dashboard/kitchen` |
| POS / EPOS till (front-of-house) | ✅ | ❌ | Flipdish | Absent — searched, not built |
| Inventory / stock cost control | ✅ | 🟡 | Flipdish | Partial — order-driven stock decrement; no cost/wastage/purchasing module |
| Order state machine / fulfilment | ✅ | ✅ | Parity | `order/OrderService.java` + `OrderStatus`/`OrderEvent` + `processed_order_events` |

*Rationale:* J'Toye's KDS is genuinely built (SSE + STOMP); Flipdish's is
POS-linked + multi-station. Flipdish has all-in-one + handheld POS and a full
stock/food-cost/wastage/purchasing suite vs J'Toye's basic order-driven stock
decrement. Both order state machines are robust; J'Toye adds outbox + idempotent
events.

### D. Growth & retention

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| Loyalty / points / stamps | ✅ | ❌ | Flipdish | Absent — searched, not built (no entity/controller/migration) |
| Marketing automation (campaigns/CRM/segmentation) | ✅ | 🟡 | Flipdish | Partial — `shop/PromotionController.java` + `AnnouncementController.java` + consent; no campaign/CRM/abandoned-cart engine |
| Redeemable promo codes / vouchers / gift cards | ✅ | ❌ | Flipdish | Absent — only shop-wide % / flat promotions; no code redemption/gift cards |
| Reviews / ratings | 🟡 | ✅ | **J'Toye** | `review/Review.java` + `ReviewService.java` + storefront `GET /shops/{slug}/reviews` |

*Rationale:* Flipdish has AI-driven loyalty, email/SMS/PPC campaign automation
(plus a managed team), and redeemable vouchers/gift cards. J'Toye has static
promotions + announcements + consent only, no redeemable codes. J'Toye's edge is
first-class food + delivery ratings on the storefront.

### E. Delivery / fulfilment

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| 3rd-party courier integration (Uber Direct, Stuart) | ✅ | ❌ | Flipdish | Absent — searched, not built |
| Dispatch / driver assign / live tracking | ✅ | 🟡 | Flipdish | Partial — `frontend app/track` + order tracking; no dispatch engine |
| Aggregator management (Deliveroo/Just Eat/Uber Eats) | ✅ | ❌ | Flipdish | Absent — searched, not built |
| Delivery-address + fee capture | ✅ | ✅ | Parity | `order/FulfilmentType.java DELIVERY` + `V26__shop_delivery_fee.sql` |

*Rationale:* Flipdish ships native courier integrations, full dispatch, and
centralised aggregator management. J'Toye has an order-tracking page but no courier
orchestration and no aggregator management. Address + flat delivery fee is parity
at the basic level.

### F. Analytics

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| Reporting / BI | ✅ | 🟡 | Flipdish | Partial — `finance/FinancialTransactionController` summary + rating summaries; no BI layer |

*Rationale:* Flipdish offers real-time + AI analytics; J'Toye has finance/VAT +
rating summaries only, no BI layer.

### G. UK compliance & regulatory

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| PPDS / Natasha's-Law allergen PDF labels | ❌ | ✅ | **J'Toye** | `product/ProductLabelService.java` (OpenPDF) + `AllergenCompletenessGate.java` + `V41__ppds_label_compliance.sql` |
| VAT ledger (append-only, per-order) | 🟡 | ✅ | **J'Toye** | `finance/FinancialTransaction.java` + `VatCalculator.java` + `V40__vat_ledger_correctness.sql` |
| FHRS + Companies House onboarding gating | ❌ | ✅ | **J'Toye** | onboarding `VendorOnboardingStateMachine` + `FhrsClient.java` + `CompaniesHouseClient.java` + `V43__vendor_onboarding.sql` |
| GDPR erasure / DSAR | 🟡 | ✅ | **J'Toye** | `gdpr/GdprController.java` + `erasure_records` (V42) |

*Rationale:* This is J'Toye's strongest cluster. J'Toye generates printable
allergen labels + gates go-live on completeness, runs a purpose-built immutable
VAT ledger, blocks go-live on live FSA hygiene + Companies House checks, and ships
a dedicated erasure controller + audit-history scrub. Flipdish leaves compliance
largely to the vendor.

### H. Platform / architecture

| Feature | Flipdish | J'Toye | Verdict | J'Toye evidence |
|---|:---:|:---:|---|---|
| Multi-tenant white-label engine (RLS) | ❌ | ✅ | **J'Toye** | `V2__rls_policies.sql` + `security/JwtTenantFilter.java` + `security/access/ShopStaff.java` |
| AI agent interface (MCP write API) | 🟡 | ✅ | **J'Toye** | `mcp-server/src/tools/create-order.ts` + `create-customer.ts` (idempotency-keyed) |
| Outbound webhooks (HMAC) | ❌ | ✅ | **J'Toye** | `webhook/WebhookSubscriptionController.java` + `WebhookDelivery.java` (V55/56) |

*Rationale:* J'Toye runs as a resellable RLS-isolated multi-tenant engine
(Flipdish is the SaaS, not the engine), exposes agent-operable read+write MCP
tools with an Idempotency-Key contract (Flipdish's AI is customer-facing, not
developer/agent-facing), and ships first-class HMAC-signed outbound webhook
subscriptions with delivery tracking.

---

## Where J'Toye wins

- **UK-compliance-native** — PPDS/Natasha's-Law labels · VAT ledger · FHRS/CH
  onboarding gating · GDPR erasure. Purpose-built for the UK regulatory surface
  Flipdish leaves to the vendor.
- **Native marketplace payments** — Stripe Connect destination charges +
  `application_fee`, wired for multi-vendor payouts.
- **Agent-operable MCP write API** — read + write tools (`create_order` /
  `create_customer`), idempotency-keyed — developer/agent-facing, not just a
  customer chatbot.
- **RLS white-label engine** — resellable, RLS-isolated multi-tenant engine, not
  a single-brand SaaS.

## Hard gaps (searched, not built)

- **Native mobile apps** (iOS/Android)
- **POS / EPOS till** (front-of-house)
- **Loyalty** (points / stamps)
- **Courier integrations** (Uber Direct, Stuart)
- **Self-service kiosk**
- **Marketing automation** (campaign/CRM/abandoned-cart engine — J'Toye has
  static promotions + announcements + consent only)

---

## Monetization & scale comparison

| | Flipdish | J'Toye |
|---|---|---|
| Brands / reach | 5,000+ brands · 25 countries | pre-market |
| Valuation | ~$1.25B (Tencent-led, Jan 2022) | — |
| Capital raised | ~$157M | £0 |
| Team | scaled org | solo/small build |
| Pricing model | £99–139/site/mo + setup fee + 2–7% commission + add-ons | Stripe Connect per-order platform fee |
| Subscription billing | built | no SaaS subscription billing built |

Flipdish monetizes via SaaS subscription + setup + per-order commission + add-on
modules. J'Toye's only wired monetization is a **Stripe Connect per-order platform
fee** — there is **no SaaS subscription billing built**.

---

## Strategic wedge — conclusion

Flipdish is a full restaurant OS sold to individual restaurants and wins ~14 of
~20 functional categories plus decisively on maturity/scale/capital. J'Toye wins
a narrow, coherent cluster — UK-compliance-native, native marketplace payments,
agent-ready, RLS white-label engine.

**Compete on the compliance + marketplace-engine + agent-ready wedge; do NOT try
to out-feature Flipdish head-on.**

---

## Sources

- flipdish.com/gb
- flipdish.com/products-overview
- flipdish.com/pos-system
- G2 Flipdish reviews
- Capterra UK Flipdish pricing
- Tracxn Flipdish profile

The J'Toye side is verified against this repo's source tree (paths cited per row
above).

---

> **Caveat (repeated for anyone quoting a single row):** For J'Toye, "PRESENT"
> means the code/wiring exists in the repo, **not** that every path is proven
> end-to-end at production scale (e.g. the `WHITE_LABEL` own-key payment path is a
> stub). Flipdish's features are live across 5,000+ brands. **Last updated:
> 2026-07-24.**

*Live interactive version: [`/competitive`](https://your-host/competitive) — radar
chart + filterable, searchable feature matrix (same dataset as this doc).*

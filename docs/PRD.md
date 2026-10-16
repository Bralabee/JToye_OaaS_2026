# J'Toye OaaS — Product Requirements Document

**Version:** v2.3 (milestone open) · **Status:** living document · **Last verified:** 2026-10-17
**Method:** every claim below was measured against `main @ 073a96dc` (Schema **V86**) and cross-referenced against [`docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`](analysis/BUSINESS_MODEL_DECISION_GUIDE.md) and [`ADR-0007`](architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md). Figures in [`docs/metrics.json`](metrics.json) are gate-enforced.

> This PRD describes the product **as it actually stands at Schema V86**, alongside the reframed Go-To-Market positioning and the malleable multi-model policy architecture ([`ADR-0007`](architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md)) governing Phase 30 (`PAY-01`..`PAY-04`), Phase 32 (`GTM-01`..`GTM-02`), and `V87+`.

---

## 1. Product summary & reframed business position

**J'Toye OaaS ("Operations as a Service")** is the **UK-Compliance-Native, Abuse-Hardened Direct-Order & Scheduled Fulfilment Operating System for Established UK West African Food Operators** — starting in one dense London cluster (Peckham, Brixton, Deptford, Lewisham, Woolwich, Barking/Dagenham).

It gives an operator end-to-end control of **both rhythms of a modern West African kitchen**:
1. **The Daily Counter & Local Delivery Takeaway (`Cohort A`):** Instant web/QR ordering, structured UK opening hours, 1-tap kitchen overload pause, real-time KDS, and cash-abuse protection.
2. **The Weekend Batch & Catering Ledger (`Cohort B`):** Advance scheduled fulfilment slots (`2L/4L` soup bowls, party jollof coolers, catering trays), cut-off enforcement, prep deposits, and consolidated kitchen "Cook Sheets".

**Core value (verbatim, `.planning/PROJECT.md`):** *"Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility."*

**Why J'Toye OaaS wins (the four high-probability pillars):**
1. **Direct-Margin Recovery (`£39/location/month + 0.5% GTV`):** Saves an established operator `~£2,500+/month` compared to 28%–35% aggregator commissions and `~£850+/month` compared to 7.99%–9%+ legacy white-label platforms (Flipdish), while keeping the **Vendor as the legal Merchant of Record (MoR)**.
2. **Statutory UK Food-Safety & Consumer-Law Moat:** Built-in server-enforced 14-allergen acknowledgement (`V63`/`V69`), separate cross-contact `"may contain"` masks (`V74`), ingredient-to-allergen reconciliation (`AllergenConsistencyService`), audited customer allergy notes (`V73`), OpenPDF Natasha's Law PPDS labels, statutory E-Commerce Regs 2002 Reg 6 trader disclosures (`V71`), and automated UK GDPR Art. 15/17 DSAR workflows (`V62`–`V75`).
3. **Abuse-Hardened, Financially Auditable Operations:** Keyed HMAC-SHA256 client-IP/email/phone cash-order caps (`V81`), server-authoritative basket price-change protection (`RWO-007`), audited order voiding with reversing per-shop VAT ledger entries (`V78`/`V79`), multi-shop staff RBAC (`V52`/`V77`/`V85`), and permanent HTTP 301 old-slug redirects (`V86`).
4. **Malleable Multi-Model Architecture ([`ADR-0007`](architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md)):** Decomposes commercial behaviour into four composable policy axes (`SettlementPolicyMode`, `FulfilmentTimingMode`, `StorefrontSurfaceMode`, `BillingPlanMode`) so J'Toye OaaS goes to market with Assisted Direct SaaS + Scheduled Batch Pre-Orders and supports additional models (Curated Batch Drops, PAYG Weekend Kitchens, White-Label Resellers, or Managed Marketplace) via configuration rather than code rewrites.

**What it explicitly is NOT:** an unsubsidised cold-start consumer delivery fleet, a payment custodian holding pooled white-label funds, a hardware POS replacement, or a legal compliance *guarantor* (software enforces structure and snapshots; the Food Business Operator remains responsible for recipe accuracy and physical cross-contamination).

---

## 2. Personas (verified at Schema V86)

| Persona | Mechanism in code | Built state (`V86`) |
|---|---|---|
| **Vendor owner** (tenant Group admin) | `GROUP_ADMIN` role in `shop_staff` (`V52`) or Keycloak realm `admin` (`V76`); tenant-wide scope | Full dashboard; `VendorOnboarding` state machine is the sole writer of `Shop.published` |
| **Shop manager / staff** | `shop_staff` (`V52`) + single-use email `staff_invite` (`V77`): `GROUP_ADMIN` / `SHOP_MANAGER` / `STAFF`; enforced by `ShopAccessService` | Shipped Phases 23 & 37. `jtoye.access.strict-scoping` defaults to **`true`** in every profile (`RWO-004`); revoking a user's last grant leaves zero access (`RWO-003`); customers (`V85`) and financial ledger rows (`V79`) are scoped per shop |
| **Kitchen staff** | KDS (`/dashboard/kitchen`), STOMP/SSE realtime, `New` order lane with looping alert + mute (`RWO-020`), session-expiry alarm (`RWO-021`), allergy-note read-receipt (`V73`), fulfilment & unverified-contact badges (`RWO-011`, `V81`) | Shipped Phases 31.1 & 37. Consolidated batch "Cook Sheet" projection is specified in `ADR-0007` (`GAP-GTM-03`) |
| **Consumer (account)** | `jtoye-customers` Keycloak 26.8.1 realm; email-verified; separate token plane (`CustomerJwtVerifier`); `/account` self-service GDPR data export & account deletion (`PGC-838`) | Live. Social IdPs recorded inert per `ADR-0005` until production HTTPS domain cutover |
| **Consumer (guest)** | Guest checkout by email; order tracking at `/track` with `V84` `order_items_guest_tracking` RLS policy showing line items and recorded allergen snapshots | Live. Server enforces allergen acknowledgement (`V69`), basket price expectations (`RWO-007`), and open cash-order caps (`V81`) |
| **Platform owner / operator** | **Deliberately none** — no cross-tenant human operator identity | DSAR access (`V75`) and erasure (`V62`–`V72`) execute via background per-tenant GUC-pinned workers; `ADR-0001` documents interim tenant-admin gate resolution (`#453`) |
| **AI agent / external integrator** | MCP server (`5` tools: `list_shops`, `list_products`, `read_orders`, `create_order`, `create_customer`), `integration_credential` register (`V82`), mandatory `Idempotency-Key` (`V50`) | Shipped Phases 20, 25 & 37 (`RWO-035`) |

---

## 3. Product surfaces (what exists on `main @ V86`)

### 3.1 Vendor dashboard — `frontend/app/dashboard/` (authenticated, tenant- and shop-scoped)
`/dashboard` overview; `shops`, `products` (with VAT rate selector `RWO-046` & cross-shop copy `RWO-026`), `products/import`, `orders` (with voiding `V78` & bulk junk-order rejection `V81`), `orders/[id]`, `customers` (`V85` shop-scoped), `finance` (`V79` shop-scoped `SALE | REVERSAL | MANUAL` ledger + CSV export), `marketing`, `kitchen` (KDS), `staff` (`V77` email invites + role grants), `webhooks`, `webhooks/[id]`, `onboarding`, `onboarding/approvals`, `media/review`, `payments/connect/{return,refresh}`. Responsive down to 375px (`MOBL-01`, `34-05`) on Tailwind CSS 4.3.3 (`39-07`) with declared four-tier width contracts (`Phase 35`).

### 3.2 Consumer storefront — `frontend/app/shop/**`, `/`, `/track`, `/account`, `/legal`, `/unsubscribe`
- **Locality & Discovery (`Phase 33`, `V61`):** Real published shops rendered server-side with JSON-LD structured data; Ordnance Survey Code-Point Open (`~1.75M` GB postcodes) geocoding on write; `GET /public/shops?lat=&lon=&radiusKm=` haversine proximity search + 3-tier postcode/text search.
- **Slug Continuity (`Phase 39-09`, `V86`):** Deliberate shop web-address changes record the previous slug in `shop_slug_alias` and answer old links/QR codes with a permanent HTTP `301` redirect.
- **Integrity-Hardened Checkout (`Phases 31.1 & 37`):**
  - Displays per-line and combined 14-allergen sets (`PGC-860`) + separate `"May contain"` cross-contact lines (`V74`, `PGC-861`).
  - Enforces server-side allergen acknowledgement (`V69`, `PGC-784`/`PGC-785`: `422` if missing, `409` if basket allergens changed).
  - Enforces server-side basket price/availability confirmation (`RWO-007`: `409` detailing any price or minimum-order change).
  - Enforces `ShopAvailabilityPolicy` (`V80` structured UK hours + timed/indefinite pause) and `FulfilmentPolicy` (`V83` `offers_delivery` / `offers_collection`).
  - Displays statutory `trader_identity` (`V71`, `PGC-789`) and conditionally renders VAT lines only when the vendor declares a VAT registration number (`RWO-047`).
  - Zero third-party Stripe scripts or cookies loaded on cash-only checkouts (`PGC-793`).

### 3.3 Public / marketing + legal
Landing `/`, `/for-operators`, `/business-model-guide`, `/competitive` (interactive Flipdish vs. J'Toye teardown), `/auth/signin`, `/track`, `/unsubscribe`, `/dsar/verify`, `/dsar/export`, `/invite/accept`, and five `/legal` routes (`index`, `privacy`, `cookies`, `retention`, `accessibility`).

### 3.4 Onboarding (`Phases 18, 21, 31.1, 37`)
The `VendorOnboarding` state machine is the **sole writer of `Shop.published`**. Four mandatory compliance gates run today:
1. `BUSINESS_VERIFIED` (Companies House lookup; `WAIVED` automatically for sole traders).
2. `FOOD_HYGIENE_RATING` (FSA FHRS/FHIS lookup with establishment picker).
3. `ALLERGEN_DATA_COMPLETE` (re-verified fresh at `GO_LIVE`).
4. `TRADER_IDENTITY` (`V71` — legal name, entity type, geographic address, and required contact email per E-Commerce Regs 2002 Reg 6(1)(c)).

### 3.5 Media (`Phases 24, 27, 36`)
Copy-on-write `media_asset` model (`V53`, `V58`–`V60`) backed by **Azure Blob Storage** (`Azurite 3.37.0` locally) with a strict public/private quarantine container split (`BLOB-02`), magic-byte sniffing, decompression-bomb guard, EXIF stripping, WebP derivative normalization, and broker-outage durability.

---

## 4. Compliance & Operational Hardening as Product (The Moat)

Every capability below is **shipped in `core-java` and `frontend` at Schema V86**:

- **Server-Enforced UK FSA 14-Allergen Snapshot & Acknowledgement (`V63`, `V69`, `V74`):** `order_items` snapshots `allergen_mask` and `allergen_flag_mask` at write time; `orders` records `allergen_ack_mask`, `allergen_ack_at`, and `placed_via` (`STOREFRONT` vs `VENDOR`). `products.may_contain_mask` (`V74`) tracks cross-contact risk separately and is never merged into the declared mask.
- **Audited Customer Allergy Note (`V73`):** Dedicated 500-char `allergy_note` at checkout; requires staff acknowledgement (`allergy_note_ack_at`, `allergy_note_ack_by`) on the KDS/order view and surfaces the read receipt to the customer on `/track` (`PGC-812`).
- **Natasha's Law PPDS PDF Labels (`V41`, `V74`):** OpenPDF generation (`AllergenLabelPdfService`) with bold allergen emphasis, separate `"May contain"` line, and `en-GB` production/use-by dates (`PGC-861`).
- **Statutory Seller Identity (`V71`):** `trader_identity` (+ `_aud`) with `ENABLE + FORCE RLS`, rendered across `/shop/[slug]`, checkout, confirmation page, and transactional emails (`PGC-789`).
- **Complete UK GDPR Art. 15 & Art. 17 Automation (`V62`, `V67`, `V68`, `V70`, `V72`, `V75`):** Single-use, expiring, AES-256-GCM encrypted cross-tenant JSON access exports (`dsar_access_export`, `V75`) and cross-tenant guest/customer erasure across `orders`, `orders_aud`, `reviews` (`V67`), and customer-realm Keycloak accounts (`V72`).
- **Cash-Order Abuse Resistance (`V81`):** Keyed HMAC-SHA256 client-IP digest (`client_ip_digest`, 48h retention), open cash-order caps (`3` per email, `3` per phone, `5` per IP), trusted-proxy CIDR validation (`RWO-006`), and per-tenant post-JWT rate limiting in `edge-go` (`RWO-039`).

---

## 5. What the Product Needs Next for Go-To-Market & Multi-Model Scale (`ADR-0007`)

To ensure none of the commercial or operational requirements standing in the way of success are omitted from implementation planning, the remaining work before and immediately following Go-To-Market is governed by [`ADR-0007`](architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md) and [`docs/analysis/GAPS_AND_IMPROVEMENTS.md`](analysis/GAPS_AND_IMPROVEMENTS.md):

| Priority | Requirement / Capability | Architectural Seam (`ADR-0007`) | Target Phase / Migration |
| :--- | :--- | :--- | :--- |
| **1 (P0)** | **Vendor-as-MoR Direct Charge Settlement Policy (`GAP-GTM-01`)** | `SettlementStrategy`: add `DIRECT_CHARGE_VENDOR_MOR` (`Stripe-Account` header + `application_fee_amount`) alongside existing `DESTINATION_CHARGE_PLATFORM_MOR` (`#102`) and `SPLIT_DEPOSIT_VENDOR_MOR` | **Phase 30 (`PAY-01`..`PAY-03`)** |
| **2 (P0)** | **Advance Pre-Orders & Scheduled Fulfilment Slots (`GAP-GTM-02`)** | `FulfilmentTimingPolicy`: add `shop_fulfilment_slots` (`ENABLE + FORCE RLS`, `cutoff_at`, `max_orders`, `max_units`, `@Version`) + `FulfilmentTimingMode` (`IMMEDIATE_ONLY`, `SCHEDULED_SLOTS_ONLY`, `HYBRID_IMMEDIATE_AND_SLOTS`) | **Flyway `V87`** |
| **3 (P1)** | **Consolidated Kitchen "Cook Sheet" Aggregator (`GAP-GTM-03`)** | `OrderCookSheetService`: read-side aggregation (`GROUP BY product_id, product_name, allergen_mask`) per scheduled date/slot + batch PPDS PDF label export | **Phase 30 / Cohort B (`#428`)** |
| **4 (P1)** | **Catering Deposits & Verified-Contact Payment Links (`GAP-GTM-04`)** | `orders.deposit_amount_pennies` / `balance_due_pennies` (`V87`) + single-use expiring payment link dispatched to verified phone/email (`PAY-04`, `#461`, `#462`) | **Phase 30 (`PAY-04`)** |
| **5 (P1)** | **SaaS Subscription Billing & Usage Metering (`GAP-GTM-05`)** | `BillingPlanMode`: wire `£39/location/month + 0.5% GTV`, flat `£79–£149/mo`, and `£0 + 6%` PAYG Batch Starter preset to Stripe Subscriptions/Invoices | **Phase 30 (`PAY-02`)** |
| **6 (P1)** | **WhatsApp-to-Checkout Pre-Order Link Bridge (`GAP-GTM-06`)** | Activate `COMMS-07` (`#208`) to generate and send pre-filled, allergen-gated storefront checkout links directly into WhatsApp threads | **Phase 30 / `#208`** |
| **7 (P1)** | **Production Domain Cutover & Merchant ROI Telemetry (`GAP-GTM-07`)** | Live AKS/ingress cutover (`jtoye.co.uk`, `Phase 29`) + Merchant ROI card on `/dashboard/finance` showing `Direct GTV`, `Commission Saved vs 28% Aggregator`, and `Blocked Junk Cash Orders` | **Phases 29 & 32 (`GTM-01`..`GTM-02`)** |

---

## 6. Constraints (from `CLAUDE.md` & `AGENTS.md`, strictly enforced by CI gates)

- **Stack (verified by `scripts/check-doc-versions.sh`):** Spring Boot 4.1.1 (Temurin JDK 25 on Gradle 9.7.1), Next.js 16.3.8 / React 19 / Tailwind CSS 4.3.3, Go 1.27 (`Gin v1.12.0`), PostgreSQL 15 (upgrading to 18.6 in `39-05`), Keycloak 26.8.1, RabbitMQ 4.3.6, Azure Blob Storage (`Azurite 3.37.0` locally).
- **Multi-tenancy is non-negotiable:** Every tenant-scoped table carries `ENABLE + FORCE ROW LEVEL SECURITY` pinned to `current_tenant_id()`, verified under `NOSUPERUSER NOBYPASSRLS` by `RlsContractTest`.
- **All new code requires fail-before-pass tests:** Counts in `docs/metrics.json` (`6,158` logical invocations at Schema `V86`) are enforced by `scripts/docs-freshness.sh` and `scripts/check-doc-metrics.sh`.

---

## 7. Related documents

- [`docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`](analysis/BUSINESS_MODEL_DECISION_GUIDE.md) — Reframed commercial positioning, UK African food market analysis, unit economics, and 90-day pilot gates.
- [`docs/architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md`](architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md) — Malleable multi-model policy architecture (`Settlement`, `FulfilmentTiming`, `Surface`, `Billing`).
- [`docs/analysis/GAPS_AND_IMPROVEMENTS.md`](analysis/GAPS_AND_IMPROVEMENTS.md) — Active Go-To-Market gap register (`GAP-GTM-01`..`07`) and resolved historical audit ledger.
- [`docs/architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) & [`docs/architecture/ESSENTIAL_ARCHITECTURE.md`](architecture/ESSENTIAL_ARCHITECTURE.md) — System architecture.
- [`.planning/ROADMAP.md`](../.planning/ROADMAP.md) & [`.planning/REQUIREMENTS.md`](../.planning/REQUIREMENTS.md) — Phase and requirement execution ledger.

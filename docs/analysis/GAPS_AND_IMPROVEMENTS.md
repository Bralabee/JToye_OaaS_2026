# Gaps, Discrepancies & Strategic Implementation Roadmap

> **Originally Generated**: 2026-04-01  
> **Last Reconciled**: 2026-10-17 (Schema **V86**, **6,158+** logical test invocations across Java, Jest, Go, Playwright, and MCP vitest)  
> **Method**: Full top-to-bottom codebase audit (`core-java`, `frontend`, `edge-go`, `mcp-server`, `k8s`) cross-referenced against [`docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`](BUSINESS_MODEL_DECISION_GUIDE.md) and [`ADR-0007`](../architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md).

---

## 1. Active Strategic & Go-To-Market Gaps (October 2026 — Must Not Be Omitted)

While the core multi-tenant platform, UK food-safety compliance floor (`V63`, `V69`, `V71`, `V73`, `V74`), GDPR DSAR automation (`V62`–`V75`), abuse resistance (`V81`), per-shop RBAC (`V52`, `V77`, `V85`), and 301 slug continuity (`V86`) are **shipped and verified**, the following **seven high-leverage commercial & operational bridges** currently stand between the codebase and repeatable UK West African market success:

| Gap ID | Priority | Area | Current Code Reality (`V87`) | Status / Remaining Implementation (`V87+` / Phases 29, 30, 32) |
| :--- | :--- | :--- | :--- | :--- |
| **`GAP-GTM-01`** | **P0 (Blocker)** | **Multi-Model Settlement Strategy (`ADR-0007` Axis 1)** | **SHIPPED (`V87`, #943 + Vendor Shop UI)**: `StripeConnectService.resolveSettlementRouting` & `PaymentService` implement `DIRECT_CHARGE_VENDOR_MOR` (`0.5%` platform fee capped at `£0.50`, vendor as MoR), `DESTINATION_CHARGE_PLATFORM_MOR` (`10%` platform fee), and `SPLIT_DEPOSIT_VENDOR_MOR` (`deposit_bps` at checkout, deferred balance), with vendor-facing `settlementPolicyMode` + `depositBps` controls on `/dashboard/shops` (`frontend/app/dashboard/shops/page.tsx`). | Complete. |
| **`GAP-GTM-02`** | **P0 (Blocker)** | **Advance Pre-Orders, Scheduled Fulfilment Slots & 1-Click Culinary Seeder (`ADR-0007` Axis 2)** | **SHIPPED (`V87`, #943 + Storefront UI #945 + Vendor Slot & Culinary Seed UI)**: `shop_fulfilment_slots` (`ENABLE + FORCE RLS`), `ShopFulfilmentSlotService`, `FulfilmentSlotPicker` on `/shop/[slug]/checkout`, vendor `ShopFulfilmentSlotsDialog` (`frontend/components/dashboard/shops/shop-fulfilment-slots-dialog.tsx`), and 1-click Pan-African & Caribbean Culinary Pack Seeder (`CulinaryPackSeedService`, `GET /api/v1/products/culinary-packs`, `POST /api/v1/products/culinary-packs/seed`, `SeedCulinaryPackDialog`, and 10 regional packs / 52 dishes in `seed/oaas_culinary_catalog.json` + 8 new Pan-African/Caribbean synonyms in `AllergenCatalog`). | Complete. |
| **`GAP-GTM-03`** | **P1 (High)** | **Consolidated Kitchen "Cook Sheet" Aggregator & 62mm Thermal PPDS Export** | **SHIPPED (`V87`, #943 + KDS UI #945 + Thermal PPDS Export)**: `OrderCookSheetService` (`GET /api/v1/orders/cook-sheet`), `KdsCookSheetPanel` (`frontend/components/dashboard/kitchen/kds-cook-sheet-panel.tsx`), `ProductLabelService.LabelFormat.THERMAL_62MM` (`GET /api/v1/products/{id}/label?format=THERMAL_62MM`, `175.75pt × 198.43pt` continuous Brother QL-800/810W layout), and 1-click `"62mm Label"` PDF export directly from each KDS Cook Sheet dish row and `/dashboard/products`. | Complete. |
| **`GAP-GTM-04`** | **P1 (High)** | **Catering Deposits & Verified-Contact Payment Links (`PAY-04`)** | **SHIPPED (`V87`, #944)**: `orders.deposit_amount_pennies` & `orders.balance_due_pennies` (`V87`), `PaymentService.createBalancePaymentIntent` (`payment_stage=BALANCE`), `GET /api/v1/orders/{id}/balance-payment-link`, and `POST /api/v1/public/orders/{orderNumber}/balance-payment-intent` (verified by `(orderNumber, email)` under guest-tracking RLS). | Complete (`#944`). |
| **`GAP-GTM-05`** | **P1 (High)** | **SaaS Subscription & Usage Metering (`PAY-02`)** | `Tenant.plan` (`V48` `STANDARD | PREMIUM | INTERNAL`) exists, but recurring Stripe Billing for `£39/location/month + 0.5% GTV` (or flat `£79–£149/mo` / `£0 + 6%` PAYG preset) is not yet wired. | Implement `BillingPlanMode` (`ADR-0007` Axis 4) and Stripe Subscription + metered usage invoicing in Phase 30 (`PAY-02`). |
| **`GAP-GTM-06`** | **P1 (High)** | **WhatsApp-to-Checkout Pre-Order Link Bridge (`#208`)** | **SHIPPED (`V87` + Vendor Slot UI)**: `ShopFulfilmentSlotService.buildWhatsAppPreOrderLink` and `GET /api/v1/shops/{id}/whatsapp-preorder-link[?slotId=...]` generate pre-formatted WhatsApp broadcast/reply text and `https://wa.me/?text=...` deep-links pointing directly to `/shop/{slug}?slot={slotId}`, surfaced in `ShopFulfilmentSlotsDialog` with 1-click copy and `wa.me` launch. | Complete. |
| **`GAP-GTM-07`** | **P1 (High)** | **Production Domain Cutover (`Phase 29`) & Merchant ROI Telemetry (`Phase 32`)** | **Part B SHIPPED (`#942`)**: `MerchantRoiCard` (`frontend/components/dashboard/merchant-roi-card.tsx` + `frontend/lib/finance-roi.ts`) is live on `/dashboard/finance` with shop-scoped `V79` Direct GTV, 28% aggregator commission saved across `ADR-0007` Axis 4 billing plans, Repeat Buyer %, and `V81` unverified-contact cash-order protection telemetry. **Part A OPEN**: Public domains (`jtoye.co.uk` / `olajay.co.uk`) await live AKS/ingress cutover (`DPLY-01`..`DPLY-05`). | Complete Phase 29 live ingress + TLS + monitoring cutover (`DPLY-01`..`DPLY-05`). |

---

## 2. Historical Audit Ledger (April 2026 Findings — Now Resolved)

Every functional, robustness, and documentation gap identified in the original `2026-04-01` audit has been closed and verified by automated gates:

| Original Item (2026-04-01) | Resolution Status | Evidence in Current Codebase (`V86`) |
| :--- | :--- | :--- |
| **1. RabbitMQ unused** | **CLOSED** (`Phases 22, 24, 27, 38, 39`) | Bound across `onboarding.events`, `payment.events` (`V46`), `media.events` (`V58`), and STOMP KDS relay (`StompDestinations.java`) on RabbitMQ `4.3.6` with Jackson 3 `JacksonJsonMessageConverter` (`BOOT4-06`). |
| **2. WhatsApp webhook incomplete** | **SCAFFOLDED** (`Phase 22`, `COMMS-07`) | `NotificationChannel` seam + off-by-default WhatsApp adapter shipped in `core-java`; pre-order link bridge tracked under `GAP-GTM-06` (`#208`). |
| **3. No pagination UI** | **CLOSED** (`Phases 23, 37`) | Server-side pagination + shop-scoped `?shopId=` filtering wired across Products, Orders, Customers, Promotions, Announcements, and Webhook deliveries (`23-18`, `37-G`). |
| **4. OrderDto excludes items** | **CLOSED** (`Phases 12, 31, 37`) | `OrderDetailDto` carries full `OrderItemDto` lines, V30 `product_name` snapshots, V63 `allergen_mask` snapshots, and V84 guest tracking RLS (`order_items_guest_tracking`). |
| **5. Financial transactions not linked to orders** | **CLOSED** (`Phases 14, 37`) | Linked via `order_id` (`V26`), `entry_kind` (`SALE | REVERSAL | MANUAL`, `V78`), and `shop_id` (`V79`) with automatic VAT-aware ledger recording and void reversal. |
| **6. No token refresh** | **CLOSED** (`Phases 15, 34, 37`) | NextAuth silent rotation, `useSyncExternalStore` customer session store (`34-03`), and KDS silent refresh + full-screen signed-out alarm (`RWO-021`). |
| **7. Customer ID not used in order creation** | **CLOSED** (`Phases 16, 39`) | Orders link to `customers` with per-shop scoping (`V85` `customers.origin_shop_id`, `39-08`). |
| **8. No search/filter in frontend** | **CLOSED** (`Phases 23, 33, 37`) | Full-text + 3-tier postcode proximity search (`V61` `PostcodeGeocoder`), plus status, shop, and date filtering across dashboard views. |
| **9. OpenAPI snapshot & ADRs missing** | **CLOSED** (`Phases 18–39`) | `core-java/openapi-snapshot.json` enforced by CI breaking-change gate; `ADR-0001` through `ADR-0007` committed under `docs/architecture/decisions/`; incident & provisioning runbooks under `docs/runbooks/`. |

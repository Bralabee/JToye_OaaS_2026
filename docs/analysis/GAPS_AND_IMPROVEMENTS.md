# Gaps, Discrepancies & Strategic Implementation Roadmap

> **Originally Generated**: 2026-04-01  
> **Last Reconciled**: 2026-10-17 (Schema **V86**, **6,158+** logical test invocations across Java, Jest, Go, Playwright, and MCP vitest)  
> **Method**: Full top-to-bottom codebase audit (`core-java`, `frontend`, `edge-go`, `mcp-server`, `k8s`) cross-referenced against [`docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md`](BUSINESS_MODEL_DECISION_GUIDE.md) and [`ADR-0007`](../architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md).

---

## 1. Active Strategic & Go-To-Market Gaps (October 2026 — Must Not Be Omitted)

While the core multi-tenant platform, UK food-safety compliance floor (`V63`, `V69`, `V71`, `V73`, `V74`), GDPR DSAR automation (`V62`–`V75`), abuse resistance (`V81`), per-shop RBAC (`V52`, `V77`, `V85`), and 301 slug continuity (`V86`) are **shipped and verified**, the following **seven high-leverage commercial & operational bridges** currently stand between the codebase and repeatable UK West African market success:

| Gap ID | Priority | Area | Current Code Reality (`V86`) | Required Implementation (`V87+` / Phases 29, 30, 32) |
| :--- | :--- | :--- | :--- | :--- |
| **`GAP-GTM-01`** | **P0 (Blocker)** | **Multi-Model Settlement Strategy (`ADR-0007` Axis 1)** | `StripeConnectService.resolveDestinationAccount` (`StripeConnectService.java:170-198`) routes connected-account payments **only** when `VendorOnboarding.model == MARKETPLACE` (destination charges, platform as MoR). `WHITE_LABEL` / Direct SaaS tenants fall back to the single platform account. | Implement `SettlementStrategy` (`DIRECT_CHARGE_VENDOR_MOR`, `DESTINATION_CHARGE_PLATFORM_MOR`, `SPLIT_DEPOSIT_VENDOR_MOR`) in `PaymentService` (`Phase 30`, `PAY-01`..`PAY-03`) so Direct SaaS vendors are the legal & technical Merchant of Record on their connected Stripe account. |
| **`GAP-GTM-02`** | **P0 (Blocker)** | **Advance Pre-Orders & Scheduled Fulfilment Slots (`ADR-0007` Axis 2)** | `ShopAvailabilityPolicy.requireTakingOrders` (`ShopAvailabilityPolicy.java:234-242`) enforces immediate open-now trading (`openNow == true`), returning `409 shop-closed` outside live hours. | Add `V87__scheduled_fulfilment_slots_and_commercial_profile.sql` (`shop_fulfilment_slots` with `ENABLE + FORCE RLS`, `cutoff_at`, `max_orders`, `max_units`, `@Version` optimistic lock) and `FulfilmentTimingMode` (`IMMEDIATE_ONLY`, `SCHEDULED_SLOTS_ONLY`, `HYBRID_IMMEDIATE_AND_SLOTS`) so customers can pre-order weekend 2L/4L soup bowls, coolers, and catering trays 24/7. |
| **`GAP-GTM-03`** | **P1 (High)** | **Consolidated Kitchen "Cook Sheet" Aggregator** | `/dashboard/kitchen` (`OrderService` / STOMP KDS) renders individual order tickets (`New`, `Confirmed`, `Preparing`, `Ready`). | Add `OrderCookSheetService` read projection (`GROUP BY product_id, product_name, allergen_mask` per `scheduled_for_date` and `fulfilment_slot_id`) + `/dashboard/kitchen/cook-sheet` view with 1-click batch PPDS PDF label export (`AllergenLabelPdfService`). |
| **`GAP-GTM-04`** | **P1 (High)** | **Catering Deposits & Verified-Contact Payment Links (`PAY-04`)** | `Order` captures a single `totalAmountPennies` payment intent (`PaymentStatus.PENDING` → `COMPLETED`). No partial deposit state or phone verification column exists yet. | Add `deposit_amount_pennies` & `balance_due_pennies` on `orders` (`V87`), `PARTIALLY_PAID` state handling, and single-use expiring balance payment links dispatched to verified phone/email (`PAY-04`, `#461`, `#462`). |
| **`GAP-GTM-05`** | **P1 (High)** | **SaaS Subscription & Usage Metering (`PAY-02`)** | `Tenant.plan` (`V48` `STANDARD | PREMIUM | INTERNAL`) exists, but recurring Stripe Billing for `£39/location/month + 0.5% GTV` (or flat `£79–£149/mo` / `£0 + 6%` PAYG preset) is not yet wired. | Implement `BillingPlanMode` (`ADR-0007` Axis 4) and Stripe Subscription + metered usage invoicing in Phase 30 (`PAY-02`). |
| **`GAP-GTM-06`** | **P1 (High)** | **WhatsApp-to-Checkout Pre-Order Link Bridge (`#208`)** | `edge-go` verifies WhatsApp HMAC signatures and `core-java` has the `COMMS-07` `NotificationChannel` seam (`Phase 22`), defaulting to OFF. | Build the **"Generate WhatsApp Pre-Order Checkout Link"** action in the vendor dashboard and WhatsApp webhook responder so DM enquiries convert into allergen-acknowledged, pre-paid Stripe checkout sessions without fragile conversational AI parsing. |
| **`GAP-GTM-07`** | **P1 (High)** | **Production Domain Cutover (`Phase 29`) & Merchant ROI Telemetry (`Phase 32`)** | **Part B SHIPPED**: `MerchantRoiCard` (`frontend/components/dashboard/merchant-roi-card.tsx` + `frontend/lib/finance-roi.ts`) is live on `/dashboard/finance` with shop-scoped `V79` Direct GTV, 28% aggregator commission saved across `ADR-0007` Axis 4 billing plans (`£39/mo + 0.5%`, `£99/mo + 0%`, `£0/mo + 6%` with `£710` upgrade tip), Repeat Buyer %, and `V81` unverified-contact cash-order protection telemetry. **Part A OPEN**: Public domains (`jtoye.co.uk` / `olajay.co.uk`) await live AKS/ingress cutover (`DPLY-01`..`DPLY-05`). | Complete Phase 29 live ingress + TLS + monitoring cutover (`DPLY-01`..`DPLY-05`). |

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

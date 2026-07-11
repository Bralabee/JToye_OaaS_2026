---
phase: 19-full-frontend-experience-overhaul
reviewed: 2026-07-11T20:04:55Z
depth: standard
files_reviewed: 61
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
  - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
  - core-java/src/main/java/uk/jtoye/core/order/FulfilmentType.java
  - core-java/src/main/java/uk/jtoye/core/order/Order.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
  - core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderRequest.java
  - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
  - core-java/src/main/resources/db/migration/V45__order_fulfilment.sql
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/order/OrderFulfilmentAuditIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductRepositoryScopingIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductSearchFtsIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
  - docs/SITEMAP.md
  - frontend/app/api/customer-auth/session/route.ts
  - frontend/app/api/customer-auth/__tests__/route.test.ts
  - frontend/app/business-model-guide/page.tsx
  - frontend/app/dashboard/finance/page.tsx
  - frontend/app/dashboard/kitchen/page.tsx
  - frontend/app/dashboard/kitchen/__tests__/page.test.tsx
  - frontend/app/dashboard/orders/page.tsx
  - frontend/app/dashboard/page.tsx
  - frontend/app/dashboard/products/page.tsx
  - frontend/app/for-operators/page.tsx
  - frontend/app/page.tsx
  - frontend/app/shop/layout.tsx
  - frontend/app/shop/orders/page.tsx
  - frontend/app/shop/[slug]/cart/page.tsx
  - frontend/app/shop/[slug]/checkout/page.tsx
  - frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx
  - frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx
  - frontend/app/shop/[slug]/page.tsx
  - frontend/app/__tests__/landing.test.tsx
  - frontend/app/__tests__/track.test.tsx
  - frontend/app/track/page.tsx
  - frontend/components/dashboard/dashboard-shell.tsx
  - frontend/components/dashboard/mobile-tab-bar.tsx
  - frontend/components/dashboard/orders/OrderDetailPanel.tsx
  - frontend/components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx
  - frontend/components/dashboard/sidebar.tsx
  - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
  - frontend/components/marketing/business-model-guide.tsx
  - frontend/components/marketing/operator-pitch.tsx
  - frontend/components/marketing/__tests__/business-model-guide.test.tsx
  - frontend/components/marketing/__tests__/operator-pitch.test.tsx
  - frontend/components/public/public-footer.tsx
  - frontend/components/public/public-header.tsx
  - frontend/components/public/public-shell.tsx
  - frontend/components/storefront/storefront-nav.tsx
  - frontend/components/ui/sheet.tsx
  - frontend/e2e/dashboard-mobile.spec.ts
  - frontend/e2e/kitchen-flow.spec.ts
  - frontend/e2e/storefront-flows.spec.ts
  - frontend/e2e/vendor-refund-flow.spec.ts
  - frontend/lib/customer-auth.ts
  - frontend/__tests__/link-graph.test.ts
  - frontend/__tests__/palette-discipline.test.ts
  - frontend/types/api.ts
findings:
  critical: 1
  warning: 10
  info: 11
  total: 22
status: issues_found
---

# Phase 19: Code Review Report

**Reviewed:** 2026-07-11T20:04:55Z
**Depth:** standard
**Files Reviewed:** 61
**Status:** issues_found

## Summary

Reviewed the Phase 19 full-frontend-experience-overhaul slice: the V45 fulfilment/address schema + Envers mirror, the guest-order fulfilment path in `PublicStorefrontService`, the GDPR address scrub, the strict per-shop product scoping (UIX-05), the dev `DemoDataSeeder`, and the full frontend surface (landing/public shell, checkout fulfilment UX, dashboard mobile shell, tracking, tests, E2E specs).

The core phase mechanics are solid: the delivery fee is genuinely server-authoritative (client value never read), server-side price/title snapshotting is correct, the V45 migration mirrors `orders_aud` per the V38 convention and is proven by a real committed audited write, GDPR erasure now nulls the new address PII on both live and `_aud` rows with Testcontainers proof, tenant scoping in the seeder rides the `jtoye_app` NOSUPERUSER role + `TenantSetLocalAspect` correctly, and the RLS-under-NOSUPERUSER test discipline is consistently applied.

However, the guest order path — a public, unauthenticated money path this phase deliberately touched — has a genuine input-validation hole (CR-01: no product↔shop match), the minimum-order rule the storefront now advertises is enforced nowhere, and several user-facing correctness defects (opening-hours parsing, overnight windows, COD copy, stale-email dead page) survive on the surfaces this phase overhauled. The pre-existing idempotency-retry response bug now directly interacts with the new fulfilment toggle.

## Critical Issues

### CR-01: Guest order accepts products that do not belong to the ordered shop (including unpublished/archived shops)

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:400-406`
**Issue:** `createGuestOrder` resolves each `itemReq.getProductId()` with `productRepository.findById(...)` and checks only `available` and stock. There is **no check that `product.getShopId()` equals `shop.getId()`**. RLS scopes the lookup to the tenant, not the shop, so an unauthenticated client can POST `/public/shops/{slug-of-shop-A}/orders` containing product IDs from any other shop of the same tenant — including shops that are **unpublished**. This directly contradicts the invariant this very phase established ("every product belongs to exactly one shop", UIX-05 — the display query was scoped, the write path was not). It is made concretely exploitable by this phase's own `DemoDataSeeder`: `quarantineNonCurated` moves legacy junk into the hidden unpublished archive shop but leaves `available = true` (DemoDataSeeder.java:315-326), so every quarantined "Label Cake 057999"-style product remains orderable by ID through a curated storefront. Result: kitchens receive orders for items they do not sell; the fee/threshold of the wrong shop is applied; the "no placeholder junk ever renders" guarantee is void on the order/KDS surface. Product IDs are enumerable from any other storefront on the tenant.
**Fix:**
```java
Product product = productRepository.findById(itemReq.getProductId())
        .orElseThrow(() -> new ResourceNotFoundException(
                "Product not found: " + itemReq.getProductId()));

// UIX-05 invariant: an order for shop X may only contain shop X's products.
if (!shop.getId().equals(product.getShopId())) {
    throw new IllegalArgumentException(
            "Product is not sold by this shop: " + product.getTitle());
}
```
Additionally, `DemoDataSeeder.quarantineNonCurated` should set `p.setAvailable(false)` when re-homing to the archive shop (defense-in-depth even after the service fix).

## Warnings

### WR-01: Minimum order value is advertised but enforced nowhere (server or client)

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:440-466` (also `frontend/app/shop/[slug]/cart/page.tsx:122-128`, `frontend/app/shop/[slug]/checkout/page.tsx:274-292`)
**Issue:** This phase's seeder sets `minimumOrderPennies = 1000` on all three demo shops, and the storefront renders "Min order £10.00" (`shop/[slug]/page.tsx:398-402`) plus a below-minimum hint on the floating cart bar. But `createGuestOrder` never compares the item subtotal to `shop.getMinimumOrderPennies()`, the cart's "Proceed to checkout" is never blocked, and checkout submits regardless. The new E2E spec even documents the belief that it is enforced ("a single item is £8.99 < £10 and the order would be blocked at checkout" — `storefront-flows.spec.ts:68-70`), which is false. A £2.50 order sails through the public path against the vendor's declared floor.
**Fix:** Enforce server-side in `createGuestOrder` after computing `itemSubtotal`:
```java
if (shop.getMinimumOrderPennies() != null && itemSubtotal < shop.getMinimumOrderPennies()) {
    throw new IllegalArgumentException("Order is below this shop's minimum of £"
            + shop.getMinimumOrderPennies() / 100.0);
}
```
Mirror it client-side by disabling the checkout submit when `subtotal < shop.minimumOrderPennies`.

### WR-02: Idempotent-duplicate response puts `paymentReference` in the `clientSecret` slot — retry payment breaks

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:341-353`
**Issue:** The idempotency short-circuit builds `GuestOrderConfirmation` with `existingOrder.getPaymentReference()` as the 10th constructor argument, which is the `clientSecret` field. The payment reference is the Stripe PaymentIntent **id** (`pi_...`), not the client secret (`pi_..._secret_...`). The frontend treats any truthy `clientSecret` as "Stripe mode" and mounts `<Elements clientSecret={...}>` with an unusable value, so a legitimate retry of an interrupted Stripe checkout renders a broken payment step instead of resuming payment. It also discloses the raw PaymentIntent id to the guest. Pre-existing code, but it now sits on the exact path this phase reworked and interacts with WR-03.
**Fix:** Do not return the payment reference as a secret. Either re-fetch the client secret from Stripe by PaymentIntent id (`PaymentIntent.retrieve(ref).getClientSecret()`) for still-payable DRAFT orders, or return `null` and let the client show an "order already placed — track it here" state keyed off `status`.

### WR-03: Checkout idempotency key never rotates, so edited resubmissions are silently ignored

**File:** `frontend/app/shop/[slug]/checkout/page.tsx:198,451-456`
**Issue:** `idempotencyKeyRef = useRef(crypto.randomUUID())` is minted once per mount. After a successful order creation the user can press "Back to details" (`setPaymentState(null)`), toggle the brand-new fulfilment control from DELIVERY to COLLECTION (expecting the £0 fee), and resubmit — the server matches the old key and returns the original DELIVERY order; the changed fulfilment/address is silently discarded, and via WR-02 the returned pseudo-clientSecret breaks the payment screen. The idempotency key must identify one *order intent*, not one page mount.
**Fix:** Regenerate the key whenever the payload materially changes or when the user returns to the details step: e.g. `idempotencyKeyRef.current = crypto.randomUUID()` inside the "Back to details" onClick, or derive the key per submission attempt after a prior success.

### WR-04: OIDC callback stores tokens as session cookies BEFORE validating the `nonce`

**File:** `frontend/lib/customer-auth.ts:262-293`
**Issue:** `handleCallback` POSTs the token set to `/api/customer-auth/login` (which sets the HttpOnly session cookies) at line ~270, and only afterwards decodes the id token and checks `payload.nonce !== storedNonce` (line ~293). On a nonce mismatch the function returns `null` — but the session cookies are already set and remain live, so the replay/mix-up defence does not actually prevent session establishment; it only suppresses the UI profile. The check is also skipped entirely when `storedNonce` is absent. (Pre-existing — this phase only touched comments in this file — but it is the security core of the customer-auth surface under review.)
**Fix:** Decode the id token and verify the nonce (and reject a missing stored nonce) *before* calling `/api/customer-auth/login`; if verification fails after cookies were set for any reason, call the logout route to clear them.

### WR-05: `isOpenNow()` produces Invalid Date for most of the month — "Closed" badge shown for open shops

**File:** `frontend/app/shop/[slug]/page.tsx:29-41`
**Issue:** `new Date(new Date().toLocaleString("en-GB", { timeZone: "Europe/London" }))` re-parses a `dd/mm/yyyy, hh:mm:ss` string with the JS Date parser, which expects `mm/dd`. For day-of-month 1–12 the day and month are silently swapped (wrong weekday → wrong hours row); for day 13–31 the result is `Invalid Date`, `getDay()` is `NaN`, `todayHours` is `undefined`, and the function returns `false`. Any shop with configured opening hours renders the "Closed" pill for roughly two-thirds of every month even while genuinely open. Pre-existing, but this is the flagship storefront surface of the phase and the seeded demo shops only mask it because the seeder sets no `openingHours`.
**Fix:** Never round-trip through a locale string. Use `Intl.DateTimeFormat` parts:
```ts
const parts = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/London", weekday: "short", hour: "2-digit", minute: "2-digit", hour12: false,
}).formatToParts(new Date())
// map weekday -> "mon".."sun", compare hour*60+minute against the parsed window
```

### WR-06: Server-side opening-hours gate rejects the entire service window of overnight shops

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:679-682`
**Issue:** `validateShopIsOpen` uses `now.isBefore(open) || !now.isBefore(close)`. For an overnight window such as `"18:00 - 02:00"` (normal for a takeaway platform), `close < open` means the predicate rejects every time of day — at 20:00: `!now.isBefore(close)` is true, so the order is refused as "currently closed" during real trading hours. The client-side `isOpenNow` has the same arithmetic, so the badge agrees with the wrong answer.
**Fix:**
```java
boolean overnight = close.isBefore(open);
boolean openNow = overnight
        ? !now.isBefore(open) || now.isBefore(close)
        : !now.isBefore(open) && now.isBefore(close);
if (!openNow) { throw new IllegalArgumentException(...); }
```

### WR-07: Order-confirmation page never refetches once the session email hydrates — dead page for signed-in customers

**File:** `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx:82-136`
**Issue:** `fetchStatus` is invoked only from a mount-only effect (`useEffect(..., [])`). The initial `email` comes synchronously from `localStorage`; the customer-session email arrives asynchronously via `setEmail`. For a signed-in customer on a device without the `jtoye-checkout-email-*` key, the mount fetch runs with an empty email (sets `loading=false`, no data), then the email state updates but nothing refetches — and because `email` is now non-empty the `EmailPrompt` branch is skipped too. The user is left on a skeleton page showing only the order number with no status, no error, and no recovery affordance short of a manual reload.
**Fix:** Re-run the fetch when the email becomes available:
```ts
useEffect(() => { if (email) fetchStatus() }, [email]) // replaces the mount-only call
```

### WR-08: COD confirmation always says "Pay on collection" — wrong for DELIVERY orders

**File:** `frontend/app/shop/[slug]/checkout/page.tsx:382-384`
**Issue:** The COD confirmation header renders `Order {n} · Pay on collection` unconditionally. This phase introduced the DELIVERY/COLLECTION split, and the backend labels the COD fallback "Cash on Delivery" (`PublicStorefrontService.java:482`), yet a customer who chose **Delivery** with a full UK address is told to pay *on collection*. The E2E spec bakes the wrong copy in (`storefront-flows.spec.ts:101-105` places a DELIVERY order and the comment quotes "Pay on collection"). Incorrect payment instructions on the confirmation screen of a money flow.
**Fix:** Track `fulfilmentType` into the confirmation state and render `Pay on {fulfilmentType === "COLLECTION" ? "collection" : "delivery"}`.

### WR-09: Customer email embedded in tracking URLs (PII in query strings)

**File:** `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx:275` (also `frontend/app/shop/orders/page.tsx:101-104`, `frontend/app/track/page.tsx:79-84`)
**Issue:** The new "Track this order" affordance links to `/track?order=...&email=<address>`, and the My Orders cards build the same URLs. The email lands in browser history, any front-proxy/access logs, and analytics that capture `location.search` — a durable PII trail for a platform that just shipped Article-17 erasure completeness (email is exactly what the GDPR machinery scrubs elsewhere). The `/track` page auto-submits from those params, so the leak is by design of the link, not incidental.
**Fix:** Pass the email out-of-band: keep only `?order=` in the URL and pre-fill the email from `getCustomerSession()`/`sessionStorage` (the track page already does the session pre-fill); or at minimum use `sessionStorage` handoff for the confirmation-page link.

### WR-10: DemoDataSeeder writes `Shop.published` directly, violating the Phase-18 sole-writer invariant

**File:** `core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java:259,277,329-339`
**Issue:** Project rules (CLAUDE.md, Phase 18) state "the onboarding state machine is the sole writer of Shop.published, gated by automatic BUSINESS_VERIFIED/FOOD_HYGIENE_RATING/ALLERGEN_DATA_COMPLETE checks". The seeder both force-publishes the three curated shops (`setPublished(true)`) with no gates and force-unpublishes every other shop in the tenant on **every dev startup** (`unpublishNonCurated`). Consequences in dev: any shop a developer takes LIVE through the real onboarding flow is silently unpublished on the next restart (making onboarding E2E work appear flaky/broken), and the curated shops present a published state that no gate ever validated. Dev-only (`@Profile("dev")` is correctly the sole wiring), so not a production risk, but it institutionalises a bypass of the invariant and will fight Phase-18 testing.
**Fix:** Scope `unpublishNonCurated` to skip shops that have an onboarding record in state LIVE (or restrict the unpublish sweep to shops whose slug matches known legacy/E2E patterns), and add a code comment acknowledging the deliberate, dev-only exception to the sole-writer rule — or drive the curated shops' publication through the onboarding service with waived gates.

## Info

### IN-01: Dead variable `itemNames` in the kitchen page

**File:** `frontend/app/dashboard/kitchen/page.tsx:415-418`
**Issue:** `itemNames` is computed per card and never used (the JSX re-derives the joined list inline).
**Fix:** Delete the variable.

### IN-02: Palette gate misses `violet-*` — the "undocumented 7th hue" survives in dashboard/products

**File:** `frontend/__tests__/palette-discipline.test.ts:42-46` (offender: `frontend/app/dashboard/products/page.tsx:66-79`)
**Issue:** The #10 gate greps only the literal `purple-`, but `AiSuggestionRow` still ships `text-violet-500`, `bg-violet-600`, `border-violet-100` — the same visual hue family the sweep claims to have removed. The gate passes while the violation persists.
**Fix:** Extend the grep to `(purple|violet|fuchsia|indigo)-` and migrate the AI-suggestion row to the documented palette (blue or amber).

### IN-03: `parseFulfilmentType` uses locale-sensitive `toUpperCase()`

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java:591`
**Issue:** `raw.trim().toUpperCase()` uses the default JVM locale; under `tr-TR` "delivery" upper-cases to `DELİVERY` and valid input is rejected with a 400.
**Fix:** `raw.trim().toUpperCase(Locale.ROOT)`.

### IN-04: No server-side postcode format validation on the guest order path

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderRequest.java:55-56`, `PublicStorefrontService.java:376-388`
**Issue:** The UK postcode regex exists only client-side (`checkout/page.tsx:26`); the server enforces presence and a 12-char cap only, so a direct API caller can persist arbitrary strings as the delivery postcode.
**Fix:** Add `@Pattern(regexp = "(?i)^[A-Z]{1,2}\\d[A-Z\\d]?\\s?\\d[A-Z]{2}$", message = "Valid UK postcode required")` on `addressPostcode` (validated only when DELIVERY, so enforce in the service branch or with a cross-field validator).

### IN-05: Session probe treats an id token without `exp` as valid forever

**File:** `frontend/app/api/customer-auth/session/route.ts:59-62`
**Issue:** `if (claims.exp && claims.exp < nowSec)` — a token missing `exp` (or with a non-numeric one) is reported `authenticated: true` indefinitely. The payload is also decoded without signature verification; that is documented as acceptable for a UI-only probe, but the missing-exp case silently widens it.
**Fix:** `if (!claims.exp || claims.exp < nowSec) return NextResponse.json({ authenticated: false })`.

### IN-06: Track page renders raw enum for unknown statuses and polls terminal REFUNDED orders forever

**File:** `frontend/app/track/page.tsx:108-120,203-206`
**Issue:** `STEPS` has no DRAFT/REFUNDED entry, so a DRAFT (awaiting Stripe) or REFUNDED order shows the raw enum string as its badge, and the 15s auto-refresh loop only stops on COMPLETED/CANCELLED — a REFUNDED order polls indefinitely.
**Fix:** Add REFUNDED to the terminal set and map DRAFT/REFUNDED to friendly labels ("Awaiting payment" / "Refunded").

### IN-07: docs/SITEMAP.md contradicts the shipped state

**File:** `docs/SITEMAP.md:32-56`
**Issue:** The dashboard table omits `/dashboard/onboarding` even though the sidebar now ships a "Go live" item (sidebar.tsx:33) and the mobile E2E exercises the route; the "Vendor onboarding" section still claims the UI is an unshipped follow-on slice ("Update this section when it lands" — it landed in PR #175). The file was edited this phase (the `/` row) without refreshing the stale section, despite its own "keep both in sync" instruction.
**Fix:** Add the `/dashboard/onboarding | Go live` row and rewrite the onboarding paragraph to reflect the shipped Phase-18 UI.

### IN-08: dashboard-mobile E2E stub drifts from the wire contract

**File:** `frontend/e2e/dashboard-mobile.spec.ts:156-186`
**Issue:** The order-detail stub uses `city`/`postcode` (real fields are `addressCity`/`addressPostcode`) and `paymentStatus: "PAID"` (not a member of `PaymentStatus`). Masked only because the stub is a COLLECTION order; if a future assertion flips it to DELIVERY the address block will silently render empty and the drift will look like an app bug.
**Fix:** Align the stub with `OrderDetail` in `types/api.ts` (`addressCity`, `addressPostcode`, `paymentStatus: "CAPTURED"`).

### IN-09: Committed default E2E password contradicts its own "never committed" comments

**File:** `frontend/e2e/kitchen-flow.spec.ts:33-34` (same pattern in `dashboard-mobile.spec.ts:36-37`, `vendor-refund-flow.spec.ts:35-36`)
**Issue:** Comments state the vendor password "is never committed", yet the fallback `?? "password123"` is committed on the same line. It is a dev-realm placeholder, but the comment is false and the literal invites secret-scanner noise (this repo already curates gitleaks fingerprints).
**Fix:** Drop the fallback (`process.env.E2E_VENDOR_PASSWORD!` + a skip when unset) or correct the comment and add a `# gitleaks:allow`-style annotation per repo convention.

### IN-10: Stale/inaccurate comments in reviewed backend/test code

**File:** `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java:526-528`; `core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java:204-206`
**Issue:** (a) The test file still says the SEC-01 helper "does not exist yet — this is the intentional RED state", two phases after it went green. (b) `GdprService` claims `@Modifying(flushAutomatically)` guarantees "the post-erasure audit rows are already redacted" before the scrub — Envers actually writes `_aud` rows at `beforeTransactionCompletion` (after the scrub UPDATE), so the mechanism description is wrong even though the outcome (rows written with already-redacted values) is right and pinned by the integration test.
**Fix:** Delete the RED-state paragraph; reword the GdprService comment to say the post-erasure Envers revision is written at commit from already-redacted entity state.

### IN-11: Checkout VAT preview hardcodes 20% regardless of the basket's predominant rate

**File:** `frontend/app/shop/[slug]/checkout/page.tsx:560-563,765-772`
**Issue:** The preview extracts `gross*20/120` and labels it "VAT (incl. 20%)" even when the server will resolve the order's predominant rate to ZERO/REDUCED (cold food baskets). Totals are unaffected (VAT-inclusive pricing) and the confirmation screens use server values, but the pre-payment figure can disagree with the confirmed one.
**Fix:** Either label it "est." or omit the VAT preview row until the server-confirmed breakdown is available.

### IN-12: OrderDetailPanel renders an empty "Delivery address" block for address-less DELIVERY orders

**File:** `frontend/components/dashboard/orders/OrderDetailPanel.tsx:226-246`
**Issue:** Every pre-V45 order defaults to `fulfilmentType=DELIVERY` with all address columns NULL (V45 backfills via column default), so the vendor detail page shows a "Delivery address" heading with nothing under it.
**Fix:** Render a fallback line, e.g. `{!order.addressLine1 && !order.addressPostcode && <span className="block text-slate-400">No address on file (pre-fulfilment order)</span>}`.

---

_Reviewed: 2026-07-11T20:04:55Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

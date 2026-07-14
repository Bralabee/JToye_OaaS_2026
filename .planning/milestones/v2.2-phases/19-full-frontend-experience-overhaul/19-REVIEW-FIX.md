---
phase: 19-full-frontend-experience-overhaul
fixed_at: 2026-07-11T21:16:03Z
review_path: .planning/phases/19-full-frontend-experience-overhaul/19-REVIEW.md
iteration: 1
findings_in_scope: 11
fixed: 11
skipped: 0
status: all_fixed
---

# Phase 19: Code Review Fix Report

**Fixed at:** 2026-07-11T21:16:03Z
**Source review:** .planning/phases/19-full-frontend-experience-overhaul/19-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 11 (1 Critical + 10 Warnings; fix_scope = critical_warning, 11 Info findings out of scope)
- Fixed: 11
- Skipped: 0

## Fixed Issues

### CR-01: Guest order accepts products that do not belong to the ordered shop

**Files modified:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java`, `core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java`, `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java`, `docs/metrics.json`
**Commit:** 3db65f5
**Applied fix:** `createGuestOrder` now rejects any item whose `product.getShopId()` differs from the storefront shop. Deliberately deviates from the review's suggested `IllegalArgumentException` + product title: per the money-path constraint, the rejection is a `ResourceNotFoundException` with the identical "Product not found: {id}" message as the absent-row case, so the 404 does not disclose that the product exists in another shop (no title, no shop detail — no existence oracle). `DemoDataSeeder.quarantineNonCurated` additionally sets `available=false` on archived products (defence-in-depth). New unit test proves a same-tenant/other-shop product is rejected, leaks no title, and mints no order row; the shared `availableProduct` helper now homes products in the ordered shop.

### WR-01: Minimum order value advertised but enforced nowhere

**Files modified:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java`, `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java`, `frontend/app/shop/[slug]/checkout/page.tsx`, `frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx`, `docs/metrics.json`
**Commit:** 9ece4df
**Applied fix:** Server-authoritative gate in `createGuestOrder`: item subtotal (delivery fee excluded) below `shop.minimumOrderPennies` throws a 400 with a Locale.ROOT-formatted "£X.XX" message. Checkout mirrors it: submit disabled below minimum with an "add £Y more" hint. Unit test (below-minimum rejected, no save) + jest test (disabled submit, no POST).

### WR-02: Idempotent-duplicate response puts paymentReference in the clientSecret slot

**Files modified:** `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java`, `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java`, `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java`, `docs/metrics.json`
**Commit:** c21bc6d
**Applied fix:** New `PaymentService.retrieveClientSecret(paymentIntentId)` (`@CircuitBreaker("stripe")`). The idempotency short-circuit now re-fetches the REAL client secret for a still-payable DRAFT duplicate (Stripe failure degrades to null with a WARN) and returns null for any non-DRAFT duplicate — the raw `pi_...` id never reaches the client. Two unit tests pin both branches.

### WR-03: Checkout idempotency key never rotates

**Files modified:** `frontend/app/shop/[slug]/checkout/page.tsx`
**Commit:** 49a577a
**Applied fix:** "Back to details" now regenerates `idempotencyKeyRef.current` (one key = one order intent), so an edited resubmission (e.g. DELIVERY→COLLECTION) creates a fresh order instead of being matched to the old key.

### WR-04: OIDC callback stores tokens as session cookies BEFORE validating the nonce

**Files modified:** `frontend/lib/customer-auth.ts`
**Commit:** 7852510
**Applied fix:** `handleCallback` reordered: decode id token → verify nonce (a MISSING stored nonce is now also a hard reject) → only then POST tokens to `/api/customer-auth/login`. No cookie is ever set for a replayed/mixed-up token; transients cleared on rejection. (Fixed although pre-existing, per explicit scope instruction.)

### WR-05: isOpenNow() produces Invalid Date for most of the month

**Files modified:** `frontend/app/shop/[slug]/page.tsx`
**Commit:** f58a487
**Applied fix:** Replaced the `new Date(toLocaleString(...))` round-trip with `Intl.DateTimeFormat("en-GB", { timeZone: "Europe/London", ... }).formatToParts` for weekday + hour/minute (hour "24" normalised). Note: logic verified by build/type gates and review only — no jest unit (function is module-private); recommend a quick manual/E2E glance at the Open/Closed badge.

### WR-06: Server-side opening-hours gate rejects overnight windows entirely

**Files modified:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java`, `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java`, `frontend/app/shop/[slug]/page.tsx`, `docs/metrics.json`
**Commit:** 43a6c76
**Applied fix:** `validateShopIsOpen` now handles `close < open` as a midnight-wrapping window (reviewer's exact predicate); client `isOpenNow` mirrors it. Two deterministic unit tests (window built relative to now in Europe/London) pin inside-accepted / outside-rejected.

### WR-07: Order-confirmation page never refetches once the session email hydrates

**Files modified:** `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx`
**Commit:** f732fd5
**Applied fix:** The fetch effect is keyed on `[email]` instead of mount-only, so the async session email triggers the fetch; `fetchStatus` still no-ops (clearing loading) while email is empty so the EmailPrompt path is preserved. Note: verified by build gates and reasoning only (no jest for this page); recommend E2E confirmation on the signed-in/no-localStorage path.

### WR-08: COD confirmation always says "Pay on collection"

**Files modified:** `frontend/app/shop/[slug]/checkout/page.tsx`, `frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx`, `frontend/e2e/storefront-flows.spec.ts`, `docs/metrics.json`
**Commit:** 5a9fd6c
**Applied fix:** `codConfirmation` state carries the submitted `fulfilmentType`; header renders "Pay on delivery"/"Pay on collection" accordingly. The two E2E spec comments that baked in the wrong copy were corrected (those flows place DELIVERY orders). Jest test pins the DELIVERY wording.

### WR-09: Customer email embedded in tracking URLs (PII in query strings)

**Files modified:** `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx`, `frontend/app/shop/orders/page.tsx`, `frontend/app/track/page.tsx`
**Commit:** 148a544
**Applied fix:** All in-app links now carry only `?order=`; the email is handed over out-of-band via a `sessionStorage` handoff (`jtoye-track-email`) set on click, and `/track` pre-fills from that handoff or the customer session. Auto-search fires when the order param plus any out-of-band email is present. Legacy `?email=` links are still honoured (read-only) so old bookmarks work; the app mints no new ones.

### WR-10: DemoDataSeeder writes Shop.published directly, violating the Phase-18 sole-writer invariant

**Files modified:** `core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java`
**Commit:** e174f42
**Applied fix:** `unpublishNonCurated` now skips the shop the onboarding state machine currently holds LIVE (single `VendorOnboardingRepository.findByTenantId` lookup — onboarding is one-per-tenant), so a developer's real go-live is never silently undone on dev restart. Routing the three curated shops' publication through the state machine is architecturally impossible (`UNIQUE(tenant_id)`: one onboarding aggregate per tenant vs three fixture shops), and dropping the publish handling would regress the phase's own UIX-05 "curated directory" deliverable — so the curated-shop force-publish remains, now explicitly documented in code as the deliberate, `@Profile("dev")`-gated bootstrap exception to the sole-writer rule.

## Verification

- `./gradlew :core-java:test` (full unit suite): **BUILD SUCCESSFUL** (includes the 6 new `@Test` methods).
- `./gradlew :core-java:integrationTest --tests '*ProductRepositoryScoping*' --tests '*Rls*' --tests '*OrderFulfilmentAudit*' --tests '*GdprErasure*'` (Testcontainers, real Postgres + RLS): **BUILD SUCCESSFUL** — 10 classes / 24 tests, 0 failures (ProductRepositoryScopingIntegrationTest, GdprErasureIntegrationTest, OrderFulfilmentAuditIntegrationTest, RlsContractTest, VendorOnboardingRlsIntegrationTest, ReviewsRlsPolicyIntegrationTest, ShopPromotionsRlsPolicyIntegrationTest, MultiTenantIsolationIntegrationTest, FinancialSummaryCrossTenantIsolationTest, ProductSearchFtsIntegrationTest).
- `cd frontend && npm run build`: **compiled successfully** (tsc gate green).
- `cd frontend && CI=true npm test`: **28/28 suites, 192 tests passed** (includes the 3 new jest tests).
- `bash scripts/docs-freshness.sh`: **OK** — metrics match source (total logical invocations 1001 → 1009: +6 Java `@Test`, +2 jest blocks). The manifest was regenerated with `--write` inside each test-adding commit and re-verified in check mode at the end.
- Not verified live: dev-stack E2E (Playwright) — the comment-only E2E edits do not change assertions; a full docker rebuild + E2E run is the verifier phase's job per project rules.

---

_Fixed: 2026-07-11T21:16:03Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_

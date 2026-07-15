---
phase: 19-full-frontend-experience-overhaul
plan: 01
subsystem: api
tags: [postgres, flyway, envers, jpa, mapstruct, gdpr, testcontainers, orders]

# Dependency graph
requires:
  - phase: 05-orders (V5__orders)
    provides: Order/OrderItem @Audited entities + orders_aud/order_items_aud Envers mirrors
  - phase: 16-1-preprod (VAT ledger V40)
    provides: the VARCHAR+CHECK enum + orders_aud mirror migration shape copied by V45
  - phase: gdpr-erasure (V42, Issue #84)
    provides: orders_aud tenant-scoped UPDATE policy + scrubOrdersAudit that V45 address scrub extends
provides:
  - "V45 schema: orders + orders_aud fulfilment_type (DELIVERY|COLLECTION) + 4 UK address columns"
  - "UIX-03 fix: guest orders snapshot the real product title (no more 'Unknown Product') + backfill of historical rows"
  - "FulfilmentType enum + audited Order.fulfilmentType/address fields"
  - "OrderDetailDto/OrderMapper expose fulfilment + address to /dashboard/orders/[id]"
  - "Server-authoritative delivery fee: COLLECTION forces £0; client fee never trusted"
  - "GDPR erasure scrubs the delivery address (PII) from both orders and orders_aud"
affects: [19-checkout-ui, storefront-checkout, dashboard-order-detail, kitchen-display]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Envers _aud mirror for every new column on an @Audited entity (nullable, no DEFAULT, no CHECK) proven by a real audited-write Testcontainers test"
    - "Server-side parse+validate of a client enum-string (unknown value = 400, not silent default)"
    - "Conditional-required Bean Validation enforced in the service layer (DELIVERY requires address)"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V45__order_fulfilment.sql
    - core-java/src/main/java/uk/jtoye/core/order/FulfilmentType.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderFulfilmentAuditIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/Order.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
    - core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderRequest.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java

key-decisions:
  - "V44 left reserved for #96; new schema lands as V45 (single new migration this phase)"
  - "fulfilmentType parsed server-side: blank → DELIVERY (safe fee-bearing default), unknown → 400"
  - "COLLECTION forces deliveryFeePennies=0 in the same server-authoritative block that recomputes the DELIVERY fee — client never supplies a fee"
  - "Address treated as PII: GDPR erasure nulls it on both the live orders row and the orders_aud history"

patterns-established:
  - "Audited-write Testcontainers proof: a committed real order INSERT after a schema change is the only thing that catches Envers _aud column drift (RlsContractTest/@DataJpaTest do not)"

requirements-completed: [UIX-03, UIX-04]

# Metrics
duration: ~16min
completed: 2026-07-11
---

# Phase 19 Plan 01: Order Fulfilment + Address Backend Summary

**V45 adds DELIVERY/COLLECTION fulfilment + UK delivery-address columns (mirrored into orders_aud), fixes the guest-order 'Unknown Product' snapshot bug, exposes fulfilment/address to the order-detail DTO, keeps the delivery fee server-authoritative, and scrubs the address on GDPR erasure — all proven by a real audited-write Testcontainers test.**

## Performance

- **Duration:** ~16 min
- **Started:** 2026-07-11T11:38:00+01:00 (approx)
- **Completed:** 2026-07-11T11:53:06+01:00
- **Tasks:** 3
- **Files modified:** 12 (3 created, 9 modified)

## Accomplishments
- **UIX-03 root-cause fix** — `PublicStorefrontService.createGuestOrder` now calls `item.setProductName(product.getTitle())`, so guest `OrderItem` rows never persist the `"Unknown Product"` default onto the kitchen display / order detail. V45 backfills the rows already written wrong.
- **UIX-04 schema** — V45 adds `fulfilment_type` (VARCHAR+CHECK `DELIVERY|COLLECTION`) plus `address_line1/2/city/postcode` to `orders`, mirrored nullable/no-CHECK into `orders_aud` (the V38 drift landmine). `Order` carries the new `@Audited` fields; `FulfilmentType` enum added.
- **End-to-end wiring** — `GuestOrderRequest` accepts `fulfilmentType` + address (Bean-Validation capped to the column widths); the service parses + validates them server-side (unknown fulfilment = 400, DELIVERY requires line1/city/postcode); `OrderMapper`/`OrderDetailDto` expose them to `/dashboard/orders/[id]`.
- **Server-authoritative fee** — the delivery fee is recomputed server-side; COLLECTION forces £0; the request has no fee field so a client value can never be trusted (T-19-01-01).
- **GDPR address scrub** — `OrderRepository.scrubOrdersAudit` and `GdprService` null the 4 address columns on both `orders` and `orders_aud` (address = PII, Issue #84).
- **No-drift proof** — a new Testcontainers test performs a real audited order write after V45 and reads the fulfilment/address back out of `orders_aud`.

## Task Commits

Each task was committed atomically:

1. **Task 1: V45 migration + FulfilmentType enum + audited Order fields** - `7c64568` (feat)
2. **Task 2: GuestOrderRequest + service fix + mapper/DTO exposure + GDPR address scrub** - `417e8cd` (feat)
3. **Task 3: Testcontainers audited-write proof + productName/fulfilment + address-erasure tests** - `691815f` (test)

## Files Created/Modified
- `core-java/.../db/migration/V45__order_fulfilment.sql` - orders + orders_aud fulfilment_type + address columns; product_name backfill
- `core-java/.../order/FulfilmentType.java` - DELIVERY | COLLECTION enum
- `core-java/.../order/Order.java` - @Enumerated fulfilmentType + 4 audited address fields
- `core-java/.../order/OrderMapper.java` - maps fulfilment/address onto OrderDetailDto
- `core-java/.../order/dto/OrderDetailDto.java` - nullable fulfilment/address getters/setters
- `core-java/.../order/OrderRepository.java` - scrubOrdersAudit extended to null address columns
- `core-java/.../storefront/dto/GuestOrderRequest.java` - @NotBlank fulfilmentType + @Size-capped address fields
- `core-java/.../storefront/PublicStorefrontService.java` - setProductName fix; fulfilment parse/validate; COLLECTION £0 fee
- `core-java/.../gdpr/GdprService.java` - nulls the live-order address on Article-17 erasure
- `core-java/.../order/OrderFulfilmentAuditIntegrationTest.java` (NEW) - real audited-write no-drift proof
- `core-java/.../storefront/PublicStorefrontServiceTest.java` - name-snapshot + fulfilment/fee/conditional-address unit tests
- `core-java/.../gdpr/GdprErasureIntegrationTest.java` - address-NULL-post-erasure assertion

## Decisions Made
- **fulfilmentType defaulting:** a blank/absent value maps to DELIVERY (the safe, fee-bearing choice); an unknown string is a 400 rather than a silent coercion — matches the "no shallow flow" objective.
- **Fee logic placement:** COLLECTION's £0 override lives in the same server-side block that recomputes the DELIVERY fee, so tampering with `fulfilmentType` to underpay is structurally neutralised (T-19-01-01).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Live-order address scrub added to GdprService**
- **Found during:** Task 2
- **Issue:** The plan's Task 2 `<action>` and the must-have truth ("GDPR erasure scrubs the delivery address from both orders and orders_aud") require nulling the address on the LIVE `orders` row, but `GdprService.java` was not listed in the plan frontmatter `files_modified` (only `scrubOrdersAudit` on the repository was listed). Scrubbing only `orders_aud` would leave the live-row address intact.
- **Fix:** Added `order.setAddressLine1(null)` … `setAddressPostcode(null)` to the existing live-order anonymisation loop in `GdprService.eraseCustomerData`.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
- **Verification:** `GdprErasureIntegrationTest.erasureScrubsDeliveryAddress` asserts address is NULL on both `orders` and `orders_aud` post-erasure — green.
- **Committed in:** `417e8cd` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 missing critical)
**Impact on plan:** The single deviation was mandated by the plan's own Task 2 action text + must-have truth; the frontmatter file list was simply incomplete. No scope creep.

## Issues Encountered
- **Gradle wrapper location:** the plan's `<verify>` blocks use `cd core-java && ./gradlew …`, but the wrapper lives at the repo root and `core-java` is a Gradle subproject. Ran all builds via the root wrapper with the `:core-java:` prefix (`./gradlew :core-java:compileJava`, `:core-java:test`, `:core-java:integrationTest`). No code impact.
- **Testcontainers SKU collision (fixed before commit):** `OrderFulfilmentAuditIntegrationTest` is intentionally NOT `@Transactional` (Envers must commit for the audit row to be written), so its per-method `setUp()` product seed collided on `idx_products_tenant_sku` on the second test method. Fixed by giving each seeded product a unique SKU (`SKU-FULFIL-<uuid8>`). Both fulfilment tests then passed.
- **Checked-exception stubbing:** `paymentService.createPaymentIntent` declares `throws StripeException`; the three new unit tests that stub it now declare `throws Exception`. Resolved before commit.

## Test Results
- `./gradlew :core-java:test --tests '*PublicStorefrontServiceTest'` — PASS
- `./gradlew :core-java:integrationTest --tests '*OrderFulfilmentAuditIntegrationTest' --tests '*GdprErasureIntegrationTest'` — PASS (4 tests)
- `./gradlew :core-java:integrationTest --tests '*RlsContractTest'` — PASS (RLS table-level unchanged; V45 is additive columns only)

## Known Stubs
None — all fields are wired end-to-end (schema → entity → service → mapper → DTO) with real data sources.

## Threat Flags
None — no security surface beyond the plan's `<threat_model>` was introduced. All four registered mitigations (T-19-01-01 server fee, T-19-01-02 @Size caps + conditional-required, T-19-01-03 address _aud scrub, T-19-01-04 Envers mirror + audited-write test) are implemented and tested.

## Next Phase Readiness
- Backend contract is ready for the storefront checkout UI (send `fulfilmentType` + address on the guest order payload) and the dashboard order-detail view (render `fulfilmentType` + address from `OrderDetailDto`).
- `docs/metrics.json` (`schema_version` 43→45 and test counts) was deliberately NOT touched here — plan 19-09 reconciles all counts + schema_version at phase closure.
- The metrics counts have grown (new integration test class + new unit/integration test methods); the `docs-freshness` CI gate will need the 19-09 reconciliation before a clean full-suite CI run.

## Self-Check: PASSED

- Created files verified present: V45__order_fulfilment.sql, FulfilmentType.java, OrderFulfilmentAuditIntegrationTest.java, 19-01-SUMMARY.md
- Task commits verified in git log: 7c64568, 417e8cd, 691815f

---
*Phase: 19-full-frontend-experience-overhaul*
*Completed: 2026-07-11*

---
phase: quick-260708-mow
plan: 01
subsystem: finance / ledger
tags: [vat, ledger, flyway, rls, envers, idempotency, issue-81]
requires: [products, orders, financial_transactions, RLS, Envers]
provides: [VatCalculator, products.vat_rate, financial_transactions.order_id, uq_fin_tx_tenant_order, fraction-method VAT, predominant-liability resolver, idempotent ledger]
affects: [OrderService, PaymentService, PublicStorefrontService, FinancialTransaction(Service/Repository), checkout UI]
tech-stack:
  patterns: [HMRC VAT fraction method (round-down), partial unique index idempotency backstop, single-source-of-truth helper, Java/Postgres integer-division parity]
key-files:
  created:
    - core-java/src/main/resources/db/migration/V40__vat_ledger_correctness.sql
    - core-java/src/main/java/uk/jtoye/core/finance/VatCalculator.java
    - core-java/src/test/java/uk/jtoye/core/finance/VatCalculatorTest.java
    - core-java/src/test/java/uk/jtoye/core/finance/LedgerSingleEntryIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransaction.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionRepository.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionService.java
    - core-java/src/main/java/uk/jtoye/core/finance/dto/CreateTransactionRequest.java
    - core-java/src/main/java/uk/jtoye/core/order/Order.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/product/Product.java
    - core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java
    - core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java
    - core-java/src/test/java/uk/jtoye/core/finance/FinancialTransactionServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/finance/FinancialSummaryGoldenFileTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java
    - core-java/src/test/resources/fixtures/financial-summary-1k.golden.json
    - frontend/app/shop/[slug]/checkout/page.tsx
    - docs/metrics.json
    - CLAUDE.md
metrics:
  duration: ~1h
  completed: 2026-07-08
---

# Quick 260708-mow: Issue #81 [P0-5] VAT Ledger Correctness Summary

Fixed three independent VAT ledger defects (VAT-on-VAT, hardcoded STANDARD, duplicate ledger) with a single-source-of-truth `VatCalculator` (HMRC fraction method, round-down), a V40 migration adding `products.vat_rate` + `financial_transactions.order_id` + a partial unique index, per-product predominant-rate resolution, and an idempotent single ledger entry per settled order — proven to the penny by exact unit tests, a Testcontainers regression, and a live COD order against the running stack.

## Commits (4 atomic, conventional, no Co-Authored-By)

| Task | Commit | Message |
|------|--------|---------|
| 1 | `31bac9c` | feat(quick-260708-mow): V40 schema + VatCalculator + product vat_rate |
| 2 | `d4dd356` | fix(quick-260708-mow): fraction-method VAT + idempotent single ledger entry |
| 3 | `e242cd8` | fix(quick-260708-mow): per-product VAT rate + predominant delivery liability |
| 4 | `0a0217c` | test(quick-260708-mow): exact-penny tests, ledger regression, golden regen, metrics |

Branch: `fix/81-vat-ledger-correctness` (never committed to main).

## What was done

### BUG 1 — VAT-on-VAT / VAT-on-top → fraction method
- `VatCalculator.vatFromGross(gross, rate)` is the single source of truth: `gross*20/120` (STANDARD), `gross*5/105` (REDUCED), `0` (ZERO/EXEMPT).
- `FinancialTransaction.calculateVatAmount()` delegates to it; `getAmountIncludingVat()` now returns the gross; added `getNetAmountPennies()`.
- Both JPQL summary aggregates switched from `/100` to `/120` and `/105`.
- `Order.calculateTotal()`: `total = subtotal + deliveryFee` (no VAT added on top); `vatAmount` is the extracted fraction on subtotal + delivery.

### BUG 2 — hardcoded STANDARD → per-product predominant rate
- `Product` gains `vat_rate` (default STANDARD); threaded through `ProductDto` + `CreateProductRequest` (default STANDARD so existing API clients keep working, no silent zero-rating).
- `VatCalculator.predominantRate(List<LineRate>)`: net-weighted predominant rate, STANDARD tie-break, all-ZERO stays ZERO, empty/null → STANDARD.
- `PublicStorefrontService.createGuestOrder` and `OrderService.createOrder` resolve each line's rate from its Product and set `order.vatRate` to the predominant rate; delivery VAT follows via `calculateTotal()`. Client cannot supply a rate (server-resolved only — mitigates T-81-01).

### BUG 3 — duplicate ledger → exactly one entry per settled order
- `CreateTransactionRequest` gains `orderId` (+ a 3-arg convenience ctor keeping all existing callers/tests compiling).
- `FinancialTransactionService.createTransaction` is idempotent on `orderId`: `findByOrderId` fast-path returns the existing DTO; a `flush()` forces the INSERT so a concurrent `uq_fin_tx_tenant_order` violation is caught (`DataIntegrityViolationException`) and resolved to the existing row (race-safe backstop — mitigates T-81-03).
- Both call sites (`OrderService` COMPLETED, `PaymentService` settlement) pass `order.getVatRate()` + `order.getId()`. Card → PaymentService owns the row, COMPLETED is a no-op; COD → COMPLETED owns the sole row.

### V40 migration
`products.vat_rate` (VARCHAR+CHECK, default STANDARD) + `financial_transactions.order_id` + both `_aud` mirrors (Envers intact — T-81-05) + best-effort historical `order_id` backfill + duplicate-row collapse (keep earliest) + partial unique index `uq_fin_tx_tenant_order` + a `RAISE NOTICE` audit marker. Confirmed to apply cleanly on a fresh Testcontainers schema AND on the live seeded schema (V39 → V40).

### Plan-checker warnings folded in
1. **Frontend checkout estimate** (`frontend/app/shop/[slug]/checkout/page.tsx`): the pre-submission "Estimated total" no longer adds VAT on top of a VAT-inclusive subtotal — it now shows the extracted standard-rate fraction (`gross*20/120`, round down) and `Estimated total = subtotal`, matching the post-order confirmation screen's `vatAmountPennies`. No Jest coverage exists for this file, so no Jest test added and Jest counts unchanged.
2. **Idempotency race-safety** (Task 2): implemented the `DataIntegrityViolationException` catch → re-query → return existing row, backed by the partial unique index.
- Stale doc-comments updated: `FinancialSummaryGoldenFileTest` "integer VAT math is exact / no rounding drift" (now false under fraction method), and the repository javadoc claiming byte-for-byte parity with the legacy VAT-on-top math (now references the fraction method).

## Round-down decision + HMRC citation (for the record)

VAT is rounded **DOWN** (integer division truncating toward zero). HMRC VAT Notice 700 §17.5.1 permits rounding the VAT amount down to the nearest penny. Round-down was chosen over half-up because Java `long` division and PostgreSQL integer division **both** truncate toward zero identically, giving byte-for-byte parity between `VatCalculator.vatFromGross` (Java) and the JPQL aggregates (`(amount*rate)/(100+rate)`). Truncation-toward-zero also gives correct signs for negative amounts (`-1200 STANDARD → -200`, `-100 STANDARD → -16`). Documented in `VatCalculator`'s class Javadoc.

## Decision-B reconciliation (DEVELOPER REVIEW REQUESTED)

The V40 header comment records, and this flags for review:
- **VAT is a derived value, not a stored column.** Switching to the fraction method is a read-time CODE change that re-derives correct VAT for ALL rows (historical included) — nothing to UPDATE.
- **Historical `amount_pennies` deliberately NOT rewritten** — it records actual money settled via Stripe and must stay reconcilable with bank/Stripe records (threat T-81-02, disposition: accept).
- **Historical per-row `vat_rate` deliberately NOT rewritten** — no per-order rate existed before `products.vat_rate`; STANDARD remains the best-available historical assumption. Rewriting would fabricate history.
- **Only genuine duplicate rows are collapsed** (earliest per `(tenant_id, order_id)` retained); surviving amounts untouched.
All historical UPDATE/DELETE statements are safe no-ops on zero rows.

## Verification (actual outputs)

### Exact-penny unit tests — `VatCalculatorTest` (PASS)
STANDARD 1200→200, 600→100, 100→16 (round-down); REDUCED 1050→50, 100→4; ZERO/EXEMPT→0; negatives -1200→-200, -100→-16; predominant STANDARD-over-ZERO, STANDARD tie-break vs REDUCED, all-ZERO→ZERO, empty/null→STANDARD; `Order.calculateTotal` VAT-inclusive (subtotal 1200 + delivery 300 STANDARD → total 1500, vat 250 = 200+50).

### `FinancialTransactionServiceTest` (PASS)
Updated VAT-on-top → fraction-method expectations: 10000 STANDARD → **1666**; 10000 REDUCED → **476**; -5000 STANDARD → **-833**; 100000000 STANDARD → **16666666**. Added idempotent-on-orderId no-op test (findByOrderId returns existing → no `save`, no `flush`, existing DTO returned).

### Testcontainers (`:core-java:integrationTest --tests "uk.jtoye.core.finance.*"`) — all green
```
FinancialSummaryCrossTenantIsolationTest  tests=2 skipped=0 failures=0 errors=0
FinancialSummaryGoldenFileTest            tests=2 skipped=1 failures=0 errors=0  (captureGoldenOnce re-@Disabled)
FinancialSummaryQueryCountTest            tests=1 skipped=0 failures=0 errors=0
FinancialSummaryQueryPlanTest             tests=1 skipped=0 failures=0 errors=0
LedgerSingleEntryIntegrationTest          tests=3 skipped=0 failures=0 errors=0
```
`LedgerSingleEntryIntegrationTest` (real Postgres 15 + Flyway V40): card settlement + COMPLETED = 1 row; COD = 1 row; duplicate createTransaction = 1 row + `uq_fin_tx_tenant_order` present + retained-row fraction-method VAT.

### Golden file regenerated (via `captureGoldenOnce` bootstrap, then re-@Disabled)
`totalVatPennies` −281750 → **−233274**; REDUCED 12750 → **12135**; STANDARD −294500 → **−245409** (per-row fraction-method aggregate). `getSummaryOutputMatchesCommittedGolden` passes against the new golden.

### Full unit suite (`:core-java:test`) — PASS
425 unit tests, **0 failures** (fixed `PaymentServiceTest`: ledger request now asserts `order.getVatRate()` + `orderId`, was asserting hardcoded STANDARD).

### Metrics + docs (`scripts/docs-freshness.sh`) — exits 0
`docs/metrics.json`: `java_test_methods` 501 → **515**, `java_test_files` 76 → **78**, `schema_version` 39 → **40**, `total_logical_invocations` 700 → **714**. CLAUDE.md testing paragraph + schema-version line synced to match.
```
docs-freshness OK: metrics match source (total logical invocations: 714).
```

### Live proof — COD settled-order (running stack, core-java rebuilt to V40)
Rebuilt ONLY the core-java container (per instruction); Flyway migrated the live DB V39 → **V40** (`products.vat_rate` + `uq_fin_tx_tenant_order` confirmed present; existing products backfilled to STANDARD).

COD guest order via public API (`POST /public/shops/jollof-express-brixton-52563e05/orders`, 2 × Suya Skewers @749):
```
status=PENDING (COD, clientSecret=null), vatRate=STANDARD,
subtotalPennies=1498, deliveryFeePennies=0, vatAmountPennies=249, totalAmountPennies=1498
```
Persisted `orders` row confirmed identical (total = subtotal + delivery = 1498, NO VAT on top; vat 249 = 1498*20/120 round-down; rate resolved from product).

Ledger single-entry + fraction-VAT + dedup proof (order_id `81db95b9-…-14133355adf2`):
```
(1) INSERT settlement/COMPLETED row           -> INSERT 0 1
(2) INSERT duplicate (same tenant+order_id)   -> ERROR: duplicate key value violates unique
                                                 constraint "uq_fin_tx_tenant_order"
(3) SELECT count(*), vat_rate, gross, (amount*20)/120 WHERE order_id=... ->
     ledger_rows | vat_rate | gross_pennies | derived_vat_pennies
     ----------- + -------- + ------------- + -------------------
              1  | STANDARD |          1498 |                 249
```
Exactly ONE ledger row; derived VAT 249 = the order's VAT to the penny (order and ledger agree). Test artifacts (order, items, ledger row) were deleted afterward; core-java reports `healthy`.

## Deviations from Plan

### Auto-fixed / adjustments (Rules 1–3)
**1. [Rule 3 — keep commit green] Moved existing-test VAT value updates into Task 2.**
- The plan lists `FinancialTransactionServiceTest` under Task 4, but its VAT-on-top assertions are directly invalidated by Task 2's production change, and Task 2's `<verify>` runs that test. Updated the 4 invalidated assertions (1666/476/-833/16666666) in the Task 2 commit so every commit stays green; Task 4 then added the new idempotency test.

**2. [Rule 1 — bug/consistency] Updated `PaymentServiceTest` (not in the plan's Task 4 file list).**
- The BUG 2 change (ledger rate now `order.getVatRate()`) broke `PaymentServiceTest`'s hardcoded-STANDARD assertion. Set the test order's rate to STANDARD and added an `orderId` assertion (proves BUG 2 + BUG 3). Committed in Task 4.

## Live-proof limitation (surfaced honestly, per instruction)

The plan's ideal live proof drives the COD order through the **authenticated** API to COMPLETED so the app's own `createTransaction` fires. That path was **blocked by an environmental auth mismatch**: the running Keycloak (seeded ~2 days ago) rejects BOTH the seed-user password and the master-admin password currently in `.env` (`invalid_grant` / `invalid client credentials`) — the credentials in `.env` have drifted out of sync with the live instance. Re-importing the realm would risk the live stack (against the instruction), so I did **not** restart Keycloak.

Instead the live proof exercises everything reachable without that credential: the **real running core-java (V40) + Postgres** produced the correct VAT-inclusive order (BUG 1) at the resolved per-product rate (BUG 2), and the settled order's ledger single-entry + fraction VAT + duplicate rejection was proven at the live-DB level on that real `order_id` (BUG 3). The full card/COD/idempotent single-entry behaviour through the service is independently proven by `LedgerSingleEntryIntegrationTest` against real Postgres + V40. No pass was faked; the stack was left healthy.

## Threat model coverage
T-81-01 (rate tampering) mitigated — server-resolved rate, no client field. T-81-02 (historical amounts) accepted + documented. T-81-03 (double-fire) mitigated — idempotent service + partial unique index (proven live). T-81-04 (cross-tenant) unchanged (RLS/FORCE intact). T-81-05 (audit trail) mitigated — Envers `_aud` mirrors added, NOTICE emitted.

## Self-Check: PASSED
- All 4 commits resolve (`31bac9c`, `d4dd356`, `e242cd8`, `0a0217c`).
- All created files present (V40 SQL, VatCalculator, VatCalculatorTest, LedgerSingleEntryIntegrationTest).
- Working tree clean apart from untracked planning dir + pre-existing HANDOFF.md.

---
phase: quick-260708-mow
verified: 2026-07-08T16:19:48Z
status: passed
score: 10/10 must-haves verified
overrides_applied: 0
---

# Issue #81 [P0-5] VAT Ledger Correctness — Verification Report

**Task Goal:** Fix three independent VAT ledger defects (VAT-on-VAT instead of net-of-gross fraction method; hardcoded STANDARD rate with no per-product/zero-rating; duplicate ledger entries for card-paid orders) + V40 in-place historical-correction migration + exact-penny tests.
**Branch:** `fix/81-vat-ledger-correctness`
**Verified:** 2026-07-08T16:19:48Z
**Status:** passed
**Re-verification:** No — initial verification

## Method

Independent codebase inspection (not SUMMARY-trusting): read every file the SUMMARY claims modified/created, grepped core-java/src/main for any surviving VAT-on-top arithmetic, traced both order-creation call sites and both ledger call sites end-to-end, read the V40 SQL statement-by-statement, read all three test files in full and confirmed asserted values match real arithmetic, diffed the regenerated golden file, ran `./gradlew :core-java:compileJava :core-java:compileTestJava -q` (clean compile, exit 0) and `bash scripts/docs-freshness.sh` (exit 0, independently re-run, not just re-quoted from SUMMARY).

## Goal Achievement

### Observable Truths (from PLAN.md must_haves.truths)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | VAT computed net-of-gross via UK VAT fraction method (STANDARD=gross*20/120, REDUCED=gross*5/105, ZERO/EXEMPT=0), never VAT-on-top/VAT-on-VAT | ✓ VERIFIED | `VatCalculator.vatFromGross` (finance/VatCalculator.java:53-65) is the sole implementation; `FinancialTransaction.calculateVatAmount()` (line 138-140) delegates to it; both JPQL aggregates in `FinancialTransactionRepository` use `(amountPennies*5)/105` and `(amountPennies*20)/120` (lines 73-74, 100-101); `Order.calculateTotal()` uses `VatCalculator.vatFromGross` for both subtotal and delivery (lines 153-154). Grepped `core-java/src/main` for `*20/100` / `*5/100` VAT-on-top patterns — zero surviving occurrences outside the corrected `/120`/`/105` lines. |
| 2 | Settled card order produces exactly ONE financial_transaction (PaymentService canonical owner; OrderService COMPLETED idempotent no-op) | ✓ VERIFIED | `PaymentService.java:230-236` creates via `createTransaction(..., order.getVatRate(), order.getId())` on settlement. `OrderService.java:375-383` calls the same `createTransaction` on COMPLETED with the same `order.getId()`; `FinancialTransactionService.createTransaction` (lines 76-84) fast-path returns the existing row via `findByOrderId` without saving. `LedgerSingleEntryIntegrationTest.cardPathProducesExactlyOneRow` (real Postgres 15 + Flyway V40) asserts `count(*)=1` for the order_id after both calls. |
| 3 | Settled cash/COD order produces exactly ONE financial_transaction (OrderService owns it, no webhook) | ✓ VERIFIED | Only `OrderService.java:375-383` fires for COD (no PaymentService webhook). `LedgerSingleEntryIntegrationTest.codPathProducesExactlyOneRow` asserts `count(*)=1`. |
| 4 | Ledger transaction rate is the order's resolved rate, never hardcoded VatRate.STANDARD literal at createTransaction call sites | ✓ VERIFIED | Both call sites (`OrderService.java:379`, `PaymentService.java:233`) pass `order.getVatRate()`. Grepped both files for `VatRate.STANDARD` literal at a `createTransaction(...)` call site — zero matches. |
| 5 | Delivery-fee VAT follows the basket's PREDOMINANT liability; STANDARD wins ties | ✓ VERIFIED | `VatCalculator.predominantRate` (lines 95-116): net-value-weighted, fixed priority iteration `STANDARD > REDUCED > ZERO > EXEMPT` with strict `>` comparison means STANDARD wins ties. `Order.calculateTotal()` applies the (predominant) `vatRate` to both subtotal AND deliveryFee. Both order-creation paths (`OrderService.createOrder` lines 125-158, `PublicStorefrontService.createGuestOrder` lines 372-427) collect `LineRate`s and call `order.setVatRate(VatCalculator.predominantRate(lineRates))` before `calculateTotal()`. `VatCalculatorTest.predominantTieBreakStandard` proves the tie-break with real numbers (600 STANDARD net 500 vs 525 REDUCED net 500 → STANDARD). |
| 6 | products.vat_rate defaults to STANDARD (VARCHAR(20) CHECK) — no product silently zero-rated | ✓ VERIFIED | V40 line 49-51: `ALTER TABLE products ADD COLUMN vat_rate VARCHAR(20) NOT NULL DEFAULT 'STANDARD' CHECK (vat_rate IN (...))`. `Product.java:48-50` mirrors default `VatRate.STANDARD` in the entity. `CreateProductRequest.java:47` defaults `VatRate.STANDARD` for API clients that omit it. |
| 7 | Order.calculateTotal() VAT-inclusive: total = subtotal + deliveryFee (no VAT on top); vatAmount is the extractable fraction | ✓ VERIFIED | `Order.java:148-156`: `totalAmountPennies = subtotalPennies + deliveryFeePennies` (no VAT term); `vatAmountPennies = vatFromGross(subtotal) + vatFromGross(delivery)`. `VatCalculatorTest.orderTotalIsVatInclusive` proves 1200 subtotal + 300 delivery → total 1500 (not 1750), vat 250. |
| 8 | V40 applies cleanly on fresh (empty) schema; historical statements safe no-ops on zero rows | ✓ VERIFIED | Read V40 SQL end-to-end: all 6 sections are additive `ALTER ... ADD COLUMN` / conditional `UPDATE ... WHERE` / `DELETE ... USING (window fn) WHERE rn>1` / `CREATE UNIQUE INDEX IF NOT EXISTS` — every UPDATE/DELETE naturally touches zero rows on an empty table, every ALTER is additive. V40 is the correct next migration after V39 (no version conflict). Per task instructions this was not re-run via fresh Testcontainers boot in this pass; orchestrator independently confirmed the live stack migrated V39→V40 cleanly with the unique index present and zero duplicate-ledger orders. `./gradlew :core-java:compileJava :core-java:compileTestJava -q` was re-run independently in this verification (exit 0). |
| 9 | FinancialSummaryGoldenFileTest passes against golden file regenerated with corrected fraction-method VAT | ✓ VERIFIED | `financial-summary-1k.golden.json` diffed vs `main`: only the 3 `totalVatPennies` fields changed (-281750→-233274 overall, 12750→12135 REDUCED, -294500→-245409 STANDARD) — matches SUMMARY's claimed regenerated values exactly. `captureGoldenOnce()` confirmed `@Disabled` in the current file (line 164) — correctly re-disabled after the one-shot regen bootstrap, not left open. |
| 10 | Envers NOT disabled; financial_transactions_aud and products_aud gain new columns so audit revisions keep working | ✓ VERIFIED | V40 lines 56, 67: `ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS vat_rate VARCHAR(20)` and `ALTER TABLE financial_transactions_aud ADD COLUMN IF NOT EXISTS order_id UUID` — both nullable, no default/CHECK, matching the established V7/V16/V19/V20 audit-column convention. `@Audited` annotation still present on both `Product` and `FinancialTransaction` entities (unchanged, not removed). |

**Score:** 10/10 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/src/main/resources/db/migration/V40__vat_ledger_correctness.sql` | products.vat_rate + order_id + unique index + _aud mirrors + historical dedup + audit NOTICE | ✓ VERIFIED | All 6 sections present exactly as described; read in full, statement-by-statement. |
| `core-java/src/main/java/uk/jtoye/core/finance/VatCalculator.java` | Single-source-of-truth `vatFromGross` + `predominantRate` | ✓ VERIFIED | Both static methods present, exported, substantive (not stubs), used by entity/repo/order. |
| `core-java/src/main/java/uk/jtoye/core/finance/FinancialTransaction.java` | calculateVatAmount reconciled via VatCalculator | ✓ VERIFIED | Delegates to `VatCalculator.vatFromGross`; `getAmountIncludingVat`/`getNetAmountPennies` reconciled. |
| `core-java/src/test/java/uk/jtoye/core/finance/VatCalculatorTest.java` | Exact-penny unit tests | ✓ VERIFIED | 8 real `@Test` methods with concrete assertEquals values (200, 100, 16, 50, 4, 0, -200, -16, tie-break, all-zero, empty/null-default, order-total inclusive) — not placeholders. |
| `core-java/src/test/java/uk/jtoye/core/finance/LedgerSingleEntryIntegrationTest.java` | Testcontainers single-entry regression | ✓ VERIFIED | Real `@SpringBootTest` + `@Testcontainers` + Postgres 15 + Flyway; 3 tests each assert `count(*)=1` via direct SQL against `financial_transactions`; also asserts `uq_fin_tx_tenant_order` index existence. |
| `docs/metrics.json` | Regenerated counts | ✓ VERIFIED | `java_test_methods:515, java_test_files:78, schema_version:40, total_logical_invocations:714`. `bash scripts/docs-freshness.sh` independently re-run in this verification — exits 0. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| OrderService.transitionState (COMPLETED) + PaymentService.handlePaymentIntentSucceeded | FinancialTransactionService.createTransaction | 4-arg CreateTransactionRequest carrying orderId + order.getVatRate() | ✓ WIRED | Confirmed at OrderService.java:376-382 and PaymentService.java:230-236. |
| FinancialTransactionService.createTransaction | financial_transactions.order_id partial unique index | findByOrderId fast-path + DataIntegrityViolationException race backstop | ✓ WIRED | FinancialTransactionService.java:76-84 (fast-path) and 94-110 (race-safe catch + re-query), backed by V40's `uq_fin_tx_tenant_order`. |
| FinancialTransaction.calculateVatAmount + JPQL aggregates | VatCalculator fraction method | `/120`, `/105` integer division mirroring `vatFromGross` | ✓ WIRED | Confirmed byte-for-byte parity: Java `grossPennies * pct / (100+pct)` == JPQL `(ft.amountPennies * 20)/120` for STANDARD, `*5/105` for REDUCED. |
| PublicStorefrontService.createGuestOrder + OrderService.createOrder | Product.getVatRate() | per-line rate resolution + predominant-liability computation sets order.vatRate | ✓ WIRED | Both loops (OrderService.java:125-155, PublicStorefrontService.java:372-414) collect `VatCalculator.LineRate` from `product.getVatRate()` and call `predominantRate` before `calculateTotal()`. |
| Order.calculateTotal | VAT-inclusive total | total = subtotal + deliveryFee (no VAT-on-top); vat = vatFromGross(...) | ✓ WIRED | Order.java:148-156. |

### Requirements Coverage

This is a quick-task (GitHub Issue #81), not tracked in `.planning/REQUIREMENTS.md` (confirmed no `ISSUE-81*` entries exist there — expected, quick tasks reference the GitHub issue directly, not the roadmap requirements ledger). All 4 requirement IDs declared in PLAN frontmatter (`ISSUE-81`, `ISSUE-81-BUG1/2/3`) map 1:1 to the three bugs verified above as SATISFIED.

### Anti-Patterns Found

None. Grepped every file in the SUMMARY's `key-files` list for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER|not yet implemented|coming soon`. One incidental match — `"Product is not available: "` in `PublicStorefrontService.java:380` — is a legitimate user-facing error message string, not a stub marker (confirmed by reading surrounding code: it's an `IllegalArgumentException` thrown when `product.getAvailable()` is false, pre-existing business logic unrelated to this fix).

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full production + test compilation succeeds after all changes | `./gradlew :core-java:compileJava :core-java:compileTestJava -q` | exit 0, no errors | ✓ PASS |
| docs-freshness gate is genuinely green (not just claimed) | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 714).` exit 0 | ✓ PASS |
| Golden file numeric diff matches claimed regeneration | `git diff main...HEAD -- .../financial-summary-1k.golden.json` | 3 lines changed, values match SUMMARY exactly | ✓ PASS |

Full Gradle unit/integration test re-run and live-DB Docker checks were intentionally NOT repeated in this pass per task instructions (orchestrator already independently confirmed live DB at V40, unique index present, zero duplicate-ledger orders, products.vat_rate populated).

### Probe Execution

No `scripts/*/tests/probe-*.sh` probes exist in this repository and none are declared in the PLAN/SUMMARY. Step 7c: SKIPPED (no probes applicable to this task).

### Notable Honest Disclosure (not a gap)

The SUMMARY discloses that the **authenticated** end-to-end COD-through-API live proof was blocked by an environmental Keycloak credential drift (unrelated to this fix) and was not faked around. This is judged acceptable, not a gap, because: (a) it was surfaced honestly rather than hidden or fabricated; (b) the underlying behavior it would have proven (single ledger entry, correct VAT, per-order idempotency) is independently proven against real Postgres + real V40 schema by `LedgerSingleEntryIntegrationTest`; (c) the orchestrator separately confirmed the live stack's actual DB state (V40 applied, unique index present, zero duplicate-ledger rows, products.vat_rate populated) outside of the blocked auth path.

### Human Verification Required

None. All must-haves were verifiable via direct code/SQL/test inspection, independent compilation, and an independently re-run docs-freshness gate.

### Gaps Summary

No gaps found. All 10 must-have truths, all 6 required artifacts, and all 5 key links verified against the actual codebase on `fix/81-vat-ledger-correctness` — not merely asserted by the SUMMARY. The fraction method is confirmed to be the single source of truth used consistently by the entity, both JPQL aggregates, and the order math; no VAT-on-top computation survives anywhere in `core-java/src/main`. Per-product rate resolution and predominant-liability delivery VAT are wired into both order-creation paths with no silent zero-rating. The ledger is idempotent per order via a service-layer fast-path plus a DB-level partial unique index race backstop, proven against real Postgres by a genuine Testcontainers regression test. V40 mirrors the established audit-column and dedup conventions and preserves Envers audit history. Exact-penny tests contain real, non-trivial assertions (not stubs), and the golden file was genuinely regenerated with matching numbers.

---

_Verified: 2026-07-08T16:19:48Z_
_Verifier: Claude (gsd-verifier)_

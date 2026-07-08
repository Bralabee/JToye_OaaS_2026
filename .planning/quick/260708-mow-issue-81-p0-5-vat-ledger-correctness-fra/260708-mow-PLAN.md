---
phase: quick-260708-mow
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/resources/db/migration/V40__vat_ledger_correctness.sql
  - core-java/src/main/java/uk/jtoye/core/finance/VatCalculator.java
  - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransaction.java
  - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionRepository.java
  - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionService.java
  - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionMapper.java
  - core-java/src/main/java/uk/jtoye/core/finance/dto/CreateTransactionRequest.java
  - core-java/src/main/java/uk/jtoye/core/product/Product.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
  - core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java
  - core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java
  - core-java/src/main/java/uk/jtoye/core/order/Order.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
  - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
  - core-java/src/test/java/uk/jtoye/core/finance/VatCalculatorTest.java
  - core-java/src/test/java/uk/jtoye/core/finance/FinancialTransactionServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/finance/LedgerSingleEntryIntegrationTest.java
  - core-java/src/test/resources/fixtures/financial-summary-1k.golden.json
  - docs/metrics.json
  - CLAUDE.md
autonomous: true
requirements:
  - "ISSUE-81"          # [P0-5] VAT ledger correctness (three bugs)
  - "ISSUE-81-BUG1"     # VAT-on-VAT: derive VAT from gross via fraction method
  - "ISSUE-81-BUG2"     # hardcoded STANDARD: resolve per-product VAT + predominant delivery liability
  - "ISSUE-81-BUG3"     # duplicate ledger: single canonical entry per settled order (idempotent)

user_setup: []

must_haves:
  truths:
    - "VAT is computed net-of-gross via the UK VAT fraction method (STANDARD = gross*20/120 = gross/6; REDUCED = gross*5/105; ZERO/EXEMPT = 0), never VAT-on-top and never VAT-on-VAT"
    - "A settled card order produces exactly ONE financial_transaction (PaymentService is the canonical ledger owner; OrderService COMPLETED is an idempotent no-op for the same order)"
    - "A settled cash/COD order produces exactly ONE financial_transaction (OrderService COMPLETED owns it; no payment webhook fires)"
    - "The ledger transaction rate is the order's resolved rate, never a hardcoded VatRate.STANDARD literal at the createTransaction call sites"
    - "Delivery-fee VAT follows the basket's PREDOMINANT liability (rate carrying greatest net goods value; STANDARD wins ties)"
    - "products.vat_rate defaults to STANDARD (VARCHAR(20) CHECK) — no product is silently zero-rated"
    - "Order.calculateTotal() treats prices as VAT-inclusive: total = subtotal + deliveryFee (no VAT added on top); vatAmount is the extractable fraction within the total"
    - "V40 applies cleanly on a fresh (empty) schema and its historical statements are safe no-ops on zero rows"
    - "Existing FinancialSummaryGoldenFileTest passes against a golden file regenerated with corrected fraction-method VAT"
    - "Envers is NOT disabled; financial_transactions_aud and products_aud gain the new columns so audit revisions keep working"
  artifacts:
    - path: "core-java/src/main/resources/db/migration/V40__vat_ledger_correctness.sql"
      provides: "products.vat_rate + financial_transactions.order_id + partial unique index + _aud mirrors + historical duplicate collapse + order_id backfill + audit NOTICE"
      contains: "products.*vat_rate"
    - path: "core-java/src/main/java/uk/jtoye/core/finance/VatCalculator.java"
      provides: "Single source of truth: vatFromGross(long grossPennies, VatRate rate) fraction method used by entity + tests"
      exports: ["vatFromGross"]
    - path: "core-java/src/main/java/uk/jtoye/core/finance/FinancialTransaction.java"
      provides: "calculateVatAmount() + getAmountIncludingVat()/getNetAmountPennies() reconciled to fraction method via VatCalculator"
      contains: "VatCalculator.vatFromGross"
    - path: "core-java/src/test/java/uk/jtoye/core/finance/VatCalculatorTest.java"
      provides: "Exact-penny unit tests: standard, zero, reduced, exempt, negative, rounding boundary, predominant-liability + delivery"
    - path: "core-java/src/test/java/uk/jtoye/core/finance/LedgerSingleEntryIntegrationTest.java"
      provides: "Testcontainers regression: card-paid COMPLETED path = 1 row; COD path = 1 row; idempotent second call"
    - path: "docs/metrics.json"
      provides: "Regenerated java_test_methods + total_logical_invocations via scripts/docs-freshness.sh --write"
  key_links:
    - from: "OrderService.transitionState (COMPLETED) + PaymentService.handlePaymentIntentSucceeded"
      to: "FinancialTransactionService.createTransaction"
      via: "CreateTransactionRequest now carries orderId + order.getVatRate()"
      pattern: "createTransaction\\("
    - from: "FinancialTransactionService.createTransaction"
      to: "financial_transactions.order_id partial unique index"
      via: "findByOrderId existence check (idempotent no-op) backed by DB unique index"
      pattern: "findByOrderId"
    - from: "FinancialTransaction.calculateVatAmount + FinancialTransactionRepository JPQL aggregates"
      to: "fraction method"
      via: "VatCalculator.vatFromGross (Java) mirrored byte-for-byte by /(100+rate) integer division in JPQL"
      pattern: "/ 120|/ 105|vatFromGross"
    - from: "PublicStorefrontService.createGuestOrder + OrderService.createOrder"
      to: "Product.getVatRate()"
      via: "per-line rate resolution + predominant-liability computation sets order.vatRate"
      pattern: "getVatRate\\(\\)"
    - from: "Order.calculateTotal"
      to: "VAT-inclusive total"
      via: "total = subtotal + deliveryFee (no VAT-on-top); vat = VatCalculator.vatFromGross(...)"
      pattern: "vatFromGross"
---

<objective>
Fix Issue #81 [P0-5]: the VAT ledger is mathematically wrong in three independent ways, and add a V40 in-place historical correction that is provable to the penny.

- BUG 1 (VAT-on-VAT / VAT-on-top): `FinancialTransaction.calculateVatAmount()` and the two JPQL summary aggregates apply `amount * rate / 100` to a VAT-inclusive gross; `Order.calculateTotal()` adds VAT on top of a VAT-inclusive subtotal. Both must switch to the UK VAT fraction method (`gross * rate / (100 + rate)`).
- BUG 2 (hardcoded STANDARD): the storefront and both `createTransaction` sites use `VatRate.STANDARD` literally. Rate must be resolved per product, with the delivery fee following the basket's predominant liability.
- BUG 3 (duplicate ledger): a card-paid order that later reaches COMPLETED fires `createTransaction` in BOTH `PaymentService` and `OrderService`, double-counting revenue. Make the ledger idempotent per order so exactly one entry exists per settled order (card OR cash).

Purpose: financial correctness and HMRC-defensible VAT reporting. A wrong ledger is a P0 compliance and revenue-integrity defect.
Output: V40 migration, a single-source-of-truth VAT helper, corrected ledger + order math, per-product rate resolution, exact-penny tests, a live settled-order check, and synced metrics/docs.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
@CLAUDE.md
@.planning/STATE.md

<vat_contract>
Single, internally-consistent VAT model this plan MUST implement (order math and ledger math agree exactly):

1. Prices are VAT-INCLUSIVE consumer prices (UK retail). `product.pricePennies` is gross.
2. VAT FRACTION METHOD (single source of truth = `VatCalculator.vatFromGross`):
   - STANDARD → `gross * 20 / 120` (= gross/6)
   - REDUCED  → `gross * 5 / 105`
   - ZERO, EXEMPT → 0
   - ROUNDING = round DOWN (integer division truncating toward zero). HMRC VAT Notice 700 §17.5.1 permits rounding VAT down to the nearest penny. Chosen over half-up because Java `long` division and PostgreSQL integer division BOTH truncate toward zero identically, giving trivial byte-for-byte parity between the Java helper and the JPQL aggregates (the recon suggested half-up; round-down is used instead for Java/SQL parity + HMRC compliance — document this in code).
   - Negative amounts (expenses/refunds) truncate toward zero in both Java and Postgres, so `-1200 STANDARD → -200`, `-100 STANDARD → -16`.
3. ORDER (single `vatRate` field = predominant rate):
   - `subtotal = Σ line.totalPricePennies` (VAT-inclusive)
   - `total = subtotal + deliveryFee` (NO VAT added on top — this is the BUG 1 fix)
   - `vatAmount = VatCalculator.vatFromGross(subtotal, order.vatRate) + VatCalculator.vatFromGross(deliveryFee, order.vatRate)`
4. PREDOMINANT RATE (set before calculateTotal in both order-creation paths):
   - For each line resolve rate from `product.getVatRate()`.
   - Bucket by rate; weight = net goods value = `line.total - vatFromGross(line.total, lineRate)`.
   - Predominant = rate with the greatest summed net value. TIE-BREAK: STANDARD wins (most conservative for HMRC).
   - `order.setVatRate(predominant)` — delivery VAT therefore follows predominant liability (per §3).
5. LEDGER (`financial_transaction`): exactly ONE per settled order. `amount_pennies = order.getTotalAmountPennies()`, `vat_rate = order.getVatRate()`, `order_id = order.getId()`. `calculateVatAmount()` then re-derives `vatFromGross(amount, rate)` which equals `order.vatAmountPennies` — order and ledger agree.

SCOPE NOTE (document in code, do NOT silently reduce): the order carries ONE vat_rate = predominant. Per-line VAT reporting at mixed rates in the LEDGER is out of scope for this issue (would require order_items.vat_rate + a stored VAT column on financial_transactions — neither is in the locked decision set). This is a bounded model choice, not a v1/placeholder.
</vat_contract>

<interfaces>
Current signatures the executor works against (already read — do not re-explore):

FinancialTransaction (finance/FinancialTransaction.java):
- Fields: amountPennies (Long, gross), vatRate (VatRate, @Enumerated STRING), reference (String). @Audited.
- long calculateVatAmount()  — BUG 1, switch on vatRate doing amount*rate/100
- long getAmountIncludingVat() — returns amount + calculateVatAmount() (UNUSED in prod; safe to reconcile)

FinancialTransactionRepository JPQL (2 queries) hardcode `(ft.amountPennies * 5)/100` and `(ft.amountPennies * 20)/100` in aggregateForCurrentTenant() and aggregateByVatRate().

CreateTransactionRequest = record(Long amountPennies, VatRate vatRate, String description).
FinancialTransactionMapper: MapStruct, toEntity maps description→reference.
FinancialTransactionService.createTransaction(request): sets tenant, saves, returns DTO (NOT idempotent).

Order (order/Order.java): single field `vatRate` (default ZERO), vatAmountPennies, deliveryFeePennies, subtotalPennies, totalAmountPennies. calculateTotal() = subtotal + VAT-on-top + delivery (BUG 1). getId() available.
OrderItem: NO per-line rate. getTotalPricePennies() = qty * unitPrice.
Product (product/Product.java): @Audited, has @Version. NO vat_rate field yet. getPricePennies() gross.

Call sites (both pass order total + hardcoded STANDARD — BUG 2/3):
- OrderService.java:361 — on COMPLETED: createTransaction(new CreateTransactionRequest(order.getTotalAmountPennies(), VatRate.STANDARD, "Order " + order.getOrderNumber()))
- PaymentService.java:228 — on payment success: createTransaction(new CreateTransactionRequest(order.getTotalAmountPennies(), VatRate.STANDARD, "Payment " + intent.getId() + " for Order " + order.getOrderNumber()))
- PublicStorefrontService.java:360 — order.setVatRate(VatRate.STANDARD) (hardcoded)
- OrderService.java:148 — admin createOrder calls calculateTotal() with Order default vatRate=ZERO (silent zero-rating)

RefundService does NOT write financial_transactions (verified) — refunds are out of ledger scope.

Envers audit tables (Flyway-managed, IF NOT EXISTS ALTER convention per V7/V16/V19/V20):
- financial_transactions_aud (V4): id, rev, revtype, tenant_id, created_at, amount_pennies, vat_rate TEXT, reference. Has FORCE RLS (V35).
- products_aud (V4 + later ALTERs): backfilled per-column across V7/V16/V19/V20; convention = mirror every new @Audited column as nullable.

Gradle test tasks:
- `./gradlew :core-java:test` — unit tests, EXCLUDES @Tag("testcontainers")
- `./gradlew :core-java:integrationTest` — @Tag("testcontainers"), real Postgres 15 + Flyway (validates V40 on fresh schema)

Metrics gate: `scripts/docs-freshness.sh` counts `@Test\b` in core-java/src/test. Current docs/metrics.json: java_test_methods=501, total_logical_invocations=700. `scripts/docs-freshness.sh --write` regenerates.
</interfaces>
</context>

<tasks>

<task type="auto" tdd="false">
  <name>Task 1: V40 schema + audit mirrors + Product domain + VatCalculator helper</name>
  <files>
    core-java/src/main/resources/db/migration/V40__vat_ledger_correctness.sql,
    core-java/src/main/java/uk/jtoye/core/finance/VatCalculator.java,
    core-java/src/main/java/uk/jtoye/core/product/Product.java,
    core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java,
    core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java,
    core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java
  </files>
  <behavior>
    - VatCalculator.vatFromGross(1200, STANDARD) == 200; (600,STANDARD)==100; (100,STANDARD)==16 (round-down)
    - vatFromGross(1050, REDUCED) == 50; (100,REDUCED)==4
    - vatFromGross(anything, ZERO)==0; (anything, EXEMPT)==0
    - vatFromGross(-1200, STANDARD) == -200; (-100,STANDARD) == -16 (truncate toward zero)
    - V40 applies cleanly on an empty Postgres schema (Flyway migrate in Testcontainers boot) and every historical statement is a no-op on zero rows
  </behavior>
  <action>
Create V40__vat_ledger_correctness.sql implementing decisions A and B. Mirror the established VARCHAR+CHECK pattern from V12 and the `_aud ADD COLUMN IF NOT EXISTS` convention from V7/V16/V19/V20. Structure the file with these sections and rich SQL comments explaining each:

1. DECISION A — products.vat_rate: `ALTER TABLE products ADD COLUMN vat_rate VARCHAR(20) NOT NULL DEFAULT 'STANDARD' CHECK (vat_rate IN ('ZERO','REDUCED','STANDARD','EXEMPT'));`. Mirror onto the audit table: `ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS vat_rate VARCHAR(20);` (nullable, no default/CHECK — audit-column convention). The NOT NULL DEFAULT backfills existing product rows to STANDARD via the column default (no separate UPDATE, no Envers revision minted — existing rows keep their current audit history).

2. DEDUPE SCHEMA — financial_transactions.order_id: `ALTER TABLE financial_transactions ADD COLUMN order_id UUID;` (nullable — the admin/manual ledger path has no order). Mirror: `ALTER TABLE financial_transactions_aud ADD COLUMN IF NOT EXISTS order_id UUID;`.

3. DECISION B — historical order_id backfill (safe on zero rows): `UPDATE financial_transactions ft SET order_id = o.id FROM orders o WHERE o.tenant_id = ft.tenant_id AND ft.order_id IS NULL AND ft.reference LIKE '%Order ' || o.order_number;`. Both legacy reference formats end with `Order <order_number>` so this matches "Order X" and "Payment Y for Order X". Comment that this is best-effort and only enables dedup + the unique index; rows that do not match stay NULL and are harmless.

4. DECISION B — duplicate collapse (safe on zero rows): delete all but the earliest row per (tenant_id, order_id) where order_id IS NOT NULL, keeping MIN(created_at) so the PaymentService settlement row is retained for card orders and the sole row is retained for cash orders. Use a window-function subquery (ROW_NUMBER() OVER (PARTITION BY tenant_id, order_id ORDER BY created_at, id) with delete where rn > 1). Do NOT rewrite amount_pennies or vat_rate on retained rows — see the audit-note rationale below.

5. DEDUPE guarantee — partial unique index (created AFTER collapse so it cannot fail on legacy dupes): `CREATE UNIQUE INDEX IF NOT EXISTS uq_fin_tx_tenant_order ON financial_transactions (tenant_id, order_id) WHERE order_id IS NOT NULL;`.

6. AUDIT NOTE — no audit/log table exists in this schema (verified), so per decision B use a documented `DO $$ BEGIN RAISE NOTICE '...'; END $$;` plus a header comment block recording: (a) fraction-method adoption is a CODE change that re-derives VAT correctly at read time for ALL rows including historical (VAT is a derived value, not a stored column — nothing to rewrite); (b) N duplicate rows collapsed; (c) DELIBERATE PRESERVATION: historical `amount_pennies` is NOT rewritten because it records actual money settled via Stripe and must stay reconcilable with bank/Stripe records; historical per-row `vat_rate` is NOT rewritten because no per-order rate existed before products.vat_rate (STANDARD remains the best-available historical assumption). This reconciles decision B against the stored schema (VAT derived, delivery commingled in the total) — flag it for developer review in the SUMMARY.

Create VatCalculator.java in the finance package as the single source of truth. Static `long vatFromGross(long grossPennies, VatRate rate)`: map rate to percent (ZERO/EXEMPT→0 short-circuit return 0; REDUCED→5; STANDARD→20); return `grossPennies * pct / (100 + pct)` using long arithmetic (multiply before divide; integer division truncates toward zero = HMRC round-down; works correctly for negative gross). Add a class-level Javadoc citing HMRC VAT Notice 700 §17.5.1 and the round-down rationale.

Thread vat_rate through the Product domain: add `@Enumerated(EnumType.STRING) @Column(name="vat_rate", nullable=false, length=20) private VatRate vatRate = VatRate.STANDARD;` to Product (import uk.jtoye.core.finance.VatRate) with getter/setter. Add `vatRate` to ProductDto and CreateProductRequest (default STANDARD when absent so existing API clients keep working). Update ProductMapper so vatRate maps in both directions (MapStruct auto-maps same-named fields; add explicit @Mapping only if the mapper does not already round-trip it — verify by reading ProductMapper first).
  </action>
  <verify>
    <automated>./gradlew :core-java:compileJava -q && ./gradlew :core-java:integrationTest --tests "uk.jtoye.core.finance.FinancialSummaryQueryPlanTest" -q</automated>
  </verify>
  <done>V40 present as the next migration (latest was V39); products.vat_rate + financial_transactions.order_id + both _aud mirrors + partial unique index + historical dedup/backfill/audit-NOTICE exist; VatCalculator compiles; Product/DTOs/mapper carry vatRate default STANDARD; a Testcontainers test boots Flyway V40 on a fresh schema without error.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 2: Ledger correctness — fraction method + idempotent single entry</name>
  <files>
    core-java/src/main/java/uk/jtoye/core/finance/FinancialTransaction.java,
    core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionRepository.java,
    core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionService.java,
    core-java/src/main/java/uk/jtoye/core/finance/dto/CreateTransactionRequest.java,
    core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionMapper.java,
    core-java/src/main/java/uk/jtoye/core/order/Order.java,
    core-java/src/main/java/uk/jtoye/core/order/OrderService.java,
    core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
  </files>
  <behavior>
    - FinancialTransaction with amount 1200, STANDARD → calculateVatAmount()==200; amount 10000 STANDARD → 1666; REDUCED 10000 → 476; ZERO/EXEMPT → 0; -5000 STANDARD → -833
    - getAmountIncludingVat() returns amountPennies (amount IS the gross); getNetAmountPennies() == amount - calculateVatAmount()
    - Two createTransaction calls with the same orderId create ONE row; the second returns the existing DTO (no second save)
    - Order.calculateTotal(): subtotal 1200 + delivery 300 at STANDARD → total 1500, vatAmount == vatFromGross(1200,STANDARD)+vatFromGross(300,STANDARD) == 200+50 == 250 (NOT VAT-on-top)
  </behavior>
  <action>
FinancialTransaction: replace the calculateVatAmount() switch body with `return VatCalculator.vatFromGross(amountPennies, vatRate);`. Reconcile the inclusive/net semantics (BUG 1 recon): since amountPennies is already the VAT-inclusive gross, change getAmountIncludingVat() to `return amountPennies;` and add `long getNetAmountPennies() { return amountPennies - calculateVatAmount(); }`. Update the method Javadoc to state amount is gross-inclusive. Add an `order_id` mapping field: add `@Column(name="order_id") private UUID orderId;` with getter/setter (nullable; @Audited column already mirrored in T1).

FinancialTransactionRepository: update BOTH JPQL aggregates (aggregateForCurrentTenant and aggregateByVatRate) to the fraction method so DB-side summary math matches VatCalculator byte-for-byte. Replace `(ft.amountPennies * 5) / 100` with `(ft.amountPennies * 5) / 105` and `(ft.amountPennies * 20) / 100` with `(ft.amountPennies * 20) / 120` in every CASE branch. Add a repository method `Optional<FinancialTransaction> findByOrderId(UUID orderId)` (RLS-scoped, tenant filter appended automatically). Update the Javadoc that currently claims parity with the old byte-for-byte math to reference the fraction method.

CreateTransactionRequest: add `UUID orderId` as a 4th component AND a 3-arg convenience constructor `public CreateTransactionRequest(Long amountPennies, VatRate vatRate, String description) { this(amountPennies, vatRate, description, null); }` so the admin controller path and existing 3-arg callers/tests compile unchanged.

FinancialTransactionMapper: ensure orderId maps from request to entity (auto-map same-named; add @Mapping only if needed).

FinancialTransactionService.createTransaction: make idempotent. After resolving tenant, if `request.orderId() != null`, call `financialTransactionRepository.findByOrderId(request.orderId())`; if present, log at INFO ("idempotent ledger no-op for order {}") and return the existing entity's DTO WITHOUT saving. Otherwise proceed to map, set tenantId, set orderId, save. This is the service-layer guard; the T1 partial unique index is the race-safe backstop.

Order.calculateTotal() (BUG 1 fix): keep `subtotalPennies = Σ line totals`; set `totalAmountPennies = subtotalPennies + deliveryFeePennies` (remove the VAT-on-top addition); set `vatAmountPennies = VatCalculator.vatFromGross(subtotalPennies, vatRate) + VatCalculator.vatFromGross(deliveryFeePennies, vatRate)` (import uk.jtoye.core.finance.VatCalculator). Delete the private static calculateVatAmount switch (superseded by VatCalculator). Keep itemCount as-is.

Call sites — canonical single path (BUG 2/3): at OrderService.java:361 and PaymentService.java:228, replace `new CreateTransactionRequest(order.getTotalAmountPennies(), VatRate.STANDARD, "...")` with the 4-arg form passing `order.getVatRate()` for the rate and `order.getId()` for orderId (keep each existing reference string). With idempotency keyed on orderId: card orders → PaymentService creates the row on settlement, the later COMPLETED transition is a no-op; cash/COD orders → no webhook, so COMPLETED creates the single row. Exactly one entry per settled order either way.
  </action>
  <verify>
    <automated>./gradlew :core-java:test --tests "uk.jtoye.core.finance.FinancialTransactionServiceTest" -q</automated>
  </verify>
  <done>calculateVatAmount + both JPQL aggregates use the fraction method; getAmountIncludingVat/getNetAmountPennies reconciled; createTransaction is idempotent on orderId; CreateTransactionRequest has orderId + a 3-arg convenience ctor; Order.calculateTotal is VAT-inclusive (no VAT-on-top); both call sites pass order.getVatRate()+order.getId(); FinancialTransactionServiceTest passes with updated fraction-method expectations (updated in T4).</done>
</task>

<task type="auto" tdd="false">
  <name>Task 3: Per-product rate resolution + predominant delivery liability</name>
  <files>
    core-java/src/main/java/uk/jtoye/core/finance/VatCalculator.java,
    core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java,
    core-java/src/main/java/uk/jtoye/core/order/OrderService.java
  </files>
  <behavior>
    - Basket: 2 lines net-value STANDARD £8.00 + 1 line net-value ZERO £2.00 → predominant == STANDARD
    - Basket: STANDARD net £5.00 vs REDUCED net £5.00 (tie) → predominant == STANDARD (tie-break)
    - After resolution, order.getVatRate() == predominant and delivery VAT is extracted at that rate in calculateTotal()
    - Empty/edge: a basket where all lines are ZERO → predominant == ZERO (no silent STANDARD upgrade); a basket with only unknown-rate fallback defaults to STANDARD
  </behavior>
  <action>
Add a reusable predominant-rate resolver so both order-creation paths share one implementation. In VatCalculator add `static VatRate predominantRate(java.util.List<LineRate> lines)` where LineRate carries (long grossPennies, VatRate rate) — or accept a `Map<VatRate,Long>` of summed net values; pick the shape that reads cleanly and unit-test it directly. Algorithm: for each line compute net = gross - vatFromGross(gross, rate); sum net per rate; return the rate with the greatest summed net value; on a tie return STANDARD (iterate a fixed priority order STANDARD > REDUCED > ZERO > EXEMPT when sums are equal). If the line list is empty, return STANDARD. Document the tie-break and the "no silent zero-rating" intent.

PublicStorefrontService.createGuestOrder (BUG 2): the loop at ~line 370 already loads each Product. While building OrderItems, collect each line's (totalPricePennies, product.getVatRate()). Replace the hardcoded `order.setVatRate(VatRate.STANDARD)` at line 360 with a call to the resolver AFTER items are added and line totals known, i.e. `order.setVatRate(VatCalculator.predominantRate(lineRates))` before `order.calculateTotal()`. Delivery VAT then follows the predominant rate via the corrected calculateTotal (T2). Keep the existing idempotency-return branch (~line 334) working — it reads existingOrder.getVatRate(), which is now the persisted predominant rate, so no change needed there.

OrderService.createOrder (~line 121-148): the admin path also loads each Product per line and currently leaves order.vatRate at the ZERO default (silent zero-rating). Collect line (totalPricePennies, product.getVatRate()) in the same loop and call `order.setVatRate(VatCalculator.predominantRate(lineRates))` before `order.calculateTotal()` at line 148. This closes the "no silent zero-rating" must-have on the admin path too.

Do not add order_items.vat_rate or a stored VAT column — per the vat_contract scope note, the order carries one predominant rate. Document that decision briefly where the resolver is called.
  </action>
  <verify>
    <automated>./gradlew :core-java:test --tests "uk.jtoye.core.finance.*" --tests "uk.jtoye.core.storefront.*" -q</automated>
  </verify>
  <done>predominantRate helper exists and is unit-tested (T4); PublicStorefrontService resolves each line's rate from its Product and sets order.vatRate to the predominant rate (hardcoded STANDARD removed); OrderService.createOrder resolves predominant rate instead of defaulting ZERO; delivery VAT follows predominant liability via the corrected calculateTotal; storefront + finance unit tests pass.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 4: Exact-penny tests, golden-file regen, live check, metrics + docs sync</name>
  <files>
    core-java/src/test/java/uk/jtoye/core/finance/VatCalculatorTest.java,
    core-java/src/test/java/uk/jtoye/core/finance/FinancialTransactionServiceTest.java,
    core-java/src/test/java/uk/jtoye/core/finance/LedgerSingleEntryIntegrationTest.java,
    core-java/src/test/resources/fixtures/financial-summary-1k.golden.json,
    docs/metrics.json,
    CLAUDE.md
  </files>
  <behavior>
    - VatCalculatorTest asserts exact pennies for STANDARD/ZERO/REDUCED/EXEMPT, negative amounts, the round-down boundary (100 STANDARD → 16), a mixed basket with delivery predominant-liability, and the tie-break
    - LedgerSingleEntryIntegrationTest asserts: card-paid-then-COMPLETED order → exactly 1 financial_transaction for that order_id; COD order COMPLETED → exactly 1; a duplicate createTransaction call for the same orderId → still 1
    - FinancialSummaryGoldenFileTest passes against the regenerated golden file
    - scripts/docs-freshness.sh (no --write) exits 0 after the update
  </behavior>
  <action>
Create VatCalculatorTest (pure JUnit, no Spring, runs under :core-java:test): exact-penny cases from the vat_contract — STANDARD (1200→200, 600→100, 100→16), REDUCED (1050→50, 100→4), ZERO/EXEMPT (→0), negatives (-1200→-200, -100→-16), the round-down boundary (assert 100 STANDARD == 16 and comment the HMRC round-down choice), predominantRate selection (mixed STANDARD+ZERO → STANDARD), and the STANDARD-vs-REDUCED tie → STANDARD. Add an Order.calculateTotal assertion (build an Order with items + delivery, set predominant rate, assert total == subtotal+delivery and vatAmount == fraction sum, proving no VAT-on-top).

Update FinancialTransactionServiceTest expected values to the fraction method (these currently assert the old VAT-on-top math and WILL fail otherwise): 10000 STANDARD 2000→1666 (lines ~137, ~196, ~274); 10000 REDUCED 500→476 (~216); -5000 STANDARD -1000→-833 (~381); 100000000 STANDARD 20000000→16666666 (~482). ZERO/EXEMPT stay 0. The getSummary mocked-row tests (~407, ~449) pass pre-aggregated rows and need no change. Leave the 3-arg CreateTransactionRequest constructions as-is (the T2 convenience ctor keeps them compiling). Add one test asserting createTransaction is idempotent when the repository already returns a row for the given orderId (mock findByOrderId to return an existing entity; verify save is never called and the existing DTO is returned).

Create LedgerSingleEntryIntegrationTest (@Tag("testcontainers"), real Postgres + Flyway V40, runs under :core-java:integrationTest). Follow the FinancialSummaryGoldenFileTest bootstrap pattern for container + DynamicPropertySource + tenant seeding. Three cases: (1) simulate the card path — create an order, call PaymentService's settlement transaction creation (or call FinancialTransactionService.createTransaction with the order's id to represent the settlement row), then drive OrderService COMPLETED (or a second createTransaction with the same orderId), assert `SELECT count(*) FROM financial_transactions WHERE order_id = ?` == 1; (2) COD path — no settlement row, one COMPLETED-path createTransaction, assert count == 1; (3) idempotency — two direct createTransaction calls with the same orderId, assert count == 1 and the DB partial unique index is present. Assert the retained row's vat_rate and derived VAT match the order's resolved rate.

Regenerate the golden file: the fraction-method change alters expected VAT in FinancialSummaryGoldenFileTest's committed golden JSON. Regenerate via the sanctioned bootstrap — temporarily remove the @Disabled annotation on captureGoldenOnce(), run `./gradlew :core-java:integrationTest --tests "uk.jtoye.core.finance.FinancialSummaryGoldenFileTest.captureGoldenOnce"`, restore the @Disabled annotation, then confirm `getSummaryOutputMatchesCommittedGolden` passes. Commit the regenerated financial-summary-1k.golden.json.

Live settled-order check (Docker + live stack per CLAUDE.md rebuild rule): rebuild ALL containers, then exercise the COD path end-to-end (Stripe test keys are unavailable in this env per STATE.md, so the card path is proven by LedgerSingleEntryIntegrationTest, not live). Create a guest order that goes COD → PENDING, transition it through to COMPLETED via the API, then `psql` the DB: `SELECT count(*), max(vat_rate) FROM financial_transactions WHERE order_id = '<id>'` must return exactly 1 with the resolved rate, and the derived VAT (amount*rate/(100+rate)) must match. Record the psql output in the SUMMARY as the live proof.

Metrics + docs: run `scripts/docs-freshness.sh --write` to regenerate docs/metrics.json (java_test_methods and total_logical_invocations rise by the number of new @Test methods added). Sync the CLAUDE.md testing-standard paragraph so its Java @Test count and total match the regenerated docs/metrics.json (single source of truth). Then run `scripts/docs-freshness.sh` (no --write) and confirm it exits 0.
  </action>
  <verify>
    <automated>./gradlew :core-java:test -q && ./gradlew :core-java:integrationTest --tests "uk.jtoye.core.finance.*" -q && bash scripts/docs-freshness.sh</automated>
  </verify>
  <done>VatCalculatorTest + updated FinancialTransactionServiceTest + LedgerSingleEntryIntegrationTest pass; FinancialSummaryGoldenFileTest passes against the regenerated golden file; docs/metrics.json regenerated and CLAUDE.md count paragraph synced; scripts/docs-freshness.sh exits 0; live COD settled-order psql proof (count==1, correct VAT) recorded in the SUMMARY.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → storefront/order API | Untrusted item lists cross here; prices AND VAT rates must be server-resolved from Product, never client-supplied |
| Flyway migration → financial_transactions/_aud | V40 mutates a live financial ledger; correctness and RLS/Envers compatibility matter |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-81-01 | Tampering | order VAT rate | mitigate | Rate resolved server-side from product.vat_rate; client cannot set it (no rate field on GuestOrderRequest/OrderItemRequest). Prices already server-side. |
| T-81-02 | Tampering | V40 historical amount_pennies | accept | Historical settled amounts are deliberately NOT rewritten — they must stay reconcilable with Stripe/bank records; only double-counted duplicate rows are removed. Documented in migration audit NOTICE. |
| T-81-03 | Integrity/DoS | double-fire ledger | mitigate | Idempotent createTransaction (findByOrderId) + partial unique index (tenant_id, order_id) as the race-safe backstop. |
| T-81-04 | Information disclosure | cross-tenant ledger read | accept | Existing RLS on financial_transactions + FORCE RLS (V35) unchanged; findByOrderId is RLS-scoped. No new exposure. |
| T-81-05 | Repudiation | audit trail of correction | mitigate | Envers NOT disabled; _aud tables gain matching columns so revisions keep writing; migration emits a documented audit NOTICE. |

No package-manager installs in this plan → package legitimacy gate N/A.
</threat_model>

<verification>
- `./gradlew :core-java:test` — unit suite green (VatCalculatorTest, updated FinancialTransactionServiceTest, storefront tests).
- `./gradlew :core-java:integrationTest --tests "uk.jtoye.core.finance.*"` — Testcontainers green: V40 applies on fresh schema, golden-file parity, LedgerSingleEntryIntegrationTest single-entry + idempotency.
- `bash scripts/docs-freshness.sh` — exits 0 (metrics.json matches source reality; CLAUDE.md synced).
- Live COD order: `psql ... SELECT count(*) FROM financial_transactions WHERE order_id = '<id>'` returns exactly 1 with correct derived VAT.
</verification>

<success_criteria>
- BUG 1 closed: VAT derived via fraction method everywhere (entity, both JPQL aggregates, Order.calculateTotal); no VAT-on-top, no VAT-on-VAT; exact-penny tests pass.
- BUG 2 closed: no hardcoded VatRate.STANDARD at rate-bearing sites; per-product rate resolution; delivery follows predominant liability; no silent zero-rating on either order-creation path.
- BUG 3 closed: exactly ONE financial_transaction per settled order (card via PaymentService, cash via OrderService), proven by Testcontainers regression + live COD check.
- V40 applies cleanly on fresh schema; historical statements are safe no-ops on zero rows; duplicates collapsed; _aud tables compatible; Envers intact; audit NOTICE documented.
- Golden-file test passes with regenerated expected values; docs/metrics.json + CLAUDE.md synced; docs-freshness gate green.
- Four atomic commits, conventional prefixes, no Co-Authored-By trailers.
</success_criteria>

<output>
Create `.planning/quick/260708-mow-issue-81-p0-5-vat-ledger-correctness-fra/260708-mow-SUMMARY.md` when done. In the SUMMARY, explicitly record: (1) the round-down rounding decision + HMRC citation; (2) the decision-B reconciliation (historical amount_pennies and vat_rate deliberately preserved; VAT re-derived at read time; duplicates collapsed) for developer review; (3) the live COD psql proof (count==1 + correct VAT); (4) final docs/metrics.json counts.
</output>

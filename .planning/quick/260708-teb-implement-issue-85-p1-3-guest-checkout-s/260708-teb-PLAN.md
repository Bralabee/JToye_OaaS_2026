---
phase: 260708-teb
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/test/java/uk/jtoye/core/storefront/GuestCheckoutStockConvergenceIntegrationTest.java
  - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
  - core-java/src/test/java/uk/jtoye/core/order/StockDecrementLocationTest.java
  - docs/metrics.json
autonomous: true
requirements: [ISSUE-85, P1-3]

must_haves:
  truths:
    - "A guest order that is created and then vendor-CONFIRMED decrements product stock EXACTLY ONCE across guest->confirm->webhook paths"
    - "Two concurrent guest checkouts against a low-stock product complete without any thread returning a 500 (no unhandled ObjectOptimisticLockingFailureException)"
    - "The SUMMARY records whether the double-decrement was CONFIRMED or REFUTED, with the observed stock delta"
    - "Cancel-path restock remains symmetric: stock decremented once at CONFIRM is restored once on cancel; a pre-confirm cancel leaks nothing"
  artifacts:
    - path: "core-java/src/test/java/uk/jtoye/core/storefront/GuestCheckoutStockConvergenceIntegrationTest.java"
      provides: "Testcontainers integration test: guest-checkout->confirm delta + concurrent-checkout no-500"
      min_lines: 120
    - path: "core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java"
      provides: "createGuestOrder with the single-decrement convergence applied (confirmed branch)"
  key_links:
    - from: "PublicStorefrontService.createGuestOrder"
      to: "products.quantity_in_stock"
      via: "NO eager stock write (confirmed branch) — decrement deferred to CONFIRM"
      pattern: "createGuestOrder"
    - from: "OrderService.transitionOrder (CONFIRMED branch)"
      to: "StockService.decrementForOrder"
      via: "sole authoritative, optimistic-lock-retry decrement point"
      pattern: "stockService\\.decrementForOrder"
---

<objective>
Close GitHub issue #85 [P1-3]: guest-checkout stock TOCTOU + apparent double-decrement.

Guest checkout currently deducts stock TWICE — once eagerly in
`PublicStorefrontService.createGuestOrder` (a naked read-modify-write with no
`@Version` retry, which surfaces contention as a customer-facing 500) and again
when the vendor CONFIRMs the order via `OrderService.transitionOrder`'s
`stockService.decrementForOrder` call. The Stripe webhook path does no stock
reconciliation. The audit flagged this as *apparent*, not confirmed.

VERIFY FIRST. Task 1 writes a characterization integration test that empirically
confirms or refutes the double-decrement (and reproduces the TOCTOU 500) BEFORE
any production code changes. Task 2's shape depends on Task 1's recorded outcome.

Purpose: guarantee stock is decremented exactly once per order, and that
concurrent checkout retries/degrades cleanly instead of returning 500 — matching
the existing CQ-01 single-decrement-at-CONFIRM design already used by the admin
order path.
Output: a Testcontainers regression guard + a minimal, backend-only convergence
of the guest-checkout stock path onto the single CONFIRM decrement point. No
schema change.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

<!-- Verified findings (static trace against current main, 2026-07-08) -->
<!-- DECREMENT #1: PublicStorefrontService.createGuestOrder lines 448-455 —
     naked read-modify-write, no @Version retry (TOCTOU + 500). -->
<!-- DECREMENT #2: OrderService.transitionOrder CONFIRMED branch lines 346-348 —
     stockService.decrementForOrder (CQ-01 retry-safe single-decrement point). -->
<!-- Webhook PaymentService.handlePaymentIntentSucceeded lines 209-214 —
     DRAFT->PENDING, NO stock reconciliation. -->
<!-- Admin path OrderService.createOrder does NOT decrement (validates only),
     so it decrements exactly once at CONFIRM. Guest path is the anomaly. -->
<!-- RefundService touches NO stock — refund restock convergence is not affected. -->
<!-- Test profile has NO Stripe key => paymentService.isConfigured()==false =>
     createGuestOrder takes the COD branch => order goes straight to PENDING
     with NO PaymentIntent. Vendor confirmOrder then does PENDING->CONFIRMED.
     This makes the integration test self-contained (no webhook simulation). -->

@core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
@core-java/src/main/java/uk/jtoye/core/order/OrderService.java
@core-java/src/main/java/uk/jtoye/core/order/StockService.java
@core-java/src/test/java/uk/jtoye/core/order/ConcurrentStockDecrementIntegrationTest.java
@core-java/src/test/java/uk/jtoye/core/testsupport/IntegrationTestSupport.java

<interfaces>
<!-- Key contracts the executor needs — no codebase exploration required. -->

From PublicStorefrontService.java:
  @Transactional GuestOrderConfirmation createGuestOrder(String slug, GuestOrderRequest request)
  // Sets TenantContext internally (resolvePublicShopForSlug) and clears it in a finally.
  // Eager decrement block to remove (confirmed branch) is at lines 448-455.
  // GuestOrderConfirmation exposes getOrderNumber().

From OrderService.java:
  OrderDto confirmOrder(UUID orderId)  // PENDING -> CONFIRMED, triggers StockService decrement

From StockService.java:
  @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))
  @Transactional(propagation = REQUIRES_NEW)
  void decrementForOrder(List<OrderItem> items)   // sole retry-safe decrement; @Recover -> InsufficientStockException
  @Transactional(propagation = REQUIRES_NEW)
  void restoreForOrder(List<OrderItem> items)      // cancel-path restock

From GuestOrderRequest / GuestOrderItemRequest (read the DTOs under
core-java/.../storefront/dto/ to confirm setters/constructors):
  GuestOrderRequest: customerName, customerEmail, customerPhone, notes,
                     idempotencyKey, customerAllergenMask, items(List<GuestOrderItemRequest>)
  GuestOrderItemRequest: productId(UUID), quantity(int)

From IntegrationTestSupport.java:
  static void registerPostgresTestProperties(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres)
  // Use this from @DynamicPropertySource instead of hand-copying the H2-override trio.

Product entity: quantity_in_stock (Integer, NULL = unlimited), @Version version.
hasStock(int) is a pure read — safe to keep as an early UX guard.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Characterization test — VERIFY FIRST (confirm or refute double-decrement + TOCTOU 500)</name>
  <files>core-java/src/test/java/uk/jtoye/core/storefront/GuestCheckoutStockConvergenceIntegrationTest.java</files>
  <behavior>
    - Test A (delta): seed tenant + published shop (no opening_hours = always open) + product with quantity_in_stock = 10. Call publicStorefrontService.createGuestOrder(slug, request) for qty = 3 (COD path: no Stripe key in test profile, so order lands PENDING). Look up the order id by the returned order number (TenantContext set), then call orderService.confirmOrder(orderId) (PENDING -> CONFIRMED). Read back products.quantity_in_stock. ASSERT the CURRENT observed value as a characterization: on the confirmed-bug code the stock is 4 (delta = 2 x qty = double-decrement). Record the observed delta in a code comment marked "PRE-FIX CHARACTERIZATION — flips to single-decrement in Task 2".
    - Test B (TOCTOU): seed product with quantity_in_stock = 1. Fire two concurrent createGuestOrder calls (qty = 1 each) on the SAME product, gated by a CountDownLatch (mirror ConcurrentStockDecrementIntegrationTest). Collect each thread's outcome. ASSERT the CURRENT behavior: exactly one thread throws (ObjectOptimisticLockingFailureException / its wrapper) — i.e. the naked RMW produces a 500 under contention. Record this as the PRE-FIX characterization.
  </behavior>
  <action>
    Create GuestCheckoutStockConvergenceIntegrationTest as a Testcontainers integration test. Copy the container + @DynamicPropertySource wiring pattern from ConcurrentStockDecrementIntegrationTest, but delegate property registration to IntegrationTestSupport.registerPostgresTestProperties. Annotate @SpringBootTest @Testcontainers @ActiveProfiles("test") @Tag("testcontainers"). Use a DEDICATED tenant UUID (e.g. 00000000-0000-0000-0000-000000000085) to avoid slug/SKU collisions with other suites, and idempotent JdbcTemplate seed helpers (tenant, published shop with a known slug, product with SKU + quantity_in_stock + version=0) copied from the ConcurrentStockDecrement test. Honor the TenantContext ThreadLocal discipline: clear in @BeforeEach/@AfterEach and set+clear per worker thread in try/finally; guest calls start with NO upstream tenant so createGuestOrder's resolvePublicShopForSlug sets it. Autowire PublicStorefrontService, OrderService, JdbcTemplate. Do NOT change any production code in this task — this test's ONLY job is to empirically capture current behavior. Run it, read the assertion result, and record in the task notes the observed delta (Test A) and whether a thread 500'd (Test B). These recorded facts drive Task 2's branch and MUST be carried into the SUMMARY. Prerequisite: Docker daemon running (Testcontainers) and JDK 21 (JDK 25 is incompatible with Gradle 8.10).
  </action>
  <verify>
    <automated>cd core-java && ./gradlew :core-java:integrationTest --tests "uk.jtoye.core.storefront.GuestCheckoutStockConvergenceIntegrationTest"</automated>
  </verify>
  <done>Test class compiles and RUNS under the integrationTest task; the observed guest->confirm stock delta and the concurrent-checkout outcome are recorded verbatim in the task notes (to become the "CONFIRMED vs REFUTED" line in the SUMMARY). No production code changed.</done>
</task>

<task type="auto">
  <name>Task 2: Remediation — converge to a single retry-safe decrement (branch on Task 1 outcome)</name>
  <files>core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java, core-java/src/test/java/uk/jtoye/core/storefront/GuestCheckoutStockConvergenceIntegrationTest.java, core-java/src/test/java/uk/jtoye/core/order/StockDecrementLocationTest.java</files>
  <action>
    Apply the branch matching Task 1's recorded outcome.

    CONFIRMED branch (expected — double-decrement observed, delta = 2 x qty):
    Remove the eager stock-decrement block from createGuestOrder (the "Deduct stock" for-loop, current lines 448-455, that does findById -> setQuantityInStock(current - qty) -> save). Keep the read-only product.hasStock(...) availability guard in the item loop (early UX rejection, not a reservation). This makes StockService.decrementForOrder at the CONFIRMED transition the SOLE authoritative decrement — retry-safe via @Retryable, consistent with the admin OrderService.createOrder path, and restoring cancel-path restock symmetry (restore only fires for oldStatus >= CONFIRMED, which now matches where the decrement happens). Then FLIP the Task 1 characterization assertions to the post-fix invariants: Test A asserts final stock = 7 (delta = 1 x qty); Test B asserts NEITHER thread throws / returns 500 (concurrent checkout no longer writes stock, so there is no version contention during checkout). Extend StockDecrementLocationTest with an assertion that PublicStorefrontService.java no longer performs an in-place stock write in createGuestOrder (e.g. does-not-contain the setQuantityInStock decrement pattern within the guest path) so the convergence cannot silently regress. Do NOT add a Flyway migration — no schema change is required.

    REFUTED branch (only if Task 1 observed delta = 1 x qty, i.e. single decrement already):
    Do NOT change the decrement location. Instead harden ONLY the TOCTOU: replace the naked read-modify-write in createGuestOrder with a call through the existing retry-safe StockService (or otherwise route the write so ObjectOptimisticLockingFailureException retries instead of surfacing as 500). Flip only Test B's assertion to expect no 500; leave Test A asserting the single-decrement invariant. Document that the "double" was ruled out.

    Whichever branch: reference issue #85 / P1-3 in the code comment on the changed block, and ensure the change is backend-only.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew :core-java:test --tests "uk.jtoye.core.order.StockDecrementLocationTest" && ./gradlew :core-java:integrationTest --tests "uk.jtoye.core.storefront.GuestCheckoutStockConvergenceIntegrationTest"</automated>
  </verify>
  <done>Guest->confirm decrements stock exactly once (Test A green on the post-fix invariant); two concurrent guest checkouts complete with zero 500s (Test B green); StockDecrementLocationTest guards the single decrement location; no schema/migration added; changed block references issue #85.</done>
</task>

<task type="auto">
  <name>Task 3: Full integration gate + docs-freshness metrics sync</name>
  <files>docs/metrics.json</files>
  <action>
    Run the full backend unit + integration suites to prove no regression from the convergence: the existing ConcurrentStockDecrementIntegrationTest, RefundWebhookHandlingIntegrationTest, LedgerSingleEntryIntegrationTest, RoleBasedAccessIntegrationTest and GdprErasureIntegrationTest must all stay green (currently 97 integration tests). Then regenerate docs/metrics.json via scripts/docs-freshness.sh --write (the docs-freshness CI gate fails on drift) — the new test file + its @Test methods will bump java_test_files and total_logical_invocations off the 736 baseline; commit whatever the script computes, do NOT hand-edit counts. Confirm scripts/docs-freshness.sh (check mode) passes clean afterwards.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew :core-java:test :core-java:integrationTest && cd .. && ./scripts/docs-freshness.sh --write && ./scripts/docs-freshness.sh</automated>
  </verify>
  <done>Full :core-java:test and :core-java:integrationTest are green; docs/metrics.json regenerated to match the new test totals; scripts/docs-freshness.sh passes with no drift.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| public storefront -> Core (guest checkout) | Unauthenticated guest input crosses into stock-mutating writes |
| concurrent requests -> shared product row | Two guest checkouts / two CONFIRMs race the same quantity_in_stock + @Version |
| test worker threads -> TenantContext ThreadLocal | Parallel threads must each scope RLS correctly |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-teb-01 | Tampering | Stock quantity via guest checkout + CONFIRM double-write | mitigate | Converge to a single retry-safe decrement at CONFIRM (StockService optimistic-lock); characterization test guards exactly-once |
| T-teb-02 | Denial of Service | Naked read-modify-write surfaces ObjectOptimisticLockingFailureException as customer-facing 500 under contention | mitigate | Remove the un-retried checkout write; the surviving decrement path already retries (maxAttempts=3) then maps to a stable 409 (InsufficientStockException) |
| T-teb-03 | Information Disclosure | Cross-tenant stock/order read in parallel test threads | mitigate | Each worker sets+clears its own TenantContext in try/finally; RLS enforced by Testcontainers Postgres (Flyway schema is source of truth) |
| T-teb-SC | Tampering | npm/pip/cargo installs | accept | No new dependencies added — backend-only change, no package installs |
</threat_model>

<verification>
- `./gradlew :core-java:integrationTest --tests "uk.jtoye.core.storefront.GuestCheckoutStockConvergenceIntegrationTest"` green after Task 2 (both delta and concurrency assertions on post-fix invariants).
- `./gradlew :core-java:test :core-java:integrationTest` fully green (no regression across the existing 97 integration tests + unit suite).
- `./scripts/docs-freshness.sh` passes with no drift after `--write`.
- SUMMARY explicitly states CONFIRMED vs REFUTED with the observed pre-fix stock delta (per the "flagged apparent" mandate).
</verification>

<success_criteria>
- A test reproduces the current behavior and the SUMMARY documents whether the double-decrement was CONFIRMED or ruled out (with the observed delta).
- Stock is decremented exactly once per order across guest -> confirm -> webhook paths.
- Concurrent guest checkout completes without any thread returning a 500 (retries/degrades via the single retry-safe decrement point, or no longer contends during checkout).
- Cancel-path restock symmetry preserved (no stock leak on pre-confirm cancel).
- Backend-only; no Flyway migration added; docs/metrics.json in sync.
</success_criteria>

<output>
Create `.planning/quick/260708-teb-implement-issue-85-p1-3-guest-checkout-s/260708-teb-SUMMARY.md` when done.
The SUMMARY MUST contain a line: "Double-decrement: CONFIRMED|REFUTED — observed guest->confirm stock delta = N x qty (pre-fix)".
</output>

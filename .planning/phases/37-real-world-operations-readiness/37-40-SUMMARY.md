---
phase: 37-real-world-operations-readiness
plan: 40
subsystem: api
tags: [sync, edge, products, shop-scoping, per-item-results, orders, rfc7807, openapi, integrators, uxt-017, uxt-037, rwo-017, rwo-037]
status: complete

requires:
  - phase: 37-39
    provides: "ProductService create/update rules (trackInventory semantics, IGNORE-null quantityInStock), InvalidRequestFieldsException, the MockMvc + real access-gate test pattern"
  - phase: QA-council 20260902 Cluster A
    provides: "the SEC-5 per-shop gate in SyncService, API-2 SyncItem bounds, API-13 shop create through ShopService"
provides:
  - "SyncItem.shopId (required on every product item), available, quantityInStock, trackInventory"
  - "SyncItemResult {index, type, key, outcome CREATED|UPDATED|REJECTED, id, code, message} with ten stable rejection codes"
  - "BatchSyncResponse.results and a derived status SUCCESS|PARTIAL|REJECTED (processedCount kept = items written)"
  - "Sync products written through ProductService.createProduct/updateProduct (spans parsed, 37-39 stock rules), one REQUIRES_NEW transaction per item"
  - "SHOP_MISMATCH: sync never moves or overwrites a product of another shop (or a legacy shop-less one)"
  - "OrderService.createOrder refuses a shop-less product with the same non-disclosing 404 as another shop's product (dashboard, REST API and MCP create_order)"
affects: [37-41, 37-E gate, edge-go, mcp-server, dashboard-new-order-form, phase-37-uat]

actuals:
  tokens: 34895
  tasks: 3
  commits: 7
plan_head_before: a0a62a35b51e4eaf889c4755b0ccec37ee8c3b9c
plan_head_after: f2e713c4f24bcaf561190292a48b81a801c789ec

tech-stack:
  added: []
  patterns:
    - "Per-item TransactionTemplate (REQUIRES_NEW) in a non-transactional batch method: a refusal rolls back one item and is reported as a typed result; TenantSetLocalAspect pins the GUC inside each item's transaction"
    - "Batch status derived from the per-item results (BatchSyncResponse.of), never asserted by the service"
    - "Gate on the item's own shop BEFORE the SKU lookup, so an ungranted caller learns nothing about the SKU"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/sync/dto/SyncItemResult.java
    - core-java/src/test/java/uk/jtoye/core/sync/SyncBatchShopRequiredIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderShopLessProductIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-40-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-40-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-40-red-task3.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/sync/SyncService.java
    - core-java/src/main/java/uk/jtoye/core/sync/dto/SyncItem.java
    - core-java/src/main/java/uk/jtoye/core/sync/dto/BatchSyncResponse.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/test/java/uk/jtoye/core/sync/SyncBatchAuthorizationIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/sync/SyncServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/sync/SyncServiceTenantCacheScopeTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderCreateFulfilmentTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/config/ScheduledCleanupServiceIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "37-40: shopId is required on EVERY product item (create and update), not only on create: an item without a shop cannot be located safely, and requiring it always is the simpler, stricter contract"
  - "37-40: a SKU that already exists under another shop, or under no shop, is REJECTED SHOP_MISMATCH; sync never moves products between shops or adopts a legacy shop-less product (UXT-064 stays out of scope)"
  - "37-40: an unexpected exception on one item is reported as REJECTED WRITE_FAILED (logged at ERROR) instead of a 500, because earlier items are already committed and a 500 would hide which ones landed"
  - "37-40: an empty batch is SUCCESS with no results (nothing skipped or refused); a batch with no item written is REJECTED, never SUCCESS or PARTIAL"
  - "37-40: SyncItem also carries trackInventory, so sync can stop stock tracking under the same 37-39 rule as PUT"

patterns-established:
  - "Per-item outcome contract for bulk endpoints: one typed result per input item in request order, a stable code enum, a status derived from the results, HTTP 200 for partial success"

requirements-completed: [RWO-017, RWO-037]

coverage:
  - id: D1
    description: "A SHOP_MANAGER's mixed batch (shop A product, shop-less product, type widget, ungranted shop B product) answers 200 PARTIAL with CREATED, REJECTED SHOP_REQUIRED, REJECTED UNKNOWN_TYPE, REJECTED SHOP_ACCESS_DENIED; only the shop-A product is stored, with shop_id A; no tenant-wide product is created"
    requirement: "RWO-017"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/sync/SyncBatchShopRequiredIntegrationTest.java#mixedBatchReportsEveryItemAndOnlyTheShopScopedProductLands"
        status: pass
    human_judgment: false
  - id: D2
    description: "A synced product's allergen spans are byte-identical to a dashboard create of the same ingredients text, and it belongs to its shop"
    requirement: "RWO-017"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/sync/SyncBatchShopRequiredIntegrationTest.java#syncedProductHasItsShopAndTheSameSpansAsADashboardCreate"
        status: pass
    human_judgment: false
  - id: D3
    description: "An update naming a different shop is REJECTED SHOP_MISMATCH and the row is unchanged; same shop with available false and quantityInStock 3 is UPDATED and stored with allergen data unchanged"
    requirement: "RWO-017"
    verification:
      - kind: integration
        ref: "SyncBatchShopRequiredIntegrationTest#updateNamingADifferentShopIsRejectedShopMismatchAndChangesNothing, #updateInTheSameShopStoresAvailabilityAndStock; SyncBatchAuthorizationIntegrationTest#shopManagerOnShopA_namingShopBsSkuUnderShopA_isRejectedShopMismatch"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/sync/SyncServiceTest.java#testProcessBatch_SkuOfAnotherShop_isRejectedShopMismatch"
        status: pass
    human_judgment: false
  - id: D4
    description: "Batch status is the truth about its items: all written SUCCESS, none written REJECTED, otherwise PARTIAL; never SUCCESS when any item was refused"
    requirement: "RWO-037"
    verification:
      - kind: integration
        ref: "SyncBatchShopRequiredIntegrationTest#allItemsWrittenIsSuccess, #noItemWrittenIsRejected; the five single-refusal arms of SyncBatchAuthorizationIntegrationTest"
        status: pass
      - kind: unit
        ref: "SyncServiceTest#testProcessBatch_NothingWritten_isRejected, #testProcessBatch_MixedItems, #testProcessBatch_Empty_isSuccessWithNoResults"
        status: pass
    human_judgment: false
  - id: D5
    description: "Every SEC-5/API-1/API-2/API-13/M1 refusal arm of the sync authorization IT is kept (16 before, 17 after plus one SHOP_MISMATCH arm), re-expressed as per-item outcomes where the refusal moved from a batch 403 to an item REJECTED"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/sync/SyncBatchAuthorizationIntegrationTest.java (18 tests)"
        status: pass
    human_judgment: false
  - id: D6
    description: "A vendor order (dashboard, REST API, MCP create_order) with a shop-less product is the same non-disclosing 404 as another shop's product and writes nothing; a same-shop product is still 201; a shop-less product stays listable"
    requirement: "RWO-017"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/order/OrderShopLessProductIntegrationTest.java (4 tests)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java#testCreateOrder_ShopLessProduct_isNotFound"
        status: pass
    human_judgment: false
  - id: D7
    description: "The committed OpenAPI contract carries the new SyncItem fields, SyncItemResult and BatchSyncResponse.results/status enum"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java#apiDocsMatchCommittedSnapshot"
        status: pass
    human_judgment: false
  - id: D8
    description: "The running compose stack serves the new contract (live sync batch and vendor order against the rebuilt images)"
    verification: []
    human_judgment: true
    rationale: "Nothing here was proven against the running stack: it still runs the 37-C images cut at d060d0af and is BEHIND the branch. The 37-E gate must rebuild every image and replay the mixed batch and a shop-less vendor order live."

duration: 67min
completed: 2026-10-13
---

# Phase 37 Plan 40: Shop-scoped sync with per-item outcomes Summary

**`POST /sync/batch` now requires a `shopId` on every product item, writes products through `ProductService` (so allergen spans are parsed and the 37-39 stock rules apply), runs each item in its own transaction, and answers one `CREATED | UPDATED | REJECTED` result per item with a stable code and a derived `SUCCESS | PARTIAL | REJECTED` status. `OrderService.createOrder` refuses a shop-less product with the same 404 as another shop's product, so the dashboard, REST API and MCP refuse what the storefront refuses.**

## Performance

- **Duration:** 67 min
- **Started:** 2026-10-12T23:06:25Z
- **Completed:** 2026-10-13T00:13:01Z
- **Tasks:** 3 (3 TDD cycles: RED then GREEN each; Task 1 a tracer)
- **Files modified:** 23 (5 main, 9 test, 3 RED records, OpenAPI snapshot, metrics, README, CLAUDE.md, AGENTS.md, deferred-items)

## Accomplishments

- **UXT-017 repro fails before and passes after.** Before (realm-admin probe on the unfixed tree): `{status: SUCCESS, processedCount: 2}`. The shop-A item was stored with `shop_id` NULL and `allergen_spans` NULL, the shop-less item was stored, and the widget was skipped silently. A scoped manager's mixed batch got a 403 for all four items. After: 200 `PARTIAL`. Only the shop-A product is written, in shop A, with spans identical to a dashboard create.
- **UXT-037 repro fails before and passes after.** Every item gets a result in request order. A batch is never `SUCCESS` when any item was refused, and is `REJECTED` when nothing was written.
- **Sync goes through the dashboard's write path.** The SKU lookup is `(tenant, sku)`, with an explicit tenant predicate. The gate on the item's shop runs before the lookup. An existing SKU in another shop, or in none, is `SHOP_MISMATCH`. A create missing a field `CreateProductRequest` requires is `INVALID_FIELDS`, naming the fields. `trackInventory` contradictions are also `INVALID_FIELDS` (the 37-39 rule).
- **One transaction per item.** `processBatch` is not transactional; a `TransactionTemplate` (REQUIRES_NEW) wraps each shop or product item. A refusal rolls back that item alone, and the items before it stay committed. Cache evictions for updates stay `afterCommit` of the item's own transaction (#483).
- **No writer can order a shop-less product.** The M2 null arm in `createOrder` is gone. Existing shop-less products are not touched and stay listable.
- **Contract published.** The OpenAPI snapshot diff is additive only. Test counts went from 5760 to 5776.

## Task Commits

1. **Task 1 (tracer): a synced product lands in its shop; the shop-less one is refused per item.** `8409f68e` (test, RED), `667f197a` (feat, GREEN). The tracer gate re-ran verify on the committed tree: 19/0.
2. **Task 2: SHOP_MISMATCH, availability and stock through sync, honest status.** `92bb4b83` (test, RED), `f9956a36` (feat, GREEN).
3. **Task 3: no writer can order a shop-less product; contract published.** `4a36808c` (test, RED), `cfde52e0` (feat, GREEN + OpenAPI + four fixtures).
4. **Metrics:** `f2e713c4` (docs: 5760 → 5776).

**Plan metadata:** this SUMMARY commit.

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | Fail-direction evidence |
|------|------------------------|-------|-------------------------|
| 1 | `8409f68e`, `evidence/37-40-red-task1.json` RED_EVIDENCE_OK (JUnit: 2 tests, 2 failed) | `667f197a`: sync IT 19/0, sync unit 22/0 | B1 below |
| 2 | `92bb4b83`, `evidence/37-40-red-task2.json` RED_EVIDENCE_OK (6 tests, 2 failed; unit 1 failed) | `f9956a36`: sync IT 24/0, sync unit 25/0 | B2 below (the plan's arm) |
| 3 | `4a36808c`, `evidence/37-40-red-task3.json` RED_EVIDENCE_OK (4 tests, 2 failed; unit 1 failed) | `cfde52e0`: order unit 193/0, order IT + OpenAPI + cleanup 83/0 | B3 below |

**RED semantic assessments.**
- **Task 1.** The target failed at its first planned assertion: status 200 was expected, and the whole batch got 403, because a refused item refused everything. The sibling arm got 200 `SUCCESS`, then failed at `results[0].outcome` because no results array existed. A temporary admin probe (removed; the file was restored by sha256 to `340782e2…`) recorded the data defect: shop_id NULL and spans NULL.
- **Task 2.** The target got `code WRITE_CONFLICT` where `SHOP_MISMATCH` was expected. Task 1's `(tenant, sku, shop)` lookup missed, and the create collided with `idx_products_tenant_sku`. `noItemWrittenIsRejected` and the five single-refusal arms got `PARTIAL` where `REJECTED` was expected. The same-shop stock arm and the all-valid arm **passed** on the Task 1 tree, because Task 1's action already maps `available`/`quantityInStock`. They are regression guards, not RED evidence.
- **Task 3.** The vendor route got 201, with the shop-less product on another shop's order. The same-shop control (201) and the listable arm (200) passed, so the harness can pass.

## Verification (both directions)

| Check | Fail direction (real output) | Pass direction (real output) |
|-------|------------------------------|------------------------------|
| **B1:** SHOP_REQUIRED check disabled | 19 tests, 3 failed. Mixed batch: `results[1].code` SHOP_ACCESS_DENIED instead of SHOP_REQUIRED. Manager mint: SHOP_ACCESS_DENIED. Admin mint: WRITE_FAILED (Spring Data's null guard on the finder, reported by the catch-all with an ERROR log) | restored by sha256; closing run 19/0 |
| **B2** (plan's arm): `status = written > 0 ? SUCCESS : REJECTED` | IT: mixed batch `$.status` PARTIAL expected, SUCCESS actual. Unit: MixedItems (PARTIAL vs SUCCESS) and Empty (SUCCESS vs REJECTED) | restored by sha256 |
| **B3:** the M2 null arm put back in `createOrder` | IT: 4 tests, 2 failed (shop-less 404 arm and identical-404 arm got 201). Unit: shop-less arm failed | restored by sha256; `core-java == HEAD` |
| Auth IT refusal arms kept | method-name diff vs a0a62a35: 16 → 17 at Task 1 (one renamed `…TypedShop403` → `…TypedShopRefusal`, one added), 18 after Task 2; none removed | 18/0 |
| OpenApiSnapshotTest | pre-plan snapshot restored temporarily: FAILED ("no longer matches the reviewed snapshot") | 1/0 on the committed snapshot |
| docs-freshness.sh / check-doc-metrics.sh | rc 1 (2680/386/5760 vs computed 2696/388/5776) / rc 1 (10 stale claims) | rc 0 / rc 0 (37 claims match) |
| Root pin guard | from /tmp: FATAL, stage actual-capture, rc 1 | PIN OK before every commit |

**Suites:**
- **core-java unit, FULL, on HEAD f2e713c4:** 1787 tests, 0 failures, 0 errors, 1 skipped, 207 classes. Before: 1783.
- **core-java FULL integrationTest** on the Task 3 tree before the cleanup fixture fix: 1178 tests, 1 failure, 1 skipped, 206 classes, 30 min 14 s. Before: 1166. The one failure was `ScheduledCleanupServiceIntegrationTest` (Deviation 3). After the fix, that class plus the order IT and OpenAPI ran 83/0.
- **Closing run on HEAD:** sync, order, OpenAPI and cleanup integration classes 107/0 (17 classes).
- **Not run:** jest, Playwright, go, vitest. No frontend, edge or MCP file changed.

## Files Created/Modified

- `sync/SyncService.java`: per-item transactions, typed results, `shopId` required, gate-then-lookup, `SHOP_MISMATCH`, writes through `ProductService`.
- `sync/dto/SyncItemResult.java` (new): the outcome DTO, with the `Outcome` and `Code` enums.
- `sync/dto/BatchSyncResponse.java`: `results`, and the status derived by `of(...)`.
- `sync/dto/SyncItem.java`: `shopId`, `available`, `quantityInStock` (`@Min 0`), `trackInventory`; Javadoc updated.
- `product/ProductRepository.java`: `findByTenantIdAndSku`.
- `order/OrderService.java`: the shop-less refusal and its comment.
- Tests:
  - New: `SyncBatchShopRequiredIntegrationTest` (6) and `OrderShopLessProductIntegrationTest` (4).
  - Rewritten: `SyncServiceTest` (12) and `SyncBatchAuthorizationIntegrationTest` (18).
  - Adapted: `SyncServiceTenantCacheScopeTest`.
  - Fixtures given a shop: `OrderServiceTest`, `OrderCreateFulfilmentTest`, `OrderControllerIntegrationTest`, `ScheduledCleanupServiceIntegrationTest`.
- `docs/api/openapi-snapshot.json`, `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`, `deferred-items.md`.

## Decisions Made

- **`shopId` is required on every product item, updates included.** The plan says "required to create; must match to update". An update without a shop has nothing to match against, so it is refused too.
- **`SHOP_MISMATCH` covers legacy shop-less SKUs as well.** Adopting an orphan into a shop is UXT-064, which is out of scope here. The message tells the integrator to assign a shop in the dashboard.
- **The gate runs on the item's shop before the lookup.** A manager without a grant learns nothing about the SKU. A manager naming another shop's SKU under their own shop gets `SHOP_MISMATCH`, whose message does not name the other shop; the old batch 403 carried shop B's id.
- **Unexpected failures are reported, not thrown.** They come back as `WRITE_FAILED` with a generic message and an ERROR log line; `DataIntegrityViolationException` is `WRITE_CONFLICT`. A 500 after earlier items have committed would hide which items landed.
- **Partial success is 200** (the planner's decision). Request-level bean validation still refuses the whole batch with a 400 before any item runs, so the API-2 arms are unchanged.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] SyncServiceTest and SyncServiceTenantCacheScopeTest changed in Task 1, not Task 2**
- **Found during:** Task 1 GREEN
- **Issue:** `SyncService` took two new collaborators (`ProductService`, `PlatformTransactionManager`). `integrationTest` compiles every test source, so both unit classes had to compile in Task 1. The cache-scope test's bare Spring context also needed a transaction-manager bean.
- **Fix:** Both classes were adapted in `667f197a`. A mock `PlatformTransactionManager` was added to the cache test (no synchronization, so `evictEntityAfterCommit` evicts at once), and `ProductService` became a `@MockitoBean`. Task 2 added the status and mismatch unit arms.
- **Committed in:** 667f197a, f9956a36

**2. [Rule 2 - Missing critical] `trackInventory` added to SyncItem; create-required fields checked per item**
- **Found during:** Task 1
- **Issue:** The plan lists `shopId`, `available` and `quantityInStock`. Without `trackInventory`, sync could start tracking but could never stop it under the 37-39 rule. A create missing `ingredientsText` would also have died on `products.ingredients_text NOT NULL` (a whole-item 500-class failure), and a missing `title` used to 400 the whole batch.
- **Fix:** Added `trackInventory`. A create missing title, ingredientsText, allergenMask or pricePennies is `REJECTED INVALID_FIELDS`, using `CreateProductRequest`'s own messages.
- **Committed in:** 667f197a

**3. [Rule 1 - Bug] Four fixtures ordered shop-less products**
- **Found during:** Task 3 (order suites, then the FULL integration run)
- **Issue:** `OrderServiceTest` (the setUp product and `product2` in the multiple-items arm), `OrderCreateFulfilmentTest` (8 arms), `OrderControllerIntegrationTest` (7 arms) and `ScheduledCleanupServiceIntegrationTest` (1 arm) all created a product with no shop and ordered it.
- **Fix:** Each product is given the ordered shop. The old "tenant-wide product is still accepted" unit arm now asserts the refusal.
- **Committed in:** cfde52e0

**4. [Rule 1 - Bug] My own control assertion was wrong**
- **Found during:** Task 1 GREEN
- **Issue:** The spans arm's control expected `allergen_spans` to contain the words "milk" and "egg". The column stores offset pairs.
- **Fix:** The control now asserts two spans. This does not affect the RED evidence: that run failed before reaching the control.
- **Committed in:** 667f197a

**5. [Rule 3 - Blocking] The docs-freshness loop was red on the new arms**
- **Found during:** overall verification
- **Fix:** `--write` plus the prose counts, as in 37-39.
- **Committed in:** f2e713c4

**6. [File list] Two new test files and four fixture files outside `files_modified`**
- `OrderShopLessProductIntegrationTest` was added because the plan's behaviour is a vendor `POST /api/v1/orders` 404, and only a filter-chain IT can show that 201 → 404.
- The fixture files are those listed in Deviation 3.

---

**Total deviations:** 5 auto-fixed (2 Rule 1, 1 Rule 2, 2 Rule 3) plus 1 file-list note.
**Impact on plan:** All of them were needed for correctness or to make the suites compile and pass. No new endpoint, no migration, no frontend, edge or MCP change.

## Issues Encountered

- **Python hook.** A stray `/usr/bin/python3` in one compound command was refused by `block-base-python`, and the whole command did not run. It was re-issued with node only. Nothing was half-applied: the guard blocks before execution.
- **A finder parameter cannot be null** (Spring Data's null guard). This only showed up under break arm B1. On the real code the `shopId == null` check returns first.

## Known Stubs

None.

## Threat Flags

None beyond the register:
- **T-37-93 (mitigated):** `shopId` is required, `createOrder` refuses shop-less products, proven by IT.
- **T-37-94 (mitigated):** the per-item `require(shopId, SHOP_MANAGER)` runs before the lookup; an ungranted item is `REJECTED SHOP_ACCESS_DENIED`, proven by IT.
- **T-37-95 (mitigated):** per-item results, a derived status, and break arm B2.
- **T-37-SC:** no package installed.
- Rejection messages carry no exception internals: `WRITE_FAILED` and `WRITE_CONFLICT` use fixed text.

## User Setup Required

None. No external service configuration is required.

## Next Phase Readiness

- **For the orchestrator:**
  - RWO-017 and RWO-037 can be ticked (core side).
  - The compose runtime is still the 37-C build cut at d060d0af and is BEHIND the branch. A live sync batch or a live `check-openapi-snapshot-fresh.sh` will show the OLD contract until the 37-E gate rebuilds.
  - Nothing here was proven against the running stack (coverage D8).
- **37-41 (edge pass-through):**
  - `edge-go/cmd/edge/types.go` `SyncBatchResponse` still documents `{status, processedCount}`. It needs `results` and the three statuses.
  - The edge forwards items verbatim, so the new item fields already pass through.
- **Behaviour change integrators will see:**
  - A refused item is now 200 with `REJECTED` and a code. It used to be a whole-batch 403 (shop gate) or 400 (missing title on create).
  - A product item without `shopId` is refused.
  - An admin can no longer mint a tenant-wide product through sync.
- **Open follow-up (recorded in `deferred-items.md`):** the dashboard's new-order form lists every tenant product whatever shop is selected. A vendor can pick a shop-less product, or another shop's product, and get "not found". The 37-E gate's live run should place a dashboard order.
- Ready for 37-41.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*

## Self-Check: PASSED

- 7/7 key files FOUND on disk (SyncItemResult, the two new IT classes, three RED records, this SUMMARY).
- 8/8 commits FOUND as ancestors of HEAD (8409f68e 667f197a 92bb4b83 f9956a36 4a36808c cfde52e0 f2e713c4 86f54a87).
- `gsd_run check evaluation-scope --plan 37-40 --commits-only`: status resolved, source plan-subjects, commits on this branch.
- Must-have artifacts: SyncItemResult contains REJECTED; SyncBatchShopRequiredIntegrationTest contains shopId; SyncService links to productService.
- Acceptance criteria re-run: Task 1 RED then GREEN with the auth IT's refusal arms kept (16 → 18, none removed); Task 2 break arm B2 reds the PARTIAL arm; Task 3 RED (201) then GREEN (404), B3 reds it again.

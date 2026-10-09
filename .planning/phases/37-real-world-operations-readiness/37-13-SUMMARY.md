---
phase: 37-real-world-operations-readiness
plan: 13
subsystem: api
tags: [catalogue, csv-import, copy-to-shop, allergens, media, shop-access, spring-boot]

requires:
  - phase: 37-11
    provides: "OpenAPI snapshot state this plan regenerates on top of; ShopAccessService gate shapes (require / requireGroupAdmin, FC-1 non-disclosing 404)"
  - phase: 24
    provides: "copy-on-write media_asset + product_media join with ref-counted delete (V53)"
  - phase: 23
    provides: "ShopAccessService.require(shopId, SHOP_MANAGER), shop_staff grants, strict scoping"
provides:
  - "POST /api/v1/products/bulk/csv?shopId= (SHOP_MANAGER, gated before any row): rows without shop_id land in that shop; neither -> per-row 'shop required'; a row naming an unmanaged shop -> per-row error, the rest still import"
  - "BulkImportService.importFromCsv(file, defaultShopId) (the single-argument form is gone)"
  - "ShopAccessService.permits(shopId, minRole): the require() decision as a boolean, safe to call inside a caller's transaction"
  - "POST /api/v1/products/copy {sourceShopId, targetShopId, productIds?} -> CopyProductsResult {created, skipped, failed, items[{sourceProductId, outcome CREATED|SKIPPED|FAILED, newProductId, reason}]}"
  - "ProductService.copyProducts: SHOP_MANAGER on both shops, target shop row locked, each copy through createProduct, allergen data verbatim, photos as new product_media links to the same assets"
  - "Flat image objects (image_url, additional_image_urls) are deleted only when no other product of the tenant still references the URL"
  - "ProductRepository: findByTenantIdAndShopIdOrderByDisplayOrderAscCreatedAtAsc, existsByTenantIdAndSku, countOtherProductsReferencingImage; ShopRepository.lockByIdAndTenantId (PESSIMISTIC_WRITE)"
affects: [37-14, 37-15, dashboard-products, product-import, media-delete]

actuals:
  tokens: 22265
  tasks: 2
  commits: 4
plan_head_before: 504b0db337a8b7691c16e0277cf46f146e9b72ad
plan_head_after: 58a41a6e3576ba91109455f07d6a7235d38f4216

tech-stack:
  added: []
  patterns:
    - "A refusal reported as data inside a transaction uses a non-throwing gate (permits), never a caught exception from a @Transactional bean"
    - "Copy-on-write sharing has a reference count on BOTH image stores: product_media links for assets, an other-products URL count for the flat columns"
    - "A copy is idempotent by a deterministic derived key (copy SKU) plus a title match, read after a lock on the target row"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/product/dto/CopyProductsRequest.java
    - core-java/src/main/java/uk/jtoye/core/product/dto/CopyProductsResult.java
    - core-java/src/test/java/uk/jtoye/core/product/BulkImportShopDefaultIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductCopyIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductController.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/test/java/uk/jtoye/core/product/BulkImportServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/product/BulkImportTenantCacheScopeIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductServiceTest.java
    - docs/api/openapi-snapshot.json

key-decisions:
  - "37-13: CSV import puts a row in its own shop_id, else in ?shopId (SHOP_MANAGER, gated before any row is read); with neither the row is the per-row error 'shop required', for a Group admin too"
  - "37-13: a CSV row naming a shop the caller does not manage is a per-row error decided by ShopAccessService.permits; catching require() would mark the import's transaction rollback-only (measured: HTTP 500)"
  - "37-13: a copy's SKU is {source SKU}-{first 8 of the target shop id} because SKUs are tenant-unique (V3); SKIPPED when the target has that copy SKU or the same title ignoring case, under a PESSIMISTIC_WRITE lock on the target shop row"
  - "37-13: copy carries allergen data verbatim, the stored emphasis spans (NULL included) overwriting createProduct's re-parse; photos are new product_media links to the same media_asset rows; a flat image object is deleted only when no other product references its URL"
  - "37-13: copy carries quantity_in_stock as stored (plan text); flagged for owner review at the phase PR"

patterns-established:
  - "Per-row authorisation inside a bulk write: ShopAccessService.permits, never try/catch around require"

requirements-completed: [RWO-026]

coverage:
  - id: D1
    description: "UXT-026 import: ?shopId=A puts every row without its own shop_id in shop A and A's public storefront lists them; the manager of A can import into A; a row's own managed shop_id wins over the default"
    requirement: "RWO-026"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/product/BulkImportShopDefaultIntegrationTest.java#groupAdminImportIntoShopA_showsOnShopAsStorefront, #managerOfA_importsIntoShopA, #rowShopIdWinsOverTheDefault"
        status: pass
    human_judgment: false
  - id: D2
    description: "No shop anywhere is three per-row 'shop required' errors and nothing written (Group admin and manager); ?shopId=B by the manager of A only is 403 shop-access-denied with nothing written; a row naming an unmanaged shop is a per-row error while the other rows import"
    requirement: "RWO-026"
    verification:
      - kind: integration
        ref: "BulkImportShopDefaultIntegrationTest#noShopAnywhere_isAPerRowErrorAndCreatesNothing, #noShopAnywhere_asManager_isAPerRowError, #managerOfA_importingIntoShopB_isRefused, #rowNamingAForeignShop_isAPerRowError"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/product/BulkImportServiceTest.java (20 tests, 5 new for the target-shop rule)"
        status: pass
      - kind: integration
        ref: "uk.jtoye.core.product.BulkImportTenantCacheScopeIntegrationTest (5/0, now imports into a shop)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Copy A->B: each copy's allergen mask, stored spans (a non-derived value and a NULL), may-contain mask, ingredients, price, VAT rate, label fields, stock and image URLs equal the source by SQL; copies show on B's storefront; the whole menu copies when productIds is omitted"
    requirement: "RWO-026"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/product/ProductCopyIntegrationTest.java#copyAtoB_carriesEveryAllergenFactVerbatim, #copiesShowOnTheTargetStorefront, #noProductIds_copiesTheWholeMenu"
        status: pass
    human_judgment: false
  - id: D4
    description: "Photos are shared copy-on-write (new product_media links to the same media_asset rows, no asset duplicated) and removing the copy's photo keeps the source's links, asset and flat object"
    requirement: "RWO-026"
    verification:
      - kind: integration
        ref: "ProductCopyIntegrationTest#copiedMedia_linksTheSameAssets, #editingTheCopy_neverChangesTheSource"
        status: pass
      - kind: integration
        ref: "uk.jtoye.core.product.ProductImageDeleteIntegrationTest 3/0, ProductImageCrossTenantBlobDeleteIntegrationTest 3/0 (the delete guard keeps single-product deletes working)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Idempotent copy: a repeat creates 0 and skips 2; a same-title product in the target is skipped; two concurrent copies create exactly one copy of each and both return 200 (3 rounds)"
    requirement: "RWO-026"
    verification:
      - kind: integration
        ref: "ProductCopyIntegrationTest#repeatingTheCopy_createsNothing, #targetAlreadyHasTheTitle_isSkipped, #concurrentCopies_createExactlyOneCopyOfEach"
        status: pass
    human_judgment: false
  - id: D6
    description: "Copy gates: unmanaged target or source is 403 shop-access-denied with nothing written; source = target is 400; a requested product not in the source shop is a FAILED item while the rest copy"
    requirement: "RWO-026"
    verification:
      - kind: integration
        ref: "ProductCopyIntegrationTest#copyIntoAnUnmanagedShop_isRefused, #copyFromAnUnmanagedShop_isRefused, #sameSourceAndTarget_isABadRequest, #productFromAnotherShop_isAFailedItem"
        status: pass
    human_judgment: false
  - id: D7
    description: "Published contract: OpenAPI snapshot regenerated (additive: the shopId parameter and 401/403 on bulk/csv, the copy operation and its two schemas) and matching"
    verification:
      - kind: integration
        ref: "uk.jtoye.core.integration.OpenApiSnapshotTest"
        status: pass
    human_judgment: false
  - id: D8
    description: "The running compose stack serves the new parameter and endpoint, and the dashboard import and copy dialog use them"
    verification: []
    human_judgment: true
    rationale: "Runtime parity needs the 37-15 rebuild, and the dashboard side (import target shop, copy dialog) is 37-14. Until 37-14 ships, a dashboard import posts no shopId and every row reports 'shop required' (WINDOWS.md #27)."

duration: 50min
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 13: CSV import into the chosen shop and copy-to-shop Summary

**A CSV menu now lands in the shop the vendor imported into, or the row says "shop required". It is never saved shop-less and invisible on the storefront. A manager can copy a menu to another shop through `createProduct`. Every allergen fact is carried verbatim, photos are shared copy-on-write, and the copy is idempotent under a lock on the target shop.**

## Performance

- **Duration:** 50 min (about 27 of them the full suite)
- **Started:** 2026-10-09T00:33:00Z
- **Completed:** 2026-10-09T01:23:18Z
- **Tasks:** 2 (Task 1 tracer, Task 2 auto, both tdd)
- **Files modified:** 16 source/test/doc files, plus 2 RED evidence records

## Accomplishments

- **UXT-026's import repro now fails before the fix and passes after it.** A Group admin imported with `?shopId=A`. Before, the rows were saved with `shop_id NULL` and shop A's storefront listed none of them. Now all three are in A by SQL, and the public `/shops/{slug}/products` endpoint lists them.
- **A shop-less product can no longer be created through CSV.** A row with no `shop_id` cell and no `shopId` parameter is the per-row error "shop required", for a Group admin too.
- **A manager can import into their own shop.** Before, a manager's import 403'd as a whole batch because no row named a shop.
- **UXT-026's copy repro (no way to copy a menu) is closed by `POST /api/v1/products/copy`.** A shop manager can now copy a menu between shops without retyping allergens. The fidelity test reads every carried column from both rows by SQL. It includes a stored span value that differs from what the text would parse to, and a NULL span value, so a re-derived copy would fail it.

## Task Commits

1. **Task 1: CSV rows land in the chosen shop and show on its storefront** (tracer, tdd)
   - `6a27a7e7` test(37-13): add failing arms for importing a CSV menu into the chosen shop (UXT-026) (RED)
   - `fe4725f1` feat(37-13): import a CSV menu into the chosen shop, never as shop-less products (UXT-026) (GREEN)
   - Tracer gate: `human_verify_mode` is end-of-phase and the verify is automated-only. The verify was re-run fresh after GREEN: BulkImportShopDefault 7/0, BulkImportTenantCacheScope 5/0, BulkImportServiceTest 20/0. Expansion continued.
2. **Task 2: Copy products to another shop through the single domain create path** (tdd)
   - `90bd5685` test(37-13): add failing arms for copying a menu to another shop with its allergens intact (UXT-026) (RED)
   - `58a41a6e` feat(37-13): copy products to another shop with every allergen fact intact (UXT-026) (GREEN)

No REFACTOR commits were needed. All 4 commits in `504b0db3..58a41a6e` belong to this plan.

**Plan metadata:** the docs(37-13) commit that adds this file.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `6a27a7e7` (`evidence/37-13-red-csv-shop-default.json`, RED_EVIDENCE_OK) | `fe4725f1` | not needed |
| 2 | `90bd5685` (`evidence/37-13-red-copy-to-shop.json`, RED_EVIDENCE_OK) | `58a41a6e` | not needed |

- **Task 1 RED.** Target: "UXT-026: a Group admin importing into shop A creates all three rows in A, and A's storefront shows them".
  - Actual: `[every imported row is in shop A, by SQL] expected only shop A but was [null, null, null]`.
  - Six of seven arms were red:
    - the Group admin's rows were shop-less;
    - with no shop anywhere, the import created 3 shop-less rows instead of 3 errors;
    - the four manager arms got a whole-batch 403.
  - The seventh arm (`?shopId=B` refused) passed before the fix for the wrong reason: the parameter was ignored, and a non-admin row without `shop_id` already 403'd the batch.
  - **Semantic assessment:** the target ran and failed on the planned assertion for the intended reason. It was not a fixture fault: the same run returned HTTP 200 with three created rows.
  - The unit-test half (`BulkImportServiceTest`, new signature) moved with GREEN. Its new arms call `importFromCsv(file, shopId)`, which would not compile on the RED tree, so a compile failure there would have been INVALID_RED.
- **Task 2 RED.** Target: "UXT-026: copying two products A->B creates two copies in B whose allergen mask, spans, may-contain mask, ingredients, price and VAT rate equal the source by SQL".
  - Actual: `HTTP 405 method-not-allowed` (expected 200). With no copy route, POST falls through to `/products/{id}`.
  - All 12 arms were red on that missing route.
  - **Semantic assessment:** this is the intended failure. `setUp` seeded three shops, grants, three products (JSONB spans, TEXT[] galleries), two assets and two links without error in every test. The failures are assertion failures, not errors.

## Acceptance criteria, both directions

| Criterion | Pass direction | Fail direction |
|-----------|----------------|----------------|
| T1: RED (storefront empty after import) recorded, then GREEN | GREEN 7/0 at `fe4725f1`; closing run 7/0 | RED 6/7 at `6a27a7e7`, shop_id `[null, null, null]` |
| T1: the shop-required rule is load-bearing | both "no shop anywhere" arms green | Break arm B (guard disabled): both arms red, HTTP 200 with 3 shop-less rows created |
| T1: per-row refusal must not poison the transaction (design choice for `permits`) | foreign-row arm green: 1 created in A, 1 per-row error | Break arm A (`try { require } catch`): that arm red, **HTTP 500** (UnexpectedRollbackException), so a caught exception from the transactional gate cannot be used |
| T2: each arm RED then GREEN; fidelity read from both rows by SQL | GREEN 12/0 at `58a41a6e`; closing run 12/0 | RED 12/12 at `90bd5685` |
| T2: drop mayContainMask from the copy request -> the fidelity arm reds (the plan's named break arm) | fidelity arm green | Break run 1: fidelity arm red, "every carried column of the copy equals the source, by SQL" |
| T2: spans carried verbatim, never derived | copy spans `[{"end": 5, "start": 0}]` equal the source's | Break run 2 (no span overwrite): fidelity arm red, the copy carried `[{"end": 10, ...}]` (the re-parse of `**milk**`) |
| T2: target lock makes concurrent copies safe | 3 rounds, created 2 + skipped 2, both 200 | Break run 1 (lock removed): concurrent arm red in round 1, `HTTP 409 duplicate "Product SKU already exists for this tenant"` |
| T2: removing the copy's photo never deletes the source's object | edit-isolation arm green; `delete(sharedUrl)` never invoked | Break run 1 (unguarded flat delete): arm red, `NeverWantedButInvoked storageService.delete("https://blob.example/…/suya.webp")` |
| T2: photos shared via new links to the same assets | media arm green, links `[primary|true|0, gallery|false|1]` | Break run 2 (no links): media arm red, `but was: []` |
| OpenAPI snapshot matches the source | `OpenApiSnapshotTest` 1/0 | snapshot reverted to the `504b0db3` version: red, "no longer matches the reviewed snapshot" |
| Full-suite aggregator can fail | the totals below, rc 0 | fed the RED copy report: `failures=12` rc 1; an empty directory: `VOID` rc 2 |

The break arms were bracketed: clean, then arms, then clean, with the restore target committed first.
- **Task 1:** `BulkImportService.java` hash `203ecb333f3fa1ca` before, armed with 2 arms, restored to `203ecb333f3fa1ca`. 0 `BREAK` tokens remained, and the closing run went 7/0. Exactly the 3 predicted arms went red.
- **Task 2:** `ProductService.java` hash `433dd5d377c96c07`.
  - Run 1 (arms 1, 3, 4) gave exactly the 3 predicted reds.
  - The file was restored to `433dd5d377c96c07`.
  - Run 2 (arms 2, 5) gave exactly the 2 predicted reds.
  - It was restored to `433dd5d377c96c07` again, 0 tokens remained, and the closing run went 12/0 plus OpenAPI 1/0.

## Verification

- Plan `<verification>` 1: BulkImportShopDefaultIntegrationTest (7/0) and ProductCopyIntegrationTest (12/0) went RED, then GREEN. BulkImportServiceTest is 20/0.
- Plan `<verification>` 2: OpenApiSnapshotTest 1/0.
- Neighbour sweep after Task 2: every `uk.jtoye.core.product.*IntegrationTest` was green. That covers ProductImageDelete 3/0, ProductImageCrossTenantBlobDelete 3/0, ProductMayContain 7/0, ProductPartialUpdate 5/0, ProductRepositoryScoping 3/0, ProductSaveAllergenWarning 4/0, ProductSearchFts 17/0 and BulkImportTenantCacheScope 5/0. The `uk.jtoye.core.product.*` unit tests were green too, with ProductServiceTest at 18/0.
- **Full suites** were run once after both tasks, with every report fresh (0 stale of 188 + 188):
  - `:core-java:test`: **1621 tests, 0 failures, 0 errors, 1 skipped**.
  - `:core-java:integrationTest`: **1006 tests, 0 failures, 0 errors, 1 skipped**.
  - Against the 37-11 baseline (1616 + 987), that is +5 unit tests (the new BulkImportServiceTest arms) and +19 integration tests (7 + 12). The deltas reconcile exactly.
- Success criterion: UXT-026's repro fails before and passes after.
  - Before: importing with a shop chosen hid everything on the storefront, and there was no copy.
  - After: the import is visible on A's storefront, and the copy shows on B's storefront.

## Decisions Made

- **Target-shop rule.** A row goes to its own `shop_id`, otherwise to `?shopId`. With neither, it gets the per-row error "shop required", and this applies to a Group admin too. The default shop is gated by `require` before any row is read, so a refused default writes nothing. I put the gate in the service rather than the controller, so every caller of `importFromCsv` gets it (theme 6).
- **`ShopAccessService.permits`.** It is a new, additive method. A per-row refusal must be data, not an abort. But `ShopAccessService` is `@Transactional`, so an exception from `require` through the proxy marks the caller's transaction rollback-only. Break arm A measured exactly that: HTTP 500. `permits` runs the same ladder as a self-invocation and returns a boolean. A foreign-tenant shop gets the same message as an unmanaged one, so the error is non-disclosing.
- **Copy SKU.** It is `{source SKU}-{first 8 characters of the target shop id}`, trimmed to 100 characters. SKUs are unique per tenant (`idx_products_tenant_sku`, V3), so the plan's "same SKU exists in the target shop" could never match. A copy keeping its source SKU would 409. The derived SKU is deterministic, so a repeat finds the earlier copy. A copy SKU already used elsewhere in the tenant is reported as a FAILED item before any write.
- **Skip rule.** A product is skipped when the target has the copy SKU, or a product with the same title ignoring case. Because `products.sku` is NOT NULL, the plan's "title only when there is no SKU" would never fire. Matching the title always is what the 37-14 toast copy "already has them" means.
- **Verbatim fields.** The create request carries everything it can. After `createProduct`, the stored spans (NULL included) and the gallery URLs are written over what create derived or ignored.
- **Photos.** The copy gets new `product_media` links to the same assets. The flat URLs are copied too, because the storefront still reads `image_url` (D-03a dual-read). Without them the copy would show no photo to customers.
- **Lock.** `ShopRepository.lockByIdAndTenantId` uses `@Lock(PESSIMISTIC_WRITE)` with a tenant predicate. It is taken after both gates and before the target's existing products are read.
- **No Idempotency-Key and no MCP tool**, per the plan's probe notes: the operation is provably idempotent, and bulk catalogue work is a dashboard task.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The plan's SKU-based skip rule could never match**
- **Found during:** Task 2 design.
- **Issue:** SKUs are unique per tenant (V3) and NOT NULL. A product in shop B can never carry a SKU from shop A, so a copy with the source SKU would 409, and "without a SKU" never happens.
- **Fix:** The copy SKU is derived deterministically. A product is skipped on the copy SKU or a case-insensitive title match. An in-tenant collision is a FAILED item.
- **Verification:** the repeat-copy, same-title and concurrent arms. The lock break arm produced exactly that 409.
- **Committed in:** `58a41a6e`

**2. [Rule 2 - Missing critical] Copied flat image URLs would let one shop delete the other's photo**
- **Found during:** Task 2 design.
- **Issue:** `removeImage`, `removeAdditionalImage` and `deleteProduct` delete the flat object by URL with no reference count. A copy shares its source's URLs, because the storefront reads them. Removing the copy's photo in shop B would have deleted the object that shop A still shows. That breaks the plan truth "a later edit in one shop never changes the other".
- **Fix:** `deleteFlatImageIfUnshared` deletes only when `countOtherProductsReferencingImage` is 0. This is the flat counterpart of the asset ref-count.
- **Files modified:** `ProductService.java`, `ProductRepository.java`
- **Verification:** the edit-isolation arm, plus break arm 4 (`NeverWantedButInvoked delete(sharedUrl)`). ProductImageDelete 3/0 and ProductImageCrossTenantBlobDelete 3/0 are unchanged.
- **Committed in:** `58a41a6e`

**3. [Rule 3 - Blocking] A per-row refusal could not be built on `require`**
- **Found during:** Task 1.
- **Issue:** catching `ShopAccessDeniedException` from the `@Transactional` `ShopAccessService` marks the import's transaction rollback-only. Measured: HTTP 500.
- **Fix:** `ShopAccessService.permits(shopId, minRole)`, which is additive. `require` is unchanged.
- **Committed in:** `fe4725f1`

**4. [Rule 3 - Blocking] Files outside the plan's list**
- `ShopAccessService.java` (`permits`), `ShopRepository.java` (the lock query), `ProductRepository.java` (three finders) and `ProductServiceTest.java` (constructor gains `ShopRepository`).
- `BulkImportTenantCacheScopeIntegrationTest.java`: it imported without a shop, so under the new rule it would have created nothing and its cache assertions would have become vacuous. It now imports into a fresh shop, with every original assertion kept, and runs 5/0.
- The OpenAPI snapshot was also regenerated in Task 1, so no commit leaves `OpenApiSnapshotTest` red.

**5. [Plan behaviour change, recorded] A row naming an unmanaged shop is no longer a whole-batch 403**
- The plan asks for a per-row "403-style" error, and that replaces the earlier whole-batch typed 403 with rollback.
- No existing test pinned the old behaviour (searched with `rg -uu`). Nothing is ever written to the refused shop, which the IT asserts by SQL.

---

**Total deviations:** 5 recorded (1 bug, 1 missing critical, 2 blocking, 1 documented behaviour change).
**Impact on plan:** all were needed for the plan's own truths: idempotency, "an edit in one shop never changes the other", and a per-row error that does not abort. The import and copy contracts are as specified.

## Goods displaced (Incremental Betterment)

- **A Group admin's CSV import without a shop used to "succeed".** It created shop-less rows that showed in the dashboard list but on no storefront. That is now the "shop required" error. This is strictly better: a reported success for an invisible menu was the defect.
- **But the dashboard import page sends no `shopId` until 37-14.** Until then every dashboard import reports "shop required" (WINDOWS.md #27). 37-14 is in the same 37-B PR, so the PR must carry both.

## Issues Encountered

- The machine's base-python guard refused a `.py` helper for the RED record, so it was rebuilt as a Node script, as the orchestrator warned. Report summaries use `/usr/bin/python3 -c` inline only.
- A suspected pre-existing issue was found and **not verified**, so it was not fixed (out of scope; `deferred-items.md` §9). `product_media.product_id` has no ON DELETE CASCADE, and `deleteProduct` does not drop links. Deleting a product that has photos may therefore return the misleading 409 "still referenced by an existing order". It should be measured before anyone files it.

## Known Stubs

None. No placeholder text, TODOs or unwired data in the files this plan touched.

## Threat Flags

None beyond the plan's threat model. T-37-35 is covered by the default-shop gate, the per-row `permits`, both copy gates and the 403 arms. T-37-36 is covered by the verbatim copy with SQL fidelity and break arms. T-37-37 is covered by the target lock and the concurrent arm. `ShopAccessService.permits` adds no new access path: it is `require`'s own ladder.

## User Setup Required

None.

## Next Phase Readiness

- 37-14 (frontend) can call `POST /products/bulk/csv?shopId=` and `POST /products/copy`, and gets `{created, skipped, failed, items[]}` back.
- The copy SKU is not the source SKU, so the dialog must not promise "same SKU".
- Owner review at the phase PR: `quantity_in_stock` is copied as stored, as the plan says. A copied count can oversell a shop that does not hold the stock.
- Runtime parity is owed to the 37-15 rebuild.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-09*

## Self-Check: PASSED

- FOUND: BulkImportShopDefaultIntegrationTest.java, ProductCopyIntegrationTest.java, CopyProductsRequest.java, CopyProductsResult.java, evidence/37-13-red-csv-shop-default.json, evidence/37-13-red-copy-to-shop.json
- FOUND commits (ancestors of HEAD): 6a27a7e7, fe4725f1, 90bd5685, 58a41a6e
- Commit count measured from the ledger: `git rev-list --count 504b0db3..58a41a6e` = 4

# Quick 261104-catalogue — SUMMARY (persona close-out, PR #1115)

Written by the supervisor from the executor's report (the executor's Write of this file was refused by the harness).

| Issue | Outcome |
|---|---|
| 831 | Fixed: the uploader takes the field for the slot it uploaded (banner vs logo); `frontend/components/ui/image-uploader.tsx`. |
| 832 | Fixed: V90 `products.archived_at` (+ `products_aud` mirror). Delete drops photo links and releases assets under V53 ref-count rules; a product named by any order line is archived (unavailable, off lists/storefront, SKU freed via partial unique index); a product on an in-flight order (DRAFT..READY) gets 409 `in-use` before any image is touched. |
| 833 | Fixed: shop delete refused with typed 409 `shop-has-products` carrying `productCount`; dialog shows the count. |
| 835 | Fixed: publish checkbox replaced by a read-only status + Onboarding link; `published` never sent; server 409 kept as defence. |
| 799 | Partly: CSV accepts the 14 UK allergen names (case-insensitive, comma/semicolon) in `allergens` or `allergen_mask`; unknown/out-of-range values are row errors (previously silently imported as 0). EPOS/Square import out of scope. |

Evidence: every new test observed RED on the unfixed code then GREEN (per-test counts in the PR body).

Gates (rc 0): `npm run lint`, `npm run build`, `npm test` (307 suites / 3615 tests; two unrelated suites flaked under load once and pass alone), `./gradlew :core-java:test`, integrationTest for product/shop/media/common/Gdpr/Storefront + RlsContractTest + OpenApiSnapshotTest (361 tests, 0 failures), OpenAPI snapshot regenerated (two delete operations), docs-freshness, check-doc-metrics, check-claims, check-doc-versions, check-branch-behind-base.

Not run: full Java integration suite, Go, mcp-server (untouched); rebuilt-Compose Playwright and 390 px browser check — owed before merge.

## Review round 1 fixes (PR #1115, four admissible findings)

Each test was written first and run RED on the code as it stood after round 0, then GREEN after the fix.

| # | Finding | Fix | RED (before) | GREEN (after) |
|---|---|---|---|---|
| 1 | Voided orders (and abandoned DRAFTs) blocked a product delete for ever | `countOpenOrderLinesForProduct`: `o.voided_at IS NULL AND status IN (PENDING..READY)`; voided and DRAFT lines archive the product instead | `ProductDeleteArchiveIntegrationTest`: `aProductOnlyOnAVoidedInFlightOrderIsArchivedNotBlockedForEver` and `aProductOnlyOnAStaleDraftOrderIsArchivedNotBlocked` both threw `ResourceInUseException` | pass; `aLiveInFlightOrderStillBlocksWhenAVoidedOneSitsBesideIt` pins that a live order still blocks |
| 2 | Blob objects deleted before commit | `MediaAssetService.detachAsset` (row only) + `StorageService.deleteObjectsAfterCommit` (afterCommit, tenant pinned, GdprService pattern) for the product's asset keys and flat URLs, and for the shop's logo and banner | `aLateForeignKeyFailureLeavesEveryBlobInPlace` (a side table forces 23503 at flush) failed `NeverWantedButInvoked` on `deleteByKey`; `aProductWithAPhotoAndNoOrdersIsDeleted` failed on "not before commit" | pass; the delete test now commits and sees the keys deleted after commit |
| 3 | Shop delete race re-orphans a product | V90 also sets `products_shop_id_fkey` to `ON DELETE RESTRICT`; `deleteShop` flushes inside a try and maps the violation to `ShopHasProductsException` (no count); archived products are detached first | `ShopDeleteRaceIntegrationTest.aProductCreatedBetweenTheCountAndTheDeleteIsNeverOrphaned`: the delete succeeded (no exception) and orphaned the product | pass. The race is deterministic with no mocks: a second connection holds `FOR NO KEY UPDATE` on the shop row, the delete blocks on it, a product is committed meanwhile, then the lock is released |
| 4 | Unquoted comma shifts CSV columns | allergen separator is `;` or `\|` only; a row with more fields than the header is a row error (field `row`); the page, template help and messages say so | `BulkImportServiceTest`: 4 of 5 new tests failed (unquoted comma imported, quoted comma accepted, bar not recognised); `import-allergen-columns.test.tsx`: 1 of 3 failed on the old "commas or semicolons" copy | pass |

Round-1 gates: `npm run lint` rc 0; `npm run build` rc 0; `npm test` 307 suites / 3616 tests, 0 failed; `./gradlew :core-java:test` for `BulkImportServiceTest`, `ProductServiceTest`, `ShopServiceTest` green; integrationTest across product, shop, media, common, Gdpr, Storefront, boot4, customer, audit, integration, `RlsContractTest`, `OpenApiSnapshotTest`: 499 tests, 1 failure, `ProductSearchFtsIntegrationTest.queryPlanUsesGinIndexOnceRlsBarrierIsAbsent` (a planner-statistics assertion on the product search EXPLAIN). It passed when run alone and again in a rerun of the whole product and shop packages, and it passed in the pre-fix run of this PR, so it is recorded as a load-dependent flake, not caused by this change (the repository SQL it explains is unchanged since round 0). docs-freshness, check-doc-metrics, check-claims and check-doc-versions pass after the count update (3242 Java methods in 447 files, 3616 Jest blocks, 7239 total).

Notes: a batched shop DELETE surfaces no constraint name and no 23503 through Hibernate (measured: `constraint [null]`), so the RESTRICT violation is recognised by the constraint name in the database message. The five recorded findings (in-use toast wording, entity-level archive predicate, dead `isPublishRefusal`, rebuilt server sentence, unfiltered Products link) are unchanged, as instructed.

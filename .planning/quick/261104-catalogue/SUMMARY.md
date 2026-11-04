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

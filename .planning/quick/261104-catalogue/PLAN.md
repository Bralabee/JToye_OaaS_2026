# Quick 261104-catalogue - persona close-out, catalogue and shop-admin lane

Branch `fix/persona-catalogue`. Issues 831, 832, 833, 835, 799 (remainder). Each re-checked on
`origin/main` first (the triage notes were leads, not proof).

## Per issue

| Issue | Defect (re-confirmed on main) | Fix | RED test |
|---|---|---|---|
| 831 | `ImageUploader` took the first non-empty of `imageUrl \|\| logoUrl \|\| bannerUrl` from the whole `ShopDto`, so a banner upload on a shop with a logo handed the logo to the banner slot and Update Shop saved it as `bannerUrl` | `resolveSlotUrl(aspectRatio, dto, fallback)`: logo takes `logoUrl`, banner takes `bannerUrl`, square keeps `imageUrl`; a shop slot with no own url reports null ("re-read"), never a sibling's image | `components/ui/__tests__/image-uploader-slot.test.tsx` |
| 832 | `deleteProduct` hard-deleted: `product_media` (no cascade) and `fk_order_items_product` (any order, even cancelled) both surfaced as the false "referenced by an existing order" 409 | delete drops the product's `product_media` links and ref-count-releases assets (V53: physical delete only at zero refs); a product any order line still names is ARCHIVED (V90 `products.archived_at`, `@SQLRestriction`, partial unique SKU index, unavailable, photos cleared); a product on an in-flight order (DRAFT..READY) is refused with the typed 409 naming the real reason | `ProductDeleteArchiveIntegrationTest` (7) |
| 833 | `products.shop_id ON DELETE SET NULL` (V20): deleting a shop orphaned its products | refuse the delete with typed 409 `shop-has-products` + `productCount` while live products exist (chosen: refusal, safest; the issue thread does not say otherwise); UI explains and links to Products | `ShopDeleteWithProductsIntegrationTest`, `shop-delete-has-products.test.tsx` |
| 835 | the form offered a "Publish" checkbox and always sent `published`; `ShopService.updateShop` throws `PublishStateNotAcceptedException` on a change BEFORE applying other fields, and the toast showed the developer detail | form shows read-only status ("Live on the storefront" / "Not live yet" + link to Onboarding), never sends `published`; an older server's `shop-publish-not-accepted` is shown in plain words. Server 409 kept as defence | `shop-publish-status.test.tsx` |
| 799 | CSV import took allergens only as the numeric bitmask; a garbled value silently became 0 | `allergens` column and/or names in `allergen_mask`: the 14 `AllergenCatalog` names, case-insensitive, comma/semicolon separated, OR-combined with the number; unknown name, garbled value or number outside 0..16383 is a typed per-row error (field + message listing valid names) and the row is not created. Import page documents both | `BulkImportServiceTest` (+8), `import-allergen-columns.test.tsx` |

## Out of scope (said, not skipped silently)

- EPOS / Square / aggregator import (799 C-10): separate large feature, no plan, owner decision.
- Cross-shop "move" of products on shop delete: the refusal copy points at the existing "Copy to another shop" + delete; no new move endpoint.

## Threat notes

- V90 adds a column and rebuilds one index; no policy created/altered/dropped, no UPDATE (no FORCE-RLS bare-UPDATE trap), `products_aud` mirror in the same migration. `RlsContractTest` re-run.
- Archive does not widen any read: `products_public_read` (V89) already needs `available = true`; an archived product is unavailable, and `@SQLRestriction` plus hand-added predicates on the three native product searches hide it from the vendor.
- Count queries carry an explicit `tenant_id` predicate (not RLS-only).
- Storage deletes happen only after the in-flight-order check (object storage does not roll back).
- Hardening beyond the brief, flagged: a garbled/out-of-range allergen number used to import as "no allergens"; it is now a row error.

## Agent-readiness record

- Idempotency-Key: no new mutating endpoint. Errors typed (RFC 7807): `shop-has-products` new, `in-use` narrowed.
- OpenAPI snapshot regenerated (delete product / delete shop descriptions and 409s).
- MCP: no product-delete or shop-delete tool exists in `mcp-server`, so there is nothing to mirror; recorded as out of scope.
- WCAG: the blocked-delete notice is `role="alert"` with a real link; the publish status is text plus link, not colour alone.

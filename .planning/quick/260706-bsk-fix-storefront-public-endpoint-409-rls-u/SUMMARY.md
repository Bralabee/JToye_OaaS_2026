---
quick_id: 260706-bsk
slug: fix-storefront-public-endpoint-409-rls-u
date: 2026-07-06
branch: fix/storefront-rls-409
base_branch: feature/phase-17-implementation
status: complete
---

# Summary: Fix storefront public-endpoint 409 (RLS uuid-cast crash)

## What was wrong

`/public/shops/{slug}/promotions`, `/announcements`, and `/config` returned
HTTP 409 to anonymous storefront visitors. Underlying Postgres error: `22P02
invalid input syntax for type uuid: ""`.

`V33__fix_rls_policies.sql` wrote three SELECT policies (`shop_promotions_read`,
`shop_announcements_read`, `reviews_tenant_read`) with the raw cast
`current_setting('app.current_tenant_id', true)::uuid`. Postgres evaluates that
constant sub-expression at query init (even on empty tables); anonymous
requests carry no tenant context, so the GUC resets to `''` and `''::uuid`
throws. Spring maps the SQLException → `DataIntegrityViolationException` → 409.
`reviews` only escaped because its service sets tenant context first.

## Fix (committed)

`V39__fix_storefront_rls_uuid_cast.sql` — recreate the three policies using the
safe `current_tenant_id()` helper (returns NULL for empty/`default`/unset)
instead of the raw cast, matching the pattern already used by `products`/`shops`.
Semantics preserved: tenant rows match for authenticated tenants; published
shops stay publicly readable via `EXISTS(published shop)`. No data change.

## Verification (evidence)

- Flyway: `flyway_schema_history` → `39 | fix storefront rls uuid cast | success=t`.
- Policies: all three now contain `current_tenant_id()`, zero `::uuid` casts.
- API on `:9090`: promotions/announcements/config → **200** (were 409);
  reviews/products → **200** (no regression). Valid JSON bodies.
- Browser (`:3100`, Playwright): `has409: false` (only the benign anonymous
  `401 /api/customer-auth/session` remains); 11/11 product images paint.
  Screenshot: `scratchpad/03_shop_detail_fixed.png`.

## Operational note — NOT in this commit

- **Test-product cleanup (Part B):** soft-disabled 7 leftover E2E test products
  (`available=false`) via a dev-DB `UPDATE` — titles ending in a numeric suffix
  (E2E/Verify/Search/Wiring/Label/ClickTest Cake). Hard delete was impossible
  (6 referenced by real `order_items`, FK `NO ACTION`); soft-disable removes
  them from the storefront while preserving order history. This is dev-data
  only, not a repo change, so it is not committed.

## Environment finding (surfaced to user)

The dev Postgres volume is bound to `feature/phase-17-implementation` (V36–V38
applied). `main` lacks those migrations, so a `main`-based core-java rebuild
fails Flyway validation against this DB. This fix was therefore built and
verified on the Phase-17 base. PR target (phase-17 vs main) pending user
decision.

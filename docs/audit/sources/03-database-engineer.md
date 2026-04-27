# Database / Data Engineer Audit
**Auditor persona**: Senior PostgreSQL DBA, multi-tenant SaaS specialty
**Date**: 2026-04-27
**Schema maturity score**: 6/10
**Operational readiness score**: 5.5/10

---

## Schema inventory

**~17 application tables across 34 Flyway migrations** (V1–V34, 1411 SQL lines). Tenant-scoped business tables: `tenants`, `shops`, `products`, `customers`, `orders`, `order_items`, `financial_transactions`, `reviews`, `shop_promotions`, `shop_announcements`, `payment_event_outbox`. Audit mirrors: `shops_aud`, `products_aud`, `customers_aud`, `orders_aud`, `order_items_aud`, `financial_transactions_aud`, plus shared `revinfo`. No `notifications`, `outbox`, or `kds_session` tables despite handlers existing.

Naming is consistent: snake_case columns, `_pennies` suffix on monetary BIGINT, `_aud` suffix on Envers tables, `idx_` and `uq_` prefixes. PKs are universally `UUID DEFAULT gen_random_uuid()` (or `uuid_generate_v4()` from `uuid-ossp` in V1) — correct choice for a multi-tenant system where IDs leak in URLs and global uniqueness matters.

Timestamps are mostly `TIMESTAMPTZ` (V1 line 39, V5 line 27, V9 line 7) — good. Two stragglers in V31 use `TIMESTAMP WITH TIME ZONE` long-form which is the same type but inconsistent stylistically. No naive `TIMESTAMP` columns found.

`_pennies` columns are `BIGINT` everywhere except `shop_promotions.discount_amount_pennies` which is `INTEGER` (V29 line 6). At UK retail volumes a £21M+ promotion is implausible, but the inconsistency is a smell — fix to BIGINT.

JSONB usage is restrained and appropriate: only `shops.opening_hours` (V16 line 17) with `Map<String,String>` mapping in `Shop.java:57`. Not used as a dumping ground.

Arrays: `products.additional_image_urls TEXT[]` (V19), `shops.featured_product_ids UUID[]` (V28), `reviews.photo_urls TEXT[]` (V27). The `featured_product_ids UUID[]` is an anti-pattern — should be a join table `shop_featured_products(shop_id, product_id, display_order)` so you can JOIN, FK-cascade, and order. As-is, you cannot enforce that the referenced product belongs to the same tenant or even exists.

---

## RLS posture (CRITICAL)

### Tables with RLS
shops, products, financial_transactions, customers, orders, order_items, reviews, shop_promotions, shop_announcements, payment_event_outbox + 6 audit tables. **Total: 16 RLS-enabled tables.**

### FORCE ROW LEVEL SECURITY coverage gaps
`FORCE` is set on shops, products, financial_transactions, customers, orders, order_items, payment_event_outbox. **NOT FORCED**: `reviews` (V27:25), `shop_promotions` (V28:26), `shop_announcements` (V29:25), and **none of the `_aud` tables**. This means any privileged role (table owner, the migration role) bypasses RLS on these — bad on three of your four most recently added tenant-scoped tables. Add `ALTER TABLE … FORCE ROW LEVEL SECURITY` to every business and audit table without exception.

### Policy correctness
There is a meaningful inconsistency in HOW policies read tenant context:
- **V1/V2/V14/V15/V33** use `current_tenant_id()` function (a NULL-safe wrapper, V1:17–34).
- **V27/V28/V29** use `current_setting('app.tenant_id', true)::UUID` — and V28 originally used `app.tenant_id` while the application aspect publishes `app.current_tenant_id` (V29:31 fixes this in `shop_announcements`, V29:43–45 fixes `shop_promotions`). V27 (`reviews_tenant_write`) **still references `app.tenant_id`** (V27:34) which never matches what the app sets — meaning customer review writes have effectively been failing the WITH CHECK on the tenant branch and only the `customer_email` OR-branch lets them through. **CRITICAL** — this is silently broken multi-tenant write protection on reviews.
- **V33** uses raw `current_setting('app.current_tenant_id', true)::uuid` rather than the centralized `current_tenant_id()` helper — a NULL setting in payment_event_outbox will throw `invalid input syntax for type uuid: ""` instead of being treated as no-tenant.

### Permissive layering bugs
RLS policies are OR-ed by Postgres. `orders` now has `orders_select_policy` (V15) AND `orders_guest_tracking` (V17) AND `orders_customer_history` (V18). All three are SELECT permissive policies, so a request that sets `app.customer_email` to any string can list every matching order across all tenants (V18:9–18). The `app.customer_email` GUC is therefore a privilege as strong as the tenant context — make sure it is only ever set on a strictly limited public endpoint and is cleared in a `finally` block. I did not see a connection-pool reset hook for these GUCs (`DISCARD ALL` between checkouts), so a leaked setting on a pooled connection is a real cross-tenant data exposure. Verify `TenantSetLocalAspect` and the public storefront filter clear via `RESET app.customer_email` (or use `SET LOCAL` only inside transactions).

### Performance impact
Every RLS predicate boils down to `tenant_id = <uuid>` and `tenant_id` is indexed on every table (V1:74–76, V5:50/56, V9:19, V27:22, V31:30). Postgres will use those indexes happily. The OR-policies on `shop_promotions_read`/`shop_announcements_read`/`reviews_tenant_read` (V33) include `EXISTS (SELECT 1 FROM shops WHERE shops.id = … AND shops.published = true)` — that subquery runs per row and `shops(id)` is the PK so it is a fast index lookup, but on a 100k-row promotions scan you will see N PK lookups. Consider adding `(published)` to a covering index or denormalizing `published_shop` into the child table.

### Bypass risk
V2:14–18 attempts `ALTER ROLE current_user NOBYPASSRLS` but swallows the exception. Whether the migration role and the runtime role are the same is unverified. The `tenants` table itself has **no RLS** (V1:37–41) — comment in V2:51 acknowledges this. Anyone with the app role can `SELECT * FROM tenants` and enumerate tenants. Either add a role-restricted policy or move `tenants` to a separate schema accessible only via admin tooling. The flusher in `PaymentEventOutboxFlusher.java:73` already does this enumeration `SELECT id FROM tenants` — it should — but the contract is not protected.

---

## Indexing analysis

### Well-indexed paths
- `tenant_id` on every table.
- Composite uniques: `(tenant_id, sku)` on products (V3:4), `(tenant_id, name)` on shops (V3:7), `(tenant_id, email)` on customers (V9:15), `(tenant_id, order_number)` on orders (V5:31), partial unique on `(tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL` (V24:7) — textbook.
- Partial indexes: `idx_shops_published WHERE published=true` (V16:43), `idx_products_available WHERE available=true` (V20:61), `idx_orders_payment_reference WHERE payment_reference IS NOT NULL` (V22:18), `idx_shop_promotions_active WHERE active=true` (V28:23). Excellent.
- GIN tsvector indexes on `products.search_vector` and `shops.search_vector` (V25:14, V25:43) with auto-update triggers — solid.

### Missing or weak indexes (with query proof)
- **`order_items.product_id`**: indexed (V5:58) — fine. But repository queries like `OrderRepository.findByShopId` (`OrderRepository.java:25`) hit `idx_orders_shop` which does NOT include `tenant_id`. Under high tenant count `idx_orders_tenant + idx_orders_shop` BitmapAnd will be slower than a composite `(tenant_id, shop_id)`. **Add `(tenant_id, shop_id, status, created_at DESC)`** for the dashboard "orders by shop with status filter" hot path.
- **`orders.created_at`**: `idx_orders_created` exists (V5:53) but is NOT tenant-prefixed. With RLS-injected `tenant_id = ?` the planner often prefers the tenant index then sorts in memory — fine for small tenants, expensive at 1M+ rows per tenant. Replace with `(tenant_id, created_at DESC)`.
- **`orders.status`**: `idx_orders_status` (V5:52) is tenant-agnostic. KDS query `findByStatus(IN (CONFIRMED, PREPARING))` will scan one tenant index then status index then BitmapAnd. Replace with `(tenant_id, status, created_at)` — covers KDS, dashboards, and reporting in one.
- **`reviews(tenant_id)`** exists (V27:22) but `(shop_id)` is the only other index (V27:21). The `shop_ratings` view (V27:39) does `GROUP BY shop_id` over the entire table — fine for a small dataset, brutal at scale. Materialize or add `(shop_id) INCLUDE (food_rating, delivery_rating)`.
- **`payment_event_outbox`**: `idx_payment_event_outbox_status_created_at` (V31:26) is correct for the flusher, but the per-tenant flusher loop in `PaymentEventOutboxFlusher.java:80` does `findTop100ByStatus...` after `SET app.current_tenant_id` — RLS adds `tenant_id = ?` to the WHERE clause. Without `(tenant_id, status, created_at)` you get a wider scan than necessary at high tenant fan-out. Add the composite.
- **`shop_announcements` and `shop_promotions`** have no `tenant_id` index at all (V28, V29). RLS predicate falls back to seq scan once these tables are non-trivial.

### Suspect / duplicate
- `idx_orders_number` (V5:54) and `idx_orders_order_number` (V17:26) are duplicates of `uk_orders_order_number` UNIQUE (V7:17). Drop two of three.
- `idx_shops_aud_rev`, `idx_products_aud_rev`, etc. (V4:57, 59, 61) duplicate the implicit PK `(id, rev)` left-prefix lookup but only on `rev` alone — keep only if you actually query "all entities at revision X", which I see no evidence of in code.

---

## Top 5 schema strengths
1. Pennies-as-BIGINT everywhere money lives — no float errors, no rounding drift.
2. RLS plus `FORCE` on the original five tables shows real security thinking.
3. Optimistic locking (`@Version`) added on orders, shops, products (V32, V34) with a thoughtful retry comment.
4. Idempotency key on orders with partial unique index (V24:7) — textbook double-submit defence.
5. Transactional outbox for payment events (V31) — correct pattern, with RLS retroactively added in V33.

## Top 5 schema concerns (severity)
1. **HIGH** — `reviews_tenant_write` policy reads the wrong GUC name (V27:34) so write authorization on reviews degenerates to "anyone whose `app.customer_email` matches the row's email" — silent multi-tenant write hole.
2. **HIGH** — `tenants` table has no RLS; the app role can enumerate and read every tenant.
3. **HIGH** — `reviews`, `shop_promotions`, `shop_announcements`, and all `_aud` tables are not `FORCE ROW LEVEL SECURITY`. A privileged DB role bypasses tenant isolation entirely.
4. **MEDIUM** — Permissive OR policies on `orders` (V17, V18) make any leaked or reused `app.customer_email` GUC a cross-tenant read primitive. No verified pool-reset hook.
5. **MEDIUM** — Missing tenant-leading composite indexes on hot order paths (`orders_status`, `orders_created`, `orders_shop`). At 1M orders per tenant this becomes the #1 latency complaint.

## Migration debt
- **Type churn**: V5 created the `order_status` enum, V6 dropped it for VARCHAR + CHECK to satisfy Hibernate's `@Enumerated(STRING)`. V12 did the identical dance for `vat_rate`. The `vat_rate_enum` PostgreSQL type (V1:11) is left as dead code per the V12:25 comment. Drop it.
- **RLS rework**: V14 fixes V9, V15 fixes V5, V33 fixes V27/V28/V29, V11 patches V4. RLS got iteratively right but the policy was wrong on first try four separate times — that's a process gap. **Mandate a tenant-isolation test in every new-table PR.**
- **Disable-then-enable RLS dance** in V16:24 to backfill slugs is correct in a controlled migration, but a similar pattern is needed every time a tenant-scoped backfill is added — no helper function exists for it.
- **V21**: denormalized `item_count` because lazy-loading `order_items` through RLS without tenant context broke the public order tracking page. The fix is correct, but it admits the RLS architecture leaks abstraction into entity design.
- No down/rollback scripts. Flyway is forward-only; that's a choice, but combine that with V12-style destructive `DROP COLUMN` and your DR posture is "restore from backup."

## Audit (Hibernate Envers)
Audited entities: `Shop`, `Product`, `FinancialTransaction`, `Customer`, `Order`, `OrderItem` (six). Every audit table has `tenant_id` (V4, V5, V9). RLS SELECT is restricted to `current_tenant_id()` (V4:78–124) but INSERT is `WITH CHECK (true)` (V11:15–25) to let Envers write DELETE rows where every column is null — the comment explains this clearly. **Cost concern**: every UPDATE on an order writes a `_aud` row. With `store_data_at_delete: true` (application.yml:52) plus order state machine transitions (DRAFT→PENDING→CONFIRMED→PREPARING→READY→COMPLETED), each completed order produces ~6–7 `orders_aud` rows. At 10M orders that's 70M+ audit rows — partition `orders_aud BY RANGE (rev)` or by month before this hurts. No partition strategy exists.

## N+1 / query hot paths

- **`OrderService.getAllOrders`** (`OrderService.java:222`) maps Page<Order> to OrderDto. `OrderMapper.toDto` likely accesses `order.getItems()` — `Order.items` is `@OneToMany` with no `fetch` declared, so default LAZY. Mapping each row triggers a SELECT against `order_items`. **Classic N+1 on the orders list page.** No `@EntityGraph` and no JOIN FETCH anywhere in `OrderRepository.java`.
- **`OrderService.getOrdersByStatus`, `getOrdersByShop`, `getOrdersByCustomer`** (lines 231/241/251) return `List<OrderDto>` — no pagination, no limit. The KDS endpoint will eventually pull every PREPARING order ever created by a tenant.
- **`StockService.decrementForOrder`** (`StockService.java:90`) does `findAllById(productIds)` — single query, good. But it runs in `REQUIRES_NEW` so the outer transaction's row locks/visibility don't apply; the comment justifies this for retry semantics, but at high concurrency the order_items lazy-load triggered via `order.getItems()` in `OrderService.transitionOrder` (line 329) happens in the OUTER transaction before the new one starts — every CONFIRM does an additional SELECT for order_items even though they were just inserted in createOrder.
- **`@Cacheable` with paginated queries**: `ProductService.getProductById` and `ShopService.getShopById` are cached with the tenant-aware key generator (good). But `getAllProducts(Pageable)` is uncached — every dashboard render re-hits Postgres. Either acceptable or a missed easy win depending on traffic.
- **Sync cache eviction**: `SyncService.processBatch` at `SyncService.java:41–44` does `@CacheEvict(allEntries=true)` on both `shops` and `products` for every batch — explicitly the anti-pattern that `TenantCacheEvictor` was created to avoid (`TenantCacheEvictor.java:19` literally documents why). One unhappy edge sync flushes every tenant's cache.
- **`BulkImportService`**: same anti-pattern — `@CacheEvict(value = "products", allEntries = true)` at lines 55 and 110.
- **`PaymentEventOutboxFlusher.flushPending`** (`PaymentEventOutboxFlusher.java:71`) loads ALL tenants every 5s, sets context, queries up to 100 PENDING rows per tenant, all inside ONE `@Transactional` boundary. At 1000 tenants this is a 1000-iteration transaction holding a connection for seconds — connection-pool starvation risk. Iterate per-tenant in separate transactions.
- **`shop_ratings` view** (V27:39) has no GROUP BY index help and no materialization — every storefront page that calls it scans the reviews table.

## Operational concerns

- **HikariCP**: dev=20/min-idle=5, staging=30/10, prod=50/10. With Postgres default `max_connections=100` and a single Core API replica that's safe. Add a second replica without bumping Postgres and you exhaust at 100 connections cross-replica. There is no PgBouncer in front of Postgres in the docker-compose stack — at 1000 tenants and 5 replicas you will need it.
- **`max-lifetime: 1800000` (30 min)** is correct, **`leak-detection-threshold: 30000` in prod** is appropriate. **`open-in-view: false`** (application.yml:54) — confirmed off, good.
- **Long-running transactions**: `flushPending` (above), and the per-tenant batch is the worst offender. `OrderService.transitionOrder` does state machine + stock decrement (REQUIRES_NEW) + save + financial transaction creation + RabbitMQ publish — if RabbitMQ is slow the outer transaction holds the orders row lock for the full publish duration. Move the publish to `TransactionSynchronization.afterCommit`.
- **`generate_statistics: false` in prod** (application-prod.yml:34) hides Hibernate stats — fine for perf, terrible for incident debugging. Add a runtime toggle.
- **Flyway**: `validate-on-migrate: true`, `out-of-order: false`, `baseline-on-migrate: false` — production-safe.
- **`batch_size: 50` prod** with `order_inserts: true`, `order_updates: true` — correct.
- **No tablespace, no partitioning, no autovacuum tuning** declared. At 10M orders Postgres autovacuum thresholds will need bumping for orders_aud and orders.

## What I would change in the next sprint

1. **Fix the `reviews_tenant_write` GUC name (V27:34)** and add `FORCE ROW LEVEL SECURITY` to `reviews`, `shop_promotions`, `shop_announcements`, and every `_aud` table. New migration V35.
2. **Add `(tenant_id, status, created_at DESC)` and `(tenant_id, shop_id, created_at DESC)` to orders.** Drop duplicate `idx_orders_number` / `idx_orders_order_number`. Drop the dead `vat_rate_enum` type.
3. **Add `tenant_id` indexes to `shop_promotions` and `shop_announcements`.**
4. **Replace `SyncService` and `BulkImportService` `@CacheEvict(allEntries=true)` with the tenant-aware evictor** — these undo all the work done in `ProductService`/`ShopService`.
5. **Lock down `tenants` table** with role-based RLS or move it to a `meta` schema only the admin role can read.
6. **Refactor `PaymentEventOutboxFlusher`** to use one transaction per tenant, not one transaction per cycle.
7. **Add `@EntityGraph(attributePaths = "items")` to `OrderRepository.findAll(Pageable)` overload** used by the dashboard list view.
8. **Pool reset / GUC discipline**: enforce `RESET app.customer_email; RESET app.tracking_email; RESET app.tracking_order_number;` on connection return, OR switch to `SET LOCAL` inside an explicit transaction. This is the most likely silent cross-tenant leak.

## What worries me long-term

At 1000 tenants and 10M orders the architecture has three load-bearing assumptions that won't survive: (a) RLS predicate on every table is fine because `tenant_id` is indexed — true on point lookups, but every `ORDER BY created_at DESC LIMIT 50` on the dashboard will scan a per-tenant slice that the planner increasingly mis-estimates as data skews across tenants; you'll need per-table partitioning by `tenant_id` (hash, ~32 partitions) or by `created_at` (monthly) for orders/orders_aud. (b) The per-tenant flusher and the `SELECT id FROM tenants` enumeration assume the tenant count is small enough to iterate every 5 seconds — at 1000 tenants that's 200 tenants/sec of context-switching plus 200 RLS-scoped queries, and at 10000 tenants this thread becomes the bottleneck before payments are even busy. (c) Envers writes a row per state transition with `store_data_at_delete: true` — orders_aud will outpace orders 7:1 and your audit table becomes the hot table without partitioning. Add a partitioning + retention plan now (move audit rows >24 months to cold storage). Finally, the `featured_product_ids UUID[]`, `announcements TEXT[]` (now migrated, V29:40), and `additional_image_urls TEXT[]` patterns will resist join-based reporting forever — bite the normalization bullet before the BI team hates you.

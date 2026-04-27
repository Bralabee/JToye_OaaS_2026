# Database Remediation — Pair-Reviewed Design Doc

**Date**: 2026-04-27
**Pair**: Senior PostgreSQL DBA (specialist) × RLS / Query-Plan Reviewer (assistant)
**Source audit**: `docs/audit/sources/03-database-engineer.md`
**Council synthesis**: `docs/audit/COUNCIL-AUDIT-2026-04-27.md`
**Schema baseline**: V1–V34 (`core-java/src/main/resources/db/migration/`)
**Target**: V35 (transactional policy + FORCE), V35.1 (CONCURRENTLY indexes), V35.2 (join-table normalisation), V36 (deferred column drop)

---

## Principles

1. **RLS is defence in depth, not a perimeter.** Every tenant-scoped table needs (a) `ENABLE`, (b) `FORCE`, (c) tenant-leading index, (d) regression test asserting (a)–(c) at boot. The four iterative RLS fixes already in tree (V11→V4, V14→V9, V15→V5, V33→V27/28/29) are a process gap. Close it with a CI test, not another retro-patch.
2. **One canonical GUC name + one canonical reader.** `app.current_tenant_id`, read via the NULL-safe `current_tenant_id()` helper (V1:17–34). The bug at V27:34 is exactly this drift; V33 fixed the same drift in V28/V29 reads but did not touch V27 writes.
3. **Tenant-leading composite indexes** for every secondary access path. Composite uniques are tenant-leading already; `status`, `created_at`, `shop_id` are not.
4. **`CONCURRENTLY` for any index build/drop on a non-trivial table.** Naive `CREATE INDEX` on `orders` takes `AccessExclusiveLock` for the build duration.
5. **Pool-leak surface = union of every GUC ever `set_config(name, value, false)`.** Today every call site uses `is_local=true` (`TenantSetLocalAspect.java:61`, `PublicStorefrontService.java:238/277/281`, V27:34) so GUCs auto-clear at COMMIT/ROLLBACK. Make that a permanent CI rule.
6. **Forward-only with compensating migrations.** Flyway has no down-scripts by configuration choice. Cost is paid via destructive migrations as `ALTER TABLE … DROP COLUMN IF EXISTS` plus a 28-day soak comment.

---

## Findings

### Finding 1 — `reviews_tenant_write` GUC name + customer_email write hole

**Specialist proposal.**

V27:31–36 has two defects:
- Reads `app.tenant_id`; the app sets `app.current_tenant_id` (`TenantSetLocalAspect.java:61`). First branch is permanently false. Today's only working write path is the OR-branch — "anyone whose `app.customer_email` matches the row's email may write a review **on any tenant_id and any order_id**."
- `current_setting('app.tenant_id', true)::UUID` throws on `''`; use the `current_tenant_id()` helper instead (V1:17–34, NULL-safe).

V35 (transactional file):

```sql
DROP POLICY IF EXISTS reviews_tenant_write ON reviews;

CREATE POLICY reviews_tenant_write ON reviews
    FOR INSERT
    WITH CHECK (
        tenant_id = current_tenant_id()
        OR (
            current_setting('app.customer_email', true) IS NOT NULL
            AND current_setting('app.customer_email', true) <> ''
            AND current_setting('app.customer_email', true) = customer_email
            AND EXISTS (
                SELECT 1 FROM orders o
                 WHERE o.id = order_id
                   AND o.customer_email = current_setting('app.customer_email', true)
                   AND o.tenant_id = reviews.tenant_id
            )
        )
    );

CREATE POLICY reviews_tenant_update ON reviews
    FOR UPDATE USING (tenant_id = current_tenant_id())
                WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY reviews_tenant_delete ON reviews
    FOR DELETE USING (tenant_id = current_tenant_id());
```

Online-safe: pure `CREATE/DROP POLICY` — brief `AccessShareLock` only.

**Assistant deliberation.**

1. **RLS bypass edge case** — the EXISTS subquery is itself RLS-evaluated. With `app.customer_email` set, `orders_customer_history` (V18:9–18, permissive OR-policy) lets the subquery find the row. Document the coupling in the migration comment.
2. **Perf risk** — one EXISTS per insert, low-volume table, O(log n) PK lookup. No concern.
3. **Without the new UPDATE/DELETE policies, FORCE RLS (Finding 2) silently breaks vendor moderation** because no policy = deny under FORCE. Specialist correctly added them.
4. **Test required** — Testcontainers regression in `MultiTenantIsolationIntegrationTest`: (a) tenant A insert with `tenant_id=A` succeeds; (b) tenant A insert with `tenant_id=B` fails; (c) only `app.customer_email='alice'` set, attempt insert against tenant B for an order alice doesn't own → fails with policy violation.

**Reconciled position.** Adopt as written. Add the three test cases. Add a one-line comment noting the dependency on V18's `orders_customer_history`. Bundle in the V35 transactional file with Finding 2.

---

### Finding 2 — `FORCE ROW LEVEL SECURITY` coverage gap

**Specialist proposal.**

Today FORCED: `shops`, `products`, `financial_transactions` (V2:8–10), `customers`/`orders`/`order_items` (per audit), `payment_event_outbox` (V33:17). NOT FORCED: `reviews` (V27:25), `shop_promotions` (V28:26), `shop_announcements` (V29:25), and **all six `_aud` tables**.

Without FORCE, table owners and `BYPASSRLS` roles read across tenants. V2:14–18 attempts `NOBYPASSRLS` but swallows the exception — runtime role status unverified.

V35:

```sql
ALTER TABLE reviews              FORCE ROW LEVEL SECURITY;
ALTER TABLE shop_promotions      FORCE ROW LEVEL SECURITY;
ALTER TABLE shop_announcements   FORCE ROW LEVEL SECURITY;
ALTER TABLE shops_aud                  FORCE ROW LEVEL SECURITY;
ALTER TABLE products_aud               FORCE ROW LEVEL SECURITY;
ALTER TABLE financial_transactions_aud FORCE ROW LEVEL SECURITY;
ALTER TABLE customers_aud              FORCE ROW LEVEL SECURITY;
ALTER TABLE orders_aud                 FORCE ROW LEVEL SECURITY;
ALTER TABLE order_items_aud            FORCE ROW LEVEL SECURITY;
```

CI test (lives next to `MultiTenantIsolationIntegrationTest`) — see Finding 11 for full code.

Online-safe: catalog-only `AccessExclusiveLock` for microseconds. No `CONCURRENTLY` exists for `ALTER TABLE … FORCE`.

**Assistant deliberation.**

1. **FORCE on `_aud` tables breaks Envers DELETE-row writes?** No — V11's INSERT policies are `WITH CHECK (true)`, unconditional, still pass under FORCE. SELECT policies read `tenant_id = current_tenant_id()` which is NULL-false on a NULL-`tenant_id` audit row — that is pre-existing behaviour, FORCE doesn't change it.
2. **One operational escape hatch closes.** Backup/ad-hoc scripts that connect as table owner and read across tenants stop working. Document in `docs/runbooks/db-admin.md` (create as part of this finding) — admin must `SET SESSION AUTHORIZATION` to a `BYPASSRLS` role.
3. **Migration order** — Finding 1 policies must apply before Finding 2 FORCE, otherwise an in-flight customer review insert briefly sees no INSERT policy and is denied. Specialist's V35 ordering is correct.
4. **Simpler alternative considered, rejected** — auto-walk `pg_class` for the FORCE list. Explicit list is uglier but auditable; CI test (Finding 11) catches the next omission.

**Reconciled position.** Adopt. Order in V35: (1) reviews policy fix, (2) FORCE block, (3) supporting indexes from Finding 5. Create `RlsContractTest`. Document the operational escape hatch in `docs/runbooks/db-admin.md`.

---

### Finding 3 — `tenants` table lockdown

**Specialist proposal.**

V1:37–41 creates `tenants` with no RLS. V2:51 acknowledges via comment. `PaymentEventOutboxFlusher.java:73` and `ScheduledCleanupService.java:53` both run `SELECT id FROM tenants` from the app role — works today, but the contract is unenforced: any caller with the app role can dump every tenant.

Three options: (a) role-restricted policy with admin GUC; (b) move to `meta` schema with separate role; (c) status quo. Pick **(a)** — zero infrastructure change, the GUC pattern already exists. (b) doubles connection pools and rewrites every FK reference to `tenants` across `shops`/`orders`/`customers`.

V35:

```sql
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;

CREATE POLICY tenants_admin_read ON tenants
    FOR SELECT USING (
        current_setting('app.is_admin', true) = 'true'
        OR id = current_tenant_id()
    );

CREATE POLICY tenants_admin_write ON tenants
    FOR ALL
    USING (current_setting('app.is_admin', true) = 'true')
    WITH CHECK (current_setting('app.is_admin', true) = 'true');
```

Application change — new `TenantEnumerationService`:

```java
@Component
public class TenantEnumerationService {
    private final EntityManager em;
    @Transactional
    public List<UUID> listAllTenants() {
        em.createNativeQuery("SET LOCAL app.is_admin = 'true'").executeUpdate();
        return em.createNativeQuery("SELECT id FROM tenants").getResultList();
    }
}
```

Update `PaymentEventOutboxFlusher.java:71` and `ScheduledCleanupService.java:49` to call this instead of inline `SELECT id FROM tenants`.

**Assistant deliberation.**

1. **Order-of-operations risk.** The Java change MUST ship before V35 hits prod, or the flusher's `SELECT id FROM tenants` returns zero rows → silent outbox backup. Two-PR sequence: app change first, V35 second.
2. **`app.is_admin` is now a privilege as strong as `app.current_tenant_id`.** Add it to the GUC discipline allow-list (Finding 4) and the CI grep.
3. **Option (b) revisited.** A dedicated `meta.tenants` schema with a `tenant_admin` role is cleaner. Cost (FK rewrites + second pool) is real. Defer for a later refactor; (a) is right for now.
4. **Verify all `tenants` query sites before merge.** `grep -rn "FROM tenants\|TenantRepository" core-java/src/main/java`. Any caller hitting `findAll` outside the schedulers will silently break post-V35.

**Reconciled position.** Option (a). Sequence: PR-N ships `TenantEnumerationService`; PR-N+1 ships V35. Add `tenants` to `RlsContractTest.TENANT_SCOPED_TABLES`. Run the grep verification before merge.

---

### Finding 4 — GUC pool discipline

**Specialist proposal.**

GUCs in flight: `app.current_tenant_id`, `app.customer_email`, `app.tracking_email`, `app.tracking_order_number`, plus `app.is_admin` from Finding 3. All set today via `set_config(name, value, true)` — `is_local=true`, auto-clears at COMMIT/ROLLBACK.

Verified: `TenantSetLocalAspect.java:61` and `PublicStorefrontService.java:238/277/281` both use `is_local=true` inside `@Transactional`. The transactional path is leak-safe today. Risk surface:
- Future `set_config(..., false)` (session-level by intent) without RESET.
- Caller invoking the setter outside a transaction → `is_local=true` is a no-op; setting becomes session-level.
- `TenantSetLocalAspect.resetTenant()` (line 69) only resets `app.current_tenant_id`. Does NOT reset `app.customer_email`/`app.tracking_*`/`app.is_admin`. Gap.

Three layers, recommend (a) + (c) only:

**(a) HikariCP `connection-init-sql: "RESET ALL"`** — defence in depth, ~0.1ms per checkout.

```yaml
# application.yml — under spring.datasource.hikari
hikari:
  ...
  connection-init-sql: "RESET ALL"
```

**(c) Static-analysis CI rule** — `scripts/check-guc-discipline.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
violations=$(grep -rEn "set_config\([^,]+,[^,]+,[[:space:]]*false\)|SET[[:space:]]+app\." \
    "$ROOT/core-java/src/main/java" "$ROOT/core-java/src/main/resources" \
    | grep -v "SET LOCAL" \
    | grep -v "scripts/check-guc-discipline.sh" || true)
if [ -n "$violations" ]; then
    echo "GUC discipline violation — use SET LOCAL or set_config(..., true):"
    echo "$violations"; exit 1
fi
```

Layer (b) (aspect-level RESET) dropped — redundant with (a).

**Assistant deliberation.**

1. **`RESET ALL` regression risk.** Resets `search_path`, `application_name`, `statement_timeout`. We rely on per-connection settings (JDBC URL), not session state. Safe.
2. **`TenantSetLocalAspect.resetTenant()` gap is moot once (a) ships** — every checkout starts clean.
3. **Perf cost** — one round-trip per checkout. At prod pool size 50, ~2% latency overhead at peak. Acceptable; targeted `RESET app.X; RESET app.Y; …` if it ever isn't.
4. **Edge case: long-running flusher transaction.** Pre-Finding-8, the flusher holds one connection across all tenants — `RESET ALL` only fires once. `is_local=true` overwrites within the transaction, no leak. Finding 8's per-tenant transactions make this even more correct.
5. **Test required** — open Hikari conn, `set_config('app.foo','bar',true)` in a transaction, return, open a second conn, assert `current_setting('app.foo', true) = ''`. Proves the leak is closed.

**Reconciled position.** Implement (a) + (c). Skip (b). Add the leak-closed unit test.

---

### Finding 5 — Tenant-leading composite indexes + duplicate-index cleanup + dead type drop

**Specialist proposal.**

Hot paths in `OrderRepository.java`: `findByStatus` (line 20, KDS), `findByShopId` (line 25, dashboard), `findByCustomerEmailOrderByCreatedAtDesc` (line 47, customer history).

V5:50–54 + V7:17 + V17:26 give us **three** indexes covering `orders.order_number` (the unique constraint's implicit index, `idx_orders_number`, `idx_orders_order_number`). Two are dead weight.

V35.1 (Flyway header `-- flyway:executeInTransaction=false`):

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_tenant_status_created
    ON orders (tenant_id, status, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_tenant_shop_created
    ON orders (tenant_id, shop_id, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payment_event_outbox_tenant_status_created
    ON payment_event_outbox (tenant_id, status, created_at);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_shop_promotions_tenant
    ON shop_promotions (tenant_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_shop_announcements_tenant
    ON shop_announcements (tenant_id);

DROP INDEX CONCURRENTLY IF EXISTS idx_orders_number;
DROP INDEX CONCURRENTLY IF EXISTS idx_orders_order_number;

DROP TYPE IF EXISTS vat_rate_enum;
```

Online-safe: every CREATE/DROP `CONCURRENTLY`. `DROP TYPE` requires no referencing column — verify with `pg_depend` first (V12 already migrated `financial_transactions.vat_rate` to VARCHAR).

**Assistant deliberation.**

1. **`CONCURRENTLY` mandatory.** Non-concurrent CREATE INDEX on `orders` blocks all writes for the build duration. The Flyway transaction-disable header is required (CONCURRENTLY can't run in a tx block).
2. **Don't drop `idx_orders_status` / `idx_orders_created`.** Under elevated role, cross-tenant queries exist. Composites cover tenant-scoped path; bare indexes cover cross-tenant. Index space is cheap.
3. **Size at scale** — ~3–4 GB per composite at 100M rows; ~10 GB total. Worth the planner improvement.
4. **Write amplification** — ~5–8 extra B-tree updates per order lifecycle. Microseconds.
5. **`pg_depend` check before `DROP TYPE`:**
   ```sql
   SELECT pg_describe_object(refclassid, refobjid, refobjsubid)
   FROM pg_depend WHERE classid = 'pg_type'::regclass
     AND objid = (SELECT oid FROM pg_type WHERE typname='vat_rate_enum');
   ```
   Empty → safe.

**Reconciled position.** Adopt. Split V35 (tx) and V35.1 (non-tx, CONCURRENTLY). Run `pg_depend` check before merge. Keep `idx_orders_status` / `idx_orders_created`. 30-day follow-up via `pg_stat_user_indexes` to prune unused indexes in V40+.

---

### Finding 6 — N+1 on dashboard orders

**Specialist proposal.**

`OrderRepository.java:15` extends `JpaRepository<Order, UUID>` with no `findAll` override. `OrderService.getAllOrders` (line 222) → `OrderMapper.toDto` → reads `order.getItems()` (lazy) → N+1.

```java
// OrderRepository.java
@Override
@EntityGraph(attributePaths = {"items"})
Page<Order> findAll(Pageable pageable);

@Override
@EntityGraph(attributePaths = {"items"})
Optional<Order> findById(UUID id);

@EntityGraph(attributePaths = {"items"})
Page<Order> findByStatus(OrderStatus status, Pageable pageable);

@EntityGraph(attributePaths = {"items"})
Page<Order> findByShopId(UUID shopId, Pageable pageable);

@EntityGraph(attributePaths = {"items"})
Page<Order> findByCustomerId(UUID customerId, Pageable pageable);
```

For `getOrdersByStatus`/`getOrdersByShop`/`getOrdersByCustomer` (lines 231/241/251) — they return unpaged `List<Order>`. KDS genuinely wants "all PREPARING right now" (typically <50). Keep unpaged for KDS but hard-cap at 200 inside the service:

```java
public List<OrderDto> getOrdersByStatus(OrderStatus status) {
    Page<Order> page = orderRepository.findByStatus(
        status, PageRequest.of(0, 200, Sort.by("createdAt").descending()));
    if (page.getTotalElements() > 200) {
        log.warn("getOrdersByStatus({}) returned {} — capping at 200 (KDS overload?)",
                 status, page.getTotalElements());
    }
    return page.getContent().stream().map(orderMapper::toDto).toList();
}
```

**Assistant deliberation.**

1. **`@EntityGraph` semantics with `Pageable`.** Hibernate JOIN FETCHes items, paginates in memory. With ~5 items × 50 orders = 250 rows fetched, dedup'd, paginated. Acceptable. At extreme skew (50 items × 50 orders = 2500 rows) still vastly better than 50 sequential queries.
2. **MultipleBagFetchException risk.** Only `items` collection exists today. Document the constraint in a comment so the next developer knows not to add a second `@OneToMany` to the EntityGraph.
3. **`findById` JOIN-FETCH** — costs ~5 extra rows even when caller doesn't read items. Trivial; consistency wins.
4. **KDS unpaged justification holds.** A KDS screen at a busy QSR has <50 orders. 200-cap with warning log is a safety net.
5. **Synergy with Finding 5** — the new `(tenant_id, status, created_at DESC)` index makes `findByStatus(status, pageable)` cheap.

**Reconciled position.** Adopt. Add Hibernate Statistics-based test asserting `getAllOrders(PageRequest.of(0,20))` over 30 orders × 4 items issues 1 (or 2 with count query) SQL statements, not 31.

---

### Finding 7 — `@CacheEvict(allEntries=true)` cross-tenant cache wipe

**Specialist proposal.**

Cross-references backend pair (01). `BulkImportService.java:55,110` and `SyncService.java:41–44` use `@CacheEvict(allEntries=true)` — wipes every tenant's cache on any tenant's import. `TenantCacheEvictor.java:73` already implements per-tenant single-entity eviction. Add a bulk variant:

```java
public void evictAllForTenant(String cacheName) {
    if (cacheManager == null) return;
    UUID tenantId = TenantContext.get().orElse(null);
    if (tenantId == null) {
        log.warn("evictAllForTenant skipped — TenantContext not set (cache={})", cacheName);
        return;
    }
    Cache cache = cacheManager.getCache(cacheName);
    if (cache == null) return;
    Object native_ = cache.getNativeCache();
    if (native_ instanceof org.springframework.data.redis.core.RedisTemplate<?, ?> redis) {
        @SuppressWarnings("unchecked")
        var template = (RedisTemplate<String, Object>) redis;
        String pattern = String.format("%s::tenant:%s:*", cacheName, tenantId);
        var keys = template.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
            log.info("Evicted {} entries from {} for tenant {}", keys.size(), cacheName, tenantId);
        }
    } else {
        cache.clear(); // test profile: per-test cache, full clear acceptable
    }
}
```

Caller updates: replace `@Caching/@CacheEvict(allEntries=true)` with imperative `cacheEvictor.evictAllForTenant(...)` after work completes.

**Assistant deliberation.**

1. **Coordination with backend pair (01).** This finding owns the API; backend pair owns caller updates.
2. **Redis `KEYS` blocks the event loop.** At >1M keys this stalls; we have thousands. Document `SCAN` migration as tech debt.
3. **Perf** — strictly less work than today's `allEntries=true`.
4. **Edge case** — Spring's Redis `@CacheEvict(allEntries=true)` deletes by `cacheName::*` prefix; tenant id is INSIDE the key, not the cache name. Confirmed cross-tenant wipe.
5. **Test required** — populate cache for tenants A and B; run `BulkImportService.importFromCsv` as A; assert B's cached entry survives.

**Reconciled position.** Adopt. Coordinate with backend pair (01). Ship with the multi-tenant cache isolation test.

---

### Finding 8 — `PaymentEventOutboxFlusher` long transaction

**Specialist proposal.**

`PaymentEventOutboxFlusher.java:69–93` — one `@Transactional` (line 70) wraps enumeration of all tenants. At 1000 tenants this is a 1000-iteration transaction holding one connection for seconds → pool starvation.

Refactor — outer method enumerates, helper has `REQUIRES_NEW`:

```java
@Component
public class PaymentEventOutboxFlusher {
    @Autowired @Lazy private PaymentEventOutboxFlusher self;  // self-proxy for REQUIRES_NEW
    private final TenantEnumerationService tenants;          // from Finding 3

    @Scheduled(fixedDelayString = "${payment.outbox.flush-interval-ms:5000}")
    public void flushPending() {
        for (UUID tenantId : tenants.listAllTenants()) {
            try {
                self.flushOneTenant(tenantId);
            } catch (Exception e) {
                log.error("Outbox flush failed for tenant {}", tenantId, e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flushOneTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            List<PaymentEventOutbox> pending = repository.claimPending();  // see deliberation #4
            if (pending.isEmpty()) return;
            for (PaymentEventOutbox row : pending) publishRow(row);
        } finally {
            TenantContext.clear();
        }
    }
    // publishRow unchanged
}
```

**Assistant deliberation.**

1. **`REQUIRES_NEW` proxy gotcha** — direct `this.flushOneTenant()` skips the proxy → reverts to old behaviour silently. `@Lazy self` injection is the correct fix; verify via test that an exception in one tenant's `flushOneTenant` does not roll back state from a previous tenant's flush.
2. **Pool arithmetic.** Old: 1 conn × ~5–30s every 5s. New: 1 conn × ~50ms × N tenants every 5s — but each individual hold is 50ms, pool serves other requests in between. With 50 prod connections this is invisible.
3. **Per-tenant failure isolation is a latent correctness fix.** Today a corrupt JSON in tenant A's outbox rolls back EVERYTHING. New code logs and continues.
4. **Horizontal scale gap the specialist missed: `SELECT FOR UPDATE SKIP LOCKED`.** Two flusher pods can both grab the same PENDING rows for tenant A. Add:
   ```java
   @Query(value = "SELECT * FROM payment_event_outbox WHERE status = 'PENDING' " +
                  "ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED",
          nativeQuery = true)
   List<PaymentEventOutbox> claimPending();
   ```
5. **No schema change.** Ship in the same PR as Finding 3 (TenantEnumerationService).

**Reconciled position.** Adopt with the `claimPending` `FOR UPDATE SKIP LOCKED` addition. Add the per-tenant failure isolation test.

---

### Finding 9 — Envers audit table growth, partitioning + retention

**Specialist proposal.**

`store_data_at_delete: true` (application.yml:52) + 6-state order machine = ~7× row ratio for `orders_aud` vs `orders`. Feasibility: 1k orders/day → 2.5M aud rows/year; 10k → 25M/year; 100k → 250M/year. PG single-table autovacuum struggles around 100M rows; `_aud` is INSERT-only post-write so XID-wraparound freeze hits before update bloat. **Urgent threshold: ~50M rows or ~50 GB for `orders_aud`.** Today this is years away. Design now, ship before urgency.

Strategy: **monthly RANGE partition on `created_at`**. Maps to retention (drop partition = drop month). NOT by tenant (1000+ partitions hurt planner). NOT by `rev` (we don't query by revision range).

V40+ sketch (deferred):

```sql
CREATE TABLE orders_aud_new (LIKE orders_aud INCLUDING ALL)
    PARTITION BY RANGE (created_at);

DO $$ DECLARE m DATE;
BEGIN
    FOR m IN SELECT generate_series(date_trunc('month', NOW()) - interval '12 months',
                                     date_trunc('month', NOW()) + interval '24 months',
                                     '1 month'::interval)::date LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS orders_aud_%s PARTITION OF orders_aud_new
             FOR VALUES FROM (%L) TO (%L)',
            to_char(m, 'YYYY_MM'), m, m + interval '1 month');
    END LOOP;
END $$;

CREATE TABLE orders_aud_default PARTITION OF orders_aud_new DEFAULT;
INSERT INTO orders_aud_new SELECT * FROM orders_aud;
ALTER TABLE orders_aud RENAME TO orders_aud_old;
ALTER TABLE orders_aud_new RENAME TO orders_aud;
```

Retention: monthly partition >24 months → `pg_dump` to S3 Glacier, `DROP TABLE`. Cron in `ScheduledCleanupService`.

**Assistant deliberation.**

1. **Does monthly partitioning map to access patterns?** Mostly no for query speed. Envers `findRevisions(entity, id)` queries (id, rev) — no pruning benefit. Audit dashboards by tenant + 30-day window — pruning helps. Compliance retrieval by entity all-time — no pruning. **Partitioning helps autovacuum + retention more than query speed.** Communicate honestly.
2. **Hybrid hash(tenant_id) + range(created_at) considered.** Cleaner pruning, but 32×24 = 768 partitions is operationally heavy. Only if tenant skew observed.
3. **Migration risk.** Naive copy requires a maintenance window. For zero-downtime use `pg_partman` from day one.
4. **Retention compliance.** Companies Act 2006 s388: 6 years for financial records → `financial_transactions_aud` retention = 6 years. `orders_aud` is operational (chargeback/dispute window): 24 months business choice.
5. **Premature partitioning is a complexity tax.** Ship when daily volume × 30 × 7 > 1M `orders_aud` rows/month.

**Reconciled position.** Monthly RANGE partition on `created_at` documented now; V40 deferred. Codify retention in `docs/runbooks/data-retention.md`: `orders_aud` 24 months, `financial_transactions_aud` 6 years statutory, others 24 months default. Wire `pg_partman` POC in staging when V40 queued.

---

### Finding 10 — Array-column normalisation

**Specialist proposal.**

- `shops.featured_product_ids UUID[]` (V28:7) — **normalize**. No FK, no JOIN-friendliness, no cross-tenant integrity. `Shop.java:79` maps `List<UUID>` directly to the array.
- `products.additional_image_urls TEXT[]` (V19) — **accept denormalised**. Opaque URLs, ordering = array index, no cross-table relationships.
- `reviews.photo_urls TEXT[]` (V27:15) — **accept denormalised**. Same reasoning.

V35.2:

```sql
CREATE TABLE IF NOT EXISTS shop_featured_products (
    shop_id       UUID NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    product_id    UUID NOT NULL REFERENCES products(id),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (shop_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_sfp_tenant_shop_order
    ON shop_featured_products (tenant_id, shop_id, display_order);

ALTER TABLE shop_featured_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_featured_products FORCE ROW LEVEL SECURITY;

CREATE POLICY sfp_tenant_all ON shop_featured_products
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Cross-tenant integrity: featured product must share tenant with shop.
CREATE OR REPLACE FUNCTION sfp_check_tenant_match() RETURNS trigger AS $$
DECLARE v_shop_t UUID; v_prod_t UUID;
BEGIN
    SELECT tenant_id INTO v_shop_t FROM shops    WHERE id = NEW.shop_id;
    SELECT tenant_id INTO v_prod_t FROM products WHERE id = NEW.product_id;
    IF v_shop_t IS NULL OR v_prod_t IS NULL OR v_shop_t <> v_prod_t THEN
        RAISE EXCEPTION 'shop_featured_products tenant mismatch: shop=%, product=%',
            v_shop_t, v_prod_t;
    END IF;
    IF NEW.tenant_id <> v_shop_t THEN
        RAISE EXCEPTION 'tenant_id (%) must match shop tenant (%)', NEW.tenant_id, v_shop_t;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER sfp_check_tenant_trigger
    BEFORE INSERT OR UPDATE ON shop_featured_products
    FOR EACH ROW EXECUTE FUNCTION sfp_check_tenant_match();

INSERT INTO shop_featured_products (shop_id, product_id, tenant_id, display_order)
SELECT s.id, fp.product_id, s.tenant_id, fp.ordinality::INTEGER - 1
FROM shops s,
     LATERAL unnest(s.featured_product_ids) WITH ORDINALITY AS fp(product_id, ordinality)
WHERE s.featured_product_ids IS NOT NULL
  AND array_length(s.featured_product_ids, 1) > 0
ON CONFLICT (shop_id, product_id) DO NOTHING;

-- DROP COLUMN deferred to V36 after 28-day soak + application cutover.
```

**Assistant deliberation.**

1. **Cross-tenant trigger** — a `CHECK` constraint can't reference other tables. Trigger is the right PG-15 mechanism. Cost is one PK lookup per insert — negligible.
2. **Migration order** — application MUST be updated to read from `shop_featured_products` BEFORE the V36 column drop. Specialist's deferral is correct.
3. **Read perf** — same disk reads, fewer round trips. Storefront previously: read array, N point-lookups. Now: one JOIN.
4. **Why not normalise the other arrays?** They're pure value objects; normalisation buys nothing.
5. **Drop `position` column from spec** — `display_order` is sufficient; `position` adds confusion.

**Reconciled position.** Adopt; `position` removed from final DDL (already excluded above). Defer `DROP COLUMN shops.featured_product_ids` to V36 after 28-day soak. Leave `additional_image_urls` and `photo_urls` denormalised.

---

### Finding 11 — Migration debt cleanup + DDL test convention

**Specialist proposal.**

V11→V4, V14→V9, V15→V5, V33→V27/28/29 — four retro-patches because RLS was forgotten on first try. Process gap.

`RlsContractTest.java`:

```java
@SpringBootTest @Testcontainers
class RlsContractTest {
    private static final List<String> TENANT_SCOPED_TABLES = List.of(
        "tenants", "shops", "products", "customers", "orders", "order_items",
        "financial_transactions", "reviews", "shop_promotions", "shop_announcements",
        "payment_event_outbox", "shop_featured_products",
        "shops_aud", "products_aud", "financial_transactions_aud",
        "customers_aud", "orders_aud", "order_items_aud"
    );

    @Test
    void everyTenantScopedTableHasRlsAndForce() {
        for (String t : TENANT_SCOPED_TABLES) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", t);
            assertThat(row.get("relrowsecurity")).as("RLS on %s", t).isEqualTo(true);
            assertThat(row.get("relforcerowsecurity")).as("FORCE RLS on %s", t).isEqualTo(true);
        }
    }

    @Test
    void everyTenantScopedTableHasTenantIdIndex() {
        for (String t : TENANT_SCOPED_TABLES) {
            if (t.equals("tenants")) continue;
            Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = ? " +
                "AND (indexdef LIKE '%(tenant_id%' OR indexdef LIKE '%(tenant_id,%')",
                Long.class, t);
            assertThat(count).as("%s tenant_id-leading index", t).isGreaterThan(0L);
        }
    }

    @Test
    void noPolicyReadsForbiddenAppTenantIdGuc() {
        List<String> bad = jdbcTemplate.queryForList(
            "SELECT polname || ' on ' || polrelid::regclass::text FROM pg_policy " +
            "WHERE pg_get_expr(polqual, polrelid) LIKE '%app.tenant_id%' " +
            "   OR pg_get_expr(polwithcheck, polrelid) LIKE '%app.tenant_id%'", String.class);
        assertThat(bad).as("policies referencing forbidden GUC app.tenant_id").isEmpty();
    }

    @Test
    void contractListMatchesActualRlsTables() {
        List<String> actual = jdbcTemplate.queryForList(
            "SELECT relname FROM pg_class WHERE relrowsecurity = true AND relkind='r' " +
            "AND relnamespace = 'public'::regnamespace ORDER BY relname", String.class);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(TENANT_SCOPED_TABLES);
    }
}
```

`CONTRIBUTING.md` (or `docs/development/database.md`):

> Every new tenant-scoped table must, in the same Flyway migration:
> 1. `tenant_id UUID NOT NULL`
> 2. `ALTER TABLE … ENABLE ROW LEVEL SECURITY;`
> 3. `ALTER TABLE … FORCE ROW LEVEL SECURITY;`
> 4. SELECT/ALL policy reading `current_tenant_id()` (NOT `current_setting('app.tenant_id', true)`).
> 5. Index whose first column is `tenant_id`.
> 6. Append the table to `RlsContractTest.TENANT_SCOPED_TABLES`.
> CI fails on any omission.

Backfill helper (avoids the V16:24 disable-then-enable pattern repeating ad hoc):

```sql
CREATE OR REPLACE FUNCTION backfill_with_tenant_disabled(p_table text, p_sql text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I DISABLE ROW LEVEL SECURITY', p_table);
    EXECUTE p_sql;
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', p_table);
END; $$;
```

**Assistant deliberation.**

1. **Highest-leverage fix in this doc.** Four retro-patches × ~6 weeks calendar each = ~6 months sunk cost. A 2s test prevents the next one.
2. **Partition tables** — when Finding 9 ships, `pg_indexes` query needs to walk parent + partitions. Update the test then; monolithic-only for now.
3. **Annotation alternative rejected.** `@TenantScoped` on entities + Spring boot inspection = magic. Hardcoded list + the assistant's `contractListMatchesActualRlsTables` test catches the omission case.
4. **Down-script reality.** Forward-only is a real policy at mature shops. Document the compensating-migration mechanism in `docs/runbooks/migrations.md` so the next on-call knows: undoing V35 = ship V36 with reversed DDL.

**Reconciled position.** Adopt all four contract tests. Add `CONTRIBUTING.md` section + `docs/runbooks/migrations.md`. Add `backfill_with_tenant_disabled` to V35.

---

## Dependency graph

```
Finding 11 (RlsContractTest, CONTRIBUTING)
  └── must merge first; gates every subsequent migration

Finding 4 (GUC pool discipline: connection-init-sql, CI grep)
  └── independent; ship in parallel with Finding 11

Finding 1 (reviews policy fix)
  └── must precede ─→ Finding 2 (FORCE on reviews)

Finding 3 (tenants RLS)
  ├── requires app change: TenantEnumerationService (PR-N)
  ├── must precede ─→ V35 (PR-N+1)
  └── consumed by ─→ Finding 8 (flusher uses TenantEnumerationService)

Finding 5 (indexes + drops)  → independent; CONCURRENTLY non-tx
Finding 6 (N+1 EntityGraph)  → benefits from Finding 5
Finding 7 (cache evict)      → coordinates with backend pair (01)
Finding 8 (flusher refactor) → depends on Finding 3
Finding 9 (audit partitioning) → design now, V40+
Finding 10 (featured_products normalisation)
  ├── V35.2 add table + backfill
  └── V36 drop column (28-day soak + app cutover)
```

---

## Wave breakdown

**Wave 1 — gates everything else**
- Finding 11: `RlsContractTest` + `CONTRIBUTING.md` (no schema change yet, lock in the contract).
- Finding 4: `connection-init-sql: "RESET ALL"` + `scripts/check-guc-discipline.sh`.

**Wave 2 — PR-N (Java)**
- Finding 3 part A: `TenantEnumerationService`; update `PaymentEventOutboxFlusher` and `ScheduledCleanupService` callers.

**Wave 3 — V35 (transactional)**
- Finding 1: reviews policy fix + UPDATE/DELETE policies.
- Finding 2: FORCE on 9 tables.
- Finding 3 part B: tenants RLS + admin policy.
- Finding 11: `backfill_with_tenant_disabled()` helper.
- Update `RlsContractTest.TENANT_SCOPED_TABLES`. CI must be green.

**Wave 4 — V35.1 (non-transactional, CONCURRENTLY)**
- Finding 5: composite indexes + drop duplicate `idx_orders_*` + drop `vat_rate_enum`.

**Wave 5 — application changes**
- Finding 6: `@EntityGraph` + Pageable overloads + 200-cap on KDS.
- Finding 7: `TenantCacheEvictor.evictAllForTenant` + caller updates (with backend pair 01).
- Finding 8: per-tenant transactions + `SELECT FOR UPDATE SKIP LOCKED`.

**Wave 6 — V35.2 (transactional)**
- Finding 10: `shop_featured_products` table + backfill (no column drop yet).

**Wave 7 — application cutover for featured products**
- `Shop.java`/`ShopService`/storefront read paths query the new table.

**Wave 8 — V36 (after 28-day soak)**
- `ALTER TABLE shops DROP COLUMN featured_product_ids;`

**Wave 9 — design only, no code (queue for later)**
- Finding 9: monthly partitioning strategy in `docs/runbooks/data-retention.md`. `pg_partman` POC in staging. V40 deferred until daily volume × 30 × 7 > 1M `orders_aud` rows/month.

---

## Open questions

1. **Is the runtime app role `NOBYPASSRLS`?** V2:14–18 attempts to set it but swallows the exception. Verify with `SELECT rolname, rolbypassrls FROM pg_roles WHERE rolname = 'jtoye_app';` against staging and prod. If `rolbypassrls = true` then FORCE is meaningless for the runtime path. Add as a deployment runbook pre-deploy check.

2. **Are there callers of `tenants` outside the two scheduled jobs?** `grep -rn "FROM tenants\|TenantRepository" core-java/src/main/java` before merging Finding 3. Anything calling `findAll()` from the app role will silently break post-V35 unless covered by the `id = current_tenant_id()` branch.

3. **What is the actual production tenant count?** Findings 8 and 9 scale arguments depend on this. Today the codebase has 13 seed tenants. <50 → flusher refactor is "wasteful but not blocking"; >500 → real risk. Confirm before prioritisation.

4. **Does anything depend on `vat_rate_enum`?** Run the `pg_depend` check before V35.1 `DROP TYPE`. Stray view/function/column reference will fail the drop.

5. **Does `customers_aud` exist?** The audit lists six audited entities; verify the table exists before adding to `RlsContractTest.TENANT_SCOPED_TABLES`.

6. **Is `pg_partman` in the dependency budget?** Standard PG partitioning helper but adds an extension dependency. Confirm with DevOps that staging/prod can install extensions, or fall back to handwritten partition cronjobs.

7. **Coordination with security pair (02) on `customer_email` GUC.** Their fix to the storefront IDOR (`verify` mandatory on `GET /public/orders`) closes the application-side leak; our Finding 4 closes the pool-side leak. Their fix should ship first; ours is defence in depth.

---

**End of remediation doc.** 11 findings, 1 follow-up (V36 column drop), 1 deferred (V40+ partitioning), 7 open questions to resolve before merging Wave 3.

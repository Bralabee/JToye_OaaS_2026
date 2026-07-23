---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
reviewed: 2026-07-21T12:49:18Z
depth: standard
files_reviewed: 26
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
  - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
  - core-java/src/main/java/uk/jtoye/core/security/access/StaffController.java
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaff.java
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java
  - core-java/src/main/java/uk/jtoye/core/security/access/Membership.java
  - core-java/src/main/java/uk/jtoye/core/security/access/GrantSource.java
  - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryRepository.java
  - core-java/src/main/java/uk/jtoye/core/security/access/dto/MyAccessDto.java
  - core-java/src/main/java/uk/jtoye/core/security/access/dto/DirectoryEntryDto.java
  - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffMemberDto.java
  - core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductLabelService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
  - core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
  - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
  - core-java/src/main/resources/application.yml
  - core-java/src/main/resources/db/migration/V57__shop_staff_grant_source.sql
  - frontend/lib/shops-api.ts
  - frontend/lib/staff-api.ts
  - frontend/components/dashboard/shop-switcher.tsx
  - frontend/components/dashboard/shop-switcher-provider.tsx
  - frontend/components/dashboard/dashboard-shell.tsx
  - frontend/app/dashboard/staff/page.tsx
findings:
  critical: 1
  warning: 2
  info: 2
  total: 5
status: issues_found
---

# Phase 23: Code Review Report (gap-closure wave, plans 23-08..23-16)

**Reviewed:** 2026-07-21T12:49:18Z
**Depth:** standard
**Files Reviewed:** 26 (production source only; the 18 test files touched by the wave were not counted, per review scope)
**Status:** issues_found

## Summary

I reviewed the Phase 23 gap-closure production changes with a FORCE stance, focusing on the five risk areas the task called out: fail-open access paths, tenant/RLS boundary correctness, the fail-closed principal logic in `ShopAccessService`, the strict-scoping tightening + bootstrap-admin lockout guard, and V57 SQL/RLS safety.

The **access-control core is, on balance, correct and fail-closed.** I traced the principal ladder (`isGroupAdmin` → `isGroupAdminForUser` → `isBootstrapAdmin`), the shared HTTP/STOMP decision funnel (`canAccessShop`), the CR-04 null-shop write/read pairing, the CR-01/WR-01 cache-bypass relocation (loaders on distinct beans + `self()` proxy for `resolveMembership`), the CR-06 pessimistic-lock last-admin guard, and the CR-07 strict-scoping de-honour path. Each holds up: unparseable/anonymous principals fail to a typed 403, strict-ON de-honouring lands scoped users at deny-by-default, and the bootstrap rule cannot strand a tenant at zero GROUP_ADMINs. The STOMP kitchen shop-gate correctly resolves identity from the session principal (not the ambient context that would inherit the internal-caller bypass) and pins/clears the tenant GUC in a `finally`. No re-introduced auth bypass was found in the Java gate.

However, the wave introduced **one BLOCKER in the V57 migration**: its "deterministic backfill" runs a bare `UPDATE` with no tenant GUC against a FORCE-RLS table, so under the production (RLS-bound) migration role it updates **zero rows**, and the subsequent `SET NOT NULL` fails on any database that already holds `shop_staff` rows. This is the exact defect class the codebase documented in V44 ("the V25 mistake … must not be repeated") and it is invisible to the Testcontainers suite because every test DB is fresh. Two WARNINGs cover a re-introduced cross-tenant cache-blast in `BulkImportService` and a broken empty control in `shop-switcher.tsx`.

## Structural Findings (fallow)

No `<structural_findings>` block was provided with this review request; no structural pre-pass to reconcile.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: V57 backfill runs a no-GUC `UPDATE` against FORCE-RLS `shop_staff` — updates zero rows, then `SET NOT NULL` fails on any populated database

**File:** `core-java/src/main/resources/db/migration/V57__shop_staff_grant_source.sql:46-64`

**Issue:**
V52 created `shop_staff` with `ENABLE` + `FORCE ROW LEVEL SECURITY` and the policy `USING (tenant_id = current_tenant_id())` (V52 lines 46-53). During a Flyway migration there is **no `app.current_tenant_id` GUC pinned** (`TenantSetLocalAspect` only pins it around transactional *application* service calls), so `current_tenant_id()` returns `NULL` (V51 hardened it to return NULL, not error, on an unset GUC). The policy predicate `tenant_id = NULL` is never TRUE, so a FORCE-RLS table is invisible to the migration role for every row.

The V57 backfill is a flat statement with no GUC handling, no tenant loop, and no `DISABLE ROW LEVEL SECURITY`:

```sql
UPDATE shop_staff
   SET grant_source = CASE WHEN created_by IS NULL THEN 'JIT' ELSE 'OPERATOR' END
 WHERE grant_source IS NULL;
```

Consequences on any DB that **already holds `shop_staff` rows** (the canonical Docker-Compose dev DB, which ran V52 and JIT-provisioned rows throughout Phase 23; and any environment where V52 shipped before V57):
- The backfill matches **zero** rows (they are RLS-hidden from the no-GUC role). Existing rows keep `grant_source = NULL`.
- The CHECK add succeeds (NULL is `unknown`, not `FALSE`, so it passes a CHECK) and `SET DEFAULT 'JIT'` succeeds — masking the problem — but
- `ALTER TABLE shop_staff ALTER COLUMN grant_source SET NOT NULL;` (line 64) then **fails** with `column "grant_source" contains null values`, aborting the migration and leaving the schema in a failed-migration state (app won't boot).

The migration runs as `${DB_USER:jtoye_app}` (there is no `spring.flyway.user` override) — the RLS-bound app role, non-superuser, no BYPASSRLS — which is exactly the role V44 was written for ("works for any role with UPDATE privilege (owner or not)"). This is the same defect V44 fixed for `products`/`shops` and explicitly warned against: *"V25 mistake (bare UPDATE, no GUC, zero rows visible) must not be repeated."* The V57 header's claim — *"Backfill pre-V57 rows deterministically … so no existing row is left ambiguous"* — is **false under RLS**.

The Testcontainers suite (`StrictScopingTighteningIntegrationTest` et al.) cannot catch this: each test DB is fresh, so V52 `CREATE TABLE` and V57's backfill both run against an empty table (zero rows → `SET NOT NULL` trivially succeeds). Green-by-construction.

**Fix:** Backfill under the tenant GUC exactly as V44 does — walk the (RLS-free) `tenants` registry and set the GUC transaction-locally per tenant so RLS-bound rows are visible:

```sql
DO $$
DECLARE t RECORD;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);
    UPDATE shop_staff
       SET grant_source = CASE WHEN created_by IS NULL THEN 'JIT' ELSE 'OPERATOR' END
     WHERE grant_source IS NULL
       AND tenant_id = t.id;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);
END $$;
```

Only then run steps 3-4 (CHECK / DEFAULT / NOT NULL / `_aud` mirror). (Alternatively, a scoped `ALTER TABLE shop_staff DISABLE ROW LEVEL SECURITY;` … `ENABLE;` around the single UPDATE, the V16 owner-only precedent — but the V44 tenant-loop is the established, owner-agnostic pattern.) After fixing, add a migration test that seeds a `shop_staff` row **before** asserting V57 leaves no `grant_source IS NULL` row, so the fresh-DB blind spot is closed.

## Warnings

### WR-01: `BulkImportService` re-uses the cross-tenant `@CacheEvict(allEntries = true)` anti-pattern the wave removed from `createProduct`/`updateProduct` — and it is unnecessary on a create-only path

**File:** `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java:65` and `:153`

**Issue:**
`ProductService.createProduct` (lines 62-70) and the wave's own comments document that `@CacheEvict(value = "products", allEntries = true)` was deliberately removed because it "nuked every tenant's cache on every create" — a cross-tenant blast on the shared, tenant-keyed `products` cache region. Yet both bulk-import entry points still carry it:

```java
@CacheEvict(value = "products", allEntries = true)
public BulkImportResult importFromCsv(MultipartFile file) { ... }

@CacheEvict(value = "products", allEntries = true)
public BulkImportResult importFromImages(MultipartFile[] files) { ... }
```

Both paths only ever `productRepository.save(new Product())` (never update an existing product), so — by the identical reasoning applied to `createProduct` — there is **no pre-existing cache entry to invalidate**, and the annotation is both unnecessary and a cross-tenant eviction of every other tenant's cached products. It is not a correctness/security defect (over-eviction only forces cache misses), so it is a WARNING, but it directly contradicts the CR-fix rationale the wave applied one class over.

**Fix:** Remove `@CacheEvict(value = "products", allEntries = true)` from both `importFromCsv` and `importFromImages`. Newly-created products have no cached entry; nothing needs eviction. If a future bulk-*update* path is added, evict per-id via `TenantCacheEvictor.evictEntity("products", "getProductById", id)` for each touched id (tenant-scoped), never `allEntries`.

### WR-02: `ShopSwitcher` renders an empty, value-less `<select>` for a non-GROUP_ADMIN who has zero granted shops

**File:** `frontend/components/dashboard/shop-switcher.tsx:39-45, 86-113`

**Issue:**
For a non-GROUP_ADMIN whose granted-shop list is empty (all grants revoked while logged in, or a strict-scoping tenant where the user holds none), the component falls through both early returns (`loading`, and the `!isGroupAdmin && shops.length === 1` pinned case) into the dropdown render. There:

```js
const selected =
  rawContext !== ALL_SHOPS_CONTEXT
    ? rawContext
    : isGroupAdmin || shops.length === 0
      ? ALL_SHOPS_CONTEXT   // -> "all"
      : shops[0].id
```

so `selected === "all"`, but the `<select>` renders **no** `ALL_SHOPS_CONTEXT` option (that option is `isGroupAdmin`-gated, line 107) and **no** shop options (empty list). The result is a controlled `<select value="all">` with zero `<option>` children — a blank, non-functional control plus a React "value not in options" warning, shown to a user who has effectively lost all shop access. The `stale` notice only fires when a *specific saved shop* was revoked, not for a clean load into a zero-grant state, so there is no user-facing explanation.

**Fix:** Handle the zero-shop non-admin case explicitly, mirroring the pinned single-grant branch — e.g. render an access-required/no-shops notice instead of a select:

```jsx
if (!isGroupAdmin && shops.length === 0) {
  return (
    <div data-testid="shop-switcher" className={cn("...", className)} role="status">
      No shop access — ask a group admin to grant you a shop.
    </div>
  )
}
```

## Info

### IN-01: STOMP shop-gate is hard-coded to the single `kitchen` feature — a latent gap for any future shop-scoped topic

**File:** `core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java:41-42, 152-154`

**Issue:**
`validateSubscription` only grant-checks the shop segment when `KITCHEN_FEATURE.equals(parts[2])`. That is correct today — `OrderStateChangeListener` (`/topic/kitchen/{tenantId}/{shopId}`) is the only shop-scoped publisher — so this is not a live vulnerability. But the gate is keyed on a string literal rather than a structural property, so a future `/topic/{feature}/{tenantId}/{shopId}` topic that carries shop-scoped data would silently get only the tenant wall (cross-shop-within-tenant leak) until someone remembers to extend this switch.

**Fix:** When adding any new shop-segmented topic, add its feature to the shop-gate (or generalise the gate to "any topic whose convention includes a `parts[4]` shop segment must pass `canAccessShop`"). A `TenantChannelInterceptorShopGateIntegrationTest` case asserting a non-kitchen shop-segmented topic is gated would prevent regression.

### IN-02: Masked directory email is rendered twice in the staff grant picker

**File:** `frontend/app/dashboard/staff/page.tsx:293-296`

**Issue:**
`WR-10` now masks directory emails server-side (`a***@example.com`). The grant-target option renders `{(d.displayName || d.email) + " (" + d.email + ")"}`, so when `displayName` is null the operator sees the same masked string duplicated — `a***@example.com (a***@example.com)`. Cosmetic only; the grant keys on `userId`, so functionality is unaffected.

**Fix:** Only append the parenthesised email when a `displayName` exists: `{d.displayName ? `${d.displayName} (${d.email})` : d.email}`.

---

_Reviewed: 2026-07-21T12:49:18Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

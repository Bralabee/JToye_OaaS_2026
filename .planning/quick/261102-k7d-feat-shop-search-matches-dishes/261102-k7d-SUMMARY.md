---
phase: quick
plan: 261102-k7d
subsystem: storefront / public search + products RLS
tags: [search, fts, rls, storefront, v89]
status: complete
issue: none (external tester report 2026-11-02)
requires: []
provides:
  - "V89 products_public_read (FOR SELECT, current_tenant_id() IS NULL, available product of a published shop)"
  - "ShopRepository.fullTextSearchPublished matches a shop through its available dishes; own-vector matches rank first"
  - "PublicStorefrontService.searchPublishedShops runs the text search tenant-less"
  - "ProductService GROUP_ADMIN list/search return an empty page when no tenant is pinned"
affects: []
tech-stack:
  added: []
  patterns: ["query-time OR EXISTS over a second tsvector; public-read RLS in the V87 shape"]
key-files:
  created:
    - core-java/src/main/resources/db/migration/V89__products_public_read.sql
    - core-java/src/test/java/uk/jtoye/core/storefront/ShopSearchMatchesDishesIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductsPublicReadRlsIntegrationTest.java
    - .planning/quick/261102-k7d-feat-shop-search-matches-dishes/evidence/red-green.txt
    - .planning/quick/261102-k7d-feat-shop-search-matches-dishes/evidence/audit.md
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - docs/CHANGELOG.md
decisions:
  - "owner-session ruling 2026-11-02: products_public_read policy (V87 shape) because products had no anonymous read policy; no-migration premise withdrawn"
  - "The text search runs with no tenant pinned and restores the caller's tenant, so a signed-in caller gets the same cross-tenant dish matches (the shop half is unchanged: published = true is in the query and shops_public_read admits every published shop)"
  - "ProductService GROUP_ADMIN branches answer an empty page with no tenant (what RLS answered before V89) rather than throwing, so no caller sees a behaviour change"
metrics:
  completed: 2026-11-02
plan_head_before: 9b737e61
---

# Quick 261102-k7d: The kitchen search finds a kitchen by the dishes it sells

`GET /api/v1/public/shops?q=pounded yam` now returns a published kitchen that sells an available
"Pounded yam with egusi", even though nothing in the kitchen's own name, tags, description or address
names the dish. Shops whose own vector matches still rank first. Shops matched only through a dish
follow, then name and id. The count query has the same WHERE.

## Why a migration after all

The brief ruled "no migration" on the premise that a published shop's products were readable
anonymously. They were not. `products` has had only `products_rls_policy` (tenant-scoped) since V2. A
probe on 9b737e61 ran the products-aware query with no tenant: as the Testcontainers superuser it
returned `[Mama's Kitchen]`, and as a NOSUPERUSER NOBYPASSRLS role it returned `[]`. The owner session
withdrew the premise and ruled V89, a FOR SELECT policy in the V87 shape.

## Final SQL shape

```sql
SELECT s.* FROM shops s
 WHERE s.published = true
   AND (s.search_vector @@ plainto_tsquery('english', :q)
        OR EXISTS (SELECT 1 FROM products p
                    WHERE p.shop_id = s.id AND p.tenant_id = s.tenant_id AND p.available = true
                      AND p.search_vector @@ plainto_tsquery('english', :q)))
 ORDER BY COALESCE(s.search_vector @@ plainto_tsquery('english', :q), false) DESC,
          COALESCE(ts_rank(s.search_vector, plainto_tsquery('english', :q)), 0) DESC,
          s.name ASC, s.id ASC
-- countQuery: SELECT COUNT(*) with the identical WHERE
```

## Audit

Every `ProductRepository` method and every call site that can run with no tenant is listed in
`evidence/audit.md` (21 rows). There was one class of finding: the GROUP_ADMIN branches of
`ProductService.listProducts` and `ProductService.search` (`findAll`, `findByAvailableTrue`,
`searchFullText`) have no tenant predicate, and a realm admin or a `SystemPrincipal` caller reaches them
without a tenant. With V89 alone, such a caller listed another tenant's product (expected 0, got 1). With
no tenant pinned, those branches now return an empty page. Every other path either pins a tenant,
carries its own tenant or shop predicate, or fails closed at the shop gate or the cache-key generator.

## Evidence

Full red/green record: `evidence/red-green.txt`.

| Gate | Fail direction | After |
|------|----------------|-------|
| ShopSearchMatchesDishesIntegrationTest (NOSUPERUSER, no tenant) | 9b737e61: tests=6 failures=4 | tests=6 failures=0 |
| ProductsPublicReadRlsIntegrationTest | 9b737e61: failures=2 (anonymous reads); V89 without the ProductService fix: failures=2 (the audit finding) | tests=11 failures=0 |
| Policy break arm (FOR ALL USING (true) WITH CHECK (true)) | failures=8: every negative read, the IS NULL guard, INSERT, UPDATE, DELETE | restored, diff empty |
| Availability break arm (predicate removed from policy and query) | the plantain test fails | restored |
| Tenant-clear break arm | the signed-in-caller test fails | restored |
| RlsContractTest | no exemption added | 7/7 |
| ProductSearchFtsIntegrationTest | n/a | 17/17 in two runs, no flake |
| Full suite (`:core-java:test :core-java:integrationTest`) | n/a | rc 0; unit 2083 tests and integration 1430, 0 failures, 1 skip each |
| check-doc-metrics.sh | 12 FAIL after `--write` | 37/37 PASS |
| check-branch-behind-base.sh | fail direction not re-executed here | rc 0 |

## Deviations

- **The premise was withdrawn mid-lane** (above). The lane stopped, reported, and resumed on the
  owner-session ruling.
- **The worktree was replaced.** The harness removed the original agent worktree between turns, so the
  lane continued in a new linked worktree, `.claude/worktrees/lane-b-shop-search`, on the same branch
  name. The old branch had no commits.
- **Break arm C was broader than intended.** Its `sed` also commented out five other
  identically-indented `TenantContext.clear()` lines in other methods. All were restored by
  `git checkout`, and `git diff --quiet HEAD` returned rc 0.
- **One criterion has no single-break fail direction.** "The unpublished kitchen never appears" is held
  by shops RLS and by the query predicate, independently. Its products-side half is proven red by arm A.

## Not verified here

- **Frontend routing** (out of this lane's scope). The landing `HeroSearch` and the `/shop?q=` deep link
  are assumed to reach the discovery page, which calls `GET /api/v1/public/shops?q=`, the one endpoint
  changed here.
- **Query plans.** No EXPLAIN was taken of the new query on a production-sized catalogue. The products
  subquery is served by `idx_products_search` only where `ts_match_vq` is LEAKPROOF (V44 notes it needs
  a superuser), and the RLS EXISTS on `shops` runs per candidate product.
- **Runtime parity:** N/A. No runtime was rebuilt or deployed, by instruction.

## Quality dimensions

- Security: one widened SELECT policy, scoped to the public menu rows; audit and NOSUPERUSER proofs above.
- SEO and web-perf: no page changed. Search results on the public storefront gain recall.
- Agent-readiness: no contract change, so the OpenAPI snapshot is untouched.
- Client-persisted identity: N/A.
- Falsifiability: every criterion has a recorded fail direction, or is named above where it has none.

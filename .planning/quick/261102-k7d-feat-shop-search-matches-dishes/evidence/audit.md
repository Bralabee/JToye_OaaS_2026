# Audit: every read of `products` that can run with NO tenant pinned (V89 `products_public_read`)

Read against origin/main 9b737e61. V89 changes what a read of `products` returns only when
`current_tenant_id() IS NULL`. `TenantSetLocalAspect` pins the GUC from `TenantContext` before every
repository / `JdbcTemplate` call and resets it to DEFAULT when `TenantContext` is empty, so "no tenant"
means `TenantContext` is empty at the call. That happens for: anonymous `/api/v1/public/**` requests that
do not pin a shop's tenant; an authenticated JWT with no `tenant_id`/`tenantId`/`tid` claim
(`JwtTenantFilter` logs a WARN and leaves the context unset, it does not refuse); and background work
that does not set a tenant.

Who could reach a gated product read with no tenant: `ShopAccessService.isGroupAdmin()` returns true for a
realm `admin` and for a declared `SystemPrincipal.asSystem` caller WITHOUT consulting the tenant. Every
other caller with no tenant fails the gate (no grants), or fails `requireShopInCallerTenant`, which throws
`ShopAccessDeniedException` when no tenant is pinned.

Search scope: every `productRepository.` call site in `core-java/src/main/java`, plus every SQL string or
JPQL naming `products` / `Product` outside `ProductRepository` (only `MediaAssetRepository`), plus every
RLS policy on another table that references `products` (none). There is no JPA association to `Product`.
The MCP server and edge-go reach products only through the REST API (JWT; covered by the ProductService
rows). No `@Scheduled` or `@RabbitListener` class calls `ProductRepository` directly.

| # | Repository method | Call site(s) | Can run with no tenant? | Own tenant or shop predicate? | Verdict |
|---|---|---|---|---|---|
| 1 | `findBySku` | `DemoDataSeeder` (`@Profile("dev")`) | No: sets `TenantContext` (DEMO_TENANT, TENANT_B) before each transaction | none | Safe (pinned) |
| 2 | `findByTenantIdAndSku` | `SyncService` | tenant passed from JWT | `tenant_id` | Safe |
| 3 | `findTenantScopedInGrantSetOrTenantWide` | `ProductService.listProducts` (scoped user) | tenant `orElseThrow` first | `tenant_id` | Safe |
| 4 | `findByAvailableTrue` | `ProductService.listProducts` GROUP_ADMIN branch | **Yes**: realm admin or system caller with no tenant claim | **none** | **FINDING, fixed**: empty page when no tenant |
| 5 | `findAll(Pageable)` (inherited) | same branch | **Yes** | **none** | **FINDING, fixed**: same guard |
| 6 | `findAvailableTenantScopedInGrantSetOrTenantWide` | `ProductService.listProducts` (scoped) | tenant `orElseThrow` | `tenant_id` | Safe |
| 7 | `findAvailableOrderedByCategory` | no caller in main | n/a | none | Dormant, recorded; a future no-tenant caller would need a predicate |
| 8 | `findAvailableByShopOrderedByCategory` | `PublicStorefrontService.getShopProducts` | No: `resolvePublicShopForSlug` pins the shop's tenant | `shop_id` | Safe |
| 9 | `findByFeaturedTrueAndAvailableTrue` | no caller in main | n/a | none | Dormant, recorded as for #7 |
| 10 | `findByShopId(UUID)` | `AllergenCompletenessGate.evaluate`, from `GateChainRunner.runAndRecompute` (`@Async`) and `VendorOnboardingService.refreshAllergenGate` (GO_LIVE / REINSTATE transition) | Runner: No, it sets `TenantContext` in a try/finally (GateChainRunner ~line 123). Transition: runs on an onboarding row already loaded under `vendor_onboarding` FORCE RLS, so a no-tenant caller never reaches it | `shop_id` | Safe; and the shop is unpublished at GO_LIVE / REINSTATE (onboarding is the sole writer of `published`), so V89 would admit none of its products anyway |
| 11 | `findByShopId(UUID, Pageable)`, `findByShopIdAndAvailableTrue` | `ProductService.listProductsByShop` after `require(shopId, STAFF)` | gate fails closed with no tenant | `shop_id` | Safe |
| 12 | `searchFullTextByShop` | `ProductService.search(q, shopId)` after `require` | gate fails closed | `shop_id` | Safe |
| 13 | `searchFullText` | `ProductService.search` GROUP_ADMIN branch | **Yes** | **none** | **FINDING, fixed**: empty page when no tenant |
| 14 | `searchFullTextInGrantSetOrTenantWide` | `ProductService.search` (scoped) | tenant `orElseThrow` | `tenant_id` | Safe |
| 15 | `findByTenantIdAndShopIdOrderByDisplayOrderAscCreatedAtAsc` | `ProductService.copyProducts` | tenant `orElseThrow` | `tenant_id` | Safe |
| 16 | `existsByTenantIdAndSku` | `ProductService` create/copy, `CulinaryPackSeedService` | tenant passed | `tenant_id` | Safe |
| 17 | `countOtherProductsReferencingImage` | `ProductService` image delete | tenant passed | `tenant_id` | Safe |
| 18 | `findById` (inherited) | `ProductCacheLoader.getProductById` | key generator `TenantAwareCacheKeyGenerator` throws `IllegalStateException` with no tenant, before any read | id | Safe (fails closed) |
| 18a | `findById` | `ProductService` update/patch/image/delete paths, `ProductLabelService`, `MediaAssetService` (x2) | a no-tenant realm admin could load a public product, then `require(shopId, ...)` runs `requireShopInCallerTenant`, which throws with no tenant | id | Safe (fails closed); writes are unaffected, V89 is FOR SELECT only |
| 18b | `findById` | `OrderService.createOrder` | tenant `orElseThrow` before the read | id | Safe |
| 18c | `findById` | `PublicStorefrontService` guest order | No: shop's tenant pinned by `resolvePublicShopForSlug`; also refuses a product of another shop | id + shop check | Safe |
| 19 | `findAllById` (inherited) | `StockService` (ids taken from an order already read under its tenant), `PublicStorefrontService.getShopConfig` featured list (pinned) | ids bound to an already-scoped row | id | Safe |
| 20 | (native) `MediaAssetRepository` reference count over `products` | media erasure | tenant passed | `tenant_id` | Safe |
| 1a | `findAll()` (inherited, unpaged) | `DemoDataSeeder.quarantineNonCurated` (line 938, `@Profile("dev")`) | No: `run()` sets `TenantContext(DEMO_TENANT)` before `transactionTemplate.execute(seed())`, and `seed()` calls it (line 403) | none | Safe (pinned, dev only). Row added after the tenancy review of #1098 found it missing |
| 21 | (native) `ShopRepository.fullTextSearchPublished`: products arm of the UNION | public search | **Yes, by design** for an anonymous caller (reads through V89). A caller carrying a tenant keeps it (the clear/restore was removed after review, ruling 2026-11-02) and reads only its own tenant's products | `(p.shop_id, p.tenant_id)` must equal `(s.id, s.tenant_id)`; `p.available` | Intended consumer of V89; reads only the pair, never a product column |
| 22 | (JPQL) `ShopRepository.searchPublished`: products EXISTS in the LIKE tier | public search, LIKE tier | **Yes, by design**, as row 21 | `p.shopId = s.id AND p.tenantId = s.tenantId AND p.available` | Intended consumer of V89; reads only existence |

Findings 4, 5 and 13 are one class (the GROUP_ADMIN branches of `ProductService.listProducts` and
`ProductService.search`) and share one fix: when `TenantContext` is empty the branch returns
`Page.empty(pageable)`, which is what RLS returned before V89. Proven: with V89 and without the fix,
`ProductsPublicReadRlsIntegrationTest` lists 1 product (expected 0) for both list and search under
`SystemPrincipal.asSystem` with no tenant (evidence/red-green.txt section 2); with the fix it lists 0, and
the tenant-pinned control still lists 2 and finds 1 (section 3).

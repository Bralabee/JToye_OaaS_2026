# Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav - Pattern Map

**Mapped:** 2026-07-19
**Files analyzed:** 29 (new + modified)
**Analogs found:** 27 / 29 (2 have no clean analog — flagged below)

> This phase is ~80% *composition of proven internal patterns* (RESEARCH §"Don't Hand-Roll"). Almost every new file has a strong, recently-modified analog already in the repo. The failure mode is NOT a missing library — it is copying the **wrong version** of an internal template (V47/V50 raw-cast RLS instead of the V51-corrected helper) or writing a migrate-time backfill that cannot exist. Each assignment below points at the *correct* analog and the exact excerpt to replicate.

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `db/migration/V52__shop_staff.sql` | migration | CRUD (schema) | `V43__vendor_onboarding.sql` (DO-block + `_aud`) + `V51` (safe helper) | exact (composite) |
| `security/access/ShopStaff.java` | model (entity) | CRUD | `onboarding/VendorOnboarding.java` (`@Audited`) | exact |
| `security/access/ShopStaffRepository.java` | repository | CRUD + read-scope | `shop/ShopRepository.java` (`findByTenantId`) | exact |
| `security/access/UserDirectory.java` | model (entity) | CRUD (no audit) | `onboarding/VendorOnboarding.java` (minus `@Audited`, composite PK) | role-match |
| `security/access/UserDirectoryRepository.java` | repository | CRUD | `shop/ShopRepository.java` | role-match |
| `security/access/ShopRole.java` | model (enum) | — | `onboarding/OnboardingState.java` / `OnboardingModel.java` | role-match |
| `security/access/ShopAccessService.java` | service | request-response + CRUD | `shop/ShopService.java` + `config/TenantCacheEvictor.java` + `KeycloakRealmRoleConverter.java` | composite |
| `exception/ShopAccessDeniedException.java` | exception | — | `exception/TenantAccessDeniedException.java` (shape) — **but distinct 403 type** | role-match |
| `exception/LastGroupAdminException.java` | exception | — | `exception/IdempotencyConflictException.java` (409 pattern) | role-match |
| `security/access/StaffController.java` | controller | request-response | `tenant/TenantAdminController.java` (`@PreAuthorize("hasRole('admin')")`) | exact |
| `security/access/dto/*` (GrantStaffRequest / StaffMemberDto / DirectoryEntryDto) | dto | — | `tenant/dto/CreateTenantRequest` + `TenantDto` (records) | role-match |
| `common/GlobalExceptionHandler.java` (MOD) | middleware | request-response | itself (`handleAccessDenied` / `handleIdempotencyConflict`) | exact |
| `resources/application.yml` (MOD) | config | — | existing `jtoye:` block (`jtoye.security.*`) | exact |
| `shop/ShopService.java` (MOD) | service | CRUD | itself (insert `require()` at method top) | in-place |
| `product/ProductService.java` (MOD) | service | CRUD | `ShopService` insertion pattern | in-place |
| `order/OrderService.java` (MOD) | service | CRUD + streaming | `ShopService` + `OrderSseService` (SSE fan-out — §3-FLAG) | in-place |
| `marketing/PromotionService.java` (MOD) | service | CRUD | `ShopService` insertion pattern | in-place |
| `marketing/AnnouncementService.java` (MOD) | service | CRUD | `ShopService` insertion pattern | in-place |
| `test/.../ShopStaffRlsPolicyIntegrationTest.java` | test (integration) | CRUD | `webhook/WebhookSubscriptionRlsPolicyIntegrationTest.java` | exact |
| `test/.../ShopAccessJitProvisionTest.java` | test (integration) | CRUD | WebhookSub RLS harness + `INSERT..ON CONFLICT` (V47/V50) | role-match |
| `test/.../ShopAccessEnforcementIntegrationTest.java` | test (integration) | request-response | WebhookSub RLS harness (role-downgrade) | role-match |
| `test/.../StaffManagementIntegrationTest.java` | test (integration) | request-response | WebhookSub RLS harness | role-match |
| `components/dashboard/shop-switcher.tsx` | component | event-driven | `dashboard/sidebar.tsx` (localStorage theme idiom) | role-match |
| `app/dashboard/staff/page.tsx` | component (page) | request-response | `app/dashboard/finance/page.tsx` (403 → access-required) | exact |
| `lib/shop-context.ts` (localStorage helper) | utility | — | `sidebar.tsx` theme persistence (lines 49-62) | role-match |
| `components/dashboard/sidebar.tsx` (MOD) | component | — | itself (`navigation` array + logo header) | in-place |
| `components/dashboard/dashboard-shell.tsx` (MOD) | component | — | itself (mobile top bar, lines 27-30) | in-place |
| `components/dashboard/__tests__/*` + `e2e/dashboard-mobile.spec.ts` (MOD) | test | — | `dashboard-shell.test.tsx` + `dashboard-mobile.spec.ts` (390px → add 375px) | in-place |
| `docs/metrics.json` + `qa/surface-ledger.json` (MOD) | config | — | `scripts/docs-freshness.sh --write` | tooling |

---

## Pattern Assignments

### `db/migration/V52__shop_staff.sql` (migration, CRUD-schema)

**Analogs:** `V43__vendor_onboarding.sql` (idempotent DO-block + `_aud` mirror) · `V51__rls_uuid_cast_safety.sql` (the **safe helper** — non-negotiable) · `V50`/`V47` (tenant table shape).

**CRITICAL LANDMINE:** copy V43's *structure* but its RLS `USING`/`WITH CHECK` uses the **raw cast** `current_setting('app.current_tenant_id', true)::UUID` (V43 lines 55-56). That form is now **build-failing** — `RlsContractTest#noPolicyUsesRawTenantGucCast` sweeps `pg_policy` and fails on any `current_setting('app.current_tenant_id'...` next to `::uuid`. Use the V51 helper `current_tenant_id()` instead. See RESEARCH §1-landmine + Pitfall 1.

**Table + RLS+FORCE + idempotent-policy DO-block** — replicate from `V43` lines 20-58, **substituting the helper**:
```sql
CREATE TABLE IF NOT EXISTS shop_staff ( ... );   -- V43 lines 20-45 shape
ALTER TABLE shop_staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_staff FORCE  ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='shop_staff' AND policyname='shop_staff_tenant_policy') THEN
    CREATE POLICY shop_staff_tenant_policy ON shop_staff
        FOR ALL
        USING      (tenant_id = current_tenant_id())     -- V51 helper, NOT ::uuid cast
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;
```

**`_aud` Envers mirror** — replicate from `V43` lines 104-165. Note the two mandatory differences from the base table: (1) **all columns nullable**, PK `(id, rev)`, FK `rev -> revinfo(rev)`; (2) the `_aud` RLS predicate **admits NULL tenant_id**: `USING (tenant_id IS NULL OR tenant_id = current_tenant_id())` (V51 lines 149-153 / V43 refunds_aud pattern). `user_directory` gets **NO `_aud`** (D-09).

**Full prescriptive DDL skeleton** already lives in RESEARCH §1 (lines 129-201) — use it verbatim as the starting point; it already uses the helper and puts `user_directory` in the same V52 slot (A3).

**Verified free-slot facts:** HEAD is V56; V52 free; `spring.flyway.out-of-order=true` set in all profiles (RESEARCH §1). Functional UNIQUE INDEX (not a table constraint) is required for the `COALESCE(shop_id, zero-uuid)` uniqueness.

---

### `security/access/ShopStaff.java` (model / entity, CRUD)

**Analog:** `onboarding/VendorOnboarding.java`

**Entity annotation + hand-written accessors** (VendorOnboarding lines 30-51, 87-99) — copy exactly: `@Entity @Table(name="shop_staff") @Audited`, `@Id @GeneratedValue(strategy=GenerationType.UUID)`, `@Column(name="tenant_id", nullable=false)`, `@CreationTimestamp` on `created_at`, hand-written getters/setters (house rule: **no Lombok on entities** — VendorOnboarding javadoc line 24-25). The `role` field mirrors VendorOnboarding's enum columns (lines 45-51): `@Enumerated(EnumType.STRING) @Column(nullable=false, length=16)`.

```java
@Entity
@Table(name = "shop_staff")
@Audited                                    // Envers -> shop_staff_aud (D-09: directory is NOT audited)
public class ShopStaff {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "user_id", nullable = false)   // Keycloak sub
    private UUID userId;
    @Column(name = "shop_id")                      // NULL = tenant-wide (GROUP_ADMIN shape)
    private UUID shopId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ShopRole role;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    // ... hand-written accessors (VendorOnboarding lines 87-128)
}
```

---

### `security/access/ShopStaffRepository.java` (repository, CRUD + read-scope)

**Analog:** `shop/ShopRepository.java`

**Read-scoping (D-01) is the key pattern here** — the whole point of read-scoping is a **tenant-scoped repository query**, exactly as `ShopRepository.findByTenantId(tenantId, pageable)` (lines 22, cited BE-03 comment lines 16-21) narrows the "my shops" list at the DB, not in the UI. For membership resolution add a finder keyed on `(tenantId, userId)`:
```java
public interface ShopStaffRepository extends JpaRepository<ShopStaff, UUID> {
    List<ShopStaff> findByTenantIdAndUserId(UUID tenantId, UUID userId);   // membership resolution (D-02/D-05 cache source)
    boolean existsByTenantIdAndRole(UUID tenantId, ShopRole role);         // last-GROUP_ADMIN guard (D-11)
    long countByTenantIdAndRole(UUID tenantId, ShopRole role);            // D-11 count-before-revoke
}
```
The grant-set the `ShopAccessService` read-helper produces then feeds a `... WHERE shop_id IN :grantedShopIds` filter on the *domain* repos (Product/Order/Promotion), mirroring the `@Query` custom-filter idiom in `ShopRepository` lines 24-25.

---

### `security/access/UserDirectory.java` + `UserDirectoryRepository.java` (model + repository, CRUD, no-audit)

**Analog:** `onboarding/VendorOnboarding.java` (entity shape) **minus `@Audited`** (D-09) + a **composite `@IdClass`/`@EmbeddedId`** on `(tenant_id, user_id)` (V52 PRIMARY KEY is composite, unlike VendorOnboarding's single-UUID PK). The throttled upsert (D-09) is NOT JPA save — it is a native `INSERT ... ON CONFLICT (tenant_id,user_id) DO UPDATE ... WHERE user_directory.last_seen < now() - :interval`, so the repository exposes a `@Modifying @Query(nativeQuery=true)` upsert method rather than relying on `save()`. RESEARCH §5 has the exact SQL.

---

### `security/access/ShopAccessService.java` (service, request-response + CRUD) — the core new file

**Analogs (composite):** `shop/ShopService.java` (`@Service @Transactional`, `TenantContext.get().orElseThrow`, `@Cacheable(keyGenerator="tenantAwareCacheKeyGenerator")`) · `config/TenantCacheEvictor.java` (evict-on-write) · `KeycloakRealmRoleConverter.java` (the `ROLE_admin` authority the D-03 bridge reads).

**Class + tenant-guard skeleton** (ShopService lines 22-47):
```java
@Service
@Transactional
public class ShopAccessService {
    // constructor injection (ShopService lines 32-40) — repos + TenantCacheEvictor
    UUID tenantId = TenantContext.get()
        .orElseThrow(() -> new IllegalStateException("Tenant context not set"));  // ShopService line 46-47
}
```

**Membership cache (D-05)** — mirror `ShopService.getShopById` (lines 81-89): `@Cacheable(value="shopMembership", keyGenerator="tenantAwareCacheKeyGenerator")` on `resolveMembership(UUID userId)`. The generator produces `tenant:{tid}:resolveMembership:{sub}` — already tenant-isolated (RESEARCH §4). **Evict-on-write** on grant/revoke reuses `TenantCacheEvictor.evictEntity("shopMembership", "resolveMembership", userId)` — it rebuilds the exact key (TenantCacheEvictor line 73) and evicts one entry. **Caveat (RESEARCH §4):** evict must run *after* the DB write commits.

**Realm-admin ⇒ implicit GROUP_ADMIN bridge (D-03/D-04 fail-safe)** — read the authority `KeycloakRealmRoleConverter` already emits (`ROLE_admin`, converter lines 47-50):
```java
boolean isRealmAdmin = SecurityContextHolder.getContext().getAuthentication()
    .getAuthorities().stream().anyMatch(a -> "ROLE_admin".equals(a.getAuthority()));
if (isRealmAdmin) return; // implicit GROUP_ADMIN — allow without consulting shop_staff
```

**JIT provision + directory upsert (D-04/D-09) — Pitfall 4:** do this **inside `ShopAccessService`** on the first `require()`/`resolveMembership()` of the request (already `@Transactional`, so `TenantSetLocalAspect` has pinned the GUC), **NOT** inside `JwtTenantFilter` (raw filter has no tx / no GUC). Use the house reserve idiom `INSERT ... ON CONFLICT DO NOTHING` (V47/V50) on `uq_shop_staff_tenant_user_shop` for race-safety. `jwt.getSubject()` (JwtTenantFilter reads the `Jwt` principal at lines 57-62) is the `sub` → `user_id`.

**Strict-scoping switch (D-12):** inject a config flag (default OFF) read at the top of the JIT branch — see application.yml assignment below.

---

### `exception/ShopAccessDeniedException.java` (exception) + `LastGroupAdminException.java`

**Analog (shape):** `exception/TenantAccessDeniedException.java` — a one-line class extending Spring's `AccessDeniedException`:
```java
public class TenantAccessDeniedException extends AccessDeniedException {
    public TenantAccessDeniedException(String message) { super(message); }
}
```

**KEY DIVERGENCE (RESEARCH §6):** `TenantAccessDeniedException` maps to the **generic** 403 (`handleAccessDenied`, type `.../forbidden`). The shop-scope 403 must be **provably distinct** from both the RLS 404 AND the generic admin 403, so the D-13 frontend can key on it. Therefore do **NOT** simply extend `AccessDeniedException` and reuse `handleAccessDenied` — either extend it but add a dedicated `@ExceptionHandler(ShopAccessDeniedException.class)` (more specific handler wins) returning a **distinct type** `https://jtoye.uk/errors/shop-access-denied` with machine-parseable props `shopId` + `requiredRole`, or make it a plain `RuntimeException` with its own handler. `LastGroupAdminException` follows the `IdempotencyConflictException` **409** pattern (GlobalExceptionHandler lines 321-327).

---

### `security/access/StaffController.java` (controller, request-response)

**Analog:** `tenant/TenantAdminController.java`

**Class-level admin gate + Swagger annotations** (TenantAdminController lines 49-66) — but scope to GROUP_ADMIN (realm-admin implicit via D-03, enforced inside `ShopAccessService`, since `@PreAuthorize("hasRole('admin')")` alone would exclude a non-realm-admin GROUP_ADMIN):
```java
@RestController
@RequestMapping("/api/v1/staff")
@Tag(name = "Staff", description = "Vendor shop-staff management: list / grant / revoke")
@SecurityRequirement(name = "bearer-jwt")
public class StaffController {
    // constructor injection (TenantAdminController lines 60-66)
    // GET /api/v1/staff            -> list directory + current grants  (like `list()` lines 84-88)
    // POST /api/v1/staff/grant     -> grant(user_id, shop_id|null, role)
    // DELETE /api/v1/staff/{id}    -> revoke; last-GROUP_ADMIN -> 409 (D-11)
}
```
Gate enforcement: call `shopAccessService.requireGroupAdmin()` at the top of each method (D-02 explicit service call), OR class-level `@PreAuthorize` composed with the bridge. **Location + Swagger `@ApiResponses`** copy TenantAdminController lines 68-88.

**DTOs** — model as **Java records** like `tenant/dto/TenantDto` + `CreateTenantRequest` (referenced in TenantAdminController imports lines 22-23); `@Valid @RequestBody` on the grant request (TenantAdminController line 75).

---

### `common/GlobalExceptionHandler.java` (MODIFIED, middleware)

**Analog:** itself — add two handlers next to the existing ones. **`handleAccessDenied`** (lines 221-227) is the exact template for the new distinct-type 403; **`handleIdempotencyConflict`** (lines 321-327) is the template for the 409:
```java
@ExceptionHandler(ShopAccessDeniedException.class)
public ProblemDetail handleShopAccessDenied(ShopAccessDeniedException ex) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Shop access denied");
    p.setTitle("Shop Access Denied");
    p.setType(URI.create("https://jtoye.uk/errors/shop-access-denied")); // DISTINCT from /forbidden AND /not-found
    // p.setProperty("shopId", ...); p.setProperty("requiredRole", ...);  // machine-parseable (agent-readiness contract)
    return p;
}
@ExceptionHandler(LastGroupAdminException.class)
public ProblemDetail handleLastGroupAdmin(LastGroupAdminException ex) {  // mirror handleIdempotencyConflict lines 321-327
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    p.setTitle("Last Group Admin"); p.setType(URI.create("https://jtoye.uk/errors/last-group-admin"));
    return p;
}
```
RLS 404 stays `handleResourceNotFound` (lines 44-50, type `.../not-found`) — the two must NOT be blurred (SPEC constraint).

---

### `resources/application.yml` (MODIFIED, config)

**Analog:** the existing `jtoye:` block (lines 96-125). Add the two new config keys (D-12 + D-09 throttle) under the same namespace, env-overridable with safe defaults, exactly like `jtoye.security.jwt.expected-audience` (line 99):
```yaml
jtoye:
  access:
    strict-scoping: ${ACCESS_STRICT_SCOPING:false}   # D-12: OFF preserves day-one JIT auto-provision
    directory-upsert-interval: ${DIRECTORY_UPSERT_INTERVAL:PT1H}  # D-09 throttle window
```
Add to all profiles per the base-inherits convention (RESEARCH Runtime State Inventory). These are **config keys, not secrets** — no sealed-secret rotation.

---

### `shop/Product/OrderService.java` etc. (MODIFIED, service) — the §3 enforcement sweep

**Analog:** in-place insertion at the top of each shop-scoped service method, driven by the **RESEARCH §3 endpoint inventory (the deliverable — lines 249-319)**. Two forms:
- **Write-gate:** `shopAccessService.require(shopId, minRole);` as the first line (before the existing body). `shopId` source per §3: path / body (`req.getShopId()`) / parent-lookup (load entity, read `entity.getShopId()`).
- **Read-scope:** filter list queries by the caller's grant set — replicate `ShopService.getAllShops` (lines 94-102) which already narrows by tenant; here narrow by `shopId IN grantedShops`.

Insertion points enumerated in RESEARCH §3 "Service method insertion points" (lines 308-313). **Two §3-FLAG items need a planner decision** (RESEARCH lines 315-317): bulk import (`/products/bulk/*` — per-row `require()`) and the SSE order stream (`OrderSseService` fan-out grant-set filter — the one place read-scoping is not a simple query filter). Do NOT ship either ungated (Pitfall 3).

**Do NOT gate** (RESEARCH §3 out-of-scope, line 319): `PublicStorefrontController`, `CustomerController` (tenant- not shop-scoped), already-`hasRole('admin')` controllers, webhook/sync controllers.

---

### `test/.../ShopStaffRlsPolicyIntegrationTest.java` (test, integration)

**Analog:** `webhook/WebhookSubscriptionRlsPolicyIntegrationTest.java` — the freshest, cleanest NOSUPERUSER role-downgrade RLS proof (copy its shape verbatim).

**Boilerplate to copy exactly:** `@SpringBootTest @Testcontainers @ActiveProfiles("test") @Tag("testcontainers") @Transactional` (lines 42-47); the `postgres:15` container (lines 49-53); `IntegrationTestSupport.registerPostgresTestProperties(registry, postgres)` in `@DynamicPropertySource` (lines 55-58).

**The NOSUPERUSER downgrade recipe** (lines 69-109) — the load-bearing mechanic (bootstrap is superuser → bypasses FORCE RLS unless downgraded):
```java
// @BeforeEach: create rls_test_role NOSUPERUSER NOBYPASSRLS + GRANT ALL (lines 71-78)
// seed tenant A row under TenantContext.set(tenantA) (lines 91-99)
private void dropSuperuserForTransaction() { jdbc.execute("SET LOCAL ROLE rls_test_role"); } // line 108
```

**The two proof tests** — mirror `tenantB_cannotListTenantASubscription` (lines 116-126, → 0 rows cross-tenant) and `tenantB_cannotForgeTenantARow` (lines 132-143, → `DataAccessException` `hasStackTraceContaining("row-level security")`). Add a **PII-disclosure proof for `user_directory`** (email cross-tenant read → 0 rows) — `shop_staff` carries no secret but `user_directory.email` is PII, so FORCE is load-bearing there (RESEARCH §2).

---

### `test/.../ShopAccessJitProvisionTest.java` + `ShopAccessEnforcementIntegrationTest.java` + `StaffManagementIntegrationTest.java`

**Analog:** same WebhookSub Testcontainers harness (role-downgrade + `IntegrationTestSupport`). Key note (RESEARCH §1-FLAG, Pitfall 2): the "backfill idempotency test" is a **JIT-provision race test** — two concurrent first-requests from the same `sub` → exactly one GROUP_ADMIN row (via `ON CONFLICT DO NOTHING`), **NOT** a migrate-time backfill test. Enforcement test asserts cross-shop write → typed 403 with `type` ≠ RLS-404 `type` (RESEARCH §9 test map, lines 479-482). Staff test asserts last-GROUP_ADMIN revoke → 409 (D-11).

---

### `components/dashboard/shop-switcher.tsx` (component, event-driven) — new

**Analog:** `dashboard/sidebar.tsx` (localStorage idiom) + Radix dropdown primitives (already vendored).

**Persistence (D-07) — mirror the theme-toggle idiom exactly** (sidebar.tsx lines 49-62), including the SSR-safe mount hydration and the existing eslint-disable comment:
```typescript
useEffect(() => {
  const saved = localStorage.getItem("shopContext")   // mirror line 50 ("theme")
  // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe mount-time hydration (sidebar.tsx line 52)
  setSelected(saved ?? "all")                          // GROUP_ADMIN default "All shops" (D-06)
}, [])
const onSelect = (id: string) => { setSelected(id); localStorage.setItem("shopContext", id) } // mirror line 61
```
"All shops" is a first-class context, not a null selection (CONTEXT §Specific Ideas). Non-GROUP_ADMIN with a single grant → pinned label, no dropdown (D-06).

---

### `app/dashboard/staff/page.tsx` (component / page, request-response) — new

**Analog:** `app/dashboard/finance/page.tsx` — the canonical **access-required-on-403** page (D-10/D-13).

**403 → in-page access-required state** (finance page lines 55-138), copy exactly:
```typescript
function httpStatus(err: unknown): number | undefined {   // lines 55-60
  if (err && typeof err === "object" && "response" in err)
    return (err as { response?: { status?: number } }).response?.status
}
// in fetch catch: if (httpStatus(error) === 403) setForbidden(true)   // lines 95-96
// render: if (forbidden) return <Card>...Admin access required...</Card>  // lines 121-138 (ShieldCheck card)
```
Use `apiClient` (finance line 81 — `apiClient.get(...)`); it already attaches `Authorization: Bearer` + `X-Tenant-Id` from the NextAuth session (api-client.ts lines 29-42) — no new client plumbing. Grant/revoke = `apiClient.post("/api/v1/staff/grant", ...)` / `apiClient.delete("/api/v1/staff/{id}")`.

---

### Modified frontend: `sidebar.tsx`, `dashboard-shell.tsx`, `dashboard-mobile.spec.ts`

- **`sidebar.tsx` (MOD):** add the Staff nav item to the shared `navigation` array (lines 26-42) — it is the **single source of truth**, `MobileTabBar` imports it (mobile-tab-bar.tsx lines 9-11, 38-40), so Staff auto-appears in the "More" sheet (not a primary tab — `PRIMARY_ORDER` is fixed, mobile-tab-bar lines 25-30). Follow the exact existing "Approvals" item convention (sidebar lines 36-38 comment: admin-only page renders access-required on 403). Mount the switcher in the logo header block (lines 66-73).
- **`dashboard-shell.tsx` (MOD):** add the mobile switcher slot into the `md:hidden` top bar (lines 27-30) next to the wordmark. Must not reintroduce 375px overflow (re-run the probe — RESEARCH §7).
- **`dashboard-mobile.spec.ts` (MOD):** add a `{ width: 375 }` case (it currently pins **390px**; the requirement literally names 375). MOBL-01 is **verify-first / already satisfied** (RESEARCH §7 live DOM probe: no occlusion, no horizontal overflow) — this is a regression assertion, **NOT** a new drawer. Extend `dashboard-shell.test.tsx` (existing 375px test surface, lines 30-58) if a Jest-level assertion is cheaper.

**Frontend typecheck gate (Pitfall 6):** touching dashboard TS requires `cd frontend && npm run build` (tsc) — jest does NOT type-check.

---

## Shared Patterns

### RLS tenant policy (ENABLE + FORCE + safe helper)
**Source:** `V51__rls_uuid_cast_safety.sql` lines 54-87 (the `current_tenant_id()` helper + corrected policy) + `V43` DO-block idempotency (lines 51-58).
**Apply to:** every new table in V52 (`shop_staff`, `shop_staff_aud`, `user_directory`).
**Rule:** `USING/WITH CHECK (tenant_id = current_tenant_id())` for base tables; `(tenant_id IS NULL OR tenant_id = current_tenant_id())` for `_aud`. **Never** the raw `::uuid` cast (fails `RlsContractTest#noPolicyUsesRawTenantGucCast`, lines 217-231).

### Tenant context resolution
**Source:** `security/TenantContext.java` (ThreadLocal `get()/set()/clear()`) + `security/JwtTenantFilter.java` lines 57-62 (`jwt.getSubject()` = the `sub`).
**Apply to:** `ShopAccessService` (reads tenant + sub); all service methods (`TenantContext.get().orElseThrow(...)`, ShopService line 46-47).

### Per-user membership cache + evict-on-write
**Source:** `config/TenantAwareCacheKeyGenerator.java` (key `tenant:{tid}:{method}:{params}`) + `config/TenantCacheEvictor.java` line 73 (`evictEntity`) + `ShopService.getShopById` `@Cacheable` (lines 81-89).
**Apply to:** `ShopAccessService.resolveMembership` (cache) + grant/revoke (evict). Cache is `@Profile("!test")` no-op in tests (RESEARCH §4).

### Realm-admin authority bridge
**Source:** `security/KeycloakRealmRoleConverter.java` lines 47-50 (`realm_access.roles → ROLE_<role>`, so `admin → ROLE_admin`).
**Apply to:** `ShopAccessService` implicit-GROUP_ADMIN check (D-03) + `StaffController` gate. Read the authority; do NOT re-parse `realm_access`.

### RFC 7807 typed errors (distinct type URIs)
**Source:** `common/GlobalExceptionHandler.java` — `handleAccessDenied` (403, lines 221-227), `handleResourceNotFound` (404, lines 44-50), `handleIdempotencyConflict` (409, lines 321-327).
**Apply to:** all new exceptions. The shop-scope 403 needs a **distinct `type` URI** from the generic 403 AND the RLS 404 so the frontend D-13 state can discriminate.

### Admin-gated controller shape
**Source:** `tenant/TenantAdminController.java` lines 49-88 (`@RestController` + `@RequestMapping("/api/v1/admin/...")` + `@Tag` + `@SecurityRequirement` + `@Valid @RequestBody` + Location-from-request + `@ApiResponses`).
**Apply to:** `StaffController`.

### Idempotent reserve insert (race-safe)
**Source:** `V47`/`V50` migration commentary + house idiom `INSERT ... ON CONFLICT DO NOTHING`.
**Apply to:** JIT GROUP_ADMIN provision (on `uq_shop_staff_tenant_user_shop`) + directory upsert (`ON CONFLICT DO UPDATE ... WHERE last_seen < now()-interval`, D-09 throttle).

### Shared nav array (single source of truth)
**Source:** `dashboard/sidebar.tsx` `navigation` export (lines 26-42) — imported by `mobile-tab-bar.tsx` (lines 9-11).
**Apply to:** the D-10 Staff item (auto-flows to the mobile "More" sheet; never redeclare the array).

### localStorage client persistence (SSR-safe)
**Source:** `dashboard/sidebar.tsx` theme toggle lines 49-62 (mount-time hydrate + `setItem` on change + the eslint-disable comment).
**Apply to:** D-07 switcher selection.

### Access-required-on-403 page state
**Source:** `app/dashboard/finance/page.tsx` lines 55-138 (`httpStatus` helper + `forbidden` state + ShieldCheck card).
**Apply to:** `/dashboard/staff` (GROUP_ADMIN-only) + any shop page hit without a grant (D-13).

### Authenticated API client
**Source:** `lib/api-client.ts` lines 29-42 (Bearer + X-Tenant-Id interceptor), lines 72-111 (5xx retry + 401 debounced refresh).
**Apply to:** all new staff/switcher API calls — just use `apiClient`, no new plumbing.

### NOSUPERUSER RLS integration-test harness
**Source:** `webhook/WebhookSubscriptionRlsPolicyIntegrationTest.java` (full file) + `testsupport/IntegrationTestSupport.registerPostgresTestProperties`.
**Apply to:** all four new integration tests. The `SET LOCAL ROLE rls_test_role` downgrade (line 108) is load-bearing — without it the superuser bootstrap bypasses FORCE RLS.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `security/access/ShopRole.java` (enum) | model | — | Trivial 3-value enum (GROUP_ADMIN/SHOP_MANAGER/STAFF). Nearest shape is `onboarding/OnboardingState.java`/`OnboardingModel.java` (String-mapped JPA enums) — use those as the *form* analog, but the values are net-new to this phase. Low risk. |
| SSE grant-set fan-out filter in `OrderSseService` (part of `OrderService` MOD) | service | streaming | **No existing read-scoped streaming analog.** Every other read-scope point is a simple query filter (`ShopRepository.findByTenantId`); the KDS SSE stream is the one place read-scoping must filter an in-memory event fan-out, not a query. RESEARCH §3-FLAG #2 flags this as its own task; planner should use RESEARCH §3 guidance (grant-set filter in the fan-out), not a copy-paste analog. |

Both are called out in RESEARCH (§3-FLAG, Open Questions 1-2); the planner should treat them as design tasks, not copy-from-analog tasks.

---

## Addendum — Plan 23-07 (shop-context wiring, added during plan revision 2026-07-19)

Plan 23-07 was inserted during the plan-check revision to satisfy VSA-03's "all shop-scoped screens operate on the selected shop" clause. Its 5 files and analogs (23-07's own `<read_first>` blocks cite these directly):

| New/Modified File | Role | Data Flow | Closest Analog | Why |
|-------------------|------|-----------|----------------|-----|
| `frontend/hooks/use-shop-context.ts` (new) | hook | client state | `frontend/hooks/use-order-events.ts` (subscribe/cleanup hook shape) + `lib/shop-context.ts` (23-05 store) | Subscribes to the same-tab `shopcontext:change` event + cross-tab `storage`; returns the current selection for consuming pages. |
| `frontend/app/dashboard/products/page.tsx` (mod) | page | list + create | its own current list/create code | Read `getShopContext()`: narrow list client-side over the 23-03 grant-scoped result; default/constrain create-form shop when not "All shops" (D-08). |
| `frontend/app/dashboard/orders/page.tsx` (mod) | page | list + create | its own current `?shopId=` server param usage | Pass the selected shop as the existing server `?shopId=` param; create-form shop default. |
| `frontend/app/dashboard/marketing/page.tsx` (mod) | page | list + create | products/orders pattern | Client filter promotions/announcements; create-form shop default. |
| `frontend/app/dashboard/kitchen/page.tsx` (mod) | page | list | its pre-existing local `selectedShopId` state | Reconcile local selector to the global `getShopContext()` as source of truth (no competing selectors). |

No new no-analog items; all five are role-match modifications to existing pages plus one hook that mirrors `use-order-events.ts`.

---

## Metadata

**Analog search scope:** `core-java/src/main/resources/db/migration/` (V43/V47/V50/V51), `core-java/src/main/java/uk/jtoye/core/{security,config,shop,onboarding,tenant,common,exception}/`, `core-java/src/test/java/uk/jtoye/core/{security,webhook,testsupport}/`, `frontend/{components/dashboard,app/dashboard,lib}/`, `frontend/e2e/`, `core-java/src/main/resources/application.yml`.
**Files scanned/read:** 22 analog files read in full or targeted range (all VERIFIED against live source, not RESEARCH assertion alone).
**Pattern extraction date:** 2026-07-19
**Upstream verified:** every analog named in RESEARCH §Sources was re-read; the V47/V50-vs-V51 raw-cast landmine, the WebhookSub NOSUPERUSER recipe, the `TenantCacheEvictor` key shape, the `KeycloakRealmRoleConverter` `ROLE_admin` emission, the `ShopRepository.findByTenantId` read-scope idiom, the `finance/page.tsx` 403 convention, and the shared `navigation` array were all confirmed at the cited line numbers.
**Bonus find (not in RESEARCH):** `exception/TenantAccessDeniedException.java` already exists — a direct shape analog for `ShopAccessDeniedException`, with the important caveat that it maps to the *generic* 403 and the new one must be distinct (RESEARCH §6).

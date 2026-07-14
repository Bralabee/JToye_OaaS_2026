# SPEC — Shop-Scoped Access Control (user ↔ shop ↔ role within a tenant)

**Status:** DECIDED 2026-07-14 — ready for a future milestone phase (spec-now, build-later)
**Decided by:** user, session 2026-07-14 (scope quoted verbatim from the verified assessment)
**Origin:** user observation that an admin login "can effect changes and interact with other shops" with no per-shop boundary; verified same day.

## Problem (verified 2026-07-14, live stack + code)

The tenant is the ONLY authorization wall. Verified evidence:

- All 3 demo shops + 11 leftover E2E-test shops belong to Tenant A; `admin-user`'s JWT carries `tenant_id = Tenant A` + realm role `admin`.
- Live probe with the real admin-user token: shop list returns exactly the 14 Tenant A shops; GET/PUT on Tenant B's shop → 404 both (RLS wall holds, DB row unchanged). Cross-tenant isolation is intact.
- BUT within the tenant there is no finer boundary: no `shop_staff`/membership table, no `owner`/`manager` column on `shops`, no user↔shop linkage anywhere in the schema.
- The realm has exactly two roles (`user`, `admin`); `admin` gates only refunds/finance/GDPR/dev-admin endpoints (#83). Ordinary shop/product/order CRUD is open to ANY authenticated tenant user on EVERY shop in the tenant.

Consequence: one login manages the whole vendor group; there is no way to scope a manager to one location, and no explicit "this change applies to one shop vs all shops" affordance.

## Locked scope (user, 2026-07-14)

1. **`shop_staff` mapping table** — user ↔ shop ↔ role, RLS tenant-scoped, next free Flyway slot (V51+ at time of writing; coordinate numbering with the image-architecture spec, which also reserves a migration).
2. **Roles:** `group-admin` / `shop-manager` / `staff`.
3. **Application-layer enforcement on shop-scoped endpoints** — RLS stays the tenant wall; this is a second, finer gate in the service layer.
4. **Dashboard shop-context switcher** with "apply to all shops" as the explicit group-wide action.

## Proposed design (defaults to confirm at discuss-phase)

### Schema
`shop_staff`: `id, tenant_id, user_id (Keycloak sub, UUID), shop_id (FK shops, NULLable), role (CHECK: GROUP_ADMIN|SHOP_MANAGER|STAFF), created_at, created_by` — ENABLE+FORCE RLS tenant-scoped (mirror V47/V50 policy pattern), unique `(tenant_id, user_id, COALESCE(shop_id, zero-uuid))`. `shop_id NULL` = tenant-wide grant (the GROUP_ADMIN shape). `_aud` mirror per Envers convention.

### Semantics
- `GROUP_ADMIN`: every shop in the tenant, including create/delete shop and staff management.
- `SHOP_MANAGER`: full CRUD on the granted shop's products/orders/marketing/KDS; no staff management, no shop create/delete.
- `STAFF`: operational read + order state transitions on the granted shop (KDS-level); no catalogue writes.
- Realm `admin` role ⇒ implicit `GROUP_ADMIN` (keeps the existing admin-user working; no migration lockout).
- **Backfill:** every existing tenant user gets a `GROUP_ADMIN` row at migration time — preserves today's behaviour exactly (Incremental Betterment: no capability regression on day one; tightening is an explicit vendor action afterwards).

### Enforcement
- JWT unchanged (tenant_id claim stays the RLS key). Shop membership resolved server-side from `shop_staff` per request (cacheable per user, tenant-aware cache).
- A `ShopAccessService.require(shopId, minRole)` check at the top of shop-scoped service methods (shops, products, orders, KDS, marketing) — deny-by-default for shop-scoped writes without a grant. Enumerate the endpoint inventory during planning; the QA surface ledger (`qa/surface-ledger.json`) is the checklist seed.
- 403 with RFC 7807 body distinct from the 404 the RLS wall produces (do not blur the tenant boundary signal).

### UI
- Shop-context switcher in the dashboard nav (persisted selection); all shop-scoped screens operate on the selected shop.
- Group-wide mutations require the explicit "apply to all shops" action, available only to `GROUP_ADMIN`.
- Staff management screen (grant/revoke roles per shop) — minimal slice: list + grant + revoke; invitations/user-creation stay in Keycloak.

## Explicitly deferred
- Cross-tenant/platform-operator roles; per-shop Keycloak clients or shop claims in the token.
- Self-serve user invitation flows (Keycloak admin remains the account source; note the KC24 unmanaged-attribute trap for programmatic creation).
- Fine-grained per-capability permissions beyond the three roles.

## Constraints
- New table RLS ENABLE+FORCE, proven under the NOSUPERUSER role-downgrade (project standard; RlsContractTest pattern).
- Tests per project standard (service-layer unit + Testcontainers integration incl. cross-shop 403 proofs + Jest for the switcher); reconcile `docs/metrics.json` via `scripts/docs-freshness.sh --write`.
- State-changing enforcement must not break the storefront public read path (`/public/*` is unauthenticated and out of scope).
- Sizing: schema + enforcement sweep + switcher UI = a proper phase (likely 2–3 plans), NOT a quick task.

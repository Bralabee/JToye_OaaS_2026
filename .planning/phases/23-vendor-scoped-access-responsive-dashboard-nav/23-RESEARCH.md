# Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav - Research

**Researched:** 2026-07-19
**Domain:** In-tenant authorization boundary (user↔shop↔role) under existing PostgreSQL RLS + Spring Security method-security + Next.js dashboard chrome
**Confidence:** HIGH (this is an internal-codebase phase; every claim below is grounded in a file read or a live browser probe, not training data)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

**The WHAT is locked (SPEC + REQUIREMENTS). These are the HOW decisions resolved with the user 2026-07-15. Copy verbatim — the planner MUST honor these.**

### Locked Decisions (D-01 .. D-13)

- **D-01 — Read visibility is SCOPED to grants (not writes-only).** For a non-GROUP_ADMIN (SHOP_MANAGER / STAFF), the shop switcher **and** the shop-scoped list/read endpoints (shops, products, orders, and any per-shop list) surface **only the shops the user has a grant on**. GROUP_ADMIN sees all shops plus an **"All shops"** context. Enforcement remains a 403 (typed, RFC 7807) distinct from the RLS 404; the storefront public read path is untouched.
- **D-02 — Enforcement is an explicit service-layer call**, not an annotation. `shopAccessService.require(shopId, minRole)` at the top of shop-scoped service methods (shops, products, orders, KDS, marketing). `shopId` frequently comes from a request body or a parent-entity lookup (product → shop). Read-scoping (D-01) is enforced by filtering list queries by the caller's grant set (a companion `shopAccessService` read helper), not a post-hoc filter.
- **D-03 — Roles + admin bridge:** GROUP_ADMIN (all shops incl. shop create/delete + staff mgmt) / SHOP_MANAGER (full CRUD on granted shop, no staff mgmt, no shop create/delete) / STAFF (operational read + order state transitions on granted shop, no catalogue writes). Realm `admin` role ⇒ **implicit GROUP_ADMIN**.
- **D-04 — Backfill via JIT lazy-provision (no Keycloak enumeration).** First authenticated request from a tenant user with no `shop_staff` row auto-creates a GROUP_ADMIN row for that user; realm `admin` ⇒ implicit GROUP_ADMIN acts as a fail-safe. Fail-closed, no live-Keycloak coupling at migrate time, dodges the KC24 unmanaged-attribute trap. Preserves day-one "everyone can do everything".
- **D-05 — Revocation is immediate: evict membership cache on write.** grant/revoke evicts that user's membership-cache entry (reuse the `TenantCacheEvictor` pattern); next request re-resolves from `shop_staff`. Short TTL as a backstop. Membership cached per-user via the tenant-aware key generator.
- **D-06 — Switcher in the sidebar header; GROUP_ADMIN defaults to "All shops".** Dropdown under the J'Toye logo in `sidebar.tsx`; GROUP_ADMIN lands on "All shops". A non-GROUP_ADMIN with a single grant shows that shop pinned. On mobile it rides the existing mobile nav / "More" sheet.
- **D-07 — Selection persisted in `localStorage`.** Client-only, mirrors the existing theme-toggle persistence in `sidebar.tsx`. Server re-validates every grant on every request, so persistence is NOT a trust boundary.
- **D-08 — Group-wide mutations only via the "All shops" context.** A GROUP_ADMIN triggers a group-wide write only when the switcher is on "All shops"; the action is explicit and GROUP_ADMIN-gated; any single-shop context does single-shop writes only.
- **D-09 — Login-populated user directory as the grant-target picker.** Upsert a lightweight tenant-scoped directory row `(tenant_id, user_id sub, email, display_name, last_seen)` from the authenticated JWT — same request point that drives D-04 JIT provisioning. ENABLE+FORCE RLS tenant-scoped, but **NO `_aud`**. New staff appear after first login. The upsert MUST be **throttled** (gate on stale `last_seen` via a config-injected interval / `ON CONFLICT DO UPDATE … WHERE` — never a write per request).
- **D-10 — GROUP_ADMIN-only staff nav item, mirroring Approvals.** A standalone "Staff"/"Team" entry in the `sidebar.tsx` `navigation` array that renders the existing access-required state for non-GROUP_ADMIN; overflow falls into the mobile "More" sheet automatically.
- **D-11 — Guard the last GROUP_ADMIN.** Block revoking/downgrading the final GROUP_ADMIN row with an RFC 7807 **409**, and warn on self-downgrade.
- **D-12 — Strict-scoping switch on top of D-04 JIT provisioning.** A config-injected `strict-scoping` switch (default OFF). While OFF, behaviour is exactly D-04. Turning it ON makes ungranted users deny-by-default (no auto-provision). Global flag now; per-tenant granularity deferred.
- **D-13 — Out-of-scope-shop UX = in-page access-required state.** A direct-URL/bookmark hit on a non-granted shop renders the existing access-required state (same as Approvals/Finance on 403), not a redirect/blank. Still a distinct RFC 7807 403 (never the RLS 404).

### Claude's Discretion
- **MOBL-01 is verify-first, not build-from-scratch.** Research MUST first verify the actual 375px state (drive it in the browser). Likely task = confirm no 375px occlusion + integrate the D-06 switcher into the existing mobile nav — NOT author a new drawer. → **This research drove it. See §7. MOBL-01 is SATISFIED-BY-PRIOR-WORK — evidence attached.**
- Exact `shop_staff` column types/index names, the cache key shape, and the `ShopAccessService` API surface are planner/executor discretion within D-01..D-08 and the spec's schema.

### Deferred Ideas (OUT OF SCOPE — do NOT build)
- Department tier (Vendor → Department → Shop).
- Self-serve user invitation / account creation (stays in Keycloak admin; KC24 trap).
- Server-side (cross-device) switcher preference.
- Fine-grained per-capability permissions beyond the three roles.
- Per-tenant strict-scoping switch (D-12 is a global flag now).
- Keycloak-admin user search / grant to never-logged-in users.
- Multi-shop grant in one action.
- Cross-tenant / platform-operator roles; per-shop Keycloak clients or shop claims in the token.
- Changes to the storefront public read path (`/public/*`, `/api/v1/public/*`, `/shop/*` unauthenticated).
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| VSA-01 | `shop_staff` mapping table (V52) + `_aud`, ENABLE+FORCE RLS, unique `(tenant_id, user_id, COALESCE(shop_id, zero-uuid))`; GROUP_ADMIN backfill; realm `admin` ⇒ implicit GROUP_ADMIN; NOSUPERUSER RLS proof + backfill idempotency test | §1 (migration slot + verbatim DDL template), §2 (RLS proof recipe), §1-FLAG (backfill↔JIT reconciliation), §5 (auth bridge) |
| VSA-02 | App-layer enforcement: `ShopAccessService.require(shopId, minRole)` on shops/products/orders/KDS/marketing; deny-by-default writes; tenant-aware membership cache; RFC 7807 403 distinct from RLS 404; Testcontainers cross-shop 403 proofs | §3 (**enforcement endpoint inventory — the deliverable**), §4 (cache), §6 (403-vs-404) |
| VSA-03 | Dashboard shop-context switcher (persisted); all shop-scoped screens operate on selected shop; GROUP_ADMIN-only "apply to all shops" | §7 (switcher slot on mobile+desktop), §8 (frontend patterns), D-01 read-scoping in §3 |
| VSA-04 | Staff management screen: list/grant/revoke; last-GROUP_ADMIN 409 guard; new authenticated staff endpoint | §8 (api-client + nav convention), §9 (validation), §5 (directory upsert) |
| MOBL-01 | Dashboard sidebar no longer overlays content at 375px; Jest/Playwright 375px viewport spec | §7 (**live 375px evidence — SATISFIED-BY-PRIOR-WORK**) |
</phase_requirements>

---

## Summary

This is an auth-boundary + schema phase layered **under** the untouched RLS tenant wall. The good news for the planner: nearly every mechanism this phase needs already exists in the codebase and has a proven template. There are **no new external dependencies** — everything is the existing Spring Boot 3.5.16 / PostgreSQL 15 RLS / Redis cache / Next.js 16 stack.

The three highest-risk areas are all de-risked here: (1) the **Flyway V52 migration** has an exact template (V47/V50) but carries a **hard landmine** — the current `RlsContractTest#noPolicyUsesRawTenantGucCast` sweep will fail the build if `shop_staff`'s policy uses the raw `current_setting(...)::uuid` cast that V47/V50 originally shipped; the new policy MUST use the safe `current_tenant_id()` helper (V51). (2) The **"backfill every existing tenant user"** language in VSA-01/ROADMAP is **not literally implementable** — there is no local users table and no Keycloak enumeration; D-04 already resolves this to JIT lazy-provision, so the "backfill" is a first-request auto-provision, not a migrate-time UPDATE. This tension is flagged explicitly (§1-FLAG) so the planner does not write a migrate-time backfill task. (3) **MOBL-01 is already satisfied** — a live 375px browser probe (below) shows full-width content, the fixed mobile tab bar visible, the desktop sidebar hidden, and zero horizontal overflow. Phase 19 Surface D already did this work; there is even an existing `e2e/dashboard-mobile.spec.ts` proving all 11 routes at 390px. The MOBL-01 task is therefore "add a 375px regression assertion + integrate the D-06 switcher into the existing mobile top bar" — **not** authoring a drawer.

**Primary recommendation:** Accept the provisional 3-plan split (23-01 schema+RLS+JIT, 23-02 enforcement sweep, 23-03 UI+MOBL-01), put the **user-directory table in the SAME V52 migration** as `shop_staff` (one forward-only slot, both must precede Phase 24's V53), gate every `shop_staff`/`user_directory` policy through `current_tenant_id()`, and treat the enforcement inventory in §3 as the task checklist for 23-02.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Tenant isolation (unchanged) | Database (RLS) | API (`TenantContext`/`TenantSetLocalAspect`) | The RLS wall stays the tenant boundary; Phase 23 adds nothing here and must not widen it |
| Shop-role membership store | Database (`shop_staff`, RLS+FORCE) | — | Tenant-scoped data; RLS+FORCE is the non-negotiable house rule (RlsContractTest sweep) |
| Membership resolution + role gate | API / Backend (service layer, `ShopAccessService`) | Redis (per-user membership cache) | D-02: explicit service call; `shopId` often from body/parent lookup — cannot live in the DB or a browser |
| Read-scoping (grant-set filtering) | API / Backend (repository query filter) | — | D-01: genuinely narrow lists server-side, not UI-hidden |
| JIT provision + directory upsert | API / Backend (per-request, off `JwtTenantFilter`) | Keycloak (claim source only) | D-04/D-09: identity lives in Keycloak; backend sees `sub`+claims per request |
| Realm-admin ⇒ implicit GROUP_ADMIN | API / Backend (`JwtRolesAndScopesConverter` → `ShopAccessService`) | Keycloak (`realm_access.roles`) | D-03: reuses the existing `ROLE_admin` plumbing |
| Shop-context switcher (persisted) | Browser / Client (`localStorage`) | Frontend Server (dashboard shell RSC) | D-06/D-07: a per-device UI preference, not a trust boundary |
| Staff management (list/grant/revoke) | API / Backend (new GROUP_ADMIN-gated REST) | Browser (dashboard staff screen) | VSA-04; last-GROUP_ADMIN guard is a server invariant (D-11) |
| Responsive dashboard nav (MOBL-01) | Browser / Client (Tailwind `md:` breakpoints) | — | Already implemented in Phase 19 Surface D — verify-only |

---

## Standard Stack

**No new external packages.** This phase composes existing, already-vendored libraries. The planner should write **zero** `npm install` / `implementation(...)` Gradle lines for new runtime deps.

### Core (all already present, versions from CLAUDE.md / build files)
| Library | Version | Purpose | Why standard here |
|---------|---------|---------|-------------------|
| Spring Security (method security) | via Boot 3.5.16 | `@EnableMethodSecurity` already active; `hasRole('admin')` gates exist | D-03 admin bridge reuses `ROLE_admin` |
| Spring Data JPA + Hibernate Envers | via Boot 3.5.16 | `shop_staff` entity + `_aud` mirror | House `_aud` convention (V43) |
| Flyway | via Boot 3.5.16 | V52 migration; `out-of-order: true` set in all profiles | VSA-01 |
| Spring Cache + Redis (Lettuce) | Redis 7 | Per-user membership cache | D-05, reuse `TenantAwareCacheKeyGenerator` |
| Testcontainers | 1.21.3 | Real Postgres 15 RLS proof under NOSUPERUSER | VSA-01/02 tests |
| Next.js / React | 16.2.2 / 19 | Switcher + staff screen + responsive nav | VSA-03/04, MOBL-01 |
| Radix UI (`Sheet`, dropdown primitives) | present | Switcher dropdown + existing "More" sheet | D-06 |
| Jest + @testing-library/react | 29.7.0 | Switcher/staff unit tests + dashboard-shell 375px | VSA-03/04, MOBL-01 |
| @playwright/test | 1.59.1 | 375px viewport regression + cross-shop E2E | MOBL-01, VSA-02 |

**Installation:** none.

## Package Legitimacy Audit

**N/A — this phase installs no external packages.** All capabilities reuse already-vendored, long-established dependencies (Spring Boot, Flyway, Testcontainers, Redis, Next.js, Playwright). No slopcheck run required; no registry verification needed. If the planner discovers a genuinely new dependency is required (not anticipated), it must run the Package Legitimacy Gate before adding it.

---

## §1 — Migration slot & pattern (VSA-01)

### Flyway HEAD and the free slots — VERIFIED
- **HEAD is V56** (`V56__webhook_delivery.sql`). Directory listing jumps `V50, V51 → V54, V55, V56`. **[VERIFIED: `ls db/migration/`]**
- **V52 and V53 are both free.** V52 = `shop_staff` (this phase); V53 = `media_asset` (Phase 24). shop_staff must land first per REQUIREMENTS §12. **[VERIFIED: REQUIREMENTS.md line 12]**
- **`out-of-order: true` is set in `application.yml` (base, inherited by all) AND explicitly in `application-staging.yml` and `application-prod.yml`.** Applying V52/V53 after V54–V56 already exist on deployed DBs is safe. **[VERIFIED: grep application*.yml]**

### The RLS policy DDL template — and the ONE landmine
V47 and V50 are the cited templates. **But they were written BEFORE V51 and use the raw cast** `current_setting('app.current_tenant_id', true)::uuid`. V51 then *rewrote all ten* such policies to the safe helper `current_tenant_id()` and installed a **permanent build-failing guard**:

`RlsContractTest#noPolicyUsesRawTenantGucCast` sweeps `pg_policy` for any policy whose `USING`/`WITH CHECK` contains `current_setting('app.current_tenant_id'...` AND `::uuid`. **A new `shop_staff` policy copied verbatim from V47/V50 WILL fail this test.** **[VERIFIED: RlsContractTest.java lines 217-231, V51 header]**

→ **The `shop_staff` / `_aud` / `user_directory` policies MUST use `current_tenant_id()`**, matching V51/V43-as-rewritten. Two other sweeps also apply and pass for free with this template: `everyPublicTableHasRlsAndForce` (needs ENABLE+FORCE) and `noPolicyReadsBuggyAppTenantIdGuc` (needs `app.current_tenant_id`, not `app.tenant_id`). **[VERIFIED: RlsContractTest.java]**

### Prescriptive V52 DDL skeleton (planner/executor discretion on exact types/index names, per Claude's Discretion)

```sql
-- V52: shop_staff (VSA-01) + login-populated user_directory (D-09).
-- Mirrors the V43 idempotent DO-block house style; RLS via current_tenant_id()
-- (V51 — NOT the raw ::uuid cast, or RlsContractTest#noPolicyUsesRawTenantGucCast fails).

-- 1. shop_staff — user <-> shop <-> role within a tenant
CREATE TABLE IF NOT EXISTS shop_staff (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    user_id     UUID        NOT NULL,                    -- Keycloak `sub`
    shop_id     UUID        REFERENCES shops(id),        -- NULL = tenant-wide (GROUP_ADMIN shape)
    role        VARCHAR(16) NOT NULL
                  CHECK (role IN ('GROUP_ADMIN','SHOP_MANAGER','STAFF')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID
);
-- Unique per (tenant,user,shop) treating tenant-wide (NULL shop) as the zero-uuid.
-- MUST be a functional UNIQUE INDEX (a table UNIQUE constraint cannot use COALESCE).
CREATE UNIQUE INDEX IF NOT EXISTS uq_shop_staff_tenant_user_shop
    ON shop_staff (tenant_id, user_id, COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX IF NOT EXISTS idx_shop_staff_tenant_user ON shop_staff (tenant_id, user_id); -- membership resolution
CREATE INDEX IF NOT EXISTS idx_shop_staff_shop        ON shop_staff (shop_id);

ALTER TABLE shop_staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_staff FORCE  ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='shop_staff' AND policyname='shop_staff_tenant_policy') THEN
    CREATE POLICY shop_staff_tenant_policy ON shop_staff
        FOR ALL
        USING      (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

-- 2. shop_staff_aud — Envers mirror (all cols nullable, PK (id,rev), FK rev->revinfo,
--    RLS predicate admits NULL tenant_id per the V43 refunds_aud pattern).
CREATE TABLE IF NOT EXISTS shop_staff_aud (
    id UUID NOT NULL, rev INT NOT NULL REFERENCES revinfo(rev), revtype SMALLINT,
    tenant_id UUID, user_id UUID, shop_id UUID, role VARCHAR(16),
    created_at TIMESTAMPTZ, created_by UUID,
    PRIMARY KEY (id, rev)
);
ALTER TABLE shop_staff_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_staff_aud FORCE  ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='shop_staff_aud' AND policyname='shop_staff_aud_tenant_policy') THEN
    CREATE POLICY shop_staff_aud_tenant_policy ON shop_staff_aud
        FOR ALL
        USING      (tenant_id IS NULL OR tenant_id = current_tenant_id())
        WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());
  END IF;
END $$;

-- 3. user_directory (D-09) — login-populated grant-target picker. RLS+FORCE, NO _aud.
CREATE TABLE IF NOT EXISTS user_directory (
    tenant_id    UUID        NOT NULL,
    user_id      UUID        NOT NULL,                    -- Keycloak `sub`
    email        VARCHAR(320),
    display_name VARCHAR(255),
    last_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);
ALTER TABLE user_directory ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_directory FORCE  ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='user_directory' AND policyname='user_directory_tenant_policy') THEN
    CREATE POLICY user_directory_tenant_policy ON user_directory
        FOR ALL
        USING      (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;
```

Notes for the planner:
- **`user_directory` goes in V52** (same migration as `shop_staff`), not a separate slot. Rationale: both are one atomic "vendor-scoped access" schema unit, both must precede V53, and using one slot keeps V53 free for Phase 24 exactly as REQUIREMENTS assumes. This resolves the CONTEXT open item "Directory version = part of V52 or the next free slot (planner assigns)". **[CITED: CONTEXT §Integration Points]**
- **`user_id` type:** the Keycloak `sub` is a UUID string in this realm (standard KC). Storing as `UUID` matches the SPEC. Confirm `jwt.getSubject()` parses as a UUID for all seed users (it does for `admin-user`/`tenant-a-user`). If any non-UUID sub ever appears, switch to `VARCHAR`. **[ASSUMED — verify at plan time with a decoded token]**
- **Envers wiring:** `@Audited` on the `ShopStaff` entity + `@Column`s; Envers auto-writes `shop_staff_aud`. `user_directory` is deliberately NOT `@Audited` (D-09). Follow the `VendorOnboarding` entity for the annotation pattern.

### §1-FLAG (CRITICAL) — "Backfill every existing tenant user" is NOT literally implementable; D-04 already resolves it

ROADMAP Success Criterion 1 and VSA-01 both say *"every existing user has a GROUP_ADMIN `shop_staff` row"* and *"Backfill: every existing tenant user → GROUP_ADMIN row **at migration time**"* (SPEC line 35). **This cannot be done as a migrate-time SQL backfill:**
- There is **no local users table** — identities live only in Keycloak. **[VERIFIED: grep — no users table; V49 KC admin client inert by default]**
- The DB has **never seen a Keycloak `sub`** until a request arrives carrying one. A migration has no set of user_ids to insert.
- Enumerating Keycloak is explicitly rejected (D-04) — KC admin client is `jtoye.keycloak.admin.enabled=false` and the KC24 unmanaged-attribute trap applies.

**Resolution (already decided — D-04):** "Backfill" = **JIT lazy-provision on first authenticated request**. The `shop_staff` V52 migration creates an **empty** table; the first request from an ungranted tenant user auto-inserts a `GROUP_ADMIN` row for that `sub`. `realm admin ⇒ implicit GROUP_ADMIN` is the fail-safe covering the window before provision.

→ **The planner must NOT write a "migrate-time GROUP_ADMIN backfill" task.** The VSA-01 "backfill idempotency test" is really a **JIT-provision idempotency test**: two concurrent first-requests from the same sub must produce exactly one row (use `INSERT ... ON CONFLICT DO NOTHING` on `uq_shop_staff_tenant_user_shop`, the house reserve idiom from V47/V50). The "day-one zero regression" proof is: an ungranted user's first request behaves identically to today (auto-GROUP_ADMIN) while `strict-scoping=OFF` (D-12).

---

## §2 — RLS-under-NOSUPERUSER proof (VSA-01)

The house recipe is fully reusable. `WebhookSubscriptionRlsPolicyIntegrationTest` (V55, 2026-07-15) is the freshest, cleanest template — copy its shape for `ShopStaffRlsPolicyIntegrationTest`. Key mechanics **[VERIFIED: WebhookSubscriptionRlsPolicyIntegrationTest.java, IntegrationTestSupport.java, RlsContractTest.java]**:

1. **Bootstrap is a Postgres SUPERUSER** which bypasses even FORCE RLS, so the test provisions a dedicated role and downgrades inside each RLS-sensitive transaction:
   ```java
   // @BeforeEach seed():
   jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='rls_test_role') THEN "
     + "CREATE ROLE rls_test_role NOSUPERUSER NOBYPASSRLS LOGIN; "
     + "GRANT ALL ON ALL TABLES IN SCHEMA public TO rls_test_role; "
     + "GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO rls_test_role; "
     + "GRANT USAGE ON SCHEMA public TO rls_test_role; END IF; END $$");
   // inside the test tx, after TenantContext.set(tenantB):
   jdbc.execute("SET LOCAL ROLE rls_test_role");   // now the policy actually fires
   ```
   The `GRANT ALL ON ALL TABLES` snapshot runs after Flyway has already created `shop_staff`/`user_directory`, so they are covered automatically.
2. **Tenant GUC is driven by `TenantContext.set(uuid)`** → `TenantSetLocalAspect` issues `SELECT set_config('app.current_tenant_id', ?, true)` on the connection (the same path production uses). **[VERIFIED: TenantSetLocalAspect.java]**
3. **Boilerplate is centralized:** `IntegrationTestSupport.registerPostgresTestProperties(registry, postgres)` in a `@DynamicPropertySource` (see WebhookSubscription test line 56-58). Reuse verbatim.

**What the two proof tests look like for `shop_staff`:**
- `tenantB_cannotSeeTenantAStaffRow()` — seed a `shop_staff` row under tenant A (superuser), then under tenant B's GUC + downgraded role assert `SELECT count(*) FROM shop_staff WHERE user_id = <A's sub>` returns **0**.
- `tenantB_cannotForgeTenantAStaffRow()` — under tenant B's GUC + downgraded role, `INSERT ... (tenant_id = tenantA, ...)` throws `DataAccessException` with `hasStackTraceContaining("row-level security")` (WITH CHECK rejection).
- **`shop_staff` carries no plaintext secret** (unlike webhook_subscription's `signing_secret`), but `user_directory` carries **email (PII)** — so add the same disclosure proof for `user_directory`: FORCE RLS is load-bearing there.

**Contract-sweep tests that run for free once the migration is correct:** `RlsContractTest#everyPublicTableHasRlsAndForce` (walks `pg_class` — `shop_staff`/`shop_staff_aud`/`user_directory` will each be asserted), `#noPolicyUsesRawTenantGucCast`, `#noPolicyReadsBuggyAppTenantIdGuc`. **No EXEMPT_TABLES entry is needed — all three new tables are tenant-scoped.**

---

## §3 — Enforcement Endpoint Inventory (VSA-02) — THE deliverable

Every shop-scoped controller endpoint below, with the `shopId` source and the gate the planner inserts. **Enforcement is at the SERVICE method** (D-02), not the controller. `shopId` sources: **path** (`/{id}` where id *is* the shop), **path(child)** (`/{shopId}` explicit), **body** (`req.getShopId()`), **parent-lookup** (load the entity, read `entity.getShopId()`). Read endpoints get **read-scope** (D-01: filter list by grant set / STAFF-require on single reads); writes get **write-gate** (`require(shopId, minRole)`). **[VERIFIED: controller grep + entity/DTO shopId field grep]**

Role floor legend: **STAFF** = read + order state transitions; **SHOP_MANAGER** = full CRUD on granted shop; **GROUP_ADMIN** = shop create/delete + staff mgmt.

### ShopController `/shops` (`/api/v1/shops`) → `ShopService`
| Endpoint | Method | shopId source | Gate | Notes |
|----------|--------|---------------|------|-------|
| `/shops` | GET (list) | each row's `id` | **read-scope** by grant set | non-GA sees only granted shops |
| `/shops/search?q` | GET | each result `id` | **read-scope** filter | narrow results to grant set |
| `/shops/{id}` | GET | path (=shopId) | require STAFF | D-13 direct-hit → 403 in-page |
| `/shops` | POST (create) | — (creating) | **require GROUP_ADMIN** | shop create is GA-only; no shopId |
| `/shops/{id}` | PUT (update) | path | require SHOP_MANAGER | |
| `/shops/{id}/logo` POST, `/logo` DELETE | POST/DELETE | path | require SHOP_MANAGER | media on the shop |
| `/shops/{id}/banner` POST, `/banner` DELETE | POST/DELETE | path | require SHOP_MANAGER | |
| `/shops/{id}` | DELETE | path | **require GROUP_ADMIN** | shop delete is GA-only |

### ProductController `/products` → `ProductService`
| Endpoint | Method | shopId source | Gate | Notes |
|----------|--------|---------------|------|-------|
| `/products` | GET (list) | `product.shopId` | **read-scope** by grant set | filter products whose shopId ∈ grants |
| `/products/search` | GET | `product.shopId` | **read-scope** filter | |
| `/products/{id}` | GET | parent-lookup | require STAFF | |
| `/products/{id}/label` | GET | parent-lookup | require STAFF | PDF label read |
| `/products/template` | GET | — | **no gate** | static CSV template, not shop data |
| `/products` | POST (create) | **body** `req.getShopId()` | require SHOP_MANAGER | already `@PreAuthorize SCOPE_catalog:write` on controller — additive |
| `/products/{id}` | PUT (update) | parent-lookup | require SHOP_MANAGER | + existing SCOPE_catalog:write |
| `/products/{id}/image` POST, `/image/analyze` POST | POST | parent-lookup | require SHOP_MANAGER | analyze is a write-path helper |
| `/products/{id}/images` POST, `/images/{index}` DELETE, `/image` DELETE | POST/DELETE | parent-lookup | require SHOP_MANAGER | |
| `/products/{id}` | DELETE | parent-lookup | require SHOP_MANAGER | |
| `/products/bulk/csv` POST, `/bulk/images` POST | POST | **per-CSV-row shopId** | **FLAG** | bulk has no single shopId; see §3-FLAG |

### OrderController `/orders` → `OrderService`
| Endpoint | Method | shopId source | Gate | Notes |
|----------|--------|---------------|------|-------|
| `/orders/stream` | GET (SSE) | tenant-wide stream | **read-scope FLAG** | KDS live feed; STAFF/SM should only see granted shops — see §3-FLAG |
| `/orders` | GET (getAllOrders) | `order.shopId` | **read-scope** by grant set | |
| `/orders/status/{status}` | GET | `order.shopId` | **read-scope** filter | |
| `/orders/shop/{shopId}` | GET | **path(child) explicit** | require STAFF | cleanest shopId source |
| `/orders/customer/{customerId}` | GET | `order.shopId` | **read-scope** filter | |
| `/orders/{id}` GET, `/{id}/detail` GET | GET | parent-lookup | require STAFF | |
| `/orders` | POST (create) | **body** `req.getShopId()` | require SHOP_MANAGER | vendor-created order (public storefront order path is separate/out-of-scope) |
| `/orders/{id}` | PUT (update) | parent-lookup | require SHOP_MANAGER | |
| `/orders/{id}` | DELETE | parent-lookup | require SHOP_MANAGER | |
| `/orders/{id}/submit·confirm·start-preparation·mark-ready·complete·cancel` | POST | parent-lookup | **require STAFF** | KDS order state transitions = STAFF-level (D-03) |

### PromotionController `/promotions` (marketing) → `PromotionService`
| Endpoint | Method | shopId source | Gate |
|----------|--------|---------------|------|
| `/promotions` | GET (list) | `promo.shopId` | **read-scope** by grant set |
| `/promotions/{id}` | GET | parent-lookup | require STAFF |
| `/promotions` | POST | **body** `req.getShopId()` | require SHOP_MANAGER |
| `/promotions/{id}` | PUT | parent-lookup | require SHOP_MANAGER |
| `/promotions/{id}` | DELETE | parent-lookup | require SHOP_MANAGER |

### AnnouncementController `/announcements` (marketing) → `AnnouncementService`
Identical shape to Promotions (create=body `req.getShopId()`; get/update/delete=parent-lookup; list=read-scope). **[VERIFIED: same controller/DTO/entity shape]**

### Service method insertion points (where `shopAccessService.require(...)` goes — D-02)
- `ShopService`: `createShop`(GA) · `getShopById`(STAFF) · `updateShop`(SM) · `deleteShop`(GA) · `uploadLogo/removeLogo/uploadBanner/removeBanner`(SM) · `getAllShops`/`search`(read-scope). **[VERIFIED: ShopController delegation]**
- `ProductService`: `createProduct`(SM, body shopId) · `updateProduct`/`deleteProduct`/image ops(SM, parent) · `getProductById`(STAFF) · `getAllProducts`/`search`(read-scope). **[VERIFIED: ProductController]**
- `OrderService`: `createOrder`(SM) · state transitions(STAFF) · `getAllOrders`/by-status/by-customer(read-scope) · `getOrdersByShop`(STAFF, path) · get/detail(STAFF) · update/delete(SM). **[VERIFIED: OrderController]**
- `PromotionService`: `getAllPromotions`(read-scope) · `getPromotionById`(STAFF) · `createPromotion`(SM) · `updatePromotion`/`deletePromotion`(SM). **[VERIFIED: grep PromotionService methods]**
- `AnnouncementService`: mirror of PromotionService. **[VERIFIED: grep AnnouncementService methods]**

### §3-FLAG — two endpoints need an explicit planner decision
1. **Bulk product import (`/products/bulk/csv`, `/bulk/images`)** — no single `shopId`; the CSV can carry rows for multiple shops. Options: (a) `require(shopId, SHOP_MANAGER)` per parsed row (deny the whole batch if any row is ungranted); (b) restrict bulk import to GROUP_ADMIN for this slice; (c) require an "All shops" context (D-08) + GROUP_ADMIN. **Recommend (a)** for SHOP_MANAGER parity, with a per-row check inside the import loop. Flag for the plan.
2. **Order SSE stream (`/orders/stream`, KDS live feed)** — currently emits **all** tenant orders. For a STAFF/SHOP_MANAGER scoped to one shop, the stream must filter to granted shops or the KDS leaks other shops' orders in real time. Options: filter events against the caller's grant set in `OrderSseService`, or open a per-shop stream. **Recommend** grant-set filtering in the SSE fan-out. This is the one place read-scoping is *not* a simple query filter — flag it as its own task. **[VERIFIED: OrderController `/stream` exists; OrderSseService is the fan-out]**

**Out of scope (do NOT gate):** `PublicStorefrontController` (`/public/**`, `/api/v1/public/**` — permitAll, unauthenticated), `CustomerController` (tenant-scoped, not shop-scoped — customers belong to a tenant, not a shop), `FinancialTransactionController`/`RefundController`/`GdprController`/`OnboardingAdminController`/`TenantAdminController` (already `hasRole('admin')`-gated; the D-03 bridge makes realm-admin an implicit GROUP_ADMIN, so behaviour is unchanged), `WebhookSubscription/DeliveryController` (tenant-scoped webhooks), `SyncController` (edge batch). **[VERIFIED: SecurityConfig permitAll list + controller grep]**

---

## §4 — Cache wiring (D-05)

All three pieces exist and compose cleanly. **[VERIFIED: TenantAwareCacheKeyGenerator.java, CacheConfig.java, TenantCacheEvictor.java]**

- **Backend:** Redis 7 (`RedisCacheManager`). `CacheConfig` is `@Profile("!test")` — so in tests the cache is a no-op and `TenantCacheEvictor` degrades to no-op (safe). **[VERIFIED: CacheConfig line 44]**
- **Key generator:** `TenantAwareCacheKeyGenerator` builds `tenant:{tenantId}:{methodName}:{params}` and **throws `IllegalStateException` if `TenantContext` is unset** (deliberate fail-fast). Membership resolution always runs inside a request with a tenant set, so this is fine.
- **Membership cache pattern (D-05):** add a cache (e.g. `"shopMembership"`) with a short TTL backstop (mirror the `products`/`shops` per-cache TTL config in `CacheConfig.cacheManager`, e.g. 5 min). Cache `ShopAccessService.resolveMembership(userId)` keyed per-user: the generated key is `tenant:{tid}:resolveMembership:{sub}` — **already tenant-isolated** because the tenant prefix is baked in by the key generator. A user_id is unique within a tenant, so no cross-tenant collision.
- **Evict-on-write (D-05):** on grant/revoke, call `TenantCacheEvictor.evictEntity("shopMembership", "resolveMembership", userId)` — it rebuilds the exact key `tenant:{tid}:resolveMembership:{sub}` and evicts that single entry (line 73). The next request re-resolves from `shop_staff`. **No stale-access window.** **[VERIFIED: TenantCacheEvictor.evictEntity]**
- **Caveat:** `evictEntity` reads `TenantContext.get()`. grant/revoke runs inside the acting GROUP_ADMIN's request, whose `TenantContext` is that same tenant — correct, because the target user is in the same tenant. Confirm the evict happens **after** the DB write commits (or in the same tx) so a re-resolve can't race the old row.

---

## §5 — Auth bridge (D-03/D-04/D-09)

The per-request hook is `JwtTenantFilter` (`@Order(200)`, runs after `BearerTokenAuthenticationFilter`, in ALL profiles). **[VERIFIED: JwtTenantFilter.java, SecurityConfig line 191]**

- **`sub` → `user_id`:** `jwt.getSubject()` is the Keycloak `sub`. It becomes `shop_staff.user_id` and `user_directory.user_id`. `JwtTenantFilter` already holds the `Jwt` principal (`auth.getPrincipal() instanceof Jwt jwt`) — the JIT-provision + directory upsert hang off this exact point, right after `TenantContext.set(...)`. **[VERIFIED: JwtTenantFilter lines 57-62]**
- **Realm admin ⇒ implicit GROUP_ADMIN (D-03):** realm roles reach Spring as `ROLE_admin` via `KeycloakRealmRoleConverter` (`realm_access.roles → ROLE_<role>`), composed by `JwtRolesAndScopesConverter`. `ShopAccessService.require(...)` checks `SecurityContextHolder` authorities for `ROLE_admin` first → if present, allow (implicit GROUP_ADMIN) without consulting `shop_staff`. This is the fail-safe covering the pre-provision window. **[VERIFIED: KeycloakRealmRoleConverter.java, JwtRolesAndScopesConverter.java]**
- **JWT claims available for D-09 upsert:** the realm export maps `tenant_id` (user.attribute → claim, `userinfo.token.claim:true`), and every client's `defaultClientScopes` include `profile` + `email`. So the token carries `email` and `name`/`preferred_username`. `tenant_id` is a **managed** attribute (declared), so the KC24 unmanaged-attribute trap does NOT bite email/name (those are standard KC fields, not custom attributes). **[VERIFIED: realm-export.template.json mappers]** **[ASSUMED: that `email`/`name` land in the ACCESS token (not just userinfo) — the default KC `email`/`profile` scopes include access-token mappers; verify against a decoded live access token at plan time.]**
- **D-09 throttle:** the upsert must NOT write per request. Use `INSERT ... ON CONFLICT (tenant_id, user_id) DO UPDATE SET last_seen = now(), email = EXCLUDED.email, display_name = EXCLUDED.display_name WHERE user_directory.last_seen < now() - <interval>` with a config-injected interval, so a returning user within the window is a no-op. Run it best-effort/non-blocking so a directory write never fails a real request (mirror the V49 `afterCommit` best-effort posture if you want it fully off the hot path).
- **Filter ordering trap:** `JwtTenantFilter` runs *after* auth but the JIT/upsert needs a transaction + the tenant GUC set. Simplest: do the provision/upsert lazily inside `ShopAccessService` (called from service methods that are already `@Transactional`, so `TenantSetLocalAspect` has pinned the GUC) rather than in the raw filter (where no tx/GUC exists yet). **Recommend** JIT-provision + directory-upsert live in `ShopAccessService`, invoked on the first `require()`/`resolveMembership()` of the request — not literally in `JwtTenantFilter`. This sidesteps the "filter has no transaction" problem. Flag as a design choice for the plan.

---

## §6 — Typed 403 vs RLS 404 (D-01/D-13)

**RFC 7807 `ProblemDetail` is the house error shape**, centralized in `GlobalExceptionHandler` (`@RestControllerAdvice`). **[VERIFIED: GlobalExceptionHandler.java]**

- **RLS 404 signal:** cross-tenant access surfaces as `ResourceNotFoundException` → `handleResourceNotFound` → **404** `type: https://jtoye.uk/errors/not-found`, title "Resource Not Found". (The RLS wall returns 0 rows → the service throws not-found.) **[VERIFIED: lines 44-50]**
- **Existing generic 403:** Spring's `AccessDeniedException` (from `hasRole('admin')` method-security) → `handleAccessDenied` → **403** `type: https://jtoye.uk/errors/forbidden`, detail "Access denied". **[VERIFIED: lines 221-227]**
- **The shop-scope 403 must be provably DISTINCT** from the RLS 404 (different status AND type) — and should also be distinguishable from the generic admin 403 so the frontend's D-13 access-required state can key on it. **Recommend a dedicated `ShopAccessDeniedException`** + a `@ExceptionHandler` returning **403** with a distinct `type` (e.g. `https://jtoye.uk/errors/shop-access-denied`), title "Shop Access Denied", and a machine-parseable property (e.g. `problem.setProperty("shopId", ...)` + `"requiredRole"`). This satisfies the AI-agent-readiness contract (typed/stable codes) and gives the frontend a stable discriminator. **Do NOT reuse the RLS 404** — blurring the two would leak the tenant-boundary signal (SPEC constraint).
- The last-GROUP_ADMIN guard (D-11) is a **409** (`IdempotencyConflictException` already demonstrates the 409 pattern) — add a `LastGroupAdminException` → 409 `type: .../last-group-admin`.

---

## §7 — MOBL-01 verify-first (CRITICAL) — LIVE 375px EVIDENCE

**Finding: MOBL-01 is ALREADY SATISFIED. There is NO 375px occlusion.** This was verified by driving the running dashboard (Docker Compose full-stack, frontend on :3000) in headless Chromium at a real 375×812 mobile viewport, logged in as the live `admin-user` Keycloak account. **[VERIFIED: live browser probe, 2026-07-19]**

**DOM measurement at 375px (`/dashboard`):**
```
{ viewportWidth: 375, docScrollWidth: 375, docClientWidth: 375,
  horizontalOverflow: false,
  tabBar:  { display:"flex",  width:375, height:56, visible:true },   // fixed bottom MobileTabBar IS shown
  main:    { display:"block", width:375, height:812, visible:true },  // content spans the FULL 375px — sidebar steals nothing
  sidebarSubtitle("OaaS Platform"): { width:0, height:0, visible:false } } // desktop sidebar HIDDEN below md
```
Screenshot (saved during research): slim top bar with "J'Toye" wordmark, full-width content column, fixed 5-item bottom tab bar (Dashboard/Orders/Products/Kitchen/More), no sidebar overlay, no horizontal scroll.

**Why it's already fixed:** `sidebar.tsx` root is `hidden md:flex h-full w-64 …` (line 65) — it occupies **zero** space below the `md` breakpoint. `dashboard-shell.tsx` renders a `md:hidden` slim top bar + a `fixed … md:hidden` `MobileTabBar`. The old occlusion (a `fixed w-64` sidebar overlaying content) described in HANDOFF #104 predates Phase 19's Surface D responsive shell. **[VERIFIED: sidebar.tsx:65, dashboard-shell.tsx, mobile-tab-bar.tsx]**

**Existing regression coverage:** `frontend/e2e/dashboard-mobile.spec.ts` already proves **all 11 dashboard routes** are usable — sidebar hidden, tab bar visible, `h1` not squeezed/overflowing, `main` width ≥ 300 — but pins **390px**, not 375px. `frontend/components/dashboard/__tests__/dashboard-shell.test.tsx` asserts the shell renders the tab bar (`md:hidden`, `fixed`) + 4 primary tabs + More trigger. **[VERIFIED: both files read]**

**→ MOBL-01 task (not a drawer build):**
1. **Add a 375px assertion.** Cheapest: parametrize `dashboard-mobile.spec.ts` (or add a focused case) to also run at `{ width: 375 }`, OR add a 375px case to `dashboard-shell.test.tsx`. The requirement literally says "375px viewport spec" — 390 proves the same responsive contract but does not *name* 375; close the letter of the requirement with a 375 case.
2. **Integrate the D-06 switcher** into (a) desktop sidebar header under the logo (`sidebar.tsx` lines 66-73) and (b) the mobile top bar in `dashboard-shell.tsx` (line 27-30) and/or the "More" sheet. The switcher must not reintroduce overflow at 375px — re-run the probe after.
3. **Update `qa/surface-ledger.json` ONLY with proof** (never silently) to record MOBL-01 as satisfied-by-prior-work + switcher-integrated, per Claude's Discretion note.

**No real occlusion exists — do not author a new sidebar drawer.**

---

## §8 — Frontend switcher + staff screen (VSA-03/04)

- **Nav item (D-10):** add `{ name: "Staff", href: "/dashboard/staff", icon: <Users-like> }` to the shared `navigation` array in `sidebar.tsx` (lines 26-42). This array is the **single source of truth** — `MobileTabBar` imports it, so the Staff item auto-appears in the mobile "More" sheet (it's not a primary tab; `PRIMARY_ORDER` is fixed to Dashboard/Orders/Products/Kitchen). **[VERIFIED: mobile-tab-bar.tsx lines 9-40]**
- **Access-required-on-403 convention (D-10/D-13):** Approvals/Finance render an in-page access-required state when the API returns 403 (the nav item is always present; the *page* gates). Mirror this for `/dashboard/staff` (GROUP_ADMIN-only) and for any shop page hit without a grant. **[CITED: sidebar.tsx line 36-38 comment; CONTEXT D-10]**
- **Switcher persistence (D-07):** mirror the exact `localStorage` idiom already in `sidebar.tsx` (`localStorage.getItem/setItem("theme")`, lines 49-62). Store the selected shopId (or `"all"`) under a key like `shopContext`; hydrate on mount with the same SSR-safe `useEffect` pattern (note the existing `eslint-disable react-hooks/set-state-in-effect` comment they use).
- **API client (VSA-04 endpoints):** `frontend/lib/api-client.ts` is a hardened axios instance that **already attaches** `Authorization: Bearer <session.accessToken>` + `X-Tenant-Id` from the NextAuth session, retries 5xx, and debounces 401-refresh. The new staff endpoints (`GET/POST/DELETE /api/v1/staff...`) just use `apiClient` — no new client plumbing. `session.user.tenantId` is available. **[VERIFIED: api-client.ts lines 1-55]**
- **New backend staff REST (VSA-04):** a `StaffController` gated so only GROUP_ADMIN (or realm-admin) can list/grant/revoke: `GET /api/v1/staff` (list directory + current grants), `POST /api/v1/staff/grant` (user_id, shop_id|null, role), `DELETE /api/v1/staff/{id}` (revoke). Enforce the **last-GROUP_ADMIN 409** (D-11) in the service before revoke/downgrade. Grant/revoke evicts membership cache (§4).
- **Frontend typecheck gate:** touching dashboard TS requires `npm run build` (tsc), **not just jest** — jest does not type-check (bit #87/PR #130). **[CITED: memory feedback_frontend_typecheck_gate]** The planner must include a build-typecheck verification step in 23-03.

---

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---------|-------------|-------------|-----|
| Tenant-scoped RLS policy | A new hand-written policy with `::uuid` cast | `current_tenant_id()` helper (V51) + V43 DO-block template | Raw cast fails `RlsContractTest#noPolicyUsesRawTenantGucCast` AND reintroduces the 22P02 bug class |
| NOSUPERUSER RLS test harness | A bespoke role-downgrade setup | Copy `WebhookSubscriptionRlsPolicyIntegrationTest` + `IntegrationTestSupport.registerPostgresTestProperties` | Bootstrap is superuser → FORCE RLS bypassed without the downgrade |
| Per-user membership cache | A custom `ConcurrentHashMap` | `@Cacheable` + `TenantAwareCacheKeyGenerator` + `TenantCacheEvictor.evictEntity` | Tenant isolation + immediate evict already solved |
| RFC 7807 error body | Ad-hoc JSON error | `ProblemDetail` in `GlobalExceptionHandler` | House convention; distinct `type` URI is the discriminator |
| Realm-role → authority | Re-parsing `realm_access` | `KeycloakRealmRoleConverter` (`ROLE_admin` already emitted) | D-03 bridge just reads the existing authority |
| Idempotent JIT insert | `SELECT-then-INSERT` | `INSERT ... ON CONFLICT DO NOTHING` on `uq_shop_staff_tenant_user_shop` | House reserve idiom (V47/V50); race-safe |
| Mobile nav / responsive shell | A new drawer | Existing `hidden md:flex` sidebar + `md:hidden` `MobileTabBar` | MOBL-01 already satisfied (§7) |
| Switcher persistence | A server preference store | `localStorage` (mirror theme toggle) | D-07; per-device is acceptable, server re-validates every request |

**Key insight:** this phase is ~80% *composition of proven internal patterns*. The failure mode is not "missing a library" — it's copying the V47/V50 raw-cast template instead of the V51-corrected one, or writing a migrate-time backfill that can't exist.

---

## Common Pitfalls

### Pitfall 1: Copying the V47/V50 raw-cast RLS policy verbatim
**What goes wrong:** `RlsContractTest#noPolicyUsesRawTenantGucCast` fails the build; also reintroduces the 22P02 empty-GUC crash on `shop_staff`.
**How to avoid:** use `current_tenant_id()` in USING/WITH CHECK (see §1 skeleton). **Warning sign:** any `::uuid` next to `current_setting('app.current_tenant_id'`.

### Pitfall 2: Writing a migrate-time GROUP_ADMIN backfill
**What goes wrong:** no users table / no `sub` set exists at migrate time; the task is un-implementable and will stall.
**How to avoid:** JIT lazy-provision on first request (D-04). The "backfill idempotency test" is a JIT-provision race test. See §1-FLAG.

### Pitfall 3: Read-scoping the SSE order stream and bulk import as if they had a single shopId
**What goes wrong:** the KDS stream leaks other shops' orders to a scoped user in real time; bulk import silently writes to ungranted shops.
**How to avoid:** grant-set-filter the SSE fan-out; per-row `require()` in bulk import. See §3-FLAG.

### Pitfall 4: Doing JIT-provision / directory-upsert inside `JwtTenantFilter`
**What goes wrong:** the raw filter has no active transaction and the tenant GUC isn't pinned yet, so a JDBC write there runs without RLS context (or fails).
**How to avoid:** do it in `ShopAccessService` on first `require()`/`resolveMembership()`, inside the already-`@Transactional` service call. See §5.

### Pitfall 5: `docs-freshness` CI gate on test counts
**What goes wrong:** adding test files without updating `docs/metrics.json` fails the `docs-freshness` gate (and CLAUDE.md prose counts).
**How to avoid:** run `scripts/docs-freshness.sh --write` after adding tests; baseline today is **1456** logical invocations / schema **V56**. New V52/V53 will bump `schema_version` to 53? No — schema_version tracks HEAD (56); it stays 56 unless a higher V lands. New tests bump the per-lane counts. **[VERIFIED: docs/metrics.json]**

### Pitfall 6: Forgetting the frontend typecheck gate
**What goes wrong:** jest passes but `tsc` (via `npm run build`) fails on the switcher/staff TS.
**How to avoid:** run `npm run build` in 23-03 verification. **[CITED: memory]**

---

## Runtime State Inventory

This phase is **additive greenfield** (new tables + new service + new UI) — it renames/migrates nothing. Each category checked explicitly:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | **None** — `shop_staff`/`user_directory` are new empty tables; no existing datastore keys on a renamed string. Existing `shop_id` columns on products/orders/promotions/announcements are reused as-is (read for parent-lookup), not migrated. | None |
| Live service config | **None** — no n8n/Datadog/Tailscale config embeds any Phase-23 string. Keycloak realm is unchanged (no new roles/clients — D-03 reuses `admin`; SPEC out-of-scope: per-shop KC clients). | None |
| OS-registered state | **None** — no Task Scheduler / pm2 / systemd registration involved. | None |
| Secrets / env vars | **New config keys only** (not secrets): `strict-scoping` flag (D-12, default OFF) + the D-09 throttle interval. Add to `application.yml` with a safe default; no secret rotation. | Add config keys (all profiles) |
| Build artifacts / installed packages | **None** — no package rename; no egg-info/binary carrying an old name. New Java classes + migration + TS components only. | None |

---

## §9 / Validation Architecture

> `workflow.nyquist_validation: true` in `.planning/config.json` — this section is REQUIRED.

### Test Framework
| Property | Value |
|----------|-------|
| Java framework | JUnit 5 + Spring Boot Test + Testcontainers 1.21.3 (real Postgres 15 for RLS) |
| Java config | `core-java/build.gradle` (`test` + `integrationTest` tasks; `@Tag("testcontainers")`) |
| Java quick run | `./gradlew test --tests "uk.jtoye.core.security.ShopStaffRlsPolicyIntegrationTest"` |
| Java full | `./gradlew test integrationTest` |
| Frontend framework | Jest 29.7.0 + @testing-library/react; Playwright 1.59.1 (e2e) |
| Frontend quick run | `cd frontend && npx jest components/dashboard` |
| Frontend typecheck | `cd frontend && npm run build` (tsc — NOT covered by jest) |
| Playwright 375px | `cd frontend && npx playwright test --project=mobile dashboard-mobile.spec` (add a 375px case) |
| Count gate | `scripts/docs-freshness.sh --write` (baseline 1456; enforced by `.github/workflows/docs-freshness.yml`) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File |
|--------|----------|-----------|-------------------|------|
| VSA-01 | `shop_staff`/`_aud`/`user_directory` RLS+FORCE proven cross-tenant under NOSUPERUSER | integration (Testcontainers) | `./gradlew test --tests "*ShopStaffRlsPolicyIntegrationTest"` | ❌ Wave 0: `ShopStaffRlsPolicyIntegrationTest.java` (copy WebhookSubscription template) |
| VSA-01 | `user_directory` email PII hidden cross-tenant (FORCE load-bearing) | integration | same suite | ❌ Wave 0 |
| VSA-01 | JIT-provision idempotent (concurrent first-requests → 1 GROUP_ADMIN row) | integration | `./gradlew test --tests "*ShopAccessJitProvisionTest"` | ❌ Wave 0 |
| VSA-01 | Contract sweeps green (RLS+FORCE, no raw cast) | integration | `./gradlew test --tests "*RlsContractTest"` | ✅ exists (auto-covers new tables) |
| VSA-02 | SHOP_MANAGER on shop A → 403 (typed, distinct type) on shop B write | integration | `./gradlew test --tests "*ShopAccessEnforcementIntegrationTest"` | ❌ Wave 0 |
| VSA-02 | STAFF read-only: can transition order state, denied catalogue write | integration | same suite | ❌ Wave 0 |
| VSA-02 | Read-scoping: scoped user's list returns only granted shops | integration | same suite | ❌ Wave 0 |
| VSA-02 | 403 body `type` ≠ RLS 404 `type` (provably distinct) | unit/integration | same suite | ❌ Wave 0 |
| VSA-03 | Switcher persists selection (localStorage); non-GA cannot see "apply to all" | unit (Jest) | `npx jest components/dashboard` | ❌ Wave 0 |
| VSA-04 | list/grant/revoke; grant→access, revoke→403; last-GROUP_ADMIN→409 | unit (Jest) + integration | `npx jest` + `./gradlew test --tests "*StaffManagementIntegrationTest"` | ❌ Wave 0 |
| MOBL-01 | 375px: sidebar hidden, tab bar visible, no occlusion/overflow | e2e (Playwright) + unit (Jest) | `npx playwright test --project=mobile dashboard-mobile.spec` | ⚠️ 390px exists — add 375px case |

### Sampling Rate
- **Per task commit:** the single new suite for that task (quick run).
- **Per wave merge:** `./gradlew test integrationTest` + `cd frontend && npm run build && npx jest`.
- **Phase gate:** full Java suite + `npx playwright test` green + `scripts/docs-freshness.sh --write` reconciled, before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `ShopStaffRlsPolicyIntegrationTest.java` — VSA-01 RLS + user_directory PII (copy `WebhookSubscriptionRlsPolicyIntegrationTest`)
- [ ] `ShopAccessJitProvisionTest.java` — VSA-01 JIT idempotency (replaces the non-existent "migrate backfill" test)
- [ ] `ShopAccessEnforcementIntegrationTest.java` — VSA-02 cross-shop 403 / STAFF / read-scope / 403≠404
- [ ] `StaffManagementIntegrationTest.java` — VSA-04 grant/revoke/last-GA-409
- [ ] Jest: switcher + staff screen specs — VSA-03/04
- [ ] Playwright: 375px case added to `dashboard-mobile.spec.ts` — MOBL-01
- [ ] `docs/metrics.json` + CLAUDE.md prose counts reconciled via `scripts/docs-freshness.sh --write`
- Framework install: **none** — all frameworks present.

---

## Security Domain

> `security_enforcement` not set to `false` in config → enabled. This phase *introduces a new authorization boundary*, so security is central, not peripheral.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard control (this phase) |
|---------------|---------|-------------------------------|
| V1 Architecture | yes | Second gate strictly *inside* the RLS tenant wall; never widens/bypasses RLS |
| V4 Access Control | **yes (core)** | `ShopAccessService.require(shopId, minRole)`; deny-by-default writes; read-scoping by grant set; last-GROUP_ADMIN guard |
| V2 Authentication | yes | Unchanged JWT; `sub`=identity; realm-admin bridge reuses existing `ROLE_admin` |
| V3 Session Mgmt | yes | Switcher persisted client-side (localStorage) is NOT a trust boundary — server re-validates every grant every request (D-07) |
| V5 Input Validation | yes | `shopId`/`role`/`user_id` validated; `role` DB CHECK constraint; UUID parse guards |
| V7 Error Handling / Logging | yes | RFC 7807 typed 403 distinct from RLS 404; no tenant-boundary signal leak; audit via `shop_staff_aud` |
| V6 Cryptography | no | No new crypto; `user_directory` PII protected by FORCE RLS, not encryption |

### Known Threat Patterns for {Spring Boot RLS + Keycloak, this boundary}
| Pattern | STRIDE | Standard mitigation | Phase-23 note |
|---------|--------|---------------------|----------------|
| Horizontal priv-esc across shops (SHOP_MANAGER acts on ungranted shop) | Elevation | `require(shopId, minRole)` at every service write; read-scope on lists | The core new surface — every §3 row is a mitigation point |
| **Fail-open on provisioning misfire** | Elevation | JIT is fail-**closed** (explicit rows); realm-admin fail-safe only for genuine admins; `strict-scoping=OFF` preserves day-one, `ON` denies ungranted | Verify: an ungranted non-admin user under `strict-scoping=ON` is denied, not auto-granted |
| JIT self-escalation (ungranted user auto-grants GROUP_ADMIN to themselves for another shop) | Elevation | JIT only ever grants a **tenant-wide GROUP_ADMIN to the caller's own sub** while OFF; it cannot mint a grant for a different user or a targeted shop | Confirm JIT never reads a client-supplied role/shop |
| RLS-404 vs 403 confusion masking a tenant breach | Info disclosure | Distinct `type` URIs; 404=RLS wall, 403=shop gate | Test asserts the two types differ |
| `user_directory` PII (email/name) cross-tenant read | Info disclosure | ENABLE+FORCE RLS; NOSUPERUSER proof | FORCE is load-bearing (like idempotency_keys/webhook_subscription) |
| Last-GROUP_ADMIN lockout | DoS (self) | 409 guard (D-11) + realm-admin recovery backstop | Test the final-GA revoke/downgrade → 409 |
| Stale-grant window after revoke | Elevation | Evict-on-write membership cache (D-05) + short TTL | Test revoke→immediate 403 |
| Bulk import / SSE stream over-broad scope | Elevation / Info disclosure | Per-row `require()`; grant-set-filtered SSE fan-out | §3-FLAG — must not ship ungated |
| Directory-upsert write amplification (DoS) | DoS | Throttled `ON CONFLICT ... WHERE last_seen < now()-interval`; best-effort | Never a write per request |

---

## State of the Art

| Old approach | Current approach | When changed | Impact on Phase 23 |
|--------------|------------------|--------------|--------------------|
| Raw `current_setting(...)::uuid` in RLS policies | Safe `current_tenant_id()` helper | V51 (2026-07-14) | New policies MUST use the helper or fail the sweep |
| Fixed `w-64` sidebar overlaying mobile | `hidden md:flex` sidebar + `md:hidden` `MobileTabBar` | Phase 19 Surface D | MOBL-01 already satisfied — verify-only |
| Any-tenant-user-CRUDs-any-shop | Shop-role gate under RLS | **this phase** | The behaviour being introduced |

**Deprecated/outdated:** HANDOFF #104's "fixed w-64 sidebar overlays at mobile width" is **stale** — superseded by Phase 19's responsive shell (proven live at 375px, §7).

---

## Environment Availability

Docker Compose full-stack was **running during research** — probes below are live, not assumed. **[VERIFIED: `docker ps`, curl]**

| Dependency | Required by | Available | Version/Port | Fallback |
|------------|-------------|-----------|--------------|----------|
| Postgres 15 (RLS) | V52 migration, RLS tests | ✓ | :5433 (jtoye-postgres) | — |
| Keycloak 24 | Auth, seed users | ✓ | :8085 (jtoye-keycloak) | — |
| Redis 7 | Membership cache | ✓ | :6379 (jtoye-redis) | test profile no-ops cache |
| Next.js frontend | MOBL-01, switcher, staff UI | ✓ (healthy) | :3000 (jtoye-frontend) | — |
| Core Java API | Enforcement, staff REST | ✓ (healthy) | :9090 | — |
| Playwright + Chromium | 375px probe, e2e | ✓ | 1.59.1, chromium installed | — |
| Testcontainers Docker | RLS integration tests | ✓ | Docker running | — |

**Note (compose XOR k8s):** local dev is Docker Compose (canonical), which is what's up now — do NOT also start a local minikube (shared dev DB). **[CITED: CLAUDE.md runtime topology]**
**Note (cohabiting stack):** `infrastructure-*` containers (a separate OlaJay stack) are also running on :3001/:8000 etc. — J'Toye's frontend is :3000, API :9090. Don't confuse the two. **[VERIFIED: `docker ps`]**

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|-------|---------|---------------|
| A1 | Keycloak `sub` parses as a UUID for all realm users → `user_id UUID` | §1 | If any non-UUID sub exists, `user_id` must be `VARCHAR`; a migration column-type change. Verify a decoded token at plan time. |
| A2 | `email` and `name` land in the ACCESS token (not just userinfo) via default KC `email`/`profile` scopes | §5 | D-09 directory upsert would have null email/name; grant picker shows raw subs. Decode a live access token to confirm. |
| A3 | `user_directory` belongs in the SAME V52 migration as `shop_staff` (one slot) | §1 | If the planner splits it, V53 (Phase 24) must not be consumed; keep both in V52. |
| A4 | Order state transitions are STAFF-level; order create is SHOP_MANAGER-level | §3 | If the vendor wants STAFF to create orders too, adjust the OrderService `createOrder` floor. Grounded in D-03 semantics but a judgement call on `createOrder`. |
| A5 | The generic admin `AccessDeniedException` 403 is acceptable to sit alongside a new distinct shop-scope 403 type | §6 | If a single 403 type is preferred, the frontend D-13 discriminator needs another signal (e.g. a property). |

*These are the items discuss-phase/planner should confirm before locking. Everything else in this research is VERIFIED against a file or a live probe.*

---

## Open Questions

1. **Bulk import scope (`/products/bulk/*`)** — per-row `require()` vs GROUP_ADMIN-only vs All-shops-context? (§3-FLAG). Recommendation: per-row SHOP_MANAGER check.
2. **SSE order stream scoping** — grant-set filter in `OrderSseService` fan-out vs per-shop stream? (§3-FLAG). Recommendation: filter the fan-out.
3. **Where JIT-provision/directory-upsert executes** — in `ShopAccessService` (recommended, has tx+GUC) vs a post-auth interceptor. (§5).
4. **`user_id` column type** — UUID (A1) vs VARCHAR. Resolve by decoding a live `sub`.
5. **Does STAFF create orders?** (A4) — confirm the `createOrder` role floor with the vendor's operational model.

---

## Sources

### Primary (HIGH confidence — files read this session)
- Migrations: `V43__vendor_onboarding.sql`, `V47__processed_order_events.sql`, `V50__idempotency_keys.sql`, `V51__rls_uuid_cast_safety.sql`; dir listing (HEAD=V56, V52/V53 free)
- Tests: `RlsContractTest.java` (the 3 sweeps incl. `noPolicyUsesRawTenantGucCast`), `WebhookSubscriptionRlsPolicyIntegrationTest.java` (NOSUPERUSER recipe), `dashboard-shell.test.tsx`, `dashboard-mobile.spec.ts`
- Security/cache: `SecurityConfig.java`, `JwtTenantFilter.java`, `JwtRolesAndScopesConverter.java`, `KeycloakRealmRoleConverter.java`, `TenantContext.java`, `TenantSetLocalAspect.java`, `TenantAwareCacheKeyGenerator.java`, `CacheConfig.java`, `TenantCacheEvictor.java`, `GlobalExceptionHandler.java`
- Controllers/entities: Shop/Product/Order/Promotion/Announcement Controllers + Product/Order/ShopPromotion/ShopAnnouncement entities + Create*Request DTOs + PromotionService/AnnouncementService method grep
- Frontend: `sidebar.tsx`, `dashboard-shell.tsx`, `mobile-tab-bar.tsx`, `lib/api-client.ts`
- Config/QA: `application*.yml` (out-of-order), `docs/metrics.json` (1456 baseline), `qa/surface-ledger.json`, `.planning/config.json`, realm-export.template.json mappers
- **Live 375px browser probe** (headless Chromium, real Keycloak login) — §7 evidence

### Locked upstream (read)
- `23-CONTEXT.md` (D-01..D-13), `shop-scoped-access-SPEC.md`, `REQUIREMENTS.md` §VSA/§MOBL-01, `CLAUDE.md`

### Tertiary (LOW — flagged as assumptions)
- Access-token email/name claim presence (A2), `sub`-as-UUID (A1) — training/standard-KC knowledge, marked for plan-time verification

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new deps; every reused component read
- Migration/RLS pattern: HIGH — verbatim templates + the sweep landmine confirmed in the test source
- Enforcement inventory: HIGH — every endpoint + shopId source verified against controller/entity source
- MOBL-01 state: HIGH — live DOM measurement + screenshot at 375px
- Backfill↔JIT reconciliation: HIGH — no users table verified; D-04 already decided
- Auth-token claim presence (A2), sub-type (A1): MEDIUM — standard KC behaviour, plan-time verify

**Research date:** 2026-07-19
**Valid until:** ~2026-08-18 (stable internal codebase; re-verify if V52/V53 get consumed by another phase first, or if the realm export changes)

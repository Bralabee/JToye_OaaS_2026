# Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav - Context

**Gathered:** 2026-07-15
**Status:** Ready for planning

<domain>
## Phase Boundary

Add a second, finer authorization boundary **inside** a tenant — the Vendor → Shop
hierarchy — layered *under* the existing RLS tenant wall (which stays the tenant
boundary, untouched). Ships:

1. **`shop_staff`** mapping table (Flyway **V52**) — user ↔ shop ↔ role, ENABLE+FORCE RLS.
2. **App-layer role gate** — a second, finer gate below RLS on shop-scoped endpoints.
3. **Dashboard shop-context switcher** with an explicit "apply to all shops" group action.
4. **Minimal staff-management screen** — list / grant / revoke roles per shop.
5. **MOBL-01** — dashboard nav does not occlude content at 375px (verify-first; see Discretion).

Shop is the finest grain this milestone. An intermediate **department** tier
(Vendor → Department → Shop) is explicitly a future organizational layer, not modeled here.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**Requirements are locked** — do NOT re-litigate WHAT to build. Downstream agents
MUST read the SPEC and the REQUIREMENTS entries before planning/implementing:
- `.planning/specs/shop-scoped-access-SPEC.md` — the DECIDED spec (schema, roles, semantics, enforcement, UI, deferred, constraints).
- `.planning/REQUIREMENTS.md` §"Vendor-scoped access (VSA)" — VSA-01..04 + §MOBL-01.

**Locked requirements:** VSA-01 (`shop_staff` V52), VSA-02 (app-layer enforcement),
VSA-03 (shop-context switcher), VSA-04 (staff-management screen), MOBL-01 (375px nav).

**In scope (from SPEC):** `shop_staff` table + `_aud`; roles GROUP_ADMIN / SHOP_MANAGER / STAFF;
application-layer enforcement on shop-scoped endpoints; dashboard shop-context switcher;
minimal staff management (list/grant/revoke).

**Out of scope (from SPEC — do NOT build):** cross-tenant / platform-operator roles;
per-shop Keycloak clients or shop claims in the token; self-serve user-invitation flows
(Keycloak admin stays the account source); fine-grained per-capability permissions beyond
the three roles; the department tier; changes to the storefront public read path (`/public/*`,
`/shop/*` unauthenticated — out of scope).

</spec_lock>

<decisions>
## Implementation Decisions

*(Requirements were locked by the SPEC; these are the "defaults to confirm at discuss-phase"
HOW decisions, resolved with the user 2026-07-15. Each confirms or refines the spec's default.)*

### Access model
- **D-01 — Read visibility is SCOPED to grants (not writes-only).** For a non-GROUP_ADMIN
  (SHOP_MANAGER / STAFF), the shop switcher **and** the shop-scoped list/read endpoints
  (shops, products, orders, and any per-shop list) surface **only the shops the user has a
  grant on**. GROUP_ADMIN sees all shops plus an **"All shops"** context. This *refines* the
  spec, which wrote "deny-by-default for shop-scoped **writes**"; the user chose to scope reads
  too — defense-in-depth, and the switcher then structurally cannot select a shop the user
  can't act on. Enforcement remains a 403 (typed, RFC 7807) distinct from the RLS 404, and the
  storefront public read path is untouched.
- **D-02 — Enforcement is an explicit service-layer call**, not an annotation.
  `shopAccessService.require(shopId, minRole)` at the top of shop-scoped service methods
  (shops, products, orders, KDS, marketing). Chosen over a custom `@RequireShopRole` SpEL
  annotation because `shopId` frequently comes from a request body or a parent-entity lookup
  (e.g. product → shop), which annotations handle awkwardly. Read-scoping (D-01) is enforced by
  filtering list queries by the caller's grant set (a companion `shopAccessService` read helper),
  not by a post-hoc filter.
- **D-03 — Roles + admin bridge (from spec, unchanged):** GROUP_ADMIN (all shops incl.
  shop create/delete + staff mgmt) / SHOP_MANAGER (full CRUD on granted shop, no staff mgmt,
  no shop create/delete) / STAFF (operational read + order state transitions on granted shop,
  no catalogue writes). Realm `admin` role ⇒ **implicit GROUP_ADMIN** (keeps admin-user working).

### Grant lifecycle
- **D-04 — Backfill via JIT lazy-provision (no Keycloak enumeration).** There is no local
  users table (identities live in Keycloak; the backend only sees a `sub` when a request
  arrives). So the **first authenticated request from a tenant user with no `shop_staff` row
  auto-creates a GROUP_ADMIN row** for that user; realm `admin` ⇒ implicit GROUP_ADMIN acts as a
  fail-safe if provisioning hasn't happened yet. This is **fail-closed** (explicit rows exist),
  needs no live-Keycloak coupling at migrate time, and dodges the KC24 unmanaged-attribute trap.
  Preserves today's "everyone can do everything" behaviour exactly on day one; vendors tighten
  explicitly afterwards. *Rejected:* Keycloak admin-API migration sweep (KC coupling + KC24 trap);
  implicit-only/no-rows (fail-OPEN — unacceptable for an auth boundary).
- **D-05 — Revocation is immediate: evict membership cache on write.** grant/revoke evicts that
  user's membership-cache entry (reuse the `TenantCacheEvictor` pattern); the next request
  re-resolves from `shop_staff`. No stale-access window — correct for an auth boundary. A short
  TTL stays as a backstop. Membership is cached per-user via the tenant-aware key generator.

### Switcher UX
- **D-06 — Switcher in the sidebar header; GROUP_ADMIN defaults to "All shops".** Dropdown under
  the J'Toye logo in `frontend/components/dashboard/sidebar.tsx`; GROUP_ADMIN lands on "All shops"
  (preserves the whole-group view = zero behaviour change day one). A non-GROUP_ADMIN with a single
  grant shows that shop pinned (no dropdown needed). On mobile it rides the existing mobile nav /
  "More" sheet (see MOBL-01 discretion).
- **D-07 — Selection persisted in `localStorage`.** Client-only, instant, no new API — mirrors the
  existing theme-toggle persistence already in `sidebar.tsx`. Per-device is acceptable for a context
  preference; the server re-validates every grant on every request, so persistence is NOT a trust
  boundary. *Rejected:* server-side preference store (new surface, over-scoped); URL `?shop=` param
  (URL clutter, easily lost, complicates All-shops routing).
- **D-08 — Group-wide mutations only via the "All shops" context.** A GROUP_ADMIN triggers a
  group-wide write only when the switcher is on "All shops"; the action is explicit and
  GROUP_ADMIN-gated; any single-shop context does single-shop writes only. One rule, minimal footgun,
  no per-form toggle state. *Rejected:* per-mutation "apply to all" toggle; hybrid context+override
  (both over-scoped for this minimal slice).

### Claude's Discretion
- **MOBL-01 is verify-first, not build-from-scratch.** The dashboard sidebar is already
  `hidden md:flex ... w-64` (`sidebar.tsx:65`) — it is **hidden below `md`**, not overlaying — and
  the `navigation` array already feeds a mobile "More" sheet (`sidebar.tsx:41` comment; the
  `qa/surface-ledger.json` seed branch `feature/ux-mobile-nav-rsc-fixes`). The requirement's stated
  source (HANDOFF #104) predates that mobile-nav work. **Research MUST first verify the actual 375px
  state** (drive it in the browser, not just read code). Likely task = confirm no 375px occlusion +
  integrate the D-06 switcher into the existing mobile nav — NOT author a new drawer. If a real 375px
  occlusion is found, fix it; if not, record MOBL-01 as satisfied-by-prior-work + switcher-integrated
  (update `qa/surface-ledger.json` only with proof, never silently).
- Exact `shop_staff` column types/index names, the cache key shape, and the `ShopAccessService` API
  surface are planner/executor discretion within D-01..D-08 and the spec's schema.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements (read FIRST)
- `.planning/specs/shop-scoped-access-SPEC.md` — the DECIDED spec: schema, role semantics,
  enforcement model, UI, explicitly-deferred items, constraints. **Source of truth for WHAT.**
- `.planning/REQUIREMENTS.md` §"Vendor-scoped access (VSA)" (lines ~40–50) + §MOBL-01 (line ~66) —
  VSA-01..04 acceptance criteria + test expectations; migration-numbering note (shop_staff = V52,
  must precede V53 media_asset in Phase 24; `out-of-order=true` in all profiles).
- `.planning/ROADMAP.md` §"Phase 23" — phase boundary + dependency notes.

### RLS + migration pattern to mirror
- `core-java/src/main/resources/db/migration/` — mirror the **V47** (`processed_order_events`)
  and **V50** (`idempotency_keys`) ENABLE+FORCE RLS tenant-scoped policy pattern for `shop_staff`.
- RLS must be proven under the **NOSUPERUSER role-downgrade** — see the existing `RlsContractTest`
  pattern (test dir under `core-java/src/test/java/...`); add a `shop_staff` policy proof.

### Enforcement + auth wiring
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` — `@EnableMethodSecurity`
  is already active (issue #83).
- `core-java/src/main/java/uk/jtoye/core/security/JwtRolesAndScopesConverter.java`,
  `KeycloakRealmRoleConverter.java` — how realm roles reach `hasRole('admin')`; the D-03 admin
  bridge reads realm role here.
- `core-java/src/main/java/uk/jtoye/core/security/TenantContext.java` — thread-local tenant id;
  the membership resolver reads tenant + `sub` here.
- Existing `@PreAuthorize("hasRole('admin')")` gates as the reference for typed 403 behaviour:
  `gdpr/GdprController.java`, `payment/RefundController.java`, `finance/FinancialTransactionController.java`,
  `onboarding/OnboardingAdminController.java`, `tenant/TenantAdminController.java`.

### Cache (membership resolution + eviction)
- `core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java`,
  `config/CacheConfig.java`, `config/TenantCacheEvictor.java` — reuse for the per-user membership
  cache (D-05 evict-on-write).

### Frontend surfaces
- `frontend/components/dashboard/sidebar.tsx` — switcher home (D-06); `navigation` array feeds
  the mobile "More" sheet; existing `localStorage` theme persistence to mirror for D-07.
- `qa/surface-ledger.json` — the surface-parity baseline + endpoint-inventory seed for VSA-02;
  update ONLY with proof (never silently) if MOBL-01 state changes.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`TenantAwareCacheKeyGenerator` + `TenantCacheEvictor` + `CacheConfig`** — drop-in for the
  per-user membership cache with immediate eviction (D-05).
- **`@EnableMethodSecurity` + `@PreAuthorize("hasRole('admin')")`** — the admin realm-role gate is
  already wired; the D-03 admin⇒GROUP_ADMIN bridge builds on the same role plumbing.
- **`sidebar.tsx` `navigation` array + `localStorage` theme toggle** — the switcher reuses this
  component + persistence idiom (D-06/D-07); the array already flows into the mobile nav.
- **V47 / V50 migrations + `RlsContractTest`** — the exact ENABLE+FORCE RLS + NOSUPERUSER proof
  template for `shop_staff`.

### Established Patterns
- **RLS is the tenant wall; app-layer gate is additive** — never widen or bypass RLS; the 403 gate
  sits *inside* the tenant boundary and its error body must stay distinct from the RLS 404 (D-01).
- **Typed RFC 7807 errors** — the shop-scope 403 follows the existing Problem Detail convention.
- **`_aud` Envers mirror** — `shop_staff` gets an `_aud` table per project convention.

### Integration Points
- New `shop_staff` table + repository + `ShopAccessService` (resolve membership, `require(shopId, role)`,
  read-scope helper) in a new `security`/`access` area under `uk.jtoye.core`.
- `shopAccessService.require(...)` calls inserted at the top of shop-scoped service methods
  (ShopService, ProductService, OrderService, KDS, marketing) — enumerate the full endpoint
  inventory during planning from `qa/surface-ledger.json` + the controller list.
- Staff-management UI + switcher wire into the dashboard shell (`sidebar.tsx`) and a new
  `/dashboard/staff` (or similar) screen; grant/revoke calls a new authenticated staff endpoint.

</code_context>

<specifics>
## Specific Ideas

- The switcher's "All shops" is a first-class context, not a null selection — it is what a
  GROUP_ADMIN lands on and the only context in which group-wide writes are offered (D-08).
- Read-scoping (D-01) should be implemented as **query-level filtering by the caller's grant set**,
  so a scoped user's lists are genuinely narrowed server-side (not just UI-hidden) — the switcher
  reflecting only granted shops is a consequence, not the enforcement.

</specifics>

<deferred>
## Deferred Ideas

- **Department tier (Vendor → Department → Shop)** — noted in the spec + REQUIREMENTS as a future
  organizational layer; not modeled in v2.3.
- **Self-serve user invitation / account creation** — stays in Keycloak admin this milestone
  (KC24 unmanaged-attribute trap applies to any future programmatic creation).
- **Server-side (cross-device) switcher preference** — considered for D-07, deferred as a new
  surface out of proportion to this slice; revisit if users report per-device drift.
- **Fine-grained per-capability permissions beyond the three roles** — explicitly out of scope.

None of the above were in-scope creep — discussion stayed within the phase boundary.

</deferred>

---

*Phase: 23-Vendor-Scoped Access + Responsive Dashboard Nav*
*Context gathered: 2026-07-15*

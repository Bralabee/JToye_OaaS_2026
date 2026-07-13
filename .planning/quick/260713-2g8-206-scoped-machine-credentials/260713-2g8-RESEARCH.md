# Quick Task 260713-2g8: #206 [AI-4] Scoped Machine Credentials — Research

**Researched:** 2026-07-13
**Domain:** OAuth2/OIDC client scopes → Spring Security method authorization (Keycloak 24.0.5 + Spring Boot 3.5.16)
**Branch researched against:** `feature/204-idempotency-key` (PR #211 mid-CI — clean base for this work)
**Confidence:** HIGH (all claims are file:line-backed against the live tree; only Keycloak-runtime behaviours carry residual MEDIUM)

## Summary

The current authorization surface is **realm-role only** (#83): a single custom `KeycloakRealmRoleConverter` maps `realm_access.roles → ROLE_*` and — critically — **REPLACES** Spring's default authority converter, so the `scope` claim is *not* currently mapped to any `SCOPE_*` authority. Six controllers carry class-level `@PreAuthorize("hasRole('admin')")`; the `/products` catalog surface (the AC-1 target) carries **no** method-security annotation today — any authenticated tenant token can list and create.

The clean, additive design is: (1) a **combined converter** that keeps `KeycloakRealmRoleConverter` for roles and *adds* Spring's stock `JwtGrantedAuthoritiesConverter` for scopes (`scope`/`scp` → `SCOPE_*`); (2) define `catalog:read` / `catalog:write` (+ `orders:*` for the AI-1 feed) as Keycloak **client scopes** with `include.in.token.scope=true`; (3) add them as **default client scopes on the existing `core-api` client** so every operator/dashboard token (minted via `core-api` — `frontend/auth.ts:22,53`, `.env.example:98`) automatically carries them → operators are unchanged; (4) mint a **new machine client** (client-credentials + service account) granted **only** `catalog:read`; (5) gate `/products` **writes** on `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` and leave reads authenticated-only. Result: the read-only machine token gets 200 on list (it is authenticated) and 403 on create (no `catalog:write`), while operators keep working because `core-api` grants them `catalog:write` by default.

**Primary recommendation:** Combined role+scope converter (additive, do not touch #83's role path); positive `SCOPE_catalog:write` gate on writes only; `catalog:*` as default scopes on `core-api` (operators) vs a single scope on the new machine client. This satisfies both ACs *without re-roling operators* and without a negative/deny SpEL expression.

---

## User Constraints (from issue #206)

### Locked scope (acceptance criteria)
1. Client-credentials token with `catalog:read` only → **200** on product list, **403** on product create (integration test).
2. Existing operator/admin flows **unchanged** (the #83 role tests stay green).
3. Realm template updated + re-import documented; scopes listed in API docs.
4. Feeds **[AI-1] MCP auth model** — keep the scope taxonomy MCP-consumable.

### Locked design directives (from "Fix direction")
- **Additive** — do not disturb the #83 `realm_access.roles → ROLE_*` flow.
- Enforce via `@PreAuthorize` scope checks on the **public-facing machine surface**.
- Realm-template changes must respect the **KC24 unmanaged-attribute trap** (declare `tenant_id` managed).
- Ship a **documented client-credentials recipe** per tenant integration.

### Out of scope (this slice)
- Only **catalog** endpoints are required by the AC (products list=read, create=403). `orders:*` scopes may be *defined* to seed the AI-1 taxonomy but need not be enforced here.
- No changes to edge-go authorization logic (see Audit §4 — edge does not front `/products`).

---

## Audit — Current Auth Surface (evidence)

### 1. The #83 role converter and how it is wired
- `core-java/.../security/KeycloakRealmRoleConverter.java:31` — `implements Converter<Jwt, Collection<GrantedAuthority>>`; reads `realm_access` (`:39`) → `roles` (`:43`) → `SimpleGrantedAuthority("ROLE_" + role)` (`:49`). Defensive: returns `List.of()` when the claim is absent/malformed (`:41,:45`).
- **Wiring — load-bearing:** `SecurityConfig.java:41-45` builds a `JwtAuthenticationConverter` and calls `converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter())` (`:43`). This **replaces** the framework default. Registered on the resource server at `SecurityConfig.java:157-158` (`oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(...))`).
- **Consequence (verified):** because the default converter is fully replaced, **no `SCOPE_*` authority is produced today**. The additive design must merge scope-mapping back in — it is not present to "not disturb". `[VERIFIED: SecurityConfig.java:43 + grep found zero other JwtGrantedAuthoritiesConverter/SCOPE_ references in main]`

### 2. Every `@PreAuthorize` in the codebase (all `hasRole('admin')`, all class-level)
| Controller | File:line | Real path (WebConfig `/api/v1` prefix) |
|---|---|---|
| RefundController | `payment/RefundController.java:53` | `/api/v1/orders/{id}/refund` (hard-coded) |
| GdprController | `gdpr/GdprController.java:27` | `/api/v1/gdpr/**` |
| FinancialTransactionController | `finance/FinancialTransactionController.java:39` | `/api/v1/financial-transactions` |
| OnboardingAdminController | `onboarding/OnboardingAdminController.java:46` | `/api/v1/…` |
| DevTenantController | `tenant/DevTenantController.java:26` | dev/local only |
| TenantAdminController | `tenant/TenantAdminController.java:51` | `/api/v1/admin/tenants` |

`@EnableMethodSecurity` at `SecurityConfig.java:30`. **`ProductController` has NO `@PreAuthorize`** — header at `product/ProductController.java:34-40` shows only `@RequestMapping("/products")` + `@SecurityRequirement`; `create()` at `:132-149` (`@PostMapping`, `@Valid @RequestBody CreateProductRequest`); `list()` at `:54-66` (`@GetMapping`). So today any authenticated tenant token can both list and create. `[VERIFIED: grep of @PreAuthorize across core-java/src/main]`

### 3. JWT validation chain and the `tenant_id` path for service accounts
- Decoder: `SecurityConfig.jwtDecoder()` `:74-103`. Validator stack (`:99-101`) = `DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(expectedIssuer), new AudienceValidator(expectedAudience))`.
- **Issuer split-horizon (#87):** `expectedIssuer` (`:60-61`, `JWT_EXPECTED_ISSUER`) is validated against the token `iss`, decoupled from the internal JWKS host `issuerUri` (`:50-51`).
- **Audience (#88):** `AudienceValidator.java:36-45` rejects any token whose `aud` does not contain `expectedAudience` (default `core-api`). Constructor throws on blank (`:27-31`) — fail-closed. **Pitfall:** a machine client whose token lacks `aud=core-api` is **401'd before any scope check runs**. The new client MUST carry the `core-api` audience mapper (see Design §Keycloak).
- **Tenant extraction:** `JwtTenantFilter.java:81-92` reads `tenant_id → tenantId → tid`, parses UUID, sets `TenantContext`. If absent for an authenticated principal, it increments `tenant.context.missing` counter (`:68-70`, #98) and logs a warn — context stays unset and RLS then blocks all tenant rows. `[VERIFIED: JwtTenantFilter.java:56-72]`
- **Service-account gap (load-bearing):** in the realm template, `service-account-core-api` has **no `tenant_id` attribute** (`realm-export.template.json:421-434` — only `realmRoles:[default-roles-jtoye-dev]`). The `tenant-id-mapper` protocol mapper exists **on the `core-api` client** (`:691-703`, `oidc-usermodel-attribute-mapper`, `user.attribute=tenant_id`), so it *would* emit the claim **if** the service-account user carried the attribute. A new machine client's SA user therefore MUST be seeded with `attributes.tenant_id`. `[VERIFIED: realm-export.template.json:421-434, 691-703]`

### 4. Edge-go — does NOT front the catalog surface
- Edge protected routes: `edge-go/cmd/edge/main.go:214-216` — only `POST /api/v1/sync/batch` behind `jwtMiddleware.Validate()`; plus the unauthenticated `POST /api/v1/webhooks/whatsapp` (`:211`). **No `/products` route.** Scoped catalog tokens go **browser/integration → core-java directly**, never through edge.
- Edge JWT middleware validates **signature + issuer + audience + tenant_id presence only** (`edge-go/internal/middleware/jwt.go:167-209`); it does **not** inspect `scope` or `realm_access` (`:190` aud, `:195-209` tenant). It also **rejects tokens with no `tenant_id`** (`:206-208`). Implication: scoped tokens pass through edge unchanged *if* they carry `tenant_id` — but they don't hit edge for this slice, so **no edge change is required**. `[VERIFIED: main.go:214-216, jwt.go:186-209]`

### 5. Realm-template infrastructure and which realm machine clients belong to
- Two realms: **`jtoye-dev`** (staff/operators + `core-api`/`edge-api` service accounts — `realm-export.template.json:3`) and **`jtoye-customers`** (B2C storefront only, client `storefront-client`, `serviceAccountsEnabled:false` — `realm-export-customers.template.json:2,68,83`). Machine/integration clients belong to **`jtoye-dev`** (same issuer + `aud=core-api` as the business API). `[VERIFIED: both templates]`
- Render + import: `docker-compose.full-stack.yml:43-73` renders `realm-export.json` from the template via an **envsubst sidecar** (P0-4 #80) substituting `KEYCLOAK_CLIENT_SECRET`, `EDGE_API_CLIENT_SECRET`, `KC_SEED_USER_PASSWORD`; Keycloak starts with `--import-realm` (`:81`) mounting the rendered files (`:102-105`).
- **Re-import pitfall:** `--import-realm` on `start-dev` **only creates realms that do not already exist** — it will NOT overwrite an existing `jtoye-dev`. Applying template changes to a running realm requires either a **Keycloak DB drop + restart** (memory-confirmed for #87/#102) or `kc.sh import --file … --override true` (`infra/keycloak/README.md:107-108`). Document this in the re-import section. `[VERIFIED: compose:81 + README:107; CITED: memory p2_remediation/#87 "realm needs Keycloak DB-drop + re-import"]`

### 6. The public machine surface for slice 1 (catalog)
`ProductController` (`product/ProductController.java`): reads `GET /products` (`:54`) + `GET /products/{id}` (`:67`); writes `POST /products` (`:132`), `PUT /products/{id}` (`:150`), `DELETE /products/{id}` (`:240`). Runtime paths carry the `/api/v1` prefix (`config/WebConfig.java:34,61-62`). AC-1 only exercises **list (read)** and **create (write)**.

---

## Design Recommendation

### A. Combined role + scope converter (additive; the #83 path is untouched)
Add a new converter that composes both mappings and wire it in place of the bare role converter:

```java
// New: security/JwtRolesAndScopesConverter.java
public final class JwtRolesAndScopesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final KeycloakRealmRoleConverter roles = new KeycloakRealmRoleConverter();      // #83, unchanged
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter(); // stock: scope/scp -> SCOPE_*
    @Override public Collection<GrantedAuthority> convert(Jwt jwt) {
        var out = new LinkedHashSet<GrantedAuthority>();
        out.addAll(roles.convert(jwt));   // ROLE_admin, ROLE_user, ...
        out.addAll(scopes.convert(jwt));  // SCOPE_catalog:read, SCOPE_catalog:write, ...
        return out;
    }
}
```
Then `SecurityConfig.java:43` → `converter.setJwtGrantedAuthoritiesConverter(new JwtRolesAndScopesConverter())`. The stock `JwtGrantedAuthoritiesConverter` reads the `scope` claim (space-delimited) or `scp`, prefixing each with `SCOPE_` — this is exactly Spring's documented default and is what #83 threw away. `[VERIFIED: SecurityConfig.java:43 replacement; CITED: Spring Security JwtGrantedAuthoritiesConverter default prefix "SCOPE_"]`

### B. Colon in authority strings — verified safe
`@PreAuthorize("hasAuthority('SCOPE_catalog:read')")` is valid. The colon lives inside a SpEL single-quoted string literal and `hasAuthority` is exact string equality — no SpEL parsing of the colon. This mirrors Spring's own canonical example authority `SCOPE_message:read`. Prefer the **colon** form (`catalog:read`) to match OAuth/Keycloak scope convention. `[VERIFIED: SpEL string-literal semantics; CITED: Spring Security docs use "SCOPE_message:read" idiomatically]`

### C. Enforcement model that keeps operators unchanged (the crux)
Evidence shows **operators carry no distinguishing role**: `tenant-a-user` has only `default-roles-jtoye-dev` (no explicit `realmRoles` block — `realm-export.template.json:449-467`), and that composite expands to `offline_access` + `uma_authorization` only (`:50-63`) — **not** `user`, **not** `admin`. The new machine service account would carry the *same* `default-roles-jtoye-dev`. **Therefore role-based discrimination on writes is impossible without re-roling.** Use **scope** as the discriminator instead:

- **Reads** (`GET /products`, `GET /products/{id}`): **leave ungated** (`isAuthenticated()` via `anyRequest().authenticated()` at `SecurityConfig.java:151`). The `catalog:read` machine token is a valid authenticated JWT → **200** (satisfies AC-1a with zero blast radius on other readers). *Optional* defense-in-depth: `@PreAuthorize("hasAuthority('SCOPE_catalog:read')")` — safe only because `core-api` will grant operators that scope by default (D below); recommend deferring to keep the slice minimal.
- **Writes** (`POST`/`PUT`/`DELETE /products`): `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")`.
  - Operators pass because `core-api` (their client) grants `catalog:write` as a **default** scope (D below) → **unchanged behaviour**, AC-2 ✓.
  - The read-only machine client holds only `catalog:read` → **403** on create, AC-1b ✓.
- **Why positive-scope, not a role fallback:** a `hasRole('vendor') or …` composite would require inventing a `vendor` role and assigning it to every operator user (larger change, and still needs a realm edit). Granting operators the scope via their existing client is smaller and purely additive. Recommend the positive-scope gate.

> **Note for the AC list:** the issue lists scopes "e.g. `catalog:read, orders:read, orders:write`" — the "e.g." is illustrative. This slice needs **`catalog:write`** for the write gate; add it to the taxonomy. Define `catalog:read`, `catalog:write` now; optionally pre-define `orders:read`/`orders:write` (unenforced) to seed AI-1.

### D. Keycloak side (realm template)
1. **Define client scopes** in `clientScopes[]` (`realm-export.template.json:920+`): `catalog:read`, `catalog:write` (+ optional `orders:read`/`orders:write`), each `protocol:openid-connect`, `attributes.include.in.token.scope:"true"` (this is what puts the scope *name* into the `scope` claim that Spring reads — mirrors the built-in `email`/`phone` scopes at `:926,:967,:1008`). **No protocol mapper is needed** for name-in-`scope`-claim.
2. **Operators keep working:** add `catalog:read` + `catalog:write` to the **`core-api` client's `defaultClientScopes`** (`:717`). Every dashboard/operator token then carries them automatically.
3. **New machine client** (e.g. `integration-catalog-ro`): `serviceAccountsEnabled:true`, `standardFlowEnabled:false`, `directAccessGrantsEnabled:false`, `publicClient:false`, `clientAuthenticatorType:client-secret`, secret via envsubst placeholder (mirror `:628-629`). Replicate two mappers from `core-api`: the **`tenant-id-mapper`** (`:691-703`) and the **`core-api-audience-mapper`** (`:705-716`, `included.client.audience:core-api`) — the audience mapper is mandatory or `AudienceValidator`/#88 gives 401. Assign **only `catalog:read`** in `defaultClientScopes`.
4. **Service-account user** for the machine client: add a user block with `serviceAccountClientId:"integration-catalog-ro"` and `attributes.tenant_id:["<tenant-uuid>"]` (mirror the seed-user pattern at `:405-407`). Seeding via **template import** preserves the attribute (import is not subject to the KC24 admin-API strip — see Pitfalls).

### E. Client-credentials recipe (document per tenant integration)
```bash
# One machine client per tenant integration (jtoye-dev realm)
curl -s -X POST "$KC/realms/jtoye-dev/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=integration-catalog-ro \
  -d client_secret="$INTEGRATION_SECRET"
# -> access_token carries: aud=core-api, tenant_id=<uuid>, scope="... catalog:read"
curl -s "$CORE/api/v1/products"            -H "Authorization: Bearer $TOK"   # 200
curl -s -X POST "$CORE/api/v1/products" -H "Authorization: Bearer $TOK" ...  # 403
```
For **programmatic per-tenant minting**, reuse the `KeycloakAdminClient` seam from #102 (`260712-hnc`) — but note the managed-attribute requirement below.

### F. Testing approach (mirror #83, no Keycloak in CI)
Follow `RoleBasedAccessIntegrationTest.java` exactly: `@SpringBootTest @AutoConfigureMockMvc @Testcontainers @ActiveProfiles("test")`, real Postgres + Flyway + RLS. Mint tokens with the MockMvc `jwt()` post-processor supplying authorities via the **real** converter:
```java
jwt().jwt(j -> j.claim("tenant_id", TENANT_A.toString())
                .claim("scope", "catalog:read"))            // machine, read-only
     .authorities(new JwtRolesAndScopesConverter());
```
(`RoleBasedAccessIntegrationTest.java:77-90` uses the identical `.authorities(new KeycloakRealmRoleConverter())` shape.) Assertions: `catalog:read` token → `get("/api/v1/products")` **200**, `post("/api/v1/products")` **403**; an operator-shaped token (`scope:"catalog:read catalog:write"`) → create **not-403**. Add a `JwtRolesAndScopesConverterTest` unit test mirroring `KeycloakRealmRoleConverterTest.java` proving roles+scopes merge and neither claim's absence throws. **CI has no Keycloak** — #83/#87 proved gates via the converter-through-MockMvc pattern; live E2E vs the running Keycloak `:8085` is an optional plus, not a CI dependency. `[VERIFIED: RoleBasedAccessIntegrationTest.java:23,77-90; KeycloakRealmRoleConverterTest.java]`

---

## Don't Hand-Roll

| Problem | Don't build | Use instead |
|---|---|---|
| `scope`/`scp` → authorities | A manual scope-claim parser | Stock `JwtGrantedAuthoritiesConverter` (default `SCOPE_` prefix, handles string+list, `scope` then `scp`) |
| Scope-name in token | Custom protocol mapper | Client scope with `include.in.token.scope=true` (KC emits the name into `scope`) |
| Audience on machine token | Bespoke aud injection | Existing `oidc-audience-mapper` (`included.client.audience:core-api`), copied from `core-api` |
| tenant_id on SA token | New filter logic | Existing `oidc-usermodel-attribute-mapper` (`tenant-id-mapper`) + attribute on the SA user |
| Token minting in tests | Real Keycloak in CI | `SecurityMockMvcRequestPostProcessors.jwt().authorities(realConverter)` (the #83 precedent) |

---

## Common Pitfalls

1. **KC24 unmanaged-attribute strip (memory-confirmed).** The realm template declares **no** `userProfile` / `unmanagedAttributePolicy` (grep found none in `realm-export.template.json`). Existing users keep `tenant_id` because it arrives via **realm import**, which is *not* subject to the strip. But **admin-API creation** of a per-tenant machine client's SA user will **silently drop `tenant_id`** unless the realm declares it managed. → For the slice (template-seeded sample client) this is fine. For the programmatic per-tenant recipe (§E, KeycloakAdminClient), first declare `tenant_id` managed (add a `userProfile` config or `unmanagedAttributePolicy:"ENABLED"` on the realm) — otherwise the minted client's tokens carry no tenant and RLS returns zero rows. `[CITED: memory reference_keycloak24_user_profile_trap + 260712-hnc finding]`
2. **Audience validator runs before scopes.** A machine client without the `core-api` audience mapper → `AudienceValidator` (#88) 401 at decode time; the `@PreAuthorize` scope check never executes. Always add the audience mapper.
3. **The default converter is *replaced*, not augmented.** If you only add a scope converter and forget to re-include `KeycloakRealmRoleConverter`, every `hasRole('admin')` gate (refunds/finance/GDPR/tenant-admin) breaks → AC-2 fails. The combined converter must emit **both** authority families.
4. **`scope` is a space-delimited string in Keycloak**, not an array. Spring's stock converter handles this; a hand-rolled parser splitting on comma would fail. In tests, set `.claim("scope", "catalog:read catalog:write")`.
5. **`--import-realm` won't overwrite an existing realm.** Template edits require DB-drop + re-import or `kc.sh import --override true`. Forgetting this means the new scopes/client never appear and live E2E silently uses the stale realm.
6. **Don't gate reads on `SCOPE_catalog:read` unless `core-api` grants it by default** — otherwise pre-existing operator tokens (and any non-machine caller) 403 on product list, breaking AC-2. Recommended: leave reads authenticated-only for this slice.
7. **Don't weaken the existing 403 posture.** The six `hasRole('admin')` gates and `anyRequest().authenticated()` (`SecurityConfig.java:151`) must remain. Scope gates are strictly *additional* denials on the write surface.

---

## Assumptions Log

| # | Claim | Risk if wrong |
|---|---|---|
| A1 | Operators authenticate via the `core-api` client, so adding `catalog:*` as `core-api` default scopes reaches all operator tokens. | Evidence is strong (`frontend/auth.ts:22,53`, `.env.example:98`) but if a separate operator client exists in prod, its default scopes need the same edit. LOW risk. |
| A2 | Keycloak 24 emits a client scope's name into the `scope` claim when `include.in.token.scope=true` with no mapper. | Matches built-in scopes' config; if KC24 changed this, a name-mapper would be needed. Verify on live `:8085` during impl. LOW risk. |
| A3 | Adding `catalog:write` to the scope taxonomy is acceptable despite the issue's example list omitting it. | The AC needs a positive write scope; "e.g." implies latitude. Confirm with issue owner if strict. LOW risk. |

## Open Questions (RESOLVED)

1. **Per-tenant vs shared machine client** — one client per tenant (SA carries that tenant's `tenant_id`) or one shared client with tenant selected another way? Recommend **one client per tenant integration** (SA `tenant_id` attribute is the natural RLS carrier; no cross-tenant token). Confirm at plan time; feeds AI-1. **RESOLVED (plan 260713-2g8):** one client per tenant integration; this slice ships ONE template-seeded sample client (`integration-catalog-ro`, tenant A SA `tenant_id`) as the reference implementation.
2. **Managed-attribute declaration now or later** — declare `tenant_id` managed in this slice (enables the programmatic recipe) or defer to AI-1? Recommend declaring it now to unblock the documented recipe. **RESOLVED (plan 260713-2g8):** documented-but-deferred — docs/security-scopes.md documents the KC24 trap and the managed-declaration prerequisite for programmatic per-tenant minting; the realm `userProfile` change itself is deferred to the [AI-1] provisioning slice (the template-imported sample client is unaffected).

## Environment Availability

| Dependency | Required by | Available | Notes |
|---|---|---|---|
| Postgres (Testcontainers) | scope integration test | ✓ | `postgres:15`, per `RoleBasedAccessIntegrationTest.java:52` |
| Keycloak 24.0.5 `:8085` | optional live E2E | ✓ (dev stack) | CI has none — gates proven via converter+MockMvc (#83 pattern) |
| envsubst sidecar | realm render | ✓ | `docker-compose.full-stack.yml:43-73` |

## Validation Architecture

- **Framework:** JUnit 5 + Spring Security Test + Testcontainers (Java). Quick run: `./gradlew test`; RLS suite: `./gradlew integrationTest`.
- **Req → test map:** AC-1 → `ScopedCatalogAccessIntegrationTest` (`catalog:read` → 200 list / 403 create; operator scope → create not-403) [❌ Wave 0]; combined-converter unit → `JwtRolesAndScopesConverterTest` [❌ Wave 0]; AC-2 → existing `RoleBasedAccessIntegrationTest` re-run green [✅ exists].
- **Metrics gate:** `docs/metrics.json` baseline `total_logical_invocations:1199` (`java_test_methods:863`, `java_test_files:141`). New tests bump these; the `docs-freshness` CI gate (`scripts/docs-freshness.sh`) fails on drift — update `docs/metrics.json` **and** the `CLAUDE.md` testing-baseline line in the same PR. `[VERIFIED: docs/metrics.json:2-12]`

## Security Domain (ASVS)

| Category | Applies | Control |
|---|---|---|
| V4 Access Control | yes | Least-privilege client scopes; positive `SCOPE_catalog:write` gate on writes; per-tenant SA |
| V5 Input Validation | yes | Existing `@Valid CreateProductRequest` unchanged |
| V6 Cryptography | no (reuse) | JWT signature/issuer/audience already enforced (#87/#88) |
| V7 Auth | yes | client-credentials grant; audience mapper mandatory (token-confusion defense, #88) |

STRIDE — **Elevation of privilege** (read-only integration performing writes): mitigated by positive write-scope gate. **Spoofing** (token minted for another client): mitigated by `AudienceValidator` `aud=core-api`. **Information disclosure** (cross-tenant read): mitigated by SA `tenant_id` + RLS.

## Sources

**Primary (HIGH — this repo):** `SecurityConfig.java:30,41-45,74-103,151,157-158`; `KeycloakRealmRoleConverter.java:31-51`; `AudienceValidator.java:27-45`; `JwtTenantFilter.java:56-92`; `ProductController.java:34-40,54,132-149`; `WebConfig.java:34,61-62`; `RoleBasedAccessIntegrationTest.java:23,77-90`; `KeycloakRealmRoleConverterTest.java`; `realm-export.template.json:3,49-63,405-434,624-718,920-1010`; `realm-export-customers.template.json:2,68,83`; `edge-go/cmd/edge/main.go:211-216`; `edge-go/internal/middleware/jwt.go:167-209`; `frontend/auth.ts:22,53`; `.env.example:98`; `docker-compose.full-stack.yml:43-105`; `infra/keycloak/README.md:107-108`; `docs/metrics.json:2-12`.

**Secondary (MEDIUM — memory):** `reference_keycloak24_user_profile_trap`; `p2_remediation` (#87/#102 re-import); `jwt_issuer_jwks_split_horizon`.

**Metadata:** confidence — audit HIGH (file:line), design HIGH (additive path is standard Spring), Keycloak-runtime MEDIUM (verify scope emission + managed-attribute on live `:8085`). Valid until ~2026-08-13 (stable stack).

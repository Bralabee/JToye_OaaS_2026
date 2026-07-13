---
phase: quick-260713-2g8-206-scoped-machine-credentials
verified: 2026-07-13T02:53:28Z
status: passed
score: 7/7 must-haves verified
overrides_applied: 0
---

# Quick Task 260713-2g8: #206 [AI-4] Scoped Machine Credentials Verification Report

**Task Goal:** GitHub issue #206 [AI-4] — Scoped machine credentials. A client-credentials token scoped to `catalog:read` only can list products (200) but cannot mutate them (403); operator/admin flows unchanged; realm template updated with catalog scopes + read-only machine client; scope taxonomy documented and feeds the [AI-1] MCP model (#203).

**Verified:** 2026-07-13T02:53Z
**Status:** passed
**Branch:** feature/206-scoped-machine-credentials (commits 75eecb6, 8d3fa38, 1717f98; merge 706235d; base d60f668) — 706235d confirmed as ancestor of current HEAD.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A JWT carrying `scope="catalog:read"` (no realm role) gets 200 on GET /api/v1/products | VERIFIED | `ScopedCatalogAccessIntegrationTest.readOnlyScopeCanListProducts` — independently re-run: 5/5 tests pass (0 failures/errors), Testcontainers Postgres, real converter. `docs/api/openapi-snapshot.json` TEST-results at `core-java/build-local/test-results/integrationTest/TEST-uk.jtoye.core.security.ScopedCatalogAccessIntegrationTest.xml` |
| 2 | That same read-only token gets 403 on POST /api/v1/products (create), with a fully valid body | VERIFIED | `readOnlyScopeForbiddenOnCreate` in the same 5/5 pass; test body is the full 5-field `CreateProductRequest` JSON (sku/title/ingredientsText/allergenMask/pricePennies), confirmed by reading the test source — the 403 is the authz gate, not a 400 |
| 3 | An operator-shaped token (`catalog:read catalog:write`) is NOT 403 on POST /api/v1/products | VERIFIED | `operatorScopeNotForbiddenOnCreate` — custom `not403()` matcher, part of the same 5/5 pass |
| 4 | Existing `hasRole('admin')` gates stay enforced — RoleBasedAccessIntegrationTest still green, zero edits | VERIFIED | `git diff d60f668 706235d -- .../RoleBasedAccessIntegrationTest.java` is empty (zero edits confirmed); independently re-run: 6/6 pass |
| 5 | The realm defines catalog:read/catalog:write client scopes, grants BOTH to core-api by default, and ships a read-only machine client + SA user with tenant_id attribute | VERIFIED | jq structural checks all pass: `catalog:write` scope exists; `core-api.defaultClientScopes` contains both `catalog:read`+`catalog:write`; `integration-catalog-ro.defaultClientScopes` contains `catalog:read` and explicitly NOT `catalog:write`; SA user `service-account-integration-catalog-ro` carries `attributes.tenant_id[0]="00000000-0000-0000-0000-000000000001"`; audience mapper (`included.client.audience:"core-api"`) and tenant-id-mapper both present on the machine client |
| 6 | Scope taxonomy, per-tenant client-credentials recipe, and realm re-import migration note are documented; scopes appear in the OpenAPI snapshot | VERIFIED | `docs/security-scopes.md` (149 lines) covers all 6 required sections (taxonomy table, realm config, curl recipe, re-import migration note, KC24 managed-attribute trap, #203 MCP feed-forward); `docs/api/openapi-snapshot.json` contains `catalog:read`/`catalog:write` under a `catalog-scopes` OAUTH2 security scheme; `OpenApiConfig.java` defines the scheme with clientCredentials flow |
| 7 | The full Java gate (test + integrationTest) is green after the converter swap — no @SpringBootTest context regression | VERIFIED (spot-checked, not full-suite re-run per instructions) | Independently re-ran the security-relevant subset: `ScopedCatalogAccessIntegrationTest` 5/5, `RoleBasedAccessIntegrationTest` 6/6, `JwtRolesAndScopesConverterTest` 4/4, and the auto-fixed `LocationHeaderContractTest` 7/7 — all green, all via real Testcontainers Postgres runs (not cached/UP-TO-DATE for the containerized ones). Executor's claim of full-suite `BUILD SUCCESSFUL` (23m18s) not independently re-run per verification scope instructions (CI is the final arbiter for the full suite) |

**Score:** 7/7 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/.../security/JwtRolesAndScopesConverter.java` | Combined converter emitting ROLE_* and SCOPE_* | VERIFIED | Plain final class, `LinkedHashSet` union of `KeycloakRealmRoleConverter` + stock `JwtGrantedAuthoritiesConverter`; matches plan spec exactly |
| `core-java/.../security/ScopedCatalogAccessIntegrationTest.java` | AC-1 proof | VERIFIED | 5 tests, all scope scenarios covered (read-only list/create/delete, operator create, no-scope stale-token) |
| `core-java/.../security/JwtRolesAndScopesConverterTest.java` | Unit proof roles+scopes merge | VERIFIED | 4 tests: roles-only, scopes-only, both-merged, neither-throws — all pass |
| `infra/keycloak/realm-export.template.json` | catalog scopes + default grant + machine client + SA user | VERIFIED | All jq structural assertions pass; `integration-catalog-ro` present with correct shape |
| `docs/security-scopes.md` | Taxonomy, recipe, migration note, #203 feed-forward | VERIFIED | Substantive 149-line doc, all required content present |
| `docs/api/openapi-snapshot.json` | Regenerated snapshot with catalog scopes | VERIFIED | Contains `catalog:read`, `catalog:write` under `catalog-scopes` security scheme |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `SecurityConfig.java` | `JwtRolesAndScopesConverter` | `converter.setJwtGrantedAuthoritiesConverter(new JwtRolesAndScopesConverter())` | WIRED | Confirmed at line 46; `@EnableMethodSecurity` pre-existing (from #83), so `@PreAuthorize` gates are live |
| `ProductController.java` | `SCOPE_catalog:write` | `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` | WIRED | Exactly 9 occurrences on the 9 specified mutating handlers (create, update, delete, uploadImage, addAdditionalImage, removeAdditionalImage, removeImage, bulkImportCsv, bulkImportImages); the 6 READ handlers (list, getById, search, downloadTemplate, generateLabel, analyzeImage) correctly ungated |
| `realm-export.template.json` | `core-api` defaultClientScopes | catalog:read + catalog:write added | WIRED | jq confirms both present |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| ScopedCatalogAccessIntegrationTest passes (real Testcontainers run, not cached) | `./gradlew :core-java:integrationTest --tests '...ScopedCatalogAccessIntegrationTest' --tests '...RoleBasedAccessIntegrationTest'` | `BUILD SUCCESSFUL`; XML: 5/5 and 6/6, 0 failures/errors | PASS |
| Unit converter test passes | `./gradlew :core-java:test --tests '...JwtRolesAndScopesConverterTest'` | XML: 4/4, 0 failures | PASS |
| Auto-fixed regression test passes | `./gradlew :core-java:integrationTest --tests '...LocationHeaderContractTest'` | XML: 7/7, 0 failures | PASS |
| Realm template renders to valid JSON with dummy secrets | `envsubst '$INTEGRATION_CATALOG_RO_SECRET $KEYCLOAK_CLIENT_SECRET $EDGE_API_CLIENT_SECRET $KC_SEED_USER_PASSWORD' < realm-export.template.json \| jq -e '.'` | Valid JSON | PASS |
| Zero unresolved `${UPPER_SNAKE}` env-style placeholders post-render (corrected invariant per executor note) | `grep -oE '\$\{[A-Z_][A-Z0-9_]*\}'` on rendered output | Zero matches (49 total `${...}` remain, all lowercase/mixed-case Keycloak i18n placeholders like `${authAdminUrl}`, `${role_default-roles}`) | PASS — confirms the executor's verifier-note correction is accurate; the plan's blanket `grep '\${'` check is indeed over-broad |
| docs-freshness gate | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 1208).` exit 0 | PASS |
| Schema stays V50 (no new migration) | `ls db/migration \| sort -n on V-number` | Highest migration is `V50__idempotency_keys.sql` (pre-existing, from prior #204 task) — no V51 added | PASS |
| No new dependency | `git diff d60f668 706235d -- core-java/build.gradle.kts frontend/package.json edge-go/go.mod` | Empty diffs on all three | PASS |
| CHANGELOG entry for #206 | `grep 206 docs/CHANGELOG.md` | `### Scoped machine credentials (#206 [AI-4]) — 2026-07-13` | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| #206-AC1 | 260713-2g8-PLAN.md | catalog:read → 200 list, 403 create (fully valid body) | SATISFIED | `ScopedCatalogAccessIntegrationTest` 5/5 (independently re-run) |
| #206-AC2 | 260713-2g8-PLAN.md | Operator/admin flows unchanged, role tests green | SATISFIED | `RoleBasedAccessIntegrationTest` zero edits + 6/6 pass (independently re-run) |
| #206-AC3 | 260713-2g8-PLAN.md | Realm template updated + re-import documented + scopes in API docs | SATISFIED | jq checks + `docs/security-scopes.md` + `openapi-snapshot.json` |
| #206-AC4 | 260713-2g8-PLAN.md | #203 MCP feed-forward documented, orders:* seeded unenforced | SATISFIED | `docs/security-scopes.md` §6; `orders:read`/`orders:write` defined in `clientScopes[]` but NOT granted to any client (jq confirms absence from both core-api and integration-catalog-ro defaultClientScopes) |

No orphaned requirements — this is a quick task (not a phase), all 4 AC IDs are declared in the plan frontmatter and map 1:1 to the GitHub issue #206 acceptance criteria (confirmed via `gh issue view 206`).

### Anti-Patterns Found

None. Scanned all 7 primary modified/created files (converter, SecurityConfig, ProductController, both new test classes, OpenApiConfig, security-scopes.md) for TBD/FIXME/XXX/TODO/HACK/PLACEHOLDER/stub patterns — zero matches (one incidental match of the word "placeholder" in security-scopes.md is a legitimate description of the envsubst `${...}` secret-injection pattern, not a debt marker).

### Human Verification Required

None. All observable truths are proven by automated tests (unit + Testcontainers integration) that were independently re-executed against real Postgres, not just trusted from the SUMMARY narrative. The realm template correctness was verified structurally (jq) and via actual envsubst rendering. Live Keycloak re-import + Playwright verification is correctly deferred (documented in `docs/security-scopes.md` §4) since it requires a running Keycloak with the realm re-imported — this is explicitly out of scope for CI/this task per the plan's own design (Playwright is not in CI).

### Gaps Summary

No gaps. All 7 derived truths (roadmap fallback via Option C, since this is a quick task using PLAN frontmatter must_haves directly) are VERIFIED against actual codebase behavior, not SUMMARY claims. Independently re-ran 4 test classes (22 individual test methods total: 5+6+4+7) against live Testcontainers Postgres and confirmed 0 failures/errors across all of them — this directly falsifies the "trust nothing" starting hypothesis for the core AC-1/AC-2 claims. Realm template structural and rendering correctness independently confirmed via jq + envsubst, not just accepted from the SUMMARY's jq check list. The one verifier-note flagged by the executor (Task 2's overly-broad `grep '\${'`) was independently re-derived and confirmed accurate — the corrected invariant (zero unresolved `${UPPER_SNAKE}` placeholders) holds cleanly.

---

*Verified: 2026-07-13T02:53:28Z*
*Verifier: Claude (gsd-verifier)*

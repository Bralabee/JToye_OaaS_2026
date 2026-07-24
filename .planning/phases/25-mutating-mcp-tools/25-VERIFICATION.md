---
phase: 25-mutating-mcp-tools
verified: 2026-07-24T18:51:34Z
status: passed
score: 11/11 must-haves verified
overrides_applied: 0
---

# Phase 25: Mutating MCP Tools Verification Report

**Phase Goal:** "Write tools on the Phase 20 MCP server riding the uniform Idempotency-Key contract, RLS-proven under the MCP credential."
**Verified:** 2026-07-24T18:51:34Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | MCP server exposes write tools mapped to appropriate write scopes; each rides the uniform Idempotency-Key contract so replay returns the original, not a duplicate | VERIFIED | `mcp-server/src/tools/create-order.ts` / `create-customer.ts` exist, registered in `server.ts::buildServer`; `idempotencyKey` is a REQUIRED Zod field (`.min(1).max(64)`), destructured OUT of the body and forwarded as the `Idempotency-Key` header via `corePost`; core-side gates confirmed live (`@PreAuthorize("hasAuthority('SCOPE_orders:write')")` / `'SCOPE_customers:write'` on the create endpoints). Replay behavior proven by `OrderIdempotencyIntegrationTest` (3/3 green, XML confirmed) + human-approved live E2E probe 4 (`create_customer` replay → identical DTO, customers row count unchanged). |
| 2 | A write attempt targeting another tenant returns empty/403 under the MCP credential — RLS-proven, test included | VERIFIED | `CrossTenantMcpWriteRlsIntegrationTest.java` exists, `@Tag("testcontainers")`, downgrades to the NOSUPERUSER `rls_test_role` via `SET LOCAL ROLE`; `crossTenantCreateOrder_foreignShopId_resolvesNotFound` asserts a tenant-A caller referencing tenant-B's `shopId` throws `ResourceNotFoundException`; `superuserBypassesForceRls_provesTheDowngradeIsLoadBearing` is the documented falsifiability RED (a superuser Testcontainers role would falsely see the foreign shop). Test-result XML confirms 3/3 passed, 0 failures. Live E2E probe 7 independently confirms (RW token → tenant-B shopId → 404 "Shop not found or does not belong to your tenant"). |
| 3 | Tool errors surface as RFC 7807 problem-detail, not raw stack traces, and the flow is proven live against the dev stack | VERIFIED | `mcp-server/src/errors.ts` (`toToolError`) is byte-for-byte unchanged on this branch (`git diff main...feature/phase-25-mutating-mcp-tools -- mcp-server/src/errors.ts` is empty) and is reused verbatim by both write tools on the `!res.ok` branch. `create-order.test.ts`/`create-customer.test.ts` assert 403/409/422 problem+json delegates to `toToolError` (isError, sanitized, no stack/PII). Live E2E probe 6 independently confirms (no-scope token → `isError:true` → `"core 403 Forbidden: Access denied"`, sanitized). |

**Score:** 3/3 roadmap Success Criteria verified.

### PLAN Frontmatter Must-Haves (merged across 25-01..25-04)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 4 | POST /orders from a no-`orders:write` token → 403 (fully-valid body, D-04) | VERIFIED | `ScopedWriteAccessIntegrationTest.noScopeTokenForbiddenOnOrderCreate` — XML: 12 tests, 0 failures (post-remediation, extended to 12 cases covering create+delete+cancel+update). |
| 5 | POST /customers from a no-`customers:write` token → 403 | VERIFIED | `noScopeTokenForbiddenOnCustomerCreate` in same green suite. |
| 6 | `orders:write`/`customers:write` tokens clear the create gates (upgraded post-remediation from "not-403" to exact-status per WR-02) | VERIFIED | `writeScopedTokenReaches404OnOrderCreate` (404, random shopId) / `writeScopedTokenCreates201OnCustomerCreate` (201) — both green. WR-02 fix confirmed: blanket `not403()` matcher removed from the file (grep for `not403` returns nothing); every positive case pins an exact status. |
| 7 | OpenApiConfig documents `orders:write`/`customers:write` as enforced (not "reserved") | VERIFIED | `OpenApiConfig.java:61-62,118-119` reads `"orders:write — create orders (enforced...)"` / `"customers:write — create customers (enforced...)"`; snapshot (`docs/api/openapi-snapshot.json`) mirrors this text. `OpenApiSnapshotTest` green (3 XML result files inspected, all `tests="1" failures="0"`). |
| 8 | `integration-orders-rw` realm client mirrors `integration-catalog-ro`, carries `orders:write`+`customers:write`+`catalog:read`, NOT `catalog:write`, both protocol mappers, placeholder secret | VERIFIED | `jq` assertions against `infra/keycloak/realm-export.template.json` all pass: scopes present/absent as specified, `core-api-audience-mapper` + `tenant-id-mapper` present, `secret == "${INTEGRATION_ORDERS_RW_SECRET}"`. `core-api` default-grant of both write scopes confirmed. `customers:read`/`customers:write` client scopes confirmed defined. File is valid JSON. |
| 9 | `INTEGRATION_ORDERS_RW_SECRET` wired fail-loud across 3 composes + 2 `.env.example` + `verify-env.sh`; `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` on core-java (VSA-02) | VERIFIED | grep confirms the var present in all 6 files with the `:?...must be set` fail-loud form (composes) / `CHANGE_ME` (`.env.example`); `ACCESS_MACHINE_CLIENT_IDS: integration-orders-rw` present on `docker-compose.full-stack.yml`'s core-java service env. |
| 10 | `corePost` SSRF-safe (fixed `CORE_BASE_URL`), verbatim Bearer, `content-type: application/json`; never logs body/token; caller headers cannot override the security-fixed headers (WR-01) | VERIFIED | `core-client.ts:60-87` — fixed base URL, extra `headers` spread FIRST then `authorization`/`accept`/`content-type` applied LAST (WR-01 fix present with inline comment). Dedicated vitest case `"WR-01: an extra-headers entry cannot override the verbatim Bearer or content-type"` exists and — along with the rest of the suite — passes (48/48 confirmed by an independent `npm test` run in this verification). |
| 11 | `docs/metrics.json` reconciled + docs-freshness check-mode exits 0; full suite green (Java 1151 tests / mcp-server 48/48) | VERIFIED | Ran `bash scripts/docs-freshness.sh` independently: `docs-freshness OK: metrics match source (total logical invocations: 1684)`. Ran `cd mcp-server && npm test` independently: `Test Files 8 passed (8)`, `Tests 48 passed (48)`. Did not re-run the 40-min `integrationTest`; instead aggregated the on-disk JUnit XML under `core-java/build-local/test-results/{test,integrationTest}` (mtimes 2026-07-24 19:42, immediately preceding the 19:43 REVIEW-REMEDIATION timestamp): `test` task 759/0/0/0 (tests/skipped/failures/errors) + `integrationTest` task 392/1/0/0 → aggregate 1151 tests, 0 failures, 0 errors — matches the claimed full-suite result exactly. Spot-checked the specific repaired/new suites: `ScopedWriteAccessIntegrationTest` 12/0, `CrossTenantMcpWriteRlsIntegrationTest` 3/0, `OrderIdempotencyIntegrationTest` 3/0, `CustomerIdempotencyIntegrationTest` 1/0, `CustomerControllerIntegrationTest` 7/0, `LocationHeaderContractTest` 7/0, `OpenApiSnapshotTest` 1/0 — all green, all failures=0. |

**Score:** 11/11 must-haves verified (3 roadmap SCs + 8 plan-level must-haves; some plan truths collapsed where evidence overlapped).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/.../order/OrderController.java` | `orders:write` gate on ALL 8 mutations (create + update + delete + 6 transitions, post CR-01) | VERIFIED | All 8 `@PreAuthorize("hasAuthority('SCOPE_orders:write')")` annotations present and confirmed by direct file read. |
| `core-java/.../customer/CustomerController.java` | `customers:write` gate on ALL 3 mutations (create + update + delete, post CR-01) | VERIFIED | All 3 `@PreAuthorize("hasAuthority('SCOPE_customers:write')")` annotations present. |
| `core-java/.../config/OpenApiConfig.java` | scope taxonomy documents both write scopes as enforced | VERIFIED | Confirmed via direct read (lines 61-62, 118-121). |
| `core-java/.../security/ScopedWriteAccessIntegrationTest.java` | converter-through-MockMvc 403/exact-status proof, all mutations | VERIFIED | 12 test methods, exact-status assertions (WR-02 fix applied — `not403()` removed). |
| `core-java/.../security/CrossTenantMcpWriteRlsIntegrationTest.java` | NOSUPERUSER cross-tenant create_order RLS proof | VERIFIED | 3 test methods incl. documented falsifiability case; 3/3 green in test-result XML. |
| `mcp-server/src/core-client.ts` | `corePost` sibling of `coreGet`, WR-01 header-precedence fix | VERIFIED | Present, `coreGet` byte-for-byte unchanged (`git diff` empty on that hunk), WR-01 fix inline. |
| `mcp-server/src/tools/create-order.ts` | write forwarder, raw-Zod schema, mandatory idempotencyKey → header | VERIFIED | Confirmed by direct read. |
| `mcp-server/src/tools/create-customer.ts` | write forwarder, raw-Zod schema, mandatory idempotencyKey → header | VERIFIED | Confirmed by direct read. |
| `mcp-server/src/server.ts` | registers both write tools | VERIFIED | `registerCreateOrder`/`registerCreateCustomer` called in `buildServer`. Live E2E `tools/list` independently returned all 5 tools including the two write tools. |
| `infra/keycloak/realm-export.template.json` | RW client + customers scopes + core-api default-grant | VERIFIED | All `jq` assertions pass; valid JSON; description trimmed to 244 chars (< 255 Keycloak column limit — the Rule 1/3 live-E2E blocker fix confirmed present). |
| `scripts/verify-env.sh` + 3 composes + 2 `.env.example` | `INTEGRATION_ORDERS_RW_SECRET` fail-loud wiring | VERIFIED | grep confirms presence in all 6 files. |
| `docs/metrics.json` | reconciled counts (post-remediation) | VERIFIED | `mcp_test_blocks: 48`, `mcp_test_files: 8`, `java_test_methods: 1143`, `total_logical_invocations: 1684` — matches `docs-freshness.sh` check-mode output (independently re-run, exit 0). |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `create-order.ts` / `create-customer.ts` | `corePost` fixed `CORE_BASE_URL` | `corePost(PATH, bearer, body, {"Idempotency-Key": key})` | WIRED | Confirmed by direct read; fixed path constants, no caller-controlled host/path. |
| `ScopedWriteAccessIntegrationTest` | `OrderController`/`CustomerController` | MockMvc POST/PUT/DELETE + `JwtRolesAndScopesConverter` token | WIRED | Real converter used (not hand-stubbed authorities); 12/12 green against Testcontainers Postgres. |
| `CrossTenantMcpWriteRlsIntegrationTest` | `OrderService.createOrder` under downgraded RLS role | `SET LOCAL ROLE rls_test_role` + tenant-A GUC + tenant-B `shopId` | WIRED | 3/3 green; the falsifiability case proves the downgrade is load-bearing (superuser would falsely pass). |
| `mcp-server/src/server.ts` | write tools | `registerCreateOrder`/`registerCreateCustomer` in `buildServer` | WIRED | Confirmed by direct read AND the live E2E `tools/list` response (5 tools returned). |
| `docker-compose.full-stack.yml` core-java env | `ShopAccessService.isAllowlistedMachineClient` | `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` | WIRED | Confirmed present in compose; live E2E probe 8 confirms 0 rogue `shop_staff` GROUP_ADMIN rows post-`create_order`. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| mcp-server full vitest suite green | `cd mcp-server && npm test` | `Test Files 8 passed (8)`, `Tests 48 passed (48)` | PASS |
| docs-freshness gate green | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 1684)` | PASS |
| Realm template valid JSON, RW client correctly scoped | `jq` assertion battery (6 checks) | All `true` | PASS |
| Java full-suite aggregate (from on-disk XML, not re-run) | XML aggregation across `test` + `integrationTest` result dirs | tests=1151, failures=0, errors=0 (mtimes align with the claimed 19:42 run) | PASS |

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| AI-02 | 25-01, 25-02, 25-03, 25-04 | Mutating MCP tools riding the Idempotency-Key contract, RLS-proven under the MCP credential | SATISFIED | All 4 contributing plans' artifacts independently confirmed in the codebase (not merely claimed in SUMMARYs); `.planning/REQUIREMENTS.md:72,132` marks AI-02 `[x]` Complete, no orphaned requirement IDs found mapped to Phase 25 beyond AI-02. |

No orphaned requirements: `grep -n "Phase 25" .planning/REQUIREMENTS.md` returns exactly the AI-02 rows.

### Anti-Patterns Found

Scanned all 14 files modified across the 4 plans (controllers, MCP tools/tests, security tests, realm template, verify-env.sh) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER|placeholder|coming soon|not yet implemented|not available`.

**None found.** No debt markers in any phase-modified file.

### Code Review Remediation (independently verified, not merely trusted)

`25-REVIEW.md` recorded 1 critical (CR-01) + 2 warnings (WR-01, WR-02) + 4 info findings. All three blocking/warning findings were independently confirmed as genuinely fixed in the current codebase:

- **CR-01 (blocker — read-only credential could still mutate/delete orders/customers):** confirmed fixed — all 8 `OrderController` mutations and all 3 `CustomerController` mutations now carry the matching `@PreAuthorize` scope gate (previously only the 2 `create` endpoints were gated).
- **WR-01 (corePost header-override):** confirmed fixed — `corePost` spreads caller headers first, fixed security headers last; a dedicated vitest case proves a forged `authorization`/`content-type` in the extra-headers map loses.
- **WR-02 (loose `not403()` matcher false-greening on 401/500):** confirmed fixed — the `not403()` custom matcher no longer appears in `ScopedWriteAccessIntegrationTest.java`; every positive case now asserts an exact status (404 or 201).
- **10 pre-existing tests the create-gate broke:** confirmed repaired via `git diff` — `@WithMockUser`/JWT authority additions in `OrderIdempotencyIntegrationTest`, `CustomerIdempotencyIntegrationTest`, `CustomerControllerIntegrationTest`, `LocationHeaderContractTest`; all now green per the on-disk test-result XML.
- **IN-01..04 (info):** recorded as deferred with justification in `25-REVIEW.md` — not phase-blocking, not silently dropped.

### Human Verification Required

None outstanding. The phase's one `checkpoint:human-verify` gate (25-04 Task 2, live E2E on the rebuilt dev stack) was already executed and human-approved during phase execution, with a detailed 8-probe evidence trail recorded in `25-04-SUMMARY.md` (statuses only, no tokens/PII, consistent with the codebase artifacts independently confirmed in this verification — e.g., the RW client scopes, the `ACCESS_MACHINE_CLIENT_IDS` allowlist, the exact 403/404 error text matching `toToolError`'s mapping and `OrderService`'s `ResourceNotFoundException` message). Per this verification's scope (spot-check artifacts, do not re-run the 40-minute suite or the live stack), this evidence is accepted rather than re-executed.

### Gaps Summary

No gaps. All roadmap Success Criteria and plan-level must-haves are independently verified against the actual codebase (not SUMMARY claims alone): the `@PreAuthorize` gates exist and cover the full mutating surface (post CR-01 remediation), the MCP write tools exist with the mandatory Idempotency-Key contract and SSRF/PII-safe forwarding (post WR-01 remediation), the cross-tenant RLS proof exists and is genuinely falsifiable under the NOSUPERUSER role, the realm/secret/config wiring is complete and valid, the docs-freshness and OpenAPI gates are green (independently re-run), and the full test suite's on-disk results corroborate the claimed 1151 Java tests / 48 mcp-server tests all green.

---

_Verified: 2026-07-24T18:51:34Z_
_Verifier: Claude (gsd-verifier)_

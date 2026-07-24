---
phase: 25
slug: mutating-mcp-tools
status: verified
threats_open: 0
asvs_level: 1
created: 2026-07-24
---

# Phase 25 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Mutating MCP tools (`create_order`/`create_customer`) over write-scope `@PreAuthorize` gates + a least-privilege RW Keycloak credential, RLS-proven under the machine credential. Register authored at plan-time across all 4 plans; verified in verify-mitigations mode.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| client → core REST | Untrusted Bearer token; the `scope` claim drives the `@PreAuthorize` authority | JWT (scope/aud/tenant_id) |
| AI agent → MCP server | Untrusted tool args (ids, PII, idempotencyKey) | Order/customer create payloads incl. PII |
| MCP server → core REST | `corePost` forwards the Bearer to a fixed allow-listed host/path (no caller-chosen host) | Bearer + JSON body + Idempotency-Key |
| Keycloak IdP → core | The RW client's token (scopes + aud=core-api + tenant_id) enters core's auth chain | Access token |
| machine credential → shop_staff | The RW SA's first write must not JIT-mutate `shop_staff` (VSA-02) | Grant rows |
| core → Postgres | FORCE RLS is the sole cross-tenant boundary under the write credential | Tenant-scoped rows |
| git repo → CI/dev host | Realm template + composes are committed; secrets must never be literals | `INTEGRATION_ORDERS_RW_SECRET` |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation (evidence) | Status |
|-----------|----------|-----------|-------------|------------------------|--------|
| T-25-01 | Elevation of Privilege | order/customer mutation endpoints | mitigate | `@PreAuthorize('SCOPE_orders:write')` on all 9 order mutations (`OrderController.java` L62/134/185/200/213/226/239/252/265) + `SCOPE_customers:write` on all 3 customer mutations (`CustomerController.java` L71/108/127). **CR-01: full 12-mutation surface gated — stronger than the 2-create plan-time register.** | closed |
| T-25-02 | Spoofing / masked authz | `@Valid`-before-`@PreAuthorize` ordering | mitigate | `ScopedWriteAccessIntegrationTest.java` sends fully-valid bodies (L76/81) so the gate, not `@Valid`, decides; 403 asserts L133/144/187/201/217/233 (D-04 documented L43-48) | closed |
| T-25-03 | Repudiation | OpenAPI scope taxonomy stale | mitigate | `OpenApiConfig.java` L61-62/118-119 mark orders:write/customers:write "enforced"; customers:read added; `openapi-snapshot.json` in sync; OpenApiSnapshotTest green | closed |
| T-25-04 | Information Disclosure | committed RW secret literal | mitigate | `realm-export.template.json` L1039 `${INTEGRATION_ORDERS_RW_SECRET}` placeholder; `.env.example`/`infra/.env.example` = `CHANGE_ME`; `verify-env.sh` required + fail-loud (L48/94-100/127-133) + weak-value deny; 3 composes `${...:?must be set}` | closed |
| T-25-05 | Spoofing | RW client missing audience mapper | mitigate | `realm-export.template.json` RW client carries BOTH `tenant-id-mapper` (L1060) + `core-api-audience-mapper` (L1074, aud=core-api L1079) | closed |
| T-25-06 | Elevation of Privilege | over-privileged agent credential | mitigate | `realm-export.template.json` L1085 `defaultClientScopes` = orders:write + customers:write + catalog:read only; catalog:write pointedly absent | closed |
| T-25-07 | Tampering / privilege accretion | RW SA JIT rogue `shop_staff` GROUP_ADMIN row | mitigate | `docker-compose.full-stack.yml` L195 `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw`; `ShopAccessService.onRequest()` skips upsert+JIT for allowlisted machine client (L498/603-607); live probe 8 = 0 rows | closed |
| T-25-08 | Tampering (SSRF) | `corePost` host from caller input | mitigate | `core-client.ts` L13 fixed `CORE_BASE_URL`, L66 `fetch(${CORE_BASE_URL}${path})`; fixed path constants in `create-order.ts` L30 / `create-customer.ts` L29 | closed |
| T-25-09 | Information Disclosure | DTO PII leak via logs/errors | mitigate | tools log `{tool,status}` only (`create-order.ts` L91 / `create-customer.ts` L82); `errors.ts` sanitizes; tests assert `customerEmail` never logged | closed |
| T-25-10 | Tampering (replay) | duplicate/replayed mutation | mitigate | `idempotencyKey` REQUIRED `z.string().min(1).max(64)` → split to `Idempotency-Key` header; missing/blank/>64 rejected (tool tests) | closed |
| T-25-11 | Information Disclosure / cross-tenant write | tenant-A token targets tenant-B resource | mitigate | `CrossTenantMcpWriteRlsIntegrationTest.java` — FORCE RLS under NOSUPERUSER NOBYPASSRLS `rls_test_role` (L120); cross-tenant blocked; superuser-bypass falsifiability pair L189-201 | closed |
| T-25-12 | Elevation / masked authz | leaky 401/403 problem+json | mitigate | `errors.ts` `GENERIC_BY_STATUS` 401/403/404/409/422 (L16-22) + 500 (`>=500` L44); tool/errors tests exercise the set | closed |
| T-25-13 | Elevation of Privilege | stale/pre-re-import token retains write | mitigate | Live E2E probe 6 (`25-04-SUMMARY.md` L95): no-scope token → sanitized 403. Human-approved | closed |
| T-25-14 | Tampering / privilege accretion | RW SA accumulates JIT GROUP_ADMIN row | mitigate | Live E2E probe 8 (`25-04-SUMMARY.md` L97): 0 GROUP_ADMIN / 0 shop_staff rows (counted under bypassrls). Human-approved | closed |
| T-25-15 | Repudiation | docs/OpenAPI drift from shipped surface | mitigate | `docs-freshness.sh` exit 0 (total 1684); `security-scopes.md` + `metrics.json` + `openapi-snapshot.json` in sync | closed |
| WR-01 | Tampering (header override) | `corePost` extra-headers override Bearer | mitigate | `core-client.ts` L71-76 spreads caller headers FIRST, applies authorization/accept/content-type LAST; `core-client.test.ts` L125-151 asserts forged header loses | closed |
| WR-02 | Test fidelity | loose `not403()` masks auth regressions | mitigate | `ScopedWriteAccessIntegrationTest.java` — `not403()` removed; exact-status asserts `isNotFound()`/`isCreated()` | closed |
| T-25-SC | Tampering (package legitimacy) | supply-chain via package installs | accept | Verified zero dependency-manifest changes vs `main` (package.json/lock, build.gradle.kts, go.mod all unchanged) — no packages installed; gate N/A | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-25-01 | T-25-SC | Phase 25 installs no new packages — realm/config changes + MCP write tools built on existing dependencies only; verified zero manifest diffs (`mcp-server/package.json`+lock, `core-java/build.gradle.kts`, `edge-go/go.mod`) vs `main`. Package Legitimacy Gate N/A this phase. | gsd-security-auditor (verified) | 2026-07-24 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-07-24 | 18 | 18 | 0 | gsd-security-auditor (opus, ASVS L1, verify-mitigations mode) |

**Note (informational, not a gap):** Live-E2E probes 6/8 (T-25-13/14 evidence) were captured before the CR-01 remediation, so they exercised the two `create` gates live. The 10 additional CR-01-gated mutations (order PUT/DELETE + 6 transitions; customer PUT/DELETE) are covered by `ScopedWriteAccessIntegrationTest` against the real controllers on Testcontainers Postgres — in the green full suite (1151 Java tests, 0 failures). No live re-run required; the plan-time register only ever required the creates verified live.

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-07-24

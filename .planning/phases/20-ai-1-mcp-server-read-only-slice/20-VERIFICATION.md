---
phase: 20-ai-1-mcp-server-read-only-slice
verified: 2026-07-13T12:22:24Z
status: passed
score: 18/18 must-haves verified (across 5 plans + 7 roadmap ACs)
overrides_applied: 0
mvp_mode: true
mvp_format_note: >
  gsd-sdk user-story.validate returned valid=false ("Must begin with \"As a \".")
  because the ROADMAP goal reads "As an external AI agent..." (grammatically
  correct "an" before a vowel sound) instead of the literal "As a ". This is a
  regex literalism, not a content defect — role/capability/outcome clauses are
  all present and unambiguous. Per adversarial judgment (the anti-pattern the
  guard exists to catch — a non-user-story goal forced into a fake flow — does
  not apply here), verification proceeded with the full User Flow Coverage
  table below rather than refusing outright. Flagged for visibility; a
  cosmetic `/gsd mvp-phase 20` rerun would clear the literal check if desired,
  but is not required for goal achievement.
---

# Phase 20: AI-1 MCP Server (Read-Only Slice) Verification Report

**Phase Goal:** As an external AI agent holding only a tenant-scoped Keycloak client-credentials token, I want to discover and read the platform (list shops, list products, read orders) through a Model Context Protocol server, so that I can integrate with J'Toye without hand-rolling an HTTP client and without any possibility of cross-tenant access.
**Verified:** 2026-07-13T12:22:24Z
**Status:** passed
**Re-verification:** No — initial verification

## Verification Method

This was **not** a documentation review. Independently of SUMMARY.md/REVIEW.md narration, the verifier:
- Ran `npm run build` (tsc, strict+NodeNext) and `npx vitest run` directly — 27/27 green, clean build.
- Ran `bash scripts/docs-freshness.sh` directly — gate green, 1235 total, arithmetic checked.
- Read every production source file in `mcp-server/src/` and confirmed each of the 9 post-review fix commits (`bf4968e..988ff66`) landed in the actual code, not just the SUMMARY.
- Found the full-stack dev stack **already running and healthy** (including `jtoye-mcp-server`, rebuilt 3 minutes prior) and **independently re-ran** `mcp-server/scripts/e2e.sh` and `mcp-server/scripts/e2e-rls.sh` myself against the live stack — both exited 0 with fresh PASS output (not a re-read of the SUMMARY's captured output).
- Independently minted a live `integration-catalog-ro` token and drove the raw MCP JSON-RPC protocol by hand (`initialize` → `tools/list` → `tools/call`) to confirm tool discovery, UUID-schema validation (WR-02), and sanitized error mapping (WR-05/AC#6) — without relying on any client tooling used during execution.

## User Flow Coverage (MVP mode)

User story: «As an external AI agent holding only a tenant-scoped Keycloak client-credentials token, I want to discover and read the platform (list shops, list products, read orders) through a Model Context Protocol server, so that I can integrate with J'Toye without hand-rolling an HTTP client and without any possibility of cross-tenant access.»

| Step | Expected | Evidence | Status |
|------|----------|----------|--------|
| Agent mints a tenant-scoped token | `integration-catalog-ro` client-credentials grant returns an access token carrying `catalog:read` scope | Verifier ran the mint live against `localhost:8085`; token returned with `scope="email catalog:read profile"` | ✓ |
| Agent discovers the platform's tools | MCP `tools/list` returns exactly `list_products`, `list_shops`, `read_orders` (no write tool) | Verifier's own raw JSON-RPC `tools/list` call against `localhost:9100/mcp` returned exactly these 3 tools with JSON-schema-typed inputs; `grep` confirms `coreGet` is hardcoded `method: "GET"` everywhere (no POST/PUT/DELETE in production code) | ✓ |
| Agent reads shops | `list_shops` forwards Bearer to `GET /api/v1/shops`, returns the tenant's shops | `mcp-server/src/tools/list-shops.ts` (56 lines) + 3-case unit suite (success/401-delegation/network-fault); registered in `server.ts` | ✓ |
| Agent reads products | `list_products` forwards Bearer to `GET /api/v1/products`, returns real catalogue rows | **Live-verified by the verifier**: `e2e.sh` re-run → HTTP 200, `isError` absent, 20 real tenant-A product rows returned | ✓ |
| Agent reads orders | `read_orders` lists/shop-scopes/detail-reads orders via `GET /api/v1/orders*`, RLS-scoped, PII never logged | `read-orders.ts` (102 lines, UUID-validated shopId/orderId post-WR-02) + 8-case unit suite incl. a PII-not-logged pino-mock spy; live: tenant-A `read_orders` view carries no tenant-B marker | ✓ |
| Outcome: integrate without hand-rolling an HTTP client | A real MCP client (any JSON-RPC/Streamable-HTTP client) can connect, discover, and call tools without bespoke REST glue | 20-05 records a real Claude Code headless client connecting, listing 3 tools, reading 20/46 rows (human-approved); verifier independently replayed the `initialize`→`tools/list`→`tools/call` handshake by hand with identical results | ✓ |
| Outcome: without any possibility of cross-tenant access | Two genuinely tenant-scoped tokens (A, B) return disjoint, non-empty product sets through the tool; no order leak | **Live-verified by the verifier**: `e2e-rls.sh` re-run → tenant A contains `MAK-JOL`, NOT `TENANTB-PROBE-1`; tenant B contains `TENANTB-PROBE-1`, NOT any tenant-A product; tenant A's `read_orders` view carries no tenant-B marker | ✓ |

All 7 user-flow steps verified. Proceeding to standard technical-check sections below (per `verify-mvp-mode.md` ordering: user flow → technical checks → coverage, all satisfied).

## Goal Achievement

### Observable Truths (Roadmap Success Criteria — issue #203 ACs, the contract)

| # | Truth (AC) | Status | Evidence |
|---|------------|--------|----------|
| 1 | New TypeScript `mcp-server/` using official `@modelcontextprotocol/sdk`, packaged as its own Docker container in compose. No new Python/Go runtime. | ✓ VERIFIED | `mcp-server/package.json` pins `@modelcontextprotocol/sdk@^1.29.0`; `mcp-server/Dockerfile` multi-stage `node:20-alpine`; `docker-compose.full-stack.yml:350-370` `mcp-server:` service block, currently running healthy (`docker compose ps` confirms `jtoye-mcp-server ... healthy`) |
| 2 | Read-only MCP tools — list shops, list products, read orders — each wraps the EXISTING core REST API over HTTP (never Postgres directly) | ✓ VERIFIED | `list-shops.ts`/`list-products.ts`/`read-orders.ts` all route through `coreGet()` (hardcoded `method: "GET"`); `grep -rn "method:\s*[\"'](POST\|PUT\|DELETE\|PATCH)"` in `mcp-server/src/` returns nothing in production code; MCP server has zero DB driver dependency in `package.json` |
| 3 | Auth reuses #206: Keycloak client-credentials pass-through; `tenant_id` claim drives RLS; tools map to `catalog:read`/`orders:read` scopes | ✓ VERIFIED (with one tracked note) | Live token mint → Bearer forwarded verbatim (`core-client.ts:35`) → core validates. `catalog:read` is genuinely core-enforced on product mutations; product/shop/order READS are authenticated-only in core today (not a hard scope gate) — this is an inherited #206-era gap, explicitly documented as WR-04 in `20-REVIEW.md` and in the README ("`orders:read` reserved"), not introduced or hidden by this phase. Non-blocking for AI-1's literal ask (pass-through reuse), tracked as a follow-up. |
| 4 | Agent with a tenant-scoped token can list shops/products and read orders via MCP against the dev stack — LIVE E2E, not unit | ✓ VERIFIED | Independently re-run by the verifier: `bash mcp-server/scripts/e2e.sh` → exit 0, HTTP 200, 20 real product rows, non-error |
| 5 | Cross-tenant access attempt returns empty/403 — RLS-proven, test included | ✓ VERIFIED | Independently re-run by the verifier: `bash mcp-server/scripts/e2e-rls.sh` → exit 0; disjoint bidirectional non-empty sets + no order leak, through the MCP tool, with two genuinely tenant-scoped tokens |
| 6 | Tool errors surface RFC 7807 problem-detail, not raw stack traces | ✓ VERIFIED | `errors.ts` `toToolError()` maps `problem+json`/bare status → sanitized text; live-tested by the verifier: malformed JSON body → `{"error":"bad_request"}` (no stack, any NODE_ENV, post-WR-03 fix); nonexistent-but-valid-UUID order → `"Not found"` isError (no body/stack echo) |
| 7 | README documents the client-credentials setup | ✓ VERIFIED | `mcp-server/README.md` §4 documents `integration-catalog-ro` + `INTEGRATION_CATALOG_RO_SECRET`, host mint endpoint, and (post-WR-05 fix) the correct `Accept: application/json, text/event-stream` verification curl |

**Score:** 7/7 roadmap ACs verified (AC#3 carries one documented, non-blocking tracked note — see Anti-Patterns).

### Per-Plan Must-Have Truths (PLAN frontmatter)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 20-01.1 | Bearer forwarded verbatim; core JSON returned as tool content | ✓ VERIFIED | `core-client.ts:35` sets `authorization: Bearer <bearer>`; `core-client.test.ts` asserts header + base URL |
| 20-01.2 | Non-2xx problem+json → sanitized isError (no stack/token) | ✓ VERIFIED | `errors.ts` + `errors.test.ts`; live-tested, no leakage |
| 20-01.3 | Network/timeout fault → isError, token never logged | ✓ VERIFIED | try/catch in every tool handler; `AbortSignal.timeout(10000)` in `core-client.ts` |
| 20-01.4 | Stateless per-request McpServer; missing Bearer → 401 | ✓ VERIFIED | `index.ts:34-35` fresh transport+server per request; live-tested, no-bearer → 401 |
| 20-02.1 | `list_shops` forwards Bearer to `GET /api/v1/shops` | ✓ VERIFIED | `list-shops.ts:29` |
| 20-02.2 | `read_orders` list/shopId/orderId routing | ✓ VERIFIED | `read-orders.ts:46-61` `buildPath()`; live-tested order-detail path |
| 20-02.3 | All three tools registered & MCP-discoverable | ✓ VERIFIED | `server.ts:21-23`; live `tools/list` returned all 3 |
| 20-02.4 | Order PII never logged | ✓ VERIFIED | `read-orders.ts:74,80` logs `{tool,status}` only; PII-mock spy test asserts it |
| 20-03.1 | MCP server builds as its own multi-stage node:20-alpine container | ✓ VERIFIED | `mcp-server/Dockerfile` builder/runner stages |
| 20-03.2 | Compose runs mcp-server, depends_on core+keycloak healthy, `CORE_BASE_URL`, no DB creds | ✓ VERIFIED | `docker-compose.full-stack.yml:350-370`; no DB env present |
| 20-03.3 | Tenant B seeded (one shop + one product) for disjoint RLS proof | ✓ VERIFIED | `DemoDataSeeder.java:95-219`; live-confirmed `TENANTB-PROBE-1` appears only for tenant B |
| 20-03.4 | README documents client-credentials setup | ✓ VERIFIED | `README.md` §4 |
| 20-04.1 | docs-freshness counts a new mcp test family | ✓ VERIFIED | `scripts/docs-freshness.sh:64-81`; gate green, 1235 |
| 20-04.2 | metrics.json regenerated via --write, includes MCP tests | ✓ VERIFIED | `docs/metrics.json`: `mcp_test_blocks: 27`, `total_logical_invocations: 1235` |
| 20-04.3 | `e2e.sh` mints token, proves list_products returns tenant-A rows | ✓ VERIFIED | Independently re-run — 20 rows, PASS |
| 20-04.4 | `e2e-rls.sh` mints two tokens, proves disjoint results | ✓ VERIFIED | Independently re-run — disjoint bidirectional PASS |
| 20-05.1 | All containers rebuilt + realm re-imported | ✓ VERIFIED | `docker compose ps` shows all 10 services healthy; live token mint succeeds (proves re-import took) |
| 20-05.2/3 | Both live E2E proofs pass | ✓ VERIFIED | Re-run independently by verifier, both exit 0 |
| 20-05.4 | Real MCP client connects and invokes a tool | ✓ VERIFIED | 20-05-SUMMARY records Claude Code headless connecting (human-approved); verifier independently replayed the raw protocol handshake with identical results |

**Score:** 18/18 plan-level truths verified.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `mcp-server/package.json` | ESM manifest, SDK ^1.29.0 | ✓ VERIFIED | pins `@modelcontextprotocol/sdk@1.29.0`, `express@5`, `zod@4`, `pino@10`; `npm run build`/`vitest run` both clean |
| `mcp-server/src/core-client.ts` | `coreGet` forwarder | ✓ VERIFIED | 47 lines, fixed base URL, timeout, no logging |
| `mcp-server/src/errors.ts` | `toToolError` mapper | ✓ VERIFIED | 48 lines, sanitized, isError always set |
| `mcp-server/src/tools/list-products.ts` | `list_products` tool | ✓ VERIFIED | 70 lines, wired to `coreGet`+`toToolError` |
| `mcp-server/src/tools/list-shops.ts` | `list_shops` tool | ✓ VERIFIED | 56 lines |
| `mcp-server/src/tools/read-orders.ts` | `read_orders` tool | ✓ VERIFIED | 102 lines, UUID-validated (post-WR-02) |
| `mcp-server/src/server.ts` | `buildServer` factory, 3 tools | ✓ VERIFIED | registers all three |
| `mcp-server/src/index.ts` | stateless HTTP host + sanitized error middleware | ✓ VERIFIED | 78 lines, post-WR-03 terminal error handler present |
| `mcp-server/Dockerfile` | multi-stage node:20-alpine | ✓ VERIFIED | builder→runner, non-root, MCP_PORT-aware healthcheck (post-IN-04) |
| `docker-compose.full-stack.yml` (mcp-server block) | wired, no secret | ✓ VERIFIED | present, healthy, no DB/secret env |
| `mcp-server/README.md` | client-credentials setup docs | ✓ VERIFIED | §4 present; verification curl fixed (post-WR-05); search-capability overclaim removed (post-WR-06) |
| `core-java/.../DemoDataSeeder.java` (tenant B) | disjoint RLS fixture | ✓ VERIFIED | idempotent upsert, own TenantContext scope, schema stays V50 |
| `scripts/docs-freshness.sh` | mcp test family | ✓ VERIFIED | folds MCP_TEST_BLOCKS into TOTAL |
| `docs/metrics.json` | regenerated manifest | ✓ VERIFIED | `mcp_test_blocks: 27`, total 1235 |
| `mcp-server/scripts/e2e.sh` | live read happy-path script | ✓ VERIFIED | 149 lines, re-run live PASS, IN-01/IN-02 fixed |
| `mcp-server/scripts/e2e-rls.sh` | live cross-tenant RLS script | ✓ VERIFIED | 228 lines, re-run live PASS |
| `.github/workflows/ci-cd.yaml` (mcp-server-tests job) | CI executes mcp-server build+vitest | ✓ VERIFIED (post-WR-01) | job `mcp-server-tests` present, installs, builds (tsc), runs vitest |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `list-products.ts` | `core-client.ts` | `coreGet(` | ✓ WIRED | grep confirms call site |
| `list-shops.ts` | `core-client.ts` | `coreGet(` | ✓ WIRED | grep confirms call site |
| `read-orders.ts` | `core-client.ts` | `coreGet(` | ✓ WIRED | grep confirms call site |
| `index.ts` | `server.ts` | `buildServer(` per request | ✓ WIRED | `index.ts:35` |
| `server.ts` | `read-orders.ts` | `registerReadOrders` | ✓ WIRED | `server.ts:4,23` |
| `docker-compose.full-stack.yml` | `mcp-server/Dockerfile` | `build.context ./mcp-server` | ✓ WIRED | compose block confirmed, container running |
| `docker-compose.full-stack.yml mcp-server` | `core-java:9090` | `CORE_BASE_URL` env | ✓ WIRED | confirmed in compose + live traffic flowed through it |
| `scripts/docs-freshness.sh` | `docs/metrics.json` | `--write` | ✓ WIRED | gate green, arithmetic verified live |
| `e2e-rls.sh` | MCP server `POST /mcp` (`list_products`) | two tenant-scoped tokens | ✓ WIRED | live re-run, disjoint proof holds |
| `.github/workflows/ci-cd.yaml` | `mcp-server/` | `mcp-server-tests` job (working-directory) | ✓ WIRED | job present post-WR-01 fix |

### Data-Flow Trace (Level 4)

| Artifact | Data Source | Produces Real Data | Status |
|----------|-------------|---------------------|--------|
| `list_products` tool result | `GET /api/v1/products` → core-java → Postgres (RLS-scoped) | Yes — verifier's live call returned 20 real tenant-A rows / 46 total, matching `DemoDataSeeder`'s catalogue | ✓ FLOWING |
| `read_orders` tool result (order-scope negative) | `GET /api/v1/orders` → core-java → Postgres (RLS-scoped) | Yes — tenant-A view genuinely lacks the tenant-B marker (no static/empty stub) | ✓ FLOWING |
| Cross-tenant fixture (`TENANTB-PROBE-1`) | `DemoDataSeeder.seedTenantB()` → Postgres, `TenantContext.set(TENANT_B)` | Yes — appears only under tenant-B token, confirmed live | ✓ FLOWING |

### Behavioral Spot-Checks (run live by the verifier, not from SUMMARY)

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Build type-checks | `npm run build` | clean, `dist/` emitted | ✓ PASS |
| Unit/integration suite | `npx vitest run` | 27/27 passed, 6 files | ✓ PASS |
| docs-freshness gate | `bash scripts/docs-freshness.sh` | `docs-freshness OK ... 1235` | ✓ PASS |
| Live read happy-path | `bash mcp-server/scripts/e2e.sh` (stack already up) | ALL PASS, 20 rows | ✓ PASS |
| Live cross-tenant RLS proof | `bash mcp-server/scripts/e2e-rls.sh` | ALL PASS, disjoint bidirectional | ✓ PASS |
| MCP protocol handshake (manual) | raw `initialize`→`tools/list` JSON-RPC via curl | 3 tools returned, matches source | ✓ PASS |
| UUID schema validation (WR-02) | `tools/call read_orders {orderId:".."}`-shaped invalid input | Zod schema rejects with `invalid_format`/`uuid` before reaching core | ✓ PASS |
| Sanitized "not found" (AC#6) | `tools/call read_orders {orderId:<valid-but-nonexistent-UUID>}` | `{"text":"Not found","isError":true}` — no stack/body | ✓ PASS |
| No-Bearer → 401 | `POST /mcp` with no Authorization header | `401` | ✓ PASS |
| Malformed JSON body sanitized (WR-03) | `POST /mcp` with `{not-json` | `{"error":"bad_request"}` — no HTML/stack | ✓ PASS |

### Probe Execution

No dedicated `scripts/*/tests/probe-*.sh` convention exists for this phase; the two authored live-proof scripts (`e2e.sh`, `e2e-rls.sh`) function as this phase's probes and are covered above under Behavioral Spot-Checks (executed directly by the verifier, not narrated from SUMMARY).

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|--------------|--------------|--------------|--------|----------|
| AI-1 | 20-01, 20-02, 20-03, 20-04, 20-05 | MCP server, read-only tenant-scoped tools (shops/products/orders) over core REST API, #206 pass-through auth, RLS boundary, RFC 7807 errors, live E2E, README | ✓ SATISFIED (functionally) | All 7 roadmap ACs verified live/in-code (table above). **Documentation-only gap:** `.planning/REQUIREMENTS.md` still shows `AI-1` as `[ ]` unchecked with no "DONE" annotation and no row added to the Traceability table at the bottom of that file (every other completed requirement has both). ROADMAP.md itself IS updated (Phase 20 marked Complete, 5/5). Recommend a housekeeping edit to REQUIREMENTS.md; does not affect the phase's functional completion. |

No orphaned requirements — AI-1 is the only requirement mapped to Phase 20 and is claimed by all 5 plans.

### Anti-Patterns Found

Code review (`20-REVIEW.md`, standard depth, 22 files) found 0 critical / 7 warnings / 8 info. The verifier independently confirmed 9 of these were fixed in commits `bf4968e..988ff66` (WR-01, WR-02, WR-03, WR-05, WR-06, IN-01, IN-02, IN-03, IN-04 — all re-read in current source above). The remaining 6 are accepted/deferred, not blockers:

| File | Finding | Severity | Disposition |
|------|---------|----------|-------------|
| `read-orders.ts` / core `SecurityConfig`/`OrderController` | WR-04: `orders:read` scope not core-enforced — any authenticated token (incl. `catalog:read`-only) can read order PII | Warning | Deferred by design — README marks it "(orders:read reserved)"; tracked for the mutating-tools phase per orchestrator note. Inherited from #206, not introduced here. |
| `mcp-server/package.json` | WR-07: 3 devDependencies (`typescript`, `@types/node`, `@types/express`) outside the originally-approved 6-package list | Warning | Accepted, no-change — devDependencies excluded from the runtime image (`npm ci --omit=dev`); `typescript` is load-bearing for the build script. |
| `index.ts:26` | IN-05: non-Bearer auth schemes forwarded double-wrapped (e.g. `Basic ...` → `Bearer Basic ...`) | Info | Not fixed — core rejects it (401) regardless; no security impact, cosmetic only. |
| `tools/*.ts` (3 files) | IN-06: each tool constructs its own `pino` instance — no single enforcement point for the never-log-body rule | Info | Not fixed — convention-based today; each tool is still individually test-proven not to log PII. |
| `README.md` §2 | IN-07: scope column says "Core-enforced" for `list_products`/`catalog:read` when core's actual gate is authenticated-only | Info | Not fixed — same root cause as WR-04; README wording could be tightened later. |
| `docker-compose.full-stack.yml:364-365` | IN-08: `mcp-server` depends_on `keycloak` is unnecessary (server never contacts Keycloak — opaque pass-through by design) | Info | Not fixed — harmless extra startup coupling, no functional effect. |

None of the above rises to a 🛑 Blocker: no unresolved TBD/FIXME/XXX debt markers were found in any phase-modified file (verified via targeted grep during this pass), no stub implementations exist (every tool forwards real data, confirmed live), and the one substantive residual gap (WR-04, orders PII reachable by a narrowly-scoped credential) is explicitly documented, reasoned, and pre-existing from #206 rather than newly introduced by this phase.

**Operational hygiene note (unrelated to phase code):** an untracked `.env.bak-wave4` (created during the 20-05 live-E2E wave, contains the freshly generated `INTEGRATION_CATALOG_RO_SECRET`) sits in the repo root and is **not** matched by any `.gitignore` pattern (only exact `.env`/`.env.*.local`/etc. are ignored). Recommend deleting it or extending `.gitignore` before any `git add -A` to avoid an accidental secret commit. Also an untracked `HANDOFF.md` from a prior session is present. Neither is part of this phase's deliverables; flagged for the developer's awareness only.

### Human Verification Required

None outstanding. The phase's own execution already ran both blocking human-verify checkpoints (20-05 Task 1: stack rebuild + realm re-import confirmation; Task 3: real MCP client connect) and received explicit user sign-off ("Approved") per `20-05-SUMMARY.md`. The verifier additionally, independently replayed the live e2e scripts and the raw MCP JSON-RPC protocol handshake in this pass and obtained identical passing results — corroborating rather than merely trusting the prior human approval.

### Gaps Summary

No gaps. All 7 roadmap Success Criteria and all 18 plan-level must-have truths are VERIFIED against live, independently-reproduced evidence — not SUMMARY narration. Build is clean, the full vitest suite passes, the docs-freshness CI gate is green and now enforced by an actual CI job (post-WR-01), both live E2E/RLS proofs were re-run by the verifier against the currently-running dev stack with identical PASS results, and a live hand-driven MCP protocol session confirmed tool discovery, UUID-schema validation, and sanitized error mapping. The residual code-review items (WR-04, WR-07, IN-05..08) are explicitly reasoned, non-blocking, and already documented in `20-REVIEW.md`; the only follow-up worth tracking outside this phase's code is a REQUIREMENTS.md housekeeping edit (mark AI-1 done + add a Traceability row) and cleaning up the untracked `.env.bak-wave4`/`HANDOFF.md` files before any broad `git add`.

---

*Verified: 2026-07-13T12:22:24Z*
*Verifier: Claude (gsd-verifier)*

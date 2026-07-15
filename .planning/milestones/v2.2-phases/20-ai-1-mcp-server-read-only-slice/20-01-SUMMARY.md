---
phase: 20-ai-1-mcp-server-read-only-slice
plan: 01
subsystem: api
tags: [mcp, model-context-protocol, typescript, esm, express, zod, pino, vitest, rls, oauth2, bearer-passthrough, rfc7807]

# Dependency graph
requires:
  - phase: "#206 scoped machine credentials"
    provides: "integration-catalog-ro client-credentials client + INTEGRATION_CATALOG_RO_SECRET + core-api audience + tenant_id claim (the reference Bearer this MCP server forwards)"
  - phase: "#88 audience gate / #87 issuer-JWKS decouple"
    provides: "core-java as the sole token validator (audience + issuer/expiry); MCP forwards opaquely"
provides:
  - "mcp-server/ standalone TypeScript ESM workspace (Node 20+, @modelcontextprotocol/sdk ^1.29.0)"
  - "coreGet(path, bearer) — thin Bearer-forwarding HTTP client to http://core-java:9090 with AbortSignal timeout (src/core-client.ts)"
  - "toToolError(res) — RFC 7807 problem+json + bare-status -> sanitized CallToolResult isError mapper (src/errors.ts)"
  - "buildServer(bearer) — per-request McpServer factory registering tools via registerTool with a RAW Zod shape inputSchema (src/server.ts)"
  - "list_products tool wired end-to-end through a stateless Streamable HTTP transport (src/tools/list-products.ts, src/index.ts)"
  - "Proven pass-through + auth-delegation + error-sanitization skeleton for 20-02 (widen tools) and 20-04/05 (live E2E)"
affects: [20-02-widen-tools, 20-03-demo-seed, 20-04-e2e-rls, 20-05-live-e2e, mcp-server, docs-freshness]

# Tech tracking
tech-stack:
  added: ["@modelcontextprotocol/sdk@1.29.0", "express@5.2.1", "zod@4.4.3", "pino@10.3.1", "vitest@4.1.10", "tsx@4.23.1", "typescript@5.9.3", "@types/express@5.0.6", "@types/node@20.19.43"]
  patterns:
    - "Bearer pass-through: MCP tier makes ZERO auth/tenancy decisions; forwards an opaque token, core+RLS isolate"
    - "Stateless Streamable HTTP: fresh transport + buildServer(bearer) per request (sessionIdGenerator: undefined) -> one container serves all tenants"
    - "RAW Zod shape inputSchema (NOT z.object) — the stable SDK v1.29.0 registerTool contract"
    - "RFC 7807 -> sanitized isError mapping; log tool+status ONLY, never Bearer/body (PII)"

key-files:
  created:
    - "mcp-server/package.json"
    - "mcp-server/tsconfig.json"
    - "mcp-server/vitest.config.ts"
    - "mcp-server/.gitignore"
    - "mcp-server/src/core-client.ts"
    - "mcp-server/src/errors.ts"
    - "mcp-server/src/tools/list-products.ts"
    - "mcp-server/src/server.ts"
    - "mcp-server/src/index.ts"
    - "mcp-server/src/{core-client,errors,tools/list-products,index}.test.ts"
  modified: []

key-decisions:
  - "Pinned the STABLE single package @modelcontextprotocol/sdk@^1.29.0 (subpath imports + raw Zod shape), NOT the v2.0-alpha split package (@modelcontextprotocol/server + z.object) that Context7 surfaces"
  - "tsconfig excludes src/**/*.test.ts from the build so dist/ carries production code only; vitest runs tests independently"
  - "index.test.ts uses native app.listen(0)+global fetch instead of supertest — supertest was NOT among the six packages cleared at the Task 1 legitimacy gate, so no unverified package was installed"
  - "app.listen(9100) is guarded by an isMain check so importing index.ts in tests does not bind a port"
  - "pino logs { tool, status } ONLY (T-20-01) — verified: no Bearer/body ever reaches a log statement"

patterns-established:
  - "Bearer pass-through with RLS as the sole isolation boundary"
  - "Stateless per-request McpServer factory for multi-tenant single-container serving"
  - "RFC 7807 -> sanitized MCP isError (no stack/body/token leakage)"

requirements-completed: [AI-1]

# Metrics
duration: 20min
completed: 2026-07-13
---

# Phase 20 Plan 01: AI-1 MCP Server (Read-Only Slice) Summary

**Standalone TypeScript ESM `mcp-server/` whose `list_products` tool is callable end-to-end through a stateless Streamable HTTP transport, forwarding the caller's Bearer verbatim to core-java and mapping RFC 7807 errors to sanitized MCP tool errors — the walking slice that proves the pass-through + auth-delegation + RLS thesis against a mocked core.**

## Performance

- **Duration:** ~20 min
- **Completed:** 2026-07-13
- **Tasks:** 3 (Task 1 checkpoint pre-resolved; Tasks 2–3 implemented via TDD)
- **Files created:** 14 (5 production TS + 4 test TS + 5 config/manifest)

## Accomplishments
- Scaffolded a greenfield ESM TypeScript workspace (`mcp-server/`) pinned to the **stable** `@modelcontextprotocol/sdk@1.29.0` — verified it compiles against the v1.29.0 API (subpath imports + raw Zod shape), not the v2-alpha.
- `coreGet(path, bearer)` forwards the Bearer verbatim to the internal `http://core-java:9090` base with a 10s `AbortSignal.timeout` (trips before core's 30s query timeout); never logs the token or the response body.
- `toToolError(res)` maps core's `application/problem+json` (`{title,detail,status}`) to `core <status> <title>: <detail>` and bare 401/403/404/409/422 + 5xx to generic sanitized text — always `isError:true`, never leaking a stack, upstream body, or the token.
- `list_products` tool is registered on a per-request `McpServer` and reachable through the stateless Streamable HTTP host: `POST /mcp` (missing Bearer → 401), `GET /health` → 200 without calling core.
- Full vitest suite: **12/12 green**; `npm run build` type-checks (strict + NodeNext) and emits `dist/`.

## Task Commits

1. **Task 2 (scaffold):** `71552cc` (chore) — ESM workspace + pinned deps + lockfile (0 vulnerabilities, no postinstall scripts)
2. **Task 2 (RED):** `ee0c34e` (test) — failing unit tests for `coreGet` + `toToolError`
3. **Task 2 (GREEN):** `eeae748` (feat) — `core-client.ts` + `errors.ts` (6 tests pass)
4. **Task 3 (RED):** `2d4fca5` (test) — failing tests for `list_products` tool + stateless HTTP host
5. **Task 3 (GREEN):** `5d9752b` (feat) — `list-products.ts` + `server.ts` + `index.ts` (full suite 12/12)

_TDD tasks produced test → feat commit pairs; the scaffold was a preceding chore commit since tests cannot run without the workspace._

## Files Created/Modified
- `mcp-server/package.json` — ESM (`"type":"module"`) manifest, scripts build/dev/test/start, pins SDK ^1.29.0 + express/zod/pino, dev typescript/vitest/tsx.
- `mcp-server/tsconfig.json` — target ES2022, module/moduleResolution NodeNext, strict, emit to `dist/`, excludes test files.
- `mcp-server/vitest.config.ts` — node env, `include: src/**/*.test.ts` (matches the docs-freshness path family).
- `mcp-server/.gitignore` — ignores `dist/` + `node_modules/` (root `.gitignore` anchors `/dist/` to repo root, so `mcp-server/dist/` needs its own rule).
- `mcp-server/src/core-client.ts` — `coreGet` Bearer forwarder, fixed internal base URL (SSRF guard), timeout, `{ok,status,contentType,body}`.
- `mcp-server/src/errors.ts` — `toToolError` RFC 7807 / bare-status → sanitized `isError`.
- `mcp-server/src/tools/list-products.ts` — `list_products` registrar + exported `listProductsHandler(bearer)`; allow-listed `page/size`; pino logs tool+status only.
- `mcp-server/src/server.ts` — `buildServer(bearer)` McpServer factory.
- `mcp-server/src/index.ts` — express stateless Streamable HTTP host (`POST /mcp`, `GET /health`), per-request Bearer capture, `isMain`-guarded listen; exports `app` for tests.
- `mcp-server/src/{core-client,errors,tools/list-products,index}.test.ts` — 12 vitest specs.

## Decisions Made
- **Stable SDK, not v2-alpha:** pinned `@modelcontextprotocol/sdk@^1.29.0` and used the raw-Zod-shape `registerTool` contract; confirmed the resolved tree is exactly 1.29.0 and it builds — sidestepping the Context7 v2-alpha (`@modelcontextprotocol/server` + `z.object`) trap called out in RESEARCH.
- **Tests excluded from the build:** `dist/` carries only production code; vitest transpiles tests independently.
- **`isMain`-guarded `app.listen`:** lets `index.test.ts` import `app` and bind an ephemeral port without the module also binding 9100.
- **Minimal pino usage honoring T-20-01:** logs `{ tool, status }` exclusively — grep-verified that no log statement references bearer/token/body.

## Deviations from Plan

### Task 1 — Package legitimacy gate (checkpoint:human-verify, blocking-human)
**Resolved before execution via delegated approval (verified-by-orchestrator-on-the-user's-behalf).** The user delegated gate approval to the orchestrator this session; the orchestrator verified all six packages against the LIVE npm registry (registry.npmjs.org + api.npmjs.org, 2026-07-13) prior to spawning this executor. Evidence:

| Package | Registry evidence | Install-time hooks |
|---------|-------------------|--------------------|
| @modelcontextprotocol/sdk | latest=1.29.0, repo modelcontextprotocol/typescript-sdk, 35.59M weekly | NONE |
| zod | 4.4.3, repo colinhacks/zod, 224M weekly | NONE |
| express | 5.2.1, repo expressjs/express, 114M weekly | NONE |
| vitest | 4.1.10, repo vitest-dev/vitest, 76M weekly | NONE |
| tsx | 4.23.1, repo privatenumber/tsx, 77M weekly | NONE |
| pino | 10.3.1, repo pinojs/pino, 38M weekly | NONE |

All names exact (typosquat check passed), all repos canonical, all pinned ranges satisfiable, zero preinstall/install/postinstall/prepare scripts on the pinned versions. `user_response = "approved"`. `npm install` subsequently completed with 0 vulnerabilities and the resolved tree matched the pinned versions exactly.

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Substituted native `app.listen(0)`+`fetch` for supertest in `index.test.ts`**
- **Found during:** Task 3 (integration test authoring)
- **Issue:** The plan's `index.test.ts` behavior block names supertest, but supertest was NOT one of the six packages cleared at the Task 1 package-legitimacy gate. Installing an unverified package would bypass that gate (and violate the package-install exclusion in the deviation rules).
- **Fix:** Wrote the integration test dependency-free — `app.listen(0)` on an ephemeral port + the Node 20 global `fetch` — which exercises the real express app (missing-Bearer → 401, `/health` → 200) without adding a package.
- **Files modified:** mcp-server/src/index.test.ts (and `index.ts` exports `app` + guards `listen` to support it)
- **Verification:** Both integration specs pass in the full 12/12 suite.
- **Committed in:** 2d4fca5 (RED) / 5d9752b (GREEN)

---

**Total deviations:** 1 auto-fixed (1 blocking). Task 1 gate pre-resolved via delegated approval (not a deviation).
**Impact on plan:** Zero scope creep. The supertest substitution keeps the walking slice within the approved dependency set while fully satisfying the integration-test acceptance criteria. All planned files, interfaces, and key-links were delivered as specified.

## Issues Encountered
None — all acceptance criteria met on the first GREEN pass for each task. No auto-fix loops.

## Known Stubs
None — `list_products` is fully wired to `coreGet`; the success path returns real forwarded JSON and the error path returns sanitized `isError`. (`list_shops` / `read_orders` are intentionally deferred to 20-02 per the plan objective, not stubbed here.)

## User Setup Required
None for this slice (unit/integration only). Live E2E (deferred to 20-04/05) will require the Keycloak realm re-import per `docs/security-scopes.md §4` and a full container rebuild before any live claim.

## Next Phase Readiness
- The proven skeleton (`coreGet`, `toToolError`, `buildServer`, stateless transport) is ready for **20-02** to widen tools (`list_shops`, `read_orders`) by cloning the `list_products` registrar pattern.
- The docs-freshness family extension (`mcp_test_blocks`/`mcp_test_files`, path `^mcp-server/(src|test)/.*\.(test|spec)\.ts$`) and `docs/metrics.json` bump are NOT part of this plan's file set — a later plan/task owns that CI-gate wiring so the 12 new vitest blocks are counted (currently invisible to the gate — RESEARCH Pitfall 5).
- No Flyway/schema change (stays V50); the MCP server never touches Postgres.

## Self-Check: PASSED
- All 14 created files verified present on disk.
- All 5 task commits (`71552cc`, `ee0c34e`, `eeae748`, `2d4fca5`, `5d9752b`) verified in git log.
- `npm run build` clean; `npx vitest run` → 12/12 green.

---
*Phase: 20-ai-1-mcp-server-read-only-slice*
*Completed: 2026-07-13*

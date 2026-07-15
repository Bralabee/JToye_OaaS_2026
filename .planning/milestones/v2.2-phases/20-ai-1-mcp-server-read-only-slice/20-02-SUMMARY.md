---
phase: 20-ai-1-mcp-server-read-only-slice
plan: 02
subsystem: api
tags: [mcp, model-context-protocol, typescript, esm, zod, pino, vitest, rls, bearer-passthrough, ssrf-guard, pii]

# Dependency graph
requires:
  - phase: "20-01 (walking slice)"
    provides: "coreGet(path,bearer), toToolError(res), buildServer(bearer) factory + registerTool raw-Zod-shape contract — the proven pass-through/error-mapping skeleton this plan clones"
provides:
  - "list_shops tool — thin coreGet forwarder to GET /api/v1/shops (empty input shape; no pagination — RESEARCH A1) (src/tools/list-shops.ts)"
  - "read_orders tool — list + shop-scoped list + single-order detail routed from allow-listed path templates (src/tools/read-orders.ts)"
  - "buildServer now registers exactly three read-only tools: list_products + list_shops + read_orders"
affects: [20-03-demo-seed, 20-04-e2e-rls, 20-05-live-e2e, mcp-server, docs-freshness]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Tool = clone of the 20-01 list_products registrar: build allow-listed path → coreGet(bearer) → toToolError on !ok → JSON-wrap on success → try/catch network-fault isError"
    - "read_orders path routing precedence: orderId (detail) > shopId (shop-scope) > list[?page&size] — templates only, never caller-chosen host (SSRF guard T-20-04)"
    - "PII posture: order DTOs carry customerName/customerEmail/customerPhone — logger records tool+status ONLY, never the body (T-20-01), asserted by a pino-mock spy test"

key-files:
  created:
    - "mcp-server/src/tools/list-shops.ts"
    - "mcp-server/src/tools/list-shops.test.ts"
    - "mcp-server/src/tools/read-orders.ts"
    - "mcp-server/src/tools/read-orders.test.ts"
  modified:
    - "mcp-server/src/server.ts"

key-decisions:
  - "list_shops takes an EMPTY raw Zod shape input ({}) — GET /api/v1/shops exposes no pagination params (RESEARCH A1); the handler forwards a single fixed path"
  - "read_orders routing precedence orderId > shopId > list, with each id interpolated into an allow-listed template + encodeURIComponent (SSRF/path-injection guard, T-20-04)"
  - "PII-guard test mocks the pino module (vi.hoisted spy) and asserts the response body's customerEmail/customerPhone never reach any log call — a real regression guard for T-20-01, not just a code-review grep"
  - "No new dependency added — supply-chain gate T-20-SC honoured (npm ci from the committed lockfile only; 0 vulnerabilities)"

patterns-established:
  - "Widen-by-clone: each new read tool reuses the 20-01 forwarder shape verbatim — no new architecture"
  - "Cross-tenant order-id read resolves to core RLS (404/empty); the MCP tier picks no tenant (T-20-03)"

requirements-completed: [AI-1]

# Metrics
duration: 10min
completed: 2026-07-13
---

# Phase 20 Plan 02: AI-1 MCP Server — Widen Read Tools Summary

**Widened the proven 20-01 walking slice from one tool to the full read-only surface: `list_shops` (GET /api/v1/shops) and `read_orders` (list + shop-scoped list + single-order detail), each a thin `coreGet` Bearer-forwarder registered on the same `buildServer` factory — completing AC#2 (read shops, list products, read orders) with RLS as the sole isolation boundary and customer PII kept out of logs.**

## Performance

- **Duration:** ~10 min
- **Completed:** 2026-07-13
- **Tasks:** 2 (both TDD — RED→GREEN, no refactor needed)
- **Files created:** 4 (2 production TS + 2 test TS); **modified:** 1 (`server.ts`)

## Accomplishments
- `list_shops` forwards `GET /api/v1/shops` with an **empty** input shape (no pagination params — RESEARCH A1), wrapping the tenant's shops as tool content and delegating non-2xx to `toToolError`.
- `read_orders` routes three read shapes from **allow-listed path templates only** (SSRF guard T-20-04): `orderId` → `/api/v1/orders/{id}/detail`, `shopId` → `/api/v1/orders/shop/{shopId}`, else `/api/v1/orders[?page&size]`. Precedence is orderId > shopId > list.
- All three read tools (`list_products`, `list_shops`, `read_orders`) are registered on the per-request `McpServer` via `buildServer` and discoverable through MCP tool listing.
- Order-tool output carries customer PII, but the handler logs **tool + status only** — proven by a pino-mock spy test asserting the response body's `customerEmail`/`customerPhone` never reach any log call (T-20-01).
- Cross-tenant order-id reads surface as core's RLS 404/empty — the MCP tier makes no tenant decision (T-20-03), asserted by a 404 problem+json → `isError` test.
- Full vitest suite: **23/23 green** (12 from 20-01 + 3 `list_shops` + 8 `read_orders`); `npm run build` type-checks clean (strict + NodeNext).

## Task Commits

1. **Task 1 (RED):** `3b271a8` (test) — failing unit tests for `list_shops` (success wrap, 401→toToolError, network-fault isError)
2. **Task 1 (GREEN):** `99a0cb2` (feat) — `list-shops.ts` + registration in `buildServer` (3/3 green)
3. **Task 2 (RED):** `24d9420` (test) — failing tests for `read_orders` (list/page/shopId/orderId routing, 404 RLS, PII guard)
4. **Task 2 (GREEN):** `6f49866` (feat) — `read-orders.ts` + all-three registration in `server.ts` (full suite 23/23)

_Both TDD tasks produced test → feat commit pairs. No refactor commits — the clone-of-sibling shape was clean on first GREEN._

## Files Created/Modified
- `mcp-server/src/tools/list-shops.ts` — `list_shops` registrar + exported `listShopsHandler(bearer)`; empty raw Zod shape; forwards fixed `/api/v1/shops`; pino logs tool+status only.
- `mcp-server/src/tools/list-shops.test.ts` — 3 vitest specs (success/401-delegation/network-fault).
- `mcp-server/src/tools/read-orders.ts` — `read_orders` registrar + exported `readOrdersHandler(bearer)`; `buildPath` allow-listed routing (orderId>shopId>list) with `encodeURIComponent`; pino logs tool+status only, never the PII-bearing body.
- `mcp-server/src/tools/read-orders.test.ts` — 8 vitest specs (list/page-size/shopId/orderId/precedence routing, 404 RLS delegation, network-fault, PII-not-logged guard via a mocked pino spy).
- `mcp-server/src/server.ts` — `buildServer` now calls `registerListProducts` + `registerListShops` + `registerReadOrders`.

## Decisions Made
- **Empty input shape for `list_shops`:** `GET /api/v1/shops` exposes no pagination params (RESEARCH A1), so the tool takes `{}` and forwards one fixed path — no query building.
- **`read_orders` precedence orderId > shopId > list:** matches the plan's routing spec; each id is interpolated into an allow-listed template and `encodeURIComponent`-escaped so a crafted arg can neither change the host (SSRF, T-20-04) nor inject path segments.
- **PII guard as a real test, not just a grep:** the pino module is mocked with a `vi.hoisted` spy; the test seeds the core body with `customerEmail: "victim@example.com"` and asserts no log call carries it — a regression guard for T-20-01 that survives future edits.
- **Zero new dependencies:** honoured the supply-chain gate (T-20-SC) — `npm ci` from the committed lockfile (0 vulnerabilities); no package added.

## Deviations from Plan
None — plan executed exactly as written. No auto-fixes (Rules 1–3) were needed; both tasks passed on the first GREEN pass. No architectural decisions (Rule 4) arose. No authentication gates (unit/mock only). No supply-chain checkpoint triggered (no install beyond the committed lockfile).

## Issues Encountered
None — RED failed for the expected reason (missing module) in both tasks; GREEN passed on the first attempt. No auto-fix loops.

## Known Stubs
None — both tools are fully wired to `coreGet`; success paths return real forwarded JSON, error paths return sanitized `isError`, and the three-tool registration is complete.

## Threat Flags
None — no new security surface beyond the plan's `<threat_model>`. The tools add no new endpoints (they forward to existing core reads), no new auth path (Bearer pass-through, core is the sole validator), and no schema change. T-20-01/03/04 mitigations are implemented and test-covered.

## User Setup Required
None for this slice (unit/mock only). Live E2E (deferred to 20-04/05) will require the Keycloak realm re-import per `docs/security-scopes.md §4` and a full container rebuild before any live RLS claim.

## Next Phase Readiness
- The full read-only tool surface (`list_products`, `list_shops`, `read_orders`) is registered and unit-proven against a mocked core — **20-03** (demo seed) and **20-04/05** (live E2E incl. `e2e-rls.sh`) can now exercise all three tools end-to-end.
- The docs-freshness family extension (`mcp_test_blocks`/`mcp_test_files`) and `docs/metrics.json` bump remain a later plan/task's job — this plan adds 11 new vitest blocks (3 + 8) currently invisible to the gate (RESEARCH Pitfall 5), consistent with 20-01's note.
- No Flyway/schema change (stays V50); the MCP server never touches Postgres.

## Self-Check: PASSED
- All 4 created files + the modified `server.ts` verified present on disk.
- All 4 task commits (`3b271a8`, `99a0cb2`, `24d9420`, `6f49866`) verified in git log.
- `npm run build` clean; `npx vitest run` → 23/23 green; `grep -R registerTool mcp-server/src` shows list_products + list_shops + read_orders; no response body logged in read-orders.ts.

---
*Phase: 20-ai-1-mcp-server-read-only-slice*
*Completed: 2026-07-13*

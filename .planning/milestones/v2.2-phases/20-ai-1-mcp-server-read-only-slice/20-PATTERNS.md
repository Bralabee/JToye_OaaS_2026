# Phase 20: AI-1 MCP Server (Read-Only Slice) - Pattern Map

**Mapped:** 2026-07-13
**Files analyzed:** 16 new + 3 modified
**Analogs found:** 13 with in-repo analogs / 19 total (6 genuinely-new MCP-SDK files lean on RESEARCH.md)

> **Nature of this phase:** GREENFIELD TypeScript workspace (`mcp-server/`) added to a Java+TS+Go monorepo. There is **no existing MCP server** to copy. The analogs below are for **CONVENTION consistency** (Docker shape, compose wiring, env/secret handling, RFC 7807 field names, HTTP-client-forwarding posture, test/docs-freshness plumbing) — **NOT for logic**. The MCP protocol code (SDK bootstrap, tool registration, Streamable HTTP transport) is genuinely new; for those files the planner must lean on RESEARCH.md §Architecture Patterns (verified against SDK v1.29.0 source), because nothing in this repo speaks MCP.

---

## File Classification

Files derived from CONTEXT.md (locked decisions) + RESEARCH.md §"Recommended Project Structure" + §"Validation Architecture / Wave 0 Gaps".

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `mcp-server/package.json` | config (workspace manifest) | — | `frontend/package.json` | role-match (Jest→vitest, CJS→ESM differ) |
| `mcp-server/tsconfig.json` | config | — | `frontend/tsconfig.json` | role-match (bundler→NodeNext differ) |
| `mcp-server/Dockerfile` | config (container build) | — | `frontend/Dockerfile` | exact (multi-stage node:20-alpine) |
| `mcp-server/.dockerignore` | config | — | `frontend/.dockerignore` | exact |
| `mcp-server/vitest.config.ts` | config (test runner) | — | `frontend/jest.config.js` | partial (different runner, echo intent) |
| `mcp-server/README.md` | docs | — | `docs/security-scopes.md` §6, `docs/idempotency.md` | role-match (doc conventions) |
| `mcp-server/src/index.ts` | bootstrap (HTTP host + transport) | request-response | **NO in-repo analog** (MCP SDK bootstrap) | none → RESEARCH.md Pattern 2 |
| `mcp-server/src/server.ts` | provider (McpServer + tool registry) | request-response | **NO in-repo analog** (MCP SDK) | none → RESEARCH.md Pattern 1 |
| `mcp-server/src/core-client.ts` | service (thin HTTP forwarder) | request-response / transform | `edge-go/internal/core/client.go` | role-match (Go→TS; forwarding shape transfers) |
| `mcp-server/src/errors.ts` | utility (error mapper) | transform | `core-java/.../common/GlobalExceptionHandler.java` | role-match (parses this shape) |
| `mcp-server/src/tools/list-shops.ts` | tool (thin GET wrapper) | request-response | `edge-go/internal/core/client.go` (forward) + RESEARCH.md Pattern 1 | partial |
| `mcp-server/src/tools/list-products.ts` | tool | request-response | same | partial |
| `mcp-server/src/tools/read-orders.ts` | tool | request-response | same | partial |
| `mcp-server/src/**/*.test.ts` (per-file units) | test | — | `frontend/lib/__tests__/api-client.test.ts` | role-match (Jest idioms → vitest `vi.mock`) |
| `mcp-server/src/index.test.ts` | test (integration/supertest) | request-response | `frontend/lib/__tests__/*.test.ts` | partial |
| `mcp-server/scripts/e2e.sh` | test (live E2E) | — | `scripts/verify-env.sh` (curl/token idioms) | role-match |
| `mcp-server/scripts/e2e-rls.sh` | test (live cross-tenant RLS proof) | — | `scripts/verify-env.sh` §RLS smoke | role-match |
| **MODIFIED** `docker-compose.full-stack.yml` | config (service wiring) | — | `edge-go:` service block (lines 259-289) | exact |
| **MODIFIED** `scripts/docs-freshness.sh` + `docs/metrics.json` | config (CI gate) | — | existing PLAYWRIGHT_* family block (lines 59-76) | exact |

---

## Pattern Assignments

### `mcp-server/Dockerfile` (config, container build)

**Analog:** `frontend/Dockerfile` — the repo's canonical multi-stage `node:20-alpine` build. Mirror the two-stage builder→runner split, the `npm ci` cache-layer ordering, the non-root user, and the `HEALTHCHECK` node one-liner.

**Multi-stage shape** (`frontend/Dockerfile:5-30`):
```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./   # manifest first → cache npm ci layer
RUN npm ci
COPY . .
RUN npm run build
```

**Non-root runner + HEALTHCHECK** (`frontend/Dockerfile:33-66`) — replicate the non-root user and the `node -e require('http').get(...)` healthcheck idiom; point it at the MCP server's own `GET /health` (RESEARCH.md says add a trivial `/health` that does NOT call core):
```dockerfile
FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup --system --gid 1001 nodejs && adduser --system --uid 1001 nodejs
...
USER nodejs
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD node -e "require('http').get('http://localhost:3000/api/health', (r) => {process.exit(r.statusCode === 200 ? 0 : 1)})"
```
**Convention to replicate:** node:20-alpine multi-stage, manifest-first COPY, non-root uid 1001, `node -e http.get` healthcheck. **Divergence from analog:** frontend uses Next.js `standalone` output (copies `.next/standalone`); the MCP server is plain `tsc → dist/` (see RESEARCH.md Dockerfile example) and copies `dist/`. Frontend EXPOSEs 3000; the MCP server EXPOSEs its own port (RESEARCH.md suggests 9100 — planner picks, must match compose `ports:` + healthcheck URL).

---

### `mcp-server/.dockerignore` (config)

**Analog:** `frontend/.dockerignore` — copy the `node_modules/`, build-output, `.env*`, IDE-file exclusions verbatim; swap Next's `.next/`/`out/` for the MCP build dir (`dist/`).

---

### `mcp-server/package.json` (config, workspace manifest)

**Analog:** `frontend/package.json` — mirror the `private: true`, `scripts` block shape (`build`/`test`/`start`), and the Zod major already standardized in the repo.

**Repo-standard versions to reuse** (`frontend/package.json:42,60,50`):
```json
"zod": "^4.2.1",          // reuse the repo's Zod 4 major (RESEARCH pins ^4)
"typescript": "5.9.3",    // match the frontend TS 5.9 major — do NOT jump to TS 7
"@types/node": "20.19.43" // node 20 types
```
**Scripts convention** (`frontend/package.json:5-13`) — the repo uses terse npm scripts (`"build"`, `"test"`, `"test:watch"`, `"test:coverage"`). Echo that naming.
**Divergence (LOCKED by CONTEXT/RESEARCH):** MCP workspace is **ESM** (`"type": "module"`) and uses **vitest**, not Jest — the frontend is CJS-Next+Jest. Do NOT copy Jest devDeps. New deps per RESEARCH.md §Standard Stack: `@modelcontextprotocol/sdk@^1.29.0`, `express@^5`, `pino`, `vitest@^4`, `tsx`, `@types/express`.

---

### `mcp-server/tsconfig.json` (config)

**Analog:** `frontend/tsconfig.json` — reuse `"strict": true`, `"skipLibCheck": true`, `"esModuleInterop": true`, `"resolveJsonModule": true`, `"isolatedModules": true`.

**Reuse block** (`frontend/tsconfig.json:8-16`):
```json
"strict": true,
"esModuleInterop": true,
"resolveJsonModule": true,
"isolatedModules": true,
"skipLibCheck": true
```
**Divergence (LOCKED — RESEARCH Pitfall 6):** frontend uses `"module": "esnext"` + `"moduleResolution": "bundler"` + `"noEmit": true` (Next bundles). MCP server must EMIT (`tsc → dist/`) and be Node-native ESM: `"module": "NodeNext"`, `"moduleResolution": "NodeNext"` (or `Node16`), `"target": "ES2022"`, `"outDir": "dist"`, `noEmit` removed. This divergence is deliberate — do not copy the bundler resolution.

---

### `mcp-server/src/core-client.ts` (service, thin HTTP forwarder)

**Analog:** `edge-go/internal/core/client.go` — the repo's existing "thin client that forwards to core over HTTP with a timeout, passing the caller's Bearer through." Go, not TS, but the **conventions transfer**: fixed internal base URL, explicit timeout, `Authorization: Bearer` forwarding, status-code branching, generic error wrapping (never leak upstream body to the caller).

**Bearer pass-through + fixed internal base URL** (`edge-go/internal/core/client.go:93-102`):
```go
httpReq, _ := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/v1/sync/batch", ...)
httpReq.Header.Set("Content-Type", "application/json")
httpReq.Header.Set("Authorization", "Bearer "+token)   // ← forward caller token verbatim
```
The compose analog sets `CORE_API_URL: http://core-java:9090` (internal service name, `docker-compose.full-stack.yml:266`). **Convention to replicate:** MCP `core-client.ts` reads `CORE_BASE_URL` env → `http://core-java:9090` (NOT `localhost` — RESEARCH Anti-Pattern), forwards the incoming Bearer verbatim.

**Explicit timeout** (`edge-go/internal/core/client.go:59-61`):
```go
client: &http.Client{ Timeout: 30 * time.Second },
```
**Convention to replicate:** hard client-side timeout that trips BEFORE core's 30s query timeout. RESEARCH.md Pattern 3 does this with `AbortSignal.timeout(10_000)` on the built-in `fetch` — use that TS form; the Go analog only justifies *having* an explicit timeout, not the value.

**Status-code branching without leaking upstream body** (`edge-go/internal/core/client.go:109-119`):
```go
if httpResp.StatusCode >= 500 {
    body, _ := io.ReadAll(httpResp.Body)
    c.logger.Error("Core API server error", zap.Int("status", ...), zap.String("body", ...))
    return nil, fmt.Errorf("server error: %d", httpResp.StatusCode)  // caller gets status only
}
```
**Convention to replicate:** branch on status class, log status server-side, return a `{ok,status,body}` struct to the caller; the error-shaping into MCP `isError` happens in `errors.ts` (below), not here. **RESEARCH Pitfall 7:** unlike the Go analog which logs `body`, the MCP tier must **NEVER log response bodies** (order DTOs carry customer PII) — log tool name + status code ONLY. This is a deliberate divergence from the Go analog's `zap.String("body", ...)`.

**What does NOT transfer:** the gobreaker circuit-breaker (`client.go:35-65`) is Go-tier resilience; RESEARCH.md deliberately keeps the MCP client dependency-free (`fetch` + `AbortSignal` only). Do not port the breaker.

---

### `mcp-server/src/errors.ts` (utility, transform: problem+json → tool error)

**Analog:** `core-java/.../common/GlobalExceptionHandler.java` — this is the **producer** of the exact `application/problem+json` payloads the MCP mapper must parse. Read it for the precise field names and the status taxonomy, then map to a sanitized MCP `isError` result (RESEARCH.md §"RFC 7807 → MCP tool error").

**Exact RFC 7807 field shape core emits** (`GlobalExceptionHandler.java:44-50`):
```java
ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
problem.setTitle("Resource Not Found");                              // → body.title
problem.setType(URI.create("https://jtoye.uk/errors/not-found"));   // → body.type
// forStatusAndDetail sets body.status (int) + body.detail (string)
```
So every core error body is `{ type, title, status, detail }`. Two handlers add an **extra property** the mapper may surface:
- Validation (`GlobalExceptionHandler.java:106`): `problem.setProperty("errors", errors)` → `body.errors` (field→message map).
- Stripe (`GlobalExceptionHandler.java:248`): `problem.setProperty("stripeCode", ...)` → `body.stripeCode` (out of scope for read tools, but confirms the extra-property pattern).

**Status taxonomy the mapper must handle** (from the `@ExceptionHandler` methods): 400 (Bad Request / Invalid Argument / validation / type-mismatch), 401 (`handleAuthentication` "Unauthorized"), 403 (`handleAccessDenied` "Forbidden"), 404 (`handleResourceNotFound` / `handleNoResourceFound`), 409 (`handleDataIntegrityViolation` / idempotency conflict), 422 (idempotency mismatch / incomplete label), 500 (`handleGenericException` — generic "An unexpected error occurred", NO internals leaked), 502 (Stripe). The RESEARCH.md `errors.ts` `generic[]` map covers 401/403/404/409/422 + a 5xx fallback — align its messages with these titles.

**Convention to replicate:** parse `content-type` for `problem+json`, read `title` + `detail`, emit `{ content:[{type:"text", text: "core <status> <title>: <detail>"}], isError:true }`. **CRITICAL (matches core's own posture, `GlobalExceptionHandler.java:344-347`):** core itself logs the stack trace server-side and returns only a generic message — the MCP mapper mirrors this: **never** forward `err.stack`, undici internals, or the token to the model.

---

### `mcp-server/src/tools/{list-shops,list-products,read-orders}.ts` (tools, request-response)

**Analog (logic shape):** NO in-repo analog — use RESEARCH.md **Pattern 1** (`registerTool` with a **raw Zod shape** `inputSchema`, NOT `z.object(...)` — the v1.29.0 contract; the v2-alpha `z.object` form will not compile). Each tool = build query string → `coreGet(path, bearer)` → on `!ok` return `toToolError` → else wrap `JSON.stringify(body)` as text content.

**Analog (forwarding posture):** `edge-go/internal/core/client.go` as above — fixed path templates, forward Bearer, never let caller input choose the host (RESEARCH Security Domain: SSRF mitigation — paths built from allow-listed templates).

**Exact core read endpoints** (verified from `docs/api/openapi-snapshot.json`):
- `list_shops` → `GET /api/v1/shops` (RESEARCH A1: no pagination params → returns default `PageShopDto`)
- `list_products` → `GET /api/v1/products` (RESEARCH A1: same; optional `search_products` → `GET /api/v1/products/search?q=`)
- `read_orders` → `GET /api/v1/orders` (accepts `pageable`; also `GET /api/v1/orders/shop/{shopId}`, `/api/v1/orders/{id}`, `/api/v1/orders/{id}/detail`)

**PII caveat (RESEARCH Pitfall 7):** `read-orders.ts` output carries `customerName/customerEmail/customerPhone/tenantId` — the tool returns it (RLS-scoped) but the code must not log the body.

---

### `mcp-server/src/index.ts` and `mcp-server/src/server.ts` (bootstrap + provider)

**Analog:** NONE — genuinely new MCP SDK code. Use RESEARCH.md **Pattern 2** (stateless Streamable HTTP: `express` app, `POST /mcp`, capture `Authorization` header, fresh `StreamableHTTPServerTransport({ sessionIdGenerator: undefined })` + `buildServer(bearer)` per request, `res.on("close")` cleanup) and **Pattern 1** (`McpServer` factory registering the 3 tools). Add the trivial `GET /health` for the Dockerfile healthcheck. The ONLY convention from this repo that applies: read config from env (`CORE_BASE_URL`), and if `pino` logging is added, mirror the Go tier's structured-JSON posture (tool + status, never token/PII).

---

### `mcp-server/vitest.config.ts` + `mcp-server/src/**/*.test.ts` (test)

**Analog (conventions to echo):** `frontend/jest.config.js` + `frontend/lib/__tests__/api-client.test.ts`. The repo's TS-test conventions to mirror even though the runner changes Jest→vitest:
- **Colocation / naming:** repo uses both `__tests__/` dirs and `*.test.ts` suffix (`jest.config.js:22-25` `testMatch`). RESEARCH.md places tests as `src/**/*.test.ts` — consistent with the suffix convention.
- **`describe`/`it` nesting + mock-the-HTTP-client idiom** (`api-client.test.ts:4-49`): the repo mocks the HTTP layer (`jest.mock('axios', ...)`) and asserts config/behavior. The MCP units do the same with `vi.mock` stubbing `coreGet`/`fetch` (RESEARCH §Phase Requirements → Test Map). Echo the `describe(<unit>) > it(<behavior>)` structure.

**Divergence (LOCKED — RESEARCH Pitfall 6 + §Test Surface):** vitest (`vi.mock`), ESM-native, NOT Jest/ts-jest. The `\b(it|test)\(` content-regex used by docs-freshness already matches vitest blocks, so keeping `it(...)`/`test(...)` naming (as the frontend does) is load-bearing for the CI gate below.

---

### `mcp-server/scripts/e2e.sh` + `e2e-rls.sh` (test, live E2E)

**Analog:** `scripts/verify-env.sh` — the repo's canonical bash health/RLS smoke idioms. Mirror the `curl -s -o /dev/null -w "%{http_code}"` status-assertion form and the `pass/fail` colored-helper structure.

**Auth-required + status-assert idiom** (`verify-env.sh:157-163`):
```bash
SHOP_NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/shops)
if [ "$SHOP_NO_AUTH" = "401" ] || [ "$SHOP_NO_AUTH" = "403" ]; then
  pass "Protected endpoints require authentication (HTTP ${SHOP_NO_AUTH})"
```
**Token mint (from CONTEXT `<specifics>` + RESEARCH Open Q1):** the E2E harness mints the reference token from the **host** endpoint `:8085` (split-horizon: `iss=localhost:8085`, which core accepts — RESEARCH Pitfall 1):
```bash
curl -s -X POST http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=integration-catalog-ro \
  -d client_secret=$INTEGRATION_CATALOG_RO_SECRET
```
For the **cross-tenant RLS proof** (`e2e-rls.sh`), RESEARCH Open Q1 recommends two `directAccessGrantsEnabled` password-grant tokens (`tenant-a-user`/`tenant-b-user`) against `core-api`, asserted **through the MCP tool** at the HTTP boundary (superuser Testcontainers cannot prove RLS — RESEARCH Pitfall 4).

---

## Shared Patterns

### Env / secret handling (reuse #206 wiring)
**Source:** `scripts/verify-env.sh:32-48` (REQUIRED_VARS already includes `INTEGRATION_CATALOG_RO_SECRET`), `.env.example:111-113`, `docker-compose.full-stack.yml:54,67`.
**Apply to:** compose service block + README + e2e scripts.
```bash
# verify-env.sh already lists the reference secret — no new required var needed for pure pass-through:
REQUIRED_VARS=( ... INTEGRATION_CATALOG_RO_SECRET )
```
```yaml
# compose renders it into the realm via envsubst (keycloak-realm-render sidecar, line 67):
INTEGRATION_CATALOG_RO_SECRET: ${INTEGRATION_CATALOG_RO_SECRET:?INTEGRATION_CATALOG_RO_SECRET must be set ...}
```
**Convention:** secrets come from `.env` (`CHANGE_ME` placeholder in `.env.example`), never literals; NON-secret config (URLs/ports) may be inline. **RESEARCH primary design is pass-through (NO secret in the MCP container)** — only add `INTEGRATION_CATALOG_RO_SECRET`/`KC_TOKEN_URL` to the MCP service block for the documented single-tenant demo fallback (RESEARCH Open Q3). If the fallback is NOT built, the MCP service needs no secret env at all.

### docker-compose service wiring (depends_on core + keycloak)
**Source:** `docker-compose.full-stack.yml` — `edge-go:` block (259-289) is the closest analog (a networked service depending on core), `frontend:` block (292-339) shows the two-way `depends_on ... service_healthy` on BOTH core + keycloak.
**Apply to:** the new `mcp-server:` block.
```yaml
edge-go:
  build: { context: ./edge-go, dockerfile: Dockerfile }
  container_name: jtoye-edge-go
  restart: unless-stopped
  environment:
    CORE_API_URL: http://core-java:9090        # internal service name, NOT localhost
  depends_on:
    core-java: { condition: service_healthy }
  networks: [ jtoye-network ]
```
**Convention to replicate for `mcp-server:`:** `build.context: ./mcp-server`, `container_name: jtoye-mcp-server`, `restart: unless-stopped`, `CORE_BASE_URL: http://core-java:9090`, `depends_on` BOTH `core-java` AND `keycloak` with `condition: service_healthy` (mirror the frontend block, 327-331, since MCP E2E needs Keycloak too), `networks: [jtoye-network]`, and a `healthcheck` hitting its own `/health` (RESEARCH.md compose example). Ports: expose the MCP port (RESEARCH suggests `9100:9100`).

### docs-freshness CI gate (new test family)
**Source:** `scripts/docs-freshness.sh:59-76` (PLAYWRIGHT_* family + TOTAL + JSON emit) and `docs/metrics.json` (baseline **1208**, schema V50).
**Apply to:** the script modification + metrics.json regeneration (RESEARCH Pitfall 5 — MCP tests are invisible unless a new family is added).
```bash
# Existing family to clone (docs-freshness.sh:59-62):
PLAYWRIGHT_BLOCKS=$(count_occurrences '^frontend/e2e/.*\.spec\.ts$' '\btest\(')
PLAYWRIGHT_SPECS=$(count_files_with '^frontend/e2e/.*\.spec\.ts$' '\btest\(')
TOTAL=$((JAVA_TEST_METHODS + JEST_BLOCKS + GO_TEST_FUNCS + PLAYWRIGHT_BLOCKS))
```
**Convention to replicate (per RESEARCH §Test Surface):** add a NEW `MCP_TEST_BLOCKS`/`MCP_TEST_FILES` family with path-regex `^mcp-server/(src|test)/.*\.(test|spec)\.ts$` and the SAME `\b(it|test)\(` content-regex (already matches vitest); fold `MCP_TEST_BLOCKS` into `TOTAL`; add `mcp_test_blocks`/`mcp_test_files` keys to the emitted JSON; run `scripts/docs-freshness.sh --write` to regenerate `docs/metrics.json` (bumps 1208) and update the CLAUDE.md testing-standard prose. This is the LOCKED family-decision (new family, not Jest extension — Jest counter is path-anchored to `frontend/`).

### Split-horizon issuer (do NOT re-implement — inherit)
**Source:** `docker-compose.full-stack.yml:266-274` (edge-go) — `KC_ISSUER_URI` (internal `keycloak:8080` for JWKS) vs `JWT_EXPECTED_ISSUER` (public `localhost:8085` for `iss`). **Apply to:** awareness only — the MCP server only *forwards* an opaque token and never validates `iss` (RESEARCH Pitfall 1 says it is therefore immune). The E2E harness must mint from the host `:8085` endpoint. Do NOT add issuer-validation env to the MCP service.

---

## No Analog Found

Files that are genuinely new (MCP protocol adapter) — planner leans on RESEARCH.md, not this repo:

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `mcp-server/src/index.ts` | bootstrap | request-response | No MCP/Express Streamable-HTTP host exists in-repo → RESEARCH.md Pattern 2 (stateless transport, per-request Bearer). |
| `mcp-server/src/server.ts` | provider | request-response | No `McpServer`/`registerTool` usage anywhere → RESEARCH.md Pattern 1 (raw-Zod-shape `inputSchema`, v1.29.0 API). |
| `mcp-server/src/tools/*.ts` (MCP wiring half) | tool | request-response | The `registerTool` + `CallToolResult` shape is MCP-SDK-specific → RESEARCH.md Pattern 1. (The forwarding half mirrors `core-client.ts`/edge Go.) |
| `mcp-server/src/errors.ts` (MCP result half) | utility | transform | `CallToolResult`/`isError` shape is SDK-specific → RESEARCH.md §"RFC 7807 → MCP tool error". (The parse half mirrors `GlobalExceptionHandler`.) |

**Also net-new (no code analog, spec-driven):** the Streamable-HTTP-vs-stdio transport decision (RESEARCH resolves: Streamable HTTP, stateless), and the `@modelcontextprotocol/sdk@^1.29.0` pin (RESEARCH §Standard Stack — beware Context7 surfacing the v2-alpha split-package API).

---

## Metadata

**Analog search scope:** `frontend/` (Dockerfile, package.json, tsconfig, jest.config, `lib/__tests__`), `edge-go/internal/core/client.go`, `core-java/.../common/GlobalExceptionHandler.java`, `docker-compose.full-stack.yml`, `scripts/{docs-freshness.sh,verify-env.sh}`, `docs/metrics.json`, `.env.example`, `docs/api/openapi-snapshot.json`.
**Files scanned:** ~14 read in full/targeted; compose service inventory + OpenAPI read-path grep.
**Pattern extraction date:** 2026-07-13

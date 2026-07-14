# Phase 20: AI-1 MCP Server (Read-Only Slice) - Research

**Researched:** 2026-07-13
**Domain:** Model Context Protocol server (TypeScript) as a thin, tenant-scoped read wrapper over the existing Spring Boot core REST API; OAuth2 client-credentials token pass-through; PostgreSQL RLS as the isolation boundary.
**Confidence:** HIGH (SDK API verified against v1.29.0 source + npm; auth substrate, endpoints, DTOs read directly from the repo/OpenAPI snapshot; two-tenant token path resolved from the realm template)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Runtime & framework**
- **TypeScript**, official `@modelcontextprotocol/sdk`. NO new Python/Go runtime added to the Java+TS+Go stack.
- New **`mcp-server/`** workspace at repo root (sibling of `core-java/`, `edge-go/`, `frontend/`).
- Packaged as its **own Docker container**, wired into `docker-compose*.yml` (dev compose is the E2E target). Node 20+ base (match frontend's Node major).

**Architecture / security boundary**
- The MCP server is a **thin tool layer over the existing core REST API via HTTP**. It does **NOT** talk to Postgres directly. `core-java` + RLS remain the sole security boundary — the MCP server holds no DB credentials and enforces no tenant logic of its own.
- Isolation is proven by RLS, not by MCP-server code. A confused/hostile agent cannot cross tenants because the token's `tenant_id` claim scopes every core query.

**Auth (REUSE #206 — do not invent a new auth path)**
- Keycloak **client-credentials** token pass-through. The agent presents a token; the MCP server forwards it as `Authorization: Bearer` to core.
- `tenant_id` claim drives RLS in core. Read tools map to `catalog:read` / `orders:read` scopes (`orders:read` is currently **reserved/unenforced** — read tools just present the token and RLS isolates; do not add enforcement in this slice).
- Reference credential: the **`integration-catalog-ro`** client + **`INTEGRATION_CATALOG_RO_SECRET`** env var shipped by #206 (client-credentials, `core-api` audience mapper, managed `tenant_id` on its service-account user).
- **#88 audience gate:** core rejects tokens lacking the `core-api` audience — the reference client already has the audience mapper; any new machine client must too.

**Tools (read-only)**
- `list_shops`, `list_products`, `read_orders` (names/shape finalized by the planner against the actual core endpoints — see Standard Stack + Architecture). Product/order reads are the priority per the ACs.
- Tool **errors** must map core's RFC 7807 Problem Detail (`application/problem+json` via `GlobalExceptionHandler`) into MCP tool-error content — **never** leak raw stack traces or HTTP client noise.

**Testing**
- Cross-tenant RLS test is an AC — must PROVE isolation, not assume it. Two tenant-scoped tokens (tenant A, tenant B); an A-token MCP `list_products` call must never return B's rows (empty/403), asserted end-to-end.
- Live E2E against the running dev stack is REQUIRED (not just unit). Standing rule: **rebuild ALL containers before any live E2E claim.**

### Claude's Discretion
- MCP **transport** choice (stdio vs Streamable HTTP) — researcher to recommend + capture token-delivery mechanism per transport. **→ Resolved below: Streamable HTTP, stateless, per-request Bearer pass-through.**
- Exact tool names/param shapes against the real endpoints.
- Test runner for the standalone TS workspace + docs-freshness reconciliation approach.
- Dockerfile shape + compose wiring details.

### Deferred Ideas (OUT OF SCOPE)
- Any **mutating** MCP tool (create/update/state-transition) — gated on wiring #204 `Idempotency-Key`; next slice.
- Enforcing `orders:*` **write** scope on the order surface — follow-up mirroring the `catalog:write` gate.
- #205 outbound tenant webhooks — separate phase.
- Per-tenant/per-agent **programmatic** client provisioning — needs realm `tenant_id` managed-attribute change (KC24 trap) + reuse of #102 `KeycloakAdminClient` seam.
- MCP resource/prompt primitives beyond tools — start with tools only.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AI-1 | A Model Context Protocol server (`mcp-server/`, TypeScript, official `@modelcontextprotocol/sdk`, own Docker container) exposes read-only tenant-scoped tools — list shops, list products, read orders — each wrapping the EXISTING core REST API over HTTP (never Postgres directly). Auth reuses #206 Keycloak client-credentials token pass-through, `tenant_id` claim drives RLS, read tools map to `catalog:read`/`orders:read`. Cross-tenant access returns empty/403 (RLS-proven test); tool errors surface RFC 7807 not raw stack traces; live E2E against the dev stack; README documents the client-credentials setup. | Standard Stack (SDK 1.29.0 + transport), Architecture Patterns (thin pass-through wrapper), §Auth Wiring (reuse `integration-catalog-ro`), §Exact READ Endpoints (real paths + DTOs from OpenAPI), §RFC 7807 mapping, Validation Architecture (RLS proof design + E2E), §Environment Availability (realm re-import precondition) |
</phase_requirements>

## Summary

This phase adds a **standalone TypeScript MCP server** (`mcp-server/`) that exposes three read-only tools — `list_shops`, `list_products`, `read_orders` — each of which makes an authenticated HTTP GET to the existing `core-java` REST API (`/api/v1/shops`, `/api/v1/products`, `/api/v1/orders`) and returns the JSON result as MCP tool content. The server holds **no database credentials, no tenant logic, and (in the recommended design) no client secret**: it forwards the caller's `Authorization: Bearer <token>` verbatim to core. Core's existing chain — `JwtTenantFilter` sets `TenantContext` from the token's `tenant_id`, `TenantSetLocalAspect` pins `app.current_tenant_id`, and Postgres RLS filters every row — is the sole isolation boundary. This is the entire security thesis: a confused or hostile agent cannot cross tenants because it never chooses the tenant; the token does, and RLS enforces it in the database.

The `@modelcontextprotocol/sdk` package is at **v1.29.0** (verified on npm, 35.6M downloads/week, official repo `modelcontextprotocol/typescript-sdk`). **Important currency note:** Context7 currently surfaces the **v2.0-alpha split-package** API (`@modelcontextprotocol/server`, `serveStdio`, `inputSchema: z.object(...)`), which is **not** the locked package. The stable single package `@modelcontextprotocol/sdk` uses subpath imports (`@modelcontextprotocol/sdk/server/mcp.js`) and `inputSchema` expressed as a **raw Zod shape object** (not wrapped in `z.object()`). The skeleton in this document is the verified v1.29.0 API; the planner must pin `@modelcontextprotocol/sdk@^1.29.0` and treat the v2-alpha snippets as forward-looking only.

**Transport recommendation: Streamable HTTP, stateless, with per-request Bearer pass-through.** A containerized server that an external agent connects to over the network is exactly the Streamable HTTP use case. Stateless mode (`sessionIdGenerator: undefined`, one transport per request) lets a **single container serve many tenants** — each MCP call carries its own tenant token in the `Authorization` header, which the tool forwards to core. stdio would pin one process to one env-injected token (single tenant), which is wrong for this deployment.

**Primary recommendation:** Build `mcp-server/` as a Node 20 / TypeScript 5.9 / Zod 4 ESM service using `@modelcontextprotocol/sdk@^1.29.0` + `express@^5` (Streamable HTTP transport, stateless), forward the incoming Bearer token to `http://core-java:9090/api/v1/...` (internal service name), map `application/problem+json` responses to `isError` tool results, test with **vitest** registered as a new docs-freshness family, and prove cross-tenant isolation live using the already-seeded `tenant-a-user` / `tenant-b-user` tokens (see the KEY open question about client-credentials tenant-B).

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| MCP protocol (tool discovery, invocation, transport) | MCP server (new) | — | Only the MCP server speaks MCP; it is a protocol adapter. |
| Tenant isolation (who sees which rows) | Database (Postgres RLS) | Core (`JwtTenantFilter` → `TenantContext`) | LOCKED: RLS is the sole boundary. MCP server MUST NOT filter by tenant. |
| AuthN/AuthZ (token validity, audience, scope) | Core (Spring OAuth2 resource server, `#88` audience validator, `JwtRolesAndScopesConverter`) | Keycloak (issuer/JWKS) | LOCKED: core rejects bad tokens; MCP server forwards opaquely. |
| Token acquisition (client-credentials grant) | Calling agent / E2E harness | Keycloak | Pass-through model: the agent mints its own token; MCP server does not (recommended). |
| Business reads (shops/products/orders) | Core REST API (`/api/v1/*`) | — | MCP tools are thin HTTP callers; no business logic in the MCP tier. |
| Error shaping (RFC 7807 → tool error) | MCP server | Core (emits `problem+json`) | MCP server translates transport/HTTP faults into MCP `isError` content without leaking stack traces. |
| Data persistence | Database | Core repositories | MCP server never touches Postgres directly (LOCKED). |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `@modelcontextprotocol/sdk` | ^1.29.0 | MCP server, tool registration, stdio + Streamable HTTP transports | LOCKED by CONTEXT; official SDK. `[VERIFIED: npm registry — but discovered via CONTEXT/official repo, see audit]` v1.29.0 published 2026-06-04, 35.6M dl/wk, repo `modelcontextprotocol/typescript-sdk`. |
| `zod` | ^4.4.3 (repo pins `^4.2.1`) | Tool input schemas (SDK consumes a Zod raw shape) | Repo already standardizes on Zod 4 in `frontend/`; SDK peer-supports `^3.25 \|\| ^4.0`. Reuse the frontend major. |
| `express` | ^5.2.1 | HTTP host for the Streamable HTTP transport endpoint (`POST /mcp`) | The SDK's Streamable HTTP examples use Express; it is a transitive dep of the SDK. `[CITED: typescript-sdk examples]` |
| `typescript` | 5.9.x (match `frontend` 5.9.3) | Build/typecheck | Match the frontend TS major (5.x); do NOT jump to TS 7 for one service. |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `undici` (or Node 20 global `fetch`) | Node 20 built-in `fetch` | HTTP client to core (timeouts via `AbortSignal.timeout`) | Prefer the built-in `fetch` (undici under the hood) — zero deps, native to Node 20. Only add `undici` explicitly if you need `Agent`/connect-timeout tuning. |
| `vitest` | ^4.1.10 | Unit + integration test runner for the ESM TS workspace | Recommended runner (see §Testing rationale). ESM-native, first-class `vi.mock` for stubbing the core HTTP client. |
| `tsx` | ^4.23.1 | Dev-run TS without a build step (`tsx watch src/index.ts`) | Local dev ergonomics; optional. |
| `pino` | ^10.3.1 | Structured JSON logging (mirrors the Go tier's `zap` posture) | Optional but recommended — log tool name + core status, NEVER the token or PII response bodies. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Streamable HTTP transport | stdio transport | stdio pins one process to one env-injected token → single-tenant only. Correct only if the agent *spawns* the server locally (e.g., Claude Desktop). Wrong for a networked container. |
| vitest | `node:test` (built-in) | Zero new deps, but weaker mocking DX and needs a TS loader (`tsx`). Acceptable if the team wants no new runner; vitest's `vi.mock` is worth one dev-dependency for a service whose whole job is calling HTTP. |
| vitest | jest (repo default) | Jest + ESM + the ESM-only SDK is real friction (the repo already noted jest quirks). Do NOT force jest onto this ESM workspace. |
| express | SDK's built-in `hono`/`@hono/node-server` (transitive) | Express matches the SDK's documented examples and is more familiar; either works. |

**Installation:**
```bash
# in mcp-server/
npm install @modelcontextprotocol/sdk@^1.29.0 zod@^4 express@^5 pino
npm install -D typescript@~5.9 vitest@^4 tsx @types/express @types/node
```

**Version verification (performed 2026-07-13):**
```
@modelcontextprotocol/sdk  1.29.0   (npm latest, modified 2026-06-04)  repo: modelcontextprotocol/typescript-sdk
zod                        4.4.3    repo: colinhacks/zod
express                    5.2.1    (SDK peer)   vitest 4.1.10   tsx 4.23.1   pino 10.3.1
```

## Package Legitimacy Audit

> slopcheck could not be installed in this session (the environment's base-Python guard blocks `pip install`). Per protocol, packages are therefore verified via npm registry + official-repo + download-count signals, and the planner should still gate the first install behind a light human sanity-check. All four core packages are extremely well-established (millions of weekly downloads, canonical GitHub repos), so residual slop risk is negligible.

| Package | Registry | Age/Version | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-------------|-----------|-------------|-----------|-------------|
| `@modelcontextprotocol/sdk` | npm | 1.29.0 (2026-06-04) | 35.6M/wk | github.com/modelcontextprotocol/typescript-sdk | unavailable | Approved (official, LOCKED) |
| `zod` | npm | 4.4.3 | 224M/wk | github.com/colinhacks/zod | unavailable | Approved (already in repo) |
| `express` | npm | 5.2.1 | (SDK peer, ubiquitous) | github.com/expressjs/express | unavailable | Approved |
| `vitest` | npm | 4.1.10 | 76M/wk | github.com/vitest-dev/vitest | unavailable | Approved |
| `tsx` | npm | 4.23.1 | 77M/wk | github.com/privatenumber/tsx | unavailable | Approved |
| `pino` | npm | 10.3.1 | (ubiquitous) | github.com/pinojs/pino | unavailable | Approved (optional) |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none
**Postinstall check:** `@modelcontextprotocol/sdk` has no `postinstall` script (verified via `npm view … scripts.postinstall` → empty).

**Provenance caveat:** Package names above are `[VERIFIED: npm registry]` for existence/version AND cross-checked against their canonical GitHub repos, but because slopcheck was unavailable the planner should keep a `checkpoint:human-verify` before the first `npm install` per the graceful-degradation rule.

## Architecture Patterns

### System Architecture Diagram

```
                         Authorization: Bearer <tenant-scoped JWT>
  ┌─────────────┐  MCP over Streamable HTTP  ┌──────────────────────────┐
  │ External AI │ ─────────────────────────► │  mcp-server (NEW)        │
  │ agent /     │   POST /mcp  (tool call)   │  Node 20 / TS / SDK 1.29 │
  │ MCP client  │ ◄───────────────────────── │                          │
  └─────────────┘   tool result / isError    │  1. read Authorization   │
        │                                     │     header (opaque)      │
        │ (agent mints its own token)         │  2. tool → HTTP GET      │
        ▼                                     │     core, forward Bearer │
  ┌─────────────┐                             │  3. map problem+json →   │
  │  Keycloak   │  client-credentials grant   │     isError content      │
  │ jtoye-dev   │  (integration-catalog-ro)   └───────────┬──────────────┘
  │ :8080 int   │                                         │ Authorization: Bearer (verbatim)
  │ :8085 host  │                                         │ GET /api/v1/{shops,products,orders}
  └─────────────┘                                         ▼
        ▲  JWKS (internal keycloak:8080)     ┌──────────────────────────┐
        └────────────────────────────────────│  core-java  :9090        │
                                             │  #88 audience validator   │  ◄── SECURITY BOUNDARY
                                             │  JwtTenantFilter →        │
                                             │  TenantContext(tenant_id) │
                                             │  JwtRolesAndScopesConv.   │
                                             └───────────┬──────────────┘
                                                         │ set_config('app.current_tenant_id', …)
                                                         ▼
                                             ┌──────────────────────────┐
                                             │ PostgreSQL 15  (RLS FORCE)│  ◄── ISOLATION HAPPENS HERE
                                             │ rows filtered by tenant_id│
                                             └──────────────────────────┘
```

Trace the primary use case: agent calls `list_products` with tenant-A token → MCP server forwards `GET /api/v1/products` with that Bearer → core validates audience/issuer, sets `TenantContext = tenant-A`, RLS returns only tenant-A rows → MCP server wraps the `PageProductDto` JSON as tool content. The MCP tier makes zero tenancy decisions.

### Recommended Project Structure
```
mcp-server/
├── package.json            # ESM ("type":"module"), scripts: build/dev/test/start
├── tsconfig.json           # target ES2022, module NodeNext, strict
├── Dockerfile              # multi-stage node:20-alpine (mirror frontend/Dockerfile)
├── .dockerignore
├── vitest.config.ts
├── README.md               # client-credentials setup (AC: "README documents the setup")
└── src/
    ├── index.ts            # bootstrap: express app + Streamable HTTP transport (stateless)
    ├── server.ts           # buildServer(bearer): McpServer factory, registers the 3 tools
    ├── core-client.ts      # thin fetch wrapper: GET(path, bearer) → {status, body} + timeout
    ├── errors.ts           # problem+json / network fault → CallToolResult(isError)
    └── tools/
        ├── list-shops.ts       # GET /api/v1/shops        → PageShopDto
        ├── list-products.ts    # GET /api/v1/products     → PageProductDto
        └── read-orders.ts      # GET /api/v1/orders?…     → PageOrderDto
```

### Pattern 1: McpServer + tool registration (stable v1.x API)
**What:** Construct `McpServer`, register a tool with a **raw Zod shape** input schema, return `content[]`.
**When to use:** Every tool in this phase.
**Example:**
```typescript
// Source: modelcontextprotocol/typescript-sdk @ v1.29.0
//   src/examples/server/simpleStatelessStreamableHttp.ts (verbatim import + registerTool shape)
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

export function buildServer(bearer: string): McpServer {
  const server = new McpServer(
    { name: "jtoye-mcp", version: "0.1.0" },
    { capabilities: { logging: {} } }
  );

  server.registerTool(
    "list_products",
    {
      title: "List products",
      description: "List the calling tenant's product catalogue (RLS-scoped by the token).",
      // NB: RAW ZOD SHAPE — NOT z.object({...}). This is the v1.x contract.
      inputSchema: {
        page: z.number().int().min(0).optional().describe("0-based page index"),
        size: z.number().int().min(1).max(100).optional().describe("page size"),
      },
    },
    async ({ page, size }): Promise<CallToolResult> => {
      const qs = new URLSearchParams();
      if (page !== undefined) qs.set("page", String(page));
      if (size !== undefined) qs.set("size", String(size));
      const res = await coreGet(`/api/v1/products?${qs}`, bearer); // see core-client.ts
      if (!res.ok) return toToolError(res);                        // see errors.ts
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };
    }
  );
  // …register list_shops and read_orders identically…
  return server;
}
```
> **Version pitfall:** the v2.0-alpha docs (Context7) show `import { McpServer } from '@modelcontextprotocol/server'`, `serveStdio`, and `inputSchema: z.object({...})`. Those are a **different package** and will not compile against `@modelcontextprotocol/sdk@1.29.0`. Use the imports above.

### Pattern 2: Streamable HTTP transport, stateless, per-request Bearer pass-through
**What:** One transport per request; the incoming `Authorization` header is captured and closed over by the tool handlers.
**When to use:** The container deployment (LOCKED transport for this phase).
**Example:**
```typescript
// Source: modelcontextprotocol/typescript-sdk @ v1.29.0 stateless example, adapted for pass-through
import express from "express";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { buildServer } from "./server.js";

const app = express();
app.use(express.json());

app.post("/mcp", async (req, res) => {
  const bearer = (req.headers.authorization ?? "").replace(/^Bearer\s+/i, "");
  if (!bearer) {                       // fail fast; core is still the real validator
    res.status(401).json({ error: "missing_bearer_token" });
    return;
  }
  // Stateless: fresh transport + server per request, tools close over this tenant's token.
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
  const server = buildServer(bearer);
  res.on("close", () => { transport.close(); server.close(); });
  await server.connect(transport);
  await transport.handleRequest(req, res, req.body);
});

app.listen(9100, () => console.log("mcp-server listening on :9100/mcp"));
```
**Multi-tenant answer:** because a fresh `buildServer(bearer)` is created per request, one container instance serves any number of tenants concurrently — each request's tenant is decided entirely by its own token. The MCP server holds no secret and no tenant state.

### Pattern 3: Core HTTP client with timeout + problem+json parsing
```typescript
// core-client.ts
const CORE = process.env.CORE_BASE_URL ?? "http://core-java:9090"; // internal service name in compose
export async function coreGet(path: string, bearer: string) {
  const r = await fetch(`${CORE}${path}`, {
    headers: { authorization: `Bearer ${bearer}`, accept: "application/json" },
    signal: AbortSignal.timeout(10_000),           // fail cleanly before core's 30s query timeout
  });
  const ct = r.headers.get("content-type") ?? "";
  const body = ct.includes("json") ? await r.json().catch(() => null) : await r.text();
  return { ok: r.ok, status: r.status, contentType: ct, body };
}
```

### Anti-Patterns to Avoid
- **Tenant filtering in the MCP tier.** Never read `tenant_id` from the token in the MCP server to filter or route. RLS does this. Adding MCP-side tenant logic creates a second, divergent boundary (LOCKED prohibition).
- **Minting tokens inside the MCP server for the general case.** Holding `INTEGRATION_CATALOG_RO_SECRET` in the MCP container pins it to tenant A and puts a secret where the boundary decision says none should live. Acceptable only as a documented single-tenant demo fallback (see Open Questions), not the primary design.
- **Forwarding core's raw error body / your own stack trace to the model.** Map to a sanitized `isError` result (see §RFC 7807).
- **stdio transport for the container.** Single-tenant, env-pinned; wrong deployment shape.
- **Calling `localhost:9090`/`localhost:8085` from inside the container.** Use compose service names (`core-java:9090`, `keycloak:8080`); `localhost` is only for host-side E2E/curl.
- **Talking to Postgres or importing a DB driver.** Explicitly forbidden.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MCP protocol framing, JSON-RPC, tool discovery | A custom JSON-RPC loop | `@modelcontextprotocol/sdk` `McpServer` + transport | Protocol churn; the SDK owns capability negotiation + Streamable HTTP session semantics. |
| Tenant isolation | MCP-side row filtering / tenant routing | Core `JwtTenantFilter` + Postgres RLS (already shipped, FORCE RLS) | LOCKED: RLS is the boundary; a second boundary is a liability. |
| Token validation (signature/issuer/audience/scope) | JWT decode/verify in the MCP server | Core Spring OAuth2 resource server (`#88` audience validator + `JwtRolesAndScopesConverter`) | Core already 401/403s bad tokens; duplicating invites split-horizon bugs (see #87). |
| Input validation | Manual arg checks | Zod raw-shape `inputSchema` (SDK validates + generates JSON Schema) | SDK infers handler arg types and rejects bad input before your code runs. |
| Error taxonomy | Bespoke error strings | Map core's RFC 7807 `problem+json` (`type/title/status/detail`) → `isError` | Core already produces client-safe problem details; reuse them. |
| HTTP client | axios + interceptors | Node 20 global `fetch` + `AbortSignal.timeout` | Zero deps; native; timeouts are one line. |

**Key insight:** This phase's correctness is almost entirely *subtraction* — the MCP server is valuable precisely because it does as little as possible. Every capability it declines to implement (auth, tenancy, validation, error taxonomy) is one the platform already owns and proves.

## Common Pitfalls

### Pitfall 1: Split-horizon issuer/JWKS (the #87 landmine, now for the MCP path)
**What goes wrong:** Tokens are stamped with the **public** issuer `http://localhost:8085/realms/jtoye-dev` (because `KC_HOSTNAME=localhost`, port 8085), but core fetches JWKS from the **internal** `http://keycloak:8080/realms/jtoye-dev` and validates `iss` against `JWT_EXPECTED_ISSUER=http://localhost:8085/...`. If the E2E harness or a token-minting MCP fallback requests a token from the wrong host, or you assume `iss == token endpoint host`, validation fails invisibly.
**Why it happens:** Keycloak's frontend URL (issuer) is decoupled from the backchannel host; core was explicitly configured (`JWT_EXPECTED_ISSUER`) to bridge this after #87 caused a total auth outage.
**How to avoid:** The MCP server only *forwards* an opaque token — it never validates `iss`, so it is immune. The **E2E harness** should mint tokens from the host token endpoint `http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token`; the returned `iss` will be `localhost:8085`, which core accepts. If a single-tenant fallback mints tokens *inside* the container against `http://keycloak:8080/...`, the returned `iss` is still `localhost:8085` (Keycloak stamps the configured frontend hostname), so it also works — but verify this explicitly.
**Warning signs:** core returns 401 "The iss claim is not valid" on a token that decodes fine.

### Pitfall 2: Realm not re-imported → `integration-catalog-ro` doesn't exist in the live IdP
**What goes wrong:** `start-dev --import-realm` only creates realms that don't already exist; the running `jtoye-dev` predates #206, so the machine client + scopes are absent. Any live token/E2E claim fails with "invalid client".
**How to avoid:** Before any live E2E, re-import per `docs/security-scopes.md §4` — either drop the Keycloak DB/volume and restart, or `kc.sh import --override true`. Then verify with the curl in CONTEXT `<specifics>`. This is a hard operational precondition, not optional.
**Warning signs:** `unauthorized_client` / `invalid_client` from the token endpoint for `integration-catalog-ro`.

### Pitfall 3: #88 audience gate rejects any new machine client silently
**What goes wrong:** If the phase adds a second machine client (e.g., for tenant B) without cloning the `core-api-audience-mapper`, its tokens lack `aud=core-api` and core 401s them at decode time — *before* any scope/RLS logic — so it looks like a tenant/RLS bug.
**How to avoid:** Any new machine client MUST carry both protocol mappers `tenant-id-mapper` + `core-api-audience-mapper` (copy the `integration-catalog-ro` block).
**Warning signs:** 401 on every call from the new client; token has no `aud` claim.

### Pitfall 4: Superuser Testcontainers cannot prove RLS
**What goes wrong:** A Java Testcontainers test using the bootstrap Postgres superuser bypasses even FORCE RLS, so an "isolation" test passes vacuously. The MCP cross-tenant AC must not be "proven" this way.
**How to avoid:** The MCP RLS proof lives at the **HTTP boundary** with two genuinely tenant-scoped tokens against the live dev stack (see Validation Architecture). The core-side `rls_test_role NOSUPERUSER` downgrade is a Java-test tool and is not the MCP proof.
**Warning signs:** an isolation test that never actually cross-checks two tokens, or runs against a superuser DB role.

### Pitfall 5: docs-freshness gate silently ignores MCP tests
**What goes wrong:** `scripts/docs-freshness.sh` only counts tests under `core-java/src/test`, `*_test.go`, `frontend/…/*.test.tsx`, `frontend/e2e/*.spec.ts`. A new `mcp-server/**/*.test.ts` is invisible — the gate stays green while the "all new code requires tests" standard is unenforced and `total_logical_invocations` (1208) undercounts.
**How to avoid:** Extend the script with a new family (see §Test Surface + docs-freshness) and `--write` to regenerate `docs/metrics.json`. This is a required task in the plan.
**Warning signs:** MCP tests exist but `docs/metrics.json` total is unchanged.

### Pitfall 6: ESM/CJS + Zod version friction
**What goes wrong:** The SDK is ESM-only (`dist/esm`). Mixing CommonJS `require`, `ts-jest`, or a stale Zod can cause `ERR_REQUIRE_ESM` or type mismatches in `inputSchema`.
**How to avoid:** `"type": "module"` in `package.json`, `module: "NodeNext"` in tsconfig, import with `.js` specifiers, use Zod 4 + vitest (ESM-native). Do not use jest here.

### Pitfall 7: read_orders / order tools surface customer PII
**What goes wrong:** `OrderDto`/`OrderDetailDto` include `customerName`, `customerEmail`, `customerPhone`, and `tenantId`. The MCP tool output therefore carries PII; logs or error messages that echo response bodies would leak it.
**How to avoid:** Never log response bodies; log only tool name + core status code. RLS keeps PII tenant-scoped, but the MCP tier must not widen exposure via logging. (Note: `tenantId` in DTOs is a known future cleanup — requirement DTO-01 — out of scope here, but be aware the tool output includes it.)

## Code Examples

### RFC 7807 → MCP tool error (no stack-trace leakage)
```typescript
// errors.ts — Source pattern derived from core GlobalExceptionHandler.java (problem+json)
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

export function toToolError(res: { status: number; contentType: string; body: unknown }): CallToolResult {
  // Core emits application/problem+json: { type, title, status, detail, [errors], [stripeCode] }
  let msg: string;
  if (res.contentType.includes("problem+json") && res.body && typeof res.body === "object") {
    const p = res.body as { title?: string; detail?: string; status?: number };
    msg = `core ${res.status} ${p.title ?? ""}: ${p.detail ?? ""}`.trim();
  } else {
    // Map bare status codes to safe, generic messages
    const generic: Record<number, string> = {
      401: "Unauthorized: token missing, expired, or wrong audience",
      403: "Forbidden: token lacks the required scope",
      404: "Not found",
      409: "Conflict",
      422: "Unprocessable request",
    };
    msg = generic[res.status] ?? (res.status >= 500 ? "Upstream core error" : `core ${res.status}`);
  }
  return { content: [{ type: "text", text: msg }], isError: true };
}

// Network/timeout faults (thrown by fetch): catch around coreGet and return:
//   { content: [{ type: "text", text: "Core API unreachable or timed out" }], isError: true }
// NEVER include err.stack, undici internals, or the token.
```

### Dockerfile (mirror frontend/Dockerfile: multi-stage node:20-alpine)
```dockerfile
# mcp-server/Dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build            # tsc → dist/

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY package*.json ./
RUN npm ci --omit=dev
COPY --from=builder /app/dist ./dist
EXPOSE 9100
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD node -e "require('http').get('http://localhost:9100/health',(r)=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"
CMD ["node", "dist/index.js"]
```
> Add a trivial `GET /health` route to the express app for the healthcheck (returns 200; does NOT call core, so the container is healthy even if core is briefly down).

### docker-compose service wiring (add to docker-compose.full-stack.yml)
```yaml
  mcp-server:
    build:
      context: ./mcp-server
      dockerfile: Dockerfile
    container_name: jtoye-mcp-server
    environment:
      TZ: Europe/London
      CORE_BASE_URL: http://core-java:9090          # internal service name, NOT localhost
      # Pass-through model: NO secret here. Only add the block below for the
      # single-tenant demo fallback (see Open Questions):
      # KC_TOKEN_URL: http://keycloak:8080/realms/jtoye-dev/protocol/openid-connect/token
      # INTEGRATION_CATALOG_RO_SECRET: ${INTEGRATION_CATALOG_RO_SECRET:?...}
    ports:
      - "9100:9100"
    depends_on:
      core-java:
        condition: service_healthy
      keycloak:
        condition: service_healthy
    restart: unless-stopped
    networks:
      - jtoye-network
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| MCP SSE transport (`/sse` + `/messages`) | **Streamable HTTP** transport | MCP spec 2025-03; SDK ≥1.10 | Use `StreamableHTTPServerTransport`, not the deprecated SSE transport. |
| `@modelcontextprotocol/sdk` single package, `inputSchema` = raw Zod shape | v2.0-**alpha** split packages (`@modelcontextprotocol/server`, `serveStdio`, `inputSchema: z.object`) | v2 alpha (2026, `2.0.0-alpha.2`) | **Do NOT adopt.** LOCKED package is the stable single `@modelcontextprotocol/sdk` (1.29.0). Context7's current snippets are the alpha API. |
| `server.tool(name, shape, cb)` | `server.registerTool(name, {title, description, inputSchema, outputSchema, annotations}, cb)` | SDK ~1.10+ | Prefer `registerTool` (richer metadata); `server.tool` still works. |

**Deprecated/outdated:**
- SSE server transport — superseded by Streamable HTTP.
- Any tutorial importing from `@modelcontextprotocol/server` / `@modelcontextprotocol/node` / `@modelcontextprotocol/express` — that is the v2 alpha, not the locked package.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `list_shops`→`GET /api/v1/shops` and `list_products`→`GET /api/v1/products` expose **no pagination query params** (OpenAPI shows the `list`/`list_2` ops with zero params returning a `Page*Dto`), so those tools cannot page and return the server default page. | Exact READ Endpoints | LOW — tools still work; only large catalogues are truncated to the default page. Planner may expose `read_orders` paging (it does accept `pageable`) and note the shop/product limitation. |
| A2 | `GET /api/v1/products/search?q=` returns a list of `ProductDto` (OpenAPI renders a single `ProductDto` ref, likely a springdoc array-flattening artefact). | Exact READ Endpoints | LOW — `search` is optional/secondary; verify actual shape if a `search_products` tool is added. |
| A3 | The recommended **pure pass-through** (no secret in the container) satisfies the AC "README documents the client-credentials setup" by documenting how the *agent/harness* mints the token, not by the server minting it. | Auth Wiring | MEDIUM — if the user actually wants a single-tenant server that mints its own token, use the documented fallback instead. Flagged in Open Questions. |
| A4 | Token minted internally (`keycloak:8080`) still gets `iss=http://localhost:8085/...` because `KC_HOSTNAME=localhost` sets the frontend/issuer URL regardless of backchannel host. | Pitfall 1 | MEDIUM — if wrong, an in-container token-minting fallback would 401; the pass-through primary design is unaffected. Verify during E2E if the fallback is built. |
| A5 | vitest (`it`/`test` blocks) can be counted by the existing `\b(it|test)\(` content-regex, so the new docs-freshness family only needs a new *path* regex. | Test Surface | LOW — trivially verifiable when writing the script change. |
| A6 | No Flyway migration is needed this phase (schema stays **V50**); the slice is a new TS service + reuse of #206 auth-config only. | Environment Availability | LOW — confirmed: MCP server never touches the DB; #206 was auth-config only. |

## Open Questions (RESOLVED 2026-07-13 — decisions baked into 20-0{3,4,5}-PLAN.md)

> **RESOLVED OQ1:** Use the direct-access password grant against `core-api` with the seeded `tenant-a-user`/`tenant-b-user` for the cross-tenant RLS proof (zero realm change). `integration-catalog-ro` (client-credentials, tenant A) is the read happy-path + README reference credential. → 20-04 (`e2e-rls.sh`), 20-05.
> **RESOLVED OQ2:** Extend dev-profile `DemoDataSeeder` to seed one shop+product for tenant B → unfakeable disjoint-set proof. → 20-03.
> **RESOLVED OQ3:** Pass-through is the locked default (stateless, no secret in the container). Server-minted token is an OPTIONAL README note only, not built this slice. → 20-03.

1. **[KEY] How do we obtain a genuine tenant-B token for the cross-tenant RLS proof?**
   - **What we know:** The #206 realm seed provides exactly **one** machine (client-credentials) client — `integration-catalog-ro` — whose service-account user has a **fixed** `tenant_id = 00000000-…-000000000001` (tenant A). A client-credentials token always carries that SA user's tenant. **There is no client-credentials path to a tenant-B token today.** However, the dev realm **already seeds two password users** with distinct tenants: `tenant-a-user` (`…0001`) and `tenant-b-user` (`…0002`), and the `core-api` client has `directAccessGrantsEnabled: true`.
   - **Recommendation (lowest friction):** For the cross-tenant proof, mint the two tokens via the **Resource-Owner Password grant** against `core-api` using `tenant-a-user` / `tenant-b-user` (both genuine tenant-scoped tokens; RLS keys off `tenant_id`, so the isolation proof is fully valid even though these are operator tokens that also carry `catalog:write`). Use `integration-catalog-ro` (tenant A, client-credentials) for the **read happy-path** E2E and the README, since that is the LOCKED reference machine credential.
   - **Alternative (higher fidelity):** Add a second machine client `integration-catalog-ro-tenant-b` (tenant B) to `realm-export.template.json`, cloning both protocol mappers (`tenant-id-mapper` with tenant B, `core-api-audience-mapper`). This keeps the "two read-only agents" narrative pure but requires a realm-template edit + re-import (re-import is required anyway). **Planner decides A vs B; both prove RLS identically.**

2. **Does tenant B have any data to make the proof bidirectional?**
   - **What we know:** `DemoDataSeeder` (`@Profile("dev")`) seeds **only** tenant A (`DEMO_TENANT = …0001`). Tenant B has zero shops/products/orders. So the minimal proof is: A-token `list_products` → non-empty (A's rows); B-token `list_products` → empty. That proves "A's rows never leak to B," but "empty for B" is doubly-explained (RLS *and* no data), which is a weaker, semi-fakeable assertion.
   - **Recommendation:** For an **unfakeable, bidirectional** proof, extend `DemoDataSeeder` (dev-only) to also seed one small shop+product for tenant B, then assert: A-token sees A's product-id and **not** B's; B-token sees B's product-id and **not** A's (disjoint non-empty sets). This is a low-risk dev-profile change and makes the AC genuinely load-bearing. (Writes are out of scope for the MCP server, so seed via the Java seeder, not via a tool.)

3. **Pass-through vs. server-minted token — which does the user want operationally?**
   - Recommended: **pass-through** (multi-tenant, no secret in the container, matches the boundary decision). Fallback: **server-minted** (single-tenant tenant-A demo; container holds `INTEGRATION_CATALOG_RO_SECRET`, mints via client-credentials at call time). The README should document the pass-through setup; the fallback is a documented convenience for demos where the calling agent cannot do OAuth. Confirm with the user which is the intended default before the planner locks the container env.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Node.js 20+ | mcp-server build/runtime | Assumed (frontend already node:20-alpine) | 20.x | — |
| Docker + compose | container + live E2E | ✓ (whole stack is compose-based) | — | — |
| Running `core-java` (`:9090`) | all tool calls | ✓ when stack up | Spring Boot 3.5.16 | none — hard dep |
| Running Keycloak (`:8085` host / `:8080` internal) | token minting for E2E | ✓ when stack up | 24.0.5 | none — hard dep |
| **Realm re-imported since #206** | live tokens for `integration-catalog-ro` | ✗ **NOT done yet** (CONTEXT `<specifics>`) | — | Re-import per `docs/security-scopes.md §4` before any live E2E |
| `INTEGRATION_CATALOG_RO_SECRET` env | minting the reference token | ✓ wired in compose + `.env.example` | — | Set in `.env` (rendered into realm by `keycloak-realm-render`) |
| Flyway migration | — | N/A | schema stays **V50** | No DB change this phase |

**Missing dependencies with no fallback:**
- Live re-imported Keycloak realm — **blocking for live E2E** (the `integration-catalog-ro` client does not exist in the currently-running IdP). Must re-import + rebuild ALL containers first.

**Missing dependencies with fallback:**
- Tenant-B client-credentials token — no direct fallback; use the seeded `tenant-b-user` password grant, or add a second machine client (Open Question 1).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | **vitest** ^4.1.10 (new, for `mcp-server/`) + curl/node-based live E2E script |
| Config file | `mcp-server/vitest.config.ts` (Wave 0 — does not exist yet) |
| Quick run command | `cd mcp-server && npx vitest run` |
| Full suite command | `cd mcp-server && npx vitest run` (unit/integration) + `mcp-server/scripts/e2e.sh` (live) |

### Phase Requirements → Test Map
| Req | Behavior | Test Type | Automated Command | File Exists? |
|-----|----------|-----------|-------------------|--------------|
| AI-1 | `list_products` forwards Bearer + returns core JSON as tool content | unit (mock `coreGet`) | `npx vitest run src/tools/list-products.test.ts` | ❌ Wave 0 |
| AI-1 | `list_shops` / `read_orders` same contract (incl. `read_orders` paging + `shopId`) | unit | `npx vitest run` | ❌ Wave 0 |
| AI-1 | non-2xx `problem+json` → `isError` result with sanitized message, no stack trace | unit | `npx vitest run src/errors.test.ts` | ❌ Wave 0 |
| AI-1 | network/timeout → `isError` "unreachable/timeout", token never logged | unit | `npx vitest run` | ❌ Wave 0 |
| AI-1 | stateless transport builds a per-request server; missing Bearer → 401 | integration (supertest against express app) | `npx vitest run src/index.test.ts` | ❌ Wave 0 |
| AI-1 | **read happy-path** — `integration-catalog-ro` token → `list_products` → 200 rows (tenant A) | **live E2E** | `mcp-server/scripts/e2e.sh` (needs re-imported realm + rebuilt stack) | ❌ Wave 0 |
| AI-1 | **cross-tenant RLS proof** — tenant-A token sees A's rows and NOT B's; tenant-B token sees NOT A's rows | **live E2E** (two real tokens at HTTP boundary) | `mcp-server/scripts/e2e-rls.sh` | ❌ Wave 0 |
| AI-1 | write attempt is not part of the MCP surface (read-only slice); a `catalog:read`-only token still 403s on a core write (sanity) | live E2E (optional) | curl per `docs/security-scopes.md §3` | ❌ Wave 0 |
| AI-1 | docs-freshness stays green with the new MCP family counted | CI gate | `scripts/docs-freshness.sh` | ✅ (script exists; extend it) |

### Cross-tenant RLS proof design (the load-bearing AC)
1. Obtain **two genuinely tenant-scoped tokens** at the HTTP boundary (per Open Question 1): tenant A and tenant B.
2. (Recommended per Open Question 2) ensure disjoint seeded data: tenant A has product `P_A`, tenant B has product `P_B`.
3. Assert, through the **MCP tool** (not a raw core curl — the proof must exercise the server):
   - `list_products` with token A → contains `P_A`, does **not** contain `P_B`.
   - `list_products` with token B → contains `P_B` (or empty if B unseeded), does **not** contain `P_A`.
   - `read_orders`/order-by-id with token A for one of B's order ids → 404/empty (never B's row).
4. This must run against the **live dev stack** with real tokens (superuser Testcontainers cannot prove RLS — Pitfall 4). Gate the whole E2E behind "rebuild ALL containers + re-import realm first."

### Sampling Rate
- **Per task commit:** `cd mcp-server && npx vitest run` (unit + integration, mocked core).
- **Per wave merge:** full vitest suite + `scripts/docs-freshness.sh` green.
- **Phase gate:** live E2E (`e2e.sh` + `e2e-rls.sh`) green against a rebuilt, realm-re-imported dev stack before `/gsd:verify-work`.

### Wave 0 Gaps
- [ ] `mcp-server/vitest.config.ts` + `package.json` test script
- [ ] `mcp-server/src/**/*.test.ts` — unit tests for each tool + `errors.ts` + `core-client.ts` (mock fetch)
- [ ] `mcp-server/src/index.test.ts` — express/transport integration (supertest)
- [ ] `mcp-server/scripts/e2e.sh` + `e2e-rls.sh` — live token mint + MCP call assertions
- [ ] `scripts/docs-freshness.sh` extension (new `mcp_test_blocks`/`mcp_test_files` family) + `docs/metrics.json --write`
- [ ] (recommended) `DemoDataSeeder` tenant-B seed for a bidirectional RLS proof

## Test Surface + docs-freshness reconciliation (detail)

**Decision: register MCP tests as a NEW family, not an extension of the Jest family.** The Jest counter is path-anchored to `frontend/…/*.test.tsx?`; MCP tests live under `mcp-server/` and use vitest. Extend `scripts/docs-freshness.sh`:
```bash
# after the PLAYWRIGHT_* block:
MCP_TEST_BLOCKS=$(count_occurrences '^mcp-server/(src|test)/.*\.(test|spec)\.ts$' '\b(it|test)\(')
MCP_TEST_FILES=$(count_files_with  '^mcp-server/(src|test)/.*\.(test|spec)\.ts$' '\b(it|test)\(')
# add MCP_TEST_BLOCKS into TOTAL:
TOTAL=$((JAVA_TEST_METHODS + JEST_BLOCKS + GO_TEST_FUNCS + PLAYWRIGHT_BLOCKS + MCP_TEST_BLOCKS))
# add "mcp_test_blocks"/"mcp_test_files" keys to the emitted JSON
```
Then run `scripts/docs-freshness.sh --write` to regenerate `docs/metrics.json` (bumps `total_logical_invocations` from 1208) and update the CLAUDE.md testing-standard prose. The `\b(it|test)\(` content-regex already matches vitest blocks, so only the path regex + a new key are new. This makes the gate enforce MCP test coverage going forward (otherwise MCP tests are invisible — Pitfall 5).

## Security Domain

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Delegated to core (Spring OAuth2 resource server + Keycloak). MCP server forwards an opaque Bearer; does not authenticate. |
| V3 Session Management | partial | MCP Streamable HTTP **stateless** (no server-side session); each request re-authorizes via its own token. No session fixation surface. |
| V4 Access Control | yes | Tenant isolation via Postgres RLS (`tenant_id` claim); scope gates (`catalog:write`) in core. MCP tier adds none (by design). |
| V5 Input Validation | yes | Zod raw-shape `inputSchema` validates tool args before the handler; core `@Valid` validates downstream. |
| V6 Cryptography | no (delegated) | JWT signature/JWKS handled by core + Keycloak; MCP server never verifies or signs. |
| V7 Error Handling & Logging | yes | Map `problem+json` → sanitized `isError`; never log token or PII response bodies (Pitfall 7). |
| V9 Communications | yes (dev caveat) | Internal compose traffic is plaintext HTTP (`core-java:9090`, `keycloak:8080`) — acceptable in-network for dev; production would terminate TLS at the ingress and the MCP endpoint must require Bearer over TLS. |

### Known Threat Patterns for {MCP pass-through over multi-tenant RLS API}
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Confused-deputy: agent tries to read another tenant's data | Elevation of Privilege / Information Disclosure | Token carries `tenant_id`; RLS filters in DB. MCP server never picks the tenant. Proven by the cross-tenant E2E AC. |
| Token forwarded to wrong upstream / SSRF via configurable base URL | Tampering / Info Disclosure | `CORE_BASE_URL` is a fixed compose env (`http://core-java:9090`), not caller-controlled. Tools build paths from allow-listed templates, never from raw agent input. |
| Missing-audience token accepted | Spoofing | Core `#88` audience validator rejects tokens without `aud=core-api` — including any misconfigured new machine client. |
| PII leakage via logs/error text | Information Disclosure | Log tool name + status only; error mapper emits generic/sanitized messages; never echo response bodies or `err.stack`. |
| Stale token after realm re-import lacks new scopes | (operational) | Fail-closed: old tokens 403 on writes until re-login; reads unaffected. Documented in `docs/security-scopes.md §4`. |
| Secret sprawl (client secret in MCP container) | Info Disclosure | Recommended pass-through holds **no** secret. If the demo fallback is used, `INTEGRATION_CATALOG_RO_SECRET` comes from env (never a literal), same posture as the rest of the stack. |

## Sources

### Primary (HIGH confidence)
- **Codebase (read directly, 2026-07-13):**
  - `docs/security-scopes.md` — scope taxonomy, `integration-catalog-ro` client, re-import (§4), KC24 trap (§5), AI-1 mandate (§6).
  - `infra/keycloak/realm-export.template.json` — confirmed: one machine client (tenant A), two password users `tenant-a-user`/`tenant-b-user` (tenants `…0001`/`…0002`), `core-api` `directAccessGrantsEnabled:true`, both protocol mappers on `integration-catalog-ro`.
  - `docs/api/openapi-snapshot.json` — exact GET paths/params/DTOs (see Exact READ Endpoints).
  - `core-java/.../security/SecurityConfig.java` — `/api/v1/public/**` permitAll; everything else `authenticated`; `JwtRolesAndScopesConverter` wired.
  - `core-java/.../security/JwtRolesAndScopesConverter.java` — ROLE_* ∪ SCOPE_* union.
  - `core-java/.../common/GlobalExceptionHandler.java` — RFC 7807 `ProblemDetail` taxonomy (401/403/404/409/422/5xx, generic 500).
  - `core-java/.../{product,shop,order}/*Controller.java` — reads authenticated-only, product writes `SCOPE_catalog:write`.
  - `core-java/.../dev/DemoDataSeeder.java` — `@Profile("dev")`, seeds **only** tenant A (`…0001`).
  - `docker-compose.full-stack.yml` — service names/ports: `core-java:9090`, `keycloak:8080` int / `8085` host; split-horizon `JWT_EXPECTED_ISSUER`; `keycloak-realm-render` envsubst of `INTEGRATION_CATALOG_RO_SECRET`.
  - `scripts/docs-freshness.sh` + `docs/metrics.json` — counted families + baseline 1208 / V50.
  - `frontend/Dockerfile`, `frontend/package.json` — node:20-alpine multi-stage, TS 5.9.3, Zod 4.2.1.
- **npm registry (verified 2026-07-13):** `@modelcontextprotocol/sdk` 1.29.0 (35.6M dl/wk, no postinstall), `zod` 4.4.3, `express` 5.2.1, `vitest` 4.1.10, `tsx` 4.23.1, `pino` 10.3.1.
- **`modelcontextprotocol/typescript-sdk` @ v1.29.0 source** (raw GitHub): `src/examples/server/simpleStatelessStreamableHttp.ts` and `mcpServerOutputSchema.ts` — verified stable-API imports (`@modelcontextprotocol/sdk/server/mcp.js`, `.../streamableHttp.js`), `registerTool` raw-Zod-shape `inputSchema`, `{ content, isError }` result shape, `sessionIdGenerator: undefined` stateless transport.

### Secondary (MEDIUM confidence)
- Context7 `/modelcontextprotocol/typescript-sdk` — useful for concepts (stateless HTTP, Bearer/`authInfo` extraction, `isError`) but **currently returns the v2.0-alpha split-package API** (`@modelcontextprotocol/server`, `serveStdio`, `inputSchema: z.object`). Treated as forward-looking; the stable v1.x API above was re-derived from the pinned v1.29.0 source.

### Tertiary (LOW confidence)
- None relied upon for load-bearing claims.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every package + version verified on npm against its canonical repo; SDK API re-derived from pinned v1.29.0 source (not training memory, not the diverging Context7 alpha).
- Architecture / auth wiring: HIGH — read directly from the realm template, SecurityConfig, compose, and controllers.
- Two-tenant RLS proof path: MEDIUM-HIGH — the constraint (one machine client = one tenant) is confirmed from the realm template; the recommended password-grant workaround is verified to be available (`directAccessGrantsEnabled:true` + two seeded users), but the bidirectional-data enhancement (seed tenant B) is a recommendation, not yet in the codebase.
- Pitfalls: HIGH — each is grounded in a repo fact or a documented prior incident (#87 split-horizon, #88 audience, §4 re-import, superuser-RLS).

**Research date:** 2026-07-13
**Valid until:** ~2026-08-13 for the auth/endpoint/codebase facts (stable); ~2026-07-27 for the SDK version (fast-moving — re-verify `@modelcontextprotocol/sdk` latest and the v2 GA status before pinning).

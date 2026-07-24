# Phase 25: Mutating MCP Tools - Research

**Researched:** 2026-07-24
**Domain:** MCP write-tool extension over Spring REST + Keycloak client-scoped auth + Postgres FORCE RLS
**Confidence:** HIGH (all findings are file:line evidence in this repo; the design is already locked in `25-CONTEXT.md`)

## Summary

Phase 25 extends the shipped Phase-20 read-only MCP server (`mcp-server/`, TypeScript, `@modelcontextprotocol/sdk@^1.29.0`) with two thin write forwarders — `create_order` and `create_customer` — over the existing core REST create endpoints. Every moving part already exists and is proven: the SSRF-safe `coreGet` forwarder, the `toToolError` problem+json mapper, the `IdempotencyService` reserve-first contract (V50), the `#206` `catalog:write` `@PreAuthorize` gate pattern with its `ScopedCatalogAccessIntegrationTest` converter-through-MockMvc proof, the `integration-catalog-ro` realm client, and the three-compose `INTEGRATION_CATALOG_RO_SECRET` secret-wiring. The phase is almost entirely a mirror-and-extend of `#206` on the auth side and Phase 20 on the MCP side. **No new npm or Gradle packages are required** — the write tools reuse `zod`/`pino`/`sdk`, and the Java side reuses Spring Security method-security already active via `@EnableMethodSecurity`.

There is **one non-obvious, load-bearing risk the CONTEXT.md decisions (D-01..D-12) do not cover**: `OrderService.createOrder` calls the VSA-02 shop-access gate `shopAccessService.require(shopId, SHOP_MANAGER)` at `OrderService.java:92` — a second application-layer authorization boundary *in addition to* the new `orders:write` scope gate. `CustomerService.createCustomer` has **no** such gate (tenant-scoped only). For the `integration-orders-rw` service-account (a Keycloak SA carries a **UUID** subject) to pass VSA-02 in the live E2E, it relies on being a *day-one implicit GROUP_ADMIN* while `strict-scoping` is OFF (the default). To avoid the SA silently accumulating a permanent opaque-UUID `GROUP_ADMIN` row in `shop_staff` on its first `create_order` (the exact pollution WR-09 built the allowlist to prevent), the plan should add `integration-orders-rw` to `ACCESS_MACHINE_CLIENT_IDS`. This interaction is fully analysed in §3 and §"Common Pitfalls" — it is the single most important thing the planner must get right.

**Primary recommendation:** Ship two plans — (25-01) core scope gates + realm/secret/config wiring + Java CI proofs; (25-02) MCP `corePost` + `create_order`/`create_customer` tools + vitest + docs reconcile + live E2E. Mirror `#206`/`ScopedCatalogAccessIntegrationTest` verbatim for the scope side; mirror `read-orders.ts` verbatim for the tool side; **allowlist the RW client in `ACCESS_MACHINE_CLIENT_IDS`** to keep the VSA-02 gate clean.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions (D-01..D-12 — do NOT re-litigate)

- **D-01:** Enforce write scopes in core now. `POST /api/v1/orders` gets `@PreAuthorize("hasAuthority('SCOPE_orders:write')")` (activating the reserved `orders:write` scope).
- **D-02** *(was Claude's discretion, resolved):* Introduce a **new `customers:write`** scope and enforce it on `POST /api/v1/customers`. Add `customers:read`/`customers:write` to the reserved taxonomy in `OpenApiConfig` doc strings (only `customers:write` enforced this phase).
- **D-03:** `core-api` client `defaultClientScopes` gains `orders:write` + `customers:write` (exactly how #206 added `catalog:write`), so every operator token transparently carries them. No new realm role; no negative/deny SpEL.
- **D-04:** Prove the gate in CI via the converter-through-MockMvc pattern (`ScopedCatalogAccessIntegrationTest` template): no-write-scope token → 403 on create; operator/write-scoped token → allowed. Send a **fully valid** request body so `@Valid` doesn't 400 and mask the authorization result.
- **D-05:** Write tools expose **`idempotencyKey` as a REQUIRED tool input** (Zod `z.string().min(1).max(64)`) and **always forward it as the `Idempotency-Key` header**. Tool description instructs the agent to reuse the same key on retry. No non-idempotent path exists.
- **D-06:** **No core change** to the idempotency mechanism — core's header stays `required=false`. Only the MCP tool layer makes the key mandatory. Core's 409/422 responses are already RFC 7807 and flow through `toToolError` unchanged.
- **D-07:** Exactly **`create_order`** + **`create_customer`** — snake_case verb_noun (the registered MCP tool *names*), consistent with the read slice.
- **D-08:** Tool `inputSchema` (raw Zod shape, **NOT** `z.object` — the SDK v1.29.0 contract) mirrors `CreateOrderRequest` / `CreateCustomerRequest`. Verify exact fields against `docs/api/openapi-snapshot.json` (source of truth).
- **D-09:** Ship a **new template-seeded** client **`integration-orders-rw`** — exact mirror of `integration-catalog-ro`'s wiring (service-accounts only, client-secret, cloned `tenant-id-mapper` + `core-api-audience-mapper`, SA user seeded with `tenant_id`). Do **not** extend `integration-catalog-ro`.
- **D-10:** RW client carries **`orders:write` + `customers:write` + `catalog:read`** (write + read-for-discovery). Pointedly **NOT** `catalog:write`. RLS walls cross-tenant.
- **D-11:** Secret via env placeholder **`${INTEGRATION_ORDERS_RW_SECRET}`** wired into all three compose renderers, both `.env.example` files, and `scripts/verify-env.sh`.
- **D-12:** Live E2E precondition: **rebuild ALL containers** + **realm re-import** (`kc.sh import --override true`). Tokens minted before the re-import are fail-closed. Playwright is **not** in CI — authorization gates proven via converter-through-MockMvc; live write E2E is a manual/scripted claim on the dev stack.

### Claude's Discretion
- Success-return shape (return created DTO as JSON text, never logged — DTOs carry PII), `corePost` error/timeout handling, and the `docs/metrics.json` MCP-vitest reconcile are mechanical — planner/executor's call, following the read-slice posture.
- The planner may refine D-02/D-10 wording but must preserve least-privilege intent (no `catalog:write` on the agent credential; separate customer/order write scopes).

### Deferred Ideas (OUT OF SCOPE)
- Order **state-transition** tools (`confirm_order`/`cancel_order`/`mark_order_ready`).
- Enforcing the reserved **`orders:read`** (and `customers:read`) on read endpoints.
- **Programmatic** per-tenant/per-agent RW client provisioning (needs the KC24 `tenant_id` managed-attribute change §5 + `KeycloakAdminClient` seam). This phase ships a **template-seeded** client only.
- `product.create` / any `catalog:write` MCP tool.
- MCP resource/prompt primitives beyond tools.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AI-02 | Mutating MCP tools (#204 wiring): extend the Phase-20 read-only MCP server with write tools (orders.create / customers.create) riding the uniform Idempotency-Key contract; tests: MCP write-tool integration test with idempotent replay + RLS-scoped proof under the MCP credential. | §1 `corePost` + tool registration (mirror `read-orders.ts`); §2 exact Zod schemas from `CreateOrderRequest`/`CreateCustomerRequest`; §3 `@PreAuthorize` scope gates + the VSA-02 interaction; §4 realm JSON diff; §5 idempotency semantics (`IdempotencyService.execute`, 1..64 key, 409/422 problem+json); §6 RLS + testing mechanics. |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Write-tool registration + Idempotency-Key mandate | MCP server (Node) | — | Thin forwarder; makes the key required at the tool layer (D-05), holds no auth state (`index.ts:33` builds per-request). |
| SSRF-safe POST forwarding | MCP server (`core-client.ts`) | — | Fixed `CORE_BASE_URL`, verbatim Bearer, no body/token logging — mirror `coreGet`. |
| Scope enforcement (`orders:write`/`customers:write`) | API / Backend (`@PreAuthorize`) | Keycloak (scope claim) | Method-security gate on the controller; Keycloak client scopes emit the `scope` claim → `SCOPE_*` authorities. |
| Shop-access gate (VSA-02) on `create_order` | API / Backend (`ShopAccessService`) | — | `OrderService.createOrder:92` requires `SHOP_MANAGER`; a second app-layer boundary the MCP credential must satisfy (see §3). |
| Idempotent replay (reserve-first store) | API / Backend (`IdempotencyService` + `idempotency_keys` V50) | Database (FORCE RLS) | Already wired on both endpoints; MCP just supplies the header. |
| Tenant isolation (cross-tenant deny) | Database (Postgres FORCE RLS) | — | The **sole** isolation boundary; MCP makes no tenant decision. |
| Error surfacing (RFC 7807 → sanitized) | MCP server (`errors.ts` `toToolError`) | API (`GlobalExceptionHandler`) | Core emits problem+json; MCP sanitizes to a tool error, never a stack. |
| Machine credential (`integration-orders-rw`) | Keycloak realm template | Compose/env secret | Template-seeded client-credentials client + SA `tenant_id`. |

## Standard Stack

**No new dependencies.** Every library needed already ships in the repo. Verified from `mcp-server/package.json`:

### Core (existing — reused, not added)
| Library | Version (declared) | Purpose | Why standard |
|---------|--------------------|---------|--------------|
| `@modelcontextprotocol/sdk` | `^1.29.0` | MCP server + Streamable HTTP transport, `registerTool` | Already the read-slice's SDK; raw-Zod-shape `inputSchema` contract is proven in `read-orders.ts:31`. `[VERIFIED: mcp-server/package.json:15]` |
| `zod` | `^4` | Tool input schemas | Read tools use it (`read-orders.ts:1`). `[VERIFIED: mcp-server/package.json:18]` |
| `pino` | `^10` | Structured logs (tool+status only, never body) | `read-orders.ts:24`. `[VERIFIED: mcp-server/package.json:17]` |
| `express` | `^5` | Stateless HTTP host | `index.ts`. `[VERIFIED: mcp-server/package.json:16]` |
| `vitest` | `^4` | Test runner (`vitest run`) | `mcp-server/package.json:10`. `[VERIFIED]` |
| Spring Security method-security | via Spring Boot 3.5.16 | `@PreAuthorize` gate | `@EnableMethodSecurity` already active. `[VERIFIED: SecurityConfig.java:30]` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Reusing `orders:write` for customer creation | (rejected) | D-02 chose a separate `customers:write` for finest-grained least-privilege — an order-only agent cannot mint customers. Locked. |
| Extending `integration-catalog-ro` to add write | (rejected) | D-09: would destroy the RO client's documented zero-blast-radius guarantee. Ship a new client. |

**Installation:** none. (If the executor runs `npm ci` in `mcp-server/`, that is dependency restore, not a new install.)

## Package Legitimacy Audit

**No external packages are installed by this phase.** All MCP dependencies (`@modelcontextprotocol/sdk`, `zod`, `pino`, `express`, `vitest`) already exist in `mcp-server/package.json` and are exercised by the shipped Phase-20 tests; the Java side adds no dependency (Spring Security method-security is already on the classpath and active). slopcheck/registry verification is **N/A** — there is nothing new to verify. If the planner later decides to add any package, the Package Legitimacy Gate must run first.

## Architecture Patterns

### System Architecture Diagram

```
  External AI agent
   │  (client-credentials token: integration-orders-rw
   │   → scope="orders:write customers:write catalog:read", aud=core-api, tenant_id=<uuid>)
   ▼
  POST /mcp  (Authorization: Bearer <token>)            mcp-server/src/index.ts
   │  strip "Bearer ", 401 if absent (index.ts:26-31)
   ▼
  buildServer(bearer)  ── per request ──                mcp-server/src/server.ts:15
   │   registers: create_order, create_customer (+ existing read tools)
   ▼
  tool handler (closes over bearer)                     tools/create-order.ts (NEW, mirror read-orders.ts)
   │   • validate args vs raw-Zod inputSchema (idempotencyKey required, min1/max64)
   │   • split idempotencyKey OUT of the body → header
   │   • build JSON body from remaining camelCase fields
   ▼
  corePost(path, bearer, body, {"Idempotency-Key": key})   core-client.ts (NEW sibling of coreGet)
   │   fixed CORE_BASE_URL http://core-java:9090 (SSRF), verbatim Bearer, 10s timeout, no body/token log
   ▼
  core:  POST /api/v1/orders  |  /api/v1/customers        Spring Boot 3.5.16
   │  ① @Valid body binding      (400 first if invalid  ← the ordering trap)
   │  ② @PreAuthorize SCOPE_orders:write | SCOPE_customers:write   (403 if scope missing)  ← NEW gate
   │  ③ @Idempotent → IdempotencyService.execute (reserve-first, GUC pin)   (409 in-flight / 422 body-mismatch)
   │  ④ OrderService.createOrder → shopAccessService.require(shopId, SHOP_MANAGER)  ← VSA-02 (orders ONLY)
   │     CustomerService.createCustomer → (no shop gate; tenant-scoped)
   │  ⑤ repository.save under Postgres FORCE RLS (tenant_id from GUC)   ← sole cross-tenant boundary
   ▼
  201 + DTO (OrderDto / CustomerDto)   OR   problem+json (400/403/404/409/422/500)
   │
   ▼  (non-2xx) toToolError(res)  →  sanitized MCP tool error (isError:true, no stack)   errors.ts:30
  agent receives: {content:[{type:text, text: JSON.stringify(dto)}]}  on success
```

File-to-implementation mapping is in the Component table below; the diagram shows only data flow.

### Recommended file layout (additive)
```
mcp-server/src/
├── core-client.ts            # ADD corePost() sibling to coreGet()
├── server.ts                 # ADD registerCreateOrder / registerCreateCustomer
├── tools/
│   ├── create-order.ts       # NEW — mirror read-orders.ts skeleton
│   ├── create-customer.ts    # NEW — mirror read-orders.ts skeleton
│   ├── create-order.test.ts  # NEW vitest
│   └── create-customer.test.ts # NEW vitest
core-java/src/main/java/uk/jtoye/core/
├── order/OrderController.java        # ADD @PreAuthorize("hasAuthority('SCOPE_orders:write')") on createOrder
├── customer/CustomerController.java  # ADD @PreAuthorize("hasAuthority('SCOPE_customers:write')") on create
└── config/OpenApiConfig.java         # update scope doc strings (orders:write/customers:write now enforced)
core-java/src/test/java/uk/jtoye/core/security/
└── ScopedOrdersCustomersAccessIntegrationTest.java  # NEW — mirror ScopedCatalogAccessIntegrationTest
infra/keycloak/realm-export.template.json  # ADD customers:read/write scopes + integration-orders-rw client + SA user; extend core-api defaultClientScopes
docker-compose.full-stack.yml, infra/docker-compose.yml, infra/docker-compose.hostnet.yml  # ADD INTEGRATION_ORDERS_RW_SECRET (env + envsubst allowlist)
.env.example, infra/.env.example, scripts/verify-env.sh  # ADD INTEGRATION_ORDERS_RW_SECRET
```

### Pattern 1: `corePost` — exact signature (§1)
**What:** POST sibling of `coreGet` (`core-client.ts:31`). Same fixed base URL (`CORE_BASE_URL` `core-client.ts:13`), same 10s `AbortSignal.timeout` (`core-client.ts:17`), same `{ ok, status, contentType, body }` return (`CoreResponse` `core-client.ts:19`). Adds `method:"POST"`, a JSON body, a `content-type: application/json` request header, and a caller-supplied extra-headers map for `Idempotency-Key`. **Never logs body or token** (DTOs carry PII — `core-client.ts:8`).
```typescript
// mcp-server/src/core-client.ts — ADD alongside coreGet (mirror its posture exactly)
export async function corePost(
  path: string,
  bearer: string,
  body: unknown,
  headers: Record<string, string> = {},
): Promise<CoreResponse> {
  const r = await fetch(`${CORE_BASE_URL}${path}`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${bearer}`,       // verbatim (core is the sole validator)
      accept: "application/json",
      "content-type": "application/json",
      ...headers,                                // e.g. { "Idempotency-Key": key }
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(CORE_TIMEOUT_MS),
  });
  const contentType = r.headers.get("content-type") ?? "";
  const respBody = contentType.includes("json")
    ? await r.json().catch(() => null)
    : await r.text();
  return { ok: r.ok, status: r.status, contentType, body: respBody };
}
```
`r.ok` is true for 201, so the success path (`res.ok`) returns the DTO and only non-2xx routes to `toToolError`. `[VERIFIED: core-client.ts:31-47, errors.ts:30]`

### Pattern 2: tool registration — raw-Zod-shape `inputSchema` (§1, D-08)
**What:** Mirror `registerReadOrders` (`read-orders.ts:90`) — a factory `createOrderHandler(bearer)` returning `async (args) => CallToolResult`, allow-listed constant path, pino `{tool,status}` logging, `catch → { ...isError:true }` "Core API unreachable" (`read-orders.ts:67-101`). The `inputSchema` is a **raw object of Zod validators** (NOT `z.object(...)`), exactly like `readOrdersInputSchema` (`read-orders.ts:31`). `idempotencyKey` is split out of the body and sent as the header:
```typescript
// tools/create-order.ts (NEW) — path is a fixed constant (SSRF, T-20-04 style)
const CREATE_ORDER_PATH = "/api/v1/orders";
export function createOrderHandler(bearer: string) {
  return async (args: CreateOrderArgs): Promise<CallToolResult> => {
    const { idempotencyKey, ...body } = args;              // key → header, never body
    try {
      const res = await corePost(CREATE_ORDER_PATH, bearer, body,
                                  { "Idempotency-Key": idempotencyKey });
      logger.info({ tool: "create_order", status: res.status }, "tool call"); // never body (PII)
      if (!res.ok) return toToolError(res);                // 403/404/409/422/500 → sanitized
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };  // 201 DTO
    } catch {
      logger.warn({ tool: "create_order" }, "core unreachable or timed out");
      return { content: [{ type: "text", text: "Core API unreachable or timed out" }], isError: true };
    }
  };
}
```
Register in `server.ts:15` `buildServer` alongside the read tools (`server.ts:21-23`). `[VERIFIED: read-orders.ts:67-101, server.ts:15-25]`

### Anti-Patterns to Avoid
- **`z.object(...)` for `inputSchema`** — the SDK v1.29.0 contract is the raw shape; `read-orders.ts:26` comments this explicitly. `[VERIFIED: read-orders.ts:26]`
- **Logging the response body or the Bearer** — order/customer DTOs carry `customerName/customerEmail/customerPhone`; the read-slice test asserts PII never reaches logs (`read-orders.test.ts:131-156`). Mirror that assertion.
- **Leaving `idempotencyKey` in the JSON body** — it is not a `CreateOrderRequest` field; strip it (Jackson would ignore it, but stripping keeps the `request_hash` clean and matches the wire contract).
- **A non-idempotent create path** — D-05: the key is *required*, so the tool cannot be used to mint a silent duplicate.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Idempotent replay / dedup | A new dedup table or in-MCP cache | `IdempotencyService.execute` (already on both endpoints) | Reserve-first, GUC-pinned, FORCE-RLS store (V50); 409/422 already RFC 7807. `[VERIFIED: IdempotencyService.java:101-162]` |
| problem+json → tool error mapping | A new error mapper | `toToolError` verbatim | Already maps 400/401/403/404/409/422/500/502. `[VERIFIED: errors.ts:16-47]` |
| Scope→authority conversion | A custom filter | `JwtRolesAndScopesConverter` (unchanged) | Emits `SCOPE_*` ∪ `ROLE_*`; the `@PreAuthorize` authority. `[VERIFIED: JwtRolesAndScopesConverter.java:38-52]` |
| Cross-tenant isolation | Any tenant check in MCP or the tool | Postgres FORCE RLS | RLS is the sole boundary; MCP picks no tenant. `[VERIFIED: 25-CONTEXT.md:9, read-orders.ts:18-23]` |
| Secret injection into the realm | Committed client secret | `${INTEGRATION_ORDERS_RW_SECRET}` envsubst placeholder | Mirror `INTEGRATION_CATALOG_RO_SECRET`. `[VERIFIED: realm template:966, verify-env.sh:47]` |

**Key insight:** This phase is a *mirror-and-extend*, not a build. Every hard problem (idempotency, RLS, error sanitisation, scope wiring) is already solved and tested; deviating from the shipped patterns re-introduces solved risk.

## Detailed Findings

### §1 — `corePost` signature and tool registration
Covered in Patterns 1 & 2 above. Confirmed: SDK `@^1.29.0` (`package.json:15`); `registerTool(name, {title, description, inputSchema}, handler)` with raw-Zod `inputSchema` (`read-orders.ts:91-101`); `buildServer(bearer)` is per-request (`index.ts:33-35`, `server.ts:15`). Tool success returns `{content:[{type:"text", text: JSON.stringify(dto)}]}`; non-2xx → `toToolError` (`read-orders.ts:75-76`).

### §2 — Exact request/response contracts (Zod schemas mirror the DTOs)

**`CreateOrderRequest`** `[VERIFIED: CreateOrderRequest.java:10-78, OrderItemRequest.java:8-30]`

| Field | Java type | Required? | Zod (raw shape) |
|-------|-----------|-----------|-----------------|
| `shopId` | `UUID` `@NotNull` | **required** | `z.string().uuid()` |
| `customerId` | `UUID` | optional | `z.string().uuid().optional()` |
| `customerName` | `String` | optional | `z.string().optional()` |
| `customerEmail` | `String` | optional | `z.string().email().optional()` |
| `customerPhone` | `String` | optional | `z.string().optional()` |
| `notes` | `String` | optional | `z.string().optional()` |
| `items` | `List<OrderItemRequest>` `@NotEmpty @Valid` | **required, non-empty** | `z.array(z.object({ productId: z.string().uuid(), quantity: z.number().int().min(1) })).min(1)` |
| `idempotencyKey` (tool-only, not a DTO field) | — | **required** (D-05) | `z.string().min(1).max(64)` |

`OrderItemRequest`: `productId` `UUID @NotNull`, `quantity` `Integer @NotNull @Min(1)`. `[VERIFIED: OrderItemRequest.java:9-14]`

**`CreateCustomerRequest`** (inner record in `CustomerController`) `[VERIFIED: CustomerController.java:156-161]`

| Field | Java constraint | Required? | Zod (raw shape) |
|-------|-----------------|-----------|-----------------|
| `name` | `@NotBlank @Size(max=255)` | **required** | `z.string().min(1).max(255)` |
| `email` | `@Email @NotBlank @Size(max=255)` | **required** | `z.string().email().max(255)` |
| `phone` | `@Size(max=50)` | optional | `z.string().max(50).optional()` |
| `allergenRestrictions` | `Integer` | optional | `z.number().int().optional()` |
| `idempotencyKey` (tool-only) | — | **required** (D-05) | `z.string().min(1).max(64)` |

**Responses:** both endpoints return **201** on first create with the DTO body:
- `OrderDto` (POJO with getters — Jackson serializes camelCase fields incl. `id`, `tenantId`, `shopId`, `orderNumber`, `status`, customer PII, `totalAmountPennies`, `vatRate`, etc.). `[VERIFIED: OrderDto.java:10-88]` Created order is `OrderStatus.DRAFT` (`OrderService.java:111`).
- `CustomerDto` (record: `id, tenantId, name, email, phone, allergenRestrictions, createdAt, updatedAt`). Customer `POST` also sets a `Location` header. `[VERIFIED: CustomerController.java:99-103, 141-150]`

**Field-naming note:** the tool *name* is snake_case (D-07) but the input *fields* are camelCase to match the JSON wire contract of the Java DTOs (the read tools already use camelCase `shopId`/`orderId` — `read-orders.ts:31-36`). `docs/api/openapi-snapshot.json` is the drift-gated source of truth; the planner/executor should diff the generated schema against these tables before finalizing. `[CITED: 25-CONTEXT.md:41,72]`

### §3 — Scope enforcement wiring (mirror #206) — and the VSA-02 interaction (CRITICAL)

**The `@PreAuthorize` annotations to add** (method-security is already active — `@EnableMethodSecurity` at `SecurityConfig.java:30`):
- `OrderController.createOrder` (`OrderController.java:64`): add `@PreAuthorize("hasAuthority('SCOPE_orders:write')")`. `[VERIFIED]`
- `CustomerController.create` (`CustomerController.java:78`): add `@PreAuthorize("hasAuthority('SCOPE_customers:write')")`. `[VERIFIED]`

Mirror `ProductController`'s nine sites exactly (`@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` at `ProductController.java:106,114,134,...`). `[VERIFIED]`

**Scope→authority path:** Keycloak client scopes with `include.in.token.scope=true` land in the `scope` claim; `JwtRolesAndScopesConverter` maps `scope → SCOPE_*` (∪ `realm_access.roles → ROLE_*`). No converter change needed. `[VERIFIED: JwtRolesAndScopesConverter.java:41-50, security-scopes.md:22-25]`

**`OpenApiConfig` doc update:** `orders:read/orders:write` are currently documented "reserved, not yet enforced" (`OpenApiConfig.java:61,115-116`). Update the doc strings so `orders:write` + `customers:write` read as **enforced**, and add `customers:read`/`customers:write` to the taxonomy (D-02). This is doc-string only. `[VERIFIED: OpenApiConfig.java:58-116]`

**The ordering trap (D-04, confirmed):** `@Valid` argument binding runs in `InvocableHandlerMethod.getMethodArgumentValues()` **before** the `@PreAuthorize` interceptor — an invalid body 400s (via `GlobalExceptionHandler`) and masks the 403. The CI 403 test **must** send a fully valid body. `[VERIFIED: ScopedCatalogAccessIntegrationTest.java:40-45,70-74; security-scopes.md:97-98]`

**⚠ VSA-02 shop-access gate on `create_order` — NOT in the CONTEXT decisions, load-bearing:**

`OrderService.createOrder` calls `shopAccessService.require(request.getShopId(), ShopRole.SHOP_MANAGER)` at **`OrderService.java:92`** — a *second* authorization boundary that runs **inside the service body**, after `@PreAuthorize`. `CustomerService.createCustomer` has **no** such gate (tenant-scoped only — `CustomerService.java:45-67`). `[VERIFIED]`

How the `integration-orders-rw` service-account (Keycloak SA → **UUID** subject) is treated by `ShopAccessService.require` → `isGroupAdmin()`:
1. `isRealmAdmin()`/`isInternalCaller()` → false (a machine token, `auth != null`). `[VERIFIED: ShopAccessService.java:230]`
2. `isDeclaredMachineClient(jwt)` → **false**: it returns false whenever the `sub` parses as a UUID (`ShopAccessService.java:579-580`) — a Keycloak SA *has* a UUID sub. So the allowlist short-circuit at `ShopAccessService.java:234` does **not** fire for it. `[VERIFIED]`
3. Falls to `isGroupAdminForUser(sub, false)` (`ShopAccessService.java:243`): with no `shop_staff` rows, `!strictScoping && perShopRole().isEmpty()` → **true** (day-one implicit GROUP_ADMIN) because `strict-scoping` defaults **OFF** (`application.yml:117`). So `require()` **passes**. `[VERIFIED: ShopAccessService.java:281-297]`

**Consequence:** `create_order` works out-of-the-box in dev (strict OFF) *whether or not* the client is allowlisted — the SA becomes an implicit GROUP_ADMIN. **But** if `integration-orders-rw` is **not** in `ACCESS_MACHINE_CLIENT_IDS`, its first write request runs `onRequest()`, which JIT-provisions a **permanent opaque-UUID `GROUP_ADMIN` row** into `shop_staff` and attempts a directory upsert (`ShopAccessService.java:529-543`) — the exact pollution WR-09's allowlist was built to prevent. Adding the client to `ACCESS_MACHINE_CLIENT_IDS` makes `onRequest()` skip both (`isAllowlistedMachineClient` at `ShopAccessService.java:498,603-606`), while `isGroupAdmin()` still returns true via the strict-OFF implicit path. `[VERIFIED]`

**Recommendation (planner MUST decide):** set `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` in the compose env (`application.yml:128` binds `${ACCESS_MACHINE_CLIENT_IDS:}`) so the RW credential's `create_order` E2E does not accumulate a JIT GROUP_ADMIN row. Forward-looking caveat: if `strict-scoping` is ever turned ON, an allowlisted UUID-sub SA becomes fully-ungranted → denied; it would then need an explicit `OPERATOR` `shop_staff` grant. Strict-scoping stays OFF this phase, so no action now — but record it. `[VERIFIED: ShopAccessService.java:281-297; application.yml:117,128]`

**CI test implication:** because the CI proof uses UUID-subject `jwt()` tokens under strict-OFF (implicit GROUP_ADMIN), the VSA-02 gate never masks the scope assertion — exactly the invariant `ScopedCatalogAccessIntegrationTest.java:85-91` documents for products. For the "operator-scoped → not 403" order case, a fully valid `CreateOrderRequest` referencing a non-existent shop/product will 404 downstream (`OrderService.java:100-102`), which still satisfies a `not403()` matcher (`ScopedCatalogAccessIntegrationTest.java:176-183`). To make it a *positive* 201 assertion instead, seed a real shop + product first.

**The `orders:write` / `customers:write` CI 403 test recipe** (mirror `ScopedCatalogAccessIntegrationTest`):
```java
// Fully valid order body — @Valid passes so @PreAuthorize (not @Valid) decides the outcome.
String VALID_ORDER_JSON =
  "{\"shopId\":\"" + UUID.randomUUID() + "\",\"items\":[{\"productId\":\""
   + UUID.randomUUID() + "\",\"quantity\":1}]}";
String VALID_CUSTOMER_JSON =
  "{\"name\":\"Ada\",\"email\":\"ada@example.com\"}";
// noWriteScopeJwt (scope="catalog:read" or none) → POST /api/v1/orders   → 403
// noWriteScopeJwt                                → POST /api/v1/customers → 403
// writeScopeJwt (scope="orders:write" / "customers:write") → not 403
// tokens carry a UUID subject + tenant_id claim + .authorities(new JwtRolesAndScopesConverter())
```
`[VERIFIED template: ScopedCatalogAccessIntegrationTest.java:52-183]`

### §4 — Realm template surgery (exact JSON additions)

All edits are in `infra/keycloak/realm-export.template.json` (the rendered `realm-export.json` is regenerated by envsubst at compose start — do **not** hand-edit it). Line anchors below. `[VERIFIED]`

**(a) New client scopes `customers:read` + `customers:write`** — mirror the existing `orders:read`/`orders:write` scope blocks (`realm-export.template.json:1036-1054`). Append two entries to `clientScopes[]` with the same shape (`include.in.token.scope:"true"`, `display.on.consent.screen:"false"`, empty `protocolMappers`). Note `customers:read` is *defined-only* this phase (only `customers:write` is enforced — D-02). `orders:read`/`orders:write` already exist (`realm-export.template.json:1037,1047`); no new orders scope needed. `[VERIFIED]`

**(b) New client `integration-orders-rw`** — clone the `integration-catalog-ro` block (`realm-export.template.json:958-1013`) verbatim, changing:
- `id` → a fresh UUID; `clientId` → `"integration-orders-rw"`; `name`/`description` → RW variant.
- `secret` → `"${INTEGRATION_ORDERS_RW_SECRET}"` (D-11).
- Keep `serviceAccountsEnabled:true`, `standardFlowEnabled:false`, `directAccessGrantsEnabled:false`, `publicClient:false`, `clientAuthenticatorType:"client-secret"` (`realm-export.template.json:965-976`).
- Keep the **two cloned protocol mappers** `tenant-id-mapper` (`:987`) + `core-api-audience-mapper` (`:1001`) — the audience mapper is mandatory (without `aud=core-api` the #88 `AudienceValidator` 401s before any scope check; `security-scopes.md:57-59`). Give each mapper a fresh `id`.
- `defaultClientScopes` → `[ "orders:write", "customers:write", "catalog:read", "web-origins", "acr", "roles", "profile", "email" ]` (D-10 — write + read-for-discovery; **no** `catalog:write`). Compare RO client's `[ "catalog:read", ... ]` (`:1012`). `[VERIFIED]`

**(c) New SA user `service-account-integration-orders-rw`** — clone the `service-account-integration-catalog-ro` user block (`realm-export.template.json:448-463`) into the `users[]` array, changing `id` → fresh UUID, `username` → `"service-account-integration-orders-rw"`, `serviceAccountClientId` → `"integration-orders-rw"`, keeping `attributes.tenant_id: ["00000000-0000-0000-0000-000000000001"]` (`:452-454`) so the RLS carrier claim is present (template import is not subject to the KC24 strip — §5 / `security-scopes.md:128-137`). `[VERIFIED]`

**(d) `core-api` default-grant (D-03)** — extend `core-api`'s `defaultClientScopes` (`realm-export.template.json:755`) from
`[ "web-origins","acr","roles","profile","email","catalog:read","catalog:write" ]` →
`[ ..., "catalog:read","catalog:write","orders:write","customers:write" ]`
so every operator/dashboard token transparently carries the two new write scopes after re-import (exactly how #206 added `catalog:write`). `[VERIFIED: realm-export.template.json:755; security-scopes.md:49-51]`

**Secret wiring (D-11) — 6 files** (mirror every `INTEGRATION_CATALOG_RO_SECRET` site):
| File:line | What to add |
|-----------|-------------|
| `docker-compose.full-stack.yml:54` (env) + `:67` (envsubst allowlist) | `INTEGRATION_ORDERS_RW_SECRET: ${INTEGRATION_ORDERS_RW_SECRET:?...}` and add `$$INTEGRATION_ORDERS_RW_SECRET` to the envsubst var list |
| `infra/docker-compose.yml:44` + `:53` | same |
| `infra/docker-compose.hostnet.yml:11` + `:20` | same |
| `.env.example:113` | `INTEGRATION_ORDERS_RW_SECRET=CHANGE_ME` + a comment line |
| `infra/.env.example:55` | `INTEGRATION_ORDERS_RW_SECRET=CHANGE_ME` |
| `scripts/verify-env.sh:47` | add `INTEGRATION_ORDERS_RW_SECRET` to the required array |
`[VERIFIED: all six sites confirmed via grep]`

**Also (VSA-02, §3):** add `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` to the compose env for `core-java` (binds `application.yml:128`). If `catalog-ro` should also be excluded from JIT pollution, use a comma-list. `[VERIFIED: application.yml:128]`

### §5 — Idempotency semantics at the boundary
`IdempotencyService.execute(endpoint, key, requestBody, dtoClass, work)` (`IdempotencyService.java:101`). Confirmed:
- **Key length 1..64** → `MAX_KEY_LENGTH=64` (`IdempotencyService.java:72,107-109`); drives the Zod bound `.min(1).max(64)` (D-05). A blank/>64 key throws `IllegalArgumentException` → 400. `[VERIFIED]`
- **In-flight (409)** `IdempotencyConflictException` when `response_status` is NULL (`IdempotencyService.java:144-148`); **same-key/different-body (422)** `IdempotencyPayloadMismatchException` (`:150-155`). Both are RFC 7807 problem+json via `GlobalExceptionHandler` and flow through `toToolError` unchanged (`errors.ts:16-22` has generic fallbacks for 409/422; the problem+json branch at `errors.ts:38-40` surfaces the title/detail). `[VERIFIED]`
- **Replay** returns the stored status + deserialized original DTO (`IdempotencyService.java:157-161`) — for creates that is 201 + the original order/customer, never a duplicate row. `[VERIFIED]`
- **No core change (D-06):** both controllers already accept an optional `Idempotency-Key` header and route through `execute` (`OrderController.java:70-78`, `CustomerController.java:83-96`). The MCP tool simply *always* supplies the header. `[VERIFIED]`

**Outbox check (the `outbox_flusher_dispatch_trap` does NOT apply):** `OrderService.createOrder` (`OrderService.java:89-177`) performs `orderRepository.save` and returns — it emits **no** outbox event. The only `eventPublisher.publishStateChange` call in the file is at `OrderService.java:424`, inside a *state-transition* method (not `createOrder`), and it publishes an already-dispatched `ORDER_STATE_CHANGED` event — pre-existing, not new to this phase. `CustomerService.createCustomer` (`CustomerService.java:45-67`) does no AMQP/outbox publish at all. Confirmed: this phase adds **no new outbox event type**, so the dispatch trap is out of scope. `[VERIFIED — matches 25-CONTEXT.md:123]`

### §6 — Testing + RLS proof mechanics

**MCP vitest** — the suite lives at `mcp-server/src/**/*.test.ts` (6 files, 27 `it/test` blocks currently — `metrics.json:12-13`). `docs-freshness.sh` counts the family via regex `^mcp-server/(src|test)/.*\.(test|spec)\.ts$` matching `\b(it|test)\(` (`docs-freshness.sh:62-65`), writing `mcp_test_blocks`/`mcp_test_files` (`:81-82`). Adding `create-order.test.ts` + `create-customer.test.ts` bumps both counts; reconcile at the phase gate with `scripts/docs-freshness.sh --write` (the `--write` run is the arbiter) then update the prose counts in `CLAUDE.md`/`AGENTS.md`. Mirror `read-orders.test.ts` structure: mock `corePost` (`vi.mock("../core-client.js")`), assert path/body/header forwarding, assert `toToolError` delegation on 403/404/409/422, and assert **PII never reaches logs** (`read-orders.test.ts:131-156`). Also add a corePost unit test mirroring `core-client.test.ts` (assert `content-type` + `Idempotency-Key` header + verbatim Bearer + no token in thrown errors). `[VERIFIED]`

**Java scope-gate CI proof** — new `ScopedOrdersCustomersAccessIntegrationTest` mirroring `ScopedCatalogAccessIntegrationTest` (converter-through-MockMvc on Testcontainers Postgres): no-write-scope → 403; write-scope → not-403 (or 201 with a seeded shop+product). UUID-subject tokens + `tenant_id` claim + `.authorities(new JwtRolesAndScopesConverter())`, fully valid bodies (§3). `[VERIFIED template: ScopedCatalogAccessIntegrationTest.java]`

**Cross-tenant RLS proof (AC-2) — superuser Testcontainers CANNOT prove FORCE RLS.** The Testcontainers bootstrap role is a SUPERUSER and bypasses even FORCE RLS (`idempotency.md:63-67`), so a real proof needs the NOSUPERUSER/NOBYPASSRLS `rls_test_role` downgrade. Rich precedent exists to mirror: `IdempotencyKeysRlsPolicyIntegrationTest`, `MediaAssetRlsPolicyIntegrationTest`, `ShopStaffRlsPolicyIntegrationTest`, `MultiTenantIsolationIntegrationTest`. **The write-side cross-tenant vector is `create_order` with a foreign `shopId`**: under a tenant-A GUC, `shopRepository.findById(tenantB_shop)` is RLS-hidden → `ResourceNotFoundException` → 404/empty (`OrderService.java:100-102`). Prove it under the downgraded role. `create_customer` has no "target another tenant" vector (the tenant is implicit from the token's GUC), so its RLS proof is the tenant-scoped write landing only under the caller's `tenant_id`. The live E2E (D-12) is the two-real-tokens boundary claim; the NOSUPERUSER Java test is the CI-runnable proof. `[VERIFIED]`

## Runtime State Inventory

This phase is additive, but it registers/mutates real runtime state beyond source files:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `shop_staff` may gain a JIT `GROUP_ADMIN` row for the RW SA's UUID sub on its first `create_order` **unless** the client is allowlisted (§3). `idempotency_keys` (V50) gains one row per keyed create — pruned by `ScheduledCleanupService` (`idempotency.md:138-143`). | Add `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` to avoid the `shop_staff` pollution. No migration. |
| Live service config | Keycloak realm `jtoye-dev`: new client scopes (`customers:read/write`), new client `integration-orders-rw`, new SA user, extended `core-api` default-grant. These live in the running IdP DB, not just git — **require re-import** to take effect (`kc.sh import --override true`; `start-dev --import-realm` will NOT overwrite an existing realm). | D-12 realm re-import + rebuild ALL containers. |
| OS-registered state | None. | None — verified: no OS-level task/registration touched. |
| Secrets/env vars | New `INTEGRATION_ORDERS_RW_SECRET` (6 wiring sites, §4). New config key `ACCESS_MACHINE_CLIENT_IDS` (not a secret; `application.yml:128`). | Wire the secret into 3 composes + 2 `.env.example` + `verify-env.sh`; set both in local `.env` before E2E. |
| Build artifacts | `docs/api/openapi-snapshot.json` (oasdiff-gated) — the two create ops gain no path change but the doc-string/security metadata may shift; `docs/metrics.json` counts shift (new vitest + Java tests). | Run `./gradlew :core-java:updateOpenApiSnapshot` if the snapshot diffs; run `docs-freshness.sh --write`. |

**Fail-closed posture:** tokens minted before the re-import lack the new scopes and 403 on writes until re-login — asserted as a deliberate contract, mirroring `#87/#88` and `ScopedCatalogAccessIntegrationTest.noScopeTokenForbiddenOnCreate`. `[VERIFIED: security-scopes.md:118-124]`

## Common Pitfalls

### Pitfall 1: `create_order` silently accumulates a JIT GROUP_ADMIN row (VSA-02)
**What goes wrong:** The RW SA (UUID sub) is not allowlisted, so its first `create_order` JIT-provisions a permanent opaque-UUID `GROUP_ADMIN` row in `shop_staff` and does a directory upsert.
**Why:** `OrderService.createOrder:92` → `require(shopId, SHOP_MANAGER)` → `onRequest()` JIT-provisions for any non-allowlisted UUID-sub caller under strict-OFF (`ShopAccessService.java:529-543`).
**How to avoid:** set `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` (`application.yml:128`, `ShopAccessService.java:498`).
**Warning signs:** an opaque UUID with no directory entry appears in the staff list after the E2E.

### Pitfall 2: `@Valid` masks the 403 in the CI scope test
**What goes wrong:** An incomplete order/customer body 400s before `@PreAuthorize` runs, so the test never proves the scope gate.
**Why:** `@Valid` binding precedes the method-security interceptor.
**How to avoid:** send fully valid `CreateOrderRequest`/`CreateCustomerRequest` bodies (§3 recipe).
**Warning signs:** the "no-scope → 403" test observes 400. `[VERIFIED: ScopedCatalogAccessIntegrationTest.java:40-45]`

### Pitfall 3: Missing `core-api-audience-mapper` on the new client → 401 before any scope check
**What goes wrong:** The RW token has the right scopes but 401s at decode.
**Why:** The #88 `AudienceValidator` rejects any token lacking `aud=core-api`.
**How to avoid:** clone BOTH mappers (`tenant-id-mapper` + `core-api-audience-mapper`) onto `integration-orders-rw`. `[VERIFIED: security-scopes.md:57-59; realm-export.template.json:1001-1010]`

### Pitfall 4: Superuser Testcontainers falsely "passes" a cross-tenant create
**What goes wrong:** A cross-tenant `create_order` appears to succeed in a normal Testcontainers test.
**Why:** The bootstrap role is SUPERUSER and bypasses FORCE RLS.
**How to avoid:** prove AC-2 under the NOSUPERUSER `rls_test_role` downgrade (mirror `IdempotencyKeysRlsPolicyIntegrationTest`). `[VERIFIED: idempotency.md:63-67]`

### Pitfall 5: Forgetting the realm re-import / rebuild before E2E
**What goes wrong:** The live `create_order`/`create_customer` E2E 403s or the client doesn't exist.
**Why:** `start-dev --import-realm` won't overwrite an existing `jtoye-dev`; new scopes/client only exist post-`kc.sh import --override true`; containers must be rebuilt for the new controller annotations + config.
**How to avoid:** D-12 sequence — rebuild ALL containers, then `kc.sh import --override true`, then re-mint tokens. `[VERIFIED: security-scopes.md:106-124]`

## Code Examples

### Registering the write tools in `buildServer`
```typescript
// mcp-server/src/server.ts — mirror the read-tool registrations (server.ts:21-23)
import { registerCreateOrder } from "./tools/create-order.js";
import { registerCreateCustomer } from "./tools/create-customer.js";
// inside buildServer(bearer):
registerCreateOrder(server, bearer);
registerCreateCustomer(server, bearer);
```
`[Source: server.ts:15-25, read-orders.ts:90-102]`

### `create_customer` input schema (raw Zod shape, D-08)
```typescript
export const createCustomerInputSchema = {
  name: z.string().min(1).max(255).describe("Customer full name"),
  email: z.string().email().max(255).describe("Customer email (unique per tenant)"),
  phone: z.string().max(50).optional().describe("Customer phone (optional)"),
  allergenRestrictions: z.number().int().optional().describe("Allergen bitmask (optional)"),
  idempotencyKey: z.string().min(1).max(64)
    .describe("Reuse the SAME key when retrying — a replay returns the original customer, never a duplicate."),
};
```
`[Source: CustomerController.java:156-161; IdempotencyService.java:72,107]`

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `orders:*` scopes defined but unenforced (reserved taxonomy) | `orders:write` + new `customers:write` enforced via `@PreAuthorize` | This phase (D-01/D-02) | Discharges the `security-scopes.md §6` "feeds MCP auth model" mandate. |
| MCP server read-only (writes 403 by design) | Read + create tools under the RW credential | This phase | `mcp-server/README.md` "write is denied 403" note flips to "write allowed under the RW credential". `[CITED: 25-CONTEXT.md:65]` |

**Deprecated/outdated:** none relevant. `OpenApiConfig` "orders:* reserved, not yet enforced" doc strings become stale and must be updated (§3).

## Validation Architecture

*(nyquist_validation = true in `.planning/config.json`; VALIDATION.md derives from this section.)*

### Test Framework
| Property | Value |
|----------|-------|
| Framework (MCP) | vitest `^4` — `vitest run` (`mcp-server/package.json:10`) |
| Framework (core) | JUnit 5 + Testcontainers 1.21.3 + Spring Boot Test (MockMvc) |
| Config file (MCP) | `mcp-server/` (vitest zero-config; tsc build via `npm run build`) |
| Quick run command (MCP) | `cd mcp-server && npm run test` |
| Quick run command (core scope test) | `./gradlew :core-java:test --tests '*ScopedOrdersCustomersAccessIntegrationTest'` |
| Full suite command (core) | `./gradlew :core-java:integrationTest` (Testcontainers) + `:core-java:test` |
| Docs gate | `scripts/docs-freshness.sh` (check) / `--write` (reconcile) |

### Phase Requirements → Test Map
| Req / AC | Behavior | Test Type | Automated Command | File Exists? |
|----------|----------|-----------|-------------------|-------------|
| AC-1 (idempotent replay) | Same `idempotencyKey` replays original DTO, no duplicate | integration (Testcontainers) | `./gradlew :core-java:integrationTest --tests '*IdempotencyKeysRls*'` (existing) + a new order/customer replay test | ❌ Wave 0 (order/customer replay via MCP-forwarded header) |
| AC-1 (tool forwards required key) | Tool sends `Idempotency-Key` header, splits it out of body | unit (vitest) | `cd mcp-server && npm run test` | ❌ Wave 0 (`create-order.test.ts`, `create-customer.test.ts`) |
| AC-2 (cross-tenant deny) | tenant-A RW token → tenant-B `shopId` create → 404/empty | integration NOSUPERUSER | `./gradlew :core-java:integrationTest --tests '*CrossTenant*'` | ❌ Wave 0 (mirror `IdempotencyKeysRlsPolicyIntegrationTest`) |
| AC-1 (scope gate) | no-`orders:write` → 403; `orders:write` → not-403 | integration MockMvc | `./gradlew :core-java:test --tests '*ScopedOrdersCustomers*'` | ❌ Wave 0 (mirror `ScopedCatalogAccessIntegrationTest`) |
| AC-3 (RFC 7807 surfacing) | 409/422/403/404 problem+json → sanitized tool error, no stack | unit (vitest) | `cd mcp-server && npm run test` | ❌ Wave 0 (assert `toToolError` delegation, PII-never-logged) |
| AC-3 (live) | Live create → replay → cross-tenant 404 on the rebuilt stack | manual/scripted E2E | curl recipe (README §5 inverted, `25-CONTEXT.md:121`) | N/A (not in CI, D-12) |

### Sampling Rate
- **Per task commit:** `cd mcp-server && npm run build && npm run test` (tool changes); `./gradlew :core-java:test --tests '*ScopedOrdersCustomers*'` (scope change).
- **Per wave merge:** `./gradlew :core-java:integrationTest` (RLS + idempotency + scope) green.
- **Phase gate:** full `:core-java:integrationTest` + `:core-java:test` + MCP vitest green; `docs-freshness.sh --write` EXIT 0; then live E2E (D-12) + `/gsd:verify-work`.

### Wave 0 Gaps
- [ ] `mcp-server/src/tools/create-order.test.ts` — header/body forwarding, `toToolError` delegation, PII-never-logged (mirror `read-orders.test.ts`).
- [ ] `mcp-server/src/tools/create-customer.test.ts` — same.
- [ ] `mcp-server/src/core-client.test.ts` — extend for `corePost` (content-type + Idempotency-Key header + verbatim Bearer + token-never-in-error).
- [ ] `core-java/.../security/ScopedOrdersCustomersAccessIntegrationTest.java` — 403/not-403 scope proof (converter-through-MockMvc, valid bodies).
- [ ] A cross-tenant `create_order` NOSUPERUSER RLS proof — mirror `IdempotencyKeysRlsPolicyIntegrationTest` / `MultiTenantIsolationIntegrationTest`.
- [ ] An idempotent-replay integration test for `orders.create` / `customers.create` (may reuse existing `IdempotencyService` coverage; confirm order/customer replay is covered).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Node.js + `mcp-server` deps | MCP tool build/test | ✓ (Phase 20 shipped) | Node 20+, sdk ^1.29.0 | — |
| JDK 21 + Gradle 8.10 wrapper | Java build/test | ✓ | project standard | — |
| Docker + Compose | rebuild ALL + live E2E | ✓ (canonical local runtime) | Compose 1.40+ | — |
| Keycloak `jtoye-dev` realm | scope/client auth | ✓ (KC 24.0.5) | re-import required (D-12) | — |
| Testcontainers Postgres 15 | RLS + scope integration tests | ✓ | 15 | — |
| `INTEGRATION_ORDERS_RW_SECRET` env | render RW client | ✗ (new) | — | must be set (verify-env.sh fails loud) |
| `ACCESS_MACHINE_CLIENT_IDS` env | clean VSA-02 (Pitfall 1) | ✗ (new, optional) | — | works without it but pollutes shop_staff |

**Missing dependencies with no fallback:** `INTEGRATION_ORDERS_RW_SECRET` (blocks realm render + live E2E; `verify-env.sh` enforces).
**Missing dependencies with fallback:** `ACCESS_MACHINE_CLIENT_IDS` (functional without it, but leaves a JIT GROUP_ADMIN row — set it).

## Security Domain

*(security_enforcement absent from config → enabled.)*

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V1 Architecture | yes | Thin forwarder; RLS is the sole tenant boundary; MCP holds no auth state (`index.ts:33`). |
| V2 Authentication | yes | Keycloak client-credentials; verbatim Bearer forward; core is the sole validator. |
| V4 Access Control | yes | `@PreAuthorize("SCOPE_orders:write" / "SCOPE_customers:write")` least-privilege scopes (D-01/D-02); VSA-02 shop gate on orders (§3); FORCE RLS cross-tenant. |
| V5 Input Validation | yes | Zod tool schemas (raw shape) mirror `@Valid` DTO constraints; UUID-typed ids; SSRF-safe allow-listed path constants (`corePost` fixed base URL). |
| V7 Error Handling & Logging | yes | `toToolError` sanitizes problem+json (no stack); tool logs status only, **never** body/token (PII). `[VERIFIED: errors.ts, read-orders.test.ts:131-156]` |
| V6 Cryptography | no (n/a) | No crypto introduced; realm secret via envsubst placeholder, never committed. |

### Known Threat Patterns for MCP-write + Spring + Keycloak + RLS
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Cross-tenant write via forged/foreign id | Tampering / Info-disclosure | Postgres FORCE RLS; NOSUPERUSER-proven (Pitfall 4). |
| Over-privileged agent credential | Elevation of Privilege | RW client carries write + `catalog:read` only, **no** `catalog:write` (D-10). |
| Duplicate/replayed mutation | Tampering | Required `idempotencyKey` → reserve-first store (D-05). |
| Missing audience → token accepted for wrong service | Spoofing | `core-api-audience-mapper` mandatory (Pitfall 3). |
| PII leak via logs/errors | Info-disclosure | tool logs status only; `toToolError` never forwards raw body/stack. |
| SSRF via caller-controlled path | Tampering | `corePost` uses fixed `CORE_BASE_URL` + allow-listed constant paths (mirror `coreGet`). |
| Stale token retains removed access | — (fail-closed) | Pre-re-import tokens 403 on writes (asserted contract). |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The OpenAPI snapshot field names/required-ness match the Java DTO records exactly (camelCase, `items[]` of `{productId,quantity}`). | §2 | Low — DTOs are the source; the executor must still diff `docs/api/openapi-snapshot.json` per D-08 before finalizing schemas. |
| A2 | The `not403()` operator-scope order test passes because a non-existent shop 404s (not 403) downstream — OR the planner seeds a shop+product for a 201. | §3 | Low — either outcome satisfies "gate passed"; seeding is the stronger positive assertion. |
| A3 | Setting `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` is sufficient to prevent JIT `shop_staff` pollution while `create_order` still passes VSA-02 (implicit GA under strict-OFF). | §3 | Low — verified against `ShopAccessService.java:281-297,498,603-606`; the only residual is a future strict-scoping flip (out of scope, recorded). |
| A4 | `docs/api/openapi-snapshot.json` gains no new *path* (both create endpoints already exist), only metadata/security changes; `updateOpenApiSnapshot` may still be needed if security scheme docs shift. | Runtime State Inventory | Low — snapshot regen is a mechanical phase-gate step already used in Phases 22–24. |

## Open Questions

1. **Does the planner want a *positive* 201 order-create CI test (seed shop+product) or the minimal `not403()` assertion?**
   - What we know: `ScopedCatalogAccessIntegrationTest` uses `not403()`; seeding gives a stronger proof.
   - What's unclear: whether the extra fixture cost is worth it here.
   - Recommendation: minimal `not403()` for the scope gate (mirrors #206); prove the full happy-path 201 + replay in the dedicated idempotency/RLS integration test and the live E2E.

2. **Should `create_customer` get its own cross-tenant Java test even though it has no foreign-id vector?**
   - What we know: customers are tenant-implicit (GUC); there is no "target tenant B" input.
   - Recommendation: prove `create_order`/foreign-`shopId` for the write-side cross-tenant AC-2; for customers, a NOSUPERUSER test asserting the created row lands only under the caller's tenant is sufficient (optional — the read tools + `IdempotencyKeysRls` already prove the customer-PII RLS boundary).

3. **Is `customers:read` needed on any client this phase?** D-10 grants the RW client `catalog:read` (for product discovery), not `customers:read`. Defined-only in the taxonomy (D-02). Recommendation: define the scope in `clientScopes[]` for symmetry, grant it to nobody, enforce nothing — exactly the `orders:read` posture.

## Sources

### Primary (HIGH confidence — codebase file:line, verified this session)
- `mcp-server/src/core-client.ts:13-47`, `server.ts:15-25`, `index.ts:26-49`, `tools/read-orders.ts:26-102`, `errors.ts:16-47`, `tools/read-orders.test.ts:131-181`, `core-client.test.ts:30-66`, `package.json:15-25`
- `core-java/.../order/OrderController.java:61-79`, `customer/CustomerController.java:70-168`, `order/dto/CreateOrderRequest.java`, `order/dto/OrderItemRequest.java`, `order/dto/OrderDto.java`, `order/OrderService.java:89-177,415-424`, `customer/CustomerService.java:45-67`
- `core-java/.../common/idempotency/IdempotencyService.java:72,101-162`, `docs/idempotency.md:14-143`
- `core-java/.../security/JwtRolesAndScopesConverter.java:38-52`, `security/access/ShopAccessService.java:166-181,229-297,470-543,578-640`, `security/SecurityConfig.java:30`, `config/OpenApiConfig.java:58-116`, `product/ProductController.java:106-232`, `resources/application.yml:100-131`
- `core-java/.../test/.../security/ScopedCatalogAccessIntegrationTest.java` (full), `docs/security-scopes.md` (full)
- `infra/keycloak/realm-export.template.json:448-463,662-757,958-1055`
- `docker-compose.full-stack.yml:54,67`, `infra/docker-compose.yml:44,53`, `infra/docker-compose.hostnet.yml:11,20`, `.env.example:108-114`, `infra/.env.example:55`, `scripts/verify-env.sh:38-48`
- `scripts/docs-freshness.sh:62-82`, `docs/metrics.json:12-14`, `.planning/config.json`
- RLS test templates: `IdempotencyKeysRlsPolicyIntegrationTest.java`, `MediaAssetRlsPolicyIntegrationTest.java`, `MultiTenantIsolationIntegrationTest.java` (existence + pattern verified)

### Secondary
- `.planning/phases/25-mutating-mcp-tools/25-CONTEXT.md` (locked decisions), `.planning/REQUIREMENTS.md` (AI-02), `.planning/STATE.md` (Phase 20/23/24 history)

### Tertiary (LOW confidence)
- None — every claim in this document is grounded in a repository artifact.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new packages; all deps present and exercised by shipped tests.
- Architecture / patterns: HIGH — direct mirror of `read-orders.ts` (MCP) and `ScopedCatalogAccessIntegrationTest`/#206 (auth), both shipped and green.
- DTO contracts: HIGH — read straight from the Java records/POJOs; executor should still diff the OpenAPI snapshot (A1).
- VSA-02 interaction: HIGH — traced through `ShopAccessService` line by line; this is the one finding CONTEXT.md omits and the planner must act on.
- Pitfalls / RLS mechanics: HIGH — verified against existing NOSUPERUSER test templates and the idempotency doc.

**Research date:** 2026-07-24
**Valid until:** 2026-08-23 (stable — brownfield extension of shipped, gated subsystems; realm/scope/idempotency contracts are locked)

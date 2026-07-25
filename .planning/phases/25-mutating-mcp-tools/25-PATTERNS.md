# Phase 25: Mutating MCP Tools - Pattern Map

**Mapped:** 2026-07-24
**Files analyzed:** 21 (new + modified)
**Analogs found:** 21 / 21 (every file is a mirror-and-extend of a shipped, green sibling)

> This phase is a **mirror-and-extend**, not a build. The MCP side mirrors the Phase-20
> read slice (`read-orders.ts` / `core-client.ts`); the auth side mirrors #206
> (`ProductController` `@PreAuthorize` + `ScopedCatalogAccessIntegrationTest` +
> `integration-catalog-ro` realm client). Every excerpt below is the exact analog the
> executor copies. **Read the Shared Patterns → VSA-02 note before planning `create_order`.**

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `mcp-server/src/tools/create-order.ts` (NEW) | tool (MCP) | request-response (write forwarder) | `mcp-server/src/tools/read-orders.ts` | role-match (read→write) |
| `mcp-server/src/tools/create-customer.ts` (NEW) | tool (MCP) | request-response (write forwarder) | `mcp-server/src/tools/read-orders.ts` | role-match (read→write) |
| `mcp-server/src/core-client.ts` (MODIFY: add `corePost`) | client/utility | request-response (POST) | its own `coreGet` (same file) | exact (sibling method) |
| `mcp-server/src/server.ts` (MODIFY: register 2 tools) | provider/factory | wiring | `registerReadOrders` call block | exact |
| `mcp-server/src/tools/create-order.test.ts` (NEW) | test (vitest) | — | `mcp-server/src/tools/read-orders.test.ts` | exact |
| `mcp-server/src/tools/create-customer.test.ts` (NEW) | test (vitest) | — | `mcp-server/src/tools/read-orders.test.ts` | exact |
| `mcp-server/src/core-client.test.ts` (MODIFY: `corePost` cases) | test (vitest) | — | its own `coreGet` cases (same file) | exact |
| `core-java/.../order/OrderController.java` (MODIFY: `@PreAuthorize`) | controller | request-response | `ProductController` create site (L134) | exact (annotation copy) |
| `core-java/.../customer/CustomerController.java` (MODIFY: `@PreAuthorize`) | controller | request-response | `ProductController` create site (L134) | exact (annotation copy) |
| `core-java/.../config/OpenApiConfig.java` (MODIFY: scope docs) | config | — | its own `catalog:*`/`orders:*` scope block | exact |
| `core-java/.../security/ScopedOrdersCustomersAccessIntegrationTest.java` (NEW) | test (integration) | request-response (MockMvc) | `ScopedCatalogAccessIntegrationTest.java` | exact |
| cross-tenant `create_order` RLS test (NEW) | test (integration, NOSUPERUSER) | CRUD under FORCE RLS | `IdempotencyKeysRlsPolicyIntegrationTest` / `MultiTenantIsolationIntegrationTest` | role-match |
| `infra/keycloak/realm-export.template.json` (MODIFY) | config (realm) | — | `integration-catalog-ro` client block (L958-1013) | exact (clone) |
| `docker-compose.full-stack.yml` / `infra/docker-compose.yml` / `infra/docker-compose.hostnet.yml` (MODIFY) | config | — | `INTEGRATION_CATALOG_RO_SECRET` env+envsubst sites | exact |
| `.env.example` / `infra/.env.example` / `scripts/verify-env.sh` (MODIFY) | config | — | `INTEGRATION_CATALOG_RO_SECRET` sites | exact |
| `docs/security-scopes.md` / `docs/idempotency.md` / `mcp-server/README.md` / `docs/metrics.json` (MODIFY) | docs | — | existing prose + `docs-freshness.sh --write` | N/A (doc reconcile) |

---

## Pattern Assignments

### `mcp-server/src/tools/create-order.ts` + `create-customer.ts` (tool, request-response write forwarder)

**Analog:** `mcp-server/src/tools/read-orders.ts` (mirror the skeleton verbatim; swap `coreGet`→`corePost`, add the `idempotencyKey`-split).

**Imports pattern** (read-orders.ts:1-6):
```typescript
import { z } from "zod";
import pino from "pino";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { coreGet } from "../core-client.js";   // → import { corePost } from "../core-client.js";
import { toToolError } from "../errors.js";
const logger = pino({ name: "jtoye-mcp" });
```

**Raw-Zod inputSchema pattern** (read-orders.ts:26-36) — copy the "NOT z.object" comment; it is the SDK v1.29.0 contract (D-08):
```typescript
// Raw Zod shape (NOT z.object) — the @modelcontextprotocol/sdk v1.29.0 contract.
export const readOrdersInputSchema = {
  page: z.number().int().min(0).optional().describe("..."),
  size: z.number().int().min(1).max(100).optional().describe("..."),
  shopId: z.string().uuid().optional().describe("scope the order list to one shop (UUID)"),
  orderId: z.string().uuid().optional().describe("fetch a single order's detail (UUID)"),
};
```
New tool schemas (mirror the DTO field tables in RESEARCH §2 — **diff against `docs/api/openapi-snapshot.json` first, D-08**):
```typescript
// create-order.ts — mirrors CreateOrderRequest (getters) + OrderItemRequest
export const createOrderInputSchema = {
  shopId: z.string().uuid().describe("Target shop (UUID, required)"),
  customerId: z.string().uuid().optional(),
  customerName: z.string().optional(),
  customerEmail: z.string().email().optional(),
  customerPhone: z.string().optional(),
  notes: z.string().optional(),
  items: z.array(z.object({ productId: z.string().uuid(), quantity: z.number().int().min(1) })).min(1),
  idempotencyKey: z.string().min(1).max(64)   // tool-only, NOT a DTO field — split to header
    .describe("Reuse the SAME key when retrying — a replay returns the original order, never a duplicate."),
};
// create-customer.ts — mirrors CustomerController.CreateCustomerRequest (record, L156-161)
export const createCustomerInputSchema = {
  name: z.string().min(1).max(255).describe("Customer full name"),
  email: z.string().email().max(255).describe("Customer email (unique per tenant)"),
  phone: z.string().max(50).optional(),
  allergenRestrictions: z.number().int().optional(),
  idempotencyKey: z.string().min(1).max(64).describe("Reuse the SAME key when retrying — replay returns the original customer."),
};
```

**Core handler + error/PII pattern** (read-orders.ts:67-87) — the factory-returning-handler, `logger.info({tool,status})` (NEVER body — PII), `!res.ok → toToolError`, `catch → sanitized isError`:
```typescript
export function readOrdersHandler(bearer: string) {
  return async (args: ReadOrdersArgs): Promise<CallToolResult> => {
    const path = buildPath(args);
    try {
      const res = await coreGet(path, bearer);
      logger.info({ tool: "read_orders", status: res.status }, "tool call");  // status only, never body (PII)
      if (!res.ok) return toToolError(res);
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };
    } catch {
      logger.warn({ tool: "read_orders" }, "core unreachable or timed out");
      return { content: [{ type: "text", text: "Core API unreachable or timed out" }], isError: true };
    }
  };
}
```
Write-tool delta (RESEARCH §Pattern 2): split `const { idempotencyKey, ...body } = args;`, call
`corePost(CREATE_ORDER_PATH, bearer, body, { "Idempotency-Key": idempotencyKey })`, path is a **fixed constant**
(`const CREATE_ORDER_PATH = "/api/v1/orders";` — SSRF, T-20-04 style).

**Registration pattern** (read-orders.ts:90-102):
```typescript
export function registerReadOrders(server: McpServer, bearer: string): void {
  server.registerTool(
    "read_orders",
    { title: "...", description: "...", inputSchema: readOrdersInputSchema },
    readOrdersHandler(bearer),
  );
}
```
New tool **names are snake_case** `create_order` / `create_customer` (D-07).

---

### `mcp-server/src/core-client.ts` (client/utility — ADD `corePost` sibling)

**Analog:** its own `coreGet` (core-client.ts:31-47) — same fixed `CORE_BASE_URL` (L13, SSRF), same `AbortSignal.timeout(CORE_TIMEOUT_MS)` (L17, 10s), same `{ ok, status, contentType, body }` return (`CoreResponse` L19-24). **Never log body/token** (module header L8-9).

**Exact analog to mirror** (core-client.ts:31-47):
```typescript
export async function coreGet(path: string, bearer: string): Promise<CoreResponse> {
  const r = await fetch(`${CORE_BASE_URL}${path}`, {
    method: "GET",
    headers: { authorization: `Bearer ${bearer}`, accept: "application/json" },
    signal: AbortSignal.timeout(CORE_TIMEOUT_MS),
  });
  const contentType = r.headers.get("content-type") ?? "";
  const body = contentType.includes("json") ? await r.json().catch(() => null) : await r.text();
  return { ok: r.ok, status: r.status, contentType, body };
}
```
`corePost` (RESEARCH §Pattern 1) adds `method:"POST"`, `"content-type":"application/json"`, a `...headers` spread
(for `Idempotency-Key`), and `body: JSON.stringify(body)`. `r.ok` is true for 201 → success returns the DTO; only
non-2xx routes to `toToolError`.

---

### `mcp-server/src/server.ts` (provider/factory — register the 2 write tools)

**Analog:** the existing read-tool registration block (server.ts:21-23). Add two imports + two calls inside `buildServer(bearer)`:
```typescript
registerListProducts(server, bearer);
registerListShops(server, bearer);
registerReadOrders(server, bearer);
// ADD:
registerCreateOrder(server, bearer);
registerCreateCustomer(server, bearer);
```
`buildServer(bearer)` is per-request (server.ts:15) — each handler closes over exactly the caller's token; the MCP tier makes no auth/tenant decision.

---

### `mcp-server/src/tools/create-order.test.ts` + `create-customer.test.ts` (test — vitest)

**Analog:** `mcp-server/src/tools/read-orders.test.ts` (mirror structure exactly).

**Mock + PII-log-guard pattern** (read-orders.test.ts:6-20, 131-156):
```typescript
const { logSpies } = vi.hoisted(() => ({ logSpies: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), /* ... */ } }));
vi.mock("pino", () => ({ default: () => logSpies }));
vi.mock("../core-client.js", () => ({ coreGet: vi.fn() }));   // → mock corePost
// ...
// "NEVER logs the response body — customer PII stays out of logs (T-20-01)":
const serialized = JSON.stringify([...logSpies.info.mock.calls, ...logSpies.warn.mock.calls, ...logSpies.error.mock.calls].flat());
expect(serialized).not.toContain(PII);
expect(serialized).not.toContain("customerEmail");
```
Also assert: (a) `corePost` called with the fixed path + camelCase body **with `idempotencyKey` stripped out** + `{ "Idempotency-Key": key }` header; (b) 403/404/409/422 problem+json delegates to `toToolError` (read-orders.test.ts:104-118); (c) thrown fault → sanitized isError, **token never in message** (read-orders.test.ts:120-129).

**`corePost` unit test** — extend `core-client.test.ts` mirroring its `coreGet` cases (L30-66): assert `content-type: application/json` + `Idempotency-Key` header forwarded + **verbatim Bearer** + token never leaks into a thrown error.

---

### `core-java/.../order/OrderController.java` + `customer/CustomerController.java` (controller — ADD `@PreAuthorize`)

**Analog:** `ProductController` create site (product/ProductController.java:134):
```java
import org.springframework.security.access.prepost.PreAuthorize;   // ProductController.java:16
...
@PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
@PostMapping
public ResponseEntity<ProductDto> create(...) { ... }
```
**Add to `OrderController.createOrder`** (currently at OrderController.java:61-64, above `@PostMapping @Idempotent(endpoint="orders.create")`):
```java
@PreAuthorize("hasAuthority('SCOPE_orders:write')")   // Phase 25 [AI-02]: activate reserved orders:write (D-01)
```
**Add to `CustomerController.create`** (currently at CustomerController.java:70-71, above `@PostMapping @Idempotent(endpoint="customers.create")`):
```java
@PreAuthorize("hasAuthority('SCOPE_customers:write')")  // Phase 25 [AI-02]: new customers:write scope (D-02)
```
Method-security is already active (`@EnableMethodSecurity`, SecurityConfig.java:30) — annotation-only change, no config. Both create bodies already route through `idempotencyService.execute(...)` on an optional `Idempotency-Key` header (OrderController.java:71-78, CustomerController.java:87-96) — **no core idempotency change (D-06)**.

---

### `core-java/.../config/OpenApiConfig.java` (config — scope doc strings)

**Analog:** its own scope block (OpenApiConfig.java:59-116). Currently `orders:read`/`orders:write` are documented "reserved, not yet enforced" (L61, L115-116). Update so `orders:write` + `customers:write` read as **enforced**, and add `customers:read`/`customers:write` to the `Scopes()` list (L112-116) + the markdown taxonomy (L59-61). Doc-string only:
```java
.addString("orders:write", "Reserved for the [AI-1] MCP model (#203) — defined, not yet enforced")  // → "enforced; gates POST /orders (Phase 25 [AI-02])"
// ADD: .addString("customers:read", "...defined-only, not enforced")
// ADD: .addString("customers:write", "Enforced; gates POST /customers (Phase 25 [AI-02])")
```

---

### `core-java/.../security/ScopedOrdersCustomersAccessIntegrationTest.java` (NEW — integration/MockMvc scope proof)

**Analog:** `ScopedCatalogAccessIntegrationTest.java` (mirror verbatim — converter-through-MockMvc on Testcontainers Postgres).

**Token-builder pattern** (ScopedCatalogAccessIntegrationTest.java:94-117) — UUID subject + `tenant_id` claim + real converter:
```java
private static RequestPostProcessor operatorJwt() {
    return jwt()
        .jwt(j -> j.subject(UUID.randomUUID().toString())      // UUID sub: 23-08 gate needs it; strict-OFF → implicit GROUP_ADMIN
                .claim("tenant_id", TENANT_A.toString())
                .claim("scope", "catalog:read catalog:write"))  // → "orders:write" / "customers:write"
        .authorities(new JwtRolesAndScopesConverter());          // the REAL production mapping
}
```
**`@Valid`-before-`@PreAuthorize` trap** (test class Javadoc L39-45 + L70-74) — send a **fully valid body** or `@Valid` 400s and masks the 403. Use the RESEARCH §3 valid bodies:
```java
String VALID_ORDER_JSON = "{\"shopId\":\"" + UUID.randomUUID() + "\",\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}";
String VALID_CUSTOMER_JSON = "{\"name\":\"Ada\",\"email\":\"ada@example.com\"}";
```
**Assertion pattern** (ScopedCatalogAccessIntegrationTest.java:129-183): no-write-scope → `status().isForbidden()`; write-scope → `not403()` custom matcher (L176-183). Tenant seeding in `@BeforeEach` (L76-83).

> RESEARCH A2/§3: an operator-scoped order create against a **non-existent** shop 404s downstream (`OrderService.java:100-102`), which still satisfies `not403()`. Seed a real shop+product only if a positive 201 assertion is wanted (Open Question 1 → recommendation: minimal `not403()`, prove 201+replay in the idempotency/live E2E).

---

### `infra/keycloak/realm-export.template.json` (config — 4 edits, all clones)

**Analog:** the `integration-catalog-ro` client block (realm-export.template.json:958-1013) + its scope/user/default-grant siblings. **Edit the TEMPLATE, never the rendered `realm-export.json`** (envsubst regenerates it).

**(a) New client scopes `customers:read` + `customers:write`** — clone the `orders:read`/`orders:write` scope blocks (L1036-1054): same `include.in.token.scope:"true"`, `display.on.consent.screen:"false"`, empty `protocolMappers`, fresh `id`. (`orders:read`/`orders:write` already exist at L1037/L1047 — no new orders scope.)

**(b) New client `integration-orders-rw`** — clone L958-1013 verbatim, change `id`(fresh UUID)/`clientId`/`name`/`description`, `secret → "${INTEGRATION_ORDERS_RW_SECRET}"` (L966 analog). **Keep BOTH protocol mappers** `tenant-id-mapper` (L985-998) + `core-api-audience-mapper` (L999-1010) with fresh `id`s — **the audience mapper is mandatory** (without `aud=core-api` the #88 AudienceValidator 401s before any scope check). Set:
```json
"serviceAccountsEnabled" : true, "standardFlowEnabled" : false,
"directAccessGrantsEnabled" : false, "publicClient" : false,
"clientAuthenticatorType" : "client-secret",
"defaultClientScopes" : [ "orders:write", "customers:write", "catalog:read", "web-origins", "acr", "roles", "profile", "email" ]
```
(D-10 — write + read-for-discovery; **NO `catalog:write`**. Compare RO client's `[ "catalog:read", ... ]` at L1012.)

**(c) New SA user `service-account-integration-orders-rw`** — clone the SA user block (realm-export.template.json:448-463), change `id`(fresh UUID)/`username`/`serviceAccountClientId → "integration-orders-rw"`, **keep** `attributes.tenant_id: ["00000000-0000-0000-0000-000000000001"]` (L452-454) — the RLS carrier claim (template import is NOT subject to the KC24 strip):
```json
{ "id": "<fresh-uuid>", "username": "service-account-integration-orders-rw", "emailVerified": false,
  "attributes": { "tenant_id": ["00000000-0000-0000-0000-000000000001"] }, "enabled": true, "totp": false,
  "serviceAccountClientId": "integration-orders-rw", "realmRoles": ["default-roles-jtoye-dev"], ... }
```

**(d) `core-api` default-grant (D-03)** — extend `core-api`'s `defaultClientScopes` (realm-export.template.json:755) from
`[ "web-origins","acr","roles","profile","email","catalog:read","catalog:write" ]` →
append `"orders:write","customers:write"` so every operator/dashboard token transparently carries them after re-import.

---

### Compose + env secret wiring (config — 6 sites, mirror `INTEGRATION_CATALOG_RO_SECRET`)

**Analog:** every `INTEGRATION_CATALOG_RO_SECRET` site (verified via grep). Add `INTEGRATION_ORDERS_RW_SECRET` beside each:

| File:line | Pattern to mirror |
|-----------|-------------------|
| `docker-compose.full-stack.yml:54` (env) | `INTEGRATION_ORDERS_RW_SECRET: ${INTEGRATION_ORDERS_RW_SECRET:?... must be set (renders realm integration-orders-rw client)}` |
| `docker-compose.full-stack.yml:67` (envsubst allowlist) | append `$$INTEGRATION_ORDERS_RW_SECRET` to the `envsubst '...'` var list |
| `infra/docker-compose.yml:44` + `:53` | same (env + envsubst) |
| `infra/docker-compose.hostnet.yml:11` + `:20` | same |
| `.env.example:113` | `INTEGRATION_ORDERS_RW_SECRET=CHANGE_ME` (+ comment line like L111) |
| `infra/.env.example:55` | `INTEGRATION_ORDERS_RW_SECRET=CHANGE_ME` |
| `scripts/verify-env.sh:47` | add `INTEGRATION_ORDERS_RW_SECRET` to the required array |

**Also (VSA-02, see Shared Patterns):** add `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` to the `core-java` compose env (binds `application.yml:128`).

---

### Docs reconcile (docs — no code analog, mechanical)

- `docs/security-scopes.md` — extend the scope taxonomy (§ enforced list), add the `integration-orders-rw` reference client + `INTEGRATION_ORDERS_RW_SECRET` wiring, note the write E2E recipe. §4 Re-import / §5 KC24 / §6 MCP-feeds are the sections this phase discharges.
- `docs/idempotency.md` — the AC-1 coverage table already lists `POST /orders` + `POST /customers` as header-idempotent; note the MCP tool now *mandates* the key (D-05).
- `mcp-server/README.md` — flip the "write is denied 403" note (auth model §) to "write allowed under the RW credential"; add the live-E2E write recipe (README §5 inverted).
- `docs/metrics.json` + `CLAUDE.md`/`AGENTS.md` prose — run `scripts/docs-freshness.sh --write` (the `--write` run is the arbiter) after adding the 2 vitest files + the Java test; it bumps `mcp_test_blocks`/`mcp_test_files` and the Java `@Test` counts. Reconcile the "1648 logical invocations" prose.

---

## Shared Patterns

### ⚠ VSA-02 shop-access gate on `create_order` — CROSS-CUTTING, PLANNER MUST WIRE EXPLICITLY

**Source:** `core-java/.../security/access/ShopAccessService.java` (`machine-client-ids` allowlist L113-114, 498, 578-596) + `application.yml:128`.
**Applies to:** `create_order` (via `OrderService.createOrder:92 → shopAccessService.require(shopId, SHOP_MANAGER)`) — a **second** app-layer authorization boundary *after* `@PreAuthorize`. `create_customer` has **no** such gate (tenant-scoped only).

This is the one finding **absent from the CONTEXT decisions (D-01..D-12)**. The `integration-orders-rw` service-account carries a **UUID subject** (a Keycloak SA), so:
1. `isDeclaredMachineClient(jwt)` returns **false** for a UUID sub → the allowlist short-circuit does not fire on the read-decision path.
2. Under `strict-scoping` **OFF** (default, `application.yml:117`), a UUID-sub caller with no `shop_staff` rows is a **day-one implicit GROUP_ADMIN** → `require()` passes. So `create_order` works out-of-the-box.
3. **BUT** on the first *write*, `onRequest()` JIT-provisions a **permanent opaque-UUID `GROUP_ADMIN` row** into `shop_staff` + a directory upsert (ShopAccessService.java:529-543) — the exact pollution WR-09's allowlist was built to prevent.

**Mitigation (mirror the allowlist skip, ShopAccessService.java:498):**
```java
if (isAllowlistedMachineClient(jwt)) {
    return;   // skip BOTH JIT-provision + directory upsert; isGroupAdmin() still true via strict-OFF path
}
```
→ Set **`ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw`** in the `core-java` compose env (`application.yml:128` binds `${ACCESS_MACHINE_CLIENT_IDS:}`). `create_order` still passes VSA-02 (implicit GA), and no JIT row accumulates. **Forward caveat (record, no action):** if `strict-scoping` is ever flipped ON, an allowlisted UUID-sub SA becomes fully-ungranted → denied, needing an explicit `OPERATOR` `shop_staff` grant. Strict-scoping stays OFF this phase.

### Scope→authority conversion (positive gate, operators default-grant)
**Source:** `JwtRolesAndScopesConverter` (maps `scope` claim → `SCOPE_*` authorities; ∪ `realm_access.roles → ROLE_*`) — **unchanged**.
**Apply to:** the two new `@PreAuthorize` gates + the realm `core-api` default-grant (D-03). Never a deny/negative SpEL; the operator `core-api` client default-grants the write scope so dashboards are transparent (#206 model exactly).

### RFC 7807 error surfacing (reuse verbatim)
**Source:** `mcp-server/src/errors.ts` `toToolError` (errors.ts:30-48) — already maps 400/401/403/404/409/422/500/502 problem+json into a sanitized `{ content, isError:true }`, **never** forwarding stack/undici internals/token/raw body.
**Apply to:** both write tools' `if (!res.ok) return toToolError(res);`. Core's 409 (in-flight replay) / 422 (same-key different-body) idempotency responses flow through it unchanged — no new error code.

### Idempotency (reserve-first, no core change)
**Source:** `IdempotencyService.execute(endpoint, key, request, dtoClass, work)` — key length **1..64** (`MAX_KEY_LENGTH=64`), reserve-first `INSERT ON CONFLICT`, 409 in-flight, 422 same-key/different-body, replay returns stored status + original DTO. Both controllers already wire it (OrderController.java:75-78, CustomerController.java:91-96).
**Apply to:** the Zod `.min(1).max(64)` bound (D-05) + the tool always forwarding the `Idempotency-Key` header. **No core change (D-06).**

### RLS is the sole tenant boundary (NOSUPERUSER-proven)
**Source:** Postgres FORCE RLS; superuser Testcontainers **cannot** prove it (bypasses FORCE RLS — `idempotency.md:63-67`).
**Apply to:** the cross-tenant AC-2 proof. Mirror `IdempotencyKeysRlsPolicyIntegrationTest` / `MultiTenantIsolationIntegrationTest` under the NOSUPERUSER `rls_test_role` downgrade. The write-side vector is `create_order` with a **foreign `shopId`** → tenant-A GUC hides tenant-B shop → `ResourceNotFoundException` → 404. `create_customer` has no foreign-id vector (tenant is implicit from the token GUC); assert the created row lands only under the caller's tenant.

### No new outbox event type (the dispatch trap does NOT apply)
**Source:** `OrderService.createOrder` (OrderService.java:89-177) does `orderRepository.save` and returns — no outbox publish (the only `publishStateChange` is in a *state-transition* method, L424, already-dispatched `ORDER_STATE_CHANGED`). `CustomerService.createCustomer` does no AMQP/outbox publish.
**Apply to:** planning — this phase adds **no** new outbox event type, so the `outbox_flusher_dispatch_trap` is out of scope (confirms CONTEXT.md:123).

---

## No Analog Found

None. Every file is a direct mirror-and-extend of a shipped, green sibling in this repo. The two "least-clean" matches are role-crossings, not gaps:
- The write tools mirror a **read** tool (`read-orders.ts`) — the skeleton (factory, allow-listed path, pino status-only log, `toToolError`, sanitized-catch) is identical; only the verb (`coreGet`→`corePost`) and the `idempotencyKey`-split differ.
- The cross-tenant RLS proof mirrors an idempotency/isolation RLS test rather than a create-order-specific one — the NOSUPERUSER downgrade harness is the reusable part.

---

## Metadata

**Analog search scope:** `mcp-server/src/` (+ `tools/`), `core-java/.../order/`, `.../customer/`, `.../product/`, `.../config/`, `.../security/` (+ `access/`, `test/.../security/`), `infra/keycloak/`, compose + `.env.example` + `scripts/`.
**Files scanned:** ~18 read/grepped (read-orders.ts, core-client.ts, server.ts, errors.ts, read-orders.test.ts, core-client.test.ts, OrderController, CustomerController, ProductController, ScopedCatalogAccessIntegrationTest, CreateOrderRequest, OrderItemRequest, OpenApiConfig, realm-export.template.json, ShopAccessService, application.yml, 6 secret-wiring sites).
**Pattern extraction date:** 2026-07-24

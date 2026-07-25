# Phase 25: Mutating MCP Tools - Context

**Gathered:** 2026-07-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Extend the Phase 20 **read-only** MCP server (`mcp-server/`) with **write tools** so an external AI agent holding a **tenant-scoped Keycloak client-credentials token** can **create orders and customers** through MCP — each riding the uniform `Idempotency-Key` contract (#204 / V50), with Postgres FORCE RLS in core as the sole isolation boundary. This is EPIC #209's mutating-MCP tail, GitHub requirement **AI-02**, structurally independent of the other v2.3 phases.

**In scope:**
- Two new write MCP tools: **`create_order`** + **`create_customer`** (snake_case, matching the read slice's `list_shops`/`list_products`/`read_orders` naming), thin forwarders over the existing core REST create endpoints (`POST /api/v1/orders`, `POST /api/v1/customers`).
- A `corePost(path, bearer, body, headers)` sibling to the existing `coreGet` in `mcp-server/src/core-client.ts` (same SSRF-safe fixed base URL, verbatim Bearer forward, no body/token logging).
- **Scope enforcement in core (mirror #206):** positively gate `POST /api/v1/orders` with `SCOPE_orders:write` and introduce + enforce a new **`customers:write`** scope on `POST /api/v1/customers`. Operators default-grant both (like `catalog:write`) so the dashboard is untouched.
- A new template-seeded **write machine credential** `integration-orders-rw` (scopes `orders:write` + `customers:write` + `catalog:read`) for reproducible live E2E.
- Cross-tenant RLS proof under the write credential (empty/403), RFC 7807 tool-error surfacing (reuse `toToolError`), idempotent-replay integration test, live E2E against the rebuilt dev stack, `docs/metrics.json` reconcile.

**Explicitly OUT of scope (deferred):**
- Order **state-transition** tools (`confirm_order` / `cancel_order` / `mark_order_ready`) over `POST /orders/{id}/*` — a clean follow-up; the phase is create-only.
- Enforcing the reserved **`orders:read`** scope on read endpoints (the read slice deliberately left reads authenticated-only).
- **Programmatic** per-tenant/per-agent RW client provisioning (needs the realm `tenant_id` **managed-attribute** change — KC24 trap §5 — + reuse of the #102 `KeycloakAdminClient` seam). This phase ships a **template-seeded** client only (import is not subject to the KC24 strip).
- `product.create` / any `catalog:write` MCP tool.

</domain>

<decisions>
## Implementation Decisions

### Scope enforcement (mirror the shipped #206 `catalog:write` gate)
- **D-01:** Enforce write scopes in core **now** — do not leave the create surfaces authenticated-only. `POST /api/v1/orders` gets `@PreAuthorize("hasAuthority('SCOPE_orders:write')")` (activating the reserved `orders:write` scope). This makes AC-1's "mapped to the appropriate write scopes" real and satisfies the standing AI agent-readiness contract ("credentials scoped/least-privilege for the action").
- **D-02:** Introduce a **new `customers:write`** scope (completing the `catalog:*`/`orders:*` taxonomy symmetrically) and enforce it on `POST /api/v1/customers`. Rationale over reusing `orders:write`: finest-grained least-privilege — an agent granted only order-creation genuinely cannot mint customers. Add `customers:read`/`customers:write` to the reserved taxonomy in `OpenApiConfig` doc strings for symmetry (only `customers:write` is enforced this phase).
- **D-03:** Operators/dashboard stay unaffected: the `core-api` client `defaultClientScopes` gains `orders:write` + `customers:write` (exactly how #206 added `catalog:write`), so every operator token transparently carries them after the realm re-import. No new realm role; no negative/deny SpEL.
- **D-04:** Prove the gate in CI via the **converter-through-MockMvc** pattern (#83/#206 precedent — `ScopedCatalogAccessIntegrationTest.noScopeTokenForbiddenOnCreate` is the template): a no-write-scope token → **403** on create; an operator/write-scoped token → allowed. Send a **fully valid** request body so `@Valid` (which runs before `@PreAuthorize`) doesn't 400 and mask the authorization result.

### Idempotency-Key origin
- **D-05:** The write tools expose **`idempotencyKey` as a REQUIRED tool input** (Zod `z.string().min(1).max(64)` to match `IdempotencyService`'s 1..64 contract) and **always forward it as the `Idempotency-Key` header**. The tool description instructs the agent to **reuse the same key when retrying**. The tool therefore has **no non-idempotent path** — it cannot be used to mint a silent duplicate. This is what makes AC-1's "a replayed call returns the original result, not a duplicate" a structural property of the tool, not a hope.
- **D-06:** **No core change** to the idempotency mechanism — core's header stays `required=false` (the dashboard still passes it optionally). Only the MCP tool layer makes the key mandatory. The 409 (in-flight) / 422 (same-key different-body) responses core already emits are RFC 7807 and flow through `toToolError` unchanged.

### Write-tool set + naming
- **D-07:** Exactly **`create_order`** + **`create_customer`** — snake_case verb_noun, consistent with the read slice (`read_orders`, `list_shops`, `list_products`). The ACs/ROADMAP write them dot-style (`orders.create`) conceptually; the registered MCP tool **names** are snake_case.
- **D-08:** Tool `inputSchema` (raw Zod shape, NOT `z.object` — the SDK v1.29.0 contract used by the read tools) mirrors `CreateOrderRequest` / `CreateCustomerRequest`. Verify exact fields/required-ness against `docs/api/openapi-snapshot.json` (source of truth, oasdiff-gated) rather than eyeballing the record. A self-describing schema is better agent DX than a loose passthrough + core 422.

### Write credential + realm
- **D-09:** Ship a **new template-seeded** client **`integration-orders-rw`** in the realm template(s) — an **exact mirror** of `integration-catalog-ro`'s wiring: `serviceAccountsEnabled: true`, `standardFlowEnabled: false`, `directAccessGrantsEnabled: false`, `publicClient: false`, client-secret auth; the **cloned `tenant-id-mapper` + `core-api-audience-mapper`** (audience mapper is mandatory — without `aud=core-api` the #88 `AudienceValidator` 401s before any scope check); SA user `service-account-integration-orders-rw` seeded with a `tenant_id` attribute. Do **not** extend `integration-catalog-ro` (that would destroy its documented read-only / zero-blast-radius guarantee).
- **D-10:** The RW client carries **`orders:write` + `customers:write` + `catalog:read`** (write + read-for-discovery). Rationale: `create_order`'s body needs a valid `shop_id` + product references, so a self-sufficient agent credential (discover products → create order → read back) makes the E2E reproducible without a second token. Blast radius stays bounded — it pointedly does **NOT** carry `catalog:write` (can't mutate the catalog), and RLS walls cross-tenant. This is still a genuine least-privilege boundary, not a god-token.
- **D-11:** Secret via env placeholder **`${INTEGRATION_ORDERS_RW_SECRET}`** (never a committed literal), wired into all three compose renderers (`docker-compose.full-stack.yml`, `infra/docker-compose.yml`, `infra/docker-compose.hostnet.yml`), both `.env.example` files, and `scripts/verify-env.sh` (fails loud when unset) — exactly how #206 wired `INTEGRATION_CATALOG_RO_SECRET`.
- **D-12:** Live E2E precondition: **rebuild ALL containers** + **realm re-import** (`kc.sh import --override true` — `start-dev --import-realm` will not overwrite an existing `jtoye-dev`) so the new scopes + RW client + SA `tenant_id` take effect. Tokens minted before the re-import are fail-closed. Playwright is **not** in CI — authorization gates are proven in CI via the converter-through-MockMvc pattern; the live write E2E is a manual/scripted claim on the dev stack.

### Claude's Discretion
- **D-02** (customer scope granularity) and **D-10** (RW credential scope bundle) were "you decide" — resolved to a **new `customers:write`** scope and a **write + `catalog:read`** bundle respectively, both justified in-line. The planner may refine wording but should preserve the least-privilege intent (no `catalog:write` on the agent credential; separate customer/order write scopes).
- Success-return shape (return the created DTO as JSON text, never logged — DTOs carry PII), `corePost` error/timeout handling, and the `docs/metrics.json` MCP-vitest reconcile are mechanical — planner/executor's call, following the read-slice posture.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### The thing being extended (Phase 20 read-only slice)
- `mcp-server/src/server.ts` — the `buildServer(bearer)` per-request factory that registers each tool; add `registerCreateOrder` / `registerCreateCustomer` here.
- `mcp-server/src/core-client.ts` — `coreGet` (SSRF-safe fixed `CORE_BASE_URL`, verbatim Bearer, 10s timeout, never logs body/token). Add the `corePost` sibling here.
- `mcp-server/src/errors.ts` — `toToolError` already maps core's `application/problem+json` (400/401/403/404/409/422/500/502) into sanitized MCP tool errors. Reuse verbatim; 409/422 idempotency responses flow through it.
- `mcp-server/src/tools/read-orders.ts` — the tool skeleton to mirror (raw-Zod inputSchema, allow-listed path build, factory-returning-handler, `registerXxx`, pino logs tool+status only).
- `mcp-server/README.md` — auth model (client-credentials pass-through), live-E2E preconditions, "write is denied 403" note that this phase turns into "write is allowed under the RW credential".
- `.planning/milestones/v2.2-phases/20-ai-1-mcp-server-read-only-slice/20-CONTEXT.md` — the locked read-slice decisions this phase inherits (thin pass-through, RLS is the sole boundary, RFC 7807, rebuild-before-E2E).

### Core write endpoints (what the tools wrap)
- `core-java/src/main/java/uk/jtoye/core/order/OrderController.java` — `createOrder` (`@PostMapping`, `@Idempotent(endpoint="orders.create")`, optional `Idempotency-Key` → `IdempotencyService.execute`). Add the `orders:write` `@PreAuthorize` here.
- `core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java` — `create` (`@PostMapping`, `@Idempotent(endpoint="customers.create")`, `CreateCustomerRequest` record). Add the `customers:write` `@PreAuthorize` here.
- `core-java/src/main/java/uk/jtoye/core/order/dto/CreateOrderRequest.java` + `CreateCustomerRequest` (inner record in `CustomerController`) — the body shapes the tool Zod schemas mirror.
- `docs/api/openapi-snapshot.json` — drift-proof source of truth for exact create paths/params/DTOs (oasdiff CI gate). Prefer over grepping controllers.

### Idempotency contract (#204 / V50)
- `docs/idempotency.md` — the uniform `Idempotency-Key` HTTP contract, the AC-1 coverage audit table (confirms `POST /orders` + `POST /customers` are header-idempotent), 409/422 semantics.
- `core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyService.java` — `execute(endpoint, key, request, dtoClass, work)`; key length 1..64 (drives D-05's Zod bound), reserve-first INSERT ON CONFLICT, 409 in-flight, 422 same-key-different-body.
- `core-java/src/main/java/uk/jtoye/core/common/idempotency/Idempotent.java` — the marker annotation + how `IdempotencyHeaderCustomizer` advertises the header in OpenAPI.

### Auth substrate / scopes (mirror target — #206)
- `docs/security-scopes.md` — scope taxonomy (`catalog:read/write` enforced, `orders:read/write` **reserved/unenforced**), the `integration-catalog-ro` reference client + its two protocol mappers, `INTEGRATION_CATALOG_RO_SECRET` wiring (3 composes + `verify-env.sh`), **§4 Re-import**, **§5 KC24 managed-attribute trap**, **§6 "Feeds [AI-1] MCP Auth Model"** (the write-scope enforcement mandate this phase discharges).
- `core-java/src/main/java/uk/jtoye/core/security/JwtRolesAndScopesConverter.java` — how `scope` claim → `SCOPE_*` authorities (∪ `realm_access.roles → ROLE_*`); the authority the `@PreAuthorize` gate checks.
- `core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java` — where `orders:read`/`orders:write` are documented as reserved (update to reflect `orders:write` + `customers:write` now enforced).
- `infra/keycloak/realm-export.template.json` (+ rendered `realm-export.json`) — where `integration-catalog-ro`, the client scopes, and the audience/tenant_id mappers are defined; the `integration-orders-rw` client is added here (mirror the RO client block).
- `scripts/verify-env.sh` + `.env.example` (root + `infra/`) — the fail-loud secret-presence checks the new `INTEGRATION_ORDERS_RW_SECRET` is wired into.

### Conventions / gates
- `CLAUDE.md` — stack constraints, multi-tenancy/RLS rules, testing standard (1648 logical invocations baseline, schema V59), "rebuild ALL containers before E2E", the Cross-Cutting Quality Contracts (AI agent-readiness = Idempotency-Key + RFC 7807 + scoped creds + MCP tool for new capability).
- `scripts/docs-freshness.sh` (`--write` = arbiter) + `docs/metrics.json` — the docs-freshness CI gate. New MCP write-tool vitest tests extend the `mcp-server/` vitest family (currently 27 blocks / 6 files); reconcile counts via `--write` so the gate stays green.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`coreGet` → `corePost`**: same `CORE_BASE_URL` (fixed compose service `http://core-java:9090`, SSRF-guarded), same `AbortSignal.timeout(10_000)`, same `{ ok, status, contentType, body }` return; add `method: "POST"`, a JSON body, and a `headers` map for `Idempotency-Key`. Never log body/token (order/customer DTOs carry PII).
- **`toToolError`**: no change needed — it already maps 400/401/403/409/422/500 problem+json, which is precisely the write-tool error taxonomy (403 no-scope, 409 in-flight replay, 422 same-key/different-body, 422/400 validation).
- **`read-orders.ts` skeleton**: copy its factory shape (`createOrderHandler(bearer)` returning `async (args) => CallToolResult`, allow-listed path constant, pino `{tool, status}` logging, catch → sanitized "core unreachable" isError).
- **`ScopedCatalogAccessIntegrationTest`** (the #206 test): the exact template for the `orders:write`/`customers:write` `@PreAuthorize` CI proof (converter-through-MockMvc, valid body so `@Valid` doesn't mask the 403).

### Established Patterns
- **RLS is the boundary, not MCP code** — cross-tenant AC-2 proof lives at the HTTP boundary with two genuinely tenant-scoped tokens (superuser Testcontainers CANNOT prove RLS — it bypasses FORCE RLS). A tenant-A RW token creating/reading a tenant-B resource resolves empty/403 via core's RLS.
- **Positive scope gate, operators default-grant** — never a deny/negative SpEL; the operator `core-api` client default-grants the write scope so dashboards are transparent (the #206 model, exactly).
- **Realm re-import is the operational gate** for any new client/scope to exist in the running IdP; template-seeded (not admin-API) avoids the KC24 `tenant_id` strip.

### Integration Points
- New tools registered in `mcp-server/src/server.ts::buildServer`.
- New `@PreAuthorize` on `OrderController.createOrder` + `CustomerController.create`.
- New `orders:write`/`customers:write` client scopes + `core-api` default-grant + `integration-orders-rw` client in `infra/keycloak/realm-export.template.json`.
- New `INTEGRATION_ORDERS_RW_SECRET` across composes/.env.example/verify-env.sh.
- `OpenApiConfig` scope docs updated (reserved → enforced for the two write scopes).
- `docs/security-scopes.md` + `mcp-server/README.md` + `docs/idempotency.md` + `docs/metrics.json` reconciled.

</code_context>

<specifics>
## Specific Ideas

- **Ports/creds (dev):** Keycloak :8085, core :9090 (mgmt :9091 prod profile), edge :8089, frontend :3000, MCP :9100, Mailhog :8025. Login `admin-user` / `JtoyeDev!2026` (= `.env KC_SEED_USER_PASSWORD`).
- **Git:** feature branch → PR, never commit to main. Suggested branch `feature/25-mutating-mcp-tools` (or `feature/204-mcp-write-tools`).
- **Live E2E shape (mirror README §5, inverted to allow writes):** mint an `integration-orders-rw` client-credentials token → `create_customer` with an `idempotencyKey` → 200 with the customer DTO; replay same key → same DTO, no duplicate; `create_order` referencing a discovered shop/product → 200; a no-write-scope token (`integration-catalog-ro`) → 403 surfaced as a sanitized isError; a tenant-A token targeting a tenant-B shop/order → empty/403 (RLS).
- **The `@Valid`-before-`@PreAuthorize` ordering trap**: the CI 403 test must send a fully valid body, or Spring's `@Valid` 400s first and masks the authorization outcome (documented in `security-scopes.md` §3).
- **Outbox note:** order/customer *creation* does not itself require a new outbox event-type dispatch branch (unlike Phase 22/24 media/webhook events), so the `outbox_flusher_dispatch_trap` does NOT apply here — this phase adds no new outbox event type. (If the planner discovers `orders.create` emits an event that isn't already dispatched, treat that as a pre-existing wiring, not new to this phase.)

</specifics>

<deferred>
## Deferred Ideas

- **Order state-transition MCP tools** (`confirm_order` / `cancel_order` / `mark_order_ready`) over `POST /orders/{id}/*` — a clean follow-up mutating slice; would pull in the order state-machine transition guards/errors as new tool surfaces to prove. Not this phase (phase is create-only).
- **Enforcing the reserved `orders:read`** (and a `customers:read`) scope on the read endpoints — the read slice deliberately left reads authenticated-only; a symmetric read-scope enforcement is a separate hardening.
- **Programmatic per-tenant/per-agent RW client provisioning** — reuses the #102 `KeycloakAdminClient` seam and requires the realm `tenant_id` **managed-attribute** change first (KC24 trap §5). This phase ships a template-seeded client only.
- **`product.create` / `catalog:write` MCP tool** — deliberately excluded to keep the RW credential's blast radius bounded (no catalog mutation).
- **MCP resource/prompt primitives** beyond tools — still tools-only.

</deferred>

---

*Phase: 25-mutating-mcp-tools*
*Context gathered: 2026-07-24*

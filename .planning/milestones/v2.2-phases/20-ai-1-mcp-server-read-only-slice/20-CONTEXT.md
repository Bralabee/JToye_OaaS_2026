# Phase 20: AI-1 MCP Server (Read-Only Slice) — Context

**Gathered:** 2026-07-13
**Status:** Ready for planning
**Source:** Locked decisions captured in-session (issue #203 + user decisions) — see `<decisions>`. No separate discuss-phase run; the issue ACs + these locked decisions fully specify the slice.
**Mode:** mvp (vertical slice — thin end-to-end: MCP tool → core REST → RLS → real data)

<domain>
## Phase Boundary

Deliver a **Model Context Protocol (MCP) server** that lets an external AI agent, holding only a **tenant-scoped Keycloak client-credentials token**, discover and **READ** the J'Toye platform: list shops, list products, read orders. This is EPIC #209 Wave 2, GitHub issue #203 [AI-1], the **read-only first slice**.

**In scope:**
- New `mcp-server/` workspace at repo root (TypeScript, official `@modelcontextprotocol/sdk`), its own Docker container wired into docker-compose.
- Read-only MCP tools that wrap the EXISTING core REST API (`core-java`, base `/api/v1`) over HTTP.
- Auth: client-credentials token pass-through; `tenant_id` claim → RLS does the isolation in core.
- Cross-tenant RLS proof (test), RFC 7807 tool-error surfacing, README for client-credentials setup, live E2E against the dev stack.

**Explicitly OUT of scope (deferred to later phases):**
- Any MUTATING MCP tool (create/update/state-transition). Deferred until it can carry #204's `Idempotency-Key` header. #204 shipped the contract (PR #211) but wiring MCP write-tools to it is a follow-up.
- Enforcing `orders:*` write scope on the order surface (follow-up mirroring the `catalog:write` gate).
- #205 outbound tenant webhooks (separate phase).
- Per-tenant/per-agent programmatic client provisioning (reuses #102 `KeycloakAdminClient` seam later; needs the realm `tenant_id` managed-attribute change first — see traps).
</domain>

<decisions>
## Implementation Decisions (LOCKED — do not re-litigate)

### Runtime & framework
- **TypeScript**, official `@modelcontextprotocol/sdk`. NO new Python/Go runtime added to the Java+TS+Go stack.
- New **`mcp-server/`** workspace at repo root (sibling of `core-java/`, `edge-go/`, `frontend/`).
- Packaged as its **own Docker container**, wired into `docker-compose*.yml` (dev compose is the E2E target). Node 20+ base (match frontend's Node major).

### Architecture / security boundary
- The MCP server is a **thin tool layer over the existing core REST API via HTTP**. It **does NOT** talk to Postgres directly. `core-java` + RLS remain the sole security boundary — the MCP server holds no DB credentials and enforces no tenant logic of its own.
- Isolation is proven by RLS, not by MCP-server code. A confused/hostile agent cannot cross tenants because the token's `tenant_id` claim scopes every core query.

### Auth (REUSE #206 — do not invent a new auth path)
- Keycloak **client-credentials** token pass-through. The agent presents a token; the MCP server forwards it as `Authorization: Bearer` to core.
- `tenant_id` claim drives RLS in core. Read tools map to `catalog:read` / `orders:read` scopes (already seeded in the realm template by #206; `orders:read` is currently **reserved/unenforced** — read tools just present the token and RLS isolates; do not add enforcement in this slice).
- Reference credential: the **`integration-catalog-ro`** client + **`INTEGRATION_CATALOG_RO_SECRET`** env var shipped by #206 (client-credentials, `core-api` audience mapper, managed `tenant_id` on its service-account user).
- **#88 audience gate:** core rejects tokens lacking the `core-api` audience — the reference client already has the audience mapper; any new machine client must too.

### Tools (read-only)
- `list_shops`, `list_products`, `read_orders` (names/shape to be finalized by the planner against the actual core endpoints — verify the live OpenAPI snapshot `docs/api/openapi-snapshot.json` for exact paths, params, DTOs). Product/order reads are the priority per the ACs.
- Tool **errors** must map core's RFC 7807 Problem Detail (core already returns `application/problem+json` via `GlobalExceptionHandler`) into MCP tool-error content — **never** leak raw stack traces or HTTP client noise.

### Testing
- Cross-tenant RLS test is an AC — must PROVE isolation, not assume it. Two tenant-scoped tokens (tenant A, tenant B); an A-token MCP `list_products` call must never return B's rows (empty/403), asserted end-to-end.
- Live E2E against the running dev stack is REQUIRED (not just unit). Standing rule: **rebuild ALL containers before any live E2E claim.**
</decisions>

<canonical_refs>
## Canonical References — downstream agents MUST read these before planning/implementing

### Auth substrate (reuse target — #206)
- `docs/security-scopes.md` — scope taxonomy (`catalog:read/write`, `orders:read/write` reserved), the `integration-catalog-ro` reference client, `INTEGRATION_CATALOG_RO_SECRET` wiring (3 composes + `verify-env.sh`), **§Re-import** (operational step to make the client exist in the running Keycloak), **§6 "Feeds [AI-1] MCP Auth Model (#203)"** (this phase's exact mandate), and the **KC24 `tenant_id` managed-attribute trap**.
- `core-java/.../security/JwtRolesAndScopesConverter.java` (#206) — how ROLE_* ∪ SCOPE_* authorities are derived from the token `scope` claim.
- Realm template (Keycloak import JSON under `core-java/.../keycloak/` or `docker/keycloak/` — locate it) — where `integration-catalog-ro` + scopes + audience mapper are defined.

### Idempotency (feed-forward only — mutations OUT of scope now)
- `docs/idempotency.md` — the uniform `Idempotency-Key` contract + adoption recipe. Read so the read-only tool design leaves a clean seam for future write-tools; do NOT implement writes now.

### Core API contract (what the tools wrap)
- `docs/api/openapi-snapshot.json` — drift-proof OpenAPI snapshot (oasdiff CI gate). Source of truth for exact read endpoints, params, and response DTOs. Prefer this over grepping controllers.
- `core-java/.../*/`*Controller.java` for shops/products/orders — confirm read routes + auth annotations (product reads are authenticated-only per #206; product WRITES are `hasAuthority('SCOPE_catalog:write')`).

### Conventions / gates
- `CLAUDE.md` — stack constraints, multi-tenancy rules, testing standard, "rebuild ALL containers before E2E".
- `scripts/docs-freshness.sh` (`--write` = arbiter) + `docs/metrics.json` — the docs-freshness CI gate. Baseline **1208** logical invocations / schema **V50**. An MCP TypeScript test surface may add test counts; the metric families are Java `@Test`, Jest `it/test`, Go `Test*`, Playwright `test()`. Decide during planning whether MCP tests register as a NEW family or extend Jest counting — reconcile via `docs-freshness.sh --write` so the gate stays green.
</canonical_refs>

<specifics>
## Specific Ideas / constraints

- **Git:** feature branch → PR, never commit to main. Suggested branch `feature/203-mcp-server-readonly`.
- **Operational precondition for live E2E:** the running dev Keycloak realm has **NOT** been re-imported since #206 merged — the `integration-catalog-ro` client does not yet exist in the live IdP. Before any live token/E2E claim: rebuild all containers + re-import the realm per `docs/security-scopes.md` §Re-import, then verify:
  `curl -s -X POST :8085/realms/jtoye-dev/protocol/openid-connect/token -d grant_type=client_credentials -d client_id=integration-catalog-ro -d client_secret=$INTEGRATION_CATALOG_RO_SECRET` → token; MCP list_products with it → 200; a write attempt → 403.
- **Ports (dev):** Keycloak :8085, core :9090 (mgmt :9091 prod profile), edge :8089, frontend :3000, Mailhog :8025. Login `admin-user` / `<dev password — see .env>`.
- **MCP transport:** researcher to recommend stdio vs Streamable HTTP for this deployment (a containerized server an external agent connects to argues for HTTP transport; a locally-spawned agent tool argues stdio). Capture the token-delivery mechanism per transport (env/config vs per-session header).
- **Superuser Testcontainers CANNOT prove RLS** (bypasses FORCE RLS). The cross-tenant proof for the TS server must exercise the real dev stack with two genuinely tenant-scoped tokens (the core-side `rls_test_role NOSUPERUSER` pattern is a Java-test tool; the MCP proof lives at the HTTP boundary with real tokens).
</specifics>

<deferred>
## Deferred Ideas

- Mutating MCP tools (order state ops, create) — gated on wiring #204 `Idempotency-Key`; next slice.
- `orders:*` write-scope enforcement on the order surface — follow-up mirroring #206's `catalog:write` gate.
- #205 outbound tenant webhooks from the V46 outbox — separate phase.
- Per-tenant/per-agent programmatic MCP client provisioning — needs realm `tenant_id` managed-attribute change (KC24 trap) + reuse of #102 `KeycloakAdminClient` seam.
- MCP resource/prompt primitives beyond tools — start with tools only.
</deferred>

---

*Phase: 20-ai-1-mcp-server-read-only-slice*
*Context captured: 2026-07-13 (in-session locked decisions; no discuss-phase run)*

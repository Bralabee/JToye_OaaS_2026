# J'Toye MCP Server (#203 / AI-1 read slice + #204 / AI-02 write tools)

A thin, **stateless** [Model Context Protocol](https://modelcontextprotocol.io) tool layer
over the J'Toye Core REST API. It lets an AI agent list a tenant's shops, products and orders
(read slice, #203) **and** create orders and customers (write tools, #204 / AI-02) through MCP
tools — without ever handling database credentials or a client secret. The write tools mandate
an `Idempotency-Key` (a replay returns the original result, never a duplicate) and require a
write-scoped token (`orders:write` / `customers:write`); a token without the scope is rejected
by Core with a sanitized `403`.

The isolation boundary is **not** in this server. Every request is authorised and
tenant-scoped by Core (JWT audience + issuer/expiry) and Postgres **FORCE Row-Level
Security** on the token's `tenant_id` claim. This tier makes **zero** auth or tenancy
decisions — it forwards an opaque Bearer and shapes errors. If a tool ever returned
another tenant's row, that would be an RLS failure in Core, not a bug here.

---

## 1. What it is

- **Runtime:** Node 20, TypeScript ESM, one `node:20-alpine` container (`Dockerfile`).
- **Transport:** stateless Streamable HTTP. `POST /mcp` builds a fresh `McpServer` +
  transport **per request** (no session state), so a single container serves every tenant
  concurrently — each request's tenant is decided entirely by its own Bearer.
- **Liveness:** `GET /health` → `200 {"status":"ok"}`. It does **not** call Core, so the
  container stays healthy even when Core is briefly down (used by the Docker `HEALTHCHECK`).
- **Port:** `9100` (override via `MCP_PORT`).
- **Holds no secret and no DB credentials.** The only config it reads is `CORE_BASE_URL`
  (a fixed internal service name — see §5, T-20-04 SSRF guard).

## 2. Tools

Each tool is a thin wrapper: build a **fixed** allow-listed path → forward the caller's Bearer
to Core → return the JSON, or a sanitized error. Paths are never composed from caller input.
Read tools GET; write tools POST the tool args as the body and split `idempotencyKey` out to
the `Idempotency-Key` header.

| MCP tool          | Core endpoint                | Scope (Core-enforced) | Notes |
| ----------------- | ---------------------------- | --------------------- | ----- |
| `list_products`   | `GET /api/v1/products`       | `catalog:read`        | Optional `page`/`size` pagination (size ≤ 100). No search argument in this slice. |
| `list_shops`      | `GET /api/v1/shops`          | authenticated         | Tenant's shops. |
| `read_orders`     | `GET /api/v1/orders`         | (`orders:read` reserved) | Output carries customer PII — RLS-scoped; the server **never** logs the body. |
| `create_order`    | `POST /api/v1/orders`        | `orders:write`        | Mandatory `idempotencyKey` → `Idempotency-Key` header (replay returns the original order, never a dup). Body carries customer PII — never logged. |
| `create_customer` | `POST /api/v1/customers`     | `customers:write`     | Mandatory `idempotencyKey` (1..64). Same-key replay returns the identical `CustomerDto`. Body carries PII — never logged. |

The two write tools are **allowed under a write-scoped credential** (e.g. `integration-orders-rw`,
§4). A `catalog:read`-only token still gets `403` on any Core mutation — surfaced by the tool as
a sanitized `isError` — so a read-only credential keeps zero write blast radius. Cross-tenant
writes resolve empty/`404` via Core's FORCE RLS: the MCP tier makes **no** tenancy decision.

## 3. Auth model — pass-through (the default, and what is built)

The calling agent mints its **own** tenant-scoped Keycloak token and presents it per request:

```
POST http://localhost:9100/mcp
Authorization: Bearer <agent's token>
```

The server strips `Bearer `, forwards the token **verbatim** to `CORE_BASE_URL`
(`http://core-java:9090`), and returns Core's response. A missing token fails fast with
`401` at the MCP host; a *present-but-invalid* token is rejected by Core (the real
validator). The MCP tier never validates `iss`, `aud` or expiry — it is immune to the
split-horizon issuer concern (that is Core's job, #87/#88).

## 4. Client-credentials setup (reference machine credential)

Two reference machine credentials ship in the realm template (see `docs/security-scopes.md`):

- **`integration-catalog-ro`** (read, #206) — default-scoped to `catalog:read` only. Its
  secret is **`INTEGRATION_CATALOG_RO_SECRET`**. Use it to prove a no-write-scope token is
  `403` on any mutation.
- **`integration-orders-rw`** (write, Phase 25 #204 / AI-02) — default-scoped to
  `orders:write` + `customers:write` + `catalog:read` (write **plus** read-for-discovery;
  pointedly **no** `catalog:write`). Its secret is **`INTEGRATION_ORDERS_RW_SECRET`**. It is
  the self-sufficient credential the `create_order` / `create_customer` tools mint against.

Both are client-credentials only and carry the `core-api` audience + a `tenant_id` claim (the
RLS carrier). Secrets are sourced from `.env` (placeholder `CHANGE_ME` in `.env.example`),
**never** a committed literal. Mint a token from the **host** Keycloak endpoint (`:8085`, the
public issuer Core accepts):

```bash
KC=http://localhost:8085            # Keycloak public base URL (dev)
MCP=http://localhost:9100           # this server

# 1. Mint a client-credentials token (secret comes from the env — never inline a literal):
TOK=$(curl -s -X POST "$KC/realms/jtoye-dev/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=integration-catalog-ro \
  -d client_secret="$INTEGRATION_CATALOG_RO_SECRET" \
  | jq -r .access_token)
# The token carries: aud=core-api, tenant_id=<uuid>, scope="... catalog:read"
```

## 5. Live E2E preconditions

The reference client only exists in the running IdP **after the realm is re-imported**, and
code changes require a full rebuild:

1. **Rebuild ALL containers** after code changes (project standard):
   `docker compose -f docker-compose.full-stack.yml up -d --build`.
2. **Re-import the realm** per `docs/security-scopes.md` §4 (Realm **Re-import** Required).
   `start-dev --import-realm` will **not** overwrite an existing `jtoye-dev`; use
   `kc.sh import --override true` (or drop the Keycloak volume) so `integration-catalog-ro`,
   `integration-orders-rw`, the new scopes and the SA `tenant_id` attributes take effect.
   Tokens minted *before* the re-import are fail-closed (they lack the new scopes). This is the
   blocking gate for any live claim (threats T-20-06 / T-25-13). Also set
   `INTEGRATION_ORDERS_RW_SECRET` + `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` in `.env`
   and run `scripts/verify-env.sh` (must pass) before the rebuild.

Then verify pass-through end to end — read allowed, write **allowed under the RW credential**,
write **denied under a no-scope token**. The Streamable HTTP transport hard-requires the dual
`Accept` header on `POST` (406 without it):

```bash
# Mint both tokens (secrets from the env — never inline a literal):
RW=$(curl -s -X POST "$KC/realms/jtoye-dev/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=integration-orders-rw \
  -d client_secret="$INTEGRATION_ORDERS_RW_SECRET" | jq -r .access_token)
RO=$(curl -s -X POST "$KC/realms/jtoye-dev/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=integration-catalog-ro \
  -d client_secret="$INTEGRATION_CATALOG_RO_SECRET" | jq -r .access_token)
HDR=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

# 1. Read is allowed (catalog:read):
curl -s "$MCP/mcp" -H "Authorization: Bearer $RW" "${HDR[@]}" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_products","arguments":{}}}'
# -> 200, product list

# 2. create_customer under the RW token (write allowed). Reuse the SAME idempotencyKey to replay:
curl -s "$MCP/mcp" -H "Authorization: Bearer $RW" "${HDR[@]}" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"create_customer","arguments":{"name":"Ada Lovelace","email":"ada@example.com","idempotencyKey":"demo-cust-001"}}}'
# -> 200 + CustomerDto; replaying id:3 with the SAME idempotencyKey returns the IDENTICAL DTO, no dup row.

# 3. create_order referencing a shop+product discovered via list_products/list_shops:
curl -s "$MCP/mcp" -H "Authorization: Bearer $RW" "${HDR[@]}" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"create_order","arguments":{"shopId":"<uuid>","items":[{"productId":"<uuid>","quantity":1}],"idempotencyKey":"demo-order-001"}}}'
# -> 200 + OrderDto (DRAFT)

# 4. A no-write-scope token (integration-catalog-ro) is rejected — sanitized isError (403), no stack/PII:
curl -s "$MCP/mcp" -H "Authorization: Bearer $RO" "${HDR[@]}" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"create_order","arguments":{"shopId":"<uuid>","items":[{"productId":"<uuid>","quantity":1}],"idempotencyKey":"demo-order-002"}}}'
# -> isError:true (Core 403 mapped through toToolError)

# 5. Cross-tenant: an RW token targeting a foreign-tenant shopId resolves empty/404 via FORCE RLS
#    (the MCP tier makes no tenancy decision). VSA-02: after the successful create_order, the RW SA
#    accrues NO opaque-UUID GROUP_ADMIN row in shop_staff (ACCESS_MACHINE_CLIENT_IDS allowlist):
#      SELECT id, user_id, role, grant_source FROM shop_staff WHERE role='GROUP_ADMIN';  -- no new RW-SA row
```

## 6. Optional (NOT built in this slice)

A single-tenant demo *could* let the container mint its own token in-process from
`INTEGRATION_CATALOG_RO_SECRET` (server-minted model). That is deliberately **not** built
here: it would put a secret inside the MCP container (T-20-07 secret-sprawl) and collapse
the multi-tenant pass-through design to one tenant. Pass-through (§3) is the locked default.

---

*Never paste real secret values into this file, logs, or tool output. Secrets live in
`.env` and are consumed by Keycloak only.*

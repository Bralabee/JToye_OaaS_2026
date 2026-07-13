# J'Toye MCP Server (read-only slice — #203 / AI-1)

A thin, **stateless, read-only** [Model Context Protocol](https://modelcontextprotocol.io)
tool layer over the J'Toye Core REST API. It lets an AI agent list a tenant's shops,
products and orders through MCP tools — without ever handling database credentials or a
client secret.

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

Each tool is a thin GET wrapper: build an allow-listed path → forward the caller's Bearer
to Core → return the JSON, or a sanitized error. Paths are never composed from caller input.

| MCP tool        | Core endpoint                | Scope (Core-enforced) | Notes |
| --------------- | ---------------------------- | --------------------- | ----- |
| `list_products` | `GET /api/v1/products`       | `catalog:read`        | Optional search → `GET /api/v1/products/search?q=`. |
| `list_shops`    | `GET /api/v1/shops`          | authenticated         | Tenant's shops. |
| `read_orders`   | `GET /api/v1/orders`         | (`orders:read` reserved) | Output carries customer PII — RLS-scoped; the server **never** logs the body. |

All three are **read-only**. There is no write tool: a `catalog:read`-only token gets `403`
on any Core mutation, which is exactly the blast-radius guarantee this slice relies on.

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

The reference read-only integration is the `integration-catalog-ro` client shipped by
#206 (see `docs/security-scopes.md`). It is client-credentials only, default-scoped to
`catalog:read`, and carries the `core-api` audience + a `tenant_id` claim (the RLS carrier).

Its secret is supplied through the environment variable **`INTEGRATION_CATALOG_RO_SECRET`**
— sourced from `.env` (placeholder `CHANGE_ME` in `.env.example`), **never** a committed
literal. Mint a token from the **host** Keycloak endpoint (`:8085`, the public issuer Core
accepts):

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
   the new scopes and the SA `tenant_id` attribute take effect. Tokens minted *before* the
   re-import are fail-closed (they lack the new scopes). This is the blocking gate for any
   live claim (threat T-20-06).

Then verify pass-through end to end — read allowed, write denied:

```bash
# Read is allowed (authenticated, catalog:read). The Streamable HTTP transport
# hard-requires the dual Accept header on POST (406 without it):
curl -s -o /dev/null -w '%{http_code}\n' "$MCP/mcp" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_products","arguments":{}}}'
# -> 200

# Write is denied by Core (no catalog:write) — a mutation attempt returns 403 upstream,
# surfaced by the MCP tool as a sanitized isError (there is no write tool in this slice).
```

## 6. Optional (NOT built in this slice)

A single-tenant demo *could* let the container mint its own token in-process from
`INTEGRATION_CATALOG_RO_SECRET` (server-minted model). That is deliberately **not** built
here: it would put a secret inside the MCP container (T-20-07 secret-sprawl) and collapse
the multi-tenant pass-through design to one tenant. Pass-through (§3) is the locked default.

---

*Never paste real secret values into this file, logs, or tool output. Secrets live in
`.env` and are consumed by Keycloak only.*

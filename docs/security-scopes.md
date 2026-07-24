# Client Scopes & Scoped Machine Credentials (#206 [AI-4])

Least-privilege OAuth2 client scopes for machine/integration access to the J'Toye Core
API. A client-credentials token scoped to `catalog:read` **only** can list products (200)
but cannot create or mutate them (403), while operator/admin (dashboard) flows are
untouched. This scope taxonomy is the auth substrate the **[AI-1] MCP model (#203)** will
consume.

> **No DB migration** — this slice is auth-config + method-security only (schema stays V50).

---

## 1. Scope Taxonomy

| Scope | Enforced now? | Meaning |
|-------|---------------|---------|
| `catalog:read`  | **Yes** | Read the product catalog — `GET /api/v1/products`, `GET /api/v1/products/{id}`, search, template, label. Authenticated-only (any valid tenant token can read). |
| `catalog:write` | **Yes** | Mutate the product catalog — create/update/delete products and product images, bulk CSV/image import. Gates all **nine** `ProductController` mutations via `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")`. |
| `orders:write`  | **Yes** (Phase 25 [AI-02]) | Create orders — gates `POST /api/v1/orders` via `@PreAuthorize("hasAuthority('SCOPE_orders:write')")`. The [AI-2]/#204 `create_order` MCP tool rides this scope. |
| `customers:write` | **Yes** (Phase 25 [AI-02]) | Create customers — gates `POST /api/v1/customers` via `@PreAuthorize("hasAuthority('SCOPE_customers:write')")`. The [AI-2]/#204 `create_customer` MCP tool rides this scope. |
| `orders:read`   | No (reserved) | **Defined only** to seed the [AI-1]/#203 MCP capability taxonomy. Reads stay authenticated-only; not enforced. |
| `customers:read`  | No (reserved) | **Defined only** for taxonomy symmetry with `orders:read`. Not enforced this phase. |

Scopes are Keycloak **client scopes** with `include.in.token.scope=true`, so their names
land in the token's space-delimited `scope` claim. The resource server maps `scope → SCOPE_*`
authorities via `JwtRolesAndScopesConverter` (which also preserves the #83 `realm_access.roles
→ ROLE_*` mapping — both authority families are emitted, neither is dropped).

### Write-gate model

- **Reads are authenticated-only.** `GET` product endpoints carry no scope gate; any valid
  tenant/machine token (including a `catalog:read`-only token) gets **200**. This is what
  gives the read-only integration its list access with zero blast radius on other readers.
- **Writes require `SCOPE_catalog:write`.** The nine mutating handlers are gated positively.
  A `catalog:read`-only token gets **403**; an operator token (which carries `catalog:write`
  by default — see §2) is not rejected by the gate.
- **Order/customer creates require `SCOPE_orders:write` / `SCOPE_customers:write`** (Phase 25
  [AI-02]). `POST /api/v1/orders` and `POST /api/v1/customers` are each gated positively — the
  same `@PreAuthorize` + operator-default-grant model as `catalog:write`. A token missing the
  scope gets **403**; the `create_order` / `create_customer` MCP tools (#204) forward a
  write-scoped token and so pass. These gates run **before** the shop-access (VSA-02) check
  and the `Idempotency-Key` reservation. The RW machine credential `integration-orders-rw`
  (§2) carries both write scopes plus `catalog:read` for discovery, but pointedly **not**
  `catalog:write`.

Operators are **unchanged** because their `core-api` client default-grants the catalog **and**
the two write scopes (`orders:write`, `customers:write`), so every dashboard token
transparently carries them after the realm re-import (§4). No new realm role is invented; no
negative/deny SpEL expression is used.

---

## 2. Realm Configuration (jtoye-dev)

Machine/integration clients live in the **`jtoye-dev`** realm (same issuer + `aud=core-api`
as the business API — the customer-facing `jtoye-customers` realm is B2C-only).

- **Client scopes:** `catalog:read`, `catalog:write`, `orders:read`, `orders:write`,
  `customers:read`, `customers:write` defined in `clientScopes[]` (the `customers:*` pair was
  added in Phase 25 [AI-02]; only `customers:write` is enforced).
- **`core-api` (operators/dashboard):** `defaultClientScopes` grants `catalog:read` +
  `catalog:write` + `orders:write` + `customers:write` → operator tokens carry every write
  scope automatically after the re-import.
- **`integration-catalog-ro` (sample machine client):** client-credentials only
  (`serviceAccountsEnabled: true`, `standardFlowEnabled: false`,
  `directAccessGrantsEnabled: false`, `publicClient: false`, `clientAuthenticatorType:
  client-secret`), `defaultClientScopes` = `catalog:read` **only** (no `catalog:write`). It
  carries two protocol mappers cloned from `core-api`:
  - `tenant-id-mapper` — emits the `tenant_id` claim from the service-account user attribute
    (the RLS carrier — without it RLS returns zero rows).
  - `core-api-audience-mapper` — **mandatory**: injects `aud=core-api`. Without it the #88
    `AudienceValidator` 401s the token at decode time, before any scope check runs.
- **`integration-orders-rw` (write machine client, Phase 25 [AI-02]):** an exact clone of
  `integration-catalog-ro`'s wiring (client-credentials only; both `tenant-id-mapper` +
  `core-api-audience-mapper`) but `defaultClientScopes` = `orders:write` + `customers:write` +
  `catalog:read` — write-plus-read-for-discovery, deliberately **no** `catalog:write` so the
  agent credential cannot mutate the catalog. This is the self-sufficient credential the
  `create_order` / `create_customer` MCP write tools (#204) mint against for the live E2E; its
  blast radius stays bounded (least-privilege) and RLS walls cross-tenant. Its SA user is
  `service-account-integration-orders-rw`, seeded with the same `attributes.tenant_id` RLS
  carrier. It is on the `ACCESS_MACHINE_CLIENT_IDS` allowlist so its writes do **not**
  accumulate a JIT `shop_staff` GROUP_ADMIN row (VSA-02).
- **Service-account user:** `service-account-integration-catalog-ro` is seeded with an
  `attributes.tenant_id` value. Template import preserves the attribute (it is **not**
  subject to the KC24 admin-API strip — see §5).

Secrets: each client's `secret` is an envsubst placeholder — `${INTEGRATION_CATALOG_RO_SECRET}`
and `${INTEGRATION_ORDERS_RW_SECRET}` — never a committed literal. Both are wired into all three
compose renderers (`docker-compose.full-stack.yml`, `infra/docker-compose.yml`,
`infra/docker-compose.hostnet.yml`), both `.env.example` files, and `scripts/verify-env.sh`
(fails loud when unset).

---

## 3. Per-tenant Client-Credentials Recipe

Mint one machine client per tenant integration (the SA user's `tenant_id` attribute is the
natural RLS carrier — no cross-tenant token). Using the shipped sample client:

```bash
# 1. Mint a client-credentials access token from the jtoye-dev realm.
#    Secret comes from the environment — never inline a literal.
KC=http://localhost:8085            # Keycloak public base URL (dev)
CORE=http://localhost:9090          # Core API base URL (dev; adjust to your topology)

TOK=$(curl -s -X POST "$KC/realms/jtoye-dev/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=integration-catalog-ro \
  -d client_secret="$INTEGRATION_CATALOG_RO_SECRET" \
  | jq -r .access_token)
# The token carries: aud=core-api, tenant_id=<uuid>, scope="... catalog:read"

# 2. Read is allowed (authenticated-only surface):
curl -s -o /dev/null -w '%{http_code}\n' "$CORE/api/v1/products" \
  -H "Authorization: Bearer $TOK"                                   # -> 200

# 3. Write is denied (no catalog:write):
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$CORE/api/v1/products" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -d '{"sku":"X","title":"X","ingredientsText":"Water","allergenMask":0,"pricePennies":999}'
# -> 403  (a FULLY valid body — @Valid runs before @PreAuthorize, so an
#          incomplete body would 400 and mask the authorization result)
```

To grant a machine client write access, add `catalog:write` to its `defaultClientScopes`
(the sample client deliberately does not carry it).

### Write recipe — `integration-orders-rw` (Phase 25 [AI-02])

The shipped write machine client rides `orders:write` + `customers:write`. After the realm
re-import (§4), mint its token and prove the write gates directly against Core:

```bash
KC=http://localhost:8085
CORE=http://localhost:9090

RW=$(curl -s -X POST "$KC/realms/jtoye-dev/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=integration-orders-rw \
  -d client_secret="$INTEGRATION_ORDERS_RW_SECRET" \
  | jq -r .access_token)
# The token carries: aud=core-api, tenant_id=<uuid>, scope="... orders:write customers:write catalog:read"

# 1. Create a customer (write allowed). Reuse the SAME Idempotency-Key to replay, never dup:
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$CORE/api/v1/customers" \
  -H "Authorization: Bearer $RW" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-cust-001' \
  -d '{"name":"Ada Lovelace","email":"ada@example.com"}'                 # -> 201 (replay -> 200, identical body)

# 2. A no-write-scope token is rejected on the same create:
RO=$(curl -s -X POST "$KC/realms/jtoye-dev/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=integration-catalog-ro \
  -d client_secret="$INTEGRATION_CATALOG_RO_SECRET" | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$CORE/api/v1/customers" \
  -H "Authorization: Bearer $RO" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-cust-002' \
  -d '{"name":"Grace Hopper","email":"grace@example.com"}'               # -> 403 (no customers:write)
```

The **through-MCP** form of this flow (POST /mcp `create_customer` / `create_order`, plus the
cross-tenant RLS probe) is in `mcp-server/README.md` §5.

---

## 4. Migration Note — Realm Re-import Required

The new scopes / client / SA user only take effect after the realm is re-imported:

- `start-dev --import-realm` **only creates realms that do not already exist** — it will
  **not** overwrite an existing `jtoye-dev`.
- To apply template changes to a running realm, either:
  - **Keycloak DB drop + restart** (drop the Keycloak schema/volume, let `--import-realm`
    recreate it), or
  - **`kc.sh import --override true`** — e.g.
    `docker exec -it jtoye-keycloak /opt/keycloak/bin/kc.sh import --file /opt/keycloak/data/import/realm-export.json --override true`
    (see `infra/keycloak/README.md`).
- **Fail-closed stale-token posture:** tokens minted **before** the re-import lack
  `catalog:write` / `orders:write` / `customers:write` and will **403** on the corresponding
  writes until re-login. This is the same posture as #87/#88 and is asserted as explicit test
  contracts (`ScopedCatalogAccessIntegrationTest.noScopeTokenForbiddenOnCreate` for catalog;
  `ScopedWriteAccessIntegrationTest` for the Phase 25 order/customer write gates).
- **Live/Playwright verification** requires the re-import first, and Playwright is **not**
  in CI — the authorization gates are proven in CI via the converter-through-MockMvc
  pattern (#83 precedent), not against a live Keycloak.

---

## 5. KC24 Managed-Attribute Trap (programmatic per-tenant minting)

Keycloak 24 **silently strips an unmanaged `tenant_id` attribute** when a user is created
via the **admin API** (imported users keep it). The template-seeded sample client in this
slice is unaffected (import is not subject to the strip). But when you provision per-tenant
machine clients **programmatically** (e.g. reusing the `KeycloakAdminClient` seam from #102),
you must first declare `tenant_id` **managed** on the realm (a `userProfile` config or
`unmanagedAttributePolicy: "ENABLED"`) — otherwise the minted client's tokens carry no
`tenant_id`, and RLS returns zero rows for every request. The realm `userProfile` change
itself is deferred to the [AI-1] provisioning slice.

---

## 6. Feeds [AI-1] MCP Auth Model (#203)

This scope taxonomy is the auth substrate for the [AI-1] Model Context Protocol server
(#203). MCP maps each exposed **tool capability** to one of these scopes — a read tool
to `catalog:read`/`orders:read`, a mutating tool to `catalog:write`/`orders:write`/
`customers:write` — and mints per-agent client-credentials tokens carrying exactly the
scopes that agent's granted capabilities require. **Phase 25 [AI-02] (#204) discharges the
write half of this mandate:** `orders:write` and `customers:write` are now enforced on the
create surfaces, the `integration-orders-rw` write credential is shipped, and the
`create_order` / `create_customer` MCP write tools ride these scopes (mirroring the
`catalog:write` gate). Still deferred: enforcing the reserved `orders:read`/`customers:read`
on the read surface, and order state-transition tools.

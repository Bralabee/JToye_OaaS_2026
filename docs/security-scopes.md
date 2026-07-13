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
| `orders:read`   | No (reserved) | **Defined only** to seed the [AI-1]/#203 MCP capability taxonomy. Not granted to any client, not enforced in this slice. |
| `orders:write`  | No (reserved) | **Defined only** to seed the [AI-1]/#203 MCP capability taxonomy. Not granted to any client, not enforced in this slice. |

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

Operators are **unchanged** because their `core-api` client default-grants both catalog
scopes, so every dashboard token transparently carries `catalog:write` after the realm
re-import (§4). No new realm role is invented; no negative/deny SpEL expression is used.

---

## 2. Realm Configuration (jtoye-dev)

Machine/integration clients live in the **`jtoye-dev`** realm (same issuer + `aud=core-api`
as the business API — the customer-facing `jtoye-customers` realm is B2C-only).

- **Client scopes:** `catalog:read`, `catalog:write`, `orders:read`, `orders:write` defined
  in `clientScopes[]`.
- **`core-api` (operators/dashboard):** `defaultClientScopes` gains `catalog:read` +
  `catalog:write` → operator tokens carry the write scope automatically.
- **`integration-catalog-ro` (sample machine client):** client-credentials only
  (`serviceAccountsEnabled: true`, `standardFlowEnabled: false`,
  `directAccessGrantsEnabled: false`, `publicClient: false`, `clientAuthenticatorType:
  client-secret`), `defaultClientScopes` = `catalog:read` **only** (no `catalog:write`). It
  carries two protocol mappers cloned from `core-api`:
  - `tenant-id-mapper` — emits the `tenant_id` claim from the service-account user attribute
    (the RLS carrier — without it RLS returns zero rows).
  - `core-api-audience-mapper` — **mandatory**: injects `aud=core-api`. Without it the #88
    `AudienceValidator` 401s the token at decode time, before any scope check runs.
- **Service-account user:** `service-account-integration-catalog-ro` is seeded with an
  `attributes.tenant_id` value. Template import preserves the attribute (it is **not**
  subject to the KC24 admin-API strip — see §5).

Secret: the client's `secret` is the envsubst placeholder `${INTEGRATION_CATALOG_RO_SECRET}`,
never a committed literal. It is wired into all three compose renderers
(`docker-compose.full-stack.yml`, `infra/docker-compose.yml`, `infra/docker-compose.hostnet.yml`),
both `.env.example` files, and `scripts/verify-env.sh` (fails loud when unset).

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
  `catalog:write` and will **403** on product writes until re-login. This is the same
  posture as #87/#88 and is asserted as an explicit test contract
  (`ScopedCatalogAccessIntegrationTest.noScopeTokenForbiddenOnCreate`).
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
(#203). MCP will map each exposed **tool capability** to one of these scopes — a read tool
to `catalog:read`/`orders:read`, a mutating tool to `catalog:write`/`orders:write` — and
mint per-agent client-credentials tokens carrying exactly the scopes that agent's granted
capabilities require. The `orders:*` scopes are defined now (unenforced) precisely so the
taxonomy is complete and MCP-consumable when #203 lands. Enforcing `orders:*` on the order
surface is a follow-up, mirroring the `catalog:write` gate shipped here.

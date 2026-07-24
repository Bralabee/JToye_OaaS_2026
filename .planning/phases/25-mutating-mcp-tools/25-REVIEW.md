---
phase: 25-mutating-mcp-tools
reviewed: 2026-07-24T00:00:00Z
depth: standard
files_reviewed: 19
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/order/OrderController.java
  - core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java
  - core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java
  - core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/CrossTenantMcpWriteRlsIntegrationTest.java
  - mcp-server/src/core-client.ts
  - mcp-server/src/server.ts
  - mcp-server/src/tools/create-order.ts
  - mcp-server/src/tools/create-customer.ts
  - mcp-server/src/tools/create-order.test.ts
  - mcp-server/src/tools/create-customer.test.ts
  - mcp-server/src/core-client.test.ts
  - infra/keycloak/realm-export.template.json
  - scripts/verify-env.sh
  - docker-compose.full-stack.yml
  - infra/docker-compose.yml
  - infra/docker-compose.hostnet.yml
  - .env.example
  - infra/.env.example
findings:
  critical: 1
  warning: 2
  info: 4
  total: 7
status: issues_found
---

# Phase 25: Code Review Report

**Reviewed:** 2026-07-24
**Depth:** standard
**Files Reviewed:** 19
**Status:** issues_found

## Summary

Phase 25 adds two `@PreAuthorize` write-scope gates (`SCOPE_orders:write` / `SCOPE_customers:write`)
on the order/customer *create* endpoints, two thin MCP write tools (`create_order` /
`create_customer`) that split a mandatory `idempotencyKey` into the `Idempotency-Key` header via a
new `corePost` forwarder, a template-seeded `integration-orders-rw` Keycloak client on an
`ACCESS_MACHINE_CLIENT_IDS` allowlist, and a NOSUPERUSER cross-tenant RLS proof.

The primary areas the brief flagged hold up well:

- **Secret handling** — no committed literals. The realm template uses `${INTEGRATION_ORDERS_RW_SECRET}`
  (and the other four render vars) as placeholders; `.env.example` / `infra/.env.example` ship
  `CHANGE_ME`, and `verify-env.sh` now lists `INTEGRATION_ORDERS_RW_SECRET` as required and rejects
  the `CHANGE_ME*` / `*secret-2026` weak patterns before boot. Clean.
- **SSRF safety of `corePost`** — base URL is a fixed compose env (`CORE_BASE_URL`), and both tools
  pass a hard-coded `path` constant. No caller input reaches the host/path.
- **Never-log-Bearer/PII** — handlers log only `{ tool, status }`; tests assert PII (email/name/phone)
  and the token never appear in log args or thrown-error messages. Verified.
- **Idempotency-key split** — `const { idempotencyKey, ...body } = args;` correctly strips the key from
  the body and forwards it as the header; tests confirm `body` has no `idempotencyKey` and the header
  carries it verbatim. The Java `@RequestHeader("Idempotency-Key")` matches.
- **`@PreAuthorize` expression strings** — `hasAuthority('SCOPE_orders:write')` /
  `hasAuthority('SCOPE_customers:write')` are correct: the real `JwtRolesAndScopesConverter` maps the
  `scope` claim to `SCOPE_*` authorities, exercised end-to-end on Testcontainers Postgres.

The material concern is **coverage, not correctness**: the least-privilege gate is applied to `create`
only, while ten other mutating order/customer endpoints (PUT/DELETE/state-transitions) remain reachable
by any authenticated token — including the committed read-only sample client. See CR-01.

## Critical Issues

### CR-01: Scope gates cover only `create`; delete/update/state-transition mutations stay open to any authenticated token (data-loss / least-privilege gap)

**File:** `core-java/src/main/java/uk/jtoye/core/order/OrderController.java:62`, `core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java:71` (and `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:154`)

**Issue:**
Phase 25 gates `POST /orders` behind `SCOPE_orders:write` and `POST /customers` behind
`SCOPE_customers:write`. But `SecurityConfig` ends with `auth.anyRequest().authenticated()` — no
route-level scope requirement — and the remaining **10 mutating endpoints carry no `@PreAuthorize`**:

- Orders: `PUT /orders/{id}`, `DELETE /orders/{id}`, and the six state transitions
  (`/submit`, `/confirm`, `/start-preparation`, `/mark-ready`, `/complete`, `/cancel`).
- Customers: `PUT /customers/{id}`, `DELETE /customers/{id}`.

Consequently a token that is *documented as read-only* can still mutate and destroy data. The
committed sample client `integration-catalog-ro` (realm-export.template.json:975) carries only
`catalog:read`, yet — being a valid `aud=core-api` token — it satisfies `anyRequest().authenticated()`
and can call `DELETE /api/v1/orders/{id}` and `DELETE /api/v1/customers/{id}` (RLS-scoped to its
tenant, so it deletes *its tenant's* orders/customers). The realm description asserts this client
proves "least-privilege catalog access: 200 on GET /api/v1/products, 403 on any product write" — that
claim is false for the order/customer surface. This is an authorization gap with a data-loss outcome
delivered alongside a phase whose stated contract is "least-privilege machine/integration access."

Note: the newly-added *create* gates are themselves correct; this is a coverage gap on the surrounding
(largely pre-existing) mutating endpoints that the phase's least-privilege narrative implicitly closes
but does not. It must be resolved — by extending the gates or by an explicit, recorded risk-acceptance
— before a read-only credential is issued to any third party.

**Fix:** Gate the remaining order/customer mutations to the same scope taxonomy, e.g.:
```java
// OrderController: destructive + lifecycle mutations
@PreAuthorize("hasAuthority('SCOPE_orders:write')")
@PutMapping("/{id}")            public ResponseEntity<OrderDto> updateOrder(...) { ... }

@PreAuthorize("hasAuthority('SCOPE_orders:write')")
@DeleteMapping("/{id}")         public ResponseEntity<Void> deleteOrder(...) { ... }

@PreAuthorize("hasAuthority('SCOPE_orders:write')")   // repeat on each /submit,/confirm,... transition
@PostMapping("/{id}/cancel")    public ResponseEntity<OrderDto> cancelOrder(...) { ... }
```
```java
// CustomerController
@PreAuthorize("hasAuthority('SCOPE_customers:write')")
@PutMapping("/{id}")            public ResponseEntity<CustomerDto> update(...) { ... }

@PreAuthorize("hasAuthority('SCOPE_customers:write')")
@DeleteMapping("/{id}")         public ResponseEntity<Void> delete(...) { ... }
```
If leaving them ungated is a deliberate scope decision, record it as an accepted risk in the phase docs
and update the `integration-catalog-ro` realm description so it no longer claims read-only isolation it
does not enforce.

## Warnings

### WR-01: `corePost` lets the extra-headers map override the verbatim Bearer (security-critical primitive)

**File:** `mcp-server/src/core-client.ts:66-76`

**Issue:**
In `corePost`, the caller-supplied `headers` map is spread **after** the fixed headers:
```ts
headers: {
  authorization: `Bearer ${bearer}`,
  accept: "application/json",
  "content-type": "application/json",
  ...headers,          // <-- can override authorization/accept/content-type
},
```
The function's own doc-comment promises "the Bearer is forwarded verbatim (core is the sole
validator)". Because `...headers` wins on key collision, a caller passing `authorization` (or
`content-type`) in the extra-headers map would silently override that invariant. Today both call sites
pass only `{ "Idempotency-Key": key }`, so this is latent — but `corePost` is the shared, security-
sensitive forwarding primitive and should not be overridable in the direction that breaks its own
contract.

**Fix:** Spread the caller headers first, then set the fixed ones so they cannot be overridden:
```ts
headers: {
  ...headers,
  authorization: `Bearer ${bearer}`,
  accept: "application/json",
  "content-type": "application/json",
},
```

### WR-02: `not403()` matcher false-greens on 401/500 in the positive scope-gate assertions

**File:** `core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java:171-178`

**Issue:**
`writeScopedTokenNot403OnOrderCreate` / `...OnCustomerCreate` assert only "status != 403". That passes
for **any** non-403 status — including 401 (authentication regression), 500 (server error), or a body
that fails validation — so the "write-scoped token passes the gate" proof can go green even if the
`scope`→`SCOPE_*` authority mapping silently broke or the request never reached the controller. The
negative cases (403) are precise; the positive cases are not.

**Fix:** Assert the exact expected outcome for a valid write-scoped token, e.g. `status().isNotFound()`
(the random shopId 404s downstream) or `status().isCreated()` for a seeded shop, rather than a blanket
"not 403". At minimum also assert `status != 401` and `status < 500`.

## Info

### IN-01: `idempotencyKey` has no charset constraint — an unsafe key surfaces as a misleading "unreachable" error

**File:** `mcp-server/src/tools/create-order.ts:57-63`, `mcp-server/src/tools/create-customer.ts:51-57`

**Issue:** `idempotencyKey` is validated as `z.string().min(1).max(64)` with no character restriction. A
value containing CR/LF or non-token characters passes Zod but makes undici throw on
`fetch(... headers: { "Idempotency-Key": value })`, which the handler's `catch` converts to the generic
"Core API unreachable or timed out" — masking what is really a bad-input problem (and never reaching
core's own validation). Not a header-injection vector (undici rejects it), but poor DX.

**Fix:** Constrain the key to a safe token charset, e.g. `.regex(/^[A-Za-z0-9._-]{1,64}$/)`, so an
invalid key fails fast at the schema boundary with a clear message.

### IN-02: `integration-orders-rw` sets `fullScopeAllowed: true`

**File:** `infra/keycloak/realm-export.template.json:1056`

**Issue:** For a least-privilege client-credentials machine client, `fullScopeAllowed: true` (cloned from
`integration-catalog-ro:999`) opts the token into the client's full role scope rather than an explicit
allow-list. In practice the service account holds only `default-roles-jtoye-dev` so there is no role
escalation today, but `fullScopeAllowed: false` is the hardened default and better matches the phase's
least-privilege intent.

**Fix:** Set `"fullScopeAllowed": false` on both integration clients and rely on the explicit
`defaultClientScopes` list (`orders:write`, `customers:write`, `catalog:read`) for authority.

### IN-03: handler `catch` swallows all errors as "unreachable/timed out", masking non-network faults

**File:** `mcp-server/src/tools/create-order.ts:94-102`, `mcp-server/src/tools/create-customer.ts:85-93`

**Issue:** The bare `catch { ... }` maps *every* thrown error (including a programming bug such as a
`TypeError`) to `"Core API unreachable or timed out"` and a `warn` with no error detail. This is
deliberate for PII/token safety, but it also removes any signal that a non-transport bug occurred,
which can hide real defects in operation.

**Fix:** Keep the sanitized user-facing message, but bind the error and log a coarse, PII-free
classification (e.g., `err instanceof DOMException && err.name === "TimeoutError"` vs other) so genuine
bugs remain observable without leaking the token or args.

### IN-04: OpenApiConfig fallback `issuer-uri` default is stale (`localhost:8081`) and the derived Swagger tokenUrl is unreachable from a browser

**File:** `core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java:30-31`, `:114`

**Issue:** The `catalog-scopes` client-credentials `tokenUrl` is derived from `issuerUri`, whose inline
fallback is `http://localhost:8081/realms/jtoye-dev` while `application.yml` and compose use `:8085`
(public) / `keycloak:8080` (internal). The fallback is effectively dead (the property is always
supplied), and under compose the resolved value is the *internal* `keycloak:8080` host — not reachable
by a browser using the Swagger "Authorize" client-credentials flow. Pre-existing and dev-only (Swagger
is `@Profile("!prod")`), surfaced here because Phase 25 extends this scheme with `orders:write` /
`customers:write`.

**Fix:** Either drop the misleading `:8081` inline default (the property is mandatory in `application.yml`)
or derive the Swagger `tokenUrl` from the browser-facing public issuer rather than the internal JWKS host.

---

_Reviewed: 2026-07-24_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

---
phase: 25-mutating-mcp-tools
plan: "03"
subsystem: mcp-server + core-security
tags: [mcp, write-tools, idempotency, ssrf, force-rls, cross-tenant, pii]
requires:
  - "25-01 orders:write / customers:write @PreAuthorize gates (the scopes these tools ride)"
  - "Phase 20 read slice: coreGet / toToolError / read-orders.ts skeleton / buildServer factory"
  - "IdempotencyService.execute reserve-first store (V50), 1..64 key bound"
provides:
  - "corePost SSRF-safe POST forwarder (sibling of coreGet)"
  - "create_order + create_customer MCP write tools (D-07), Idempotency-Key mandatory (D-05)"
  - "cross-tenant create_order RLS proof under the NOSUPERUSER rls_test_role (write-side AC-2)"
affects:
  - "25-04 docs-freshness reconcile (2 new vitest files) + live write E2E (D-12)"
tech-stack:
  added: []
  patterns:
    - "Thin corePost forwarder mirroring coreGet: fixed CORE_BASE_URL (SSRF), verbatim Bearer, 10s timeout, never logs body/token"
    - "idempotencyKey split OUT of the JSON body -> Idempotency-Key header (no non-idempotent path, D-05)"
    - "NOSUPERUSER rls_test_role SET LOCAL ROLE downgrade to prove FORCE RLS (superuser bootstrap bypasses it)"
    - "auth==null internal-caller bypass lets a service-level create reach RLS without a SecurityContext, isolating RLS as the only boundary under test"
key-files:
  created:
    - mcp-server/src/tools/create-order.ts
    - mcp-server/src/tools/create-customer.ts
    - mcp-server/src/tools/create-order.test.ts
    - mcp-server/src/tools/create-customer.test.ts
    - core-java/src/test/java/uk/jtoye/core/security/CrossTenantMcpWriteRlsIntegrationTest.java
  modified:
    - mcp-server/src/core-client.ts
    - mcp-server/src/server.ts
    - mcp-server/src/core-client.test.ts
decisions:
  - "Kept items (order) + name/email (customer) REQUIRED in the tool schemas to match the runtime @NotEmpty/@NotBlank constraints; the OpenAPI snapshot under-reports them (springdoc does not propagate those to `required`) — a create without them is a guaranteed 400, so a self-describing schema is the better agent DX (D-08 intent)"
  - "Did NOT duplicate idempotent-replay coverage: OrderIdempotencyIntegrationTest already proves orders.create replay (original + zero duplicates + concurrent + 422); cited it rather than re-proving (anti-false-green), and added only the genuinely-missing cross-tenant write proof"
  - "AI-02 kept PENDING — 25-04 (docs reconcile + live E2E, D-12) is the last contributing plan"
metrics:
  duration: "~22min"
  tasks: 3
  files: 8
  completed: "2026-07-24"
---

# Phase 25 Plan 03: Mutating MCP Write Tools + Cross-Tenant RLS Proof Summary

Extended the Phase-20 read-only MCP server with the two write tools — `create_order` + `create_customer` (snake_case, D-07) — as thin, SSRF-safe, PII-safe `corePost` forwarders that mandate the `Idempotency-Key` (no non-idempotent path, D-05) and surface core's RFC 7807 as sanitized tool errors, then proved the write-side cross-tenant boundary under the NOSUPERUSER `rls_test_role`: a tenant-A token targeting a tenant-B `shopId` resolves 404 because FORCE RLS hides the foreign shop.

## What Was Built

### Task 1 (`89ca656`, feat) — corePost + the two write tools

- **`corePost(path, bearer, body, headers)`** — an exact POST sibling of `coreGet` in `core-client.ts`: fixed `CORE_BASE_URL` (SSRF guard, T-25-08), verbatim Bearer, `accept` + `content-type: application/json`, the extra-headers map spread (for `Idempotency-Key`), `JSON.stringify(body)`, the same 10s `AbortSignal.timeout`, and the same `{ ok, status, contentType, body }` return. `r.ok` is true for 201 so only a non-2xx routes to `toToolError`. Never logs body/token. `coreGet` is byte-for-byte untouched (no removed lines in the diff); `errors.ts` untouched (`toToolError` reused verbatim, D-06).
- **`create-order.ts` / `create-customer.ts`** — mirror `read-orders.ts` verbatim: raw-Zod `inputSchema` (NOT `z.object` — the SDK v1.29.0 contract, comment preserved), a FIXED path constant (`/api/v1/orders`, `/api/v1/customers` — no caller input in the path), a factory `create{Order,Customer}Handler(bearer)` that does `const { idempotencyKey, ...body } = args;` and calls `corePost(PATH, bearer, body, { "Idempotency-Key": idempotencyKey })`, a `logger.info({ tool, status })` (status ONLY, never body/args — PII), `!res.ok → toToolError(res)`, the DTO as JSON text on success, and a sanitized `isError` catch. Field shapes verified against `docs/api/openapi-snapshot.json` (D-08).
- **`server.ts`** — `buildServer(bearer)` now calls `registerCreateOrder` + `registerCreateCustomer` beside the three read tools.

### Task 2 (`171eca3`, test) — vitest for the tools + corePost

- **`create-order.test.ts` / `create-customer.test.ts`** mirror `read-orders.test.ts` (hoisted pino spies, `vi.mock("../core-client.js")`): assert `corePost` is invoked with the **fixed path** + the camelCase body with **`idempotencyKey` STRIPPED** + the `{ "Idempotency-Key": key }` header; assert 403 (no-scope) / 409 (in-flight) / 422 (same-key different-body) `problem+json` delegate to `toToolError` (isError, sanitized, status in the message); assert a thrown fetch fault → `"Core API unreachable or timed out"` with the bearer NEVER in the message; and assert the fixture PII (email/name/phone) + the field key `customerEmail` NEVER appear in any serialized pino call (T-25-09). Added input-schema cases proving `idempotencyKey` is REQUIRED (1..64), `items` non-empty, and `name`/`email` required.
- **`core-client.test.ts`** extended with `corePost` cases: `content-type: application/json`, the `Idempotency-Key` header, the verbatim Bearer, the JSON-serialized body, `ok===true` for 201, and token-never-in-a-thrown-error.
- **mcp-server vitest: 47 passed (8 files)** (was 27/6); `npm run build` (tsc) clean.

### Task 3 (`671b54a`, test) — cross-tenant write RLS proof (NOSUPERUSER)

`CrossTenantMcpWriteRlsIntegrationTest` (`@Tag("testcontainers")`) mirrors `IdempotencyKeysRlsPolicyIntegrationTest`'s downgrade harness (`rls_test_role` NOSUPERUSER NOBYPASSRLS, `SET LOCAL ROLE` inside the transaction). Three methods, **3/3 GREEN**:

1. **`crossTenantCreateOrder_foreignShopId_resolvesNotFound`** — seeds a shop under tenant B (as the superuser bootstrap), then under tenant A's GUC + the downgraded role: `shopRepository.findById(tenantBShop)` is empty (RLS hides it) AND `orderService.createOrder(req with foreign shopId)` throws `ResourceNotFoundException` ("Shop not found…") → the 404. The MCP write credential cannot reach a foreign shop (write-side AC-2).
2. **`superuserBypassesForceRls_provesTheDowngradeIsLoadBearing`** — the documented falsifiability: WITHOUT the downgrade (bootstrap SUPERUSER), the identical tenant-A→tenant-B lookup resolves the row (`isPresent()`). This is the RED the isolation proof would show under a superuser role — the NOSUPERUSER downgrade is what makes proof #1 real.
3. **`createCustomer_landsUnderCallerTenantOnly`** — under tenant A + the downgraded role, `customerService.createCustomer` returns a DTO tagged with tenant A, and the row is invisible under tenant B's GUC (customers have no foreign-id vector; the tenant is implicit from the GUC).

Service methods are invoked with no `Authentication` on the thread: `ShopAccessService.onRequest()` returns immediately and `isGroupAdmin()` short-circuits true via `isInternalCaller()`, so the VSA-02 shop gate passes without touching `shop_staff` — leaving RLS as the sole boundary under test (the point of the proof).

## RED/GREEN (falsifiability)

- **Cross-tenant RLS proof is genuinely falsifiable.** Method #2 (`superuserBypassesForceRls…`) passes by asserting the foreign shop IS visible under the un-downgraded superuser role — the exact RED that method #1 would show if run without the NOSUPERUSER downgrade (a superuser bypasses FORCE RLS and a cross-tenant create would falsely succeed). Together they are the RED/GREEN pair: proof #1 is GREEN only because RLS is genuinely enforced under `rls_test_role`.
- **Tool tests are behaviour-real, not tautologies.** `toToolError` runs for real (only `core-client.js` is mocked), so the 403/409/422 delegation is exercised end-to-end; the PII-never-logged assertion serializes every pino call and fails if any customer field leaks.

## Verification

- `cd mcp-server && npm run build && npm test` — tsc clean; **47 passed (8 files)**.
- `./gradlew :core-java:integrationTest --tests '*CrossTenantMcpWriteRls*'` — **BUILD SUCCESSFUL**, TESTSUMMARY total=3 passed=3 failed=0 skipped=0 (confirmed per-method via a test-event listener; Testcontainers Postgres 15, real Flyway schema + FORCE RLS).
- `git diff mcp-server/src/core-client.ts` — `coreGet` unchanged (only `corePost` added); `git diff mcp-server/src/errors.ts` — empty (`toToolError` reused verbatim, D-06).

## Threat Model Discharge

| Threat ID | Disposition | How mitigated this plan |
|-----------|-------------|--------------------------|
| T-25-08 (SSRF) | mitigate | `corePost` uses the fixed `CORE_BASE_URL` + fixed path constants (mirror coreGet); unit-proven |
| T-25-09 (PII disclosure via logs/errors) | mitigate | Tools log `{tool,status}` only; `toToolError` sanitizes; vitest asserts email/name/phone + `customerEmail` never logged |
| T-25-10 (duplicate/replayed mutation) | mitigate | `idempotencyKey` REQUIRED (Zod .min(1).max(64)) → always forwarded as `Idempotency-Key`; no non-idempotent path (unit-proven the key is split to the header). Replay itself proven by OrderIdempotencyIntegrationTest |
| T-25-11 (cross-tenant write) | mitigate | FORCE RLS; proven under the NOSUPERUSER `rls_test_role` (Task 3); superuser Testcontainers would falsely pass (method #2 shows the RED) |
| T-25-12 (masked/leaky authz error) | mitigate | `toToolError` maps 401/403/404/409/422/500 to sanitized tool errors, reused verbatim; 403/409/422 delegation unit-proven |
| T-25-SC (package installs) | accept | No new packages; `npm ci` is dependency restore — Package Legitimacy Gate N/A |

## Deviations from Plan

Both are documented decisions within the plan's explicit discretion (D-08 / "may reuse existing coverage"), not unplanned fixes:

**1. [D-08 required-ness] Tool schemas keep `items` (order) and `name`/`email` (customer) REQUIRED, though the OpenAPI snapshot omits them from `required`.** The snapshot's `required` array lists only `shopId` for `CreateOrderRequest` and nothing for `CreateCustomerRequest`, because springdoc does not propagate `@NotEmpty` on a collection or `@NotBlank` on a record component to the schema `required` set. At runtime those constraints DO reject the missing values (guaranteed 400). D-08 says "follow the snapshot and note it" when required-ness differs — here I followed the **runtime** contract (matching the RESEARCH §2 / PLAN interfaces field tables) and note the reason: a self-describing schema that prevents a guaranteed 400 is better agent DX than a loose passthrough. Field NAMES and types match the snapshot exactly (camelCase `shopId`/`customerId`/…/`items[{productId,quantity}]`, `name`/`email`/`phone`/`allergenRestrictions`).

**2. [Anti-false-green] Idempotent-replay cited, not duplicated.** Task 3's action allowed adding a replay assertion "if not already covered." It IS covered: `OrderIdempotencyIntegrationTest` proves `orders.create` same-key replay → the ORIGINAL order + zero duplicates, the concurrent race → exactly one row, and same-key/different-body → 422; its class comment explicitly defers the cross-tenant RLS proof to a separate test — precisely the test created here. `IdempotencyKeysRlsPolicyIntegrationTest` proves the reserve-first store is FORCE-RLS tenant-scoped. Customer create rides the identical `IdempotencyService.execute` path. I added only the genuinely-missing cross-tenant write proof rather than re-proving replay.

## Requirement Status

**AI-02 kept PENDING (anti-false-green).** This plan delivers AI-02's core deliverable — the write tools + the idempotent-header mandate + the cross-tenant RLS proof under the MCP credential — but 25-04 is the last contributing plan (docs-freshness `--write` reconcile of the two new vitest files + the new Java tests, write-surface docs, and the manual/scripted live write E2E on the rebuilt dev stack, D-12). Marking AI-02 complete now would be a false-green, consistent with 25-01 holding it PENDING for the same reason.

## Known Stubs

None. Both tools are real forwarders returning the created DTO; no placeholder/empty-value surfaces introduced.

## Self-Check: PASSED

- Files: all 5 created + 3 modified present (verified on disk).
- Commits: `89ca656` (feat), `171eca3` (test), `671b54a` (test) all present in git history.
- Tests: mcp-server vitest 47/47; CrossTenantMcpWriteRls 3/3 (per-method SUCCESS confirmed).

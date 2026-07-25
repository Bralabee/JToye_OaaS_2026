# Phase 25 — Code Review Remediation

**Date:** 2026-07-24
**Branch:** `feature/phase-25-mutating-mcp-tools`
**Resolves:** CR-01 (blocker), WR-01, WR-02 from `25-REVIEW.md` + the latent integration
regression Phase 25's create gates already shipped.
**Deferred (recorded, not fixed):** IN-01, IN-02, IN-03, IN-04.

## What changed

### CR-01 (blocker) — gate the full order/customer mutating surface

Phase 25 gated only the two `create` endpoints; `SecurityConfig` ended with
`anyRequest().authenticated()`, so a documented read-only credential
(`integration-catalog-ro`, `catalog:read` only) could still `DELETE`/`PUT` orders and customers
within its tenant. All remaining mutations now carry the same `@PreAuthorize` write-scope gate:

- `OrderController` (`SCOPE_orders:write`): `updateOrder` (PUT), `deleteOrder` (DELETE),
  `submitOrder`, `confirmOrder`, `startPreparation`, `markOrderReady`, `completeOrder`,
  `cancelOrder`.
- `CustomerController` (`SCOPE_customers:write`): `update` (PUT), `delete` (DELETE).

The user chose to gate **all** mutations (not just create). Runtime is unaffected: the dashboard
orders / KDS / customers pages all use the `core-api` NextAuth token, which default-grants both
write scopes; storefront guest checkout uses `/public/**` + the service layer (bypasses the gated
controller); edge-go calls none of these endpoints. No `frontend/`, `edge-go/`, or realm-export
changes were required or made.

### The 10 pre-broken integration tests (repaired)

The create-gate executor only ran its own new test, leaving the full `integrationTest` task RED.
All 10 are now green, with each test's original tenant mechanism and behavioural assertions
preserved:

- `OrderIdempotencyIntegrationTest` (3) — direct proxied-bean calls need a SecurityContext under
  `@EnableMethodSecurity`. Class-level `@WithMockUser(authorities = {ROLE_admin, SCOPE_orders:write})`:
  the write scope satisfies the gate, and `ROLE_admin` (implicit GROUP_ADMIN) preserves the
  pre-Phase-25 internal-caller access that `OrderService.createOrder`'s VSA-02 shop gate requires.
  The concurrent worker threads install the same context explicitly (ThreadLocal does not propagate
  to the pool).
- `CustomerIdempotencyIntegrationTest` (1) — class-level `@WithMockUser(authorities = SCOPE_customers:write)`
  (customer create has no shop dependency).
- `CustomerControllerIntegrationTest` (5) — class-level `@WithMockUser(authorities = SCOPE_customers:write)`;
  the two `@Valid`-400 tests still 400 (validation precedes the gate — D-04).
- `LocationHeaderContractTest` (1) — `operatorJwt()` now also carries `SCOPE_customers:write`.

### New gate coverage (CR-01) + WR-02

`ScopedWriteAccessIntegrationTest` gains MockMvc coverage for the surrounding mutations:
403-without-scope and exact-status-with-scope on order `DELETE` + `cancel` (transition) and
customer `PUT` + `DELETE`. All requests are fully valid so a 400 cannot mask the 403 (D-04).

**WR-02:** the blanket `not403()` matcher is removed; every positive scope-gate assertion now pins
the EXACT expected status — `404` for the random-id create/mutation cases, `201` for the
dependency-free customer create — so an auth-mapping regression that 401s/500s can no longer
false-green.

### WR-01 — `corePost` header precedence

`mcp-server/src/core-client.ts` now spreads the caller's extra headers **first** and applies
`authorization` / `accept` / `content-type` **last**, so an extra-headers entry can never override
the verbatim Bearer. New `core-client.test.ts` case asserts a forged `authorization`/`content-type`
in the extra-headers map loses to the real token (Idempotency-Key still forwarded).

### Docs + metrics

- `docs/security-scopes.md`: the write scopes now gate **all** order/customer mutations (create +
  update + delete + the six order state-transitions), with the CR-01 rationale recorded.
- `docs/metrics.json` regenerated via `scripts/docs-freshness.sh --write`; prose in `CLAUDE.md`
  and `AGENTS.md` reconciled to the new totals.

| metric | before | after |
|--------|--------|-------|
| java_test_methods | 1135 | 1143 (+8 gate tests) |
| mcp_test_blocks | 47 | 48 (+1 WR-01 test) |
| total_logical_invocations | 1675 | 1684 |

## Verification evidence

- `mcp-server` — `npm test`: **48/48 passed**.
- `scripts/docs-freshness.sh` — **exit 0** ("metrics match source: 1684").
- Targeted integration set (`OrderIdempotency`, `CustomerIdempotency`, `CustomerController`,
  `LocationHeaderContract`, `ScopedWriteAccess`): **all green** (30 tests; the 3 initial
  OrderIdempotency failures were the missing GROUP_ADMIN and were fixed by adding `ROLE_admin`).
- Full `./gradlew :core-java:test :core-java:integrationTest`: _see FULL-SUITE RESULT below._

### FULL-SUITE RESULT

`./gradlew :core-java:test :core-java:integrationTest` — **BUILD SUCCESSFUL in 40m 18s**.

- Aggregated JUnit XML across both tasks: **tests=1151, failures=0, errors=0** (no `<failure>`/
  `<error>` element in any result file).
- `OpenApiSnapshotTest`: **1 test, 0 failures** — the `@PreAuthorize` additions do not change the
  OpenAPI document, so the committed snapshot is unchanged (no `updateOpenApiSnapshot` needed).
- The 10 previously-RED tests (`OrderIdempotencyIntegrationTest` ×3, `CustomerIdempotencyIntegrationTest`
  ×1, `CustomerControllerIntegrationTest` ×5, `LocationHeaderContractTest` ×1) are green, and the two
  `@Valid`-400 customer tests still pass.
- `mcp-server` `npm test`: **48/48 passed**. `scripts/docs-freshness.sh`: **exit 0** (1684).

No changes were made to `frontend/`, `edge-go/`, or `infra/keycloak/realm-export*`; `STATE.md` /
`ROADMAP.md` were left untouched (orchestrator owns phase closure).

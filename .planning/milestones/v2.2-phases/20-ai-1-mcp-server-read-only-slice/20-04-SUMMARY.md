---
phase: 20-ai-1-mcp-server-read-only-slice
plan: 04
subsystem: ci
tags: [mcp, docs-freshness, metrics, e2e, rls, cross-tenant, keycloak, client-credentials, password-grant, bearer-passthrough, sse, streamable-http]

# Dependency graph
requires:
  - phase: "20-01 (walking slice)"
    provides: "mcp-server/ workspace + stateless Streamable HTTP POST /mcp + GET /health on :9100 — the surface the e2e scripts drive"
  - phase: "20-02 (widen tools)"
    provides: "list_products + read_orders tools registered on buildServer — the tools the RLS + read scripts assert THROUGH"
  - phase: "20-03 (packaging + tenant-B seed)"
    provides: "tenant-B probe SKU TENANTB-PROBE-1 (slug tenant-b-probe) + compose mcp-server service — the disjoint non-empty fixture the RLS proof keys on"
  - phase: "#206 scoped machine credentials"
    provides: "integration-catalog-ro client-credentials client + INTEGRATION_CATALOG_RO_SECRET — the read happy-path reference credential"
  - phase: "#88 audience gate"
    provides: "core-api audience mapper — a 200 through the tool implicitly confirms aud=core-api"
provides:
  - "scripts/docs-freshness.sh — NEW mcp test family (path ^mcp-server/(src|test)/.*\\.(test|spec)\\.ts$) folded into TOTAL + mcp_test_blocks/mcp_test_files JSON keys"
  - "docs/metrics.json — regenerated via --write: total_logical_invocations 1208 -> 1231 (23 vitest blocks across 6 files), schema stays 50"
  - "CLAUDE.md — testing-standard prose names the MCP family and the new 1231 total"
  - "mcp-server/scripts/e2e.sh — live read happy-path: integration-catalog-ro token -> POST /mcp list_products -> assert 200 + non-empty non-error rows"
  - "mcp-server/scripts/e2e-rls.sh — live cross-tenant RLS proof through the MCP tool: two password-grant tokens, disjoint non-empty bidirectional sets + order-scope negative"
affects: [20-05-live-e2e, docs-freshness, mcp-server, CLAUDE.md]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "New docs-freshness test family = clone the PLAYWRIGHT_* block: same \\b(it|test)\\( content-regex (matches vitest), only the path family + JSON keys are new; --write is the metrics.json arbiter"
    - "MCP live proof calls tools/call DIRECTLY (no initialize handshake) on the stateless Streamable HTTP transport; reply is an SSE 'data:' line -> jq .result.content[0].text, errors at .result.isError"
    - "Cross-tenant RLS proven at the HTTP boundary THROUGH the tool with two genuinely tenant-scoped tokens against disjoint seeded markers (identifiers only, never PII)"
    - "Scripts fail fast on missing required env (INTEGRATION_CATALOG_RO_SECRET / KC_SEED_USER_PASSWORD); never hardcode a secret; log status + counts/markers only (T-20-01)"

key-files:
  created:
    - "mcp-server/scripts/e2e.sh"
    - "mcp-server/scripts/e2e-rls.sh"
  modified:
    - "scripts/docs-freshness.sh"
    - "docs/metrics.json"
    - "CLAUDE.md"

key-decisions:
  - "Empirically probed the running mcp-server (with mock core) to lock the exact live request: tools/call works directly in stateless mode, reply is text/event-stream, Accept MUST include text/event-stream (else 406) — scripts encode these facts rather than assuming"
  - "Password grant against the CONFIDENTIAL core-api client sends client_secret CONDITIONALLY (when KEYCLOAK_CLIENT_SECRET is set) — core-api is publicClient:false/client-secret, so the token endpoint requires client auth; conditional keeps the existing load-test.sh idiom working while making the live run robust (Rule 2)"
  - "list_products called with size=100 in the RLS proof so tenant A's full catalogue returns in one page, making the marker-present + probe-absent assertions complete rather than page-dependent"
  - "Order-scope negative uses the plan's fallback branch: tenant B seeds no orders, so assert tenant A's read_orders view carries no tenant-B marker (no cross-tenant order leak) instead of a concrete B order id"
  - "Both scripts functionally smoke-tested against mock Keycloak + mock core + the real mcp-server (beyond the plan's bash -n) — happy path PASSES and a simulated leak correctly FAILS (exit 1), proving the assertions are not vacuous"

patterns-established:
  - "docs-freshness new-family extension = clone PLAYWRIGHT_* + one path regex + two JSON keys + one TOTAL term; --write reconciles the manifest"
  - "MCP tool live-proof harness: token mint (host :8085) -> SSE tools/call -> jq-parse .result -> assert on identifiers"

requirements-completed: [AI-1]

# Metrics
duration: 30min
completed: 2026-07-13
---

# Phase 20 Plan 04: AI-1 MCP Server — Docs-Freshness Family & Live E2E/RLS Scripts Summary

**The MCP vitest suite is now visible to the CI gate (a new `mcp` test family folds 23 vitest blocks into `total_logical_invocations`, bumping the manifest 1208 -> 1231, schema unchanged at V50), and the two live proofs are scripted: `e2e.sh` drives the read happy-path with the `integration-catalog-ro` reference credential, and `e2e-rls.sh` proves disjoint, non-empty, bidirectional cross-tenant isolation THROUGH the `list_products` tool with two genuinely tenant-scoped password-grant tokens — both authored, syntax-valid, and additionally smoke-tested against mocks (happy path green, simulated leak red), ready for the 20-05 live run.**

## Performance

- **Duration:** ~30 min
- **Completed:** 2026-07-13
- **Tasks:** 3 (all `type="auto"`, fully autonomous)
- **Files:** 2 created (e2e.sh, e2e-rls.sh) + 3 modified (docs-freshness.sh, metrics.json, CLAUDE.md)

## Accomplishments

- **Task 1 — docs-freshness mcp family:** Cloned the `PLAYWRIGHT_*` block in `scripts/docs-freshness.sh` to add `MCP_TEST_BLOCKS`/`MCP_TEST_FILES` anchored to `^mcp-server/(src|test)/.*\.(test|spec)\.ts$` (the existing `\b(it|test)\(` content-regex already matches vitest), folded `MCP_TEST_BLOCKS` into `TOTAL`, and emitted `mcp_test_blocks`/`mcp_test_files` in the COMPUTED JSON. Regenerated `docs/metrics.json` via `--write`: **1208 -> 1231** (23 vitest blocks across 6 files), `schema_version` stays **50**. Verified the count against reality: `npm ci` (committed lockfile only, 0 vulnerabilities, no new deps) + `npx vitest run` reports **23 tests / 6 files**, exactly matching the regex. Updated the CLAUDE.md testing-standard prose to name the MCP family and the new total. The gate (`bash scripts/docs-freshness.sh`, no `--write`) exits 0.
- **Task 2 — `e2e.sh` read happy-path:** Preflights the required `INTEGRATION_CATALOG_RO_SECRET` + MCP `/health`, mints a `client_credentials` token for `integration-catalog-ro` from the host endpoint `:8085` (split-horizon `iss=localhost:8085`), then POSTs a `tools/call` for `list_products` to `/mcp` (Accept includes `text/event-stream`), parses the SSE `data:` line, and asserts HTTP 200 + `isError` absent + a non-empty, shape-agnostic row count. Fails fast with actionable hints (`invalid_client` -> realm not re-imported). Logs status + row count only.
- **Task 3 — `e2e-rls.sh` cross-tenant RLS proof:** Mints TWO direct-access `password`-grant tokens (`tenant-a-user`, `tenant-b-user`) against the confidential `core-api` client (audience mapper -> a 200 confirms the #88 aud gate), then through the `list_products` MCP tool asserts the disjoint, bidirectional, non-empty proof — A contains `MAK-JOL` and NOT `TENANTB-PROBE-1`; B contains `TENANTB-PROBE-1` and NOT `MAK-JOL` — plus an order-scope negative (tenant A's `read_orders` view carries no tenant-B marker). Compares identifiers only; never echoes a token or PII body.

## Task Commits

1. **Task 1:** `17dda9c` (chore) — docs-freshness mcp family + metrics.json 1231 + CLAUDE.md prose
2. **Task 2:** `b202d95` (feat) — `mcp-server/scripts/e2e.sh` read happy-path
3. **Task 3:** `9b88528` (feat) — `mcp-server/scripts/e2e-rls.sh` cross-tenant RLS proof

## Files Created/Modified

- `scripts/docs-freshness.sh` — new `MCP_TEST_BLOCKS`/`MCP_TEST_FILES` family (path `^mcp-server/(src|test)/.*\.(test|spec)\.ts$`), folded into `TOTAL`, emits `mcp_test_blocks`/`mcp_test_files`.
- `docs/metrics.json` — regenerated: `mcp_test_blocks: 23`, `mcp_test_files: 6`, `total_logical_invocations: 1231`, `schema_version: 50`.
- `CLAUDE.md` — testing constraint now reads "1231 logical invocations" and enumerates "+ 23 MCP-server vitest `it/test` blocks across 6 files under `mcp-server/`".
- `mcp-server/scripts/e2e.sh` (147 lines) — read happy-path: `integration-catalog-ro` client-credentials -> `list_products` -> 200 + non-empty non-error rows; fail-fast preflight; PII-safe logging.
- `mcp-server/scripts/e2e-rls.sh` (222 lines) — cross-tenant RLS proof: two password-grant tokens -> disjoint non-empty bidirectional `list_products` sets + order-scope negative; identifiers only.

## Decisions Made

- **Locked the live request format empirically, not by assumption.** Ran the real mcp-server against a mock core and probed: `tools/call` succeeds directly (no `initialize` handshake needed in stateless mode); the reply is `text/event-stream` (an SSE `data:` line), so the scripts parse `.result.content[0].text` / `.result.isError` from that line; and the request `Accept` must include `text/event-stream` (a plain `application/json` Accept returns **406**). These facts are encoded in both scripts.
- **Conditional `client_secret` for the confidential `core-api` password grant.** `core-api` is `publicClient:false` with a `client-secret` authenticator, so the token endpoint requires client authentication. The existing `load-test.sh` idiom omits the secret; to keep the live run robust either way, the RLS script sends `client_secret=$KEYCLOAK_CLIENT_SECRET` only when that env var is present. (Documented as a Rule 2 deviation below.)
- **`size=100` on the RLS `list_products` call** so tenant A's full DemoDataSeeder catalogue returns in one page — the "contains `MAK-JOL`" + "does NOT contain `TENANTB-PROBE-1`" assertions become complete, not page-dependent.
- **Order-scope negative via the plan's fallback branch.** Tenant B seeds only a probe product (no orders — RESEARCH Q#2), so instead of a concrete tenant-B order id the script asserts tenant A's `read_orders` view carries no tenant-B marker (`TENANTB-PROBE-1` / `tenant-b-probe`).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical correctness] Conditional client authentication on the core-api password grant**
- **Found during:** Task 3
- **Issue:** The plan's `<interfaces>` note and the existing `infra/load-testing/load-test.sh` mint the tenant password-grant token with only `client_id=core-api` (no client secret). But `core-api` in `infra/keycloak/realm-export.template.json` is a **confidential** client (`publicClient: false`, `clientAuthenticatorType: client-secret`); a confidential client's token endpoint requires client authentication, so a bare password grant can return `invalid_client` and break the live 20-05 run.
- **Fix:** `e2e-rls.sh` adds `--data-urlencode "client_secret=$KEYCLOAK_CLIENT_SECRET"` to the password grant **only when** `KEYCLOAK_CLIENT_SECRET` is set in the env — authenticating the confidential client when the secret is available while preserving the secret-less `load-test.sh` idiom when it is not. Never a literal (config-injection rule).
- **Files modified:** `mcp-server/scripts/e2e-rls.sh`
- **Verification:** Functional smoke test (mock Keycloak reflecting the username + mock core keyed on the forwarded Bearer) passed with the secret unset; the acceptance greps (`grant_type=password`, `client_id=core-api`, both usernames) still match.
- **Committed in:** `9b88528`

**Total deviations:** 1 auto-fixed (Rule 2). No architectural changes (Rule 4). No authentication gates (scripts author credentials; the live mint happens in 20-05). No supply-chain checkpoint (only `npm ci` from the committed lockfile — 0 new deps, T-20-SC honoured).

**Beyond the plan's `bash -n` verify (added confidence, not a deviation):** both scripts were functionally executed against a mock Keycloak token endpoint + a tenant-aware mock core + the real mcp-server. `e2e.sh` and the isolation path of `e2e-rls.sh` PASS end-to-end; a deliberately leaky mock core (tenant A served a tenant-B row) makes `e2e-rls.sh` FAIL with exit 1 — proving the RLS assertions are load-bearing, not vacuous.

## Issues Encountered

None blocking. The one substantive finding (confidential `core-api` needs client auth) is captured as the Rule 2 deviation above. The `docs-freshness` count (23) was cross-checked against a live `vitest run` (23) — no drift.

## Known Stubs

None. The docs-freshness family is fully wired and the gate is green; both e2e scripts are complete, syntax-valid, and smoke-tested. The only intentionally-deferred item is the **live execution** of the two scripts, which is the 20-05 operational gate (requires the rebuilt stack + realm re-import + populated `.env`), exactly as the plan scopes it.

## Threat Flags

None — no new security surface beyond the plan's `<threat_model>`. The scripts add no endpoint and hold no secret literal; they mint from env, forward opaque tokens through the existing MCP tool, and log status + identifiers only (T-20-01/02/03/08 all mitigated and, for the RLS proof, executably demonstrated via the leak smoke test).

## User Setup Required

For the 20-05 live run only: rebuild ALL containers, RE-IMPORT the Keycloak realm (`docs/security-scopes.md §4`), and ensure `.env` carries `INTEGRATION_CATALOG_RO_SECRET` and `KC_SEED_USER_PASSWORD` (the orchestrator noted `.env` currently lacks `INTEGRATION_CATALOG_RO_SECRET`) — the scripts fail fast with a clear message if either is missing.

## Next Phase Readiness

- **20-05 (live E2E gate):** run `mcp-server/scripts/e2e.sh` then `mcp-server/scripts/e2e-rls.sh` against the rebuilt, realm-re-imported stack. Both are ready and pre-validated against mocks; the RLS script is the load-bearing disjoint-set proof superuser Testcontainers cannot provide.
- **CI:** the `docs-freshness` gate now enforces MCP coverage going forward (any added/removed MCP vitest block requires a `--write` reconciliation), closing RESEARCH Pitfall 5.
- No Flyway/schema change (stays V50).

## Self-Check: PASSED

- Created files verified present: `mcp-server/scripts/e2e.sh`, `mcp-server/scripts/e2e-rls.sh`.
- Modified files verified: `scripts/docs-freshness.sh`, `docs/metrics.json` (mcp_test_blocks:23, total:1231), `CLAUDE.md` (1231).
- All 3 task commits verified in git log: `17dda9c`, `b202d95`, `9b88528`.
- Gate green (`bash scripts/docs-freshness.sh` -> total 1231); both scripts `bash -n` clean; `grep -q mcp_test_blocks docs/metrics.json` matches.

---
*Phase: 20-ai-1-mcp-server-read-only-slice*
*Completed: 2026-07-13*
